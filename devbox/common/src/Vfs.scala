package devbox.common
import devbox.common._
import geny.Generator

import scala.collection.mutable
import upickle.default.{ReadWriter, macroRW}
/**
  * Represents a simple in-memory filesystem, storing values of type [[T]] on
  * every directory and file. Useful for modelling changes to a real filesystem
  * without the expense of going to disk, or as a compact set of file paths
  * that can conveniently be traversed in pre/post-order.
  */
final class Vfs[T](val root: Vfs.Dir[T]) {
  def this(rootMetadata: T) = {
    this(Vfs.Dir[T](rootMetadata, mutable.LinkedHashMap.empty))
  }

  def resolve(p: os.SubPath): Option[Vfs.Node[T]] = {
    var current: Option[Vfs.Node[T]] = Some(root)
    for(segment <- p.segments) current = current match{
      case Some(Vfs.Dir(metadata, value)) => value.get(segment)
      case _ => None
    }
    current
  }

  def resolveParent(p: os.SubPath): Option[(String, Vfs.Dir[T])] = {
    if (p.segments.isEmpty) None
    else resolve(p / os.up).collect { case v: Vfs.Dir[T] => (p.segments.last, v)}
  }
}

object Vfs{
  sealed trait Node[T]{
    def value: T
    def value_=(v: T): Unit
  }
  case class File[T](var value: T) extends Node[T]

  case class Dir[T](var value: T,
                    children: mutable.Map[String, Node[T]]) extends Node[T]

  // Update stateVfs according to the given action
  def overwriteUpdateVfs(p: os.SubPath, sig: Sig, vfs: Vfs[Sig]) = {
    val (name, folder) = vfs.resolveParent(p).getOrElse(throw new Exception("overwriteUpdateVfs failed"))
    folder.children(name) =
      if (!sig.isInstanceOf[Sig.Dir]) Vfs.File(sig)
      else Vfs.Dir(sig, mutable.LinkedHashMap.empty[String, Vfs.Node[Sig]])
  }

  def updateVfs(a: Action, stateVfs: Vfs[Sig]) = a match{
    case Rpc.PutFile(_, path, perms) =>
      val (name, folder) = stateVfs.resolveParent(path).getOrElse(throw new Exception("Parent path not found " + path.toString))
      assert(!folder.children.contains(name))
      folder.children(name) = Vfs.File(Sig.File(perms, Nil, 0))

    case Rpc.Remove(_, path) =>
      for((name, folder) <- stateVfs.resolveParent(path)){
        folder.children.remove(name)
      }

    case Rpc.PutDir(_, path, perms) =>
      val (name, folder) = stateVfs.resolveParent(path).getOrElse(throw new Exception("Parent path not found " + path.toString))
      assert(!folder.children.contains(name))
      folder.children(name) = Vfs.Dir(
        Sig.Dir(perms),
        mutable.LinkedHashMap.empty[String, Vfs.Node[Sig]]
      )

    case Rpc.PutLink(_, path, dest) =>
      val (name, folder) = stateVfs.resolveParent(path).getOrElse(throw new Exception("Parent path not found " + path.toString))
      assert(!folder.children.contains(name))
      folder.children(name) = Vfs.File(Sig.Symlink(dest))

    case Action.WriteChunk(path, index, hash) =>
      val currentFile = stateVfs.resolve(path).getOrElse(throw new Exception("File not found " + path.toString)).asInstanceOf[Vfs.File[Sig.File]]
      currentFile.value = currentFile.value.copy(
        blockHashes =
          if (index < currentFile.value.blockHashes.length) currentFile.value.blockHashes.updated(index.toInt, hash)
          else if (index == currentFile.value.blockHashes.length) currentFile.value.blockHashes :+ hash
          else ???
      )

    case Rpc.SetSize(_, path, offset) =>
      val currentFile = stateVfs.resolve(path).getOrElse(throw new Exception("File not found " + path.toString)).asInstanceOf[Vfs.File[Sig.File]]
      currentFile.value = currentFile.value.copy(
        size = offset,
        blockHashes = currentFile.value.blockHashes.take(
          // offset / blockSize, rounded up, to give the number of chunks
          // required to hold that many bytes
          ((offset + Util.blockSize - 1) / Util.blockSize).toInt
        )
      )

    case Rpc.SetPerms(_, path, perms) =>
      stateVfs.resolve(path) match{
        case Some(f @ Vfs.File(file: Sig.File)) => f.value = file.copy(perms = perms)
        case Some(f @ Vfs.Dir(dir: Sig.Dir, _)) => f.value = dir.copy(perms = perms)
      }
  }

  implicit def fileRw[T: ReadWriter]: ReadWriter[File[T]] = macroRW
  implicit def dirRw[T: ReadWriter]: ReadWriter[Dir[T]] = macroRW
  implicit def nodeRw[T: ReadWriter]: ReadWriter[Node[T]] = macroRW
}
