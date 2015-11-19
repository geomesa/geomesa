package org.locationtech.geomesa.blob.handlers.exif

import java.io.File
import java.lang.Boolean
import java.util
import java.util.Date

import com.drew.imaging._
import com.drew.metadata.exif.GpsDirectory
import com.vividsolutions.jts.geom.Geometry
import org.locationtech.geomesa.blob.core.handlers.AbstractFileHandler
import org.locationtech.geomesa.utils.text.WKTUtils

import scala.collection.JavaConversions._

class MetadataFileHandler extends AbstractFileHandler {
  override def getGeometryFromFile(file: File): Option[Geometry] = {
    val gps = ImageMetadataReader.readMetadata(file).getDirectoriesOfType(classOf[GpsDirectory])
    val gl = gps.head.getGeoLocation

    Option(WKTUtils.read(s"POINT(${gl.getLongitude} ${gl.getLatitude})"))
  }

  override def canProcess(file: File, params: util.Map[String, String]): Boolean = {
    val gps = ImageMetadataReader.readMetadata(file).getDirectoriesOfType(classOf[GpsDirectory])
    if (gps == null) {
      false
    } else {
      val geo = gps.head.getGeoLocation
      if (geo == null) {
        false
      }
      else {
        true
      }
    }
  }

  // TODO: Implement date extraction
  override def getDateFromFile(file: File): Option[Date] = super.getDateFromFile(file)
}
