/***********************************************************************
  * Copyright (c) 2013-2015 Commonwealth Computer Research, Inc.
  * All rights reserved. This program and the accompanying materials
  * are made available under the terms of the Apache License, Version 2.0 which
  * accompanies this distribution and is available at
  * http://www.opensource.org/licenses/apache2.0.php.
  *************************************************************************/

package org.locationtech.geomesa.accumulo.iterators

import java.util.{Map => JMap}

import com.typesafe.scalalogging.LazyLogging
import org.apache.accumulo.core.client.IteratorSetting
import org.apache.accumulo.core.data.{Key, Value}
import org.apache.accumulo.core.iterators.{IteratorEnvironment, SortedKeyValueIterator}
import org.geotools.data.Query
import org.geotools.feature.simple.SimpleFeatureBuilder
import org.locationtech.geomesa.accumulo.index.QueryHints._
import org.locationtech.geomesa.accumulo.index.QueryPlanner.SFIter
import org.locationtech.geomesa.accumulo.iterators.FeatureAggregatingIterator.Result
import org.locationtech.geomesa.accumulo.sumNumericValueMutableMaps
import org.locationtech.geomesa.accumulo.util.CloseableIterator
import org.locationtech.geomesa.features.ScalaSimpleFeatureFactory
import org.locationtech.geomesa.utils.geotools.{GeometryUtils, SimpleFeatureTypes}
import org.opengis.feature.simple.SimpleFeatureType

import scala.collection.mutable
import scala.languageFeature.implicitConversions
import scala.collection.JavaConverters._

@deprecated
class MapAggregatingIterator(other: MapAggregatingIterator, env: IteratorEnvironment)
  extends FeatureAggregatingIterator[MapAggregatingIteratorResult](other, env) {

  var mapAttribute: String = null

  def this() = this(null, null)

  override def initProjectedSFTDefClassSpecificVariables(source: SortedKeyValueIterator[Key, Value],
                                                         options: JMap[String, String],
                                                         env: IteratorEnvironment): Unit = {

    mapAttribute = options.get(MapAggregatingIterator.MAP_ATTRIBUTE)
    projectedSFTDef = MapAggregatingIterator.projectedSFTDef(mapAttribute, simpleFeatureType)
  }

  override def handleKeyValue(resultO: Option[MapAggregatingIteratorResult],
                              topSourceKey: Key,
                              topSourceValue: Value): MapAggregatingIteratorResult = {

    val feature = originalDecoder.deserialize(topSourceValue.get)
    val currCounts = feature.getAttribute(mapAttribute).asInstanceOf[JMap[AnyRef, Int]].asScala

    val result = resultO.getOrElse(MapAggregatingIteratorResult(mapAttribute))
    sumNumericValueMutableMaps(Seq(currCounts), result.countMap)
    result
  }
}

@deprecated
object MapAggregatingIterator extends LazyLogging {

  val MAP_ATTRIBUTE = "map_attribute"
  def projectedSFTDef(mapAttributeName: String, underlyingSFT: SimpleFeatureType) = {
    val mapAttributeSpec =
      SimpleFeatureTypes.AttributeSpecFactory.fromAttributeDescriptor(
        underlyingSFT,
        underlyingSFT.getDescriptor(mapAttributeName))

    s"${mapAttributeSpec.toSpec},geom:Point:srid=4326"
  }

  def configure(cfg: IteratorSetting, mapAttribute: String) =
    setMapAttribute(cfg, mapAttribute)

  def setMapAttribute(iterSettings: IteratorSetting, mapAttribute: String): Unit =
    iterSettings.addOption(MAP_ATTRIBUTE, mapAttribute)
}

@deprecated
case class MapAggregatingIteratorResult(mapAttributeName: String,
                                        countMap: mutable.Map[AnyRef, Int] = mutable.Map()) extends Result {
  override def addToFeature(sfb: SimpleFeatureBuilder): Unit =  {
    sfb.add(countMap.toMap)
    sfb.add(GeometryUtils.zeroPoint)
  }
}
