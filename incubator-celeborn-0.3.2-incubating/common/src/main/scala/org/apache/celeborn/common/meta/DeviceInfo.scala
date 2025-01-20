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

package org.apache.celeborn.common.meta

import java.io.File
import java.util

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

import org.slf4j.LoggerFactory

import org.apache.celeborn.common.CelebornConf
import org.apache.celeborn.common.internal.Logging
import org.apache.celeborn.common.protocol.StorageInfo
import org.apache.celeborn.common.util.{JavaUtils, Utils}
import org.apache.celeborn.common.util.Utils.runCommand

class DiskInfo(
    val mountPoint: String,
    var actualUsableSpace: Long,
    var avgFlushTime: Long, // in nano seconds
    var avgFetchTime: Long, // in nano seconds
    var activeSlots: Long,
    val dirs: List[File],
    val deviceInfo: DeviceInfo) extends Serializable with Logging {

  def this(
      mountPoint: String,
      usableSpace: Long,
      avgFlushTime: Long,
      avgFetchTime: Long,
      activeSlots: Long) = {
    this(mountPoint, usableSpace, avgFlushTime, avgFetchTime, activeSlots, List.empty, null)
  }

  def this(
      mountPoint: String,
      dirs: List[File],
      deviceInfo: DeviceInfo,
      conf: CelebornConf) = {
    this(mountPoint, 0, 0, 0, 0, dirs, deviceInfo)
    flushTimeMetrics =
      new TimeWindow(
        conf.workerDiskTimeSlidingWindowSize,
        conf.workerDiskTimeSlidingWindowMinFlushCount)
    fetchTimeMetrics =
      new TimeWindow(
        conf.workerDiskTimeSlidingWindowSize,
        conf.workerDiskTimeSlidingWindowMinFetchCount)
  }

  var flushTimeMetrics: TimeWindow = _
  var fetchTimeMetrics: TimeWindow = _
  var status: DiskStatus = DiskStatus.HEALTHY
  var threadCount = 1
  var configuredUsableSpace = 0L
  var storageType: StorageInfo.Type = _
  var maxSlots: Long = 0
  lazy val shuffleAllocations = new util.HashMap[String, Integer]()

  def setStatus(status: DiskStatus): this.type = this.synchronized {
    this.status = status
    this
  }

  def setUsableSpace(usableSpace: Long): this.type = this.synchronized {
    this.actualUsableSpace = usableSpace
    this
  }

  def updateFlushTime(): Unit = {
    avgFlushTime = flushTimeMetrics.getAverage()
  }

  def updateFetchTime(): Unit = {
    avgFetchTime = fetchTimeMetrics.getAverage()
  }

  /**
   * Returns the available slots of the disk calculated by maxSlots minus activeSlots.
   * Returns zero for the negative slots calculated.
   *
   * <b>Note:</b>`maxSlots` is calculated by actualUsableSpace divided estimatedPartitionSize.
   * Meanwhile, `activeSlots` include slots reserved.
   *
   * @return the available slots of the disk.
   */
  def availableSlots(): Long = this.synchronized {
    math.max(maxSlots - activeSlots, 0L)
  }

  def allocateSlots(shuffleKey: String, slots: Int): Unit = this.synchronized {
    val allocated = shuffleAllocations.getOrDefault(shuffleKey, 0)
    shuffleAllocations.put(shuffleKey, allocated + slots)
    activeSlots = activeSlots + slots
  }

  def releaseSlots(shuffleKey: String, slots: Int): Unit = this.synchronized {
    val allocated = shuffleAllocations.getOrDefault(shuffleKey, 0)
    if (allocated < slots) {
      logError(s"allocated $allocated is less than to release $slots !")
    }
    val delta = Math.min(allocated, slots)
    activeSlots = activeSlots - delta
    if (allocated > slots) {
      shuffleAllocations.put(shuffleKey, allocated - slots)
    } else {
      shuffleAllocations.put(shuffleKey, 0)
    }
  }

  def releaseSlots(shuffleKey: String): Unit = this.synchronized {
    val allocated = shuffleAllocations.remove(shuffleKey)
    if (allocated != null) {
      activeSlots = activeSlots - allocated
    }
  }

  def getShuffleKeySet(): util.HashSet[String] = this.synchronized {
    new util.HashSet(shuffleAllocations.keySet())
  }

  override def toString: String = this.synchronized {
    val (emptyShuffles, nonEmptyShuffles) = shuffleAllocations.asScala.partition(_._2 == 0)
    s"DiskInfo(maxSlots: $maxSlots," +
      s" committed shuffles ${emptyShuffles.size}" +
      s" shuffleAllocations: ${nonEmptyShuffles.toMap}," +
      s" mountPoint: $mountPoint," +
      s" usableSpace: ${Utils.bytesToString(actualUsableSpace)}," +
      s" avgFlushTime: ${Utils.nanoDurationToString(avgFlushTime)}," +
      s" avgFetchTime: ${Utils.nanoDurationToString(avgFetchTime)}," +
      s" activeSlots: $activeSlots)" +
      s" status: $status" +
      s" dirs ${dirs.mkString("\t")}"
  }
}

class DeviceInfo(val name: String) extends Serializable {
  var diskInfos: ListBuffer[DiskInfo] = new ListBuffer[DiskInfo]()
  // if deviceStatAvailable is false means that there is no device info found.
  var deviceStatAvailable = true

  def addDiskInfo(diskInfo: DiskInfo): Unit = {
    diskInfos.append(diskInfo)
  }

  override def hashCode(): Int = {
    name.hashCode
  }

  override def equals(obj: Any): Boolean = {
    obj.isInstanceOf[DeviceInfo] && name.equals(obj.asInstanceOf[DeviceInfo].name)
  }

  override def toString: String = {
    s"DeviceName: ${name}\tMount Infos: ${diskInfos.mkString("\n")}"
  }
}

object DeviceInfo {
  val logger = LoggerFactory.getLogger(classOf[DeviceInfo])

  /**
   * @param workingDirs array of (workingDir, max usable space, flush thread count, storage type)
   * @return it will return two maps
   *         (deviceName -> deviceInfo)
   *         (mount point -> diskInfo)
   */
  def getDeviceAndDiskInfos(
      workingDirs: Seq[(File, Long, Int, StorageInfo.Type)],
      conf: CelebornConf): (util.Map[String, DeviceInfo], util.Map[String, DiskInfo]) = {
    val deviceNameToDeviceInfo = new util.HashMap[String, DeviceInfo]()
    val mountPointToDeviceInfo = new util.HashMap[String, DeviceInfo]()

    val dfResult = runCommand("df -ah").trim
    logger.info(s"df result\n$dfResult")
    // (/dev/vdb, /mnt/disk1)
    val fsMounts = dfResult
      .split("[\n\r]")
      .tail
      .map(line => {
        val tokens = line.trim.split("[ \t]+")
        (tokens.head, tokens.last)
      })

    // (vda, vdb)
    val lsBlockResult = runCommand("ls /sys/block/").trim
    logger.info(s"ls block\n$lsBlockResult")
    val blocks = lsBlockResult.split("[ \n\r\t]+")

    fsMounts.foreach { case (fileSystem, mountPoint) =>
      val deviceName = fileSystem.substring(fileSystem.lastIndexOf('/') + 1)
      var index = -1
      var maxLength = -1
      blocks.zipWithIndex.foreach(block => {
        if (deviceName.startsWith(block._1) && block._1.length > maxLength) {
          index = block._2
          maxLength = block._1.length
        }
      })

      val newDeviceInfoFunc =
        new util.function.Function[String, DeviceInfo]() {
          override def apply(s: String): DeviceInfo = {
            val deviceInfo = new DeviceInfo(s)
            if (index < 0) {
              // device not found in /sys/block/
              deviceInfo.deviceStatAvailable = false
            }
            deviceInfo
          }
        }

      val deviceInfo =
        if (index >= 0) {
          deviceNameToDeviceInfo.computeIfAbsent(blocks(index), newDeviceInfoFunc)
        } else {
          deviceNameToDeviceInfo.computeIfAbsent(deviceName, newDeviceInfoFunc)
        }
      mountPointToDeviceInfo.putIfAbsent(mountPoint, deviceInfo)
    }

    val retDeviceInfos = JavaUtils.newConcurrentHashMap[String, DeviceInfo]()
    val retDiskInfos = JavaUtils.newConcurrentHashMap[String, DiskInfo]()

    workingDirs.groupBy { case (dir, _, _, _) =>
      getMountPoint(dir.getCanonicalPath, mountPointToDeviceInfo.keySet())
    }.foreach {
      case (mountPoint, dirs) =>
        if (mountPoint.nonEmpty) {
          val deviceInfo = mountPointToDeviceInfo.get(mountPoint)
          val diskInfo = new DiskInfo(
            mountPoint,
            dirs.map(_._1).toList,
            deviceInfo,
            conf)
          val (_, maxUsableSpace, threadCount, storageType) = dirs(0)
          diskInfo.configuredUsableSpace = maxUsableSpace
          diskInfo.threadCount = threadCount
          diskInfo.storageType = storageType
          deviceInfo.addDiskInfo(diskInfo)
          retDiskInfos.put(mountPoint, diskInfo)
        } else {
          logger.warn(
            s"Can't find mount point for ${dirs.map(_._1.getCanonicalPath).mkString(",")}")
        }
    }
    deviceNameToDeviceInfo.asScala.foreach {
      case (_, deviceInfo) =>
        if (deviceInfo.diskInfos.nonEmpty) {
          retDeviceInfos.put(deviceInfo.name, deviceInfo)
        }
    }

    logger.info(s"Device initialization \n " +
      s"$retDeviceInfos \n $retDiskInfos")

    (retDeviceInfos, retDiskInfos)
  }

  def getMountPoint(absPath: String, mountPoints: util.Set[String]): String = {
    var curMax = -1
    var curMount = ""
    mountPoints.asScala.foreach(mount => {
      if (absPath.startsWith(mount) && mount.length > curMax) {
        if (absPath.length == mount.length) {
          return mount
        } else if (absPath.length > mount.length &&
          (mount == "/" || absPath.substring(mount.length, mount.length + 1) == "/")) {
          curMax = mount.length
          curMount = mount
        }
      }
    })
    curMount
  }

  def getMountPoint(absPath: String, diskInfos: util.Map[String, DiskInfo]): String = {
    getMountPoint(absPath, diskInfos.keySet())
  }
}
