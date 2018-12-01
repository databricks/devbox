package devbox
import devbox.common.{Action, Bytes, Rpc, Signature}
import geny.Generator

import scala.collection.mutable

final class Vfs[T, V](rootMetadata: V) {
  val root = Vfs.Folder[T, V](rootMetadata, mutable.LinkedHashMap.empty)

  def walk(preOrder: Boolean = true) = new geny.Generator[(List[String], Vfs.Node[T, V])]{
    def generate(handleItem: ((List[String], Vfs.Node[T, V])) => Generator.Action): Generator.Action = {
      var currentAction: Generator.Action = Generator.Continue
      def rec(reversePath: List[String], current: Vfs.Node[T, V]): Unit = current match{
        case Vfs.File(meta, value) => currentAction = handleItem((reversePath, current))
        case Vfs.Folder(meta, value) =>
          if (preOrder){
            if (preOrder) currentAction = handleItem((reversePath, current))

            for((k, v) <- value if currentAction == Generator.Continue) {
              rec(k :: reversePath, v)
            }

            if (!preOrder) currentAction = handleItem((reversePath, current))
          }
        case Vfs.Symlink(value) => currentAction = handleItem((reversePath, current))
      }
      rec(Nil, root)
      currentAction
    }
  }

  def resolve(p: String): Option[Vfs.Node[T, V]] = {
    if (p == "") Some(root)
    else {
      assert(p.head != '/' && p.last != '/')
      val segments = p.split('/')
      var current: Option[Vfs.Node[T, V]] = Some(root)
      for(segment <- segments) current = current match{
        case Some(Vfs.Folder(metadata, value)) => value.get(segment)
        case _ => None
      }
      current
    }
  }

  def resolveParent(p: String): Option[(String, Vfs.Folder[T, V])] = {
    if (p == "") None
    else{
      assert(p.head != '/' && p.last != '/')
      val segments = p.split('/')
      resolve(segments.dropRight(1).mkString("/"))
        .collect { case v: Vfs.Folder[T, V] => (segments.last, v)}
    }
  }
}

object Vfs{
  sealed trait Node[+T, +V]
  case class File[T, V](var metadata: V, var value: T) extends Node[T, V]
  case class Folder[T, V](var metadata: V,
                          value: mutable.LinkedHashMap[String, Node[T, V]]) extends Node[T, V]
  case class Symlink(var value: String) extends Node[Nothing, Nothing]

  // Update stateVfs according to the given action
  def updateVfs(p: String, sig: Signature, vfs: Vfs[(Long, Seq[Bytes]), Int]) = sig match{
    case Signature.File(perms, hashes, size) =>
      val (name, folder) = vfs.resolveParent(p).get
      assert(!folder.value.contains(name))
      folder.value(name) = Vfs.File(perms, (size, hashes))

    case Signature.Dir(perms) =>
      val (name, folder) = vfs.resolveParent(p).get
      assert(!folder.value.contains(name))
      folder.value(name) = Vfs.Folder(perms, mutable.LinkedHashMap.empty[String, Vfs.Node[(Long, Seq[Bytes]), Int]])

    case Signature.Symlink(dest) =>
      val (name, folder) = vfs.resolveParent(p).get
      assert(!folder.value.contains(name))
      folder.value(name) = Vfs.Symlink(dest)
  }

  def updateVfs(a: Action, stateVfs: Vfs[(Long, Seq[Bytes]), Int]) = a match{
    case Rpc.PutFile(path, perms) =>
      val (name, folder) = stateVfs.resolveParent(path).get
      assert(!folder.value.contains(name))
      folder.value(name) = Vfs.File(perms, (0, Nil))

    case Rpc.Remove(path) =>
      stateVfs.resolveParent(path).foreach{
        case (name, folder) => folder.value.remove(name)
      }

    case Rpc.PutDir(path, perms) =>
      val (name, folder) = stateVfs.resolveParent(path).get
      assert(!folder.value.contains(name))
      folder.value(name) = Vfs.Folder(perms, mutable.LinkedHashMap.empty[String, Vfs.Node[(Long, Seq[Bytes]), Int]])

    case Rpc.PutLink(path, dest) =>
      val (name, folder) = stateVfs.resolveParent(path).get
      assert(!folder.value.contains(name))
      folder.value(name) = Vfs.Symlink(dest)

    case Rpc.WriteChunk(path, offset, bytes, hash) =>
      assert(offset % Signature.blockSize == 0)
      val index = offset / Signature.blockSize
      val currentFile = stateVfs.resolve(path).get.asInstanceOf[Vfs.File[(Long, Seq[Bytes]), Int]]
      currentFile.value = (
        currentFile.value._1,
        if (index < currentFile.value._2.length) currentFile.value._2.updated(index.toInt, hash)
        else if (index == currentFile.value._2.length) currentFile.value._2 :+ hash
        else ???
      )

    case Rpc.Truncate(path, offset) =>
      val currentFile = stateVfs.resolve(path).get.asInstanceOf[Vfs.File[(Long, Seq[Bytes]), Int]]
      currentFile.value = (offset, currentFile.value._2)

    case Rpc.SetPerms(path, perms) =>
      stateVfs.resolve(path) match{
        case Some(f @ Vfs.File(_, _)) => f.metadata = perms
        case Some(f @ Vfs.Folder(_, _)) => f.metadata = perms
      }
  }
}
