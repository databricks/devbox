package devbox

import devbox.common.{MutablePathSet, PathSet, Skipper}
import utest._

object PathSetTests extends TestSuite {


  val tests = Tests{
    test("mutable"){
      val s = new MutablePathSet()
      s.getSize ==> 0
      s.add(Seq("hello"))
      s.getSize ==> 1
      s.add(Seq("hello"))
      s.getSize ==> 1
      s.add(Seq("i am", "cow"))
      s.getSize ==> 2
      s.add(Seq("hello", "world"))
      s.getSize ==> 3

      s.walk(Nil).toSet ==> Set(
        Seq("hello"), Seq("hello", "world"), Seq("i am", "cow")
      )
      s.walk(Seq("hello")).toSet ==> Set(Seq("hello"), Seq("hello", "world"))
      s.walk(Seq("hello", "world")).toSet ==> Set(Seq("hello", "world"))

      s.walk(Seq("i am")).toSet ==> Set(Seq("i am", "cow"))

      s.walk(Seq("nope")).toSet ==> Set()

      s.clear()
      s.getSize ==> 0
    }
    test("immutable"){
      var s = new PathSet()
      s.getSize ==> 0
      s = s.withPath(Seq("hello"))
      s.getSize ==> 1
      s = s.withPath(Seq("hello"))
      s.getSize ==> 1
      s = s.withPath(Seq("i am", "cow"))
      s.getSize ==> 2
      s = s.withPath(Seq("hello", "world"))
      s.getSize ==> 3

      s.walk(Nil).toSet ==> Set(
        Seq("hello"), Seq("hello", "world"), Seq("i am", "cow")
      )
    }
  }
}
