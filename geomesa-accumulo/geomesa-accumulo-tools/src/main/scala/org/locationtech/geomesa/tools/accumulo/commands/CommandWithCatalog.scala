/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.tools.accumulo.commands

import com.beust.jcommander.JCommander
import org.geotools.data.DataStoreFinder
import org.locationtech.geomesa.accumulo.data.{AccumuloDataStore, AccumuloDataStoreParams}
import org.locationtech.geomesa.tools.accumulo.GeoMesaConnectionParams
import org.locationtech.geomesa.tools.common.commands.Command

/**
 * Abstract class for commands that have a pre-existing catalog
 */
abstract class CommandWithCatalog(parent: JCommander) extends Command(parent) {
  override val params: GeoMesaConnectionParams
  lazy val ds = DataStoreParamsHelper.createDataStore(params)
  lazy val catalog = params.catalog
}

object DataStoreParamsHelper {
  def getDataStoreParams(params: GeoMesaConnectionParams): Map[String, String] = {
    Map[String, String](
      AccumuloDataStoreParams.instanceIdParam.getName -> params.instance,
      AccumuloDataStoreParams.zookeepersParam.getName -> params.zookeepers,
      AccumuloDataStoreParams.userParam.getName       -> params.user,
      AccumuloDataStoreParams.passwordParam.getName   -> params.password,
      AccumuloDataStoreParams.tableNameParam.getName  -> params.catalog,
      AccumuloDataStoreParams.visibilityParam.getName -> params.visibilities,
      AccumuloDataStoreParams.authsParam.getName      -> params.auths,
      AccumuloDataStoreParams.mockParam.getName       -> params.useMock.toString).filter(_._2 != null)
  }

  /**
    * Get a handle to a datastore for a pre-existing catalog table
    *
    * @throws Exception if the catalog table does not exist in accumulo
    */
  def createDataStore(params: GeoMesaConnectionParams): AccumuloDataStore = {
    val dataStoreParams = getDataStoreParams(params)
    import scala.collection.JavaConversions._
    Option(DataStoreFinder.getDataStore(dataStoreParams).asInstanceOf[AccumuloDataStore]).getOrElse {
      throw new IllegalArgumentException("Could not load a data store with the provided parameters: " +
        dataStoreParams.map { case (k,v) => s"$k=$v" }.mkString(","))
    }
  }
}
