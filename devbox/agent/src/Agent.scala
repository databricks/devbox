package devbox.agent

import java.io.{DataInputStream, DataOutputStream}
import java.nio.channels.FileChannel
import java.nio.file.{Files, Paths, StandardOpenOption}

import devbox.common.{Rpc, Signature, Util}

object Agent {
  def main(args: Array[String]): Unit = {
    val verbose = args(0).toBoolean
    if (verbose) System.err.println("Starting agent in " + os.pwd)

    val dataIn = new DataInputStream(System.in)
    val dataOut = new DataOutputStream(System.out)

    while (true){
      val msg = Util.readMsg[Rpc](dataIn)
      if (verbose) System.err.println("AGENT " + msg)
      msg match{
        case Rpc.FullScan(path) =>
          val scanned = os.walk(
            os.Path(path, os.pwd),
            p => p.segments.contains(".git") && !os.isDir(p, followLinks = false)
          ).map(
            p => (p.relativeTo(os.pwd).toString, Signature.compute(p))
          )
          Util.writeMsg(dataOut, scanned)

        case Rpc.CheckHash(path) =>
          val sig = Signature.compute(os.Path(path, os.pwd))
          System.err.println(sig)
          Util.writeMsg(dataOut, sig)

        case Rpc.Remove(path) =>
          val p = os.Path(path, os.pwd)
          if (os.isLink(p)) os.remove(p)
          else os.remove.all(p)
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

        case Rpc.WriteChunk(path, offset, data, hash) =>
          os.write.write(os.Path(path, os.pwd), data.value, Seq(StandardOpenOption.WRITE), 0, offset)
          Util.writeMsg(dataOut, 0)

        case Rpc.Truncate(path, offset) =>
          val channel = FileChannel.open(os.Path(path, os.pwd) toNIO, StandardOpenOption.WRITE)
          try channel.truncate(offset)
          finally channel.close()
          Util.writeMsg(dataOut, 0)

        case Rpc.SetPerms(path, perms) =>
          os.perms.set.apply(os.Path(path, os.pwd), perms)
          Util.writeMsg(dataOut, 0)
      }
    }
  }
}
