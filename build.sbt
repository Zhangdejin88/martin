import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport._
import com.typesafe.sbt.packager.MappingsHelper._
import com.typesafe.sbt.packager.docker.{Cmd, ExecCmd}

lazy val project = Project(
  id = "access-router",
  base = file("."),
  settings = Defaults.coreDefaultSettings ++ Seq(
    scalaVersion := "2.12.1",
    resolvers ++= Seq(
      "chrisdinn" at "http://chrisdinn.github.io/releases/",
      "rediscala" at "http://dl.bintray.com/etaty/maven",
      "SpinGo OSS" at "http://spingo-oss.s3.amazonaws.com/repositories/releases",
      "spray repo" at "http://repo.spray.io"
    ),
    libraryDependencies ++= {
      val akkaVersion = "2.5.11"
      Seq(
        // ScalaTest framework
        "org.scalatest" %% "scalatest" % "3.0.1" % "test",

        // Akka framework
        "com.typesafe.akka" %% "akka-actor" % akkaVersion,
        "com.typesafe.akka" %% "akka-remote" % akkaVersion,
        "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
        "com.typesafe.akka" %% "akka-cluster-metrics" % akkaVersion,
        "com.typesafe.akka" %% "akka-cluster-tools" % akkaVersion,
        "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
        "com.typesafe.akka" %% "akka-http" % "10.0.6",

        // Akka management
        "com.lightbend.akka.management" %% "akka-management" % "0.18.0",
        "com.lightbend.akka.management" %% "akka-management-cluster-http" % "0.18.0",
        "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % "0.18.0",
        "com.lightbend.akka.discovery" %% "akka-discovery-dns" % "0.18.0",

        // Logback
        "ch.qos.logback" % "logback-classic" % "1.1.3",

        // Json
        "com.typesafe.play" %% "play-json" % "2.6.0-M1",
        "com.alibaba" % "fastjson" % "1.2.49",

        // Access
        "com.mamcharge.access" %% "access-library-common" % "0.1.11",
        "com.mamcharge.access" %% "access-library-mysql" % "0.1.3",
        "com.mamcharge.access" %% "message-core" % "0.1.2"
      )
    }
  )
).enablePlugins(JavaAppPackaging)
  .enablePlugins(DockerPlugin)
  .enablePlugins(DockerSpotifyClientPlugin)

//指定java文件使用UTF-8编码进行编译
javacOptions ++= Seq("-encoding", "UTF-8")
mappings in Universal ++= {
  directory("scripts") ++
    contentOf("src/main/resources").toMap.mapValues("config/" + _) ++
    contentOf("public").toMap.mapValues("public/" + _)
}

scriptClasspath := Seq("../config/") ++ scriptClasspath.value

dockerRepository := Some("registry-internal.cn-hangzhou.aliyuncs.com/chargerlink")

dockerBaseImage:="fabric8/java-jboss-openjdk8-jdk"
dockerEntrypoint ++= Seq(
  "2551",
  "-J-XX:+PrintGCDetails ",
  "-J-Xloggc:log/$HOSTNAME/gc.log",
  "-J-XX:+HeapDumpOnOutOfMemoryError ",
  "-J-XX:HeapDumpPath=log/$HOSTNAME/dump.log",
  "-J-XX:SurvivorRatio=6",
  "-J-XX:NewRatio=4",
  "-J-XX:MaxDirectMemorySize=1024M",
  "-J-XX:MaxTenuringThreshold=12",
  "-J-XX:PretenureSizeThreshold=5242880",
  "-J-XX:+UseConcMarkSweepGC",
  "-J-XX:CMSInitiatingOccupancyFraction=80",
  "-J-XX:+UseCMSInitiatingOccupancyOnly",
  "-J-XX:+CMSClassUnloadingEnabled",
  "-J-XX:+DoEscapeAnalysis",
  "-J-XX:+EliminateAllocations",
  "-Dio.netty.recycler.maxCapacity=0",
  "-Dio.netty.recycler.maxCapacity.default=0",
  "-Dio.netty.leakDetectionLevel=advanced",
  "-Dio.netty.noPreferDirect=true"
)
dockerCommands :=
  dockerCommands.value.flatMap {
    case ExecCmd("ENTRYPOINT", args @ _*) => Seq(Cmd("ENTRYPOINT", args.mkString(" ")))
    case v => Seq(v)
  }

dockerCommands ++= Seq(
  Cmd("USER", "root"),
  ExecCmd(
    "RUN",
    "chmod",
    "u+x",
    s"${(defaultLinuxInstallLocation in Docker).value}/bin/${executableScriptName.value}")
)

dockerCommands := dockerCommands.value.filterNot {
  case ExecCmd("RUN", args @ _*) => args.contains("chown") && args.contains("-R") && args.contains(".")
  case cmd                       => false
}

bashScriptExtraDefines += """
mkdir -p log/$HOSTNAME
"""