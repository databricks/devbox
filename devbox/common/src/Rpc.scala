package devbox.common
import upickle.default.{ReadWriter, macroRW}
sealed trait Rpc
object Rpc{
  case class CheckHash(path: Seq[String]) extends Rpc
  object CheckHash{ implicit val rw: ReadWriter[CheckHash] = macroRW }

  case class PutFile(path: Seq[String], perms: Int) extends Rpc
  object PutFile{ implicit val rw: ReadWriter[PutFile] = macroRW }

  case class Remove(path: Seq[String]) extends Rpc
  object Remove{ implicit val rw: ReadWriter[Remove] = macroRW }

  case class PutDir(path: Seq[String], perms: Int) extends Rpc
  object PutDir{ implicit val rw: ReadWriter[PutDir] = macroRW }

  case class PutLink(path: Seq[String], dest: String) extends Rpc
  object PutLink{ implicit val rw: ReadWriter[PutLink] = macroRW }

  case class WriteChunk(path: Seq[String], offset: Long, data: Array[Byte]) extends Rpc
  object WriteChunk{ implicit val rw: ReadWriter[WriteChunk] = macroRW }

  case class Truncate(path: Seq[String], offset: Long) extends Rpc
  object Truncate{ implicit val rw: ReadWriter[Truncate] = macroRW }

  case class SetPerms(path: Seq[String], perms: Int) extends Rpc
  object SetPerms{ implicit val rw: ReadWriter[SetPerms] = macroRW }

  implicit val rw: ReadWriter[Rpc] = macroRW
}