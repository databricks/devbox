package devbox.common

import java.io.{DataInputStream, DataOutputStream}
import geny.Generator

object Util {
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
      path.relativeTo(base).segments.startsWith(Seq(".git")) || GitIgnore.checkGitIgnore(path, base)
    case "" => (path, base) =>
      assert(path.startsWith(base))
      false
  }

  def readChunks(p: os.Path, buffer: Array[Byte]): geny.Generator[(Array[Byte], Int)] = {
    new Generator[(Array[Byte], Int)] {
      def generate(handleItem: ((Array[Byte], Int)) => Generator.Action): Generator.Action = {
        autoclose(os.read.inputStream(p)){is =>
          var bufferOffset = 0
          var lastAction: Generator.Action = Generator.Continue
          while ( {
            is.read(buffer, bufferOffset, buffer.length - bufferOffset) match {
              case -1 =>
                lastAction = handleItem((buffer, bufferOffset))
                false
              case n =>
                if (n + bufferOffset == buffer.length) {
                  lastAction = handleItem((buffer, buffer.length))
                  bufferOffset = 0
                } else {
                  bufferOffset += n
                }
                lastAction == Generator.Continue
            }
          }) ()
          lastAction
        }
      }
    }
  }
}
