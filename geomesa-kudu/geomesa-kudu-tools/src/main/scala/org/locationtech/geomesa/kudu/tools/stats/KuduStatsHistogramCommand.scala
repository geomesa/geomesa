/***********************************************************************
 * Copyright (c) 2013-2020 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.kudu.tools.stats

import com.beust.jcommander.Parameters
import org.locationtech.geomesa.kudu.data.KuduDataStore
import org.locationtech.geomesa.kudu.tools.KuduDataStoreCommand
import org.locationtech.geomesa.kudu.tools.KuduDataStoreCommand.KuduParams
import org.locationtech.geomesa.kudu.tools.stats.KuduStatsHistogramCommand.KuduStatsHistogramParams
import org.locationtech.geomesa.tools.RequiredTypeNameParam
import org.locationtech.geomesa.tools.stats.StatsHistogramCommand
import org.locationtech.geomesa.tools.stats.StatsHistogramCommand.StatsHistogramParams

class KuduStatsHistogramCommand extends StatsHistogramCommand[KuduDataStore] with KuduDataStoreCommand {
  override val params = new KuduStatsHistogramParams
}

object KuduStatsHistogramCommand {
  @Parameters(commandDescription = "View or calculate counts of attribute in a GeoMesa feature type, grouped by sorted values")
  class KuduStatsHistogramParams extends StatsHistogramParams with KuduParams with RequiredTypeNameParam
}
