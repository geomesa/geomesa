/*
 * Copyright (c) 2013-2015 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0 which
 * accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.accumulo.index

import com.typesafe.scalalogging.slf4j.Logging
import org.geotools.filter.text.ecql.ECQL
import org.locationtech.geomesa.accumulo.data.tables._
import org.locationtech.geomesa.accumulo.index.Strategy.StrategyType
import org.locationtech.geomesa.accumulo.index.Strategy.StrategyType.StrategyType
import org.locationtech.geomesa.filter._
import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType
import org.opengis.feature.simple.SimpleFeatureType
import org.opengis.filter.{And, Filter, Id, Or}

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

/**
 * Class for splitting queries up based on Boolean clauses and the available query strategies.
 */
class QueryFilterSplitter(sft: SimpleFeatureType) extends Logging {

  val supported = GeoMesaTable.getTables(sft).toSet

  /**
   * Splits the query up into different filter plans to be evaluated. Each filter plan will consist of one or
   * more query plans. Each query plan will have a primary part (that would be used for query planning)
   * and an optional secondary part (that would be applied as a secondary filter).
   *
   * Examples:
   *
   * bbox(geom) AND attr1 = ? =>
   *
   * Seq(FilterPlan(Seq(QueryFilter(ST,Seq([ geom bbox ]),Some([ attr1 = ? ])))))
   *
   * bbox(geom) OR attr1 = ? =>
   *
   * Seq(FilterPlan(Seq(QueryFilter(ST,Seq([ geom bbox ]),None), QueryFilter(ATTRIBUTE,Seq([ attr1 = ? ]),None))))
   *
   * bbox(geom) AND dtg DURING ? AND attr1 = ? =>
   *
   * Seq(FilterPlan(Seq(QueryFilter(Z3,Seq([ geom bbox ], [ dtg during ? ]),Some([ attr1 = ? ])))),
   *     FilterPlan(Seq(QueryFilter(ATTRIBUTE,Seq([ attr1 = ? ]),Some([ geom bbox AND dtg during ? ])))))
   * note: spatial and temporal filters are combined.
   *
   * (bbox(geom) OR geom INTERSECT) AND attr1 = ? =>
   *
   * Seq(FilterPlan(Seq(QueryFilter(ST,Seq([ geom bbox ]),Some([ attr1 = ? ])), QueryFilter(ST,Seq([ geom intersect ]),Some([ attr1 = ? ]))))
   *     FilterPlan(Seq(QueryFilter(ATTRIBUTE,Seq([ attr1 = ? ]),Some([ geom bbox OR geom intersect ])))))
   *
   * note: ors will not be split if they are part of the secondary filter
   *
   */
  def getQueryOptions(filter: Filter): Seq[FilterPlan] = {
    rewriteFilterInDNF(filter) match {
      case o: Or  => getOrQueryOptions(o)
      case f      => getAndQueryOptions(f)
    }
  }

  private def getAndQueryOptions(f: Filter): Seq[FilterPlan] = {
    f match {
      case a: And => getAndQueryOptions(a.getChildren.sortBy(ECQL.toCQL))
      case _      => getAndQueryOptions(Seq(f))
    }
  }

  /**
   * Gets options based on 'anded' filters
   */
  private def getAndQueryOptions(filters: Seq[Filter]): Seq[FilterPlan] = {

    // check for filter.exclude - nothing will be returned
    if (filters.contains(Filter.EXCLUDE)) {
      return Seq.empty
    }

    val options = ArrayBuffer.empty[FilterPlan]

    // filter out any whole world geoms, since they are essentially meaningless
    val (invalid, validFilters) = filters.filter(_ != Filter.INCLUDE).partition(FilterHelper.isFilterWholeWorld)
    if (invalid.nonEmpty) {
      logger.debug(s"Dropping whole world filters: ${invalid.map(filterToString).mkString(", ")}")
    }
    val (spatial, temporal, attribute, dateAttribute, others) = partitionFilters(validFilters)

    // z3 and spatio-temporal
    if (supported.contains(Z3Table) && isBounded(temporal)) {
      // z3 works pretty well for temporal only queries - we add a whole world bbox later
      val primary = spatial ++ temporal
      val secondary = andOption(attribute ++ others)
      options.append(FilterPlan(Seq(QueryFilter(StrategyType.Z3, primary, secondary))))
    } else if (supported.contains(SpatioTemporalTable) && spatial.nonEmpty) {
      val primary = spatial ++ temporal
      val secondary = andOption(attribute ++ others)
      options.append(FilterPlan(Seq(QueryFilter(StrategyType.ST, primary, secondary))))
    }

    // ids
    if (others.nonEmpty) {
      val ids = others.collect { case id: Id => id }
      if (supported.contains(RecordTable) && ids.nonEmpty) {
        val primary = ids
        val nonIds = others.filterNot(ids.contains) ++ spatial ++ temporal ++ attribute
        val secondary = andOption(nonIds)
        options.append(FilterPlan(Seq(QueryFilter(StrategyType.RECORD, primary, secondary))))
      }
    }

    // attributes
    if (supported.contains(AttributeTable) && (attribute.nonEmpty || dateAttribute.nonEmpty)) {
      val allAttributes = attribute ++ dateAttribute
      val attributes = allAttributes.groupBy(getAttributeProperty(_).get.name)
      attributes.foreach { case (name, thisAttribute) =>
        val primary = thisAttribute
        val nonPrimary = allAttributes.filterNot(thisAttribute.contains) ++
            temporal.filterNot(thisAttribute.contains) ++ spatial ++ others
        val secondary = andOption(nonPrimary)
        options.append(FilterPlan(Seq(QueryFilter(StrategyType.ATTRIBUTE, primary, secondary))))
      }
    }

    // fall back - full table scan
    if (options.isEmpty) {
      options.append(fullTableScanOption(andOption(validFilters)))
    }
    options.toSeq
  }

  private def getOrQueryOptions(filter: Or): Seq[FilterPlan] = {

    // for each child of the or, get the query options
    // each filter plan should only have a single query filter
    def getChildOptions: Seq[Seq[FilterPlan]] = filter.getChildren.map(getAndQueryOptions)

    // combine the filter plans so that each plan has multiple query filters
    // use the permutations of the different options for each child
    def reduceChildOptions(childOptions: Seq[Seq[FilterPlan]]): Seq[FilterPlan] =
      childOptions.reduce { (left, right) =>
        left.flatMap(l => right.map(r => FilterPlan(l.filters ++ r.filters)))
      }

    // try to combine query filters in each filter plan if they have the same primary filter
    // this avoids scanning the same ranges twice with different secondary predicates
    def combineSecondaryFilters(options: Seq[FilterPlan]): Seq[FilterPlan] = options.map { r =>
      val groups = r.filters.distinct.groupBy(d => (d.strategy, d.primary)).map { case (group, secondaries) =>
        QueryFilter(group._1, group._2, orOption(secondaries.flatMap(_.secondary)))
      }
      FilterPlan(groups.toSeq)
    }

    // if a filter plan has any query filters that scan a subset of the range of a different query filter,
    // then we can combine them, as we have to scan the larger range anyway
    def mergeOverlappedFilters(options: Seq[FilterPlan]): Seq[FilterPlan] = options.map { filterPlan =>
      val filters = ArrayBuffer(filterPlan.filters: _*)
      var merged: QueryFilter = null
      var i = 0
      while (i < filters.length) {
        val toMerge = filters(i)
        if (toMerge != null) {
          var j = 0
          while (j < filters.length && merged == null) {
            if (i != j) {
              val mergeTo = filters(j)
              if (mergeTo != null) {
                merged = tryMerge(toMerge, mergeTo)
              }
            }
            j += 1
          }
          if (merged != null) {
            // remove the merged query filter and replace the one merged into
            filters.update(i, null)
            filters.update(j - 1, merged)
            merged = null
          }
        }
        i += 1
      }

      // if we have replaced anything, recreate the filter plan
      val overlapped = filters.filter(_ != null)
      if (overlapped.length < filterPlan.filters.length) {
        FilterPlan(overlapped)
      } else {
        filterPlan
      }
    }

    mergeOverlappedFilters(combineSecondaryFilters(reduceChildOptions(getChildOptions)))
  }

  /**
   * Splits filters up according to the filter type. Note that the 'dateAttribute' will be a duplicate
   * of a filter in the 'temporal' filter list.
   */
  private def partitionFilters(filters: Seq[Filter]) = {
    val (spatial, nonSpatial)         = partitionPrimarySpatials(filters, sft)
    val (temporal, nonSpatioTemporal) = partitionPrimaryTemporals(nonSpatial, sft)
    val (attribute, others)           = partitionIndexedAttributes(nonSpatioTemporal, sft)
    val dateAttribute                 = partitionIndexedAttributes(temporal, sft)._1

    (spatial, temporal, attribute, dateAttribute, others)
  }

  /**
   * Try to merge the two query filters. Return the merged query filter if successful, else null.
   */
  private def tryMerge(toMerge: QueryFilter, mergeTo: QueryFilter): QueryFilter = {
    if (mergeTo.primary.forall(_ == Filter.INCLUDE)) {
      // this is a full table scan, we can just append the OR to the secondary filter
      val secondary = orOption(mergeTo.secondary.toSeq ++ andOption(toMerge.primary ++ toMerge.secondary.toSeq))
      mergeTo.copy(secondary = secondary)
    } else {
      // TODO we could technically check for overlapping geoms, date ranges, attribute ranges, etc
      // not sure it's worth it though
      null
    }
  }

  /**
   * Returns true if the temporal filters create a range with an upper and lower bound
   */
  private def isBounded(temporalFilters: Seq[Filter]): Boolean = {
    import FilterHelper._
    val interval = extractInterval(temporalFilters, sft.getDtgField)
    interval != null && interval.getStart != minDateTime && interval.getEnd != maxDateTime
  }

  /**
   * Will perform a full table scan - used when we don't have anything better. Uses record table
   */
  private def fullTableScanOption(filter: Option[Filter]): FilterPlan =
    FilterPlan(Seq(QueryFilter(StrategyType.RECORD, Seq(Filter.INCLUDE), filter)))
}

/**
 * Filters split into a 'primary' that will be used for range planning,
 * and a 'secondary' that will be applied as a final step.
 */
case class QueryFilter(strategy: StrategyType, primary: Seq[Filter], secondary: Option[Filter] = None) {
  lazy val filter = andFilters(primary ++ secondary)
  override lazy val toString: String = s"$strategy[${primary.map(filterToString).mkString(" AND ")}]" +
      s"[${secondary.map(filterToString).getOrElse("None")}]"
}

/**
 * A series of queries required to satisfy a filter - basically split on ORs
 */
case class FilterPlan(filters: Seq[QueryFilter]) {
  override lazy val toString: String = s"FilterPlan[${filters.mkString(",")}]"
}

