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

import java.net.InetSocketAddress

import com.typesafe.scalalogging.slf4j.Logging
import kafka.server.KafkaConfig
import kafka.utils.{TestUtils, Utils}
import org.apache.zookeeper.server.{NIOServerCnxnFactory, ZooKeeperServer}

trait HasEmbeddedZookeeper {
  val zkConnect = EmbeddedZookeeper.connect()
  def shutdown(): Unit = EmbeddedZookeeper.shutdown()
}

trait HasEmbeddedKafka {
  val (brokerConnect, zkConnect) = EmbeddedKafka.connect()
  def shutdown(): Unit = EmbeddedKafka.shutdown()
}

trait EmbeddedServiceManager[S <: EmbeddedService[C], C] extends Logging {

  private var count = 0
  private var service: S = null.asInstanceOf[S]

  def connect(): C = synchronized {
    count += 1
    if (count == 1) {
      logger.debug("Establishing connection")
      service = establishConnection()
      logger.debug("Connection established")
    }

    logger.debug("connect() complete - there are now {} tests using this service", count.asInstanceOf[AnyRef])
    service.connection
  }

  def shutdown(): Unit = synchronized {
    count -= 1
    if (count == 0) {
      logger.debug("Beginning shutdown")
      service.shutdown()
      logger.debug("Shutdown complete")
      service = null.asInstanceOf[S]
    }

    logger.info("shutdown() complete - there are now {} tests using this service", count.asInstanceOf[AnyRef])
  }

  protected def establishConnection(): S
}

object EmbeddedZookeeper extends EmbeddedServiceManager[EmbeddedZookeeper, String] {

  protected def establishConnection() = new EmbeddedZookeeper
}

object EmbeddedKafka extends EmbeddedServiceManager[EmbeddedKafka, (String, String)] {

  protected def establishConnection() = new EmbeddedKafka
}

trait EmbeddedService[C] extends AnyRef {

  def connection: C

  def shutdown(): Unit
}

class EmbeddedZookeeper extends EmbeddedService[String] {
  private val zkPort = TestUtils.choosePort()

  val zkConnect = "127.0.0.1:" + zkPort
  override def connection = zkConnect

  val snapshotDir = TestUtils.tempDir()
  val logDir = TestUtils.tempDir()
  val tickTime = 500
  val zookeeper = new ZooKeeperServer(snapshotDir, logDir, tickTime)
  val factory = new NIOServerCnxnFactory()
  factory.configure(new InetSocketAddress("127.0.0.1", zkPort), 1024)
  factory.startup(zookeeper)

  override def shutdown(): Unit = {
    try { zookeeper.shutdown() } catch { case _: Throwable => }
    try { factory.shutdown() } catch { case _: Throwable => }
    Utils.rm(logDir)
    Utils.rm(snapshotDir)
  }
}

class EmbeddedKafka extends EmbeddedService[(String, String)] {

  private val zkConnect = EmbeddedZookeeper.connect()

  private val brokerConf = {
    val conf = TestUtils.createBrokerConfig(1)
    conf.setProperty("zookeeper.connect", zkConnect) // override to use a unique zookeeper
    conf
  }

  val brokerConnect = s"${brokerConf.getProperty("host.name")}:${brokerConf.getProperty("port")}"
  override def connection = (brokerConnect, zkConnect)

  private val server = TestUtils.createServer(new KafkaConfig(brokerConf))

  override def shutdown(): Unit = {
    try { server.shutdown() } catch { case _: Throwable => }

    EmbeddedZookeeper.shutdown()
  }

}