/*
 * Copyright (c) 2018 MAMCHARGE 深圳智链物联科技有限公司. <http://www.mamcharge.com>
 *
 */

package com.mamcharge.access.router

import akka.cluster.routing.ClusterRouterGroupSettings
import com.mamcharge.access.library.common.config.DeviceTypeConfig._
import com.mamcharge.access.library.message.DeviceMessage

/**
  * @author 李杰铭 [jieming@mamcharge.com]
  *         Created in 2018/8/20 下午6:18
  */
object AccessRouterMessages {
  // 服务注册
  trait RegisterMessage extends Serializable
  final case class RegisterSendDataToDevice(networkProtocol: NetworkProtocol, messageProtocol: MessageProtocol = MessageProtocol.Any) extends RegisterMessage
  final case class RegisterReceiveDataFromAccess(deviceType: DeviceTypeValue) extends RegisterMessage
  final case class RegisterReceiveDataFromDevice(deviceType: DeviceTypeValue) extends RegisterMessage
  final case class RegisterSendDataToBusiness(pushProtocol: PushProtocol) extends RegisterMessage
  final case class RegisterReceiveDataFromBusiness(deviceType: DeviceTypeValue) extends RegisterMessage
  final case class RegisterDeviceType(deviceTypeConfigInfo: DeviceTypeConfigInfo) extends RegisterMessage

  final case class RegisterServiceGroup(groupName: String, groupSettings: ClusterRouterGroupSettings, registerMessages: Seq[RegisterMessage]) extends Serializable

  // 服务健康检查
  final case class PingServiceGroup(groupName: String) extends Serializable
  final case class PongServiceGroup(groupName: Option[String], groupRegistered: Boolean = true) extends Serializable

  type ClassName = String

  // 向设备下发消息
  final case class SendDataToDevice(dataMessage: DeviceMessage[Array[Byte]]) extends Serializable
  final case class SendDataToDeviceFailed(dataMessage: DeviceMessage[Array[Byte]]) extends Serializable

  // 设备上报消息
  final case class ReceiveDataFromDevice(data: DeviceMessage[Array[Byte]]) extends Serializable
  final case class ReceiveDataFromAccess(data: DeviceMessage[(ClassName, Array[Byte])]) extends Serializable

  // 向业务平台推送消息
  final case class SendDataToBusiness(message: DeviceMessage[(ClassName, Array[Byte])]) extends Serializable

  // 业务平台下发消息
  final case class ReceiveDataFromBusiness(message: DeviceMessage[(ClassName, Array[Byte])]) extends Serializable
}
