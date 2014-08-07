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
package geomesa.tools

import java.net.URLDecoder
import java.nio.charset.Charset

import com.google.common.hash.Hashing
import com.typesafe.scalalogging.slf4j.Logging
import geomesa.core.data.AccumuloDataStore
import geomesa.core.index.Constants
import geomesa.feature.AvroSimpleFeatureFactory
import geomesa.utils.geotools.SimpleFeatureTypes
import org.geotools.data.{DataStoreFinder, Transaction}
import org.geotools.factory.Hints
import org.geotools.filter.identity.FeatureIdImpl
import org.geotools.geometry.jts.JTSFactoryFinder
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import scala.io.Source
import scala.util.matching.Regex
import scala.util.parsing.combinator.RegexParsers

class SVIngest(config: ScoptArguments, dsConfig: Map[String, _]) extends Logging {

  import scala.collection.JavaConversions._

  lazy val table            = config.catalog
  lazy val path             = config.file
  lazy val typeName         = config.typeName
  lazy val sftSpec          = URLDecoder.decode(config.spec, "UTF-8")
  lazy val dtgField         = config.dtField
  lazy val dtgFmt           = config.dtFormat
  lazy val dtgTargetField   = sft.getUserData.get(Constants.SF_PROPERTY_START_TIME).asInstanceOf[String]

  lazy val ds = DataStoreFinder.getDataStore(dsConfig).asInstanceOf[AccumuloDataStore]
  ds.createSchema(sft)

  lazy val sft = {
    val ret = SimpleFeatureTypes.createType(typeName, sftSpec)
    ret.getUserData.put(Constants.SF_PROPERTY_START_TIME, dtgField)
    ret
  }

  lazy val builder = AvroSimpleFeatureFactory.featureBuilder(sft)
  lazy val geomFactory = JTSFactoryFinder.getGeometryFactory
  lazy val dtFormat = DateTimeFormat.forPattern(dtgFmt)
  lazy val attributes = sft.getAttributeDescriptors
  lazy val dtBuilder = buildDtBuilder
  lazy val idBuilder = buildIDBuilder

  lazy val fw = ds.getFeatureWriterAppend(typeName, Transaction.AUTO_COMMIT)

  config.method.toLowerCase match {
    case "local" =>
      Source.fromFile(path).getLines.foreach { line => parseFeature (line) }
    case _ =>
      logger.error(s"Error, no such SV method: ${config.method.toLowerCase}"); false
  }

  def parseFeature(line: String) = {
    try {
      val fields = config.format.toUpperCase match {
        case "TSV" => TSV.parse(line).toArray[String]
        case "CSV" => CSV.parse(line).toArray[String]
        case _     => throw new Exception
      }
      val id = idBuilder(fields)
      builder.reset()
      builder.addAll(fields.asInstanceOf[Array[AnyRef]])
      val feature = builder.buildFeature(id)
      // assume last field is the WKT geom, however it is a valid property of feature...
      feature.setDefaultGeometry(feature.getAttribute(feature.getAttributeCount-1))
      val dtg = dtBuilder(feature.getAttribute(dtgField))
      feature.setAttribute(dtgTargetField, dtg.toDate)
      val toWrite = fw.next()
      sft.getAttributeDescriptors.foreach { ad =>
        toWrite.setAttribute(ad.getName, feature.getAttribute(ad.getName))
      }
      toWrite.getIdentifier.asInstanceOf[FeatureIdImpl].setID(id)
      toWrite.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
      fw.write()
    } catch {
      case e: Exception => logger.error(s"Cannot parse: $line", e)
    }
  }

  def buildIDBuilder: (Array[String]) => String = {
     val hashFn = Hashing.md5()
     attrs => hashFn.newHasher().putString(attrs.mkString("|"), Charset.defaultCharset()).hash().toString
  }

  def buildDtBuilder: (AnyRef) => DateTime =
    attributes.find(_.getLocalName == dtgField).map {
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

  // make sure we close the feature writer
  fw.close()
}

object CSV extends SV {
  override def SEP: String = ","

  override def SPACES: Regex = "[ \t]+".r
}

object TSV extends SV {
  override def SEP: String = "\t"

  override def SPACES: Regex = "".r
}

trait SV extends RegexParsers {
  override val skipWhitespace = false

  def SEP: String //= "\t"
  def SPACES: Regex //= "[ \t]+".r

  def DQUOTE  = "\""
  def DQUOTE2 = "\"\"" ^^ { case _ => "\"" }  // combine 2 dquotes into 1
  def CRLF    = "\r\n" | "\n"
  def TXT     = s"""[^\"$SEP\r\n]""".r

  def file: Parser[List[List[String]]] = repsep(record, CRLF) <~ (CRLF?)
  def record: Parser[List[String]] = repsep(field, SEP)
  def field: Parser[String] = escaped|nonescaped
  def escaped: Parser[String] = {
    ((SPACES?)~>DQUOTE~>((TXT|SEP|CRLF|DQUOTE2)*)<~DQUOTE<~(SPACES?)) ^^ { case ls => ls.mkString("") }
  }
  def nonescaped: Parser[String] = (TXT*) ^^ { case ls => ls.mkString("") }
  def parse(s: String) = parseAll(record, s) match {
    case Success(res, _) => res
    case e => throw new Exception(e.toString)
  }
}
