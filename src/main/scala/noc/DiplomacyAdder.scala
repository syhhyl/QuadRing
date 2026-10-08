package noc

import chisel3._
import _root_.circt.stage.ChiselStage
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy.nodes._

/**
  * Minimal smoke test that Diplomacy is wired in: the plain `adder.Adder`
  * wrapped as a `LazyModule`. It is not part of the NoC -- delete it once you
  * start writing real designs under `src/main/scala/noc/`.
  *
  * Run: `./mill QuadRing.runMain noc.DiplomacyAdder`
  */
class DiplomacyAdder(val width: Int = 32)(implicit p: Parameters) extends LazyModule {
  // Hardware is not created in the constructor: Diplomacy negotiates first and
  // only then evaluates this `lazy val` (two-phase elaboration).
  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle {
      val a   = Input(UInt(width.W))
      val b   = Input(UInt(width.W))
      val sum = Output(UInt(width.W))
    })
    io.sum := io.a + io.b
  }
}

object DiplomacyAdder extends App {
  implicit val p: Parameters = Parameters.empty
  val top                    = LazyModule(new DiplomacyAdder(32))
  ChiselStage.emitSystemVerilogFile(
    top.module,
    args = Array("--target-dir", "generated"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
