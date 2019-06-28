/*
 * Copyright (c) 2018 MAMCHARGE 深圳智链物联科技有限公司. <http://www.mamcharge.com>
 *
 */

package com.mamcharge.access.router

import java.net.InetAddress

import akka.actor.ActorSystem
import akka.management.AkkaManagement
import akka.management.cluster.bootstrap.ClusterBootstrap
import com.mamcharge.access.library.common.config.{DeviceTypeConfig, LogbackConfig}
import com.mamcharge.access.library.config.AccessConfigManager
import com.typesafe.config.ConfigFactory

/**
  * @author 李杰铭 [jieming@mamcharge.com]
  *         Created in 2018/8/28 下午5:46
  */
object AccessRouterServices extends App {
  private val systemName = ConfigFactory.load.getString("system.name")
  private val port = sys.env.getOrElse("AKKA_REMOTING_PORT", "2552")
  private val namespace = sys.env.getOrElse("POD_NAMESPACE", "")
  private val namespaceConfig = if (namespace.nonEmpty) {
    ConfigFactory.parseString(s"akka.management.cluster.bootstrap.contact-point-discovery.service-namespace = $namespace.svc.cluster.local")
  }
  else {
    ConfigFactory.parseString("akka.cluster.seed-nodes = [\"akka.tcp://" + systemName + "@127.0.0.1:" + port +"\"]")
  }

  private val localhost = InetAddress.getLocalHost.getHostName + "-" + port
  private val config = namespaceConfig.
    withFallback(ConfigFactory.parseString(s"akka.cluster.roles = [${AccessRouter.role}]")).
    withFallback(ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port)).
    withFallback(ConfigFactory.parseString("node.name= \"" + localhost + "\"")).
    withFallback(ConfigFactory.load("application"))

  System.setProperty("hostname", sys.env.getOrElse("HOSTNAME", "unknown"))
  LogbackConfig.reload()

  implicit val system: ActorSystem = ActorSystem(systemName, config)

  AkkaManagement(system).start()
  ClusterBootstrap(system).start()
  AccessConfigManager.setupManager(system)

  KubernetesHealthChecks.start(system)

  AccessRouter.startup(system)
  AccessRouterManager.startup(system)
}
