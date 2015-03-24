/*
 * Copyright 2015 Commonwealth Computer Research, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.locationtech.geomesa.jobs.index

import com.twitter.scalding._
import org.apache.accumulo.core.data.{Range => AcRange}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.Text
import org.geotools.data.DataStoreFinder
import org.locationtech.geomesa.core.data.AccumuloDataStoreFactory.params._
import org.locationtech.geomesa.core.data.AccumuloFeatureWriter.FeatureToWrite
import org.locationtech.geomesa.core.data._
import org.locationtech.geomesa.core.data.tables.AttributeTable
import org.locationtech.geomesa.core.index._
import org.locationtech.geomesa.feature.SimpleFeatureEncoder
import org.locationtech.geomesa.jobs.scalding.ConnectionParams._
import org.locationtech.geomesa.jobs.scalding._
import org.locationtech.geomesa.utils.geotools.RichAttributeDescriptors.RichAttributeDescriptor
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.stats.IndexCoverage
import org.locationtech.geomesa.utils.stats.IndexCoverage.IndexCoverage
import org.opengis.feature.`type`.AttributeDescriptor
import org.opengis.feature.simple.SimpleFeature

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.util.Try

class AttributeIndexJob(args: Args) extends GeoMesaBaseJob(args) {

  val feature = args.optional(FEATURE_IN).getOrElse(args(FEATURE_NAME_OLD))
  val dsParams = toDataStoreInParams(args)

  // add a comma-split to allow comma-separated values
  val attributes = args.list(AttributeIndexJob.ATTRIBUTES_TO_INDEX).flatMap(_.split(","))
  val coverage = args.optional(AttributeIndexJob.INDEX_COVERAGE)
      .flatMap(c => Try(IndexCoverage.withName(c)).toOption)
      .getOrElse(IndexCoverage.JOIN)

  val input = GeoMesaInputOptions(dsParams, feature)
  val (output, visibilities) = {
    val ds = DataStoreFinder.getDataStore(dsParams.asJava).asInstanceOf[AccumuloDataStore]
    val sft = ds.getSchema(feature)
    assert(sft != null, s"The feature '$feature' does not exist in the input data store")
    val descriptors = sft.getAttributeDescriptors.map(_.getLocalName)
    attributes.foreach {
      a => assert(descriptors.contains(a), s"Attribute '$a' does not exist in feature $feature")
    }
    val attributeTable = ds.getAttributeTable(feature)
    val instance = dsParams(instanceIdParam.getName)
    val zoos = dsParams(zookeepersParam.getName)
    val user = dsParams(userParam.getName)
    val pwd = dsParams(passwordParam.getName)
    val visibilities = ds.writeVisibilities
    (AccumuloOutputOptions(instance, zoos, user, pwd, attributeTable, createTable = true), visibilities)
  }

  // scalding job
  GeoMesaSource(input)
    .using(new AttributeIndexResources)
    .flatMap(('id, 'sf) -> 'mutation) {
      (r: AttributeIndexResources, kv: (Text, SimpleFeature)) => getMutations(kv._2, r)
    }.write(AccumuloSource(output))

  def getMutations(sf: SimpleFeature, r: AttributeIndexResources) = {
    val toWrite = new FeatureToWrite(sf, visibilities, r.fe, r.ive)
    AttributeTable.getAttributeIndexMutations(toWrite, r.attrs, r.prefix)
  }

  override def afterJobTasks() = {
    val ds = DataStoreFinder.getDataStore(dsParams.asJava).asInstanceOf[AccumuloDataStore]
    // schedule a table compaction to clean up the table
    ds.connector.tableOperations().compact(output.table, null, null, true, false)
    // update the metadata
    val sft = ds.getSchema(feature)
    def wasIndexed(ad: AttributeDescriptor) = attributes.contains(ad.getLocalName)
    sft.getAttributeDescriptors.filter(wasIndexed).foreach(_.setIndexCoverage(coverage))
    val updatedSpec = SimpleFeatureTypes.encodeType(sft)
    ds.updateIndexedAttributes(feature, updatedSpec)
  }

  class AttributeIndexResources {
    val ds = DataStoreFinder.getDataStore(dsParams.asJava).asInstanceOf[AccumuloDataStore]
    val sft = ds.getSchema(feature)
    val prefix = org.locationtech.geomesa.core.index.getTableSharingPrefix(sft)
    val encoding = ds.getFeatureEncoding(sft)
    val fe = SimpleFeatureEncoder(sft, encoding)
    val ive = IndexValueEncoder(sft, ds.getGeomesaVersion(sft))

    // the attributes we want to index
    val attrs = sft.getAttributeDescriptors.zipWithIndex
        .filter { case (ad, idx) => attributes.contains(ad.getLocalName) }
    attrs.foreach { case (ad, idx) => ad.setIndexCoverage(coverage) }

    // required by scalding
    def release(): Unit = {}
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
