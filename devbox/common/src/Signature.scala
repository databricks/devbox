package devbox.common
import java.nio.file.Files

import upickle.default.{ReadWriter, macroRW}
import java.security.MessageDigest
import scala.collection.mutable

/**
  * The minimal amount of metadata identifying something on the filesystem that
  * is necessary to perform efficient synchronization.
  */
sealed trait Signature

object Signature{
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

          chunks.append(new Bytes(digest.digest()))
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
