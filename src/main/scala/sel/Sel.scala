package sel

import chisel3._
import _root_.circt.stage.ChiselStage

/** 2-to-1 multiplexer: `res` is `a` when `select` is high, otherwise `b`. */
class Sel(width: Int = 8) extends Module {
  val io = IO(new Bundle {
    val a      = Input(UInt(width.W))
    val b      = Input(UInt(width.W))
    val select = Input(Bool())
    val res    = Output(UInt(width.W))
  })

  io.res := Mux(io.select, io.a, io.b)
}

/** Emits `Sel.sv` and `filelist.f` into `generated/`. */
object Sel extends App {
  ChiselStage.emitSystemVerilogFile(
    new Sel(8),
    args = Array("--target-dir", "generated"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
  )
}
