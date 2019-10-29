package devbox.common

import org.eclipse.jgit.ignore.{FastIgnoreRule, IgnoreNode}
import org.eclipse.jgit.internal.storage.file.FileRepository


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

    var gitIndexCache = Option.empty[Set[os.SubPath]]

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

      paths.find(_._1 == os.sub / ".git" / "index").foreach{ case (indexFile, _) =>
        val repo = new FileRepository((base / ".git").toIO)
        gitIndexCache = Some(
          org.eclipse.jgit.dircache.DirCache.read(repo)
            .getEntriesWithin("")
            .flatMap(_.getPathString.split('/').inits)
            .map(_.toIndexedSeq)
            .toSet
            .map((x: IndexedSeq[String]) => os.SubPath(x))
        )
        repo.close()
      }


      paths
        .filter{ case (p, isDir) =>
          lazy val indexed = gitIndexCache.exists(_.contains(p))
          lazy val ignoredEntries = for {
            (enclosingPartialPath, i) <- p.segments.inits.zipWithIndex
            if enclosingPartialPath.nonEmpty
            gitIgnoreRoot <- enclosingPartialPath.inits.drop(1)
            (linesHash, ignoreNode) <- ignorePaths.get(os.sub / gitIgnoreRoot / ".gitignore")
          } yield {
            val enclosedPartialPathStr = enclosingPartialPath.drop(gitIgnoreRoot.length).mkString("/")

            val ignored = ignoreCache.getOrElseUpdate(
              (enclosingPartialPath, gitIgnoreRoot.length, isDir),
              ignoreNode.isIgnored(enclosedPartialPathStr, if (i == 0) isDir else true) match{
                case IgnoreNode.MatchResult.IGNORED => true
                case IgnoreNode.MatchResult.NOT_IGNORED => false
                case _ => false
              }
            )
            ignored

          }

          lazy val notIgnored = !ignoredEntries.exists(identity)
          val res = indexed || notIgnored
          if (!res) pprint.log((p, isDir, indexed, notIgnored))
          res
        }
        .map(_._1)
    }
  }
}