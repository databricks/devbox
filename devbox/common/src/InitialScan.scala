package devbox.common

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.{ExecutionContext, Future}

object InitialScan {
  def initialSkippedScan(scanRoots: Seq[os.Path], skippers: Seq[Skipper])
                        (f: (os.Path, os.SubPath, Signature, Int) => Unit)
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
          .zipWithIndex
          .map { case ((p, attrs), i) =>
            Future {
              val buffer = buffers.take()
              try (scanRoot, p.subRelativeTo(scanRoot), Signature.compute(p, buffer, attrs.fileType), i)
              finally buffers.put(buffer)
            }
          }
          .toVector
      )(()) {
        case (_, (p, s, Some(sig), i)) => f(p, s, sig, i)
        case _ => ()
      }.map(_ => ())
    }

    Future.sequence(futuress).map(_ => ())
  }
}
