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

package org.locationtech.geomesa.plugin.wcs

import com.typesafe.scalalogging.slf4j.Logging
import org.geotools.coverage.CoverageFactoryFinder
import org.geotools.coverage.grid.io.AbstractGridCoverage2DReader
import org.geotools.coverage.grid.{GridCoverage2D, GridEnvelope2D}
import org.geotools.factory.Hints
import org.geotools.geometry.GeneralEnvelope
import org.geotools.referencing.CRS
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.geotools.util.Utilities
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import org.locationtech.geomesa.raster.data.{GeoMesaCoverageQueryParams, RasterStore}
import org.opengis.parameter.GeneralParameterValue

import scala.util.Try

object GeoMesaCoverageReader {
  val GeoServerDateFormat = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
  val DefaultDateString = GeoServerDateFormat.print(new DateTime(DateTimeZone.forID("UTC")))
  val FORMAT = """accumulo://(.*):(.*)@(.*)/(.*)#zookeepers=([^#]*)(?:#auths=)?([^#]*)(?:#visibilities=)?([^#]*)$""".r
}

import org.locationtech.geomesa.plugin.wcs.GeoMesaCoverageReader._

class GeoMesaCoverageReader(val url: String, hints: Hints) extends AbstractGridCoverage2DReader() with Logging {
  logger.debug(s"""creating coverage reader for url "${url.replaceAll(":.*@", ":********@").replaceAll("#auths=.*","#auths=********")}"""")
  val FORMAT(user, password, instanceId, table, zookeepers, auths, visibilities) = url
  logger.debug(s"extracted user $user, password ********, instance id $instanceId, table $table, zookeepers $zookeepers, auths ********")

  coverageName = table

  val ars = RasterStore(user, password, instanceId, zookeepers, table, auths, "")

  // TODO: Either this is needed for rasterToCoverages or remove it.
  this.crs = Try(CRS.decode("EPSG:4326")).getOrElse(DefaultGeographicCRS.WGS84)
  this.originalEnvelope = getBounds()
  this.originalEnvelope.setCoordinateReferenceSystem(this.crs)
  this.originalGridRange = getGridRange()
  this.coverageFactory = CoverageFactoryFinder.getGridCoverageFactory(this.hints)
  // TODO: Provide writeVisibilites??  Sort out read visibilites

  /**
   * Default implementation does not allow a non-default coverage name
   * @param coverageName
   * @return
   */
  override protected def checkName(coverageName: String) = {
    Utilities.ensureNonNull("coverageName", coverageName)
    true
  }

  override def getOriginalEnvelope = this.originalEnvelope

  override def getCoordinateReferenceSystem = this.crs

  override def getCoordinateReferenceSystem(coverageName: String) = this.getCoordinateReferenceSystem

  override def getFormat = new GeoMesaCoverageFormat

  override def getOriginalGridRange() = this.originalGridRange

  override def getOriginalGridRange(coverageName: String) = this.originalGridRange

  def read(parameters: Array[GeneralParameterValue]): GridCoverage2D = {
    logger.debug(s"READ: $parameters")
    val params = new GeoMesaCoverageQueryParams(parameters)
    val rq = params.toRasterQuery
    logger.info(s"In rastersToCoverage: width: ${params.width.toInt} height: ${params.height.toInt} resX: ${params.resX} resY: ${params.resY} env: ${params.envelope}")
    val mosaic = ars.getMosaicedRaster(rq, params)
    val coverage = this.coverageFactory.create(coverageName, mosaic, params.envelope)
    mosaic.flush()
    coverage
  }

  def getBounds(): GeneralEnvelope = {
    val bbox = ars.getBounds()
    new GeneralEnvelope(Array(bbox.minLon, bbox.minLat), Array(bbox.maxLon, bbox.maxLat))
  }

  def getGridRange(): GridEnvelope2D = {
    ars.getGridRange()
  }
}