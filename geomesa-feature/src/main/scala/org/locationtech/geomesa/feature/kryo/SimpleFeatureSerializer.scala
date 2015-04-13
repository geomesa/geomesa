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

package org.locationtech.geomesa.feature.kryo

import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, Serializer}
import org.locationtech.geomesa.feature.EncodingOption.EncodingOptions
import org.locationtech.geomesa.feature.ScalaSimpleFeature
import org.locationtech.geomesa.feature.serialization.{DecodingsVersionCache, EncodingsCache, KryoSerialization}
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

/**
 * Kryo serialization implementation for simple features. This class shouldn't be used directly -
 * see @KryoFeatureSerializer
 *
 * @param sft the type of simple feature
 * @param opts the encoding options (optional)
 */
class SimpleFeatureSerializer(sft: SimpleFeatureType, opts: EncodingOptions = EncodingOptions.none)
  extends BaseSimpleFeatureSerializer(sft, opts) {

  type AttributeEncoding = EncodingsCache[Output]#AttributeEncoding

  lazy val encodings: Array[AttributeEncoding] = KryoSerialization.encodings(sft)
  lazy val decodingsByVersion: DecodingsVersionCache[Input] = KryoSerialization.decodings(sft)

  override def writeAttributes(output: Output, sf: SimpleFeature) = {
    var i = 0

    while (i < encodings.length) {
      encodings(i)(output, sf)
      i += 1
    }
  }

  def readAttributes(input: Input, version: Int): Array[AnyRef] = {
    val values = Array.ofDim[AnyRef](sft.getAttributeCount)
    var i = 0

    val decodings = decodingsByVersion.forVersion(version)
    while (i < decodings.length) {
      values(i) = decodings(i)(input)
      i += 1
    }

    values
  }
}

/**
 * Reads just the id from a serialized simple feature
 */
class FeatureIdSerializer extends Serializer[KryoFeatureId] {

  override def write(kryo: Kryo, output: Output, id: KryoFeatureId): Unit = ???

  override def read(kryo: Kryo, input: Input, typ: Class[KryoFeatureId]): KryoFeatureId = {
    input.readInt(true) // discard version info, not used for ID
    KryoFeatureId(input.readString())
  }
}

/**
 * Kryo serialization implementation for simple features - provides transformation during read and write
 *
 * @param sft
 * @param transform
 */
class TransformingSimpleFeatureSerializer(sft: SimpleFeatureType, transform: SimpleFeatureType,
                                          options: EncodingOptions)
  extends BaseSimpleFeatureSerializer(transform, options) {

  type AttributeEncoding = EncodingsCache[Output]#AttributeEncoding

  val (transformEncodings, transformDecodingsMapping) = {
    val encodings = KryoSerialization.encodings(sft)

    val enc = scala.collection.mutable.ArrayBuffer.empty[AttributeEncoding]
    val dec = scala.collection.mutable.ArrayBuffer.empty[Int]

    var i = 0
    while (i < encodings.length) {
      val index = transform.indexOf(sft.getDescriptor(i).getLocalName)
      if (index != -1) {
        enc.append(encodings(i))
      }
      dec.append(index)
      i += 1
    }
    (enc, dec)
  }

  lazy val decodingsByVersion: DecodingsVersionCache[Input] = KryoSerialization.decodings(sft)

  override def writeAttributes(output: Output, sf: SimpleFeature): Unit = {
    var i = 0
    while (i < transformEncodings.length) {
      transformEncodings(i)(output, sf)
      i += 1
    }
  }

  override def readAttributes(input: Input, version: Int): Array[AnyRef] = {
    val values = Array.ofDim[AnyRef](transform.getAttributeCount)
    var i = 0

    val transformDecodings = decodingsByVersion.forVersion(version)
    while (i < transformDecodings.length) {
      val decoding = transformDecodings(i)
      val index = transformDecodingsMapping(i)
      if (index == -1) {
        decoding(input) // discard
      } else {
        values(index) = decoding(input)
      }
      i += 1
    }
    values
  }
}

abstract class BaseSimpleFeatureSerializer(sft: SimpleFeatureType, opts: EncodingOptions)
  extends Serializer[SimpleFeature] {

  import org.locationtech.geomesa.feature.kryo.SimpleFeatureSerializer.VERSION

  val doWrite: (Kryo, Output, SimpleFeature) => Unit =
    if (opts.withUserData) writeWithUserData else defaultWrite

  val doRead: (Kryo, Input, Class[SimpleFeature]) => SimpleFeature =
    if (opts.withUserData) readWithUserData else defaultRead

  override def write(kryo: Kryo, output: Output, sf: SimpleFeature) = doWrite(kryo, output, sf)
  override def read(kryo: Kryo, input: Input, typ: Class[SimpleFeature]) = doRead(kryo, input, typ)

  def defaultWrite(kryo: Kryo, output: Output, sf: SimpleFeature): Unit = {
    output.writeInt(VERSION, true)
    output.writeString(sf.getID)

    writeAttributes(output, sf)
  }

  def writeWithUserData(kryo: Kryo, output: Output, sf: SimpleFeature) = {
    defaultWrite(kryo, output, sf)

    val kw = KryoSerialization.writer
    kw.writeGenericMap(output, sf.getUserData)
  }

  def defaultRead(kryo: Kryo, input: Input, typ: Class[SimpleFeature]): SimpleFeature = {
    val version = input.readInt(true)
    val id = input.readString()
    val values = readAttributes(input, version)

    new ScalaSimpleFeature(id, sft, values)
  }

  def readWithUserData(kryo: Kryo, input: Input, typ: Class[SimpleFeature]): SimpleFeature = {
    val version = input.readInt(true)
    val id = input.readString()
    val values = readAttributes(input, version)

    val sf = new ScalaSimpleFeature(id, sft, values)

    val kr = KryoSerialization.reader
    val userData = kr.readGenericMap(version)(input)
    sf.getUserData.putAll(userData)
    sf
  }

  def writeAttributes(output: Output, sf: SimpleFeature)
  def readAttributes(input: Input, version: Int): Array[AnyRef]
}

object SimpleFeatureSerializer {

  val VERSION = 1

  val NULL_BYTE     = 0.asInstanceOf[Byte]
  val NON_NULL_BYTE = 1.asInstanceOf[Byte]

}