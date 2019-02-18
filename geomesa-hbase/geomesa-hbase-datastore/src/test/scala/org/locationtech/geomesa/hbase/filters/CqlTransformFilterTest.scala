/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.hbase.filters

import com.typesafe.scalalogging.LazyLogging
import org.apache.hadoop.hbase.Cell
import org.apache.hadoop.hbase.client.Put
import org.apache.hadoop.hbase.filter.Filter.ReturnCode
import org.geotools.data._
import org.junit.runner.RunWith
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.features.kryo.KryoFeatureSerializer
import org.locationtech.geomesa.hbase.data.HBaseFeature
import org.locationtech.geomesa.hbase.index.HBaseZ2Index
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class CqlTransformFilterTest extends Specification with LazyLogging {

  import scala.collection.JavaConverters._

  val sft = SimpleFeatureTypes.createType("CqlTransformFilterTest", "name:String,*geom:Point:srid=4326")

  // serialized filters are:
  //  CqlFilter[BBOX(geom, -55.0,45.0,-45.0,55.0)]
  //  TransformFilter[name=name]
  //  CqlTransformFilter[BBOX(geom, -55.0,45.0,-45.0,55.0), name=name]

  val serialized = Seq(
    "AAAAIW5hbWU6U3RyaW5nLCpnZW9tOlBvaW50OnNyaWQ9NDMyNgAAACFCQk9YKGdlb20sIC01NS4wLDQ1LjAsLTQ1LjAsNTUuMCn/////",
    "AAAAIW5hbWU6U3RyaW5nLCpnZW9tOlBvaW50OnNyaWQ9NDMyNgAAAAAAAAAJbmFtZT1uYW1lAAAAC25hbWU6U3RyaW5n",
    "AAAAIW5hbWU6U3RyaW5nLCpnZW9tOlBvaW50OnNyaWQ9NDMyNgAAACFCQk9YKGdlb20sIC01NS4wLDQ1LjAsLTQ1LjAsNTUuMCkAAAAJbmFtZT1uYW1lAAAAC25hbWU6U3RyaW5n"
  )

  "CqlTransformFilter" should {
    "deserialize filters without an index" in {
      val tsft = SimpleFeatureTypes.createType("", "name:String")
      val serializer = KryoFeatureSerializer.builder(tsft).withoutId.build()
      val wrapper = HBaseFeature.wrapper(sft)
      val converter = HBaseZ2Index.writer(sft, null)
      val features = Seq(
        ScalaSimpleFeature.create(sft, "0", "name0", "POINT (-50 50)"),
        ScalaSimpleFeature.create(sft, "1", "name1", "POINT (-60 60)")
      )
      val mutations = features.flatMap(f => converter.apply(wrapper.apply(f)).asInstanceOf[Seq[Put]])
      val cells = mutations.flatMap(m => m.getFamilyCellMap.asScala.values.map(_.asScala)).flatten
      cells must haveLength(2)

      val filter = CqlTransformFilter.parseFrom(Base64.decode(serialized.head))
      val transform = CqlTransformFilter.parseFrom(Base64.decode(serialized(1)))
      val filterTransform = CqlTransformFilter.parseFrom(Base64.decode(serialized(2)))

      def getAttributes(cell: Cell): Seq[AnyRef] =
        serializer.deserialize(cell.getValueArray, cell.getValueOffset, cell.getValueLength).getAttributes.asScala

      filter.filterKeyValue(cells.head) mustEqual ReturnCode.INCLUDE
      filter.transformCell(cells.head) mustEqual cells.head
      filter.filterKeyValue(cells.last) mustEqual ReturnCode.SKIP

      transform.filterKeyValue(cells.head) mustEqual ReturnCode.INCLUDE
      getAttributes(transform.transformCell(cells.head)) mustEqual Seq("name0")
      transform.filterKeyValue(cells.last) mustEqual ReturnCode.INCLUDE
      getAttributes(transform.transformCell(cells.last)) mustEqual Seq("name1")

      filterTransform.filterKeyValue(cells.head) mustEqual ReturnCode.INCLUDE
      getAttributes(filterTransform.transformCell(cells.head)) mustEqual Seq("name0")
      filterTransform.filterKeyValue(cells.last) mustEqual ReturnCode.SKIP
    }
  }
}
