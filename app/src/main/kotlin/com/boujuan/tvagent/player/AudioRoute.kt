package com.boujuan.tvagent.player

import android.content.Context
import android.media.AudioTrack
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Media3's own audio output (AudioTrackAudioOutputProvider), with a way to read the route's formats again.
 *
 * When the TV's audio HAL kills a passthrough AudioTrack (`write` gives ERROR_DEAD_OBJECT: Oppenheimer's DTS-HD,
 * 2026-10-24 01:37), Media3 recovers, but from then on takes the route for stereo PCM only, until an HDMI or audio
 * device change says otherwise. On the TV's own speakers none comes, so the film went on decoded to PCM. Engine
 * calls refresh() once the route reports the format again, then re-enables the audio renderer alone.
 */
class AudioRoute(context: Context) : AudioOutputProvider {
    private val inner = AudioTrackAudioOutputProvider.Builder(context).build()
    private val listeners = CopyOnWriteArrayList<AudioOutputProvider.Listener>()
    // The rest is the playback thread's, like the provider itself.
    private var quiet = false
    private var last: AudioOutputProvider.FormatConfig? = null
    private val forward = AudioOutputProvider.Listener { if (!quiet) listeners.forEach { it.onFormatSupportChanged() } }

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        last = formatConfig
        return inner.getFormatSupport(formatConfig)
    }

    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        last = formatConfig
        return inner.getOutputConfig(formatConfig)
    }

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput = inner.getAudioOutput(config)

    override fun addListener(listener: AudioOutputProvider.Listener) {
        if (listeners.isEmpty()) inner.addListener(forward)
        listeners += listener
    }

    override fun removeListener(listener: AudioOutputProvider.Listener) {
        listeners -= listener
    }

    override fun setClock(clock: Clock) = inner.setClock(clock)
    override fun hasPendingReleases() = inner.hasPendingReleases()
    override fun release() = inner.release()

    /**
     * On the playback thread: Media3's stand-in for the route replaced by what the route says now. The player isn't
     * told: it would seek every renderer, the picture's too, where Engine re-enables only the audio. Media3 reads the
     * route again when the audio attributes change, so it's asked once with a difference that changes nothing for
     * the sound (who may capture it) and once as it was.
     */
    fun refresh() {
        val c = last ?: return
        quiet = true
        try {
            inner.getFormatSupport(c.buildUpon().setAudioAttributes(nudged(c.audioAttributes)).build())
            inner.getFormatSupport(c)
        } finally {
            quiet = false
        }
    }

    companion object {
        fun nudged(a: AudioAttributes): AudioAttributes = a.buildUpon().setAllowedCapturePolicy(
            if (a.allowedCapturePolicy == C.ALLOW_CAPTURE_BY_ALL) C.ALLOW_CAPTURE_BY_SYSTEM else C.ALLOW_CAPTURE_BY_ALL).build()

        /** The protocol's name for an audio sink error that can cost the bitstream, or null for one that can't. */
        fun cause(e: Exception): String? = when (e) {
            is AudioSink.WriteException ->
                if (e.errorCode == AudioTrack.ERROR_DEAD_OBJECT || e.errorCode == NATIVE_DEAD_OBJECT) "dead_object"
                else "write_failed"
            is AudioSink.InitializationException -> "init_failed"
            else -> null                                    // e.g. a timestamp jump: nothing lost
        }

        private const val NATIVE_DEAD_OBJECT = -32           // what some devices give for ERROR_DEAD_OBJECT
    }
}
