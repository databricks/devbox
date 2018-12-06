package devbox.common

object Util {
  val blockSize = 4 * 1024 * 1024

  implicit val permsetRw: upickle.default.ReadWriter[os.PermSet] =
    upickle.default.readwriter[String].bimap[os.PermSet](
      _.toString(),
      os.PermSet.fromString
    )

  def autoclose[T <: AutoCloseable, V](x: T)(f: T => V) = {
    try f(x)
    finally x.close()
  }

  /**
    * Convert the lines of a gitignore file into a re2j regex.
    *
    * re2j provides efficient linear-time non-backtracking regex matching,
    * letting us efficiently match file paths against the gitignore. Somehow
    * 100-1000x faster than matching using JGit's FastIgnoreRule.
    */
  def gitIgnoreToRegex(p: os.Path) = {
    com.google.re2j.Pattern.compile(
      os.read.lines.stream(p)
        .filter(l => l.nonEmpty && l(0) != '#')
        .map { line0 =>
          val isRoot = line0(0) == '/'
          val line = line0.stripPrefix("/")
          val lastChunk = new collection.mutable.StringBuilder()
          val output = new collection.mutable.StringBuilder()
          if (!isRoot) output.append(".*")

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
          lastChunk.clear()
          output.append("($|/).*")
          output.toString()
        }
        .mkString("|")
    )
  }
}
