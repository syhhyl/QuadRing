package chi

import circt.stage.ChiselStage
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.LazyModule

object ChiSystemMain {
  def main(args: Array[String]): Unit = {
    implicit val p: Parameters = Parameters.empty
    ChiselStage.emitSystemVerilogFile(LazyModule(new ChiSystem(ChiSystemParams())).module, args)
  }
}
