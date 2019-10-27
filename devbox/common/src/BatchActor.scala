package devbox.common
import java.util.concurrent.{CyclicBarrier, Executors, ThreadFactory, TimeUnit}

import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext}

trait ActorContext extends ExecutionContext {
  def executionContext: ExecutionContext
  def reportFailure(t: Throwable): Unit

  def reportSchedule(): Unit
  def reportSchedule(a: Actor[_], msg: Any): Unit

  def reportComplete(): Unit
  def reportComplete(a: Actor[_], msg: Any): Unit

  def scheduleMsg[T](a: Actor[T], msg: T, time: java.time.Duration): Unit

  def execute(runnable: Runnable): Unit = {
    reportSchedule()
    executionContext.execute(new Runnable {
      def run(): Unit = {
        try runnable.run()
        finally reportComplete()
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

    def scheduleMsg[T](a: Actor[T], msg: T, delay: java.time.Duration) = {
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

    def reportSchedule() = ()
    def reportSchedule(a: Actor[_], msg: Any) = ()

    def reportComplete() = ()
    def reportComplete(a: Actor[_], msg: Any) = ()
  }

  class Test(ec: ExecutionContext, logEx: Throwable => Unit) extends ActorContext.Scheduler {
    @volatile private[this] var active = 0L
    @volatile private[this] var promise = concurrent.Promise.successful[Unit](())

    def executionContext = ec
    def reportFailure(t: Throwable) = logEx(t)

    def reportSchedule() = this.synchronized{
      if (active == 0) {
        assert(promise.isCompleted)
        promise = concurrent.Promise[Unit]
      }
      active += 1
    }

    def reportSchedule(a: Actor[_], msg: Any) = reportSchedule()


    def reportComplete() = this.synchronized{
      active -= 1
      if (active == 0) promise.success(())
    }

    def reportComplete(a: Actor[_], msg: Any) = reportComplete()

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
  def send(t: T): Unit
}

abstract class BatchActor[T]()(implicit ac: ActorContext) extends Actor[T]{
  def runBatch(msgs: Seq[T]): Unit

  private val queue = new mutable.Queue[T]()
  private var scheduled = false

  def send(t: T): Unit = synchronized{
    ac.reportSchedule(this, t)
    queue.enqueue(t)
    if (!scheduled){
      scheduled = true
      ac.execute(() => runWithItems())
    }
  }

  private[this] def runWithItems(): Unit = {
    val msgs = synchronized(queue.dequeueAll(_ => true))
    try runBatch(msgs)
    catch{case e: Throwable => ac.reportFailure(e)}
    finally msgs.foreach(m => ac.reportComplete(this, m))
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
  class State(val run: T => State)
  protected[this] def initialState: State
  protected[this] var state: State = initialState
  def run(msg: T): Unit = {
    state = state.run(msg)
  }
}
