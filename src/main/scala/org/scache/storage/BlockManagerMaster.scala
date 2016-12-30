/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.scache.storage

import scala.collection.Iterable
import scala.collection.generic.CanBuildFrom
import scala.concurrent.Future

import org.scache.util.{ScacheConf, Logging, RpcUtils, ThreadUtils}
import org.scache.rpc.RpcEndpointRef
import org.scache.storage.BlockManagerMessages._

private[scache]
class BlockManagerMaster(
    var driverEndpoint: RpcEndpointRef,
    conf: ScacheConf,
    isDriver: Boolean)
  extends Logging {

  val timeout = RpcUtils.askRpcTimeout(conf)

  /** Remove a dead executor from the driver endpoint. This is only called on the driver side. */
  def removeExecutor(execId: String) {
    tell(RemoveExecutor(execId))
    logInfo("Removed " + execId + " successfully in removeExecutor")
  }

  /** Request removal of a dead executor from the driver endpoint.
   *  This is only called on the driver side. Non-blocking
   */
  def removeExecutorAsync(execId: String) {
    driverEndpoint.ask[Boolean](RemoveExecutor(execId))
    logInfo("Removal of executor " + execId + " requested")
  }

  /** Register the BlockManager's id with the driver. */
  def registerBlockManager(
      blockManagerId: BlockManagerId, maxMemSize: Long, slaveEndpoint: RpcEndpointRef): Unit = {
    logInfo(s"Registering BlockManager $blockManagerId")
    tell(RegisterBlockManager(blockManagerId, maxMemSize, slaveEndpoint))
    logInfo(s"Registered BlockManager $blockManagerId")
  }

  def updateBlockInfo(
      blockManagerId: BlockManagerId,
      blockId: BlockId,
      storageLevel: StorageLevel,
      memSize: Long,
      diskSize: Long): Boolean = {
    val res = driverEndpoint.askWithRetry[Boolean](
      UpdateBlockInfo(blockManagerId, blockId, storageLevel, memSize, diskSize))
    logDebug(s"Updated info of block $blockId")
    res
  }

  /** Get locations of the blockId from the driver */
  def getLocations(blockId: BlockId): Seq[BlockManagerId] = {
    driverEndpoint.askWithRetry[Seq[BlockManagerId]](GetLocations(blockId))
  }

  /** Get locations of multiple blockIds from the driver */
  def getLocations(blockIds: Array[BlockId]): IndexedSeq[Seq[BlockManagerId]] = {
    driverEndpoint.askWithRetry[IndexedSeq[Seq[BlockManagerId]]](
      GetLocationsMultipleBlockIds(blockIds))
  }

  /**
   * Check if block manager master has a block. Note that this can be used to check for only
   * those blocks that are reported to block manager master.
   */
  def contains(blockId: BlockId): Boolean = {
    !getLocations(blockId).isEmpty
  }

  /** Get ids of other nodes in the cluster from the driver */
  def getPeers(blockManagerId: BlockManagerId): Seq[BlockManagerId] = {
    driverEndpoint.askWithRetry[Seq[BlockManagerId]](GetPeers(blockManagerId))
  }

  def getExecutorEndpointRef(executorId: String): Option[RpcEndpointRef] = {
    driverEndpoint.askWithRetry[Option[RpcEndpointRef]](GetExecutorEndpointRef(executorId))
  }

  def getBlockManagerId(executorId: String): Option[BlockManagerId] = {
    driverEndpoint.askWithRetry[Option[BlockManagerId]](GetBlockManagerId(executorId))
  }

  /**
   * Remove a block from the slaves that have it. This can only be used to remove
   * blocks that the driver knows about.
   */
  def removeBlock(blockId: BlockId) {
    driverEndpoint.askWithRetry[Boolean](RemoveBlock(blockId))
  }

  /** Remove all blocks belonging to the given RDD. */
  def removeRdd(rddId: Int, blocking: Boolean) {
    val future = driverEndpoint.askWithRetry[Future[Seq[Int]]](RemoveRdd(rddId))
    future.onFailure {
      case e: Exception =>
        logWarning(s"Failed to remove RDD $rddId - ${e.getMessage}", e)
    }(ThreadUtils.sameThread)
    if (blocking) {
      timeout.awaitResult(future)
    }
  }

  /** Remove all blocks belonging to the given shuffle. */
  def removeShuffle(shuffleId: Int, blocking: Boolean) {
    val future = driverEndpoint.askWithRetry[Future[Seq[Boolean]]](RemoveShuffle(shuffleId))
    future.onFailure {
      case e: Exception =>
        logWarning(s"Failed to remove shuffle $shuffleId - ${e.getMessage}", e)
    }(ThreadUtils.sameThread)
    if (blocking) {
      timeout.awaitResult(future)
    }
  }

  /** Remove all blocks belonging to the given broadcast. */
  def removeBroadcast(broadcastId: Long, removeFromMaster: Boolean, blocking: Boolean) {
    val future = driverEndpoint.askWithRetry[Future[Seq[Int]]](
      RemoveBroadcast(broadcastId, removeFromMaster))
    future.onFailure {
      case e: Exception =>
        logWarning(s"Failed to remove broadcast $broadcastId" +
          s" with removeFromMaster = $removeFromMaster - ${e.getMessage}", e)
    }(ThreadUtils.sameThread)
    if (blocking) {
      timeout.awaitResult(future)
    }
  }

  /**
   * Return the memory status for each block manager, in the form of a map from
   * the block manager's id to two long values. The first value is the maximum
   * amount of memory allocated for the block manager, while the second is the
   * amount of remaining memory.
   */
  def getMemoryStatus: Map[BlockManagerId, (Long, Long)] = {
    driverEndpoint.askWithRetry[Map[BlockManagerId, (Long, Long)]](GetMemoryStatus)
  }

  def getStorageStatus: Array[StorageStatus] = {
    driverEndpoint.askWithRetry[Array[StorageStatus]](GetStorageStatus)
  }

  /**
   * Return the block's status on all block managers, if any. NOTE: This is a
   * potentially expensive operation and should only be used for testing.
   *
   * If askSlaves is true, this invokes the master to query each block manager for the most
   * updated block statuses. This is useful when the master is not informed of the given block
   * by all block managers.
   */
  def getBlockStatus(
      blockId: BlockId,
      askSlaves: Boolean = true): Map[BlockManagerId, BlockStatus] = {
    val msg = GetBlockStatus(blockId, askSlaves)
    /*
     * To avoid potential deadlocks, the use of Futures is necessary, because the master endpoint
     * should not block on waiting for a block manager, which can in turn be waiting for the
     * master endpoint for a response to a prior message.
     */
    val response = driverEndpoint.
      askWithRetry[Map[BlockManagerId, Future[Option[BlockStatus]]]](msg)
    val (blockManagerIds, futures) = response.unzip
    implicit val sameThread = ThreadUtils.sameThread
    val cbf =
      implicitly[
        CanBuildFrom[Iterable[Future[Option[BlockStatus]]],
        Option[BlockStatus],
        Iterable[Option[BlockStatus]]]]
    val blockStatus = timeout.awaitResult(
      Future.sequence[Option[BlockStatus], Iterable](futures)(cbf, ThreadUtils.sameThread))
    if (blockStatus == null) {
      throw new Exception("BlockManager returned null for BlockStatus query: " + blockId)
    }
    blockManagerIds.zip(blockStatus).flatMap { case (blockManagerId, status) =>
      status.map { s => (blockManagerId, s) }
    }.toMap
  }

  /**
   * Return a list of ids of existing blocks such that the ids match the given filter. NOTE: This
   * is a potentially expensive operation and should only be used for testing.
   *
   * If askSlaves is true, this invokes the master to query each block manager for the most
   * updated block statuses. This is useful when the master is not informed of the given block
   * by all block managers.
   */
  def getMatchingBlockIds(
      filter: BlockId => Boolean,
      askSlaves: Boolean): Seq[BlockId] = {
    val msg = GetMatchingBlockIds(filter, askSlaves)
    val future = driverEndpoint.askWithRetry[Future[Seq[BlockId]]](msg)
    timeout.awaitResult(future)
  }

  /**
   * Find out if the executor has cached blocks. This method does not consider broadcast blocks,
   * since they are not reported the master.
   */
  def hasCachedBlocks(executorId: String): Boolean = {
    driverEndpoint.askWithRetry[Boolean](HasCachedBlocks(executorId))
  }

  /** Stop the driver endpoint, called only on the Spark driver node */
  def stop() {
    if (driverEndpoint != null && isDriver) {
      tell(StopBlockManagerMaster)
      driverEndpoint = null
      logInfo("BlockManagerMaster stopped")
    }
  }

  /** Send a one-way message to the master endpoint, to which we expect it to reply with true. */
  private def tell(message: Any) {
    if (!driverEndpoint.askWithRetry[Boolean](message)) {
      throw new Exception("BlockManagerMasterEndpoint returned false, expected true.")
    }
  }

}

private[scache] object BlockManagerMaster {
  val DRIVER_ENDPOINT_NAME = "BlockManagerMaster"
}
