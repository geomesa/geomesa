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

import org.apache.accumulo.core.data._
import org.apache.accumulo.core.iterators.{IteratorEnvironment, SortedKeyValueIterator}
import org.geotools.feature.simple.SimpleFeatureBuilder
import org.geotools.filter.text.ecql.ECQL
import org.locationtech.geomesa.core.data._
import org.locationtech.geomesa.core.index
import org.locationtech.geomesa.core.index._
import org.locationtech.geomesa.feature.{FeatureEncoding, SimpleFeatureEncoder, AvroSimpleFeatureFactory}
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.opengis.feature.`type`.AttributeDescriptor
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

/**
 * This is an Index Only Iterator, to be used in situations where the data records are
 * not useful enough to pay the penalty of decoding when using the
 * SpatioTemporalIntersectingIterator.
 *
 * This iterator returns as its nextKey the key for the index. nextValue is
 * the value for the INDEX, mapped into a SimpleFeature
 *
 * Note that this extends the SpatioTemporalIntersectingIterator, but never creates a dataSource
 * and hence never iterates through it.
 */
class IndexIterator extends SpatioTemporalIntersectingIterator with SortedKeyValueIterator[Key, Value] {

  import org.locationtech.geomesa.core._

  var featureBuilder: SimpleFeatureBuilder = null
  var featureEncoder: SimpleFeatureEncoder = null
  var outputAttributes: List[AttributeDescriptor] = null
  var indexAttributes: List[AttributeDescriptor] = null

  override def init(source: SortedKeyValueIterator[Key, Value],
                    options: java.util.Map[String, String],
                    env: IteratorEnvironment) {
    TServerClassLoader.initClassLoader(logger)

    val simpleFeatureTypeSpec = options.get(GEOMESA_ITERATORS_SIMPLE_FEATURE_TYPE)

    val featureType = SimpleFeatureTypes.createType(this.getClass.getCanonicalName, simpleFeatureTypeSpec)
    featureType.decodeUserData(options, GEOMESA_ITERATORS_SIMPLE_FEATURE_TYPE)

    indexEncoder = IndexValueEncoder(featureType)

    dateAttributeName = getDtgFieldName(featureType)

    // default to text if not found for backwards compatibility
    val encodingOpt = Option(options.get(FEATURE_ENCODING)).getOrElse(FeatureEncoding.TEXT.toString)
    featureEncoder = SimpleFeatureEncoder(featureType, encodingOpt)

    featureBuilder = AvroSimpleFeatureFactory.featureBuilder(featureType)

    if (options.containsKey(DEFAULT_FILTER_PROPERTY_NAME)) {
      val filterString  = options.get(DEFAULT_FILTER_PROPERTY_NAME)
      filter = ECQL.toFilter(filterString)
      val sfb = new SimpleFeatureBuilder(featureType)
      testSimpleFeature = sfb.buildFeature("test")
    }

    if (options.containsKey(DEFAULT_CACHE_SIZE_NAME))
      maxInMemoryIdCacheEntries = options.get(DEFAULT_CACHE_SIZE_NAME).toInt
    deduplicate = IndexSchema.mayContainDuplicates(featureType)

    this.indexSource = source.deepCopy(env)
  }

  /**
   * Generates from the key's value a SimpleFeature that matches the current
   * (top) reference of the index-iterator.
   *
   * We emit the top-key from the index-iterator, and the top-value from the
   * converted key value.  This is *IMPORTANT*, as otherwise we do not emit rows
   * that honor the SortedKeyValueIterator expectation, and Bad Things Happen.
   */
  override def seekData(decodedValue: DecodedIndexValue) {
    // now increment the value of nextKey, copy because reusing it is UNSAFE
    nextKey = new Key(indexSource.getTopKey)
    // using the already decoded index value, generate a SimpleFeature and set as the Value
    val nextSimpleFeature = IndexIterator.encodeIndexValueToSF(featureBuilder, decodedValue)
    nextValue = new Value(featureEncoder.encode(nextSimpleFeature))
  }

  override def deepCopy(env: IteratorEnvironment) =
    throw new UnsupportedOperationException("IndexIterator does not support deepCopy.")
}

object IndexIterator {

  import org.locationtech.geomesa.core.index.IndexValueEncoder.ID_FIELD

  /**
   * Converts values taken from the Index Value to a SimpleFeature, using the passed SimpleFeatureBuilder
   * Note that the ID, taken from the index, is preserved
   * Also note that the SimpleFeature's other attributes may not be fully parsed and may be left as null;
   * the SimpleFeatureFilteringIterator *may* remove the extraneous attributes later in the Iterator stack
   */
  def encodeIndexValueToSF(featureBuilder: SimpleFeatureBuilder, value: DecodedIndexValue): SimpleFeature = {
    // Build and fill the Feature. This offers some performance gain over building and then setting the attributes.
    featureBuilder.buildFeature(value.id, attributeArray(featureBuilder.getFeatureType, value))
  }

  /**
   * Construct and fill an array of the SimpleFeature's attribute values
   */
  def attributeArray(sft: SimpleFeatureType, indexValue: DecodedIndexValue): Array[AnyRef] = {
    val attrArray = new Array[AnyRef](sft.getAttributeCount)
    indexValue.attributes.foreach { case (name, value) =>
      if (name != ID_FIELD) {
        attrArray.update(sft.indexOf(name), value.asInstanceOf[AnyRef])
      }
    }
    attrArray
  }
}
