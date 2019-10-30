package devbox.common

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.{ExecutionContext, Future}

object InitialScan {
  def initialSkippedScan(scanRoots: Seq[os.Path], skippers: Seq[Skipper])
                        (f: (os.Path, os.SubPath, Signature) => Unit)
                        (implicit ec: ExecutionContext): Future[Unit] = {
    val buffers = new LinkedBlockingQueue[Array[Byte]]()
    for(i <- 0 until 6) buffers.add(new Array[Byte](Util.blockSize))
    val futuress = for((scanRoot, skipper) <- scanRoots.zip(skippers)) yield {

      if (!os.isDir(scanRoot)) os.makeDir.all(scanRoot)

      val fileStream = os.walk.stream.attrs(
        scanRoot,
        (p, attrs) => skipper.processSingle(scanRoot, p.subRelativeTo(scanRoot), attrs.isDir)
      )

      Future.foldLeft(
        fileStream
          .map { case (p, attrs) =>
            Future {
              val buffer = buffers.take()
              try Signature.compute(p, buffer, attrs.fileType).map((scanRoot, p.subRelativeTo(scanRoot), _))
              catch{case e: Throwable => None}
              finally buffers.put(buffer)
            }
          }
          .toVector
      )(()) {
        case (_, Some((p, s, sig))) => f(p, s, sig)
        case _ => ()
      }.map(_ => ())
    }

    Future.sequence(futuress).map(_ => ())
  }
}
