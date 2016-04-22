/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.accumulo.index

import com.typesafe.scalalogging.LazyLogging
import org.apache.accumulo.core.data.{Range => aRange}
import org.apache.hadoop.io.Text
import org.geotools.factory.Hints
import org.locationtech.geomesa.accumulo.data.stats.GeoMesaStats
import org.locationtech.geomesa.accumulo.data.tables.RecordTable
import org.locationtech.geomesa.accumulo.index.QueryHints.RichHints
import org.locationtech.geomesa.accumulo.index.Strategy.CostEvaluation._
import org.locationtech.geomesa.accumulo.index.Strategy._
import org.locationtech.geomesa.accumulo.iterators.{BinAggregatingIterator, KryoLazyFilterTransformIterator, KryoLazyStatsIterator}
import org.locationtech.geomesa.filter._
import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType
import org.opengis.feature.simple.SimpleFeatureType
import org.opengis.filter.{Filter, Id}

import scala.collection.JavaConversions._


object RecordIdxStrategy extends StrategyProvider {

  // top-priority index - always 1 if there are actually ID filters
  override protected def statsBasedCost(filter: QueryFilter,
                                        sft: SimpleFeatureType,
                                        stats: GeoMesaStats): Option[Long] = {
    if (QueryFilterSplitter.isFullTableScan(filter)) Some(Long.MaxValue) else Some(1L)
  }

  // top-priority index - always 1
  override protected def indexBasedCost(filter: QueryFilter, sft: SimpleFeatureType): Long = 1L

  def intersectIdFilters(filters: Seq[Filter]): Set[String] = {
    if (filters.length < 2) {
      filters.map(_.asInstanceOf[Id]).flatMap(_.getIDs.map(_.toString)).toSet
    } else {
      // get the Set of IDs in *each* filter and convert to a Scala immutable Set
      // take the intersection of all sets, since the filters and joined with 'and'
      filters.map(_.asInstanceOf[Id].getIDs.map(_.toString).toSet).reduceLeft(_ intersect _)
    }
  }
}

class RecordIdxStrategy(val filter: QueryFilter) extends Strategy with LazyLogging {

  override def getQueryPlan(queryPlanner: QueryPlanner, hints: Hints, output: ExplainerOutputType) = {

    val sft = queryPlanner.sft
    val acc = queryPlanner.acc
    val featureEncoding = queryPlanner.featureEncoding
    val prefix = sft.getTableSharingPrefix

    val ranges = if (filter.primary.forall(_ == Filter.INCLUDE)) {
      // allow for full table scans - we use the record index for queries that can't be satisfied elsewhere
      filter.secondary.foreach { f =>
        logger.warn(s"Running full table scan for schema ${sft.getTypeName} with filter ${filterToString(f)}")
      }
      val start = new Text(prefix)
      Seq(new aRange(start, true, aRange.followingPrefix(start), false))
    } else {
      // Multiple sets of IDs in a ID Filter are ORs. ANDs of these call for the intersection to be taken.
      // intersect together all groups of ID Filters, producing Some[Id] if the intersection returns something
      val identifiers = RecordIdxStrategy.intersectIdFilters(filter.primary)
      output(s"Extracted ID filter: ${identifiers.mkString(", ")}")
      if (identifiers.nonEmpty) {
        identifiers.toSeq.map(id => aRange.exact(RecordTable.getRowKey(prefix, id)))
      } else {
        // TODO GEOMESA-347 instead pass empty query plan
        Seq.empty
      }
    }

    val table = acc.getTableName(sft.getTypeName, RecordTable)
    val threads = acc.getSuggestedThreads(sft.getTypeName, RecordTable)

    if (sft.getSchemaVersion > 5) {
      // optimized path when we know we're using kryo serialization
      if (hints.isBinQuery) {
        // use the server side aggregation
        val iters = Seq(BinAggregatingIterator.configureDynamic(sft, filter.secondary, hints, deduplicate = false))
        val kvsToFeatures = BinAggregatingIterator.kvsToFeatures()
        BatchScanPlan(filter, table, ranges, iters, Seq.empty, kvsToFeatures, threads, hasDuplicates = false)
      } else if (hints.isStatsIteratorQuery) {
        val iters = Seq(KryoLazyStatsIterator.configure(sft, filter.secondary, hints, deduplicate = false))
        val kvsToFeatures = KryoLazyStatsIterator.kvsToFeatures(sft)
        BatchScanPlan(filter, table, ranges, iters, Seq.empty, kvsToFeatures, threads, hasDuplicates = false)
      } else {
        val iters = KryoLazyFilterTransformIterator.configure(sft, filter.secondary, hints).toSeq
        val kvsToFeatures = queryPlanner.defaultKVsToFeatures(hints)
        BatchScanPlan(filter, table, ranges, iters, Seq.empty, kvsToFeatures, threads, hasDuplicates = false)
      }
    } else {
      val iters = if (filter.secondary.isDefined || hints.getTransformSchema.isDefined) {
        Seq(configureRecordTableIterator(sft, featureEncoding, filter.secondary, hints))
      } else {
        Seq.empty
      }
      val kvsToFeatures = if (hints.isBinQuery) {
        BinAggregatingIterator.nonAggregatedKvsToFeatures(sft, hints, featureEncoding)
      } else {
        queryPlanner.defaultKVsToFeatures(hints)
      }
      BatchScanPlan(filter, table, ranges, iters, Seq.empty, kvsToFeatures, threads, hasDuplicates = false)
    }
  }
}
