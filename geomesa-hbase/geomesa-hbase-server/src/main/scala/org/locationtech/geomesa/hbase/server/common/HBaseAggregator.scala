/***********************************************************************
 * Copyright (c) 2013-2020 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.hbase.server.common

import org.apache.hadoop.hbase.Cell
import org.apache.hadoop.hbase.regionserver.RegionScanner
import org.locationtech.geomesa.index.iterators.AggregatingScan
import org.locationtech.geomesa.index.iterators.AggregatingScan.RowValue

/**
 * HBase mixin for aggregating scans
 *
 * @tparam T aggregate result type
 */
trait HBaseAggregator[T <: AggregatingScan.Result] extends AggregatingScan[T] {

  private val results = new java.util.ArrayList[Cell]
  private var scanner: RegionScanner = _
  private var more: Boolean = false
  private var iter: java.util.Iterator[Cell] = _
  private var lastscanned: Array[Byte] = _

  def setScanner(scanner: RegionScanner): Unit = {
    this.scanner = scanner
    results.clear()
    lastscanned = null
    more = scanner.next(results)
    iter = results.iterator()
  }

  def getLastScanned: Array[Byte] = lastscanned

  override protected def hasNextData: Boolean = iter.hasNext || more && {
    results.clear()
    more = scanner.next(results)
    iter = results.iterator()
    hasNextData
  }

  override protected def nextData(): RowValue = {
    val cell = iter.next()
    lastscanned = cell.getRowArray
    RowValue(cell.getRowArray, cell.getRowOffset, cell.getRowLength,
      cell.getValueArray, cell.getValueOffset, cell.getValueLength)
  }
}
