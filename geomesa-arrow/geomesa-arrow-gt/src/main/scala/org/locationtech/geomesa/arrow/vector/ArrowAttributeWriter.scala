/***********************************************************************
* Copyright (c) 2013-2017 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.arrow.vector

import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.util.Date

import com.vividsolutions.jts.geom._
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.complex.NullableMapVector
import org.apache.arrow.vector.complex.impl.NullableMapWriter
import org.apache.arrow.vector.complex.writer.BaseWriter.{ListWriter, MapWriter}
import org.apache.arrow.vector.complex.writer._
import org.apache.arrow.vector.types.Types.MinorType
import org.apache.arrow.vector.{NullableIntVector, NullableSmallIntVector, NullableTinyIntVector}
import org.locationtech.geomesa.arrow.vector.GeometryVector.GeometryWriter
import org.locationtech.geomesa.arrow.vector.LineStringVector.LineStringWriter
import org.locationtech.geomesa.arrow.vector.MultiLineStringVector.MultiLineStringWriter
import org.locationtech.geomesa.arrow.vector.MultiPointVector.MultiPointWriter
import org.locationtech.geomesa.arrow.vector.MultiPolygonVector.MultiPolygonWriter
import org.locationtech.geomesa.arrow.vector.PointVector.PointWriter
import org.locationtech.geomesa.arrow.vector.PolygonVector.PolygonWriter
import org.locationtech.geomesa.features.serialization.ObjectType
import org.locationtech.geomesa.features.serialization.ObjectType.ObjectType
import org.locationtech.geomesa.utils.text.WKTUtils
import org.opengis.feature.`type`.AttributeDescriptor

trait ArrowAttributeWriter extends Closeable {
  def apply(value: AnyRef): Unit
  override def close(): Unit = {}
}

object ArrowAttributeWriter {

  import scala.collection.JavaConversions._

  def apply(descriptor: AttributeDescriptor,
            vector: NullableMapVector,
            writer: NullableMapWriter,
            dictionary: Option[ArrowDictionary],
            allocator: BufferAllocator): ArrowAttributeWriter = {
    val name = descriptor.getLocalName
    val classBinding = descriptor.getType.getBinding
    val (objectType, bindings) = ObjectType.selectType(classBinding, descriptor.getUserData)
    apply(name, bindings.+:(objectType), classBinding, vector, writer, dictionary, allocator)
  }

  def apply(name: String,
            bindings: Seq[ObjectType],
            classBinding: Class[_],
            vector: NullableMapVector,
            writer: NullableMapWriter,
            dictionary: Option[ArrowDictionary],
            allocator: BufferAllocator): ArrowAttributeWriter = {
    dictionary match {
      case None =>
        bindings.head match {
          case ObjectType.STRING   => new ArrowStringWriter(writer.varChar(name), allocator)
          case ObjectType.GEOMETRY => new ArrowGeometryWriter(writer.map(name), classBinding)
          case ObjectType.INT      => new ArrowIntWriter(writer.integer(name))
          case ObjectType.LONG     => new ArrowLongWriter(writer.bigInt(name))
          case ObjectType.FLOAT    => new ArrowFloatWriter(writer.float4(name))
          case ObjectType.DOUBLE   => new ArrowDoubleWriter(writer.float8(name))
          case ObjectType.BOOLEAN  => new ArrowBooleanWriter(writer.bit(name))
          case ObjectType.DATE     => new ArrowDateWriter(writer.date(name))
          case ObjectType.LIST     => new ArrowListWriter(writer.list(name), bindings(1), allocator)
          case ObjectType.MAP      => new ArrowMapWriter(writer.map(name), bindings(1), bindings(2), allocator)
          case ObjectType.BYTES    => new ArrowBytesWriter(writer.varBinary(name), allocator)
          case ObjectType.JSON     => new ArrowStringWriter(writer.varChar(name), allocator)
          case ObjectType.UUID     => new ArrowStringWriter(writer.varChar(name), allocator)
          case _ => throw new IllegalArgumentException(s"Unexpected object type ${bindings.head}")
        }

      case Some(dict) =>
        bindings.head match {
          case ObjectType.STRING =>
            val encoding = dict.encoding
            if (encoding.getIndexType.getBitWidth == 8) {
              vector.addOrGet(name, MinorType.TINYINT, classOf[NullableTinyIntVector], encoding)
              new ArrowDictionaryByteWriter(writer.tinyInt(name), dict)
            } else if (encoding.getIndexType.getBitWidth == 16) {
              vector.addOrGet(name, MinorType.SMALLINT, classOf[NullableSmallIntVector], encoding)
              new ArrowDictionaryShortWriter(writer.smallInt(name), dict)
            } else {
              vector.addOrGet(name, MinorType.INT, classOf[NullableIntVector], encoding)
              new ArrowDictionaryIntWriter(writer.integer(name), dict)
            }

          case _ => throw new IllegalArgumentException(s"Dictionary only supported for string type: ${bindings.head}")
        }
    }
  }

  class ArrowDictionaryByteWriter(writer: TinyIntWriter, dictionary: ArrowDictionary)
      extends ArrowAttributeWriter {
    override def apply(value: AnyRef): Unit = if (value != null) {
      writer.writeTinyInt(dictionary.index(value).toByte)
    }
  }

  class ArrowDictionaryShortWriter(writer: SmallIntWriter, dictionary: ArrowDictionary)
      extends ArrowAttributeWriter {
    override def apply(value: AnyRef): Unit = if (value != null) {
      writer.writeSmallInt(dictionary.index(value).toShort)
    }
  }

  class ArrowDictionaryIntWriter(writer: IntWriter, dictionary: ArrowDictionary)
      extends ArrowAttributeWriter {
    override def apply(value: AnyRef): Unit = if (value != null) {
      writer.writeInt(dictionary.index(value))
    }
  }

  class ArrowGeometryWriter(writer: MapWriter, binding: Class[_]) extends ArrowAttributeWriter {
    private val delegate: GeometryWriter[Geometry] = if (binding == classOf[Point]) {
      new PointWriter(writer).asInstanceOf[GeometryWriter[Geometry]]
    } else if (binding == classOf[LineString]) {
      new LineStringWriter(writer).asInstanceOf[GeometryWriter[Geometry]]
    } else if (binding == classOf[Polygon]) {
      new PolygonWriter(writer).asInstanceOf[GeometryWriter[Geometry]]
    } else if (binding == classOf[MultiLineString]) {
      new MultiLineStringWriter(writer).asInstanceOf[GeometryWriter[Geometry]]
    } else if (binding == classOf[MultiPolygon]) {
      new MultiPolygonWriter(writer).asInstanceOf[GeometryWriter[Geometry]]
    } else if (binding == classOf[MultiPoint]) {
      new MultiPointWriter(writer).asInstanceOf[GeometryWriter[Geometry]]
    } else if (classOf[Geometry].isAssignableFrom(binding)) {
      throw new NotImplementedError(s"Geometry type $binding is not supported")
    } else {
      throw new IllegalArgumentException(s"Expected geometry type, got $binding")
    }

    override def apply(value: AnyRef): Unit = if (value != null) {
      delegate.set(value.asInstanceOf[Geometry])
    }
    override def close(): Unit = delegate.close()
  }

  class ArrowStringWriter(writer: VarCharWriter, allocator: BufferAllocator) extends ArrowAttributeWriter {
    override def apply(value: AnyRef): Unit = if (value != null) {
      val bytes = value.toString.getBytes(StandardCharsets.UTF_8)
      val buffer = allocator.buffer(bytes.length)
      buffer.setBytes(0, bytes)
      writer.writeVarChar(0, bytes.length, buffer)
      buffer.close()
    }
  }

  class ArrowIntWriter(writer: IntWriter) extends ArrowAttributeWriter {
    override def apply(value: AnyRef): Unit = if (value != null) {
      writer.writeInt(value.asInstanceOf[Int])
    }
  }

  class ArrowLongWriter(writer: BigIntWriter) extends ArrowAttributeWriter {
    override def apply(value: AnyRef): Unit = if (value != null) {
      writer.writeBigInt(value.asInstanceOf[Long])
    }
  }

  class ArrowFloatWriter(writer: Float4Writer) extends ArrowAttributeWriter {
    override def apply(value: AnyRef): Unit = if (value != null) {
      writer.writeFloat4(value.asInstanceOf[Float])
    }
  }

  class ArrowDoubleWriter(writer: Float8Writer) extends ArrowAttributeWriter {
    override def apply(value: AnyRef): Unit = if (value != null) {
      writer.writeFloat8(value.asInstanceOf[Double])
    }
  }

  class ArrowBooleanWriter(writer: BitWriter) extends ArrowAttributeWriter {
    override def apply(value: AnyRef): Unit = if (value != null) {
      writer.writeBit(if (value.asInstanceOf[Boolean]) { 1 } else { 0 })
    }
  }

  class ArrowDateWriter(writer: DateWriter) extends ArrowAttributeWriter {
    override def apply(value: AnyRef): Unit = if (value != null) {
      writer.writeDate(value.asInstanceOf[Date].getTime)
    }
  }

  class ArrowListWriter(writer: ListWriter, binding: ObjectType, allocator: BufferAllocator)
      extends ArrowAttributeWriter {
    val subWriter = toListWriter(writer, binding, allocator)
    override def apply(value: AnyRef): Unit = if (value != null) {
      writer.startList()
      value.asInstanceOf[java.util.List[AnyRef]].foreach(subWriter)
      writer.endList()
    }
  }

  class ArrowMapWriter(writer: MapWriter, keyBinding: ObjectType, valueBinding: ObjectType, allocator: BufferAllocator)
      extends ArrowAttributeWriter {
    val keyWriter   = toMapWriter(writer, "k", keyBinding, allocator)
    val valueWriter = toMapWriter(writer, "v", valueBinding, allocator)
    override def apply(value: AnyRef): Unit = if (value != null) {
      writer.start()
      value.asInstanceOf[java.util.Map[AnyRef, AnyRef]].foreach { case (k, v) =>
        keyWriter(k)
        valueWriter(v)
      }
      writer.end()
    }
  }

  class ArrowBytesWriter(writer: VarBinaryWriter, allocator: BufferAllocator) extends ArrowAttributeWriter {
    override def apply(value: AnyRef): Unit = if (value != null) {
      val bytes = value.asInstanceOf[Array[Byte]]
      val buffer = allocator.buffer(bytes.length)
      buffer.setBytes(0, bytes)
      writer.writeVarBinary(0, bytes.length, buffer)
      buffer.close()
    }
  }

  // TODO maps/lists not fully implemented
  // TODO close allocated buffers

  private def toListWriter(writer: ListWriter, binding: ObjectType, allocator: BufferAllocator): (AnyRef) => Unit = {
    if (binding == ObjectType.STRING || binding == ObjectType.JSON || binding == ObjectType.UUID) {
      (value: AnyRef) => if (value != null) {
        val bytes = value.toString.getBytes(StandardCharsets.UTF_8)
        val buffer = allocator.buffer(bytes.length)
        buffer.setBytes(0, bytes)
        writer.varChar().writeVarChar(0, bytes.length, buffer)
      }
    } else if (binding == ObjectType.INT) {
      (value: AnyRef) => if (value != null) {
        writer.integer().writeInt(value.asInstanceOf[Int])
      }
    } else if (binding == ObjectType.LONG) {
      (value: AnyRef) => if (value != null) {
        writer.bigInt().writeBigInt(value.asInstanceOf[Long])
      }
    } else if (binding == ObjectType.FLOAT) {
      (value: AnyRef) => if (value != null) {
        writer.float4().writeFloat4(value.asInstanceOf[Float])
      }
    } else if (binding == ObjectType.DOUBLE) {
      (value: AnyRef) => if (value != null) {
        writer.float8().writeFloat8(value.asInstanceOf[Double])
      }
    } else if (binding == ObjectType.BOOLEAN) {
      (value: AnyRef) => if (value != null) {
        writer.bit().writeBit(if (value.asInstanceOf[Boolean]) { 1 } else { 0 })
      }
    } else if (binding == ObjectType.DATE) {
      (value: AnyRef) => if (value != null) {
        writer.date().writeDate(value.asInstanceOf[Date].getTime)
      }
    } else if (binding == ObjectType.GEOMETRY) {
      (value: AnyRef) => if (value != null) {
        val bytes = WKTUtils.write(value.asInstanceOf[Geometry]).getBytes(StandardCharsets.UTF_8)
        val buffer = allocator.buffer(bytes.length)
        buffer.setBytes(0, bytes)
        writer.varChar().writeVarChar(0, bytes.length, buffer)
      }
    } else if (binding == ObjectType.BYTES) {
      (value: AnyRef) => if (value != null) {
        val bytes = value.asInstanceOf[Array[Byte]]
        val buffer = allocator.buffer(bytes.length)
        buffer.setBytes(0, bytes)
        writer.varBinary().writeVarBinary(0, bytes.length, buffer)
      }
    } else {
      throw new IllegalArgumentException(s"Unexpected list object type $binding")
    }
  }

  private def toMapWriter(writer: MapWriter, key: String, binding: ObjectType, allocator: BufferAllocator): (AnyRef) => Unit = {
    if (binding == ObjectType.STRING || binding == ObjectType.JSON || binding == ObjectType.UUID) {
      val subWriter = writer.varChar(key)
      (value: AnyRef) => if (value != null) {
        val bytes = value.toString.getBytes(StandardCharsets.UTF_8)
        val buffer = allocator.buffer(bytes.length)
        buffer.setBytes(0, bytes)
        subWriter.writeVarChar(0, bytes.length, buffer)
      }
    } else if (binding == ObjectType.INT) {
      val subWriter = writer.integer(key)
      (value: AnyRef) => if (value != null) {
        subWriter.writeInt(value.asInstanceOf[Int])
      }
    } else if (binding == ObjectType.LONG) {
      val subWriter = writer.bigInt(key)
      (value: AnyRef) => if (value != null) {
        subWriter.writeBigInt(value.asInstanceOf[Long])
      }
    } else if (binding == ObjectType.FLOAT) {
      val subWriter = writer.float4(key)
      (value: AnyRef) => if (value != null) {
        subWriter.writeFloat4(value.asInstanceOf[Float])
      }
    } else if (binding == ObjectType.DOUBLE) {
      val subWriter = writer.float8(key)
      (value: AnyRef) => if (value != null) {
        subWriter.writeFloat8(value.asInstanceOf[Double])
      }
    } else if (binding == ObjectType.BOOLEAN) {
      val subWriter = writer.bit(key)
      (value: AnyRef) => if (value != null) {
        subWriter.writeBit(if (value.asInstanceOf[Boolean]) { 1 } else { 0 })
      }
    } else if (binding == ObjectType.DATE) {
      val subWriter = writer.date(key)
      (value: AnyRef) => if (value != null) {
        subWriter.writeDate(value.asInstanceOf[Date].getTime)
      }
    } else if (binding == ObjectType.GEOMETRY) {
      val subWriter = writer.varChar(key)
      (value: AnyRef) => if (value != null) {
        val bytes = WKTUtils.write(value.asInstanceOf[Geometry]).getBytes(StandardCharsets.UTF_8)
        val buffer = allocator.buffer(bytes.length)
        buffer.setBytes(0, bytes)
        subWriter.writeVarChar(0, bytes.length, buffer)
      }
    } else if (binding == ObjectType.BYTES) {
      val subWriter = writer.varBinary(key)
      (value: AnyRef) => if (value != null) {
        val bytes = value.asInstanceOf[Array[Byte]]
        val buffer = allocator.buffer(bytes.length)
        buffer.setBytes(0, bytes)
        subWriter.writeVarBinary(0, bytes.length, buffer)
      }
    } else {
      throw new IllegalArgumentException(s"Unexpected list object type $binding")
    }
  }
}
