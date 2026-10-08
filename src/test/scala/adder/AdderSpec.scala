package adder

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class AdderSpec extends AnyFreeSpec with Matchers with ChiselSim {
  "Adder should sum its two inputs" in {
    simulate(new Adder(8)) { dut =>
      // Includes overflow cases; `& 0xff` models the 8-bit truncation.
      val cases = Seq((0, 0), (1, 1), (2, 3), (255, 0), (255, 1), (255, 255))

      for ((a, b) <- cases) {
        dut.io.a.poke(a.U)
        dut.io.b.poke(b.U)
        dut.clock.step()
        dut.io.sum.expect(((a + b) & 0xff).U)
      }
    }
  }
}
