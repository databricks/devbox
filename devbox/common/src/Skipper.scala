package devbox.common

import org.eclipse.jgit.ignore.{FastIgnoreRule, IgnoreNode}
import org.eclipse.jgit.internal.storage.file.FileRepository

trait Skipper {
  def batchRemoveSkippedPaths(base: os.Path, paths: PathMap[Boolean]): PathSet
  def initialScanIsPathSkipped(base: os.Path, path: os.SubPath, isDir: Boolean): Boolean
}

object Skipper{
  def fromString(strategy: String, forceInclude: PathSet, proxyGit: Boolean): Skipper = strategy match{
    case "dotgit" => Skipper.DotGit
    case "gitignore" => new Skipper.GitIgnore(forceInclude, proxyGit)
    case "" => Skipper.Null
  }

  def fromString(strategy: String, base: os.Path, proxyGit: Boolean): Skipper = strategy match{
    case "gitignore" => fromString(strategy, Skipper.pathsInGitIndex(base), proxyGit)
    case _ => fromString(strategy, new PathSet, false)
  }

  object Null extends Skipper {
    def batchRemoveSkippedPaths(base: os.Path, paths: PathMap[Boolean]) = PathSet.from(paths.walk())
    def initialScanIsPathSkipped(base: os.Path, path: os.SubPath, isDir: Boolean) = false
  }

  object DotGit extends Skipper {
    def batchRemoveSkippedPaths(base: os.Path, paths: PathMap[Boolean]) = {
      PathSet.from(paths.walk().filter(!_.lift(0).contains(".git")))
    }
    def initialScanIsPathSkipped(base: os.Path, path: os.SubPath, isDir: Boolean) = {
      path.segments.lift(0).contains(".git")
    }
  }

  /**
   * Ignore files in .gitignore, except those found in `forceInclude`. Usually, the whitelist consists
   * of files that are tracked by Git and that might be in .gitignored (see the Skipper.readIndexCache).
   *
   * @proxyGit If true, this will not sync the .git repository folder (git commands on the devbox should go
   *           through the git proxy server)
   */
  class GitIgnore(val initForceInclude: PathSet, proxyGit: Boolean) extends Skipper {

    def this(base: os.Path, proxyGit: Boolean) {
      this(pathsInGitIndex(base), proxyGit)
    }

    val ignoreNodeCache = collection.mutable.Map.empty[os.SubPath, (Int, IgnoreNode)]

    // make a mutable copy so we can update it in case the local git index changes underneath us
    private var forceInclude: PathSet = initForceInclude

    val isPathIgnoredCache = collection.mutable.Map.empty[
      (IndexedSeq[String], Int, Boolean),
      Boolean
    ]

    def updateIgnoreCache(base: os.Path, path: os.SubPath) = {
      if (os.isFile(base / path, followLinks = false)){
        val lines = try os.read.lines(base / path) catch{case e: Throwable => Nil }
        val linesHash = lines.hashCode
        if (!ignoreNodeCache.get(path / os.up).exists(_._1 == linesHash)) {

          import collection.JavaConverters._

          val n = new IgnoreNode(
            lines
              .filter(l => l != "" && l(0) != '#' && l != "/")
              .map(new FastIgnoreRule(_))
              .asJava
          )

          ignoreNodeCache(path / os.up) = (linesHash, n)
          isPathIgnoredCache.clear()
        }
      }
    }

    def checkFileActive(base: os.Path, path: os.SubPath, isDir: Boolean) = {
      if (path == os.sub / ".git" / "index.lock") false
      else if (isSkippableGitPath(path) && proxyGit) false
      else {
        lazy val indexed = forceInclude.containsPathPrefix(path.segments)
        lazy val ignoredEntries = for {
          (enclosingPartialPath, i) <- path.segments.inits.zipWithIndex
          if enclosingPartialPath.nonEmpty
          gitIgnoreRoot <- enclosingPartialPath.inits.drop(1)
          (linesHash, ignoreNode) <- ignoreNodeCache.get(os.sub / gitIgnoreRoot)
        } yield {
          val enclosedPartialPathStr = enclosingPartialPath.drop(gitIgnoreRoot.length).mkString("/")

          val ignored = isPathIgnoredCache.getOrElseUpdate(
            (enclosingPartialPath, gitIgnoreRoot.length, isDir),
            ignoreNode.isIgnored(enclosedPartialPathStr, if (i == 0) isDir else true) match {
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
    }

    /**
     * Return true if the path is inside .git and can be skipped when proxying Git commands back to the laptop.
     *
     * Certain files inside .git/ need to appear on the devbox, or else Bazel doesn't work.
     */
    def isSkippableGitPath(path: os.SubPath): Boolean = {
      // these paths are used by Bazel to invalidate certain targets and need to be present
      lazy val whitelistedGitFile: Boolean =
        (path == os.sub / ".git" / "HEAD"
          || path.startsWith(os.sub / ".git" / "refs")
          || path.startsWith(os.sub / ".git" / "packed-refs"))

      (path.startsWith(os.sub / ".git")
        && (path != os.sub / ".git") // we need to allow .git/ or else the scanner will not recurse inside .git/
        && !whitelistedGitFile)
    }

    def initialScanIsPathSkipped(base: os.Path, path: os.SubPath, isDir: Boolean): Boolean =
      (isSkippableGitPath(path) && proxyGit) || {
        // During the initial scan, we want to look up the git index and gitignore
        // files early, rather than waiting for them to turn up in our folder walk,
        // so we can get an up to date skipper as soon as possible. Otherwise we
        // might end up walking tons of files we do not care about only to realize
        // later that they are all ignored
        if (isDir) updateIgnoreCache(base, path / ".gitignore")
        !checkFileActive(base, path, isDir)
      }

    def batchRemoveSkippedPaths(base: os.Path, paths: PathMap[Boolean]) = {
        for (p <- ignoreNodeCache.keySet) {
          if (!os.isFile(base / p / ".gitignore") && ignoreNodeCache.contains(p)) {
            ignoreNodeCache.remove(p)
            isPathIgnoredCache.clear()
          }
        }

        for ((p0, isDir) <- paths.walkValues() if !isDir) {
          val p = os.SubPath(p0)
          if (p.last == ".gitignore") updateIgnoreCache(base, p)
          // In case the change is in the Git index, update our force includes
          updateIndexCache(base, p)
        }

        PathSet.from(
          paths
            .walkValues()
            .collect { case (p, isDir) if checkFileActive(base, os.SubPath(p), isDir) => p }
        )
      }

    def updateIndexCache(base: os.Path, path: os.SubPath): Unit = {
      if (path == os.sub / ".git" / "index") {
        forceInclude = pathsInGitIndex(base)
      }
    }
  }

  /**
   * Read the git index and return all paths within.
   */
  def pathsInGitIndex(base: os.Path): PathSet = {
    val gitIndexCache = new MutablePathSet()
    if (os.isFile(base / ".git" / "index", followLinks = false)) {
      val repo = new FileRepository((base / ".git").toIO)
      for(e <- org.eclipse.jgit.dircache.DirCache.read(repo).getEntriesWithin("")){
        gitIndexCache.add(e.getPathString.split('/'))
      }
      repo.close()
    }
    PathSet.from(gitIndexCache.walk(Seq.empty))
  }

}
