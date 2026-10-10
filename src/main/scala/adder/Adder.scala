package adder

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.experimental.SourceInfo
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy.nodes._

object AdderNodeImp extends SimpleNodeImp[Int, Int, Int, UInt] {
  def edge(offered: Int, wanted: Int, p: Parameters, sourceInfo: SourceInfo): Int = {
    math.min(offered, wanted)
  }

  def bundle(width: Int): UInt = {
    UInt(width.W)
  }

  def render(width: Int): RenderedEdge = {
    RenderedEdge(colour = "blue", label = width.toString)
  }
}

class Adder(implicit p: Parameters) extends LazyModule {
  def forwardOffered(offered: Seq[Int]): Int = {
    offered(0)
  }

  def forwardWanted(wanted: Seq[Int]): Int = {
    wanted(0)
  }

  val node = new NexusNode(AdderNodeImp)(forwardOffered, forwardWanted)

  lazy val module = new AdderImp(this)
}

class AdderImp(outer: Adder) extends LazyModuleImp(outer) {
  val left   = outer.node.in(0)._1
  val right  = outer.node.in(1)._1
  val sum    = left + right
  val output = outer.node.out(0)._1
  output := sum
}

class AdderTop(implicit p: Parameters) extends LazyModule {
  val adder = LazyModule(new Adder)
  val inputA = new SourceNode(AdderNodeImp)(Seq(64))
  val inputB = new SourceNode(AdderNodeImp)(Seq(64))
  val output = new SinkNode(AdderNodeImp)(Seq(32))

  adder.node := inputA
  adder.node := inputB
  output := adder.node

  lazy val module = new AdderTopImp(this)
}

class AdderTopImp(outer: AdderTop) extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val a   = Input(UInt(64.W))
    val b   = Input(UInt(64.W))
    val sum = Output(UInt(32.W))
  })

  outer.inputA.out(0)._1 := io.a
  outer.inputB.out(0)._1 := io.b
  io.sum := outer.output.in(0)._1
}

object AdderMain extends App {
  implicit val p: Parameters = Parameters.empty
  val top = LazyModule(new AdderTop)
  ChiselStage.emitSystemVerilogFile(top.module, args = Array("--target-dir", "generated"))

  // Export the Diplomacy graph; open it in yEd / Gephi. `graphML` walks the
  // whole LazyModule tree, so read it after elaboration (i.e. after emit).
  java.nio.file.Files.writeString(
    java.nio.file.Path.of("generated/AdderTop.graphml"),
    top.graphML
  )
}
