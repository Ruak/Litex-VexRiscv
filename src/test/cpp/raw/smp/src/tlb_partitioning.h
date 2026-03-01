#ifndef TLB_PARTITIONING_H
#define TLB_PARTITIONING_H

#include <stdint.h>

/* Must match Riscv.scala: 0x50x so csr[9:8]=00 => U/S/M can access */
#define CSR_TLB_SID       0x500
#define CSR_TLB_CMD       0x501
#define CSR_TLB_ALLOC_SID 0x502
#define CSR_TLB_FREE_SID  0x503
#define CSR_TLB_FLUSH_SID 0x504
#define CSR_TLB_STATUS    0x505

static inline void csr_write_u32(uint32_t csr, uint32_t value) {
  asm volatile ("csrw %0, %1" :: "i"(csr), "r"(value));
}

static inline uint32_t csr_read_u32(uint32_t csr) {
  uint32_t value;
  asm volatile ("csrr %0, %1" : "=r"(value) : "i"(csr));
  return value;
}

static inline void tlb_set_sid(uint32_t sid) {
  csr_write_u32(CSR_TLB_SID, sid);
}

static inline uint32_t tlb_get_sid(void) {
  return csr_read_u32(CSR_TLB_SID);
}

static inline uint32_t tlb_domain_alloc(uint32_t sid) {
  csr_write_u32(CSR_TLB_ALLOC_SID, sid);
  csr_write_u32(CSR_TLB_CMD, 1u << 0);
  return csr_read_u32(CSR_TLB_STATUS);
}

static inline uint32_t tlb_domain_free(uint32_t sid) {
  csr_write_u32(CSR_TLB_FREE_SID, sid);
  csr_write_u32(CSR_TLB_CMD, 1u << 1);
  return csr_read_u32(CSR_TLB_STATUS);
}

static inline uint32_t tlb_domain_flush(uint32_t sid) {
  csr_write_u32(CSR_TLB_FLUSH_SID, sid);
  csr_write_u32(CSR_TLB_CMD, 1u << 2);
  return csr_read_u32(CSR_TLB_STATUS);
}

static inline void tlb_flush_all(void) {
  csr_write_u32(CSR_TLB_CMD, 1u << 3);
}

#endif
