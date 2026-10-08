package noc

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.experimental.SourceInfo
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy.nodes._

/**
  * Small Diplomacy closed loop (NoC on hold).
  *
  * `MultiAdderNodeImp` defines a tiny "protocol": a single `UInt` wire whose
  * width is the negotiated edge parameter. In a real NoC this is where you would
  * define a CHI flit instead.
  *
  *   D = downward parameter (source -> sink)
  *   U = upward   parameter (sink -> source)
  *   E = negotiated edge parameter (what the wire actually is)
  *   B = the hardware Bundle deployed on each port
  */
object MultiAdderNodeImp extends SimpleNodeImp[Int, Int, Int, UInt] {
  // Negotiation rule: both sides must agree on the narrower width.
  override def edge(pd: Int, pu: Int, p: Parameters, sourceInfo: SourceInfo): Int = math.min(pd, pu)
  override def bundle(e: Int): UInt             = UInt(e.W)
  override def render(e: Int): RenderedEdge     = RenderedEdge(colour = "#000080", label = s"${e}b")
}

/** N inputs -> sum -> broadcast to M outputs. */
class MultiAdder(implicit p: Parameters) extends LazyModule {
  val node = new NexusNode(MultiAdderNodeImp)(
    dFn = { inWidths: Seq[Int]  => inWidths.min },
    uFn = { outWidths: Seq[Int] => outWidths.min }
  )

  lazy val module = new MultiAdderImp(this)
}

class MultiAdderImp(outer: MultiAdder) extends LazyModuleImp(outer) {
  // Widths are the edge parameters Diplomacy negotiated. This design assumes a
  // single common width; fail loudly rather than silently truncate otherwise.
  val inWidths = outer.node.in.map(_._2)
  require(inWidths.nonEmpty, "MultiAdder needs at least one connected input")
  require(inWidths.distinct.size == 1, s"MultiAdder inputs must share one width, got $inWidths")

  val width = inWidths.head
  val sum   = Wire(UInt(width.W))
  sum := outer.node.in.map(_._1).reduce(_ + _)
  outer.node.out.foreach { case (out, _) => out := sum }
}

/** Top-level system: turn the diplomatic graph into real IO ports. */
class MultiAdderTop(val inWidths: Seq[Int], val outWidths: Seq[Int])(implicit p: Parameters)
    extends LazyModule {
  val sources = new SourceNode(MultiAdderNodeImp)(inWidths)
  val sinks   = new SinkNode(MultiAdderNodeImp)(outWidths)
  val adder   = LazyModule(new MultiAdder)

  // `:*=` fan-out to M sinks, `:=*` fan-in from N sources.
  sinks :*= adder.node
  adder.node :=* sources

  lazy val module = new MultiAdderTopImp(this)
}

class MultiAdderTopImp(outer: MultiAdderTop) extends LazyModuleImp(outer) {
  val nIn  = outer.inWidths.length
  val nOut = outer.outWidths.length

  // Width comes from negotiation, not from the constructor arguments.
  val inWidths = outer.sources.out.map(_._2)
  require(inWidths.nonEmpty, "MultiAdderTop needs at least one connected source")
  require(inWidths.distinct.size == 1, s"MultiAdderTop source widths must match, got $inWidths")
  val w = inWidths.head

  val io = IO(new Bundle {
    val in  = Input(Vec(nIn, UInt(w.W)))
    val out = Output(Vec(nOut, UInt(w.W)))
  })

  outer.sources.out.zipWithIndex.foreach { case ((wire, _), i) => wire := io.in(i) }
  outer.sinks.in.zipWithIndex.foreach { case ((wire, _), i)   => io.out(i) := wire }
}

object MultiAdderMain extends App {
  implicit val p: Parameters = Parameters.empty
  val top                    = LazyModule(new MultiAdderTop(Seq(32, 32, 32), Seq(32, 32)))
  ChiselStage.emitSystemVerilogFile(
    top.module,
    args = Array("--target-dir", "generated"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
