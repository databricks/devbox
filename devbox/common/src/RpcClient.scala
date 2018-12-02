package devbox.common
import java.io._

class RpcClient(out: OutputStream with DataOutput, in: InputStream with DataInput) {
  private[this] var outstandingMsgs = 0
  def clearOutstandingMsgs() = outstandingMsgs = 0
  def getOutstandingMsgs = outstandingMsgs
  def drainOutstandingMsgs() = {
    while(getOutstandingMsgs > 0) assert(readMsg[Int]() == 0)
  }
  def writeMsg[T: upickle.default.Writer](t: T, success: Boolean = true): Unit = {
    outstandingMsgs += 1
    try {
      val blob = upickle.default.writeBinary(t)
      out.writeBoolean(success)
      out.writeInt(blob.length)
      out.write(blob)
      out.flush()
    }catch{case e: java.io.IOException =>

    }
  }

  def readMsg[T: upickle.default.Reader](): T = {
    outstandingMsgs -= 1
    val success = in.readBoolean()

    val length = in.readInt()
    val blob = new Array[Byte](length)
    in.readFully(blob)
    if (success) upickle.default.readBinary[T](blob)
    else throw RpcException(upickle.default.readBinary[RemoteException](blob))
  }
}
