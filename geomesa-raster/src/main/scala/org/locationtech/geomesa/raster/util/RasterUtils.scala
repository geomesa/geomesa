package org.locationtech.geomesa.raster.util

import java.awt.image.{BufferedImage, RenderedImage, WritableRaster, Raster => JRaster}
import java.io._
import java.nio.ByteBuffer
import java.util.{Hashtable => JHashtable}
import javax.media.jai.remote.SerializableRenderedImage

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{BytesWritable, IOUtils, SequenceFile}
import org.geotools.coverage.grid.{GridCoverage2D, GridCoverageFactory, GridGeometry2D}
import org.geotools.geometry.jts.ReferencedEnvelope
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.joda.time.DateTime
import org.locationtech.geomesa.core.index.DecodedIndex
import org.locationtech.geomesa.raster.data.{Raster, RasterQuery, RasterStore}
import org.locationtech.geomesa.utils.geohash.{BoundingBox, GeoHash}
import org.opengis.geometry.Envelope

import scala.collection.mutable.ListBuffer
import scala.reflect.runtime.universe._

object RasterUtils {

  object IngestRasterParams {
    val ACCUMULO_INSTANCE   = "geomesa-tools.ingestraster.instance"
    val ZOOKEEPERS          = "geomesa-tools.ingestraster.zookeepers"
    val ACCUMULO_MOCK       = "geomesa-tools.ingestraster.useMock"
    val ACCUMULO_USER       = "geomesa-tools.ingestraster.user"
    val ACCUMULO_PASSWORD   = "geomesa-tools.ingestraster.password"
    val AUTHORIZATIONS      = "geomesa-tools.ingestraster.authorizations"
    val VISIBILITIES        = "geomesa-tools.ingestraster.visibilities"
    val FILE_PATH           = "geomesa-tools.ingestraster.path"
    val HDFS_FILES          = "geomesa-tools.ingestraster.hdfs.files"
    val FORMAT              = "geomesa-tools.ingestraster.format"
    val TIME                = "geomesa-tools.ingestraster.time"
    val GEOSERVER_REG       = "geomesa-tools.ingestraster.geoserver.reg"
    val TABLE               = "geomesa-tools.ingestraster.table"
    val WRITE_MEMORY        = "geomesa-tools.ingestraster.write.memory"
    val WRITE_THREADS       = "geomesa-tools.ingestraster.write.threads"
    val QUERY_THREADS       = "geomesa-tools.ingestraster.query.threads"
    val SHARDS              = "geomesa-tools.ingestraster.shards"
    val PARLEVEL            = "geomesa-tools.ingestraster.parallel.level"
    val CHUNKSIZE           = "geomesa-tools.ingestraster.chunk.size"
    val IS_TEST_INGEST      = "geomesa.tools.ingestraster.is-test-ingest"
  }

  def imageSerialize(image: RenderedImage): Array[Byte] = {
    val buffer: ByteArrayOutputStream = new ByteArrayOutputStream
    val out: ObjectOutputStream = new ObjectOutputStream(buffer)
    val serializableImage = new SerializableRenderedImage(image, true)
    try {
      out.writeObject(serializableImage)
    } finally {
      out.close
    }
    buffer.toByteArray
  }

  def imageDeserialize(imageBytes: Array[Byte]): RenderedImage = {
    val in: ObjectInputStream = new ObjectInputStream(new ByteArrayInputStream(imageBytes))
    var read: RenderedImage = null
    try {
      read = in.readObject.asInstanceOf[RenderedImage]
    } finally {
      in.close
    }
    read
  }

  val defaultGridCoverageFactory = new GridCoverageFactory

  def renderedImageToGridCoverage2d(name: String, image: RenderedImage, env: Envelope): GridCoverage2D =
    defaultGridCoverageFactory.create(name, image, env)

  def getEmptyMosaic(width: Int, height: Int, chunk: RenderedImage): BufferedImage = {
    val properties = new JHashtable[String, Object]
    if (chunk.getPropertyNames != null) {
      chunk.getPropertyNames.foreach(name => properties.put(name, chunk.getProperty(name)))
    }
    val colorModel = chunk.getColorModel
    val alphaPremultiplied = colorModel.isAlphaPremultiplied
    val sampleModel = chunk.getSampleModel.createCompatibleSampleModel(width, height)
    val emptyRaster = JRaster.createWritableRaster(sampleModel, null)
    new BufferedImage(colorModel, emptyRaster, alphaPremultiplied, properties)
  }

  def setMosaicData(mosaic: BufferedImage, raster: Raster, env: Envelope, resX: Double, resY: Double) = {
    val rasterEnv = raster.referencedEnvelope
    val chunk = raster.chunk
    val dx = ((rasterEnv.getMinimum(0) - env.getMinimum(0)) / resX).toInt
    val dy = ((env.getMaximum(1) - rasterEnv.getMaximum(1)) / resY).toInt
    mosaic.getRaster.setRect(dx, dy, chunk.getData)
  }

  def getEmptyImage(width: Int, height: Int): BufferedImage = {
    new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
  }

  def mosaicRasters(rasters: Iterator[Raster], width: Int, height: Int, env: Envelope, resX: Double, resY: Double): (BufferedImage, Int) = {
    if (rasters.isEmpty) {
      (getEmptyImage(width, height), 0)
    } else {
      val rescaleX = resX / (env.getSpan(0) / width)
      val rescaleY = resY / (env.getSpan(1) / height)
      val scaledWidth = width / rescaleX
      val scaledHeight = height / rescaleY
      val imageWidth = Math.max(Math.round(scaledWidth), 1).toInt
      val imageHeight = Math.max(Math.round(scaledHeight), 1).toInt
      val firstRaster = rasters.next()
      var count = 1
      val mosaic = getEmptyMosaic(imageWidth, imageHeight, firstRaster.chunk)
      setMosaicData(mosaic, firstRaster, env, resX, resY)
      while (rasters.hasNext) {
        val raster = rasters.next()
        setMosaicData(mosaic, raster, env, resX, resY)
        count += 1
      }
      (mosaic, count)
    }
  }

  //TODO: WCS: Split off functions useful for just tests into a separate object, which includes classes from here on down
  val white = Array[Int] (255, 255, 255)
  val black = Array[Int] (0, 0, 0)

  def getNewImage[T: TypeTag](w: Int, h: Int, fill: Array[T], imageType: Int = BufferedImage.TYPE_BYTE_GRAY): BufferedImage = {
    val image = new BufferedImage(w, h, imageType)
    val wr = image.getRaster
    val setPixel: (Int, Int) => Unit = typeOf[T] match {
      case t if t =:= typeOf[Int]    =>
        (i, j) => wr.setPixel(j, i, fill.asInstanceOf[Array[Int]])
      case t if t =:= typeOf[Float]  =>
        (i, j) => wr.setPixel(j, i, fill.asInstanceOf[Array[Float]])
      case t if t =:= typeOf[Double] =>
        (i, j) => wr.setPixel(j, i, fill.asInstanceOf[Array[Double]])
      case _                         =>
        throw new IllegalArgumentException(s"Error, cannot handle Arrays of type: ${typeOf[T]}")
    }

    for (i <- 1 until h; j <- 1 until w) { setPixel(i, j) }
    image
  }

  def imageToCoverage(img: WritableRaster, env: ReferencedEnvelope, cf: GridCoverageFactory) = {
    cf.create("testRaster", img, env)
  }

  def createRasterStore(tableName: String) = {
    val rs = RasterStore("user", "pass", "testInstance", "zk", tableName, "SUSA", "SUSA", true)
    rs
  }

  def generateQuery(minX: Double, maxX: Double, minY: Double, maxY: Double, res: Double = 10.0) = {
    val bb = BoundingBox(new ReferencedEnvelope(minX, maxX, minY, maxY, DefaultGeographicCRS.WGS84))
    new RasterQuery(bb, res, None, None)
  }

  def generateTestRaster(minX: Double, maxX: Double, minY: Double, maxY: Double, w: Int = 256, h: Int = 256, res: Double = 10.0): Raster = {
    val ingestTime = new DateTime()
    val env = new ReferencedEnvelope(minX, maxX, minY, maxY, DefaultGeographicCRS.WGS84)
    val bbox = BoundingBox(env)
    val metadata = DecodedIndex(Raster.getRasterId("testRaster"), bbox.geom, Option(ingestTime.getMillis))
    val image = getNewImage(w, h, Array[Int](255, 255, 255))
    val coverage = imageToCoverage(image.getRaster, env, defaultGridCoverageFactory)
    Raster(coverage.getRenderedImage, metadata, res)
  }

  def generateTestRasterFromBoundingBox(bbox: BoundingBox, w: Int = 256, h: Int = 256, res: Double = 10.0): Raster = {
    generateTestRaster(bbox.minLon, bbox.maxLon, bbox.minLat, bbox.maxLat, w, h, res)
  }

  def generateTestRasterFromGeoHash(gh: GeoHash, w: Int = 256, h: Int = 256, res: Double = 10.0): Raster = {
    generateTestRasterFromBoundingBox(gh.bbox, w, h, res)
  }

  case class sharedRasterParams(gg: GridGeometry2D, envelope: Envelope) {
    val width = gg.getGridRange2D.getWidth
    val height = gg.getGridRange2D.getHeight
    val resX = (envelope.getMaximum(0) - envelope.getMinimum(0)) / width
    val resY = (envelope.getMaximum(1) - envelope.getMinimum(1)) / height
    val accumuloResolution = math.min(resX, resY)
  }

  def saveBytesToHdfsFile(name: String, bytes: Array[Byte], outFile: String, conf: Configuration) {
    val outPath = new Path(outFile)
    val key = new BytesWritable
    val value = new BytesWritable
    var writer: SequenceFile.Writer = null

    try {
      val optPath = SequenceFile.Writer.file(outPath)
      val optKey =  SequenceFile.Writer.keyClass(key.getClass)
      val optVal =  SequenceFile.Writer.valueClass(value.getClass)
      writer = SequenceFile.createWriter(conf, optPath, optKey, optVal)
      writer.append(new BytesWritable(name.getBytes), new BytesWritable(bytes))
    } catch {
      case e: Exception =>
        System.out.println("Cannot write to Hdfs sequence file: " + e.getMessage())
    } finally {
      IOUtils.closeStream(writer)
    }
  }

  //Encode a list of byte arrays into one byte array using protocol: length | data
  //Result is like: length[4 bytes], byte array, ... [length[4 bytes], byte array]
  def encodeByteArrays(bas: List[Array[Byte]]): Array[Byte] =  {
    val totalLength = bas.map(_.length).sum
    val buffer = ByteBuffer.allocate(totalLength + 4 * bas.length)
    bas.foreach{ ba => buffer.putInt(ba.length).put(ba) }
    buffer.array
  }

  //Decode a byte array into a list of byte array using protocol: length | data
  def decodeByteArrays(ba: Array[Byte]): List[Array[Byte]] = {
    var pos = 0
    val listBuf: ListBuffer[Array[Byte]] = new ListBuffer[Array[Byte]]()
    while(pos + 4 <= ba.length) {
      val length = ByteBuffer.wrap(ba, pos, 4).getInt
      listBuf += ba.slice(pos + 4, pos + 4 + length)
      pos = pos + 4 + length
    }
    listBuf.toList
  }

  val doubleSize = 8
  def doubleToBytes(d: Double): Array[Byte] = {
    val bytes = new Array[Byte](doubleSize)
    ByteBuffer.wrap(bytes).putDouble(d)
    bytes
  }
  def bytesToDouble(bs: Array[Byte]): Double = ByteBuffer.wrap(bs).getDouble
}

