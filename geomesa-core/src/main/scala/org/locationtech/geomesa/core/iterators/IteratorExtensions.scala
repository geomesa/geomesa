/*
 * Copyright 2014 Commonwealth Computer Research, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.locationtech.geomesa.core.iterators

import org.geotools.filter.text.ecql.ECQL
import org.locationtech.geomesa.core._
import org.locationtech.geomesa.core.index._
import org.locationtech.geomesa.core.iterators.IteratorExtensions.OptionMap
import org.locationtech.geomesa.core.transform.TransformCreator
import org.locationtech.geomesa.feature.{FeatureEncoding, SimpleFeatureDecoder, SimpleFeatureEncoder}
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter

/**
 * Defines common iterator functionality in traits that can be mixed-in to iterator implementations
 */
trait IteratorExtensions {
  def init(featureType: SimpleFeatureType, options: OptionMap)
}

object IteratorExtensions {
  type OptionMap = java.util.Map[String, String]
}

/**
 * We need a concrete class to mix the traits into. This way they can share a common 'init' method
 * that will be called for each trait. See http://stackoverflow.com/a/1836619
 */
class HasIteratorExtensions extends IteratorExtensions {
  override def init(featureType: SimpleFeatureType, options: OptionMap) = {}
}

/**
 * Provides a feature type based on the iterator config
 */
trait HasFeatureType {

  var featureType: SimpleFeatureType = null

  // feature type config
  def initFeatureType(options: OptionMap) = {
    val sftName = Option(options.get(GEOMESA_ITERATORS_SFT_NAME)).getOrElse(this.getClass.getSimpleName)
    featureType = SimpleFeatureTypes.createType(sftName, options.get(GEOMESA_ITERATORS_SIMPLE_FEATURE_TYPE))
    featureType.decodeUserData(options, GEOMESA_ITERATORS_SIMPLE_FEATURE_TYPE)
  }
}

trait HasVersion extends IteratorExtensions {

  var version: Int = -1

  abstract override def init(featureType: SimpleFeatureType, options: OptionMap) = {
    super.init(featureType, options)
    version = options.get(GEOMESA_ITERATORS_VERSION).toInt
  }
}

/**
 * Provides an index value decoder
 */
trait HasIndexValueDecoder extends HasVersion {

  var indexSft: SimpleFeatureType = null
  var indexEncoder: IndexValueEncoder = null

  // index value encoder/decoder
  abstract override def init(featureType: SimpleFeatureType, options: OptionMap) = {
    super.init(featureType, options)
    val version = options.get(GEOMESA_ITERATORS_VERSION).toInt
    indexSft = SimpleFeatureTypes.createType(featureType.getTypeName,
      options.get(GEOMESA_ITERATORS_SFT_INDEX_VALUE))
    indexEncoder = IndexValueEncoder(indexSft, featureType, version)
  }
}

/**
 * Provides a feature encoder and decoder
 */
trait HasFeatureDecoder extends IteratorExtensions {

  var featureDecoder: SimpleFeatureDecoder = null
  var featureEncoder: SimpleFeatureEncoder = null
  val defaultEncoding = org.locationtech.geomesa.core.data.DEFAULT_ENCODING

  // feature encoder/decoder
  abstract override def init(featureType: SimpleFeatureType, options: OptionMap) = {
    super.init(featureType, options)
    // this encoder is for the source sft
    val encoding = Option(options.get(FEATURE_ENCODING)).map(FeatureEncoding.withName).getOrElse(defaultEncoding)
    featureDecoder = SimpleFeatureDecoder(featureType, encoding)
    featureEncoder = SimpleFeatureEncoder(featureType, encoding)
  }
}

/**
 * Provides a spatio-temporal filter (date and geometry only) if the iterator config specifies one
 */
trait HasSpatioTemporalFilter extends IteratorExtensions {

  var stFilter: Filter = null

  // spatio-temporal filter config
  abstract override def init(featureType: SimpleFeatureType, options: OptionMap) = {
    super.init(featureType, options)
    if (options.containsKey(ST_FILTER_PROPERTY_NAME)) {
      val filter = ECQL.toFilter(options.get(ST_FILTER_PROPERTY_NAME))
      if (filter != Filter.INCLUDE) {
        stFilter = filter
      }
    }
  }
}

/**
 * Provides an arbitrary filter if the iterator config specifies one
 */
trait HasFilter extends IteratorExtensions {

  var filter: Filter = null

  // other filter config
  abstract override def init(featureType: SimpleFeatureType, options: OptionMap) = {
    super.init(featureType, options)
    if (options.containsKey(GEOMESA_ITERATORS_ECQL_FILTER)) {
      val ecql = ECQL.toFilter(options.get(GEOMESA_ITERATORS_ECQL_FILTER))
      if (ecql != Filter.INCLUDE) {
        filter = ecql
      }
    }
  }
}

/**
 * Provides a feature type transformation if the iterator config specifies one
 */
trait HasTransforms extends IteratorExtensions {

  import org.locationtech.geomesa.core.data.DEFAULT_ENCODING

  type TransformFunction = (SimpleFeature) => Array[Byte]
  var transform: TransformFunction = null

  // feature type transforms
  abstract override def init(featureType: SimpleFeatureType, options: OptionMap) = {
    super.init(featureType, options)
    if (options.containsKey(GEOMESA_ITERATORS_TRANSFORM_SCHEMA) &&
        options.containsKey(GEOMESA_ITERATORS_TRANSFORM)) {
      val transformSchema = options.get(GEOMESA_ITERATORS_TRANSFORM_SCHEMA)
      val targetFeatureType = SimpleFeatureTypes.createType(this.getClass.getCanonicalName, transformSchema)
      targetFeatureType.decodeUserData(options, GEOMESA_ITERATORS_TRANSFORM_SCHEMA)

      val transformString = options.get(GEOMESA_ITERATORS_TRANSFORM)
      val transformEncoding = Option(options.get(FEATURE_ENCODING)).map(FeatureEncoding.withName)
          .getOrElse(DEFAULT_ENCODING)

      transform = TransformCreator.createTransform(targetFeatureType, transformEncoding, transformString)
    }
  }
}

/**
 * Provides deduplication if the iterator config specifies it
 */
trait HasInMemoryDeduplication extends IteratorExtensions {

  type CheckUniqueId = (String) => Boolean

  private var deduplicate: Boolean = false

  // each thread maintains its own (imperfect!) list of the unique identifiers it has seen
  private var maxInMemoryIdCacheEntries = 10000
  private var inMemoryIdCache: java.util.HashSet[String] = null

  /**
   * Returns a local estimate as to whether the current identifier
   * is likely to be a duplicate.
   *
   * Because we set a limit on how many unique IDs will be preserved in
   * the local cache, a TRUE response is always accurate, but a FALSE
   * response may not be accurate.  (That is, this cache allows for false-
   * negatives, but no false-positives.)  We accept this, because there is
   * a final, client-side filter that will eliminate all duplicate IDs
   * definitively.  The purpose of the local cache is to reduce traffic
   * through the remainder of the iterator/aggregator pipeline as quickly as
   * possible.
   *
   * @return False if this identifier is in the local cache; True otherwise
   */
  var checkUniqueId: CheckUniqueId = null

  abstract override def init(featureType: SimpleFeatureType, options: OptionMap) = {
    super.init(featureType, options)
    // check for dedupe - we don't need to dedupe for density queries
    if (!options.containsKey(GEOMESA_ITERATORS_IS_DENSITY_TYPE)) {
      deduplicate = IndexSchema.mayContainDuplicates(featureType)
      if (deduplicate) {
        if (options.containsKey(DEFAULT_CACHE_SIZE_NAME)) {
          maxInMemoryIdCacheEntries = options.get(DEFAULT_CACHE_SIZE_NAME).toInt
        }
        inMemoryIdCache = new java.util.HashSet[String](maxInMemoryIdCacheEntries)
        checkUniqueId =
            (id: String) => if (inMemoryIdCache.size < maxInMemoryIdCacheEntries) {
              inMemoryIdCache.add(id)
            } else {
              !inMemoryIdCache.contains(id)
            }
      }
    }
  }
}