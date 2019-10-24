package devbox.common
import java.io._

class RpcClient(out: => OutputStream with DataOutput,
                in: => InputStream with DataInput,
                logger: (String, Any) => Unit) {

  def writeMsg[T: upickle.default.Writer](t: T, success: Boolean = true): Unit = {
    logger("MSG WRITE", t)
    val blob = upickle.default.writeBinary(t)

    val compressed = Util.gzip(blob)
    out.writeInt(compressed.length)
    out.write(compressed)
    out.flush()
  }

  def readMsg[T: upickle.default.Reader](): T = {

    val blob = {
      val length = in.readInt()
      val compressed = new Array[Byte](length)
      in.readFully(compressed)
      Util.gunzip(compressed)
    }

    val res =
      try upickle.default.readBinary[T](blob)
      catch{case e: upickle.core.Abort =>
        throw new Exception(upickle.default.readBinary[upack.Msg](blob).toString, e)
      }
    logger("MSG READ", res)

    res
  }
}
