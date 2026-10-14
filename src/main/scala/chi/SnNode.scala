package chi

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy.nodes._

case class SnParams(nodeId: Int = 3, initialLines: Seq[BigInt] = Seq.fill(4)(BigInt(0))) {
  require(nodeId >= 0, "SN node ID must be nonnegative")
  require(initialLines.nonEmpty, "SN memory must contain at least one line")
  require(initialLines.forall(line => line >= 0 && line < (BigInt(1) << 512)),
    "SN memory lines must fit in 512 bits")
}

/** One memory operation at a time, with separate write authorization and completion. */
class SnNode(val params: SnParams)(implicit p: Parameters) extends LazyModule {
  val node = new SourceNode(ChiNodeImp)(Seq(ChiEndpointParams(params.nodeId, ChiRole.SN)))
  lazy val module = new SnNodeModule(this)
}

class SnNodeModule(outer: SnNode) extends LazyModuleImp(outer) {
  private val (port, edge) = outer.node.out.head
  private val params = edge.network.chi
  private val initialLines = outer.params.initialLines
  require(BigInt(initialLines.size) <= (BigInt(1) << (params.addrWidth - 6)),
    "SN memory does not fit the negotiated address width")

  private val memory = RegInit(VecInit(initialLines.map(_.U(512.W))))
  val idle :: readLine :: sendData :: sendDbId :: waitWriteData :: writeLine :: sendComp :: Nil = Enum(7)
  val state = RegInit(idle)
  private val request = Reg(new ReqFlit(params))
  private val data = Reg(UInt(512.W))
  // A single active data slot; write DAT uses this ID, not the original REQ txnID.
  private val dbId = 0.U(params.txnIdWidth.W)
  private val memoryLine = if (initialLines.size == 1) memory(0) else {
    val index = (request.addr >> 6)(log2Ceil(initialLines.size) - 1, 0)
    memory(index)
  }

  port.reqRx.ready := state === idle
  when(port.reqRx.fire) {
    val req = port.reqRx.bits
    assert(req.opcode === ChiOpcode.Req.ReadNoSnp || req.opcode === ChiOpcode.Req.WriteNoSnpFull,
      "SN currently supports only ReadNoSnp and WriteNoSnpFull")
    assert(req.tgtID === outer.params.nodeId.U, "REQ target must be this SN")
    assert(req.addr(5, 0) === 0.U, "SN requires line-aligned addresses")
    assert((req.addr >> 6) < initialLines.size.U, "SN address is outside memory")
    assert(req.allowRetry && req.pCrdType === 0.U, "SN currently accepts only first attempts")
    request := req
    state := Mux(req.opcode === ChiOpcode.Req.ReadNoSnp, readLine, sendDbId)
  }

  when(state === readLine) {
    data := memoryLine
    state := sendData
  }

  port.datTx.valid := state === sendData
  port.datTx.bits := 0.U.asTypeOf(port.datTx.bits)
  port.datTx.bits.tgtID := request.srcID
  port.datTx.bits.srcID := outer.params.nodeId.U
  port.datTx.bits.txnID := request.txnID
  port.datTx.bits.opcode := ChiOpcode.Dat.CompData
  port.datTx.bits.resp := ChiResp.I
  port.datTx.bits.data := data
  when(port.datTx.fire) { state := idle }

  port.rspTx.valid := state === sendDbId || state === sendComp
  port.rspTx.bits := 0.U.asTypeOf(port.rspTx.bits)
  port.rspTx.bits.tgtID := request.srcID
  port.rspTx.bits.srcID := outer.params.nodeId.U
  port.rspTx.bits.txnID := request.txnID
  port.rspTx.bits.opcode := Mux(state === sendDbId, ChiOpcode.Rsp.DBIDResp, ChiOpcode.Rsp.Comp)
  port.rspTx.bits.dbID := dbId
  when(port.rspTx.fire) {
    state := Mux(state === sendDbId, waitWriteData, idle)
  }

  port.datRx.ready := state === waitWriteData
  when(port.datRx.fire) {
    val dat = port.datRx.bits
    assert(dat.tgtID === outer.params.nodeId.U && dat.srcID === request.srcID && dat.txnID === dbId,
      "SN received write data for another transaction")
    assert(dat.opcode === ChiOpcode.Dat.NonCopyBackWrData && dat.resp === ChiResp.I && dat.dbID === 0.U,
      "SN expects non-coherent NonCopyBackWrData")
    data := dat.data
    state := writeLine
  }

  when(state === writeLine) {
    memoryLine := data
    state := sendComp
  }

  port.reqTx.valid := false.B
  port.reqTx.bits := 0.U.asTypeOf(port.reqTx.bits)
  port.snpTx.valid := false.B
  port.snpTx.bits := 0.U.asTypeOf(port.snpTx.bits)
  port.rspRx.ready := false.B
  port.snpRx.ready := false.B
}
