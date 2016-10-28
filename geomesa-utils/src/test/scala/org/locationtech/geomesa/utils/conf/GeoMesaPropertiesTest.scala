/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.utils.conf

import org.junit.runner.RunWith
import org.locationtech.geomesa.utils.conf.GeoMesaProperties.PropOrDefault
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import com.typesafe.scalalogging.LazyLogging

@RunWith(classOf[JUnitRunner])
class GeoMesaPropertiesTest extends Specification with LazyLogging {

  sequential

  val TEST_PROP_1 = "test.system.properties.1"
  val TEST_PROP_2 = "test.system.properties.2"
  val TEST_PROP_3 = "test.system.properties.3"
  val REAL_PROP = "geomesa.stats.compact.millis"
  val REAL_PROP_VAL = "3600000"

  def testProp1 = PropOrDefault(TEST_PROP_1)
  def testProp2 = PropOrDefault(TEST_PROP_2, "default")
  // This is loaded from embedded config
  def realProp = PropOrDefault(REAL_PROP)

  "props" should {
    "contain system properties" in {
      GeoMesaProperties.ProjectVersion must not beNull;
      GeoMesaProperties.BuildDate must not beNull;
      GeoMesaProperties.GitCommit must not beNull;
      GeoMesaProperties.GitBranch must not beNull;
    }
  }

  "PropOrDefault" should {
    "return proper values" in {
      testProp1.default must beNull
      testProp1.get must beNull
      testProp1.option must beEqualTo(None)

      testProp2.set("test")
      testProp2.get must beEqualTo("test")
      testProp2.option must beEqualTo(Option("test"))
      testProp2.clear()
      testProp2.get must beEqualTo("default")

      realProp.default must beEqualTo(REAL_PROP_VAL)
      realProp.get must beEqualTo(REAL_PROP_VAL)
      realProp.option must beEqualTo(Option(REAL_PROP_VAL))
    }
  }

  "getProperty" should {
    "return null when property is empty" in {
      GeoMesaProperties.getProperty(TEST_PROP_3) must beNull
    }

    "return proper values" in {
      System.setProperty(TEST_PROP_3, "test")
      GeoMesaProperties.getProperty(TEST_PROP_3) must beEqualTo("test")
    }
  }
}
