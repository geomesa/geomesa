/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.tools.ingest

import java.io.File

import com.beust.jcommander.{Parameter, ParameterException}
import com.typesafe.config.Config
import org.geotools.data.DataStore
import org.locationtech.geomesa.tools._
import org.locationtech.geomesa.tools.utils.{CLArgResolver, DataFormats}
import org.locationtech.geomesa.utils.io.PathUtils
import org.opengis.feature.simple.SimpleFeatureType

import scala.util.Try

trait IngestCommand[DS <: DataStore] extends DataStoreCommand[DS] {

  import scala.collection.JavaConversions._

  override val name = "ingest"
  override def params: IngestParams

  def libjarsFile: String
  def libjarsPaths: Iterator[() => Seq[File]]

  override def execute(): Unit = {
    import DataFormats.{Avro, Csv, Shp, Tsv}

    ensureSameFs(PathUtils.RemotePrefixes)

    val ingest = if (params.fmt == Shp) {
      createShpIngest()
    } else if (params.spec == null && params.config == null && Seq(Csv, Tsv, Avro).contains(params.fmt)) {
      // if there is no sft and no converter passed in, try to use the auto ingest which will
      // pick up the schema from the input files themselves
      if (params.featureName == null) {
        throw new ParameterException("Feature name is required when a schema is not specified")
      }
      // auto-detect the import schema
      Command.user.info("No schema or converter defined - will attempt to detect schema from input files")
      createAutoIngest()
    } else {
      // validate arguments
      if (params.config == null) {
        throw new ParameterException("Converter Config argument is required")
      }
      val converterConfig = CLArgResolver.getConfig(params.config)

      val sft = if (params.spec != null) {
        CLArgResolver.getSft(params.spec, params.featureName)
      } else if (params.featureName != null) {
        Try(withDataStore(_.getSchema(params.featureName))).filter(_ != null).getOrElse {
          throw new ParameterException(s"SimpleFeatureType '${params.featureName}' does not currently exist, " +
              "please provide specification argument")
        }
      } else {
        throw new ParameterException("SimpleFeatureType name and/or specification argument is required")
      }

      createConverterIngest(sft, converterConfig)
    }

    ingest.run()
  }

  protected def createConverterIngest(sft: SimpleFeatureType, converterConfig: Config): Runnable = {
    new ConverterIngest(sft, connection, converterConfig, params.files, Option(params.mode),
      libjarsFile, libjarsPaths, params.threads, params.maxSplitSize)
  }

  protected def createAutoIngest(): Runnable = {
    new AutoIngest(params.featureName, connection, params.files, params.fmt, Option(params.mode),
      libjarsFile, libjarsPaths, params.threads)
  }

  protected def createShpIngest(): Runnable = {
    new ShapefileIngest(connection, Option(params.featureName), params.files, params.threads)
  }

  def ensureSameFs(prefixes: Seq[String]): Unit = {
    prefixes.foreach { pre =>
      if (params.files.exists(_.toLowerCase.startsWith(s"$pre://")) &&
        !params.files.forall(_.toLowerCase.startsWith(s"$pre://"))) {
        throw new ParameterException(s"Files must all be on the same file system: ($pre) or all be local")
      }
    }
  }
}

// @Parameters(commandDescription = "Ingest/convert various file formats into GeoMesa")
trait IngestParams extends OptionalTypeNameParam with OptionalFeatureSpecParam
    with OptionalConverterConfigParam with OptionalInputFormatParam with DistributedRunParam {
  @Parameter(names = Array("-t", "--threads"), description = "Number of threads if using local ingest")
  var threads: Integer = 1

  @Parameter(names = Array("--split-max-size"), description = "Maximum size of a split in bytes")
  var maxSplitSize: Integer = -1
}
