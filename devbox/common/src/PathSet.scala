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
  * A simple high-performance mutable Set of path segments, implemented as a tree.
  *
  * Comes in both mutable and immutable flavors. Generally append-only, with no
  * provision for removing elements from the set except by throwing away the whole thing
  *
  * Allows efficient [[containsPathPrefix]] checks, as well as iteration over prefixed
  * elements via [[walk]]
  */
abstract class BasePathSet(){


  protected def value: BasePathSet.Node

  def getSize: Int

  def containsPathPrefix(segments: IterableOnce[String]): Boolean = {
    query(segments).nonEmpty
  }

  def query(segments: IterableOnce[String]): Option[BasePathSet.Node] = {
    segments
      .foldLeft(Option(value)) {
        case (None, _) => None
        case (Some(node:BasePathSet. Node), segment) => node.children.get(segment)
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
  protected var size = 0
  def getSize = size
  def add(segments: IterableOnce[String]): Unit = {
    var current = value
    for(segment <- segments){
      current = current.children.getOrElseUpdate(segment, new MutablePathSet.Node())
    }
    if (!current.hasValue) {
      current.hasValue = true
      size += 1
    }
  }
  def clear() = {
    size = 0
    value = new MutablePathSet.Node()
    this
  }

}
object PathSet{
  case class Node(hasValue: Boolean = false,
                  children: Map[String, Node] = Map()) extends BasePathSet.Node
}
class PathSet(protected val value: PathSet.Node = PathSet.Node(),
              size: Int = 0) extends BasePathSet{

  def getSize = size
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
          case None => rec(PathSet.Node())
          case Some(child) => rec(child)
        }
        (current.copy(children = current.children.updated(key, newChild)), childNewPath)
      }
    }

    val (newValue, newPath) = rec(value)

    new PathSet(newValue, if (newPath) size + 1 else size)

  }
}