/***********************************************************************
* Copyright (c) 2016  IBM
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.cassandra.index

import com.google.common.primitives.{Bytes, Longs, Shorts}

import com.datastax.driver.core._
import com.datastax.driver.core.querybuilder._
import org.geotools.factory.Hints
import org.locationtech.geomesa.cassandra._
import org.locationtech.geomesa.cassandra.data._
import org.locationtech.geomesa.index.api.FilterStrategy
import org.locationtech.geomesa.index.index.AttributeIndex
import org.locationtech.geomesa.index.index.ClientSideFiltering.RowAndValue
import org.locationtech.geomesa.index.index.{ClientSideFiltering, IndexAdapter}
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.opengis.filter.Filter
import org.opengis.feature.simple.SimpleFeatureType

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

case object CassandraAttributeIndex
    extends CassandraFeatureIndex with AttributeIndex[CassandraDataStore, CassandraFeature, CassandraRow, CassandraRow] {
  import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType

  override val version: Int = 1

  type RowAttrToValues = (Array[Byte], Boolean) => Any

  def getAttrValuesFromSFT(sft: SimpleFeatureType): RowAttrToValues = {
    val sharing = sft.getTableSharingPrefix
    (row, insert) => {
      var off = if (sharing == "") 0 else 1
      row.length match {
        case 2 => ("", ByteBuffer.wrap(row, 0, 2).getShort)
        case 3 => (sharing, ByteBuffer.wrap(row, 1, 2).getShort)
        case _ => {
          if (insert){
            val attrIdx = ByteBuffer.wrap(row, off, 2).getShort
            off += 2
            val rem = row.slice(off, row.length - off)
            off = rem.indexOf(0)
            val attrVal = rem.slice(0, off)
            off += 1
            val dtg = if (sft.getDtgField.isDefined){
              val d = row.slice(off, off + 12)
              off += 12
              d
            } else {
              ""
            }
            val fid = new String(row.slice(off, row.length))
            (sharing, attrIdx, attrVal, dtg, fid)
          } else {
            val idx = row.indexOf(0)
            val attrVal = row.slice(off, idx)
            off = idx + 1
            val dtg = if (sft.getDtgField.isDefined){
              row.slice(off, row.length)
            } else {
              ""
            }
            (sharing, attrVal, dtg)
          }
        }
      }
    }
  }

  override def configure(sft: SimpleFeatureType, ds: CassandraDataStore): Unit = {
    ds.metadata.insert(sft.getTypeName, tableNameKey, generateTableName(sft, ds))
    val tableName = getTableName(sft.getTypeName, ds)
    val cluster = ds.session.getCluster
    val ks = ds.session.getLoggedKeyspace
    val table = cluster.getMetadata().getKeyspace(ks).getTable(tableName);
    if (table == null){
      ds.session.execute(s"CREATE TABLE $tableName (attrVal blob, sharing text, fid text, dtg blob, attrIdx smallint, sf blob, PRIMARY KEY(attrVal, sharing))")
    }
  }

  override protected def createInsert(row: Array[Byte], cf: CassandraFeature): CassandraRow = {
    val (sharing:java.lang.String, attrIdx:java.lang.Short,
      attrVal:Array[Byte], dtg:Array[Byte], fid:java.lang.String) = getAttrValuesFromSFT(cf.feature.getFeatureType)(row, true)
    val qs = "INSERT INTO %s (attrVal, sharing, fid, dtg, attrIdx, sf) VALUES (?, ?, ?, ?, ?, ?)"
    CassandraRow(qs=Some(qs), values=Some(Seq(ByteBuffer.wrap(attrVal), sharing, cf.feature.getID,
      ByteBuffer.wrap(dtg), attrIdx, ByteBuffer.wrap(cf.fullValue))))
  }

  override protected def createDelete(row: Array[Byte], cf: CassandraFeature): CassandraRow = {
    val (sharing:java.lang.String, _, _, _, fid:java.lang.String) = getAttrValuesFromSFT(cf.feature.getFeatureType)(row, true)
    val qs = "DELETE FROM %s WHERE fid=$fid AND sharing=$sharing"
    CassandraRow(qs=Some(qs))
  }

  override protected def scanPlan(sft: SimpleFeatureType,
                                  ds: CassandraDataStore,
                                  filter: FilterStrategy[CassandraDataStore, CassandraFeature, CassandraRow],
                                  hints: Hints,
                                  ranges: Seq[CassandraRow],
                                  ecql: Option[Filter]): CassandraQueryPlanType = {
    import org.locationtech.geomesa.index.conf.QueryHints.RichHints
    if (ranges.isEmpty) {
      EmptyPlan(filter)
    } else {
      val ks = ds.session.getLoggedKeyspace
      val tableName = getTableName(sft.getTypeName, ds)
      val getRowAttrValues = getAttrValuesFromSFT(sft)
      val queries = ranges.map(row => {
        val select = QueryBuilder.select.all.from(ks, tableName)
        val start = row.start.get
        val end = row.end.getOrElse(Array())

        if ((start.length > 0) && (end.length > 0)){
          val (stSharing:java.lang.String, stAttrVal:Array[byte], stDtg:java.lang.String) = getAttrValuesFromSFT(sft)(start, false)
          val (edSharing:java.lang.String, edAttrVal:Array[byte], edDtg:java.lang.String) = getAttrValuesFromSFT(sft)(end, false)

          val where = if (stAttrVal.equals(edAttrVal)) {
            select.where(QueryBuilder.eq("attrVal", stAttrVal))
          } else {
            select.where(QueryBuilder.in("attrVal", java.util.Arrays.asList(stAttrVal, edAttrVal)))
          }

          if (stSharing == edSharing){
            where.and(QueryBuilder.eq("sharing", stSharing))
          } else {
            where.and(QueryBuilder.gt("sharing", stSharing))
              .and(QueryBuilder.lt("sharing", edSharing))
          }

          if ((stDtg != "") && (edDtg != "")){
            where.and(QueryBuilder.gt("dtg", stDtg))
            where.and(QueryBuilder.lt("dtg", edDtg))
          } else if (stDtg != "") {
            where.and(QueryBuilder.gt("dtg", stDtg))
          } else if (edDtg != ""){
            where.and(QueryBuilder.lt("dtg", edDtg))
          }
        } else if (start.length > 0){
          val (stSharing:java.lang.String, stAttrVal:Array[byte], stDtg:java.lang.String) = getRowAttrValues(start, false)
          val where = select.where(QueryBuilder.eq("attrVal", stAttrVal))
                        .and(QueryBuilder.eq("sharing", stSharing))

          if (stDtg != ""){
            where.and(QueryBuilder.gt("dtg", stDtg))
          }
        } else if (end.length > 0){
          val (edSharing:java.lang.String, edAttrVal:Array[byte], edDtg:java.lang.String) = getRowAttrValues(end, false)
          val where = select.where(QueryBuilder.eq("attrVal", edAttrVal))
                        .and(QueryBuilder.eq("sharing", edSharing))
          if (edDtg != ""){
            where.and(QueryBuilder.lt("dtg", edDtg))
          }
        }
        CassandraRow(stmt=Some(select))
      })
      val toFeatures = resultsToFeatures(sft, ecql, hints.getTransform)
      QueryPlan(filter, tableName, queries, ecql, toFeatures)
    }
  }

  override protected def range(start: Array[Byte], end: Array[Byte]): CassandraRow = {
    CassandraRow(start=Some(start), end=Some(end))
  }

  override protected def rangeExact(row: Array[Byte]): CassandraRow = {
    CassandraRow(start=Some(row))
  }

  override protected def rowAndValue(result: Row): RowAndValue = {
    val attrVal:Array[Byte] = result.getBytes(0).array
    val sharing = result.getString(1)
    val fid = result.getString(2).getBytes
    val dtg = result.getBytes(3).array
    val idx = result.getShort(0)
    val sf:Array[Byte] = result.getBytes(5).array

    val row = if (sharing == "") {
      if (dtg == ""){
        Bytes.concat(Shorts.toByteArray(idx), attrVal, Array(0), fid)
      } else {
        Bytes.concat(Shorts.toByteArray(idx), attrVal, Array(0), dtg, fid)
      }
    } else {
      if (dtg == ""){
        Bytes.concat(sharing.getBytes, Shorts.toByteArray(idx), attrVal, Array(0), fid)
      } else {
        Bytes.concat(sharing.getBytes, Shorts.toByteArray(idx), attrVal, Array(0), dtg, fid)
      }
    }

    RowAndValue(row, 0, row.length, sf, 0, sf.length)
  }
}
