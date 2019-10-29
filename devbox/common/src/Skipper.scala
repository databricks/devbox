package devbox.common

import org.eclipse.jgit.ignore.{FastIgnoreRule, IgnoreNode}


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
    val ignorePaths = collection.mutable.Map.empty[os.SubPath, (Int, IgnoreNode)]

    val ignoreCache = collection.mutable.Map.empty[
      (IndexedSeq[String], Int, Boolean),
      Boolean
    ]

    def process(base: os.Path, paths: Set[(os.SubPath, Boolean)]) = {
      val newIgnorePaths = paths.filter(_._1.last == ".gitignore").map(_._1)

      for(p <- ignorePaths.keySet | newIgnorePaths){
        val isFile = os.isFile(base / p, followLinks = false)
        if (!isFile && ignorePaths.contains(p)) {
          ignorePaths.remove(p)
          ignoreCache.clear()
        } else if (isFile) {
          val lines = try os.read.lines(base / p) catch{case e: Throwable => Nil }
          val linesHash = lines.hashCode
          if (!ignorePaths.get(p).exists(_._1 == linesHash)){

            import collection.JavaConverters._

            val n = new IgnoreNode(
              lines
                .filter(l => l != "" && l(0) != '#' && l != "/")
                .map(new FastIgnoreRule(_))
                .asJava
            )

            ignorePaths(p) = (linesHash, n)
            ignoreCache.clear()
          }
        }
      }

      paths
        .filter{ case (p, isDir) =>
          val ignored = for {
            (enclosingPartialPath, i) <- p.segments.inits.zipWithIndex
            if enclosingPartialPath.nonEmpty
            gitIgnoreRoot <- enclosingPartialPath.inits.drop(1)
            (linesHash, ignoreNode) <- ignorePaths.get(os.sub / gitIgnoreRoot / ".gitignore")
          } yield {
            ignoreCache.getOrElseUpdate(
              (enclosingPartialPath, gitIgnoreRoot.length, isDir),
              ignoreNode.isIgnored(
                enclosingPartialPath.drop(gitIgnoreRoot.length).mkString("/"),
                if (i == 0) isDir else true
              )  match{
                case IgnoreNode.MatchResult.IGNORED => true
                case IgnoreNode.MatchResult.NOT_IGNORED => false
                case _ => false
              }
            )

          }
          !ignored.exists(identity)
        }
        .map(_._1)
    }
  }
}