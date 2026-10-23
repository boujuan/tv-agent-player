package com.boujuan.tvagent.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * How much of a file is held in memory (DESIGN.md, Memory): FORWARD_MS ahead and BACK_MS behind at the file's
 * bitrate, so "skip 30 seconds" and "back 10" stay inside what's loaded, within a budget the TV can spare.
 */
object Budget {
    const val FORWARD_MS = 40_000
    const val MIN_FORWARD_MS = 30_000        // below this much ahead, loading starts again
    const val BACK_MS = 12_000               // "back 10" (the agent's default) plus a keyframe interval
    const val START_MS = 1_000               // buffered before the first start (Media3's default)
    const val RESTART_MS = 2_000             // and after running dry
    const val CEILING = 256 shl 20
    const val PRELOAD = 32 shl 20            // the next episode's first seconds
    private const val FLOOR = 32L shl 20

    /** Bytes for a file of `bitrate` bit/s (0: unknown, so the whole budget). */
    fun bytes(bitrate: Long, heapLimit: Long, available: Long): Long {
        val budget = maxOf(FLOOR, minOf(CEILING.toLong(), heapLimit * 45 / 100, available * 35 / 100))
        if (bitrate <= 0) return budget
        return (bitrate / 8 * (FORWARD_MS + BACK_MS) / 1000).coerceIn(FLOOR, budget)
    }
}

/**
 * Media3's DefaultLoadControl (time thresholds, the back buffer, per-player accounting, the preload's own budget)
 * with a byte cap that follows each file's bitrate: DefaultLoadControl's own byte targets are fixed when it's built.
 */
class Loading private constructor(private val inner: DefaultLoadControl, private val allocator: DefaultAllocator) :
    LoadControl {
    /** The playing file's cap, set per load (Budget.bytes). */
    @Volatile var cap: Long = Budget.CEILING.toLong()
        set(value) {
            field = value
            fit()
        }
    @Volatile private var playing: Allocator? = null      // the player's own share of the allocator

    /**
     * The allocator keeps freed blocks for reuse up to its target, which DefaultLoadControl sets to its own most:
     * after a big file, a small one loaded in place would keep the big one's blocks. So the target follows the
     * cap, after each of DefaultLoadControl's own updates.
     */
    private fun fit() = allocator.setTargetBufferSize((cap + Budget.PRELOAD).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

    override fun getAllocator(playerId: PlayerId): Allocator =
        inner.getAllocator(playerId).also { if (playerId != PlayerId.PRELOAD) playing = it }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val going = inner.shouldContinueLoading(parameters)       // always asked: it keeps its own loading state
        if (parameters.playerId == PlayerId.PRELOAD) return going
        return going && (playing?.totalBytesAllocated ?: 0) < cap
    }

    override fun onPrepared(playerId: PlayerId) {
        inner.onPrepared(playerId)
        fit()
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>,
    ) {
        inner.onTracksSelected(parameters, trackGroups, trackSelections)
        fit()
    }

    override fun onStopped(playerId: PlayerId) {
        inner.onStopped(playerId)
        fit()
    }

    override fun onReleased(playerId: PlayerId) = inner.onReleased(playerId)
    override fun getBackBufferDurationUs(playerId: PlayerId) = inner.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId) = inner.retainBackBufferFromKeyframe(playerId)
    override fun shouldStartPlayback(parameters: LoadControl.Parameters) = inner.shouldStartPlayback(parameters)
    override fun shouldContinuePreloading(
        playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaPeriodId, bufferedDurationUs: Long,
    ) = inner.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    companion object {
        /** One control for the player and the preload: one allocator, so one budget in memory. */
        fun build(): Loading {
            val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
            return Loading(DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(Budget.MIN_FORWARD_MS, Budget.FORWARD_MS, Budget.START_MS, Budget.RESTART_MS)
                .setBackBuffer(Budget.BACK_MS, true)
                .setTargetBufferBytes(Budget.CEILING)                         // the most; `cap` is the file's
                .setPlayerTargetBufferBytes(PlayerId.PRELOAD.name, Budget.PRELOAD)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build(), allocator)
        }
    }
}
