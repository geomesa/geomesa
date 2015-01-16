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

import java.util.Map.Entry

import org.apache.accumulo.core.client.{BatchWriterConfig, Connector, TableExistsException}
import org.apache.accumulo.core.data.{Range, Key, Mutation, Value}
import org.apache.accumulo.core.security.{Authorizations, TablePermission}
import org.joda.time.DateTime
import org.locationtech.geomesa.core.index._
import org.locationtech.geomesa.core.iterators.BBOXCombiner
import org.locationtech.geomesa.core.security.AuthorizationsProvider
import org.locationtech.geomesa.core.stats.StatWriter
import org.locationtech.geomesa.raster._
import org.locationtech.geomesa.raster.index.RasterIndexSchema
import org.locationtech.geomesa.utils.geohash.BoundingBox

import scala.collection.JavaConversions._

trait RasterOperations extends StrategyHelpers {
  def getTable(): String
  def ensureTableExists(): Unit
  def ensureBoundsTableExists(): Unit
  def getAuths(): Authorizations
  def getVisibility(): String
  def getConnector(): Connector
  def getRasters(rasterQuery: RasterQuery): Iterator[Raster]
  def putRaster(raster: Raster): Unit
  def getBounds(): BoundingBox
  def getAvailableResolutions(): Set[Double]
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
                                     queryThreadsConfig: Option[Int] = None) extends RasterOperations {
  //By default having at least as many shards as tservers provides optimal parallelism in queries
  val shards = shardsConfig.getOrElse(connector.instanceOperations().getTabletServers.size())
  val writeMemory = writeMemoryConfig.getOrElse("10000").toLong
  val writeThreads = writeThreadsConfig.getOrElse(10)
  val bwConfig: BatchWriterConfig =
    new BatchWriterConfig().setMaxMemory(writeMemory).setMaxWriteThreads(writeThreads)

  // TODO: WCS: GEOMESA-585 Add ability to use arbitrary schemas
  val schema = RasterIndexSchema("")

  lazy val queryPlanner: AccumuloRasterQueryPlanner = new AccumuloRasterQueryPlanner(schema)

  lazy val boundsPlanner: AccumuloRasterBoundsPlanner = new AccumuloRasterBoundsPlanner(rasterTable)

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

  def getRasters(rasterQuery: RasterQuery): Iterator[Raster] = {
    //TODO: WCS: Abstract number of threads
    val numQThreads = 20
    val batchScanner = connector.createBatchScanner(rasterTable, authorizationsProvider.getAuthorizations, numQThreads)
    val plan = queryPlanner.getQueryPlan(rasterQuery)
    configureBatchScanner(batchScanner, plan)
    adaptIterator(batchScanner.iterator)
  }

  def getBounds(): BoundingBox = {
    ensureTableExists(GEOMESA_RASTER_BOUNDS_TABLE)
    val scanner = connector.createScanner(GEOMESA_RASTER_BOUNDS_TABLE, authorizationsProvider.getAuthorizations)
    scanner.setRange(new Range(getBoundsRowID))
    val resultingBounds = scanner.iterator.toList
    if (resultingBounds.length > 0) {
      //TODO: add fix for stuff crossing anti-meridian, may not be an issue...
      BBOXCombiner.valueToBbox(resultingBounds.head.getValue)
    } else {
      BoundingBox(-180, 180, -90, 90)
    }
  }

  def getAvailableResolutions(): Set[Double] = {
    ensureTableExists(GEOMESA_RASTER_BOUNDS_TABLE)
    val scanner = connector.createScanner(GEOMESA_RASTER_BOUNDS_TABLE, authorizationsProvider.getAuthorizations)
    scanner.setRange(new Range(getBoundsRowID))
    val scanResultingCQs = scanner.iterator.toList.map(_.getKey.getColumnQualifier.toString)
    val resultingResolutions = scanResultingCQs.length match {
      case 0 => Set[Double]()
      case _ => scanResultingCQs.map(lexiDecodeStringToDouble).toSet[Double]
    }
    resultingResolutions
  }

  def adaptIterator(iter: java.util.Iterator[Entry[Key, Value]]): Iterator[Raster] = {
    iter.map { entry => schema.decode((entry.getKey, entry.getValue)) }
  }

  def ensureTableExists() = {
    ensureTableExists(rasterTable)
    ensureBoundsTableExists()
  }

  def ensureBoundsTableExists() = {
    ensureTableExists(GEOMESA_RASTER_BOUNDS_TABLE)
    if (!tableOps.listIterators(GEOMESA_RASTER_BOUNDS_TABLE).containsKey("GEOMESA_BBOX_COMBINER")) {
      val bboxcombinercfg = boundsPlanner.getBoundsScannerCfg()
      tableOps.attachIterator(GEOMESA_RASTER_BOUNDS_TABLE, bboxcombinercfg)
    }
  }

  private def dateToAccTimestamp(dt: DateTime): Long =  dt.getMillis / 1000

  private def createBoundsMutation(raster: Raster): Mutation = {
    val mutation = new Mutation(getBoundsRowID)
    val value = BBOXCombiner.bboxToValue(BoundingBox(raster.metadata.geom.getEnvelopeInternal))
    mutation.put("", lexiEncodeDoubleToString(raster.resolution), value)
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
        tableOps.create(tableName)
        RasterTableConfig.settings(defaultVisibilities).foreach { case (key, value) =>
          tableOps.setProperty(tableName, key, value)
        }
        RasterTableConfig.permissions.split(",").foreach { p =>
          securityOps.grantTablePermission(user, tableName, TablePermission.valueOf(p))
        }
      } catch {
        case e: TableExistsException => // this can happen with multiple threads but shouldn't cause any issues
      }
    }
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
            queryThreadsConfig: Option[Int],
            collectStats: Boolean): AccumuloBackedRasterOperations  =
    if (collectStats)
      new AccumuloBackedRasterOperations(connector,
                                         tableName,
                                         authorizationsProvider,
                                         visibility,
                                         shardsConfig,
                                         writeMemoryConfig,
                                         writeThreadsConfig,
                                         queryThreadsConfig) with StatWriter
    else
      new AccumuloBackedRasterOperations(connector,
                                         tableName,
                                         authorizationsProvider,
                                         visibility,
                                         shardsConfig,
                                         writeMemoryConfig,
                                         writeThreadsConfig,
                                         queryThreadsConfig)
}

object RasterTableConfig {
  //TODO: WCS: document settings --  GEOMESA-565
  def settings(visibilities: String): Map[String, String] = Map (
    "table.security.scan.visibility.default" -> visibilities,
    "table.iterator.majc.vers.opt.maxVersions" -> "2147483647",
    "table.iterator.minc.vers.opt.maxVersions" -> "2147483647",
    "table.iterator.scan.vers.opt.maxVersions" -> "2147483647",
    "table.split.threshold" -> "16M"
  )
  val permissions = "BULK_IMPORT,READ,WRITE,ALTER_TABLE"
}
