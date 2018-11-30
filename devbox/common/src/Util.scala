package devbox.common

import java.io.{DataInputStream, DataOutputStream}
import geny.Generator

object Util {


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
