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
package org.locationtech.geomesa.tools.ingest

import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import com.twitter.scalding.{Args, Hdfs, Local, Mode}
import org.apache.accumulo.core.client.Connector
import org.apache.commons.codec.binary.Hex
import org.apache.commons.io.IOUtils
import org.apache.hadoop.conf.Configuration
import org.locationtech.geomesa.core.data.AccumuloDataStore
import org.locationtech.geomesa.jobs.JobUtils
import org.locationtech.geomesa.tools.Utils.Formats._
import org.locationtech.geomesa.tools.Utils.Modes._
import org.locationtech.geomesa.tools.Utils.{IngestParams, Modes}
import org.locationtech.geomesa.tools.commands.IngestCommand.IngestParameters
import org.locationtech.geomesa.tools.ingest.DelimitedIngest._
import org.locationtech.geomesa.tools.{AccumuloProperties, FeatureCreator}

import scala.collection.JavaConversions._

class DelimitedIngest(params: IngestParameters) extends AccumuloProperties {

  def run(): Unit = {
    // create schema for the feature prior to Ingest job
    FeatureCreator.createFeature(params)

    val conf = new Configuration()
    JobUtils.setLibJars(conf, libJars = ingestLibJars, searchPath = ingestJarSearchPath)

    // setup ingest
    val mode =
      if (getJobMode(params.files(0)) == Modes.Hdfs) {
        Hdfs(strict = true, conf)
      } else {
        Local(strictSources = true)
      }

    validateFileArgs(mode, params)

    val arguments = Mode.putMode(mode, getScaldingArgs())
    val job = new ScaldingDelimitedIngestJob(arguments)
    val flow = job.buildFlow

    //block until job is completed.
    flow.complete()
    job.printStatInfo
  }

  def validateFileArgs(mode: Mode, params: IngestParameters) =
    mode match {
      case Local(_) =>
        if (params.files.size > 1) {
          throw new IllegalArgumentException("Cannot ingest multiple files in Local mode..." +
            "please provide only a single file argument")
        }
      case _ =>
    }

  def ingestLibJars = {
    val is = getClass.getClassLoader.getResourceAsStream("org/locationtech/geomesa/tools/ingest-libjars.list")
    try {
      IOUtils.readLines(is)
    } catch {
      case e: Exception => throw new Exception("Error reading ingest libjars: "+e.getMessage, e)
    } finally {
      IOUtils.closeQuietly(is)
    }
  }

  def ingestJarSearchPath: Iterator[() => Seq[File]] =
    Iterator(() => JobUtils.getJarsFromEnvironment("GEOMESA_HOME"),
      () => JobUtils.getJarsFromEnvironment("ACCUMULO_HOME"),
      () => JobUtils.getJarsFromClasspath(classOf[ScaldingDelimitedIngestJob]),
      () => JobUtils.getJarsFromClasspath(classOf[AccumuloDataStore]),
      () => JobUtils.getJarsFromClasspath(classOf[Connector]))

  def getScaldingArgs(): Args = {
    val singleArgs = List(classOf[ScaldingDelimitedIngestJob].getCanonicalName, getModeFlag(params.files(0)))

    val requiredKvArgs: Map[String, String] = Map(
      IngestParams.FILE_PATH         -> encodeFileList(params.files.toList),
      IngestParams.SFT_SPEC          -> URLEncoder.encode(params.spec, "UTF-8"),
      IngestParams.CATALOG_TABLE     -> params.catalog,
      IngestParams.ZOOKEEPERS        -> Option(params.zookeepers).getOrElse(zookeepersProp),
      IngestParams.ACCUMULO_INSTANCE -> Option(params.instance).getOrElse(instanceName),
      IngestParams.ACCUMULO_USER     -> params.user,
      IngestParams.ACCUMULO_PASSWORD -> getPassword(params.password),
      IngestParams.DO_HASH           -> params.hash.toString,
      IngestParams.FORMAT            -> Option(params.format).getOrElse(getFileExtension(params.files(0))),
      IngestParams.FEATURE_NAME      -> params.featureName,
      IngestParams.IS_TEST_INGEST    -> false.toString
    )

    val optionalKvArgs: Map[String, String] =
      List(
        IngestParams.COLS              -> Option(params.columns),
        IngestParams.DT_FORMAT         -> Option(params.dtFormat),
        IngestParams.ID_FIELDS         -> Option(params.idFields),
        IngestParams.DT_FIELD          -> Option(params.dtgField),
        IngestParams.LON_ATTRIBUTE     -> Option(params.lon),
        IngestParams.LAT_ATTRIBUTE     -> Option(params.lat),
        IngestParams.AUTHORIZATIONS    -> Option(params.auths),
        IngestParams.VISIBILITIES      -> Option(params.visibilities),
        IngestParams.INDEX_SCHEMA_FMT  -> Option(params.indexSchema),
        IngestParams.SHARDS            -> Option(params.numShards),
        IngestParams.LIST_DELIMITER    -> Option(params.listDelimiter))
      .filter(p => p._2.nonEmpty)
      .map { case (k,o) => k -> o.get.toString }
      .toMap

    if ( !optionalKvArgs.contains(IngestParams.DT_FIELD) ) {
      // assume user has no date field to use and that there is no column of data signifying it.
      logger.warn("Warning: no date-time field specified. Assuming that data contains no date column. \n" +
        s"GeoMesa is defaulting to the system time for ingested features.")
    }

    val kvArgs = (requiredKvArgs ++ optionalKvArgs).flatMap { case (k,v) => List(s"--$k", v) }
    Args(singleArgs ++ kvArgs)
  }
}

object DelimitedIngest {
  def encodeFileList(files: List[String]) =
    files.map { s => Hex.encodeHexString(s.getBytes(StandardCharsets.UTF_8)) }.mkString(" ")

  def decodeFileList(encoded: String) =
    encoded.split(" ").map { s => new String(Hex.decodeHex(s.toCharArray)) }
}