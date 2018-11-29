package devbox.agent

import java.io.{DataInputStream, DataOutputStream}
import java.nio.channels.FileChannel
import java.nio.file.{Files, Paths, StandardOpenOption}

import devbox.common.{Rpc, Signature, Util}

object Agent {
  def main(args: Array[String]): Unit = {
    System.err.println("Starting agent in " + os.pwd)
    val dataIn = new DataInputStream(System.in)
    val dataOut = new DataOutputStream(System.out)
    while (true){
      val msg = Util.readMsg[Rpc](dataIn)
      System.err.println("AGENT " + msg)
      msg match{
        case Rpc.CheckHash(path) =>
          Util.writeMsg(dataOut, Signature.compute(os.Path(path, os.pwd)))

        case Rpc.Remove(path) =>
          os.remove.all(os.Path(path, os.pwd))
          Util.writeMsg(dataOut, 0)

        case Rpc.PutFile(path, perms) =>
          os.write(os.Path(path, os.pwd), "", perms, createFolders = false)
          Util.writeMsg(dataOut, 0)

        case Rpc.PutDir(path, perms) =>
          os.makeDir(os.Path(path, os.pwd), perms)
          Util.writeMsg(dataOut, 0)

        case Rpc.PutLink(path, dest) =>
          Files.createSymbolicLink(
            os.Path(path, os.pwd).toNIO,
            Paths.get(dest)
          )
          Util.writeMsg(dataOut, 0)

        case Rpc.WriteChunk(path, offset, data) =>
          os.write.write(os.Path(path, os.pwd), data.value, Seq(StandardOpenOption.WRITE), 0, offset)
          Util.writeMsg(dataOut, 0)

        case Rpc.Truncate(path, offset) =>
          val channel = FileChannel.open(os.Path(path, os.pwd) toNIO, StandardOpenOption.WRITE)
          try channel.truncate(offset)
          finally channel.close()
          Util.writeMsg(dataOut, 0)
      }
    }
  }
}
