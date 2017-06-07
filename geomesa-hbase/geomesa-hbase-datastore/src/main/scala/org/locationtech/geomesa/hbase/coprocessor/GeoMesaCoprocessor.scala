/***********************************************************************
 * Copyright (c) 2013-2017 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.hbase.coprocessor

import java.io._

import com.google.protobuf.{ByteString, RpcCallback, RpcController, Service}
import org.apache.hadoop.hbase.client.coprocessor.Batch.Call
import org.apache.hadoop.hbase.client.{Scan, Table}
import org.apache.hadoop.hbase.coprocessor.{CoprocessorException, CoprocessorService, RegionCoprocessorEnvironment}
import org.apache.hadoop.hbase.exceptions.DeserializationException
import org.apache.hadoop.hbase.filter.{FilterList, Filter => HFilter}
import org.apache.hadoop.hbase.ipc.BlockingRpcCallback
import org.apache.hadoop.hbase.protobuf.ResponseConverter
import org.apache.hadoop.hbase.{Cell, Coprocessor, CoprocessorEnvironment}
import org.locationtech.geomesa.features.SerializationOption.SerializationOptions
import org.locationtech.geomesa.features.kryo.KryoFeatureSerializer
import org.locationtech.geomesa.hbase.coprocessor.GeoMesaCoprocessor._
import org.locationtech.geomesa.hbase.coprocessor.aggregators.GeoMesaHBaseAggregator
import org.locationtech.geomesa.hbase.coprocessor.utils.{GeoMesaHBaseCallBack, GeoMesaHBaseRpcController}
import org.locationtech.geomesa.hbase.proto.GeoMesaProto
import org.locationtech.geomesa.hbase.proto.GeoMesaProto.{GeoMesaCoprocessorRequest, GeoMesaCoprocessorResponse, GeoMesaCoprocessorService}
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.io.CloseQuietly

import scala.annotation.tailrec
import scala.collection.JavaConversions._

class GeoMesaCoprocessor extends GeoMesaCoprocessorService with Coprocessor with CoprocessorService {

  private var env: RegionCoprocessorEnvironment = _

  @throws[IOException]
  def start(env: CoprocessorEnvironment): Unit = {
    env match {
      case e: RegionCoprocessorEnvironment => this.env = e
      case _ => throw new CoprocessorException("Must be loaded on a table region!")
    }
  }

  @throws[IOException]
  def stop(coprocessorEnvironment: CoprocessorEnvironment): Unit = {
  }

  def getService: Service = this

  def getResult(controller: RpcController,
                 request: GeoMesaProto.GeoMesaCoprocessorRequest,
                 done: RpcCallback[GeoMesaProto.GeoMesaCoprocessorResponse]): Unit = {
    val options: Map[String, String] = deserializeOptions(request.getOptions.toByteArray)
    val aggregator = getAggregator(options)

    val scanList: List[Scan] = getScanFromOptions(options)
    val filterList: FilterList = getFilterListFromOptions(options)

    val sft = SimpleFeatureTypes.createType("input", options(SFT_OPT))
    val serializer = new KryoFeatureSerializer(sft, SerializationOptions.withoutId)

    val response: GeoMesaCoprocessorResponse = try {

      scanList.foreach(scan => {
        scan.setFilter(filterList)
        // TODO: Explore use of MultiRangeFilter
        val scanner = env.getRegion.getScanner(scan)

        try {
          val results = new java.util.ArrayList[Cell]

          def processResults() = {
            for (cell <- results) {
              val sf = serializer.deserialize(cell.getValueArray, cell.getValueOffset, cell.getValueLength)
              aggregator.aggregate(sf)
            }
            results.clear()
          }

          @tailrec
          def readScanner(): Unit = {
            if(scanner.next(results)) {
              processResults()
              readScanner()
            } else {
              processResults()
            }
          }

          readScanner()
        } catch {
          case t: Throwable => throw t
        } finally CloseQuietly(scanner)
      })

      val result: Array[Byte] = aggregator.encodeResult()
      GeoMesaCoprocessorResponse.newBuilder.setSf(ByteString.copyFrom(result)).build
    } catch {
      case ioe: IOException =>
        ResponseConverter.setControllerException(controller, ioe)
        null
      case cnfe: ClassNotFoundException =>
        throw cnfe
      case dse: DeserializationException =>
        throw dse
    }

    done.run(response)
  }
}

object GeoMesaCoprocessor {
  /**
    * It gives the combined result of pairs received from different region servers value of a column for a given column family for the
    * given range. In case qualifier is null, a min of all values for the given
    * family is returned.
    *
    * @param table
    * @return HashMap result;
    * @throws Throwable
    */
  def execute(table: Table, options: Array[Byte]): List[ByteString] = {
    val requestArg: GeoMesaCoprocessorRequest = GeoMesaCoprocessorRequest.newBuilder().setOptions(ByteString.copyFrom(options)).build()

    val callBack: GeoMesaHBaseCallBack = new GeoMesaHBaseCallBack()

    table.coprocessorService(classOf[GeoMesaCoprocessorService], null, null, new Call[GeoMesaCoprocessorService, ByteString]() {
      override def call(instance: GeoMesaCoprocessorService): ByteString = {
        val controller: RpcController = new GeoMesaHBaseRpcController()
        val rpcCallback: BlockingRpcCallback[GeoMesaCoprocessorResponse] =
          new BlockingRpcCallback[GeoMesaCoprocessorResponse]()
        instance.getResult(controller, requestArg, rpcCallback)
        val response: GeoMesaCoprocessorResponse = rpcCallback.get
        if (controller.failed()) {
          throw new IOException(controller.errorText())
        }
        response.getSf
      }
    },
      callBack
    )
    callBack.getResult
  }

  def getAggregator(options: Map[String, String]): GeoMesaHBaseAggregator = {
    val classname = options(GeoMesaHBaseAggregator.AGGREGATOR_CLASS)
    val agg: GeoMesaHBaseAggregator = Class.forName(classname).newInstance().asInstanceOf[GeoMesaHBaseAggregator]
    agg.init(options)
    agg
  }
}
