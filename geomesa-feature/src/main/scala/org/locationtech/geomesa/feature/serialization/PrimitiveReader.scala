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

import java.nio.ByteBuffer
import java.util.Date

/** A collection of [[DatumReader]]s for reading primitive-like datums.
  *
  * Created by mmatz on 4/7/15.
  */
trait PrimitiveReader {

  def readString: DatumReader[String]
  def readInt: DatumReader[Int]
  def readLong: DatumReader[Long]
  def readFloat: DatumReader[Float]
  def readDouble: DatumReader[Double]
  def readBoolean: DatumReader[Boolean]
  def readDate: DatumReader[Date]
  def readBytes: DatumReader[ByteBuffer]
  
}
