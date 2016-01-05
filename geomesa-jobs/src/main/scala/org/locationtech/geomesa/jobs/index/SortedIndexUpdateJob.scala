/***********************************************************************
* Copyright (c) 2013-2015 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.jobs.index

import com.twitter.scalding._
import org.apache.accumulo.core.data.{Mutation, Range => AcRange}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.Text
import org.geotools.data.DataStoreFinder
import org.locationtech.geomesa.accumulo.data.AccumuloDataStoreFactory.params._
import org.locationtech.geomesa.accumulo.data.AccumuloFeatureWriter.FeatureToWrite
import org.locationtech.geomesa.accumulo.data._
import org.locationtech.geomesa.accumulo.data.tables.SpatioTemporalTable
import org.locationtech.geomesa.accumulo.index._
import org.locationtech.geomesa.features.{SimpleFeatureDeserializers, SimpleFeatureSerializers}
import org.locationtech.geomesa.jobs.GeoMesaBaseJob
import org.locationtech.geomesa.jobs.scalding.ConnectionParams._
import org.locationtech.geomesa.jobs.scalding._

import scala.collection.JavaConverters._

class SortedIndexUpdateJob(args: Args) extends GeoMesaBaseJob(args) {

  val UPDATE_TO_VERSION = 2 // we want to maintain compatibility with any attribute indices written

  val feature = args(FEATURE_IN)
  val dsParams = toDataStoreInParams(args)

  // non-serializable resources - need to be lazy and transient so they are available to each mapper
  @transient lazy val ds = DataStoreFinder.getDataStore(dsParams.asJava).asInstanceOf[AccumuloDataStore]
  @transient lazy val sft = ds.getSchema(feature)
  @transient lazy val indexSchemaFmt = ds.buildDefaultSpatioTemporalSchema(feature)
  @transient lazy val encoding = ds.getFeatureEncoding(sft)
  @transient lazy val featureEncoder = SimpleFeatureSerializers(sft, encoding)
  // this won't use the new schema version, but anything less than version 4 is handled the same way
  @transient lazy val indexValueEncoder = IndexValueEncoder(sft)
  @transient lazy val binEncoder = BinEncoder(sft)
  @transient lazy val encoder = IndexSchema.buildKeyEncoder(sft, indexSchemaFmt)
  @transient lazy val decoder = SimpleFeatureDeserializers(sft, encoding)

  val (input, output) = {
    val indexSchemaFmt = ds.getIndexSchemaFmt(sft.getTypeName)
    val encoder = IndexSchema.buildKeyEncoder(sft, indexSchemaFmt)
    val maxShard = IndexSchema.maxShard(indexSchemaFmt)
    val prefixes = (0 to maxShard).map { i =>
      encoder.rowf match { case CompositeTextFormatter(formatters, sep) =>
        formatters.take(2).map {
          case f: PartitionTextFormatter => f.fmt(i)
          case f: ConstantTextFormatter => f.constStr
        }.mkString("", sep, sep)
      }
    }
    val ranges = SerializedRange(prefixes.map(p => new AcRange(p, p + "~")))
    val stTable = ds.getTableName(feature, SpatioTemporalTable)
    val instance = dsParams(instanceIdParam.getName)
    val zoos = dsParams(zookeepersParam.getName)
    val user = dsParams(userParam.getName)
    val pwd = dsParams(passwordParam.getName)
    val input = AccumuloInputOptions(instance, zoos, user, pwd, stTable, ranges)
    val output = AccumuloOutputOptions(instance, zoos, user, pwd, stTable)
    (input, output)
  }

  // validation
  assert(sft != null, s"The feature '$feature' does not exist in the input data store")

  // scalding job
  TypedPipe.from(AccumuloSource(input))
    .flatMap { case (key, value) =>
      if (SpatioTemporalTable.isIndexEntry(key) || SpatioTemporalTable.isDataEntry(key)) {
        // already up-to-date
        Seq.empty
      } else {
        val visibility = key.getColumnVisibilityParsed
        val delete = new Mutation(key.getRow)
        delete.putDelete(key.getColumnFamily, key.getColumnQualifier, visibility)
        val mutations = if (key.getColumnQualifier.toString == "SimpleFeatureAttribute") {
          // data entry, re-calculate the keys for index and data entries
          val sf = decoder.deserialize(value.get())
          val toWrite = new FeatureToWrite(sf, key.getColumnVisibility.toString, featureEncoder, indexValueEncoder, binEncoder)
          encoder.encode(toWrite)
        } else {
          // index entry, ignore it (will be handled by associated data entry)
          Seq.empty
        }
        (Seq(delete) ++ mutations).map((null: Text, _))
      }
    }.write(AccumuloSource(output))

  override def afterJobTasks() = {
    // schedule a table compaction to remove the deleted entries
    val ds = DataStoreFinder.getDataStore(dsParams.asJava).asInstanceOf[AccumuloDataStore]
    ds.connector.tableOperations().compact(output.table, null, null, true, false)
    ds.setIndexSchemaFmt(feature, ds.buildDefaultSpatioTemporalSchema(feature))
    ds.setGeomesaVersion(feature, UPDATE_TO_VERSION)
  }
}

object SortedIndexUpdateJob {
  def runJob(conf: Configuration, params: Map[String, String], feature: String) = {
    val args = toInArgs(params) ++ Seq(FEATURE_IN -> List(feature)).toMap
    val instantiateJob = (args: Args) => new SortedIndexUpdateJob(args)
    GeoMesaBaseJob.runJob(conf, args, instantiateJob)
  }
}
