package noc

import chisel3.util.log2Ceil

/** Shared parameters for one ring. */
case class RingParams(nodeCount: Int = 4) {
  require(nodeCount >= 2, "a ring must contain at least two nodes")

  val dstWidth: Int = log2Ceil(nodeCount)
}
