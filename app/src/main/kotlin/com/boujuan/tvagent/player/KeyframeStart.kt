package com.boujuan.tvagent.player

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.StreamKey
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.util.ReleasableExecutor
import androidx.media3.extractor.text.SubtitleParser
import com.google.common.base.Supplier

/**
 * A file started from a position (a resume) begins at the keyframe at or before it. Media3 starts a file exactly
 * where it's told: it reads from that keyframe and decodes every frame up to the position without showing them,
 * which cost up to 2.2 s and 25 MB before the first frame 5 s past a keyframe (Breaking Bad S2E4 from 0:05,
 * 2026-10-25), where from 0:00 or 0:10 it was 0.4-0.6 s. Seek modes only apply once a file is prepared, so the
 * file's period moves its first start itself: on its first track selection, once its index is read, to the
 * keyframe the index gives, at most MAX_EARLY_US earlier (a file without an index starts where it was told).
 * A `load` with `exact` (a jump to a moment) starts exactly; seeks keep their own modes (Engine.seek).
 */
object KeyframeStart {
    /** The load's wish, read when its file's period first selects tracks (on the playback thread). */
    class Rule {
        @Volatile var exact = false
    }

    private const val MAX_EARLY_US = 10_000_000L    // a keyframe farther back than this: started exactly
    private const val TAG = "Start"

    /** The keyframe start to use for a first start at `positionUs`, or `positionUs` itself. */
    fun start(positionUs: Long, keyframeUs: Long, exact: Boolean): Long =
        if (positionUs > 0 && !exact && keyframeUs in (positionUs - MAX_EARLY_US)..positionUs) keyframeUs else positionUs

    /** The sources Media3 makes, each wrapped so its period starts at a keyframe. */
    class Factory(private val inner: MediaSource.Factory, private val rule: Rule) : MediaSource.Factory {
        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = apply {
            inner.setDrmSessionManagerProvider(provider)
        }

        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = apply {
            inner.setLoadErrorHandlingPolicy(policy)
        }

        // The optional settings too, so whatever Media3 sets on the factory reaches the one that makes the sources
        override fun setCmcdConfigurationFactory(factory: CmcdConfiguration.Factory) = apply {
            inner.setCmcdConfigurationFactory(factory)
        }

        override fun experimentalParseSubtitlesDuringExtraction(parse: Boolean) = apply {
            inner.experimentalParseSubtitlesDuringExtraction(parse)
        }

        override fun setSubtitleParserFactory(factory: SubtitleParser.Factory) = apply {
            inner.setSubtitleParserFactory(factory)
        }

        override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecFlags: Int) = apply {
            inner.experimentalSetCodecsToParseWithinGopSampleDependencies(codecFlags)
        }

        override fun setDownloadExecutor(supplier: Supplier<ReleasableExecutor>) = apply {
            inner.setDownloadExecutor(supplier)
        }

        override fun getSupportedTypes(): IntArray = inner.supportedTypes
        override fun createMediaSource(mediaItem: MediaItem): MediaSource = Source(inner.createMediaSource(mediaItem), rule)
    }

    private class Source(inner: MediaSource, private val rule: Rule) : WrappingMediaSource(inner) {
        override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
            Period(super.createPeriod(id, allocator, startPositionUs), rule)

        override fun releasePeriod(mediaPeriod: MediaPeriod) = super.releasePeriod((mediaPeriod as Period).inner)
    }

    /** Media3's period for the file, its first start moved to the keyframe; everything else passed through. */
    private class Period(val inner: MediaPeriod, private val rule: Rule) : MediaPeriod, MediaPeriod.Callback {
        private var callback: MediaPeriod.Callback? = null
        private var selected = false

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            this.callback = callback
            inner.prepare(this, positionUs)
        }

        override fun onPrepared(mediaPeriod: MediaPeriod) {
            callback?.onPrepared(this)
        }

        override fun onContinueLoadingRequested(source: MediaPeriod) {
            callback?.onContinueLoadingRequested(this)
        }

        override fun selectTracks(selections: Array<out ExoTrackSelection?>, mayRetainStreamFlags: BooleanArray,
                                  streams: Array<SampleStream?>, streamResetFlags: BooleanArray, positionUs: Long): Long {
            var at = positionUs
            if (!selected) {
                selected = true
                if (positionUs > 0 && !rule.exact) {
                    at = start(positionUs, inner.getAdjustedSeekPositionUs(positionUs, SeekParameters.PREVIOUS_SYNC), false)
                    if (at != positionUs) Log.i(TAG, "from the keyframe ${(positionUs - at) / 1000} ms before the position")
                }
            }
            return inner.selectTracks(selections, mayRetainStreamFlags, streams, streamResetFlags, at)
        }

        override fun maybeThrowPrepareError() = inner.maybeThrowPrepareError()
        override fun getTrackGroups(): TrackGroupArray = inner.trackGroups
        override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> = inner.getStreamKeys(trackSelections)
        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = inner.discardBuffer(positionUs, toKeyframe)
        override fun readDiscontinuity(): Long = inner.readDiscontinuity()
        override fun setUsesStreamPrerollFlags() = inner.setUsesStreamPrerollFlags()
        override fun seekToUs(positionUs: Long): Long = inner.seekToUs(positionUs)
        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
            inner.getAdjustedSeekPositionUs(positionUs, seekParameters)
        override fun getBufferedPositionUs(): Long = inner.bufferedPositionUs
        override fun getNextLoadPositionUs(): Long = inner.nextLoadPositionUs
        override fun continueLoading(loadingInfo: LoadingInfo): Boolean = inner.continueLoading(loadingInfo)
        override fun isLoading(): Boolean = inner.isLoading
        override fun reevaluateBuffer(positionUs: Long) = inner.reevaluateBuffer(positionUs)
        override fun setEndPositionUs(endPositionUs: Long): Long = inner.setEndPositionUs(endPositionUs)
    }
}
