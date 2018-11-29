package devbox.common
import upickle.default.{ReadWriter, macroRW}
sealed trait Rpc
object Rpc{
  case class CheckHash(path: String) extends Rpc
  object CheckHash{ implicit val rw: ReadWriter[CheckHash] = macroRW }

  case class PutFile(path: String, perms: Int) extends Rpc
  object PutFile{ implicit val rw: ReadWriter[PutFile] = macroRW }

  case class Remove(path: String) extends Rpc
  object Remove{ implicit val rw: ReadWriter[Remove] = macroRW }

  case class PutDir(path: String, perms: Int) extends Rpc
  object PutDir{ implicit val rw: ReadWriter[PutDir] = macroRW }

  case class PutLink(path: String, dest: String) extends Rpc
  object PutLink{ implicit val rw: ReadWriter[PutLink] = macroRW }

  case class WriteChunk(path: String, offset: Long, data: Bytes) extends Rpc
  object WriteChunk{ implicit val rw: ReadWriter[WriteChunk] = macroRW }

  case class Truncate(path: String, offset: Long) extends Rpc
  object Truncate{ implicit val rw: ReadWriter[Truncate] = macroRW }

  case class SetPerms(path: String, perms: Int) extends Rpc
  object SetPerms{ implicit val rw: ReadWriter[SetPerms] = macroRW }

  implicit val rw: ReadWriter[Rpc] = macroRW
}