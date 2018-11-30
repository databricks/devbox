package devbox.agent

import java.io.{DataInputStream, DataOutputStream}
import java.nio.channels.FileChannel
import java.nio.file.{Files, Paths, StandardOpenOption}

import devbox.common._

object Agent {
  def main(args: Array[String]): Unit = {
    val verbose = args(0).toBoolean
    if (verbose) System.err.println("Starting agent in " + os.pwd)

    val client = new RpcClient(
      new DataOutputStream(System.out),
      new DataInputStream(System.in)
    )
    try {
      while (true) {
        val msg = client.readMsg[Rpc]()
        if (verbose) System.err.println("AGENT " + msg)
        msg match {
          case Rpc.FullScan(path) =>
            val scanned = os.walk(
              os.Path(path, os.pwd),
              p => p.segments.contains(".git") && !os.isDir(p, followLinks = false)
            ).map(
              p => (p.relativeTo(os.pwd).toString, Signature.compute(p))
            )
            client.writeMsg(scanned)

          case Rpc.CheckHash(path) =>
            val sig = Signature.compute(os.Path(path, os.pwd))
            System.err.println(sig)
            client.writeMsg(sig)

          case Rpc.Remove(path) =>
            val p = os.Path(path, os.pwd)
            if (os.isLink(p)) os.remove(p)
            else os.remove.all(p)
            client.writeMsg(0)

          case Rpc.PutFile(path, perms) =>
            os.write(os.Path(path, os.pwd), "", perms, createFolders = false)
            client.writeMsg(0)

          case Rpc.PutDir(path, perms) =>
            os.makeDir(os.Path(path, os.pwd), perms)
            client.writeMsg(0)

          case Rpc.PutLink(path, dest) =>
            Files.createSymbolicLink(
              os.Path(path, os.pwd).toNIO,
              Paths.get(dest)
            )
            client.writeMsg(0)

          case Rpc.WriteChunk(path, offset, data, hash) =>
            os.write.write(os.Path(path, os.pwd), data.value, Seq(StandardOpenOption.WRITE), 0, offset)
            client.writeMsg(0)

          case Rpc.Truncate(path, offset) =>
            val channel = FileChannel.open(os.Path(path, os.pwd) toNIO, StandardOpenOption.WRITE)
            try channel.truncate(offset)
            finally channel.close()
            client.writeMsg(0)

          case Rpc.SetPerms(path, perms) =>
            os.perms.set.apply(os.Path(path, os.pwd), perms)
            client.writeMsg(0)
        }
      }
    } catch{ case e: Throwable =>
      client.writeMsg(RemoteException.create(e), false)
      System.exit(1)
    }
  }
}
