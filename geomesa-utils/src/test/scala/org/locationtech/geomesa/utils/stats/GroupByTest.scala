/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.utils.stats

import java.lang.{Double => jDouble, Float => jFloat, Integer => jInt, Long => jLong}

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class GroupByTest extends Specification with StatTestHelper {

  sequential

  def newStat[T](attribute: String, groupedStat: String, observe: Boolean = true): GroupBy[T] = {
    val stat = Stat(sft, s"GroupBy($attribute,$groupedStat)")
    if (observe) {
      features.foreach { stat.observe }
    }
    stat.asInstanceOf[GroupBy[T]]
  }

  "GroupBy Stat" should {
    "work with" >> {
      "nested GroupBy Count() and" >> {
        def groupByStat(groupBy: GroupBy[Int], index: Int = 0): GroupBy[String] =
          groupBy.getOrElse(index).asInstanceOf[GroupBy[String]]
        def countStat(groupBy: GroupBy[String], index: String): CountStat =
          groupBy.getOrElse(index).asInstanceOf[CountStat]
        val groupByCountMatcher = """^\[(\{ "\d" : \[(\{ "." : \{ "count": \d \}\},?)+\]\},?)*\]$"""

        "be empty initially" >> {
          val groupBy = newStat[Int]("cat1","GroupBy(cat2,Count())", false)
          groupBy.toJson mustEqual "[]"
          groupBy.isEmpty must beTrue
        }

        "observe correct values" >> {
          val groupBy = newStat[Int]("cat1", "GroupBy(cat2,Count())")
          groupBy.size mustEqual 10
          val nestedGroupBy = groupByStat(groupBy)
          countStat(nestedGroupBy, "S").counter mustEqual 1L
        }

        "unobserve correct values" >> {
          val groupBy = newStat[Int]("cat1", "GroupBy(cat2,Count())")
          groupBy.size mustEqual 10
          features.take(10).foreach(groupBy.unobserve)
          val nestedGroupBy = groupByStat(groupBy)
          countStat(nestedGroupBy, "S").counter mustEqual 1L
        }

        "serialize to json" >> {
          val groupBy = newStat[Int]("cat1", "GroupBy(cat2,Count())")
          groupBy.toJson must beMatching (groupByCountMatcher)
        }

        "serialize and deserialize" >> {
          "observed" >> {
            val groupBy = newStat[Int]("cat1", "GroupBy(cat2,Count())")
            val packed = StatSerializer(sft).serialize(groupBy)
            val unpacked = StatSerializer(sft).deserialize(packed)
            // Sometimes Json is deserialized in a different order making direct comparison not possible
            groupBy.toJson must beMatching (groupByCountMatcher)
            unpacked.toJson must beMatching (groupByCountMatcher)
          }
          "unobserved" >> {
            val groupBy = newStat[Int]("cat1", "GroupBy(cat2,Count())", false)
            val packed = StatSerializer(sft).serialize(groupBy)
            val unpacked = StatSerializer(sft).deserialize(packed)
            // Sometimes Json is deserialized in a different order making direct comparison not possible
            groupBy.toJson must beMatching (groupByCountMatcher)
            unpacked.toJson must beMatching (groupByCountMatcher)
          }
        }

        "deserialize as immutable value" >> {
          val groupBy = newStat[Int]("cat1", "GroupBy(cat2,Count())")
          val packed = StatSerializer(sft).serialize(groupBy)
          val unpacked = StatSerializer(sft).deserialize(packed, immutable = true)
          // Sometimes Json is deserialized in a different order making direct comparison not possible
          groupBy.toJson must beMatching (groupByCountMatcher)
          unpacked.toJson must beMatching (groupByCountMatcher)

          unpacked.clear must throwAn[Exception]
          unpacked.+=(groupBy) must throwAn[Exception]
          unpacked.observe(features.head) must throwAn[Exception]
          unpacked.unobserve(features.head) must throwAn[Exception]
        }

        "combine two stats" >> {
          val groupBy = newStat[Int]("cat1", "GroupBy(cat2,Count())")
          val groupBy2 = newStat[Int]("cat1", "GroupBy(cat2,Count())", false)

          features2.foreach { groupBy2.observe }

          groupBy2.size mustEqual 10

          groupBy += groupBy2

          groupBy.size mustEqual 10
          groupBy2.size mustEqual 10
        }

        "clear" >> {
          val groupBy = newStat[Int]("cat1", "GroupBy(cat2,Count())")
          groupBy.isEmpty must beFalse

          groupBy.clear()

          groupBy.size mustEqual 0L
          groupBy.isEmpty must beTrue
        }
      }

      "Count Stat and" >> {
        def countStat(groupBy: GroupBy[Int], index: Int = 0): CountStat =
          groupBy.getOrElse(index).asInstanceOf[CountStat]

        "be empty initially" >> {
          val groupBy = newStat[Int]("cat1","Count()", false)
          groupBy.toJson mustEqual "[]"
          groupBy.isEmpty must beTrue
        }

        "observe correct values" >> {
          val groupBy = newStat[Int]("cat1", "Count()")
          groupBy.size mustEqual 10
          countStat(groupBy).counter mustEqual 10L
        }

        "unobserve correct values" >> {
          val groupBy = newStat[Int]("cat1", "Count()")
          groupBy.size mustEqual 10
          features.take(10).foreach(groupBy.unobserve)
          countStat(groupBy).counter mustEqual 9L
        }

        "serialize to json" >> {
          val groupBy = newStat[Int]("cat1", "Count()")
          groupBy.toJson must beMatching ("""^\[(\{ "\d" : \{ "count": 10 \}\},?){10}\]$""")
        }

        "serialize and deserialize" >> {
          "observed" >> {
            val groupBy = newStat[Int]("cat1", "Count()")
            val packed = StatSerializer(sft).serialize(groupBy)
            val unpacked = StatSerializer(sft).deserialize(packed)
            groupBy.toJson mustEqual unpacked.toJson
          }
          "unobserved" >> {
            val groupBy = newStat[Int]("cat1", "Count()", false)
            val packed = StatSerializer(sft).serialize(groupBy)
            val unpacked = StatSerializer(sft).deserialize(packed)
            groupBy.toJson mustEqual unpacked.toJson
          }
        }

        "deserialize as immutable value" >> {
          val groupBy = newStat[Int]("cat1", "Count()")
          val packed = StatSerializer(sft).serialize(groupBy)
          val unpacked = StatSerializer(sft).deserialize(packed, immutable = true)
          unpacked.toJson mustEqual groupBy.toJson

          unpacked.clear must throwAn[Exception]
          unpacked.+=(groupBy) must throwAn[Exception]
          unpacked.observe(features.head) must throwAn[Exception]
          unpacked.unobserve(features.head) must throwAn[Exception]
        }

        "combine two stats" >> {
          val groupBy = newStat[Int]("cat1", "Count()")
          val groupBy2 = newStat[Int]("cat1", "Count()", false)

          features2.foreach { groupBy2.observe }

          groupBy2.size mustEqual 10

          groupBy += groupBy2

          groupBy.size mustEqual 10
          groupBy2.size mustEqual 10
        }

        "clear" >> {
          val groupBy = newStat[Int]("cat1", "Count()")
          groupBy.isEmpty must beFalse

          groupBy.clear()

          groupBy.size mustEqual 0L
          groupBy.isEmpty must beTrue
        }
      }

      "MinMax Stat and" >> {
        def minmaxStat(groupBy: GroupBy[Int], index: Int = 0): MinMax[String] =
          groupBy.getOrElse(index).asInstanceOf[MinMax[String]]

        "be empty initially" >> {
          val groupBy = newStat[Int]("cat1","MinMax(strAttr)", false)
          groupBy.toJson mustEqual "[]"
          groupBy.isEmpty must beTrue
        }

        "observe correct values" >> {
          val groupBy = newStat[Int]("cat1","MinMax(strAttr)")
          val stat = minmaxStat(groupBy)
          stat.bounds mustEqual ("abc000", "abc090")
          stat.cardinality must beCloseTo(10L, 5)
        }

        "serialize to json" >> {
          val groupBy = newStat[Int]("cat1","MinMax(strAttr)")
          groupBy.toJson must beMatching ("""^\[(\{ "\d" : \{ "min": "abc[0-9]{3}", "max": "abc[0-9]{3}", "cardinality": \d+ \}\},?){10}\]$""")
        }

        "serialize empty to json" >> {
          val groupBy = newStat[Int]("cat1","MinMax(strAttr)", false)
          groupBy.toJson mustEqual "[]"
        }

        "serialize and deserialize" >> {
          val groupBy = newStat[Int]("cat1","MinMax(strAttr)")
          val packed = StatSerializer(sft).serialize(groupBy)
          val unpacked = StatSerializer(sft).deserialize(packed)
          unpacked.toJson mustEqual groupBy.toJson
        }

        "serialize and deserialize empty MinMax" >> {
          val groupBy = newStat[Int]("cat1","MinMax(strAttr)", false)
          val packed = StatSerializer(sft).serialize(groupBy)
          val unpacked = StatSerializer(sft).deserialize(packed)
          unpacked.toJson mustEqual groupBy.toJson
        }

        "deserialize as immutable value" >> {
          val groupBy = newStat[Int]("cat1","MinMax(strAttr)")
          val packed = StatSerializer(sft).serialize(groupBy)
          val unpacked = StatSerializer(sft).deserialize(packed, immutable = true)
          unpacked.toJson mustEqual groupBy.toJson

          unpacked.clear must throwAn[Exception]
          unpacked.+=(groupBy) must throwAn[Exception]
          unpacked.observe(features.head) must throwAn[Exception]
          unpacked.unobserve(features.head) must throwAn[Exception]
        }

        "combine two MinMaxes" >> {
          val groupBy1 = newStat[Int]("cat1","MinMax(strAttr)")
          val groupBy2 = newStat[Int]("cat1","MinMax(strAttr)", false)

          features2.foreach { groupBy2.observe }
          val gS20 = minmaxStat(groupBy2)
          gS20.bounds mustEqual ("abc100", "abc190")
          gS20.cardinality must beCloseTo(10L, 5)

          groupBy1 += groupBy2
          val gS10 = minmaxStat(groupBy1)
          gS10.bounds mustEqual ("abc000", "abc190")
          gS10.cardinality must beCloseTo(20L, 5)
          gS20.bounds mustEqual ("abc100", "abc190")
        }

        "clear" >> {
          val groupBy = newStat[Int]("cat1","MinMax(strAttr)")
          groupBy.isEmpty must beFalse

          groupBy.clear()

          groupBy.isEmpty must beTrue
          groupBy.size mustEqual 0
        }
      }

      "Enumeration Stat and" >> {
        "work with ints" >> {
          def enumerationStat(groupBy: GroupBy[Int], index: Int = 0): EnumerationStat[jInt] =
            groupBy.getOrElse(index).asInstanceOf[EnumerationStat[jInt]]

          "be empty initially" >> {
            val groupBy = newStat[Int]("cat1","Enumeration(intAttr)", false)
            groupBy.toJson mustEqual "[]"
            groupBy.isEmpty must beTrue
          }

          "observe correct values" >> {
            val groupBy = newStat[Int]("cat1","Enumeration(intAttr)")
            forall(0 until 10){i =>
              val enumStat = enumerationStat(groupBy, i)
              enumStat.enumeration.forall(enum => enum._2 mustEqual 1)
            }
          }

          "serialize to json" >> {
            val groupBy = newStat[Int]("cat1","Enumeration(intAttr)")
            val packed   = StatSerializer(sft).serialize(groupBy)
            val unpacked = StatSerializer(sft).deserialize(packed)
            val enums1 = enumerationStat(groupBy)
            val enums2 = enumerationStat(unpacked.asInstanceOf[GroupBy[Int]])

            enums2.attribute mustEqual enums1.attribute
            enums2.enumeration mustEqual enums1.enumeration
            enums2.size mustEqual enums1.size
            enums2.toJson mustEqual enums1.toJson
            groupBy.toJson mustEqual unpacked.toJson
          }

          "serialize empty to json" >> {
            val groupBy = newStat[Int]("cat1","Enumeration(intAttr)", false)
            groupBy.toJson mustEqual "[]"
          }

          "serialize and deserialize" >> {
            "observed" >> {
              val groupBy = newStat[Int]("cat1","Enumeration(intAttr)")
              val packed = StatSerializer(sft).serialize(groupBy)
              val unpacked = StatSerializer(sft).deserialize(packed)
              unpacked.toJson mustEqual groupBy.toJson
            }
            "unobserved" >> {
              val groupBy = newStat[Int]("cat1","Enumeration(intAttr)", false)
              val packed = StatSerializer(sft).serialize(groupBy)
              val unpacked = StatSerializer(sft).deserialize(packed)
              unpacked.toJson mustEqual groupBy.toJson
            }
          }

          "combine two stats" >> {
            val groupBy = newStat[Int]("cat1","Enumeration(intAttr)")
            val groupBy2 = newStat[Int]("cat1","Enumeration(intAttr)", false)

            features2.foreach { groupBy2.observe }
            val enum2 = enumerationStat(groupBy2)
            enum2.enumeration must haveSize(10)
            forall(10 until 20)(i => enum2.enumeration(i * 10) mustEqual 1L)

            groupBy += groupBy2
            val enum = enumerationStat(groupBy)
            enum.enumeration must haveSize(20)
            forall(0 until 20)(i => enum.enumeration(i * 10) mustEqual 1L)
            enum2.enumeration must haveSize(10)
            forall(10 until 20)(i => enum2.enumeration(i * 10) mustEqual 1L)
          }

          "clear" >> {
            val groupBy = newStat[Int]("cat1","Enumeration(intAttr)")
            groupBy.isEmpty must beFalse

            groupBy.clear()

            groupBy.isEmpty must beTrue
            groupBy.size mustEqual 0
          }
        }
      }

      "Histogram Stat and" >> {
        def histogramStatString(attribute: String, bins: Int, min: String, max: String): String =
          s"Histogram($attribute,$bins,'$min','$max')"

        "work with integers and" >> {
          def histogramStat(groupBy: GroupBy[Int], index: Int = 0): Histogram[Int] =
            groupBy.getOrElse(index).asInstanceOf[Histogram[Int]]
          def intStat(bins: Int, min: Int, max: Int): String =
            histogramStatString("intAttr", bins, min.toString, max.toString)

          "be empty initially" >> {
            val groupBy = newStat[Int]("cat1", intStat(20, 0, 199), false)
            groupBy.toJson mustEqual "[]"
            groupBy.isEmpty must beTrue
          }

          "correctly bin values"  >> {
            val groupBy = newStat[Int]("cat1", intStat(20, 0, 199))
            groupBy.isEmpty must beFalse
            val hist = histogramStat(groupBy)
            hist.bins.length mustEqual 20
            forall(0 until 10)(hist.bins.counts(_) mustEqual 1)
            forall(10 until 20)(hist.bins.counts(_) mustEqual 0)
          }

          "correctly remove values"  >> {
            val groupBy = newStat[Int]("cat1", intStat(20, 0, 199))
            groupBy.isEmpty must beFalse
            val hist = histogramStat(groupBy)
            hist.length mustEqual 20
            forall(0 until 10)(hist.bins.counts(_) mustEqual 1)
            forall(10 until 20)(hist.bins.counts(_) mustEqual 0)
            features.take(50).foreach(groupBy.unobserve)
            val hist2 = histogramStat(groupBy)
            forall(5 until 10)(hist2.bins.counts(_) mustEqual 1)
            forall((0 until 5) ++ (10 until 20))(hist2.bins.counts(_) mustEqual 0)
          }

          "serialize and deserialize" >> {
            "observered" >> {
              val groupBy = newStat[Int]("cat1", intStat(20, 0, 199))
              val packed = StatSerializer(sft).serialize(groupBy)
              val unpacked = StatSerializer(sft).deserialize(packed).asInstanceOf[GroupBy[Int]]

              unpacked.toJson mustEqual groupBy.toJson

              forall(0 until 10) { i =>
                val groupByHist = histogramStat(groupBy, i)
                val unpackedHist = histogramStat(unpacked, i)

                unpackedHist must beAnInstanceOf[Histogram[Int]]
                unpackedHist.length mustEqual groupByHist.length
                unpackedHist.attribute mustEqual groupByHist.attribute
              }
            }

            "unobserved" >> {
              val groupBy = newStat[Int]("cat1", intStat(20, 0, 199), false)
              val packed = StatSerializer(sft).serialize(groupBy)
              val unpacked = StatSerializer(sft).deserialize(packed).asInstanceOf[GroupBy[Int]]

              unpacked.toJson mustEqual groupBy.toJson
            }
          }

          "combine two RangeHistograms" >> {
            val groupBy = newStat[Int]("cat1", intStat(20, 0, 199))
            val groupBy2 = newStat[Int]("cat1", intStat(20, 0, 199), false)

            features2.foreach { groupBy2.observe }

            forall(0 until 10) { i =>
              val hist2 = histogramStat(groupBy2, i)
              hist2.length mustEqual 20
              forall(0 until 10)(hist2.count(_) mustEqual 0)
              forall(10 until 20)(hist2.count(_) mustEqual 1)
            }

            groupBy += groupBy2

            forall(0 until 10) { i =>
              val hist = histogramStat(groupBy, i)
              hist.length mustEqual 20
              forall(0 until 20)(hist.count(_) mustEqual 1)
            }
          }

          "clear" >> {
            val groupBy = newStat[Int]("cat1", intStat(20, 0, 199))
            groupBy.clear()

            groupBy.isEmpty must beTrue
            groupBy.toJson mustEqual "[]"
          }
        }
      }

//      "Seq stat" should {
//        val statStr = "MinMax(intAttr);IteratorStackCount();Enumeration(longAttr);Histogram(doubleAttr,20,0,200)"
//
//        "be empty initiallly" >> {
//          val groupBy = newStat[Int]("cat1", statStr, false)
//
//          groupBy.size mustEqual 0
//          groupBy.toJson mustEqual "[]"
//          groupBy.isEmpty must beTrue
//        }
//      }
    }
  }
}
