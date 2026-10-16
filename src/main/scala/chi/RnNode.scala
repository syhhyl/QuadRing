package chi

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy.nodes._

case class RnParams(nodeId: Int = 0, hnId: Int = 1, coherent: Boolean = false,
  cacheLines: Int = 8) {
  require(nodeId >= 0 && hnId >= 0, "RN and HN node IDs must be nonnegative")
  require(cacheLines >= 2 && (cacheLines & (cacheLines - 1)) == 0,
    "RN cacheLines must be a power of two greater than one")
}

/** One local command at a time; coherent mode adds a small direct-mapped line cache. */
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

  private val cacheIndexWidth = if (outer.params.coherent) log2Ceil(outer.params.cacheLines) else 1
  private val cacheTagWidth = if (outer.params.coherent) {
    params.addrWidth - 6 - cacheIndexWidth
  } else 1
  if (outer.params.coherent) {
    require(cacheTagWidth > 0, "RN cache must leave at least one tag bit")
  }

  val (idle :: sendReq :: waitData :: waitDbId :: waitComp :: sendWriteData :: sendCompAck ::
    returnResult :: Nil) = Enum(8)
  val state = RegInit(idle)
  private val request = Reg(new RnRequest(params))
  private val result = Reg(new RnResponse(params))
  private val requestOpcode = RegInit(ChiOpcode.Req.ReadNoSnp)
  private val victimAddress = Reg(UInt(params.addrWidth.W))
  private val writeDbId = Reg(UInt(params.txnIdWidth.W))
  private val completionDbId = Reg(UInt(params.txnIdWidth.W))
  private val cacheValid = RegInit(VecInit(Seq.fill(outer.params.cacheLines)(false.B)))
  private val cacheTags = Reg(Vec(outer.params.cacheLines, UInt(cacheTagWidth.W)))
  private val cacheStates = RegInit(VecInit(Seq.fill(outer.params.cacheLines)(RnCacheState.I)))
  private val cacheData = Reg(Vec(outer.params.cacheLines, UInt(512.W)))
  private val retryAckSeen = RegInit(false.B)
  private val creditHeld = RegInit(false.B)
  private val retried = RegInit(false.B)
  private val waitingForReply = state === waitData || state === waitDbId || state === waitComp
  private val retryReady = retryAckSeen && creditHeld

  private val localCacheIndex: UInt = if (outer.params.coherent) {
    io.request.bits.addr(6 + cacheIndexWidth - 1, 6)
  } else 0.U(cacheIndexWidth.W)
  private val localCacheTag: UInt = if (outer.params.coherent) {
    io.request.bits.addr(params.addrWidth - 1, 6 + cacheIndexWidth)
  } else 0.U(cacheTagWidth.W)
  private val localCacheHit = cacheValid(localCacheIndex) &&
    cacheTags(localCacheIndex) === localCacheTag && cacheStates(localCacheIndex) =/= RnCacheState.I

  private val requestCacheIndex: UInt = if (outer.params.coherent) {
    request.addr(6 + cacheIndexWidth - 1, 6)
  } else 0.U(cacheIndexWidth.W)
  private val requestCacheTag: UInt = if (outer.params.coherent) {
    request.addr(params.addrWidth - 1, 6 + cacheIndexWidth)
  } else 0.U(cacheTagWidth.W)

  io.request.ready := state === idle
  when(io.request.fire) {
    assert(io.request.bits.addr(5, 0) === 0.U, "RN requires line-aligned addresses")
    if (outer.params.coherent) {
      when(!io.request.bits.write && localCacheHit) {
        result.txnID := io.request.bits.txnID
        result.data := cacheData(localCacheIndex)
        state := returnResult
      }.elsewhen(io.request.bits.write && localCacheHit &&
        (cacheStates(localCacheIndex) === RnCacheState.UC ||
          cacheStates(localCacheIndex) === RnCacheState.UD)) {
        cacheData(localCacheIndex) := io.request.bits.data
        cacheStates(localCacheIndex) := RnCacheState.UD
        result.txnID := io.request.bits.txnID
        result.data := 0.U
        state := returnResult
      }.otherwise {
        val replace = cacheValid(localCacheIndex) &&
          cacheStates(localCacheIndex) =/= RnCacheState.I && !localCacheHit
        request := io.request.bits
        requestOpcode := Mux(io.request.bits.write, ChiOpcode.Req.ReadUnique,
          ChiOpcode.Req.ReadShared)
        when(replace) {
          assert(cacheStates(localCacheIndex) =/= RnCacheState.UD,
            "RN dirty cache replacement requires WriteBackFull")
          victimAddress := Cat(cacheTags(localCacheIndex), localCacheIndex, 0.U(6.W))
          requestOpcode := ChiOpcode.Req.Evict
          // Drop clean permission before Evict; its address survives retries separately.
          cacheValid(localCacheIndex) := false.B
          cacheStates(localCacheIndex) := RnCacheState.I
        }
        retryAckSeen := false.B
        creditHeld := false.B
        retried := false.B
        state := sendReq
      }
    } else {
      request := io.request.bits
      requestOpcode := Mux(io.request.bits.write, ChiOpcode.Req.WriteNoSnpFull,
        ChiOpcode.Req.ReadNoSnp)
      retryAckSeen := false.B
      creditHeld := false.B
      retried := false.B
      state := sendReq
    }
  }

  port.reqTx.valid := state === sendReq
  port.reqTx.bits := 0.U.asTypeOf(port.reqTx.bits)
  port.reqTx.bits.tgtID := outer.params.hnId.U
  port.reqTx.bits.srcID := outer.params.nodeId.U
  port.reqTx.bits.txnID := request.txnID
  port.reqTx.bits.opcode := requestOpcode
  port.reqTx.bits.addr := request.addr
  if (outer.params.coherent) {
    port.reqTx.bits.addr := Mux(requestOpcode === ChiOpcode.Req.Evict, victimAddress, request.addr)
  }
  port.reqTx.bits.allowRetry := !retryReady
  when(port.reqTx.fire) {
    when(retryReady) {
      retryAckSeen := false.B
      creditHeld := false.B
      retried := true.B
    }
    if (outer.params.coherent) {
      state := Mux(requestOpcode === ChiOpcode.Req.Evict, waitComp, waitData)
    } else {
      state := Mux(request.write, waitDbId, waitData)
    }
  }

  // RSP takes priority if data and a retry response arrive together.
  port.datRx.ready := state === waitData && !retryAckSeen && !creditHeld && !port.rspRx.valid
  when(port.datRx.fire) {
    val dat = port.datRx.bits
    if (outer.params.coherent) {
      assert(dat.tgtID === outer.params.nodeId.U && dat.srcID === outer.params.hnId.U &&
        dat.txnID === request.txnID, "RN received data for another transaction")
      assert(dat.opcode === ChiOpcode.Dat.CompData &&
        (dat.resp === ChiResp.SC || dat.resp === ChiResp.UC),
        "RN expects coherent CompData")
      assert(!request.write || dat.resp === ChiResp.UC,
        "RN ReadUnique requires unique permission")
      completionDbId := dat.dbID
      cacheValid(requestCacheIndex) := true.B
      cacheTags(requestCacheIndex) := requestCacheTag
      when(request.write) {
        cacheData(requestCacheIndex) := request.data
        cacheStates(requestCacheIndex) := RnCacheState.UD
        result.data := 0.U
      }.otherwise {
        cacheData(requestCacheIndex) := dat.data
        cacheStates(requestCacheIndex) := Mux(dat.resp === ChiResp.SC,
          RnCacheState.SC, RnCacheState.UC)
        result.data := dat.data
      }
      result.txnID := request.txnID
      state := sendCompAck
    } else {
      assert(dat.tgtID === outer.params.nodeId.U && dat.srcID === outer.params.hnId.U &&
        dat.txnID === request.txnID, "RN received data for another transaction")
      assert(dat.opcode === ChiOpcode.Dat.CompData && dat.resp === ChiResp.I && dat.dbID === 0.U,
        "RN expects non-coherent CompData")
      result.txnID := request.txnID
      result.data := dat.data
      state := returnResult
    }
  }

  port.rspRx.ready := waitingForReply && !retryReady
  when(port.rspRx.fire) {
    val rsp = port.rspRx.bits
    assert(rsp.tgtID === outer.params.nodeId.U && rsp.srcID === outer.params.hnId.U,
      "RN received a response for another transaction")
    when(rsp.opcode === ChiOpcode.Rsp.RetryAck || rsp.opcode === ChiOpcode.Rsp.PCrdGrant) {
      assert(!retried, "RN cannot retry an already credited request")
      assert(rsp.resp === ChiResp.I && rsp.pCrdType === 0.U, "RN expects retry credit type zero")
      when(rsp.opcode === ChiOpcode.Rsp.RetryAck) {
        assert(rsp.txnID === request.txnID, "RN received RetryAck for another transaction")
        assert(rsp.dbID === 0.U, "RN expects zero RetryAck DBID")
        assert(!retryAckSeen, "RN received duplicate RetryAck")
        retryAckSeen := true.B
      }.otherwise {
        assert(rsp.txnID === 0.U && rsp.dbID === 0.U, "RN expects zero PCrdGrant IDs")
        assert(!creditHeld, "RN received duplicate PCrdGrant")
        creditHeld := true.B
      }
    }.otherwise {
      if (outer.params.coherent) {
        assert(state === waitComp && !retryAckSeen && !creditHeld,
          "RN expects Evict completion without a pending retry")
        assert(rsp.txnID === request.txnID, "RN received a response for another transaction")
        assert(rsp.opcode === ChiOpcode.Rsp.Comp && rsp.resp === ChiResp.I &&
          rsp.dbID === 0.U && rsp.pCrdType === 0.U, "RN expects Evict Comp with zero response fields")
        // Evict and the following read are separate requests, each with its own retry credit.
        requestOpcode := Mux(request.write, ChiOpcode.Req.ReadUnique, ChiOpcode.Req.ReadShared)
        retryAckSeen := false.B
        creditHeld := false.B
        retried := false.B
        state := sendReq
      } else {
      assert(rsp.txnID === request.txnID, "RN received a response for another transaction")
      assert(state === waitDbId && !retryAckSeen && !creditHeld,
        "RN cannot complete a request awaiting retry")
      assert(rsp.opcode === ChiOpcode.Rsp.CompDBIDResp && rsp.resp === ChiResp.I &&
        rsp.pCrdType === 0.U, "RN expects non-coherent CompDBIDResp")
      writeDbId := rsp.dbID
      state := sendWriteData
      }
    }
  }
  when(waitingForReply && retryReady) { state := sendReq }

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

  port.rspTx.valid := (if (outer.params.coherent) state === sendCompAck else false.B)
  port.rspTx.bits := 0.U.asTypeOf(port.rspTx.bits)
  port.rspTx.bits.tgtID := outer.params.hnId.U
  port.rspTx.bits.srcID := outer.params.nodeId.U
  port.rspTx.bits.txnID := completionDbId
  port.rspTx.bits.opcode := ChiOpcode.Rsp.CompAck
  port.rspTx.bits.resp := ChiResp.I
  when(port.rspTx.fire) {
    state := returnResult
  }

  io.response.valid := state === returnResult
  io.response.bits := result
  when(io.response.fire) { state := idle }

  port.snpTx.valid := false.B
  port.snpTx.bits := 0.U.asTypeOf(port.snpTx.bits)
  port.reqRx.ready := false.B
  port.snpRx.ready := false.B
}
