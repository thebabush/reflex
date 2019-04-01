package io.github.thebabush.reflex

class Pretty
object Pretty {
  def print(regex: Regex): StringBuilder = {
    def np(r: Regex): Boolean = r match {
      case RESet(_)   => false
      case Literal(_) => false
      case _          => true
    }

    def ppp(regex: Regex): StringBuilder = {
      var sb = new StringBuilder()
      if (np(regex)) {
        sb += '('
        sb ++= pp(regex)
        sb += ')'
      } else {
        sb = pp(regex)
      }
      sb
    }

    def ppr(s: String): StringBuilder = {
      var sb = new StringBuilder()

      if (s.equals((1 to 255).mkString)) {
        sb += '.'
      } else if (s.length > 0x80) {
        sb ++= "[^"
        sb ++= Set.from[Char](1.asInstanceOf[Char] to 255).diff(Set.from(s)).toSeq.sorted.flatMap(i2c)
        sb += ']'
      } else {
        sb += '['
        sb ++= s.toSeq.sorted.flatMap(i2c)
        sb += ']'
      }

      sb
    }

    def i2c(c: Char): String = {
      if (c >= 'a' && c <= 'z')
        "" + c
      else if (c >= 'A' && c <= 'Z')
        "" + c
      else if (c >= '0' && c <= '9')
        "" + c
      else if (c == ' ')
        " "
      else if (c == '<')
        "<"
      else c match {
        case '#' => "#"
        case '\t' => "\\t"
        case '\n' => "\\n"
        case '-' => "\\-"
        case ch => f"\\x${ch.asInstanceOf[Int]}%02X"
      }
    }

    def pp(regex: Regex): StringBuilder = {
      var sb = new StringBuilder()
      regex match {
        case Empty()          => sb ++= "∅"
        case Epsilon()        => sb ++= "ε"
        case Literal(c)       => sb ++= i2c(c)
        case Optional(r)      => { sb ++= ppp(r); sb += '?' }
        case Then(r1, r2)     => { sb ++= pp(r1); sb ++= pp(r2) }
        case Or(r1, r2)       => { sb += '('; sb ++= pp(r1); sb += '|'; sb ++= pp(r2); sb += ')' }
        case Star(r)          => { sb ++= ppp(r); sb += '*' }
        case OneOrMore(r)     => { sb ++= ppp(r); sb += '+' }
        case RESet(rs)        => { sb ++= ppr(rs.toSeq.sorted.mkString) }
      }
    }

    regex match {
      case Optional(r) => pp(r)
      case r           => pp(r)
    }
  }
}
