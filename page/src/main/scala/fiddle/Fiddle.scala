package fiddle

import org.scalajs.dom
import org.scalajs.dom.{CanvasRenderingContext2D, html}
import org.scalajs.dom.html.{Canvas, Div}

import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.util.Try
import scalatags.JsDom.all._

import scala.scalajs.js.timers.{SetIntervalHandle, SetTimeoutHandle}

/**
  * API for things that belong to the page, and are useful to both the fiddle
  * client, user code as well as exported read-only pages.
  */
@JSExportTopLevel("Fiddle")
object Fiddle {

  object colors {
    def red    = span(color := "#E95065")
    def blue   = span(color := "#46BDDF")
    def green  = span(color := "#52D273")
    def yellow = span(color := "#E5C453")
    def orange = span(color := "#E57255")
  }

  /**
    * Gets the element from the given ID and casts it,
    * shortening that common pattern
    */
  private def getElem[T](id: String): T = dom.document.getElementById(id).asInstanceOf[T]

  val sandbox: Div                   = getElem[html.Div]("container")
  val canvas: Canvas                 = getElem[html.Canvas]("canvas")
  val draw: CanvasRenderingContext2D = canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
  val panel: Div                     = getElem[html.Div]("output")

  def println(ss: Any): Unit = {
    ss match {
      case null =>
        print(div(cls := "monospace", "null"))
      case m: Modifier @unchecked =>
        print(div(m))
      case _ =>
        print(div(cls := "monospace", ss.toString))
    }
  }

  def printDiv(ss: Modifier*): Unit = {
    print(div(ss))
  }

  def print(ss: Modifier*): Unit = {
    ss.foreach(_.applyTo(panel))
    panel.scrollTop = panel.scrollHeight - panel.clientHeight
  }

  def clear(): Unit = {
    // clear panel and canvas
    panel.innerHTML = ""
    canvas.height = sandbox.clientHeight
    canvas.width = sandbox.clientWidth
    draw.clearRect(0, 0, 10000, 10000)
  }

  def defer[T](t: => T): Future[T] = {
    val p = Promise[T]()
    scala.scalajs.concurrent.JSExecutionContext.queue.execute(
      new Runnable {
        def run(): Unit = p.complete(Try(t))
      }
    )
    p.future
  }

  def scheduleOnce(delay: Int)(f: => Unit): SetTimeoutHandle = {
    val handle = js.timers.setTimeout(delay)(f)
    handle
  }

  def schedule(interval: Int)(f: => Unit): SetIntervalHandle = {
    val handle = js.timers.setInterval(interval)(f)
    handle
  }

  def loadJS(url: String): Future[Unit] = {
    val script = dom.document.createElement("script").asInstanceOf[html.Script]
    script.`type` = "text/javascript"
    script.src = url
    val p = Promise[Unit]
    script.onload = (e: dom.Event) => p.success(())
    dom.document.body.appendChild(script)
    p.future
  }

  def loadCSS(url: String): Unit = {
    val link = dom.document.createElement("link").asInstanceOf[html.Link]
    link.rel = "stylesheet"
    link.href = url
    dom.document.head.appendChild(link)
  }

}
