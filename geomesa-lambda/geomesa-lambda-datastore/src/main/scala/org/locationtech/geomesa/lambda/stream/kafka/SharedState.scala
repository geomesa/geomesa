/***********************************************************************
 * Copyright (c) 2013-2017 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.lambda.stream.kafka

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

import com.typesafe.scalalogging.LazyLogging
import org.joda.time.{DateTime, DateTimeZone}
import org.locationtech.geomesa.lambda.stream.OffsetManager.OffsetListener
import org.opengis.feature.simple.SimpleFeature

import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

/**
  * Locally cached features
  */
class SharedState(topic: String, partitions: Int) extends OffsetListener with LazyLogging {

  // map of feature id -> current feature
  private val features = new ConcurrentHashMap[String, SimpleFeature]

  // technically we should synchronize all access to the following arrays, since we expand them if needed;
  // however, in normal use it will be created up front and then only read.
  // if partitions are added at runtime, we may have synchronization issues...

  // array, indexed by partition, of queues of (offset, create time, feature), sorted by offset
  private val queues = ArrayBuffer.fill(partitions)((new ReentrantLock, new java.util.ArrayDeque[(Long, Long, SimpleFeature)]))
  private val offsets = ArrayBuffer.fill(partitions)(new AtomicLong(-1L))

  private val debug = logger.underlying.isDebugEnabled()

  /**
    * Initialize this cached state for a given partition and offset
    *
    * @param partition partition
    * @param offset offset
    */
  def partitionAssigned(partition: Int, offset: Long): Unit = synchronized {
    while (queues.length <= partition) {
      queues += ((new ReentrantLock, new java.util.ArrayDeque[(Long, Long, SimpleFeature)]))
      offsets += new AtomicLong(-1L)
    }
  }

  /**
    * Returns the most recent version of a feature in this cache, by feature ID
    *
    * @param id feature id
    * @return
    */
  def get(id: String): SimpleFeature = features.get(id)

  /**
    * Returns most recent versions of all features currently in this cache
    *
    * @return
    */
  def all(): Iterator[SimpleFeature] = {
    import scala.collection.JavaConverters._
    features.values.iterator.asScala
  }

  /**
    * Add a feature to the cached state
    *
    * @param f feature
    * @param partition partition corresponding to the add message
    * @param offset offset corresponding to the add message
    * @param created time feature was created
    */
  def add(f: SimpleFeature, partition: Int, offset: Long, created: Long): Unit = {
    if (offsets(partition).get < offset) {
      logger.trace(s"Adding [$partition:$offset] $f created at ${new DateTime(created, DateTimeZone.UTC)}")
      features.put(f.getID, f)
      val (lock, queue) = queues(partition)
      lock.lock()
      try { queue.addLast((offset, created, f)) } finally {
        lock.unlock()
      }
    } else {
      logger.trace(s"Ignoring [$partition:$offset] $f created at ${new DateTime(created, DateTimeZone.UTC)}")
    }
  }

  /**
    * Removes a feature from the cached state, for instance upon delete
    *
    * @param f feature
    * @param partition partition corresponding to the delete message
    * @param offset offset corresponding to the delete message
    * @param created time feature was deleted
    */
  def delete(f: SimpleFeature, partition: Int, offset: Long, created: Long): Unit = {
    logger.trace(s"Deleting [$partition:$offset] $f created at ${new DateTime(created, DateTimeZone.UTC)}")
    features.remove(f.getID)
  }

  /**
    * Thread safe check for any expired features
    *
    * @param expiry expiry
    * @return
    */
  def expired(expiry: Long): Seq[Int] = {
    val result = ArrayBuffer.empty[Int]
    var i = 0
    while (i < this.queues.length) {
      val (lock, queue) = queues(i)
      lock.lock()
      val peek = try { queue.peek } finally { lock.unlock() }
      peek match {
        case null => // no-op
        case (_, created, _) => if (expiry > created) { result += i }
      }
      i += 1
    }
    result
  }

  /**
    * Thread safe check to retrieve and remove any expired entries.
    *
    * @param partition partition
    * @param expiry expiry
    * @return (maxExpiredOffset, (offset, expired feature)), ordered by offset
    */
  def expired(partition: Int, expiry: Long): (Long, Seq[(Long, SimpleFeature)]) = {
    val expired = ArrayBuffer.empty[(Long, SimpleFeature)]
    val (lock, queue) = this.queues(partition)

    var loop = true
    while (loop) {
      lock.lock()
      try {
        val poll = queue.poll()
        if (poll == null) {
          lock.unlock()
          loop = false
        } else if (poll._2 > expiry) {
          queue.addFirst(poll) // note: add back to the queue before unlocking
          lock.unlock()
          loop = false
        } else {
          lock.unlock()
          expired += ((poll._1, poll._3))
        }
      } catch {
        case NonFatal(e) => lock.unlock(); throw e
      }
    }

    logger.debug(s"Checking [$topic:$partition] for expired entries: found ${expired.size} expired and ${queue.size} remaining")

    val maxExpiredOffset = if (expired.isEmpty) { -1L } else { expired(expired.length - 1)._1 }

    // only remove from feature cache (and persist) if there haven't been additional updates
    val latest = expired.filter { case (_, feature) => features.remove(feature.getID, feature) }
    (maxExpiredOffset, latest)
  }

  override def offsetChanged(partition: Int, offset: Long): Unit = {
    val (lock, queue) = queues(partition)

    // remove the expired features from the cache
    val (featureSize, queueSize, start) = if (!debug) { (0, 0, 0L) } else {
      logger.debug(s"Offsets changed for [$topic:$partition]: -> $offset")
      (features.size, queue.size, System.currentTimeMillis())
    }

    var loop = true
    while (loop) {
      lock.lock()
      try {
        val poll = queue.poll()
        if (poll == null) {
          lock.unlock()
          loop = false
        } else if (poll._1 > offset) {
          queue.addFirst(poll) // note: add back to the queue before unlocking
          lock.unlock()
          loop = false
        } else {
          lock.unlock()
          // only remove from feature cache if there haven't been additional updates
          features.remove(poll._3.getID, poll._3)
        }
      } catch {
        case NonFatal(e) => lock.unlock(); throw e
      }
    }

    // update the valid offset
    var last = offsets(partition).get
    while(last < offset && !offsets(partition).compareAndSet(last, offset)) {
      last = offsets(partition).get
    }

    logger.debug(s"Size of cached state for [$topic:$partition]: features (total): " +
        s"${diff(featureSize, features.size)}, offsets: ${diff(queueSize, queue.size)} in " +
        s"${System.currentTimeMillis() - start}ms")
  }

  // debug message
  private def diff(original: Int, updated: Int): String = f"$updated%d (${updated - original}%+d)"
}
