/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.hbase.index

import com.typesafe.scalalogging.LazyLogging
import org.apache.hadoop.fs.Path
import org.apache.hadoop.hbase._
import org.apache.hadoop.hbase.client._
import org.apache.hadoop.hbase.filter.{KeyOnlyFilter, Filter => HFilter}
import org.apache.hadoop.hbase.util.Bytes
import org.geotools.factory.Hints
import org.locationtech.geomesa.hbase._
import org.locationtech.geomesa.hbase.coprocessor.KryoLazyDensityCoprocessor
import org.locationtech.geomesa.hbase.data._
import org.locationtech.geomesa.hbase.filters.JSimpleFeatureFilter
import org.locationtech.geomesa.hbase.index.HBaseFeatureIndex.ScanConfig
import org.locationtech.geomesa.index.index.ClientSideFiltering.RowAndValue
import org.locationtech.geomesa.index.index.{ClientSideFiltering, IndexAdapter}
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.index.IndexMode.IndexMode
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter

object HBaseFeatureIndex extends HBaseIndexManagerType {

  // note: keep in priority order for running full table scans
  override val AllIndices: Seq[HBaseFeatureIndex] =
    Seq(HBaseZ3Index, HBaseXZ3Index, HBaseZ2Index, HBaseXZ2Index, HBaseIdIndex, HBaseAttributeIndex,
      HBaseAttributeIndexV2, HBaseAttributeDateIndex)

  override val CurrentIndices: Seq[HBaseFeatureIndex] =
    Seq(HBaseZ3Index, HBaseXZ3Index, HBaseZ2Index, HBaseXZ2Index, HBaseIdIndex, HBaseAttributeIndex)

  override def indices(sft: SimpleFeatureType, mode: IndexMode): Seq[HBaseFeatureIndex] =
    super.indices(sft, mode).asInstanceOf[Seq[HBaseFeatureIndex]]
  override def index(identifier: String): HBaseFeatureIndex =
    super.index(identifier).asInstanceOf[HBaseFeatureIndex]

  val DataColumnFamily: Array[Byte] = Bytes.toBytes("d")
  val DataColumnFamilyDescriptor = new HColumnDescriptor(DataColumnFamily)

  val DataColumnQualifier: Array[Byte] = Bytes.toBytes("d")
  val DataColumnQualifierDescriptor = new HColumnDescriptor(DataColumnQualifier)

  case class ScanConfig(filters: Seq[HFilter],
                        coprocessor: Option[Coprocessor],
                        entriesToFeatures: Iterator[Result] => Iterator[SimpleFeature])
}

trait HBaseFeatureIndex extends HBaseFeatureIndexType
  with IndexAdapter[HBaseDataStore, HBaseFeature, Mutation, Query] with ClientSideFiltering[Result] with LazyLogging {

  import HBaseFeatureIndex.{DataColumnFamily, DataColumnQualifier}

  override def configure(sft: SimpleFeatureType, ds: HBaseDataStore): Unit = {
    super.configure(sft, ds)
    val name = TableName.valueOf(getTableName(sft.getTypeName, ds))
    val coproUrl = ds.config.coprocessorUrl
    val admin = ds.connection.getAdmin

    def addCoprocessor(clazz: Class[_ <: Coprocessor], desc: HTableDescriptor, path: Path): Unit ={
      val name = clazz.getCanonicalName
      if (!desc.getCoprocessors.contains(name)) {
        if (!path.equals(HBaseDataStoreParams.CoprocessorUrl.sample)) {
          desc.addCoprocessor(name, path, Coprocessor.PRIORITY_USER, null)
        } else {
          desc.addCoprocessor(name)
        }
      }
    }

    def addCoprocessors(desc: HTableDescriptor, path: Path): Unit = {
      Seq(
        classOf[KryoLazyDensityCoprocessor]
      ).foreach(c => addCoprocessor(c, desc, path))
    }

    try {
      val descriptor = new HTableDescriptor(name)
      if (!admin.tableExists(name)) {
        logger.debug("Attempting coprocessor registration")
        addCoprocessors(descriptor, coproUrl)
        descriptor.addFamily(HBaseFeatureIndex.DataColumnFamilyDescriptor)
        admin.createTable(descriptor, getSplits(sft).toArray)
      } else {
        if (!descriptor.getCoprocessors.contains(classOf[KryoLazyDensityCoprocessor].toString)) {
          logger.info(s"Adding coprocessor registration to $name")

          admin.disableTable(name)
          addCoprocessors(descriptor, coproUrl)
          admin.modifyTable(name, descriptor)
          admin.enableTable(name)

          logger.info("Coprocessors Registered, waiting for replication")

          def wait(): Unit = {
            if (admin.getAlterStatus(name).getFirst > 0) {
              Thread sleep 100
              wait()
            }
          }
          wait()

          logger.info("Registration complete")
        } else {
          logger.warn(s"GeoMesa coprocessors are not registered for table: $name")
        }
      }
    } finally {
      admin.close()
    }
  }

  override def delete(sft: SimpleFeatureType, ds: HBaseDataStore, shared: Boolean): Unit = {
    import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType

    import scala.collection.JavaConversions._

    if (shared) {
      val table = ds.connection.getTable(TableName.valueOf(getTableName(sft.getTypeName, ds)))
      try {
        val scan = new Scan()
          .setRowPrefixFilter(sft.getTableSharingBytes)
          .setFilter(new KeyOnlyFilter)
        ds.applySecurity(scan)
        val scanner = table.getScanner(scan)
        try {
          scanner.iterator.grouped(10000).foreach { result =>
            // TODO set delete visibilities
            val deletes = result.map(r => new Delete(r.getRow))
            table.delete(deletes)
          }
        } finally {
          scanner.close()
        }
      } finally {
        table.close()
      }
    } else {
      val table = TableName.valueOf(getTableName(sft.getTypeName, ds))
      val admin = ds.connection.getAdmin
      try {
        admin.disableTable(table)
        admin.deleteTable(table)
      } finally {
        admin.close()
      }
    }
  }

  override protected def createInsert(row: Array[Byte], feature: HBaseFeature): Mutation = {
    val put = new Put(row).addImmutable(feature.fullValue.cf, feature.fullValue.cq, feature.fullValue.value)
    feature.fullValue.vis.foreach(put.setCellVisibility)
    put
  }

  override protected def createDelete(row: Array[Byte], feature: HBaseFeature): Mutation = {
    val del = new Delete(row).addFamily(feature.fullValue.cf)
    feature.fullValue.vis.foreach(del.setCellVisibility)
    del
  }

  override protected def range(start: Array[Byte], end: Array[Byte]): Query =
    new Scan(start, end).addColumn(DataColumnFamily, DataColumnQualifier)

  override protected def rangeExact(row: Array[Byte]): Query =
    new Get(row).addColumn(DataColumnFamily, DataColumnQualifier)

  override def rowAndValue(result: Result): RowAndValue = {
    val cell = result.rawCells()(0)
    RowAndValue(cell.getRowArray, cell.getRowOffset, cell.getRowLength,
      cell.getValueArray, cell.getValueOffset, cell.getValueLength)
  }

  override protected def scanPlan(sft: SimpleFeatureType,
                                  ds: HBaseDataStore,
                                  filter: HBaseFilterStrategyType,
                                  hints: Hints,
                                  ranges: Seq[Query],
                                  ecql: Option[Filter]): HBaseQueryPlanType = {
    if (ranges.isEmpty) { EmptyPlan(filter) } else {
      val table = TableName.valueOf(getTableName(sft.getTypeName, ds))
      val dedupe = hasDuplicates(sft, filter.primary)
      val ScanConfig(hbaseFilters, coprocessor, toFeatures) = scanConfig(ds, sft, filter, hints, ecql, dedupe)
      buildPlatformScanPlan(ds, sft, filter, hints, ranges, table, hbaseFilters, coprocessor, toFeatures)
    }
  }

  /**
    * Sets up everything needed to execute the scan - iterators, column families, deserialization, etc
    *
    * @param ds     data store
    * @param sft    simple feature type
    * @param filter hbase filter strategy type
    * @param hints  query hints
    * @param ecql   secondary filter being applied, if any
    * @param dedupe scan may have duplicate results or not
    * @return
    */
  protected def scanConfig(ds: HBaseDataStore,
                           sft: SimpleFeatureType,
                           filter: HBaseFilterStrategyType,
                           hints: Hints,
                           ecql: Option[Filter],
                           dedupe: Boolean): ScanConfig = {

    import org.locationtech.geomesa.index.conf.QueryHints.RichHints

    val transform: Option[(String, SimpleFeatureType)] = hints.getTransform

    if (!ds.config.remoteFilter) {
      // everything is done client side
      ScanConfig(Seq.empty, None, resultsToFeatures(sft, ecql, transform))
    } else {

      val coprocessor: Option[Coprocessor] = if (hints.isDensityQuery) {
        Some(new KryoLazyDensityCoprocessor)
      } else {
        None
      }

      val (remoteTdefArg, returnSchema) = transform.getOrElse(("", sft))
      val toFeatures = resultsToFeatures(returnSchema, None, None)
      val filterTransform: Seq[HFilter] = if (ecql.isEmpty && transform.isEmpty) { Seq.empty } else {
        // transforms and filters are applied server-side
        val remoteCQLFilter: Filter = ecql.getOrElse(Filter.INCLUDE)
        Seq(new JSimpleFeatureFilter(sft, remoteCQLFilter, remoteTdefArg, SimpleFeatureTypes.encodeType(returnSchema)))
      }

      val additionalFilters = createPushDownFilters(ds, sft, filter, transform)
      ScanConfig(filterTransform ++ additionalFilters, coprocessor, toFeatures)
    }
  }

  protected def hasDuplicates(sft: SimpleFeatureType, filter: Option[Filter]): Boolean = false

  // default implementation does nothing, override in subclasses
  protected def createPushDownFilters(ds: HBaseDataStore,
                                      sft: SimpleFeatureType,
                                      filter: HBaseFilterStrategyType,
                                      transform: Option[(String, SimpleFeatureType)]): Seq[HFilter] = Seq.empty

  protected def buildPlatformScanPlan(ds: HBaseDataStore,
                                      sft: SimpleFeatureType,
                                      filter: HBaseFilterStrategyType,
                                      hints: Hints,
                                      ranges: Seq[Query],
                                      table: TableName,
                                      hbaseFilters: Seq[HFilter],
                                      coprocessor: Option[Coprocessor],
                                      toFeatures: (Iterator[Result]) => Iterator[SimpleFeature]): HBaseQueryPlan
}
