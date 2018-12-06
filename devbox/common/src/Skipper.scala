package devbox.common

import devbox.common.Util.gitIgnoreToRegex
import devbox.common.Vfs.Node
import os.Path

import scala.collection.mutable

trait Skipper {
  def initialize(p: os.Path): (os.Path => Boolean)
  def checkReset(p: os.Path): Option[String]
}

object Skipper{
  def fromString(strategy: String): Skipper = strategy match{
    case "dotgit" => Skipper.DotGit
    case "gitignore" => Skipper.GitIgnore
    case "" => Skipper.Null
  }

  object Null extends Skipper {
    def initialize(p: Path): Path => Boolean = _ => false

    def checkReset(p: Path) = None
  }

  object DotGit extends Skipper {
    def initialize(base: Path): Path => Boolean = { path =>
      assert(path.startsWith(base), path + " " + base)
      path.relativeTo(base).segments.startsWith(Seq(".git"))
    }

    def checkReset(p: Path) = None
  }

  object GitIgnore extends  Skipper {
    def initialize(base: os.Path): os.Path => Boolean = {
      val listed = os.proc("git", "ls-files").call(cwd = base).out.lines.toSet

      type NodeType = Option[com.google.re2j.Pattern]
      def resolve(p: os.Path) = {
        if (!os.exists(p)) None
        else Some(gitIgnoreToRegex(base, p))
      }
      val gitIgnoreVfs = new Vfs[NodeType](resolve(base / ".gitignore"))
      for(gitignore <- os.walk.stream(base).filter(_.last == ".gitignore")){
        val rules = resolve(gitignore)
        if (rules.nonEmpty){
          var current = gitIgnoreVfs.root
          for((segment, i) <- gitignore.relativeTo(base).segments.toSeq.dropRight(1).zipWithIndex){
            if (!current.children.contains(segment)){
              current.children(segment) = new Vfs.Dir[NodeType](
                if (i == gitignore.relativeTo(base).segments.length - 2) rules else None,
                mutable.LinkedHashMap.empty[String, Node[NodeType]]
              )
            }
            current = current.children(segment).asInstanceOf[Vfs.Dir[NodeType]]
          }
        }
      }

      { path =>
        val pathString = path.relativeTo(base).toString
        assert(path.startsWith(base), path + " " + base)
        if (listed(pathString)) false
        else {
          var current = gitIgnoreVfs.root
          var parents = current.value.toList
          var continue = true
          for(segment <- path.relativeTo(base).segments if continue){
            if (current.children.contains(segment)){
              current = current.children(segment).asInstanceOf[Vfs.Dir[NodeType]]
              parents = current.value.toList ::: parents
            }else{
              continue = false
            }
          }
          parents.exists(_.matches(pathString + (if (os.isDir(path)) "/" else "")))
        }
      }
    }

    def checkReset(p: Path) =
      if (p.last != ".gitignore") None
      else Some(".gitignore file changed")
  }
}