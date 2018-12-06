package devbox.common
import java.io._
import java.util.concurrent.atomic.AtomicInteger

class RpcClient(out: OutputStream with DataOutput,
                in: InputStream with DataInput,
                logger: (String, Any) => Unit) {
  private[this] val outstandingMsgs = new AtomicInteger()
  def clearOutstandingMsgs() = outstandingMsgs.set(0)
  def getOutstandingMsgs = outstandingMsgs.get()
  def drainOutstandingMsgs() = {
    while(getOutstandingMsgs > 0) assert(readMsg[Int]() == 0)
  }
  def writeMsg[T: upickle.default.Writer](t: T, success: Boolean = true): Unit = {
    logger("MSG WRITE", t)
    outstandingMsgs.incrementAndGet()

    val blob = upickle.default.writeBinary(t)
    out.synchronized {
      out.writeBoolean(success)
      out.writeInt(blob.length)
      out.write(blob)
      out.flush()
    }
  }

  def readMsg[T: upickle.default.Reader](): T = {

    outstandingMsgs.decrementAndGet()
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
