package devbox.common

import devbox.common
import devbox.common.Util.gitIgnoreToRegex
import devbox.common.Vfs.Node

import scala.collection.mutable

trait Skipper {
  def prepare(base: os.Path): (os.RelPath, Boolean) => Boolean
}

object Skipper{
  def fromString(strategy: String): Skipper = strategy match{
    case "dotgit" => Skipper.DotGit
    case "gitignore" => Skipper.GitIgnore
    case "" => Skipper.Null
  }

  object Null extends Skipper {
    def prepare(base: os.Path) = (_, _) => false
  }

  object DotGit extends Skipper {
    def prepare(base: os.Path) = (p, _) => p.segments.startsWith(Seq(".git"))
  }

  object GitIgnore extends Skipper {
    type NodeType = Option[com.google.re2j.Pattern]

    def prepare(base: os.Path) = {
      val vfsTree = new Vfs[NodeType](None)
      def check(p: os.RelPath, isDir: Boolean): Boolean = {
        var current = vfsTree.root
        var parents = current.value.toList
        var continue = true
        for (segment <- p.segments if continue) {
          if (!current.children.contains(segment)) continue = false
          else{
            current = current.children(segment).asInstanceOf[Vfs.Dir[NodeType]]
            parents = current.value.toList ::: parents
          }
        }
        parents.exists(_.matches(p.toString + (if (isDir) "/" else "")))
      }

      val walk = os.walk.stream.attrs(
        base,
        skip = (p, stat) => check(p.relativeTo(base), stat.isDir),
        includeTarget = true
      )

      for ((sub, attrs) <- walk) {
        if (attrs.isDir){
          val value =
            if (!os.exists(sub / ".gitignore")) None
            else Some(gitIgnoreToRegex(base, sub.relativeTo(base) / ".gitignore"))

          if (sub == base) vfsTree.root.value = value
          else {
            val (name, node) = vfsTree.resolveParent(sub.relativeTo(base)).get
            node.children(name) = new Vfs.Dir[NodeType](value, mutable.Map.empty)
          }
        }
      }

      check
    }
  }
}