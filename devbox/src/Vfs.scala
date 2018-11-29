package devbox
import geny.Generator

import scala.collection.mutable

final class Vfs[T] {
  val root = Vfs.Folder[T](mutable.LinkedHashMap.empty)

  def walk(preOrder: Boolean = true) = new geny.Generator[(List[String], Vfs.Node[T])]{
    def generate(handleItem: ((List[String], Vfs.Node[T])) => Generator.Action): Generator.Action = {
      var currentAction: Generator.Action = Generator.Continue
      def rec(reversePath: List[String], current: Vfs.Node[T]): Unit = current match{
        case Vfs.File(value) => currentAction = handleItem((reversePath, current))
        case Vfs.Folder(value) =>
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
}
object Vfs{
  sealed trait Node[+T]
  case class File[T](value: T) extends Node[T]
  case class Folder[T](value: mutable.LinkedHashMap[String, Node[T]]) extends Node[T]
  case class Symlink(value: String) extends Node[Nothing]

}