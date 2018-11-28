package devbox.agent

import java.io.{DataInputStream, DataOutputStream}
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

import devbox.common.{Rpc, Signature, Util}

object Agent {
  def main(args: Array[String]): Unit = {
    val dataIn = new DataInputStream(System.in)
    val dataOut = new DataOutputStream(System.out)
    while (true){
      Util.readMsg[Rpc](dataIn) match{
        case Rpc.CheckHash(path) =>
          Util.writeMsg(dataOut, Signature.compute(os.root/path))

        case Rpc.Remove(path) =>
          os.remove.all(os.root/path)
          dataOut.writeInt(0)

        case Rpc.PutFile(path, perms) =>
          os.write(os.root/path, "", perms, createFolders = false)
          dataOut.writeInt(0)

        case Rpc.PutDir(path, perms) =>
          os.makeDir(os.root/path, perms)
          dataOut.writeInt(0)

        case Rpc.PutLink(path, dest) =>
          os.symlink(os.root/path, os.root/dest)
          dataOut.writeInt(0)

        case Rpc.WriteChunk(path, offset, data) =>
          os.write.write(os.root/path, data, Seq(StandardOpenOption.WRITE), 0, offset)
          dataOut.writeInt(0)

        case Rpc.Truncate(path, offset) =>
          val channel = FileChannel.open(os.root/path toNIO)
          try channel.truncate(offset)
          finally channel.close()
          dataOut.writeInt(0)
      }
    }
  }
}
