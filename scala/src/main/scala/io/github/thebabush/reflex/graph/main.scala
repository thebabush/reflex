package io.github.thebabush.reflex.graph

import gremlin.scala._
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph

import scala.collection.mutable

/**
 * WARNING: shitty code ahead.
 * I don't know scala very well, nor gremlin... and scala-gremlin was probably a mistake.
 * Also, I don't have much time left and I have to make tons of beautiful slides (:
 */

object main {

  final val PCODE_INT_EQS: Seq[String] = Seq("INT_EQUAL", "INT_NOTEQUAL")
  final val PCODE_INT_GTS_LTS: Seq[String]
  = Seq("INT_SLESS", "INT_LESS")
  final val PTR_OPS: Seq[String] = Seq("PTRADD", "PTRSUB")

  final val CouldBeTable = Key[Boolean]("could_be_table")
  final val IsInput = Key[Boolean]("is_input")
  final val OpMnemonic = Key[String]("op_mnemonic")
  final val ConstValue = Key[Long]("const_value")
  final val FileOffset = Key[Long]("file_offset")
  final val OpSize = Key[Long]("op_size")
  final val Id = Key[Long]("id")

  private class Results {
    var tables: mutable.Map[String, List[(Long, Long)]] = mutable.Map[String, List[(Long, Long)]]().withDefaultValue(List.empty)
    var maxState: List[Long] = List.empty

    override def toString: String = {
      Seq(
        maxState.map(ms => "--max-state 0x" + String.format("%08X", ms)).mkString("\n"),
        tables.map({
          case (k, xs) => {
              xs.map({
                case (addr, size) => {
                  k.replaceFirst("yy_", "--") + String.format(" 0x%08X", addr) + " " + size
                }
              }).mkString("\n")
          }
        }).toSeq.sorted.mkString("\n")
      ).mkString("\n")
    }
  }

  private def findTables(g: TraversalSource): Results = {
    val ret = new Results

    // Φ(state) => int operations => yy_base[_] => sum with `class` variable
    // => yy_chk[_] =>  => comparison with state => same

    val YyBase = StepLabel[Vertex]("yy_base")
    val YyBaseLoad = StepLabel[Vertex]("yy_base_load")
    val ClassPhi = StepLabel[Vertex]("class_phi")
    val OtherTable = StepLabel[Vertex]("other_table")
    val OtherTableLoad = StepLabel[Vertex]("other_table_load")

    val Tmp0 = StepLabel[Vertex]("tmp_find_yy_base")
    val Tmp1 = StepLabel[Vertex]("tmp_find_class_var")
    val Tmp2 = StepLabel[Vertex]("tmp_find_yy_table")

    val StatePhi = StepLabel[Vertex]("state_phi")

    // match `class + yy_base[state]`
    val classPlusYyBase = g.V
      .repeat(_.both.has(OpMnemonic, "MULTIEQUAL").dedup).emit() // Group together all multiequals
      .as(StatePhi)
      .optional(_.out.has(OpMnemonic, "CAST"))
      .repeat(
        _.out.has(OpMnemonic, P.within(Seq("INT_SEXT", "INT_MULT", "SUBPIECE")))
      )
      .emit()
      .out.has(OpMnemonic, P.within(PTR_OPS))
      .as(Tmp0)
      // The other operand should be yy_base... Find it
      .repeat(_.in.has(OpMnemonic, P.within(PTR_OPS))).emit()
      // Tag yy_base
      .in.has(CouldBeTable, true).as(YyBase)
      // Go back to where we were before
      .select(Tmp0)
      .optional(_.out.has(OpMnemonic, "CAST"))
      // Tag the load so we will be able to get its size
      .out.has(OpMnemonic, "LOAD").as(YyBaseLoad)
      // Skip integer operations
      .repeat(_.out.has(OpMnemonic, P.within(Seq("INT_SEXT", "INT_ADD")))).emit()
      .as(Tmp1)
      // Go look for a phi(class)
      .optional(_.in.has(OpMnemonic, P.within(Seq("INT_ZEXT"))))
      .in.has(OpMnemonic, "MULTIEQUAL")
      .as(ClassPhi)
      // Look for the first ptr (referring to either yy_chk or yy_nxt)
      .select(Tmp1)
      .optional(_.out.has(OpMnemonic, "CAST"))
      // (AND used as a way to trunc values... blame ghidra)
      .repeat(_.out.has(OpMnemonic, P.within(Seq("INT_AND", "INT_SEXT", "INT_MULT", "INT_ZEXT")))).emit()
      .out.has(OpMnemonic, P.within(PTR_OPS))
      .as(Tmp2)
      // Look for the other table
      .repeat(_.in.has(OpMnemonic, P.within(PTR_OPS))).emit()
      .in.has(CouldBeTable, true).as(OtherTable)
      // Rewind
      .select(Tmp2)
      .optional(_.out.has(OpMnemonic, "CAST"))
      .out.has(OpMnemonic, "LOAD").as(OtherTableLoad)

//    println(classPlusYyBase.clone.path.toIterator.mkString("\n"))

    // Find yy_chk: state != classPlusYyBase
    val TmpCmp = StepLabel[Vertex]("tmp_cmp")
    val stateNeqYyChk = classPlusYyBase.clone()
      .out.has(OpMnemonic, "INT_SEXT")
      .out.has(OpMnemonic, P.within(PCODE_INT_EQS)).as(TmpCmp)
      .optional(_.in.has(OpMnemonic, "SUBPIECE"))
      .in.has(OpMnemonic, "MULTIEQUAL")
      // Check the last node is the same from which we started from
      .where(P.eq(StatePhi.name): P[String])

    val queryDefMaxState = stateNeqYyChk.clone()
    val queryYyAccept = stateNeqYyChk.clone()

//    println(stateNeqYyChk.clone.path.toIterator.mkString("\n"))

    // Extract the values
    stateNeqYyChk.select((YyBase, YyBaseLoad, OtherTable, OtherTableLoad))
      .toIterator.foreach({
      case (base, baseLoad, otherTable, otherTableLoad) => {
        val yyBase = base.asScala.property(FileOffset).value()
        val yyBaseElementSize = baseLoad.asScala.property(OpSize).value()
        val yyChk = otherTable.asScala.property(FileOffset).value()
        val yyChkElementSize = otherTableLoad.asScala.property(OpSize).value()
        ret.tables("yy_base") :+= (yyBase, yyBaseElementSize)
        ret.tables("yy_chk") :+= (yyChk, yyChkElementSize)
      }
    })

    val TmpPtr = StepLabel[Vertex]("tmp_ptr")

    val queryMaxState2 = classPlusYyBase.clone()
      .repeat(_.out.has(OpMnemonic, P.within(Seq("INT_SEXT", "CAST", "INT_MULT")))).emit()
      .out.has(OpMnemonic, P.within(PTR_OPS)).as(TmpPtr)
      // Find yy_base again
      .repeat(_.in.has(OpMnemonic, P.within(PTR_OPS))).emit()
      .in.where(P.eq(YyBase.name): P[String]).by(ConstValue.name)
      // Reach the comparison with max_state
      .select(TmpPtr)
      .repeat(_.out.has(OpMnemonic, P.within(PTR_OPS ++ Seq("CAST", "LOAD")))).emit()
      .out.has(OpMnemonic, P.within(PCODE_INT_EQS)).as(TmpCmp)
      .out.has(OpMnemonic, "CBRANCH")
      .select(TmpCmp)
      .in.has(ConstValue)
//    println(queryMaxState2.clone.path.toIterator.mkString("\n"))
    queryMaxState2.select((OtherTable, OtherTableLoad)).toIterator().foreach({
      case (other, otherLoad) => {
        val yyNxt = other.asScala.property(FileOffset).value
        val yyNxtElementSize = otherLoad.asScala.property(OpSize).value
        ret.tables("yy_nxt") :+= (yyNxt, yyNxtElementSize)
      }
    })

    val TmpPtrMeta = StepLabel[Vertex]("tmp_yy_meta")
    val YyMeta = StepLabel[Vertex]("yy_meta")
    val YyMetaLoad = StepLabel[Vertex]("yy_meta_load")
    val YyEc = StepLabel[Vertex]("yy_ec")
    val YyEcLoad = StepLabel[Vertex]("yy_ec_load")
    val queryEcMetaMaxState = classPlusYyBase.clone
      .select(ClassPhi)
      // Goddamn ghidra... Sometimes PTR_*, sometimes integer operations T.T
      .repeat(_.out.has(OpMnemonic, P.within(Seq("INT_ZEXT", "CAST", "INT_SEXT", "INT_MULT")))).emit()
      // Go back and find table (hack for integers used as pointers)
      .out.has(OpMnemonic, P.within(PTR_OPS ++ Seq("INT_ADD", "CAST"))).as(TmpPtrMeta)
      .repeat(_.in.has(OpMnemonic, P.within(PTR_OPS ++ Seq("CAST", "INT_ADD")))).emit()
      .in.has(CouldBeTable, true).as(YyMeta)
      // Go back to ptr
      .select(TmpPtrMeta)
        .optional(_.out.has(OpMnemonic, "CAST"))
      .out.has(OpMnemonic, "LOAD").as(YyMetaLoad)
      // Go back to phi node
      .out.where(P.eq(ClassPhi.name): P[String])
      // Find yy_ec
      .in.has(OpMnemonic, "LOAD").as(YyEcLoad)
      .optional(_.in.has(OpMnemonic, "CAST"))
      // Integer arithmetic hack
      .repeat(_.in.has(OpMnemonic, P.within(PTR_OPS ++ Seq("INT_ADD", "CAST")))).emit()
      .in.has(CouldBeTable, true).as(YyEc)
      // Disambiguate yy_ec from yy_meta
      .select(YyEcLoad)
      .optional(_.out.has(OpMnemonic, "MULTILABEL")) // HACK
      .repeat(_.in.has(OpMnemonic, P.within(PTR_OPS))).emit()
      .repeat(_.in.has(OpMnemonic, P.within(Seq("INT_SEXT", "INT_ZEXT", "INT_MULT", "CAST")))).emit()
      .in.has(OpMnemonic, "LOAD")
    //println(queryEcMetaMaxState.clone.path.toIterator.mkString("\n"))
    val queryEc = queryEcMetaMaxState.clone
    queryEc.select((YyEc, YyEcLoad)).dedup.toIterator().foreach({
      case (yyEc, yyEcLoad) => {
        ret.tables("yy_ec") :+= (
          yyEc.asScala.property(FileOffset).value,
          yyEcLoad.asScala.property(OpSize).value
        )
      }
    })
    val queryMeta = queryEcMetaMaxState.clone
    queryMeta.select((YyMeta, YyMetaLoad)).dedup.toIterator().foreach({
      case (yyMeta, yyMetaLoadSize) => {
        ret.tables("yy_meta") :+= (
          yyMeta.asScala.property(FileOffset).value,
          yyMetaLoadSize.asScala.property(OpSize).value
        )
      }
    })

    // state = yy_def[state]
    // if (const < state)
    val YyDefPtrTmp = StepLabel[Vertex]("yy_def_ptr_tmp")
    val YyDef = StepLabel[Vertex]("yy_def")
    val YyDefLoad = StepLabel[Vertex]("yy_def_load")
    val MaxState = StepLabel[Vertex]("max_state")
    val MaxStateCond = StepLabel[Vertex]("max_state_cond")
    val YyDefBackEdge = StepLabel[Vertex]("yy_def_back_edge")
    val doQueryDefMaxState = queryDefMaxState
      .select(StatePhi)
      .repeat(_.out.has(OpMnemonic, P.within(Seq("CAST", "INT_SEXT", "INT_MULT", "INT_ZEXT")))).emit()
      .out.has(OpMnemonic, P.within(PTR_OPS)).as(YyDefPtrTmp)
      // Find yy_def
      .repeat(_.in.has(OpMnemonic, P.within(PTR_OPS))).emit()
      .optional(_.in.has(OpMnemonic, "CAST"))
      .in.has(CouldBeTable, true).as(YyDef)
      // Go back to ptr
      .select(YyDefPtrTmp)
      .optional(_.out.has(OpMnemonic, "CAST"))
      .out.has(OpMnemonic, "LOAD").as(YyDefLoad)
      // Skip extensions
      .repeat(_.out.has(OpMnemonic, P.within(Seq("INT_SEXT", "INT_ZEXT")))).emit().as(YyDefBackEdge)
      // Check back edge
      .out.where(P.eq(StatePhi.name): P[String])
      .select(YyDefBackEdge)
      // Look for comparison + branch
      .optional(_.out.has(OpMnemonic, "CAST"))
      .out.has(OpMnemonic, P.within(PCODE_INT_GTS_LTS)).as(MaxStateCond)
      // Find max_state
      .in.has(ConstValue).as(MaxState)
      // Find cbranch
      .select(MaxStateCond)
      .out.has(OpMnemonic, "CBRANCH")

    doQueryDefMaxState.select((YyDef, YyDefLoad, MaxState)).toIterator().foreach({
      case (yyDef, yyDefLoad, maxState) => {
        ret.tables("yy_def") :+= (
          yyDef.asScala.property(FileOffset).value,
          yyDefLoad.asScala.property(OpSize).value,
        )
        ret.maxState :+= maxState.asScala.property(ConstValue).value
      }
    })

    val YyAcceptPtr = StepLabel[Vertex]("yy_accept_ptr")
    val YyAccept = StepLabel[Vertex]("yy_accept")
    val YyAcceptLoad = StepLabel[Vertex]("yy_accept_load")
    val YyAcceptCmp = StepLabel[Vertex]("yy_accept_cmp")
    val doQueryYyAccept = queryYyAccept
      .select(StatePhi)
      .repeat(_.both.has(OpMnemonic, "MULTIEQUAL").dedup).emit() // Group together all multiequals
      .optional(_.out.has(OpMnemonic, "CAST"))
      .repeat(_.out.has(OpMnemonic, P.within(Seq("INT_SEXT", "INT_MULT")))).emit()
      .out.has(OpMnemonic, P.within(PTR_OPS)).as(YyAcceptPtr)
      // Look for yy_accept
      .repeat(_.in.has(OpMnemonic, P.within(PTR_OPS))).emit()
      .in.has(CouldBeTable, true).as(YyAccept)
      // Look for cbranch 0
      .select(YyAcceptPtr)
      .optional(_.out.has(OpMnemonic, "CAST"))
      .out.has(OpMnemonic, "LOAD").as(YyAcceptLoad)
      .out.has(OpMnemonic, P.within(PCODE_INT_EQS)).as(YyAcceptCmp)
      .in.has(ConstValue, 0L)
      .select(YyAcceptCmp)
      .out.has(OpMnemonic, "CBRANCH")
    doQueryYyAccept.select((YyAccept, YyAcceptLoad)).dedup.toIterator().foreach({
      case (yyAccept, yyAcceptLoad) => {
        ret.tables("yy_accept") :+= (
          yyAccept.asScala.property(FileOffset).value, yyAcceptLoad.asScala.property(OpSize).value
        )
      }
    })

    ret
  }

  def main(args: Array[String]): Unit = {
    if (args.length != 1) {
      println("Usage: find-tables /path/to/graphml.xml")
      System.exit(1);
    }

    val graphmlPath = args(0);

    val jgraph = TinkerGraph.open()
    jgraph.traversal().io(graphmlPath).read().iterate()

    val graph = jgraph.asScala
    val g = graph.traversal

    val results = findTables(g)
    println(results)
  }
}
