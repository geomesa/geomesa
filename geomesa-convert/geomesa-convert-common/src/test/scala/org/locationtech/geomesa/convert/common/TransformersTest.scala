/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.convert.common

import java.util.Date

import com.google.common.hash.Hashing
import com.vividsolutions.jts.geom._
import org.apache.commons.codec.binary.Base64
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.locationtech.geomesa.convert.Transformers
import org.locationtech.geomesa.convert.Transformers.{EvaluationContext, EvaluationContextImpl}
import org.locationtech.geomesa.utils.text.WKTUtils
import org.specs2.matcher.Matcher

import org.specs2.runner.JUnitRunner

import scala.util.Random

@RunWith(classOf[JUnitRunner])
class TransformersTest extends org.specs2.mutable.Spec {

  "Transformers" should {

    implicit val ctx = EvaluationContext.empty

    "handle transformations" >> {

      "handle string transformations" >> {
        "allow literal strings" >> {
          val exp = Transformers.parseTransform("'hello'")
          exp.eval(Array(null)) mustEqual "hello"
        }
        "allow quoted strings" >> {
          val exp = Transformers.parseTransform("'he\\'llo'")
          exp.eval(Array(null)) mustEqual "he'llo"
        }
        "allow empty literal strings" >> {
          val exp = Transformers.parseTransform("''")
          exp.eval(Array(null)) mustEqual ""
        }
        "allow native ints" >> {
          val res = Transformers.parseTransform("1").eval(Array(null))
          res must beAnInstanceOf[java.lang.Integer].asInstanceOf[Matcher[Any]]
          res mustEqual 1
        }
        "allow native longs" >> {
          val res = Transformers.parseTransform("1L").eval(Array(null))
          res must beAnInstanceOf[java.lang.Long].asInstanceOf[Matcher[Any]]
          res mustEqual 1L
        }
        "allow native floats" >> {
          val f = Transformers.parseTransform("1.0f").eval(Array(null))
          f must beAnInstanceOf[java.lang.Float].asInstanceOf[Matcher[Any]]
          f mustEqual 1.0f
          val F = Transformers.parseTransform("1.0F").eval(Array(null))
          F must beAnInstanceOf[java.lang.Float].asInstanceOf[Matcher[Any]]
          F mustEqual 1.0f
        }
        "allow native doubles" >> {
          val res = Transformers.parseTransform("1.0").eval(Array(null))
          res must beAnInstanceOf[java.lang.Double].asInstanceOf[Matcher[Any]]
          res mustEqual 1.0d
          val d = Transformers.parseTransform("1.0d").eval(Array(null))
          d must beAnInstanceOf[java.lang.Double].asInstanceOf[Matcher[Any]]
          d mustEqual 1.0d
          val D = Transformers.parseTransform("1.0D").eval(Array(null))
          D must beAnInstanceOf[java.lang.Double].asInstanceOf[Matcher[Any]]
          D mustEqual 1.0d
        }
        "allow native booleans" >> {
          Transformers.parseTransform("false").eval(Array(null)) mustEqual false
          Transformers.parseTransform("true").eval(Array(null)) mustEqual true
        }
        "allow native nulls" >> {
          Transformers.parseTransform("null").eval(Array(null)) must beNull
        }
        "trim" >> {
          val exp = Transformers.parseTransform("trim($1)")
          exp.eval(Array("", "foo ", "bar")) mustEqual "foo"
        }
        "capitalize" >> {
          val exp = Transformers.parseTransform("capitalize($1)")
          exp.eval(Array("", "foo", "bar")) mustEqual "Foo"
        }
        "lowercase" >> {
          val exp = Transformers.parseTransform("lowercase($1)")
          exp.eval(Array("", "FOO", "bar")) mustEqual "foo"
        }
        "uppercase" >> {
          val exp = Transformers.parseTransform("uppercase($1)")
          exp.eval(Array("", "FoO")) mustEqual "FOO"
        }
        "regexReplace" >> {
          val exp = Transformers.parseTransform("regexReplace('foo'::r,'bar',$1)")
          exp.eval(Array("", "foobar")) mustEqual "barbar"
        }
        "compound expression" >> {
          val exp = Transformers.parseTransform("regexReplace('foo'::r,'bar',trim($1))")
          exp.eval(Array("", " foobar ")) mustEqual "barbar"
        }
        "substr" >> {
          val exp = Transformers.parseTransform("substr($1, 2, 5)")
          exp.eval(Array("", "foobarbaz")) mustEqual "foobarbaz".substring(2, 5)
        }
        "substring" >> {
          val exp = Transformers.parseTransform("substring($1, 2, 5)")
          exp.eval(Array("", "foobarbaz")) mustEqual "foobarbaz".substring(2, 5)
        }
        "strlen" >> {
          val exp = Transformers.parseTransform("strlen($1)")
          exp.eval(Array("", "FOO")) mustEqual 3
        }
        "length" >> {
          val exp = Transformers.parseTransform("length($1)")
          exp.eval(Array("", "FOO")) mustEqual 3
        }
        "toString" >> {
          val exp = Transformers.parseTransform("toString($1)")
          exp.eval(Array("", 5)) mustEqual "5"
        }
        "concat with tostring" >> {
          val exp = Transformers.parseTransform("concat(toString($1), toString($2))")
          exp.eval(Array("", 5, 6)) mustEqual "56"
        }
        "concat many args" >> {
          val exp = Transformers.parseTransform("concat($1, $2, $3, $4, $5, $6)")
          exp.eval(Array("", 1, 2, 3, 4, 5, 6)) mustEqual "123456"
        }
        "mkstring" >> {
          val exp = Transformers.parseTransform("mkstring(',', $1, $2, $3, $4, $5, $6)")
          exp.eval(Array("", 1, 2, 3, 4, 5, 6)) mustEqual "1,2,3,4,5,6"
        }
      }

      "handle non-string literals" >> {
        "input is an int" >> {
          val exp = Transformers.parseTransform("$2")
          exp.eval(Array("", "1", 2)) mustEqual 2
        }
        "cast to int" >> {
          val exp = Transformers.parseTransform("$1::int")
          exp.eval(Array("", "1")) mustEqual 1
        }
        "cast to integer" >> {
          val exp = Transformers.parseTransform("$1::integer")
          exp.eval(Array("", "1")) mustEqual 1
        }
        "cast to bool" >> {
          val exp = Transformers.parseTransform("$1::bool")
          exp.eval(Array("", "true")) mustEqual true
        }
        "cast to boolean" >> {
          val exp = Transformers.parseTransform("$1::boolean")
          exp.eval(Array("", "false")) mustEqual false
        }
        "cast to string" >> {
          val exp = Transformers.parseTransform("$1::string")
          exp.eval(Array("", "1")) mustEqual "1"
          exp.eval(Array("", 1)) mustEqual "1"
        }
      }

      "handle dates" >> {
        val testDate = DateTime.parse("2015-01-01T00:00:00.000Z").toDate

        "date with custom format" >> {
          val exp = Transformers.parseTransform("date('yyyyMMdd', $1)")
          exp.eval(Array("", "20150101")).asInstanceOf[Date] mustEqual testDate
        }
        "date with a realistic custom format" >> {
          val exp = Transformers.parseTransform("date('YYYY-MM-dd\\'T\\'HH:mm:ss.SSSSSS', $1)")
          exp.eval(Array("", "2015-01-01T00:00:00.000000")).asInstanceOf[Date] mustEqual testDate
        }
        "datetime" >> {
          val exp = Transformers.parseTransform("datetime($1)")
          exp.eval(Array("", "2015-01-01T00:00:00.000Z")).asInstanceOf[Date] mustEqual testDate
        }
        "dateTime" >> {
          val exp = Transformers.parseTransform("dateTime($1)")
          exp.eval(Array("", "2015-01-01T00:00:00.000Z")).asInstanceOf[Date] mustEqual testDate
        }
        "isodate" >> {
          val exp = Transformers.parseTransform("isodate($1)")
          exp.eval(Array("", "20150101")).asInstanceOf[Date] mustEqual testDate
        }
        "basicDate" >> {
          val exp = Transformers.parseTransform("basicDate($1)")
          exp.eval(Array("", "20150101")).asInstanceOf[Date] mustEqual testDate
        }
        "isodatetime" >> {
          val exp = Transformers.parseTransform("isodatetime($1)")
          exp.eval(Array("", "20150101T000000.000Z")).asInstanceOf[Date] mustEqual testDate
        }
        "basicDateTime" >> {
          val exp = Transformers.parseTransform("basicDateTime($1)")
          exp.eval(Array("", "20150101T000000.000Z")).asInstanceOf[Date] mustEqual testDate
        }
        "basicDateTimeNoMillis" >> {
          val exp = Transformers.parseTransform("basicDateTimeNoMillis($1)")
          exp.eval(Array("", "20150101T000000Z")).asInstanceOf[Date] mustEqual testDate
        }
        "dateHourMinuteSecondMillis" >> {
          val exp = Transformers.parseTransform("dateHourMinuteSecondMillis($1)")
          exp.eval(Array("", "2015-01-01T00:00:00.000")).asInstanceOf[Date] mustEqual testDate
        }
        "millisToDate" >> {
          val millis = testDate.getTime
          val exp = Transformers.parseTransform("millisToDate($1)")
          exp.eval(Array("", millis)).asInstanceOf[Date] mustEqual testDate
        }

        "secsToDate" >> {
          val secs = testDate.getTime / 1000L
          val exp = Transformers.parseTransform("secsToDate($1)")
          exp.eval(Array("", secs)).asInstanceOf[Date] mustEqual testDate
        }

      }

      "handle point geometries" >> {
        val exp = Transformers.parseTransform("point($1, $2)")
        exp.eval(Array("", 45.0, 45.0)).asInstanceOf[Point].getCoordinate mustEqual new Coordinate(45.0, 45.0)

        val trans = Transformers.parseTransform("point($0)")
        trans.eval(Array("POINT(50 52)")).asInstanceOf[Point].getCoordinate mustEqual new Coordinate(50, 52)

        // turn "Geometry" into "Point"
        val geoFac = new GeometryFactory()
        val geom = geoFac.createPoint(new Coordinate(55, 56)).asInstanceOf[Geometry]
        val res = trans.eval(Array(geom))
        res must beAnInstanceOf[Point].asInstanceOf[Matcher[Any]]
        res.asInstanceOf[Point] mustEqual geoFac.createPoint(new Coordinate(55, 56))
      }

      "handle linestring wkt" >> {
        val geoFac = new GeometryFactory()
        val lineStr = geoFac.createLineString(Seq((102, 0), (103, 1), (104, 0), (105, 1)).map{ case (x,y) => new Coordinate(x, y)}.toArray)
        val trans = Transformers.parseTransform("linestring($0)")
        trans.eval(Array("Linestring(102 0, 103 1, 104 0, 105 1)")).asInstanceOf[LineString] mustEqual lineStr

        // type conversion
        val geom = lineStr.asInstanceOf[Geometry]
        val res = trans.eval(Array(geom))
        res must beAnInstanceOf[LineString].asInstanceOf[Matcher[Any]]
        res.asInstanceOf[LineString] mustEqual WKTUtils.read("Linestring(102 0, 103 1, 104 0, 105 1)")
      }

      "handle polygon wkt" >> {
        val geoFac = new GeometryFactory()
        val poly = geoFac.createPolygon(Seq((100, 0), (101, 0), (101, 1), (100, 1), (100, 0)).map{ case (x,y) => new Coordinate(x, y)}.toArray)
        val trans = Transformers.parseTransform("polygon($0)")
        trans.eval(Array("polygon((100 0, 101 0, 101 1, 100 1, 100 0))")).asInstanceOf[Polygon] mustEqual poly

        // type conversion
        val geom = poly.asInstanceOf[Polygon]
        val res = trans.eval(Array(geom))
        res must beAnInstanceOf[Polygon].asInstanceOf[Matcher[Any]]
        res.asInstanceOf[Polygon] mustEqual WKTUtils.read("polygon((100 0, 101 0, 101 1, 100 1, 100 0))")
      }

      "handle geometry wkt" >> {
        val geoFac = new GeometryFactory()
        val lineStr = geoFac.createLineString(Seq((102, 0), (103, 1), (104, 0), (105, 1)).map{ case (x,y) => new Coordinate(x, y)}.toArray)
        val trans = Transformers.parseTransform("geometry($0)")
        trans.eval(Array("Linestring(102 0, 103 1, 104 0, 105 1)")).asInstanceOf[Geometry] mustEqual lineStr
      }

      "handle identity functions" >> {
        val bytes = Array.ofDim[Byte](32)
        Random.nextBytes(bytes)
        "md5" >> {
          val hasher = Hashing.md5().newHasher()
          val exp = Transformers.parseTransform("md5($0)")
          val hashedResult = exp.eval(Array(bytes)).asInstanceOf[String]
          hashedResult mustEqual hasher.putBytes(bytes).hash().toString
        }
        "uuid" >> {
          val exp = Transformers.parseTransform("uuid()")
          exp.eval(Array(null)) must beAnInstanceOf[String].asInstanceOf[Matcher[Any]]
        }
        "base64" >> {
          val exp = Transformers.parseTransform("base64($0)")
          exp.eval(Array(bytes)) mustEqual Base64.encodeBase64URLSafeString(bytes)
        }
      }

      "handle named values" >> {
        implicit val ctx = EvaluationContext(IndexedSeq(null, "foo", null), Array[Any](null, "bar", null), null)
        val exp = Transformers.parseTransform("capitalize($foo)")
        exp.eval(Array(null))(ctx) mustEqual "Bar"
      }

      "handle exceptions to casting" >> {
        val exp = Transformers.parseTransform("try($1::int, 0)")
        exp.eval(Array("", "1")).asInstanceOf[Int] mustEqual 1
        exp.eval(Array("", "")).asInstanceOf[Int] mustEqual 0
        exp.eval(Array("", "abcd")).asInstanceOf[Int] mustEqual 0
      }

      "handle exceptions to millisecond conversions" >> {
        val exp = Transformers.parseTransform("try(millisToDate($1), now())")
        val millis = 100000L
        exp.eval(Array("", millis)).asInstanceOf[Date] mustEqual new Date(millis)
        exp.eval(Array("", "")).asInstanceOf[Date].getTime must beCloseTo(System.currentTimeMillis(), 100)
        exp.eval(Array("", "abcd")).asInstanceOf[Date].getTime must beCloseTo(System.currentTimeMillis(), 100)
      }

      "handle exceptions to millisecond conversions with null defaults" >> {
        val exp = Transformers.parseTransform("try(millisToDate($1), null)")
        val millis = 100000L
        exp.eval(Array("", millis)).asInstanceOf[Date] mustEqual new Date(millis)
        exp.eval(Array("", "")).asInstanceOf[Date] must beNull
        exp.eval(Array("", "abcd")).asInstanceOf[Date] must beNull
      }

      "handle exceptions to second conversions" >> {
        val exp = Transformers.parseTransform("try(secsToDate($1), now())")
        val secs = 100L
        exp.eval(Array("", secs)).asInstanceOf[Date] mustEqual new Date(secs*1000L)
        exp.eval(Array("", "")).asInstanceOf[Date].getTime must beCloseTo(System.currentTimeMillis(), 1000)
        exp.eval(Array("", "abcd")).asInstanceOf[Date].getTime must beCloseTo(System.currentTimeMillis(), 100)
      }

      "handle exceptions to second conversions with null defaults" >> {
        val exp = Transformers.parseTransform("try(secsToDate($1), null)")
        val secs = 100L
        exp.eval(Array("", secs)).asInstanceOf[Date] mustEqual new Date(secs*1000L)
        exp.eval(Array("", "")).asInstanceOf[Date] must beNull
        exp.eval(Array("", "abcd")).asInstanceOf[Date] must beNull
      }
    }

    "handle predicates" >> {
      "string equals" >> {
        val exp = Transformers.parsePred("strEq($1, $2)")
        exp.eval(Array("", "1", "2")) must beFalse
        exp.eval(Array("", "1", "1")) must beTrue
      }

      "numeric predicates" >> {
        "int equals" >> {
          val exp = Transformers.parsePred("intEq($1::int, $2::int)")
          exp.eval(Array("", "1", "2")) must beFalse
          exp.eval(Array("", "1", "1")) must beTrue
        }
        "integer equals" >> {
          val exp = Transformers.parsePred("integerEq($1::int, $2::int)")
          exp.eval(Array("", "1", "2")) must beFalse
          exp.eval(Array("", "1", "1")) must beTrue
        }
        "nested int equals" >> {
          val exp = Transformers.parsePred("intEq($1::int, strlen($2))")
          exp.eval(Array("", "3", "foo")) must beTrue
          exp.eval(Array("", "4", "foo")) must beFalse
        }
        "int lteq" >> {
          val exp = Transformers.parsePred("intLTEq($1::int, $2::int)")
          exp.eval(Array("", "1", "2")) must beTrue
          exp.eval(Array("", "1", "1")) must beTrue
          exp.eval(Array("", "1", "0")) must beFalse
        }
        "int lt" >> {
          val exp = Transformers.parsePred("intLT($1::int, $2::int)")
          exp.eval(Array("", "1", "2")) must beTrue
          exp.eval(Array("", "1", "1")) must beFalse
        }
        "int gteq" >> {
          val exp = Transformers.parsePred("intGTEq($1::int, $2::int)")
          exp.eval(Array("", "1", "2")) must beFalse
          exp.eval(Array("", "1", "1")) must beTrue
          exp.eval(Array("", "2", "1")) must beTrue
        }
        "int gt" >> {
          val exp = Transformers.parsePred("intGT($1::int, $2::int)")
          exp.eval(Array("", "1", "2")) must beFalse
          exp.eval(Array("", "1", "1")) must beFalse
          exp.eval(Array("", "2", "1")) must beTrue
        }
        "double equals" >> {
          val exp = Transformers.parsePred("doubleEq($1::double, $2::double)")
          exp.eval(Array("", "1.0", "2.0")) must beFalse
          exp.eval(Array("", "1.0", "1.0")) must beTrue
        }
        "double lteq" >> {
          val exp = Transformers.parsePred("doubleLTEq($1::double, $2::double)")
          exp.eval(Array("", "1.0", "2.0")) must beTrue
          exp.eval(Array("", "1.0", "1.0")) must beTrue
          exp.eval(Array("", "1.0", "0.0")) must beFalse
        }
        "double lt" >> {
          val exp = Transformers.parsePred("doubleLT($1::double, $2::double)")
          exp.eval(Array("", "1.0", "2.0")) must beTrue
          exp.eval(Array("", "1.0", "1.0")) must beFalse
        }
        "double gteq" >> {
          val exp = Transformers.parsePred("doubleGTEq($1::double, $2::double)")
          exp.eval(Array("", "1.0", "2.0")) must beFalse
          exp.eval(Array("", "1.0", "1.0")) must beTrue
          exp.eval(Array("", "2.0", "1.0")) must beTrue
        }
        "double gt" >> {
          val exp = Transformers.parsePred("doubleGT($1::double, $2::double)")
          exp.eval(Array("", "1.0", "2.0")) must beFalse
          exp.eval(Array("", "1.0", "1.0")) must beFalse
          exp.eval(Array("", "2.0", "1.0")) must beTrue
        }
      }

      "stringTo functions" >> {
        "stringToDouble" >> {
          "double stringToDouble zero default" >> {
            val exp = Transformers.parseTransform("stringToDouble($1, 0.0)")
            exp.eval(Array("", "1.2")) mustEqual 1.2
            exp.eval(Array("", "")) mustEqual 0.0
            exp.eval(Array("", null)) mustEqual 0.0
            exp.eval(Array("", "notadouble")) mustEqual 0.0
          }
          "double stringToDouble null default" >> {
            val exp = Transformers.parseTransform("stringToDouble($1, $2)")
            exp.eval(Array("", "1.2", null)) mustEqual 1.2
            exp.eval(Array("", "", null)) mustEqual null
            exp.eval(Array("", null, null)) mustEqual null
            exp.eval(Array("", "notadouble", null)) mustEqual null
          }
        }
        "stringToInt" >> {
          "int stringToInt zero default" >> {
            val exp = Transformers.parseTransform("stringToInt($1, 0)")
            exp.eval(Array("", "2")) mustEqual 2
            exp.eval(Array("", "")) mustEqual 0
            exp.eval(Array("", null)) mustEqual 0
            exp.eval(Array("", "1.2")) mustEqual 0
          }
          "int stringToInt null default" >> {
            val exp = Transformers.parseTransform("stringToInt($1, $2)")
            exp.eval(Array("", "2", null)) mustEqual 2
            exp.eval(Array("", "", null)) mustEqual null
            exp.eval(Array("", null, null)) mustEqual null
            exp.eval(Array("", "1.2", null)) mustEqual null
          }
        }
        "stringToInteger" >> {
          "int stringToInteger zero default" >> {
            val exp = Transformers.parseTransform("stringToInteger($1, 0)")
            exp.eval(Array("", "2")) mustEqual 2
            exp.eval(Array("", "")) mustEqual 0
            exp.eval(Array("", null)) mustEqual 0
            exp.eval(Array("", "1.2")) mustEqual 0
          }
          "int stringToInteger null default" >> {
            val exp = Transformers.parseTransform("stringToInteger($1, $2)")
            exp.eval(Array("", "2", null)) mustEqual 2
            exp.eval(Array("", "", null)) mustEqual null
            exp.eval(Array("", null, null)) mustEqual null
            exp.eval(Array("", "1.2", null)) mustEqual null
          }
        }
        "stringToLong" >> {
          "long stringToLong zero default" >> {
            val exp = Transformers.parseTransform("stringToLong($1, 0L)")
            exp.eval(Array("", "22960000000")) mustEqual 22960000000L
            exp.eval(Array("", "")) mustEqual 0L
            exp.eval(Array("", null)) mustEqual 0L
            exp.eval(Array("", "1.2")) mustEqual 0L
          }
          "long stringToLong null default" >> {
            val exp = Transformers.parseTransform("stringToLong($1, $2)")
            exp.eval(Array("", "22960000000", null)) mustEqual 22960000000L
            exp.eval(Array("", "", null)) mustEqual null
            exp.eval(Array("", null, null)) mustEqual null
            exp.eval(Array("", "1.2", null)) mustEqual null
          }
        }
        "stringToFloat" >> {
          "float stringToFloat zero default" >> {
            val exp = Transformers.parseTransform("stringToFloat($1, 0.0f)")
            exp.eval(Array("", "1.2")) mustEqual 1.2f
            exp.eval(Array("", "")) mustEqual 0.0f
            exp.eval(Array("", null)) mustEqual 0.0f
            exp.eval(Array("", "notafloat")) mustEqual 0.0f
          }
          "float stringToFloat zero default" >> {
            val exp = Transformers.parseTransform("stringToFloat($1, $2)")
            exp.eval(Array("", "1.2", null)) mustEqual 1.2f
            exp.eval(Array("", "", null)) mustEqual null
            exp.eval(Array("", null, null)) mustEqual null
            exp.eval(Array("", "notafloat", null)) mustEqual null
          }
        }
        "stringToBoolean" >> {
          "boolean stringToBoolean false default" >> {
            val exp = Transformers.parseTransform("stringToBoolean($1, false)")
            exp.eval(Array("", "true")) mustEqual true
            exp.eval(Array("", "")) mustEqual false
            exp.eval(Array("", null)) mustEqual false
            exp.eval(Array("", "18")) mustEqual false
          }
          "boolean stringToBoolean null default" >> {
            val exp = Transformers.parseTransform("stringToBoolean($1,$2)")
            exp.eval(Array("", "true", null)) mustEqual true
            exp.eval(Array("", "", null)) mustEqual null
            exp.eval(Array("", null, null)) mustEqual null
            exp.eval(Array("", "18", null)) mustEqual null
          }
        }
      }

      "logic predicates" >> {
        "not" >> {
          val exp = Transformers.parsePred("not(strEq($1, $2))")
          exp.eval(Array("", "1", "1")) must beFalse
        }
        "and" >> {
          val exp = Transformers.parsePred("and(strEq($1, $2), strEq(concat($3, $4), $1))")
          exp.eval(Array("", "foo", "foo", "f", "oo")) must beTrue
        }
        "or" >> {
          val exp = Transformers.parsePred("or(strEq($1, $2), strEq($3, $1))")
          exp.eval(Array("", "foo", "foo", "f", "oo")) must beTrue
        }
      }

      "support cql functions" >> {
        "buffer" >> {
          val exp = Transformers.parseTransform("cql:buffer($1, $2)")
          val buf = exp.eval(Array(null, "POINT(1 1)", 2.0))
          buf must beAnInstanceOf[Polygon].asInstanceOf[Matcher[Any]]
          buf.asInstanceOf[Polygon].getCentroid.getX must beCloseTo(1, 0.0001)
          buf.asInstanceOf[Polygon].getCentroid.getY must beCloseTo(1, 0.0001)
          // note: area is not particularly close as there aren't very many points in the polygon
          buf.asInstanceOf[Polygon].getArea must beCloseTo(math.Pi * 4.0, 0.2)
        }
      }
    }

    "return null for non-existing fields" >> {
      val fieldsCtx = new EvaluationContextImpl(IndexedSeq("foo", "bar"), Array("5", "10"), null)
      Transformers.parseTransform("$b").eval(Array())(fieldsCtx) mustEqual null
      Transformers.parseTransform("$bar").eval(Array())(fieldsCtx) mustEqual "10"
    }

    import scala.collection.JavaConversions._
    "create lists" >> {
      val trans = Transformers.parseTransform("list($0, $1, $2)")
      val res = trans.eval(Array("a", "b", "c")).asInstanceOf[java.util.List[String]]
      res.size() mustEqual 3
      res.toList must containTheSameElementsAs(List("a", "b", "c"))
    }

    "parse lists" >> {
      "default delimiter" >> {
        val trans = Transformers.parseTransform("parseList('string', $0)")
        val res = trans.eval(Array("a,b,c")).asInstanceOf[java.util.List[String]]
        res.size mustEqual 3
        res.toList must containTheSameElementsAs(List("a", "b", "c"))
      }
      "custom delimiter" >> {
        val trans = Transformers.parseTransform("parseList('string', $0, '%')")
        val res = trans.eval(Array("a%b%c")).asInstanceOf[java.util.List[String]]
        res.size mustEqual 3
        res.toList must containTheSameElementsAs(List("a", "b", "c"))
      }
      "with numbers" >> {
        val trans = Transformers.parseTransform("parseList('int', $0, '%')")
        val res = trans.eval(Array("1%2%3")).asInstanceOf[java.util.List[Int]]
        res.size mustEqual 3
        res.toList must containTheSameElementsAs(List(1,2,3))
      }
      "with numbers" >> {
        val trans = Transformers.parseTransform("parseList('int', $0, '%')")
        trans.eval(Array("1%2%a")).asInstanceOf[java.util.List[Int]] must throwAn[IllegalArgumentException]
      }
    }
  }
}
