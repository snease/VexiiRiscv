package vexiiriscv.test

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin
import vexiiriscv.Global
import vexiiriscv.decode.{Decode, DecodePipelinePlugin, DecoderPlugin}
import vexiiriscv.execute._
import vexiiriscv.fetch.{Fetch, FetchPipelinePlugin}
import vexiiriscv.misc.PipelineBuilderPlugin
import vexiiriscv.prediction.LearnCmd
import vexiiriscv.regfile.{RegFileWrite, RegFileWriter, RegFileWriterService}
import vexiiriscv.riscv.{Const, Riscv}
import vexiiriscv.schedule.{DispatchPlugin, FlushCmd, ReschedulePlugin}

class WhiteboxerPlugin extends FiberPlugin{
  buildBefore(host[PipelineBuilderPlugin].elaborationLock)

  val logic = during build new Logic()
  class Logic extends Area{
    def wrap[T <: Data](that: T): T = CombInit(that).simPublic

    val fpp = host[FetchPipelinePlugin]
    val dpp = host[DecodePipelinePlugin]
    val fetch = new Area {
      val c = fpp.fetch(0)
      val fire = wrap(c.down.isFiring)
      val hartId = wrap(c(Global.HART_ID))
      val fetchId = wrap(c(Fetch.ID))
    }


    val decodes = for (laneId <- 0 until Decode.LANES) yield new Area {
      val c = dpp.ctrl(0).lane(laneId)
      val fire = wrap(c.down.isFiring)
      val hartId = wrap(c(Global.HART_ID))
      val pc = wrap(c(Global.PC))
      val fetchId = wrap(c(Fetch.ID))
      val decodeId = wrap(c(Decode.DOP_ID))
    }

    val serializeds = for (laneId <- 0 until Decode.LANES) yield new Area {
      val decodeAt = host[DecoderPlugin].decodeAt
      val c = dpp.ctrl(decodeAt).lane(laneId)

      host[DecoderPlugin].logic.await()
      val fire = wrap(c.up.transactionSpawn)
      val hartId = wrap(c(Global.HART_ID))
      val decodeId = wrap(c(Decode.DOP_ID))
      val microOpId = wrap(c(Decode.UOP_ID))
      val microOp = wrap(c(Decode.UOP))
    }

    val dispatches = for (eu <- host.list[ExecuteLaneService]) yield new Area {
      val c = eu.ctrl(0)
      val fire = wrap(c.down.isFiring)
      val hartId = wrap(c(Global.HART_ID))
      val microOpId = wrap(c(Decode.UOP_ID))
    }


    val executes = for (eu <- host.list[ExecuteLaneService]) yield new Area {
      val c = eu.ctrl(eu.executeAt)
      val fire = wrap(c.down.transactionSpawn)
      val hartId = wrap(c(Global.HART_ID))
      val microOpId = wrap(c(Decode.UOP_ID))
    }

    val csr = new Area {
      val p = host[CsrAccessPlugin].logic
      val port = Verilator.public(Flow(new Bundle {
        val hartId = Global.HART_ID()
        val uopId = Decode.UOP_ID()
        val address = UInt(12 bits)
        val write = Bits(Riscv.XLEN bits)
        val read = Bits(Riscv.XLEN bits)
        val writeDone = Bool()
        val readDone = Bool()
      }))
      port.valid := p.fsm.regs.fire
      port.uopId := p.fsm.regs.uopId
      port.hartId := p.fsm.regs.hartId
      port.address := U(p.fsm.regs.uop)(Const.csrRange)
      port.write := p.fsm.regs.onWriteBits
      port.read := p.fsm.regs.csrValue
      port.writeDone := p.fsm.regs.write
      port.readDone := p.fsm.regs.read
    }

    val rfWrites = new Area {
      val ports = host.list[RegFileWriterService].flatMap(_.getRegFileWriters()).map(wrap)
    }

    val completions = new Area {
      val ports = host.list[CompletionService].flatMap(cp => cp.getCompletions().map(wrap))
    }

    val reschedules = new Area {
      val rp = host[ReschedulePlugin]
      rp.elaborationLock.await()
      val flushes = rp.flushPorts.map(wrap)
    }

    val prediction = new Area{
      val bp = host[BranchPlugin]
      val learn = wrap(bp.logic.jumpLogic.learn)
    }

    val loadExecute = new Area {
      val fire = Bool()
      val hartId = Global.HART_ID()
      val uopId = Decode.UOP_ID()
      val size = UInt(2 bits)
      val address = Global.PHYSICAL_ADDRESS()
      val data = Bits(Riscv.LSLEN bits)

      SimPublic(fire, hartId, uopId, size, address, data)

      val lcp = host.get[LsuCachelessPlugin] map (p => new Area {
        val c = p.logic.wbCtrl
        fire := c.down.isFiring && c(AguPlugin.SEL) && c(AguPlugin.LOAD) && !c(p.logic.onAddress.translationPort.keys.IO)
        hartId := c(Global.HART_ID)
        uopId := c(Decode.UOP_ID)
        size := c(AguPlugin.SIZE).resized
        address := c(SrcStageables.ADD_SUB).asUInt
        data := host.find[IntFormatPlugin](_.laneName == p.laneName).logic.stages.find(_.ctrlLink == c.ctrlLink).get.wb.payload
      })
    }

    val storeCommit = new Area {
      val fire = Bool()
      val hartId = Global.HART_ID()
      val uopId = Decode.UOP_ID()
      val size = UInt(2 bits)
      val address = Global.PHYSICAL_ADDRESS()
      val data = Bits(Riscv.LSLEN bits)
      SimPublic(fire, hartId, uopId, size, address, data)

      val lcp = host.get[LsuCachelessPlugin] map (p => new Area {
        val c = p.logic.forkCtrl
        val bus = p.logic.bus
        fire := bus.cmd.fire && bus.cmd.write && !bus.cmd.io
        hartId := c(Global.HART_ID)
        uopId := c(Decode.UOP_ID)
        size := bus.cmd.size.resized
        address := bus.cmd.address
        data := bus.cmd.data
      })
    }

    val storeBroadcast = new Area {
      val fire = Bool()
      val hartId = Global.HART_ID()
      val uopId = Decode.UOP_ID()
      SimPublic(fire, hartId, uopId)

      val lcp = host.get[LsuCachelessPlugin] map (p => new Area {
        val c = p.logic.joinCtrl
        fire := c.down.isFiring && c(AguPlugin.SEL) && !c(AguPlugin.LOAD)
        hartId := c(Global.HART_ID)
        uopId := c(Decode.UOP_ID)
      })
    }


    def self = this
    class Proxies {
      val fetch = new FetchProxy()
      val decodes = self.decodes.indices.map(new DecodeProxy(_)).toArray
      val serializeds = self.serializeds.indices.map(new SerializedProxy(_)).toArray
      val dispatches = self.dispatches.indices.map(new DispatchProxy(_)).toArray
      val executes = self.executes.indices.map(new ExecuteProxy(_)).toArray
      val csr = new CsrProxy()
      val rfWrites = self.rfWrites.ports.map(new RfWriteProxy(_)).toArray
      val completions = self.completions.ports.map(new CompletionProxy(_)).toArray
      val flushes = self.reschedules.flushes.map(new FlushProxy(_)).toArray
      val loadExecute = new LoadExecuteProxy()
      val storeCommit = new StoreCommitProxy()
      val storeBroadcast = new storeBroadcastProxy()
      val learn = new LearnProxy(self.prediction.learn)
    }

    class FetchProxy {
      val fire = fetch.fire.simProxy()
      val hartd = fetch.hartId.simProxy()
      val id = fetch.fetchId.simProxy()
    }

    class DecodeProxy(laneId: Int) {
      val self = decodes(laneId)
      val fire = self.fire.simProxy()
      val hartId = self.hartId.simProxy()
      val pc = self.pc.simProxy()
      val fetchId = self.fetchId.simProxy()
      val decodeId = self.decodeId.simProxy()
    }

    class SerializedProxy(laneId: Int) {
      val self = serializeds(laneId)
      val fire = self.fire.simProxy()
      val hartId = self.hartId.simProxy()
      val decodeId = self.decodeId.simProxy()
      val microOpId = self.microOpId.simProxy()
      val microOp = self.microOp.simProxy()
    }

    class DispatchProxy(laneId: Int) {
      val self = dispatches(laneId)
      val fire = self.fire.simProxy()
      val hartId = self.hartId.simProxy()
      val microOpId = self.microOpId.simProxy()
    }

    class ExecuteProxy(laneId: Int) {
      val self = executes(laneId)
      val fire = self.fire.simProxy()
      val hartId = self.hartId.simProxy()
      val microOpId = self.microOpId.simProxy()
    }


    class CsrProxy{
      val valid = self.csr.port.valid.simProxy()
      val hartId = self.csr.port.hartId.simProxy()
      val uopId = self.csr.port.uopId.simProxy()
      val address = self.csr.port.address.simProxy()
      val write = self.csr.port.write.simProxy()
      val read = self.csr.port.read.simProxy()
      val writeDone = self.csr.port.writeDone.simProxy()
      val readDone = self.csr.port.readDone.simProxy()
    }

    class RfWriteProxy(val port : Flow[RegFileWriter]) {
      val valid = port.valid.simProxy()
      val data = port.data.simProxy()
      val hartId = port.hartId.simProxy()
      val uopId = port.uopId.simProxy()
    }

    class CompletionProxy(port: Flow[CompletionPayload]) {
      val valid = port.valid.simProxy()
      val hartId = port.hartId.simProxy()
      val uopId = port.uopId.simProxy()
    }

    class FlushProxy(port: Flow[FlushCmd]) {
      val withUopId = port.withUopId
      val valid = port.valid.simProxy()
      val hartId = port.hartId.simProxy()
      val uopId = port.withUopId generate port.uopId.simProxy()
      val laneAge = port.laneAge.simProxy()
      val self = port.self.simProxy()
    }

    class LoadExecuteProxy {
      val fire = loadExecute.fire.simProxy()
      val hartId = loadExecute.hartId.simProxy()
      val uopId = loadExecute.uopId.simProxy()
      val size = loadExecute.size.simProxy()
      val address = loadExecute.address.simProxy()
      val data = loadExecute.data.simProxy()
    }

    class StoreCommitProxy {
      val fire = storeCommit.fire.simProxy()
      val hartId = storeCommit.hartId.simProxy()
      val uopId = storeCommit.uopId.simProxy()
      val size = storeCommit.size.simProxy()
      val address = storeCommit.address.simProxy()
      val data = storeCommit.data.simProxy()
    }

    class storeBroadcastProxy {
      val fire = storeCommit.fire.simProxy()
      val hartId = storeBroadcast.hartId.simProxy()
      val uopId = storeBroadcast.uopId.simProxy()
    }

    class LearnProxy(port: Flow[LearnCmd]) {
      val valid = port.valid.simProxy()
      val pcOnLastSlice = port.pcOnLastSlice.simProxy()
      val pcTarget = port.pcTarget.simProxy()
      val taken = port.taken.simProxy()
      val isBranch = port.isBranch.simProxy()
      val wasWrong = port.wasWrong.simProxy()
      val history = port.history.simProxy()
      val uopId = port.uopId.simProxy()
      val hartId = port.hartId.simProxy()
    }
  }
}
