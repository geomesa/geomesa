/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.jobs.mapreduce

import java.io.IOException

import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.IOUtils
import org.apache.hadoop.io.Text
import org.apache.hadoop.mapreduce._
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat
import org.geotools.data.{DataStoreFinder, DataUtilities}
import org.geotools.filter.identity.FeatureIdImpl
import org.locationtech.geomesa.index.api.{GeoMesaFeatureIndex, WrappedFeature}
import org.locationtech.geomesa.index.geotools.{GeoMesaDataStore, GeoMesaFeatureWriter}
import org.locationtech.geomesa.index.index.ClientSideFiltering
import org.locationtech.geomesa.jobs.GeoMesaConfigurator
import org.locationtech.geomesa.utils.index.IndexMode
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

import scala.collection.JavaConversions._

object GeoMesaOutputFormat {

  object Counters {
    val Group   = "org.locationtech.geomesa.jobs.output"
    val Written = "written"
    val Failed  = "failed"
  }

  /**
   * Configure the data store you will be writing to.
   */
  def configureDataStore(job: Job, dsParams: Map[String, String]): Unit = {
    val ds = DataStoreFinder.getDataStore(dsParams).asInstanceOf[GeoMesaDataStore[_, _, _]]
    assert(ds != null, "Invalid data store parameters")
    ds.dispose()

    // set the datastore parameters so we can access them later
    val conf = job.getConfiguration
    GeoMesaConfigurator.setDataStoreOutParams(conf, dsParams)
    GeoMesaConfigurator.setSerialization(conf)
  }
}

/**
  * Output format that writes simple features using GeoMesaDataStore's FeatureWriterAppend. Can write only
  * specific indices if desired
  */
class GeoMesaOutputFormat extends OutputFormat[Text, SimpleFeature] {

  override def getRecordWriter(context: TaskAttemptContext): RecordWriter[Text, SimpleFeature] = {
    val params  = GeoMesaConfigurator.getDataStoreOutParams(context.getConfiguration)
    val indices = GeoMesaConfigurator.getIndicesOut(context.getConfiguration)
    new GeoMesaRecordWriter(params, indices, context)
  }

  override def checkOutputSpecs(context: JobContext): Unit = {
    val params = GeoMesaConfigurator.getDataStoreOutParams(context.getConfiguration)
    if (!DataStoreFinder.getAvailableDataStores.exists(_.canProcess(params))) {
      throw new IOException("Data store connection parameters are not set")
    }
  }

  override def getOutputCommitter(context: TaskAttemptContext): OutputCommitter =
    new NullOutputFormat[Text, SimpleFeature]().getOutputCommitter(context)
}

/**
  * Record reader that delegates to accumulo record readers and transforms the key/values coming back into
  * simple features.
  *
  * @param reader
  */
class GeoMesaRecordReader[FI <: GeoMesaFeatureIndex[_, _, _]]
(
  sft: SimpleFeatureType,
  table: FI,
  reader: RecordReader[Array[Byte], Array[Byte]],
  hasId: Boolean,
  decoder: org.locationtech.geomesa.features.SimpleFeatureSerializer
) extends RecordReader[Text, SimpleFeature] {

  private var currentFeature: SimpleFeature = null

  private val getId = table.getIdFromRow(sft)

  override def initialize(split: InputSplit, context: TaskAttemptContext): Unit = {
    reader.initialize(split, context)
  }
  override def getProgress: Float = reader.getProgress

  override def nextKeyValue(): Boolean = nextKeyValueInternal()

  /**
    * Get the next key value from the underlying reader, incrementing the reader when required
    */
  private def nextKeyValueInternal(): Boolean = {
    if (reader.nextKeyValue()) {
      currentFeature = decoder.deserialize(reader.getCurrentValue)
      if (!hasId) {
        val row = reader.getCurrentKey
        currentFeature.getIdentifier.asInstanceOf[FeatureIdImpl].setID(getId(row, 0, row.length))
      }
      true
    } else {
      false
    }
  }

  override def getCurrentValue: SimpleFeature = currentFeature

  override def getCurrentKey = new Text(currentFeature.getID)

  override def close(): Unit = { reader.close() }
}


/**
 * Record writer for GeoMesa SimpleFeatures.
 *
 * Key is ignored. If the feature type for the given feature does not exist yet, it will be created.
 */
class GeoMesaRecordWriter[DS <: GeoMesaDataStore[DS, F, W], F <: WrappedFeature, W]
    (params: Map[String, String], indices: Option[Seq[String]], context: TaskAttemptContext)
    extends RecordWriter[Text, SimpleFeature] with LazyLogging {

  val ds: GeoMesaDataStore[DS, F, W] = DataStoreFinder.getDataStore(params).asInstanceOf[GeoMesaDataStore[DS, F, W]]

  val sftCache    = scala.collection.mutable.Map.empty[String, SimpleFeatureType]
  val writerCache = scala.collection.mutable.Map.empty[String, GeoMesaFeatureWriter[_, _, _, _]]

  val written: Counter = context.getCounter(GeoMesaOutputFormat.Counters.Group, GeoMesaOutputFormat.Counters.Written)
  val failed: Counter = context.getCounter(GeoMesaOutputFormat.Counters.Group, GeoMesaOutputFormat.Counters.Failed)

  override def write(key: Text, value: SimpleFeature): Unit = {
    val sftName = value.getFeatureType.getTypeName
    // TODO we shouldn't serialize the sft with each feature
    // ensure that the type has been created if we haven't seen it before
    val sft = sftCache.getOrElseUpdate(sftName, {
      // schema operations are thread-safe
      val existing = ds.getSchema(sftName)
      if (existing == null) {
        ds.createSchema(value.getFeatureType)
        ds.getSchema(sftName)
      } else {
        existing
      }
    })

    val writer = writerCache.getOrElseUpdate(sftName, {
      val i = indices match {
        case Some(names) => names.map(ds.manager.index)
        case None => ds.manager.indices(sft, IndexMode.Write)
      }
      ds.getIndexWriterAppend(sftName, i)
    })

    try {
      val next = writer.next()
      next.getIdentifier.asInstanceOf[FeatureIdImpl].setID(value.getID)
      next.setAttributes(value.getAttributes)
      next.getUserData.putAll(value.getUserData)
      writer.write()
      written.increment(1)
    } catch {
      case e: Exception =>
        logger.error(s"Error writing feature '${DataUtilities.encodeFeature(value)}'", e)
        failed.increment(1)
    }
  }

  override def close(context: TaskAttemptContext): Unit = {
    writerCache.values.foreach(IOUtils.closeQuietly)
    ds.dispose()
  }
}