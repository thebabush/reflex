package io.github.thebabush.reflex

import scala.util.control.Breaks.{break, breakable}

class Simple
object Simple {

  def simplify(root: Regex): Regex = {
    var ret: Regex = root
    var modified = true

    while (modified) {
      val t = traverse(ret)
      ret = t._1
      modified = t._2
    }

    ret
  }

  def simplify(root: Regex, max: Int): Regex = {
    var ret: Regex = root
    var modified = true

    breakable {
      for (i <- 0 until max) {
        val t = traverse(ret)
        ret = t._1
        modified = t._2
        if (!modified) break
      }
    }

    ret
  }

  private def traverse(root: Regex): (Regex, Boolean) = {
    var modified = false;
    var ret = root;

    val applied = applyRules(ret)
    ret = applied._1
    modified = applied._2

    if (!modified) {
      root match {
        case Empty() => ()
        case Epsilon() => ()
        case Literal(_) => ()
        case OneOrMore(r) => {
          val (c, m) = traverse(r)
          modified |= m
          ret = OneOrMore(c)
        }
        case Optional(r) => {
          val (c, m) = traverse(r)
          modified |= m
          ret = Optional(c)
        }
        case Or(l, r) => {
          val ll = traverse(l)
          modified |= ll._2
          val rr = traverse(r)
          modified |= rr._2
          ret = Or(ll._1, rr._1)
        }
        case RESet(_) => ()
        case Star(r) => {
          val (c, m) = traverse(r)
          modified |= m
          ret = Star(c)
        }
        case Then(l, r) => {
          val (lr, lm) = traverse(l)
          modified |= lm
          val (rr, rm) = traverse(r)
          modified |= rm
          ret = Then(lr, rr)
        }
      }
    }

    (ret, modified)
  }

  private def applyRules(regex: Regex): (Regex, Boolean) = {
    var ret = regex;
    var modified = true;

    /*
    Simplification rules.
    Some of them are taken from https://github.com/izuzak/noam/blob/277242b16ac3f8ced94c4c61a4082610cc876792/src/noam.re.js
     */
    ret = regex match {
      // TODO: Test all this lol
      case Or(Empty(), r) => r
      case Or(r, Empty()) => r
      case Or(r, Epsilon()) => Optional(r)
      case Or(Epsilon(), r) => Optional(r)
      case Then(Empty(), _) => Empty()
      case Then(_, Empty()) => Empty()
      case Then(Epsilon(), a) => a
      case Then(a, Epsilon()) => a
      // TESTED (r*)* => r*
      case Star(Star(r))    => Star(r)
      // TESTED (a|b*)* => (a|b)*
      case Star(Or(r, Star(s))) => Star(Or(r, s))
      // TESTED (b*|a)* => (a|b)*
      case Star(Or(Star(r), s)) => Star(Or(r, s))
      case RESet(xs) if xs.size == 1 => Literal(xs.head)
      case Optional(Empty()) => Epsilon()
      case Optional(Star(r)) => Star(r)
      case Or(Literal(a), Literal(b)) => RESet(Set(a, b))
      case Or(RESet(aa), RESet(bb)) => RESet(aa ++ bb)
      case Star(Epsilon()) => Epsilon()
      case Star(Empty()) => Epsilon()
      // TESTED (a?|b)+ => (a|b)*
      case OneOrMore(Or(Optional(a), b)) => Star(Or(a, b))
      // TESTED (a|b?)+ => (a|b)*
      case OneOrMore(Or(a, Optional(b))) => Star(Or(a, b))
      // TESTED (a?|b?)+ => (a|b)*
      //case OneOrMore(Or(Optional(a), Optional(b))) => Star(Or(a, b)) // UNREACHABLE
      /* Kinda expensive */
      // TESTED aa* => a+
      case Then(r, Star(s)) if r == s => OneOrMore(r)
      // TESTED a*a => a+
      case Then(Star(r), s) if r == s => OneOrMore(r)
      // TESTED aa*b => a+b
      case Then(a, Then(Star(b), c)) if a == b => Then(OneOrMore(a), c)
      // TESTED a*a*b => a*b
      case Then(Star(a), Then(Star(b), c)) if a == b => Then(Star(a), c)
      // TESTED (a|ba) => b?a
      case Or(a, Then(b, c)) if a == c => Then(Optional(b), a)
      // (a|(a|b)) => (a|b)
      case Or(a, Or(b, c)) if a == b => Or(a, c)
      // TESTED (ab|ac) => a(b|c)
      case Or(Then(a, b), Then(c, d)) if a == c => Then(a, Or(b, d))
      // TESTED (a|a) => a
      case Or(a, b) if a == b => a
      /* Ad-hoc */
      // TESTED ((a?|aa))* => a*
      case Star(Or(Optional(a), Then(b, c))) if a == b && b == c => Star(a)
      // a|ac => ac?
      case Or(a, Then(b, c)) if a == b => Then(a, Optional(c))
      // (a+)? => a*
      case Optional(OneOrMore(a)) => Star(a)
      // (a?)* => a*
      case Star(Optional(a)) => Star(a)
      // abb* => ab+
      case Then(Then(a, b), Star(c)) if b == c => Then(a, OneOrMore(b))
      // ab+|acb+ => ac?b+
      case Or(Then(a, OneOrMore(b)), Then(Then(c, d), OneOrMore(e)))
        //if a == c && b == e => Then(Then(c, Optional(d)), OneOrMore(e))
        if a == c && b == e => Then(c, Then(Optional(d), OneOrMore(e)))
      // qr
      case Then(Then(q, Then(r, a)), Star(b)) if a == b
        => Then(q, Then(r, OneOrMore(a)))
      // xaa*y => xa+y
      case Then(Then(x, a), Then(Star(b), y)) if a == b
        => Then(x, Then(OneOrMore(a), y))
      // ab | ac => a(b|c)
      case Or(Then(a, b), Then(Then(c, d), e)) if c == a
        => Then(a, Or(b, Then(d, e)))
      // ab|a => ab?
      case Or(Then(a, b), c) if a == c => Then(a, Optional(b))
      // [...]|x => [...x]
      case Or(RESet(xs), Literal(x)) => RESet(xs + x)
      case Or(Literal(x), RESet(xs)) => RESet(xs + x)
      /* No simplification */
      case r => modified = false; r
    }

    (ret, modified)
  }
}
