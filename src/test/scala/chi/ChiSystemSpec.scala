package chi

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.DecoupledIO
import circt.stage.ChiselStage
import noc.RingParams
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.util.Random

class ChiSystemSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private implicit val p: Parameters = Parameters.empty
  private val configs = Seq(
    ChiSystemParams(),
    ChiSystemParams(ChiNoCParams(ChiParams(3, 7, 32), RingParams(4, 5)), 6, 1, 4, 0)
  )
  private val runCycles = sys.env.get("QUADRING_LONG_TESTS") match {
    case None | Some("0") => 5000
    case Some("1") => 100000
    case Some(value) => throw new IllegalArgumentException(s"QUADRING_LONG_TESTS must be 0 or 1, got '$value'")
  }

  private def ids(params: ChiSystemParams): Seq[Int] =
    Seq(params.rn0Id, params.hnId, params.rn1Id, params.snId)

  private def ports(dut: ChiSystemModule): Seq[ChiPort] =
    Seq(dut.io.rn0, dut.io.hn, dut.io.rn1, dut.io.sn)

  private case class Channel(tx: DecoupledIO[_ <: Bundle], rx: DecoupledIO[_ <: Bundle],
    payload: Bundle, destination: UInt)

  private def channels(dut: ChiSystemModule): Seq[Seq[Channel]] = Seq(
    ports(dut).map(port => Channel(port.reqTx, port.reqRx, port.reqTx.bits, port.reqTx.bits.tgtID)),
    ports(dut).map(port => Channel(port.rspTx, port.rspRx, port.rspTx.bits, port.rspTx.bits.tgtID)),
    ports(dut).map(port => Channel(port.snpTx, port.snpRx, port.snpTx.bits.payload, port.snpTx.bits.dst)),
    ports(dut).map(port => Channel(port.datTx, port.datRx, port.datTx.bits, port.datTx.bits.tgtID))
  )

  private def pokeFields(payload: Bundle, values: Map[String, BigInt]): Unit = {
    payload.elements.keySet shouldBe values.keySet
    for ((name, value) <- values) {
      payload.elements(name) match {
        case field: Bool => field.poke(value != 0)
        case field: UInt => field.poke(value)
      }
    }
  }

  private def peekFields(payload: Bundle): Map[String, BigInt] =
    payload.elements.map { case (name, field: UInt) => name -> field.peekValue().asBigInt }.toMap

  private def initialize(all: Seq[Seq[Channel]]): Unit = {
    for (channel <- all; port <- channel) {
      port.tx.valid.poke(false)
      port.rx.ready.poke(false)
      pokeFields(port.payload, port.payload.elements.keys.map(_ -> BigInt(0)).toMap)
      port.destination.poke(0)
    }
  }

  private case class Message(dst: Int, fields: Map[String, BigInt])

  // These are transport-test patterns, including arbitrary opcode/Resp bits on RSP.
  private def message(params: ChiSystemParams, channel: Int, src: Int, dst: Int, tag: Int): Message = {
    val chi = params.network.chi
    val txnMask = (BigInt(1) << chi.txnIdWidth) - 1
    val txn = BigInt(tag) & txnMask
    val address = (BigInt(1) << (chi.addrWidth - 1)) | (BigInt(tag) << 6)
    val fields: Map[String, BigInt] = channel match {
      case 0 => Map("srcID" -> ids(params)(src), "tgtID" -> ids(params)(dst), "txnID" -> txn,
        "opcode" -> 4, "addr" -> address, "allowRetry" -> (tag % 2), "pCrdType" -> 0)
      case 1 => Map("srcID" -> ids(params)(src), "tgtID" -> ids(params)(dst), "txnID" -> txn,
        "dbID" -> ((BigInt(tag) >> chi.txnIdWidth) & txnMask),
        "pCrdType" -> ((BigInt(tag) >> (2 * chi.txnIdWidth)) & 15),
        "resp" -> ((BigInt(tag) >> (2 * chi.txnIdWidth + 4)) & 7),
        "opcode" -> ((BigInt(tag) >> (2 * chi.txnIdWidth + 7)) & 15))
      case 2 => Map("srcID" -> ids(params)(src), "txnID" -> txn, "opcode" -> 1, "addr" -> address)
      case 3 => Map("srcID" -> ids(params)(src), "tgtID" -> ids(params)(dst), "txnID" -> txn,
        "opcode" -> 4, "resp" -> 6, "dbID" -> txn,
        "data" -> ((BigInt(1) << 511) | (BigInt(tag) << 256) | 0x12345678))
    }
    Message(dst, fields)
  }

  "four endpoint modules negotiate IDs, fixed roles, widths, and port directions" in {
    for (params <- configs) {
      ChiselStage.emitCHIRRTL {
        val top = LazyModule(new ChiSystem(params))
        val dut = top.module
        val expected = ids(params).zip(Seq(ChiRole.RN, ChiRole.HN, ChiRole.RN, ChiRole.SN))
          .map { case (id, role) => ChiEndpointParams(id, role) }
        top.network.node.edges.in.map(_.endpoint) shouldBe expected
        val endpointNodes = Seq(top.rn0.node, top.hn.node, top.rn1.node, top.sn.node)
        endpointNodes.map(_.edges.out.head.endpoint) shouldBe expected
        endpointNodes.map(_.edges.out.head.network) shouldBe Seq.fill(4)(params.network)
        top.network.node.edges.in.map(_.network) shouldBe Seq.fill(4)(params.network)
        val localPorts = Seq(top.rn0.module.io, top.hn.module.io, top.rn1.module.io, top.sn.module.io)
        for (port <- ports(dut) ++ localPorts) {
          for (tx <- Seq(port.reqTx, port.rspTx, port.snpTx, port.datTx)) {
            DataMirror.directionOf(tx.valid) shouldBe ActualDirection.Input
            DataMirror.directionOf(tx.ready) shouldBe ActualDirection.Output
          }
          for (rx <- Seq(port.reqRx, port.rspRx, port.snpRx, port.datRx)) {
            DataMirror.directionOf(rx.valid) shouldBe ActualDirection.Output
            DataMirror.directionOf(rx.ready) shouldBe ActualDirection.Input
          }
          port.reqTx.bits.tgtID.getWidth shouldBe params.network.chi.nodeIdWidth
          port.reqRx.bits.txnID.getWidth shouldBe params.network.chi.txnIdWidth
          port.snpTx.bits.dst.getWidth shouldBe params.network.chi.nodeIdWidth
          port.snpRx.bits.addr.getWidth shouldBe params.network.chi.addrWidth
          port.datRx.bits.data.getWidth shouldBe 512
        }
        dut
      }
    }
  }

  "the assembled system requires four ring stops" in {
    an[IllegalArgumentException] should be thrownBy ChiSystemParams(ChiNoCParams(ring = RingParams(3)))
  }

  "negative IDs are rejected before graph construction" in {
    for ((rn0, hn, rn1, sn) <- Seq((-1, 1, 2, 3), (0, -1, 2, 3),
      (0, 1, -1, 3), (0, 1, 2, -1))) {
      an[IllegalArgumentException] should be thrownBy {
        ChiSystemParams(rn0Id = rn0, hnId = hn, rn1Id = rn1, snId = sn)
      }
    }
  }

  "duplicate endpoint IDs are rejected by the assembled network" in {
    val error = intercept[IllegalArgumentException] {
      ChiselStage.emitCHIRRTL(LazyModule(new ChiSystem(ChiSystemParams(snId = 0))).module)
    }
    error.getMessage should include("unique")
  }

  "endpoint IDs must fit the negotiated width" in {
    val error = intercept[IllegalArgumentException] {
      ChiselStage.emitCHIRRTL(LazyModule(new ChiSystem(ChiSystemParams(hnId = 4))).module)
    }
    error.getMessage should include("negotiated width")
  }

  for ((params, configIndex) <- configs.zipWithIndex) {
    s"all channels route randomized traffic and recover from blockage with IDs ${ids(params)}" in {
      simulate(LazyModule(new ChiSystem(params)).module) { dut =>
        val all = channels(dut)
        initialize(all)
        val random = new Random(0x5eedL + configIndex)
        val offers = Array.fill[Option[Message]](4, 4)(None)
        val stalled = Array.fill[Option[Map[String, BigInt]]](4, 4)(None)
        val pending = Seq.fill(4)(mutable.Map.empty[Map[String, BigInt], Int])
        val routes = Seq.fill(4)(mutable.Set.empty[(Int, Int)])
        val accepted = Array.fill(4)(0)
        val delivered = Array.fill(4)(0)
        val nextTag = Array.fill(4)(1)
        val idleCycles = Array.fill(4)(0)
        val injectionStalled = Array.fill(4)(false)
        var sawFullBlockage = false
        var cycle = 0

        def tick(generate: Boolean, blockedChannel: Option[Int] = None, draining: Boolean = false): Unit = {
          val blockAll = !draining && blockedChannel.isEmpty && cycle % 1024 >= 768 && cycle % 1024 < 800
          sawFullBlockage ||= blockAll
          val ready = Array.tabulate(4, 4) { (channel, _) =>
            !blockAll && !blockedChannel.contains(channel) &&
              (draining || cycle % 16 == 0 || random.nextBoolean())
          }
          for ((channelPorts, channel) <- all.zipWithIndex; (port, src) <- channelPorts.zipWithIndex) {
            if (generate && offers(channel)(src).isEmpty && random.nextDouble() < 0.75) {
              val flit = message(params, channel, src, random.nextInt(4), nextTag(channel))
              nextTag(channel) += 1
              offers(channel)(src) = Some(flit)
              pokeFields(port.payload, flit.fields)
              port.destination.poke(ids(params)(flit.dst))
            }
            port.tx.valid.poke(offers(channel)(src).nonEmpty)
            port.rx.ready.poke(ready(channel)(src))
          }

          val progressed = Array.fill(4)(false)
          for ((channelPorts, channel) <- all.zipWithIndex; (port, src) <- channelPorts.zipWithIndex) {
            offers(channel)(src).foreach { flit =>
              if (port.tx.ready.peekBoolean()) {
                assert(!pending(channel).contains(flit.fields), s"cycle $cycle: repeated test tag")
                pending(channel)(flit.fields) = flit.dst
                routes(channel) += ((src, flit.dst))
                offers(channel)(src) = None
                accepted(channel) += 1
                progressed(channel) = true
              } else injectionStalled(channel) = true
            }
          }
          for ((channelPorts, channel) <- all.zipWithIndex; (port, dst) <- channelPorts.zipWithIndex) {
            val current = if (port.rx.valid.peekBoolean()) Some(peekFields(port.rx.bits)) else None
            stalled(channel)(dst).foreach { previous =>
              assert(current.contains(previous), s"cycle $cycle channel $channel: blocked output changed")
            }
            current.foreach { fields =>
              assert(pending(channel).get(fields).contains(dst),
                s"cycle $cycle channel $channel: corrupt, duplicate, or misrouted message at $dst")
              if (ready(channel)(dst)) {
                pending(channel).remove(fields)
                delivered(channel) += 1
                progressed(channel) = true
              }
            }
            stalled(channel)(dst) = if (ready(channel)(dst)) None else current
          }
          for (channel <- 0 until 4) {
            idleCycles(channel) = if (!blockAll && !blockedChannel.contains(channel) &&
              pending(channel).nonEmpty && !progressed(channel)) idleCycles(channel) + 1 else 0
            assert(idleCycles(channel) < 256, s"cycle $cycle channel $channel: no progress")
            assert(pending(channel).size < 4 * params.network.ring.bufferDepth,
              s"cycle $cycle channel $channel: bubble exhausted")
          }
          dut.clock.step()
          cycle += 1
        }

        // Each ring is completely blocked while the other three continue delivering.
        for (blocked <- 0 until 4) {
          val blockedBefore = delivered(blocked)
          for (_ <- 0 until 4) {
            val before = delivered.clone()
            for (_ <- 0 until 64) tick(generate = true, blockedChannel = Some(blocked))
            for (channel <- 0 until 4 if channel != blocked) delivered(channel) should be > before(channel)
          }
          delivered(blocked) shouldBe blockedBefore
          pending(blocked) should not be empty
          injectionStalled(blocked) shouldBe true
        }

        for (_ <- 0 until runCycles) tick(generate = true)
        sawFullBlockage shouldBe true
        for (channel <- 0 until 4) routes(channel).size shouldBe 16

        var drainCycles = 0
        while ((pending.exists(_.nonEmpty) || offers.exists(_.exists(_.nonEmpty))) && drainCycles < 512) {
          tick(generate = false, draining = true)
          drainCycles += 1
        }
        pending.foreach(_ shouldBe empty)
        offers.forall(_.forall(_.isEmpty)) shouldBe true
        accepted.toSeq shouldBe delivered.toSeq
        for (_ <- 0 until 4 * params.network.ring.bufferDepth * 2) tick(generate = false, draining = true)
      }
    }
  }

  "reset clears blocked traffic and all four endpoint channels accept new messages" in {
    val params = ChiSystemParams()
    simulate(LazyModule(new ChiSystem(params)).module) { dut =>
      val all = channels(dut)
      initialize(all)
      def send(tag: Int): Seq[Message] = {
        val messages = (0 until 4).map(channel => message(params, channel, 0, 1, tag))
        for ((channelPorts, channel) <- all.zipWithIndex) {
          val source = channelPorts.head
          pokeFields(source.payload, messages(channel).fields)
          source.destination.poke(params.hnId)
          source.tx.valid.poke(true)
          source.tx.ready.expect(true.B)
        }
        dut.clock.step()
        all.foreach(_.head.tx.valid.poke(false))
        messages
      }
      send(1)
      dut.clock.step(6)
      all.foreach(_(1).rx.valid.expect(true.B))
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      for (channel <- all; port <- channel) {
        port.rx.valid.expect(false.B)
        port.rx.ready.poke(true)
      }
      val expected = send(2)
      val delivered = Array.fill(4)(0)
      for (_ <- 0 until 24) {
        for ((channelPorts, channel) <- all.zipWithIndex; (port, dst) <- channelPorts.zipWithIndex) {
          if (port.rx.valid.peekBoolean()) {
            dst shouldBe 1
            peekFields(port.rx.bits) shouldBe expected(channel).fields
            delivered(channel) += 1
          }
        }
        dut.clock.step()
      }
      delivered.toSeq shouldBe Seq(1, 1, 1, 1)
    }
  }
}
