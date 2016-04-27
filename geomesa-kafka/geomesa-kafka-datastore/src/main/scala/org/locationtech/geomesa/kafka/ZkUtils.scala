/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.kafka

import kafka.admin.AdminUtils
import kafka.client.ClientUtils
import kafka.consumer.AssignmentContext
import kafka.network.BlockingChannel
import org.I0Itec.zkclient.ZkClient
import org.apache.zookeeper.data.Stat

trait ZkUtils {
  def zkClient: ZkClient
  def channelToOffsetManager(groupId: String, socketTimeoutMs: Int, retryBackOffMs: Int): BlockingChannel
  def deleteTopic(topic: String): Unit
  def topicExists(topic: String): Boolean
  def createTopic(topic: String, partitions: Int, replication: Int): Unit
  def getLeaderForPartition(topic: String, partition: Int): Option[Int]
  def createEphemeralPathExpectConflict(path: String, data: String): Unit
  def createEphemeralPathExpectConflictHandleZKBug(path: String,
                                                   data: String,
                                                   expectedCallerData: Any,
                                                   checker: (String, Any) => Boolean,
                                                   backoffTime: Int): Unit
  def deletePath(path: String): Unit
  def getConsumerPartitionOwnerPath(groupId: String, topic: String, partition: Int): String
  def getChildrenParentMayNotExist(path: String): Seq[String]
  def getAllBrokersInCluster: Seq[kafka.cluster.Broker]
  def createAssignmentContext(group: String, consumerId: String, excludeInternalTopics: Boolean): AssignmentContext
  def readData(path: String): (String, Stat)
  def close(): Unit
}

case class ZkUtils08(zkClient: ZkClient) extends ZkUtils {
  override def channelToOffsetManager(groupId: String, socketTimeoutMs: Int, retryBackOffMs: Int): BlockingChannel =
    ClientUtils.channelToOffsetManager(groupId, zkClient, socketTimeoutMs, retryBackOffMs)
  override def deleteTopic(topic: String): Unit = AdminUtils.deleteTopic(zkClient, topic)
  override def topicExists(topic: String): Boolean = AdminUtils.topicExists(zkClient, topic)
  override def createTopic(topic: String, partitions: Int, replication: Int) = AdminUtils.createTopic(zkClient, topic, partitions, replication)
  override def getLeaderForPartition(topic: String, partition: Int): Option[Int] = kafka.utils.ZkUtils.getLeaderForPartition(zkClient, topic, partition)
  override def createEphemeralPathExpectConflict(path: String, data: String): Unit = kafka.utils.ZkUtils.createEphemeralPathExpectConflict(zkClient, path, data)
  override def createEphemeralPathExpectConflictHandleZKBug(path: String,
                                                            data: String,
                                                            expectedCallerData: Any,
                                                            checker: (String, Any) => Boolean,
                                                            backoffTime: Int): Unit =
    kafka.utils.ZkUtils.createEphemeralPathExpectConflictHandleZKBug(zkClient, path, data, expectedCallerData, checker, backoffTime)
  override def deletePath(path: String) = kafka.utils.ZkUtils.deletePath(zkClient, path)
  override def getConsumerPartitionOwnerPath(groupId: String, topic: String, partition: Int): String =
    kafka.utils.ZkUtils.getConsumerPartitionOwnerPath(groupId, topic, partition)
  override def getChildrenParentMayNotExist(path: String): Seq[String] = kafka.utils.ZkUtils.getChildrenParentMayNotExist(zkClient, path)
  override def getAllBrokersInCluster: Seq[kafka.cluster.Broker] = kafka.utils.ZkUtils.getAllBrokersInCluster(zkClient)
  override def createAssignmentContext(group: String, consumerId: String, excludeInternalTopics: Boolean): AssignmentContext =
    new AssignmentContext(group, consumerId, excludeInternalTopics, zkClient)
  override def readData(path: String): (String, Stat) = kafka.utils.ZkUtils.readData(zkClient, path)
  override def close(): Unit = zkClient.close()
}
