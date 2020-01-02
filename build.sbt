import sbt._
import Keys._
import Settings._

ThisBuild / scalafmtOnCompile := true

val commonSettings = Seq(
  scalacOptions := scalacArgs,
  scalaVersion := "2.12.6",
  version := versions.fiddle,
  libraryDependencies ++= Seq()
)

val crossVersions = crossScalaVersions := Seq("2.12.10", "2.11.12")

lazy val root = project
  .in(file("."))
  .aggregate(shared, page, compilerServer, runtime, client, router)

lazy val shared = project
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .settings(crossVersions)

lazy val client = project
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(shared)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.scala-js"          %%% "scalajs-dom" % versions.dom,
      "com.github.marklister" %%% "base64"      % versions.base64
    ),
    //Compile / fullOptJS / scalaJSLinkerConfig ~= { _.withClosureCompilerIfAvailable(false) },
    // rename output always to -opt.js
    Compile / fastOptJS / artifactPath := ((Compile / fastOptJS / crossTarget).value /
      ((fastOptJS / moduleName).value + "-opt.js")),
    relativeSourceMaps := true
  )

lazy val page = project
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .settings(
    crossVersions,
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % versions.dom,
      "com.lihaoyi"  %%% "scalatags"   % versions.scalatags
    )
  )

lazy val runtime = project
  .settings(commonSettings)
  .settings(
    crossVersions,
    libraryDependencies ++= Seq(
      "org.scala-js"   %% "scalajs-library" % scalaJSVersion,
      "org.scala-lang" % "scala-reflect"    % scalaVersion.value
    )
  )

lazy val compilerServer = project
  .in(file("compiler-server"))
  .dependsOn(shared, page)
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(sbtdocker.DockerPlugin)
  .settings(commonSettings)
  .settings(Revolver.settings: _*)
  .settings(
    name := "scalafiddle-core",
    crossVersions,
    libraryDependencies ++= Seq(
      "org.scala-lang"         % "scala-compiler"   % scalaVersion.value,
      "org.scala-js"           % "scalajs-compiler" % scalaJSVersion cross CrossVersion.full,
      "org.scala-js"           %% "scalajs-tools"   % scalaJSVersion,
      "org.scalamacros"        %% "paradise"        % versions.macroParadise cross CrossVersion.full,
      "org.spire-math"         %% "kind-projector"  % versions.kindProjector cross CrossVersion.binary,
      "org.scala-lang.modules" %% "scala-async"     % versions.async % "provided",
      "com.lihaoyi"            %% "scalatags"       % versions.scalatags,
      "com.lihaoyi"            %% "upickle"         % versions.upickle,
      "io.get-coursier"        %% "coursier"        % versions.coursier,
      "io.get-coursier"        %% "coursier-cache"  % versions.coursier,
      "org.apache.maven"       % "maven-artifact"   % "3.3.9",
      "org.xerial.snappy"      % "snappy-java"      % "1.1.2.6",
      "org.xerial.larray"      %% "larray"          % "0.4.0"
    ) ++ kamon ++ akka ++ logging,
    (Compile / resources) ++= {
      (runtime / Compile / managedClasspath).value.map(_.data) ++ Seq(
        (page / Compile / packageBin).value
      )
    },
    resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/",
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
        repository = s"scalafiddle-core-${scalaBinaryVersion.value}",
        tag = Some("latest")
      ),
      ImageName(
        namespace = Some("scalafiddle"),
        repository = s"scalafiddle-core-${scalaBinaryVersion.value}",
        tag = Some(version.value)
      )
    )
  )

lazy val router = project
  .in(file("router"))
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(sbtdocker.DockerPlugin)
  .dependsOn(shared)
  .settings(Revolver.settings: _*)
  .settings(commonSettings)
  .settings(
    name := "scalafiddle-router",
    libraryDependencies ++= Seq(
      "com.lihaoyi"           %% "scalatags"      % versions.scalatags,
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
           |scalaVersion=${scalaVersion.value}
           |scalaJSVersion=$scalaJSVersion
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
