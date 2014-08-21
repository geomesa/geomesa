/*
 * Copyright 2013 Commonwealth Computer Research, Inc.
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


package org.locationtech.geomesa.plugin.wfs

import org.apache.wicket.ResourceReference
import org.apache.wicket.markup.html.form.validation.IFormValidator
import org.apache.wicket.markup.html.form.{Form, FormComponent}
import org.apache.wicket.markup.html.image.Image
import org.apache.wicket.model.PropertyModel
import org.geoserver.web.GeoServerBasePage
import org.geotools.data.DataAccessFactory.Param
import org.locationtech.geomesa.plugin.GeoMesaStoreEditPanel

class AccumuloDataStoreEditPanel (componentId: String, storeEditForm: Form[_])
    extends GeoMesaStoreEditPanel(componentId, storeEditForm) {

  val model = storeEditForm.getModel
  setDefaultModel(model)
  val paramsModel = new PropertyModel(model, "connectionParameters")

  val instanceId = addTextPanel(paramsModel, new Param("instanceId", classOf[String], "The Accumulo Instance ID", true))
  val zookeepers = addTextPanel(paramsModel, new Param("zookeepers", classOf[String], "Zookeepers", true))
  val user = addTextPanel(paramsModel, new Param("user", classOf[String], "User", true))
  val password = addPasswordPanel(paramsModel, new Param("password", classOf[String], "Password", true))
  val auths = addTextPanel(paramsModel, new Param("auths", classOf[String], "DataStore-level Authorizations", false))
  val visibilities = addTextPanel(paramsModel, new Param("visibilities", classOf[String], "Accumulo visibilities that will be applied to data written by this DataStore", false))
  val tableName = addTextPanel(paramsModel, new Param("tableName", classOf[String], "The Accumulo Table Name", true))

  val collectStats = addTextPanel(paramsModel, new Param("collectStats", classOf[String], "Set to 'false' to disable collection of statistics", false))
  val writeThreads = addTextPanel(paramsModel, new Param("writeThreads", classOf[String], "Default number of threads used to write data", false))
  val queryThreads = addTextPanel(paramsModel, new Param("queryThreads", classOf[String], "Default number of threads used to query data", false))
  val recordThreads = addTextPanel(paramsModel, new Param("recordThreads", classOf[String], "Default number of threads used to retrieve records", false))

  val dependentFormComponents = Array[FormComponent[_]](instanceId,
                                                        zookeepers,
                                                        user,
                                                        password,
                                                        tableName,
                                                        auths,
                                                        visibilities,
                                                        collectStats,
                                                        writeThreads,
                                                        queryThreads,
                                                        recordThreads)
  dependentFormComponents.foreach(_.setOutputMarkupId(true))

  storeEditForm.add(new IFormValidator() {
    override def getDependentFormComponents = dependentFormComponents

    override def validate(form: Form[_]) {}
  })

  val open = new ResourceReference(classOf[GeoServerBasePage], "img/icons/silk/bullet_arrow_down.png")
  val closed = new ResourceReference(classOf[GeoServerBasePage], "img/icons/silk/bullet_arrow_right.png")
  add(new Image("toggleOpen", open))
  add(new Image("toggleClosed", closed))
}
