package devbox
import com.sun.jna.{NativeLong, Pointer}
import io.methvin.watchservice.jna.CarbonAPI.FSEventStreamCallback
import io.methvin.watchservice.jna._

class FSEventsWatcher(srcs: Seq[os.Path], onEvent: Array[String] => Unit) {
  val callback = new FSEventStreamCallback{
    def invoke(streamRef: FSEventStreamRef,
               clientCallBackInfo: Pointer,
               numEvents: NativeLong,
               eventPaths: Pointer,
               eventFlags: Pointer,
               eventIds: Pointer) = {
      val length = numEvents.intValue
      onEvent(eventPaths.getStringArray(0, length))
    }
  }

  val streamRef = CarbonAPI.INSTANCE.FSEventStreamCreate(
    Pointer.NULL,
    callback,
    Pointer.NULL,
    CarbonAPI.INSTANCE.CFArrayCreate(
      null,
      srcs.map(p => CFStringRef.toCFString(p.toString).getPointer).toArray,
      CFIndex.valueOf(1),
      null
    ),
    -1,
    0.01,
    0
  )

  var current: CFRunLoopRef = null

  def start() = {
    CarbonAPI.INSTANCE.FSEventStreamScheduleWithRunLoop(
      streamRef,
      CarbonAPI.INSTANCE.CFRunLoopGetCurrent(),
      CFStringRef.toCFString("kCFRunLoopDefaultMode")
    )
    CarbonAPI.INSTANCE.FSEventStreamStart(streamRef)
    current = CarbonAPI.INSTANCE.CFRunLoopGetCurrent()
    CarbonAPI.INSTANCE.CFRunLoopRun()
  }

  def stop() = {
    CarbonAPI.INSTANCE.CFRunLoopStop(current)
  }
}
