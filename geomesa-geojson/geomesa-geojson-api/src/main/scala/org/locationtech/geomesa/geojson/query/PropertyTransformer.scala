/***********************************************************************
 * Copyright (c) 2013-2021 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.geojson.query

trait PropertyTransformer {
  def useFid(prop: String): Boolean

  def transform(prop: String): String
}

object PropertyTransformer {
  val empty = new PropertyTransformer {
    override def useFid(prop: String): Boolean = false

    override def transform(prop: String): String = prop
  }
}