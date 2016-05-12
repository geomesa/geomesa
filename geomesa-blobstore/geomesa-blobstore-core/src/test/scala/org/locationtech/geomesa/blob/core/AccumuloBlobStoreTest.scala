/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.blob.core

import java.io.File

import com.google.common.io.{ByteStreams, Files}
import org.geotools.data.DataStoreFinder
import org.geotools.filter.text.ecql.ECQL
import org.junit.runner.RunWith
import org.locationtech.geomesa.accumulo.data.AccumuloDataStore
import org.opengis.filter._
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.collection.JavaConversions._

@RunWith(classOf[JUnitRunner])
class AccumuloBlobStoreTest extends Specification {
  val dsParams = Map(
    "instanceId"        -> "mycloud",
    "zookeepers"        -> "zoo1:2181,zoo2:2181,zoo3:2181",
    "user"              -> "myuser",
    "password"          -> "mypassword",
    "tableName"         -> "geomesa",
    "useMock"           -> "true")
  val ds = DataStoreFinder.getDataStore(dsParams).asInstanceOf[AccumuloDataStore]

  val bstore = new AccumuloBlobStore(ds)

  sequential

  val testfile1 = "testFile.txt"
  val testfile2 = "testFile2.txt"
  val testfile3 = "testFile3.txt"
  var testFile1Id = ""

  "AccumuloBlobStore" should {

    "be able to store and delete a file" in {
      val (storeId, file) = ingestFile(testfile3, "POINT(10 10)")

      bstore.delete(storeId)

      // test if blobstore get is failing to return anything
      val ret = bstore.get(storeId)
      ret.getKey mustEqual ""
      ret.getValue must beEmpty

      // test if geomesa feature table is empty
      val ids = bstore.getIds(Filter.INCLUDE).toList
      ids must beEmpty
    }

    "be able to store and retrieve a file" in {
      val (storeId, file) = ingestFile(testfile1, "POINT(0 0)")

      val ret = bstore.get(storeId)

      val inputStream = ByteStreams.toByteArray(Files.newInputStreamSupplier(file))

      ret.getKey mustEqual testfile1
      inputStream mustEqual ret.getValue
    }

    "query for ids and then retrieve a file" in {
      // Since the test is sequential, the first file should be in the store.
      val ids = bstore.getIds(Filter.INCLUDE).toList

      ids.size mustEqual 1
      val id = ids.head

      val ret = bstore.get(id)

      ret.getValue must not be null
      ret.getKey mustEqual testfile1
    }

    "insert a second file and then be able to query for both" in {
      val (storeId2, _) = ingestFile(testfile2, "POINT(50 50)")

      val ids2 = bstore.getIds(Filter.INCLUDE).toList
      ids2.size mustEqual 2

      val ret = bstore.get(storeId2)

      ret.getKey mustEqual testfile2

      val filter = ECQL.toFilter("BBOX(geom, -10,-10,10,10)")

      val filteredIds = bstore.getIds(filter).toList

      filteredIds.size mustEqual 1
    }
  }

  def ingestFile(fileName: String, wkt: String): (String, File) = {
    val file = new File(getClass.getClassLoader.getResource(fileName).getFile)
    val params = Map("wkt" -> wkt)
    val storeId = bstore.put(file, params)
    (storeId, file)
  }
}
