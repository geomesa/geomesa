/***********************************************************************
* Copyright (c) 2013-2015 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.jobs.index

import com.twitter.scalding._
import org.apache.accumulo.core.data.{Range => AcRange}
import org.geotools.data.DataStoreFinder
import org.locationtech.geomesa.accumulo.data._
import org.locationtech.geomesa.jobs.GeoMesaBaseJob
import org.locationtech.geomesa.jobs.scalding.ConnectionParams._
import org.locationtech.geomesa.jobs.scalding._

import scala.collection.JavaConverters._

/**
 * Class to copy a schema and all data from one data store to another.
 *
 * Can be used to 'update' geomesa data from older versions. It does this by reading data in the old format
 * and writing it to a new schema which will use the latest format. This way, improvements in serialization,
 * etc can be leveraged for old data.
 */
class SchemaCopyJob(args: Args) extends GeoMesaBaseJob(args) {

  val feature = args(FEATURE_IN)
  val dsInParams = toDataStoreInParams(args)
  val dsOutParams = toDataStoreOutParams(args)

  val input = GeoMesaInputOptions(dsInParams, feature)
  val output = GeoMesaOutputOptions(dsOutParams)

  {
    // validation
    val dsIn = DataStoreFinder.getDataStore(dsInParams.asJava).asInstanceOf[AccumuloDataStore]
    assert(dsIn != null, "The specified input data store could not be created - check your job parameters")
    val dsOut = DataStoreFinder.getDataStore(dsOutParams.asJava).asInstanceOf[AccumuloDataStore]
    assert(dsOut != null, "The specified output data store could not be created - check your job parameters")
    val sft = dsIn.getSchema(feature)
    assert(sft != null, s"The feature '$feature' does not exist in the input data store")
    // create the schema in the output datastore if it does not exist already
    dsOut.createSchema(sft)
  }

  // scalding job
  GeoMesaSource(input).write(GeoMesaSource(output))
}


