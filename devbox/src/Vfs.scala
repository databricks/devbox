package devbox
import devbox.common._
import geny.Generator

import scala.collection.mutable

/**
  * Represents a simple in-memory filesystem, storing values of type [[T]] on
  * every directory and file. Useful for modelling changes to a real filesystem
  * without the expense of going to disk, or as a compact set of file paths
  * that can conveniently be traversed in pre/post-order.
  */
final class Vfs[T](rootMetadata: T) {
  val root = Vfs.Dir[T](rootMetadata, mutable.LinkedHashMap.empty)

  def walk(preOrder: Boolean = true) = new geny.Generator[(List[String], Vfs.Node[T])]{
    def generate(handleItem: ((List[String], Vfs.Node[T])) => Generator.Action): Generator.Action = {
      var currentAction: Generator.Action = Generator.Continue
      def rec(reversePath: List[String], current: Vfs.Node[T]): Unit = current match{
        case Vfs.File(value) => currentAction = handleItem((reversePath, current))
        case Vfs.Dir(value, children) =>
          if (preOrder){
            if (preOrder) currentAction = handleItem((reversePath, current))

            for((k, v) <- children if currentAction == Generator.Continue) {
              rec(k :: reversePath, v)
            }

            if (!preOrder) currentAction = handleItem((reversePath, current))
          }
      }
      rec(Nil, root)
      currentAction
    }
  }

  def resolve(p: String): Option[Vfs.Node[T]] = {
    if (p == "") Some(root)
    else {
      assert(p.head != '/' && p.last != '/')
      val segments = p.split('/')
      var current: Option[Vfs.Node[T]] = Some(root)
      for(segment <- segments) current = current match{
        case Some(Vfs.Dir(metadata, value)) => value.get(segment)
        case _ => None
      }
      current
    }
  }

  def resolveParent(p: String): Option[(String, Vfs.Dir[T])] = {
    if (p == "") None
    else{
      assert(p.head != '/' && p.last != '/')
      val segments = p.split('/')
      resolve(segments.dropRight(1).mkString("/"))
        .collect { case v: Vfs.Dir[T] => (segments.last, v)}
    }
  }
}

object Vfs{
  sealed trait Node[+T]{
    def value: T
  }
  case class File[T](var value: T) extends Node[T]
  case class Dir[T](var value: T,
                    children: mutable.LinkedHashMap[String, Node[T]]) extends Node[T]

  // Update stateVfs according to the given action
  def updateVfs(p: String, sig: Signature, vfs: Vfs[Signature]) = {
    val (name, folder) = vfs.resolveParent(p).get
    assert(!folder.children.contains(name))
    folder.children(name) =
    if (!sig.isInstanceOf[Signature.Dir]) Vfs.File(sig)
    else Vfs.Dir(sig, mutable.LinkedHashMap.empty[String, Vfs.Node[Signature]])
  }

  def updateVfs(a: Action, stateVfs: Vfs[Signature]) = a match{
    case Rpc.PutFile(path, perms) =>
      val (name, folder) = stateVfs.resolveParent(path).get
      assert(!folder.children.contains(name))
      folder.children(name) = Vfs.File(Signature.File(perms, Nil, 0))

    case Rpc.Remove(path) =>
      for((name, folder) <- stateVfs.resolveParent(path)){
        folder.children.remove(name)
      }

    case Rpc.PutDir(path, perms) =>
      val (name, folder) = stateVfs.resolveParent(path).get
      assert(!folder.children.contains(name))
      folder.children(name) = Vfs.Dir(
        Signature.Dir(perms),
        mutable.LinkedHashMap.empty[String, Vfs.Node[Signature]]
      )

    case Rpc.PutLink(path, dest) =>
      val (name, folder) = stateVfs.resolveParent(path).get
      assert(!folder.children.contains(name))
      folder.children(name) = Vfs.File(Signature.Symlink(dest))

    case Rpc.WriteChunk(path, offset, bytes, hash) =>
      assert(offset % Util.blockSize == 0)
      val index = offset / Util.blockSize
      val currentFile = stateVfs.resolve(path).get.asInstanceOf[Vfs.File[Signature.File]]
      currentFile.value = currentFile.value.copy(
        blockHashes =
          if (index < currentFile.value.blockHashes.length) currentFile.value.blockHashes.updated(index.toInt, hash)
          else if (index == currentFile.value.blockHashes.length) currentFile.value.blockHashes :+ hash
          else ???
      )

    case Rpc.SetSize(path, offset) =>
      val currentFile = stateVfs.resolve(path).get.asInstanceOf[Vfs.File[Signature.File]]
      currentFile.value = currentFile.value.copy(
        size = offset,
        blockHashes = currentFile.value.blockHashes.take(
          // offset / blockSize, rounded up, to give the number of chunks
          // required to hold that many bytes
          ((offset + Util.blockSize - 1) / Util.blockSize).toInt
        )
      )

    case Rpc.SetPerms(path, perms) =>
      stateVfs.resolve(path) match{
        case Some(f @ Vfs.File(file: Signature.File)) => f.value = file.copy(perms = perms)
        case Some(f @ Vfs.Dir(dir: Signature.Dir, _)) => f.value = dir.copy(perms = perms)
      }
  }
}
