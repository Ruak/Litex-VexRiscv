package vexriscv.plugin

import spinal.core._
import vexriscv._
import vexriscv.Riscv._

/**
  * Expose MMU/TLB partitioning controls via custom instructions (user-mode accessible).
  *
  * Encoding uses RISC-V custom-0 opcode (0x0B) and funct7 to select the operation:
  * - XTLB_GET_SID        : rd <- currentSid
  * - XTLB_SET_SID        : currentSid <- rs1
  * - XTLB_GET_STATUS     : rd <- status
  * - XTLB_CMD            : triggers <- rs1[3:0]
  * - XTLB_SET_ALLOC_SID  : allocSid <- rs1
  * - XTLB_SET_FREE_SID   : freeSid <- rs1
  * - XTLB_SET_FLUSH_SID  : flushSid <- rs1
  */
class TlbCustomInstructionPlugin extends Plugin[VexRiscv] {
  object XTLB_OP extends SpinalEnum(binarySequential) {
    val NONE, GET_SID, SET_SID, GET_STATUS, CMD, SET_ALLOC_SID, SET_FREE_SID, SET_FLUSH_SID, SYNC_PID = newElement()
  }

  object XTLB_CTRL extends Stageable(XTLB_OP())

  override def setup(pipeline: VexRiscv): Unit = {
    import pipeline.config._
    val decoder = pipeline.service(classOf[DecoderService])

    decoder.addDefault(XTLB_CTRL, XTLB_OP.NONE)

    def common = List(
      BYPASSABLE_EXECUTE_STAGE -> True,
      BYPASSABLE_MEMORY_STAGE  -> True
    )

    decoder.add(XTLB_GET_SID, common ++ List(
      XTLB_CTRL            -> XTLB_OP.GET_SID,
      REGFILE_WRITE_VALID  -> True
    ))
    decoder.add(XTLB_GET_STATUS, common ++ List(
      XTLB_CTRL            -> XTLB_OP.GET_STATUS,
      REGFILE_WRITE_VALID  -> True
    ))

    decoder.add(XTLB_SET_SID, common ++ List(
      XTLB_CTRL            -> XTLB_OP.SET_SID,
      RS1_USE              -> True
    ))
    decoder.add(XTLB_CMD, common ++ List(
      XTLB_CTRL            -> XTLB_OP.CMD,
      RS1_USE              -> True,
      HAS_SIDE_EFFECT      -> True
    ))
    decoder.add(XTLB_SET_ALLOC_SID, common ++ List(
      XTLB_CTRL            -> XTLB_OP.SET_ALLOC_SID,
      RS1_USE              -> True
    ))
    decoder.add(XTLB_SET_FREE_SID, common ++ List(
      XTLB_CTRL            -> XTLB_OP.SET_FREE_SID,
      RS1_USE              -> True
    ))
    decoder.add(XTLB_SET_FLUSH_SID, common ++ List(
      XTLB_CTRL            -> XTLB_OP.SET_FLUSH_SID,
      RS1_USE              -> True
    ))
    decoder.add(XTLB_SYNC_PID, common ++ List(
      XTLB_CTRL            -> XTLB_OP.SYNC_PID,
      HAS_SIDE_EFFECT      -> True
    ))
  }

  override def build(pipeline: VexRiscv): Unit = {
    import pipeline._
    import pipeline.config._

    val tlb = pipeline.service(classOf[TlbPartitionInterface])

    execute plug new Area {
      val op = execute.input(XTLB_CTRL)
      val rs1 = execute.input(RS1).asUInt

      // Defaults: no-op on interface.
      tlb.setCurrentSid(rs1, False)
      tlb.setAllocSid(rs1, False)
      tlb.setFreeSid(rs1, False)
      tlb.setFlushSid(rs1, False)
      tlb.setTriggers(False, False, False, False)
      tlb.requestPidSync(False)

      // Read paths
      when(op === XTLB_OP.GET_SID) {
        execute.output(REGFILE_WRITE_DATA) := tlb.currentSid.resize(32).asBits
      }
      when(op === XTLB_OP.GET_STATUS) {
        execute.output(REGFILE_WRITE_DATA) := tlb.status
      }

      // Write paths
      when(op === XTLB_OP.SET_SID) {
        tlb.setCurrentSid(rs1, True)
      }
      when(op === XTLB_OP.SET_ALLOC_SID) {
        tlb.setAllocSid(rs1, True)
      }
      when(op === XTLB_OP.SET_FREE_SID) {
        tlb.setFreeSid(rs1, True)
      }
      when(op === XTLB_OP.SET_FLUSH_SID) {
        tlb.setFlushSid(rs1, True)
      }

      when(op === XTLB_OP.CMD) {
        tlb.setTriggers(
          alloc = rs1(0),
          free = rs1(1),
          flushSid = rs1(2),
          flushAll = rs1(3)
        )
      }

      when(op === XTLB_OP.SYNC_PID && execute.arbitration.isValid){
        tlb.requestPidSync(True)
        execute.arbitration.haltItself := tlb.pidSyncBusy
      }
    }
  }
}

