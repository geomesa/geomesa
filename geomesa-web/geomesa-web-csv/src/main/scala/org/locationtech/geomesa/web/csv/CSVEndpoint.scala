/***********************************************************************
* Copyright (c) 2013-2015 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.web.csv

import java.io.{BufferedOutputStream, ByteArrayOutputStream, File, OutputStream}
import java.net.URL
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.TimeUnit

import com.google.common.cache._
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.FilenameUtils
import org.geotools.GML
import org.geotools.gml.producer.FeatureTransformer
import org.locationtech.geomesa.accumulo.{TypeSchema, csv}
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.web.core.GeoMesaScalatraServlet
import org.locationtech.geomesa.web.scalatra.{PkiAuthenticationSupport, User}
import org.scalatra._
import org.scalatra.servlet.{FileUploadSupport, MultipartConfig, SizeConstraintExceededException}

// TODO:
// Right now we cannot have GeoServer directly access a secured .gml endpoint
// since the geoserver cert -- if it even tries to use one -- will not match
// the cert of the uploading user.  ahulbert has suggested using wps instead
// if using a servlet at all, which isn't a bad idea.
//
// There should be two wps processes
// 1) geomesa:csv2xsd infers a schema from the uploaded csv data;
//    the end user can just pass the head of their csv to minimize transfers
// 2) geomesa:csvimport takes a schema as well as the csv data and converts
//    csv records to SimpleFeatures on the fly for ingest
class CSVEndpoint extends GeoMesaScalatraServlet with FileUploadSupport with LazyLogging with PkiAuthenticationSupport {

  override val root: String = "csv"

  // caps CSV file size at 10MB
  configureMultipartHandling(MultipartConfig(maxFileSize = Some(10*1024*1024)))
  error {
    case e: SizeConstraintExceededException => RequestEntityTooLarge("Uploaded file too large!")
  }

  class Record(val csvFile: File, val hasHeader: Boolean, var schema: TypeSchema)

  val records: Cache[RecordTag, Record] = {
    val removalListener = new RemovalListener[RecordTag, Record]() {
      override def onRemoval(notification: RemovalNotification[RecordTag, Record]) =
        cleanup(notification.getKey, notification.getValue)
    }
    CacheBuilder.newBuilder()
        .expireAfterAccess(1, TimeUnit.HOURS)
        .removalListener(removalListener)
        .build()
  }

  case class RecordTag(userId: Option[User], csvId: String)
  private[this] def getUser = scentry.authenticate("Pki")
  private[this] def getRecordTag = RecordTag(getUser, params("csvid"))

  post("/") {
    try {
      val fileItem = fileParams("csvfile")
      val csvFile = File.createTempFile(FilenameUtils.removeExtension(fileItem.name), ".csv")
      fileItem.write(csvFile)
      val hasHeader = params.get("hasHeader").map(_.toBoolean).getOrElse(true)
      val schema = csv.guessTypes(csvFile, hasHeader)
      val csvId = UUID.randomUUID.toString
      val tag = RecordTag(getUser, csvId)
      records.put(tag, new Record(csvFile, hasHeader, schema))
      Ok(csvId)
    } catch {
      case ex: Throwable =>
        logger.warn("Error uploading CSV", ex)
        NotAcceptable(reason = ex.getMessage)
    }
  }

  get("/types/:csvid") {
    val tag = getRecordTag
    val record = records.getIfPresent(tag)
    if (record == null) {
      NotFound()
    } else {
      val TypeSchema(name, schema, _) = record.schema
      Ok(s"$name\n$schema")
    }
  }

  post("/types/update/:csvid") {
    val tag = getRecordTag
    val record = records.getIfPresent(tag)
    if (record == null) {
      BadRequest(reason = s"Could not find record ${tag.csvId} for user ${tag.userId}")
    } else {
      val name = params.getOrElse("name", record.schema.name)
      val schema = params.getOrElse("schema", record.schema.schema)
      val latLon = for (latf <- params.get("latField"); lonf <- params.get("lonField")) yield (latf, lonf)
      record.schema = TypeSchema(name, schema, latLon)
      Ok()
    }
  }

  get("/:csvid.gml") {
    val tag = getRecordTag
    val record = records.getIfPresent(tag)
    if (record == null) {
      NotFound()
    } else {
      contentType = "application/xml"
      val file = record.csvFile
      val header = record.hasHeader
      try {
        // before running the gml code, first create the XSD, otherwise it can cause deadlocks in geotools
        getXsd(tag.csvId, record, new ByteArrayOutputStream())

        val fc = csv.csvToFeatures(file, header, record.schema)
        val out = new BufferedOutputStream(response.getOutputStream)
        val transformer = new FeatureTransformer()
        transformer.getFeatureTypeNamespaces.declareNamespace(fc.getSchema,
          "geomesa", s"feat:geomesa:${tag.csvId}")
        transformer.addSchemaLocation(s"feat:geomesa:${tag.csvId}",
          request.getRequestURL.toString.replaceAll("gml$", "xsd"))

        transformer.setIndentation(2)
        transformer.setCollectionBounding(true)
        transformer.setEncoding(Charset.forName("utf-8"))
        transformer.setGmlPrefixing(true)
        transformer.setSrsName("http://www.opengis.net/gml/srs/epsg.xml#4326")

        transformer.transform(fc, out)
        out.flush()

        Ok()
      } catch {
        case ex: Throwable =>
          logger.error("Error creating GML", ex)
          InternalServerError()
      }
    }
  }

  get("/:csvid.xsd") {
    val tag = getRecordTag
    val record = records.getIfPresent(tag)
    if (record == null) {
      NotFound()
    } else {
      contentType = "application/xml"
      try {
        val out = new BufferedOutputStream(response.getOutputStream)
        getXsd(tag.csvId, record, out)
        out.flush()
        Ok()
      } catch {
        case ex: Throwable =>
          logger.error("Error creating GML", ex)
          InternalServerError()
      }
    }
  }

  def getXsd(csvId: String, record: Record, out: OutputStream) = {
    record.synchronized {
      val sft = SimpleFeatureTypes.createType(record.schema.name, record.schema.schema)
      val gml = new GML(GML.Version.GML2)
      gml.setBaseURL(new URL("http://localhost"))
      gml.setNamespace("geomesa", s"feat:geomesa:$csvId")
      gml.encode(out, sft)
    }
  }

  post("/delete/:csvid.csv") {
    val tag = getRecordTag
    Option(records.getIfPresent(tag)).foreach(cleanup(tag, _))
    Ok()
  }

  delete("/:csvid.csv") {
    val tag = getRecordTag
    Option(records.getIfPresent(tag)).foreach(cleanup(tag, _))
    Ok()
  }

  private def cleanup(tag: RecordTag, record: Record) {
    record.csvFile.delete()
    records.invalidate(tag)
  }
}
