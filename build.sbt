import sbt._
import Keys._
import Settings._

// shadow sbt-scalajs' crossProject and CrossType from Scala.js 0.6.x
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

val scalaJS06xVersion = "0.6.31"
val scalaJS06xCrossVersion = CrossVersion.binaryWith(prefix = "sjs0.6_", suffix = "")

val scalaJS1xVersion = "1.0.0"
val scalaJS1xCrossVersion = CrossVersion.binaryWith(prefix = "sjs1_", suffix = "")

def scalaJSBinVersion(scalaJSVersion: String): String =
  if (scalaJSVersion.startsWith("0.6.")) "0.6"
  else "1"

inThisBuild(Def.settings(
  scalafmtOnCompile := true,
  scalacOptions := scalacArgs,
  scalaVersion := "2.12.10",
  version := versions.fiddle,
))

val crossVersions = crossScalaVersions := Seq("2.12.10", "2.11.12")

lazy val root = project
  .in(file("."))
  .aggregate(
    shared.js, shared.jvm, client, router,
    page06x, compilerServer06x, runtime06x,
    page1x, compilerServer1x, runtime1x
  )

lazy val shared = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .settings(crossVersions)

lazy val client = project
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(shared.js)
  .settings(
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % versions.dom
    ),
    //Compile / fullOptJS / scalaJSLinkerConfig ~= { _.withClosureCompilerIfAvailable(false) },
    // rename output always to -opt.js
    Compile / fastOptJS / artifactPath := ((Compile / fastOptJS / crossTarget).value /
      ((fastOptJS / moduleName).value + "-opt.js")),
    scalaJSLinkerConfig := {
      val artifactPathURI = (Compile / fastOptJS / artifactPath).value.toURI()
      scalaJSLinkerConfig.value
        .withRelativizeSourceMapBase(Some(artifactPathURI))
    }
  )

/* This project is configured so that it *compiles* as if it were a Scala.js
 * project (with the Scala.js compiler plugin, and the Scala.js library on the
 * classpath) but without using the sbt plugin `ScalaJSPlugin`. We do not link
 * it from sbt (only programmatically from a compiler service) so that is not
 * necessary. This setup allows to decouple the version of Scala.js used for
 * `page` from the one used for `client` and its transitive dependencies.
 */
def pageProject(p: Project, suffix: String, scalaJSVersion: String, scalaJSCrossVersion: CrossVersion): Project = {
  p.settings(
    name := "page",
    Compile / sourceDirectory := baseDirectory.value.getParentFile / "page/src/main",
    crossVersions,
    platformDepsCrossVersion := scalaJSCrossVersion,
    crossVersion := scalaJSCrossVersion,
    addCompilerPlugin("org.scala-js" % "scalajs-compiler" % scalaJSVersion cross CrossVersion.full),
    libraryDependencies ++= Seq(
      "org.scala-js" %% "scalajs-library" % scalaJSVersion,
      "org.scala-js" %%% "scalajs-dom"    % versions.dom,
      "com.lihaoyi"  %%% "scalatags"      % versions.scalatags(suffix)
    )
  )
}
lazy val page06x = pageProject(project, "06x", scalaJS06xVersion, scalaJS06xCrossVersion)
lazy val page1x = pageProject(project, "1x", scalaJS1xVersion, scalaJS1xCrossVersion)

/* This project is not compiled. It is only used to easily resolve
 * dependencies.
 * TODO We might want to replace this setup with direct usage of the
 * librarymanagement API.
 */
def runtimeProject(p: Project, scalaJSVersion: String): Project = {
  p.settings(
    crossVersions,
    libraryDependencies ++= Seq(
      "org.scala-js"   %% "scalajs-library" % scalaJSVersion,
      "org.scala-lang" % "scala-reflect"    % scalaVersion.value
    )
  )
}
lazy val runtime06x = runtimeProject(project, scalaJS06xVersion)
lazy val runtime1x = runtimeProject(project, scalaJS1xVersion)

def compilerServerProject(p: Project, suffix: String, scalaJSVersion: String): Project = p
  .dependsOn(shared.jvm)
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(sbtdocker.DockerPlugin)
  .settings(Revolver.settings: _*)
  .settings(
    name := "scalafiddle-core",
    Compile / sourceDirectory := baseDirectory.value.getParentFile / "compiler-server/src/main",
    Compile / unmanagedSourceDirectories += (Compile / sourceDirectory).value / s"scala-sjs$suffix",
    crossVersions,
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-compiler"   % scalaVersion.value,
      "org.scala-js"   % "scalajs-compiler" % scalaJSVersion cross CrossVersion.full,
      {
        if (scalaJSVersion.startsWith("0.6."))
          "org.scala-js" %% "scalajs-tools" % scalaJSVersion
        else
          "org.scala-js" %% "scalajs-linker" % scalaJSVersion
      },
      "org.scalamacros"   %% "paradise"        % versions.macroParadise cross CrossVersion.full,
      "org.spire-math"    %% "kind-projector"  % versions.kindProjector cross CrossVersion.binary,
      "com.lihaoyi"       %% "upickle"         % versions.upickle,
      "io.get-coursier"   %% "coursier"        % versions.coursier,
      "io.get-coursier"   %% "coursier-cache"  % versions.coursier,
      "org.apache.maven"  % "maven-artifact"   % "3.3.9",
      "org.xerial.snappy" % "snappy-java"      % "1.1.2.6",
      "org.xerial.larray" %% "larray"          % "0.4.0"
    ) ++ kamon ++ akka ++ logging,
    (Compile / resources) ++= {
      (LocalProject(s"runtime$suffix") / Compile / managedClasspath).value.map(_.data) ++ Seq(
        (LocalProject(s"page$suffix") / Compile / packageBin).value
      )
    },
    resolvers += "Typesafe Repo" at "https://repo.typesafe.com/typesafe/releases/",
    reStart / javaOptions ++= Seq("-Xmx3g", "-Xss4m"),
    Universal / javaOptions ++= Seq("-J-Xss4m"),
    Compile / resourceGenerators += Def.task {
      // store build a / version property file
      val file = (Compile / resourceManaged).value / "version.properties"
      val contents =
        s"""
           |version=${version.value}
           |scalaVersion=${scalaVersion.value}
           |scalaJSVersion=$scalaJSVersion
           |aceVersion=${versions.ace}
           |""".stripMargin
      IO.write(file, contents)
      Seq(file)
    }.taskValue,
    scriptClasspath := Seq("../config/") ++ scriptClasspath.value,
    docker / dockerfile := {
      val appDir: File = stage.value
      val targetDir    = "/app"

      new Dockerfile {
        from("anapsix/alpine-java:8_jdk")
        entryPoint(s"$targetDir/bin/${executableScriptName.value}")
        copy(appDir, targetDir)
      }
    },
    docker / imageNames := Seq(
      ImageName(
        namespace = Some("scalafiddle"),
        repository = s"scalafiddle-core-${scalaBinaryVersion.value}-sjs${scalaJSBinVersion(scalaJSVersion)}",
        tag = Some("latest")
      ),
      ImageName(
        namespace = Some("scalafiddle"),
        repository = s"scalafiddle-core-${scalaBinaryVersion.value}-sjs${scalaJSBinVersion(scalaJSVersion)}",
        tag = Some(version.value)
      )
    )
  )

lazy val compilerServer06x = compilerServerProject(project, "06x", scalaJS06xVersion)
lazy val compilerServer1x = compilerServerProject(project, "1x", scalaJS1xVersion)

lazy val router = project
  .in(file("router"))
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(sbtdocker.DockerPlugin)
  .dependsOn(shared.jvm)
  .settings(Revolver.settings: _*)
  .settings(
    name := "scalafiddle-router",
    libraryDependencies ++= Seq(
      "com.lihaoyi"           %% "scalatags"      % versions.scalatags("jvm"),
      "org.webjars"           % "ace"             % versions.ace,
      "org.webjars"           % "normalize.css"   % "2.1.3",
      "org.webjars"           % "jquery"          % "2.2.2",
      "org.webjars.npm"       % "js-sha1"         % "0.4.0",
      "com.lihaoyi"           %% "upickle"        % versions.upickle,
      "com.github.marklister" %% "base64"         % versions.base64,
      "ch.megard"             %% "akka-http-cors" % "0.3.0"
    ) ++ kamon ++ akka ++ logging,
    reStart / javaOptions ++= Seq("-Xmx1g"),
    scriptClasspath := Seq("../config/") ++ scriptClasspath.value,
    Compile / resourceGenerators += Def.task {
      // store build a / version property file
      val file = (Compile / resourceManaged).value / "version.properties"
      val contents =
        s"""
           |version=${version.value}
           |aceVersion=${versions.ace}
           |""".stripMargin
      IO.write(file, contents)
      Seq(file)
    }.taskValue,
    (Compile / resources) ++= {
      // Seq((client / Compile / fullOptJS).value.data)
      Seq((client / Compile / fastOptJS).value.data)
    },
    docker / dockerfile := {
      val appDir: File = stage.value
      val targetDir    = "/app"

      new Dockerfile {
        from("anapsix/alpine-java:8_jdk")
        entryPoint(s"$targetDir/bin/${executableScriptName.value}")
        copy(appDir, targetDir)
        expose(8880)
      }
    },
    docker / imageNames := Seq(
      ImageName(
        namespace = Some("scalafiddle"),
        repository = "scalafiddle-router",
        tag = Some("latest")
      ),
      ImageName(
        namespace = Some("scalafiddle"),
        repository = "scalafiddle-router",
        tag = Some(version.value)
      )
    )
  )
