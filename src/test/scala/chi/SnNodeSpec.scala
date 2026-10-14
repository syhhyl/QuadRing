package chi

import chisel3._
import chisel3.simulator.Exceptions.AssertionFailed
import circt.stage.ChiselStage
import noc.RingParams
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class SnNodeSpec extends AnyFreeSpec with Matchers with ChiNodeTestSupport {
  private implicit val p: Parameters = Parameters.empty

  "invalid memory initialization is rejected before graph construction" in {
    an[IllegalArgumentException] should be thrownBy SnParams(-1)
    an[IllegalArgumentException] should be thrownBy SnParams(initialLines = Seq.empty)
    an[IllegalArgumentException] should be thrownBy SnParams(initialLines = Seq(BigInt(-1)))
    an[IllegalArgumentException] should be thrownBy SnParams(initialLines = Seq(BigInt(1) << 512))
  }

  "the memory must fit the negotiated address space" in {
    val error = intercept[IllegalArgumentException] {
      ChiselStage.emitCHIRRTL(LazyModule(new ChiNodeFixtures.Sn(
        ChiNoCParams(ChiParams(1, 1, 6), RingParams(2)), SnParams(1, lines))).module)
    }
    error.getMessage should include("address width")
  }

  private val configs = Seq(
    (ChiNoCParams(), SnParams(3, lines), 1),
    (ChiNoCParams(ChiParams(3, 7, 32), RingParams(4, 5)), SnParams(6, lines), 4),
    (ChiNoCParams(ChiParams(1, 1, 6), RingParams(2)), SnParams(1, Seq(lines.head)), 0)
  )
  for ((config, sn, hn) <- configs) {
    s"SN ${sn.nodeId} reads every line and holds blocked data with parameters ${config.chi}" in {
      simulate(LazyModule(new ChiNodeFixtures.Sn(config, sn)).module) { dut =>
        initialize(dut.io)
        for ((value, index) <- sn.initialLines.zipWithIndex) {
          val txn = (index + 1) & ((1 << config.chi.txnIdWidth) - 1)
          dut.io.datTx.ready.poke(false)
          request(dut.io.reqRx.bits, sn.nodeId, hn, txn, BigInt(index) << 6)
          dut.io.reqRx.valid.poke(true)
          dut.io.reqRx.ready.expect(true.B)
          dut.clock.step()
          dut.io.reqRx.valid.poke(false)
          request(dut.io.reqRx.bits, sn.nodeId, 0, 0, 0)
          dut.io.datTx.valid.expect(false.B)
          dut.clock.step()
          for (_ <- 0 until 6) {
            dut.io.reqRx.ready.expect(false.B)
            dut.io.datTx.valid.expect(true.B)
            expectData(dut.io.datTx.bits, hn, sn.nodeId, txn, value)
            dut.io.reqTx.valid.expect(false.B)
            dut.io.rspTx.valid.expect(false.B)
            dut.io.snpTx.valid.expect(false.B)
            dut.clock.step()
          }
          dut.io.datTx.ready.poke(true)
          dut.clock.step()
          for (_ <- 0 until 3) {
            dut.io.reqRx.ready.expect(true.B)
            dut.io.datTx.valid.expect(false.B)
            dut.clock.step()
          }
        }
      }
    }
  }

  for ((address, message) <- Seq(BigInt(1) -> "line-aligned", BigInt(192) -> "outside memory",
    (BigInt(1) << 47) -> "outside memory")) {
    s"SN rejects invalid address $address without aliasing another line" in {
      val error = intercept[AssertionFailed] {
        simulate(LazyModule(new ChiNodeFixtures.Sn(ChiNoCParams(), SnParams(3, lines))).module) { dut =>
          initialize(dut.io)
          request(dut.io.reqRx.bits, 3, 1, 13, address)
          dut.io.reqRx.valid.poke(true)
          dut.clock.step()
        }
      }
      error.getMessage should include(message)
    }
  }

  "an unsupported memory write is rejected rather than executed as a read" in {
    val error = intercept[AssertionFailed] {
      simulate(LazyModule(new ChiNodeFixtures.Sn(ChiNoCParams(), SnParams(3, lines))).module) { dut =>
        initialize(dut.io)
        request(dut.io.reqRx.bits, 3, 1, 13, 0)
        dut.io.reqRx.bits.opcode.poke(ChiOpcode.Req.WriteNoSnpFull)
        dut.io.reqRx.valid.poke(true)
        dut.clock.step()
      }
    }
    error.getMessage should include("only ReadNoSnp")
  }

  for (readCycles <- Seq(0, 1)) {
    s"reset cancels the SN read after $readCycles local read cycles" in {
      simulate(LazyModule(new ChiNodeFixtures.Sn(ChiNoCParams(), SnParams(3, lines))).module) { dut =>
        initialize(dut.io)
        dut.io.datTx.ready.poke(false)
        request(dut.io.reqRx.bits, 3, 1, 13, 64)
        dut.io.reqRx.valid.poke(true)
        dut.clock.step()
        dut.io.reqRx.valid.poke(false)
        if (readCycles > 0) dut.clock.step(readCycles)
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        dut.io.reqRx.ready.expect(true.B)
        dut.io.datTx.valid.expect(false.B)
        dut.clock.step(3)
        dut.io.datTx.valid.expect(false.B)
        request(dut.io.reqRx.bits, 3, 1, 2, 128)
        dut.io.reqRx.valid.poke(true)
        dut.clock.step()
        dut.io.reqRx.valid.poke(false)
        dut.clock.step()
        dut.io.datTx.valid.expect(true.B)
        expectData(dut.io.datTx.bits, 1, 3, 2, lines(2))
        dut.io.datTx.ready.poke(true)
        dut.clock.step()
        dut.io.reqRx.ready.expect(true.B)
      }
    }
  }
}
