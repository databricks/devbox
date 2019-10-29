package devbox.common

import devbox.common
import org.eclipse.jgit.ignore.IgnoreNode


trait Skipper {
  def process(base: os.Path, paths: Set[(os.SubPath, Boolean)]): Set[os.SubPath]
}

object Skipper{
  def fromString(strategy: String): Skipper = strategy match{
    case "dotgit" => Skipper.DotGit
    case "gitignore" => new Skipper.GitIgnore()
    case "" => Skipper.Null
  }

  object Null extends Skipper {
    def process(base: os.Path, paths: Set[(os.SubPath, Boolean)]) = paths.map(_._1)
  }

  object DotGit extends Skipper {
    def process(base: os.Path, paths: Set[(os.SubPath, Boolean)]) = paths.map(_._1).filter(!_.segments(0).contains(".git"))
  }

  class GitIgnore() extends Skipper {
    val ignoreTree = new Vfs[Option[IgnoreNode]](None)

    def process(base: os.Path, paths: Set[(os.SubPath, Boolean)]) = {
      val gitIgnorePaths = paths.filter(_._1.last == ".gitignore")
      for((gitIgnore, _) <- gitIgnorePaths){
        var current = ignoreTree.root
        for(segment <- gitIgnore.segments.dropRight(1)){
          current = current.children.get(segment) match {
            case Some(d: Vfs.Dir[Option[IgnoreNode]]) => d
            case _=> new common.Vfs.Dir[Option[IgnoreNode]](None, collection.mutable.Map())
          }
        }
        current.value =
          if (!os.exists(base / gitIgnore)) None
          else {
            val n = new IgnoreNode()
            n.parse(os.read.inputStream(base / gitIgnore))
            Some(n)
          }
      }

      paths
        .filter{ case (p, isDir) =>
          var current = ignoreTree.root
          var done = false
          var ignored = false
          for((segment, i) <- p.segments.dropRight(1).zipWithIndex if !done){
            if (current.value.exists(_.checkIgnored(p.segments.drop(i).mkString("/"), isDir))){
              ignored = true
            }
            current.children.get(segment) match {
              case Some(d: Vfs.Dir[Option[IgnoreNode]]) => current = d
              case _=> done = true
            }
          }
          !ignored
        }
        .map(_._1)
    }
  }
}