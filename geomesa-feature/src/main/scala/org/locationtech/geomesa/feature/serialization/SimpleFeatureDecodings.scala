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
package org.locationtech.geomesa.feature.serialization

import org.locationtech.geomesa.utils.cache.SoftThreadLocalCache
import org.opengis.feature.simple.SimpleFeatureType

import collection.JavaConversions._
import CacheKeyGenerator.cacheKeyForSFT


/** Provides access to the decodings for a [[SimpleFeatureType]].
  *
  */
class SimpleFeatureDecodings[Reader](val datumReaders: AbstractReader[Reader], val sft: SimpleFeatureType) {

  lazy val attributeDecodings: Array[DatumReader[Reader, AnyRef]] =
    sft.getAttributeDescriptors.map { d =>
      val clas = d.getType.getBinding
      datumReaders.selectReader(clas, d.getUserData, datumReaders.standardNullable)
    }.toArray
}

/** Caches [[SimpleFeatureDecodings]] for multiple [[SimpleFeatureType]]s.
  * Each thread has its own cache.
  *
  * Concrete subclasses must be objects not classes.
  */
trait SimpleFeatureDecodingsCache[Reader] {

  private val decodingsCache = new SoftThreadLocalCache[String, SimpleFeatureDecodings[Reader]]()

  def datumReadersFactory: () => AbstractReader[Reader]

  /** Gets a sequence of functions to decode the attributes of a simple feature. */
  def sftDecodings(sft: SimpleFeatureType): SimpleFeatureDecodings[Reader] =
    decodingsCache.getOrElseUpdate(cacheKeyForSFT(sft), {
      val datumReaders = datumReadersFactory()
      new SimpleFeatureDecodings[Reader](datumReaders, sft)
    })
}

object CacheKeyGenerator {

  import collection.JavaConversions._

  def cacheKeyForSFT(sft: SimpleFeatureType) =
    s"${sft.getName};${sft.getAttributeDescriptors.map(ad => s"${ad.getName.toString}${ad.getType}").mkString(",")}"
}