/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.utils.stats

import java.lang.{Double => jDouble, Float => jFloat, Long => jLong}
import java.util.Date

import com.vividsolutions.jts.geom.Geometry
import org.geotools.feature.simple.SimpleFeatureBuilder
import org.junit.runner.RunWith
import org.locationtech.geomesa.utils.geotools.GeoToolsDateFormat
import org.locationtech.geomesa.utils.text.WKTUtils
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class HistogramTest extends org.specs2.mutable.Spec with StatTestHelper {

  def createStat[T](attribute: String, bins: Int, min: String, max: String, observe: Boolean): Histogram[T] = {
    val s = Stat(sft, s"Histogram($attribute,$bins,'$min','$max')")
    if (observe) {
      features.foreach { s.observe }
    }
    s.asInstanceOf[Histogram[T]]
  }

  def stringStat(bins: Int, min: String, max: String, observe: Boolean = true) =
    createStat[String]("strAttr", bins, min, max, observe)

  def intStat(bins: Int, min: Int, max: Int, observe: Boolean = true) =
    createStat[Integer]("intAttr", bins, min.toString, max.toString, observe)

  def longStat(bins: Int, min: Long, max: Long, observe: Boolean = true) =
    createStat[jLong]("longAttr", bins, min.toString, max.toString, observe)

  def floatStat(bins: Int, min: Float, max: Float, observe: Boolean = true) =
    createStat[jFloat]("floatAttr", bins, min.toString, max.toString, observe)

  def doubleStat(bins: Int, min: Double, max: Double, observe: Boolean = true) =
    createStat[jDouble]("doubleAttr", bins, min.toString, max.toString, observe)

  def dateStat(bins: Int, min: String, max: String, observe: Boolean = true) =
    createStat[Date]("dtg", bins, min, max, observe)

  def geomStat(bins: Int, min: String, max: String, observe: Boolean = true) =
    createStat[Geometry]("geom", bins, min, max, observe)

  def toDate(string: String) = GeoToolsDateFormat.parseDateTime(string).toDate
  def toGeom(string: String) = WKTUtils.read(string)

  "RangeHistogram stat" should {

    "work with strings" >> {
      "be empty initially" >> {
        val stat = stringStat(20, "abc000", "abc200", observe = false)
        stat.isEmpty must beTrue
        stat.length mustEqual 20
        stat.bounds mustEqual ("abc000", "abc200")
        forall(0 until 20)(stat.count(_) mustEqual 0)
      }

      "correctly bin values"  >> {
        val stat = stringStat(36, "abc000", "abc099")
        stat.isEmpty must beFalse
        stat.length mustEqual 36
        (0 until 36).map(stat.count).sum mustEqual 100
      }

      "serialize and deserialize" >> {
        val stat = stringStat(20, "abc000", "abc200")
        val packed   = StatSerializer(sft).serialize(stat)
        val unpacked = StatSerializer(sft).deserialize(packed)

        unpacked must beAnInstanceOf[Histogram[String]]
        unpacked.asInstanceOf[Histogram[String]].length mustEqual stat.length
        unpacked.asInstanceOf[Histogram[String]].attribute mustEqual stat.attribute
        unpacked.asInstanceOf[Histogram[String]].toJson mustEqual stat.toJson
      }

      "serialize and deserialize empty stats" >> {
        val stat = stringStat(20, "abc000", "abc200", observe = false)
        val packed   = StatSerializer(sft).serialize(stat)
        val unpacked = StatSerializer(sft).deserialize(packed)

        unpacked must beAnInstanceOf[Histogram[String]]
        unpacked.asInstanceOf[Histogram[String]].length mustEqual stat.length
        unpacked.asInstanceOf[Histogram[String]].attribute mustEqual stat.attribute
        unpacked.asInstanceOf[Histogram[String]].toJson mustEqual stat.toJson
      }

      "deserialize as immutable value" >> {
        val stat = stringStat(20, "abc000", "abc200")
        val packed   = StatSerializer(sft).serialize(stat)
        val unpacked = StatSerializer(sft).deserialize(packed, immutable = true)

        unpacked must beAnInstanceOf[Histogram[String]]
        unpacked.asInstanceOf[Histogram[String]].length mustEqual stat.length
        unpacked.asInstanceOf[Histogram[String]].attribute mustEqual stat.attribute
        unpacked.asInstanceOf[Histogram[String]].toJson mustEqual stat.toJson

        unpacked.clear must throwAn[Exception]
        unpacked.+=(stat) must throwAn[Exception]
        unpacked.observe(features.head) must throwAn[Exception]
        unpacked.unobserve(features.head) must throwAn[Exception]
      }

      "combine two RangeHistograms" >> {
        val stat = stringStat(36, "abc000", "abc099")
        val stat2 = stringStat(36, "abc100", "abc199", observe = false)

        features2.foreach { stat2.observe }

        stat2.length mustEqual 36
        (0 until 36).map(stat2.count).sum mustEqual 100

        stat += stat2

        stat.length mustEqual 36
        (0 until 36).map(stat.count).sum mustEqual 200

        stat2.length mustEqual 36
        (0 until 36).map(stat2.count).sum mustEqual 100
      }

      "combine two RangeHistograms with empty values" >> {
        val stat = stringStat(100, "0", "z", observe = false)
        val stat2 = stringStat(100, "alpha", "gamma", observe = false)

        stat.bins.add("0")
        stat2.bins.add("alpha")
        stat2.bins.add("beta")
        stat2.bins.add("gamma")
        stat2.bins.add("cappa")

        stat2 += stat

        stat2.bounds mustEqual ("00000", "gamma")
      }

      "clear" >> {
        val stat = stringStat(20, "abc000", "abc200")
        stat.clear()

        stat.isEmpty must beTrue
        stat.length mustEqual 20
        forall(0 until 20)(stat.count(_) mustEqual 0)
      }
    }

    "work with integers" >> {
      "be empty initially" >> {
        val stat = intStat(20, 0, 199, observe = false)
        stat.isEmpty must beTrue
        stat.length mustEqual 20
        stat.bounds mustEqual (0, 199)
        forall(0 until 20)(stat.count(_) mustEqual 0)
      }

      "correctly bin values"  >> {
        val stat = intStat(20, 0, 199)
        stat.isEmpty must beFalse
        stat.length mustEqual 20
        forall(0 until 10)(stat.count(_) mustEqual 10)
        forall(10 until 20)(stat.count(_) mustEqual 0)
      }

      "correctly remove values"  >> {
        val stat = intStat(20, 0, 199)
        stat.isEmpty must beFalse
        stat.length mustEqual 20
        forall(0 until 10)(stat.count(_) mustEqual 10)
        forall(10 until 20)(stat.count(_) mustEqual 0)
        features.take(50).foreach(stat.unobserve)
        forall(5 until 10)(stat.count(_) mustEqual 10)
        forall((0 until 5) ++ (10 until 20))(stat.count(_) mustEqual 0)
      }

      "serialize and deserialize" >> {
        val stat = intStat(20, 0, 199)
        val packed   = StatSerializer(sft).serialize(stat)
        val unpacked = StatSerializer(sft).deserialize(packed)

        unpacked must beAnInstanceOf[Histogram[Integer]]
        unpacked.asInstanceOf[Histogram[Integer]].length mustEqual stat.length
        unpacked.asInstanceOf[Histogram[Integer]].attribute mustEqual stat.attribute
        unpacked.toJson mustEqual stat.toJson
      }

      "serialize and deserialize empty stats" >> {
        val stat = intStat(20, 0, 199, observe = false)
        val packed   = StatSerializer(sft).serialize(stat)
        val unpacked = StatSerializer(sft).deserialize(packed)

        unpacked must beAnInstanceOf[Histogram[Integer]]
        unpacked.asInstanceOf[Histogram[Integer]].length mustEqual stat.length
        unpacked.asInstanceOf[Histogram[Integer]].attribute mustEqual stat.attribute
        unpacked.toJson mustEqual stat.toJson
      }

      "combine two RangeHistograms" >> {
        val stat = intStat(20, 0, 199)
        val stat2 = intStat(20, 0, 199, observe = false)

        features2.foreach { stat2.observe }

        stat2.length mustEqual 20
        forall(0 until 10)(stat2.count(_) mustEqual 0)
        forall(10 until 20)(stat2.count(_) mustEqual 10)

        stat += stat2

        stat.length mustEqual 20
        forall(0 until 20)(stat.count(_) mustEqual 10)
        stat2.length mustEqual 20
        forall(0 until 10)(stat2.count(_) mustEqual 0)
        forall(10 until 20)(stat2.count(_) mustEqual 10)
      }

      "combine two RangeHistograms with different bounds" >> {
        val stat = intStat(20, 0, 99)
        val stat2 = intStat(20, 100, 199, observe = false)

        features2.foreach { stat2.observe }

        stat.length mustEqual 20
        forall(0 until 20)(stat.count(_) mustEqual 5)

        stat2.length mustEqual 20
        forall(0 until 20)(stat2.count(_) mustEqual 5)

        stat += stat2

        stat.length mustEqual 20
        stat.bounds mustEqual (0, 199)
        forall(0 until 20)(stat.count(_) mustEqual 10)
      }

      "combine two RangeHistograms with different lengths" >> {
        val stat = intStat(20, 0, 199)
        val stat2 = intStat(10, 0, 199, observe = false)

        features2.foreach { stat2.observe }

        stat.length mustEqual 20
        forall(0 until 10)(stat.count(_) mustEqual 10)
        forall(10 until 20)(stat.count(_) mustEqual 0)

        stat2.length mustEqual 10
        forall(0 until 5)(stat2.count(_) mustEqual 0)
        forall(5 until 10)(stat2.count(_) mustEqual 20)

        stat += stat2

        stat.length mustEqual 20
        stat.bounds mustEqual (0, 199)
        forall(0 until 20)(stat.count(_) mustEqual 10)
      }

      "combine two RangeHistograms with empty values" >> {
        val stat = intStat(20, -100, 300)
        val stat2 = intStat(20, 50, 249, observe = false)

        features2.foreach { stat2.observe }

        stat.length mustEqual 20
        forall((0 until 5) ++ (10 until 20))(stat.count(_) mustEqual 0)
        forall(5 until 10)(stat.count(_) mustEqual 20)

        stat2.length mustEqual 20
        forall((0 until 5) ++ (15 until 20))(stat2.count(_) mustEqual 0)
        forall(5 until 15)(stat2.count(_) mustEqual 10)

        stat += stat2

        stat.length mustEqual 20
        stat.bounds mustEqual (0, 199)
        stat.bins.counts mustEqual Array(6, 8, 12, 8, 12, 8, 12, 8, 12, 8, 16, 10, 10, 10, 10, 10, 10, 10, 10, 10)
        (0 until stat.length).map(stat.count).sum mustEqual 200
      }

      "clear" >> {
        val stat = intStat(20, 0, 199)
        stat.clear()

        stat.isEmpty must beTrue
        stat.length mustEqual 20
        forall(0 until 20)(stat.count(_) mustEqual 0)
      }
    }

    "work with longs" >> {
      "be empty initially" >> {
        val stat = longStat(10, 0, 99, observe = false)
        stat.isEmpty must beTrue
        stat.length mustEqual 10
        stat.bounds mustEqual (0, 99)
        forall(0 until 10)(stat.count(_) mustEqual 0)
      }

      "correctly bin values" >> {
        val stat = longStat(10, 0, 99)

        stat.isEmpty must beFalse
        stat.length mustEqual 10
        stat.bounds mustEqual (0, 99)
        forall(0 until 10)(stat.count(_) mustEqual 10)
      }

      "serialize and deserialize" >> {
        val stat = longStat(7, 90, 110)
        val packed   = StatSerializer(sft).serialize(stat)
        val unpacked = StatSerializer(sft).deserialize(packed)

        unpacked must beAnInstanceOf[Histogram[jLong]]
        unpacked.asInstanceOf[Histogram[jLong]].length mustEqual stat.length
        unpacked.asInstanceOf[Histogram[jLong]].attribute mustEqual stat.attribute
        unpacked.toJson mustEqual stat.toJson
      }

      "serialize and deserialize empty stats" >> {
        val stat = longStat(7, 90, 110, observe = false)
        val packed   = StatSerializer(sft).serialize(stat)
        val unpacked = StatSerializer(sft).deserialize(packed)

        unpacked must beAnInstanceOf[Histogram[jLong]]
        unpacked.asInstanceOf[Histogram[jLong]].length mustEqual stat.length
        unpacked.asInstanceOf[Histogram[jLong]].attribute mustEqual stat.attribute
        unpacked.toJson mustEqual stat.toJson
      }

      "combine two RangeHistograms" >> {
        val stat = longStat(10, 0, 99)
        val stat2 = longStat(10, 100, 199, observe = false)

        features2.foreach { stat2.observe }

        stat2.length mustEqual 10
        forall(0 until 10)(stat2.count(_) mustEqual 10)

        stat += stat2

        stat.length mustEqual 10
        stat.bounds mustEqual (0, 199)
        forall(0 until 10)(stat.count(_) mustEqual 20)

        stat2.length mustEqual 10
        forall(0 until 10)(stat2.count(_) mustEqual 10)
      }

      "clear" >> {
        val stat = longStat(7, 90, 110)
        stat.clear()

        stat.isEmpty must beTrue
        stat.length mustEqual 7
        forall(0 until 7)(stat.count(_) mustEqual 0)
      }
    }

    "work with floats" >> {
      "be empty initially" >> {
        val stat = floatStat(7, 90, 110, observe = false)

        stat.isEmpty must beTrue
        stat.length mustEqual 7
        stat.bounds mustEqual (90f, 110f)
        forall(0 until 7)(stat.count(_) mustEqual 0)
      }

      "correctly bin values" >> {
        val stat = floatStat(10, 0, 100)

        stat.isEmpty must beFalse
        stat.length mustEqual 10
        stat.bounds mustEqual (0f, 100f)

        forall(0 until 10)(stat.count(_) mustEqual 10)
      }

      "serialize and deserialize" >> {
        val stat = floatStat(7, 90, 110)

        val packed   = StatSerializer(sft).serialize(stat)
        val unpacked = StatSerializer(sft).deserialize(packed)

        unpacked must beAnInstanceOf[Histogram[jFloat]]
        unpacked.asInstanceOf[Histogram[jFloat]].length mustEqual stat.length
        unpacked.asInstanceOf[Histogram[jFloat]].attribute mustEqual stat.attribute
        unpacked.toJson mustEqual stat.toJson
      }

      "serialize and deserialize empty stats" >> {
        val stat = floatStat(7, 90, 110, observe = false)
        val packed   = StatSerializer(sft).serialize(stat)
        val unpacked = StatSerializer(sft).deserialize(packed)

        unpacked must beAnInstanceOf[Histogram[jFloat]]
        unpacked.asInstanceOf[Histogram[jFloat]].length mustEqual stat.length
        unpacked.asInstanceOf[Histogram[jFloat]].attribute mustEqual stat.attribute
        unpacked.toJson mustEqual stat.toJson
      }

      "combine two RangeHistograms" >> {
        val stat = floatStat(10, 0, 100)
        val stat2 = floatStat(10, 100, 200, observe = false)

        features2.foreach { stat2.observe }

        stat2.length mustEqual 10
        stat2.bounds mustEqual (100f, 200f)
        forall(0 until 10)(stat2.count(_) mustEqual 10)

        stat += stat2

        stat.length mustEqual 10
        stat.count(0) mustEqual 15
        forall(1 until 9)(stat.count(_) mustEqual 20)
        stat.count(9) mustEqual 25

        stat2.length mustEqual 10
        stat2.bounds mustEqual (100f, 200f)
        forall(0 until 10)(stat2.count(_) mustEqual 10)
      }

      "clear" >> {
        val stat = floatStat(7, 90, 110)
        stat.clear()

        stat.isEmpty must beTrue
        stat.length mustEqual 7
        forall(0 until 7)(stat.count(_) mustEqual 0)
      }
    }

    "work with doubles" >> {
      "be empty initially" >> {
        val stat = doubleStat(7, 90, 110, observe = false)

        stat.isEmpty must beTrue
        stat.length mustEqual 7
        stat.bounds mustEqual (90.0, 110.0)
        forall(0 until 7)(stat.count(_) mustEqual 0)
      }

      "correctly bin values" >> {
        val stat = doubleStat(10, 0, 99)

        stat.isEmpty must beFalse
        stat.length mustEqual 10
        stat.bounds mustEqual (0.0, 99.0)
        forall(0 until 10)(stat.count(_) mustEqual 10)
      }

      "serialize and deserialize" >> {
        val stat = doubleStat(7, 90, 110)

        val packed   = StatSerializer(sft).serialize(stat)
        val unpacked = StatSerializer(sft).deserialize(packed)

        unpacked must beAnInstanceOf[Histogram[jDouble]]
        unpacked.asInstanceOf[Histogram[jDouble]].length mustEqual stat.length
        unpacked.asInstanceOf[Histogram[jDouble]].attribute mustEqual stat.attribute
        unpacked.toJson mustEqual stat.toJson
      }

      "serialize and deserialize empty stats" >> {
        val stat = doubleStat(7, 90, 110, observe = false)
        val packed   = StatSerializer(sft).serialize(stat)
        val unpacked = StatSerializer(sft).deserialize(packed)

        unpacked must beAnInstanceOf[Histogram[jDouble]]
        unpacked.asInstanceOf[Histogram[jDouble]].length mustEqual stat.length
        unpacked.asInstanceOf[Histogram[jDouble]].attribute mustEqual stat.attribute
        unpacked.toJson mustEqual stat.toJson
      }

      "combine two RangeHistograms" >> {
        val stat = doubleStat(10, 0, 100)
        val stat2 = doubleStat(10, 100, 200, observe = false)

        features2.foreach { stat2.observe }

        stat2.length mustEqual 10
        forall(0 until 10)(stat2.count(_) mustEqual 10)

        stat += stat2

        stat.length mustEqual 10
        stat.bounds mustEqual (0.0, 200.0)

        stat.count(0) mustEqual 15
        forall(1 until 9)(stat.count(_) mustEqual 20)
        stat.count(9) mustEqual 25
        (0 until 10).map(stat.count).sum mustEqual 200

        stat2.length mustEqual 10
        forall(0 until 10)(stat2.count(_) mustEqual 10)
      }

      "clear" >> {
        val stat = doubleStat(7, 90, 110)
        stat.clear()

        stat.isEmpty must beTrue
        stat.length mustEqual 7
        forall(0 until 7)(stat.count(_) mustEqual 0)
      }
    }

    "work with dates" >> {
      "be empty initially" >> {
        val stat = dateStat(24, "2012-01-01T00:00:00.000Z", "2012-01-03T00:00:00.000Z", observe = false)

        stat.isEmpty must beTrue
        stat.length mustEqual 24
        stat.bounds mustEqual (toDate("2012-01-01T00:00:00.000Z"), toDate("2012-01-03T00:00:00.000Z"))
        forall(0 until 24)(stat.count(_) mustEqual 0)
      }

      "correctly bin values" >> {
        val stat = dateStat(24, "2012-01-01T00:00:00.000Z", "2012-01-03T00:00:00.000Z")

        stat.isEmpty must beFalse
        stat.length mustEqual 24
        stat.bounds mustEqual (toDate("2012-01-01T00:00:00.000Z"), toDate("2012-01-03T00:00:00.000Z"))
        forall(0 until 2)(stat.count(_) mustEqual 10)
        forall(2 until 12)(stat.count(_) mustEqual 8)
        forall(12 until 24)(stat.count(_) mustEqual 0)
      }

      "serialize and deserialize" >> {
        val stat = dateStat(24, "2012-01-01T00:00:00.000Z", "2012-01-03T00:00:00.000Z")

        val packed   = StatSerializer(sft).serialize(stat)
        val unpacked = StatSerializer(sft).deserialize(packed)

        unpacked must beAnInstanceOf[Histogram[Date]]
        unpacked.asInstanceOf[Histogram[Date]].length mustEqual stat.length
        unpacked.asInstanceOf[Histogram[Date]].attribute mustEqual stat.attribute
        unpacked.toJson mustEqual stat.toJson
      }

      "serialize and deserialize empty stats" >> {
        val stat = dateStat(24, "2012-01-01T00:00:00.000Z", "2012-01-03T00:00:00.000Z", observe = false)
        val packed   = StatSerializer(sft).serialize(stat)
        val unpacked = StatSerializer(sft).deserialize(packed)

        unpacked must beAnInstanceOf[Histogram[jDouble]]
        unpacked.asInstanceOf[Histogram[jDouble]].length mustEqual stat.length
        unpacked.asInstanceOf[Histogram[jDouble]].attribute mustEqual stat.attribute
        unpacked.toJson mustEqual stat.toJson
      }

      "combine two RangeHistograms" >> {
        val stat = dateStat(24, "2012-01-01T00:00:00.000Z", "2012-01-03T00:00:00.000Z")
        val stat2 = dateStat(24, "2012-01-01T00:00:00.000Z", "2012-01-03T00:00:00.000Z", observe = false)

        features2.foreach { stat2.observe }

        stat2.length mustEqual 24
        forall(0 until 12)(stat2.count(_) mustEqual 0)
        forall((12 until 14) ++ (16 until 24))(stat2.count(_) mustEqual 8)
        forall(15 until 16)(stat2.count(_) mustEqual 10)

        stat += stat2

        stat.length mustEqual 24
        forall((0 until 2) ++ (15 until 16))(stat.count(_) mustEqual 10)
        forall((2 until 14) ++ (16 until 24))(stat.count(_) mustEqual 8)

        stat2.length mustEqual 24
        forall(0 until 12)(stat2.count(_) mustEqual 0)
        forall((12 until 14) ++ (16 until 24))(stat2.count(_) mustEqual 8)
        forall(15 until 16)(stat2.count(_) mustEqual 10)
      }

      "combine two RangeHistograms with weekly splits" >> {
        // simulates the way date histograms will be gathered as we track stats dynamically
        val stat = dateStat(4, "2012-01-01T00:00:00.000Z", "2012-01-28T23:59:59.999Z", observe = false)
        val stat2 = dateStat(5, "2012-01-01T00:00:00.000Z", "2012-02-04T23:59:59.999Z", observe = false)

        val attributes = Array.ofDim[AnyRef](7)
        (1 to 28).foreach { i =>
          attributes(6) = f"2012-01-$i%02dT12:00:00.000Z"
          stat.observe(SimpleFeatureBuilder.build(sft, attributes, ""))
        }
        (29 to 31).foreach { i =>
          attributes(6) = f"2012-01-$i%02dT12:00:00.000Z"
          stat2.observe(SimpleFeatureBuilder.build(sft, attributes, ""))
        }
        (1 to 4).foreach { i =>
          attributes(6) = f"2012-02-$i%02dT12:00:00.000Z"
          stat2.observe(SimpleFeatureBuilder.build(sft, attributes, ""))
        }

        stat.length mustEqual 4
        forall(0 until 4)(stat.count(_) mustEqual 7)

        stat2.length mustEqual 5
        forall(0 until 4)(stat2.count(_) mustEqual 0)
        stat2.count(4) mustEqual 7

        stat += stat2

        stat.length mustEqual 5
        forall(0 until 5)(stat.count(_) mustEqual 7)

        stat2.length mustEqual 5
        forall(0 until 4)(stat2.count(_) mustEqual 0)
        stat2.count(4) mustEqual 7
      }

      "clear" >> {
        val stat = dateStat(24, "2012-01-01T00:00:00.000Z", "2012-01-03T00:00:00.000Z")
        stat.clear()

        stat.isEmpty must beTrue
        stat.length mustEqual 24
        forall(0 until 24)(stat.count(_) mustEqual 0)
      }
    }

    "work with geometries" >> {
      "be empty initially" >> {
        val stat = geomStat(32, "POINT(-180 -90)", "POINT(180 90)", observe = false)

        stat.isEmpty must beTrue
        stat.length mustEqual 32
        stat.bounds mustEqual (toGeom("POINT(-180 -90)"), toGeom("POINT(180 90)"))
        forall(0 until 32)(stat.count(_) mustEqual 0)
      }

      "correctly bin values" >> {
        val stat = geomStat(32, "POINT(-180 -90)", "POINT(180 90)")

        stat.isEmpty must beFalse
        stat.length mustEqual 32
        stat.bounds mustEqual (toGeom("POINT(-180 -90)"), toGeom("POINT(180 90)"))

        stat.count(18) mustEqual 45
        stat.count(19) mustEqual 44
        stat.count(20) mustEqual 9
        stat.count(22) mustEqual 1
        stat.count(24) mustEqual 1
        forall((0 until 18) ++ Seq(21, 23) ++ (25 until 32))(stat.count(_) mustEqual 0)
      }

      "serialize and deserialize" >> {
        val stat = geomStat(32, "POINT(-180 -90)", "POINT(180 90)")

        val packed   = StatSerializer(sft).serialize(stat)
        val unpacked = StatSerializer(sft).deserialize(packed)

        unpacked must beAnInstanceOf[Histogram[jDouble]]
        unpacked.asInstanceOf[Histogram[jDouble]].length mustEqual stat.length
        unpacked.asInstanceOf[Histogram[jDouble]].attribute mustEqual stat.attribute
        unpacked.toJson mustEqual stat.toJson
      }

      "serialize and deserialize empty stats" >> {
        val stat = geomStat(32, "POINT(-180 -90)", "POINT(180 90)", observe = false)
        val packed   = StatSerializer(sft).serialize(stat)
        val unpacked = StatSerializer(sft).deserialize(packed)

        unpacked must beAnInstanceOf[Histogram[jDouble]]
        unpacked.asInstanceOf[Histogram[jDouble]].length mustEqual stat.length
        unpacked.asInstanceOf[Histogram[jDouble]].attribute mustEqual stat.attribute
        unpacked.toJson mustEqual stat.toJson
      }

      "combine two RangeHistograms" >> {
        val stat = geomStat(32, "POINT(-180 -90)", "POINT(180 90)")
        val stat2 = geomStat(32, "POINT(-180 -90)", "POINT(180 90)", observe = false)

        features2.foreach { stat2.observe }

        stat2.length mustEqual 32
        stat2.count(25) mustEqual 10
        stat2.count(27) mustEqual 20
        stat2.count(30) mustEqual 46
        stat2.count(31) mustEqual 24
        forall((0 until 25) ++ Seq(26, 28, 29))(stat2.count(_) mustEqual 0)

        stat += stat2

        stat.count(18) mustEqual 45
        stat.count(19) mustEqual 44
        stat.count(20) mustEqual 9
        stat.count(22) mustEqual 1
        stat.count(24) mustEqual 1
        stat.count(25) mustEqual 10
        stat.count(27) mustEqual 20
        stat.count(30) mustEqual 46
        stat.count(31) mustEqual 24
        forall((0 until 18) ++ Seq(21, 23, 26, 28, 29))(stat.count(_) mustEqual 0)

        stat2.length mustEqual 32
        stat2.count(25) mustEqual 10
        stat2.count(27) mustEqual 20
        stat2.count(30) mustEqual 46
        stat2.count(31) mustEqual 24
        forall((0 until 25) ++ Seq(26, 28, 29))(stat2.count(_) mustEqual 0)
      }

      "clear" >> {
        val stat = geomStat(32, "POINT(-180 -90)", "POINT(180 90)")
        stat.clear()

        stat.isEmpty must beTrue
        stat.length mustEqual 32
        forall(0 until 32)(stat.count(_) mustEqual 0)
      }
    }
  }
}
