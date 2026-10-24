package com.boujuan.tvagent.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a resume starts (KeyframeStart.start): its keyframe, unless asked exact or the keyframe is far back. */
class KeyframeStartTest {
    @Test
    fun aResumeStartsAtTheKeyframeAtOrBeforeIt() {
        assertEquals(0L, KeyframeStart.start(5_000_000, 0, exact = false))                 // S2E4 from 0:05
        assertEquals(1_250_000_000L, KeyframeStart.start(1_253_400_000, 1_250_000_000, exact = false))
    }

    @Test
    fun exactOrTheBeginningOrAFarKeyframeStartWhereTold() {
        assertEquals(5_000_000L, KeyframeStart.start(5_000_000, 0, exact = true))            // a jump to a moment
        assertEquals(0L, KeyframeStart.start(0, 0, exact = false))
        assertEquals(60_000_000L, KeyframeStart.start(60_000_000, 0, exact = false))         // no index: 0 isn't a keyframe start
        assertEquals(60_000_000L, KeyframeStart.start(60_000_000, 61_000_000, exact = false))  // never later
    }
}
