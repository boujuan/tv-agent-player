package com.boujuan.tvagent.player

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/**
 * Android's own media session (not media3-session, a library half the size of the player for the same thing): the
 * remote's media keys while the voice pill is in front, Google TV's "now playing", and `dumpsys media_session`.
 * Next and previous go to the agent, which knows what comes next.
 */
class Session(context: Context, private val player: ExoPlayer, private val actions: Actions) {

    interface Actions {
        fun play()
        fun pause()
        fun stop()
        fun seekTo(ms: Long)
        fun seekBy(ms: Long)
        fun key(name: String)
    }

    private val session = MediaSession(context, "TV Agent Player").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = actions.play()
            override fun onPause() = actions.pause()
            override fun onStop() = actions.stop()
            override fun onSeekTo(pos: Long) = actions.seekTo(pos)
            override fun onFastForward() = actions.seekBy(30_000)
            override fun onRewind() = actions.seekBy(-10_000)
            override fun onSkipToNext() = actions.key("next")
            override fun onSkipToPrevious() = actions.key("previous")
        })
    }
    private var title: String? = null
    private var duration = -1L
    private var lastState = -1
    private var lastSpeed = 0f
    private var lastPos = 0L
    private var lastAt = 0L

    /** The title and length (when they change) and the state (as of now: readers extrapolate the position). */
    fun update(title: String?) {
        val dur = player.duration.let { if (it == C.TIME_UNSET) -1L else it }
        if (title != this.title || dur != duration) {
            this.title = title
            duration = dur
            session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur).build())
        }
        val state = when {
            title == null -> PlaybackState.STATE_NONE
            player.playbackState == Player.STATE_BUFFERING -> PlaybackState.STATE_BUFFERING
            player.playbackState == Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
            player.isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        val speed = if (player.isPlaying) player.playbackParameters.speed else 0f
        val pos = player.currentPosition
        val now = SystemClock.elapsedRealtime()
        // Same state and speed, and the position where readers' extrapolation puts it (no seek): nothing to tell.
        if (state == lastState && speed == lastSpeed && abs(lastPos + ((now - lastAt) * speed).toLong() - pos) < 1500) return
        lastState = state
        lastSpeed = speed
        lastPos = pos
        lastAt = now
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or
                PlaybackState.ACTION_REWIND or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(state, pos, speed, now)
            .build())
        session.isActive = state != PlaybackState.STATE_NONE
    }

    fun release() = session.release()
}
