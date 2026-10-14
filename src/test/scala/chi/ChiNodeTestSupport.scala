package chi

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy.nodes._
import org.scalatest.matchers.should.Matchers

/** Direct diplomatic peers let endpoint tests block each send independently of the ring. */
private[chi] object ChiNodeFixtures {
  class Rn(val config: ChiNoCParams, val params: RnParams)(implicit p: Parameters) extends LazyModule {
    val rn = LazyModule(new RnNode(params))
    val peer = new SinkNode(ChiNodeImp)(Seq(config))
    peer := rn.node
    lazy val module = new RnModule(this)
  }
  class RnModule(outer: Rn) extends LazyModuleImp(outer) {
    val io = IO(new Bundle {
      val local = new RnPort(outer.config.chi)
      val network = new ChiPort(outer.config.chi)
    })
    io.local <> outer.rn.module.io
    outer.peer.in.head._1 <> io.network
  }

  class Hn(val config: ChiNoCParams, val params: HnParams)(implicit p: Parameters) extends LazyModule {
    val hn = LazyModule(new HnNode(params))
    val peer = new SinkNode(ChiNodeImp)(Seq(config))
    peer := hn.node
    lazy val module = new HnModule(this)
  }
  class HnModule(outer: Hn) extends LazyModuleImp(outer) {
    val io = IO(new ChiPort(outer.config.chi))
    outer.peer.in.head._1 <> io
  }

  class Sn(val config: ChiNoCParams, val params: SnParams)(implicit p: Parameters) extends LazyModule {
    val sn = LazyModule(new SnNode(params))
    val peer = new SinkNode(ChiNodeImp)(Seq(config))
    peer := sn.node
    lazy val module = new SnModule(this)
  }
  class SnModule(outer: Sn) extends LazyModuleImp(outer) {
    val io = IO(new ChiPort(outer.config.chi))
    outer.peer.in.head._1 <> io
  }
}

private[chi] trait ChiNodeTestSupport extends ChiselSim { this: org.scalatest.TestSuite with Matchers =>
  protected val lines: Seq[BigInt] = Seq.tabulate(3)(index =>
    (BigInt(1) << 511) | (BigInt(index + 1) << 256) | BigInt(0x12345678 + index))

  protected def initialize(port: ChiPort): Unit = {
    Seq(port.reqTx, port.rspTx, port.snpTx, port.datTx).foreach(_.ready.poke(true))
    Seq(port.reqRx, port.rspRx, port.snpRx, port.datRx).foreach(_.valid.poke(false))
    for (bits <- Seq(port.reqRx.bits, port.rspRx.bits, port.snpRx.bits, port.datRx.bits);
      field <- bits.elements.values) {
      field match {
        case value: Bool => value.poke(false)
        case value: UInt => value.poke(BigInt(0))
      }
    }
  }

  protected def initialize(port: RnPort): Unit = {
    port.request.valid.poke(false)
    port.request.bits.write.poke(false)
    port.request.bits.addr.poke(BigInt(0))
    port.request.bits.data.poke(BigInt(0))
    port.request.bits.txnID.poke(BigInt(0))
    port.response.ready.poke(false)
  }

  protected def localRequest(port: RnPort, txn: Int, address: BigInt): Unit = {
    port.request.bits.write.poke(false)
    port.request.bits.addr.poke(address)
    port.request.bits.txnID.poke(BigInt(txn))
    port.request.bits.data.poke(BigInt(0))
    port.request.valid.poke(true)
  }

  protected def request(req: ReqFlit, target: Int, src: Int, txn: Int, address: BigInt): Unit = {
    req.tgtID.poke(BigInt(target))
    req.srcID.poke(BigInt(src))
    req.txnID.poke(BigInt(txn))
    req.opcode.poke(ChiOpcode.Req.ReadNoSnp)
    req.addr.poke(address)
    req.allowRetry.poke(true)
    req.pCrdType.poke(BigInt(0))
  }

  protected def expectRequest(req: ReqFlit, target: Int, src: Int, txn: Int, address: BigInt): Unit = {
    req.tgtID.expect(target.U)
    req.srcID.expect(src.U)
    req.txnID.expect(txn.U)
    req.opcode.expect(ChiOpcode.Req.ReadNoSnp)
    req.addr.expect(address.U)
    req.allowRetry.expect(true.B)
    req.pCrdType.expect(0.U)
  }

  protected def data(dat: DatFlit, target: Int, src: Int, txn: Int, value: BigInt): Unit = {
    dat.tgtID.poke(BigInt(target))
    dat.srcID.poke(BigInt(src))
    dat.txnID.poke(BigInt(txn))
    dat.opcode.poke(ChiOpcode.Dat.CompData)
    dat.resp.poke(ChiResp.I)
    dat.dbID.poke(BigInt(0))
    dat.data.poke(value)
  }

  protected def expectData(dat: DatFlit, target: Int, src: Int, txn: Int, value: BigInt): Unit = {
    dat.tgtID.expect(target.U)
    dat.srcID.expect(src.U)
    dat.txnID.expect(txn.U)
    dat.opcode.expect(ChiOpcode.Dat.CompData)
    dat.resp.expect(ChiResp.I)
    dat.dbID.expect(0.U)
    dat.data.expect(value.U)
  }
}
