/***********************************************************************
 * Copyright (c) 2013-2017 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.index.filters

import org.joda.time.format.ISOPeriodFormat
import org.joda.time.{DateTime, Period}
import org.opengis.feature.simple.SimpleFeatureType

/**
  * Age off a feature based on the key timestamp
  */
trait AgeOffFilter extends AbstractFilter {

  protected var expiry: Long = -1L

  override def init(options: Map[String, String]): Unit = {
    import AgeOffFilter.Configuration.ExpiryOpt
    expiry = DateTime.now().minus(AgeOffFilter.format.parsePeriod(options(ExpiryOpt))).getMillis
  }

  override def accept(row: Array[Byte],
                      rowOffset: Int,
                      rowLength: Int,
                      value: Array[Byte],
                      valueOffset: Int,
                      valueLength: Int,
                      timestamp: Long): Boolean = timestamp > expiry
}

object AgeOffFilter {

  private val format = ISOPeriodFormat.standard()

  // configuration keys
  object Configuration {
    val ExpiryOpt = "retention"
  }

  def configure(sft: SimpleFeatureType, expiry: Period): Map[String, String] = {
    import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType
    require(!sft.isTableSharing, "AgeOff filter can only be applied to features that don't use table sharing")
    Map(Configuration.ExpiryOpt -> format.print(expiry))
  }
}
