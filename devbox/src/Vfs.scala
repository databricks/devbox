package devbox
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
}
