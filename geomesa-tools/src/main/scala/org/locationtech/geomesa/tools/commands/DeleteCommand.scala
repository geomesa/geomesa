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

import com.beust.jcommander.{JCommander, Parameter, Parameters}
import com.typesafe.scalalogging.slf4j.Logging
import org.locationtech.geomesa.tools.DataStoreHelper
import org.locationtech.geomesa.tools.commands.DeleteCommand._

class DeleteCommand(parent: JCommander) extends Command with Logging {

  val params = new DeleteParams
  parent.addCommand(Command, params)

  override def execute() = {
    val feature = params.featureName
    val catalog = params.catalog

    if (params.forceDelete || promptConfirm(feature, catalog)) {
      logger.info(s"Deleting '$catalog:$feature'")
      try {
        val ds = new DataStoreHelper(params).ds
        ds.removeSchema(feature)
        if (!ds.getNames.contains(feature)) {
          println(s"Deleted $catalog:$feature")
        } else {
          logger.info(s"There was an error deleting feature '$catalog:$feature'" +
            "Please check that all arguments are correct in the previous command.")
        }
      } catch {
        case e: Exception =>
          logger.error(s"Error deleting feature '$catalog:$feature': "+e.getMessage, e)
      }
    } else {
      logger.info(s"Cancelled deletion of feature '$catalog:$feature'")
    }

  }

}

object DeleteCommand {
  val Command = "delete"

  def promptConfirm(feature: String, catalog: String) =
    if (System.console() != null) {
      print(s"Delete '$feature' from catalog table '$catalog'? (yes/no): ")
      System.console().readLine().toLowerCase().trim == "yes"
    } else {
      throw new IllegalStateException("Unable to confirm feature deletion via console..." +
        "Please ensure stdout is not redirected or --force flag is set")
    }

  @Parameters(commandDescription = "Delete a feature's data and definition from a GeoMesa catalog")
  class DeleteParams extends FeatureParams {
    @Parameter(names = Array("-f", "--force"), description = "Force deletion of feature without prompt", required = false)
    var forceDelete: Boolean = false
  }
}
