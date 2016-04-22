/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.accumulo.index

import java.util.Date

import com.google.common.primitives.Longs
import org.apache.accumulo.core.security.Authorizations
import org.geotools.data.Query
import org.geotools.factory.CommonFactoryFinder
import org.geotools.filter.text.ecql.ECQL
import org.junit.runner.RunWith
import org.locationtech.geomesa.accumulo.TestWithDataStore
import org.locationtech.geomesa.accumulo.data.tables.{Z2Table, Z3Table}
import org.locationtech.geomesa.accumulo.index.QueryHints._
import org.locationtech.geomesa.accumulo.index.Strategy.StrategyType
import org.locationtech.geomesa.accumulo.iterators.BinAggregatingIterator
import org.locationtech.geomesa.curve.Z2SFC
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.filter.function.{Convert2ViewerFunction, ExtendedValues}
import org.locationtech.sfcurve.zorder.Z2
import org.opengis.feature.simple.SimpleFeature
import org.opengis.filter.Filter
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.collection.JavaConversions._

@RunWith(classOf[JUnitRunner])
class Z2IdxStrategyTest extends Specification with TestWithDataStore {

  val spec = "name:String,track:String,dtg:Date,*geom:Point:srid=4326"

  val features =
    (0 until 10).map { i =>
      val sf = new ScalaSimpleFeature(s"$i", sft)
      sf.setAttributes(Array[AnyRef](s"name$i", "track1", s"2010-05-07T0$i:00:00.000Z", s"POINT(40 6$i)"))
      sf
    } ++ (10 until 20).map { i =>
      val sf = new ScalaSimpleFeature(s"$i", sft)
      sf.setAttributes(Array[AnyRef](s"name$i", "track2", s"2010-05-${i}T$i:00:00.000Z", s"POINT(40 6${i - 10})"))
      sf
    } ++ (20 until 30).map { i =>
      val sf = new ScalaSimpleFeature(s"$i", sft)
      sf.setAttributes(Array[AnyRef](s"name$i", "track3", s"2010-05-${i}T${i-10}:00:00.000Z", s"POINT(40 8${i - 20})"))
      sf
    }
  addFeatures(features)

  implicit val ff = CommonFactoryFinder.getFilterFactory2
  val strategy = StrategyType.Z2
  val queryPlanner = new QueryPlanner(sft, ds)
  val output = ExplainNull

  "Z3IdxStrategy" should {
    "print values" in {
      skipped("used for debugging")
      println()
      ds.connector.createScanner(ds.getTableName(sftName, Z2Table), new Authorizations()).foreach { r =>
        val bytes = r.getKey.getRow.getBytes
        val keyZ = Longs.fromByteArray(bytes.drop(2))
        val (x, y) = Z2SFC.invert(Z2(keyZ))
        println(s"row: $x $y")
      }
      println()
      success
    }

    "return all features for inclusive filter" >> {
      val filter = "bbox(geom, 35, 55, 45, 75)" +
          " AND dtg between '2010-05-07T00:00:00.000Z' and '2010-05-08T00:00:00.000Z'"
      val features = execute(filter)
      features must haveSize(10)
      features.map(_.getID.toInt) must containTheSameElementsAs(0 to 9)
    }

    "return some features for exclusive geom filter" >> {
      val filter = "bbox(geom, 35, 55, 45, 65)" +
          " AND dtg between '2010-05-07T00:00:00.000Z' and '2010-05-08T00:00:00.000Z'"
      val features = execute(filter)
      features must haveSize(6)
      features.map(_.getID.toInt) must containTheSameElementsAs(0 to 5)
    }

    "return some features for exclusive date filter" >> {
      val filter = "bbox(geom, 35, 55, 45, 75)" +
          " AND dtg between '2010-05-07T06:00:00.000Z' and '2010-05-08T00:00:00.000Z'"
      val features = execute(filter)
      features must haveSize(4)
      features.map(_.getID.toInt) must containTheSameElementsAs(6 to 9)
    }

    "work with whole world filter" >> {
      val filter = "bbox(geom, -180, -90, 180, 90)" +
          " AND dtg between '2010-05-07T05:00:00.000Z' and '2010-05-07T08:00:00.000Z'"
      val features = execute(filter)
      features must haveSize(4)
      features.map(_.getID.toInt) must containTheSameElementsAs(5 to 8)
    }

    "work across week bounds" >> {
      val filter = "bbox(geom, 35, 65, 45, 75)" +
          " AND dtg between '2010-05-07T06:00:00.000Z' and '2010-05-21T00:00:00.000Z'"
      val features = execute(filter)
      features must haveSize(9)
      features.map(_.getID.toInt) must containTheSameElementsAs((6 to 9) ++ (15 to 19))
    }

    "work across 2 weeks" >> {
      val filter = "bbox(geom, 35, 64.5, 45, 70)" +
          " AND dtg between '2010-05-10T00:00:00.000Z' and '2010-05-17T23:59:59.999Z'"
      val features = execute(filter)
      features must haveSize(3)
      features.map(_.getID.toInt) must containTheSameElementsAs(15 to 17)
    }

    "work with whole world filter across week bounds" >> {
      val filter = "bbox(geom, -180, -90, 180, 90)" +
          " AND dtg between '2010-05-07T06:00:00.000Z' and '2010-05-21T00:00:00.000Z'"
      val features = execute(filter)
      features must haveSize(15)
      features.map(_.getID.toInt) must containTheSameElementsAs(6 to 20)
    }

    "work with whole world filter across 3 week periods" >> {
      val filter = "bbox(geom, -180, -90, 180, 90)" +
        " AND dtg between '2010-05-08T06:00:00.000Z' and '2010-05-30T00:00:00.000Z'"
      val features = execute(filter)
      features must haveSize(20)
      features.map(_.getID.toInt) must containTheSameElementsAs(10 to 29)
    }

    "work with small bboxes and date ranges" >> {
      val filter = "bbox(geom, 39.999, 60.999, 40.001, 61.001)" +
        " AND dtg between '2010-05-07T00:59:00.000Z' and '2010-05-07T01:01:00.000Z'"
      val features = execute(filter)
      features must haveSize(1)
      features.head.getID.toInt mustEqual 1
    }

    "apply secondary filters" >> {
      val filter = "bbox(geom, 35, 55, 45, 75)" +
          " AND dtg between '2010-05-07T06:00:00.000Z' and '2010-05-08T00:00:00.000Z'" +
          " AND name = 'name8'"
      val features = execute(filter)
      features must haveSize(1)
      features.map(_.getID.toInt) must containTheSameElementsAs(Seq(8))
    }

    "apply transforms" >> {
      val filter = "bbox(geom, 35, 55, 45, 75)" +
          " AND dtg between '2010-05-07T06:00:00.000Z' and '2010-05-08T00:00:00.000Z'"
      val features = execute(filter, Some(Array("name")))
      features must haveSize(4)
      features.map(_.getID.toInt) must containTheSameElementsAs(6 to 9)
      forall(features)((f: SimpleFeature) => f.getAttributeCount mustEqual 2) // geom always gets added
      forall(features)((f: SimpleFeature) => f.getAttribute("geom") must not(beNull))
      forall(features)((f: SimpleFeature) => f.getAttribute("name") must not(beNull))
    }

    "apply functional transforms" >> {
      val filter = "bbox(geom, 35, 55, 45, 75)" +
          " AND dtg between '2010-05-07T06:00:00.000Z' and '2010-05-08T00:00:00.000Z'"
      val features = execute(filter, Some(Array("derived=strConcat('my', name)")))
      features must haveSize(4)
      features.map(_.getID.toInt) must containTheSameElementsAs(6 to 9)
      forall(features)((f: SimpleFeature) => f.getAttributeCount mustEqual 2) // geom always gets added
      forall(features)((f: SimpleFeature) => f.getAttribute("geom") must not(beNull))
      forall(features)((f: SimpleFeature) => f.getAttribute("derived").asInstanceOf[String] must beMatching("myname\\d"))
    }

    "apply transforms using only the row key" >> {
      val filter = "bbox(geom, 35, 55, 45, 75)" +
          " AND dtg between '2010-05-07T06:00:00.000Z' and '2010-05-08T00:00:00.000Z'"
      val query = new Query(sftName, ECQL.toFilter(filter), Array("geom", "dtg"))
      val qps = getQueryPlans(query)
      forall(qps)(p => p.columnFamilies must containTheSameElementsAs(Seq(Z3Table.BIN_CF)))

      val features = execute(filter, Some(Array("geom", "dtg")))
      features must haveSize(4)
      features.map(_.getID.toInt) must containTheSameElementsAs(6 to 9)
      forall(features)((f: SimpleFeature) => f.getAttributeCount mustEqual 2)
      forall(features)((f: SimpleFeature) => f.getAttribute("geom") must not(beNull))
      forall(features)((f: SimpleFeature) => f.getAttribute("dtg") must not(beNull))
    }.pendingUntilFixed("not implemented")

    "optimize for bin format" >> {
      val filter = "bbox(geom, -180, -90, 180, 90)" +
          " AND dtg between '2010-05-07T00:00:00.000Z' and '2010-05-07T12:00:00.000Z'"
      val query = new Query(sftName, ECQL.toFilter(filter))
      query.getHints.put(BIN_TRACK_KEY, "name")
      query.getHints.put(BIN_BATCH_SIZE_KEY, 100)
      val qps = getQueryPlans(query)
      qps must haveSize(1)
      qps.head.iterators.map(_.getIteratorClass) must
          contain(classOf[BinAggregatingIterator].getCanonicalName)
      val returnedFeatures = queryPlanner.runQuery(query, Some(strategy))
      // the same simple feature gets reused - so make sure you access in serial order
      val aggregates = returnedFeatures.map(f =>
        f.getAttribute(BinAggregatingIterator.BIN_ATTRIBUTE_INDEX).asInstanceOf[Array[Byte]]).toSeq
      aggregates.size must beLessThan(10) // ensure some aggregation was done
      val bin = aggregates.flatMap(a => a.grouped(16).map(Convert2ViewerFunction.decode))
      bin must haveSize(10)
      bin.map(_.trackId) must containAllOf((0 until 10).map(i => s"name$i".hashCode.toString))
      bin.map(_.dtg) must
          containAllOf((0 until 10).map(i => features(i).getAttribute("dtg").asInstanceOf[Date].getTime))
      bin.map(_.lat) must containAllOf((0 until 10).map(_ + 60.0))
      forall(bin.map(_.lon))(_ mustEqual 40.0)
    }

    "optimize for bin format with sorting" >> {
      val filter = "bbox(geom, -180, -90, 180, 90)" +
          " AND dtg between '2010-05-07T00:00:00.000Z' and '2010-05-07T12:00:00.000Z'"
      val query = new Query(sftName, ECQL.toFilter(filter))
      query.getHints.put(BIN_TRACK_KEY, "name")
      query.getHints.put(BIN_BATCH_SIZE_KEY, 100)
      query.getHints.put(BIN_SORT_KEY, true)
      val qps = getQueryPlans(query)
      qps must haveSize(1)
      qps.head.iterators.map(_.getIteratorClass) must
          contain(classOf[BinAggregatingIterator].getCanonicalName)
      val returnedFeatures = queryPlanner.runQuery(query, Some(strategy))
      // the same simple feature gets reused - so make sure you access in serial order
      val aggregates = returnedFeatures.map(f =>
        f.getAttribute(BinAggregatingIterator.BIN_ATTRIBUTE_INDEX).asInstanceOf[Array[Byte]]).toSeq
      aggregates.size must beLessThan(10) // ensure some aggregation was done
      forall(aggregates) { a =>
        val window = a.grouped(16).map(Convert2ViewerFunction.decode(_).dtg).sliding(2).filter(_.length > 1)
        forall(window)(w => w.head must beLessThanOrEqualTo(w(1)))
      }
      val bin = aggregates.flatMap(a => a.grouped(16).map(Convert2ViewerFunction.decode))
      bin must haveSize(10)
      bin.map(_.trackId) must containAllOf((0 until 10).map(i => s"name$i".hashCode.toString))
      bin.map(_.dtg) must
          containAllOf((0 until 10).map(i => features(i).getAttribute("dtg").asInstanceOf[Date].getTime))
      bin.map(_.lat) must containAllOf((0 until 10).map(_ + 60.0))
      forall(bin.map(_.lon))(_ mustEqual 40.0)
    }

    "optimize for bin format with label" >> {
      val filter = "bbox(geom, -180, -90, 180, 90)" +
          " AND dtg between '2010-05-07T00:00:00.000Z' and '2010-05-07T12:00:00.000Z'"
      val query = new Query(sftName, ECQL.toFilter(filter))
      query.getHints.put(BIN_TRACK_KEY, "name")
      query.getHints.put(BIN_LABEL_KEY, "name")
      query.getHints.put(BIN_BATCH_SIZE_KEY, 100)
      val qps = getQueryPlans(query)
      qps must haveSize(1)
      qps.head.iterators.map(_.getIteratorClass) must
          contain(classOf[BinAggregatingIterator].getCanonicalName)
      val returnedFeatures = queryPlanner.runQuery(query, Some(strategy))
      // the same simple feature gets reused - so make sure you access in serial order
      val aggregates = returnedFeatures.map(f =>
        f.getAttribute(BinAggregatingIterator.BIN_ATTRIBUTE_INDEX).asInstanceOf[Array[Byte]]).toSeq
      aggregates.size must beLessThan(10) // ensure some aggregation was done
      val bin = aggregates.flatMap(a => a.grouped(24).map(Convert2ViewerFunction.decode))
      bin must haveSize(10)
      bin.map(_.trackId) must containAllOf((0 until 10).map(i => s"name$i".hashCode.toString))
      bin.map(_.dtg) must
          containAllOf((0 until 10).map(i => features(i).getAttribute("dtg").asInstanceOf[Date].getTime))
      bin.map(_.lat) must containAllOf((0 until 10).map(_ + 60.0))
      forall(bin.map(_.lon))(_ mustEqual 40.0)
      forall(bin)(_ must beAnInstanceOf[ExtendedValues])
      bin.map(_.asInstanceOf[ExtendedValues].label) must containAllOf((0 until 10).map(i => Convert2ViewerFunction.convertToLabel(s"name$i")))
    }

    "support sampling" in {
      val query = new Query(sftName, Filter.INCLUDE)
      query.getHints.put(SAMPLING_KEY, new java.lang.Float(.5f))
      val results = queryPlanner.runQuery(query, Some(strategy)).toList
      results must haveLength(15)
    }

    "support sampling with cql" in {
      val query = new Query(sftName, ECQL.toFilter("track = 'track1'"))
      query.getHints.put(SAMPLING_KEY, new java.lang.Float(.5f))
      val results = queryPlanner.runQuery(query, Some(strategy)).toList
      results must haveLength(5)
      forall(results)(_.getAttribute("track") mustEqual "track1")
    }

    "support sampling with transformations" in {
      val query = new Query(sftName, Filter.INCLUDE, Array("name", "geom"))
      query.getHints.put(SAMPLING_KEY, new java.lang.Float(.5f))
      val results = queryPlanner.runQuery(query, Some(strategy)).toList
      results must haveLength(15)
      forall(results)(_.getAttributeCount mustEqual 2)
    }

    "support sampling with cql and transformations" in {
      val query = new Query(sftName, ECQL.toFilter("track = 'track2'"), Array("name", "geom"))
      query.getHints.put(SAMPLING_KEY, new java.lang.Float(.2f))
      val results = queryPlanner.runQuery(query, Some(strategy)).toList
      results must haveLength(2)
      forall(results)(_.getAttributeCount mustEqual 2)
    }

    "support sampling by thread" in {
      val query = new Query(sftName, Filter.INCLUDE)
      query.getHints.put(SAMPLING_KEY, new java.lang.Float(.5f))
      query.getHints.put(SAMPLE_BY_KEY, "track")
      val results = queryPlanner.runQuery(query, Some(strategy)).toList
      results must haveLength(15)
      results.count(_.getAttribute("track") == "track1") mustEqual 5
      results.count(_.getAttribute("track") == "track2") mustEqual 5
      results.count(_.getAttribute("track") == "track3") mustEqual 5
    }

    "support sampling with bin queries" in {
      import BinAggregatingIterator.BIN_ATTRIBUTE_INDEX
      val query = new Query(sftName, Filter.INCLUDE)
      query.getHints.put(BIN_TRACK_KEY, "track")
      query.getHints.put(BIN_BATCH_SIZE_KEY, 1000)
      query.getHints.put(SAMPLING_KEY, new java.lang.Float(.2f))
      query.getHints.put(SAMPLE_BY_KEY, "track")

      // have to evaluate attributes before pulling into collection, as the same sf is reused
      val results = queryPlanner.runQuery(query, Some(strategy)).map(_.getAttribute(BIN_ATTRIBUTE_INDEX)).toList
      forall(results)(_ must beAnInstanceOf[Array[Byte]])
      val bins = results.flatMap(_.asInstanceOf[Array[Byte]].grouped(16).map(Convert2ViewerFunction.decode))
      bins must haveLength(6)
      bins.map(_.trackId) must containTheSameElementsAs {
        Seq("track1", "track1", "track2", "track2", "track3", "track3").map(_.hashCode.toString)
      }
    }
  }

  def execute(ecql: String, transforms: Option[Array[String]] = None) = {
    val query = transforms match {
      case None    => new Query(sftName, ECQL.toFilter(ecql))
      case Some(t) => new Query(sftName, ECQL.toFilter(ecql), t)
    }
    queryPlanner.runQuery(query, Some(strategy)).toSeq
  }

  def getQueryPlans(query: Query): Seq[QueryPlan] = {
    queryPlanner.planQuery(query, Some(strategy), output)
  }
}
