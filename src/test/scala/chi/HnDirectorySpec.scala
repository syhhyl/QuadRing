package chi

import chisel3._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class HnDirectorySpec extends AnyFreeSpec with Matchers with ChiNodeTestSupport {
  private implicit val p: Parameters = Parameters.empty

  private val config = ChiNoCParams()
  private val hn = HnParams(coherent = true, directoryLines = 4)

  private def issueRead(dut: ChiNodeFixtures.HnModule, src: Int, txn: Int,
    address: BigInt, opcode: UInt): Unit = {
    val port = dut.io
    request(port.reqRx.bits, hn.nodeId, src, txn, address)
    port.reqRx.bits.opcode.poke(opcode)
    port.reqRx.valid.poke(true)
    port.reqRx.ready.expect(true.B)
    dut.clock.step()
    port.reqRx.valid.poke(false)
  }

  private def finishRead(dut: ChiNodeFixtures.HnModule, src: Int, txn: Int,
    address: BigInt, value: BigInt, resp: UInt): Unit = {
    val port = dut.io
    port.reqTx.valid.expect(true.B)
    expectRequest(port.reqTx.bits, hn.snId, hn.nodeId, 0, address, ChiOpcode.Req.ReadNoSnp)
    dut.clock.step()

    port.datRx.ready.expect(true.B)
    data(port.datRx.bits, hn.nodeId, hn.snId, 0, value)
    port.datRx.valid.poke(true)
    dut.clock.step()
    port.datRx.valid.poke(false)

    port.datTx.valid.expect(true.B)
    port.datTx.bits.tgtID.expect(src.U)
    port.datTx.bits.srcID.expect(hn.nodeId.U)
    port.datTx.bits.txnID.expect(txn.U)
    port.datTx.bits.opcode.expect(ChiOpcode.Dat.CompData)
    port.datTx.bits.dbID.expect(0.U)
    port.datTx.bits.data.expect(value.U)
    port.datTx.bits.resp.expect(resp)
    dut.clock.step()
    port.datTx.valid.expect(false.B)

    response(port.rspRx.bits, hn.nodeId, src, txn, ChiOpcode.Rsp.CompAck)
    port.rspRx.valid.poke(true)
    port.rspRx.ready.expect(true.B)
    dut.clock.step()
    port.rspRx.valid.poke(false)
  }

  "directory tracks UC then SC holders and HN waits for CompAck" in {
    simulate(LazyModule(new ChiNodeFixtures.Hn(config, hn)).module) { dut =>
      initialize(dut.io)
      val address = BigInt(0)
      val value = lines.head

      issueRead(dut, 0, 3, address, ChiOpcode.Req.ReadShared)
      finishRead(dut, 0, 3, address, value, ChiResp.UC)

      issueRead(dut, 2, 4, address, ChiOpcode.Req.ReadShared)
      finishRead(dut, 2, 4, address, value, ChiResp.SC)

      issueRead(dut, 0, 5, address, ChiOpcode.Req.ReadShared)
      finishRead(dut, 0, 5, address, value, ChiResp.SC)
    }
  }

  "ReadUnique allocates a UC directory entry when no RN owns the line" in {
    simulate(LazyModule(new ChiNodeFixtures.Hn(config, hn)).module) { dut =>
      initialize(dut.io)
      issueRead(dut, 2, 9, BigInt(64), ChiOpcode.Req.ReadUnique)
      finishRead(dut, 2, 9, BigInt(64), lines(1), ChiResp.UC)
    }
  }

  "directory parameters require a power-of-two entry count" in {
    an[IllegalArgumentException] should be thrownBy HnParams(coherent = true, directoryLines = 3)
  }
}
