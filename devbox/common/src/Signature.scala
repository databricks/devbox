package devbox.common
import java.nio.file.Files

import upickle.default.{ReadWriter, macroRW}
import java.security.MessageDigest

import scala.collection.mutable

sealed trait Signature

object Signature{

  val blockSize = 4 * 1024 * 1024

  /**
    * Computes the signature of a given path. Assumes the file exists.
    */
  def compute(p: os.Path, buffer: Array[Byte]): Signature = {
    val stat = os.stat(p, followLinks = false)
    stat.fileType match{
      case os.FileType.SymLink => Symlink(Files.readSymbolicLink(p.toNIO).toString)
      case os.FileType.Dir => Dir(os.perms(p).toInt())
      case os.FileType.File =>
        val digest = MessageDigest.getInstance("MD5")
        val chunks = mutable.ArrayBuffer.empty[Bytes]
        var size = 0L
        for(d <- Util.readChunks(p, buffer)){
          val (buffer, n) = d
          size += n
          digest.reset()
          digest.update(buffer, 0, n)

          chunks.append(Bytes(digest.digest()))
        }
        File(os.perms(p).toInt, chunks, size)
    }
  }

  case class File(perms: Int, blockHashes: Seq[Bytes], size: Long) extends Signature
  object File{ implicit val rw: ReadWriter[File] = macroRW }

  case class Dir(perms: Int) extends Signature
  object Dir{ implicit val rw: ReadWriter[Dir] = macroRW }

  case class Symlink(dest: String) extends Signature
  object Symlink{ implicit val rw: ReadWriter[Symlink] = macroRW }

  implicit val rw: ReadWriter[Signature] = macroRW
}

case class Bytes(value: Array[Byte]){
  override def hashCode() = java.util.Arrays.hashCode(value)
  override def equals(other: Any) = other match{
    case o: Bytes => java.util.Arrays.equals(value, o.value)
    case _ => false
  }

  override def toString = {
    val cutoff = 10
    val hex = upickle.core.Util.bytesToString(value.take(cutoff))
    val dots = if(value.length > cutoff) "..." else ""
    s"Bytes(${value.length}, $hex$dots)"
  }
}

object Bytes{ implicit val rw: ReadWriter[Bytes] = macroRW }

case class RpcException(wrapped: RemoteException) extends Throwable(wrapped)
case class RemoteException(clsName: String,
                           msg: String,
                           stack: Seq[StackTraceElement],
                           parent: Option[RemoteException]) extends Throwable(
  clsName + ": " + msg
){
  this.setStackTrace(stack.toArray[StackTraceElement])
}
object RemoteException{
  def create(e: Throwable): RemoteException = RemoteException(
    e.getClass.getName,
    e.getMessage,
    e.getStackTrace,
    Option(e.getCause).map(create)
  )

  implicit val rw: ReadWriter[RemoteException] = macroRW
  implicit val stackTraceRW = upickle.default.readwriter[ujson.Obj].bimap[StackTraceElement](
    ste => ujson.Obj(
      "declaringClass" -> ujson.Str(ste.getClassName),
      "methodName" -> ujson.Str(ste.getMethodName),
      "fileName" -> ujson.Str(ste.getFileName),
      "lineNumber" -> ujson.Num(ste.getLineNumber)
    ),
    {case json: ujson.Obj =>
      new StackTraceElement(
        json("declaringClass").str.toString,
        json("methodName").str.toString,
        json("fileName").str.toString,
        json("lineNumber").num.toInt
      )
    }
  )
}