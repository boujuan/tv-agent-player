package com.boujuan.tvagent.player

import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing itself is Media3's (it needs Android to run, so it's checked on the TV); the rest is here. */
class ExternalSubsTest {
    private val srt = "1\n00:00:01,000 --> 00:00:03,000\n¿Dónde está?\n\n2\n00:00:05,000 --> 00:00:07,500\nAquí.\n"

    private fun event(text: String, fromS: Double, toS: Double) =
        CuesWithTiming(ImmutableList.of(Cue.Builder().setText(text).build()), (fromS * 1e6).toLong(),
            ((toS - fromS) * 1e6).toLong())

    private val events = listOf(event("¿Dónde está?", 1.0, 3.0), event("Aquí.", 5.0, 7.5), event("Sí", 7.0, 9.0))

    private fun texts(posS: Double) = ExternalSubs.active(events, (posS * 1e6).toLong()).map { it.cues[0].text.toString() }

    @Test
    fun theLinesAtATime() {
        assertTrue(texts(0.5).isEmpty())
        assertEquals(listOf("¿Dónde está?"), texts(2.0))
        assertTrue(texts(4.0).isEmpty())
        assertEquals(listOf("Aquí.", "Sí"), texts(7.2))        // overlapping lines (SSA) are both shown
        assertEquals(listOf("Sí"), texts(8.0))
    }

    @Test
    fun onlyChangesAreReported() {
        val subs = ExternalSubs()
        subs.tracks += ExternalSubs.Track("spa", "Spanish", "srt", events)
        subs.shown = 0
        assertEquals(1, subs.cuesAt(2_000_000)!!.size)
        assertNull(subs.cuesAt(2_500_000))                // the same line: nothing to redraw
        assertEquals(0, subs.cuesAt(4_000_000)!!.size)
        subs.shown = -1
        assertNull(subs.cuesAt(6_000_000))                // hidden, and nothing was shown
    }

    @Test
    fun legacyCharsetsAreRead() {
        assertEquals(srt, ExternalSubs.decode(srt.toByteArray(Charsets.UTF_8), "spa"))
        assertEquals(srt, ExternalSubs.decode(srt.toByteArray(charset("windows-1252")), "spa"))
        assertEquals("简体中文", ExternalSubs.decode("简体中文".toByteArray(charset("GB18030")), "chs"))
    }

    @Test
    fun formatGuessedFromContent() {
        assertEquals("vtt", ExternalSubs.guess("\uFEFFWEBVTT\n\n00:01.000 --> 00:02.000\nHi"))
        assertEquals("ass", ExternalSubs.guess("[Script Info]\nTitle: x"))
        assertEquals("srt", ExternalSubs.guess(srt))
    }

    @Test
    fun aDelaySetByHandWinsOverTheAgentsMeasurementWhichOnlyAddedTracksHave() {
        assertEquals(0L, ExternalSubs.delay(null, null))                 // a file's own track: none unless by hand
        assertEquals(-500L, ExternalSubs.delay(null, -500))              // an added track as measured
        assertEquals(300L, ExternalSubs.delay(300, -500))                // set by hand for this file
        assertEquals(0L, ExternalSubs.delay(0, -500))                    // set back to none by hand: stays none
    }

    @Test
    fun aMeasurementCantMoveSubtitlesFar() {
        assertEquals(10_000L, ExternalSubs.measured(600_000))
        assertEquals(-10_000L, ExternalSubs.measured(-45_000))
        assertEquals(-2_300L, ExternalSubs.measured(-2_300))
    }
}
