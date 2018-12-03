package devbox.common

import java.io.{DataInputStream, DataOutputStream}
import geny.Generator

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

  def ignoreCallback(strategy: String): ((os.Path, os.Path) => Boolean) = strategy match{
    case "dotgit" => (path, base) =>
      assert(path.startsWith(base), path + " " + base)
      path.relativeTo(base).segments.startsWith(Seq(".git"))
    case "gitignore" => (path, base) =>
      assert(path.startsWith(base))
      GitIgnore.checkGitIgnore(path, base)
    case "" => (path, base) =>
      assert(path.startsWith(base))
      false
  }
}
