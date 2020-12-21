/***********************************************************************
 * Copyright (c) 2013-2020 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.spark.jts.rules

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.LeafExpression
import org.apache.spark.sql.catalyst.expressions.codegen.CodegenFallback
import org.apache.spark.sql.jts.JTSTypes._
import org.apache.spark.sql.types.DataType
import org.locationtech.jts.geom._

/**
 * Catalyst AST expression used during rule rewriting to extract geometry literal values
 * from Catalyst memory and keep a copy in JVM heap space for subsequent use in rule evaluation.
 */
abstract class GeometryLiteral extends LeafExpression with CodegenFallback {
  def geom: Geometry
  def repr: Any
  override def foldable: Boolean = true
  override def nullable: Boolean = true
  override def eval(input: InternalRow): Any = repr
}

object GeometryLiteral {

  def unapply(g: GeometryLiteral): Option[(Any, Geometry)] = Some(g.repr, g.geom)

  case class PointLiteral(geom: Point, repr: InternalRow) extends GeometryLiteral {
    override def dataType: DataType = PointUDT
  }

  object PointLiteral {
    def apply(repr: InternalRow): PointLiteral = PointLiteral(PointUDT.deserialize(repr), repr)
  }

  case class LineStringLiteral(geom: LineString, repr: InternalRow) extends GeometryLiteral {
    override def dataType: DataType = LineStringUDT
  }

  object LineStringLiteral {
    def apply(repr: InternalRow): LineStringLiteral =
      LineStringLiteral(LineStringUDT.deserialize(repr), repr)
  }

  case class PolygonLiteral(geom: Polygon, repr: InternalRow) extends GeometryLiteral {
    override def dataType: DataType = PolygonUDT
  }

  object PolygonLiteral {
    def apply(repr: InternalRow): PolygonLiteral =
      PolygonLiteral(PolygonUDT.deserialize(repr), repr)
  }

  case class GenericGeometryLiteral(geom: Geometry, repr: Array[Byte]) extends GeometryLiteral {
    override def dataType: DataType = GeometryUDT
  }

  object GenericGeometryLiteral {
    def apply(repr: Array[Byte]): GenericGeometryLiteral =
      GenericGeometryLiteral(GeometryUDT.deserialize(repr), repr)
  }

  case class MultiPointLiteral(geom: MultiPoint, repr: InternalRow) extends GeometryLiteral {
    override def dataType: DataType = MultiPointUDT
  }

  object MultiPointLiteral {
    def apply(repr: InternalRow): MultiPointLiteral =
      MultiPointLiteral(MultiPointUDT.deserialize(repr), repr)
  }

  case class MultiLineStringLiteral(geom: MultiLineString, repr: InternalRow) extends GeometryLiteral {
    override def dataType: DataType = MultiLineStringUDT
  }

  object MultiLineStringLiteral {
    def apply(repr: InternalRow): MultiLineStringLiteral =
      MultiLineStringLiteral(MultiLineStringUDT.deserialize(repr), repr)
  }

  case class MultiPolygonLiteral(geom: MultiPolygon, repr: InternalRow) extends GeometryLiteral {
    override def dataType: DataType = MultiPolygonUDT
  }

  object MultiPolygonLiteral {
    def apply(repr: InternalRow): MultiPolygonLiteral =
      MultiPolygonLiteral(MultiPolygonUDT.deserialize(repr), repr)
  }

  case class GeometryCollectionLiteral(geom: GeometryCollection, repr: InternalRow) extends GeometryLiteral {
    override def dataType: DataType = GeometryCollectionUDT
  }

  object GeometryCollectionLiteral {
    def apply(repr: InternalRow): GeometryCollectionLiteral =
      GeometryCollectionLiteral(GeometryCollectionUDT.deserialize(repr), repr)
  }
}

