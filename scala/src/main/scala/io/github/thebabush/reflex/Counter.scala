package io.github.thebabush.reflex

class Counter {

  private var counter = 0

  private def mk(s: String) = {
    counter += 1
    s"${s}${counter}"
  }

  def mkMessage(obj: Object): String = {
    mkMessage(obj.getClass.getSimpleName)
  }

  def mkMessage(prefix: String): String = {
    mk(prefix)
  }

}
