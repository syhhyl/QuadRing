package chi

import chisel3._
import chisel3.simulator.Exceptions.AssertionFailed
import circt.stage.ChiselStage
import noc.RingParams
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class HnNodeSpec extends AnyFreeSpec with Matchers with ChiNodeTestSupport {
  private implicit val p: Parameters = Parameters.empty
  private val configs = Seq(
    (ChiNoCParams(), HnParams(), 0, 2),
    (ChiNoCParams(ChiParams(3, 7, 32), RingParams(4, 5)), HnParams(4, 1), 6, 0)
  )

  "HN parameters reject negative IDs and an SN target wider than the negotiated ID" in {
    an[IllegalArgumentException] should be thrownBy HnParams(-1, 3)
    an[IllegalArgumentException] should be thrownBy HnParams(1, -1)
    val error = intercept[IllegalArgumentException] {
      ChiselStage.emitCHIRRTL(LazyModule(new ChiNodeFixtures.Hn(ChiNoCParams(), HnParams(1, 4))).module)
    }
    error.getMessage should include("SN target")
  }

  for ((config, hn, rn0, rn1) <- configs) {
    s"HN ${hn.nodeId} retains the upstream identity across REQ and DAT backpressure" in {
      simulate(LazyModule(new ChiNodeFixtures.Hn(config, hn)).module) { dut =>
        val port = dut.io
        initialize(port)
        for ((src, txn, address, value) <- Seq(
          (rn0, 13, BigInt(128), lines(2)), (rn1, 13, BigInt(0), lines.head))) {
          port.reqTx.ready.poke(false)
          port.datTx.ready.poke(false)
          request(port.reqRx.bits, hn.nodeId, src, txn, address)
          port.reqRx.valid.poke(true)
          port.reqRx.ready.expect(true.B)
          dut.clock.step()
          port.reqRx.valid.poke(false)
          request(port.reqRx.bits, hn.nodeId, rn1, 3, 64)
          for (_ <- 0 until 6) {
            port.reqRx.ready.expect(false.B)
            port.reqTx.valid.expect(true.B)
            expectRequest(port.reqTx.bits, hn.snId, hn.nodeId, 0, address)
            port.datRx.ready.expect(false.B)
            dut.clock.step()
          }
          port.reqTx.ready.poke(true)
          dut.clock.step()
          port.reqTx.valid.expect(false.B)
          port.datRx.ready.expect(true.B)
          dut.clock.step(3)
          data(port.datRx.bits, hn.nodeId, hn.snId, 0, value)
          port.datRx.valid.poke(true)
          dut.clock.step()
          port.datRx.valid.poke(false)
          port.datRx.bits.data.poke(BigInt(0))
          for (_ <- 0 until 6) {
            port.reqRx.ready.expect(false.B)
            port.datTx.valid.expect(true.B)
            expectData(port.datTx.bits, src, hn.nodeId, txn, value)
            port.rspTx.valid.expect(false.B)
            port.snpTx.valid.expect(false.B)
            dut.clock.step()
          }
          port.datTx.ready.poke(true)
          dut.clock.step()
          // Non-coherent read releases at DAT acceptance, without waiting for CompAck.
          port.reqRx.ready.expect(true.B)
          port.datTx.valid.expect(false.B)
          dut.clock.step(3)
          port.datTx.valid.expect(false.B)
        }
      }
    }
  }

  for (wrongField <- Seq("srcID", "txnID", "opcode")) {
    s"SN data with mismatched $wrongField cannot advance the HN transaction" in {
      val error = intercept[AssertionFailed] {
        simulate(LazyModule(new ChiNodeFixtures.Hn(ChiNoCParams(), HnParams())).module) { dut =>
          initialize(dut.io)
          request(dut.io.reqRx.bits, 1, 0, 13, 64)
          dut.io.reqRx.valid.poke(true)
          dut.clock.step()
          dut.io.reqRx.valid.poke(false)
          dut.clock.step()
          data(dut.io.datRx.bits, 1, 3, 0, lines(1))
          dut.io.datRx.bits.elements(wrongField).asInstanceOf[UInt].poke(BigInt(2))
          dut.io.datRx.valid.poke(true)
          dut.clock.step()
        }
      }
      error.getMessage should include(if (wrongField == "opcode") "CompData" else "another transaction")
    }
  }

  "an unsupported REQ is rejected rather than forwarded as a read" in {
    val error = intercept[AssertionFailed] {
      simulate(LazyModule(new ChiNodeFixtures.Hn(ChiNoCParams(), HnParams())).module) { dut =>
        initialize(dut.io)
        request(dut.io.reqRx.bits, 1, 0, 3, 0)
        dut.io.reqRx.bits.opcode.poke(ChiOpcode.Req.WriteNoSnpFull)
        dut.io.reqRx.valid.poke(true)
        dut.clock.step()
      }
    }
    error.getMessage should include("only ReadNoSnp")
  }
}
