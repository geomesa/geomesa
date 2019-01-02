/***********************************************************************
 * Copyright (c) 2013-2019 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.index.view

import java.awt.RenderingHints.Key
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

import com.typesafe.scalalogging.LazyLogging
import org.geotools.data._
import org.geotools.data.simple.{SimpleFeatureCollection, SimpleFeatureSource}
import org.geotools.data.store.DataFeatureCollection
import org.geotools.factory.Hints
import org.geotools.feature.visitor.{BoundsVisitor, MaxVisitor, MinVisitor}
import org.geotools.geometry.jts.ReferencedEnvelope
import org.locationtech.geomesa.index.geotools.GeoMesaFeatureCollection
import org.locationtech.geomesa.index.geotools.GeoMesaFeatureSource.DelegatingResourceInfo
import org.locationtech.geomesa.index.planning.QueryPlanner
import org.locationtech.geomesa.index.process.GeoMesaProcessVisitor
import org.locationtech.geomesa.index.view.MergedFeatureSourceView.MergedQueryCapabilities
import org.opengis.feature.FeatureVisitor
import org.opengis.feature.`type`.Name
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter
import org.opengis.filter.expression.PropertyName
import org.opengis.filter.sort.SortBy
import org.opengis.util.ProgressListener

/**
  * Feature source for merged data store view
  *
  * @param ds data store
  * @param sources delegate feature sources
  * @param sft simple feature type
  */
class MergedFeatureSourceView(ds: MergedDataStoreView, sources: Seq[SimpleFeatureSource], sft: SimpleFeatureType)
    extends SimpleFeatureSource with LazyLogging {

  lazy private val hints = Collections.unmodifiableSet(Collections.emptySet[Key])

  lazy private val capabilities = new MergedQueryCapabilities(sources.map(_.getQueryCapabilities))

  override def getSchema: SimpleFeatureType = sft

  override def getCount(query: Query): Int = sources.map(_.getCount(query)).sum

  override def getBounds: ReferencedEnvelope = {
    val bounds = new ReferencedEnvelope(org.locationtech.geomesa.utils.geotools.CRS_EPSG_4326)
    sources.foreach(s => bounds.expandToInclude(s.getBounds))
    bounds
  }

  override def getBounds(query: Query): ReferencedEnvelope = {
    val bounds = new ReferencedEnvelope(org.locationtech.geomesa.utils.geotools.CRS_EPSG_4326)
    sources.foreach(s => bounds.expandToInclude(s.getBounds(query)))
    bounds
  }

  override def getQueryCapabilities: QueryCapabilities = capabilities

  override def getFeatures: SimpleFeatureCollection = getFeatures(Filter.INCLUDE)

  override def getFeatures(filter: Filter): SimpleFeatureCollection = getFeatures(new Query(sft.getTypeName, filter))

  override def getFeatures(query: Query): SimpleFeatureCollection = new MergedFeatureCollection(query)

  override def getName: Name = getSchema.getName

  override def getDataStore: DataStore = ds

  override def getSupportedHints: java.util.Set[Key] = hints

  override def getInfo: ResourceInfo = new DelegatingResourceInfo(this)

  override def addFeatureListener(listener: FeatureListener): Unit = throw new NotImplementedError()

  override def removeFeatureListener(listener: FeatureListener): Unit = throw new NotImplementedError()

  /**
    * Feature collection implementation
    *
    * @param query query
    */
  class MergedFeatureCollection(query: Query) extends DataFeatureCollection(GeoMesaFeatureCollection.nextId) {

    private val open = new AtomicBoolean(false)

    override def getSchema: SimpleFeatureType = {
      if (!open.get) {
        // once opened the query will already be configured by the query planner,
        // otherwise we have to compute it here
        ds.runner.configureQuery(sft, query)
      }
      // copy the query so that we don't set any hints for transforms, etc
      val copy = new Query(query)
      copy.setHints(new Hints(query.getHints))
      QueryPlanner.setQueryTransforms(copy, sft)
      ds.runner.getReturnSft(sft, copy.getHints)
    }

    override protected def openIterator(): java.util.Iterator[SimpleFeature] = {
      val iter = super.openIterator()
      open.set(true)
      iter
    }

    override def accepts(visitor: FeatureVisitor, progress: ProgressListener): Unit =
      visitor match {
        case v: BoundsVisitor =>
          v.reset(ds.stats.getBounds(sft, query.getFilter))

        case v: MinVisitor if v.getExpression.isInstanceOf[PropertyName] =>
          val attribute = v.getExpression.asInstanceOf[PropertyName].getPropertyName
          minMax(attribute, exact = false).orElse(minMax(attribute, exact = true)) match {
            case Some((min, _)) => v.setValue(min)
            case None           => super.accepts(visitor, progress)
          }

        case v: MaxVisitor if v.getExpression.isInstanceOf[PropertyName] =>
          val attribute = v.getExpression.asInstanceOf[PropertyName].getPropertyName
          minMax(attribute, exact = false).orElse(minMax(attribute, exact = true)) match {
            case Some((_, max)) => v.setValue(max)
            case None           => super.accepts(visitor, progress)
          }

        case v: GeoMesaProcessVisitor =>
          v.execute(MergedFeatureSourceView.this, query)

        case _ =>
          super.accepts(visitor, progress)
      }

    private def minMax(attribute: String, exact: Boolean): Option[(Any, Any)] =
      ds.stats.getAttributeBounds[Any](sft, attribute, query.getFilter, exact).map(_.bounds)

    override def reader(): FeatureReader[SimpleFeatureType, SimpleFeature] =
      ds.getFeatureReader(query, Transaction.AUTO_COMMIT)

    override def getBounds: ReferencedEnvelope = MergedFeatureSourceView.this.getBounds(query)

    override def getCount: Int = MergedFeatureSourceView.this.getCount(query)

    override def size: Int = {
      // note: we shouldn't return -1 here, but we don't return the actual value unless EXACT_COUNT is set
      val count = getCount
      if (count < 0) { 0 } else { count }
    }
  }
}

object MergedFeatureSourceView {

  /**
    * Query capabilities
    *
    * @param capabilities delegates
    */
  class MergedQueryCapabilities(capabilities: Seq[QueryCapabilities]) extends QueryCapabilities {
    override def isOffsetSupported: Boolean = capabilities.forall(_.isOffsetSupported)
    override def supportsSorting(sortAttributes: Array[SortBy]): Boolean =
      capabilities.forall(_.supportsSorting(sortAttributes))
    override def isReliableFIDSupported: Boolean = capabilities.forall(_.isReliableFIDSupported)
    override def isUseProvidedFIDSupported: Boolean = capabilities.forall(_.isUseProvidedFIDSupported)
    override def isJoiningSupported: Boolean = capabilities.forall(_.isJoiningSupported)
    override def isVersionSupported: Boolean = capabilities.forall(_.isVersionSupported)
  }
}
