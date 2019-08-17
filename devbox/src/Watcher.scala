package devbox

abstract class Watcher extends AutoCloseable{
  def start(): Unit
}
