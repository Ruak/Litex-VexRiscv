#include <stdint.h>
#include "tlb_partitioning.h"

// Minimal bare-metal style probe helper.
static inline uint32_t rdcycle(void) {
  uint32_t v;
  asm volatile ("rdcycle %0" : "=r"(v));
  return v;
}

static uint32_t touch_pages(volatile uint32_t *base, uint32_t words, uint32_t step) {
  uint32_t start = rdcycle();
  for(uint32_t i = 0; i < words; i += step) {
    (void)base[i];
  }
  return rdcycle() - start;
}

// This function is intended to be called by a dedicated test task/thread.
// It demonstrates SID programming and a simple access-latency measurement.
uint32_t tlb_partitioning_probe(volatile uint32_t *base, uint32_t words, uint32_t sid) {
  if(sid != 0) {
    tlb_domain_alloc(sid);
  }
  tlb_set_sid(sid);
  uint32_t dt = touch_pages(base, words, 1024);
  tlb_domain_flush(sid);
  tlb_set_sid(0);
  return dt;
}
