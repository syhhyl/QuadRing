package sel

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class SelSpec extends AnyFreeSpec with Matchers with ChiselSim {
  "Sel should output a when select is high, otherwise b" in {
    simulate(new Sel(8)) { dut =>
      val cases = Seq(
        (0x12, 0x34, true),
        (0x12, 0x34, false),
        (0x00, 0xff, true),
        (0x00, 0xff, false),
        (0xff, 0x00, true),
        (0xff, 0x00, false)
      )

      for ((a, b, select) <- cases) {
        dut.io.a.poke(a.U)
        dut.io.b.poke(b.U)
        dut.io.select.poke(select.B)
        dut.clock.step()
        dut.io.res.expect((if (select) a else b).U)
      }
    }
  }
}
