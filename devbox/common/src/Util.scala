package devbox.common
import devbox.common
import devbox.common.Vfs.Node
import org.eclipse.jgit.ignore.FastIgnoreRule
import os.Path

import scala.collection.mutable

object Util {
  val blockSize = 4 * 1024 * 1024

  implicit val permsetRw: upickle.default.ReadWriter[os.PermSet] =
    upickle.default.readwriter[String].bimap[os.PermSet](
      _.toString(),
      os.PermSet.fromString
    )

  def autoclose[T <: AutoCloseable, V](x: T)(f: T => V) = {
    try f(x)
    finally x.close()
  }

  def ignoreCallback(strategy: String): Skipper = strategy match{
    case "dotgit" =>
      new Skipper {
        def initialize(base: Path): Path => Boolean = { path =>
          assert(path.startsWith(base), path + " " + base)
          path.relativeTo(base).segments.startsWith(Seq(".git"))
        }

        def checkReset(p: Path): Boolean = false
      }
    case "gitignore" =>
      new Skipper {
        def initialize(base: Path): Path => Boolean = {
          def resolve(p: Path) = {
            if (!os.exists(p)) Nil
            else os.read.lines(p).map(new FastIgnoreRule(_)).toList
          }
          val gitIgnoreVfs = new Vfs[List[FastIgnoreRule]](resolve(base / ".gitignore"))
          for(gitignore <- os.walk.stream(base).filter(_.last == ".gitignore")){
            val rules = resolve(gitignore)
            if (rules.nonEmpty){
              var current = gitIgnoreVfs.root
              for((segment, i) <- gitignore.relativeTo(base).segments.toSeq.dropRight(1).zipWithIndex){
                if (!current.children.contains(segment)){
                  current.children(segment) = new Vfs.Dir[List[FastIgnoreRule]](
                    if (i == gitignore.relativeTo(base).segments.length - 2) rules else Nil,
                    mutable.LinkedHashMap.empty[String, Node[List[FastIgnoreRule]]]
                  )
                }
                current = current.children(segment).asInstanceOf[Vfs.Dir[List[FastIgnoreRule]]]
              }
            }
          }

          for((p, n) <- gitIgnoreVfs.walk()){
            println("GITIGNOREVFS " + p + " " + n.value.length)
          }

          { path =>
            assert(path.startsWith(base), path + " " + base)
            var parents = gitIgnoreVfs.root.value
            var current = gitIgnoreVfs.root
            var continue = true
            for(segment <- path.relativeTo(base).segments if continue){
              if (current.children.contains(segment)){
                current = current.children(segment).asInstanceOf[Vfs.Dir[List[FastIgnoreRule]]]
                parents :::= current.value
              }else{
                continue = false
              }
            }
            parents.exists(rule =>
              rule.isMatch(path.relativeTo(base).toString, os.isDir(path)) ^ rule.getNegation
            )
          }
        }

        def checkReset(p: Path): Boolean = p.last == ".gitignore"
      }
    case "" =>
      new Skipper {
        def initialize(p: Path): Path => Boolean = _ => false

        def checkReset(p: Path): Boolean = false
      }
  }
}
