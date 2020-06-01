/***********************************************************************
 * Copyright (c) 2013-2020 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.features.avro.serialization

import org.apache.avro.io.{Decoder, Encoder}
import org.geotools.util.factory.Hints
import org.locationtech.geomesa.features.serialization.HintKeySerialization
import org.locationtech.geomesa.utils.geotools.converters.FastConverter

object AvroUserDataSerialization {

  import scala.collection.JavaConverters._

  private lazy val HintsKeyClass = classOf[Hints.Key].getName

  def serialize(out: Encoder, map: java.util.Map[_ <: AnyRef, _ <: AnyRef]): Unit = {
    out.writeArrayStart()
    out.setItemCount(map.size)

    def writePair(value: Any): Unit = {
      val string = value match {
        case key: Hints.Key if HintKeySerialization.canSerialize(key) => HintKeySerialization.keyToId(key)
        case _ => FastConverter.convert(value, classOf[String])
      }
      if (string == null) {
        out.writeIndex(0)
        out.writeNull()
        out.writeIndex(0)
        out.writeNull()
      } else {
        out.writeIndex(1)
        out.writeString(value.getClass.getName)
        out.writeIndex(1)
        out.writeString(string)
      }
    }

    map.asScala.foreach { case (key, value) =>
      out.startItem()
      writePair(key)
      writePair(value)
    }

    out.writeArrayEnd()
  }

  def deserialize(in: Decoder): java.util.Map[AnyRef, AnyRef] = {
    val size = in.readArrayStart().toInt
    val map = new java.util.HashMap[AnyRef, AnyRef](size)
    deserializeWithSize(in, size, map)
    map
  }

  def deserialize(in: Decoder, map: java.util.Map[AnyRef, AnyRef]): Unit = {
    deserializeWithSize(in, in.readArrayStart().toInt, map)
  }

  private def deserializeWithSize(in: Decoder, size: Int, map: java.util.Map[AnyRef, AnyRef]): Unit = {
    def readPair(): AnyRef = {
      in.readIndex() match {
        case 0 => in.readNull(); in.readIndex(); in.readNull(); null
        case 1 =>
          val clas = in.readString()
          in.readIndex()
          val string = in.readString()
          if (clas == HintsKeyClass) {
            HintKeySerialization.idToKey(string)
          } else {
            FastConverter.convert(string, Class.forName(clas)).asInstanceOf[AnyRef]
          }
      }
    }
    var remaining = size
    while (remaining > 0) {
      val key = readPair()
      val value = readPair()
      map.put(key, value)
      remaining -= 1
      if (remaining == 0) {
        remaining = in.arrayNext().toInt
      }
    }
  }
}
