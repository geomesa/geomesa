/*******************************************************************************
 * Copyright (c) 2013-2017 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ******************************************************************************/

package org.locationtech.geomesa.accumulo.iterators

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import org.apache.arrow.memory.RootAllocator
import org.geotools.data.{Query, Transaction}
import org.junit.runner.RunWith
import org.locationtech.geomesa.accumulo.TestWithDataStore
import org.locationtech.geomesa.arrow.io.SimpleFeatureArrowFileReader
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.index.conf.QueryHints
import org.locationtech.geomesa.index.utils.ExplainPrintln
import org.locationtech.geomesa.utils.collection.SelfClosingIterator
import org.opengis.filter.Filter
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ArrowBatchIteratorTest extends TestWithDataStore {

  override val spec = "name:String,dtg:Date,*geom:Point:srid=4326"

  val features = (0 until 10).map { i =>
    ScalaSimpleFeature.create(sft, s"$i", s"name${i % 2}", s"2017-02-03T00:0$i:00.000Z", s"POINT(40 6$i)")
  }

  addFeatures(features)

  "ArrowBatchIterator" should {
    "return arrow encoded data" in {
      val query = new Query(sft.getTypeName, Filter.INCLUDE)
      query.getHints.put(QueryHints.ARROW_ENCODE, true)
      val results = SelfClosingIterator(ds.getFeatureReader(query, Transaction.AUTO_COMMIT))
      val out = new ByteArrayOutputStream
      results.foreach(sf => out.write(sf.getAttribute(0).asInstanceOf[Array[Byte]]))
      val in = new ByteArrayInputStream(out.toByteArray)
      implicit val allocator = new RootAllocator(Long.MaxValue)
      val reader = new SimpleFeatureArrowFileReader(in)
      try {
        reader.read().toSeq must containTheSameElementsAs(features)
      } finally {
        reader.close()
        allocator.close()
      }
    }
  }
}
