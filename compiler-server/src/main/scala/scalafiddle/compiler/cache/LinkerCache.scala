package scalafiddle.compiler.cache

import scalafiddle.compiler.ScalaJSCompat.Linker

object LinkerCache extends LRUCache[Linker]("Linker") {}
