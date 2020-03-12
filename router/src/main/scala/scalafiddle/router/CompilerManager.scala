package scalafiddle.router

import akka.actor._
import scalafiddle.shared._

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

case class RegisterCompiler(id: String, compilerService: ActorRef, scalaVersion: String, scalaJSVersion: String)

case class UnregisterCompiler(id: String)

case class UpdateState(id: String, newState: CompilerState)

case class CancelCompilation(id: String)

case class CancelCompletion(id: String)

case object RefreshLibraries

case object CheckCompilers

class CompilerManager extends Actor with ActorLogging {
  import CompilerManager._

  val compilers          = mutable.Map.empty[String, CompilerInfo]
  var compilerQueue      = mutable.Queue.empty[(CompilerRequest, ActorRef)]
  val compilationPending = mutable.Map.empty[String, ActorRef]
  var currentLibs        = Map.empty[(String, String), Set[ExtLib]]
  var compilerTimer      = context.system.scheduler.schedule(5.minute, 1.minute, context.self, CheckCompilers)
  val dependencyRE       = """ *// \$FiddleDependency (.+)""".r
  val scalaVersionRE     = """ *// \$ScalaVersion (.+)""".r
  val scalaJSVersionRE   = """ *// \$ScalaJSVersion (.+)""".r
  val defaultLibs =
    Config.defaultLibs.mapValues(_.map(lib => s"// $$FiddleDependency $lib").mkString("\n", "\n", "\n"))

  def now = System.currentTimeMillis()

  override def preStart(): Unit = {
    super.preStart()
    // set internal HTTP agent to something valid
    System.setProperty(
      "http.agent",
      "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/28.0.1500.29 Safari/537.36"
    )
    // try to load libraries
    currentLibs = loadLibraries
    if (currentLibs.isEmpty) {
      // try again soon
      context.system.scheduler.scheduleOnce(5.seconds, context.self, RefreshLibraries)
    } else {
      context.system.scheduler.scheduleOnce(Config.refreshLibraries, context.self, RefreshLibraries)
    }
  }

  override def postStop(): Unit = {
    compilerTimer.cancel()
    super.postStop()
  }

  def loadLibraries: Map[(String, String), Set[ExtLib]] = {
    val url = Config.extLibsUrl
    val result: Map[(String, String), Set[ExtLib]] =
      try {
        log.debug(s"Loading libraries from $url")
        val data = if (url.startsWith("file:")) {
          // load from file system
          scala.io.Source.fromFile(url.drop(5), "UTF-8").mkString
        } else if (url.startsWith("http")) {
          // load from internet
          scala.io.Source.fromURL(url, "UTF-8").mkString
        } else {
          // load from resources
          scala.io.Source.fromInputStream(getClass.getResourceAsStream(url), "UTF-8").mkString
        }
        val extLibs = Librarian.loadLibraries(data)
        // join with default libs
        extLibs.map {
          case (versions, libs) => versions -> (libs ++ Config.defaultLibs.getOrElse(versions._1, Nil))
        }
      } catch {
        case e: Throwable =>
          log.error(e, s"Unable to load libraries")
          Map.empty
      }
    result.foreach {
      case ((scalaVersion, scalaJSVersion), libs) =>
        log.debug(s"${libs.size} libraries for Scala $scalaVersion and Scala.js $scalaJSVersion")
    }
    result
  }

  private def extractLibs(source: String): (Set[ExtLib], String, String) = {
    val codeLines = source.replaceAll("\r", "").split('\n')
    val libs = codeLines.collect {
      case dependencyRE(dep) => ExtLib(dep)
    }.toSet
    val scalaVersion = codeLines
      .collectFirst {
        case scalaVersionRE(v) => v
      }
      .getOrElse("2.11")
    val scalaJSVersion = codeLines
      .collectFirst {
        case scalaJSVersionRE(v) => v
      }
      .getOrElse("0.6")
    (libs, scalaVersion, scalaJSVersion)
  }

  def selectCompiler(req: CompilerRequest): Option[CompilerInfo] = {
    // extract libs from the source
    // log.debug(s"Source\n${req.source}")
    val (libs, scalaVersion, scalaJSVersion) = extractLibs(req.source)

    log.debug(s"Selecting compiler for Scala $scalaVersion and libs $libs")
    // check that all libs are supported
    //    val versionLibs = currentLibs.getOrElse((scalaVersion, scalaJSVersion), Set.empty)
    val versionLibs = currentLibs.getOrElse((scalaVersion, "0.6"), Set.empty)

    libs.foreach(lib =>
      if (!versionLibs.exists(_.sameAs(lib))) throw new IllegalArgumentException(s"Library $lib is not supported")
    )
    // select the best available compiler server based on:
    // 1) time of last activity
    // 2) set of libraries
    compilers.values.toSeq
      .filter(c => c.state == CompilerState.Ready && c.scalaVersion == scalaVersion && c.scalaJSVersion == scalaJSVersion)
      .sortBy(_.lastActivity)
      .zipWithIndex
      .sortBy(info => if (info._1.lastLibs == libs) -1 else info._2) // use index to ensure stable sort
      .headOption
      .map(_._1.copy(lastLibs = libs))
  }

  def updateCompilerState(id: String, newState: CompilerState): Unit = {
    if (compilers.contains(id)) {
      compilers.update(id, compilers(id).copy(state = newState, lastActivity = now))
    }
  }

  def compilerSeen(id: String): Unit = {
    if (compilers.contains(id)) {
      compilers.update(id, compilers(id).copy(lastSeen = now))
    }
  }

  def processQueue(): Unit = {
    if (compilerQueue.nonEmpty) {
      val (req, sourceActor) = compilerQueue.dequeue()
      try {
        selectCompiler(req) match {
          case Some(compilerInfo) =>
            compilers.update(compilerInfo.id, compilerInfo.copy(state = CompilerState.Compiling))
            compilationPending += compilerInfo.id -> sourceActor
            // add default libs
            compilerInfo.compilerService ! req.updated(src => src + defaultLibs(compilerInfo.scalaVersion))
            // process next in queue
            processQueue()
          case None if compilers.isEmpty =>
            // no compilers registered at this point
            log.error("No compiler instance currently registered")
            sourceActor ! Left("No compiler instance currently registered")
          case None =>
            // no compiler available
            log.error("No suitable compiler available")
            sourceActor ! Left("No suitable compiler available")
        }
      } catch {
        case e: Throwable =>
          log.error(e, s"Compilation failed")
          sourceActor ! Left(e.getMessage)
      }
    }
  }

  def receive = {
    case RegisterCompiler(id, compilerService, scalaVersion, scalaJSVersion) =>
      compilers += id -> CompilerInfo(
        id,
        compilerService,
        scalaVersion,
        scalaJSVersion,
        CompilerState.Initializing,
        now,
        "unknown",
        Set.empty,
        now
      )
      log.debug(s"Registered compiler $id for Scala $scalaVersion")
      // send current libraries
      // todo: scalaJSVersion is currently always 0.6
      val libs = currentLibs.getOrElse((scalaVersion, "0.6"), Set.empty).toList
      //      val libs = currentLibs.getOrElse((scalaVersion, scalaJSVersion), Set.empty).toList
      compilerService ! UpdateLibraries(libs)
      context.watch(compilerService)

    case UnregisterCompiler(id) =>
      compilers.get(id).foreach(info => context.unwatch(info.compilerService))
      compilers -= id

    case Terminated(compilerService) =>
      // check if it still exist in the map
      compilers.find(_._2.compilerService == compilerService) match {
        case Some((id, info)) =>
          compilers -= id
        case _ =>
      }

    case CompilerPing(id) =>
      compilerSeen(id)

    case UpdateState(id, newState) =>
      updateCompilerState(id, newState)

    case req: CompilerRequest =>
      // add to the queue
      compilerQueue.enqueue((req, sender()))
      processQueue()

    case CancelCompilation(id) =>
      compilerQueue = compilerQueue.filterNot(_._1.id == id)

    case (id: String, CompilerReady) =>
      log.info(s"Compiler $id is now ready")
      updateCompilerState(id, CompilerState.Ready)
      processQueue()

    case (id: String, response: CompilerResponse) =>
      log.debug(s"Received compiler response from $id")
      updateCompilerState(id, CompilerState.Ready)
      compilationPending.get(id) match {
        case Some(actor) =>
          compilationPending -= id
          actor ! Right(response)
        case None =>
          log.error(s"No compilation pending for compiler $id")
      }
      processQueue()

    case RefreshLibraries =>
      try {
        log.info("Refreshing libraries")
        val newLibs = loadLibraries
        // are there any changes?
        if (newLibs.nonEmpty && newLibs != currentLibs) {
          currentLibs = newLibs
          // inform all connected compilers
          compilers.values.foreach { comp =>
            val libs = currentLibs((comp.scalaVersion, comp.scalaJSVersion)).toList
            comp.compilerService ! UpdateLibraries(libs)
          }
        }
      } catch {
        case e: Throwable =>
          log.error(s"Error while refreshing libraries", e)
      }
      if (currentLibs.isEmpty) {
        // try again soon
        context.system.scheduler.scheduleOnce(5.seconds, context.self, RefreshLibraries)
      } else {
        context.system.scheduler.scheduleOnce(Config.refreshLibraries, context.self, RefreshLibraries)
      }

    case CheckCompilers =>
      compilers.foreach {
        case (id, compiler) =>
          if (now - compiler.lastSeen > 120 * 1000) {
            log.error(s"Compiler service $id not seen in ${(now - compiler.lastSeen) / 1000} seconds, terminating compiler")
            context.stop(compiler.compilerService)
          }
      }

    case other =>
      log.error(s"Received unknown message $other")
  }
}

object CompilerManager {
  def props = Props(new CompilerManager)

  case class CompilerInfo(
      id: String,
      compilerService: ActorRef,
      scalaVersion: String,
      scalaJSVersion: String,
      state: CompilerState,
      lastActivity: Long,
      lastClient: String,
      lastLibs: Set[ExtLib],
      lastSeen: Long
  )

}
