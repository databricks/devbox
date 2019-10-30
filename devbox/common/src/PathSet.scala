package devbox.common
import collection.mutable

/**
  * A simple high-performance mutable Set of path segments, implemented as a tree.
  *
  * Only supports four operations: [[add]], [[contains]], [[clear]], and [[getSize]].
  */
class PathSet(){
  type Node = mutable.Map[String, Any]
  def Node = mutable.Map
  private var value: Node = Node()
  private var size = 0
  def getSize = size

  def clear() = {
    size = 0
    value = Node()
    this
  }
  def contains(segments: IterableOnce[String]): Boolean = {
    segments
      .foldLeft(Option(value)) {
        case (None, _) => None
        case (Some(node: Node), segment) => node.get(segment).map(_.asInstanceOf[Node])
      }
      .nonEmpty
  }

  def add(segments: IterableOnce[String]): Unit = {
    var current = value
    var newPath = false
    for(segment <- segments){
      current = current.getOrElseUpdate(
        segment, {
          newPath = true
          Node()
        }
      ).asInstanceOf[Node]
    }
    if (newPath) size += 1
  }
}