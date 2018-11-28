package devbox
import scala.collection.mutable

class Vfs[T] {
  val root = Vfs.Folder[T](mutable.LinkedHashMap.empty)
}
object Vfs{
  sealed trait Node[+T]
  case class File[T](value: T) extends Node[T]
  case class Folder[T](value: mutable.LinkedHashMap[String, Node[T]]) extends Node[T]
  case class Symlink(value: String) extends Node[Nothing]

}