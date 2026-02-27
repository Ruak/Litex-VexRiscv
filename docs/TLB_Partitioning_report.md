# VexRiscv TLB Partitioning 改动说明

本文是仓库中 **TLB Partitioning** 相关改动的统一说明文档，基于当前分支相对于上一次提交的 `git diff`，逐文件、逐区域给出代码差异和行为变化说明。

---

## 1. 总览：哪些文件被改动了？

本次改动主要涉及四个 Scala 源文件：

- `src/main/scala/vexriscv/Riscv.scala`  
  定义 RISC-V 指令编码和 CSR 编号。在这里增加了一些 **新的 CSR 常量**，专门用于 TLB 分区控制。

- `src/main/scala/vexriscv/plugin/MmuPlugin.scala`  
  MMU/TLB 插件的主体。绝大部分 TLB Partitioning 逻辑都在这里，包括：
  - 分区参数（set/way/域）
  - CSR 寄存器接口
  - 查找路径（根据 SID 选 set）
  - 分配/释放/flush 安全域
  - refill 时保持 SID 隔离

- `src/main/scala/vexriscv/demo/smp/VexRiscvSmpCluster.scala`  
  SMP 集群中 CPU 的配置函数 `vexRiscvConfig(...)`。我们在这里把“是否启用分区、如何分配 secure set”等参数暴露给上层。

- `src/main/scala/vexriscv/demo/smp/VexRiscvSmpLitexCluster.scala`  
  Litex SoC 的 netlist 生成入口。这里：
  - 定义了命令行选项，例如 `--tlb-partitioning`。
  - 把命令行参数传到 `vexRiscvConfig(...)` 再传到 `MmuPlugin`。

## 2. `Riscv.scala`：CSR 扩展

### 2.1 新增代码片段

文件末尾 `object CSR` 中新增的部分如下：

```scala
object CSR{
  // ...（原有 CSR 定义略）...

  val DCSR      = 0x7B0
  val DPC       = 0x7B1
  val TSELECT   = 0x7A0
  val TDATA1    = 0x7A1
  val TDATA2    = 0x7A2
  val TINFO     = 0x7a4
  val TCONTROL  = 0x7A5

  // TLB partitioning CSRs (machine RW).
  val TLB_SID       = 0x5C0
  val TLB_CMD       = 0x5C1
  val TLB_ALLOC_SID = 0x5C2
  val TLB_FREE_SID  = 0x5C3
  val TLB_FLUSH_SID = 0x5C4
  val TLB_STATUS    = 0x5C5
}
```

### 2.2 修改解释

- `object CSR`：存放常量，每个 `val` 是一个 CSR 的地址（编号）。
- 新增的 6 个 CSR：
  - `TLB_SID`：当前 **安全域 ID**（SID）。
  - `TLB_CMD`：命令寄存器（哪个 bit 拉高就触发哪个操作）。
  - `TLB_ALLOC_SID`：为哪个 SID 分配 secure set。
  - `TLB_FREE_SID`：释放哪个 SID。
  - `TLB_FLUSH_SID`：刷哪个 SID 的 TLB。
  - `TLB_STATUS`：命令执行的简单状态标记（哪些操作被接受执行）。

- 这些只是“编号”，真正使用是在 `MmuPlugin` 里，通过 `csrService.rw/r/onWrite` 读写寄存器。

---

## 3. `MmuPlugin.scala`：配置与参数（`MmuPortConfig`）

### 3.1 新的 `MmuPortConfig` 定义

```scala
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
```

### 3.2 修改解释

`MmuPortConfig` 描述单个 MMU 端口的 TLB 组织形式和行为参数。新增字段的语义如下：

- `enablePartitioning`：是否启用 TLB 分区功能。为 `false` 时，行为与原实现等价。
- `secureSetCount`：预留给安全域使用的 set 数；为 0 时默认使用 `setCount / 2`。
- `setsPerSecureDomain`：每个安全域占用的 set 数。
- `maxSecureDomains`：支持的安全域个数上限（SID>0 的域数）。
- `allowNonSecureOnFreeSecureSets`：secure set 未分配时，是否允许 SID=0 的访问落在这些 set 上。

派生方法对上述参数进行归一化和约束检查：

- `partitionSecureSetCount` / `partitionNonSecureSetCount`：划分 secure / non-secure 区域的 set 数。
- `partitionSetsPerSecureDomain`：确保 `secureSetCount` 能被 `setsPerSecureDomain` 整除。
- `partitionMaxSecureDomains` / `partitionSidWidth`：确定最多可支持的安全域数量以及 SID 所需的位宽。

`require(...)` 保证在构造配置时即可发现非法参数组合，不会生成不一致的硬件结构。

---

## 4. `MmuPlugin.scala`：CSR 区域扩展（`val csr = ...`）

### 4.1 新增代码片段

```scala
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
```

### 4.2 修改解释

- `partitionSidWidth`：在所有启用分区的端口上取所需 SID 宽度的最大值，以保证单一 `currentSid` 寄存器即可覆盖所有端口的需求。
- `partition` Area：
  - `currentSid`：当前 MMU 查找/填充所使用的 SID，对应 CSR `TLB_SID`。
  - `allocSid` / `freeSid` / `flushSid`：对应三个参数 CSR，用于分配、释放、按 SID 刷新安全域。
  - 各种 `*Trigger`：由 `TLB_CMD` 的不同 bit 触发的一拍脉冲，用于驱动后续硬件状态机。
  - `status`：记录最近一次命令的接受情况（按位编码），通过 `TLB_STATUS` 读出。
- CSR 映射：`csrService.rw/r/onWrite` 将上述寄存器暴露在 RISC-V CSR 空间中，形成软件可见的控制接口。

---

## 5. `MmuPlugin.scala`：端口 Area – 分区表与 set 选择

### 5.1 完整代码片段（关键部分）

```scala
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

    // ... 后面还有查找/响应/分配等逻辑
  }
}
```

### 5.2 行为对比：改前 vs 改后

- 改前：
  - 仅通过 `setIndexFromVirtual(address)` 从虚拟地址固定比特段提取 set index。
  - 不区分 SID，所有上下文共享同一组 set。
- 改后：
  - 将 set 选择逻辑拆分为一组函数：
    - `setIndexFromVPage` / `setIndexFromVirtual`：统一页号到 set index 的映射。
    - `secureSetForSid`：在 secure 区域中，为给定 SID 计算对应的 set 区间和局部 index。
    - `chooseLookupSet`：根据 `enablePartitioning`、`sid`、是否允许重用空闲 secure set 等因素，最终选择 NS 或 secure 区域中的具体 set。
  - `enablePartitioning=false` 时退化为原有行为；`enablePartitioning=true` 时，根据 SID 实现 set 级隔离。

---

## 6. `MmuPlugin.scala`：alloc/free/flushSid 行为（分区生命周期）

### 6.1 完整代码片段

```scala
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
```

### 6.2 行为说明

- alloc：
  - 在 `allocTrigger` 有效且 `allocSid` ∈ `[1, maxSecureDomains]` 时，计算该 SID 对应的 secure set 区间。
  - 在该区间内更新 `partitionTable`，并清空对应 set 的 TLB 条目。
  - 在 `id == 0` 的端口上，将 `status(0)` 置位，用于软件侧确认。
- free：
  - 在 `freeTrigger` 有效且 `freeSid` ∈ `[1, maxSecureDomains]` 时，释放该 SID 对应的 secure set，并清空对应条目。
  - 在 `id == 0` 的端口上，将 `status(1)` 置位。
- flushSid：
  - 在 `flushSidTrigger` 有效时，遍历所有 secure set，将 `sid == flushSid` 的 set 内条目清空。
  - 在 `id == 0` 的端口上，将 `status(2)` 置位。

### 6.3 一个bug 修复

最初实现时，这里的条件写成了：

```scala
when(csr.partition.allocTrigger && allocSid =/= 0 && allocSid <= maxSecureDomains) { ... }
```

在 Scala 里，运算符优先级 + Int 与 UInt 的混用，可能导致最终综合成：

```verilog
assign when_MmuPlugin_l325 = (MmuPlugin_partition_allocTrigger && 1'b0) && ...;
```

也就是永远为 false。  
改成：

- 显式写成 `allocSid =/= U(0, sidWidth bits)`，并加括号：
  - `csr.partition.allocTrigger && (allocSid =/= U(...)) && (allocSid <= U(...))`

这样既让类型一致，又避免了 Scala 默认优先级问题。  
这也是在网表静态验证中看到 `!= 1'b0` 而不是 `&& 1'b0` 的原因。

---

## 7. `MmuPlugin.scala`：shared Area – refill 的分区感知

### 7.1 完整代码片段

```scala
val shared = new Area {
  val State = new SpinalEnum{
    val IDLE, L1_CMD, L1_RSP, L0_CMD, L0_RSP = newElement()
  }
  val state = RegInit(State.IDLE)
  val vpn = Reg(Vec(UInt(10 bits), UInt(10 bits)))
  val refillSid = Reg(UInt(csr.partitionSidWidth bits)) init(0)
  val portSortedOh = Reg(Bits(portsInfo.length bits))
  // ... PTE 定义、dBusRspStaged、dBusRsp 省略 ...

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
    // ... L1_CMD / L1_RSP / L0_CMD / L0_RSP 状态机略 ...
  }

  // dBusRspStaged 完成后写回 TLB
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
        // ... 真正写入 cache 行的逻辑略 ...
      }
    }
  }
}
```

### 7.2 设计意图

TLB refill 由多拍状态机完成，在整个过程中可能发生上下文切换，`TLB_SID` 也可能被软件重写。如果在写回 TLB 时直接使用 `currentSid`，则存在“旧上下文发起的 refill 被写入新上下文域内”的风险。

为避免该问题，设计采用以下策略：

- 在从 `IDLE` 进入 `L1_CMD`（即确定发起 refill）时，将当时的 `currentSid` 锁存到 `refillSid`。
- 后续所有 refill 写回路径仅使用 `refillSid`，从而保证 refill 结果始终归属发起请求的 SID。

---

## 8. `MmuPlugin.scala`：fence 阶段 – flushAllTrigger

### 8.1 代码片段

```scala
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
```

### 8.2 行为解释

原来这块只有两种触发“全 TLB 清空”的方式：

- `SFENCE_VMA`（软更新时间映射后，用来让 CPU 丢弃旧 TLB）；
- 写 `SATP`（切换页表），也会全清空。

现在我们多了一种方式：

- 写 `TLB_CMD` 的 bit3（`flushAllTrigger`）时，也会触发“所有端口、所有 set 的 `valid` 清零”。

用途是给 OS 提供一个比较粗暴但简单的全局清空能力，比如：

- 做安全域重配置；
- 做 Debug/测试；
- 或者在认为“全刷对性能影响可接受”的场合。

---

## 9. `VexRiscvSmpCluster.scala`：在配置函数中打开/参数化分区

### 9.1 新的函数签名

```scala
def vexRiscvConfig(hartId : Int,
                   // ... 省略若干原参数 ...
                   privilegedDebug: Boolean = false,
                   privilegedDebugTriggers: Int = 2,
                   privilegedDebugTriggersLsu: Boolean = false,
                   csrFull : Boolean = false,
                   tlbPartitioning : Boolean = false,
                   tlbSecureSetCount : Int = 0,
                   tlbSetsPerSecureDomain : Int = 1,
                   tlbMaxSecureDomains : Int = 0,
                   tlbAllowNonSecureReuse : Boolean = false
                  ) = {
  // ...
}
```

### 9.2 I-TLB / D-TLB 中的 `MmuPortConfig` 调用

以 I-TLB 为例：

```scala
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
```

D-TLB 的配置和上面一致，只是 `portTlbSize` 换成 `dTlbSize`。

### 9.3 修改解释

`vexRiscvConfig(...)` 是生成 `VexRiscvConfig` 的集中入口。新增的 TLB 分区相关参数提供：

- 开关：`tlbPartitioning` 控制是否启用分区；
- 拓扑：`tlbSecureSetCount` / `tlbSetsPerSecureDomain` / `tlbMaxSecureDomains` 定义 secure/non-secure set 的划分方式和域数上限；
- reuse 策略：`tlbAllowNonSecureReuse` 控制 NS 是否可以使用空闲 secure set。

这些参数被直接传递给 I-TLB 与 D-TLB 的 `MmuPortConfig`，从而影响底层 `MmuPlugin` 的行为。

---

## 10. `VexRiscvSmpLitexCluster.scala`：命令行参数与 netlist 生成

### 10.1 新增的命令行变量与解析

```scala
object VexRiscvLitexSmpClusterCmdGen extends App {
  // ...
  var iTlbSize = 4
  var dTlbSize = 4
  var tlbPartitioning = true
  var tlbSecureSetCount = 0
  var tlbSetsPerSecureDomain = 1
  var tlbMaxSecureDomains = 0
  var tlbAllowNonSecureReuse = false
  // ...
  assert(new scopt.OptionParser[Unit]("VexRiscvLitexSmpClusterCmdGen") {
    // ... 省略原有选项 ...
    opt[String]("itlb-size") action { (v, c) => iTlbSize = v.toInt }
    opt[String]("dtlb-size") action { (v, c) => dTlbSize = v.toInt }
    opt[String]("tlb-partitioning") action { (v, c) => tlbPartitioning = v.toBoolean }
    opt[String]("tlb-secure-set-count") action { (v, c) => tlbSecureSetCount = v.toInt }
    opt[String]("tlb-sets-per-secure-domain") action { (v, c) => tlbSetsPerSecureDomain = v.toInt }
    opt[String]("tlb-max-secure-domains") action { (v, c) => tlbMaxSecureDomains = v.toInt }
    opt[String]("tlb-allow-ns-reuse") action { (v, c) => tlbAllowNonSecureReuse = v.toBoolean }
    // ...
  }.parse(args, Unit).nonEmpty)
  // ...
}
```

### 10.2 把命令行参数传到 `vexRiscvConfig(...)`

```scala
val coherency = coherentDma || cpuCount > 1
def parameter = VexRiscvLitexSmpClusterParameter(
  cluster = VexRiscvSmpClusterParameter(
    cpuConfigs = List.tabulate(cpuCount) { hartId => {
      val c = vexRiscvConfig(
        hartId = hartId,
        ioRange = address => address.msb,
        resetVector = resetVector,
        iBusWidth = iBusWidth,
        dBusWidth = dBusWidth,
        iCacheSize = iCacheSize,
        dCacheSize = dCacheSize,
        iCacheWays = iCacheWays,
        dCacheWays = dCacheWays,
        coherency = coherency,
        privilegedDebug = privilegedDebug,
        iBusRelax = true,
        earlyBranch = true,
        withFloat = fpu,
        withDouble = fpu,
        externalFpu = fpu,
        loadStoreWidth = if(fpu) 64 else 32,
        rvc = rvc,
        injectorStage = rvc,
        iTlbSize = iTlbSize,
        dTlbSize = dTlbSize,
        tlbPartitioning = tlbPartitioning,
        tlbSecureSetCount = tlbSecureSetCount,
        tlbSetsPerSecureDomain = tlbSetsPerSecureDomain,
        tlbMaxSecureDomains = tlbMaxSecureDomains,
        tlbAllowNonSecureReuse = tlbAllowNonSecureReuse
      )
      if(aesInstruction) c.add(new AesPlugin)
      c
    }},
    // ...
  ),
  // ...
)
```

### 10.3 实际效果

通过命令行参数，可以方便地在 Litex 生成脚本层面配置是否启用 TLB 分区以及分区的具体形态。例如：

- 分区：

  ```bash
  --tlb-partitioning=True \
  --tlb-secure-set-count=1 \
  --tlb-sets-per-secure-domain=1 \
  --tlb-max-secure-domains=1 \
  --tlb-allow-ns-reuse=False
  ```

- 不启用分区：

  ```bash
  --tlb-partitioning=False
  ```

---

## 11. Linux 接入

- 在内核维护 `task_struct -> sid` 映射，**不要直接用 PID 当 SID**。
- 在上下文切换时：
  - 从任务中取出 `sid`；
  - 用 `csrw TLB_SID, sid` 设置当前 SID。
- 在安全域生命周期中：
  - 新建安全域：调用 `tlb_domain_alloc(sid)`；
  - 退出时：调用 `tlb_domain_free(sid)`；
  - 需要清理 TLB 时：调用 `tlb_domain_flush(sid)` 或 `tlb_flush_all()`。

具体的 Linux patch 如何写，取决于目标内核版本和架构目录。

---

## 12. sbt 命令与验证步骤

### 12.1 分区 netlist 生成命令

（以实际使用的参数为基础，加入分区参数）

```bash
sbt "runMain vexriscv.demo.smp.VexRiscvLitexSmpClusterCmdGen \
  --cpu-count=1 \
  --reset-vector=0 \
  --ibus-width=32 \
  --dbus-width=32 \
  --dcache-size=4096 \
  --icache-size=4096 \
  --dcache-ways=1 \
  --icache-ways=1 \
  --litedram-width=64 \
  --aes-instruction=False \
  --expose-time=False \
  --out-of-order-decoder=True \
  --privileged-debug=False \
  --hardware-breakpoints=1 \
  --wishbone-memory=False \
  --fpu=False \
  --cpu-per-fpu=4 \
  --rvc=False \
  --netlist-name=VexRiscvLitexSmpCluster_Cc1_Iw32Is4096Iy1_Dw32Ds4096Dy1_ITs4DTs4_Ldw64_Ood_Hb1 \
  --netlist-directory=/home/cva6/output/ \
  --dtlb-size=4 \
  --itlb-size=4 \
  --jtag-tap=False \
  --tlb-partitioning=True \
  --tlb-secure-set-count=1 \
  --tlb-sets-per-secure-domain=1 \
  --tlb-max-secure-domains=1 \
  --tlb-allow-ns-reuse=False"
```

### 12.2 netlist 无分区

只需把 `--tlb-partitioning=True` 改成 `False`，并且可以去掉其他分区参数（因为不会被使用）：

```bash
--tlb-partitioning=False
```

### 12.3 静态验证

在分区版网表上：

- 查 CSR & 分区寄存器：

```bash
rg "TLB_SID|TLB_CMD|MmuPlugin_partition_" /home/cva6/output/*.v
```

- 查 set 选择路径与分区表：

```bash
rg "partitionTable|refillSid|setIndexCalc" /home/cva6/output/*.v
```

- 检查 alloc/free 条件是否被折叠：

```bash
rg "when_MmuPlugin_l325|when_MmuPlugin_l340" /home/cva6/output/*.v
```

确认是 `!= 1'b0` 而不是 `&& 1'b0`。

### 12.4 运行态验证

1. 在内核或裸机中：  
   - 创建两个任务：SID=0（非安全）和 SID=1（安全域）。  
   - 通过写 CSR `TLB_SID`、`TLB_ALLOC_SID`、`TLB_CMD` 等接口在 SID=1 上完成安全域分配。
2. 运行冲突访问测试：  
   - 让 SID=0 和 SID=1 访问“落在同一原始 set”的虚拟页集合；  
   - 比较分区开/关时跨域互相驱逐的程度（TLB miss/访问延迟）。


