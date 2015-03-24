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

package org.locationtech.geomesa.jobs.mapred

import java.io.{DataInput, DataOutput}
import java.lang.Float.isNaN

import com.typesafe.scalalogging.slf4j.Logging
import org.apache.accumulo.core.client.mapred.{AccumuloInputFormat, InputFormatBase, RangeInputSplit}
import org.apache.accumulo.core.client.security.tokens.PasswordToken
import org.apache.accumulo.core.data.{Key, Value}
import org.apache.accumulo.core.security.Authorizations
import org.apache.accumulo.core.util.{Pair => AccPair}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.{Text, Writable}
import org.apache.hadoop.mapred._
import org.geotools.data.{DataStoreFinder, Query}
import org.geotools.filter.identity.FeatureIdImpl
import org.geotools.filter.text.ecql.ECQL
import org.locationtech.geomesa.core.data.{AccumuloDataStore, AccumuloDataStoreFactory}
import org.locationtech.geomesa.core.index._
import org.locationtech.geomesa.feature.FeatureEncoding.FeatureEncoding
import org.locationtech.geomesa.feature.{ScalaSimpleFeature, SimpleFeatureDecoder}
import org.locationtech.geomesa.jobs.GeoMesaConfigurator
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter

import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

object GeoMesaInputFormat extends Logging {

  /**
   * Configure the input format.
   *
   * This is a single method, as we have to calculate several things to pass to the underlying
   * AccumuloInputFormat, and there is not a good hook to indicate when the config is finished.
   */
  def configure(job: JobConf,
                dsParams: Map[String, String],
                featureTypeName: String,
                filter: Option[String]): Unit = {

    val ds = DataStoreFinder.getDataStore(dsParams).asInstanceOf[AccumuloDataStore]

    assert(ds != null, "Invalid data store parameters")

    // set up the underlying accumulo input format
    val user = AccumuloDataStoreFactory.params.userParam.lookUp(dsParams).asInstanceOf[String]
    val password = AccumuloDataStoreFactory.params.passwordParam.lookUp(dsParams).asInstanceOf[String]
    InputFormatBase.setConnectorInfo(job, user, new PasswordToken(password.getBytes()))

    val instance = AccumuloDataStoreFactory.params.instanceIdParam.lookUp(dsParams).asInstanceOf[String]
    val zookeepers = AccumuloDataStoreFactory.params.zookeepersParam.lookUp(dsParams).asInstanceOf[String]
    InputFormatBase.setZooKeeperInstance(job, instance, zookeepers)

    val auths = Option(AccumuloDataStoreFactory.params.authsParam.lookUp(dsParams).asInstanceOf[String])
    auths.foreach(a => InputFormatBase.setScanAuthorizations(job, new Authorizations(a.split(","): _*)))

    // run an explain query to set up the iterators, ranges, etc
    val ecql = filter.map(ECQL.toFilter).getOrElse(Filter.INCLUDE)
    val query = new Query(featureTypeName, ecql)
    val queryPlans = ds.explainQuery(featureTypeName, query, ExplainNull)

    // see if the plan is something we can execute from a single table
    val tryPlan = if (queryPlans.length > 1) None else queryPlans.headOption.filter {
      case qp: JoinPlan => false
      case _ => true
    }

    val queryPlan = tryPlan.getOrElse {
      // this query has a join or requires multiple scans - instead, fall back to the ST index
      logger.warn("Desired query plan requires multiple scans - falling back to spatio-temporal scan")
      val sft = ds.getSchema(featureTypeName)
      val featureEncoding = ds.getFeatureEncoding(sft)
      val indexSchema = ds.getIndexSchemaFmt(featureTypeName)
      val hints = ds.strategyHints(sft)
      val version = ds.getGeomesaVersion(sft)
      val queryPlanner = new QueryPlanner(sft, featureEncoding, indexSchema, ds, hints, version)
      new STIdxStrategy().getQueryPlan(query, queryPlanner, ExplainNull)
    }

    // use the explain results to set the accumulo input format options
    InputFormatBase.setInputTableName(job, queryPlan.table)
    if (!queryPlan.ranges.isEmpty) {
      InputFormatBase.setRanges(job, queryPlan.ranges)
    }
    if (!queryPlan.columnFamilies.isEmpty) {
      InputFormatBase.fetchColumns(job, queryPlan.columnFamilies.map(cf => new AccPair[Text, Text](cf, null)))
    }
    queryPlan.iterators.foreach(InputFormatBase.addIterator(job, _))

    // auto adjust ranges - this ensures that each split created will have a single location, which we want
    // for the GeoMesaInputFormat below
    InputFormatBase.setAutoAdjustRanges(job, true)

    // also set the datastore parameters so we can access them later
    GeoMesaConfigurator.setDataStoreInParams(job, dsParams)
    GeoMesaConfigurator.setFeatureType(job, featureTypeName)
    filter.foreach(GeoMesaConfigurator.setFilter(job, _))
  }
}

/**
 * Input format that allows processing of simple features from GeoMesa based on a CQL query
 */
class GeoMesaInputFormat extends InputFormat[Text, SimpleFeature] {

  val delegate = new AccumuloInputFormat

  var sft: SimpleFeatureType = null
  var encoding: FeatureEncoding = null
  var numShards: Int = -1

  private def init(conf: Configuration) = if (sft == null) {
    val params = GeoMesaConfigurator.getDataStoreInParams(conf)
    val ds = DataStoreFinder.getDataStore(params).asInstanceOf[AccumuloDataStore]
    sft = ds.getSchema(GeoMesaConfigurator.getFeatureType(conf))
    encoding = ds.getFeatureEncoding(sft)
    numShards = IndexSchema.maxShard(ds.getIndexSchemaFmt(sft.getTypeName))
  }

  /**
   * Gets splits for a job.
   *
   * Our delegated AccumuloInputFormat creates a split for each range - because we set a lot of ranges in
   * geomesa, that creates too many mappers. Instead, we try to group the ranges by tservers. We use the
   * number of shards in the schema as a proxy for number of tservers. We also know that each range will
   * only have a single location, because we always set autoAdjustRanges in the configure method above.
   *
   * The numSplits hint that gets passed in tends to be too low, so we disregard it.
   */
  override def getSplits(job: JobConf, numSplits: Int) = {
    init(job)
    val accumuloSplits = delegate.getSplits(job, numSplits)
    // try to create 2 mappers per node - account for case where there are less splits than shards
    val groupSize = Math.max(numShards * 2, accumuloSplits.length / (numShards * 2))

    // We know each range will only have a single location because of autoAdjustRanges
    val splits = accumuloSplits.groupBy(_.getLocations()(0)).flatMap { case (location, splits) =>
      splits.grouped(groupSize).map { group =>
        val split = new GroupedSplit()
        split.location = location
        split.splits.append(group.map(_.asInstanceOf[RangeInputSplit]): _*)
        split
      }
    }
    splits.toArray
  }

  override def getRecordReader(split: InputSplit, job: JobConf, reporter: Reporter) = {
    init(job)
    val splits = split.asInstanceOf[GroupedSplit].splits
    // we need to use an iterator to use delayed execution on the delegate record readers,
    // otherwise this kills memory
    val readers = splits.iterator.map(delegate.getRecordReader(_, job, reporter))
    val decoder = SimpleFeatureDecoder(sft, encoding)
    new GeoMesaRecordReader(sft, decoder, readers, splits.length)
  }
}

/**
 * Record reader that delegates to accumulo record readers and transforms the key/values coming back into
 * simple features.
 */
class GeoMesaRecordReader(sft: SimpleFeatureType,
                          decoder: SimpleFeatureDecoder,
                          readers: Iterator[RecordReader[Key, Value]],
                          numReaders: Int) extends RecordReader[Text, SimpleFeature] {

  import org.locationtech.geomesa.utils.geotools.RichIterator.RichIterator

  var readersProgress = 0f
  var currentReader: Option[RecordReader[Key, Value]] = None
  val delegateKey = new Key()
  val delegateValue = new Value()
  var pos = 0L

  nextReader()

  /**
   * Advances to the next reader delegate
   */
  private[this] def nextReader() = {
    // increment our running progress and pos with the last reader
    readersProgress = readersProgress + 1f / numReaders
    pos = pos + currentReader.map(_.getPos).getOrElse(0L)
    currentReader = readers.headOption
  }

  /**
   * Get the next key value from the underlying reader, incrementing the reader when required
   */
  @tailrec
  private def nextInternal(): Boolean =
    currentReader match {
      case None => false
      case Some(reader) =>
        if (reader.next(delegateKey, delegateValue)) {
          true
        } else {
          nextReader()
          nextInternal()
        }
    }

  override def next(key: Text, value: SimpleFeature) =
    if (nextInternal()) {
      val sf = decoder.decode(delegateValue.get())
      // copy the decoded sf into the reused one passed in to this method
      key.set(sf.getID)
      value.getIdentifier.asInstanceOf[FeatureIdImpl].setID(sf.getID) // value will be a ScalaSimpleFeature
      value.setAttributes(sf.getAttributes)
      value.getUserData.clear()
      value.getUserData.putAll(sf.getUserData)
      true
    } else {
      false
    }

  override def getProgress =
    readersProgress + currentReader.map(_.getProgress / numReaders).filterNot(isNaN).getOrElse(0f)

  override def getPos = pos + currentReader.map(_.getPos).getOrElse(0L)

  override def createKey() = new Text()

  override def createValue() = new ScalaSimpleFeature("", sft)

  override def close() = {} // delegate Accumulo readers have a no-op close
}

/**
 * Input split that groups a series of RangeInputSplits. Has to implement Hadoop Writable, thus the vars and
 * mutable state.
 */
class GroupedSplit extends InputSplit with Writable {

  var location: String = null
  var splits: ArrayBuffer[RangeInputSplit] = ArrayBuffer.empty

  override def getLength = splits.foldLeft(0L)((l: Long, r: RangeInputSplit) => l + r.getLength)

  override def getLocations = if (location == null) Array.empty else Array(location)

  override def write(out: DataOutput) = {
    out.writeUTF(location)
    out.writeInt(splits.length)
    splits.foreach(_.write(out))
  }

  override def readFields(in: DataInput) = {
    location = in.readUTF()
    splits.clear()
    var i = 0
    val size = in.readInt()
    while (i < size) {
      val split = new RangeInputSplit()
      split.readFields(in)
      splits.append(split)
      i = i + 1
    }
  }

  override def toString = s"mapred.GroupedSplit[$location](${splits.length})"
}