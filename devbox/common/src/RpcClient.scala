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
      out.writeBoolean(success)
      out.writeInt(blob.length)
      out.write(blob)
      out.flush()
    }
  }

  def readMsg[T: upickle.default.Reader](): T = {

    val (success, blob) = in.synchronized{
      val success = in.readBoolean()

      val length = in.readInt()
      val blob = new Array[Byte](length)
      in.readFully(blob)
      (success, blob)
    }

    val res =
      if (success) {
        try upickle.default.readBinary[T](blob)
        catch{case e: upickle.core.Abort =>
          throw new Exception(upickle.default.readBinary[upack.Msg](blob).toString, e)
        }
      }
      else throw RpcException(upickle.default.readBinary[RemoteException](blob))
    logger("MSG READ", res)

    res
  }
}
