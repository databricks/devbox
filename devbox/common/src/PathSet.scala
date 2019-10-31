package devbox.common
import geny.Generator

import collection.mutable
object BasePathSet{
  trait Node {
    def hasValue: Boolean
    def children: collection.Map[String, Node]
  }
}
/**
  * A compact, high-performance mutable Set of path segments, implemented as a tree.
  *
  * Comes in both mutable and immutable flavors. Generally append-only, with no
  * provision for removing elements from the set except by throwing away the whole thing
  *
  * Allows efficient insertion, [[containsPathPrefix]] checks, [[size]] checks, as
  * well as iteration over prefixed elements via [[walk]] and [[walkSubPaths]]
  */
abstract class BasePathSet(){


  protected def value: BasePathSet.Node

  def size: Int

  def containsPathPrefix(segments: IterableOnce[String]): Boolean = {
    query(segments).nonEmpty
  }

  def contains(segments: IterableOnce[String]): Boolean = {
    query(segments).exists(_.hasValue)
  }

  def query(segments: IterableOnce[String]): Option[BasePathSet.Node] = {
    segments
      .foldLeft(Option(value)) {
        case (None, _) => None
        case (Some(node), segment) => node.children.get(segment)
      }
  }
  def walk(baseSegments: Seq[String]): geny.Generator[IndexedSeq[String]] = {
    walkSubPaths(baseSegments).map(baseSegments.toIndexedSeq ++ _)
  }

  def walkSubPaths(baseSegments: IterableOnce[String]): geny.Generator[IndexedSeq[String]] = {
    new Generator[IndexedSeq[String]] {
      def generate(handleItem: IndexedSeq[String] => Generator.Action): Generator.Action = {

        query(baseSegments) match{
          case None => Generator.Continue
          case Some(base) =>

            def rec(sub: Vector[String], n: BasePathSet.Node): Generator.Action = {
              var state: Generator.Action =
                if (n.hasValue) handleItem(sub)
                else Generator.Continue
              val iter = n.children.iterator
              while(state == Generator.Continue && iter.hasNext){
                val (k, v) = iter.next()
                state = rec(sub :+ k, v)
              }
              state
            }
            rec(Vector(), base)
        }

      }
    }
  }
}
object MutablePathSet{
  class Node extends BasePathSet.Node{
    var hasValue = false
    val children = mutable.Map.empty[String, Node]
  }
}
class MutablePathSet() extends BasePathSet(){

  protected var value = new MutablePathSet.Node()
  protected var size0 = 0
  def size = size0
  def add(segments: IterableOnce[String]): Unit = {
    var current = value
    for(segment <- segments){
      current = current.children.getOrElseUpdate(segment, new MutablePathSet.Node())
    }
    if (!current.hasValue) {
      current.hasValue = true
      size0 += 1
    }
  }
  def clear() = {
    size0 = 0
    value = new MutablePathSet.Node()
    this
  }

}
object PathSet{
  case class Node(hasValue: Boolean = false,
                  children: Map[String, Node] = Map.empty) extends BasePathSet.Node

  /**
    * Shared instance to represent the common case of a file with no children
    */
  val ZeroChildLeaveNode = Node(hasValue = true)
}
class PathSet(protected val value: PathSet.Node = PathSet.Node(),
              size0: Int = 0) extends BasePathSet{

  def size = size0
  def withPaths(segments: geny.Generator[IterableOnce[String]]): PathSet = {
    segments.foldLeft(this)((s, p) => s.withPath(p))
  }
  def withPath(segments: IterableOnce[String]): PathSet = {
    val segmentsIter = segments.iterator
    def rec(current: PathSet.Node): (PathSet.Node, Boolean) = {
      if (!segmentsIter.hasNext) (current.copy(hasValue = true), !current.hasValue)
      else{
        val key = segmentsIter.next
        val (newChild, childNewPath) = current.children.get(key) match{
          case None =>
            if (!segmentsIter.hasNext) (PathSet.ZeroChildLeaveNode, true)
            else rec(PathSet.Node())

          case Some(child) => rec(child)
        }
        (current.copy(children = current.children.updated(key, newChild)), childNewPath)
      }
    }

    val (newValue, newPath) = rec(value)

    new PathSet(newValue, if (newPath) size0 + 1 else size0)

  }
}