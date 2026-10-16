package chi

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy.nodes._

case class HnParams(nodeId: Int = 1, snId: Int = 3, retrySlots: Int = 2) {
  require(nodeId >= 0 && snId >= 0, "HN and SN node IDs must be nonnegative")
  require(retrySlots > 0, "HN must have at least one retry slot")
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
    waitRnData :: waitSnDbId :: sendSnData :: waitSnComp :: sendGrant :: waitRetryReq :: Nil) = Enum(11)
  val state = RegInit(idle)
  private val request = Reg(new ReqFlit(params))
  private val data = Reg(UInt(512.W))
  // One downstream request is active, so its ID can remain zero.
  private val downstreamTxnId = 0.U(params.txnIdWidth.W)
  private val dbId = 0.U(params.txnIdWidth.W)
  private val snDbId = Reg(UInt(params.txnIdWidth.W))
  private val retryAckSeen = RegInit(false.B)
  private val creditHeld = RegInit(false.B)
  private val retried = RegInit(false.B)
  private val retryWaiting = RegInit(VecInit(Seq.fill(outer.params.retrySlots)(false.B)))
  private val retryRequests = Reg(Vec(outer.params.retrySlots, new ReqFlit(params)))
  private val retryAckPending = RegInit(false.B)
  private val retryAckTarget = Reg(UInt(params.nodeIdWidth.W))
  private val retryAckTxn = Reg(UInt(params.txnIdWidth.W))
  private val slotWidth = log2Ceil(outer.params.retrySlots max 2)
  private val grantSlot = Reg(UInt(slotWidth.W))
  private val rrSlot = RegInit(0.U(slotWidth.W))
  private val waitingForSnReply = state === waitSnData || state === waitSnDbId
  private val retryReady = retryAckSeen && creditHeld
  private val hasRetryWaiting = retryWaiting.asUInt.orR
  private val retrySlotFree = retryWaiting.map(slot => !slot).reduce(_ || _)
  private val duplicateRetry = retryWaiting.zip(retryRequests).map { case (valid, saved) =>
    valid && saved.srcID === port.reqRx.bits.srcID
  }.reduce(_ || _)
  private val activeState = state =/= idle && state =/= sendGrant && state =/= waitRetryReq
  private val selectedRetrySlot: UInt = {
    var found: Bool = false.B
    var selected: UInt = 0.U(slotWidth.W)
    for (start <- 0 until outer.params.retrySlots; distance <- 0 until outer.params.retrySlots) {
      val index = (start + distance) % outer.params.retrySlots
      val take = !found && rrSlot === start.U && retryWaiting(index)
      selected = Mux(take, index.U(slotWidth.W), selected)
      found = found || (rrSlot === start.U && retryWaiting(index))
    }
    selected
  }

  port.reqRx.ready := (state === idle && !hasRetryWaiting) ||
    (activeState && retrySlotFree && !retryAckPending) ||
    state === waitRetryReq
  when(port.reqRx.fire) {
    val req = port.reqRx.bits
    when(state === waitRetryReq) {
      val saved = retryRequests(grantSlot)
      assert(req.opcode === saved.opcode && req.addr === saved.addr && req.txnID === saved.txnID &&
        req.srcID === saved.srcID && req.tgtID === saved.tgtID,
        "HN received a retry for another request")
      assert(!req.allowRetry && req.pCrdType === 0.U, "HN expects a credited retry")
      request := req
      retryWaiting(grantSlot) := false.B
      rrSlot := Mux(grantSlot === (outer.params.retrySlots - 1).U, 0.U, grantSlot + 1.U)
      retryAckSeen := false.B
      creditHeld := false.B
      retried := true.B
      state := Mux(req.opcode === ChiOpcode.Req.ReadNoSnp, sendSnReq, sendRnRsp)
    }.elsewhen(state === idle) {
      assert(req.opcode === ChiOpcode.Req.ReadNoSnp || req.opcode === ChiOpcode.Req.WriteNoSnpFull,
        "HN currently supports only ReadNoSnp and WriteNoSnpFull")
      assert(req.tgtID === outer.params.nodeId.U, "REQ target must be this HN")
      assert(req.addr(5, 0) === 0.U, "HN requires line-aligned addresses")
      assert(req.allowRetry && req.pCrdType === 0.U, "HN currently accepts only first attempts")
      request := req
      retryAckSeen := false.B
      creditHeld := false.B
      retried := false.B
      state := Mux(req.opcode === ChiOpcode.Req.ReadNoSnp, sendSnReq, sendRnRsp)
    }.otherwise {
      assert(req.opcode === ChiOpcode.Req.ReadNoSnp || req.opcode === ChiOpcode.Req.WriteNoSnpFull,
        "HN currently supports only ReadNoSnp and WriteNoSnpFull")
      assert(req.tgtID === outer.params.nodeId.U && req.addr(5, 0) === 0.U &&
        req.allowRetry && req.pCrdType === 0.U,
        "HN expects a retryable first attempt")
      assert(retrySlotFree && !retryAckPending, "HN retry table is full")
      assert(!duplicateRetry, "HN already has a retry waiting for this requester")
      var allEarlierOccupied: Bool = true.B
      for (slot <- 0 until outer.params.retrySlots) {
        val choose = allEarlierOccupied && !retryWaiting(slot)
        when(choose) {
          retryRequests(slot) := req
          retryWaiting(slot) := true.B
        }
        allEarlierOccupied = allEarlierOccupied && retryWaiting(slot)
      }
      retryAckTarget := req.srcID
      retryAckTxn := req.txnID
      retryAckPending := true.B
    }
  }

  port.reqTx.valid := state === sendSnReq
  port.reqTx.bits := request
  port.reqTx.bits.tgtID := outer.params.snId.U
  port.reqTx.bits.srcID := outer.params.nodeId.U
  port.reqTx.bits.txnID := downstreamTxnId
  port.reqTx.bits.allowRetry := !retryReady
  port.reqTx.bits.pCrdType := 0.U
  when(port.reqTx.fire) {
    when(retryReady) {
      retryAckSeen := false.B
      creditHeld := false.B
      retried := true.B
    }
    state := Mux(request.opcode === ChiOpcode.Req.ReadNoSnp, waitSnData, waitSnDbId)
  }

  port.rspTx.valid := retryAckPending || state === sendRnRsp || state === sendGrant
  port.rspTx.bits := 0.U.asTypeOf(port.rspTx.bits)
  port.rspTx.bits.tgtID := Mux(state === sendRnRsp, request.srcID,
    Mux(retryAckPending, retryAckTarget, retryRequests(grantSlot).srcID))
  port.rspTx.bits.srcID := outer.params.nodeId.U
  port.rspTx.bits.txnID := Mux(state === sendRnRsp, request.txnID,
    Mux(retryAckPending, retryAckTxn, 0.U))
  port.rspTx.bits.opcode := Mux(state === sendRnRsp, ChiOpcode.Rsp.CompDBIDResp,
    Mux(retryAckPending, ChiOpcode.Rsp.RetryAck, ChiOpcode.Rsp.PCrdGrant))
  port.rspTx.bits.dbID := Mux(state === sendGrant, 0.U, dbId)
  when(port.rspTx.fire) {
    when(state === sendRnRsp) {
      state := waitRnData
    }.elsewhen(retryAckPending) {
      retryAckPending := false.B
    }.elsewhen(state === sendGrant) {
      state := waitRetryReq
    }.otherwise {
      state := waitRnData
    }
  }

  port.datRx.ready := state === waitRnData ||
    (state === waitSnData && !retryAckSeen && !creditHeld && !port.rspRx.valid)
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

  port.rspRx.ready := (waitingForSnReply && !retryReady) || state === waitSnComp
  when(port.rspRx.fire) {
    val rsp = port.rspRx.bits
    assert(rsp.tgtID === outer.params.nodeId.U && rsp.srcID === outer.params.snId.U,
      "HN received a response for another transaction")
    assert(rsp.resp === ChiResp.I && rsp.pCrdType === 0.U, "HN expects non-coherent SN responses")
    when(rsp.opcode === ChiOpcode.Rsp.RetryAck || rsp.opcode === ChiOpcode.Rsp.PCrdGrant) {
      assert(waitingForSnReply && !retried, "HN cannot retry an already accepted SN request")
      when(rsp.opcode === ChiOpcode.Rsp.RetryAck) {
        assert(rsp.txnID === downstreamTxnId, "HN received RetryAck for another transaction")
        assert(rsp.dbID === 0.U, "HN expects zero RetryAck DBID")
        assert(!retryAckSeen, "HN received duplicate RetryAck")
        retryAckSeen := true.B
      }.otherwise {
        assert(rsp.txnID === 0.U && rsp.dbID === 0.U, "HN expects zero PCrdGrant IDs")
        assert(!creditHeld, "HN received duplicate PCrdGrant")
        creditHeld := true.B
      }
    }.otherwise {
      assert(rsp.txnID === downstreamTxnId, "HN received a response for another transaction")
      assert(!retryAckSeen && !creditHeld, "HN cannot complete a request awaiting retry")
      when(state === waitSnDbId) {
        assert(rsp.opcode === ChiOpcode.Rsp.DBIDResp, "HN expects SN DBIDResp")
        snDbId := rsp.dbID
        state := sendSnData
      }.otherwise {
        assert(state === waitSnComp && rsp.opcode === ChiOpcode.Rsp.Comp, "HN expects SN write completion")
        assert(rsp.dbID === snDbId, "HN received completion for another data slot")
        state := idle
      }
    }
  }
  when(waitingForSnReply && retryReady) { state := sendSnReq }

  port.datTx.valid := state === sendRnData || state === sendSnData
  port.datTx.bits := 0.U.asTypeOf(port.datTx.bits)
  port.datTx.bits.tgtID := Mux(state === sendSnData, outer.params.snId.U, request.srcID)
  port.datTx.bits.srcID := outer.params.nodeId.U
  port.datTx.bits.txnID := Mux(state === sendSnData, snDbId, request.txnID)
  port.datTx.bits.opcode := Mux(state === sendSnData, ChiOpcode.Dat.NonCopyBackWrData, ChiOpcode.Dat.CompData)
  port.datTx.bits.resp := ChiResp.I
  port.datTx.bits.data := data
  when(port.datTx.fire) {
    state := Mux(state === sendRnData, idle, waitSnComp)
  }

  port.snpTx.valid := false.B
  port.snpTx.bits := 0.U.asTypeOf(port.snpTx.bits)
  port.snpRx.ready := false.B

  when(state === idle && hasRetryWaiting) {
    grantSlot := selectedRetrySlot
    state := sendGrant
  }
}
