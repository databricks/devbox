package devbox

import scala.collection.mutable
import scala.concurrent.ExecutionContext

trait ActorContext extends ExecutionContext {
  def executionContext: ExecutionContext
  def reportFailure(t: Throwable): Unit

  def reportSchedule(): Unit
  def reportSchedule(a: Actor[_], msg: Any): Unit
  def reportComplete(): Unit
  def reportComplete(a: Actor[_], msg: Any): Unit
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
  class Simple(ec: ExecutionContext, logEx: Throwable => Unit) extends ActorContext{
    def executionContext = ec
    def reportFailure(t: Throwable) = logEx(t)
    def reportSchedule() = ()
    def reportSchedule(a: Actor[_], msg: Any) = ()
    def reportComplete() = ()
    def reportComplete(a: Actor[_], msg: Any) = ()
  }
  class Test(ec: ExecutionContext, logEx: Throwable => Unit) extends ActorContext{
    val active = new java.util.concurrent.atomic.AtomicLong(0)
    def executionContext = ec
    def reportFailure(t: Throwable) = logEx(t)
    def reportSchedule() = active.incrementAndGet()
    def reportSchedule(a: Actor[_], msg: Any) = active.incrementAndGet()
    def reportComplete() = active.decrementAndGet()
    def reportComplete(a: Actor[_], msg: Any) = active.decrementAndGet()
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
