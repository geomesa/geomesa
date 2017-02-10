package org.locationtech.geomesa.spark.hbase

import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.collections.map.CaseInsensitiveMap
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.client.{Mutation, Result}
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapreduce.{MultiTableInputFormat, TableInputFormat}
import org.apache.hadoop.io.Text
import org.apache.hadoop.mapreduce._
import org.geotools.data.DataStoreFinder
import org.geotools.filter.identity.FeatureIdImpl
import org.geotools.filter.text.ecql.ECQL
import org.geotools.process.vector.TransformProcess
import org.locationtech.geomesa.hbase.data.{HBaseDataStore, HBaseFeature}
import org.locationtech.geomesa.hbase.index.HBaseFeatureIndex
import org.locationtech.geomesa.index.api.GeoMesaFeatureIndex
import org.locationtech.geomesa.jobs.GeoMesaConfigurator
import org.locationtech.geomesa.utils.index.IndexMode
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter

import scala.collection.JavaConversions._

/**
  * Input format that allows processing of simple features from GeoMesa based on a CQL query
  */
class GeoMesaHBaseInputFormat extends InputFormat[Text, SimpleFeature] with LazyLogging {

  val delegate = new MultiTableInputFormat

  var sft: SimpleFeatureType = _
  var table: GeoMesaFeatureIndex[HBaseDataStore, HBaseFeature, Mutation] = _

  class HBaseGeoMesaRecordReader(sft: SimpleFeatureType,
                                 table: HBaseFeatureIndex,
                                 reader: RecordReader[ImmutableBytesWritable, Result],
                                 hasId: Boolean,
                                 filterOpt: Option[Filter],
                                 transformSchema: Option[SimpleFeatureType]
                                ) extends RecordReader[Text, SimpleFeature] {


    private def nextFeatureFromOptional(fn: Result => Option[SimpleFeature]) = () => {
      staged = null
      while(reader.nextKeyValue() && staged == null) {
        fn(reader.getCurrentValue) match {
          case Some(feature) =>
            staged = feature

          case None =>
            staged = null
        }
      }
    }

    private def nextFeatureFromDirect(fn: Result => SimpleFeature) = () => {
      staged = null
      while(reader.nextKeyValue() && staged == null) {
        staged = fn(reader.getCurrentValue)
      }
    }


    var staged: SimpleFeature = _
    private val nextFeature =
      (filterOpt, transformSchema) match {
        case (Some(filter), Some(ts)) =>
          val indices = ts.getAttributeDescriptors.map { ad => sft.indexOf(ad.getLocalName) }
          val fn = table.toFeaturesWithFilterTransform(sft, filter, Array.empty[TransformProcess.Definition], indices.toArray, ts)
          nextFeatureFromOptional(fn)

        case (Some(filter), None) =>
          val fn = table.toFeaturesWithFilter(sft, filter)
          nextFeatureFromOptional(fn)

        case (None, Some(ts))         =>
          val indices = ts.getAttributeDescriptors.map { ad => sft.indexOf(ad.getLocalName) }
          val fn = table.toFeaturesWithTransform(sft, Array.empty[TransformProcess.Definition], indices.toArray, ts)
          nextFeatureFromDirect(fn)

        case (None, None)         =>
          val fn = table.toFeaturesDirect(sft)
          nextFeatureFromDirect(fn)
      }

    private val getId = table.getIdFromRow(sft)

    override def initialize(split: InputSplit, context: TaskAttemptContext): Unit = {
      reader.initialize(split, context)
    }
    override def getProgress: Float = reader.getProgress

    override def nextKeyValue(): Boolean = nextKeyValueInternal()

    /**
      * Get the next key value from the underlying reader, incrementing the reader when required
      */
    private def nextKeyValueInternal(): Boolean = {
      nextFeature()
      if(staged != null) {
        if (!hasId) {
          val row = reader.getCurrentKey
          val offset = row.getOffset
          val length = row.getLength
          staged.getIdentifier.asInstanceOf[FeatureIdImpl].setID(getId(row.get(), offset, length))
        }
      }

      staged != null
    }

    override def getCurrentValue: SimpleFeature = staged

    override def getCurrentKey = new Text(staged.getID)

    override def close(): Unit = { reader.close() }
  }

  private def init(conf: Configuration) = if (sft == null) {
    import scala.collection.JavaConversions._

    val params = GeoMesaConfigurator.getDataStoreInParams(conf)
    val ds = DataStoreFinder.getDataStore(new CaseInsensitiveMap(params).asInstanceOf[java.util.Map[_, _]]).asInstanceOf[HBaseDataStore]
    sft = ds.getSchema(GeoMesaConfigurator.getFeatureType(conf))
    val tableName = GeoMesaConfigurator.getTable(conf)
    table = HBaseFeatureIndex.indices(sft, IndexMode.Read)
      .find(t => t.getTableName(sft.getTypeName, ds) == tableName)
      .getOrElse(throw new RuntimeException(s"Couldn't find input table $tableName"))

    delegate.setConf(conf)
    // see TableMapReduceUtil.java
    HBaseConfiguration.merge(conf, HBaseConfiguration.create(conf))
    conf.set(TableInputFormat.INPUT_TABLE, tableName)
  }


  /**
    * Gets splits for a job.
    */
  override def getSplits(context: JobContext): java.util.List[InputSplit] = {
    init(context.getConfiguration)
    val splits = delegate.getSplits(context)
    logger.debug(s"Got ${splits.size()} splits")
    splits
  }

  override def createRecordReader(split: InputSplit, context: TaskAttemptContext): RecordReader[Text, SimpleFeature] = {
    init(context.getConfiguration)
    val rr = delegate.createRecordReader(split, context)

    val transformSchema = GeoMesaConfigurator.getTransformSchema(context.getConfiguration)
    val q = GeoMesaConfigurator.getFilter(context.getConfiguration).map { f => ECQL.toFilter(f) }
    new HBaseGeoMesaRecordReader(sft, table.asInstanceOf[HBaseFeatureIndex], rr, true, q, transformSchema)
  }
}
