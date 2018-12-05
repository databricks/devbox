package devbox.common

trait Skipper {
  def initialize(p: os.Path): (os.Path => Boolean)
  def checkReset(p: os.Path): Boolean
}
