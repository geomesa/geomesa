/*******************************************************************************
 * Copyright (c) 2013-2017 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ******************************************************************************/

package org.locationtech.geomesa.arrow.vector.writer;

import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.Point;
import org.apache.arrow.vector.complex.impl.NullableMapWriter;
import org.apache.arrow.vector.complex.writer.BaseWriter.ListWriter;
import org.apache.arrow.vector.complex.writer.BaseWriter.MapWriter;

public class LineStringWriter implements GeometryWriter<LineString> {

  private final MapWriter writer;
  private final ListWriter xWriter;
  private final ListWriter yWriter;

  public LineStringWriter(MapWriter writer) {
    this.writer = writer;
    this.xWriter = writer.list("x");
    this.yWriter = writer.list("y");
  }

  @Override
  public void set(LineString geom) {
    if (geom != null) {
      writer.start();
      xWriter.startList();
      yWriter.startList();
      for (int i = 0; i < geom.getNumPoints(); i++) {
        Point p = geom.getPointN(i);
        xWriter.float8().writeFloat8(p.getX());
        yWriter.float8().writeFloat8(p.getY());
      }
      xWriter.endList();
      yWriter.endList();
      writer.end();
    }
  }

  @Override
  public void set(int i, LineString geom) {
    writer.setPosition(i);
    set(geom);
  }

  @Override
  public void setValueCount(int count) {
    if (writer instanceof NullableMapWriter) {
      ((NullableMapWriter) writer).setValueCount(count);
    } else {
      throw new RuntimeException("Not nullable writer: " + writer);
    }
  }

  @Override
  public void close() throws Exception {
    writer.close();
  }
}
