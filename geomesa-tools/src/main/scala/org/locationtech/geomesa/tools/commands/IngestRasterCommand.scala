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
package org.locationtech.geomesa.tools.commands

import java.io.File

import com.beust.jcommander.{IParameterValidator, JCommander, Parameter, Parameters}
import org.locationtech.geomesa.raster.util.RasterUtils.IngestRasterParams
import org.locationtech.geomesa.tools.Utils.Formats._
import org.locationtech.geomesa.tools._
import org.locationtech.geomesa.tools.commands.IngestRasterCommand.{Command, IngestRasterParameters}
import org.locationtech.geomesa.tools.ingest.{RasterChunking, LocalRasterIngest, RasterFilesSerialization, RemoteRasterIngest}

import scala.util.{Failure, Success}

class IngestRasterCommand(parent: JCommander) extends Command with AccumuloProperties {

  val params = new IngestRasterParameters()
  validateCommand
  parent.addCommand(Command, params)

  override def execute() {
    val fmt = Option(params.format).getOrElse(getFileExtension(params.file))
    fmt match {
      case TIFF | DTED =>
        ingest(params.mode)
      case _         =>
        logger.error("Error: File format not supported for file " + params.file + ". Supported formats " +
          "are geotiff and DTED")
    }
  }

  //Raster ingestion starts from local file or directory.
  //If chunking is specified, input image is cut into chunks stored in a directory.
  //In local mode, file(s) is(are) directly ingested into an Accumulo table.
  //In distributed mode, file(s) is(are) serialized and stored into HDFS as sequence file(s),
  //and a scalding job is executed to ingest sequence file(s) into an Accumulo table from HDFS.
  def ingest(mode: String) {
    val baseRasterIngestParams = getRasterIngestParams
    val ingestPath = params.doChunk match {
      case true =>
        val rasterChunker =
          new RasterChunking(baseRasterIngestParams + (IngestRasterParams.FILE_PATH -> Some(params.file)))
        rasterChunker.runChunkTask match {
          case Success(outDir) =>
            logger.info("Raster chunking is done.")
            outDir
          case Failure(e) => throw new RuntimeException(e)
        }
      case _ => params.file
    }

    mode.toLowerCase match {
      case "local" =>
        val simpleIngester =
          new LocalRasterIngest(baseRasterIngestParams + (IngestRasterParams.FILE_PATH  -> Some(ingestPath)))
        simpleIngester.runIngestTask match {
          case Success(info) => logger.info("Local ingestion is done.")
          case Failure(e) => throw new RuntimeException(e)
        }
      case "distributed" =>
        val rasterSerializer =
          new RasterFilesSerialization(baseRasterIngestParams + (IngestRasterParams.FILE_PATH  -> Some(ingestPath)))
        rasterSerializer.runSerializationTask match {
          case Success(outPath) =>
            logger.info("Raster files serialization is done.")
            new RemoteRasterIngest(baseRasterIngestParams + (IngestRasterParams.FILE_PATH  -> Some(outPath))).run
            Utils.deleteHdfsDirectory(outPath)
            logger.info("Remote ingestion is done.")
          case Failure(e) => throw new RuntimeException(e)
        }
    }

    if (params.doChunk)
      Utils.deleteLocalDirectory(ingestPath)
  }

  def validateCommand() {
    if (params.doChunk && new File(params.file).isDirectory)
      throw new Exception(s"${params.file} is a directory. Please specify a file to chunk raster.")
  }

  def getFormat(fileOrDir: File): String = {
    val file =
      if (fileOrDir.isDirectory) fileOrDir.listFiles.head
      else fileOrDir
    getFileExtension(file.getName)
  }

  def getRasterIngestParams(): Map[String, Option[String]] = {
    Map(
      IngestRasterParams.ZOOKEEPERS        -> Some(Option(params.zookeepers).getOrElse(zookeepersProp)),
      IngestRasterParams.ACCUMULO_INSTANCE -> Some(Option(params.instance).getOrElse(instanceName)),
      IngestRasterParams.ACCUMULO_USER     -> Some(params.user),
      IngestRasterParams.ACCUMULO_PASSWORD -> Some(getPassword(params.password)),
      IngestRasterParams.AUTHORIZATIONS    -> Option(params.auths),
      IngestRasterParams.VISIBILITIES      -> Option(params.visibilities),
      IngestRasterParams.ACCUMULO_MOCK     -> Some(params.useMock.toString),
      IngestRasterParams.TABLE             -> Some(params.table),
      IngestRasterParams.FORMAT            -> Some(Option(params.format).getOrElse(getFormat(new File(params.file)))),
      IngestRasterParams.GEOSERVER_REG     -> Option(params.geoserverConf),
      IngestRasterParams.TIME              -> Option(params.timeStamp),
      IngestRasterParams.WRITE_MEMORY      -> Option(params.writeMemory).map(_.toString),
      IngestRasterParams.WRITE_THREADS     -> Option(params.writeThreads).map(_.toString),
      IngestRasterParams.QUERY_THREADS     -> Option(params.queryThreads).map(_.toString),
      IngestRasterParams.SHARDS            -> Option(params.numShards).map(_.toString),
      IngestRasterParams.PARLEVEL          -> Some(params.parLevel.toString),
      IngestRasterParams.CHUNKSIZE         -> Some(params.chunkSize.toString)
    )
  }
}

class PathValidator extends IParameterValidator {
  def validate(name: String, value: String) {
    if (value == null) handleError(value, "is null")
    val fileOrDir = new File(value)
    if (!fileOrDir.exists) handleError(value, "doesn't exist")
    if (fileOrDir.isDirectory && fileOrDir.listFiles.size == 0) handleError(value, "is empty")
  }

  def handleError(fileOrDir: String, reason: String) {
    throw new Exception(s"No file is specified to ingest: ${fileOrDir} ${reason}.")
  }
}

class ModeValidator extends IParameterValidator {
  def validate(name: String, value: String): Unit = {
    if (!(value.toLowerCase == "local" || value.toLowerCase == "distributed"))
      throw new Exception(s"Unsupported ingestion mode: ${value}. Use either local (default) or distributed.")
  }
}

object IngestRasterCommand {
  val Command = "ingestraster"

  @Parameters(commandDescription = "Ingest a raster file or raster files in a directory into GeoMesa")
  class IngestRasterParameters extends CreateRasterParams {
    @Parameter(names = Array("-fmt", "--format"), description = "Format of incoming raster data " +
      "(geotiff | DTED) to override file extension recognition")
    var format: String = null

    @Parameter(names = Array("-f", "--file"), description = "Single raster file or directory of " +
      "raster files to be ingested", validateWith = classOf[PathValidator], required = true)
    var file: String = null

    @Parameter(names = Array("-tm", "--timestamp"), description = "Ingestion time (default to current time)")
    var timeStamp: String = null

    @Parameter(names = Array("-par", "--parallel-level"), description = " -DEPRECATED-FOR-LOCAL-INGEST Maximum number of local " +
      "threads for ingesting multiple raster files (default to 1)")
    var parLevel: Integer = 1

    @Parameter(names = Array("-m", "--mode"), description = "Ingestion mode (local | distributed, default " +
      "to local)", validateWith = classOf[ModeValidator])
    var mode: String = "local"

    @Parameter(names = Array("-ck", "--chunk"), description = "Create raster chunks before ingestion")
    var doChunk: Boolean = false

    @Parameter(names = Array("-cs", "--chunk-size"), description = "Desired size (in kilobytes) of each chunk")
    var chunkSize: Integer = 600
  }
}
