package vexriscv.plugin

import vexriscv.{VexRiscv, _}
import spinal.core._
import spinal.lib._

import scala.collection.mutable.ArrayBuffer

trait DBusAccessService{
  def newDBusAccess() : DBusAccess
}

case class DBusAccessCmd() extends Bundle {
  val address = UInt(32 bits)
  val size = UInt(2 bits)
  val write = Bool
  val data = Bits(32 bits)
  val writeMask = Bits(4 bits)
}

case class DBusAccessRsp() extends Bundle {
  val data = Bits(32 bits)
  val error = Bool()
  val redo = Bool()
}

case class DBusAccess() extends Bundle {
  val cmd = Stream(DBusAccessCmd())
  val rsp = Flow(DBusAccessRsp())
}


object MmuPort{
  val PRIORITY_DATA = 1
  val PRIORITY_INSTRUCTION = 0
}
case class MmuPort(bus : MemoryTranslatorBus, priority : Int, args : MmuPortConfig, id : Int)

case class MmuPortConfig(
  portTlbSize : Int,
  tlbWayCount : Int = 0,
  latency : Int = 0,
  earlyRequireMmuLockup : Boolean = false,
  earlyCacheHits : Boolean = false,
  enablePartitioning : Boolean = false,
  secureSetCount : Int = 0,
  setsPerSecureDomain : Int = 1,
  maxSecureDomains : Int = 0,
  allowNonSecureOnFreeSecureSets : Boolean = false
) {
  def effectiveWayCount : Int = if(tlbWayCount == 0) portTlbSize else tlbWayCount
  def setCount : Int = {
    if(tlbWayCount == 0) {
      1
    } else {
      require(portTlbSize % tlbWayCount == 0, "portTlbSize must be a multiple of tlbWayCount")
      val sets = portTlbSize / tlbWayCount
      require(isPow2(sets), "set count must be power of two")
      sets
    }
  }

  def partitionSecureSetCount : Int = {
    if(!enablePartitioning) {
      0
    } else {
      val candidate = if(secureSetCount == 0) setCount / 2 else secureSetCount
      require(candidate >= 1 && candidate < setCount, "secureSetCount must be in [1, setCount)")
      candidate
    }
  }

  def partitionNonSecureSetCount : Int = setCount - partitionSecureSetCount

  def partitionSetsPerSecureDomain : Int = {
    val value = if(setsPerSecureDomain <= 0) 1 else setsPerSecureDomain
    require(!enablePartitioning || (partitionSecureSetCount % value == 0), "secureSetCount must be a multiple of setsPerSecureDomain")
    value
  }

  def partitionDomainCapacity : Int = if(!enablePartitioning) 0 else partitionSecureSetCount / partitionSetsPerSecureDomain

  def partitionMaxSecureDomains : Int = {
    if(!enablePartitioning) {
      0
    } else if(maxSecureDomains == 0) {
      partitionDomainCapacity
    } else {
      require(maxSecureDomains <= partitionDomainCapacity, "maxSecureDomains must be <= partitionDomainCapacity")
      maxSecureDomains
    }
  }

  def partitionSidWidth : Int = log2Up(partitionMaxSecureDomains + 1) max 1
}

class MmuPlugin(var ioRange : UInt => Bool,
                virtualRange : UInt => Bool = address => True,
//                allowUserIo : Boolean = false,
                enableMmuInMachineMode : Boolean = false,
                exportSatp: Boolean = false
                ) extends Plugin[VexRiscv] with MemoryTranslator {

  var dBusAccess : DBusAccess = null
  val portsInfo = ArrayBuffer[MmuPort]()

  override def newTranslationPort(priority : Int,args : Any): MemoryTranslatorBus = {
    val config = args.asInstanceOf[MmuPortConfig]
    val port = MmuPort(MemoryTranslatorBus(MemoryTranslatorBusParameter(wayCount = config.effectiveWayCount, latency = config.latency)),priority, config, portsInfo.length)
    portsInfo += port
    port.bus
  }

  object IS_SFENCE_VMA2 extends Stageable(Bool)
  override def setup(pipeline: VexRiscv): Unit = {
    import Riscv._
    import pipeline.config._
    val decoderService = pipeline.service(classOf[DecoderService])
    decoderService.addDefault(IS_SFENCE_VMA2, False)
    decoderService.add(SFENCE_VMA, List(IS_SFENCE_VMA2 -> True))


    dBusAccess = pipeline.service(classOf[DBusAccessService]).newDBusAccess()
  }

  override def build(pipeline: VexRiscv): Unit = {
    import pipeline._
    import pipeline.config._
    import Riscv._
    val csrService = pipeline.service(classOf[CsrPlugin])

    //Sorted by priority
    val sortedPortsInfo = portsInfo.sortBy(_.priority)

    case class CacheLine() extends Bundle {
      val valid, exception, superPage = Bool
      val virtualAddress = Vec(UInt(10 bits), UInt(10 bits))
      val physicalAddress = Vec(UInt(10 bits), UInt(10 bits))
      val allowRead, allowWrite, allowExecute, allowUser = Bool

      def init = {
        valid init (False)
        this
      }
    }

    val csr = pipeline plug new Area{
      val partitionSidWidth = (sortedPortsInfo.filter(_.args.enablePartitioning).map(_.args.partitionSidWidth) :+ 1).max
      val status = new Area{
        val sum, mxr, mprv = RegInit(False)
        mprv clearWhen(csrService.xretAwayFromMachine)
      }
      val satp = new Area {
        val mode = RegInit(False)
        val asid = Reg(Bits(9 bits))
        val ppn = Reg(UInt(22 bits)) // Bottom 20 bits are used in implementation, but top 2 bits are still stored for OS use.
        if(exportSatp) {
          out(mode, asid, ppn)
        }
      }
      val partition = new Area {
        val currentSid = Reg(UInt(partitionSidWidth bits)) init(0)
        val allocSid = Reg(UInt(partitionSidWidth bits)) init(0)
        val freeSid = Reg(UInt(partitionSidWidth bits)) init(0)
        val flushSid = Reg(UInt(partitionSidWidth bits)) init(0)

        val allocTrigger = False
        val freeTrigger = False
        val flushSidTrigger = False
        val flushAllTrigger = False

        // status[0] = allocSuccess, status[1] = freeSuccess, status[2] = flushSidSuccess
        val status = Reg(Bits(32 bits)) init(0)
      }

      for(offset <- List(CSR.MSTATUS, CSR.SSTATUS)) csrService.rw(offset, 19 -> status.mxr, 18 -> status.sum, 17 -> status.mprv)
      csrService.rw(CSR.SATP, 31 -> satp.mode, 22 -> satp.asid, 0 -> satp.ppn)
      csrService.rw(CSR.TLB_SID, 0 -> partition.currentSid)
      csrService.rw(CSR.TLB_ALLOC_SID, 0 -> partition.allocSid)
      csrService.rw(CSR.TLB_FREE_SID, 0 -> partition.freeSid)
      csrService.rw(CSR.TLB_FLUSH_SID, 0 -> partition.flushSid)
      csrService.r(CSR.TLB_STATUS, 0 -> partition.status)
      csrService.onWrite(CSR.TLB_CMD){
        partition.allocTrigger := csrService.writeData()(0)
        partition.freeTrigger := csrService.writeData()(1)
        partition.flushSidTrigger := csrService.writeData()(2)
        partition.flushAllTrigger := csrService.writeData()(3)
      }
    }

    val core = pipeline plug new Area {
      val ports = for (port <- sortedPortsInfo) yield new Area {
        val handle = port
        val id = port.id
        val privilegeService = pipeline.serviceElse(classOf[PrivilegeService], PrivilegeServiceDefault())
        val wayCount = port.args.effectiveWayCount
        val setCount = port.args.setCount
        val setIndexWidth = log2Up(setCount) max 1
        val cache = Vec(Vec(Reg(CacheLine()) init, wayCount), setCount)
        val dirty = RegInit(False).allowUnsetRegToAvoidLatch
        if(port.args.earlyRequireMmuLockup){
          dirty clearWhen(!port.bus.cmd.last.isStuck)
        }

        val partitionEnabled = port.args.enablePartitioning
        val secureSetCount = port.args.partitionSecureSetCount
        val nonSecureSetCount = port.args.partitionNonSecureSetCount
        val secureBaseSet = nonSecureSetCount
        val setsPerSecureDomain = port.args.partitionSetsPerSecureDomain
        val maxSecureDomains = port.args.partitionMaxSecureDomains
        val sidWidth = port.args.partitionSidWidth

        case class PartitionEntry() extends Bundle {
          val sid = UInt(sidWidth bits)
          val allocated = Bool
        }
        val partitionTable = if(partitionEnabled) Vec(Reg(PartitionEntry()) init(PartitionEntry().getZero), secureSetCount) else null

        def setIndexFromVPage(vPage : UInt, count : Int): UInt = {
          val width = log2Up(count) max 1
          if(count == 1) {
            U(0, width bits)
          } else if(isPow2(count)) {
            vPage(log2Up(count) - 1 downto 0)
          } else {
            (vPage % U(count, width bits)).resized
          }
        }

        def setIndexFromVirtual(address : UInt, count : Int) : UInt = {
          setIndexFromVPage(address(31 downto 12), count)
        }

        def secureSetForSid(vPage : UInt, sid : UInt): UInt = {
          val localIdx = setIndexFromVPage(vPage, setsPerSecureDomain)
          val sidDomain = sid.resized - U(1, sidWidth bits)
          val base = U(secureBaseSet, setIndexWidth bits) + (sidDomain.resize(setIndexWidth) * U(setsPerSecureDomain, setIndexWidth bits))
          (base + localIdx.resize(setIndexWidth)).resized
        }

        def chooseLookupSet(vAddress : UInt, sid : UInt): UInt = {
          if(!partitionEnabled) {
            setIndexFromVirtual(vAddress, setCount).resized
          } else {
            val nonSecureSet = setIndexFromVirtual(vAddress, nonSecureSetCount)
            val secureProbe = U(secureBaseSet, setIndexWidth bits) + setIndexFromVirtual(vAddress, secureSetCount).resized
            val chosen = UInt(setIndexWidth bits)
            chosen := nonSecureSet.resized
            when(sid =/= 0){
              chosen := secureSetForSid(vAddress(31 downto 12), sid)
            }
            if(port.args.allowNonSecureOnFreeSecureSets){
              when(sid === 0 && !partitionTable(secureProbe - U(secureBaseSet, setIndexWidth bits)).allocated) {
                chosen := secureProbe
              }
            }
            chosen
          }
        }

        def toRsp[T <: Data](data : T, from : MemoryTranslatorCmd) : T = from match {
          case _ if from == port.bus.cmd.last => data
          case _ =>  {
            val next = port.bus.cmd.dropWhile(_ != from)(1)
            toRsp(RegNextWhen(data, !next.isStuck), next)
          }
        }
        val requireMmuLockupCmd = port.bus.cmd.takeRight(if(port.args.earlyRequireMmuLockup) 2 else 1).head

        val requireMmuLockupCalc = virtualRange(requireMmuLockupCmd.virtualAddress) && !requireMmuLockupCmd.bypassTranslation && csr.satp.mode
        if(!enableMmuInMachineMode) {
          requireMmuLockupCalc clearWhen(!csr.status.mprv && privilegeService.isMachine())
          when(privilegeService.isMachine()) {
            if (port.priority == MmuPort.PRIORITY_DATA) {
              requireMmuLockupCalc clearWhen (!csr.status.mprv || pipeline(MPP) === 3)
            } else {
              requireMmuLockupCalc := False
            }
          }
        }

        val cacheHitsCmd = port.bus.cmd.takeRight(if(port.args.earlyCacheHits) 2 else 1).head
        val setIndexCalc = chooseLookupSet(cacheHitsCmd.virtualAddress, csr.partition.currentSid.resized)
        val cacheHitsCalc = B(cache(setIndexCalc).map(line => line.valid && line.virtualAddress(1) === cacheHitsCmd.virtualAddress(31 downto 22) && (line.superPage || line.virtualAddress(0) === cacheHitsCmd.virtualAddress(21 downto 12))))


        val requireMmuLockup = toRsp(requireMmuLockupCalc, requireMmuLockupCmd)
        val cacheHits = toRsp(cacheHitsCalc, cacheHitsCmd)
        val setIndex = toRsp(setIndexCalc, cacheHitsCmd)

        val cacheHit = cacheHits.asBits.orR
        val cacheLine = MuxOH(cacheHits, cache(setIndex))
        val entryToReplace = Vec(Reg(UInt(log2Up(wayCount) bits)) init(0), setCount)

        when(requireMmuLockup) {
          port.bus.rsp.physicalAddress := cacheLine.physicalAddress(1) @@ (cacheLine.superPage ? port.bus.cmd.last.virtualAddress(21 downto 12) | cacheLine.physicalAddress(0)) @@ port.bus.cmd.last.virtualAddress(11 downto 0)
          port.bus.rsp.allowRead := cacheLine.allowRead  || csr.status.mxr && cacheLine.allowExecute
          port.bus.rsp.allowWrite := cacheLine.allowWrite
          port.bus.rsp.allowExecute := cacheLine.allowExecute
          port.bus.rsp.exception := !dirty &&  cacheHit && (cacheLine.exception || cacheLine.allowUser && privilegeService.isSupervisor() && !csr.status.sum || !cacheLine.allowUser && privilegeService.isUser())
          port.bus.rsp.refilling :=  dirty || !cacheHit
          port.bus.rsp.isPaging := True
        } otherwise {
          port.bus.rsp.physicalAddress := port.bus.cmd.last.virtualAddress
          port.bus.rsp.allowRead := True
          port.bus.rsp.allowWrite := True
          port.bus.rsp.allowExecute := True
          port.bus.rsp.exception := False
          port.bus.rsp.refilling := False
          port.bus.rsp.isPaging := False
        }
        port.bus.rsp.isIoAccess := ioRange(port.bus.rsp.physicalAddress)

        port.bus.rsp.bypassTranslation := !requireMmuLockup
        for(wayId <- 0 until wayCount){
          port.bus.rsp.ways(wayId).sel := cacheHits(wayId)
          port.bus.rsp.ways(wayId).physical := cache(setIndex)(wayId).physicalAddress(1) @@ (cache(setIndex)(wayId).superPage ? port.bus.cmd.last.virtualAddress(21 downto 12) | cache(setIndex)(wayId).physicalAddress(0)) @@ port.bus.cmd.last.virtualAddress(11 downto 0)
        }

        if(partitionEnabled) {
          val allocSid = csr.partition.allocSid.resized
          val freeSid = csr.partition.freeSid.resized
          val flushSid = csr.partition.flushSid.resized

          when(csr.partition.allocTrigger && (allocSid =/= U(0, sidWidth bits)) && (allocSid <= U(maxSecureDomains, sidWidth bits))){
            val base = (allocSid - U(1, sidWidth bits)) * U(setsPerSecureDomain, sidWidth bits)
            for(entryId <- 0 until secureSetCount){
              val local = U(entryId, sidWidth bits)
              when(local >= base && local < (base + U(setsPerSecureDomain, sidWidth bits))){
                partitionTable(entryId).sid := allocSid
                partitionTable(entryId).allocated := True
                for(line <- cache(secureBaseSet + entryId)) line.valid := False
              }
            }
            if(id == 0) {
              csr.partition.status(0) := True
            }
          }

          when(csr.partition.freeTrigger && (freeSid =/= U(0, sidWidth bits)) && (freeSid <= U(maxSecureDomains, sidWidth bits))){
            for(entryId <- 0 until secureSetCount){
              when(partitionTable(entryId).allocated && partitionTable(entryId).sid === freeSid){
                partitionTable(entryId).allocated := False
                partitionTable(entryId).sid := 0
                for(line <- cache(secureBaseSet + entryId)) line.valid := False
              }
            }
            if(id == 0) {
              csr.partition.status(1) := True
            }
          }

          when(csr.partition.flushSidTrigger){
            for(entryId <- 0 until secureSetCount){
              when(partitionTable(entryId).allocated && partitionTable(entryId).sid === flushSid){
                for(line <- cache(secureBaseSet + entryId)) line.valid := False
              }
            }
            if(id == 0) {
              csr.partition.status(2) := True
            }
          }
        }

        // Avoid keeping any invalid line in the cache after an exception.
        // https://github.com/riscv/riscv-linux/blob/8fe28cb58bcb235034b64cbbb7550a8a43fd88be/arch/riscv/include/asm/pgtable.h#L276
        when(service(classOf[IContextSwitching]).isContextSwitching) {
          for (set <- cache; line <- set) {
            when(line.exception) {
              line.valid := False
            }
          }
        }
      }

      val shared = new Area {
        val State = new SpinalEnum{
          val IDLE, L1_CMD, L1_RSP, L0_CMD, L0_RSP = newElement()
        }
        val state = RegInit(State.IDLE)
        val vpn = Reg(Vec(UInt(10 bits), UInt(10 bits)))
        val refillSid = Reg(UInt(csr.partitionSidWidth bits)) init(0)
        val portSortedOh = Reg(Bits(portsInfo.length bits))
        case class PTE() extends Bundle {
          val V, R, W ,X, U, G, A, D = Bool()
          val RSW = Bits(2 bits)
          val PPN0 = UInt(10 bits)
          val PPN1 = UInt(12 bits)
        }

        val dBusRspStaged = dBusAccess.rsp.stage()
        val dBusRsp = new Area{
          val pte = PTE()
          pte.assignFromBits(dBusRspStaged.data)
          val exception = !pte.V || (!pte.R && pte.W) || dBusRspStaged.error
          val leaf = pte.R || pte.X
        }

        val pteBuffer = RegNextWhen(dBusRsp.pte, dBusRspStaged.valid && !dBusRspStaged.redo)

        dBusAccess.cmd.valid := False
        dBusAccess.cmd.write := False
        dBusAccess.cmd.size := 2
        dBusAccess.cmd.address.assignDontCare()
        dBusAccess.cmd.data.assignDontCare()
        dBusAccess.cmd.writeMask.assignDontCare()

        val refills = OHMasking.last(B(ports.map(port => port.handle.bus.cmd.last.isValid && port.requireMmuLockup && !port.dirty && !port.cacheHit)))
        switch(state){
          is(State.IDLE){
            when(refills.orR){
              portSortedOh := refills
              refillSid := csr.partition.currentSid
              state := State.L1_CMD
              val address = MuxOH(refills, sortedPortsInfo.map(_.bus.cmd.last.virtualAddress))
              vpn(1) := address(31 downto 22)
              vpn(0) := address(21 downto 12)
            }
          }
          is(State.L1_CMD){
            dBusAccess.cmd.valid := True
            // RV spec allows for 34-bit phys address in Sv32 mode; we only implement 32 bits and ignore the top 2 bits of satp.
            dBusAccess.cmd.address := csr.satp.ppn(19 downto 0) @@ vpn(1) @@ U"00"
            when(dBusAccess.cmd.ready){
              state := State.L1_RSP
            }
          }
          is(State.L1_RSP){
            when(dBusRspStaged.valid){
              state := State.L0_CMD
              when(dBusRsp.leaf || dBusRsp.exception){
                state := State.IDLE
              }
              when(dBusRspStaged.redo){
                state := State.L1_CMD
              }
            }
          }
          is(State.L0_CMD){
            dBusAccess.cmd.valid := True
            dBusAccess.cmd.address := pteBuffer.PPN1(9 downto 0) @@ pteBuffer.PPN0 @@ vpn(0) @@ U"00"
            when(dBusAccess.cmd.ready){
              state := State.L0_RSP
            }
          }
          is(State.L0_RSP){
            when(dBusRspStaged.valid) {
              state := State.IDLE
              when(dBusRspStaged.redo){
                state := State.L0_CMD
              }
            }
          }
        }

        for((port, id) <- sortedPortsInfo.zipWithIndex) {
          port.bus.busy := state =/= State.IDLE && portSortedOh(id)
        }

        when(dBusRspStaged.valid && !dBusRspStaged.redo && (dBusRsp.leaf || dBusRsp.exception)){
          for((port, id) <- ports.zipWithIndex) {
            when(portSortedOh(id)) {
              val refillSetIndex = UInt((log2Up(port.handle.args.setCount) max 1) bits)
              refillSetIndex := 0
              if(port.handle.args.enablePartitioning){
                val sid = refillSid.resized
                val local = if(port.handle.args.partitionSetsPerSecureDomain == 1) U(0, (log2Up(port.handle.args.partitionSetsPerSecureDomain) max 1) bits) else vpn(0)(log2Up(port.handle.args.partitionSetsPerSecureDomain)-1 downto 0)
                when(sid === 0){
                  if(port.handle.args.partitionNonSecureSetCount == 1) {
                    refillSetIndex := 0
                  } else {
                    refillSetIndex := vpn(0)(log2Up(port.handle.args.partitionNonSecureSetCount)-1 downto 0).resized
                  }
                } otherwise {
                  val base = (sid - U(1, sid.getWidth bits)) * U(port.handle.args.partitionSetsPerSecureDomain, sid.getWidth bits)
                  refillSetIndex := U(port.handle.args.partitionNonSecureSetCount, refillSetIndex.getWidth bits) + (base + local.resized).resized
                }
              } else {
                if(port.handle.args.setCount == 1) {
                  refillSetIndex := U(0, (log2Up(port.handle.args.setCount) max 1) bits)
                } else {
                  refillSetIndex := vpn(0)(log2Up(port.handle.args.setCount) - 1 downto 0).resized
                }
              }

              port.entryToReplace(refillSetIndex) := port.entryToReplace(refillSetIndex) + 1
              if(port.handle.args.earlyRequireMmuLockup) {
                port.dirty := True
              } //Avoid having non coherent TLB lookup
              for ((line, lineId) <- port.cache(refillSetIndex).zipWithIndex) {
                when(port.entryToReplace(refillSetIndex) === lineId){
                  val superPage = state === State.L1_RSP
                  line.valid := True
                  line.exception := dBusRsp.exception || (superPage && dBusRsp.pte.PPN0 =/= 0) || !dBusRsp.pte.A
                  line.virtualAddress := vpn
                  line.physicalAddress := Vec(dBusRsp.pte.PPN0, dBusRsp.pte.PPN1(9 downto 0))
                  line.allowRead := dBusRsp.pte.R
                  line.allowWrite := dBusRsp.pte.W && dBusRsp.pte.D
                  line.allowExecute := dBusRsp.pte.X
                  line.allowUser := dBusRsp.pte.U
                  line.superPage := state === State.L1_RSP
                }
              }
            }
          }
        }
      }
    }

    val fenceStage = execute

    //Both SFENCE_VMA and SATP reschedule the next instruction in the CsrPlugin itself with one extra cycle to ensure side effect propagation.
    fenceStage plug new Area{
      import fenceStage._
      when(arbitration.isValid && arbitration.isFiring && input(IS_SFENCE_VMA2)){
        for(port <- core.ports; set <- port.cache; line <- set) line.valid := False
      }

      csrService.onWrite(CSR.SATP){
        for(port <- core.ports; set <- port.cache; line <- set) line.valid := False
      }

      when(csr.partition.flushAllTrigger){
        for(port <- core.ports; set <- port.cache; line <- set) line.valid := False
      }
    }
  }
}
