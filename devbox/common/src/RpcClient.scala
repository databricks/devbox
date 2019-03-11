package devbox.common
import java.io._
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import scala.util.control.NonFatal

class RpcClient(var out: OutputStream with DataOutput,
                var in: InputStream with DataInput,
                logger: (String, Any) => Unit,
                ackPing: Option[() => Unit] = None) {
  private[this] val outstandingMsgs = new AtomicInteger()
  private[this] val pendingQueue = new ConcurrentHashMap[Int, Rpc]()

  def resetOut(out: OutputStream with DataOutput) = {this.out = out}
  def resetIn(in: InputStream with DataInput) = {this.in = in}

  def clearOutstandingMsgs() = outstandingMsgs.set(0)
  def getOutstandingMsgs = outstandingMsgs.get()
  def drainOutstandingMsgs() = {
    while(getOutstandingMsgs >= 0) {
      try {
        readMsg[Rpc]()
      } catch {
        case NonFatal(ex) => logger("ERROR", ex)
      }
    }
  }

  def flushOutstandingMsgs() = {
    logger("PENDING FLUSH", pendingQueue.size())
    pendingQueue.values().forEach { rpc =>
      logger("FLUSH", rpc)
      outstandingMsgs.incrementAndGet()
      writeMsg0(rpc)
    }
  }

  def writeMsg0[T: upickle.default.Writer](t: T, success: Boolean = true): Unit = {
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

  def writeMsg[T: upickle.default.Writer](t: T, success: Boolean = true): Unit = {
    t match {
      case rpc: Rpc if !rpc.isInstanceOf[Rpc.Ack] =>
        logger("KEY", rpc.hashCode())
        logger("VALUE", rpc)
        pendingQueue.put(rpc.hashCode(), rpc)
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
    outstandingMsgs.decrementAndGet()

    res match {
      case Rpc.Ack(hash) =>
        logger(s"ACK VALUE", pendingQueue.get(hash))
        logger(s"ACK", s"Pending queue size ${pendingQueue.size()}")
        pendingQueue.remove(hash)
      case Rpc.Pong() =>
        ackPing.get()
      case _ =>
    }

    res
  }

  def ping(): Unit = {
    val ping = Rpc.Ping()
    writeMsg0(ping)
  }

  def pong(): Unit = {
    val pong = Rpc.Pong()
    writeMsg0(pong)
  }
}
