package devbox
import utest._

object PipeTests extends TestSuite {
  val tests = Tests{
    def check(totalSize: Int,
              bufferSize: Int,
              read: (Array[Byte], java.io.InputStream) => Unit,
              write: (Array[Byte], java.io.OutputStream) => Unit): Unit = {
      val pipe = new Pipe(bufferSize)
      val source = new Array[Byte](totalSize)
      val dest = new Array[Byte](totalSize)
      val random = new scala.util.Random(313373)
      random.nextBytes(source)
      val reader = new Thread(() => read(dest, pipe.in))
      val writer = new Thread(() => write(source, pipe.out))
      reader.start()
      writer.start()
      reader.join()
      writer.join()
      source ==> dest
    }
    'readByte0 - check(
      totalSize = 10,
      bufferSize = 3,
      read = (dest, in) => for(i <- dest.indices) dest(i) = in.read().toByte,
      write = (source, out) => for(i <- source.indices) out.write(source(i))
    )
    'readByte - check(
      totalSize = 1024 * 1024,
      bufferSize = 1337,
      read = (dest, in) => for(i <- dest.indices) dest(i) = in.read().toByte,
      write = (source, out) => for(i <- source.indices) out.write(source(i))
    )
    'readRange - check(
      totalSize = 10,
      bufferSize = 3,
      read = (dest, in) => {
        var i = 0
        while(i < dest.length){
          val n = in.read(dest, i, math.min(479, dest.length - i))
          i += n
        }

      },
      write = (source, out) => {
        var i = 0
        while(i < source.length){
          val n = math.min(4, source.length - i)
          out.write(source, i, n)
          i += n
        }
      }
    )
    'readRange - check(
      totalSize = 1024*1024,
      bufferSize = 1337,
      read = (dest, in) => {
        var i = 0
        while(i < dest.length){
          val n = in.read(dest, i, math.min(479, dest.length - i))
          i += n
        }

      },
      write = (source, out) => {
        var i = 0
        while(i < source.length){
          val n = math.min(4, source.length - i)
          out.write(source, i, n)
          i += n
        }
      }
    )
  }
}

