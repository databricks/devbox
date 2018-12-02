package devbox
import utest._

object UnitTests extends TestSuite {
  val tests = Tests{
    'pipes - {
      val (in, out) = InMemoryAgent.createPipedStreams(1024)
      val source = new Array[Byte](1024 * 1024)
      val dest = new Array[Byte](1024 * 1024)
      val random = new scala.util.Random(313373)
      random.nextBytes(source)
      val reader = new Thread(
        () => {
          for(i <- dest.indices) dest(i) = in.read().toByte
        }
      )
      val writer = new Thread(
        () => {
          for(i <- source.indices) out.write(source(i))
        }
      )
      reader.start()
      writer.start()
      reader.join()
      writer.join()
      assert(java.util.Arrays.equals(source, dest))
    }
  }
}

