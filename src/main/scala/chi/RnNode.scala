package chi

import chisel3._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy.nodes._

/** RN endpoint shell; external drivers supply flits until its transaction logic is added. */
class RnNode(val nodeId: Int)(implicit p: Parameters) extends LazyModule {
  val node = new SourceNode(ChiNodeImp)(Seq(ChiEndpointParams(nodeId, ChiRole.RN)))
  lazy val module = new RnNodeModule(this)
}

class RnNodeModule(outer: RnNode) extends LazyModuleImp(outer) {
  private val (port, edge) = outer.node.out.head
  val io = IO(Flipped(new ChiPort(edge.network.chi)))
  port <> io
}
