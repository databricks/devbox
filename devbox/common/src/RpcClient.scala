package devbox.common
import java.io._
import java.util.concurrent.LinkedBlockingQueue

import devbox.common.Rpc.Ack

import scala.util.control.NonFatal

class RpcClient(var out: OutputStream with DataOutput,
                var in: InputStream with DataInput,
                logger: (String, Any) => Unit) {
  private[this] val pendingQueue = new LinkedBlockingQueue[Rpc]()
  private[this] var flushing = false

  def resetOut(out: OutputStream with DataOutput) = {this.out = out}
  def resetIn(in: InputStream with DataInput) = {this.in = in}

  def clearOutstandingMsgs() = pendingQueue.clear()
  def getOutstandingMsgs = pendingQueue.size()
  def shouldFlush(): Boolean = flushing

  def drainOutstandingMsgs(): Boolean = {
    val msg = try {
      readMsg[Rpc]()
    } catch {
      case NonFatal(ex) => logger("ERROR", ex)
    }
    msg.isInstanceOf[Ack]
  }

  def setShouldFlush() = {
    flushing = true
  }

  def flushOutstandingMsgs() = {
    logger("PENDING FLUSH", pendingQueue.size())
    pendingQueue.iterator().forEachRemaining(rpc => writeMsg0(rpc))
    flushing = false
  }

  def writeMsg0[T: upickle.default.Writer](t: T, success: Boolean = true): Unit = {
    logger("MSG WRITE", t)
    val blob = upickle.default.writeBinary(t)
    out.synchronized {
      out.writeBoolean(success)
      out.writeInt(blob.length)
      out.write(blob)
      out.flush()
    }
  }

  def writeMsg[T: upickle.default.Writer](t: T, success: Boolean = true): Unit = {
    t match {
      case rpc: Rpc if !rpc.isInstanceOf[Rpc.Ack] && !rpc.isInstanceOf[Rpc.FullScan] =>
        pendingQueue.offer(rpc)
      case _ =>
    }
    writeMsg0(t)
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

    res match {
      case Rpc.Ack(hash) =>
        val rpc = pendingQueue.take()
        assert(rpc.hashCode() == hash)
      case _ =>
    }

    res
  }
}
