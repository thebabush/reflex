package io.github.thebabush.reflex

class MakeRust {

  private val counter = new Counter
  private var code = Seq[String]()

  private def mkLiteral(ch: Char): String = {
    f"L${ch.asInstanceOf[Int]}%02X"
  }

  private def mkEscape(ch: Char): String = {
    f"\\u{${ch.asInstanceOf[Int]}%02X}"
  }

  def serialize(origRegexes: Seq[Regex]): String = {
    // Add a default "catch all" regex

    val regexes = origRegexes :+ RESet((0 until 255).map(_.asInstanceOf[Char]).toSet)
    code :+= PRELUDE

    val names = regexes.map(r => counter.mkMessage(r))

    regexes.zip(names).foreach(rn => {
      val (regex, name) = rn;
      serialize(name, regex);
    })

    val name = "BaseUnion"
    val length = regexes.length

    // BaseUnion declaration
    val sb = new StringBuilder
    sb ++= "#[derive(Clone, Debug, Serialize, Deserialize)]\n"
    sb ++= "pub enum BaseUnion {\n"
    names.foreach(child => {
      sb ++= s"    ${child}(${child}),\n"
    })
    sb ++= "}\n\n"

    // BaseUnion sampling
    sb ++= s"impl Distribution<${name}> for Standard {\n"
    sb ++= s"    fn sample<R: Rng + ?Sized>(&self, rng: &mut R) -> ${name} {\n"
    sb ++= s"        match rng.gen_range(0, ${length}) {\n"
    names.zipWithIndex.foreach(ci => {
      val (child, i) = ci
      sb ++= s"            ${i} => ${name}::${child}(rng.sample(Standard)),\n"
    })
    sb ++= s"            _ => unreachable!(),\n"
    sb ++= s"        }\n"
    sb ++= s"    }\n"
    sb ++= s"}\n\n"

    // BaseUnion impl Node
    sb ++= s"impl Node for ${name} {\n"
    sb ++= s"    fn pp(&self, s: &mut String) -> () {\n"
    sb ++= s"        match self {\n"
    names.foreach(child => {
      sb ++= s"            ${name}::${child}(vv) => vv.pp(s),\n"
    })
    sb ++= s"        }\n"
    sb ++= s"    }\n"
    sb ++= s"}\n"


    code :+= sb.toString

    serialize_star("ActualRoot", "BaseUnion", 0)

    code.mkString("\n")
  }

  private def serialize(name: String, regex: Regex): Unit = {
    regex match {
      case Empty() => throw new IllegalArgumentException("serialize => Empty()")
      case Epsilon() => throw new IllegalArgumentException("serialize => Epsilon()")
      case Literal(v) => serialize_literal(name, Seq() :+ v)
      case RESet(vv) => serialize_literal(name, vv.toSeq)
      case OneOrMore(child) => {
        val childName = counter.mkMessage(child)
        serialize(childName, child)
        serialize_star(name, childName, 1)
      }
      case Optional(child) => {
        val childName = counter.mkMessage(child)
        serialize(childName, child)
        serialize_optional(name, childName)
      }
      case Or(left, right) => {
        val children = Seq() :+ left :+ right
        val childrenNames = children.map(counter.mkMessage)
        children.zip(childrenNames).foreach(cn => {
          val (child, name) = cn
          serialize(name, child)
        })
        serialize_or(name, childrenNames)
      }
      case Star(child) => {
        val childName = counter.mkMessage(child)
        serialize(childName, child)
        serialize_star(name, childName, 0)
      }
      case Then(left, right) => {
        val children = Seq() :+ left :+ right
        val childrenNames = children.map(counter.mkMessage)
        children.zip(childrenNames).foreach(cn => {
          val (child, name) = cn
          serialize(name, child)
        })
        serialize_then(name, childrenNames)
      }
    }
    ()
  }

  private def serialize_then(name: String, childrenNames: Seq[String]): Unit = {
    val length = childrenNames.length

    // Then declaration
    val sb = new StringBuilder
    sb ++= s"#[derive(Clone, Debug, Serialize, Deserialize)]\n"
    sb ++= s"pub struct ${name} {\n"
    childrenNames.foreach(child => {
      sb ++= s"    ${child.toLowerCase}: ${child},\n"
    })
    sb ++= s"}\n\n"

    // Then sampling
    sb ++= s"impl Distribution<${name}> for Standard {\n"
    sb ++= s"    fn sample<R: Rng + ?Sized>(&self, rng: &mut R) -> ${name} {\n"
    sb ++= s"        ${name} {\n"
    childrenNames.foreach(child => {
      sb ++= s"            ${child.toLowerCase}: rng.sample(Standard),\n"
    })
    sb ++= s"        }\n"
    sb ++= s"    }\n"
    sb ++= s"}\n\n"

    // Then impl Node
    sb ++= s"impl Node for ${name} {\n"
    sb ++= s"    fn pp(&self, s: &mut String) -> () {\n"
    childrenNames.foreach(child => {
      sb ++= s"        self.${child.toLowerCase}.pp(s);\n"
    })
    sb ++= s"    }\n"
    sb ++= s"}\n"

    code :+= sb.toString
  }

  private def serialize_or(name: String, childrenNames: Seq[String]): Unit = {
    val length = childrenNames.length

    // Or declaration
    val sb = new StringBuilder
    sb ++= s"#[derive(Clone, Debug, Serialize, Deserialize)]\n"
    sb ++= s"pub enum ${name} {\n"
    childrenNames.foreach(child => {
      sb ++= s"    ${child}(${child}),\n"
    })
    sb ++= s"}\n\n"

    // Or sampling
    sb ++= s"impl Distribution<${name}> for Standard {\n"
    sb ++= s"    fn sample<R: Rng + ?Sized>(&self, rng: &mut R) -> ${name} {\n"
    sb ++= s"        match rng.gen_range(0, ${length}) {\n"
    childrenNames.zipWithIndex.foreach(ci => {
      val (child, i) = ci
      sb ++= s"            ${i} => ${name}::${child}(rng.sample(Standard)),\n"
    })
    sb ++= s"            _ => unreachable!(),\n"
    sb ++= s"        }\n"
    sb ++= s"    }\n"
    sb ++= s"}\n\n"

    // BaseUnion impl Node
    sb ++= s"impl Node for ${name} {\n"
    sb ++= s"    fn pp(&self, s: &mut String) -> () {\n"
    sb ++= s"        match self {\n"
    childrenNames.foreach(child => {
      sb ++= s"            ${name}::${child}(vv) => vv.pp(s),\n"
    })
    sb ++= s"        }\n"
    sb ++= s"    }\n"
    sb ++= s"}\n"


    code :+= sb.toString
  }

  private def serialize_literal(name: String, chars: Seq[Char]): Unit = {
    val childrenNames = chars.map(char => mkLiteral(char))
    val childrenNo = chars.length

    val sb = new StringBuilder
    sb ++= s"#[derive(Clone, Debug, Serialize, Deserialize)]\n"
    sb ++= s"pub enum ${name} {\n"
    childrenNames.foreach(childName => {
      sb ++= s"    ${childName},\n"
    })
    sb ++= s"}\n\n"

    // impl Sample
    sb ++= s"impl Distribution<${name}> for Standard {\n"
    sb ++= s"    fn sample<R: Rng + ?Sized>(&self, rng: &mut R) -> ${name} {\n"
    sb ++= s"        match rng.gen_range(0, ${childrenNo}) {\n"
    childrenNames.zipWithIndex.foreach(ci => {
      val (childName, i) = ci;
      sb ++= s"            ${i} => ${name}::${childName},\n"
    })
    sb ++= s"            _ => unreachable!(),\n"
    sb ++= s"        }\n"
    sb ++= s"    }\n"
    sb ++= s"}\n\n"

    // impl Node
    sb ++= s"impl Node for ${name} {\n"
    sb ++= s"    fn pp(&self, s: &mut String) -> () {\n"
    sb ++= s"        match self {\n"
    childrenNames.zip(chars).foreach(cc => {
      val (child, ch) = cc;
      sb ++= s"""            ${name}::${child} => s.push_str("${mkEscape(ch)}"),\n"""
    })
    sb ++= s"        }\n"
    sb ++= s"    }\n"
    sb ++= s"}\n"


    code :+= sb.toString
  }

  private def serialize_optional(name: String, childName: String): Unit = {
    val sb = new StringBuilder

    // type definition
    sb ++= s"type ${name} = Option<${childName}>;\n\n"

    // impl Sample
//    sb ++= s"impl Distribution<${name}> for Standard {"

    // impl Node
    sb ++=
s"""
impl Node for ${name} {
    fn pp(&self, s: &mut String) -> () {
        match self {
            None => (),
            Some(r) => r.pp(s),
        };
    }
}
"""

    code :+= sb.toString
  }

  private def serialize_star(name: String, child: String, min: Int): Unit = {
    val sb = new StringBuilder

    // struct definition
    sb ++= "#[derive(Clone, Debug, Serialize, Deserialize)]\n"
    sb ++= s"pub struct ${name} {\n"
    sb ++= s"    pub children: Vec<${child}>,"
    sb ++= s"}\n\n"

    // impl Sample
    sb ++= s"impl Distribution<${name}> for Standard {\n"
    sb ++= s"    fn sample<R: Rng + ?Sized>(&self, rng: &mut R) -> ${name} {\n"
    sb ++= s"        let children_no = rng.gen_range(${min}, 3);\n"
    sb ++= s"        let mut children = Vec::with_capacity(children_no as usize);\n"
    sb ++= s"        for _ in 0..children_no {\n"
    sb ++= s"            children.push(rng.sample(Standard));\n"
    sb ++= s"        };\n"
    sb ++= s"        ${name} { children: children }\n"
    sb ++= s"    }\n"
    sb ++= s"}\n\n"

    // impl Node
    sb ++= s"impl Node for ${name} {\n"
    sb ++= s"    fn pp(&self, s: &mut String) -> () {\n"
    sb ++= s"        for i in 0..self.children.len() {\n"
    sb ++= s"            self.children[i].pp(s);\n\n"
    sb ++= s"        }\n"
    sb ++= s"    }\n"
    sb ++= s"}\n\n"

    code :+= sb.toString
  }

  private val PRELUDE: String =
s"""
use serde_derive::{Deserialize, Serialize};
use rand::distributions::{Distribution, Standard};
use rand::Rng;

use crate::common::{Node};
"""
}
