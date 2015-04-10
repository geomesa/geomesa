/*
 * Copyright 2014 Commonwealth Computer Research, Inc.
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

package org.locationtech.geomesa.utils.geotools

import com.vividsolutions.jts.geom.Geometry
import org.junit.runner.RunWith
import org.locationtech.geomesa.utils.security.SecurityUtils
import org.opengis.feature.simple.SimpleFeature
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.collection.immutable.HashMap

@RunWith(classOf[JUnitRunner])
class ConversionsTest extends Specification with Mockito {
sequential
  "ScalaCollectionsConverterFactory" should {
    val factory = new ScalaCollectionsConverterFactory

    "create a converter between" >> {

      "list interfaces" >> {
        "seq and list" >> {
          val converter = factory.createConverter(classOf[Seq[_]], classOf[java.util.List[_]], null)
          converter must not beNull;
          converter must beAnInstanceOf[ListToListConverter]
        }
        "list and sequence" >> {
          val converter = factory.createConverter(classOf[java.util.List[_]], classOf[Seq[_]], null)
          converter must not beNull;
          converter must beAnInstanceOf[ListToListConverter]
        }
      }

      "list subclasses" >> {
        "list and java list" >> {
          val converter = factory.createConverter(classOf[List[_]], classOf[java.util.List[_]], null)
          converter must not beNull;
          converter must beAnInstanceOf[ListToListConverter]
        }
        "java list and list" >> {
          val converter = factory.createConverter(classOf[java.util.List[_]], classOf[List[_]], null)
          converter must not beNull;
          converter must beAnInstanceOf[ListToListConverter]
        }
        "array list and sequence" >> {
          val converter = factory.createConverter(classOf[java.util.ArrayList[_]], classOf[Seq[_]], null)
          converter must not beNull;
          converter must beAnInstanceOf[ListToListConverter]
        }
        "sequence and array list" >> {
          val converter = factory.createConverter(classOf[Seq[_]], classOf[java.util.ArrayList[_]], null)
          converter must not beNull;
          converter must beAnInstanceOf[ListToListConverter]
        }
      }

      "map interfaces" >> {
        "map and java map" >> {
          val converter = factory.createConverter(classOf[Map[_, _]], classOf[java.util.Map[_, _]], null)
          converter must not beNull;
          converter must beAnInstanceOf[MapToMapConverter]
        }
        "java map and map" >> {
          val converter = factory.createConverter(classOf[java.util.Map[_, _]], classOf[Map[_, _]], null)
          converter must not beNull;
          converter must beAnInstanceOf[MapToMapConverter]
        }
      }

      "map subclasses" >> {
        "map and java hashmap" >> {
          val converter = factory.createConverter(classOf[Map[_, _]], classOf[java.util.HashMap[_, _]], null)
          converter must not beNull;
          converter must beAnInstanceOf[MapToMapConverter]
        }
        "java hashmap and map" >> {
          val converter = factory.createConverter(classOf[java.util.HashMap[_, _]], classOf[Map[_, _]], null)
          converter must not beNull;
          converter must beAnInstanceOf[MapToMapConverter]
        }
        "java map and hashmap" >> {
          val converter = factory.createConverter(classOf[java.util.Map[_, _]], classOf[HashMap[_, _]], null)
          converter must not beNull;
          converter must beAnInstanceOf[MapToMapConverter]
        }
        "hashmap and java map" >> {
          val converter = factory.createConverter(classOf[HashMap[_, _]], classOf[java.util.Map[_, _]], null)
          converter must not beNull;
          converter must beAnInstanceOf[MapToMapConverter]
        }
      }
    }

    "return null for unhandled class types" >> {
      val converter = factory.createConverter(classOf[String], classOf[Int], null)
      converter must beNull
    }
  }


  "RichSimpleFeature" should {

    import Conversions.RichSimpleFeature

    val sf = mock[SimpleFeature]

    "support implicit conversion" >> {
      val rsf: RichSimpleFeature = sf
      success
    }

    "be able to access visibility" >> {
      "when not set" >> {
        val userData: java.util.Map[AnyRef, AnyRef] = java.util.Collections.emptyMap()
        sf.getUserData returns userData

        sf.visibility mustEqual None
      }

      "when set" >> {
        val userData: java.util.Map[AnyRef, AnyRef] = java.util.Collections.singletonMap(SecurityUtils.FEATURE_VISIBILITY, "vis")
        sf.getUserData returns userData

        sf.visibility mustEqual Some("vis")
      }
    }

    "be able to set visibility" >> {
      val userData = new java.util.HashMap[AnyRef, AnyRef]
      sf.getUserData returns userData

      sf.visibility = "vis"
      userData.size() mustEqual 1
      userData.get(SecurityUtils.FEATURE_VISIBILITY) mustEqual "vis"
    }

    "be able to clear visibility" >> {
      val userData = new java.util.HashMap[AnyRef, AnyRef]
      sf.getUserData returns userData
      sf.visibility = "vis"

      sf.visibility = None
      userData.size() mustEqual 1
      userData.get(SecurityUtils.FEATURE_VISIBILITY) must beNull
      sf.visibility mustEqual None
    }

    "be able to access default geometry" >> {
      val geo = mock[Geometry]
      sf.getDefaultGeometry returns geo

      sf.geometry mustEqual geo
    }

    "throw exception if defaultgeometry is not a Geometry" >> {
      sf.getDefaultGeometry returns "not a Geometry!"

      sf.geometry must throwA[ClassCastException]
    }
  }
}
