package devbox.common

import os.Path


trait Skipper {
  def initialize(p: os.Path): (os.Path, Boolean) => Boolean
}

object Skipper{
  def fromString(strategy: String): Skipper = strategy match{
    case "dotgit" => Skipper.DotGit
    case "gitignore" => Skipper.GitIgnore
    case "" => Skipper.Null
  }

  object Null extends Skipper {
    def initialize(p: Path): (Path, Boolean) => Boolean = (_, _) => false
  }

  object DotGit extends Skipper {
    def initialize(base: Path): (Path, Boolean) => Boolean = { (path, isDir) =>
      assert(path.startsWith(base), path + " " + base)
      path.relativeTo(base).segments.startsWith(Seq(".git"))
    }
  }

  object GitIgnore extends  Skipper {
    def initialize(base: os.Path): (os.Path, Boolean) => Boolean = {

      val gitListed = os
        .proc("git", "ls-files")
        .call(cwd = base)
        .out
        .lines
        .map(os.RelPath(_))
        .toSet

      { (path, isDir) => !gitListed.contains(path.relativeTo(base)) }
    }
  }
}