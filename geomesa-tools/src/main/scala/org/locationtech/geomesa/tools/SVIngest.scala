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
package org.locationtech.geomesa.tools

import java.net.URLDecoder
import java.nio.charset.Charset

import com.google.common.hash.Hashing
import com.twitter.scalding.{Args, Job, TextLine}
import com.typesafe.scalalogging.slf4j.Logging
import com.vividsolutions.jts.geom.Coordinate
import org.apache.commons.csv.{CSVFormat, CSVParser}
import org.geotools.data.{DataStoreFinder, FeatureWriter, Transaction}
import org.geotools.factory.Hints
import org.geotools.filter.identity.FeatureIdImpl
import org.geotools.geometry.jts.JTSFactoryFinder
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.locationtech.geomesa.core.data.AccumuloDataStore
import org.locationtech.geomesa.core.index.Constants
import org.locationtech.geomesa.feature.{AvroSimpleFeature, AvroSimpleFeatureFactory}
import org.locationtech.geomesa.tools.Utils.IngestParams
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

import scala.util.{Failure, Success, Try}

class SVIngest(args: Args) extends Job(args) with Logging {

  import scala.collection.JavaConversions._

  //val logger = LoggerFactory.getLogger(classOf[SVIngest])

  var lineNumber            = 0
  var failures              = 0
  var successes             = 0

  lazy val idFields         = args.optional(IngestParams.ID_FIELDS).orNull
  lazy val path             = args(IngestParams.FILE_PATH)
  lazy val sftSpec          = URLDecoder.decode(args(IngestParams.SFT_SPEC), "UTF-8")
  lazy val dtgField         = args.optional(IngestParams.DT_FIELD)
  lazy val dtgFmt           = args.optional(IngestParams.DT_FORMAT).getOrElse("MILLISEPOCH")
  lazy val lonField         = args.optional(IngestParams.LON_ATTRIBUTE).orNull
  lazy val latField         = args.optional(IngestParams.LAT_ATTRIBUTE).orNull
  lazy val doHash           = args(IngestParams.DO_HASH).toBoolean
  lazy val format           = args.optional(IngestParams.FORMAT).orNull
  lazy val dtgTargetField   = sft.getUserData.get(Constants.SF_PROPERTY_START_TIME).asInstanceOf[String]
  lazy val dtFormat         = DateTimeFormat.forPattern(dtgFmt)

  //Data Store parameters
  lazy val catalog          = args(IngestParams.CATALOG_TABLE)
  lazy val instanceId       = args(IngestParams.ACCUMULO_INSTANCE)
  lazy val featureName      = args(IngestParams.FEATURE_NAME)
  lazy val zookeepers       = args(IngestParams.ZOOKEEPERS)
  lazy val user             = args(IngestParams.ACCUMULO_USER)
  lazy val password         = args(IngestParams.ACCUMULO_PASSWORD)
  lazy val auths            = args.optional(IngestParams.AUTHORIZATIONS).orNull
  lazy val visibilities     = args.optional(IngestParams.VISIBILITIES).orNull
  lazy val indexSchemaFmt   = args.optional(IngestParams.INDEX_SCHEMA_FMT).orNull
  lazy val shards           = args.optional(IngestParams.SHARDS).orNull
  lazy val useMock          = args.optional(IngestParams.ACCUMULO_MOCK).orNull
  lazy val runIngest        = args.optional(IngestParams.RUN_INGEST)

  // need to work in shards, vis, isf
  lazy val dsConfig =
    Map(
      "zookeepers"        -> zookeepers,
      "instanceId"        -> instanceId,
      "tableName"         -> catalog,
      "featureName"       -> featureName,
      "user"              -> user,
      "password"          -> password,
      "auths"             -> auths,
      "visibilities"      -> visibilities,
      "indexSchemaFormat" -> indexSchemaFmt,
      "maxShard"          -> maxShard,
      "useMock"           -> useMock
    )

  val maxShard: Option[Int] = shards match {
    case s: String => Some(s.toInt)
    case _         => None
  }

  lazy val delim = format match {
    case s: String if s.toUpperCase == "TSV" => CSVFormat.TDF
    case s: String if s.toUpperCase == "CSV" => CSVFormat.DEFAULT
    case _                       => throw new Exception("Error, no format set and/or unrecognized format provided")
  }

  lazy val sft = {
    val ret = SimpleFeatureTypes.createType(featureName, sftSpec)
    ret.getUserData.put(Constants.SF_PROPERTY_START_TIME, dtgField.getOrElse(Constants.SF_PROPERTY_START_TIME))
    ret
  }

  lazy val builder = AvroSimpleFeatureFactory.featureBuilder(sft)
  lazy val geomFactory = JTSFactoryFinder.getGeometryFactory
  lazy val attributes = sft.getAttributeDescriptors
  lazy val dtBuilder = buildDtBuilder
  lazy val idBuilder = buildIDBuilder

  // non-serializable resources.
  class Resources {
    val ds = DataStoreFinder.getDataStore(dsConfig).asInstanceOf[AccumuloDataStore]
    val fw = ds.getFeatureWriterAppend(featureName, Transaction.AUTO_COMMIT)
    def release(): Unit = { fw.close() }
  }

  // Check to see if this an actual ingest job or just a test.
  if ( runIngest.isDefined ) {
    // I am not sure if we want this warning in here or not ...
    if ( dtgField.isEmpty ) {
      // assume we have no user input on what date field to use and that
      // there is no column of data signifying it.
      logger.warn("Warning: no date-time field specified. Assuming that data contains no date column. \n" +
        s"GeoMesa is defaulting to the system time for ingested features.")
    }
    TextLine(path).using(new Resources)
      .foreach('line) { (cfw: Resources, line: String) => lineNumber += 1; ingestLine(cfw.fw, line) }
  }

  def runTestIngest(lines: Iterator[String]) = Try {
    val cfw = new Resources
    lines.foreach( line => ingestLine(cfw.fw, line) )
    cfw.release()
  }

  def ingestLine(fw: FeatureWriter[SimpleFeatureType, SimpleFeature], line: String): Unit = {
    lineToFeature(line) match {
      case Success(ft) =>
        writeFeature(fw, ft) match {
          case Success(wu) =>
            successes += 1
            if ( lineNumber % 10000 == 0 ) {
              val successPvsS = if (successes == 1) "feature" else "features"
              val failurePvsS = if (failures == 1) "feature" else "features"
              logger.info(s"${DateTime.now} Ingest proceeding, on line number: $lineNumber," +
                s" ingested: $successes $successPvsS, and failed to ingest: $failures $failurePvsS.")
            }
          case Failure(ex) =>
            failures += 1
            logger.info(s"Cannot ingest avro simple feature on line number: $lineNumber, with value $line ")
        }
      case Failure(ex) => failures +=1; logger.info(s"Could not write feature due to: ${ex.getLocalizedMessage}")
    }
  }

  def lineToFeature(line: String): Try[AvroSimpleFeature] = Try {
    val reader: CSVParser = CSVParser.parse(line, delim)
    val fields: Array[String] = try {
      reader.iterator.toArray.flatten
    } catch {
      case e: Exception => throw new Exception(s"Commons CSV could not parse " +
        s"line number: $lineNumber \n\t with value: $line")
    } finally {
      reader.close()
    }

    val id = idBuilder(fields)
    builder.reset()
    builder.addAll(fields.asInstanceOf[Array[AnyRef]])
    val feature = builder.buildFeature(id).asInstanceOf[AvroSimpleFeature]

    if (dtgField.isDefined) {
      // override the feature dtgField
      try {
        val dtgFieldIndex = getAttributeIndexInLine(dtgField.get)
        val date = dtBuilder(fields(dtgFieldIndex)).toDate
        feature.setAttribute(dtgField.get, date)
      } catch {
        case e: Exception => throw new Exception(s"Could not form Date object from field" +
          s" using dt-format: $dtgFmt, on line number: $lineNumber \n\t With value of: $line")
      }
      //now try to build the date time object and set the dtgTargetField to the date value
      val dtg = try {
        dtBuilder(feature.getAttribute(dtgField.get))
      } catch {
        case e: Exception => throw new Exception(s"Could not find date-time field: '${dtgField}'," +
          s" on line  number: $lineNumber \n\t With value of: $line")
      }

      feature.setAttribute(dtgTargetField, dtg.toDate)
    }

    // Support for point data method
    val lon = Option(feature.getAttribute(lonField)).map(_.asInstanceOf[Double])
    val lat = Option(feature.getAttribute(latField)).map(_.asInstanceOf[Double])
    (lon, lat) match {
      case (Some(x), Some(y)) => feature.setDefaultGeometry(geomFactory.createPoint(new Coordinate(x, y)))
      case _                  => Nil
    }

    feature
  }

  def writeFeature(fw: FeatureWriter[SimpleFeatureType, SimpleFeature], feature: AvroSimpleFeature) = Try {
    val toWrite = fw.next()
    sft.getAttributeDescriptors.foreach { ad =>
      toWrite.setAttribute(ad.getName, feature.getAttribute(ad.getName))
    }
    toWrite.getIdentifier.asInstanceOf[FeatureIdImpl].setID(feature.getID)
    toWrite.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
    fw.write()
  }

  def getAttributeIndexInLine(attribute: String) = attributes.indexOf(sft.getDescriptor(attribute))

  def buildIDBuilder: (Array[String]) => String = {
    (idFields, doHash) match {
       case (s: String, false) =>
         val idSplit = idFields.split(",").map { f => sft.indexOf(f) }
         attrs => idSplit.map { idx => attrs(idx) }.mkString("_")
       case (s: String, true) =>
         val hashFn = Hashing.md5()
         val idSplit = idFields.split(",").map { f => sft.indexOf(f) }
         attrs => hashFn.newHasher().putString(idSplit.map { idx => attrs(idx) }.mkString("_"),
           Charset.defaultCharset()).hash().toString
       case _         =>
         val hashFn = Hashing.md5()
         attrs => hashFn.newHasher().putString(attrs.mkString ("_"),
           Charset.defaultCharset()).hash().toString
     }
  }

  def buildDtBuilder: (AnyRef) => DateTime =
    attributes.find(_.getLocalName == dtgField.getOrElse(None)).map {
      case attr if attr.getType.getBinding.equals(classOf[java.lang.Long]) =>
        (obj: AnyRef) => new DateTime(obj.asInstanceOf[java.lang.Long])

      case attr if attr.getType.getBinding.equals(classOf[java.util.Date]) =>
        (obj: AnyRef) => obj match {
          case d: java.util.Date => new DateTime(d)
          case s: String         => dtFormat.parseDateTime(s)
        }

      case attr if attr.getType.getBinding.equals(classOf[java.lang.String]) =>
        (obj: AnyRef) => dtFormat.parseDateTime(obj.asInstanceOf[String])

    }.getOrElse(throw new RuntimeException("Cannot parse date"))
}

