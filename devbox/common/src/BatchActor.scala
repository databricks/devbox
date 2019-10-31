package devbox.common
import java.util.concurrent.{CyclicBarrier, Executors, ThreadFactory, TimeUnit}

import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext}

trait ActorContext extends ExecutionContext {
  def getActive = 0L
  def executionContext: ExecutionContext
  def reportFailure(t: Throwable): Unit

  def reportSchedule()
                    (implicit fileName: sourcecode.FileName,
                     line: sourcecode.Line): Unit

  def reportSchedule(a: Actor[_], msg: Any)
                    (implicit fileName: sourcecode.FileName,
                     line: sourcecode.Line): Unit = reportSchedule()

  def reportComplete()
                    (implicit fileName: sourcecode.FileName,
                     line: sourcecode.Line): Unit

  def reportComplete(a: Actor[_], msg: Any)
                    (implicit fileName: sourcecode.FileName,
                     line: sourcecode.Line): Unit = reportComplete()

  def scheduleMsg[T](a: Actor[T], msg: T, time: java.time.Duration)
                    (implicit fileName: sourcecode.FileName,
                     line: sourcecode.Line): Unit

  def execute(runnable: Runnable): Unit = {
    val fileName = sourcecode.FileName()
    val line = sourcecode.Line()
    reportSchedule()(fileName, line)
    executionContext.execute(new Runnable {
      def run(): Unit = {
        try runnable.run()
        finally reportComplete()(fileName, line)
      }
    })
  }
}

object ActorContext{
  trait Scheduler extends ActorContext {
    lazy val scheduler = Executors.newSingleThreadScheduledExecutor(
      new ThreadFactory {
        def newThread(r: Runnable): Thread = {
          val t = new Thread(r, "ActorContext-Scheduler-Thread")
          t.setDaemon(true)
          t
        }
      }
    )

    def scheduleMsg[T](a: Actor[T], msg: T, delay: java.time.Duration)
                      (implicit fileName: sourcecode.FileName,
                       line: sourcecode.Line)= {
      reportSchedule(a, msg)
      scheduler.schedule[Unit](
        () => {
          a.send(msg)
          reportComplete(a, msg)
        },
        delay.toMillis,
        TimeUnit.MILLISECONDS
      )
    }
  }

  class Simple(ec: ExecutionContext, logEx: Throwable => Unit) extends ActorContext.Scheduler {
    def executionContext = ec
    def reportFailure(t: Throwable) = logEx(t)

    def reportSchedule()
                      (implicit fileName: sourcecode.FileName,
                       line: sourcecode.Line)= ()

    def reportComplete()
                      (implicit fileName: sourcecode.FileName,
                       line: sourcecode.Line)= ()
  }

  class Test(ec: ExecutionContext, logEx: Throwable => Unit) extends ActorContext.Scheduler {
    @volatile private[this] var active = 0L
    @volatile private[this] var promise = concurrent.Promise.successful[Unit](())
    override def getActive = synchronized( active )
    def executionContext = ec
    def reportFailure(t: Throwable) = logEx(t)

    def reportSchedule()
                      (implicit fileName: sourcecode.FileName,
                       line: sourcecode.Line) = this.synchronized{
//      println(s"reportSchedule (${fileName.value}:${line.value}) $active -> ${active + 1}")
      if (active == 0) {
        assert(promise.isCompleted)
        promise = concurrent.Promise[Unit]
      }
      active += 1
    }

    override def reportSchedule(a: Actor[_], msg: Any)
                               (implicit fileName: sourcecode.FileName,
                                line: sourcecode.Line) = this.synchronized{
//      println(s"reportSchedule (${fileName.value}:${line.value}) ($a, $msg) $active -> ${active + 1}")
      if (active == 0) {
        assert(promise.isCompleted)
        promise = concurrent.Promise[Unit]
      }
      active += 1
    }


    def reportComplete()
                      (implicit fileName: sourcecode.FileName,
                       line: sourcecode.Line) = this.synchronized{
//      println(s"reportComplete (${fileName.value}:${line.value}) $active -> ${active - 1}")
      assert(active > 0)
      active -= 1

      if (active == 0) promise.success(())
    }

    override def reportComplete(a: Actor[_], msg: Any)
                               (implicit fileName: sourcecode.FileName,
                                line: sourcecode.Line) = this.synchronized{
//      println(s"reportComplete (${fileName.value}:${line.value}) ($a, $msg) $active -> ${active - 1}")
      assert(active > 0)
      active -= 1
      if (active == 0) promise.success(())
    }

    def waitForInactivity(timeout: Option[java.time.Duration] = None) = {
      Await.result(
        promise.future,
        timeout match{
          case None => scala.concurrent.duration.Duration.Inf
          case Some(t) => scala.concurrent.duration.Duration.fromNanos(t.toNanos)
        }
      )
    }
  }
}

trait Actor[T]{
  def send(t: T)
          (implicit fileName: sourcecode.FileName,
           line: sourcecode.Line): Unit
}

class ProxyActor[T, V](f: T => V, downstream: Actor[V])
                      (implicit ac: ActorContext) extends SimpleActor[T]{
  def run(msg: T): Unit = downstream.send(f(msg))
}

abstract class BatchActor[T]()(implicit ac: ActorContext) extends Actor[T]{
  def runBatch(msgs: Seq[T]): Unit

  private val queue = new mutable.Queue[(T, sourcecode.FileName, sourcecode.Line)]()
  private var scheduled = false

  def send(t: T)
          (implicit fileName: sourcecode.FileName,
           line: sourcecode.Line): Unit = synchronized{
    ac.reportSchedule(this, t)
    queue.enqueue((t, fileName, line))
    if (!scheduled){
      scheduled = true
      ac.execute(() => runWithItems())
    }
  }

  private[this] def runWithItems(): Unit = {
    val msgs = synchronized(queue.dequeueAll(_ => true))
    try runBatch(msgs.map(_._1))
    catch{case e: Throwable => ac.reportFailure(e)}
    finally msgs.foreach(m => ac.reportComplete(this, m._1)(m._2, m._3))
    synchronized{
      if (queue.nonEmpty) ac.execute(() => runWithItems())
      else{
        assert(scheduled)
        scheduled = false
      }
    }
  }
}
abstract class SimpleActor[T]()(implicit ac: ActorContext) extends BatchActor[T]{
  def run(msg: T): Unit
  def runBatch(msgs: Seq[T]): Unit = msgs.foreach{ msg =>
    try run(msg)
    catch{case e: Throwable => ac.reportFailure(e)}
  }
}

abstract class StateMachineActor[T]()
                                   (implicit ac: ActorContext) extends SimpleActor[T]() {

  class State(run0: T => State = null) {
    def run = run0
  }
  protected[this] def initialState: State
  protected[this] var state: State = initialState
  def run(msg: T): Unit = {
    state = state.run(msg)
  }
}
