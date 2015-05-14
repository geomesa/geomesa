/*
 * Copyright 2015 Commonwealth Computer Research, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package org.locationtech.geomesa.kafka

import org.opengis.feature.simple.SimpleFeature
import org.specs2.matcher.Matcher
import org.specs2.mutable.Specification

trait SimpleFeatureMatchers extends Specification {

  def containFeatures(sf: SimpleFeature*): Matcher[Seq[SimpleFeature]] =
    contain(exactly(sf.map(equalSF) : _*))

  def containGeoMessages(sfs: Seq[GeoMessage]): Matcher[Seq[GeoMessage]] =
    contain(exactly(sfs.map(equalGeoMessage) : _*))


  def containSF(expected: SimpleFeature): Matcher[Seq[SimpleFeature]] = {
    val matcher = equalSF(expected)

    seq: Seq[SimpleFeature] => seq.exists(matcher.test)
  }

  def equalSF(expected: SimpleFeature): Matcher[SimpleFeature] = {
    sf: SimpleFeature => {
      sf.getID mustEqual expected.getID
      sf.getDefaultGeometry mustEqual expected.getDefaultGeometry
      sf.getAttributes mustEqual expected.getAttributes
      sf.getUserData mustEqual expected.getUserData
    }
  }
  
  def equalGeoMessage(expected: GeoMessage): Matcher[GeoMessage] = expected match {
    case _: Delete => equalTo(expected)
    case _: Clear => equalTo(expected)
    case CreateOrUpdate(ts, sf) => actual: GeoMessage => {
      actual must beAnInstanceOf[CreateOrUpdate]
      actual.timestamp mustEqual ts
      actual.asInstanceOf[CreateOrUpdate].feature must equalSF(sf)
    }
  }
}
