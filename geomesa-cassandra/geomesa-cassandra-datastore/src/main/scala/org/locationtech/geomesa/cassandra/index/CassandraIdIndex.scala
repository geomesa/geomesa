/***********************************************************************
* Copyright (c) 2013-2016 IBM
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.cassandra.index


import com.google.common.primitives.{Bytes, Longs, Shorts}
import com.datastax.driver.core.querybuilder._
import com.datastax.driver.core._
import org.geotools.factory.Hints
import org.locationtech.geomesa.cassandra._
import org.locationtech.geomesa.cassandra.data._
import org.locationtech.geomesa.index.index.ClientSideFiltering.RowAndValue
import org.locationtech.geomesa.index.api.FilterStrategy
import org.locationtech.geomesa.index.index.IdIndex
import org.opengis.filter.Filter
import org.opengis.feature.simple.SimpleFeatureType

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

case object CassandraIdIndex
    extends CassandraFeatureIndex with IdIndex[CassandraDataStore, CassandraFeature, CassandraRow, CassandraRow] {
  import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType
  override val version: Int = 1

  override def getRowValuesFromSFT(sft: SimpleFeatureType): RowToValues = {
    val sharing = sft.getTableSharingPrefix
    (row) => {
      val fid = if (sharing == ""){
        new String(row, 0, row.length, StandardCharsets.UTF_8)
      } else {
        new String(row, 1, row.length, StandardCharsets.UTF_8)
      }
      (fid, sharing)
    }
  }

  override def configure(sft: SimpleFeatureType, ds: CassandraDataStore): Unit = {
    ds.metadata.insert(sft.getTypeName, tableNameKey, generateTableName(sft, ds))
    val tableName = getTableName(sft.getTypeName, ds)
    val cluster = ds.session.getCluster
    val ks = ds.session.getLoggedKeyspace
    val table = cluster.getMetadata().getKeyspace(ks).getTable(tableName);

    if (table == null){
      ds.session.execute(s"CREATE TABLE $tableName (fid text, sharing text, sf blob, PRIMARY KEY (fid, sharing))")
    }
  }

  override protected def createInsert(row: Array[Byte], cf: CassandraFeature): CassandraRow = {
    val (fid:java.lang.String, sharing:java.lang.String) = getRowValuesFromSFT(cf.feature.getFeatureType)(row)
    val qs = "INSERT INTO %s (fid, sharing, sf) VALUES (?, ?, ?)"
    CassandraRow(qs=Some(qs), values=Some(Seq(fid, sharing, ByteBuffer.wrap(cf.fullValue))))
  }

  override protected def createDelete(row: Array[Byte], cf: CassandraFeature): CassandraRow = {
    val (fid:java.lang.String, sharing:java.lang.String) = getRowValuesFromSFT(cf.feature.getFeatureType)(row)
    val qs = "DELETE FROM %s WHERE fid=$fid and sharing=$sharing"
    CassandraRow(qs=Some(qs))
  }

  override protected def scanPlan(sft: SimpleFeatureType,
                                  ds: CassandraDataStore,
                                  filter: FilterStrategy[CassandraDataStore, CassandraFeature, CassandraRow],
                                  hints: Hints,
                                  rows: Seq[CassandraRow],
                                  ecql: Option[Filter]): CassandraQueryPlanType = {
    import org.locationtech.geomesa.index.conf.QueryHints.RichHints
    if (rows.isEmpty) {
      EmptyPlan(filter)
    } else {
      val ks = ds.session.getLoggedKeyspace
      val tableName = getTableName(sft.getTypeName, ds)
      val getRowValues = getRowValuesFromSFT(sft)

      val queries = rows.map(row => {
        val select = QueryBuilder.select.all.from(ks, tableName)
        val start = row.start.get
        val end = row.start.getOrElse(Array())
        if ((start.length > 0) && (end.length > 0)){
          val (stFid:java.lang.String, stSharing:java.lang.String) = getRowValues(start)
          val (edFid:java.lang.String, _) = getRowValues(end)

          val where = if (stFid == edFid) {
            select.where(QueryBuilder.eq("fid", stFid))
          } else {
            select.where(QueryBuilder.in("fid", java.util.Arrays.asList(stFid, edFid)))
          }

          where.and(QueryBuilder.eq("sharing", stSharing))

        } else if (start.length > 0){
          val (stFid:java.lang.String, stSharing:java.lang.String) = getRowValues(start)
          select.where(QueryBuilder.eq("fid", stFid))
            .and(QueryBuilder.eq("sharing", stSharing))
        } else if (end.length > 0){
          val (edFid:java.lang.String, edSharing:java.lang.String) = getRowValues(end)
          select.where(QueryBuilder.eq("fid", edFid))
            .and(QueryBuilder.eq("sharing", edSharing))
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
    val fid:Array[Byte] = result.getString(0).getBytes
    val sharing = result.getString(1)
    val sf:Array[Byte] = result.getBytes(2).array
    val row = if (sharing == "") {
      fid
    } else {
      Bytes.concat(sharing.getBytes, fid)
    }
    RowAndValue(row, 0, row.length, sf, 0, sf.length)
  }

}
