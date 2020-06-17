/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.arrow.vector;

import com.vividsolutions.jts.geom.*;
import com.vividsolutions.jts.io.WKTReader;
import com.vividsolutions.jts.io.WKTWriter;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.dictionary.DictionaryProvider;
import org.apache.arrow.vector.dictionary.DictionaryProvider.MapDictionaryProvider;
import org.apache.arrow.vector.stream.ArrowStreamReader;
import org.apache.arrow.vector.stream.ArrowStreamWriter;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

public class GeometryVectorTest {

  @Test
  public void testPoint() throws Exception {
    WKTReader wktReader = new WKTReader();
    WKTWriter wktWriter = new WKTWriter();

    String point1 = "POINT (0 20)";
    String point2 = "POINT (10 20)";
    String point3 = "POINT (30 20)";

    try (RootAllocator allocator = new RootAllocator(Long.MAX_VALUE);
         PointFloatVector floats = new PointFloatVector("points", allocator, null);
         PointVector doubles = new PointVector("points", allocator, null)) {

      Field floatField = floats.getVector().getField();
      Field doubleField = doubles.getVector().getField();

      floats.getWriter().set(0, (Point) wktReader.read(point1));
      floats.getWriter().set(1, (Point) wktReader.read(point2));
      floats.getWriter().set(3, (Point) wktReader.read(point3));
      floats.getWriter().setValueCount(4);

      doubles.getWriter().set(0, (Point) wktReader.read(point1));
      doubles.getWriter().set(1, (Point) wktReader.read(point2));
      doubles.getWriter().set(3, (Point) wktReader.read(point3));
      doubles.getWriter().setValueCount(4);

      Assert.assertEquals(4, floats.getReader().getValueCount());
      Assert.assertEquals(1, floats.getReader().getNullCount());
      Assert.assertEquals(point1, wktWriter.write(floats.getReader().get(0)));
      Assert.assertEquals(point2, wktWriter.write(floats.getReader().get(1)));
      Assert.assertEquals(point3, wktWriter.write(floats.getReader().get(3)));
      Assert.assertNull(floats.getReader().get(2));

      Assert.assertEquals(4, doubles.getReader().getValueCount());
      Assert.assertEquals(1, doubles.getReader().getNullCount());
      Assert.assertEquals(point1, wktWriter.write(doubles.getReader().get(0)));
      Assert.assertEquals(point2, wktWriter.write(doubles.getReader().get(1)));
      Assert.assertEquals(point3, wktWriter.write(doubles.getReader().get(3)));
      Assert.assertNull(doubles.getReader().get(2));

      // ensure field was created correctly up front

      Assert.assertEquals(floatField, floats.getVector().getField());
      Assert.assertEquals(floatField.getChildren(), PointFloatVector.fields);

      Assert.assertEquals(doubleField, doubles.getVector().getField());
      Assert.assertEquals(doubleField.getChildren(), PointVector.fields);

      // overwriting

      floats.getWriter().set(0, (Point) wktReader.read(point3));
      floats.getWriter().set(1, (Point) wktReader.read(point2));
      floats.getWriter().set(2, (Point) wktReader.read(point1));
      floats.getWriter().setValueCount(3);

      Assert.assertEquals(3, floats.getReader().getValueCount());
      Assert.assertEquals(0, floats.getReader().getNullCount());
      Assert.assertEquals(point3, wktWriter.write(floats.getReader().get(0)));
      Assert.assertEquals(point2, wktWriter.write(floats.getReader().get(1)));
      Assert.assertEquals(point1, wktWriter.write(floats.getReader().get(2)));
    }
  }

  @Test
  public void testPointTransfer() throws Exception {
    WKTReader wktReader = new WKTReader();
    WKTWriter wktWriter = new WKTWriter();

    String point1 = "POINT (0 20)";
    String point2 = "POINT (10 20)";
    String point3 = "POINT (30 20)";

    try (RootAllocator allocator = new RootAllocator(Long.MAX_VALUE);
         PointFloatVector from = new PointFloatVector("points", allocator, null);
         PointFloatVector to = new PointFloatVector("points", allocator, null)) {

      from.getWriter().set(0, (Point) wktReader.read(point1));
      from.getWriter().set(1, (Point) wktReader.read(point2));
      from.getWriter().set(3, (Point) wktReader.read(point3));
      from.getWriter().setValueCount(4);

      for (int i = 0; i < 4; i++) {
        from.transfer(i, i, to);
      }
      to.getWriter().setValueCount(4);

      for (PointFloatVector vector: Arrays.asList(from, to)) {
        Assert.assertEquals(4, vector.getReader().getValueCount());
        Assert.assertEquals(1, vector.getReader().getNullCount());
        Assert.assertEquals(point1, wktWriter.write(vector.getReader().get(0)));
        Assert.assertEquals(point2, wktWriter.write(vector.getReader().get(1)));
        Assert.assertEquals(point3, wktWriter.write(vector.getReader().get(3)));
        Assert.assertNull(vector.getReader().get(2));
      }

      from.getVector().clear();
      from.getWriter().set(1, (Point) wktReader.read(point1));
      from.getWriter().set(2, (Point) wktReader.read(point2));
      from.getWriter().set(3, (Point) wktReader.read(point3));
      from.getWriter().setValueCount(4);

      for (int i = 0; i < 4; i++) {
        from.transfer(i, i, to);
      }
      to.getWriter().setValueCount(4);

      for (PointFloatVector vector: Arrays.asList(from, to)) {
        Assert.assertEquals(4, vector.getReader().getValueCount());
        Assert.assertEquals(1, vector.getReader().getNullCount());
        Assert.assertEquals(point1, wktWriter.write(vector.getReader().get(1)));
        Assert.assertEquals(point2, wktWriter.write(vector.getReader().get(2)));
        Assert.assertEquals(point3, wktWriter.write(vector.getReader().get(3)));
        Assert.assertNull(vector.getReader().get(0));
      }
    }
  }

  @Test
  public void testLineString() throws Exception {
    WKTReader wktReader = new WKTReader();
    WKTWriter wktWriter = new WKTWriter();

    String line1 = "LINESTRING (30 10, 10 30, 40 40)";
    String line2 = "LINESTRING (40 10, 10 30)";
    String line3 = "LINESTRING (30 10, 10 30, 40 45, 55 60, 56 60)";

    try (RootAllocator allocator = new RootAllocator(Long.MAX_VALUE);
         LineStringFloatVector floats = new LineStringFloatVector("lines", allocator, null);
         LineStringVector doubles = new LineStringVector("lines", allocator, null)) {

      Field floatField = floats.getVector().getField();
      Field doubleField = doubles.getVector().getField();

      floats.getWriter().set(0, (LineString) wktReader.read(line1));
      floats.getWriter().set(1, (LineString) wktReader.read(line2));
      floats.getWriter().set(2, null);
      floats.getWriter().set(3, (LineString) wktReader.read(line3));
      floats.getWriter().setValueCount(4);

      doubles.getWriter().set(0, (LineString) wktReader.read(line1));
      doubles.getWriter().set(1, (LineString) wktReader.read(line2));
      doubles.getWriter().set(3, (LineString) wktReader.read(line3));
      doubles.getWriter().setValueCount(4);

      Assert.assertEquals(4, floats.getReader().getValueCount());
      Assert.assertEquals(1, floats.getReader().getNullCount());
      Assert.assertEquals(line1, wktWriter.write(floats.getReader().get(0)));
      Assert.assertEquals(line2, wktWriter.write(floats.getReader().get(1)));
      Assert.assertEquals(line3, wktWriter.write(floats.getReader().get(3)));
      Assert.assertNull(floats.getReader().get(2));

      Assert.assertEquals(4, doubles.getReader().getValueCount());
      Assert.assertEquals(1, doubles.getReader().getNullCount());
      Assert.assertEquals(line1, wktWriter.write(doubles.getReader().get(0)));
      Assert.assertEquals(line2, wktWriter.write(doubles.getReader().get(1)));
      Assert.assertEquals(line3, wktWriter.write(doubles.getReader().get(3)));
      Assert.assertNull(doubles.getReader().get(2));

      // ensure field was created correctly up front

      Assert.assertEquals(floatField, floats.getVector().getField());
      Assert.assertEquals(floatField.getChildren(), LineStringFloatVector.fields);

      Assert.assertEquals(doubleField, doubles.getVector().getField());
      Assert.assertEquals(doubleField.getChildren(), LineStringVector.fields);

      // loading/unloading
      try (LineStringFloatVector recovered = new LineStringFloatVector((ListVector) writeToFile(floats, allocator))) {
        Assert.assertEquals(4, recovered.getReader().getValueCount());
        Assert.assertEquals(1, recovered.getReader().getNullCount());
        Assert.assertEquals(line1, wktWriter.write(recovered.getReader().get(0)));
        Assert.assertEquals(line2, wktWriter.write(recovered.getReader().get(1)));
        Assert.assertEquals(line3, wktWriter.write(recovered.getReader().get(3)));
        Assert.assertNull(recovered.getReader().get(2));
      }

      // overwriting

      floats.getWriter().set(0, (LineString) wktReader.read(line3));
      floats.getWriter().set(1, (LineString) wktReader.read(line2));
      floats.getWriter().set(3, (LineString) wktReader.read(line1));
      floats.getWriter().setValueCount(3);

      Assert.assertEquals(3, floats.getReader().getValueCount());
      Assert.assertEquals(0, floats.getReader().getNullCount());
      Assert.assertEquals(line3, wktWriter.write(floats.getReader().get(0)));
      Assert.assertEquals(line2, wktWriter.write(floats.getReader().get(1)));
      Assert.assertEquals(line1, wktWriter.write(floats.getReader().get(3)));
    }
  }

  @Test
  public void testLineStringTransfer() throws Exception {
    WKTReader wktReader = new WKTReader();
    WKTWriter wktWriter = new WKTWriter();

    String line1 = "LINESTRING (30 10, 10 30, 40 40)";
    String line2 = "LINESTRING (40 10, 10 30)";
    String line3 = "LINESTRING (30 15, 10 30, 40 45, 55 60, 56 60)";

    try (RootAllocator allocator = new RootAllocator(Long.MAX_VALUE);
         LineStringFloatVector from = new LineStringFloatVector("lines", allocator, null);
         LineStringFloatVector to = new LineStringFloatVector("lines", allocator, null)) {

      from.getWriter().set(0, (LineString) wktReader.read(line1));
      from.getWriter().set(1, (LineString) wktReader.read(line2));
      from.getWriter().set(3, (LineString) wktReader.read(line3));
      from.getWriter().setValueCount(4);

      for (int i = 0; i < 4; i++) {
        from.transfer(i, i, to);
      }
      to.getWriter().setValueCount(4);

      for (LineStringFloatVector vector: Arrays.asList(from, to)) {
        Assert.assertEquals(4, vector.getReader().getValueCount());
        Assert.assertEquals(1, vector.getReader().getNullCount());
        Assert.assertEquals(line1, wktWriter.write(vector.getReader().get(0)));
        Assert.assertEquals(line2, wktWriter.write(vector.getReader().get(1)));
        Assert.assertEquals(line3, wktWriter.write(vector.getReader().get(3)));
        Assert.assertNull(vector.getReader().get(2));
      }

      // TODO calling clear seems to put the vector in an invalid state
      // from.getVector().clear();
      from.getWriter().set(0, null);
      from.getWriter().set(1, (LineString) wktReader.read(line1));
      from.getWriter().set(2, (LineString) wktReader.read(line2));
      from.getWriter().set(3, (LineString) wktReader.read(line3));
      from.getWriter().setValueCount(4);

      for (int i = 0; i < 4; i++) {
        from.transfer(i, i, to);
      }
      to.getWriter().setValueCount(4);

      for (LineStringFloatVector vector: Arrays.asList(from, to)) {
        Assert.assertEquals(4, vector.getReader().getValueCount());
        Assert.assertEquals(1, vector.getReader().getNullCount());
        Assert.assertEquals(line1, wktWriter.write(vector.getReader().get(1)));
        Assert.assertEquals(line2, wktWriter.write(vector.getReader().get(2)));
        Assert.assertEquals(line3, wktWriter.write(vector.getReader().get(3)));
        Assert.assertNull(vector.getReader().get(0));
      }
    }
  }

  @Test
  public void testPolygon() throws Exception {
    WKTReader wktReader = new WKTReader();
    WKTWriter wktWriter = new WKTWriter();

    String p0 = "POLYGON ((30 10, 40 40, 20 40, 10 20, 30 10))";
    String p1 = "POLYGON ((35 10, 45 45, 15 40, 10 20, 35 10), (20 30, 35 35, 30 20, 20 30))";
    String p2 = "POLYGON ((35 10, 45 45, 15 40, 10 20, 35 10), (20 30, 35 35, 30 20, 20 30), (19 36, 23 38, 22 34, 19 36))";
    try(RootAllocator allocator = new RootAllocator(Long.MAX_VALUE);
        PolygonFloatVector floats = new PolygonFloatVector("polys", allocator, null);
        PolygonVector doubles = new PolygonVector("polys", allocator, null)) {

      Field floatField = floats.getVector().getField();
      Field doubleField = doubles.getVector().getField();

      floats.getWriter().set(0, (Polygon) wktReader.read(p0));
      floats.getWriter().set(1, (Polygon) wktReader.read(p1));
      floats.getWriter().set(2, null);
      floats.getWriter().set(3, (Polygon) wktReader.read(p2));
      floats.getWriter().setValueCount(4);

      doubles.getWriter().set(0, (Polygon) wktReader.read(p0));
      doubles.getWriter().set(1, (Polygon) wktReader.read(p1));
      doubles.getWriter().set(3, (Polygon) wktReader.read(p2));
      doubles.getWriter().setValueCount(4);

      Assert.assertEquals(4, floats.getReader().getValueCount());
      Assert.assertEquals(1, floats.getReader().getNullCount());
      Assert.assertEquals(p0, wktWriter.write(floats.getReader().get(0)));
      Assert.assertEquals(p1, wktWriter.write(floats.getReader().get(1)));
      Assert.assertEquals(p2, wktWriter.write(floats.getReader().get(3)));
      Assert.assertNull(floats.getReader().get(2));

      Assert.assertEquals(4, doubles.getReader().getValueCount());
      Assert.assertEquals(1, doubles.getReader().getNullCount());
      Assert.assertEquals(p0, wktWriter.write(doubles.getReader().get(0)));
      Assert.assertEquals(p1, wktWriter.write(doubles.getReader().get(1)));
      Assert.assertEquals(p2, wktWriter.write(doubles.getReader().get(3)));
      Assert.assertNull(doubles.getReader().get(2));

      // ensure field was created correctly up front

      Assert.assertEquals(floatField, floats.getVector().getField());
      Assert.assertEquals(floatField.getChildren(), PolygonFloatVector.fields);

      Assert.assertEquals(doubleField, doubles.getVector().getField());
      Assert.assertEquals(doubleField.getChildren(), PolygonVector.fields);

      // overwriting

      floats.getWriter().set(0, (Polygon) wktReader.read(p2));
      floats.getWriter().set(1, (Polygon) wktReader.read(p1));
      floats.getWriter().set(2, (Polygon) wktReader.read(p0));
      floats.getWriter().setValueCount(3);

      Assert.assertEquals(3, floats.getReader().getValueCount());
      Assert.assertEquals(0, floats.getReader().getNullCount());
      Assert.assertEquals(p2, wktWriter.write(floats.getReader().get(0)));
      Assert.assertEquals(p1, wktWriter.write(floats.getReader().get(1)));
      Assert.assertEquals(p0, wktWriter.write(floats.getReader().get(2)));
    }
  }

  @Test
  public void testMultiLineString() throws Exception {
    WKTReader wktReader = new WKTReader();
    WKTWriter wktWriter = new WKTWriter();

    String mls0 = "MULTILINESTRING ((10 10, 20 20, 10 40), (40 40, 30 30, 40 20, 30 10))";
    String mls1 = "MULTILINESTRING ((10 10, 20 30, 10 40), (30 40, 30 30, 40 20, 20 10), (40 50, 40 40, 50 30, 30 20))";
    String mls2 = "MULTILINESTRING ((10 10, 20 40, 10 40))";
    try(RootAllocator allocator = new RootAllocator(Long.MAX_VALUE);
        MultiLineStringFloatVector floats = new MultiLineStringFloatVector("lines", allocator, null);
        MultiLineStringVector doubles = new MultiLineStringVector("lines", allocator, null)) {

      Field floatField = floats.getVector().getField();
      Field doubleField = doubles.getVector().getField();

      floats.getWriter().set(0, (MultiLineString) wktReader.read(mls0));
      floats.getWriter().set(1, (MultiLineString) wktReader.read(mls1));
      floats.getWriter().set(2, null);
      floats.getWriter().set(3, (MultiLineString) wktReader.read(mls2));
      floats.getWriter().setValueCount(4);

      doubles.getWriter().set(0, (MultiLineString) wktReader.read(mls0));
      doubles.getWriter().set(1, (MultiLineString) wktReader.read(mls1));
      doubles.getWriter().set(3, (MultiLineString) wktReader.read(mls2));
      doubles.getWriter().setValueCount(4);

      Assert.assertEquals(4, floats.getReader().getValueCount());
      Assert.assertEquals(1, floats.getReader().getNullCount());
      Assert.assertEquals(mls0, wktWriter.write(floats.getReader().get(0)));
      Assert.assertEquals(mls1, wktWriter.write(floats.getReader().get(1)));
      Assert.assertEquals(mls2, wktWriter.write(floats.getReader().get(3)));
      Assert.assertNull(floats.getReader().get(2));

      Assert.assertEquals(4, doubles.getReader().getValueCount());
      Assert.assertEquals(1, doubles.getReader().getNullCount());
      Assert.assertEquals(mls0, wktWriter.write(doubles.getReader().get(0)));
      Assert.assertEquals(mls1, wktWriter.write(doubles.getReader().get(1)));
      Assert.assertEquals(mls2, wktWriter.write(doubles.getReader().get(3)));
      Assert.assertNull(doubles.getReader().get(2));

      // ensure field was created correctly up front

      Assert.assertEquals(doubleField, doubles.getVector().getField());
      Assert.assertEquals(doubleField.getChildren(), MultiLineStringVector.fields);

      Assert.assertEquals(floatField, floats.getVector().getField());
      Assert.assertEquals(floatField.getChildren(), MultiLineStringFloatVector.fields);

      // overwriting

      floats.getWriter().set(0, (MultiLineString) wktReader.read(mls2));
      floats.getWriter().set(1, (MultiLineString) wktReader.read(mls1));
      floats.getWriter().set(2, (MultiLineString) wktReader.read(mls0));
      floats.getWriter().setValueCount(3);

      Assert.assertEquals(3, floats.getReader().getValueCount());
      Assert.assertEquals(0, floats.getReader().getNullCount());
      Assert.assertEquals(mls2, wktWriter.write(floats.getReader().get(0)));
      Assert.assertEquals(mls1, wktWriter.write(floats.getReader().get(1)));
      Assert.assertEquals(mls0, wktWriter.write(floats.getReader().get(2)));
    }
  }

  @Test
  public void testMultiPoint() throws Exception {
    WKTReader wktReader = new WKTReader();
    WKTWriter wktWriter = new WKTWriter();

    String p0 = "MULTIPOINT ((10 40), (40 30), (20 20), (30 10))";
    String p1 = "MULTIPOINT ((10 40))";
    String p2 = "MULTIPOINT ((40 30), (20 20))";
    try (RootAllocator allocator = new RootAllocator(Long.MAX_VALUE);
         MultiPointFloatVector floats = new MultiPointFloatVector("multipoints", allocator, null);
         MultiPointVector doubles = new MultiPointVector("multipoints", allocator, null)) {

      Field floatField = floats.getVector().getField();
      Field doubleField = doubles.getVector().getField();

      floats.getWriter().set(0, (MultiPoint) wktReader.read(p0));
      floats.getWriter().set(1, (MultiPoint) wktReader.read(p1));
      floats.getWriter().set(2, null);
      floats.getWriter().set(3, (MultiPoint) wktReader.read(p2));
      floats.getWriter().setValueCount(4);

      doubles.getWriter().set(0, (MultiPoint) wktReader.read(p0));
      doubles.getWriter().set(1, (MultiPoint) wktReader.read(p1));
      doubles.getWriter().set(3, (MultiPoint) wktReader.read(p2));
      doubles.getWriter().setValueCount(4);

      Assert.assertEquals(4, floats.getReader().getValueCount());
      Assert.assertEquals(1, floats.getReader().getNullCount());
      Assert.assertEquals(p0, wktWriter.write(floats.getReader().get(0)));
      Assert.assertEquals(p1, wktWriter.write(floats.getReader().get(1)));
      Assert.assertEquals(p2, wktWriter.write(floats.getReader().get(3)));
      Assert.assertNull(floats.getReader().get(2));

      Assert.assertEquals(4, doubles.getReader().getValueCount());
      Assert.assertEquals(1, doubles.getReader().getNullCount());
      Assert.assertEquals(p0, wktWriter.write(doubles.getReader().get(0)));
      Assert.assertEquals(p1, wktWriter.write(doubles.getReader().get(1)));
      Assert.assertEquals(p2, wktWriter.write(doubles.getReader().get(3)));
      Assert.assertNull(doubles.getReader().get(2));

      // ensure field was created correctly up front

      Assert.assertEquals(floatField, floats.getVector().getField());
      Assert.assertEquals(floatField.getChildren(), MultiPointFloatVector.fields);

      Assert.assertEquals(doubleField, doubles.getVector().getField());
      Assert.assertEquals(doubleField.getChildren(), MultiPointVector.fields);

      // overwriting

      floats.getWriter().set(0, (MultiPoint) wktReader.read(p2));
      floats.getWriter().set(1, (MultiPoint) wktReader.read(p1));
      floats.getWriter().set(2, (MultiPoint) wktReader.read(p0));
      floats.getWriter().setValueCount(3);

      Assert.assertEquals(3, floats.getReader().getValueCount());
      Assert.assertEquals(0, floats.getReader().getNullCount());
      Assert.assertEquals(p2, wktWriter.write(floats.getReader().get(0)));
      Assert.assertEquals(p1, wktWriter.write(floats.getReader().get(1)));
      Assert.assertEquals(p0, wktWriter.write(floats.getReader().get(2)));
    }
  }

  @Test
  public void testMultiPolygon() throws Exception {
    WKTReader wktReader = new WKTReader();
    WKTWriter wktWriter = new WKTWriter();

    String p0 = "MULTIPOLYGON (((30 20, 45 40, 10 40, 30 20)), ((15 5, 40 10, 10 20, 5 10, 15 5)))";
    String p1 = "MULTIPOLYGON (((40 40, 20 45, 45 30, 40 40)), ((20 35, 10 30, 10 10, 30 5, 45 20, 20 35), (30 20, 20 15, 20 25, 30 20)))";
    String p2 = "MULTIPOLYGON (((30 20, 45 40, 10 40, 30 20)))";

    try (RootAllocator allocator = new RootAllocator(Long.MAX_VALUE);
         MultiPolygonFloatVector floats = new MultiPolygonFloatVector("multipolys", allocator, null);
         MultiPolygonVector doubles = new MultiPolygonVector("multipolys", allocator, null)) {

      Field floatField = floats.getVector().getField();
      Field doubleField = doubles.getVector().getField();

      floats.getWriter().set(0, (MultiPolygon) wktReader.read(p0));
      floats.getWriter().set(1, (MultiPolygon) wktReader.read(p1));
      floats.getWriter().set(2, null);
      floats.getWriter().set(3, (MultiPolygon) wktReader.read(p2));
      floats.getWriter().setValueCount(4);

      doubles.getWriter().set(0, (MultiPolygon) wktReader.read(p0));
      doubles.getWriter().set(1, (MultiPolygon) wktReader.read(p1));
      doubles.getWriter().set(3, (MultiPolygon) wktReader.read(p2));
      doubles.getWriter().setValueCount(4);

      Assert.assertEquals(4, floats.getReader().getValueCount());
      Assert.assertEquals(1, floats.getReader().getNullCount());
      Assert.assertEquals(p0, wktWriter.write(floats.getReader().get(0)));
      Assert.assertEquals(p1, wktWriter.write(floats.getReader().get(1)));
      Assert.assertEquals(p2, wktWriter.write(floats.getReader().get(3)));
      Assert.assertNull(floats.getReader().get(2));

      Assert.assertEquals(4, doubles.getReader().getValueCount());
      Assert.assertEquals(1, doubles.getReader().getNullCount());
      Assert.assertEquals(p0, wktWriter.write(doubles.getReader().get(0)));
      Assert.assertEquals(p1, wktWriter.write(doubles.getReader().get(1)));
      Assert.assertEquals(p2, wktWriter.write(doubles.getReader().get(3)));
      Assert.assertNull(doubles.getReader().get(2));

      // ensure field was created correctly up front

      Assert.assertEquals(floatField, floats.getVector().getField());
      Assert.assertEquals(floatField.getChildren(), MultiPolygonFloatVector.fields);

      Assert.assertEquals(doubleField, doubles.getVector().getField());
      Assert.assertEquals(doubleField.getChildren(), MultiPolygonVector.fields);

      // overwriting

      floats.getWriter().set(0, (MultiPolygon) wktReader.read(p2));
      floats.getWriter().set(1, (MultiPolygon) wktReader.read(p1));
      floats.getWriter().set(3, (MultiPolygon) wktReader.read(p0));
      floats.getWriter().setValueCount(3);

      Assert.assertEquals(3, floats.getReader().getValueCount());
      Assert.assertEquals(0, floats.getReader().getNullCount());
      Assert.assertEquals(p2, wktWriter.write(floats.getReader().get(0)));
      Assert.assertEquals(p1, wktWriter.write(floats.getReader().get(1)));
      Assert.assertEquals(p0, wktWriter.write(floats.getReader().get(3)));
    }
  }

  @Test
  public void testWKBGeometry() throws Exception {
    WKTReader wktReader = new WKTReader();
    WKTWriter wktWriter = new WKTWriter();

    String geom1 = "POINT (0 20)";
    String geom2 = "LINESTRING (30 10, 10 30, 40 45, 55 60, 56 60)";
    String geom3 = "POLYGON ((35 10, 45 45, 15 40, 10 20, 35 10), (20 30, 35 35, 30 20, 20 30))";

    try (RootAllocator allocator = new RootAllocator(Long.MAX_VALUE);
         WKBGeometryVector doubles = new WKBGeometryVector("geoms", allocator, null)) {

      Field doubleField = doubles.getVector().getField();

      doubles.getWriter().set(0, wktReader.read(geom1));
      doubles.getWriter().set(1, wktReader.read(geom2));
      doubles.getWriter().set(3, wktReader.read(geom3));
      doubles.getWriter().setValueCount(4);

      Assert.assertEquals(4, doubles.getReader().getValueCount());
      Assert.assertEquals(1, doubles.getReader().getNullCount());
      Assert.assertEquals(geom1, wktWriter.write(doubles.getReader().get(0)));
      Assert.assertEquals(geom2, wktWriter.write(doubles.getReader().get(1)));
      Assert.assertEquals(geom3, wktWriter.write(doubles.getReader().get(3)));
      Assert.assertNull(doubles.getReader().get(2));

      // ensure field was created correctly up front
      Assert.assertEquals(doubleField, doubles.getVector().getField());
      Assert.assertEquals(doubleField.getFieldType().getType(), ArrowType.Binary.INSTANCE);

      // overwriting

      doubles.getWriter().set(0, wktReader.read(geom3));
      doubles.getWriter().set(1, wktReader.read(geom2));
      doubles.getWriter().set(2, wktReader.read(geom1));
      doubles.getWriter().setValueCount(3);

      Assert.assertEquals(3, doubles.getReader().getValueCount());
      Assert.assertEquals(0, doubles.getReader().getNullCount());
      Assert.assertEquals(geom3, wktWriter.write(doubles.getReader().get(0)));
      Assert.assertEquals(geom2, wktWriter.write(doubles.getReader().get(1)));
      Assert.assertEquals(geom1, wktWriter.write(doubles.getReader().get(2)));
    }
  }

  @Test
  public void testWKBGeometryTransfer() throws Exception {
    WKTReader wktReader = new WKTReader();
    WKTWriter wktWriter = new WKTWriter();

    String geom1 = "POINT (0 20)";
    String geom2 = "LINESTRING (30 10, 10 30, 40 45, 55 60, 56 60)";
    String geom3 = "POLYGON ((35 10, 45 45, 15 40, 10 20, 35 10), (20 30, 35 35, 30 20, 20 30))";

    try (RootAllocator allocator = new RootAllocator(Long.MAX_VALUE);
         WKBGeometryVector from = new WKBGeometryVector("geometries", allocator, null);
         WKBGeometryVector to = new WKBGeometryVector("geometries", allocator, null)) {

      from.getWriter().set(0, (Point) wktReader.read(geom1));
      from.getWriter().set(1, (LineString) wktReader.read(geom2));
      from.getWriter().set(3, (Polygon) wktReader.read(geom3));
      from.getWriter().setValueCount(4);

      for (int i = 0; i < 4; i++) {
        from.transfer(i, i, to);
      }
      to.getWriter().setValueCount(4);

      for (WKBGeometryVector vector: Arrays.asList(from, to)) {
        Assert.assertEquals(4, vector.getReader().getValueCount());
        Assert.assertEquals(1, vector.getReader().getNullCount());
        Assert.assertEquals(geom1, wktWriter.write(vector.getReader().get(0)));
        Assert.assertEquals(geom2, wktWriter.write(vector.getReader().get(1)));
        Assert.assertEquals(geom3, wktWriter.write(vector.getReader().get(3)));
        Assert.assertNull(vector.getReader().get(2));
      }

      // TODO calling clear seems to put the vector in an invalid state
      // from.getVector().clear();
      from.getWriter().set(0, null);
      from.getWriter().set(1, (Point) wktReader.read(geom1));
      from.getWriter().set(2, (LineString) wktReader.read(geom2));
      from.getWriter().set(3, (Polygon) wktReader.read(geom3));
      from.getWriter().setValueCount(4);

      for (int i = 0; i < 4; i++) {
        from.transfer(i, i, to);
      }
      to.getWriter().setValueCount(4);

      for (WKBGeometryVector vector: Arrays.asList(from, to)) {
        Assert.assertEquals(4, vector.getReader().getValueCount());
        Assert.assertEquals(1, vector.getReader().getNullCount());
        Assert.assertEquals(geom1, wktWriter.write(vector.getReader().get(1)));
        Assert.assertEquals(geom2, wktWriter.write(vector.getReader().get(2)));
        Assert.assertEquals(geom3, wktWriter.write(vector.getReader().get(3)));
        Assert.assertNull(vector.getReader().get(0));
      }
    }
  }

  private FieldVector writeToFile(GeometryVector vector, BufferAllocator allocator) {
    File file;
    try {
      file = Files.createTempFile("geometry-vector-test", ".arrow").toFile();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    try {
      try (FileOutputStream out = new FileOutputStream(file)) {
        VectorSchemaRoot root = new VectorSchemaRoot(Collections.singletonList(vector.getVector().getField()),
                                                     Collections.singletonList(vector.getVector()),
                                                     vector.getReader().getValueCount());
        DictionaryProvider dict = new MapDictionaryProvider();
        ArrowStreamWriter writer = new ArrowStreamWriter(root, dict, Channels.newChannel(out));
        writer.start();
        writer.writeBatch();
        writer.end();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      try (FileInputStream in = new FileInputStream(file)) {
        ArrowStreamReader reader = new ArrowStreamReader(Channels.newChannel(in), allocator);
        reader.loadNextBatch();
        return reader.getVectorSchemaRoot().getFieldVectors().get(0);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    } finally {
      if (!file.delete()) {
        file.deleteOnExit();
      }
    }
  }
}
