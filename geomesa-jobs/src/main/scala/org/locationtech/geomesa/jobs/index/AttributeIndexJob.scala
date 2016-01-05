/***********************************************************************
* Copyright (c) 2013-2015 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.jobs.index
import com.twitter.scalding._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.Text
import org.geotools.data.DataStoreFinder
import org.locationtech.geomesa.accumulo.data.AccumuloDataStoreFactory.params._
import org.locationtech.geomesa.accumulo.data.AccumuloFeatureWriter.FeatureToWrite
import org.locationtech.geomesa.accumulo.data._
import org.locationtech.geomesa.accumulo.data.tables.{AttributeTable, AttributeTableV5}
import org.locationtech.geomesa.accumulo.index._
import org.locationtech.geomesa.features.SimpleFeatureSerializers
import org.locationtech.geomesa.jobs.GeoMesaBaseJob
import org.locationtech.geomesa.jobs.scalding.ConnectionParams._
import org.locationtech.geomesa.jobs.scalding._
import org.locationtech.geomesa.utils.geotools.RichAttributeDescriptors.RichAttributeDescriptor
import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.stats.IndexCoverage
import org.locationtech.geomesa.utils.stats.IndexCoverage.IndexCoverage
import org.opengis.feature.`type`.AttributeDescriptor

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.util.Try

class AttributeIndexJob(args: Args) extends GeoMesaBaseJob(args) {

  val feature = args(FEATURE_IN)
  val dsParams = toDataStoreInParams(args)

  // add a comma-split to allow comma-separated values
  val attributes = args.list(AttributeIndexJob.ATTRIBUTES_TO_INDEX).flatMap(_.split(","))
  val coverage = args.optional(AttributeIndexJob.INDEX_COVERAGE)
      .flatMap(c => Try(IndexCoverage.withName(c)).toOption)
      .getOrElse(IndexCoverage.JOIN)

  val input = GeoMesaInputOptions(dsParams, feature)

  // non-serializable resources - need to be lazy and transient so they are available to each mapper
  @transient lazy val ds = DataStoreFinder.getDataStore(dsParams.asJava).asInstanceOf[AccumuloDataStore]
  @transient lazy val sft = {
    val sft = ds.getSchema(feature)
    if (sft != null) {
      // set the coverage for each descriptor so that we write out the ones we want to index and not others
      sft.getAttributeDescriptors.foreach { d =>
        d.setIndexCoverage(if (attributes.contains(d.getLocalName)) coverage else IndexCoverage.NONE)
      }
    }
    sft
  }
  @transient lazy val writer =
    if (sft.getSchemaVersion < 6) AttributeTableV5.writer(sft) else AttributeTable.writer(sft)
  @transient lazy val encoding = ds.getFeatureEncoding(sft)
  @transient lazy val featureEncoder = SimpleFeatureSerializers(sft, encoding)
  @transient lazy val indexValueEncoder = IndexValueEncoder(sft)
  @transient lazy val binEncoder = BinEncoder(sft)
  @transient lazy val visibilities = ds.writeVisibilities

  // validation
  {
    require(sft != null, s"The feature '$feature' does not exist in the input data store")
    val valid = sft.getAttributeDescriptors.map(_.getLocalName)
    attributes.foreach(a => assert(valid.contains(a), s"Attribute '$a' does not exist in feature $feature"))
  }

  val output = {
    val attributeTable = ds.getTableName(feature, AttributeTable)
    val instance = dsParams(instanceIdParam.getName)
    val zoos = dsParams(zookeepersParam.getName)
    val user = dsParams(userParam.getName)
    val pwd = dsParams(passwordParam.getName)
    AccumuloOutputOptions(instance, zoos, user, pwd, attributeTable, createTable = true)
  }

  // scalding job
  TypedPipe.from(GeoMesaSource(input)).flatMap { case (id, sf) =>
    writer(new FeatureToWrite(sf, visibilities, featureEncoder, indexValueEncoder, binEncoder)).map((null: Text, _))
  }.write(AccumuloSource(output))

  override def afterJobTasks() = {
    // schedule a table compaction to clean up the table
    ds.connector.tableOperations().compact(output.table, null, null, true, false)
    // update the metadata
    // reload the sft, as we nulled out the index flags earlier
    val sft = ds.getSchema(feature)
    def wasIndexed(ad: AttributeDescriptor) = attributes.contains(ad.getLocalName)
    sft.getAttributeDescriptors.filter(wasIndexed).foreach(_.setIndexCoverage(coverage))
    val updatedSpec = SimpleFeatureTypes.encodeType(sft)
    ds.updateIndexedAttributes(feature, updatedSpec)
  }
}

object AttributeIndexJob {

  val ATTRIBUTES_TO_INDEX = "geomesa.index.attributes"
  val INDEX_COVERAGE      = "geomesa.index.coverage"

  def runJob(conf: Configuration,
             dsParams: Map[String, String],
             feature: String,
             attributes: List[String],
             indexCoverage: IndexCoverage = IndexCoverage.JOIN) = {
    val args = Seq(FEATURE_IN -> List(feature),
                   ATTRIBUTES_TO_INDEX -> attributes,
                   INDEX_COVERAGE -> List(indexCoverage.toString)).toMap ++ toInArgs(dsParams)
    val instantiateJob = (args: Args) => new AttributeIndexJob(args)
    GeoMesaBaseJob.runJob(conf, args, instantiateJob)
  }
}
