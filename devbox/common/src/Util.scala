package devbox.common

import java.io.{DataInputStream, DataOutputStream}
import java.nio.channels.FileChannel

import geny.Generator

object Util {
  def writeMsg[T: upickle.default.Writer](out: DataOutputStream, t: T): Unit = {
    val blob = upickle.default.writeBinary(t)
    out.writeInt(blob.length)
    out.write(blob)
  }
  def readMsg[T: upickle.default.Reader](in: DataInputStream): T = {
    val length = in.readInt()
    val blob = new Array[Byte](length)
    in.readFully(blob)
    upickle.default.readBinary[T](blob)
  }

  def readChunks(p: os.Path, blockSize: Int): geny.Generator[(Array[Byte], Int)] = {
    val is = os.read.inputStream(p)
    val buffer = new Array[Byte](blockSize)
    var bufferOffset = 0
    new Generator[(Array[Byte], Int)] {
      def generate(handleItem: ((Array[Byte], Int)) => Generator.Action): Generator.Action = {
        var lastAction: Generator.Action = Generator.Continue
        while({
          is.read(buffer, bufferOffset, buffer.length - bufferOffset) match{
            case -1 =>
              lastAction = handleItem((buffer, bufferOffset))
              false
            case n =>
              if (n + bufferOffset == buffer.length){
                lastAction = handleItem((buffer, buffer.length))
                bufferOffset = 0
              }else{
                bufferOffset += n
              }
              lastAction == Generator.Continue
          }
        })()
        lastAction
      }
    }
  }
}
