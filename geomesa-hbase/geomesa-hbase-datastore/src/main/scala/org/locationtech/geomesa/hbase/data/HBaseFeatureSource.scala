/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.hbase.data

import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.geotools.data.store.{ContentEntry, ContentFeatureStore}
import org.geotools.data.{FeatureReader, FeatureWriter, Query, QueryCapabilities}
import org.geotools.geometry.jts.ReferencedEnvelope
import org.joda.time.{DateTime, DateTimeZone, Interval}
import org.locationtech.geomesa.features.kryo.KryoFeatureSerializer
import org.locationtech.geomesa.filter
import org.locationtech.geomesa.filter.FilterHelper._
import org.locationtech.geomesa.utils.geotools
import org.locationtech.geomesa.utils.geotools.ContentFeatureSourceInfo
import org.locationtech.geomesa.utils.text.WKTUtils
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.{And, Filter}

class HBaseFeatureSource(e: ContentEntry,
                         query: Query,
                         sft: SimpleFeatureType)
    extends ContentFeatureStore(e, query) with LazyLogging
    with ContentFeatureSourceInfo {
  import geotools._

  import scala.collection.JavaConversions._

  private val dtgIndex =
    sft.getAttributeDescriptors
      .zipWithIndex
      .find { case (ad, idx) => classOf[java.util.Date].equals(ad.getType.getBinding) }
      .map  { case (_, idx)  => idx }
      .getOrElse(throw new RuntimeException("No date attribute"))

  type FR = FeatureReader[SimpleFeatureType, SimpleFeature]
  private val ds = entry.getDataStore.asInstanceOf[HBaseDataStore]

  private val bounds = ReferencedEnvelope.create(new Envelope(-180, 180, -90, 90), CRS_EPSG_4326)

  override def buildFeatureType(): SimpleFeatureType = sft

  override def getBoundsInternal(query: Query): ReferencedEnvelope = bounds

  override def getCountInternal(query: Query): Int = Int.MaxValue

  override def getWriterInternal(query: Query, flags: Int): FeatureWriter[SimpleFeatureType, SimpleFeature] = {
    if (query.getFilter == null || query.getFilter == Filter.INCLUDE) {
      new HBaseFeatureWriterAppend(sft, ds.getZ3Table(sft))
    } else {
      throw new NotImplementedError("Updating features not supported")
    }
  }

  override def getReaderInternal(query: Query): FR = {
    if (query.getFilter == null || query.getFilter == Filter.INCLUDE) {
      include()
    } else {
      filter.rewriteFilterInCNF(query.getFilter)(filter.ff) match {
        case a: And => {
          val (spatialFilter, temporalFilter, postFilter) = partitionFilters(a.getChildren)
          if (temporalFilter.isEmpty || !isBounded(temporalFilter)) {
            logger.warn(s"Temporal filter missing or not fully bounded; falling back to full-table scan for $query")
            include(Some(query.getFilter))
          } else {
            and(a)
          }
        }
        case _ =>
          logger.warn(s"Failing back to full-table scan for $query.")
          include(Some(query.getFilter))
      }
    }
  }

  override protected def canFilter: Boolean = true
  override protected def canSort: Boolean = true
  override protected def canReproject: Boolean = true
  override protected def buildQueryCapabilities: QueryCapabilities = {
    new QueryCapabilities {
      override def isUseProvidedFIDSupported: Boolean = true
    }
  }

  private def include(clientFilter: Option[Filter] = None): FR = {
    new HBaseFeatureReader(ds.getZ3Table(sft), sft, 0, Seq.empty, new KryoFeatureSerializer(sft), clientFilter)
  }

  private def and(a: And): FR = {
    // TODO: currently assumes geom + dtg
    import HBaseFeatureSource.AllGeom
    import filter._

    // TODO: cache serializers
    val serializer = new KryoFeatureSerializer(sft)
    val table = ds.getZ3Table(sft)

    val (spatialFilter, temporalFilter, postFilter) = partitionFilters(a.getChildren)

    val (lx, ly, ux, uy) = {
      // note: because we AND the filters, we know at most one geometry will come back
      val geom =
        andOption(spatialFilter)
            .flatMap(extractGeometries(_, sft.getGeometryDescriptor.getLocalName).headOption)
            .getOrElse(AllGeom)
      val env = geom.getEnvelopeInternal
      (env.getMinX, env.getMinY, env.getMaxX, env.getMaxY)
    }

    val (weeks, lt, ut) = {
      val dtFieldName = sft.getDescriptor(dtgIndex).getLocalName
      // note: because we and the filters, there will be only one interval
      // because we have already validated that the temporal filters exist, there will be at least 1 interval
      val (startTime, endTime) = extractIntervals(andFilters(temporalFilter), dtFieldName).head
      val lTime = dateToIndex(startTime)
      val uTime = dateToIndex(endTime)
      val weeks = scala.Range.inclusive(lTime.bin, uTime.bin)
      (weeks, lTime.offset, uTime.offset)
    }

    // time range for a chunk is 0 to 1 week (in seconds)
    val (tStart, tEnd) = (SFC.time.min.toLong, SFC.time.max.toLong)

    // the z3 index breaks time into 1 week chunks, so create a range for each week in our range
    // TODO: ignoring seconds for now
    if (weeks.length == 1) {
      val ranges = SFC.ranges((lx, ux), (ly, uy), (lt, ut))
      new HBaseFeatureReader(table, sft, weeks.head, ranges, serializer, Some(a))
    } else {
      val head +: xs :+ last = weeks.toList

      val headRanges     = SFC.ranges((lx, ux), (ly, uy), (lt, tEnd))
      lazy val midRanges = SFC.ranges((lx, ux), (ly, uy), (tStart, tEnd))
      val lastRanges     = SFC.ranges((lx, ux), (ly, uy), (tStart, ut))

      val headReader = new HBaseFeatureReader(table, sft, head, headRanges, serializer, Some(a))
      val middleReaders = xs.map { w =>
        new HBaseFeatureReader(table, sft, w, midRanges, serializer, Some(a))
      }
      val lastReader = new HBaseFeatureReader(table, sft, head, lastRanges, serializer, Some(a))

      val readers = Seq(headReader) ++ middleReaders ++ Seq(lastReader)

      new FeatureReader[SimpleFeatureType, SimpleFeature] {
        val readerIter = readers.iterator
        var curReader = readerIter.next()

        override def next(): SimpleFeature = {
          curReader.next()
        }

        override def hasNext: Boolean =
          if (curReader.hasNext) {
            true
          } else {
            curReader.close()
            if (readerIter.hasNext) {
              curReader = readerIter.next()
              hasNext
            } else {
              false
            }
          }

        override def getFeatureType: SimpleFeatureType = sft

        override def close(): Unit = {
          readers.foreach(_.close())
        }
      }
    }
  }

  import org.locationtech.geomesa.filter._

  private def partitionFilters(filters: Seq[Filter]) = {
    val (spatial, nonSpatial)         = partitionPrimarySpatials(filters, sft)
    val dtFieldName = sft.getDescriptor(dtgIndex).getLocalName
    val (temporal, nonSpatioTemporal) = nonSpatial.partition(isTemporalFilter(_, dtFieldName))

    (spatial, temporal, andOption(nonSpatioTemporal))
  }

  private def isBounded(temporalFilters: Seq[Filter]): Boolean = {
    andOption(temporalFilters)
        .flatMap(FilterHelper.extractIntervals(_, sft.getDescriptor(dtgIndex).getLocalName).headOption)
        .exists(i => i._1 != MinDateTime && i._2 != MaxDateTime)
  }
}

object HBaseFeatureSource {
  val AllGeom = WKTUtils.read("POLYGON((-180 -90, 0 -90, 180 -90, 180 90, 0 90, -180 90, -180 -90))")
  // Z3-indexable dates: "[1901-12-13T20:45:51.001Z, 2038-01-19T03:14:07.999Z]"
  // rounding in a little bit to make it clear these are arbitrary dates
  val MinDateTime = new DateTime(1901, 12, 31, 0, 0, 0, DateTimeZone.UTC).getMillis
  val MaxDateTime = new DateTime(2038,  1,  1, 0, 0, 0, DateTimeZone.UTC).getMillis
  val AllDateTime = new Interval(MinDateTime, MaxDateTime, DateTimeZone.UTC)
}