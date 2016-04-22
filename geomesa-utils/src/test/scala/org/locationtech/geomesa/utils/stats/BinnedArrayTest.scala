/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.utils.stats

import java.util.Date

import com.vividsolutions.jts.geom.Point
import org.junit.runner.RunWith
import org.locationtech.geomesa.utils.text.WKTUtils
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class BinnedArrayTest extends Specification with StatTestHelper {

  "BinnedArray" should {
    "bin integers" >> {
      val array = new BinnedIntegerArray(10, (0, 99))
      forall(0 to 9)(array.indexOf(_) mustEqual 0)
      forall(10 to 19)(array.indexOf(_) mustEqual 1)
      forall(20 to 29)(array.indexOf(_) mustEqual 2)
      forall(30 to 39)(array.indexOf(_) mustEqual 3)
      forall(40 to 49)(array.indexOf(_) mustEqual 4)
      forall(50 to 59)(array.indexOf(_) mustEqual 5)
      forall(60 to 69)(array.indexOf(_) mustEqual 6)
      forall(70 to 79)(array.indexOf(_) mustEqual 7)
      forall(80 to 89)(array.indexOf(_) mustEqual 8)
      forall(90 to 99)(array.indexOf(_) mustEqual 9)
      array.medianValue(0) mustEqual 5
      array.medianValue(1) mustEqual 15
      array.medianValue(2) mustEqual 25
      array.medianValue(3) mustEqual 35
      array.medianValue(4) mustEqual 45
      array.medianValue(5) mustEqual 54
      array.medianValue(6) mustEqual 64
      array.medianValue(7) mustEqual 74
      array.medianValue(8) mustEqual 84
      array.medianValue(9) mustEqual 94
      array.bounds(0) mustEqual (0, 9)
      array.bounds(1) mustEqual (10, 19)
      array.bounds(2) mustEqual (20, 29)
      array.bounds(3) mustEqual (30, 39)
      array.bounds(4) mustEqual (40, 49)
      array.bounds(5) mustEqual (50, 59)
      array.bounds(6) mustEqual (60, 69)
      array.bounds(7) mustEqual (70, 79)
      array.bounds(8) mustEqual (80, 89)
      array.bounds(9) mustEqual (90, 99)
    }

    "bin longs" >> {
      val array = new BinnedLongArray(10, (0L, 99L))
      forall(0 to 9)(i => array.indexOf(i.toLong) mustEqual 0)
      forall(10 to 19)(i => array.indexOf(i.toLong) mustEqual 1)
      forall(20 to 29)(i => array.indexOf(i.toLong) mustEqual 2)
      forall(30 to 39)(i => array.indexOf(i.toLong) mustEqual 3)
      forall(40 to 49)(i => array.indexOf(i.toLong) mustEqual 4)
      forall(50 to 59)(i => array.indexOf(i.toLong) mustEqual 5)
      forall(60 to 69)(i => array.indexOf(i.toLong) mustEqual 6)
      forall(70 to 79)(i => array.indexOf(i.toLong) mustEqual 7)
      forall(80 to 89)(i => array.indexOf(i.toLong) mustEqual 8)
      forall(90 to 99)(i => array.indexOf(i.toLong) mustEqual 9)
      array.medianValue(0) mustEqual 5L
      array.medianValue(1) mustEqual 15L
      array.medianValue(2) mustEqual 25L
      array.medianValue(3) mustEqual 35L
      array.medianValue(4) mustEqual 45L
      array.medianValue(5) mustEqual 54L
      array.medianValue(6) mustEqual 64L
      array.medianValue(7) mustEqual 74L
      array.medianValue(8) mustEqual 84L
      array.medianValue(9) mustEqual 94L
      array.bounds(0) mustEqual (0L, 9L)
      array.bounds(1) mustEqual (10L, 19L)
      array.bounds(2) mustEqual (20L, 29L)
      array.bounds(3) mustEqual (30L, 39L)
      array.bounds(4) mustEqual (40L, 49L)
      array.bounds(5) mustEqual (50L, 59L)
      array.bounds(6) mustEqual (60L, 69L)
      array.bounds(7) mustEqual (70L, 79L)
      array.bounds(8) mustEqual (80L, 89L)
      array.bounds(9) mustEqual (90L, 99L)
    }

    "bin floats" >> {
      val array = new BinnedFloatArray(10, (0f, 1f))
      forall(0 to 9)(i => array.indexOf(0.0f + 0.01f * i) mustEqual 0)
      forall(0 to 9)(i => array.indexOf(0.1f + 0.01f * i) mustEqual 1)
      forall(0 to 9)(i => array.indexOf(0.2f + 0.01f * i) mustEqual 2)
      forall(0 to 9)(i => array.indexOf(0.3f + 0.01f * i) mustEqual 3)
      forall(0 to 9)(i => array.indexOf(0.4f + 0.01f * i) mustEqual 4)
      forall(0 to 9)(i => array.indexOf(0.5f + 0.01f * i) mustEqual 5)
      forall(0 to 9)(i => array.indexOf(0.6f + 0.01f * i) mustEqual 6)
      forall(0 to 9)(i => array.indexOf(0.7f + 0.01f * i) mustEqual 7)
      forall(0 to 9)(i => array.indexOf(0.8f + 0.01f * i) mustEqual 8)
      forall(0 to 9)(i => array.indexOf(0.9f + 0.01f * i) mustEqual 9)
      Float.unbox(array.medianValue(0)) must beCloseTo(0.05f, 0.001f)
      Float.unbox(array.medianValue(1)) must beCloseTo(0.15f, 0.001f)
      Float.unbox(array.medianValue(2)) must beCloseTo(0.25f, 0.001f)
      Float.unbox(array.medianValue(3)) must beCloseTo(0.35f, 0.001f)
      Float.unbox(array.medianValue(4)) must beCloseTo(0.45f, 0.001f)
      Float.unbox(array.medianValue(5)) must beCloseTo(0.55f, 0.001f)
      Float.unbox(array.medianValue(6)) must beCloseTo(0.65f, 0.001f)
      Float.unbox(array.medianValue(7)) must beCloseTo(0.75f, 0.001f)
      Float.unbox(array.medianValue(8)) must beCloseTo(0.85f, 0.001f)
      Float.unbox(array.medianValue(9)) must beCloseTo(0.95f, 0.001f)
      def toSeq(t: (java.lang.Float, java.lang.Float)) = Seq[Float](t._1, t._2)
      toSeq(array.bounds(0)) must contain(allOf(beCloseTo(0.0f, 0.001f), beCloseTo(0.1f, 0.001f)).inOrder)
      toSeq(array.bounds(1)) must contain(allOf(beCloseTo(0.1f, 0.001f), beCloseTo(0.2f, 0.001f)).inOrder)
      toSeq(array.bounds(2)) must contain(allOf(beCloseTo(0.2f, 0.001f), beCloseTo(0.3f, 0.001f)).inOrder)
      toSeq(array.bounds(3)) must contain(allOf(beCloseTo(0.3f, 0.001f), beCloseTo(0.4f, 0.001f)).inOrder)
      toSeq(array.bounds(4)) must contain(allOf(beCloseTo(0.4f, 0.001f), beCloseTo(0.5f, 0.001f)).inOrder)
      toSeq(array.bounds(5)) must contain(allOf(beCloseTo(0.5f, 0.001f), beCloseTo(0.6f, 0.001f)).inOrder)
      toSeq(array.bounds(6)) must contain(allOf(beCloseTo(0.6f, 0.001f), beCloseTo(0.7f, 0.001f)).inOrder)
      toSeq(array.bounds(7)) must contain(allOf(beCloseTo(0.7f, 0.001f), beCloseTo(0.8f, 0.001f)).inOrder)
      toSeq(array.bounds(8)) must contain(allOf(beCloseTo(0.8f, 0.001f), beCloseTo(0.9f, 0.001f)).inOrder)
      toSeq(array.bounds(9)) must contain(allOf(beCloseTo(0.9f, 0.001f), beCloseTo(1.0f, 0.001f)).inOrder)
    }

    "bin doubles" >> {
      val array = new BinnedDoubleArray(10, (0.0, 1.0))
      forall(1 to 9)(i => array.indexOf(0.0 + 0.01 * i) mustEqual 0)
      forall(1 to 9)(i => array.indexOf(0.1 + 0.01 * i) mustEqual 1)
      forall(1 to 9)(i => array.indexOf(0.2 + 0.01 * i) mustEqual 2)
      forall(1 to 9)(i => array.indexOf(0.3 + 0.01 * i) mustEqual 3)
      forall(1 to 9)(i => array.indexOf(0.4 + 0.01 * i) mustEqual 4)
      forall(1 to 9)(i => array.indexOf(0.5 + 0.01 * i) mustEqual 5)
      forall(1 to 9)(i => array.indexOf(0.6 + 0.01 * i) mustEqual 6)
      forall(1 to 9)(i => array.indexOf(0.7 + 0.01 * i) mustEqual 7)
      forall(1 to 9)(i => array.indexOf(0.8 + 0.01 * i) mustEqual 8)
      forall(1 to 9)(i => array.indexOf(0.9 + 0.01 * i) mustEqual 9)
      Double.unbox(array.medianValue(0)) must beCloseTo(0.05, 0.001)
      Double.unbox(array.medianValue(1)) must beCloseTo(0.15, 0.001)
      Double.unbox(array.medianValue(2)) must beCloseTo(0.25, 0.001)
      Double.unbox(array.medianValue(3)) must beCloseTo(0.35, 0.001)
      Double.unbox(array.medianValue(4)) must beCloseTo(0.45, 0.001)
      Double.unbox(array.medianValue(5)) must beCloseTo(0.55, 0.001)
      Double.unbox(array.medianValue(6)) must beCloseTo(0.65, 0.001)
      Double.unbox(array.medianValue(7)) must beCloseTo(0.75, 0.001)
      Double.unbox(array.medianValue(8)) must beCloseTo(0.85, 0.001)
      Double.unbox(array.medianValue(9)) must beCloseTo(0.95, 0.001)
      def toSeq(t: (java.lang.Double, java.lang.Double)) = Seq[Double](t._1, t._2)
      toSeq(array.bounds(0)) must contain(allOf(beCloseTo(0.0, 0.001), beCloseTo(0.1, 0.001)).inOrder)
      toSeq(array.bounds(1)) must contain(allOf(beCloseTo(0.1, 0.001), beCloseTo(0.2, 0.001)).inOrder)
      toSeq(array.bounds(2)) must contain(allOf(beCloseTo(0.2, 0.001), beCloseTo(0.3, 0.001)).inOrder)
      toSeq(array.bounds(3)) must contain(allOf(beCloseTo(0.3, 0.001), beCloseTo(0.4, 0.001)).inOrder)
      toSeq(array.bounds(4)) must contain(allOf(beCloseTo(0.4, 0.001), beCloseTo(0.5, 0.001)).inOrder)
      toSeq(array.bounds(5)) must contain(allOf(beCloseTo(0.5, 0.001), beCloseTo(0.6, 0.001)).inOrder)
      toSeq(array.bounds(6)) must contain(allOf(beCloseTo(0.6, 0.001), beCloseTo(0.7, 0.001)).inOrder)
      toSeq(array.bounds(7)) must contain(allOf(beCloseTo(0.7, 0.001), beCloseTo(0.8, 0.001)).inOrder)
      toSeq(array.bounds(8)) must contain(allOf(beCloseTo(0.8, 0.001), beCloseTo(0.9, 0.001)).inOrder)
      toSeq(array.bounds(9)) must contain(allOf(beCloseTo(0.9, 0.001), beCloseTo(1.0, 0.001)).inOrder)
    }

    "bin dates" >> {
      import org.locationtech.geomesa.utils.geotools.GeoToolsDateFormat
      def toDate(hh: Int, mm: Int) = GeoToolsDateFormat.parseDateTime(f"2016-01-01T$hh%02d:$mm%02d:00.000Z").toDate

      val array = new BinnedDateArray(10, (toDate(0, 0), toDate(10, 0)))
      forall(0 to 59)(i => array.indexOf(toDate(0, i)) mustEqual 0)
      forall(0 to 59)(i => array.indexOf(toDate(1, i)) mustEqual 1)
      forall(0 to 59)(i => array.indexOf(toDate(2, i)) mustEqual 2)
      forall(0 to 59)(i => array.indexOf(toDate(3, i)) mustEqual 3)
      forall(0 to 59)(i => array.indexOf(toDate(4, i)) mustEqual 4)
      forall(0 to 59)(i => array.indexOf(toDate(5, i)) mustEqual 5)
      forall(0 to 59)(i => array.indexOf(toDate(6, i)) mustEqual 6)
      forall(0 to 59)(i => array.indexOf(toDate(7, i)) mustEqual 7)
      forall(0 to 59)(i => array.indexOf(toDate(8, i)) mustEqual 8)
      forall(0 to 59)(i => array.indexOf(toDate(9, i)) mustEqual 9)
      array.medianValue(0) mustEqual toDate(0, 30)
      array.medianValue(1) mustEqual toDate(1, 30)
      array.medianValue(2) mustEqual toDate(2, 30)
      array.medianValue(3) mustEqual toDate(3, 30)
      array.medianValue(4) mustEqual toDate(4, 30)
      array.medianValue(5) mustEqual toDate(5, 30)
      array.medianValue(6) mustEqual toDate(6, 30)
      array.medianValue(7) mustEqual toDate(7, 30)
      array.medianValue(8) mustEqual toDate(8, 30)
      array.medianValue(9) mustEqual toDate(9, 30)
      array.bounds(0) mustEqual (toDate(0, 0), new Date(toDate(1, 0).getTime - 1))
      array.bounds(1) mustEqual (toDate(1, 0), new Date(toDate(2, 0).getTime - 1))
      array.bounds(2) mustEqual (toDate(2, 0), new Date(toDate(3, 0).getTime - 1))
      array.bounds(3) mustEqual (toDate(3, 0), new Date(toDate(4, 0).getTime - 1))
      array.bounds(4) mustEqual (toDate(4, 0), new Date(toDate(5, 0).getTime - 1))
      array.bounds(5) mustEqual (toDate(5, 0), new Date(toDate(6, 0).getTime - 1))
      array.bounds(6) mustEqual (toDate(6, 0), new Date(toDate(7, 0).getTime - 1))
      array.bounds(7) mustEqual (toDate(7, 0), new Date(toDate(8, 0).getTime - 1))
      array.bounds(8) mustEqual (toDate(8, 0), new Date(toDate(9, 0).getTime - 1))
      array.bounds(9) mustEqual (toDate(9, 0), new Date(toDate(10, 0).getTime - 1))
    }

    "bin strings" >> {
      val array = new BinnedStringArray(36, ("aa0", "aaz"))
      forall(0 until 10)(i => array.indexOf("aa" + ('0' + i).toChar + ('0' + 12).toChar) mustEqual i)
      forall(0 until 25)(i => array.indexOf("aa" + ('a' + i).toChar + ('0' + 12).toChar) mustEqual i + 10)
      array.indexOf("aaz") mustEqual 35
      forall(0 until 10)(i => array.medianValue(i) mustEqual s"aa$i")
      forall(10 until 36)(i => array.medianValue(i) mustEqual "aa" + ('a'.toInt + i - 10).toChar)
    }

    "bin points" >> {
      def toPoint(x: Double, y: Double) = WKTUtils.read(s"POINT ($x $y)")
      val xys = (1 to 18).flatMap(i => (1 to 9).map((i, _)))

      val array = new BinnedGeometryArray(4, (toPoint(-180, -90), toPoint(180, 90)))
      forall(xys) { case (x, y) => array.indexOf(toPoint(-10 * x, -10 * y)) mustEqual 0 }
      forall(xys) { case (x, y) => array.indexOf(toPoint(-10 * x,  10 * y)) mustEqual 1 }
      forall(xys) { case (x, y) => array.indexOf(toPoint( 10 * x, -10 * y)) mustEqual 2 }
      forall(xys) { case (x, y) => array.indexOf(toPoint( 10 * x,  10 * y)) mustEqual 3 }

      val m0 = array.medianValue(0).asInstanceOf[Point]
      m0.getX must beLessThan(0.0)
      m0.getY must beLessThan(0.0)
      val m1 = array.medianValue(1).asInstanceOf[Point]
      m1.getX must beLessThan(0.0)
      m1.getY must beGreaterThan(0.0)
      val m2 = array.medianValue(2).asInstanceOf[Point]
      m2.getX must beGreaterThan(0.0)
      m2.getY must beLessThan(0.0)
      val m3 = array.medianValue(3).asInstanceOf[Point]
      m3.getX must beGreaterThan(0.0)
      m3.getY must beGreaterThan(0.0)
    }
  }
}
