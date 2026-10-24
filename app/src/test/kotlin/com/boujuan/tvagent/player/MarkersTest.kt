package com.boujuan.tvagent.player

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Skip button, auto skips and the Next episode card from tv-agent's markers (MarkerWatch). */
class MarkersTest {
    private val recap = Marker("recap", 8_000, 49_000, auto = false)
    private val intro = Marker("intro", 384_000, 409_000, auto = true)
    private val credits = Marker("credits", 2_521_000, 2_582_000, auto = false)
    private val next = NextUp("S1E4 · Please, Carol", 10_000)

    @Test
    fun anAutoIntroIsSkippedWhenPlayingReachesItOnce() {
        val w = MarkerWatch(listOf(recap, intro, credits), next)
        assertNull(w.at(383_000).skip)
        assertSame(intro, w.at(384_100).skip)
        w.at(300_000, seek = true)                                   // back before it, and played again
        assertNull(w.at(384_200).skip)                               // once per load
    }

    @Test
    fun aSeekIntoAnAutoIntroDoesntSkipIt() {
        val w = MarkerWatch(listOf(intro), null)
        w.at(100_000)
        assertNull(w.at(390_000, seek = true).skip)
        assertNull(w.at(391_000).skip)                               // and playing on inside it doesn't either
    }

    @Test
    fun theButtonShowsInsideAMarkerUntilTwoSecondsBeforeItsEnd() {
        val w = MarkerWatch(listOf(recap, intro), null)
        assertNull(w.at(7_000).button)
        assertSame(recap, w.at(8_000).button)
        assertSame(recap, w.at(46_900).button)
        assertNull(w.at(47_000).button)
        assertNull(w.at(390_000, seek = true).button)                // an auto one has no button
    }

    @Test
    fun backHidesAButtonForTheLoad() {
        val w = MarkerWatch(listOf(recap), null)
        w.hide(w.at(10_000).button!!)
        assertNull(w.at(11_000).button)
    }

    @Test
    fun theCardShowsFromTheCreditsWhenThereIsANextOneUntilDismissed() {
        val w = MarkerWatch(listOf(credits), next)
        assertFalse(w.at(2_520_000).card)
        assertTrue(w.at(2_521_000).card)
        assertTrue(w.at(2_560_000, seek = true).card)
        w.cardDone()
        assertFalse(w.at(2_561_000).card)
        assertFalse(MarkerWatch(listOf(credits), null).at(2_530_000).card)       // nothing next: no card
    }

    @Test
    fun markersThatMakeNoSenseAreLeftOut() {
        val list = Marker.list(JSONArray("""[{"kind":"intro","start":5,"end":3},{"kind":"ads","start":1,"end":9},
            {"kind":"credits","start":100,"end":0,"auto":true},{"kind":"recap","start":0,"end":40000}]"""))
        assertEquals(listOf("credits", "recap"), list.map { it.kind })
        assertFalse(list[0].auto)                                    // credits are never skipped by themselves
        assertEquals(384_000L, MarkerWatch(listOf(intro), null).nextAutoStart(0))
    }
}
