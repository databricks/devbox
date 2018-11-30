package devbox.common
import java.io.{DataInputStream, DataOutputStream}

class RpcClient(out: DataOutputStream, in: DataInputStream) {
  def writeMsg[T: upickle.default.Writer](t: T, success: Boolean = true): Unit = {
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
    val success = in.readBoolean()

    val length = in.readInt()
    val blob = new Array[Byte](length)
    in.readFully(blob)
    if (success) upickle.default.readBinary[T](blob)
    else throw RpcException(upickle.default.readBinary[RemoteException](blob))
  }
}
