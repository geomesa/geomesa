/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.convert.xml

import java.io.{InputStream, StringReader}
import java.nio.charset.StandardCharsets
import javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.IOUtils
import org.apache.xpath.CachedXPathAPI
import org.locationtech.geomesa.convert.LineMode.LineMode
import org.locationtech.geomesa.convert.Transformers.{EvaluationContext, Expr}
import org.locationtech.geomesa.convert._
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.w3c.dom.Node
import org.xml.sax.InputSource

import scala.collection.immutable.IndexedSeq
import scala.io.Source

class XMLConverter(val targetSFT: SimpleFeatureType,
                   val idBuilder: Expr,
                   val featurePath: Option[String],
                   val xsd: Option[String],
                   val inputFields: IndexedSeq[Field],
                   val userDataBuilder: Map[String, Expr],
                   val validating: Boolean,
                   val lineMode: LineMode) extends ToSimpleFeatureConverter[String] with LazyLogging {

  private val docBuilder = {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setNamespaceAware(false)
    factory.newDocumentBuilder()
  }
  private val validator = xsd.map { path =>
    val schemaFactory = SchemaFactory.newInstance(W3C_XML_SCHEMA_NS_URI)
    val xsdStream = getClass.getClassLoader.getResourceAsStream(path)
    val schema = schemaFactory.newSchema(new StreamSource(xsdStream))
    xsdStream.close()
    schema.newValidator()
  }
  private val cachedAPI = new CachedXPathAPI

  override def fromInputType(i: String): Seq[Array[Any]] = {
    // if a schema is defined, validate it - this will throw an exception on failure
    validator.foreach(_.validate(new StreamSource(new StringReader(i))))
    // parse the document once, then extract each feature node and operate on it
    val root = docBuilder.parse(new InputSource(new StringReader(i))).getDocumentElement

    featurePath.map { path =>
      val nl = cachedAPI.eval(root, path).nodelist()
      (0 until nl.getLength).map { i => Array[Any](nl.item(i)) }
    }.getOrElse(Seq(Array[Any](root)))
  }

  // TODO GEOMESA-1039 more efficient InputStream processing for multi mode
  override def process(is: InputStream, ec: EvaluationContext = createEvaluationContext()): Iterator[SimpleFeature] =
    lineMode match {
      case LineMode.Single =>
        processInput(Source.fromInputStream(is, StandardCharsets.UTF_8.displayName).getLines(), ec)
      case LineMode.Multi =>
        processInput(Iterator(IOUtils.toString(is, StandardCharsets.UTF_8.displayName)), ec)
    }
}

class XMLConverterFactory extends AbstractSimpleFeatureConverterFactory[String] {

  private val cachedXPathAPI = new CachedXPathAPI()

  override protected val typeToProcess = "xml"

  override protected def buildConverter(sft: SimpleFeatureType,
                                        conf: Config,
                                        idBuilder: Expr,
                                        fields: IndexedSeq[Field],
                                        userDataBuilder: Map[String, Expr],
                                        validating: Boolean): XMLConverter = {
    // feature path can be any xpath that resolves to a node set (or a single node)
    // it can be absolute, or relative to the root node
    val featurePath = if (conf.hasPath("feature-path")) Some(conf.getString("feature-path")) else None
    val xsd         = if (conf.hasPath("xsd")) Some(conf.getString("xsd")) else None
    val lineMode    = LineMode.getLineMode(conf)
    new XMLConverter(sft, idBuilder, featurePath, xsd, fields, userDataBuilder, validating, lineMode)
  }

  override protected def buildField(field: Config): Field = {
    val name = field.getString("name")
    val transform = if (field.hasPath("transform")) {
      Transformers.parseTransform(field.getString("transform"))
    } else {
      null
    }
    if (field.hasPath("path")) {
      // path can be absolute, or relative to the feature node
      // it can also include xpath functions to manipulate the result
      XMLField(name, field.getString("path"), transform, cachedXPathAPI)
    } else {
      SimpleField(name, transform)
    }
  }
}

case class XMLField(name: String, expression: String, transform: Expr, xpath: CachedXPathAPI) extends Field {

  private val mutableArray = Array.ofDim[Any](1)

  override def eval(args: Array[Any])(implicit ec: EvaluationContext): Any = {
    mutableArray(0) = xpath.eval(args(0).asInstanceOf[Node], expression).str()
    if (transform == null) {
      mutableArray(0)
    } else {
      super.eval(mutableArray)
    }
  }
}
