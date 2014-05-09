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


package geomesa.core.data

import geomesa.core.index._
import java.util.UUID
import org.apache.accumulo.core.client.{BatchWriterConfig, Connector}
import org.apache.accumulo.core.data.{PartialKey, Mutation, Value, Key}
import org.apache.hadoop.mapred.{Reporter, RecordWriter}
import org.apache.hadoop.mapreduce.TaskInputOutputContext
import org.apache.log4j.Logger
import org.geotools.data.DataUtilities
import org.geotools.data.simple.SimpleFeatureWriter
import org.geotools.factory.Hints
import org.geotools.feature.simple.SimpleFeatureBuilder
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

object AccumuloFeatureWriter {

  type AccumuloRecordWriter = RecordWriter[Key,Value]

  val EMPTY_VALUE = new Value()

  class LocalRecordWriter(tableName: String, connector: Connector) extends AccumuloRecordWriter {
    private val bw = connector.createBatchWriter(tableName, new BatchWriterConfig())

    def write(key: Key, value: Value) {
      val m = new Mutation(key.getRow)
      m.put(key.getColumnFamily, key.getColumnQualifier, key.getColumnVisibilityParsed, key.getTimestamp, value)
      bw.addMutation(m)
    }

    def close(reporter: Reporter) {
      bw.flush()
      bw.close()
    }
  }

  class LocalRecordDeleter(tableName: String, connector: Connector) extends AccumuloRecordWriter {
    private val bw = connector.createBatchWriter(tableName, new BatchWriterConfig())

    def write(key: Key, value: Value) {
      val m = new Mutation(key.getRow)
      m.putDelete(key.getColumnFamily, key.getColumnQualifier, key.getColumnVisibilityParsed, key.getTimestamp)
      bw.addMutation(m)
    }

    def close(reporter: Reporter) {
      bw.flush()
      bw.close()
    }
  }

  class MapReduceRecordWriter(context: TaskInputOutputContext[_,_,Key,Value]) extends AccumuloRecordWriter {
    def write(key: Key, value: Value) {
      context.write(key, value)
    }

    def close(reporter: Reporter) {}
  }
}

abstract class AccumuloFeatureWriter(featureType: SimpleFeatureType,
                                     indexer: SpatioTemporalIndexSchema,
                                     recordWriter: RecordWriter[Key,Value]) extends SimpleFeatureWriter {

  private val log = Logger.getLogger(classOf[AccumuloFeatureWriter])

  def getFeatureType: SimpleFeatureType = featureType

  /* Return a String representing nextId - use UUID.random for universal uniqueness across multiple ingest nodes */
  protected def nextFeatureId = UUID.randomUUID().toString

  val builder = new SimpleFeatureBuilder(featureType)

  protected def writeToAccumulo(feature: SimpleFeature) = {
    // see if there's a suggested ID to use for this feature
    // (relevant when this insertion is wrapped inside a Transaction)
    val toWrite =
      if(feature.getUserData.containsKey(Hints.PROVIDED_FID)) {
        builder.init(feature)
        builder.buildFeature(feature.getUserData.get(Hints.PROVIDED_FID).toString)
      }
      else feature

    // require non-null geometry to write to geomesa (can't index null geo yo!)
    val kvPairsToWrite =
      if (toWrite.getDefaultGeometry != null) indexer.encode(toWrite)
      else {
        log.warn("Invalid feature to write:  " + DataUtilities.encodeFeature(toWrite))
        List()
      }
    kvPairsToWrite.foreach { case (k,v) => recordWriter.write(k,v) }
  }

  def close = recordWriter.close(null)

  def remove() {}

  def hasNext: Boolean = false
}



class AppendAccumuloFeatureWriter(featureType: SimpleFeatureType,
                                  indexer: SpatioTemporalIndexSchema,
                                  recordWriter: RecordWriter[Key,Value])
  extends AccumuloFeatureWriter(featureType, indexer, recordWriter) {

  var currentFeature: SimpleFeature = null

  def write() {
    if (currentFeature != null) writeToAccumulo(currentFeature)
    currentFeature = null
  }

  def next(): SimpleFeature = {
    currentFeature = SimpleFeatureBuilder.template(featureType, nextFeatureId)
    currentFeature
  }

}

class ModifyAccumuloFeatureWriter(featureType: SimpleFeatureType,
                                      indexer: SpatioTemporalIndexSchema,
                                      recordWriter: RecordWriter[Key,Value],
                                      deleter: RecordWriter[Key, Value],
                                      dataStore: AccumuloDataStore)
  extends AccumuloFeatureWriter(featureType, indexer, recordWriter) {

  val reader = dataStore.getFeatureReader(featureType.getName.toString)
  var live: SimpleFeature = null      /* feature to let user modify   */
  var original: SimpleFeature = null  /* feature returned from reader */

  override def remove = if (original != null) indexer.encode(original).foreach { case (k,v) => deleter.write(k,v) }

  override def hasNext = reader.hasNext

  /* only write if non null and it hasn't changed...*/
  /* original should be null only when reader runs out */
  override def write =
    if(!live.equals(original)) {  // This depends on having the same SimpleFeature concrete class
      if(original != null) keysToDelete.foreach { k => deleter.write(k, EMPTY_VALUE)}
      writeToAccumulo(live)
    }

  /* Delete keys from original index and data entries that are different from new keys */
  /* Return list of old keys that should be deleted */
  def keysToDelete = {
    val oldKeys = indexer.encode(original).map { case (k,v) => k }
    val newKeys = indexer.encode(live).map { case (k,v) => k }
    oldKeys.zip(newKeys).filter { case(o, n) =>
      !o.equals(n, PartialKey.ROW_COLFAM_COLQUAL_COLVIS) }.map{ case (k1, k2) => k1
    }
  }

  override def next: SimpleFeature = {
    original = null
    live =
      if(hasNext) {
        original = reader.next
        builder.init(original)
        builder.buildFeature(original.getID)
      } else {
        SimpleFeatureBuilder.template(featureType, nextFeatureId)
      }
    live
  }

  override def close = {
    super.close() //closes writer
    deleter.close(null)
    reader.close
  }

}