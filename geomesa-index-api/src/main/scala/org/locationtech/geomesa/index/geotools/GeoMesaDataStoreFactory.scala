/***********************************************************************
 * Copyright (c) 2013-2020 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.index.geotools

import java.io.Serializable
import java.util.concurrent.TimeUnit

import org.locationtech.geomesa.index.conf.{QueryProperties, StatsProperties}
import org.locationtech.geomesa.utils.audit.{AuditProvider, AuditWriter}
import org.locationtech.geomesa.utils.geotools.GeoMesaParam
import org.locationtech.geomesa.utils.geotools.GeoMesaParam.{ConvertedParam, ReadWriteFlag, SystemPropertyBooleanParam, SystemPropertyDurationParam}

import scala.concurrent.duration.Duration

object GeoMesaDataStoreFactory {

  private val GenerateStatsSysParam = SystemPropertyBooleanParam(StatsProperties.GenerateStats)
  private val TimeoutSysParam = SystemPropertyDurationParam(QueryProperties.QueryTimeout)

  private val DeprecatedTimeout =
    ConvertedParam[Duration, java.lang.Long]("queryTimeout", v => Duration(v, TimeUnit.SECONDS))
  private val DeprecatedAccumuloTimeout =
    ConvertedParam[Duration, java.lang.Long]("accumulo.queryTimeout", v => Duration(v, TimeUnit.SECONDS))

  val QueryThreadsParam =
    new GeoMesaParam[Integer](
      "geomesa.query.threads",
      "The number of threads to use per query",
      default = Int.box(8),
      deprecatedKeys = Seq("queryThreads", "accumulo.queryThreads"),
      supportsNiFiExpressions = true,
      readWrite = ReadWriteFlag.ReadUpdate
    )

  val QueryTimeoutParam =
    new GeoMesaParam[Duration](
      "geomesa.query.timeout",
      "The max time a query will be allowed to run before being killed, e.g. '60 seconds'",
      deprecatedParams = Seq(DeprecatedTimeout, DeprecatedAccumuloTimeout),
      systemProperty = Some(TimeoutSysParam),
      supportsNiFiExpressions = true,
      readWrite = ReadWriteFlag.ReadUpdate
    )

  val LooseBBoxParam =
    new GeoMesaParam[java.lang.Boolean](
      "geomesa.query.loose-bounding-box",
      "Use loose bounding boxes - queries will be faster but may return extraneous results",
      default = true,
      deprecatedKeys = Seq("looseBoundingBox"),
      readWrite = ReadWriteFlag.ReadUpdate
    )

  val StrictBBoxParam =
    new GeoMesaParam[java.lang.Boolean](
      "geomesa.query.loose-bounding-box",
      "Use loose bounding boxes - queries will be faster but may return extraneous results",
      default = false,
      deprecatedKeys = Seq("looseBoundingBox"),
      readWrite = ReadWriteFlag.ReadUpdate
    )

  val AuditQueriesParam =
    new GeoMesaParam[java.lang.Boolean](
      "geomesa.query.audit",
      "Audit queries being run",
      default = true,
      deprecatedKeys = Seq("auditQueries", "collectQueryStats"),
      readWrite = ReadWriteFlag.ReadUpdate
    )

  val CachingParam =
    new GeoMesaParam[java.lang.Boolean](
      "geomesa.query.caching",
      "Cache the results of queries for faster repeated searches. Warning: large result sets can swamp memory",
      default = false,
      deprecatedKeys = Seq("caching"),
      readWrite = ReadWriteFlag.ReadOnly
    )

  val GenerateStatsParam =
    new GeoMesaParam[java.lang.Boolean](
      "geomesa.stats.enable",
      "Generate and persist data statistics for new feature types",
      default = true,
      deprecatedKeys = Seq("generateStats"),
      systemProperty = Some(GenerateStatsSysParam),
      readWrite = ReadWriteFlag.WriteOnly
    )

  val NamespaceParam = new GeoMesaParam[String]("namespace", "Namespace")

  trait NamespaceConfig {
    def namespace: Option[String]
    def catalog: String
  }

  trait GeoMesaDataStoreConfig extends NamespaceConfig {
    def audit: Option[(AuditWriter, AuditProvider, String)]
    def generateStats: Boolean
    def queries: DataStoreQueryConfig
  }

  trait DataStoreQueryConfig {
    def threads: Int
    def timeout: Option[Long]
    def looseBBox: Boolean
    def caching: Boolean
  }

  // noinspection TypeAnnotation
  trait NamespaceParams {
    val NamespaceParam = GeoMesaDataStoreFactory.NamespaceParam
  }

  // noinspection TypeAnnotation
  trait GeoMesaDataStoreParams extends NamespaceParams {

    protected def looseBBoxDefault = true

    val AuditQueriesParam  = GeoMesaDataStoreFactory.AuditQueriesParam
    val GenerateStatsParam = GeoMesaDataStoreFactory.GenerateStatsParam
    val QueryThreadsParam  = GeoMesaDataStoreFactory.QueryThreadsParam
    val QueryTimeoutParam  = GeoMesaDataStoreFactory.QueryTimeoutParam
    val CachingParam       = GeoMesaDataStoreFactory.CachingParam

    val LooseBBoxParam =
      if (looseBBoxDefault) { GeoMesaDataStoreFactory.LooseBBoxParam } else { GeoMesaDataStoreFactory.StrictBBoxParam }
  }

  trait GeoMesaDataStoreInfo {
    def DisplayName: String
    def Description: String
    def ParameterInfo: Array[GeoMesaParam[_ <: AnyRef]]
    def canProcess(params: java.util.Map[String, _ <: Serializable]): Boolean
  }
}
