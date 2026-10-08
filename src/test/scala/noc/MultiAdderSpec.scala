package noc

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class MultiAdderSpec extends AnyFreeSpec with Matchers with ChiselSim {
  implicit val p: Parameters = Parameters.empty

  "MultiAdder should sum all inputs and broadcast the result to all outputs" in {
    val top = LazyModule(new MultiAdderTop(Seq(32, 32, 32), Seq(32, 32)))
    simulate(top.module) { dut =>
      val cases = Seq(Seq(1, 2, 3), Seq(10, 20, 30), Seq(0, 0, 0), Seq(100, 200, 300))

      for (ins <- cases) {
        ins.zipWithIndex.foreach { case (v, i) => dut.io.in(i).poke(v.U) }
        dut.clock.step()
        val expected = ins.sum
        for (i <- 0 until 2) dut.io.out(i).expect(expected.U)
      }
    }
  }
}
