package devbox.common

import org.eclipse.jgit.ignore.{FastIgnoreRule, IgnoreNode}
import org.eclipse.jgit.internal.storage.file.FileRepository

import scala.collection.mutable


trait Skipper {
  def processBatch(base: os.Path, paths: Set[(os.SubPath, Boolean)]): Set[os.SubPath]
  def processInitialScanSingle(base: os.Path, path: os.SubPath, isDir: Boolean): Boolean
}

object Skipper{
  def fromString(strategy: String): Skipper = strategy match{
    case "dotgit" => Skipper.DotGit
    case "gitignore" => new Skipper.GitIgnore()
    case "" => Skipper.Null
  }

  object Null extends Skipper {
    def processBatch(base: os.Path, paths: Set[(os.SubPath, Boolean)]) = paths.map(_._1)
    def processInitialScanSingle(base: os.Path, path: os.SubPath, isDir: Boolean) = false
  }

  object DotGit extends Skipper {
    def processBatch(base: os.Path, paths: Set[(os.SubPath, Boolean)]) = {
      paths.map(_._1).filter(!_.segments.lift(0).contains(".git"))
    }
    def processInitialScanSingle(base: os.Path, path: os.SubPath, isDir: Boolean) = {
      path.segments.lift(0).contains(".git")
    }
  }

  class GitIgnore() extends Skipper {
    val ignorePaths = collection.mutable.Map.empty[os.SubPath, (Int, IgnoreNode)]

    val ignoreCache = collection.mutable.Map.empty[
      (IndexedSeq[String], Int, Boolean),
      Boolean
    ]

    case class GitIndexTree(value: mutable.LinkedHashMap[String, GitIndexTree] = mutable.LinkedHashMap.empty){
      def containsPath(segments: Seq[String]): Boolean = {
        segments
          .foldLeft(Option(this)) {
            case (None, _) => None
            case (Some(node), segment) => node.value.get(segment)
          }
          .nonEmpty
      }

      def createPath(segments: Seq[String]): Unit = {
        var current = this
        for(segment <- segments){
          current = current.value.getOrElseUpdate(segment, GitIndexTree())
        }
      }
    }

    val gitIndexCache = GitIndexTree()

    def updateIgnoreCache(base: os.Path, path: os.SubPath) = {
      if (os.isFile(base / path, followLinks = false)){
        val lines = try os.read.lines(base / path) catch{case e: Throwable => Nil }
        val linesHash = lines.hashCode
        if (!ignorePaths.get(path / os.up).exists(_._1 == linesHash)) {

          import collection.JavaConverters._

          val n = new IgnoreNode(
            lines
              .filter(l => l != "" && l(0) != '#' && l != "/")
              .map(new FastIgnoreRule(_))
              .asJava
          )

          ignorePaths(path / os.up) = (linesHash, n)
          ignoreCache.clear()
        }
      }
    }

    def updateIndexCache(base: os.Path, path: os.SubPath) = {
      if (path == os.sub / ".git" / "index" && os.isFile(base / path, followLinks = false)){
        val repo = new FileRepository((base / ".git").toIO)
        gitIndexCache.value.clear()

        for(e <- org.eclipse.jgit.dircache.DirCache.read(repo).getEntriesWithin("")){
          gitIndexCache.createPath(e.getPathString.split('/'))
        }
        repo.close()
      }
    }

    def checkFileSkipped(base: os.Path, path: os.SubPath, isDir: Boolean) = {
      lazy val indexed = gitIndexCache.containsPath(path.segments)
      lazy val ignoredEntries = for {
        (enclosingPartialPath, i) <- path.segments.inits.zipWithIndex
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
      indexed || notIgnored
    }

    def processInitialScanSingle(base: os.Path, path: os.SubPath, isDir: Boolean) = {
      // During the initial scan, we want to look up the git index and gitignore
      // files early, rather than waiting for them to turn up in our folder walk,
      // so we can get an up to date skipper as soon as possible. Otherwise we
      // might end up walking tons of files we do not care about only to realize
      // later that they are all ignored
      if (isDir) updateIgnoreCache(base, path / ".gitignore")
      if (path == os.sub) updateIndexCache(base, os.sub / ".git" / "index")
      !checkFileSkipped(base, path, isDir)
    }

    def processBatch(base: os.Path, paths: Set[(os.SubPath, Boolean)]) = {
      for(p <- ignorePaths.keySet){
        if (!os.isFile(base / p) && ignorePaths.contains(p)) {
          ignorePaths.remove(p)
          ignoreCache.clear()
        }
      }

      for((p, isDir) <- paths if !isDir){
        updateIgnoreCache(base, p)
        updateIndexCache(base, p)
      }

      paths.collect{ case (p, isDir) if checkFileSkipped(base, p, isDir) => p }
    }
  }
}