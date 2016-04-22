/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.accumulo.index

import com.google.common.primitives.{Bytes, Longs, Shorts}
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.{Geometry, GeometryCollection}
import org.apache.accumulo.core.data.Range
import org.apache.hadoop.io.Text
import org.geotools.factory.Hints
import org.locationtech.geomesa.accumulo.data.tables.Z3Table
import org.locationtech.geomesa.accumulo.iterators._
import org.locationtech.geomesa.curve.Z3SFC
import org.locationtech.geomesa.filter._
import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType
import org.locationtech.sfcurve.zorder.Z3
import org.opengis.feature.simple.SimpleFeatureType
import org.opengis.filter.Filter
import org.opengis.filter.spatial._

class Z3IdxStrategy(val filter: QueryFilter) extends Strategy with LazyLogging with IndexFilterHelpers  {

  /**
   * Plans the query - strategy implementations need to define this
   */
  override def getQueryPlan(queryPlanner: QueryPlanner, hints: Hints, output: ExplainerOutputType) = {

    import QueryHints.{LOOSE_BBOX, RichHints}
    import Z3IdxStrategy._
    import org.locationtech.geomesa.filter.FilterHelper._

    val ds  = queryPlanner.ds
    val sft = queryPlanner.sft

    val dtgField = sft.getDtgField

    val (geomFilters, temporalFilters) = {
      val (g, t) = filter.primary.partition(isSpatialFilter)
      if (g.isEmpty) {
        // allow for date only queries - if no geom, use whole world
        (Seq(ff.bbox(sft.getGeomField, -180, -90, 180, 90, "EPSG:4326")), t)
      } else {
        (g, t)
      }
    }

    output(s"Geometry filters: ${filtersToString(geomFilters)}")
    output(s"Temporal filters: ${filtersToString(temporalFilters)}")

    // standardize the two key query arguments:  polygon and date-range
    val geomsToCover = tryReduceGeometryFilter(geomFilters).flatMap(decomposeToGeometry)

    val collectionToCover: Geometry = geomsToCover match {
      case Nil => null
      case seq: Seq[Geometry] => new GeometryCollection(geomsToCover.toArray, geomsToCover.head.getFactory)
    }

    // since we don't apply a temporal filter, we pass offsetDuring to
    // make sure we exclude the non-inclusive endpoints of a during filter.
    // note that this isn't completely accurate, as we only index down to the second
    val interval = extractInterval(temporalFilters, dtgField, exclusive = true)
    val geometryToCover = netGeom(collectionToCover)

    output(s"GeomsToCover: $geometryToCover")
    output(s"Interval:  $interval")

    val looseBBox = if (hints.containsKey(LOOSE_BBOX)) Boolean.unbox(hints.get(LOOSE_BBOX)) else ds.config.looseBBox

    val ecql: Option[Filter] = if (!looseBBox || sft.nonPoints) {
      // if the user has requested strict bounding boxes, we apply the full filter
      // if this is a non-point geometry, the index is coarse-grained, so we apply the full filter
      filter.filter
    } else {
      // for normal bboxes, the index is fine enough that we don't need to apply the filter on top of it
      // this may cause some minor errors at extremely fine resolution, but the performance is worth it
      // if we have a complicated geometry predicate, we need to pass it through to be evaluated
      val complexGeomFilter = filterListAsAnd(geomFilters.filter(isComplicatedSpatialFilter))
      (complexGeomFilter, filter.secondary) match {
        case (Some(gf), Some(fs)) => filterListAsAnd(Seq(gf, fs))
        case (None, fs)           => fs
        case (gf, None)           => gf
      }
    }

    val (iterators, kvsToFeatures, colFamily, hasDupes) = if (hints.isBinQuery) {
      // if possible, use the pre-computed values
      // can't use if there are non-st filters or if custom fields are requested
      val (iters, cf) =
        if (filter.secondary.isEmpty && BinAggregatingIterator.canUsePrecomputedBins(sft, hints)) {
          (Seq(BinAggregatingIterator.configurePrecomputed(sft, ecql, hints, sft.nonPoints)), Z3Table.BIN_CF)
        } else {
          val iter = BinAggregatingIterator.configureDynamic(sft, ecql, hints, sft.nonPoints)
          (Seq(iter), Z3Table.FULL_CF)
        }
      (iters, BinAggregatingIterator.kvsToFeatures(), cf, false)
    } else if (hints.isDensityQuery) {
      val iter = Z3DensityIterator.configure(sft, ecql, hints)
      (Seq(iter), KryoLazyDensityIterator.kvsToFeatures(), Z3Table.FULL_CF, false)
    } else if (hints.isStatsIteratorQuery) {
      val iter = KryoLazyStatsIterator.configure(sft, ecql, hints, sft.nonPoints)
      (Seq(iter), KryoLazyStatsIterator.kvsToFeatures(sft), Z3Table.FULL_CF, false)
    } else if (hints.isMapAggregatingQuery) {
      val iter = KryoLazyMapAggregatingIterator.configure(sft, ecql, hints, sft.nonPoints)
      (Seq(iter), queryPlanner.defaultKVsToFeatures(hints), Z3Table.FULL_CF, false)
    } else {
      val iters = KryoLazyFilterTransformIterator.configure(sft, ecql, hints).toSeq
      (iters, queryPlanner.defaultKVsToFeatures(hints), Z3Table.FULL_CF, sft.nonPoints)
    }

    val z3table = ds.getTableName(sft.getTypeName, Z3Table)
    val numThreads = ds.getSuggestedThreads(sft.getTypeName, Z3Table)

    // setup Z3 iterator
    val env = geometryToCover.getEnvelopeInternal
    val (lx, ly, ux, uy) = (env.getMinX, env.getMinY, env.getMaxX, env.getMaxY)

    val (epochWeekStart, lt) = Z3Table.getWeekAndSeconds(interval.getStart)
    val (epochWeekEnd, ut) = Z3Table.getWeekAndSeconds(interval.getEnd)
    val weeks = scala.Range.inclusive(epochWeekStart, epochWeekEnd).map(_.toShort)

    // time range for a chunk is 0 to 1 week (in seconds)
    val (tStart, tEnd) = (Z3SFC.time.min.toInt, Z3SFC.time.max.toInt)

    val getRanges: (Seq[Array[Byte]], (Double, Double), (Double, Double), (Long, Long)) => Seq[Range] =
      if (sft.isPoints) getPointRanges else getGeomRanges

    val hasSplits = Z3Table.hasSplits(sft)

    val prefixes = if (hasSplits) {
      val wBytes = weeks.map(Shorts.toByteArray)
      Z3Table.SPLIT_ARRAYS.flatMap(s => wBytes.map(b => Array(s(0), b(0), b(1))))
    } else {
      weeks.map(Shorts.toByteArray)
    }

    // the z3 index breaks time into 1 week chunks, so create a range for each week in our range
    val ranges = if (weeks.length == 1) {
      getRanges(prefixes, (lx, ux), (ly, uy), (lt, ut))
    } else {
      val head +: middle :+ last = prefixes.toList
      val headRanges = getRanges(Seq(head), (lx, ux), (ly, uy), (lt, tEnd))
      val lastRanges = getRanges(Seq(last), (lx, ux), (ly, uy), (tStart, ut))
      val middleRanges = if (middle.isEmpty) Seq.empty else getRanges(middle, (lx, ux), (ly, uy), (tStart, tEnd))
      headRanges ++ middleRanges ++ lastRanges
    }

    // index space values for comparing in the iterator
    def decode(x: Double, y: Double, t: Int): (Int, Int, Int) = if (sft.isPoints) {
      Z3SFC.index(x, y, t).decode
    } else {
      Z3(Z3SFC.index(x, y, t).z & Z3Table.GEOM_Z_MASK).decode
    }

    val (xmin, ymin, tmin) = decode(lx, ly, lt)
    val (xmax, ymax, tmax) = decode(ux, uy, ut)
    val (tLo, tHi) = (Z3SFC.time.normalize(tStart), Z3SFC.time.normalize(tEnd))

    val wmin = weeks.head
    val wmax = weeks.last

    val zIter = Z3Iterator.configure(sft.isPoints, xmin, xmax, ymin, ymax, tmin, tmax, wmin, wmax, tLo, tHi, hasSplits, Z3_ITER_PRIORITY)
    val iters = Seq(zIter) ++ iterators
    BatchScanPlan(z3table, ranges, iters, Seq(colFamily), kvsToFeatures, numThreads, hasDupes)
  }

  def getPointRanges(prefixes: Seq[Array[Byte]], x: (Double, Double), y: (Double, Double), t: (Long, Long)): Seq[Range] = {
    Z3SFC.ranges(x, y, t).flatMap { case indexRange =>
      val startBytes = Longs.toByteArray(indexRange.lower)
      val endBytes = Longs.toByteArray(indexRange.upper)
      prefixes.map { prefix =>
        val start = new Text(Bytes.concat(prefix, startBytes))
        val end   = Range.followingPrefix(new Text(Bytes.concat(prefix, endBytes)))
        new Range(start, true, end, false)
      }
    }
  }

  def getGeomRanges(prefixes: Seq[Array[Byte]], x: (Double, Double), y: (Double, Double), t: (Long, Long)): Seq[Range] = {
    Z3SFC.ranges(x, y, t, 8 * Z3Table.GEOM_Z_NUM_BYTES).flatMap { indexRange =>
      val startBytes = Longs.toByteArray(indexRange.lower).take(Z3Table.GEOM_Z_NUM_BYTES)
      val endBytes = Longs.toByteArray(indexRange.upper).take(Z3Table.GEOM_Z_NUM_BYTES)
      prefixes.map { prefix =>
        val start = new Text(Bytes.concat(prefix, startBytes))
        val end = Range.followingPrefix(new Text(Bytes.concat(prefix, endBytes)))
        new Range(start, true, end, false)
      }
    }
  }
}

object Z3IdxStrategy extends StrategyProvider {

  val Z3_ITER_PRIORITY = 21
  val FILTERING_ITER_PRIORITY = 25

  /**
   * Gets the estimated cost of running the query. Currently, cost is hard-coded to sort between
   * strategies the way we want. Z3 should be more than id lookups (at 1), high-cardinality attributes (at 1)
   * and less than STidx (at 400) and unknown cardinality attributes (at 999).
   *
   * Eventually cost will be computed based on dynamic metadata and the query.
   */
  override def getCost(filter: QueryFilter, sft: SimpleFeatureType, hints: StrategyHints) =
  // https://geomesa.atlassian.net/browse/GEOMESA-1166
  // TODO check date range and use z2 instead if too big
  // TODO also if very small bbox, z2 has ~10 more bits of lat/lon info
    if (filter.primary.exists(isSpatialFilter)) 200 else 401

  def isComplicatedSpatialFilter(f: Filter): Boolean = {
    f match {
      case _: BBOX => false
      case _: DWithin => true
      case _: Contains => true
      case _: Crosses => true
      case _: Intersects => true
      case _: Overlaps => true
      case _: Within => true
      case _ => false        // Beyond, Disjoint, DWithin, Equals, Touches
    }
  }

}
