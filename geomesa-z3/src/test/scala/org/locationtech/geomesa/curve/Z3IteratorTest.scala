package org.locationtech.geomesa.curve

import java.util

import com.google.common.primitives.{Longs, Bytes}
import org.apache.accumulo.core.data.{ByteSequence, Range, Key, Value}
import org.apache.accumulo.core.iterators.{IteratorEnvironment, SortedKeyValueIterator}
import org.apache.hadoop.io.Text
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.collection.JavaConversions._

@RunWith(classOf[JUnitRunner])
class Z3IteratorTest extends Specification {

  "Z3Iterator" should {
    sequential

    val (lx, ly, lt) = (-78.0, 38, 300)
    val (ux, uy, ut) = (-75.0, 40, 800)

    val Z3Curve = new Z3SFC
    val zmin = Z3Curve.index(lx, ly, lt)
    val zmax = Z3Curve.index(ux, uy, ut)

    val srcIter = new SortedKeyValueIterator[Key, Value] {
      var key: Key = null
      var staged: Key = null
      override def deepCopy(iteratorEnvironment: IteratorEnvironment): SortedKeyValueIterator[Key, Value] = this
      override def next(): Unit = {
        staged = key
        key = null
      }
      override def getTopValue: Value = null
      override def getTopKey: Key = staged
      override def init(sortedKeyValueIterator: SortedKeyValueIterator[Key, Value],
                        map: util.Map[String, String],
                        iteratorEnvironment: IteratorEnvironment): Unit = {}
      override def seek(range: Range, collection: util.Collection[ByteSequence], b: Boolean): Unit = {
        println("seek called")
        key = null
        staged = null
      }
      override def hasTop: Boolean = staged != null
    }

    val iter = new Z3Iterator
    iter.init(srcIter, Map("zmin" -> s"${zmin.z}", "zmax" -> s"${zmax.z}"), null)

    "keep in bounds values" >> {
      val test1 = Z3Curve.index(-76.0, 38.5, 500)
      val prefix = Array[Byte](0, 0)
      val row = Bytes.concat(prefix, Longs.toByteArray(test1.z))
      srcIter.key = new Key(new Text(row))
      iter.next()
      iter.hasTop must beTrue
    }

    "drop out of bounds values" >> {
      val test2 = Z3Curve.index(-70.0, 38.5, 500)
      val prefix = Array[Byte](0, 0)
      val row = Bytes.concat(prefix, Longs.toByteArray(test2.z))
      srcIter.key = new Key(new Text(row))
      iter.next()
      iter.hasTop must beFalse
    }
  }
}
