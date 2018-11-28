package devbox.common
import java.nio.file.Files

import upickle.default.{ReadWriter, macroRW}
import java.security.MessageDigest

import scala.collection.mutable

sealed trait Signature

object Signature{

  val blockSize = 4 * 1024 * 1024
  def compute(p: os.Path): Option[Signature] = {
    if (!os.exists(p, followLinks = false)) None
    else Some{
      os.stat(p).fileType match{
        case os.FileType.SymLink => Symlink(Files.readSymbolicLink(p.toNIO).toString)
        case os.FileType.Dir => Dir(os.perms(p).toInt())
        case os.FileType.File =>
          val digest = MessageDigest.getInstance("MD5")
          val chunks = mutable.ArrayBuffer.empty[Bytes]
          var size = 0L
          for(d <- Util.readChunks(p, blockSize)){
            val (buffer, n) = d
            size += n
            digest.reset()
            digest.update(buffer, 0, n)

            chunks.append(Bytes(digest.digest()))
          }
          File(os.perms(p).toInt, chunks, size)
      }
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
}

object Bytes{ implicit val rw: ReadWriter[Bytes] = macroRW }