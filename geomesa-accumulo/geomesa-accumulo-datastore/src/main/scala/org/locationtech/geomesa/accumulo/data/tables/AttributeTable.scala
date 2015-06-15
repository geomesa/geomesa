/***********************************************************************
* Copyright (c) 2013-2015 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/


package org.locationtech.geomesa.accumulo.data.tables

import java.util.{Collection => JCollection, Date, Locale, Map => JMap}

import com.typesafe.scalalogging.slf4j.Logging
import org.apache.accumulo.core.data.Mutation
import org.apache.hadoop.io.Text
import org.calrissian.mango.types.{LexiTypeEncoders, SimpleTypeEncoders, TypeEncoder}
import org.joda.time.format.ISODateTimeFormat
import org.locationtech.geomesa.accumulo.data.AccumuloFeatureWriter.{FeatureToMutations, FeatureToWrite}
import org.locationtech.geomesa.accumulo.data._
import org.locationtech.geomesa.utils.geotools.RichAttributeDescriptors.RichAttributeDescriptor
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.stats.IndexCoverage
import org.opengis.feature.`type`.AttributeDescriptor
import org.opengis.feature.simple.SimpleFeatureType

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

/**
 * Contains logic for converting between accumulo and geotools for the attribute index
 */
object AttributeTable extends GeoMesaTable with Logging {

  override def supports(sft: SimpleFeatureType) =
    SimpleFeatureTypes.getSecondaryIndexedAttributes(sft).nonEmpty

  override val suffix: String = "attr_idx"

  override def writer(sft: SimpleFeatureType): Option[FeatureToMutations] = {
    val indexedAttributes = SimpleFeatureTypes.getSecondaryIndexedAttributes(sft)
    if (indexedAttributes.isEmpty) {
      None
    } else {
      val rowIdPrefix = org.locationtech.geomesa.accumulo.index.getTableSharingPrefix(sft)

      val indexesOfIndexedAttributes = indexedAttributes.map { a => sft.indexOf(a.getName) }
      val attributesToIdx = indexedAttributes.zip(indexesOfIndexedAttributes)

      Some((toWrite: FeatureToWrite) => getAttributeIndexMutations(toWrite, attributesToIdx, rowIdPrefix))
    }
  }

  override def remover(sft: SimpleFeatureType): Option[FeatureToMutations] = {
    val indexedAttributes = SimpleFeatureTypes.getSecondaryIndexedAttributes(sft)
    if (indexedAttributes.isEmpty) {
      None
    } else {
      val rowIdPrefix = org.locationtech.geomesa.accumulo.index.getTableSharingPrefix(sft)

      val indexesOfIndexedAttributes = indexedAttributes.map { a => sft.indexOf(a.getName)}
      val attributesToIdx = indexedAttributes.zip(indexesOfIndexedAttributes)

      Some((toWrite: FeatureToWrite) => getAttributeIndexMutations(toWrite, attributesToIdx, rowIdPrefix, delete = true))
    }
  }

  val typeRegistry = LexiTypeEncoders.LEXI_TYPES
  private val NULLBYTE = "\u0000"

  /**
   * Gets mutations for the attribute index table
   *
   * @param toWrite
   * @param indexedAttributes attributes that will be indexed
   * @param rowIdPrefix
   * @param delete whether we are writing or deleting
   * @return
   */
  def getAttributeIndexMutations(toWrite: FeatureToWrite,
                                 indexedAttributes: Seq[(AttributeDescriptor, Int)],
                                 rowIdPrefix: String,
                                 delete: Boolean = false): Seq[Mutation] = {
    val cq = new Text(toWrite.feature.getID)
    indexedAttributes.flatMap { case (descriptor, idx) =>
      val attribute = toWrite.feature.getAttribute(idx)
      val mutations = getAttributeIndexRows(rowIdPrefix, descriptor, attribute).map(new Mutation(_))
      if (delete) {
        mutations.foreach(_.putDelete(EMPTY_COLF, cq, toWrite.columnVisibility))
      } else {
        val value = descriptor.getIndexCoverage() match {
          case IndexCoverage.FULL => toWrite.dataValue
          case IndexCoverage.JOIN => toWrite.indexValue
        }
        mutations.foreach(_.put(EMPTY_COLF, cq, toWrite.columnVisibility, value))
      }
      mutations
    }
  }

  /**
   * Gets row keys for the attribute index. Usually a single row will be returned, but collections
   * will result in multiple rows.
   *
   * @param rowIdPrefix
   * @param descriptor
   * @param value
   * @return
   */
  def getAttributeIndexRows(rowIdPrefix: String,
                            descriptor: AttributeDescriptor,
                            value: Any): Seq[String] = {
    val prefix = getAttributeIndexRowPrefix(rowIdPrefix, descriptor)
    encode(value, descriptor).map(prefix + _)
  }

  /**
   * Gets a prefix for an attribute row - useful for ranges over a particular attribute
   *
   * @param rowIdPrefix
   * @param descriptor
   * @return
   */
  def getAttributeIndexRowPrefix(rowIdPrefix: String, descriptor: AttributeDescriptor): String =
    rowIdPrefix ++ descriptor.getLocalName ++ NULLBYTE

  /**
   * Decodes an attribute value out of row string
   *
   * @param rowIdPrefix table sharing prefix
   * @param descriptor the attribute we're decoding
   * @param row
   * @return
   */
  def decodeAttributeIndexRow(rowIdPrefix: String,
                              descriptor: AttributeDescriptor,
                              row: String): Try[AttributeIndexRow] =
    for {
      suffix <- Try(row.substring(rowIdPrefix.length))
      separator = suffix.indexOf(NULLBYTE)
      name = suffix.substring(0, separator)
      encodedValue = suffix.substring(separator + 1)
      decodedValue = decode(encodedValue, descriptor)
    } yield {
      AttributeIndexRow(name, decodedValue)
    }

  /**
   * Lexicographically encode the value. Collections will return multiple rows, one for each entry.
   *
   * @param value
   * @param descriptor
   * @return
   */
  def encode(value: Any, descriptor: AttributeDescriptor): Seq[String] = {
    if (value == null) {
      Seq.empty
    } else if (descriptor.isCollection) {
      // encode each value into a separate row
      value.asInstanceOf[JCollection[_]].toSeq.flatMap(Option(_).map(typeEncode).filterNot(_.isEmpty))
    } else if (descriptor.isMap) {
      // TODO GEOMESA-454 - support querying against map attributes
      Seq.empty
    } else {
      val encoded = typeEncode(value)
      if (encoded.isEmpty) Seq.empty else Seq(encoded)
    }
  }

  private def typeEncode(value: Any): String = Try(typeRegistry.encode(value)).getOrElse(value.toString)

  /**
   * Decode an encoded value. Note that for collection types, only a single entry of the collection
   * will be decoded - this is because the collection entries have been broken up into multiple rows.
   *
   * @param encoded
   * @param descriptor
   * @return
   */
  def decode(encoded: String, descriptor: AttributeDescriptor): Any = {
    if (descriptor.isCollection) {
      // get the alias from the type of values in the collection
      val alias = descriptor.getCollectionType().map(_.getSimpleName.toLowerCase(Locale.US)).head
      Seq(typeRegistry.decode(alias, encoded)).asJava
    } else if (descriptor.isMap) {
      // TODO GEOMESA-454 - support querying against map attributes
      Map.empty.asJava
    } else {
      val alias = descriptor.getType.getBinding.getSimpleName.toLowerCase(Locale.US)
      typeRegistry.decode(alias, encoded)
    }
  }

  private val dateFormat = ISODateTimeFormat.dateTime()
  private val simpleEncoders = SimpleTypeEncoders.SIMPLE_TYPES.getAllEncoders

  private type TryEncoder = Try[(TypeEncoder[Any, String], TypeEncoder[_, String])]

  /**
   * Tries to convert a value from one class to another. When querying attributes, the query
   * literal has to match the class of the attribute for lexicoding to work.
   *
   * @param value
   * @param current
   * @param desired
   * @return
   */
  def convertType(value: Any, current: Class[_], desired: Class[_]): Any = {
    val result =
      if (current == desired) {
        Success(value)
      } else if (desired == classOf[Date] && current == classOf[String]) {
        // try to parse the string as a date - right now we support just ISO format
        Try(dateFormat.parseDateTime(value.asInstanceOf[String]).toDate)
      } else {
        // cheap way to convert between basic classes (string, int, double, etc) - encode the value
        // to a string and then decode to the desired class
        val encoderOpt = simpleEncoders.find(_.resolves().equals(current)).map(_.asInstanceOf[TypeEncoder[Any, String]])
        val decoderOpt = simpleEncoders.find(_.resolves().equals(desired))
        (encoderOpt, decoderOpt) match {
          case (Some(e), Some(d)) => Try(d.decode(e.encode(value)))
          case _ => Failure(new RuntimeException("No matching encoder/decoder"))
        }
      }

    result match {
      case Success(converted) => converted
      case Failure(e) =>
        logger.warn(s"Error converting type for '$value' from ${current.getSimpleName} to " +
          s"${desired.getSimpleName}: ${e.toString}")
        value
    }
  }
}

case class AttributeIndexRow(attributeName: String, attributeValue: Any)
