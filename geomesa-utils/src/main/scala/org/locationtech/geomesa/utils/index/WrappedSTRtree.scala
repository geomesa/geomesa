/***********************************************************************
 * Copyright (c) 2013-2019 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.utils.index

import org.locationtech.jts.index.strtree.STRtree

class WrappedSTRtree[T](nodeCapacity:Int) extends WrapperIndex[T,STRtree](
  indexBuider = () => new STRtree(nodeCapacity)
) with Serializable {

  def this()
  {
    this(10)
  }

  override def size(): Int = index.size()

}