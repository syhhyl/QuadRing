package chi

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy.nodes._

case class RnParams(nodeId: Int = 0, hnId: Int = 1) {
  require(nodeId >= 0 && hnId >= 0, "RN and HN node IDs must be nonnegative")
}

/** One local command at a time; R0 supports non-coherent reads and whole-line writes. */
class RnNode(val params: RnParams)(implicit p: Parameters) extends LazyModule {
  val node = new SourceNode(ChiNodeImp)(Seq(ChiEndpointParams(params.nodeId, ChiRole.RN)))
  lazy val module = new RnNodeModule(this)
}

class RnNodeModule(outer: RnNode) extends LazyModuleImp(outer) {
  private val (port, edge) = outer.node.out.head
  private val params = edge.network.chi
  require(BigInt(outer.params.hnId) < (BigInt(1) << params.nodeIdWidth),
    "HN target does not fit the negotiated node ID width")
  val io = IO(new RnPort(params))

  val (idle :: sendReq :: waitData :: waitDbId :: sendWriteData :: returnResult :: Nil) = Enum(6)
  val state = RegInit(idle)
  private val request = Reg(new RnRequest(params))
  private val result = Reg(new RnResponse(params))
  private val writeDbId = Reg(UInt(params.txnIdWidth.W))

  io.request.ready := state === idle
  when(io.request.fire) {
    assert(io.request.bits.addr(5, 0) === 0.U, "RN requires line-aligned addresses")
    request := io.request.bits
    state := sendReq
  }

  port.reqTx.valid := state === sendReq
  port.reqTx.bits := 0.U.asTypeOf(port.reqTx.bits)
  port.reqTx.bits.tgtID := outer.params.hnId.U
  port.reqTx.bits.srcID := outer.params.nodeId.U
  port.reqTx.bits.txnID := request.txnID
  port.reqTx.bits.opcode := Mux(request.write, ChiOpcode.Req.WriteNoSnpFull, ChiOpcode.Req.ReadNoSnp)
  port.reqTx.bits.addr := request.addr
  port.reqTx.bits.allowRetry := true.B
  when(port.reqTx.fire) { state := Mux(request.write, waitDbId, waitData) }

  port.datRx.ready := state === waitData
  when(port.datRx.fire) {
    val dat = port.datRx.bits
    assert(dat.tgtID === outer.params.nodeId.U && dat.srcID === outer.params.hnId.U &&
      dat.txnID === request.txnID, "RN received data for another transaction")
    assert(dat.opcode === ChiOpcode.Dat.CompData && dat.resp === ChiResp.I && dat.dbID === 0.U,
      "RN expects non-coherent CompData")
    result.txnID := request.txnID
    result.data := dat.data
    state := returnResult
  }

  port.rspRx.ready := state === waitDbId
  when(port.rspRx.fire) {
    val rsp = port.rspRx.bits
    assert(rsp.tgtID === outer.params.nodeId.U && rsp.srcID === outer.params.hnId.U &&
      rsp.txnID === request.txnID, "RN received a response for another transaction")
    assert(rsp.opcode === ChiOpcode.Rsp.CompDBIDResp && rsp.resp === ChiResp.I &&
      rsp.pCrdType === 0.U, "RN expects non-coherent CompDBIDResp")
    writeDbId := rsp.dbID
    state := sendWriteData
  }

  port.datTx.valid := state === sendWriteData
  port.datTx.bits := 0.U.asTypeOf(port.datTx.bits)
  port.datTx.bits.tgtID := outer.params.hnId.U
  port.datTx.bits.srcID := outer.params.nodeId.U
  port.datTx.bits.txnID := writeDbId
  port.datTx.bits.opcode := ChiOpcode.Dat.NonCopyBackWrData
  port.datTx.bits.resp := ChiResp.I
  port.datTx.bits.data := request.data
  when(port.datTx.fire) {
    result.txnID := request.txnID
    result.data := 0.U
    state := returnResult
  }

  io.response.valid := state === returnResult
  io.response.bits := result
  when(io.response.fire) { state := idle }

  port.rspTx.valid := false.B
  port.rspTx.bits := 0.U.asTypeOf(port.rspTx.bits)
  port.snpTx.valid := false.B
  port.snpTx.bits := 0.U.asTypeOf(port.snpTx.bits)
  port.reqRx.ready := false.B
  port.snpRx.ready := false.B
}
