package chi

import chisel3._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class RnCacheSpec extends AnyFreeSpec with Matchers with ChiNodeTestSupport {
  private implicit val p: Parameters = Parameters.empty

  private val config = ChiNoCParams()
  private val rn = RnParams(nodeId = 0, hnId = 1, coherent = true, cacheLines = 4)

  private def completeDataRead(dut: ChiNodeFixtures.RnModule, address: BigInt,
    txn: Int, value: BigInt, resp: UInt): Unit = {
    val port = dut.io.network
    port.datRx.ready.expect(true.B)
    data(port.datRx.bits, rn.nodeId, rn.hnId, txn, value)
    port.datRx.bits.resp.poke(resp)
    port.datRx.valid.poke(true)
    dut.clock.step()
    port.datRx.valid.poke(false)
    port.rspTx.valid.expect(true.B)
    expectResponse(port.rspTx.bits, rn.hnId, rn.nodeId, txn, ChiOpcode.Rsp.CompAck)
    dut.clock.step()
    port.rspTx.valid.expect(false.B)
  }

  "a coherent read miss fills the cache and a second read hits locally" in {
    simulate(LazyModule(new ChiNodeFixtures.Rn(config, rn)).module) { dut =>
      val local = dut.io.local
      val port = dut.io.network
      initialize(local)
      initialize(port)
      val address = BigInt(0)
      val value = lines.head

      localRequest(local, 3, address)
      local.request.ready.expect(true.B)
      dut.clock.step()
      local.request.valid.poke(false)
      port.reqTx.valid.expect(true.B)
      expectRequest(port.reqTx.bits, rn.hnId, rn.nodeId, 3, address, ChiOpcode.Req.ReadShared)
      dut.clock.step()
      completeDataRead(dut, address, 3, value, ChiResp.UC)

      local.response.valid.expect(true.B)
      local.response.bits.data.expect(value.U)
      local.response.ready.poke(true.B)
      dut.clock.step()
      local.response.ready.poke(false.B)

      localRequest(local, 4, address)
      local.request.ready.expect(true.B)
      dut.clock.step()
      local.request.valid.poke(false)
      local.response.valid.expect(true.B)
      local.response.bits.txnID.expect(4.U)
      local.response.bits.data.expect(value.U)
      port.reqTx.valid.expect(false.B)
      port.rspTx.valid.expect(false.B)
      local.response.ready.poke(true.B)
      dut.clock.step()
    }
  }

  "a coherent write miss uses ReadUnique and a UC hit writes locally" in {
    simulate(LazyModule(new ChiNodeFixtures.Rn(config, rn)).module) { dut =>
      val local = dut.io.local
      val port = dut.io.network
      initialize(local)
      initialize(port)
      val address = BigInt(64)
      val value = (BigInt(1) << 511) | BigInt(0x55aa)

      localWriteRequest(local, 7, address, value)
      dut.clock.step()
      local.request.valid.poke(false)
      port.reqTx.valid.expect(true.B)
      expectRequest(port.reqTx.bits, rn.hnId, rn.nodeId, 7, address, ChiOpcode.Req.ReadUnique)
      dut.clock.step()
      completeDataRead(dut, address, 7, BigInt(0), ChiResp.UC)
      local.response.valid.expect(true.B)
      local.response.bits.data.expect(0.U)
      local.response.ready.poke(true.B)
      dut.clock.step()
      local.response.ready.poke(false)

      localRequest(local, 8, address)
      dut.clock.step()
      local.request.valid.poke(false)
      local.response.valid.expect(true.B)
      local.response.bits.txnID.expect(8.U)
      local.response.bits.data.expect(value.U)
      port.reqTx.valid.expect(false.B)
      local.response.ready.poke(true.B)
      dut.clock.step()
    }
  }

  "a shared hit upgrades through ReadUnique before a write" in {
    simulate(LazyModule(new ChiNodeFixtures.Rn(config, rn)).module) { dut =>
      val local = dut.io.local
      val port = dut.io.network
      initialize(local)
      initialize(port)
      val address = BigInt(128)
      val oldValue = lines(1)
      val newValue = BigInt(0xdeadbeef)

      localRequest(local, 1, address)
      dut.clock.step()
      local.request.valid.poke(false)
      dut.clock.step()
      completeDataRead(dut, address, 1, oldValue, ChiResp.SC)
      local.response.ready.poke(true.B)
      dut.clock.step()
      local.response.ready.poke(false)

      localWriteRequest(local, 2, address, newValue)
      dut.clock.step()
      local.request.valid.poke(false)
      port.reqTx.valid.expect(true.B)
      expectRequest(port.reqTx.bits, rn.hnId, rn.nodeId, 2, address, ChiOpcode.Req.ReadUnique)
      dut.clock.step()
      completeDataRead(dut, address, 2, oldValue, ChiResp.UC)
      local.response.bits.data.expect(0.U)
      local.response.ready.poke(true.B)
      dut.clock.step()
    }
  }

  "cache parameters require a power-of-two line count" in {
    an[IllegalArgumentException] should be thrownBy RnParams(cacheLines = 3)
  }
}
