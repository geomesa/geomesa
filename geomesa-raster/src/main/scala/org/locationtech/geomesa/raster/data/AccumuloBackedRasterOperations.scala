/*
 * Copyright 2014 Commonwealth Computer Research, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.locationtech.geomesa.raster.data

import java.awt.image.BufferedImage
import java.util.Map.Entry
import java.util.concurrent.{Callable, TimeUnit}

import com.google.common.cache.CacheBuilder
import com.google.common.collect.ImmutableSetMultimap
import org.apache.accumulo.core.client.{BatchWriterConfig, Connector, TableExistsException}
import org.apache.accumulo.core.data.{Key, Mutation, Range, Value}
import org.apache.accumulo.core.security.{Authorizations, TablePermission}
import org.geotools.coverage.grid.GridEnvelope2D
import org.joda.time.DateTime
import org.locationtech.geomesa.core.index._
import org.locationtech.geomesa.core.iterators.BBOXCombiner._
import org.locationtech.geomesa.core.security.AuthorizationsProvider
import org.locationtech.geomesa.core.stats.{RasterQueryStat, RasterQueryStatTransform, StatWriter}
import org.locationtech.geomesa.core.util.{SelfClosingBatchScanner, SelfClosingScanner}
import org.locationtech.geomesa.raster._
import org.locationtech.geomesa.raster.index.RasterIndexSchema
import org.locationtech.geomesa.raster.util.RasterUtils
import org.locationtech.geomesa.utils.geohash.BoundingBox
import org.locationtech.geomesa.utils.stats.{MethodProfiling, TimingsImpl}

import scala.collection.JavaConversions._

trait RasterOperations extends StrategyHelpers {
  def getTable(): String
  def ensureBoundsTableExists(): Unit
  def createTableStructure(): Unit
  def getAuths(): Authorizations
  def getVisibility(): String
  def getConnector(): Connector
  def getRasters(rasterQuery: RasterQuery): Iterator[Raster]
  def getQueryRecords(numRecords: Int): Iterator[String]
  def putRaster(raster: Raster): Unit
  def getBounds(): BoundingBox
  def getAvailableResolutions(): Seq[Double]
  def getAvailableGeoHashLengths(): Seq[Int]
  def getResolutionAndGeoHashLengthMap(): ImmutableSetMultimap[Double, Int]
  def getGridRange(): GridEnvelope2D
  def getMosaicedRaster(query: RasterQuery, params: GeoMesaCoverageQueryParams): BufferedImage
}

/**
 * This class handles Accumulo related operations including tables creation,
 * read data from /write data to Accumulo tables.
 *
 * @param connector
 * @param rasterTable
 * @param authorizationsProvider
 * @param writeVisibilities
 * @param shardsConfig
 * @param writeMemoryConfig
 * @param writeThreadsConfig
 * @param queryThreadsConfig
 */
class AccumuloBackedRasterOperations(val connector: Connector,
                                     val rasterTable: String,
                                     val authorizationsProvider: AuthorizationsProvider,
                                     val writeVisibilities: String,
                                     shardsConfig: Option[Int] = None,
                                     writeMemoryConfig: Option[String] = None,
                                     writeThreadsConfig: Option[Int] = None,
                                     queryThreadsConfig: Option[Int] = None) extends RasterOperations with MethodProfiling with StatWriter {
  //By default having at least as many shards as tservers provides optimal parallelism in queries
  val shards = shardsConfig.getOrElse(connector.instanceOperations().getTabletServers.size())
  val writeMemory = writeMemoryConfig.getOrElse("10000").toLong
  val writeThreads = writeThreadsConfig.getOrElse(10)
  val bwConfig: BatchWriterConfig =
    new BatchWriterConfig().setMaxMemory(writeMemory).setMaxWriteThreads(writeThreads)
  val numQThreads = queryThreadsConfig.getOrElse(20)

  // TODO: WCS: GEOMESA-585 Add ability to use arbitrary schemas
  val schema = RasterIndexSchema("")

  lazy val queryPlanner: AccumuloRasterQueryPlanner = new AccumuloRasterQueryPlanner(schema)

  private val tableOps = connector.tableOperations()
  private val securityOps = connector.securityOperations

  def getAuths() = authorizationsProvider.getAuthorizations

  //TODO: WCS: this needs to be implemented .. or  maybe not
  //lazy val aRasterReader = new AccumuloRasterReader(tableName)

  def getVisibility() = writeVisibilities

  def getConnector() = connector

  def getTable() = rasterTable

  private def getBoundsRowID = rasterTable + "_bounds"

  def putRasters(rasters: Seq[Raster]) {
    rasters.foreach { putRaster(_) }
  }

  def putRaster(raster: Raster) {
    writeMutations(rasterTable, createMutation(raster))
    writeMutations(GEOMESA_RASTER_BOUNDS_TABLE, createBoundsMutation(raster))
  }

  def getMosaicedRaster(query: RasterQuery, params: GeoMesaCoverageQueryParams) = {
    implicit val timings = new TimingsImpl
    val rasters = getRastersWithTiming(query)

    val (image, numRasters) = profile("mosaic") {
      RasterUtils.mosaicChunks(rasters,
                                params.height.toInt,
                                params.width.toInt,
                                params.envelope)
    }
    val stat = RasterQueryStat(rasterTable,
      System.currentTimeMillis(),
      query.toString,
      timings.time("planning"),
      timings.time("scanning") - timings.time("planning"),
      timings.time("mosaic"),
      numRasters)
    this.writeStat(stat, s"${rasterTable}_queries")
    image
  }

  def getRastersWithTiming(rasterQuery: RasterQuery)(implicit timings: TimingsImpl): Iterator[Raster] = {
    profile("scanning") {
      val batchScanner = connector.createBatchScanner(rasterTable, authorizationsProvider.getAuthorizations, numQThreads)
      val plan = profile(queryPlanner.getQueryPlan(rasterQuery, getResolutionAndGeoHashLengthMap), "planning")
      configureBatchScanner(batchScanner, plan)
      adaptIteratorToChunks(SelfClosingBatchScanner(batchScanner))
    }
  }

  // Consider a no-op timing option to unify getRasters(WithTiming) https://geomesa.atlassian.net/browse/GEOMESA-672
  def getRasters(rasterQuery: RasterQuery): Iterator[Raster] = {
    val batchScanner = connector.createBatchScanner(rasterTable, authorizationsProvider.getAuthorizations, numQThreads)
    val plan = queryPlanner.getQueryPlan(rasterQuery, getResolutionAndGeoHashLengthMap)
    configureBatchScanner(batchScanner, plan)
    adaptIteratorToChunks(SelfClosingBatchScanner(batchScanner))
  }

  def getQueryRecords(numRecords: Int): Iterator[String] = {
    val scanner = connector.createScanner(s"${rasterTable}_queries", authorizationsProvider.getAuthorizations)
    scanner.iterator.take(numRecords).map(RasterQueryStatTransform.decodeStat)
  }

  def getBounds(): BoundingBox = {
    ensureTableExists(GEOMESA_RASTER_BOUNDS_TABLE)
    val scanner = connector.createScanner(GEOMESA_RASTER_BOUNDS_TABLE, authorizationsProvider.getAuthorizations)
    scanner.setRange(new Range(getBoundsRowID))
    val resultingBounds = SelfClosingScanner(scanner)
    if (resultingBounds.isEmpty) {
      BoundingBox(-180, 180, -90, 90)
    } else {
      //TODO: GEOMESA-646 anti-meridian questions
      reduceValuesToBoundingBox(resultingBounds.map(_.getValue))
    }
  }

  def getAvailableResolutions(): Seq[Double] = {
    // TODO: Consider adding resolutions + extent info  https://geomesa.atlassian.net/browse/GEOMESA-645
    getResolutionAndGeoHashLengthMap().keySet.toSeq.sorted
  }

  def getAvailableGeoHashLengths(): Seq[Int] = {
    getResolutionAndGeoHashLengthMap().values.toSeq.distinct
  }

  def getResolutionAndGeoHashLengthMap(): ImmutableSetMultimap[Double, Int] = {
    AccumuloBackedRasterOperations.boundsCache.get(rasterTable, callable)
  }

  def callable = new Callable[ImmutableSetMultimap[Double, Int]] {
    override def call(): ImmutableSetMultimap[Double, Int] = {
      ensureTableExists(GEOMESA_RASTER_BOUNDS_TABLE)
      val scanner = connector.createScanner(GEOMESA_RASTER_BOUNDS_TABLE, getAuths())
      scanner.setRange(new Range(getBoundsRowID))
      val scanResultingKeys = SelfClosingScanner(scanner).map(_.getKey).toSeq
      val geohashlens = scanResultingKeys.map(_.getColumnFamily.toString).map(lexiDecodeStringToInt)
      val resolutions = scanResultingKeys.map(_.getColumnQualifier.toString).map(lexiDecodeStringToDouble)
      val m = new ImmutableSetMultimap.Builder[Double, Int]()
      (resolutions zip geohashlens).foreach(x => m.put(x._1, x._2))
      m.build()
    }
  }

  def getGridRange(): GridEnvelope2D = {
    val bounds = getBounds()
    val resolutions = getAvailableResolutions()
    // If no resolutions are available, then we have an empty table so assume 1.0 for now
    val resolution = resolutions match {
      case Nil => 1.0
      case _   => resolutions.min
    }
    val width  = Math.abs(bounds.getWidth / resolution).toInt
    val height = Math.abs(bounds.getHeight / resolution).toInt

    new GridEnvelope2D(0, 0, width, height)
  }

  def adaptIteratorToChunks(iter: java.util.Iterator[Entry[Key, Value]]): Iterator[Raster] = {
    iter.map { entry => schema.decode((entry.getKey, entry.getValue)) }
  }

  def createTableStructure() = {
    ensureTableExists(rasterTable)
    ensureBoundsTableExists()
  }

  def ensureBoundsTableExists() = {
    ensureTableExists(GEOMESA_RASTER_BOUNDS_TABLE)
    if (!tableOps.listIterators(GEOMESA_RASTER_BOUNDS_TABLE).containsKey("GEOMESA_BBOX_COMBINER")) {
      val bboxcombinercfg =  AccumuloRasterBoundsPlanner.getBoundsScannerCfg(rasterTable)
      tableOps.attachIterator(GEOMESA_RASTER_BOUNDS_TABLE, bboxcombinercfg)
    }
  }

  private def dateToAccTimestamp(dt: DateTime): Long =  dt.getMillis / 1000

  private def createBoundsMutation(raster: Raster): Mutation = {
    // write the bounds mutation
    val mutation = new Mutation(getBoundsRowID)
    val value = bboxToValue(BoundingBox(raster.metadata.geom.getEnvelopeInternal))
    val resolution = lexiEncodeDoubleToString(raster.resolution)
    val geohashlen = lexiEncodeIntToString(raster.minimumBoundingGeoHash.map( _.hash.length ).getOrElse(0))
    mutation.put(geohashlen, resolution, value)
    mutation
  }

  /**
   * Create Mutation instance from input Raster instance
   *
   * @param raster Raster instance
   * @return Mutation instance
   */
  private def createMutation(raster: Raster): Mutation = {
    val (key, value) = schema.encode(raster, writeVisibilities)
    val mutation = new Mutation(key.getRow)
    val colFam   = key.getColumnFamily
    val colQual  = key.getColumnQualifier
    val colVis   = key.getColumnVisibilityParsed
    // TODO: WCS: determine if this is wise/useful
    // GEOMESA-562
    val timestamp: Long = dateToAccTimestamp(raster.time)
    mutation.put(colFam, colQual, colVis, timestamp, value)
    mutation
  }

  /**
   * Write mutations into accumulo table
   *
   * @param mutations
   */
  private def writeMutations(tableName: String, mutations: Mutation*) {
    val writer = connector.createBatchWriter(tableName, bwConfig)
    mutations.foreach { m => writer.addMutation(m) }
    writer.flush()
    writer.close()
  }

  /**
   * Create table if it doesn't exist.
   *
   * @param tableName
   */
  private def ensureTableExists(tableName: String) {
    // TODO: WCS: ensure that this does not duplicate what is done in AccumuloDataStore
    // Perhaps consolidate with different default configurations
    // GEOMESA-564
    val user = connector.whoami
    val defaultVisibilities = authorizationsProvider.getAuthorizations.toString.replaceAll(",", "&")
    if (!tableOps.exists(tableName)) {
      try {
        createTables(Array(tableName, s"${tableName}_queries"), user, defaultVisibilities)
      } catch {
        case e: TableExistsException => // this can happen with multiple threads but shouldn't cause any issues
      }
    }
  }

  private def createTables(tableNames: Array[String], user: String, defaultVisibilities: String) = {
    tableNames.foreach(tableName => {
      tableOps.create(tableName)
      RasterTableConfig.settings(defaultVisibilities).foreach { case (key, value) =>
        tableOps.setProperty(tableName, key, value)
      }
      RasterTableConfig.permissions.split(",").foreach { p =>
        securityOps.grantTablePermission(user, tableName, TablePermission.valueOf(p))
      }
    })
  }
}

object AccumuloBackedRasterOperations {
  def apply(connector: Connector,
            tableName: String,
            authorizationsProvider: AuthorizationsProvider,
            visibility: String,
            shardsConfig: Option[Int],
            writeMemoryConfig: Option[String],
            writeThreadsConfig: Option[Int],
            queryThreadsConfig: Option[Int]): AccumuloBackedRasterOperations  =

      new AccumuloBackedRasterOperations(connector,
                                         tableName,
                                         authorizationsProvider,
                                         visibility,
                                         shardsConfig,
                                         writeMemoryConfig,
                                         writeThreadsConfig,
                                         queryThreadsConfig)

  val boundsCache =
    CacheBuilder.newBuilder()
      .expireAfterAccess(10, TimeUnit.MINUTES)
      .expireAfterWrite(10, TimeUnit.MINUTES)
      .build[String, ImmutableSetMultimap[Double, Int]]
}

object RasterTableConfig {
  /**
   * documentation for raster table settings:
   *
   * table.security.scan.visibility.default
   * - The default visibility for the table
   *
   * table.iterator.majc.vers.opt.maxVersions
   * - Versioning iterator setting
   * - max versions, major compaction
   *
   * table.iterator.minc.vers.opt.maxVersions
   * - Versioning iterator setting
   * - max versions, minor compaction
   *
   * table.iterator.scan.vers.opt.maxVersions
   * - Versioning iterator setting
   * - max versions, scan time
   *
   * table.split.threshold
   * - The split threshold for the table, when reached
   * - Accumulo splits the table into tablets of this size.
   *
   * @param visibilities
   * @return
   */
  def settings(visibilities: String): Map[String, String] = Map (
    "table.security.scan.visibility.default" -> visibilities,
    "table.iterator.majc.vers.opt.maxVersions" -> "1",
    "table.iterator.minc.vers.opt.maxVersions" -> "1",
    "table.iterator.scan.vers.opt.maxVersions" -> "1",
    "table.split.threshold" -> "512M"
  )
  val permissions = "BULK_IMPORT,READ,WRITE,ALTER_TABLE"
}
