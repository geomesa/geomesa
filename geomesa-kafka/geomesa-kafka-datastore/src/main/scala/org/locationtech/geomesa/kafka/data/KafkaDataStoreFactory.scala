/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.kafka.data

import java.awt.RenderingHints
import java.io.Serializable
import java.util.Properties

import com.github.benmanes.caffeine.cache.Ticker
import com.typesafe.scalalogging.LazyLogging
import org.geotools.data.DataAccessFactory.Param
import org.geotools.data.DataStoreFactorySpi
import org.locationtech.geomesa.index.geotools.GeoMesaDataStoreFactory
import org.locationtech.geomesa.index.geotools.GeoMesaDataStoreFactory.NamespaceParams
import org.locationtech.geomesa.kafka.data.KafkaDataStore.{EventTimeConfig, IndexConfig, KafkaDataStoreConfig}
import org.locationtech.geomesa.kafka.data.KafkaDataStoreFactory.KafkaDataStoreFactoryParams.{Brokers, ZkPath, Zookeepers}
import org.locationtech.geomesa.security
import org.locationtech.geomesa.security.AuthorizationsProvider
import org.locationtech.geomesa.utils.audit.{AuditLogger, AuditProvider, NoOpAuditProvider}
import org.locationtech.geomesa.utils.geotools.GeoMesaParam
import org.locationtech.geomesa.utils.geotools.GeoMesaParam.{ConvertedParam, DeprecatedParam}

import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

class KafkaDataStoreFactory extends DataStoreFactorySpi {

  import org.locationtech.geomesa.kafka.data.KafkaDataStoreFactory.KafkaDataStoreFactoryParams._

  // this is a pass-through required of the ancestor interface
  override def createNewDataStore(params: java.util.Map[String, Serializable]): KafkaDataStore =
    createDataStore(params)

  override def createDataStore(params: java.util.Map[String, Serializable]): KafkaDataStore =
    new KafkaDataStore(KafkaDataStoreFactory.buildConfig(params))

  override def getDisplayName: String = KafkaDataStoreFactory.DisplayName

  override def getDescription: String = KafkaDataStoreFactory.Description

  // note: we don't return producer configs, as they would not be used in geoserver
  override def getParametersInfo: Array[Param] =
    Array(
      Brokers,
      Zookeepers,
      ZkPath,
      ConsumerCount,
      ConsumerConfig,
      CacheExpiry,
      EventTime,
      IndexResolutionX,
      IndexResolutionY,
      EventTimeOrdering,
      CqEngineCache,
      LazyFeatures,
      ConsumeEarliest,
      AuditQueries,
      LooseBBox,
      Authorizations,
      NamespaceParam
    )

  override def canProcess(params: java.util.Map[String, Serializable]): Boolean =
    KafkaDataStoreFactory.canProcess(params)

  override def isAvailable: Boolean = true

  override def getImplementationHints: java.util.Map[RenderingHints.Key, _] = null
}

object KafkaDataStoreFactory extends LazyLogging {

  val DisplayName = "Kafka (GeoMesa)"
  val Description = "Apache Kafka\u2122 distributed log"

  val DefaultZkPath: String = "geomesa/ds/kafka"

  def canProcess(params: java.util.Map[String, Serializable]): Boolean =
    Brokers.exists(params) && Zookeepers.exists(params)

  def buildConfig(params: java.util.Map[String, Serializable]): KafkaDataStoreConfig = {
    import KafkaDataStoreFactoryParams._

    val catalog = createZkNamespace(params)
    val brokers = checkBrokerPorts(Brokers.lookup(params))
    val zookeepers = Zookeepers.lookup(params)

    val partitions = TopicPartitions.lookup(params).intValue()
    val replication = TopicReplication.lookup(params).intValue()

    val consumers = ConsumerCount.lookup(params).intValue

    val producerConfig = ProducerConfig.lookupOpt(params).getOrElse(new Properties)
    val consumerConfig = ConsumerConfig.lookupOpt(params).getOrElse(new Properties)

    val consumeFromBeginning = ConsumeEarliest.lookup(params).booleanValue

    val cacheExpiry = CacheExpiry.lookupOpt(params).getOrElse(Duration.Inf)
    val cqEngine = CqEngineCache.lookup(params).booleanValue()
    val xBuckets = IndexResolutionX.lookup(params).intValue()
    val yBuckets = IndexResolutionY.lookup(params).intValue()
    val lazyDeserialization = LazyFeatures.lookup(params).booleanValue()

    val indexConfig = IndexConfig(cacheExpiry, cqEngine, xBuckets, yBuckets, lazyDeserialization)

    val eventTime = EventTime.lookupOpt(params).map { e =>
      EventTimeConfig(e, EventTimeOrdering.lookup(params).booleanValue())
    }

    val looseBBox = LooseBBox.lookup(params).booleanValue()

    val audit = if (!AuditQueries.lookup(params)) { None } else {
      Some((AuditLogger, buildAuditProvider(params), "kafka"))
    }
    val authProvider = buildAuthProvider(params)

    val ns = Option(NamespaceParam.lookUp(params).asInstanceOf[String])

    // noinspection ScalaDeprecation
    Seq(CacheCleanup, CacheConsistency, CacheTicker).foreach { p =>
      if (params.containsKey(p.key)) {
        logger.warn(s"Parameter '${p.key}' is deprecated, and no longer has any effect")
      }
    }

    KafkaDataStoreConfig(catalog, brokers, zookeepers, consumers, partitions, replication, producerConfig,
      consumerConfig, consumeFromBeginning, indexConfig, eventTime, looseBBox, authProvider, audit, ns)
  }

  private def buildAuthProvider(params: java.util.Map[String, Serializable]): AuthorizationsProvider = {
    import KafkaDataStoreFactoryParams.Authorizations
    // get the auth params passed in as a comma-delimited string
    val auths = Authorizations.lookupOpt(params).map(_.split(",").filterNot(_.isEmpty)).getOrElse(Array.empty)
    security.getAuthorizationsProvider(params, auths)
  }

  private def buildAuditProvider(params: java.util.Map[String, Serializable]): AuditProvider =
    Option(AuditProvider.Loader.load(params)).getOrElse(NoOpAuditProvider)

  /**
    * Gets up a zk path parameter - trims, removes leading/trailing "/" if needed
    *
    * @param params data store params
    * @return
    */
  private [data] def createZkNamespace(params: java.util.Map[String, Serializable]): String = {
    ZkPath.lookupOpt(params)
        .map(_.trim)
        .filterNot(_.isEmpty)
        .map(p => if (p.startsWith("/")) { p.substring(1).trim } else { p })  // leading '/'
        .map(p => if (p.endsWith("/")) { p.substring(0, p.length - 1).trim } else { p })  // trailing '/'
        .filterNot(_.isEmpty)
        .getOrElse(DefaultZkPath)
  }

  private def checkBrokerPorts(brokers: String): String = {
    if (brokers.indexOf(':') != -1) { brokers } else {
      try { brokers.split(",").map(b => s"${b.trim}:9092").mkString(",") } catch {
        case NonFatal(_) => brokers
      }
    }
  }

  // noinspection TypeAnnotation
  object KafkaDataStoreFactoryParams extends NamespaceParams {
    // deprecated lookups
    private val DeprecatedProducer = ConvertedParam[java.lang.Integer, java.lang.Boolean]("isProducer", (v) => if (v) { 0 } else { 1 })
    private val DeprecatedOffset = ConvertedParam[java.lang.Boolean, String]("autoOffsetReset", (v) => "earliest".equalsIgnoreCase(v))
    private val DeprecatedExpiry = ConvertedParam[Duration, java.lang.Long]("expirationPeriod", (v) => Duration(v, "ms"))
    private val DeprecatedConsistency = ConvertedParam[Duration, java.lang.Long]("consistencyCheck", (v) => Duration(v, "ms"))
    private val DeprecatedCleanup = new DeprecatedParam[Duration] {
      override val key = "cleanUpCache"
      override def lookup(params: java.util.Map[String, _ <: Serializable], required: Boolean): Duration = {
        val param = new GeoMesaParam[java.lang.Boolean](key, default = false)
        if (!param.lookup(params)) { Duration.Inf } else {
          Duration(new GeoMesaParam[String]("cleanUpCachePeriod", default = "10s").lookup(params))
        }
      }
    }

    val Brokers           = new GeoMesaParam[String]("kafka.brokers", "Kafka brokers", optional = false, deprecatedKeys = Seq("brokers"))
    val Zookeepers        = new GeoMesaParam[String]("kafka.zookeepers", "Kafka zookeepers", optional = false, deprecatedKeys = Seq("zookeepers"))
    val ZkPath            = new GeoMesaParam[String]("kafka.zk.path", "Zookeeper discoverable path (namespace)", default = DefaultZkPath, deprecatedKeys = Seq("zkPath"))
    val ProducerConfig    = new GeoMesaParam[Properties]("kafka.producer.config", "Configuration options for kafka producer, in Java properties format. See http://kafka.apache.org/documentation.html#producerconfigs", largeText = true, deprecatedKeys = Seq("producerConfig"))
    val ConsumerConfig    = new GeoMesaParam[Properties]("kafka.consumer.config", "Configuration options for kafka consumer, in Java properties format. See http://kafka.apache.org/documentation.html#newconsumerconfigs", largeText = true, deprecatedKeys = Seq("consumerConfig"))
    val ConsumeEarliest   = new GeoMesaParam[java.lang.Boolean]("kafka.consumer.from-beginning", "Start reading from the beginning of the topic (vs ignore old messages)", default = Boolean.box(false), deprecatedParams = Seq(DeprecatedOffset))
    val TopicPartitions   = new GeoMesaParam[Integer]("kafka.topic.partitions", "Number of partitions to use in kafka topics", default = 1, deprecatedKeys = Seq("partitions"))
    val TopicReplication  = new GeoMesaParam[Integer]("kafka.topic.replication", "Replication factor to use in kafka topics", default = 1, deprecatedKeys = Seq("replication"))
    val ConsumerCount     = new GeoMesaParam[Integer]("kafka.consumer.count", "Number of kafka consumers used per feature type. Set to 0 to disable consuming (i.e. producer only)", default = 1, deprecatedParams = Seq(DeprecatedProducer))
    val CacheExpiry       = new GeoMesaParam[Duration]("kafka.cache.expiry", "Features will be expired after this delay", deprecatedParams = Seq(DeprecatedExpiry))
    // TODO these should really be per-feature, not per datastore...
    val EventTime         = new GeoMesaParam[String]("kafka.cache.event-time", "Instead of message time, determine expiry based on feature data. This can be an attribute name or a CQL expression, but it must evaluate to a date")
    val IndexResolutionX  = new GeoMesaParam[Integer]("kafka.index.resolution.x", "Number of bins in the x-dimension of the spatial index", default = Int.box(360))
    val IndexResolutionY  = new GeoMesaParam[Integer]("kafka.index.resolution.y", "Number of bins in the y-dimension of the spatial index", default = Int.box(180))
    val EventTimeOrdering = new GeoMesaParam[java.lang.Boolean]("kafka.cache.event-time.ordering", "Instead of message time, determine feature ordering based on event time data", default = Boolean.box(false))
    val CqEngineCache     = new GeoMesaParam[java.lang.Boolean]("kafka.cache.cqengine", "Use CQEngine-based implementation of live feature cache", default = Boolean.box(false), deprecatedKeys = Seq("useCQCache"))
    val LazyFeatures      = new GeoMesaParam[java.lang.Boolean]("kafka.serialization.lazy", "Use lazy deserialization of features. This may improve processing load at the expense of slightly slower query times", default = Boolean.box(true))
    val LooseBBox         = GeoMesaDataStoreFactory.LooseBBoxParam
    val AuditQueries      = GeoMesaDataStoreFactory.AuditQueriesParam
    val Authorizations    = org.locationtech.geomesa.security.AuthsParam

    @deprecated val CacheCleanup     = new GeoMesaParam[Duration]("kafka.cache.cleanup", "Run a thread to clean expired features from the cache (vs cleanup during reads and writes)", default = Duration("30s"), deprecatedParams = Seq(DeprecatedCleanup))
    @deprecated val CacheConsistency = new GeoMesaParam[Duration]("kafka.cache.consistency", "Check the feature cache for consistency at this interval", deprecatedParams = Seq(DeprecatedConsistency))
    @deprecated val CacheTicker      = new GeoMesaParam[Ticker]("kafka.cache.ticker", "Ticker to use for expiring/cleaning feature cache")
  }
}
