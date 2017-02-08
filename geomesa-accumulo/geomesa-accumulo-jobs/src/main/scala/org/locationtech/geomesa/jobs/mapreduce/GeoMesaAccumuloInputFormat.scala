/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.jobs.mapreduce

import java.net.{URL, URLClassLoader}

import com.typesafe.scalalogging.LazyLogging
import org.apache.accumulo.core.client.mapreduce.{AbstractInputFormat, AccumuloInputFormat, InputFormatBase}
import org.apache.accumulo.core.client.security.tokens.PasswordToken
import org.apache.accumulo.core.security.Authorizations
import org.apache.accumulo.core.util.{Pair => AccPair}
import org.apache.commons.collections.map.CaseInsensitiveMap
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.Text
import org.apache.hadoop.mapreduce._
import org.geotools.data.{DataStoreFinder, Query}
import org.geotools.filter.text.ecql.ECQL
import org.locationtech.geomesa.accumulo.data.{AccumuloDataStore, AccumuloDataStoreParams}
import org.locationtech.geomesa.accumulo.index.AccumuloFeatureIndex
import org.locationtech.geomesa.features.SerializationOption.SerializationOptions
import org.locationtech.geomesa.features.kryo.KryoFeatureSerializer
import org.locationtech.geomesa.index.conf.QueryHints.RichHints
import org.locationtech.geomesa.jobs.GeoMesaConfigurator
import org.locationtech.geomesa.jobs.accumulo.AccumuloJobUtils
import org.locationtech.geomesa.utils.index.IndexMode
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter

import scala.collection.JavaConversions._

object GeoMesaAccumuloInputFormat extends LazyLogging {

  val SYS_PROP_SPARK_LOAD_CP = "org.locationtech.geomesa.spark.load-classpath"

  def configure(job: Job,
                dsParams: Map[String, String],
                featureTypeName: String,
                filter: Option[String] = None,
                transform: Option[Array[String]] = None): Unit = {
    val ecql = filter.map(ECQL.toFilter).getOrElse(Filter.INCLUDE)
    val trans = transform.getOrElse(Query.ALL_NAMES)
    val query = new Query(featureTypeName, ecql, trans)
    configure(job, dsParams, query)
  }

  /**
   * Configure the input format.
   *
   * This is a single method, as we have to calculate several things to pass to the underlying
   * AccumuloInputFormat, and there is not a good hook to indicate when the config is finished.
   */
  def configure(job: Job, dsParams: Map[String, String], query: Query): Unit = {

    val ds = DataStoreFinder.getDataStore(dsParams).asInstanceOf[AccumuloDataStore]
    assert(ds != null, "Invalid data store parameters")

    // set up the underlying accumulo input format
    val user = AccumuloDataStoreParams.userParam.lookUp(dsParams).asInstanceOf[String]
    val password = AccumuloDataStoreParams.passwordParam.lookUp(dsParams).asInstanceOf[String]
    InputFormatBaseAdapter.setConnectorInfo(job, user, new PasswordToken(password.getBytes))

    val instance = AccumuloDataStoreParams.instanceIdParam.lookUp(dsParams).asInstanceOf[String]
    val zookeepers = AccumuloDataStoreParams.zookeepersParam.lookUp(dsParams).asInstanceOf[String]
    if (java.lang.Boolean.valueOf(AccumuloDataStoreParams.mockParam.lookUp(dsParams).asInstanceOf[String])) {
      AbstractInputFormat.setMockInstance(job, instance)
    } else {
      InputFormatBaseAdapter.setZooKeeperInstance(job, instance, zookeepers)
    }

    val auths = Option(AccumuloDataStoreParams.authsParam.lookUp(dsParams).asInstanceOf[String])
    auths.foreach(a => InputFormatBaseAdapter.setScanAuthorizations(job, new Authorizations(a.split(","): _*)))

    val featureTypeName = query.getTypeName

    // get the query plan to set up the iterators, ranges, etc
    val queryPlan = AccumuloJobUtils.getSingleQueryPlan(ds, query)

    // use the query plan to set the accumulo input format options
    InputFormatBase.setInputTableName(job, queryPlan.table)
    if (queryPlan.ranges.nonEmpty) {
      InputFormatBase.setRanges(job, queryPlan.ranges)
    }
    if (queryPlan.columnFamilies.nonEmpty) {
      InputFormatBase.fetchColumns(job, queryPlan.columnFamilies.map(cf => new AccPair[Text, Text](cf, null)))
    }
    queryPlan.iterators.foreach(InputFormatBase.addIterator(job, _))

    InputFormatBase.setBatchScan(job, true)

    // also set the datastore parameters so we can access them later
    val conf = job.getConfiguration

    GeoMesaConfigurator.setSerialization(conf)
    GeoMesaConfigurator.setTable(conf, queryPlan.table)
    GeoMesaConfigurator.setDataStoreInParams(conf, dsParams)
    GeoMesaConfigurator.setFeatureType(conf, featureTypeName)
    if (query.getFilter != Filter.INCLUDE) {
      GeoMesaConfigurator.setFilter(conf, ECQL.toCQL(query.getFilter))
    }
    query.getHints.getTransformSchema.foreach(GeoMesaConfigurator.setTransformSchema(conf, _))

    ds.dispose()
  }

  /**
   * This takes any jars that have been loaded by spark in the context classloader and makes them
   * available to the general classloader. This is required as not all classes (even spark ones) check
   * the context classloader.
   */
  def ensureSparkClasspath(): Unit = {
    val sysLoader = ClassLoader.getSystemClassLoader
    val ccl = Thread.currentThread().getContextClassLoader
    if (ccl == null || !ccl.getClass.getCanonicalName.startsWith("org.apache.spark.")) {
      logger.debug("No spark context classloader found")
    } else if (!ccl.isInstanceOf[URLClassLoader]) {
      logger.warn(s"Found context classloader, but can't handle type ${ccl.getClass.getCanonicalName}")
    } else if (!sysLoader.isInstanceOf[URLClassLoader]) {
      logger.warn(s"Found context classloader, but can't add to type ${sysLoader.getClass.getCanonicalName}")
    } else {
      // hack to get around protected visibility of addURL
      // this might fail if there is a security manager present
      val addUrl = classOf[URLClassLoader].getDeclaredMethod("addURL", classOf[URL])
      addUrl.setAccessible(true)
      val sysUrls = sysLoader.asInstanceOf[URLClassLoader].getURLs.map(_.toString).toSet
      val (dupeUrls, newUrls) = ccl.asInstanceOf[URLClassLoader].getURLs.filterNot(_.toString.contains("__app__.jar")).partition(url => sysUrls.contains(url.toString))
      newUrls.foreach(addUrl.invoke(sysLoader, _))
      logger.debug(s"Loaded ${newUrls.length} urls from context classloader into system classloader " +
          s"and ignored ${dupeUrls.length} that are already loaded")
    }
  }
}

/**
 * Input format that allows processing of simple features from GeoMesa based on a CQL query
 */
class GeoMesaAccumuloInputFormat extends InputFormat[Text, SimpleFeature] with LazyLogging {

  val delegate = new AccumuloInputFormat

  var sft: SimpleFeatureType = null
  var table: AccumuloFeatureIndex = null

  private def init(conf: Configuration) = if (sft == null) {
    val params = GeoMesaConfigurator.getDataStoreInParams(conf)
    val ds = DataStoreFinder.getDataStore(new CaseInsensitiveMap(params).asInstanceOf[java.util.Map[_, _]]).asInstanceOf[AccumuloDataStore]
    sft = ds.getSchema(GeoMesaConfigurator.getFeatureType(conf))
    val tableName = GeoMesaConfigurator.getTable(conf)
    table = AccumuloFeatureIndex.indices(sft, IndexMode.Read)
        .find(t => t.getTableName(sft.getTypeName, ds) == tableName)
        .getOrElse(throw new RuntimeException(s"Couldn't find input table $tableName"))
    ds.dispose()
  }

  /**
   * Gets splits for a job.
   *
   * Our delegated AccumuloInputFormat creates a split for each range - because we set a lot of ranges in
   * geomesa, that creates too many mappers. Instead, we try to group the ranges by tservers. We use the
   * number of shards in the schema as a proxy for number of tservers.
   */
  override def getSplits(context: JobContext): java.util.List[InputSplit] = {
    init(context.getConfiguration)
    val accumuloSplits = delegate.getSplits(context)
    logger.debug(s"Got ${accumuloSplits.length} splits")
    accumuloSplits
  }

  override def createRecordReader(split: InputSplit, context: TaskAttemptContext): GeoMesaRecordReader[AccumuloFeatureIndex] = {
    init(context.getConfiguration)
    val reader = new RecordReader[Array[Byte], Array[Byte]] {
      private val rr = delegate.createRecordReader(split, context)

      override def getProgress: Float = rr.getProgress

      override def nextKeyValue(): Boolean = rr.nextKeyValue()

      override def getCurrentValue: Array[Byte] = rr.getCurrentValue.get()

      override def initialize(inputSplit: InputSplit, taskAttemptContext: TaskAttemptContext): Unit =
        rr.initialize(inputSplit, taskAttemptContext)

      override def getCurrentKey: Array[Byte] = rr.getCurrentKey.getRow.getBytes

      override def close(): Unit = rr.close()
    }

    val schema = GeoMesaConfigurator.getTransformSchema(context.getConfiguration).getOrElse(sft)
    val hasId = table.serializedWithId
    val serializationOptions = if (hasId) SerializationOptions.none else SerializationOptions.withoutId
    val decoder = new KryoFeatureSerializer(schema, serializationOptions)
    new GeoMesaRecordReader(sft, table, reader, hasId, decoder)
  }
}
