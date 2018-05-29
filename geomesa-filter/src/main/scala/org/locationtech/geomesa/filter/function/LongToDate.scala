/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.filter.function

import java.time.Instant
import java.util.Date

import org.geotools.filter.FunctionExpressionImpl
import org.geotools.filter.capability.FunctionNameImpl
import org.geotools.filter.capability.FunctionNameImpl._

class LongToDate extends FunctionExpressionImpl(LongToDate.Name) {
  override def evaluate(f: java.lang.Object): AnyRef = {
    getExpression(0).evaluate(f) match {
      case str: String =>
        Date.from(Instant.ofEpochSecond(str.toLong))
      case long: java.lang.Long =>
        Date.from(Instant.ofEpochSecond(long))
      case integer: java.lang.Integer =>
        Date.from(Instant.ofEpochSecond(integer.toLong))
      case _ =>
        null
    }
  }

}

object LongToDate {
  val Name = new FunctionNameImpl(
    "longToDate",
    classOf[java.util.Date],
    parameter("arg", classOf[java.lang.Object])
  )
}
