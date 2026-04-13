package vexriscv.demo.smp

import spinal.core
import spinal.core._
import spinal.core.sim.{onSimEnd, simSuccess}
import spinal.lib._
import spinal.lib.bus.bmb.sim.BmbMemoryAgent
import spinal.lib.bus.bmb._
import spinal.lib.bus.misc.{DefaultMapping, SizeMapping}
import spinal.lib.bus.wishbone.{Wishbone, WishboneConfig, WishboneToBmb, WishboneToBmbGenerator}
import spinal.lib.com.jtag.{Jtag, JtagInstructionDebuggerGenerator, JtagTapInstructionCtrl}
import spinal.lib.com.jtag.sim.JtagTcp
import spinal.lib.com.jtag.xilinx.Bscane2BmbMasterGenerator
import spinal.lib.generator._
import spinal.core.fiber._
import spinal.idslplugin.PostInitCallback
import spinal.lib.cpu.riscv.debug.{DebugModule, DebugModuleCpuConfig, DebugModuleParameter, DebugTransportModuleJtagTap, DebugTransportModuleJtagTapWithTunnel, DebugTransportModuleParameter, DebugTransportModuleTunneled}
import spinal.lib.misc.plic.PlicMapping
import spinal.lib.system.debugger.SystemDebuggerConfig
import vexriscv.ip.{DataCacheAck, DataCacheConfig, DataCacheMemBus, InstructionCache, InstructionCacheConfig}
import vexriscv.plugin._
import vexriscv.plugin.TlbCustomInstructionPlugin
import vexriscv.{Riscv, VexRiscv, VexRiscvBmbGenerator, VexRiscvConfig, plugin}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import spinal.lib.generator._
import vexriscv.ip.fpu.FpuParameter

case class VexRiscvSmpClusterParameter(cpuConfigs : Seq[VexRiscvConfig],
                                       jtagHeaderIgnoreWidth : Int,
                                       withExclusiveAndInvalidation : Boolean,
                                       forcePeripheralWidth : Boolean = true,
                                       outOfOrderDecoder : Boolean = true,
                                       fpu : Boolean = false,
                                       privilegedDebug : Boolean = false,
                                       hardwareBreakpoints : Int = 0,
                                       jtagTap : Boolean = false)

class VexRiscvSmpClusterBase(p : VexRiscvSmpClusterParameter) extends Area with PostInitCallback{
  val cpuCount = p.cpuConfigs.size

  val debugCd = ClockDomainResetGenerator()
  debugCd.holdDuration.load(4095)
  debugCd.makeExternal()

  val systemCd = ClockDomainResetGenerator()
  systemCd.holdDuration.load(63)
  systemCd.setInput(debugCd)


  val ctx = systemCd.outputClockDomain.push()
  override def postInitCallback(): VexRiscvSmpClusterBase.this.type = {
    ctx.restore()
    this
  }

  implicit val interconnect = BmbInterconnectGenerator()

  val customDebug = !p.privilegedDebug generate new Area {
    val debugBridge = debugCd.outputClockDomain on JtagInstructionDebuggerGenerator(p.jtagHeaderIgnoreWidth)
    debugBridge.jtagClockDomain.load(ClockDomain.external("jtag", withReset = false))

    val debugPort = Handle(debugBridge.logic.jtagBridge.io.ctrl.toIo).setName("debugPort")
  }

  val dBusCoherent = BmbBridgeGenerator()
  val dBusNonCoherent = BmbBridgeGenerator()

  val smp = p.withExclusiveAndInvalidation generate new Area{
    val exclusiveMonitor = BmbExclusiveMonitorGenerator()
    interconnect.addConnection(dBusCoherent.bmb, exclusiveMonitor.input)

    val invalidationMonitor = BmbInvalidateMonitorGenerator()
    interconnect.addConnection(exclusiveMonitor.output, invalidationMonitor.input)
    interconnect.addConnection(invalidationMonitor.output, dBusNonCoherent.bmb)
    if(p.outOfOrderDecoder) interconnect.masters(invalidationMonitor.output).withOutOfOrderDecoder()
  }

  val noSmp = !p.withExclusiveAndInvalidation generate new Area{
    interconnect.addConnection(dBusCoherent.bmb, dBusNonCoherent.bmb)
  }

  val cores = for(cpuId <- 0 until cpuCount) yield new Area{
    val cpu = VexRiscvBmbGenerator()
    cpu.config.load(p.cpuConfigs(cpuId))
    interconnect.addConnection(
      cpu.dBus -> List(dBusCoherent.bmb)
    )

    cpu.hardwareBreakpointCount.load(p.hardwareBreakpoints)
    if(!p.privilegedDebug) {
      cpu.enableDebugBmb(
        debugCd = debugCd.outputClockDomain,
        resetCd = systemCd,
        mapping = SizeMapping(cpuId * 0x1000, 0x1000)
      )
      interconnect.addConnection(customDebug.debugBridge.bmb, cpu.debugBmb)
    } else {
      cpu.enableRiscvDebug(debugCd.outputClockDomain, systemCd)
    }
  }

  val privilegedDebug = p.privilegedDebug generate new Area{

    val systemReset = Handle(Bool())
    systemCd.relaxedReset(systemReset, ResetSensitivity.HIGH)

    val dp = DebugTransportModuleParameter(
      addressWidth = 7,
      version = 1,
      idle = 7
    )

    val logic = hardFork(debugCd.outputClockDomain on new Area {
      val XLEN = 32

      val dm = DebugModule(
        DebugModuleParameter(
          version = dp.version + 1,
          harts = cpuCount,
          progBufSize = 2,
          datacount = XLEN / 32 + cores.exists(_.cpu.config.get.FLEN == 64).toInt,
          hartsConfig = cores.map(c => DebugModuleCpuConfig(
            xlen = XLEN,
            flen = c.cpu.config.get.FLEN,
            withFpuRegAccess = c.cpu.config.get.FLEN == 64
          ))
        )
      )
      systemReset := dm.io.ndmreset
      for ((cpu, i) <- cores.zipWithIndex) {
        val privBus = cpu.cpu.debugRiscv
        privBus <> dm.io.harts(i)
        privBus.dmToHart.removeAssignments() <-< dm.io.harts(i).dmToHart
      }

      val clintStop =  (cores.map(e => e.cpu.logic.cpu.service(classOf[CsrPlugin]).stoptime).andR)

      val noTap = !p.jtagTap generate new Area {
        val jtagCd = ClockDomain.external("jtag", withReset = false)

        val tunnel = DebugTransportModuleTunneled(
          p = dp,
          jtagCd = jtagCd,
          debugCd = ClockDomain.current
        )
        dm.io.ctrl <> tunnel.io.bus
        val debugPort = Handle(tunnel.io.instruction.toIo).setName("debugPort")
      }

      val withTap = p.jtagTap generate new Area {
        val tunnel = DebugTransportModuleJtagTapWithTunnel(
          p = dp,
          debugCd = ClockDomain.current
        )
        dm.io.ctrl <> tunnel.io.bus
        val debugPort = Handle(tunnel.io.jtag.toIo).setName("debugPort")
      }
    })
  }
}


class VexRiscvSmpClusterWithPeripherals(p : VexRiscvSmpClusterParameter) extends VexRiscvSmpClusterBase(p) {
  val peripheralBridge = BmbToWishboneGenerator(DefaultMapping)
  val peripheral = Handle(peripheralBridge.logic.io.output.toIo)
  if(p.forcePeripheralWidth) interconnect.slaves(peripheralBridge.bmb).forceAccessSourceDataWidth(32)

  val plic = BmbPlicGenerator()(interconnect = null)
  plic.priorityWidth.load(2)
  plic.mapping.load(PlicMapping.sifive)

  val plicWishboneBridge = new Generator{
    dependencies += plic.ctrl

    plic.accessRequirements.load(BmbAccessParameter(
      addressWidth = 22,
      dataWidth = 32
    ).addSources(1, BmbSourceParameter(
      contextWidth = 0,
      lengthWidth = 2,
      alignment =  BmbParameter.BurstAlignement.LENGTH
    )))

    val logic = add task new Area{
      val bridge = WishboneToBmb(WishboneConfig(20, 32))
      bridge.io.output >> plic.ctrl
    }
  }
  val plicWishbone = plicWishboneBridge.produceIo(plicWishboneBridge.logic.bridge.io.input)

  val clint = BmbClintGenerator(0)(interconnect = null)
  val clintWishboneBridge = new Generator{
    dependencies += clint.ctrl

    clint.accessRequirements.load(BmbAccessParameter(
      addressWidth = 16,
      dataWidth = 32
    ).addSources(1, BmbSourceParameter(
      contextWidth = 0,
      lengthWidth = 2,
      alignment =  BmbParameter.BurstAlignement.LENGTH
    )))

    val logic = add task new Area{
      val bridge = WishboneToBmb(WishboneConfig(14, 32))
      bridge.io.output >> clint.ctrl
    }
  }
  val clintWishbone = clintWishboneBridge.produceIo(clintWishboneBridge.logic.bridge.io.input)

  val interrupts = in Bits(32 bits)
  for(i <- 1 to 31) yield plic.addInterrupt(interrupts(i), i)

  for ((core, cpuId) <- cores.zipWithIndex) {
    core.cpu.setTimerInterrupt(clint.timerInterrupt(cpuId))
    core.cpu.setSoftwareInterrupt(clint.softwareInterrupt(cpuId))
    plic.priorityWidth.load(2)
    plic.mapping.load(PlicMapping.sifive)
    plic.addTarget(core.cpu.externalInterrupt)
    plic.addTarget(core.cpu.externalSupervisorInterrupt)
    List(clint.logic, core.cpu.logic).produce {
      for (plugin <- core.cpu.config.plugins) plugin match {
        case plugin: CounterPlugin if plugin.time != null => plugin.time := clint.logic.io.time
        case _ =>
      }
    }
  }

  clint.cpuCount.load(cpuCount)
  if(p.privilegedDebug) hardFork(clint.logic.io.stop := privilegedDebug.logic.clintStop)
}

//python3 -m litex_boards.targets.digilent_nexys_video --cpu-type=vexriscv_smp --with-privileged-debug --sys-clk-freq 50000000 --cpu-count 1  --build --load
object VexRiscvSmpClusterGen {
  def vexRiscvConfig(hartId : Int,
                     ioRange : UInt => Bool = (x => x(31 downto 28) === 0xF),
                     resetVector : Long = 0x80000000l,
                     iBusWidth : Int = 128,
                     dBusWidth : Int = 64,
                     loadStoreWidth : Int = 32,
                     coherency : Boolean = true,
                     atomic : Boolean = true,
                     iCacheSize : Int = 8192,
                     dCacheSize : Int = 8192,
                     iCacheWays : Int = 2,
                     dCacheWays : Int = 2,
                     iBusRelax : Boolean = false,
                     injectorStage : Boolean = false,
                     earlyBranch : Boolean = false,
                     earlyShifterInjection : Boolean = true,
                     dBusCmdMasterPipe : Boolean = false,
                     withMmu : Boolean = true,
                     withSupervisor : Boolean = true,
                     withFloat : Boolean = false,
                     withDouble : Boolean = false,
                     externalFpu : Boolean = true,
                     simHalt : Boolean = false,
                     decoderIsolationBench : Boolean = false,
                     decoderStupid : Boolean = false,
                     regfileRead : RegFileReadKind = plugin.ASYNC,
                     rvc : Boolean = false,
                     iTlbSize : Int = 4,
                     dTlbSize : Int = 4,
                     prediction : BranchPrediction = vexriscv.plugin.NONE,
                     withDataCache : Boolean = true,
                     withInstructionCache : Boolean = true,
                     forceMisa : Boolean = false,
                     forceMscratch : Boolean = false,
                     privilegedDebug: Boolean = false,
                     privilegedDebugTriggers: Int = 2,
                     privilegedDebugTriggersLsu: Boolean = false,
                     csrFull : Boolean = true,
                     tlbPartitioning : Boolean = false,
                     tlbSecureSetCount : Int = 0,
                     tlbSetsPerSecureDomain : Int = 1,
                     tlbMaxSecureDomains : Int = 0,
                     tlbAllowNonSecureReuse : Boolean = false,
                     /** True: XTLB_SYNC_PID reads dedicated `pid_latched` wired in LiteX generator; false: MMIO read via DBus. */
                     tlbPidSyncDedicatedIo : Boolean = false
                    ) = {
    assert(iCacheSize/iCacheWays <= 4096, "Instruction cache ways can't be bigger than 4096 bytes")
    assert(dCacheSize/dCacheWays <= 4096, "Data cache ways can't be bigger than 4096 bytes")
    assert(!(withDouble && !withFloat))

    val misa = Riscv.misaToInt(s"ima${if(withFloat) "f" else ""}${if(withDouble) "d" else ""}${if(rvc) "c" else ""}${if(withSupervisor) "su" else ""}")
    val csrConfig = if(withSupervisor){
      var c = CsrPluginConfig.openSbi(mhartid = hartId, misa = misa).copy(
        utimeAccess = CsrAccess.READ_ONLY,
        withPrivilegedDebug = privilegedDebug,
        debugTriggers = privilegedDebugTriggers,
        debugTriggersLsu = privilegedDebugTriggersLsu
      )
      if(csrFull){
       c = c.copy(
         mcauseAccess   = CsrAccess.READ_WRITE,
         mbadaddrAccess = CsrAccess.READ_WRITE,
         ucycleAccess   = CsrAccess.READ_ONLY,
         uinstretAccess = CsrAccess.READ_ONLY,
         mcycleAccess   = CsrAccess.READ_WRITE,
         minstretAccess = CsrAccess.READ_WRITE
       )
      }
      c
    } else {
      assert(!csrFull)
      CsrPluginConfig(
        catchIllegalAccess = true,
        mvendorid      = 0,
        marchid        = 0,
        mimpid         = 0,
        mhartid        = hartId,
        misaExtensionsInit = misa,
        misaAccess     = if(forceMisa) CsrAccess.READ_ONLY else CsrAccess.NONE,
        mtvecAccess    = CsrAccess.READ_WRITE,
        mtvecInit      = null,
        mepcAccess     = CsrAccess.READ_WRITE,
        mscratchGen    = forceMscratch,
        mcauseAccess   = CsrAccess.READ_ONLY,
        mbadaddrAccess = CsrAccess.READ_ONLY,
        mcycleAccess   = CsrAccess.NONE,
        minstretAccess = CsrAccess.NONE,
        ecallGen       = true,
        ebreakGen      = true,
        wfiGenAsWait   = false,
        wfiGenAsNop    = true,
        ucycleAccess   = CsrAccess.NONE,
        withPrivilegedDebug = privilegedDebug
      )
    }
    val config = VexRiscvConfig(
      plugins = List(
        if(withMmu)new MmuPlugin(
          ioRange = ioRange,
          tlbPidSyncDedicatedIo = tlbPidSyncDedicatedIo
        )else new StaticMemoryTranslatorPlugin(
          ioRange = ioRange
        ),
        //Uncomment the whole IBusCachedPlugin and comment IBusSimplePlugin if you want cached iBus config
        if(withInstructionCache) new IBusCachedPlugin(
          resetVector = resetVector,
          compressedGen = rvc,
          prediction = prediction,
          historyRamSizeLog2 = 9,
          relaxPredictorAddress = true,
          injectorStage = injectorStage,
          relaxedPcCalculation = iBusRelax,
          config = InstructionCacheConfig(
            cacheSize = iCacheSize,
            bytePerLine = 64,
            wayCount = iCacheWays,
            addressWidth = 32,
            cpuDataWidth = 32,
            memDataWidth = iBusWidth,
            catchIllegalAccess = true,
            catchAccessFault = true,
            asyncTagMemory = false,
            twoCycleRam = false,
            twoCycleCache = true,
            reducedBankWidth = true
          ),
          memoryTranslatorPortConfig = MmuPortConfig(
            portTlbSize = iTlbSize,
            tlbWayCount = 2,
            latency = 1,
            earlyRequireMmuLockup = true,
            earlyCacheHits = true,
            enablePartitioning = tlbPartitioning,
            secureSetCount = tlbSecureSetCount,
            setsPerSecureDomain = tlbSetsPerSecureDomain,
            maxSecureDomains = tlbMaxSecureDomains,
            allowNonSecureOnFreeSecureSets = tlbAllowNonSecureReuse
          )
        ) else new IBusSimplePlugin(
          resetVector = resetVector,
          cmdForkOnSecondStage = false,
          cmdForkPersistence = false,
          prediction = NONE,
          catchAccessFault = false,
          compressedGen = rvc,
          busLatencyMin = 2,
          vecRspBuffer = true
        ),
        if(withDataCache) new DBusCachedPlugin(
          dBusCmdMasterPipe = dBusCmdMasterPipe || dBusWidth == 32,
          dBusCmdSlavePipe = true,
          dBusRspSlavePipe = true,
          relaxedMemoryTranslationRegister = true,
          config = new DataCacheConfig(
            cacheSize         = dCacheSize,
            bytePerLine       = 64,
            wayCount          = dCacheWays,
            addressWidth      = 32,
            cpuDataWidth      = loadStoreWidth,
            memDataWidth      = dBusWidth,
            catchAccessError  = true,
            catchIllegal      = true,
            catchUnaligned    = true,
            withLrSc = atomic,
            withAmo = atomic,
            withExclusive = coherency,
            withInvalidate = coherency,
            withWriteAggregation = dBusWidth > 32
          ),
          memoryTranslatorPortConfig = MmuPortConfig(
            portTlbSize = dTlbSize,
            tlbWayCount = 2,
            latency = 1,
            earlyRequireMmuLockup = true,
            earlyCacheHits = true,
            enablePartitioning = tlbPartitioning,
            secureSetCount = tlbSecureSetCount,
            setsPerSecureDomain = tlbSetsPerSecureDomain,
            maxSecureDomains = tlbMaxSecureDomains,
            allowNonSecureOnFreeSecureSets = tlbAllowNonSecureReuse
          )
        ) else new DBusSimplePlugin(
          catchAddressMisaligned = false,
          catchAccessFault = false,
          earlyInjection = false
        ),
        new DecoderSimplePlugin(
          catchIllegalInstruction = true,
          decoderIsolationBench = decoderIsolationBench,
          stupidDecoder = decoderStupid
        ),
        new RegFilePlugin(
          regFileReadyKind = regfileRead,
          zeroBoot = false,
          x0Init = true
        ),
        new IntAluPlugin,
        new SrcPlugin(
          separatedAddSub = false
        ),
        new FullBarrelShifterPlugin(earlyInjection = earlyShifterInjection),
        //        new LightShifterPlugin,
        new HazardSimplePlugin(
          bypassExecute           = true,
          bypassMemory            = true,
          bypassWriteBack         = true,
          bypassWriteBackBuffer   = true,
          pessimisticUseSrc       = false,
          pessimisticWriteRegFile = false,
          pessimisticAddressMatch = false
        ),
        new MulPlugin,
        new MulDivIterativePlugin(
          genMul = false,
          genDiv = true,
          mulUnrollFactor = 32,
          divUnrollFactor = 1
        ),
        new CsrPlugin(csrConfig),
        new BranchPlugin(
          earlyBranch = earlyBranch,
          catchAddressMisaligned = true,
          fenceiGenAsAJump = false
        ),
        new CounterPlugin(if(csrFull) CounterPluginConfig(
          ucycleAccess        = CsrAccess.READ_ONLY,
          mcounterenAccess    = CsrAccess.NONE,
          scounterenAccess    = CsrAccess.NONE
        ) else CounterPluginConfig(
          NumOfCounters       = 0,
          mcycleAccess        = CsrAccess.NONE,
          ucycleAccess        = CsrAccess.NONE,
          minstretAccess      = CsrAccess.NONE,
          uinstretAccess      = CsrAccess.NONE,
          mcounterenAccess    = CsrAccess.NONE,
          scounterenAccess    = CsrAccess.NONE,
          mcounterAccess      = CsrAccess.NONE,
          ucounterAccess      = CsrAccess.NONE,
          meventAccess        = CsrAccess.NONE,
          mcountinhibitAccess = CsrAccess.NONE
        )),
        new YamlPlugin(s"cpu$hartId.yaml")
      )
    )

    if(withMmu && tlbPartitioning) config.plugins += new TlbCustomInstructionPlugin

    if(withFloat) config.plugins += new FpuPlugin(
      externalFpu = externalFpu,
      simHalt = simHalt,
      p = FpuParameter(withDouble = withDouble)
    )
    if (withMmu && tlbPidSyncDedicatedIo) config.withTlbPidLatchedPort = true
    config
  }
}