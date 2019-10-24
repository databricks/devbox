package devbox.common

object Util {
  val blockSize = 4 * 1024 * 1024

  implicit val permsetRw: upickle.default.ReadWriter[os.PermSet] =
    upickle.default.readwriter[String].bimap[os.PermSet](
      _.toString(),
      os.PermSet.fromString
    )
  implicit val relpathRw: upickle.default.ReadWriter[os.RelPath] =
    upickle.default.readwriter[String].bimap[os.RelPath](
      _.toString(),
      os.RelPath(_)
    )

  def autoclose[T <: AutoCloseable, V](x: T)(f: T => V) = {
    try f(x)
    finally x.close()
  }

  def initialSkippedScan(scanRoots: Seq[os.Path], skipper: Skipper)
                        (f: (os.Path, os.Path, Signature, Int, Int) => Unit) = {
    val buffer = new Array[Byte](Util.blockSize)
    for(scanRoot <- scanRoots) {
      val skip = skipper.prepare(scanRoot)
      if (!os.isDir(scanRoot)) os.makeDir.all(scanRoot)

      val fileStream = os.walk.stream.attrs(
        scanRoot,
        (p, attrs) => skip(p.relativeTo(scanRoot), attrs.isDir) && !attrs.isDir
      )

      val total = fileStream.count()
      for {
        ((p, attrs), i) <- fileStream.zipWithIndex
        sig <- Signature.compute(p, buffer, attrs.fileType)
      } f(scanRoot, p, sig, i, total)
    }
  }
  /**
    * Convert the lines of a gitignore file into a re2j regex.
    *
    * re2j provides efficient linear-time non-backtracking regex matching,
    * letting us efficiently match file paths against the gitignore. Somehow
    * 100-1000x faster than matching using JGit's FastIgnoreRule.
    */
  def gitIgnoreToRegex(base: os.Path, p: os.RelPath) = {
    com.google.re2j.Pattern.compile(
      os.read.lines.stream(base / p)
        .filter(l => l.nonEmpty && l(0) != '#')
        .map(gitIgnoreLineToRegex(_, (p / os.up).toString()))
        .mkString("|")
    )
  }

  def gitIgnoreLineToRegex(line0: String, enclosingPrefix: String) = {
    val isRoot = line0(0) == '/'
    val line = line0.stripPrefix("/")
    val containsSlash = line.stripSuffix("/").contains('/')
    val lastChunk = new collection.mutable.StringBuilder()
    val output = new collection.mutable.StringBuilder()
    if (!isRoot) {
      if (enclosingPrefix != "") output.append(com.google.re2j.Pattern.quote(enclosingPrefix + "/"))
      if (!containsSlash) output.append("(.*/)?")
    }

    for (c <- line) {
      c match {
        case '*' =>
          output.append(com.google.re2j.Pattern.quote(lastChunk.toString()))
          lastChunk.clear()
          output.append(".*")
        case '?' =>
          output.append(com.google.re2j.Pattern.quote(lastChunk.toString()))
          lastChunk.clear()
          output.append(".")
        case c =>
          lastChunk.append(c)
      }
    }
    output.append(com.google.re2j.Pattern.quote(lastChunk.toString()))
    if (lastChunk.nonEmpty && lastChunk.last == '/') output.append("($|.*)")
    else output.append("($|/.*)")
    lastChunk.clear()

    output.toString()
  }
}
