package chi

import chisel3._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._

case class ChiSystemParams(network: ChiNoCParams = ChiNoCParams(),
  rn0Id: Int = 0, hnId: Int = 1, rn1Id: Int = 2, snId: Int = 3,
  initialLines: Seq[BigInt] = Seq.fill(4)(BigInt(0))) {
  require(network.ring.nodeCount == 4, "CHI system requires four ring stops")
  require(Seq(rn0Id, hnId, rn1Id, snId).forall(_ >= 0), "node IDs must be nonnegative")
  // Validate memory initialization before starting a Diplomacy construction scope.
  SnParams(snId, initialLines)
}

/** Serial non-coherent reads at ring positions RN0 -> HN -> RN1 -> SN -> RN0. */
class ChiSystem(val params: ChiSystemParams)(implicit p: Parameters) extends LazyModule {
  val network = LazyModule(new ChiNoC(params.network))
  val rn0 = LazyModule(new RnNode(RnParams(params.rn0Id, params.hnId)))
  val hn = LazyModule(new HnNode(HnParams(params.hnId, params.snId)))
  val rn1 = LazyModule(new RnNode(RnParams(params.rn1Id, params.hnId)))
  val sn = LazyModule(new SnNode(SnParams(params.snId, params.initialLines)))

  // Connection order fixes ring positions; node IDs are negotiated independently.
  network.node := rn0.node
  network.node := hn.node
  network.node := rn1.node
  network.node := sn.node

  lazy val module = new ChiSystemModule(this)
}

class ChiSystemModule(outer: ChiSystem) extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val rn0 = new RnPort(outer.params.network.chi)
    val rn1 = new RnPort(outer.params.network.chi)
  })
  io.rn0 <> outer.rn0.module.io
  io.rn1 <> outer.rn1.module.io
}
