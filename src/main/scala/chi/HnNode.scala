package chi

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy.nodes._

case class HnParams(nodeId: Int = 1, snId: Int = 3) {
  require(nodeId >= 0 && snId >= 0, "HN and SN node IDs must be nonnegative")
}

/** One upstream transaction; retain its identity while issuing a downstream request. */
class HnNode(val params: HnParams)(implicit p: Parameters) extends LazyModule {
  val node = new SourceNode(ChiNodeImp)(Seq(ChiEndpointParams(params.nodeId, ChiRole.HN)))
  lazy val module = new HnNodeModule(this)
}

class HnNodeModule(outer: HnNode) extends LazyModuleImp(outer) {
  private val (port, edge) = outer.node.out.head
  private val params = edge.network.chi
  require(BigInt(outer.params.snId) < (BigInt(1) << params.nodeIdWidth),
    "SN target does not fit the negotiated node ID width")

  val idle :: sendSnReq :: waitSnData :: sendRnData :: Nil = Enum(4)
  val state = RegInit(idle)
  private val request = Reg(new ReqFlit(params))
  private val data = Reg(UInt(512.W))
  // One downstream request is active, so its ID can remain zero.
  private val downstreamTxnId = 0.U(params.txnIdWidth.W)

  port.reqRx.ready := state === idle
  when(port.reqRx.fire) {
    val req = port.reqRx.bits
    assert(req.opcode === ChiOpcode.Req.ReadNoSnp, "HN currently supports only ReadNoSnp")
    assert(req.tgtID === outer.params.nodeId.U, "REQ target must be this HN")
    assert(req.addr(5, 0) === 0.U, "HN requires line-aligned addresses")
    assert(req.allowRetry && req.pCrdType === 0.U, "HN currently accepts only first attempts")
    request := req
    state := sendSnReq
  }

  port.reqTx.valid := state === sendSnReq
  port.reqTx.bits := request
  port.reqTx.bits.tgtID := outer.params.snId.U
  port.reqTx.bits.srcID := outer.params.nodeId.U
  port.reqTx.bits.txnID := downstreamTxnId
  port.reqTx.bits.allowRetry := true.B
  when(port.reqTx.fire) { state := waitSnData }

  port.datRx.ready := state === waitSnData
  when(port.datRx.fire) {
    val dat = port.datRx.bits
    assert(dat.tgtID === outer.params.nodeId.U && dat.srcID === outer.params.snId.U &&
      dat.txnID === downstreamTxnId, "HN received data for another transaction")
    assert(dat.opcode === ChiOpcode.Dat.CompData && dat.resp === ChiResp.I && dat.dbID === 0.U,
      "HN expects non-coherent CompData")
    data := dat.data
    state := sendRnData
  }

  port.datTx.valid := state === sendRnData
  port.datTx.bits := 0.U.asTypeOf(port.datTx.bits)
  port.datTx.bits.tgtID := request.srcID
  port.datTx.bits.srcID := outer.params.nodeId.U
  port.datTx.bits.txnID := request.txnID
  port.datTx.bits.opcode := ChiOpcode.Dat.CompData
  port.datTx.bits.resp := ChiResp.I
  port.datTx.bits.data := data
  when(port.datTx.fire) { state := idle }

  port.rspTx.valid := false.B
  port.rspTx.bits := 0.U.asTypeOf(port.rspTx.bits)
  port.snpTx.valid := false.B
  port.snpTx.bits := 0.U.asTypeOf(port.snpTx.bits)
  port.rspRx.ready := false.B
  port.snpRx.ready := false.B
}
