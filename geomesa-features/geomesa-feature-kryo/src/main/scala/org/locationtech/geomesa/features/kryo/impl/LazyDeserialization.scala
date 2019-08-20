/***********************************************************************
 * Copyright (c) 2013-2019 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.features.kryo
package impl

import java.io.InputStream

import com.esotericsoftware.kryo.io.Input
import org.locationtech.geomesa.features.ScalaSimpleFeature.{LazyAttributeReader, LazyImmutableSimpleFeature, LazyMutableSimpleFeature, LazyUserDataReader}
import org.locationtech.geomesa.features.kryo.impl.KryoFeatureDeserialization.KryoAttributeReader
import org.locationtech.geomesa.features.kryo.impl.LazyDeserialization._
import org.locationtech.geomesa.features.kryo.serialization.KryoUserDataSerialization
import org.locationtech.geomesa.utils.collection.IntBitSet
import org.opengis.feature.simple.SimpleFeature

object LazyDeserialization {

  /**
    * Creates mutable features, lazily evaluated
    */
  trait MutableLazyDeserialization extends LazyDeserialization {
    override protected def createFeature(
        id: String,
        reader: LazyAttributeReader,
        userData: LazyUserDataReader): SimpleFeature = {
      new LazyMutableSimpleFeature(out, id, reader, userData)
    }
  }

  /**
    * Creates immutable features, lazily evaluated
    */
  trait ImmutableLazyDeserialization extends LazyDeserialization {
    override protected def createFeature(
        id: String,
        reader: LazyAttributeReader,
        userData: LazyUserDataReader): SimpleFeature = {
      new LazyImmutableSimpleFeature(out, id, reader, userData)
    }
  }

  /**
    * Attribute reader for v3 serialization
    *
    * @param readers readers
    * @param nulls null set
    * @param count number of attributes
    * @param bytes raw serialized bytes
    * @param offset offset into the byte array
    * @param length number of valid bytes in the byte array
    */
  class LazyReaderV3(
      readers: Array[KryoAttributeReader],
      nulls: IntBitSet,
      count: Int,
      bytes: Array[Byte],
      offset: Int,
      length: Int
    ) extends LazyAttributeReader {
    override def read(i: Int): AnyRef = {
      if (i >= count || nulls.contains(i)) { null } else {
        // read the offset and go to the position for reading
        // we create a new kryo input each time, so that position and offset are not affected by other reads
        // this should be thread-safe, as long as the same attribute is not being read in multiple threads
        // (since kryo can mutate the bytes during read)
        val input = new Input(bytes, offset + (2 * i), length - (2 * i))
        input.setPosition(offset + input.readShort())
        readers(i).apply(input)
      }
    }
  }

  /**
    * User data reader for v3 serialization
    *
    * @param count number of attributes
    * @param bytes raw serialized bytes
    * @param offset offset into the byte array
    * @param length number of valid bytes in the byte array
    */
  class LazyUserDataReaderV3(count: Int, bytes: Array[Byte], offset: Int, length: Int) extends LazyUserDataReader {
    override def read(): java.util.Map[AnyRef, AnyRef] = {
      // read the offset and go to the position for reading
      // we create a new kryo input each time, so that position and offset are not affected by other reads
      // this should be thread-safe, as long as the user data is not being read in multiple threads
      // (since kryo can mutate the bytes during read)
      val input = new Input(bytes, offset + (2 * count), length - (2 * count))
      // read the offset and go to the position for reading
      input.setPosition(offset + input.readShort())
      KryoUserDataSerialization.deserialize(input)
    }
  }

  /**
    * Attribute reader for v2 serialization
    *
    * @param readers readers
    * @param offsets offsets for each attribute
    * @param bytes raw serialized bytes
    * @param length number of valid bytes in the byte array
    */
  class LazyReaderV2(readers: Array[Input => AnyRef], offsets: Array[Int], bytes: Array[Byte], length: Int)
      extends LazyAttributeReader {
    override def read(i: Int): AnyRef = {
      val offset = offsets(i)
      if (offset == -1) { null } else {
        // we create a new kryo input each time, so that position and offset are not affected by other reads
        // this should be thread-safe, as long as the same attribute is not being read in multiple threads
        // (since kryo can mutate the bytes during read)
        readers(i)(new Input(bytes, offset, length - offset))
      }
    }
  }

  /**
    * User data reader for v2 serialization
    *
    * @param bytes raw serialized bytes
    * @param userDataOffset offset to the serialized user data
    * @param length number of valid bytes in the byte array
    */
  class LazyUserDataReaderV2(bytes: Array[Byte], userDataOffset: Int, length: Int) extends LazyUserDataReader {
    override def read(): java.util.Map[AnyRef, AnyRef] = {
      // we create a new kryo input each time, so that position and offset are not affected by other reads
      // this should be thread-safe, as long as the user data is not being read in multiple threads
      // (since kryo can mutate the bytes during read)
      KryoUserDataSerialization.deserialize(new Input(bytes, userDataOffset, length - userDataOffset))
    }
  }

  /**
    * Reader for serialization without user data
    */
  case object WithoutUserDataReader extends LazyUserDataReader {
    override def read(): java.util.Map[AnyRef, AnyRef] = new java.util.HashMap[AnyRef, AnyRef](1)
  }
}

/**
  * Wraps the input but defers deserialization until an attribute is required
  */
trait LazyDeserialization extends KryoFeatureDeserialization {

  override def deserialize(bytes: Array[Byte]): SimpleFeature =
    deserialize("", bytes, 0, bytes.length)

  override def deserialize(id: String, bytes: Array[Byte]): SimpleFeature =
    deserialize(id, bytes, 0, bytes.length)

  override def deserialize(bytes: Array[Byte], offset: Int, length: Int): SimpleFeature =
    deserialize("", bytes, offset, length)

  override def deserialize(id: String, bytes: Array[Byte], offset: Int, length: Int): SimpleFeature = {
    bytes(offset) match {
      case KryoFeatureSerializer.Version3 => readFeatureV3(id, bytes, offset + 3, length - 3) // note: our offset starts after the version byte and 'count' short
      case KryoFeatureSerializer.Version2 => readFeatureV2(id, bytes, offset, length)
      case b => throw new IllegalArgumentException(s"Can't process features serialized with version: $b")
    }
  }

  // TODO read into a byte array so we can lazily evaluate it
  // user data is tricky here as we don't know the length...
  override def deserialize(in: InputStream): SimpleFeature = throw new NotImplementedError
  override def deserialize(id: String, in: InputStream): SimpleFeature = throw new NotImplementedError

  protected def createFeature(id: String, reader: LazyAttributeReader, userData: LazyUserDataReader): SimpleFeature

  private def readFeatureV3(id: String, bytes: Array[Byte], offset: Int, length: Int): SimpleFeature = {
    // our offset starts after the 'count' short, go back so we can read it
    val input = new Input(bytes, offset - 2, length + 2)
    val count = input.readShort()

    // read our null mask
    input.setPosition(offset + (2 * count) + 2)
    val nulls = IntBitSet.deserialize(input, count)

    // we should now be positioned to read the feature id
    val finalId = if (withoutId) { id } else { input.readString() }

    val reader = new LazyReaderV3(readers, nulls, count, bytes, offset, length)
    val userData = if (withoutUserData) { WithoutUserDataReader } else {
      new LazyUserDataReaderV3(count, bytes, offset, length)
    }

    createFeature(finalId, reader, userData)
  }

  private def readFeatureV2(id: String, bytes: Array[Byte], offset: Int, length: Int): SimpleFeature = {
    val input = new Input(bytes, offset + 1, length - 1) // skip the version byte
    // read the start of the offsets, then the feature id
    val offsets = Array.ofDim[Int](readersV2.length)
    val offsetStarts = offset + input.readInt()
    val finalId = if (withoutId) { id } else { input.readString() }
    // now read our offsets
    input.setPosition(offsetStarts) // set to offsets start
    var i = 0
    while (i < offsets.length && input.position < input.limit) {
      offsets(i) = offset + input.readInt(true)
      i += 1
    }
    if (i < offsets.length) {
      // attributes have been added to the sft since this feature was serialized
      do { offsets(i) = -1; i += 1 } while (i < offsets.length)
    }
    val userDataOffset = input.position()

    val reader = new LazyReaderV2(readersV2, offsets, bytes, length)
    val userData = if (withoutUserData) { WithoutUserDataReader } else {
      new LazyUserDataReaderV2(bytes, userDataOffset, length)
    }

    createFeature(finalId, reader, userData)
  }
}
