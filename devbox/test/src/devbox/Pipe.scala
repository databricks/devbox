package devbox

import java.io._
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
/**
  * Creates a pair of piped streams with a fixed buffer in between, but
  * without the weird "read end dead"/"write end dead" errors that turn up
  * when trying to use [[java.io.PipedInputStream]]/[[java.io.PipedOutputStream]]
  */
class Pipe(bufferSize: Int = 1024) {
  private[this] val buffer = new Array[Byte](bufferSize)
  private[this] val availableWrite = new Semaphore(bufferSize)
  private[this] val availableRead = new Semaphore(0)
  private[this] val writeIndex = new AtomicLong()
  private[this] val readIndex = new AtomicLong()
  object out extends OutputStream with DataOutput{
    private[this] val data = new DataOutputStream(this)
    def write(b: Int) = synchronized{
      availableWrite.acquire(1)
      val i = writeIndex.getAndIncrement()
      buffer((i % bufferSize).toInt) = b.toByte

      availableRead.release(1)
    }

    override def write(b: Array[Byte]): Unit = synchronized{ write(b, 0, b.length) }

    override def write(b: Array[Byte], off: Int, len: Int): Unit = synchronized{
      var n = 0
      while(n < len){
        availableWrite.acquire()
        val allPermits = availableWrite.drainPermits() + 1
        val writeStart = (writeIndex.get() % bufferSize).toInt
        val numToWrite = math.min(allPermits, len - n)
        if (writeStart + numToWrite <= bufferSize){
          System.arraycopy(b, off + n, buffer, writeStart, numToWrite)
        }else{
          val firstHalfLength = bufferSize - writeStart
          System.arraycopy(b, off + n, buffer, writeStart, firstHalfLength)
          System.arraycopy(b, off + n + firstHalfLength, buffer, 0, numToWrite - firstHalfLength)
        }
        n += numToWrite
        writeIndex.addAndGet(numToWrite)
        availableWrite.release(allPermits - numToWrite)
        availableRead.release(numToWrite)
      }
    }

    def writeBoolean(v: Boolean) = synchronized{ data.writeBoolean(v) }
    def writeByte(v: Int) = synchronized{ data.writeByte(v) }
    def writeShort(v: Int) = synchronized{ data.writeShort(v) }
    def writeChar(v: Int) = synchronized{ data.writeChar(v) }
    def writeInt(v: Int) = synchronized{ data.writeInt(v) }
    def writeLong(v: Long) = synchronized{ data.writeLong(v) }
    def writeFloat(v: Float) = synchronized{ data.writeFloat(v) }
    def writeDouble(v: Double) = synchronized{ data.writeDouble(v) }
    def writeBytes(s: String) = synchronized{ data.writeBytes(s) }
    def writeChars(s: String) = synchronized{ data.writeChars(s) }
    def writeUTF(s: String) = synchronized{ data.writeUTF(s) }
  }

  object in extends InputStream with DataInput{
    private[this] val data = new DataInputStream(this)
    override def available() = availableRead.availablePermits()
    def read() = synchronized{
      availableRead.acquire(1)
      val i = readIndex.getAndIncrement()
      val res = buffer((i % bufferSize).toInt) & 0xff

      availableWrite.release(1)
      res
    }

    override def read(b: Array[Byte]) = synchronized{ read(b, 0, b.length) }

    override def read(b: Array[Byte], off: Int, len: Int) = synchronized{
      var n = 0
      while(n < len){
        availableRead.acquire()
        val allPermits = availableRead.drainPermits() + 1
        val readStart = (readIndex.get() % bufferSize).toInt
        val numToRead = math.min(allPermits, len - n)
        if (readStart + numToRead <= bufferSize){
          System.arraycopy(buffer, readStart, b, off + n, numToRead)
        }else{
          val firstHalfLength = bufferSize - readStart
          System.arraycopy(buffer, readStart, b, off + n, firstHalfLength)
          System.arraycopy(buffer, 0, b, off + n + firstHalfLength, numToRead - firstHalfLength)
        }
        n += numToRead
        readIndex.addAndGet(numToRead)
        availableRead.release(allPermits - numToRead)
        availableWrite.release(numToRead)
      }
      len
    }

    def readFully(b: Array[Byte]) = synchronized{ data.readFully(b) }
    def readFully(b: Array[Byte], off: Int, len: Int) = synchronized{ data.readFully(b, off, len) }
    def skipBytes(n: Int) = synchronized{ data.skipBytes(n) }
    def readBoolean() = synchronized{ data.readBoolean() }
    def readByte() = synchronized{ data.readByte() }
    def readUnsignedByte() = synchronized{ data.readUnsignedByte() }
    def readShort() = synchronized{ data.readShort() }
    def readUnsignedShort() = synchronized{ data.readUnsignedShort() }
    def readChar() = synchronized{ data.readChar() }
    def readInt() = synchronized{ data.readInt() }
    def readLong() = synchronized{ data.readLong() }
    def readFloat() = synchronized{ data.readFloat() }
    def readDouble() = synchronized{ data.readDouble() }
    def readLine() = synchronized{ data.readLine() }
    def readUTF() = synchronized{ data.readUTF() }
  }
}
