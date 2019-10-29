package devbox.common

import org.eclipse.jgit.ignore.{FastIgnoreRule, IgnoreNode}
import org.eclipse.jgit.internal.storage.file.FileRepository

import scala.collection.mutable


trait Skipper {
  def process(base: os.Path, paths: Set[(os.SubPath, Boolean)]): Set[os.SubPath]
  def processSingle(base: os.Path, path: os.SubPath, isDir: Boolean): Boolean
}

object Skipper{
  def fromString(strategy: String): Skipper = strategy match{
    case "dotgit" => Skipper.DotGit
    case "gitignore" => new Skipper.GitIgnore()
    case "" => Skipper.Null
  }

  object Null extends Skipper {
    def process(base: os.Path, paths: Set[(os.SubPath, Boolean)]) = paths.map(_._1)
    def processSingle(base: os.Path, path: os.SubPath, isDir: Boolean) = false
  }

  object DotGit extends Skipper {
    def process(base: os.Path, paths: Set[(os.SubPath, Boolean)]) = {
      paths.map(_._1).filter(_.segments(0) != ".git")
    }
    def processSingle(base: os.Path, path: os.SubPath, isDir: Boolean) = {
      path.segments(0) == ".git"
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
      if (os.isFile(base / path /  ".gitignore", followLinks = false)){
        val lines = try os.read.lines(base / path / ".gitignore") catch{case e: Throwable => Nil }
        val linesHash = lines.hashCode
        if (!ignorePaths.get(path).exists(_._1 == linesHash)) {

          import collection.JavaConverters._

          val n = new IgnoreNode(
            lines
              .filter(l => l != "" && l(0) != '#' && l != "/")
              .map(new FastIgnoreRule(_))
              .asJava
          )

          ignorePaths(path) = (linesHash, n)
          ignoreCache.clear()
        }
      }
    }

    def updateIndexCache(base: os.Path, path: os.SubPath) = {
      if (path.segments.isEmpty && os.isFile(base / path /  ".git" / "index", followLinks = false)){
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
    def processSingle(base: os.Path, path: os.SubPath, isDir: Boolean) = {
      updateIgnoreCache(base, path)
      updateIndexCache(base, path)
      !checkFileSkipped(base, path, isDir)
    }

    def process(base: os.Path, paths: Set[(os.SubPath, Boolean)]) = {
      for(p <- ignorePaths.keySet){
        if (!os.isFile(base / p) && ignorePaths.contains(p)) {
          ignorePaths.remove(p)
          ignoreCache.clear()
        }
      }

      for((p, isDir) <- paths){
        updateIgnoreCache(base, p)
        updateIndexCache(base, p)
      }

      paths.collect{ case (p, isDir) if checkFileSkipped(base, p, isDir) => p }
    }
  }
}