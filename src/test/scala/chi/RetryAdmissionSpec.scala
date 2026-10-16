package chi

import chisel3._
import noc.RingParams
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** T2 checks that a busy completion side consumes retryable requests and later grants them fairly. */
class RetryAdmissionSpec extends AnyFreeSpec with Matchers with ChiNodeTestSupport {
  private implicit val p: Parameters = Parameters.empty

  "SN remembers a busy read, grants it after the active read, and accepts only the credited retry" in {
    val config = ChiNoCParams(ChiParams(3, 4, 32), RingParams(4, 3))
    val sn = SnParams(3, lines)
    simulate(LazyModule(new ChiNodeFixtures.Sn(config, sn)).module) { dut =>
      val port = dut.io
      initialize(port)
      port.datTx.ready.poke(false)
      request(port.reqRx.bits, 3, 1, 5, 0)
      port.reqRx.valid.poke(true)
      port.reqRx.ready.expect(true.B)
      dut.clock.step()
      port.reqRx.valid.poke(false)
      dut.clock.step() // readLine -> sendData

      port.rspTx.ready.poke(false)
      request(port.reqRx.bits, 3, 2, 6, 64)
      port.reqRx.valid.poke(true)
      port.reqRx.ready.expect(true.B)
      dut.clock.step()
      port.reqRx.valid.poke(false)
      port.rspTx.valid.expect(true.B)
      expectResponse(port.rspTx.bits, 2, 3, 6, ChiOpcode.Rsp.RetryAck)
      dut.clock.step(3)
      port.rspTx.ready.poke(true)
      dut.clock.step()

      port.datTx.ready.poke(true)
      dut.clock.step()
      port.rspTx.ready.poke(false)
      port.rspTx.valid.expect(true.B)
      expectResponse(port.rspTx.bits, 2, 3, 0, ChiOpcode.Rsp.PCrdGrant)
      dut.clock.step(2)
      port.rspTx.ready.poke(true)
      dut.clock.step()

      request(port.reqRx.bits, 3, 2, 6, 64)
      port.reqRx.bits.allowRetry.poke(false)
      port.reqRx.valid.poke(true)
      port.reqRx.ready.expect(true.B)
      dut.clock.step()
      port.reqRx.valid.poke(false)
      dut.clock.step()
      port.datTx.valid.expect(true.B)
      expectData(port.datTx.bits, 2, 3, 6, lines(1))
      dut.clock.step()
    }
  }

  "HN grants two queued requesters in round-robin order and keeps their identities" in {
    val config = ChiNoCParams(ChiParams(3, 4, 32), RingParams(4, 3))
    val hn = HnParams(nodeId = 1, snId = 3, retrySlots = 2)
    simulate(LazyModule(new ChiNodeFixtures.Hn(config, hn)).module) { dut =>
      val port = dut.io
      initialize(port)
      port.reqTx.ready.poke(false)
      request(port.reqRx.bits, 1, 0, 1, 0)
      port.reqRx.valid.poke(true)
      dut.clock.step()
      port.reqRx.valid.poke(false)

      port.rspTx.ready.poke(false)
      request(port.reqRx.bits, 1, 2, 2, 64)
      port.reqRx.valid.poke(true)
      port.reqRx.ready.expect(true.B)
      dut.clock.step()
      port.reqRx.valid.poke(false)
      port.rspTx.valid.expect(true.B)
      expectResponse(port.rspTx.bits, 2, 1, 2, ChiOpcode.Rsp.RetryAck)
      port.rspTx.ready.poke(true)
      dut.clock.step()

      request(port.reqRx.bits, 1, 4, 3, 128)
      port.reqRx.valid.poke(true)
      port.reqRx.ready.expect(true.B)
      port.rspTx.ready.poke(false)
      dut.clock.step()
      port.reqRx.valid.poke(false)
      port.rspTx.valid.expect(true.B)
      expectResponse(port.rspTx.bits, 4, 1, 3, ChiOpcode.Rsp.RetryAck)
      port.rspTx.ready.poke(true)
      dut.clock.step()

      port.reqTx.ready.poke(true)
      dut.clock.step() // active SN REQ is accepted
      data(port.datRx.bits, 1, 3, 0, lines.head)
      port.datRx.valid.poke(true)
      dut.clock.step()
      port.datRx.valid.poke(false)
      port.datTx.ready.poke(true)
      dut.clock.step() // active response is delivered; slot 0 is granted first
      dut.clock.step()
      port.rspTx.ready.poke(false)
      port.rspTx.valid.expect(true.B)
      expectResponse(port.rspTx.bits, 2, 1, 0, ChiOpcode.Rsp.PCrdGrant)
      port.rspTx.ready.poke(true)
      dut.clock.step()

      request(port.reqRx.bits, 1, 2, 2, 64)
      port.reqRx.bits.allowRetry.poke(false)
      port.reqRx.valid.poke(true)
      dut.clock.step()
      port.reqRx.valid.poke(false)
      port.reqTx.ready.poke(false)
      dut.clock.step()
      expectRequest(port.reqTx.bits, 3, 1, 0, 64)
      port.reqTx.valid.expect(true.B)

      port.reqTx.ready.poke(true)
      dut.clock.step()
      data(port.datRx.bits, 1, 3, 0, lines(1))
      port.datRx.valid.poke(true)
      dut.clock.step()
      port.datRx.valid.poke(false)
      port.datTx.ready.poke(true)
      dut.clock.step()
      dut.clock.step()
      port.rspTx.ready.poke(false)
      port.rspTx.valid.expect(true.B)
      expectResponse(port.rspTx.bits, 4, 1, 0, ChiOpcode.Rsp.PCrdGrant)
    }
  }
}
