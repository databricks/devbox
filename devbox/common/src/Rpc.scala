package devbox.common
import upickle.default.{ReadWriter, macroRW}
import Util.{permsetRw, relpathRw, subpathRw}
sealed trait Rpc
sealed trait PathRpc {
  def path: os.SubPath
}
sealed trait Action

object Action{
  case class WriteChunk(path: os.SubPath, blockIndex: Int, hash: Bytes) extends Action
}

object Rpc{
  /** Perform a full scan of each root path, including any gitignored files appearing in the cooresponding forceIncludes */
  case class FullScan(roots: Seq[os.RelPath],
                      forceIncludes: Seq[Seq[os.SubPath]],
                      proxyGit: Boolean,
                      syncIgnore: Option[String]) extends Rpc
  object FullScan{ implicit val rw: ReadWriter[FullScan] = macroRW }



  case class PutFile(root: os.RelPath, path: os.SubPath, perms: os.PermSet) extends Rpc with PathRpc with Action
  object PutFile{ implicit val rw: ReadWriter[PutFile] = macroRW }

  case class Remove(root: os.RelPath, path: os.SubPath) extends Rpc with PathRpc with Action
  object Remove{ implicit val rw: ReadWriter[Remove] = macroRW }

  case class PutDir(root: os.RelPath, path: os.SubPath, perms: os.PermSet) extends Rpc with PathRpc with Action
  object PutDir{ implicit val rw: ReadWriter[PutDir] = macroRW }

  case class PutLink(root: os.RelPath, path: os.SubPath, dest: String) extends Rpc with PathRpc with Action
  object PutLink{ implicit val rw: ReadWriter[PutLink] = macroRW }

  case class WriteChunk(root: os.RelPath, path: os.SubPath, offset: Long, data: Bytes, compression: CompressionMode.Value) extends Rpc
  object WriteChunk{ implicit val rw: ReadWriter[WriteChunk] = macroRW }

  case class SetSize(root: os.RelPath, path: os.SubPath, offset: Long) extends Rpc with PathRpc with Action
  object SetSize{ implicit val rw: ReadWriter[SetSize] = macroRW }

  case class SetPerms(root: os.RelPath, path: os.SubPath, perms: os.PermSet) extends Rpc with PathRpc with Action
  object SetPerms{ implicit val rw: ReadWriter[SetPerms] = macroRW }

  case class Complete() extends Rpc with Action
  object Complete{ implicit val rw: ReadWriter[Complete] = macroRW }



  implicit val rw: ReadWriter[Rpc] = macroRW
}
sealed trait Response
object Response{
  case class Scanned(base: os.RelPath, p: os.SubPath, s: Sig) extends Response
  object Scanned{ implicit val rw: ReadWriter[Scanned] = macroRW }

  case class Ack() extends Response
  object Ack{ implicit  val rw: ReadWriter[Ack] = macroRW }

  implicit val rw: ReadWriter[Response] = macroRW
}
