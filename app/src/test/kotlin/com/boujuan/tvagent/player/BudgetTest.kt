package com.boujuan.tvagent.player

import org.junit.Assert.assertEquals
import org.junit.Test

class BudgetTest {
    private val mb = 1L shl 20
    private val heap = 512 * mb               // largeHeap on the TV
    private val free = 650 * mb               // available while playing 4K (tv-agent's player tests)

    @Test
    fun aWebDlGetsItsSecondsAhead() {
        // 25 Mbit/s: 52 s (40 ahead, 12 behind) is ~155 MB
        assertEquals(25_000_000L / 8 * 52, Budget.bytes(25_000_000, heap, free))
    }

    @Test
    fun aRemuxIsCappedByTheBudget() {
        assertEquals(free * 35 / 100, Budget.bytes(60_000_000, heap, free))      // what Android has free sets it
        assertEquals(heap * 45 / 100, Budget.bytes(60_000_000, heap, 2048 * mb))  // or the heap: ~230 MB
        assertEquals(Budget.CEILING.toLong(), Budget.bytes(60_000_000, 1024 * mb, 2048 * mb))
    }

    @Test
    fun unknownBitrateGetsTheBudgetAndTinyOnesAFloor() {
        assertEquals(free * 35 / 100, Budget.bytes(0, heap, free))
        assertEquals(32 * mb, Budget.bytes(1_000_000, heap, free))
        assertEquals(32 * mb, Budget.bytes(60_000_000, heap, 10 * mb))
    }
}
