package org.locationtech.geomesa.core

import java.io.{File, FileReader, Reader}
import java.net.URI

import com.typesafe.scalalogging.slf4j.Logging
import com.vividsolutions.jts.geom.{Coordinate, GeometryFactory, Geometry}
import org.apache.commons.csv.{CSVParser, CSVFormat}
import org.geotools.factory.CommonFactoryFinder
import org.geotools.feature.DefaultFeatureCollection
import org.geotools.feature.simple.SimpleFeatureBuilder
import org.locationtech.geomesa.core.util.SftBuilder
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.opengis.feature.simple.SimpleFeatureType

import scala.collection.JavaConversions._
import scala.collection.generic.CanBuildFrom
import scala.concurrent.Future
import scala.io.Source
import scala.util.{Failure, Success, Try}

package object csv extends Logging {
  def tryTraverse[A, B, M[_] <: TraversableOnce[_]](in: M[A])(fn: A => Try[B])
                                                   (implicit cbf: CanBuildFrom[M[A], B, M[B]]): Try[M[B]] =
    in.foldLeft(Try(cbf(in))) { (tr, a) =>
      for (r <- tr; b <- fn(a.asInstanceOf[A])) yield r += b
    }.map(_.result())

  val parser =
    CSVFormat.newFormat(',')
      .withQuote('"')
      .withEscape('\\')
      .withIgnoreEmptyLines(true)
      .withCommentMarker('#')
      .withIgnoreSurroundingSpaces(true)
      .withSkipHeaderRecord(true)
      .withRecordSeparator('\n')

  abstract class Schema(name: String, fields: Seq[String], types: Seq[Char], tField: String) {
    { // Schema must satisfy these to be well-formed
      require(types.size == fields.size, s"Number of types (${types.size}) must equal number of fields (${types.size})")
      val tfIdx = fields indexOf tField
      require(tfIdx != -1, s"Requested temporal field $tField not present")
      require(types(tfIdx) == 't', s"Requested temporal field $tField is not temporal")
    }

    lazy val ft: SimpleFeatureType = {
      val sftb = new SftBuilder
      for ((field, typeChar) <- fields zip types) {
        typeChar match {
          case 'i' => sftb.intType(field)
          case 'd' => sftb.doubleType(field)
          case 't' => sftb.date(field)
          case 'g' => sftb.geometry(field)
          case 's' => sftb.stringType(field)
        }
      }
      sftb.withDefaultDtg(tField)

      sftb.build(name)
    }

    def parseLine(csvLine: String): Try[Seq[String]] = {
      val entries = csvLine.split(",")
      Try {
        require(entries.size == fields.size, s"Found ${entries.size} entries in line, expected ${fields.size}\nError parsing line: $csvLine")
        entries
      }
    }

    def parseEntries(stringData: Seq[String]): Try[Map[String, Any]] = {
      def verifySize(entries: Seq[String]): Try[Unit] =
        if (entries.size == types.size) Failure(new Exception(s"Expected ${types.size} entries but received ${entries.size}"))
        else Success(())

      def getParser(char: Char): Try[Parsable[_]] =
        Try { Parsable.parserMap.getOrElse(char, throw new Exception(s"Cannot find parser for type character $char")) }

      def tryParse(entries: Seq[String]): Try[Seq[Any]] =
        tryTraverse(entries.toSeq zip types) { case (datum, typeChar) =>
          for {
            parser <- getParser(typeChar)
            parsed <- parser.parse(datum)
          } yield parsed
        }

      for {
        _             <- verifySize(stringData)
        parsedEntries <- tryParse(stringData)
      } yield fields.zip(parsedEntries).toMap
    }

    def extractGeometry(fields: Map[String, Any]): Try[(Geometry, Map[String, Any])]
  }

  class GeomSchema(name: String, fields: Seq[String], types: Seq[Char], tField: String, gField: String)
    extends Schema(name: String, fields: Seq[String], types: Seq[Char], tField: String) {

    def extractGeometry(entries: Map[String, Any]): Try[(Geometry, Map[String, Any])] =
      for {
        spatialEntry <- Try { entries.getOrElse(gField, throw new Exception(s"Cannot find spatial field $gField")) }
        spatialData  <- Try { spatialEntry.asInstanceOf[Geometry] }
      } yield (spatialData, entries - gField)
  }

  class LatLonSchema(name: String, fields: Seq[String], types: Seq[Char], tField: String, latField: String, lonField: String)
    extends Schema(name: String, fields: Seq[String], types: Seq[Char], tField: String) {
    val gf = new GeometryFactory()

    def extractGeometry(entries: Map[String, Any]): Try[(Geometry, Map[String, Any])] =
      for {
        latEntry <- Try { entries.getOrElse(latField, throw new Exception(s"Cannot find latitude field $latField")) }
        lat      <- Try { latEntry.asInstanceOf[Double] }
        lonEntry <- Try { entries.getOrElse(lonField, throw new Exception(s"Cannot find longitude field $lonField")) }
        lon      <- Try { lonEntry.asInstanceOf[Double] }
      } yield {
        val spatialData = gf.createPoint(new Coordinate(lon, lat))
        (spatialData, entries - (latField, lonField))
      }
  }

  def buildFeatureCollection(lines: Stream[String], schema: Schema): DefaultFeatureCollection = {
    val ft = schema.ft
    val featureFactory = CommonFactoryFinder.getFeatureFactory(null)
    val builder = new SimpleFeatureBuilder(ft, featureFactory)
    val fc = new DefaultFeatureCollection()
    for ((line, idx) <- lines.zipWithIndex) {
      val feature = for {
        parsedLine <- schema.parseLine(line)
        parsedEntries <- schema.parseEntries(parsedLine)
        (spatial, otherEntries) <- schema.extractGeometry(parsedEntries)
      } yield { // doesn't handle geometry yet!
        builder.reset()
        for ((name, value) <- otherEntries) {
          builder.set(name, value)
        }
        builder.buildFeature(idx.toString)
      }
      feature match {
        case Success(f)  => fc.add(f)
        case Failure(ex) => logger.warn(s"Failed to parse CSV line as feature:\n$line")
      }
    }
    fc
  }

//  not done yet
//  def buildShapefile(fc: FeatureCollection): File = {
//    val newFile = getNewShapeFile(file)
//    val dataStoreFactory = new ShapefileDataStoreFactory()
//  }

  def readCSV(csvPath: URI, name: String, types: Seq[Char], temporalField: String, spatialField: String) {
    val sb = (name: String, header: Seq[String], types: Seq[Char], tField: String) =>
      new GeomSchema(name, header, types, tField, spatialField)
    readCSV(csvPath, name, types, temporalField, sb)
  }

  def readCSV(csvPath: URI, name: String, types: Seq[Char], temporalField: String, latField: String, lonField: String) {
    val sb = (name: String, header: Seq[String], types: Seq[Char], tField: String) =>
      new LatLonSchema(name, header, types, tField, latField, lonField)
    readCSV(csvPath, name, types, temporalField, sb)
  }

  type SchemaBuilder = (String, Seq[String], Seq[Char], String) => Schema

  def readCSV(csvPath: URI, name: String, types: Seq[Char], temporalField: String, sb: SchemaBuilder) {
    val csvLines = Source.fromFile(csvPath).getLines()
    val header = csvLines.next().split(",")
    val schema = sb(name, header, types, temporalField)
    val fc = buildFeatureCollection(csvLines.toStream, schema)
    // need to build shapefile now
  }

  // this can probably be cleaned up and simplified now that parsers don't need to do double duty...
  def typeData(rawData: TraversableOnce[String]): Try[Seq[Char]] = {
    def tryAllParsers(datum: String): Try[(Any, Char)] =
      Parsable.parsers.view.map(_.parseAndType(datum)).collectFirst { case Success(x) => x } match {
        case Some(x) => Success(x)
        case None    => Failure(new IllegalArgumentException(s"Could not parse $datum as any known type"))
      }

    tryTraverse(rawData)(tryAllParsers(_).map { case (_, c) => c }).map(_.toSeq)
  }

  case class TypeSchema(name: String, schema: String)

  import scala.concurrent.ExecutionContext.Implicits.global

  def guessTypes(csvFile: File): Future[TypeSchema] = {
    val name = csvFile.getName.replace(".csv","")
    val reader = new FileReader(csvFile)
    val guess = for (g <- guessTypes(name, reader)) yield { reader.close(); g }
    guess
  }

  def guessTypes(name: String, csvReader: Reader, format: CSVFormat = CSVFormat.DEFAULT): Future[TypeSchema] =
    Future {
             val records = new CSVParser(csvReader, format).iterator
             (for {
               header    <- Try { records.next }
               record    <- Try { records.next }
               typeChars <- typeData(record.iterator)
             } yield {
               val sftb = new SftBuilder
               var defaultDateSet = false
               var defaultGeomSet = false
               for ((field, c) <- header.iterator.zip(typeChars.iterator)) { c match {
                 case 'i' =>
                   sftb.intType(field)
                 case 'd' =>
                   sftb.doubleType(field)
                 case 't' =>
                   if (defaultDateSet) sftb.date(field)
                   else {
                     sftb.date(field, default = true)
                     defaultDateSet = true
                   }
                 case 'g' =>
                   if (defaultGeomSet) sftb.geometry(field)
                   else {
                     sftb.geometry(field, default = true)
                     defaultGeomSet = true
                   }
                 case 's' =>
                   sftb.stringType(field)
               }}

               val sft = sftb.build(name)
               TypeSchema(name, SimpleFeatureTypes.encodeType(sft))
             }).get
           }
}
