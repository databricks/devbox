package devbox.common
import geny.Generator

import collection.mutable
object BasePathMap{
  trait Node[T] {
    def valueOpt: Option[T]
    def children: collection.Map[String, Node[T]]
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
abstract class BasePathMap[T](){


  protected def root: BasePathMap.Node[T]

  def size: Int

  def containsPathPrefix(segments: IterableOnce[String]): Boolean = {
    query(segments).nonEmpty
  }

  def contains(segments: IterableOnce[String]): Boolean = {
    query(segments).exists(_.valueOpt.nonEmpty)
  }

  def query(segments: IterableOnce[String]): Option[BasePathMap.Node[T]] = {
    segments
      .foldLeft(Option(root)) {
        case (None, _) => None
        case (Some(node), segment) => node.children.get(segment)
      }
  }
  def walkValues(baseSegments: Seq[String] = Nil): geny.Generator[(IndexedSeq[String], T)] = {
    walkSubPathsValues(baseSegments).map{case (p, v) => (baseSegments.toIndexedSeq ++ p, v)}
  }

  def walkSubPathsValues(baseSegments: IterableOnce[String]): geny.Generator[(IndexedSeq[String], T)] = {
    new Generator[(IndexedSeq[String], T)] {
      def generate(handleItem: ((IndexedSeq[String], T)) => Generator.Action): Generator.Action = {

        query(baseSegments) match{
          case None => Generator.Continue
          case Some(base) =>

            def rec(sub: Vector[String], n: BasePathMap.Node[T]): Generator.Action = {
              var state: Generator.Action = n.valueOpt match{
                case Some(v) => handleItem((sub, v))
                case None => Generator.Continue
              }
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

  def walkSubPaths(baseSegments: IterableOnce[String]) = walkSubPathsValues(baseSegments).map(_._1)

  def walk(baseSegments: Seq[String] = Nil) = walkValues(baseSegments).map(_._1)
}
object MutablePathMap{
  class Node[T] extends BasePathMap.Node[T]{
    var valueOpt = Option.empty[T]
    val children = mutable.Map.empty[String, Node[T]]
  }
}
class MutablePathMap[T]() extends BasePathMap[T](){

  protected var root = new MutablePathMap.Node()
  protected var size0 = 0
  def size = size0
  def add(segments: IterableOnce[String], v: T): Unit = {
    var current = root
    for(segment <- segments){
      current = current.children.getOrElseUpdate(segment, new MutablePathMap.Node[T]())
    }
    if (current.valueOpt.isEmpty) {
      current.valueOpt = Some(v)
      size0 += 1
    }
  }
  def clear() = {
    size0 = 0
    root = new MutablePathMap.Node()
    this
  }
}


object PathMap{
  case class Node[T](valueOpt: Option[T] = None,
                     children: Map[String, Node[T]] = Map.empty[String, Node[T]]) extends BasePathMap.Node[T]

  def apply[T](paths: IterableOnce[(IterableOnce[String], T)]) = new PathMap().withPaths(paths)


}
class PathMap[T](protected val root: PathMap.Node[T] = PathMap.Node[T](),
                 size0: Int = 0) extends BasePathMap[T]{

  def size = size0
  def withPaths(segments: geny.Generator[(IterableOnce[String], T)]): PathMap[T] = {
    segments.foldLeft(this)((s, p) => s.withPath(p._1, p._2))
  }
  def withPath(segments: IterableOnce[String], value: T): PathMap[T] = {
    val (newValue, newPath) = withPath0(segments, value)
    new PathMap(newValue, if (newPath) size0 + 1 else size0)
  }
  def withPath0(segments: IterableOnce[String], value: T) = {
    val segmentsIter = segments.iterator
    def rec(current: PathMap.Node[T]): (PathMap.Node[T], Boolean) = {
      if (!segmentsIter.hasNext) (current.copy(valueOpt = Some(value)), current.valueOpt.isEmpty)
      else {
        val key = segmentsIter.next
        val (newChild, childNewPath) = current.children.get(key) match {
          case None => rec(PathMap.Node())
          case Some(child) => rec(child)
        }
        (current.copy(children = current.children.updated(key, newChild)), childNewPath)
      }
    }

    rec(root)

  }
}

class MutablePathSet() extends MutablePathMap[Unit] {
  def add(segments: IterableOnce[String]): Unit =  add(segments, ())
}

object PathSet{
  def apply[T](paths: IterableOnce[IterableOnce[String]]) = new PathSet().withPaths(paths)
}
class PathSet(root: PathMap.Node[Unit] = PathMap.Node[Unit](),
              size0: Int = 0) extends PathMap[Unit](root, size0) {
  def withPaths(segments: geny.Generator[IterableOnce[String]]): PathSet = {
    segments.foldLeft(this)((s, p) => s.withPath(p))
  }
  def withPath(segments: IterableOnce[String]): PathSet = {
    val (newValue, newPath) = withPath0(segments, ())
    new PathSet(newValue, if (newPath) size0 + 1 else size0)
  }
}