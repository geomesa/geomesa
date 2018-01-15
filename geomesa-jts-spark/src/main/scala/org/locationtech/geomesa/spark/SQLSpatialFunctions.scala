/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.spark

import java.awt.geom.AffineTransform

import com.vividsolutions.jts.geom._
import com.vividsolutions.jts.operation.distance.DistanceOp
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.udaf.ConvexHull
import org.apache.spark.sql.functions.udf
import SQLFunctionHelper.nullableUDF
import org.geotools.geometry.jts.{JTS, JTSFactoryFinder}
import org.geotools.referencing.GeodeticCalculator
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.geotools.referencing.operation.transform.AffineTransform2D

object SQLSpatialFunctions {
  import java.{lang => jl}

  // Geometry editors
  val ST_Translate: (Geometry, Double, Double) => Geometry =
    (g, deltaX, deltaY) => translate(g, deltaX, deltaY)

  // Spatial relationships
  // DE-9IM relations
  val ST_Contains:   (Geometry, Geometry) => jl.Boolean = nullableUDF((geom1, geom2) => geom1.contains(geom2))
  val ST_Covers:     (Geometry, Geometry) => jl.Boolean = nullableUDF((geom1, geom2) => geom1.covers(geom2))
  val ST_Crosses:    (Geometry, Geometry) => jl.Boolean = nullableUDF((geom1, geom2) => geom1.crosses(geom2))
  val ST_Disjoint:   (Geometry, Geometry) => jl.Boolean = nullableUDF((geom1, geom2) => geom1.disjoint(geom2))
  val ST_Equals:     (Geometry, Geometry) => jl.Boolean = nullableUDF((geom1, geom2) => geom1.equals(geom2))
  val ST_Intersects: (Geometry, Geometry) => jl.Boolean = nullableUDF((geom1, geom2) => geom1.intersects(geom2))
  val ST_Overlaps:   (Geometry, Geometry) => jl.Boolean = nullableUDF((geom1, geom2) => geom1.overlaps(geom2))
  val ST_Touches:    (Geometry, Geometry) => jl.Boolean = nullableUDF((geom1, geom2) => geom1.touches(geom2))
  val ST_Within:     (Geometry, Geometry) => jl.Boolean = nullableUDF((geom1, geom2) => geom1.within(geom2))
  val ST_Relate:     (Geometry, Geometry) => String = nullableUDF((geom1, geom2) => geom1.relate(geom2).toString)
  val ST_RelateBool: (Geometry, Geometry, String) => Boolean =
    nullableUDF((geom1, geom2, pattern) => geom1.relate(geom2, pattern))

  val ST_Area: Geometry => jl.Double = nullableUDF(g => g.getArea)
  val ST_Centroid: Geometry => Point = nullableUDF(g => g.getCentroid)
  val ST_ClosestPoint: (Geometry, Geometry) => Point =
    nullableUDF((g1, g2) => closestPoint(g1, g2))
  val ST_Distance: (Geometry, Geometry) => jl.Double =
    nullableUDF((g1, g2) => g1.distance(g2))
  val ST_DistanceSpheroid: (Geometry, Geometry) => jl.Double =
    nullableUDF((s, e) => fastDistance(s.getCoordinate, e.getCoordinate))
  val ST_Length: Geometry => jl.Double = nullableUDF(g => g.getLength)

  // Assumes input is two points, for use with collect_list and window functions
  val ST_AggregateDistanceSpheroid: Seq[Geometry] => jl.Double = a => ST_DistanceSpheroid(a(0), a(1))

  val ST_LengthSpheroid: LineString => jl.Double =
    nullableUDF(line => line.getCoordinates.sliding(2).map { case Array(l, r) => fastDistance(l, r) }.sum)

  implicit def st_translate = udf(ST_Translate)
  implicit def st_contains = udf(ST_Contains)
  implicit def st_covers = udf(ST_Covers)
  implicit def st_crosses = udf(ST_Crosses)
  implicit def st_disjoint = udf(ST_Disjoint)
  implicit def st_equals = udf(ST_Equals)
  implicit def st_intersects = udf(ST_Intersects)
  implicit def st_overlaps = udf(ST_Overlaps)
  implicit def st_touches = udf(ST_Touches)
  implicit def st_within = udf(ST_Within)
  implicit def st_relate = udf(ST_Relate)
  implicit def st_relateBool = udf(ST_RelateBool)
  implicit def st_area = udf(ST_Area)
  implicit def st_centroid = udf(ST_Centroid)
  implicit def st_closestPoint = udf(ST_ClosestPoint)
  implicit def st_cistance = udf(ST_Distance)
  implicit def st_distanceSpheroid = udf(ST_DistanceSpheroid)
  implicit def st_length = udf(ST_Length)
  implicit def st_aggregateDistanceSpheroid = udf(ST_AggregateDistanceSpheroid)
  implicit def st_lengthSpheroid = udf(ST_LengthSpheroid)

  // Geometry Processing
  val ch = new ConvexHull

  def registerFunctions(sqlContext: SQLContext): Unit = {
    // Register geometry editors
    sqlContext.udf.register("st_translate", ST_Translate)

    // Register spatial relationships
    sqlContext.udf.register("st_contains"    , ST_Contains)
    sqlContext.udf.register("st_covers"      , ST_Covers)
    sqlContext.udf.register("st_crosses"     , ST_Crosses)
    sqlContext.udf.register("st_disjoint"    , ST_Disjoint)
    sqlContext.udf.register("st_equals"      , ST_Equals)
    sqlContext.udf.register("st_intersects"  , ST_Intersects)
    sqlContext.udf.register("st_overlaps"    , ST_Overlaps)
    sqlContext.udf.register("st_touches"     , ST_Touches)
    sqlContext.udf.register("st_within"      , ST_Within)
    // renamed st_relate variant that returns a boolean since
    // Spark SQL doesn't seem to support polymorphic UDFs
    sqlContext.udf.register("st_relate"      , ST_Relate)
    sqlContext.udf.register("st_relateBool"  , ST_RelateBool)

    sqlContext.udf.register("st_area"            , ST_Area)
    sqlContext.udf.register("st_closestpoint"    , ST_ClosestPoint)
    sqlContext.udf.register("st_centroid"        , ST_Centroid)
    sqlContext.udf.register("st_distance"        , ST_Distance)
    sqlContext.udf.register("st_length"          , ST_Length)

    sqlContext.udf.register("st_distanceSpheroid", ST_DistanceSpheroid)
    sqlContext.udf.register("st_aggregateDistanceSpheroid"  , ST_AggregateDistanceSpheroid)
    sqlContext.udf.register("st_lengthSpheroid"  , ST_LengthSpheroid)

    // Register geometry Processing
    sqlContext.udf.register("st_convexhull", ch)
  }

  @transient private val geoCalcs = new ThreadLocal[GeodeticCalculator] {
    override def initialValue(): GeodeticCalculator = new GeodeticCalculator(DefaultGeographicCRS.WGS84)
  }
  @transient val geomFactory = JTSFactoryFinder.getGeometryFactory

  def closestPoint(g1: Geometry, g2: Geometry): Point = {
    val op = new DistanceOp(g1, g2)
    val coord = op.nearestPoints()
    geomFactory.createPoint(coord(0))
  }

  def fastDistance(c1: Coordinate, c2: Coordinate): Double = {
    val calc = geoCalcs.get()
    calc.setStartingGeographicPoint(c1.x, c1.y)
    calc.setDestinationGeographicPoint(c2.x, c2.y)
    calc.getOrthodromicDistance
  }

  def translate(g: Geometry, deltax: Double, deltay: Double): Geometry = {
    val affineTransform = AffineTransform.getTranslateInstance(deltax, deltay)
    val transform = new AffineTransform2D(affineTransform)
    JTS.transform(g, transform)
  }
}
