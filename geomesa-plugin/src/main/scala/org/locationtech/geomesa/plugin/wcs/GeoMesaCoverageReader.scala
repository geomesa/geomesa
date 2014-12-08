package org.locationtech.geomesa.plugin.wcs

import java.awt.Rectangle
import java.awt.image._
import java.util.Date

import com.typesafe.scalalogging.slf4j.Logging
import org.apache.accumulo.core.client.security.tokens.PasswordToken
import org.apache.accumulo.core.client.{IteratorSetting, Scanner, ZooKeeperInstance}
import org.apache.accumulo.core.iterators.user.VersioningIterator
import org.apache.accumulo.core.security.Authorizations
import org.apache.hadoop.io.Text
import org.geotools.coverage.CoverageFactoryFinder
import org.geotools.coverage.grid.io.{AbstractGridCoverage2DReader, AbstractGridFormat}
import org.geotools.coverage.grid.{GridCoverage2D, GridEnvelope2D, GridGeometry2D}
import org.geotools.factory.Hints
import org.geotools.geometry.GeneralEnvelope
import org.geotools.parameter.Parameter
import org.geotools.util.{DateRange, Utilities}
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import org.locationtech.geomesa.core.iterators.AggregatingKeyIterator
import org.locationtech.geomesa.plugin.ImageUtils._
import org.locationtech.geomesa.utils.geohash.{BoundingBox, Bounds}
import org.opengis.coverage.grid.GridCoverage
import org.opengis.geometry.Envelope
import org.opengis.parameter.GeneralParameterValue

import scala.collection.JavaConversions._
import scala.util.Random

object GeoMesaCoverageReader {
  val GeoServerDateFormat = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
  val DefaultDateString = GeoServerDateFormat.print(new DateTime(DateTimeZone.forID("UTC")))
  val FORMAT = """accumulo://(.*):(.*)@(.*)/(.*)#columns=(.*)#geohash=(.*)#resolution=([0-9]*)#timeStamp=(.*)#rasterName=(.*)#zookeepers=([^#]*)(?:#auths=)?(.*)$""".r
}

import org.locationtech.geomesa.plugin.wcs.GeoMesaCoverageReader._

class GeoMesaCoverageReader(val url: String, hints: Hints) extends AbstractGridCoverage2DReader() with Logging {

  //TODO: WCS: Implement function/class for parsing our "new" url
  // right now we want to extract the table name and magnification like this "dataSource_mag"
  // later, if the magnification is not provided in the URL, we should estimate it later in the read() method

  logger.debug(s"""creating coverage reader for url "${url.replaceAll(":.*@", ":********@").replaceAll("#auths=.*","#auths=********")}"""")

  val FORMAT(user, password, instanceId, table, columnsStr, geohash, resolutionStr, timeStamp, rasterName, zookeepers, authtokens) = url

  logger.debug(s"extracted user $user, password ********, instance id $instanceId, table $table, columns $columnsStr, " +
    s"resolution $resolutionStr, zookeepers $zookeepers, auths ********")

  coverageName = table + ":" + columnsStr
  val columns = columnsStr.split(",").map(_.split(":").take(2) match {
    case Array(columnFamily, columnQualifier, _) => (columnFamily, columnQualifier)
    case Array(columnFamily) => (columnFamily, "")
    case _ =>
  })

  this.crs = AbstractGridFormat.getDefaultCRS
  this.originalEnvelope = new GeneralEnvelope(Array(-180.0, -90.0), Array(180.0, 90.0))
  this.originalEnvelope.setCoordinateReferenceSystem(this.crs)
  this.originalGridRange = new GridEnvelope2D(new Rectangle(0, 0, 1024, 512))
  this.coverageFactory = CoverageFactoryFinder.getGridCoverageFactory(this.hints)

  // TODO: WCS: most of all of this should be pushed to the RasterStore or CoverageStore bits
  // Once we have the table name we *should* have enough to create a RasterStore
  // so we do something like
  // val coverageStore =   AccumuloCoverageStore(configs)
  // EXCEPT PERHAPS WE NEED A COVERAGESOURCE INSTEAD (READ ONLY)?

  val zkInstance = new ZooKeeperInstance(instanceId, zookeepers)
  val connector = zkInstance.getConnector(user, new PasswordToken(password.getBytes))

  // When parsing an old-form Accumulo layer URI the authtokens field matches the empty string, requesting no authorizations
  val auths = new Authorizations(authtokens.split(","): _*)

  val aggPrefix = AggregatingKeyIterator.aggOpt
  val timeStampString = timeStamp.toLong

  /**
   * Default implementation does not allow a non-default coverage name
   * @param coverageName
   * @return
   */
  override protected def checkName(coverageName: String) = {
    Utilities.ensureNonNull("coverageName", coverageName)
    true
  }

  override def getCoordinateReferenceSystem = this.crs

  override def getCoordinateReferenceSystem(coverageName: String) = this.getCoordinateReferenceSystem

  override def getFormat = new GeoMesaCoverageFormat

  def getGeohashPrecision = resolutionStr.toInt

  def read(parameters: Array[GeneralParameterValue]): GridCoverage2D = {
    //TODO: WCS: the GeneralParameterValue parsing should be done within a specialized class
    // that class should have the vals below as member vals
    // that class should also create a RasterQuery as another member val
    val paramsMap = parameters.map(gpv => (gpv.getDescriptor.getName.getCode, gpv)).toMap
    val gridGeometry = paramsMap(AbstractGridFormat.READ_GRIDGEOMETRY2D.getName.toString).asInstanceOf[Parameter[GridGeometry2D]].getValue
    val env = gridGeometry.getEnvelope
    val min = Array(Math.max(env.getMinimum(0), -180) + .00000001, Math.max(env.getMinimum(1), -90) + .00000001)
    val max = Array(Math.min(env.getMaximum(0), 180) - .00000001, Math.min(env.getMaximum(1), 90) - .00000001)
    val bbox = BoundingBox(Bounds(min(0), max(0)), Bounds(min(1), max(1)))
    //TODO: WCS: I think getChunk should be a function method of our CoverageStore
    // one of its arguments should be the GeneralParameterValue parsing object from above
    // so we want something like this val image=coverageStore.getChunks(requestParameters)
    val image = getChunk(geohash, getGeohashPrecision, None)

    /**
     * Included for when mosaicing and final key structure are utilized
     *
     * val chunks = getChunks(geohash, getGeohashPrecision, None, bbox)
     * val image = mosaicGridCoverages(chunks, env = env)
     * this.coverageFactory.create(coverageName, image, env)
     */

    this.coverageFactory.create(coverageName, image, env)
  }

  def getChunk(geohash: String, iRes: Int, timeParam: Option[Either[Date, DateRange]]): RenderedImage = {
    withScanner(scanner => {
      val row = new Text(s"~$iRes~$geohash")
      scanner.setRange(new org.apache.accumulo.core.data.Range(row))
      val name = "version-" + Random.alphanumeric.take(5).mkString
      val cfg = new IteratorSetting(2, name, classOf[VersioningIterator])
      VersioningIterator.setMaxVersions(cfg, 1)
      scanner.addScanIterator(cfg)
    })(_.map(entry => {
        rasterImageDeserialize(entry.getValue.get)
    })).head
  }

  /**
   * TODO: WCS: We'd like getChunks to call out to getRasters
   *
   * so
   *
   * def getChunks(requestParameters) = {
   *    val rasterIter = coverageStore.getRasters(requestParameters.rasterQuery)
   *    chunkIter = rasterIter.map{raster => rasterToChunk(raster)}
   *
   * }
   *
   * The rest of this iterator code should be moved into RasterStore
   *
   */



  /**
   * Included for when mosaicing and final key structure are utilized
   *
   * def getChunks(geohash: String, iRes: Int, timeParam: Option[Either[Date, DateRange]], bbox: BoundingBox): Iterator[GridCoverage] = {
   *   withScanner(scanner => {
   *     val row = new Text(s"~$iRes~$geohash")
   *     scanner.setRange(new org.apache.accumulo.core.data.Range(row))
   *     val name = "version-" + Random.alphanumeric.take(5).mkString
   *     val cfg = new IteratorSetting(2, name, classOf[VersioningIterator])
   *     VersioningIterator.setMaxVersions(cfg, 1)
   *     scanner.addScanIterator(cfg)
   *   })(_.map(entry => {
   *     this.coverageFactory.create(coverageName,
   *       rasterImageDeserialize(entry.getValue.get),
   *       new ReferencedEnvelope(RasterIndexEntry.decodeIndexCQMetadata(entry.getKey).geom.getEnvelopeInternal, CRS.decode("EPSG:4326")))
   *   })).toIterator
   * }
   */

  protected def withScanner[A](configure: Scanner => Unit)(f: Scanner => A): A = {
    val scanner = connector.createScanner(table, auths)
    try {
      configure(scanner)
      f(scanner)
    } catch {
      case e: Exception => throw new Exception(s"Error accessing table ", e)
    }
  }
}