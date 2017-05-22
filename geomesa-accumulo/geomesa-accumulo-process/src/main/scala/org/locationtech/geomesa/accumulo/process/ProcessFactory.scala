/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.accumulo.process

import org.geotools.process.factory.AnnotatedBeanProcessFactory
import org.geotools.text.Text
import org.locationtech.geomesa.accumulo.process.knn.KNearestNeighborSearchProcess
import org.locationtech.geomesa.accumulo.process.proximity.ProximitySearchProcess
import org.locationtech.geomesa.accumulo.process.query.QueryProcess
import org.locationtech.geomesa.accumulo.process.stats.StatsIteratorProcess
import org.locationtech.geomesa.accumulo.process.tube.TubeSelectProcess
import org.locationtech.geomesa.accumulo.process.unique.UniqueProcess

class ProcessFactory
  extends AnnotatedBeanProcessFactory(
    Text.text("GeoMesa Process Factory"),
    "geomesa",
    classOf[KNearestNeighborSearchProcess],
    classOf[ProximitySearchProcess],
    classOf[QueryProcess],
    classOf[StatsIteratorProcess],
    classOf[TubeSelectProcess],
    classOf[UniqueProcess],
//    classOf[ArrowConversionProcess],
    classOf[BinConversionProcess],
    classOf[JoinProcess],
    classOf[RouteSearchProcess],
    classOf[SamplingProcess]
  )
