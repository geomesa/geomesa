/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.accumulo.iterators

import java.util.concurrent.ConcurrentHashMap

import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.locationtech.geomesa.features.SerializationOption.SerializationOption
import org.locationtech.geomesa.features.kryo.KryoFeatureSerializer
import org.locationtech.geomesa.filter.factory.FastFilterFactory
import org.locationtech.geomesa.utils.cache.SoftThreadLocalCache
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.opengis.feature.simple.SimpleFeatureType
import org.opengis.filter.Filter

/**
  * Cache for expensive objects used in iterators
  */
object IteratorCache {

  implicit val allocator: BufferAllocator = new RootAllocator(Long.MaxValue)

  // thread safe objects can use a concurrent hashmap
  private val sftCache = new ConcurrentHashMap[String, SimpleFeatureType]()
  private val serializerCache = new ConcurrentHashMap[(String, String), KryoFeatureSerializer]()

  // non-thread safe objects use thread-locals
  // note: treating filters as unsafe due to an abundance of caution
  private val filterCache = new SoftThreadLocalCache[(String, String), Filter]()

  /**
    * Returns a cached simple feature type, creating one if necessary. Note: do not modify returned value.
    *
    * @param spec simple feature type spec
    * @return
    */
  def sft(spec: String): SimpleFeatureType = {
    // note: before the cache is populated, we might end up creating multiple objects, but it is still thread-safe
    val cached = sftCache.get(spec)
    if (cached != null) { cached } else {
      val sft = SimpleFeatureTypes.createType("", spec)
      sftCache.put(spec, sft)
      sft
    }
  }

  /**
    * Returns a cached serializer, creating one if necessary
    *
    * @param spec simple feature type spec
    * @param options serialization options
    * @return
    */
  def serializer(spec: String, options: Set[SerializationOption]): KryoFeatureSerializer = {
    // note: before the cache is populated, we might end up creating multiple objects, but it is still thread-safe
    val cached = serializerCache.get((spec, options.mkString))
    if (cached != null) { cached } else {
      val serializer = new KryoFeatureSerializer(sft(spec), options)
      serializerCache.put((spec, options.mkString), serializer)
      serializer
    }
  }

  /**
    * Returns a cached filter, creating one if necessary.
    *
    * Note: need to include simple feature type in cache key,
    * as attribute name -> attribute index gets cached in the filter
    *
    * @param spec simple feature type spec being filtered
    * @param ecql ecql
    * @return
    */
  def filter(spec: String, ecql: String): Filter =
    filterCache.getOrElseUpdate((spec, ecql), FastFilterFactory.toFilter(ecql))
}
