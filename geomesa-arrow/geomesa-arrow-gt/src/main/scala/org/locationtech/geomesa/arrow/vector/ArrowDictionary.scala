/***********************************************************************
* Copyright (c) 2013-2017 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.arrow.vector

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

import com.google.common.collect.ImmutableBiMap
import org.apache.arrow.vector.types.pojo.{ArrowType, DictionaryEncoding}

class ArrowDictionary(val values: Seq[AnyRef], val id: Long = ArrowDictionary.nextId) {

  lazy private val (map, inverse) = {
    val builder = ImmutableBiMap.builder[AnyRef, Int]
    var i = 0
    values.foreach { value =>
      builder.put(value, i)
      i += 1
    }
    val m = builder.build()
    (m, m.inverse())
  }

  lazy val encoding: DictionaryEncoding = {
    // TODO smaller encoding
    if (values.length < Byte.MaxValue) {
      new DictionaryEncoding(id, false, new ArrowType.Int(8, true))
    } else if (values.length < Short.MaxValue) {
      new DictionaryEncoding(id, false, new ArrowType.Int(16, true))
    } else {
      new DictionaryEncoding(id, false, new ArrowType.Int(32, true))
    }
  }

  def index(value: AnyRef): Int = map.get(value)

  def lookup(i: Int): AnyRef = inverse.get(i)
}

object ArrowDictionary {
  private val values = new SecureRandom().longs(0, Long.MaxValue).iterator()
  private val ids = new AtomicLong(values.next)

  def nextId: Long = ids.getAndSet(values.next)
}
