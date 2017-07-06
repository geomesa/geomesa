/***********************************************************************
 * Copyright (c) 2013-2017 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.lambda.stream.kafka

import java.time.Clock
import java.util.concurrent._
import java.util.{Collections, Properties, UUID}

import com.google.common.primitives.Longs
import com.google.common.util.concurrent.MoreExecutors
import com.typesafe.scalalogging.LazyLogging
import kafka.admin.AdminUtils
import kafka.common.TopicAlreadyMarkedForDeletionException
import kafka.utils.ZkUtils
import org.apache.kafka.clients.consumer.{Consumer, ConsumerRebalanceListener, KafkaConsumer}
import org.apache.kafka.clients.producer._
import org.apache.kafka.common.serialization._
import org.apache.kafka.common.{Cluster, TopicPartition}
import org.geotools.data.{DataStore, Query, Transaction}
import org.geotools.factory.Hints
import org.locationtech.geomesa.features.SerializationOption.SerializationOptions
import org.locationtech.geomesa.features.kryo.KryoFeatureSerializer
import org.locationtech.geomesa.index.geotools.GeoMesaFeatureWriter
import org.locationtech.geomesa.index.utils.{ExplainLogging, Explainer}
import org.locationtech.geomesa.lambda.data.LambdaDataStore.LambdaConfig
import org.locationtech.geomesa.lambda.stream.kafka.KafkaStore.MessageTypes
import org.locationtech.geomesa.lambda.stream.{OffsetManager, TransientStore}
import org.locationtech.geomesa.security.{AuthorizationsProvider, SecurityUtils}
import org.locationtech.geomesa.utils.conf.GeoMesaSystemProperties.SystemProperty
import org.locationtech.geomesa.utils.io.{CloseWithLogging, WithClose}
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter

import scala.concurrent.duration.Duration
import scala.util.hashing.MurmurHash3

class KafkaStore(ds: DataStore,
                 val sft: SimpleFeatureType,
                 authProvider: Option[AuthorizationsProvider],
                 offsetManager: OffsetManager,
                 producer: Producer[Array[Byte], Array[Byte]],
                 consumerConfig: Map[String, String],
                 config: LambdaConfig)
                (implicit clock: Clock = Clock.systemUTC())
    extends TransientStore with LazyLogging {

  private val topic = KafkaStore.topic(config.zkNamespace, sft)

  private val state = new SharedState(config.partitions)

  private val serializer = new KryoFeatureSerializer(sft, SerializationOptions.withUserData)

  private val queryRunner = new KafkaQueryRunner(state, stats, authProvider)

  private val loader = new KafkaCacheLoader(offsetManager, serializer, state, consumerConfig, topic, config.consumers)
  private val persistence = config.expiry match {
    case Duration.Inf => None
    case d => Some(new DataStorePersistence(ds, sft, offsetManager, state, topic, d.toMillis, config.persist))
  }

  private val setVisibility: (SimpleFeature) => (SimpleFeature) = config.visibility match {
    case None => (f) => f
    case Some(vis) => (f) => {
      if (SecurityUtils.getVisibility(f) == null) {
        SecurityUtils.setFeatureVisibility(f, vis)
      }
      f
    }
  }

  override def createSchema(): Unit = {
    KafkaStore.withZk(config.zookeepers) { zk =>
      if (AdminUtils.topicExists(zk, topic)) {
       logger.warn(s"Topic [$topic] already exists - it may contain stale data")
      } else {
        val replication = SystemProperty("geomesa.kafka.replication").option.map(_.toInt).getOrElse(1)
        AdminUtils.createTopic(zk, topic, config.partitions, replication)
      }
    }
  }

  override def removeSchema(): Unit = {
    offsetManager.deleteOffsets(topic)
    KafkaStore.withZk(config.zookeepers) { zk =>
      try {
        if (AdminUtils.topicExists(zk, topic)) {
          AdminUtils.deleteTopic(zk, topic)
        }
      } catch {
        case _: TopicAlreadyMarkedForDeletionException => logger.warn("Topic is already marked for deletion")
      }
    }
  }

  override def read(filter: Option[Filter] = None,
                    transforms: Option[Array[String]] = None,
                    hints: Option[Hints] = None,
                    explain: Explainer = new ExplainLogging): Iterator[SimpleFeature] = {
    val query = new Query()
    filter.foreach(query.setFilter)
    transforms.foreach(query.setPropertyNames)
    hints.foreach(query.setHints) // note: we want to share the hints object
    queryRunner.runQuery(sft, query, explain)
  }

  override def write(original: SimpleFeature): Unit = {
    val feature = prepFeature(original)
    val key = KafkaStore.serializeKey(clock.millis(), MessageTypes.Write)
    producer.send(new ProducerRecord(topic, key, serializer.serialize(feature)))
    logger.trace(s"Wrote feature to [$topic]: $feature")
  }

  override def delete(original: SimpleFeature): Unit = {
    import org.locationtech.geomesa.filter.ff
    // send a message to delete from all transient stores
    val feature = prepFeature(original)
    val key = KafkaStore.serializeKey(clock.millis(), MessageTypes.Delete)
    producer.send(new ProducerRecord(topic, key, serializer.serialize(feature)))
    // also delete from persistent store
    if (config.persist) {
      val filter = ff.id(ff.featureId(feature.getID))
      WithClose(ds.getFeatureWriter(sft.getTypeName, filter, Transaction.AUTO_COMMIT)) { writer =>
        while (writer.hasNext) {
          writer.next()
          writer.remove()
        }
      }
    }
  }

  override def persist(): Unit = persistence match {
    case Some(p) => p.run()
    case None => throw new IllegalStateException("Persistence disabled for this store")
  }

  override def close(): Unit = {
    CloseWithLogging(loader)
    persistence.foreach(CloseWithLogging.apply)
  }

  private def prepFeature(original: SimpleFeature): SimpleFeature =
    setVisibility(GeoMesaFeatureWriter.featureWithFid(sft, original))

}

object KafkaStore {

  private [stream] val executor =
    MoreExecutors.getExitingScheduledExecutorService(
      Executors.newScheduledThreadPool(2).asInstanceOf[ScheduledThreadPoolExecutor])
  sys.addShutdownHook { executor.shutdownNow(); executor.awaitTermination(1, TimeUnit.SECONDS) }

  object MessageTypes {
    val Write:  Byte = 0
    val Delete: Byte = 1
  }

  def topic(ns: String, sft: SimpleFeatureType): String =
    s"${ns}_${sft.getTypeName}".replaceAll("[^a-zA-Z0-9_\\-]", "_")

  def withZk[T](zookeepers: String)(fn: (ZkUtils) => T): T = {
    val security = SystemProperty("geomesa.zookeeper.security.enabled").option.exists(_.toBoolean)
    val zkUtils = ZkUtils(zookeepers, 3000, 3000, security)
    try { fn(zkUtils) } finally {
      zkUtils.close()
    }
  }

  def producer(connect: Map[String, String]): Producer[Array[Byte], Array[Byte]] = {
    import org.apache.kafka.clients.producer.ProducerConfig._
    val props = new Properties()
    // set some defaults but allow them to be overridden
    props.put(ACKS_CONFIG, "1") // mix of reliability and performance
    props.put(RETRIES_CONFIG, Int.box(3))
    props.put(LINGER_MS_CONFIG, Int.box(3)) // helps improve batching at the expense of slight delays in write
    connect.foreach { case (k, v) => props.put(k, v) }
    props.put(PARTITIONER_CLASS_CONFIG, classOf[FeatureIdPartitioner].getName)
    props.put(KEY_SERIALIZER_CLASS_CONFIG, classOf[ByteArraySerializer].getName)
    props.put(VALUE_SERIALIZER_CLASS_CONFIG, classOf[ByteArraySerializer].getName)
    new KafkaProducer[Array[Byte], Array[Byte]](props)
  }

  def consumer(connect: Map[String, String], group: String): Consumer[Array[Byte], Array[Byte]] = {
    import org.apache.kafka.clients.consumer.ConsumerConfig._
    val props = new Properties()
    connect.foreach { case (k, v) => props.put(k, v) }
    props.put(GROUP_ID_CONFIG, group)
    props.put(ENABLE_AUTO_COMMIT_CONFIG, "false")
    props.put(AUTO_OFFSET_RESET_CONFIG, "earliest")
    props.put(KEY_DESERIALIZER_CLASS_CONFIG, classOf[ByteArrayDeserializer].getName)
    props.put(VALUE_DESERIALIZER_CLASS_CONFIG, classOf[ByteArrayDeserializer].getName)
    new KafkaConsumer[Array[Byte], Array[Byte]](props)
  }

  // creates a consumer and sets to the latest offsets
  private [kafka] def consumers(connect: Map[String, String],
                                topic: String,
                                manager: OffsetManager,
                                state: SharedState,
                                parallelism: Int): Seq[Consumer[Array[Byte], Array[Byte]]] = {
    require(parallelism > 0, "Parallelism must be greater than 0")

    val group = UUID.randomUUID().toString
    val topics = java.util.Arrays.asList(topic)

    Seq.fill(parallelism) {
      val consumer = KafkaStore.consumer(connect, group)
      consumer.subscribe(topics, new OffsetRebalanceListener(consumer, manager, state))
      consumer
    }
  }

  private [kafka] def serializeKey(time: Long, action: Byte): Array[Byte] = {
    val result = Array.ofDim[Byte](9)

    result(0) = ((time >> 56) & 0xff).asInstanceOf[Byte]
    result(1) = ((time >> 48) & 0xff).asInstanceOf[Byte]
    result(2) = ((time >> 40) & 0xff).asInstanceOf[Byte]
    result(3) = ((time >> 32) & 0xff).asInstanceOf[Byte]
    result(4) = ((time >> 24) & 0xff).asInstanceOf[Byte]
    result(5) = ((time >> 16) & 0xff).asInstanceOf[Byte]
    result(6) = ((time >> 8)  & 0xff).asInstanceOf[Byte]
    result(7) = (time & 0xff        ).asInstanceOf[Byte]
    result(8) = action

    result
  }

  private [kafka] def deserializeKey(key: Array[Byte]): (Long, Byte) = (Longs.fromByteArray(key), key(8))

  private [kafka] class OffsetRebalanceListener(consumer: Consumer[Array[Byte], Array[Byte]],
                                                manager: OffsetManager,
                                                state: SharedState) extends ConsumerRebalanceListener {

    // use reflection to work with kafak 0.9 or 0.10
    lazy val seekToBeginning: TopicPartition => Unit = {
      val method = consumer.getClass.getDeclaredMethods.find(_.getName == "seekToBeginning").getOrElse {
        throw new NoSuchMethodException("Couldn't find Consumer.seekToBeginning method")
      }
      val parameterTypes = method.getParameterTypes
      if (parameterTypes.length != 1) {
        throw new NoSuchMethodException("Couldn't find Consumer.seekToBeginning method with correct parameters")
      }
      val binding = method.getParameterTypes.apply(0)

      if (binding == classOf[Array[TopicPartition]]) {
        (tp) => method.invoke(consumer, Array(tp))
      } else if (binding == classOf[java.util.Collection[TopicPartition]]) {
        (tp) => method.invoke(consumer, Collections.singletonList(tp))
      } else {
        throw new NoSuchMethodException("Couldn't find Consumer.seekToBeginning method with correct parameters")
      }
    }

    override def onPartitionsRevoked(topicPartitions: java.util.Collection[TopicPartition]): Unit = {}

    override def onPartitionsAssigned(topicPartitions: java.util.Collection[TopicPartition]): Unit = {
      import scala.collection.JavaConversions._

      topicPartitions.foreach { tp =>
        // ensure we have queues for each partition
        state.ensurePartition(tp.partition)
        // read our last committed offsets and seek to them
        val lastRead = manager.getOffset(tp.topic(), tp.partition())
        if (lastRead > 0) {
          consumer.seek(tp, lastRead)
        } else {
          seekToBeginning(tp)
        }
      }
    }
  }

  /**
    * Ensures that updates to a given feature go to the same partition, so that they maintain order
    */
  class FeatureIdPartitioner extends Partitioner {

    override def partition(topic: String,
                           key: scala.Any,
                           keyBytes: Array[Byte],
                           value: scala.Any,
                           valueBytes: Array[Byte],
                           cluster: Cluster): Int = {
      val count = cluster.partitionsForTopic(topic).size
      // feature id starts at position 5
      val id = KryoFeatureSerializer.getInput(valueBytes, 5, valueBytes.length - 5).readString()
      Math.abs(MurmurHash3.stringHash(id)) % count
    }

    override def configure(configs: java.util.Map[String, _]): Unit = {}

    override def close(): Unit = {}
  }
}
