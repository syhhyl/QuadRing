package noc

import chisel3._
import chisel3.util._

/** Buffer ring input, eject local destinations, and prioritize forwards at ring output. */
class RingStop[T <: Data](params: RingParams, nodeId: Int, payloadType: T) extends Module {
  require(nodeId >= 0 && nodeId < params.nodeCount, "nodeId must be a valid ring position")

  val io = IO(new Bundle {
    val ringIn = Flipped(Decoupled(new RingFlit(params, payloadType)))
    val ringOut = Decoupled(new RingFlit(params, payloadType))
    val local = new RingPort(params, payloadType)
  })

  // Only ring input enters the FIFO. Local injection waits outside this buffer.
  val buffer = Module(new Queue(new RingFlit(params, payloadType), entries = params.bufferDepth))
  buffer.io.enq <> io.ringIn

  // Only the FIFO head can leave; backpressure preserves its contents and order.
  val isLocal = buffer.io.deq.bits.dst === nodeId.U
  io.local.eject.valid := buffer.io.deq.valid && isLocal
  io.local.eject.bits := buffer.io.deq.bits
  buffer.io.deq.ready := Mux(isLocal, io.local.eject.ready, io.ringOut.ready)

  // Arbitrate at ring output: a buffered forward wins over pending local injection.
  // Decoupled permits changing the selected source while stalled; only valid && ready transfers.
  val forward = buffer.io.deq.valid && !isLocal
  io.ringOut.valid := forward || io.local.inject.valid
  io.ringOut.bits := Mux(forward, buffer.io.deq.bits, io.local.inject.bits)
  io.local.inject.ready := io.ringOut.ready && !forward
}
