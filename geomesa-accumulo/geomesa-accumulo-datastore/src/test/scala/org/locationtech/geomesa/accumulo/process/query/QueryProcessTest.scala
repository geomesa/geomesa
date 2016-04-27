/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.accumulo.process.query

import com.vividsolutions.jts.geom.Geometry
import org.geotools.filter.text.cql2.CQL
import org.joda.time.{DateTime, DateTimeZone}
import org.junit.runner.RunWith
import org.locationtech.geomesa.accumulo.TestWithDataStore
import org.locationtech.geomesa.features.avro.AvroSimpleFeatureFactory
import org.locationtech.geomesa.utils.text.WKTUtils
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class QueryProcessTest extends Specification with TestWithDataStore {

  sequential

  val dtg = org.locationtech.geomesa.accumulo.process.tube.DEFAULT_DTG_FIELD
  override val spec = s"type:String,*geom:Point:srid=4326,$dtg:Date"

  val features = List("a", "b").flatMap { name =>
    List(1, 2, 3, 4).zip(List(45, 46, 47, 48)).map { case (i, lat) =>
      val sf = AvroSimpleFeatureFactory.buildAvroFeature(sft, List(), name + i.toString)
      sf.setDefaultGeometry(WKTUtils.read(f"POINT($lat%d $lat%d)"))
      sf.setAttribute(org.locationtech.geomesa.accumulo.process.tube.DEFAULT_DTG_FIELD, new DateTime(s"2011-01-0${i}T00:00:00Z", DateTimeZone.UTC).toDate)
      sf.setAttribute("type", name)
      sf
    }
  }

  addFeatures(features)

  "GeomesaQuery" should {
    "return things without a filter" in {
      val features = fs.getFeatures()

      val geomesaQuery = new QueryProcess
      val results = geomesaQuery.execute(features, null)

      val f = results.features()
      while (f.hasNext) {
        val sf = f.next
        sf.getAttribute("type") should beOneOf("a", "b")
      }

      results.size mustEqual 8
    }

    "respect a parent filter" in {
      val features = fs.getFeatures(CQL.toFilter("type = 'b'"))

      val geomesaQuery = new QueryProcess
      val results = geomesaQuery.execute(features, null)

      val f = results.features()
      while (f.hasNext) {
        val sf = f.next
        sf.getAttribute("type") mustEqual "b"
      }

      results.size mustEqual 4
    }

    "be able to use its own filter" in {
      val features = fs.getFeatures(CQL.toFilter("type = 'b' OR type = 'a'"))

      val geomesaQuery = new QueryProcess
      val results = geomesaQuery.execute(features, CQL.toFilter("type = 'a'"))

      val f = results.features()
      while (f.hasNext) {
        val sf = f.next
        sf.getAttribute("type") mustEqual "a"
      }

      results.size mustEqual 4
    }

    "query wtih short dates (using joda conversion factory)" in {
      val features = fs.getFeatures()

      val geomesaQuery = new QueryProcess
      val results = geomesaQuery.execute(features, CQL.toFilter("dtg between '2011-01-01' AND '2011-01-02'"))

      val f = results.features()

      results.size mustEqual 4
    }

    "query with a variety of date formats (using joda conversion factory)" in {
      val features = fs.getFeatures()

      val geomesaQuery = new QueryProcess
      geomesaQuery.execute(features, CQL.toFilter("dtg between '2011-01-01T00:00:00.000Z' AND '2011-01-02T00:00:00.000Z'")).size() mustEqual 4
      geomesaQuery.execute(features, CQL.toFilter("dtg between '2011-01-01T00:00:00.000' AND '2011-01-02T00:00:00.000'")).size() mustEqual 4

      geomesaQuery.execute(features, CQL.toFilter("dtg between '2011-01-01T00:00:00Z' AND '2011-01-02T00:00:00Z'")).size() mustEqual 4
      geomesaQuery.execute(features, CQL.toFilter("dtg between '2011-01-01T00:00:00' AND '2011-01-02T00:00:00'")).size() mustEqual 4

      geomesaQuery.execute(features, CQL.toFilter("dtg between '2011-01-01T00:00Z' AND '2011-01-02T00:00Z'")).size() mustEqual 4
      geomesaQuery.execute(features, CQL.toFilter("dtg between '2011-01-01T00:00' AND '2011-01-02T00:00'")).size() mustEqual 4

      geomesaQuery.execute(features, CQL.toFilter("dtg between '2011-01-01' AND '2011-01-02'")).size() mustEqual 4
    }

    "properly query geometry" in {
      val features = fs.getFeatures()

      val geomesaQuery = new QueryProcess
      val results = geomesaQuery.execute(features, CQL.toFilter("bbox(geom, 45.0, 45.0, 46.0, 46.0)"))

      val poly = WKTUtils.read("POLYGON((45 45, 46 45, 46 46, 45 46, 45 45))")

      val f = results.features()
      while (f.hasNext) {
        val sf = f.next
        poly.intersects(sf.getDefaultGeometry.asInstanceOf[Geometry]) must beTrue
      }

      results.size mustEqual 4
    }
  }


}
