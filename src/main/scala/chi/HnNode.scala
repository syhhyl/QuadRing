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

  val (idle :: sendSnReq :: waitSnData :: sendRnData :: sendRnRsp ::
    waitRnData :: waitSnDbId :: sendSnData :: waitSnComp :: Nil) = Enum(9)
  val state = RegInit(idle)
  private val request = Reg(new ReqFlit(params))
  private val data = Reg(UInt(512.W))
  // One downstream request is active, so its ID can remain zero.
  private val downstreamTxnId = 0.U(params.txnIdWidth.W)
  private val dbId = 0.U(params.txnIdWidth.W)
  private val snDbId = Reg(UInt(params.txnIdWidth.W))

  port.reqRx.ready := state === idle
  when(port.reqRx.fire) {
    val req = port.reqRx.bits
    assert(req.opcode === ChiOpcode.Req.ReadNoSnp || req.opcode === ChiOpcode.Req.WriteNoSnpFull,
      "HN currently supports only ReadNoSnp and WriteNoSnpFull")
    assert(req.tgtID === outer.params.nodeId.U, "REQ target must be this HN")
    assert(req.addr(5, 0) === 0.U, "HN requires line-aligned addresses")
    assert(req.allowRetry && req.pCrdType === 0.U, "HN currently accepts only first attempts")
    request := req
    state := Mux(req.opcode === ChiOpcode.Req.ReadNoSnp, sendSnReq, sendRnRsp)
  }

  port.reqTx.valid := state === sendSnReq
  port.reqTx.bits := request
  port.reqTx.bits.tgtID := outer.params.snId.U
  port.reqTx.bits.srcID := outer.params.nodeId.U
  port.reqTx.bits.txnID := downstreamTxnId
  port.reqTx.bits.allowRetry := true.B
  when(port.reqTx.fire) {
    state := Mux(request.opcode === ChiOpcode.Req.ReadNoSnp, waitSnData, waitSnDbId)
  }

  port.rspTx.valid := state === sendRnRsp
  port.rspTx.bits := 0.U.asTypeOf(port.rspTx.bits)
  port.rspTx.bits.tgtID := request.srcID
  port.rspTx.bits.srcID := outer.params.nodeId.U
  port.rspTx.bits.txnID := request.txnID
  port.rspTx.bits.opcode := ChiOpcode.Rsp.CompDBIDResp
  port.rspTx.bits.dbID := dbId
  when(port.rspTx.fire) { state := waitRnData }

  port.datRx.ready := state === waitSnData || state === waitRnData
  when(port.datRx.fire) {
    val dat = port.datRx.bits
    when(state === waitSnData) {
      assert(dat.tgtID === outer.params.nodeId.U && dat.srcID === outer.params.snId.U &&
        dat.txnID === downstreamTxnId, "HN received data for another transaction")
      assert(dat.opcode === ChiOpcode.Dat.CompData && dat.resp === ChiResp.I && dat.dbID === 0.U,
        "HN expects non-coherent CompData")
      state := sendRnData
    }.otherwise {
      assert(dat.tgtID === outer.params.nodeId.U && dat.srcID === request.srcID && dat.txnID === dbId,
        "HN received write data for another transaction")
      assert(dat.opcode === ChiOpcode.Dat.NonCopyBackWrData && dat.resp === ChiResp.I && dat.dbID === 0.U,
        "HN expects non-coherent NonCopyBackWrData")
      state := sendSnReq
    }
    data := dat.data
  }

  port.rspRx.ready := state === waitSnDbId || state === waitSnComp
  when(port.rspRx.fire) {
    val rsp = port.rspRx.bits
    assert(rsp.tgtID === outer.params.nodeId.U && rsp.srcID === outer.params.snId.U &&
      rsp.txnID === downstreamTxnId, "HN received a response for another transaction")
    assert(rsp.resp === ChiResp.I && rsp.pCrdType === 0.U, "HN expects non-coherent SN responses")
    when(state === waitSnDbId) {
      assert(rsp.opcode === ChiOpcode.Rsp.DBIDResp, "HN expects SN DBIDResp")
      snDbId := rsp.dbID
      state := sendSnData
    }.otherwise {
      assert(rsp.opcode === ChiOpcode.Rsp.Comp, "HN expects SN write completion")
      assert(rsp.dbID === snDbId, "HN received completion for another data slot")
      state := idle
    }
  }

  port.datTx.valid := state === sendRnData || state === sendSnData
  port.datTx.bits := 0.U.asTypeOf(port.datTx.bits)
  port.datTx.bits.tgtID := Mux(state === sendSnData, outer.params.snId.U, request.srcID)
  port.datTx.bits.srcID := outer.params.nodeId.U
  port.datTx.bits.txnID := Mux(state === sendSnData, snDbId, request.txnID)
  port.datTx.bits.opcode := Mux(state === sendSnData, ChiOpcode.Dat.NonCopyBackWrData, ChiOpcode.Dat.CompData)
  port.datTx.bits.resp := ChiResp.I
  port.datTx.bits.data := data
  when(port.datTx.fire) { state := Mux(state === sendRnData, idle, waitSnComp) }

  port.snpTx.valid := false.B
  port.snpTx.bits := 0.U.asTypeOf(port.snpTx.bits)
  port.snpRx.ready := false.B
}
