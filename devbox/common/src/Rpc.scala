package devbox.common
import upickle.default.{ReadWriter, macroRW}
import Util.{permsetRw, relpathRw}
sealed trait Rpc
sealed trait Action

object Rpc{
  case class FullScan(root: os.RelPath) extends Rpc
  object FullScan{ implicit val rw: ReadWriter[FullScan] = macroRW }

  case class PutFile(root: os.RelPath, path: os.RelPath, perms: os.PermSet) extends Rpc with Action
  object PutFile{ implicit val rw: ReadWriter[PutFile] = macroRW }

  case class Remove(root: os.RelPath, path: os.RelPath) extends Rpc with Action
  object Remove{ implicit val rw: ReadWriter[Remove] = macroRW }

  case class PutDir(root: os.RelPath, path: os.RelPath, perms: os.PermSet) extends Rpc with Action
  object PutDir{ implicit val rw: ReadWriter[PutDir] = macroRW }

  case class PutLink(root: os.RelPath, path: os.RelPath, dest: String) extends Rpc with Action
  object PutLink{ implicit val rw: ReadWriter[PutLink] = macroRW }

  case class WriteChunk(root: os.RelPath, path: os.RelPath, offset: Long, data: Bytes, hash: Bytes) extends Rpc with Action
  object WriteChunk{ implicit val rw: ReadWriter[WriteChunk] = macroRW }

  case class SetSize(root: os.RelPath, path: os.RelPath, offset: Long) extends Rpc with Action
  object SetSize{ implicit val rw: ReadWriter[SetSize] = macroRW }

  case class SetPerms(root: os.RelPath, path: os.RelPath, perms: os.PermSet) extends Rpc with Action
  object SetPerms{ implicit val rw: ReadWriter[SetPerms] = macroRW }

  case class Ack(hash: Int) extends Rpc with Action
  object Ack{ implicit  val rw: ReadWriter[Ack] = macroRW }

  case class Ping() extends Rpc with Action
  object Ping{ implicit  val rw: ReadWriter[Ping] = macroRW }

  case class Pong() extends Rpc with Action
  object Pong{ implicit  val rw: ReadWriter[Pong] = macroRW }

  implicit val rw: ReadWriter[Rpc] = macroRW
}
