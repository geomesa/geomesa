/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.accumulo.data

import java.io.IOException
import java.util.Date

import com.google.common.collect.ImmutableSet
import com.vividsolutions.jts.geom.Geometry
import org.apache.accumulo.core.client.Connector
import org.apache.commons.codec.binary.Hex
import org.apache.hadoop.io.Text
import org.geotools.data._
import org.geotools.data.simple.SimpleFeatureStore
import org.geotools.factory.Hints
import org.geotools.feature.DefaultFeatureCollection
import org.geotools.filter.text.cql2.CQL
import org.geotools.filter.text.ecql.ECQL
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.locationtech.geomesa.accumulo.data.tables._
import org.locationtech.geomesa.accumulo.index._
import org.locationtech.geomesa.accumulo.iterators.Z2Iterator
import org.locationtech.geomesa.accumulo.util.SelfClosingIterator
import org.locationtech.geomesa.accumulo.{AccumuloVersion, TestWithMultipleSfts}
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.features.avro.AvroSimpleFeatureFactory
import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.text.WKTUtils
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType._

import scala.collection.JavaConversions._

@RunWith(classOf[JUnitRunner])
class AccumuloDataStoreTest extends Specification with TestWithMultipleSfts {

  sequential

  val defaultSpec = "name:String,geom:Point:srid=4326,dtg:Date"

  val defaultSft = createNewSchema(defaultSpec)
  val defaultTypeName = defaultSft.getTypeName

  addFeature(defaultSft, defaultPoint(defaultSft))
  addFeature(defaultSft, defaultPoint(defaultSft, id = "f2"))

  val defaultGeom = WKTUtils.read("POINT(45.0 49.0)")

  def defaultPoint(sft: SimpleFeatureType,
                   id: String = "f1",
                   name: String = "testType",
                   point: String = "POINT(45.0 49.0)"): SimpleFeature = {
    new ScalaSimpleFeature(id, sft, Array(name, WKTUtils.read(point), new Date(100000)))
  }

  "AccumuloDataStore" should {
    "create a store" in {
      ds must not(beNull)
    }

    "create a schema" in {
      ds.getSchema(defaultSft.getTypeName) mustEqual defaultSft
    }

    "create a schema with keywords" in {

      val sftWithKeywords: SimpleFeatureType = createNewSchema("name:String", dtgField = None)
      val keywords: Seq[String] = Seq("keywordA", "keywordB", "keywordC")

      sftWithKeywords.getUserData.put("geomesa.keywords", keywords.mkString(KEYWORDS_JOINER))// Put keywords in userData

      val spec = SimpleFeatureTypes.encodeType(sftWithKeywords, true)
      val newType = SimpleFeatureTypes.createType("keywordsTest", spec)
      ds.createSchema(newType)

      val fs = ds.getFeatureSource(newType.getTypeName)
      fs.getInfo.getKeywords.toSeq must containAllOf(keywords)
    }

    "create and retrieve a schema without a geometry" in {
      import org.locationtech.geomesa.utils.geotools.Conversions._

      val sft = createNewSchema("name:String", dtgField = None)

      val retrievedSft = ds.getSchema(sft.getTypeName)

      retrievedSft must not(beNull)
      retrievedSft.getAttributeCount mustEqual 1

      val f = new ScalaSimpleFeature("fid1", sft, Array("my name"))
      f.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
      val f2 = new ScalaSimpleFeature("replaceme", sft, Array("my other name"))

      val fs = ds.getFeatureSource(sft.getTypeName).asInstanceOf[SimpleFeatureStore]

      val fc = new DefaultFeatureCollection()
      fc.add(f)
      fc.add(f2)
      val ids = fs.addFeatures(fc)
      ids.map(_.getID).find(_ != "fid1").foreach(f2.getIdentifier.setID)

      fs.getFeatures(Filter.INCLUDE).features().toList must containTheSameElementsAs(List(f, f2))
      fs.getFeatures(ECQL.toFilter("IN('fid1')")).features().toList mustEqual List(f)
      fs.getFeatures(ECQL.toFilter("name = 'my name'")).features().toList mustEqual List(f)
      fs.getFeatures(ECQL.toFilter("name = 'my other name'")).features().toList mustEqual List(f2)
      fs.getFeatures(ECQL.toFilter("name = 'false'")).features().toList must beEmpty

      ds.removeSchema(sft.getTypeName)
      ds.getSchema(sft.getTypeName) must beNull
    }

    "return NULL when a feature name does not exist" in {
      ds.getSchema("testTypeThatDoesNotExist") must beNull
    }

    "return type names" in {
      ds.getTypeNames.toSeq must contain(defaultSft.getTypeName)
    }

    "provide ability to write using the feature source and read what it wrote" in {
      import org.locationtech.geomesa.utils.geotools.Conversions._

      // compose a CQL query that uses a reasonably-sized polygon for searching
      val cqlFilter = CQL.toFilter(s"BBOX(geom, 44.9,48.9,45.1,49.1)")
      val query = new Query(defaultTypeName, cqlFilter)

      // Let's read out what we wrote.
      val results = ds.getFeatureSource(defaultTypeName).getFeatures(query)
      val features = results.features.toList

      "results schema should match" >> { results.getSchema mustEqual defaultSft }
      "geometry should be set" >> { forall(features)(_.getDefaultGeometry mustEqual defaultGeom) }
      "result length should be 1" >> { features must haveLength(2) }
    }

    "create a schema with custom record splitting options with table sharing off" in {
      val spec = "name:String,dtg:Date,*geom:Point:srid=4326;table.splitter.class=" +
          s"${classOf[DigitSplitter].getName},table.splitter.options='fmt:%02d,min:0,max:99'"
      val sft = SimpleFeatureTypes.createType("customsplit", spec)
      sft.setTableSharing(false)
      ds.createSchema(sft)
      val recTable = ds.getTableName(sft.getTypeName, RecordTable)
      val splits = ds.connector.tableOperations().listSplits(recTable)
      splits.size() mustEqual 100
      splits.head mustEqual new Text("00")
      splits.last mustEqual new Text("99")
    }

    "create a schema with custom record splitting options with talbe sharing on" in {
      val spec = "name:String,dtg:Date,*geom:Point:srid=4326;table.splitter.class=" +
        s"${classOf[DigitSplitter].getName},table.splitter.options='fmt:%02d,min:0,max:99'"
      val sft = SimpleFeatureTypes.createType("customsplit2", spec)
      sft.setTableSharing(true)

      import scala.collection.JavaConversions._
      val prevsplits = ImmutableSet.copyOf(ds.connector.tableOperations().listSplits("AccumuloDataStoreTest_records").toIterable)
      ds.createSchema(sft)
      val recTable = ds.getTableName(sft.getTypeName, RecordTable)
      val afterSplits = ds.connector.tableOperations().listSplits(recTable)

      object TextOrdering extends Ordering[Text] {
        def compare(a: Text, b: Text) = a.compareTo(b)
      }
      val newSplits = (afterSplits.toSet -- prevsplits.toSet).toList.sorted(TextOrdering)
      val prefix = ds.getSchema(sft.getTypeName).getTableSharingPrefix
      newSplits.length mustEqual 100
      newSplits.head mustEqual new Text(s"${prefix}00")
      newSplits.last mustEqual new Text(s"${prefix}99")
    }

    "Prevent mixed geometries in spec" in {
      "throw an exception if geometry is specified" >> {
        createNewSchema("name:String,dtg:Date,*geom:Geometry:srid=4326") must throwA[IllegalArgumentException]
      }
      "allow for override" >> {
        val sft = createNewSchema("name:String,dtg:Date,*geom:Geometry:srid=4326;geomesa.mixed.geometries=true")
        sft.getGeometryDescriptor.getType.getBinding mustEqual classOf[Geometry]
      }
    }

    "allow for a configurable number of threads in z3 queries" in {
      val param = AccumuloDataStoreParams.queryThreadsParam.getName
      val query = new Query(defaultTypeName, ECQL.toFilter("bbox(geom,-75,-75,-60,-60) AND " +
          "dtg DURING 2010-05-07T00:00:00.000Z/2010-05-08T00:00:00.000Z"))

      def testThreads(numThreads: Int) = {
        val params = dsParams ++ Map(param -> numThreads)
        val dst = DataStoreFinder.getDataStore(params).asInstanceOf[AccumuloDataStore]
        val qpts = dst.getQueryPlan(query)
        forall(qpts) { qpt =>
          qpt.table mustEqual dst.getTableName(defaultTypeName, Z3Table)
          qpt.numThreads mustEqual numThreads
        }
      }

      forall(Seq(1, 5, 8, 20, 100))(testThreads)

      // check default
      val qpts = ds.getQueryPlan(query)
      forall(qpts) { qpt =>
        qpt.table mustEqual ds.getTableName(defaultTypeName, Z3Table)
        qpt.numThreads mustEqual 8
      }
    }

    "allow users to call explainQuery" in {
      val query = new Query(defaultTypeName, Filter.INCLUDE)
      val out = new ExplainString
      ds.getQueryPlan(new Query(defaultTypeName, Filter.INCLUDE), explainer = out)
      val explain = out.toString()
      explain must startWith(s"Planning '$defaultTypeName'")
    }

    "allow secondary attribute indexes" >> {
      val sft = createNewSchema("name:String:index=true,numattr:Integer,dtg:Date,*geom:Point:srid=4326")
      val sftName = sft.getTypeName

      "create all appropriate tables" >> {
        val tables = GeoMesaTable.getTableNames(sft, ds)
        tables must haveLength(4)
        forall(Seq(Z2Table, Z3Table, AttributeTable, AttributeTable))(t => tables must contain(endWith(t.suffix)))
        forall(tables)(t => ds.connector.tableOperations.exists(t) must beTrue)
      }

      val pt = WKTUtils.read("POINT (0 0)")
      val one = AvroSimpleFeatureFactory.buildAvroFeature(sft, Seq("one", new Integer(1), new DateTime(), pt), "1")
      val two = AvroSimpleFeatureFactory.buildAvroFeature(sft, Seq("two", new Integer(2), new DateTime(), pt), "2")

      val fs = ds.getFeatureSource(sftName).asInstanceOf[SimpleFeatureStore]
      fs.addFeatures(DataUtilities.collection(List(one, two)))

      // indexed attribute
      val q1 = ECQL.toFilter("name = 'one'")
      val fr = ds.getFeatureReader(new Query(sftName, q1), Transaction.AUTO_COMMIT)
      val results = SelfClosingIterator(fr).toList
      results must haveLength(1)
      results.head.getAttribute("name") mustEqual "one"

      // non-indexed attributes
      val q2 = ECQL.toFilter("numattr = 2")
      val fr2 = ds.getFeatureReader(new Query(sftName, q2), Transaction.AUTO_COMMIT)
      val results2 = SelfClosingIterator(fr2).toList
      results2 must haveLength(1)
      results2.head.getAttribute("numattr") mustEqual 2
    }

    "hex encode multibyte chars as multiple underscore + hex" in {
      // accumulo supports only alphanum + underscore aka ^\\w+$
      // this should end up hex encoded
      val sftName = "nihao你好"
      val sft = SimpleFeatureTypes.createType(sftName, s"name:String:index=true,dtg:Date,*geom:Point:srid=4326")
      sft.setTableSharing(false)
      ds.createSchema(sft)

      // encode groups of 2 hex chars since we are doing multibyte chars
      def enc(s: String): String = Hex.encodeHex(s.getBytes("UTF8")).grouped(2)
        .map{ c => "_" + c(0) + c(1) }.mkString.toLowerCase

      // three byte UTF8 chars result in 9 char string
      enc("你") must haveLength(9)
      enc("好") must haveLength(9)

      val encodedSFT = "nihao" + enc("你") + enc("好")
      encodedSFT mustEqual GeoMesaTable.hexEncodeNonAlphaNumeric(sftName)

      forall(GeoMesaTable.getTables(sft)) { table =>
        table.formatTableName(ds.catalogTable, sft) mustEqual s"${ds.catalogTable}_${encodedSFT}_${table.suffix}"
      }

      val c = ds.connector

      c.tableOperations().exists(ds.catalogTable) must beTrue
      forall(GeoMesaTable.getTables(sft)) { table =>
        c.tableOperations().exists(s"${ds.catalogTable}_${encodedSFT}_${table.suffix}") must beTrue
      }
    }

    "update metadata for indexed attributes" in {
      val originalSchema = "name:String,dtg:Date,*geom:Point:srid=4326:index=full:index-value=true"
      val updatedSchema = "name:String:index=join,dtg:Date,*geom:Point:srid=4326:index=full:index-value=true"

      val sft = createNewSchema(originalSchema)
      ds.updateIndexedAttributes(sft.getTypeName, updatedSchema)
      val retrievedSchema = SimpleFeatureTypes.encodeType(ds.getSchema(sft.getTypeName))
      retrievedSchema mustEqual updatedSchema
    }

    "prevent changing schema types" in {
      val originalSchema = "name:String,dtg:Date,*geom:Point:srid=4326"
      val sft = createNewSchema(originalSchema)
      val sftName = sft.getTypeName

      "prevent changing default geometry" in {
        val updatedSchema = "name:String,dtg:Date,geom:Point:srid=4326"
        ds.updateIndexedAttributes(sftName, updatedSchema) should throwA[IllegalArgumentException]
        val retrievedSchema = SimpleFeatureTypes.encodeType(ds.getSchema(sftName))
        retrievedSchema mustEqual originalSchema
      }
      "prevent changing attribute order" in {
        val updatedSchema = "dtg:Date,name:String,*geom:Point:srid=4326"
        ds.updateIndexedAttributes(sftName, updatedSchema) should throwA[IllegalArgumentException]
        val retrievedSchema = SimpleFeatureTypes.encodeType(ds.getSchema(sftName))
        retrievedSchema mustEqual originalSchema
      }
      "prevent adding attributes" in {
        val updatedSchema = "name:String,dtg:Date,*geom:Point:srid=4326,newField:String"
        ds.updateIndexedAttributes(sftName, updatedSchema) should throwA[IllegalArgumentException]
        val retrievedSchema = SimpleFeatureTypes.encodeType(ds.getSchema(sftName))
        retrievedSchema mustEqual originalSchema
      }
      "prevent removing attributes" in {
        val updatedSchema = "dtg:Date,*geom:Point:srid=4326"
        ds.updateIndexedAttributes(sftName, updatedSchema) should throwA[IllegalArgumentException]
        val retrievedSchema = SimpleFeatureTypes.encodeType(ds.getSchema(sftName))
        retrievedSchema mustEqual originalSchema
      }
    }

    "Provide a feature update implementation" in {
      val sft = createNewSchema("name:String,dtg:Date,*geom:Point:srid=4326")
      val sftName = sft.getTypeName

      addFeatures(sft, (0 until 6).map { i =>
        val sf = new ScalaSimpleFeature(i.toString, sft)
        sf.setAttributes(Array[AnyRef](i.toString, "2012-01-02T05:06:07.000Z", "POINT(45.0 45.0)"))
        sf
      })

      val filter = ECQL.toFilter("IN ('2')")
      val writer = ds.getFeatureWriter(sftName, filter, Transaction.AUTO_COMMIT)
      writer.hasNext must beTrue
      val feat = writer.next
      feat.getID mustEqual "2"
      feat.getAttribute("name") mustEqual "2"
      feat.setAttribute("name", "2-updated")
      writer.write()
      writer.hasNext must beFalse
      writer.close()

      val reader = ds.getFeatureReader(new Query(sftName, filter), Transaction.AUTO_COMMIT)
      reader.hasNext must beTrue
      val updated = reader.next()
      reader.hasNext must beFalse
      reader.close()
      updated.getID mustEqual "2"
      updated.getAttribute("name") mustEqual "2-updated"
    }

    "allow caching to be configured" in {
      DataStoreFinder.getDataStore(dsParams ++ Map("caching" -> false))
          .asInstanceOf[AccumuloDataStore].config.caching must beFalse
      DataStoreFinder.getDataStore(dsParams ++ Map("caching" -> true))
          .asInstanceOf[AccumuloDataStore].config.caching must beTrue
    }

    "not use caching by default with mocks" in {
      ds.config.caching must beFalse
    }

    "support caching for improved WFS performance due to count/getFeatures" in {
      val ds = DataStoreFinder.getDataStore(dsParams ++ Map("caching" -> true)).asInstanceOf[AccumuloDataStore]

      "typeOf feature source must be CachingAccumuloFeatureCollection" >> {
        val fc = ds.getFeatureSource(defaultTypeName).getFeatures(Filter.INCLUDE)
        fc must haveClass[CachingAccumuloFeatureCollection]
      }

      "suport getCount" >> {
        val query = new Query(defaultTypeName, Filter.INCLUDE)
        val fs = ds.getFeatureSource(defaultTypeName)
        val count = fs.getCount(query)
        count mustEqual 2
        val features = SelfClosingIterator(fs.getFeatures(query).features()).toList
        features must haveSize(2)
        features.map(_.getID) must containAllOf(Seq("f1", "f2"))
      }
    }

    "Project to date/geom" in {
      val sft = createNewSchema("name:String,dtg:Date,*geom:Point:srid=4326,attr2:String")
      val sftName = sft.getTypeName

      val features = (0 until 6).map { i =>
        val sf = new ScalaSimpleFeature(i.toString, sft)
        sf.setAttributes(Array[AnyRef](i.toString, s"2012-01-02T05:0$i:07.000Z", s"POINT(45.0 4$i.0)", s"2-$i"))
        sf
      }
      addFeatures(sft, features)

      val baseTime = features(0).getAttribute("dtg").asInstanceOf[Date].getTime

      val query = new Query(sftName, ECQL.toFilter("BBOX(geom, 40.0, 40.0, 50.0, 50.0)"), Array("geom", "dtg"))
      val reader = ds.getFeatureReader(query, Transaction.AUTO_COMMIT)

      val read = SelfClosingIterator(reader).toList

      // verify that all the attributes came back
      read must haveSize(6)
      read.sortBy(_.getAttribute("dtg").toString).zipWithIndex.foreach { case (sf, i) =>
        sf.getAttributeCount mustEqual 2
        sf.getAttribute("name") must beNull
        sf.getAttribute("geom") mustEqual WKTUtils.read(s"POINT(45.0 4$i.0)")
        sf.getAttribute("dtg").asInstanceOf[Date].getTime mustEqual baseTime + i * 60000
      }
      success
    }

    "create query plan that uses the Z2 iterator with simple bbox" in {
      val query = new Query(defaultTypeName, ECQL.toFilter("bbox(geom, -100, -45, 100, 45)"))
      val plans = ds.getQueryPlan(query)
      forall(plans) { plan =>
        plan.iterators.map(_.getIteratorClass) must containTheSameElementsAs(Seq(classOf[Z2Iterator].getName))
      }
    }

    "create key plan that does not use any iterators when given the Whole World bbox" in {
      val query = new Query(defaultTypeName, ECQL.toFilter("bbox(geom, -180, -90, 180, 90)"))
      val plans = ds.getQueryPlan(query)
      plans must haveLength(1)
      plans.head.iterators must beEmpty
    }

    "create key plan that does not use STII when given something larger than the Whole World bbox" in {
      val query = new Query(defaultTypeName, ECQL.toFilter("bbox(geom, -190, -100, 190, 100)"), Array("geom"))
      val explain = {
        val o = new ExplainString
        ds.getQueryPlan(query, explainer = o)
        o.toString()
      }
      explain.split("\n").map(_.trim).filter(_.startsWith("Strategy filter:")).toSeq mustEqual
          Seq("Strategy filter: Z2[INCLUDE][None]")
    }

    "create key plan that does not use STII when given an or'd geometry query with redundant bbox" in {
      val query = new Query(defaultTypeName, ECQL.toFilter("bbox(geom, -180, -90, 180, 90) OR bbox(geom, -10, -10, 10, 10)"))
      val plans = ds.getQueryPlan(query)
      plans must haveLength(1)
      plans.head.iterators must beEmpty
    }

    "create key plan that does not use STII when given two bboxes that when unioned are the whole world" in {
      // Todo: https://geomesa.atlassian.net/browse/GEOMESA-785
      val query = new Query(defaultTypeName, ECQL.toFilter("bbox(geom, -180, -90, 0, 90) OR bbox(geom, 0, -90, 180, 90)"))
      val plans = ds.getQueryPlan(query)
      plans must haveLength(1)
      plans.head.iterators must beEmpty
    }.pendingUntilFixed("Fixed query planner to deal with OR'd whole world geometry")

    "transform index value data correctly" in {
      import org.locationtech.geomesa.utils.geotools.GeoToolsDateFormat

      val sft = createNewSchema("trackId:String:index-value=true,label:String:index-value=true," +
          "extraValue:String,score:Double:index-value=true,dtg:Date,geom:Point:srid=4326")
      val sftName = sft.getTypeName

      addFeatures(sft, (0 until 5).map { i =>
        val sf = new ScalaSimpleFeature(s"f$i", sft)
        sf.setAttributes(Array[AnyRef](s"trk$i", s"label$i", "extra", s"$i", s"2014-01-01T0$i:00:00.000Z", s"POINT(5$i 50)"))
        sf
      })

      "with out of order attributes" >> {
        val query = new Query(sftName, ECQL.toFilter("bbox(geom,49,49,60,60)"), Array("geom", "dtg", "label"))
        val features =
          SelfClosingIterator(ds.getFeatureSource(sftName).getFeatures(query).features).toList.sortBy(_.getID)
        features must haveSize(5)
        (0 until 5).foreach { i =>
          features(i).getID mustEqual s"f$i"
          features(i).getAttributeCount mustEqual 3
          features(i).getAttribute("label") mustEqual s"label$i"
          features(i).getAttribute("dtg") mustEqual GeoToolsDateFormat.parseDateTime(s"2014-01-01T0$i:00:00.000Z").toDate
          features(i).getAttribute("geom") mustEqual WKTUtils.read(s"POINT(5$i 50)")
        }
        success
      }

      "with only date and geom" >> {
        val query = new Query(sftName, ECQL.toFilter("bbox(geom,49,49,60,60)"), Array("geom", "dtg"))
        val features =
          SelfClosingIterator(ds.getFeatureSource(sftName).getFeatures(query).features).toList.sortBy(_.getID)
        features must haveSize(5)
        (0 until 5).foreach { i =>
          features(i).getID mustEqual s"f$i"
          features(i).getAttributeCount mustEqual 2
          features(i).getAttribute("dtg") mustEqual GeoToolsDateFormat.parseDateTime(s"2014-01-01T0$i:00:00.000Z").toDate
          features(i).getAttribute("geom") mustEqual WKTUtils.read(s"POINT(5$i 50)")
        }
        success
      }

      "with all attributes" >> {
        val query = new Query(sftName, ECQL.toFilter("bbox(geom,49,49,60,60)"),
          Array("geom", "dtg", "label", "score", "trackId"))
        val features =
          SelfClosingIterator(ds.getFeatureSource(sftName).getFeatures(query).features).toList.sortBy(_.getID)
        features must haveSize(5)
        (0 until 5).foreach { i =>
          features(i).getID mustEqual s"f$i"
          features(i).getAttributeCount mustEqual 5
          features(i).getAttribute("label") mustEqual s"label$i"
          features(i).getAttribute("trackId") mustEqual s"trk$i"
          features(i).getAttribute("score") mustEqual i.toDouble
          features(i).getAttribute("dtg") mustEqual GeoToolsDateFormat.parseDateTime(s"2014-01-01T0$i:00:00.000Z").toDate
          features(i).getAttribute("geom") mustEqual WKTUtils.read(s"POINT(5$i 50)")
        }
        success
      }
    }

    "delete all associated tables" >> {
      val catalog = "AccumuloDataStoreDeleteTest"
      // note the table needs to be different to prevent testing errors
      val ds = DataStoreFinder.getDataStore(dsParams ++ Map("tableName" -> catalog)).asInstanceOf[AccumuloDataStore]
      val sft = SimpleFeatureTypes.createType(catalog, "name:String:index=true,dtg:Date,*geom:Point:srid=4326")
      ds.createSchema(sft)
      val tables = GeoMesaTable.getTableNames(sft, ds) ++ Seq(catalog)
      tables must haveSize(5)
      connector.tableOperations().list().toSeq must containAllOf(tables)
      ds.delete()
      connector.tableOperations().list().toSeq must not(containAnyOf(tables))
    }

    "query on bbox and unbounded temporal" >> {
      val sft = createNewSchema("name:String,dtg:Date,*geom:Point:srid=4326")

      addFeatures(sft, (0 until 6).map { i =>
        val sf = new ScalaSimpleFeature(i.toString, sft)
        sf.setAttributes(Array[AnyRef](i.toString, s"2012-01-02T05:0$i:07.000Z", s"POINT(45.0 4$i.0)"))
        sf
      })

      val query = new Query(sft.getTypeName,
        ECQL.toFilter("BBOX(geom, 40.0, 40.0, 50.0, 44.5) AND dtg after 2012-01-02T05:02:00.000Z"))
      val reader = ds.getFeatureReader(query, Transaction.AUTO_COMMIT)

      val read = SelfClosingIterator(reader).toList

      // verify that all the attributes came back
      read must haveSize(3)
      read.map(_.getID) must containAllOf(Seq("2", "3", "4"))
    }

    "create tables with an accumulo namespace" >> {
      val table = "test.AccumuloDataStoreNamespaceTest"
      val params = Map("connector" -> ds.connector, "tableName" -> table)
      val dsWithNs = DataStoreFinder.getDataStore(params).asInstanceOf[AccumuloDataStore]
      val sft = SimpleFeatureTypes.createType("test", "*geom:Point:srid=4326")
      if (AccumuloVersion.accumuloVersion == AccumuloVersion.V15) {
        dsWithNs.createSchema(sft) must throwAn[IllegalArgumentException]
      } else {
        dsWithNs.createSchema(sft)
        val nsOps = classOf[Connector].getMethod("namespaceOperations").invoke(dsWithNs.connector)
        AccumuloVersion.nameSpaceExists(nsOps, nsOps.getClass, "test") must beTrue
      }
    }

    "only create catalog table when necessary" >> {
      val table = "AccumuloDataStoreTableTest"
      val params = Map("connector" -> this.ds.connector, "tableName" -> table)
      val ds = DataStoreFinder.getDataStore(params).asInstanceOf[AccumuloDataStore]
      ds must not(beNull)
      def exists = ds.connector.tableOperations().exists(table)
      exists must beFalse
      ds.getTypeNames must beEmpty
      exists must beFalse
      ds.getSchema("test") must beNull
      exists must beFalse
      ds.getFeatureReader(new Query("test"), Transaction.AUTO_COMMIT) must throwAn[IOException]
      exists must beFalse
      ds.createSchema(SimpleFeatureTypes.createType("test", "*geom:Point:srid=4326"))
      exists must beTrue
      ds.getSchema("test") must not(beNull)
    }
  }
}
