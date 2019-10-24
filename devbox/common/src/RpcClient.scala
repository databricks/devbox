package devbox.common
import java.io._

class RpcClient(var out: OutputStream with DataOutput,
                var in: InputStream with DataInput,
                logger: (String, Any) => Unit) {

  def resetOut(out: OutputStream with DataOutput) = {this.out = out}
  def resetIn(in: InputStream with DataInput) = {this.in = in}

  def writeMsg[T: upickle.default.Writer](t: T, success: Boolean = true): Unit = {
    logger("MSG WRITE", t)
    val blob = upickle.default.writeBinary(t)
    out.synchronized {
      val compressed = Util.gzip(blob)
      out.writeInt(compressed.length)
      out.write(compressed)
      out.flush()
    }
  }

  def readMsg[T: upickle.default.Reader](): T = {

    val blob = in.synchronized{
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
