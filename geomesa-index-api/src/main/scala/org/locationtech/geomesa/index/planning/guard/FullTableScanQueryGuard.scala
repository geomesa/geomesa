/***********************************************************************
 * Copyright (c) 2013-2020 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.index.planning.guard

import org.geotools.data.{DataStore, Query}
import org.locationtech.geomesa.filter.filterToString
import org.locationtech.geomesa.index.api._
import org.locationtech.geomesa.index.conf.QueryHints.RichHints
import org.locationtech.geomesa.index.conf.QueryProperties
import org.locationtech.geomesa.index.planning.QueryInterceptor
import org.opengis.feature.simple.SimpleFeatureType

class FullTableScanQueryGuard extends QueryInterceptor {
  override def guard(strategy: QueryStrategy): Option[IllegalArgumentException] = {
    if (strategy.values.isEmpty && strategy.hints.getMaxFeatures.forall(_ > QueryProperties.BlockMaxThreshold.toInt.get)) {
      Some(new IllegalArgumentException(s"The query ${filterToString(strategy.filter.filter)} " +
        s"would lead to a full-table scan and has been stopped."))
    } else {
      None
    }
  }

  override def init(ds: DataStore, sft: SimpleFeatureType): Unit = { }

  override def rewrite(query: Query): Unit = { }

  override def close(): Unit = { }
}
