/***********************************************************************
* Copyright (c) 2013-2015 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.accumulo.data.mapreduce

import com.typesafe.scalalogging.slf4j.Logging
import org.apache.accumulo.core.data.{Key, Value}
import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.mapreduce.{Mapper => HMapper}
import org.geotools.data._
import org.geotools.factory.Hints
import org.geotools.filter.identity.FeatureIdImpl
import org.locationtech.geomesa.accumulo.data.{AccumuloDataStore, AccumuloDataStoreFactory}
import org.locationtech.geomesa.utils.geotools.FeatureHandler
import org.locationtech.geomesa.utils.text.WKBUtils
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

object FeatureIngestMapper
       extends Logging {

  type Mapper = HMapper[LongWritable,Text,Key,Value]

  import org.locationtech.geomesa.accumulo._

  class FeatureIngestMapper extends Mapper {
    var featureType: SimpleFeatureType = null
    var fw: FeatureWriter[SimpleFeatureType, SimpleFeature] = null

    override def setup(context: Mapper#Context) {
      super.setup(context)

      val featureName = context.getConfiguration.get(DEFAULT_FEATURE_NAME)
      val ds = DataStoreFinder.getDataStore(
        AccumuloDataStoreFactory.getMRAccumuloConnectionParams(
          context.getConfiguration)).asInstanceOf[AccumuloDataStore]

      featureType = ds.getSchema(featureName)
      fw = ds.getFeatureWriterAppend(featureName, Transaction.AUTO_COMMIT)
    }

    override def map(key: LongWritable, value: Text, context: Mapper#Context) {
      val geom::encoded = value.toString.split(FeatureHandler.OUTPUT_FIELD_SEPARATOR_CHAR).toList

      try {
        // decode the simple-feature
        // ("mkString" is here to guard against strings broken by our separator)
        val simpleFeature = DataUtilities.createFeature(featureType,
          encoded.mkString(FeatureHandler.OUTPUT_FIELD_SEPARATOR))
        simpleFeature.setDefaultGeometry(WKBUtils.read(Base64.decode(geom)))
        simpleFeature.getUserData.put(Hints.USE_PROVIDED_FID, Boolean.box(x = true))

        val next = fw.next()
        // "Clone" the simpleFeature to write into the featureWriter's "next" and then write it to Accumulo.
        next.setAttributes(simpleFeature.getAttributes)
        next.getIdentifier.asInstanceOf[FeatureIdImpl].setID(simpleFeature.getID)

        fw.write()
      } catch {
        case e : Exception => logger.warn("[WARNING] Problem writing feature; skipping it.", e)
      }
    }
  }
}