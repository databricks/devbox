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
      val ignored =
        os.proc("git", "status", "--short", "--ignored")
          .call(cwd = base)
          .out
          .lines
          .collect{ case s"!! $name" => os.RelPath(name) }

      (p, isDir) => ignored.exists(p.startsWith(_))
    }
  }
}