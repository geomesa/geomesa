/*
 * Copyright (c) 2013-2015 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0 which
 * accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.curve

import java.util

import com.google.common.primitives.{Bytes, Longs}
import org.apache.accumulo.core.data.{ByteSequence, Key, Range, Value}
import org.apache.accumulo.core.iterators.{IteratorEnvironment, SortedKeyValueIterator}
import org.apache.hadoop.io.Text
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.collection.JavaConversions._
import scala.util.Random

@RunWith(classOf[JUnitRunner])
class Z3Test extends Specification {

  val rand = new Random(-574)
  val maxInt = Math.pow(2, Z3.MAX_BITS - 1).toInt
  def nextDim() = rand.nextInt(maxInt)

  def padTo(s: String) = (new String(Array.fill(63)('0')) + s).takeRight(63)

  "Z3" should {

    "apply and unapply" >> {
      val (x, y, t) = (nextDim(), nextDim(), nextDim())
      val z = Z3(x, y, t)
      z match { case Z3(zx, zy, zt) =>
        x mustEqual zx
        y mustEqual zy
        t mustEqual zt
      }
    }

    "split" >> {
      val splits = Seq(
        0x00000000ffffffL,
        0x00000000000000L,
        0x00000000000001L,
        0x000000000c0f02L,
        0x00000000000802L
      ) ++ (0 until 10).map(_ => nextDim().toLong)
      splits.foreach { l =>
        val expected = padTo(new String(l.toBinaryString.toCharArray.flatMap(c => s"00$c")))
        padTo(Z3.split(l).toBinaryString) mustEqual expected
      }
      success
    }

    "split and combine" >> {
      val z = nextDim()
      val split = Z3.split(z)
      val combined = Z3.combine(split)
      combined.toInt mustEqual z
    }

    "support mid" >> {
      val (x, y, z)    = (0, 0, 0)
      val (x2, y2, z2) = (2, 2, 2)
      Z3(x, y, z).mid(Z3(x2, y2, z2)) match {
        case Z3(midx, midy, midz) =>
          midx mustEqual 1
          midy mustEqual 1
          midz mustEqual 1
      }
    }

    "support bigmin" >> {
      val zmin = Z3(2, 2, 0)
      val zmax = Z3(3, 6, 0)
      val f = Z3(5, 1, 0)
      val (_, bigmin) = Z3.zdivide(f, zmin, zmax)
      bigmin match {
        case Z3(xhi, yhi, zhi) =>
          xhi mustEqual 2
          yhi mustEqual 4
          zhi mustEqual 0
      }
    }

    "support litmax" >> {
      val zmin = Z3(2, 2, 0)
      val zmax = Z3(3, 6, 0)
      val f = Z3(1, 7, 0)
      val (litmax, _) = Z3.zdivide(f, zmin, zmax)
      litmax match {
        case Z3(xlow, ylow, zlow) =>
          xlow mustEqual 3
          ylow mustEqual 5
          zlow mustEqual 0
      }
    }

    "support in range" >> {
      val (x, y, z) = (nextDim(), nextDim(), nextDim())
      val z3 = Z3(x, y , z)
      val lessx  = Z3(x - 1, y, z)
      val lessx2 = Z3(x - 2, y, z)
      val lessy  = Z3(x, y - 1, z)
      val lessy2 = Z3(x, y - 2, z)
      val lessz  = Z3(x, y, z - 1)
      val lessz2 = Z3(x, y, z - 2)
      val less1  = Z3(x - 1, y - 1, z - 1)
      val less2  = Z3(x - 2, y - 2, z - 2)
      val morex  = Z3(x + 1, y, z)
      val morex2 = Z3(x + 2, y, z)
      val morey  = Z3(x, y + 1, z)
      val morez  = Z3(x, y, z + 1)
      val more1  = Z3(x + 1, y + 1, z + 1)

      z3.inRange(lessx, morex) must beTrue
      z3.inRange(lessx, morey) must beTrue
      z3.inRange(lessx, morez) must beTrue
      z3.inRange(lessx, more1) must beTrue

      z3.inRange(lessy, morex) must beTrue
      z3.inRange(lessy, morey) must beTrue
      z3.inRange(lessy, morez) must beTrue
      z3.inRange(lessy, more1) must beTrue

      z3.inRange(lessz, morex) must beTrue
      z3.inRange(lessz, morey) must beTrue
      z3.inRange(lessz, morez) must beTrue
      z3.inRange(lessz, more1) must beTrue

      z3.inRange(less1, more1) must beTrue

      z3.inRange(more1, less1) must beFalse
      z3.inRange(morex, morex2) must beFalse
      z3.inRange(lessx2, lessx) must beFalse
      z3.inRange(lessy2, lessy) must beFalse
      z3.inRange(lessz2, lessx) must beFalse
      z3.inRange(less2, less1) must beFalse
      z3.inRange(less2, more1) must beTrue
    }

    "calculate ranges" >> {
      val min = Z3(2, 2, 0)
      val max = Z3(3, 6, 0)
      val ranges = Z3.zranges(min, max, 100)
      ranges must haveLength(3)
      ranges must containTheSameElementsAs(Seq((Z3(2, 2, 0).z, Z3(3, 3, 0).z),
        (Z3(2, 4, 0).z, Z3(3, 5, 0).z), (Z3(2, 6, 0).z, Z3(3, 6, 0).z)))
    }
  }
}
