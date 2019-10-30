package devbox

import devbox.common.{MutablePathSet, PathSet, Skipper}
import utest._

object PathSetTests extends TestSuite {


  val tests = Tests{
    test("mutable"){
      val s = new MutablePathSet()
      s.size ==> 0
      s.add(Seq("hello"))
      s.size ==> 1
      s.add(Seq("hello"))
      s.size ==> 1
      s.add(Seq("i am", "cow"))
      s.size ==> 2
      s.add(Seq("hello", "world"))
      s.size ==> 3

      s.walk(Nil).toSet ==> Set(
        Seq("hello"), Seq("hello", "world"), Seq("i am", "cow")
      )
      s.walk(Seq("hello")).toSet ==> Set(Seq("hello"), Seq("hello", "world"))
      s.walk(Seq("hello", "world")).toSet ==> Set(Seq("hello", "world"))

      s.walk(Seq("i am")).toSet ==> Set(Seq("i am", "cow"))

      s.walk(Seq("nope")).toSet ==> Set()

      s.clear()
      s.size ==> 0
    }
    test("immutable"){
      var s = new PathSet()
      s.size ==> 0
      s = s.withPath(Seq("hello"))
      s.size ==> 1
      s = s.withPath(Seq("hello"))
      s.size ==> 1
      s = s.withPath(Seq("i am", "cow"))
      s.size ==> 2
      s = s.withPath(Seq("hello", "world"))
      s.size ==> 3

      s.walk(Nil).toSet ==> Set(
        Seq("hello"), Seq("hello", "world"), Seq("i am", "cow")
      )
    }
  }
}
