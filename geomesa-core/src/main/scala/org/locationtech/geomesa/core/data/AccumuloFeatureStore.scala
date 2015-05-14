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

package org.locationtech.geomesa.core.data

import java.util.{List => JList}

import com.google.common.collect.Lists
import com.vividsolutions.jts.geom.Geometry
import org.geotools.data._
import org.geotools.factory.Hints
import org.geotools.feature._
import org.geotools.feature.simple.SimpleFeatureTypeBuilder
import org.geotools.filter.FunctionExpressionImpl
import org.geotools.filter.identity.FeatureIdImpl
import org.geotools.geometry.jts.ReferencedEnvelope
import org.geotools.process.vector.TransformProcess.Definition
import org.locationtech.geomesa.utils.geotools.MinMaxTimeVisitor
import org.opengis.feature.GeometryAttribute
import org.opengis.feature.`type`.{AttributeDescriptor, GeometryDescriptor, Name}
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.expression.PropertyName
import org.opengis.filter.identity.FeatureId

class AccumuloFeatureStore(val dataStore: AccumuloDataStore, val featureName: Name)
    extends AbstractFeatureStore with AccumuloAbstractFeatureSource {
  override def addFeatures(collection: FeatureCollection[SimpleFeatureType, SimpleFeature]): JList[FeatureId] = {
    val fids = Lists.newArrayList[FeatureId]()
    if (collection.size > 0) {
      writeBounds(collection.getBounds)
      writeTimeBounds(collection)

      val fw = dataStore.getFeatureWriterAppend(featureName.getLocalPart, Transaction.AUTO_COMMIT)

      val iter = collection.features()
      while(iter.hasNext) {
        val feature = iter.next()
        val newFeature = fw.next()

        try {
          newFeature.setAttributes(feature.getAttributes)
          newFeature.getUserData.putAll(feature.getUserData)
        } catch {
          case ex: Exception =>
            throw new DataSourceException(s"Could not create ${featureName.getLocalPart} out of provided feature: ${feature.getID}", ex)
        }

        val useExisting = java.lang.Boolean.TRUE.equals(feature.getUserData.get(Hints.USE_PROVIDED_FID).asInstanceOf[java.lang.Boolean])
        if (getQueryCapabilities().isUseProvidedFIDSupported && useExisting) {
          newFeature.getIdentifier.asInstanceOf[FeatureIdImpl].setID(feature.getID)
        }

        fw.write()
        fids.add(newFeature.getIdentifier)
      }
      fw.close()
    }
    fids
  }

  def updateTimeBounds(collection: FeatureCollection[SimpleFeatureType, SimpleFeature]) = {
    val sft = collection.getSchema
    val dateField = org.locationtech.geomesa.core.index.getDtgFieldName(sft)

    dateField.flatMap { dtg =>
      val minMax = new MinMaxTimeVisitor(dtg)
      collection.accepts(minMax, null)
      Option(minMax.getBounds)
    }
  }

  def writeTimeBounds(collection: FeatureCollection[SimpleFeatureType, SimpleFeature]) {
    updateTimeBounds(collection).foreach { dataStore.writeTemporalBounds(featureName.getLocalPart, _) }
  }

  def writeBounds(envelope: ReferencedEnvelope) {
    if(envelope != null)
      dataStore.writeSpatialBounds(featureName.getLocalPart, envelope)
  }
}

object MapReduceAccumuloFeatureStore {
  val MAPRED_CLASSPATH_USER_PRECEDENCE_KEY = "mapreduce.task.classpath.user.precedence"
}

object AccumuloFeatureStore {

  def computeSchema(origSFT: SimpleFeatureType, transforms: Seq[Definition]): SimpleFeatureType = {
    val attributes: Seq[AttributeDescriptor] = transforms.map { definition =>
      val name = definition.name
      val cql  = definition.expression
      cql match {
        case p: PropertyName =>
          val origAttr = origSFT.getDescriptor(p.getPropertyName)
          val ab = new AttributeTypeBuilder()
          ab.init(origAttr)
          val descriptor = if (origAttr.isInstanceOf[GeometryDescriptor]) {
            ab.buildDescriptor(name, ab.buildGeometryType())
          } else {
            ab.buildDescriptor(name, ab.buildType())
          }
          descriptor.getUserData.putAll(origAttr.getUserData)
          descriptor

        case f: FunctionExpressionImpl  =>
          val clazz = f.getFunctionName.getReturn.getType
          val ab = new AttributeTypeBuilder().binding(clazz)
          if(classOf[Geometry].isAssignableFrom(clazz))
            ab.buildDescriptor(name, ab.buildGeometryType())
          else
            ab.buildDescriptor(name, ab.buildType())

      }
    }

    val geomAttributes = attributes.filter { _.isInstanceOf[GeometryAttribute] }
    val sftBuilder = new SimpleFeatureTypeBuilder()
    sftBuilder.setName(origSFT.getName)
    sftBuilder.addAll(attributes.toArray)
    if(geomAttributes.size > 0) {
      val defaultGeom =
        if(geomAttributes.size == 1) geomAttributes.head.getLocalName
        else {
          // try to find a geom with the same name as the original default geom
          val origDefaultGeom = origSFT.getGeometryDescriptor.getLocalName
          geomAttributes.find(_.getLocalName.equals(origDefaultGeom))
            .map(_.getLocalName)
            .getOrElse(geomAttributes.head.getLocalName)
        }
      sftBuilder.setDefaultGeometry(defaultGeom)
    }
    val schema = sftBuilder.buildFeatureType()
    schema.getUserData.putAll(origSFT.getUserData)
    schema
  }
}