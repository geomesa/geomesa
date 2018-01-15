/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.spark

import java.{lang => jl}

import com.vividsolutions.jts.geom._
import SQLFunctionHelper.nullableUDF
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.functions.udf

object SQLSpatialAccessorFunctions {
  val ST_Boundary: Geometry => Geometry = nullableUDF(geom => geom.getBoundary)
  val ST_CoordDim: Geometry => Int = nullableUDF(geom => {
    val coord = geom.getCoordinate
    if (coord.z.isNaN) 2 else 3
  })
  val ST_Dimension: Geometry => Int = nullableUDF(geom => geom.getDimension)
  val ST_Envelope: Geometry => Geometry = nullableUDF(geom => geom.getEnvelope)
  val ST_ExteriorRing: Geometry => LineString = {
    case geom: Polygon => geom.getExteriorRing
    case _ => null
  }
  val ST_GeometryN: (Geometry, Int) => Geometry = nullableUDF((geom, n) => geom.getGeometryN(n-1))
  val ST_GeometryType: Geometry => String = nullableUDF(geom => geom.getGeometryType)
  val ST_InteriorRingN: (Geometry, Int) => Geometry = nullableUDF((geom, n) => {
    geom match {
      case geom: Polygon =>
        if (0 < n && n <= geom.getNumInteriorRing) {
          geom.getInteriorRingN(n-1)
        } else {
          null
        }
      case _ => null
    }
  })
  val ST_IsClosed: Geometry => jl.Boolean = nullableUDF({
    case geom: LineString => geom.isClosed
    case geom: MultiLineString => geom.isClosed
    case _ => true
  })
  val ST_IsCollection: Geometry => jl.Boolean = nullableUDF(geom => geom.isInstanceOf[GeometryCollection])
  val ST_IsEmpty: Geometry => jl.Boolean = nullableUDF(geom => geom.isEmpty)
  val ST_IsRing: Geometry => jl.Boolean = nullableUDF({
    case geom: LineString => geom.isClosed && geom.isSimple
    case geom: MultiLineString => geom.isClosed && geom.isSimple
    case geom => geom.isSimple
  })
  val ST_IsSimple: Geometry => jl.Boolean = nullableUDF(geom => geom.isSimple)
  val ST_IsValid: Geometry => jl.Boolean = nullableUDF(geom => geom.isValid)
  val ST_NumGeometries: Geometry => Int = nullableUDF(geom => geom.getNumGeometries)
  val ST_NumPoints: Geometry => Int = nullableUDF(geom => geom.getNumPoints)
  val ST_PointN: (Geometry, Int) => Point = nullableUDF((geom, n) => {
    geom match {
      case geom: LineString =>
        if (n < 0) {
          geom.getPointN(n + geom.getLength.toInt)
        } else {
          geom.getPointN(n-1)
        }
      case _ => null
    }
  })
  val ST_X: Geometry => jl.Float = {
    case geom: Point => geom.getX.toFloat
    case _ => null
  }
  val ST_Y: Geometry => jl.Float = {
    case geom: Point => geom.getY.toFloat
    case _ => null
  }

  implicit def st_boundary = udf(ST_Boundary)
  implicit def st_coordDim = udf(ST_CoordDim)
  implicit def st_dimension = udf(ST_Dimension)
  implicit def st_envelope = udf(ST_Envelope)
  implicit def st_exteriorRing = udf(ST_ExteriorRing)
  implicit def st_geometryN = udf(ST_GeometryN)
  implicit def st_geometryType = udf(ST_GeometryType)
  implicit def st_interiorRingN = udf(ST_InteriorRingN)
  implicit def st_isClosed = udf(ST_IsClosed)
  implicit def st_isCollection = udf(ST_IsCollection)
  implicit def st_isEmpty = udf(ST_IsEmpty)
  implicit def st_isRing = udf(ST_IsRing)
  implicit def st_isSimple = udf(ST_IsSimple)
  implicit def st_isValid = udf(ST_IsValid)
  implicit def st_numGeometries = udf(ST_NumGeometries)
  implicit def st_numPoints = udf(ST_NumPoints)
  implicit def st_pointN = udf(ST_PointN)
  implicit def st_x = udf(ST_X)
  implicit def st_y = udf(ST_Y)

  def registerFunctions(sqlContext: SQLContext): Unit = {
    sqlContext.udf.register("st_boundary"      , ST_Boundary)
    sqlContext.udf.register("st_coordDim"      , ST_CoordDim)
    sqlContext.udf.register("st_dimension"     , ST_Dimension)
    sqlContext.udf.register("st_envelope"      , ST_Envelope)
    sqlContext.udf.register("st_exteriorRing"  , ST_ExteriorRing)
    sqlContext.udf.register("st_geometryN"     , ST_GeometryN)
    sqlContext.udf.register("st_geometryType"  , ST_GeometryType)
    sqlContext.udf.register("st_interiorRingN" , ST_InteriorRingN)
    sqlContext.udf.register("st_isClosed"      , ST_IsClosed)
    sqlContext.udf.register("st_isCollection"  , ST_IsCollection)
    sqlContext.udf.register("st_isEmpty"       , ST_IsEmpty)
    sqlContext.udf.register("st_isRing"        , ST_IsRing)
    sqlContext.udf.register("st_isSimple"      , ST_IsSimple)
    sqlContext.udf.register("st_isValid"       , ST_IsValid)
    sqlContext.udf.register("st_numGeometries" , ST_NumGeometries)
    sqlContext.udf.register("st_numPoints"     , ST_NumPoints)
    sqlContext.udf.register("st_pointN"        , ST_PointN)
    sqlContext.udf.register("st_x"             , ST_X)
    sqlContext.udf.register("st_y"             , ST_Y)
  }
}
