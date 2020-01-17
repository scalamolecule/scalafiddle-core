package scalafiddle.compiler

import org.scalajs.core.tools.io.MemVirtualSerializedScalaJSIRFile
import org.scalajs.core.tools.io.WritableMemVirtualJSFile
import org.scalajs.core.tools.io.RelativeVirtualFile
import org.scalajs.core.tools.io.VirtualScalaJSIRFile

import org.scalajs.core.tools.linker.StandardLinker

import scalafiddle.compiler.cache.{AbstractFlatJar, FlatFileSystem, FlatJar}

object ScalaJSCompat {
  type Level = org.scalajs.core.tools.logging.Level
  val Level = org.scalajs.core.tools.logging.Level

  type ScalaJSCompilerPlugin = org.scalajs.core.compiler.ScalaJSPlugin

  type IRFile = org.scalajs.core.tools.io.VirtualScalaJSIRFile

  def memIRFile(path: String, content: Array[Byte]): IRFile = {
    val f = new MemVirtualSerializedScalaJSIRFile(path)
    f.content = content
    f
  }

  type IRFileCache = org.scalajs.core.tools.io.IRFileCache

  def createGlobalIRCache(): IRFileCache =
    new org.scalajs.core.tools.io.IRFileCache()

  type IRContainer = org.scalajs.core.tools.io.IRFileCache.IRContainer
  val IRContainer = org.scalajs.core.tools.io.IRFileCache.IRContainer

  def flatJarFileToIRContainer(jar: AbstractFlatJar, ffs: FlatFileSystem): IRContainer = {
    val jarFile = new VirtualFlatJarFile(jar.flatJar, ffs)
    org.scalajs.core.tools.io.IRFileCache.IRContainer.Jar(jarFile)
  }

  def loadIRFilesInIRContainers(globalIRCache: IRFileCache, containers: Seq[IRContainer]): Seq[IRFile] = {
    val cache = globalIRCache.newCache
    cache.cached(containers)
  }

  type Semantics = org.scalajs.core.tools.sem.Semantics
  val Semantics = org.scalajs.core.tools.sem.Semantics

  type LinkerConfig = StandardLinker.Config

  def defaultLinkerConfig: LinkerConfig =
    StandardLinker.Config()

  type Linker = org.scalajs.core.tools.linker.Linker

  def createLinker(config: LinkerConfig): Linker =
    StandardLinker(config)

  type MemJSFile = org.scalajs.core.tools.io.MemVirtualJSFile

  type Logger = org.scalajs.core.tools.logging.Logger

  def link(linker: Linker, irFiles: Seq[IRFile], logger: Logger): MemJSFile = {
    val output = WritableMemVirtualJSFile("output.js")
    linker.link(irFiles, Nil, output, logger)
    output
  }

  def memJSFileContentAsString(file: MemJSFile): String =
    file.content

  private class JarEntryIRFile(outerPath: String, val relativePath: String)
      extends MemVirtualSerializedScalaJSIRFile(s"$outerPath:$relativePath")
      with RelativeVirtualFile

  private class VirtualFlatJarFile(flatJar: FlatJar, ffs: FlatFileSystem) extends org.scalajs.core.tools.io.VirtualJarFile {

    override def content: Array[Byte] = null
    override def path: String         = flatJar.name
    override def exists: Boolean      = true

    override def sjsirFiles: Seq[VirtualScalaJSIRFile with RelativeVirtualFile] = {
      flatJar.files.filter(_.path.endsWith("sjsir")).map { file =>
        val content = ffs.load(flatJar, file.path)
        new JarEntryIRFile(flatJar.name, file.path).withContent(content).withVersion(Some(path))
      }
    }
  }
}
