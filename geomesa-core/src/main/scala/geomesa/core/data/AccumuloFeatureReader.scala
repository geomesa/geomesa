/*
 * Copyright 2013 Commonwealth Computer Research, Inc.
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

package geomesa.core.data

import geomesa.core.index._
import org.geotools.data.{Query, FeatureReader}
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

class AccumuloFeatureReader(dataStore: AccumuloDataStore,
                            featureName: String,              // JNH can go.
                            query: Query,
                            indexSchemaFmt: String,
                            attributes: String,         // JNH can go.
                            sft: SimpleFeatureType,
                            featureEncoder: SimpleFeatureEncoder)
  extends FeatureReader[SimpleFeatureType, SimpleFeature] {

  val indexSchema = IndexSchema(indexSchemaFmt, sft, featureEncoder)
  lazy val (iter, bs) = indexSchema.query(query, dataStore.createBatchScanner)

  override def getFeatureType = sft

  override def next() = iter.next()

  override def hasNext = iter.hasNext

  override def close() = bs.close()
}
