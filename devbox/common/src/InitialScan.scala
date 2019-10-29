package devbox.common

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.{ExecutionContext, Future}

object InitialScan {
  def initialSkippedScan(scanRoots: Seq[os.Path], skippers: Seq[Skipper])
                        (f: (os.Path, os.SubPath, Signature, Int, Int) => Unit)
                        (implicit ec: ExecutionContext): Future[Unit] = {
    val buffers = new LinkedBlockingQueue[Array[Byte]]()
    for(i <- 0 until 6) buffers.add(new Array[Byte](Util.blockSize))
    val futuress = for((scanRoot, skipper) <- scanRoots.zip(skippers)) yield {

      if (!os.isDir(scanRoot)) os.makeDir.all(scanRoot)

      val fileStream = os.walk.attrs(
        scanRoot,
        (p, attrs) => {
          skipper.process(scanRoot, Set(p.subRelativeTo(scanRoot) -> attrs.isDir)).isEmpty
        }
      )

      val total = fileStream.size
      Future.foldLeft(
        for(((p, attrs), i) <- fileStream.zipWithIndex) yield Future {
          val buffer = buffers.take()
          try (scanRoot, p.subRelativeTo(scanRoot), Signature.compute(p, buffer, attrs.fileType), i, total)
          finally buffers.put(buffer)
        }
      )(()) {
        case (_, (p, s, Some(sig), i, t)) => f(p, s, sig, i, t)
        case _ => ()
      }.map(_ => ())
    }

    Future.sequence(futuress).map(_ => ())
  }
}
