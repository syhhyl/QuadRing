package adder

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class AdderSpec extends AnyFreeSpec with Matchers with ChiselSim {
  implicit val p: Parameters = Parameters.empty

  "Adder should sum its two inputs at the negotiated width" in {
    simulate(LazyModule(new AdderTop).module) { dut =>
      // 64-bit sources narrowed by the 32-bit sink -> the sum wraps at 32 bits.
      val cases = Seq((0L, 0L), (1L, 2L), (0xffffffffL, 0L), (0xffffffffL, 1L))

      for ((a, b) <- cases) {
        dut.io.a.poke(a.U)
        dut.io.b.poke(b.U)
        dut.clock.step()
        dut.io.sum.expect(((a + b) & 0xffffffffL).U)
      }
    }
  }
}
