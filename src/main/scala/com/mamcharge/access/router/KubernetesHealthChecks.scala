/*
 * Copyright (c) 2018 MAMCHARGE 深圳智链物联科技有限公司. <http://www.mamcharge.com>
 *
 */

package com.mamcharge.access.router

import akka.actor.ActorSystem
import akka.cluster.{Cluster, MemberStatus}
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{complete, concat, get, path}
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer

import scala.concurrent.ExecutionContextExecutor

/**
  * @author 李杰铭 [jieming@mamcharge.com]
  *         Created in 2018/11/8 下午2:34
  */
object KubernetesHealthChecks {
  val healthCheckPorts = 25230

  def start(implicit system: ActorSystem): Unit = {
    implicit val materializer = ActorMaterializer()
    implicit val executionContext: ExecutionContextExecutor = system.dispatcher

    val k8sHealthChecks = new KubernetesHealthChecks(system)
    val routes = k8sHealthChecks.k8sHealthChecks
    system.log.info("Starting Kubernetes Health Checks")

    Http().bindAndHandle(routes, "0.0.0.0", healthCheckPorts)

    system.log.info(s"Kubernetes Health Checks Started at http://localhost:$healthCheckPorts")
  }
}

class KubernetesHealthChecks(system: ActorSystem) {

  val log: LoggingAdapter = Logging(system, getClass)
  val cluster = Cluster(system)

  //#health
  private val readyStates: Set[MemberStatus] = Set(MemberStatus.Up, MemberStatus.WeaklyUp)

  val k8sHealthChecks: Route =
    concat(
      path("ready") {
        get {
          val selfState = cluster.selfMember.status
          log.debug("ready? clusterState {}", selfState)
          if (readyStates.contains(selfState)) complete(StatusCodes.OK)
          else complete(StatusCodes.InternalServerError)
        }
      },
      path("alive") {
        get {
          // When Akka HTTP can respond to requests, that is sufficient
          // to consider ourselves 'live': we don't want K8s to kill us even
          // when we're in the process of shutting down (only stop sending
          // us traffic, which is done due to the readyness check then failing)
          complete(StatusCodes.OK)
        }
      })
  //#health
}
