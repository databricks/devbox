package devbox
import com.sun.jna.{NativeLong, Pointer}
import devbox.common.Logger

class FSEventsWatcher(srcs: Seq[os.Path],
                      onEvent: Array[String] => Unit,
                      logger: Logger,
                      latency: Double) extends Watcher{
  val callback = new FSEventStreamCallback{
    def invoke(streamRef: FSEventStreamRef,
               clientCallBackInfo: Pointer,
               numEvents: NativeLong,
               eventPaths: Pointer,
               eventFlags: Pointer,
               eventIds: Pointer) = {
      val length = numEvents.intValue
      val p = eventPaths.getStringArray(0, length)
      logger("SYNC FSEVENT", p)
      onEvent(p)
    }
  }

  val streamRef = CarbonApi.INSTANCE.FSEventStreamCreate(
    Pointer.NULL,
    callback,
    Pointer.NULL,
    CarbonApi.INSTANCE.CFArrayCreate(
      null,
      srcs.map(p => CFStringRef.toCFString(p.toString).getPointer).toArray,
      CFIndex.valueOf(srcs.length),
      null
    ),
    -1,
    latency,
    0
  )

  var current: CFRunLoopRef = null

  def start() = {
    CarbonApi.INSTANCE.FSEventStreamScheduleWithRunLoop(
      streamRef,
      CarbonApi.INSTANCE.CFRunLoopGetCurrent(),
      CFStringRef.toCFString("kCFRunLoopDefaultMode")
    )
    CarbonApi.INSTANCE.FSEventStreamStart(streamRef)
    current = CarbonApi.INSTANCE.CFRunLoopGetCurrent()
    logger("SYNC FSLOOP RUN")
    CarbonApi.INSTANCE.CFRunLoopRun()
    logger("SYNC FSLOOP END")
  }

  def close() = {
    logger("SYNC FSLOOP STOP")
    CarbonApi.INSTANCE.CFRunLoopStop(current)
    CarbonApi.INSTANCE.FSEventStreamStop(streamRef)
    CarbonApi.INSTANCE.FSEventStreamUnscheduleFromRunLoop(
      streamRef,
      current,
      CFStringRef.toCFString("kCFRunLoopDefaultMode")
    )
    CarbonApi.INSTANCE.FSEventStreamInvalidate(streamRef)
    CarbonApi.INSTANCE.FSEventStreamRelease(streamRef)
    logger("SYNC FSLOOP STOP2")
  }
}
