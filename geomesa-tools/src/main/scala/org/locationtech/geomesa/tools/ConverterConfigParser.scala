/***********************************************************************
* Copyright (c) 2013-2015 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/
package org.locationtech.geomesa.tools

import java.io.File

import com.beust.jcommander.ParameterException
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging
import org.locationtech.geomesa.convert.SimpleFeatureConverters

import scala.util.{Failure, Success, Try}

/**
 * Attempts to parse Converter config from arguments as either a string or
 * as a filename containing the converter config
 */
object ConverterConfigParser extends LazyLogging {

  /**
   * @throws ParameterException if the config cannot be parsed
   * @return the converter config parsed from the args
   */
  @throws[ParameterException]
  def getConfig(configArg: String): Config = {
    getLoadedConf(configArg).orElse(parseString(configArg)).getOrElse {
      throw new ParameterException(s"Unable to parse Converter config from argument $configArg")
    }
  }

  private[ConverterConfigParser] def getLoadedConf(configArg: String): Option[Config] =
    SimpleFeatureConverters.confs.find(_._1 == configArg).map(_._2)

  private[ConverterConfigParser] def parseString(configArg: String): Option[Config] =
    Try(ConfigFactory.parseString(configArg)) match {
      case Success(config) => Some(config)
      case Failure(ex) =>
        logger.debug(s"Unable to parse config from string $configArg")
        None
    }
}