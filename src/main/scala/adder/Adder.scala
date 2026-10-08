package adder

import chisel3._
import _root_.circt.stage.ChiselStage

/** Parameterized combinational adder: `sum` is the low `width` bits of `a + b`. */
class Adder(width: Int = 8) extends Module {
  val io = IO(new Bundle {
    val a   = Input(UInt(width.W))
    val b   = Input(UInt(width.W))
    val sum = Output(UInt(width.W))
  })

  // `+` truncates to the wider operand; use `+&` to keep the carry-out.
  io.sum := io.a + io.b
}

/** Emits `Adder.sv` and `filelist.f` into `generated/`. */
object Adder extends App {
  ChiselStage.emitSystemVerilogFile(
    new Adder(8),
    // `--target-dir` is a Chisel-stage option, so it goes in `args`, not `firtoolOpts`.
    args = Array("--target-dir", "generated"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
  )
}
