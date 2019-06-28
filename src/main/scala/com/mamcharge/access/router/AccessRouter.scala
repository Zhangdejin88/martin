package com.mamcharge.access.router

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, OneForOneStrategy, Props, SupervisorStrategy}
import akka.cluster.metrics.AdaptiveLoadBalancingGroup
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Subscribe, SubscribeAck}
import akka.cluster.routing.{ClusterRouterGroup, ClusterRouterGroupSettings}
import akka.routing.RoundRobinPool
import com.mamcharge.access.library.common.CommonUtils
import com.mamcharge.access.library.common.config.DeviceTypeConfig
import com.mamcharge.access.library.common.config.DeviceTypeConfig.{DeviceTypeValue, MessageProtocol, NetworkProtocol, PushProtocol}
import com.mamcharge.access.router.AccessRouterMessages._

import scala.collection.immutable

/**
  * AccessRouter，作为消息的中转站，设备和业务平台的消息经解析后都通过它传递给特定的Processor去处理
  * Processor处理时需要向设备或业务平台发送消息时，也通过AccessRouter进行转发
  *
  * 其它服务需要向 AccessRouter 注册自己要接收的消息
  * 另外 processor 还需要注册设备类型信息
  *
  * @author 李杰铭 [jieming@mamcharge.com]
  *         Created in 2018/7/5 下午3:50
  */
object AccessRouter {
  val role = "access-router"
  val name = "access-router"
  val path = s"/user/$name"
  val groupName = "access-router-group"
  val groupPath = s"/user/$groupName"
  val MessageTimeRemarkKey: String = "message_time"

  def startup(system: ActorSystem): ActorRef = {
    system.actorOf(Props(new AccessRouter), name)
  }

  def startupGroup(system: ActorSystem): ActorRef = {
    val routerSettings = ClusterRouterGroupSettings(100, immutable.Seq(path), allowLocalRoutees = false, Set(role))
    system.actorOf(ClusterRouterGroup(AdaptiveLoadBalancingGroup(), routerSettings).props(), groupName)
  }
}

class AccessRouter extends Actor with AccessRouterRegister with ActorLogging {
  import AccessRouter.MessageTimeRemarkKey

  val mediator: ActorRef = DistributedPubSub(context.system).mediator

  val workerCount = 20
  val routerConfig = RoundRobinPool(workerCount, supervisorStrategy = new OneForOneStrategy({
    case _ => SupervisorStrategy.Restart
  }))
  val workers: ActorRef = context.system.actorOf(Props(new AccessRouterWorker).withRouter(routerConfig))

  override def preStart(): Unit = {
    super.preStart()

    mediator ! Subscribe(AccessRouter.groupName, self)
  }

  override def receive: Receive = {
    case SubscribeAck(Subscribe(AccessRouter.groupName, None, `self`)) =>
      log.debug(s"SubscribeAck: ${AccessRouter.groupName}")

    case message => workers forward message
  }

  class AccessRouterWorker extends Actor with ActorLogging {
    override def receive: Receive = {
      case accessMessage@ReceiveDataFromAccess(dataMessage) =>
        accessMessage.data.messageRemarks = accessMessage.data.messageRemarks + (MessageTimeRemarkKey -> System.currentTimeMillis())

        log.info(s"Received message ${CommonUtils.prettyPrint(accessMessage, recursion = true)}")
        val deviceType = dataMessage.deviceIdentifierInfo.deviceType
        val groupActorSet = getGroupActors(Set(
          RegisterReceiveDataFromAccess(deviceType),
          RegisterReceiveDataFromAccess(DeviceTypeValue.Any)
        ))

        if (groupActorSet.isEmpty) {
          log.warning(s"Unhandled ReceiveDataFromAccess ${CommonUtils.prettyPrint(dataMessage)}")
        }
        else {
          groupActorSet.foreach(_ ! accessMessage)
        }

      case accessMessage@ReceiveDataFromDevice(dataMessage) =>
        accessMessage.data.messageRemarks = accessMessage.data.messageRemarks + (MessageTimeRemarkKey -> System.currentTimeMillis())

        log.info(s"Received message ${CommonUtils.prettyPrint(accessMessage, recursion = true)}")
        val deviceType = dataMessage.deviceIdentifierInfo.deviceType
        val groupActorSet = getGroupActors(Set(
          RegisterReceiveDataFromDevice(deviceType),
          RegisterReceiveDataFromDevice(DeviceTypeValue.Any)
        ))

        if (groupActorSet.isEmpty) {
          log.warning(s"Unhandled ReceiveDataFromDevice ${CommonUtils.prettyPrint(dataMessage)}")
        }
        else {
          groupActorSet.foreach(_ ! accessMessage)
        }

      case SendDataToDeviceFailed(dataMessage) =>
        log.info(s"SendDataToDeviceFailed $dataMessage")
        self ! SendDataToDevice(dataMessage)

      case accessMessage@SendDataToDevice(dataMessage) =>
        accessMessage.dataMessage.messageRemarks = accessMessage.dataMessage.messageRemarks + (MessageTimeRemarkKey -> System.currentTimeMillis())

        log.info(s"Received message ${CommonUtils.prettyPrint(accessMessage, recursion = true)}")
        val deviceTypeOption = DeviceTypeConfig.getDeviceTypeConfigInfo(dataMessage.deviceIdentifierInfo.deviceType)
        if (deviceTypeOption.isEmpty) {
          log.warning(s"Unhandled SendDataToDevice ${CommonUtils.prettyPrint(dataMessage)}")
        }
        else {
          val groupActorSet = getGroupActors(Set(
            RegisterSendDataToDevice(deviceTypeOption.get.networkProtocol, deviceTypeOption.get.messageProtocol),
            RegisterSendDataToDevice(deviceTypeOption.get.networkProtocol, MessageProtocol.Any),
            RegisterSendDataToDevice(NetworkProtocol.Any, deviceTypeOption.get.messageProtocol),
            RegisterSendDataToDevice(NetworkProtocol.Any, MessageProtocol.Any)
          ))

          if (groupActorSet.isEmpty) {
            log.warning(s"Unhandled SendDataToDevice ${CommonUtils.prettyPrint(dataMessage)}")
          }
          else {
            groupActorSet.foreach(_ ! accessMessage)
          }
        }

      case accessMessage@SendDataToBusiness(dataMessage) =>
        accessMessage.message.messageRemarks = accessMessage.message.messageRemarks + (MessageTimeRemarkKey -> System.currentTimeMillis())

        log.info(s"Received message ${CommonUtils.prettyPrint(accessMessage, recursion = true)}")
        val pushProtocolSet = DeviceTypeConfig.getDeviceTypeConfigInfo(dataMessage.deviceIdentifierInfo.deviceType).map(_.pushProtocols).getOrElse(Set.empty)

        if (pushProtocolSet.isEmpty) {
          log.warning(s"Unhandled SendDataToBusiness (pushProtocolSet) ${CommonUtils.prettyPrint(dataMessage)}")
        }
        else {
          val groupActorSet = pushProtocolSet.flatMap { protocol =>
            getGroupActors(Set(
              RegisterSendDataToBusiness(protocol),
              RegisterSendDataToBusiness(PushProtocol.Any)
            ))
          }

          if (groupActorSet.isEmpty) {
            log.warning(s"Unhandled SendDataToBusiness (groupActorSet) ${CommonUtils.prettyPrint(dataMessage)}")
          }
          else {
            groupActorSet.foreach(actor => {
              log.debug(s"Handled SendDataToBusiness (${actor.pathString}) ${CommonUtils.prettyPrint(dataMessage)}")
              actor ! accessMessage
            })
          }
        }

      case accessMessage@ReceiveDataFromBusiness(dataMessage) =>
        accessMessage.message.messageRemarks = accessMessage.message.messageRemarks + (MessageTimeRemarkKey -> System.currentTimeMillis())

        log.info(s"Received message ${CommonUtils.prettyPrint(accessMessage, recursion = true)}")
        val deviceType = dataMessage.deviceIdentifierInfo.deviceType
        val groupActorSet = getGroupActors(Set(
          RegisterReceiveDataFromBusiness(deviceType),
          RegisterReceiveDataFromBusiness(DeviceTypeValue.Any)
        ))

        if (groupActorSet.isEmpty) {
          log.warning(s"Unhandled ReceiveDataFromBusiness ${CommonUtils.prettyPrint(dataMessage)}")
        }
        else {
          groupActorSet.foreach(_ ! accessMessage)
        }
    }
  }
}
