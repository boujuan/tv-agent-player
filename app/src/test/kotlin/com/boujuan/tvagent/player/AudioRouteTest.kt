package com.boujuan.tvagent.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioRouteTest {
    private val dts = Format.Builder().setSampleMimeType("audio/vnd.dts.hd").build()

    @Test
    fun aDeadTrackIsTold() {
        // 2026-10-24 01:37: "AudioTrack write failed: -6"
        assertEquals("dead_object", AudioRoute.cause(AudioSink.WriteException(-6, dts, true)))
        assertEquals("dead_object", AudioRoute.cause(AudioSink.WriteException(-32, dts, true)))
        assertEquals("write_failed", AudioRoute.cause(AudioSink.WriteException(-3, dts, false)))
        assertNull(AudioRoute.cause(AudioSink.UnexpectedDiscontinuityException(1_000_000, 0)))
    }

    @Test
    fun theRouteIsAskedWithAttributesThatDifferOnlyInWhoMayCapture() {
        val movie = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
        val nudged = AudioRoute.nudged(movie)
        assertNotEquals(movie, nudged)
        assertEquals(movie, nudged.buildUpon().setAllowedCapturePolicy(movie.allowedCapturePolicy).build())
        assertEquals(movie.allowedCapturePolicy, AudioRoute.nudged(nudged).allowedCapturePolicy)
    }
}
