/*
 * Copyright (c) 2018 MAMCHARGE 深圳智链物联科技有限公司. <http://www.mamcharge.com>
 *
 */

package com.mamcharge.access.router

import java.util.Date

import akka.Done
import akka.actor.{Actor, ActorLogging, ActorSelection, PoisonPill, Terminated, Timers}
import akka.cluster.metrics.AdaptiveLoadBalancingGroup
import akka.cluster.routing.ClusterRouterGroup
import akka.event.LoggingAdapter
import com.mamcharge.access.library.common.config.DeviceTypeConfig.{DeviceTypeConfigInfo, DeviceTypeValue, NetworkProtocol, PushProtocol}
import com.mamcharge.access.router.AccessRouterMessages._

import scala.collection.mutable
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

/**
  * 处理 AccessRouter 注册消息和设备类型
  *
  * @author 李杰铭 [jieming@mamcharge.com]
  *         Created in 2018/8/28 下午3:24
  */
object AccessRouterRegister {
  case object ServiceLivenessCheck
  case class ServiceGroupTerminated(groupName: String)

  private[AccessRouterRegister] def getGroupActorName(groupName: String): String = groupName
  private[AccessRouterRegister] def getGroupActorPath(groupName: String): String = s"/user/${getGroupActorName(groupName)}"

  val serviceGroups: mutable.Map[String, RegisterServiceGroup] = mutable.Map.empty
  val registerMessageGroups: mutable.Map[RegisterMessage, Set[RegisterServiceGroup]] = mutable.Map.empty

  protected val livenessCheckTimeInterval: Int = 15
  protected val livenessCheckTimeOut: Int = 120
  val serviceLivenessTimes: mutable.Map[String, Long] = mutable.Map.empty
}

trait AccessRouterRegister extends Actor with Timers with ActorLogging {
  import AccessRouterRegister._
  import scala.concurrent.duration._
  implicit val dispatcher: ExecutionContextExecutor = context.dispatcher

  def getGroupActors(registerMessages: Set[RegisterMessage]): Set[ActorSelection] = {
    registerMessages.flatMap { message =>
      registerMessageGroups.getOrElse(message, Set.empty).map { group =>
        context.system.actorSelection(getGroupActorPath(group.groupName))
      }
    }
  }

  override def preStart(): Unit = {
    super.preStart()

    timers.startPeriodicTimer(ServiceLivenessCheck, ServiceLivenessCheck, livenessCheckTimeInterval.seconds)
  }

  override def aroundReceive(receive: Receive, msg: Any): Unit = {
    super.aroundReceive(registerReceive.orElse(receive), msg)
  }

  def registerReceive: Receive = {
    case ServiceLivenessCheck =>
      log.debug(s"Received ServiceLivenessCheck ${sender()}")
      serviceGroups.foreach { case (groupName, serviceGroup) =>
        if (System.currentTimeMillis() - serviceLivenessTimes.getOrElse(groupName, 0L) > livenessCheckTimeOut * 1000) {
          // 超时，移除service group
          log.debug(s"removeServiceGroup timeout ${serviceGroup.groupName}")
          removeServiceGroup(serviceGroup)
          terminateServiceGroupActor(serviceGroup)
        }
        else {
          // Ping Service
          context.system.actorSelection(getGroupActorPath(groupName)) ! PingServiceGroup(groupName)
        }
      }

    case group: RegisterServiceGroup =>
      log.debug(s"Received RegisterServiceGroup $group ${sender()}")
      val existedGroup = serviceGroups.get(group.groupName)

      if (existedGroup.isDefined) {
        log.debug(s"removeServiceGroup register ${existedGroup.get.groupName}")
        removeServiceGroup(existedGroup.get)
        addServiceGroup(group)
      }
      else {
        log.debug(s"addServiceGroup register ${group.groupName}")
        addServiceGroup(group)
        createServiceGroupActor(group)
      }

      // 更新服务生命时间
      serviceLivenessTimes.update(group.groupName, System.currentTimeMillis())

      // 立刻下发一个 Ping，告知 service 注册成功
      sender() ! PingServiceGroup(group.groupName)

    case PingServiceGroup(groupName) =>
      log.debug(s"Received PingServiceGroup $groupName ${sender()}")
      // 收到 service 的 Ping，回复 Pong
      log.debug(s"serviceGroups.contains(groupName) ${serviceGroups.contains(groupName)} $serviceGroups")
      if (serviceGroups.contains(groupName)) {
        log.debug(s"Respond PongServiceGroup $groupName true to ${sender()}")
        sender() ! PongServiceGroup(Some(groupName))
        serviceLivenessTimes.update(groupName, System.currentTimeMillis())
      }
      else {
        log.debug(s"Respond PongServiceGroup $groupName false to ${sender()}")
        sender() ! PongServiceGroup(Some(groupName), groupRegistered = false)
      }

    case PongServiceGroup(Some(groupName), _) if serviceGroups.keys.toSet.contains(groupName) =>
      log.debug(s"Received PongServiceGroup $groupName ${sender()}")
      serviceLivenessTimes.update(groupName, System.currentTimeMillis())

    case PongServiceGroup(None, _) => // DO NOTHING

    case ServiceGroupTerminated(groupName) =>
      // 如果该 service 还存在，则重启
      if (serviceGroups.contains(groupName)) {
        val group = serviceGroups(groupName)
        createServiceGroupActor(group)
      }
  }

  private def removeServiceGroup(serviceGroup: RegisterServiceGroup): Unit = {
    serviceGroups.remove(serviceGroup.groupName)

    // 处理注册信息
    serviceGroup.registerMessages.foreach {
      case RegisterDeviceType(_) =>
        // 设备注册信息不删除
      case registerMessage =>
        registerMessageGroups.update(registerMessage, registerMessageGroups.getOrElse(registerMessage, Set.empty) - serviceGroup)
        // 如果注册消息已经没有服务对应了，就删除掉
        if (registerMessageGroups.getOrElse(registerMessage, Set.empty).isEmpty) {
          registerMessageGroups.remove(registerMessage)
        }
    }
  }

  private def addServiceGroup(serviceGroup: RegisterServiceGroup): Unit = {
    serviceGroups += (serviceGroup.groupName -> serviceGroup)

    // 处理注册信息
    serviceGroup.registerMessages.foreach(registerMessage =>
      registerMessageGroups.update(registerMessage, registerMessageGroups.getOrElse(registerMessage, Set.empty) + serviceGroup))
  }

  private def createServiceGroupActor(serviceGroup: RegisterServiceGroup): Unit = {
    // 创建 group actor
    Try(context.system.actorOf(ClusterRouterGroup(AdaptiveLoadBalancingGroup(), serviceGroup.groupSettings).props(), getGroupActorName(serviceGroup.groupName))) match {
      case Success(_) => log.debug(s"createServiceGroupActor success ${serviceGroup.groupName}")
      case Failure(ex) => log.debug(s"createServiceGroupActor failed ${serviceGroup.groupName} $ex")
    }
  }

  private def terminateServiceGroupActor(serviceGroup: RegisterServiceGroup): Future[Done] = {
    // 删除 group actor
    val future = context.system.actorSelection(getGroupActorPath(serviceGroup.groupName)).resolveOne(2.second).map { actorRef =>
      context.watchWith(actorRef, ServiceGroupTerminated(serviceGroup.groupName))
      context.system.stop(actorRef)
      Done
    }
    future.onComplete(res => log.debug(s"terminateServiceGroupActor result = $res"))
    future
  }
}
