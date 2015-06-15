/***********************************************************************
* Copyright (c) 2013-2015 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.accumulo.index

import org.geotools.data.Query
import org.geotools.filter.text.ecql.ECQL
import org.junit.runner.RunWith
import org.locationtech.geomesa.accumulo.data
import org.locationtech.geomesa.accumulo.filter.TestFilters._
import org.locationtech.geomesa.accumulo.util.SftBuilder
import org.locationtech.geomesa.accumulo.util.SftBuilder.Opts
import org.locationtech.geomesa.utils.stats.Cardinality
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.reflect.ClassTag

//Expand the test - https://geomesa.atlassian.net/browse/GEOMESA-308
@RunWith(classOf[JUnitRunner])
class QueryStrategyDeciderTest extends Specification {

  val sftIndex = new SftBuilder()
    .intType("id")
    .point("geom", default = true)
    .date("dtg", default = true)
    .stringType("attr1")
    .stringType("attr2", index = true)
    .stringType("high", Opts(index = true, cardinality = Cardinality.HIGH))
    .stringType("low", Opts(index = true, cardinality = Cardinality.LOW))
    .date("dtgNonIdx")
    .build("feature")

  val sftNonIndex = new SftBuilder()
    .intType("id")
    .point("geom", default = true)
    .date("dtg", default = true)
    .stringType("attr1")
    .stringType("attr2")
    .build("featureNonIndex")

  def getStrategy(filterString: String, version: Int = data.INTERNAL_GEOMESA_VERSION): Strategy = {
    val sft = if (version > 0) sftIndex else sftNonIndex
    val filter = ECQL.toFilter(filterString)
    val hints = new UserDataStrategyHints()
    val query = new Query(sft.getTypeName)
    query.setFilter(filter)
    QueryStrategyDecider.chooseStrategy(sft, query, hints, version)
  }

  def getStrategyT[T <: Strategy](filterString: String, ct: ClassTag[T]) =
    getStrategy(filterString) must beAnInstanceOf[T](ct)

  def getStStrategy(filterString: String) =
    getStrategyT(filterString, ClassTag(classOf[STIdxStrategy]))

  def getAttributeIdxEqualsStrategy(filterString: String) =
    getStrategyT(filterString, ClassTag(classOf[AttributeIdxEqualsStrategy]))

  def getAttributeIdxLikeStrategy(filterString: String) =
    getStrategyT(filterString, ClassTag(classOf[AttributeIdxLikeStrategy]))

  def getAttributeIdxStrategy(filterString: String) =
    getStrategyT(filterString, ClassTag(classOf[AttributeIdxStrategy]))

  def getZ3Strategy(filterString: String) =
    getStrategyT(filterString, ClassTag(classOf[Z3IdxStrategy]))

  "Good spatial predicates" should {
    "get the stidx strategy" in {
      forall(goodSpatialPredicates){ getStStrategy }
    }
  }

  "Attribute filters" should {
    "get the attribute equals strategy" in {
      val fs = "attr2 = 'val56'"

      getStrategy(fs) must beAnInstanceOf[AttributeIdxEqualsStrategy]
    }

    "get the attribute equals strategy" in {
      val fs = "attr1 = 'val56'"

      getStrategy(fs) must beAnInstanceOf[STIdxStrategy]
    }

    "get the attribute likes strategy" in {
      val fs = "attr2 ILIKE '2nd1%'"

      getStrategy(fs) must beAnInstanceOf[AttributeIdxLikeStrategy]
    }

    "get the stidx strategy if attribute non-indexed" in {
      val fs = "attr1 ILIKE '2nd1%'"

      getStrategy(fs) must beAnInstanceOf[STIdxStrategy]
    }

    "get the attribute strategy for lte" in {
      val fs = "attr2 <= 11"

      getStrategy(fs) must beAnInstanceOf[AttributeIdxRangeStrategy]
    }

    "get the attribute strategy for lt" in {
      val fs = "attr2 < 11"

      getStrategy(fs) must beAnInstanceOf[AttributeIdxRangeStrategy]
    }

    "get the attribute strategy for gte" in {
      val fs = "attr2 >= 11"

      getStrategy(fs) must beAnInstanceOf[AttributeIdxRangeStrategy]
    }

    "get the attribute strategy for gt" in {
      val fs = "attr2 > 11"

      getStrategy(fs) must beAnInstanceOf[AttributeIdxRangeStrategy]
    }

    "get the attribute strategy for gt prop on right" in {
      val fs = "11 > attr2"

      getStrategy(fs) must beAnInstanceOf[AttributeIdxRangeStrategy]
    }

    "get the attribute strategy for during" in {
      val fs = "attr2 DURING 2012-01-01T11:00:00.000Z/2014-01-01T12:15:00.000Z"

      getStrategy(fs) must beAnInstanceOf[AttributeIdxRangeStrategy]
    }

    "get the attribute strategy for after" in {
      val fs = "attr2 AFTER 2013-01-01T12:30:00.000Z"

      getStrategy(fs) must beAnInstanceOf[AttributeIdxRangeStrategy]
    }

    "get the attribute strategy for before" in {
      val fs = "attr2 BEFORE 2014-01-01T12:30:00.000Z"

      getStrategy(fs) must beAnInstanceOf[AttributeIdxRangeStrategy]
    }

    "get the attribute strategy for between" in {
      val fs = "attr2 BETWEEN 10 and 20"

      getStrategy(fs) must beAnInstanceOf[AttributeIdxRangeStrategy]
    }

    "get the attribute strategy for ANDed attributes" in {
      val fs = "attr2 >= 11 AND attr2 < 20"

      getStrategy(fs) must beAnInstanceOf[AttributeIdxRangeStrategy]
    }
  }

  "Attribute filters" should {
    "get the stidx strategy if not catalog" in {
      val fs = "attr1 ILIKE '2nd1%'"
      getStrategy(fs) must beAnInstanceOf[STIdxStrategy]
    }
  }

  "Id filters" should {
    "get the attribute equals strategy" in {
      val fs = "IN ('val56')"

      getStrategy(fs) must beAnInstanceOf[RecordIdxStrategy]
    }
  }

  "Id and Spatio-temporal filters" should {
    "get the records strategy" in {
      val fs = "IN ('val56') AND INTERSECTS(geom, POLYGON ((45 23, 48 23, 48 27, 45 27, 45 23)))"

      getStrategy(fs) must beAnInstanceOf[RecordIdxStrategy]
    }
  }

  "Id and Attribute filters" should {
    "get the records strategy" in {
      val fs = "IN ('val56') AND attr2 = val56"

      getStrategy(fs) must beAnInstanceOf[RecordIdxStrategy]
    }
  }

  "Really complicated Id AND * filters" should {
    "get the records strategy" in {
      val fsFragment1="INTERSECTS(geom, POLYGON ((45 23, 48 23, 48 27, 45 27, 45 23)))"
      val fsFragment2="AND IN ('val56','val55') AND attr2 = val56 AND IN('val59','val54') AND attr2 = val60"
      val fs = s"$fsFragment1 $fsFragment2"

      getStrategy(fs) must beAnInstanceOf[RecordIdxStrategy]
    }
  }

  "IS NOT NULL filters" should {
    "get the attribute strategy if attribute is indexed" in {
      val fs = "attr2 IS NOT NULL"
      getStrategy(fs) must beAnInstanceOf[AttributeIdxRangeStrategy]
    }
    "get the stidx strategy if attribute is not indexed" in {
      val fs = "attr1 IS NOT NULL"
      getStrategy(fs) must beAnInstanceOf[STIdxStrategy]
    }
  }

  "Anded Attribute filters" should {
    "get the STIdx strategy with stIdxStrategyPredicates" in {
      forall(stIdxStrategyPredicates) { getStStrategy }
    }

    "get the stidx strategy with attributeAndGeometricPredicates" in {
      forall(attributeAndGeometricPredicates) { getStStrategy }
    }

    "get the z3 strategy with spatio-temporal queries" in {
      forall(spatioTemporalPredicates) { getZ3Strategy }
      val morePredicates = temporalPredicates.drop(1).flatMap(p => goodSpatialPredicates.map(_ + " AND " + p))
      forall(morePredicates) { getZ3Strategy }
      val withAttrs = temporalPredicates.drop(1).flatMap(p => attributeAndGeometricPredicates.map(_ + " AND " + p))
      forall(withAttrs) { getZ3Strategy }
      val wholeWorld = "BBOX(geom,-180,-90,180,90) AND dtg DURING 2010-08-08T00:00:00.000Z/2010-08-08T23:59:59.000Z"
      getZ3Strategy(wholeWorld)
    }

    "get the attribute strategy with attrIdxStrategyPredicates" in {
      forall(attrIdxStrategyPredicates) { getAttributeIdxStrategy }
    }

    "respect high cardinality attributes regardless of order" in {
      val attr = "high = 'test'"
      val geom = "BBOX(geom, -10,-10,10,10)"
      getStrategy(s"$attr AND $geom") must beAnInstanceOf[AttributeIdxEqualsStrategy]
      getStrategy(s"$geom AND $attr") must beAnInstanceOf[AttributeIdxEqualsStrategy]
    }

    "respect low cardinality attributes regardless of order" in {
      val attr = "low = 'test'"
      val geom = "BBOX(geom, -10,-10,10,10)"
      getStrategy(s"$attr AND $geom") must beAnInstanceOf[STIdxStrategy]
      getStrategy(s"$geom AND $attr") must beAnInstanceOf[STIdxStrategy]
    }

    "respect cardinality with multiple attributes" in {
      val attr1 = "low = 'test'"
      val attr2 = "high = 'test'"
      val geom = "BBOX(geom, -10,-10,10,10)"
      getStrategy(s"$geom AND $attr1 AND $attr2") must beAnInstanceOf[AttributeIdxEqualsStrategy]
      getStrategy(s"$geom AND $attr2 AND $attr1") must beAnInstanceOf[AttributeIdxEqualsStrategy]
      getStrategy(s"$attr1 AND $attr2 AND $geom") must beAnInstanceOf[AttributeIdxEqualsStrategy]
      getStrategy(s"$attr2 AND $attr1 AND $geom") must beAnInstanceOf[AttributeIdxEqualsStrategy]
      getStrategy(s"$attr1 AND $geom AND $attr2") must beAnInstanceOf[AttributeIdxEqualsStrategy]
      getStrategy(s"$attr2 AND $geom AND $attr1") must beAnInstanceOf[AttributeIdxEqualsStrategy]
    }
  }
}
