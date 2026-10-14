package chi

import chisel3._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._

case class ChiSystemParams(network: ChiNoCParams = ChiNoCParams(),
  rn0Id: Int = 0, hnId: Int = 1, rn1Id: Int = 2, snId: Int = 3) {
  require(network.ring.nodeCount == 4, "CHI system requires four ring stops")
  require(Seq(rn0Id, hnId, rn1Id, snId).forall(_ >= 0), "node IDs must be nonnegative")
}

/** Four endpoint shells at ring positions RN0 -> HN -> RN1 -> SN -> RN0. */
class ChiSystem(val params: ChiSystemParams)(implicit p: Parameters) extends LazyModule {
  val network = LazyModule(new ChiNoC(params.network))
  val rn0 = LazyModule(new RnNode(params.rn0Id))
  val hn = LazyModule(new HnNode(params.hnId))
  val rn1 = LazyModule(new RnNode(params.rn1Id))
  val sn = LazyModule(new SnNode(params.snId))

  // Connection order fixes ring positions; node IDs are negotiated independently.
  network.node := rn0.node
  network.node := hn.node
  network.node := rn1.node
  network.node := sn.node

  lazy val module = new ChiSystemModule(this)
}

class ChiSystemModule(outer: ChiSystem) extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val rn0 = Flipped(new ChiPort(outer.params.network.chi))
    val hn = Flipped(new ChiPort(outer.params.network.chi))
    val rn1 = Flipped(new ChiPort(outer.params.network.chi))
    val sn = Flipped(new ChiPort(outer.params.network.chi))
  })
  io.rn0 <> outer.rn0.module.io
  io.hn <> outer.hn.module.io
  io.rn1 <> outer.rn1.module.io
  io.sn <> outer.sn.module.io
}
