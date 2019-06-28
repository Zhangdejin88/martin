/*
 * Copyright (c) 2018 MAMCHARGE 深圳智链物联科技有限公司. <http://www.mamcharge.com>
 *
 */

package com.mamcharge.access.router

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{complete, get, path}
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import com.mamcharge.access.router.AccessRouterMessages.{RegisterMessage, RegisterServiceGroup}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._
import scala.collection.Map
import scala.concurrent.ExecutionContextExecutor
import scala.util.{Failure, Success}

/**
  * @author 李杰铭 [jieming@mamcharge.com]
  *         Created in 2018/9/14 下午3:09
  */
object AccessRouterManager {
  val log: Logger = LoggerFactory.getLogger(this.getClass)

  def startup(implicit system: ActorSystem): Unit = {
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    implicit val executionContext: ExecutionContextExecutor = system.dispatcher

    val httpHost = system.settings.config.getString("router-manager.http.server.host")
    val httpPort = system.settings.config.getInt("router-manager.http.server.port")

    log.debug(s"AccessRouterManager Starting [$httpHost:$httpPort]")

    Http().bindAndHandle(route, httpHost, httpPort) onComplete{
      case Success(info) => log.debug(s"AccessRouterManager Start Success [$info]")
      case Failure(ex) => log.debug(s"AccessRouterManager Start Failed [$ex]")
    }
  }

  val route: Route =
    get {
      path("services") {
        log.debug(s"AccessRouterManager get services")
        val res = com.alibaba.fastjson.JSON.toJSONString(serviceStatus().asJava, true)
        complete(StatusCodes.OK, res)
      }
    }

  def serviceStatus(): Map[String, Any] = {
    val groupNames = AccessRouterRegister.serviceGroups.values.map(_.groupName).toSet
    val groupRoles = AccessRouterRegister.serviceGroups.values.flatMap(_.groupSettings.useRoles).toSet
    val registerMessages = AccessRouterRegister.registerMessageGroups.keys.toSet.map { msg: RegisterMessage =>
      msg.toString
    }
    val groupDetails = AccessRouterRegister.serviceGroups.values.toSet.map { group: RegisterServiceGroup =>
      Map(
        "groupNames" -> group.groupName,
        "routeesPaths" -> group.groupSettings.routeesPaths.toArray,
        "useRoles" -> group.groupSettings.useRoles.toArray,
        "registerMessages" -> group.registerMessages.map(_.toString).toArray
      ).asJava
    }

    Map (
      "groupNames" -> groupNames.toArray,
      "groupRoles" -> groupRoles.toArray,
      "registeredMessages" -> registerMessages.toArray,
      "groupDetails" -> groupDetails.toArray
    )
  }

  def registerMessageToMap(obj: AnyRef): Map[String, Any] = {
    val params = obj.getClass.getDeclaredFields.map { field =>
      field.setAccessible(true)
      field.get(obj) match {
        case o => field.getName -> o.toString
      }
    }.toMap
    Map("class" -> obj.getClass.getSimpleName) ++ params
  }
}