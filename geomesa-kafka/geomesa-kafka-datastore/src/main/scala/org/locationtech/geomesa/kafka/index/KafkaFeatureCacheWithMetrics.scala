/***********************************************************************
 * Copyright (c) 2013-2021 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php. 
 ***********************************************************************/

package org.locationtech.geomesa.kafka.index

import com.codahale.metrics.Gauge
import org.locationtech.geomesa.kafka.data.KafkaDataStore.IndexConfig
import org.locationtech.geomesa.kafka.index.FeatureStateFactory.FeatureState
import org.locationtech.geomesa.metrics.core.GeoMesaMetrics
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

class KafkaFeatureCacheWithMetrics(sft: SimpleFeatureType, config: IndexConfig, metrics: GeoMesaMetrics)
    extends KafkaFeatureCacheImpl(sft, config){

  metrics.register(sft.getTypeName, "index-size", new Gauge[Int] { override def getValue: Int = size() })

  private val updates     = metrics.meter(sft.getTypeName, "updates")
  private val removals    = metrics.meter(sft.getTypeName, "removals")
  private val expirations = metrics.meter(sft.getTypeName, "expirations")

  override def put(feature: SimpleFeature): Unit = {
    super.put(feature)
    updates.mark()
  }

  override def remove(id: String): Unit = {
    super.remove(id)
    removals.mark()
  }

  override def expire(featureState: FeatureState): Unit = {
    super.expire(featureState)
    expirations.mark()
  }
}
