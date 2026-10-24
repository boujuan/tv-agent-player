package com.boujuan.tvagent.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a held ←/→ asks the agent for a picture, and which answers count (PROTOCOL.md, `thumb_req` and `thumb`). */
class SeekPreviewTest {
    private val film = 9_000_000L                                   // 2.5 hours: a 50th is 3 min

    @Test
    fun oneRequestAtATime() {
        val pace = SeekPreview.Pace()
        assertTrue(pace.ask("a#1", 600_000, film, 0))
        assertFalse(pace.ask("a#1", 900_000, film, 50))             // moved 5 min, but one is out
        assertTrue(pace.answered("a#1", 600_000))
        assertTrue(pace.ask("a#1", 900_000, film, 100))
    }

    @Test
    fun theNextOnceTheTargetHasMovedAFiftiethOrAWhileAfter() {
        val pace = SeekPreview.Pace()
        assertTrue(pace.ask("a#1", 600_000, film, 0))
        assertTrue(pace.answered("a#1", 600_000))
        assertFalse(pace.ask("a#1", 600_000, film, 1_000))          // hasn't moved
        assertFalse(pace.ask("a#1", 700_000, film, 300))            // a little, and soon
        assertTrue(pace.ask("a#1", 700_000, film, 400))             // a little, 0.4 s on
        assertTrue(pace.answered("a#1", 700_000))
        assertTrue(pace.ask("a#1", 880_000, film, 450))             // 3 min on, at once
    }

    @Test
    fun staleAnswersAreDropped() {
        val pace = SeekPreview.Pace()
        assertTrue(pace.ask("a#1", 600_000, film, 0))
        assertFalse(pace.answered("a#0", 600_000))                  // the file before
        assertFalse(pace.answered("a#1", 500_000))                  // a request before
        assertTrue(pace.answered("a#1", 600_000))
        assertFalse(pace.answered("a#1", 600_000))                  // taken once
        assertTrue(pace.ask("a#1", 900_000, film, 100))
        pace.reset()                                                // the key came up
        assertFalse(pace.answered("a#1", 900_000))
    }

    @Test
    fun anAnswerThatNeverCameIsGivenUpOn() {
        val pace = SeekPreview.Pace()
        assertTrue(pace.ask("a#1", 600_000, film, 0))
        assertFalse(pace.ask("a#1", 900_000, film, 1_999))
        assertTrue(pace.ask("a#1", 900_000, film, 2_000))
        assertFalse(pace.answered("a#1", 600_000))                  // it came after all: too late
    }

    @Test
    fun anotherFileStartsAfresh() {
        val pace = SeekPreview.Pace()
        assertTrue(pace.ask("a#1", 600_000, film, 0))
        assertTrue(pace.ask("b#2", 600_000, film, 10))
        assertEquals("b#2", pace.item)
        assertFalse(pace.ask("b#2", 600_000, 0, 5_000))             // no length known: no pictures
        assertFalse(pace.ask(null, 700_000, film, 5_000))
    }
}
