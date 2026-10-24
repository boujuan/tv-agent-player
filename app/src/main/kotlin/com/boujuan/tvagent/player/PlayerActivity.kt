package com.boujuan.tvagent.player

import android.app.Activity
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The player screen. tv-agent opens it (`am start`, no extras) and drives it over the link; the remote works on it
 * directly too. It plays through onPause (the voice pill and the lists open over it) and lets everything go in
 * onStop, after telling the agent where it was: the next play opens a fresh one in the still-warm process.
 */
class PlayerActivity : Activity(), Link.Listener, Engine.Host, Session.Actions {
    private lateinit var engine: Engine
    private lateinit var session: Session
    private var link: Link? = null
    private val main = Handler(Looper.getMainLooper())
    private var shut = false

    private lateinit var frame: AspectRatioFrameLayout
    private lateinit var subtitles: SubtitleView
    private lateinit var message: TextView
    private lateinit var bar: View
    private lateinit var title: TextView
    private lateinit var time: TextView
    private lateinit var duration: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Codecs.warmUp()
        setContentView(R.layout.player)
        frame = findViewById(R.id.frame)
        subtitles = findViewById(R.id.subtitles)
        message = findViewById(R.id.message)
        bar = findViewById(R.id.bar)
        title = findViewById(R.id.title)
        time = findViewById(R.id.time)
        duration = findViewById(R.id.duration)
        progress = findViewById(R.id.progress)
        subtitles.setUserDefaultStyle()
        subtitles.setUserDefaultTextSize()
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { shutDown("back") }
        engine = Engine(this, this)
        engine.attach(findViewById<SurfaceView>(R.id.surface))
        session = Session(this, engine.player, this)
        val pairing = Pairing.load(this)
        if (pairing == null) {
            say(getString(R.string.not_paired))
        } else {
            say(getString(R.string.connecting))
            link = Link(pairing, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, this).also { it.start() }
        }
        idleLater()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) shutDown("home")
    }

    override fun onDestroy() {
        shutDown("home")
        super.onDestroy()
    }

    /** `closed` with the position, the decoders freed, the link closed after it, the screen gone. Once. */
    private fun shutDown(why: String) {
        if (shut) return
        shut = true
        main.removeCallbacksAndMessages(null)
        engine.release(why)
        session.release()
        link?.close()
        // The buffer's blocks back to Android now, not once the runtime gets round to it (~30 s in the background):
        // the next app (the screensaver's Dolby Vision, another player) wants that memory.
        Thread({ Runtime.getRuntime().gc() }, "release-gc").start()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!isFinishing) finish()
    }

    // ---- the agent ----

    override fun onUp() {
        if (engine.item == null) say(null)
        engine.sendAll()
    }

    override fun onDown() {
        if (engine.item == null) say(getString(R.string.connecting))
    }

    override fun onRefused(error: String) {
        say(getString(R.string.refused, error))
    }

    override fun onCommand(cmd: JSONObject) {
        if (shut) return
        val id = cmd.opt("id")
        val t = cmd.optString("t")
        if (engine.item == null) idleLater()            // the agent is still using the screen
        try {
            when (t) {
                "load" -> {
                    engine.load(cmd)
                    say(null)
                    main.removeCallbacks(idle)
                }
                "preload" -> engine.preload(cmd)
                "play" -> engine.play()
                "pause" -> engine.pause()
                "toggle" -> engine.toggle()
                "stop" -> {
                    engine.close("stop")
                    ack(id, null, null)
                    shutDown("stop")
                    return
                }
                "seek" -> engine.seek(cmd.getLong("ms"), cmd.optString("mode").ifEmpty { null })
                "seek_by" -> engine.seekBy(cmd.getLong("ms"), cmd.optString("mode").ifEmpty { null })
                "tracks" -> return ack(id, null, engine.tracks())
                "select" -> engine.select(cmd)
                "add_sub" -> return engine.addSub(cmd) { index, error -> ack(id, error, index?.let { JSONObject().put("text", it) }) }
                "volume" -> volume(cmd)
                "status" -> return ack(id, null, engine.status())
                "osd" -> say(cmd.optString("text").ifEmpty { null }, cmd.optLong("ms", 0))
                "bench" -> {
                    Thread({
                        val result = try {
                            Bench.run(engine.http, cmd)
                        } catch (e: Exception) {
                            null
                        }
                        main.post { ack(id, if (result == null) "bench failed" else null, result) }
                    }, "bench").start()
                    return
                }
                else -> return ack(id, "unknown command $t", null)
            }
            ack(id, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "$t: ${e.javaClass.simpleName}")
            ack(id, Engine.scrub(e.message ?: e.javaClass.simpleName), null)
        }
    }

    private fun ack(id: Any?, error: String?, result: JSONObject?) {
        val o = (result ?: JSONObject()).put("t", "ack").put("id", id ?: JSONObject.NULL)
        if (error != null) o.put("error", error)
        link?.send(o)
    }

    private fun volume(cmd: JSONObject) {
        if (cmd.has("level")) engine.volume(cmd.getDouble("level"))
        if (cmd.has("system")) {
            val audio = getSystemService(AudioManager::class.java)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (cmd.getDouble("system").coerceIn(0.0, 100.0) * max / 100).roundToInt(), 0)
        }
    }

    // ---- Engine.Host ----

    override fun send(event: JSONObject) {
        link?.send(event)
        when (event.optString("t")) {
            "closed", "ended" -> idleLater()
        }
    }

    override fun onVideoAspect(ratio: Float) = frame.setAspectRatio(ratio)

    override fun onActive(active: Boolean) {
        if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onCues(cues: List<Cue>) = subtitles.setCues(cues)

    override fun onChanged() {
        session.update(engine.item?.let { it.title ?: "" })
        val going = engine.player.playWhenReady
        if (going != wasGoing) showBar(BAR_MS)          // paused: it stays; resumed: it goes after a moment
        else if (bar.visibility == View.VISIBLE) drawBar()
        wasGoing = going
    }

    private var wasGoing = true

    // ---- the remote, while the player is in front ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> if (event.repeatCount == 0) engine.toggle()
            KeyEvent.KEYCODE_MEDIA_PLAY -> engine.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> engine.pause()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> jump(event, -1)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> jump(event, 1)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> {}
            KeyEvent.KEYCODE_MEDIA_NEXT -> key("next")
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> key("previous")
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                shutDown("stop")
                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        showBar(BAR_MS)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (scrub == C.TIME_UNSET || keyCode !in JUMP_KEYS) return super.onKeyUp(keyCode, event)
        if (event.isCanceled) dropScrub() else letGo()       // canceled: another window took the key mid-hold
        return true
    }

    /**
     * A press jumps at once (−10 s / +30 s). Held, the target glides along the bar without seeking, faster the longer
     * it's held, and one exact seek goes there when the key is let go: a seek per step cancelled the one before's read,
     * so neither the picture nor the time moved until the key came up (2026-10-24).
     */
    private fun jump(event: KeyEvent, direction: Int) {
        if (event.repeatCount == 0) {
            if (scrub != C.TIME_UNSET) letGo()               // the other key while one was held
            engine.seekBy(if (direction < 0) -10_000 else 30_000, null)
            return
        }
        val now = event.eventTime
        if (scrub == C.TIME_UNSET) {
            scrub = engine.player.currentPosition            // the press's jump target while it's being sought
            movedAt = now
        }
        main.removeCallbacks(letGoLater)
        main.postDelayed(letGoLater, LET_GO_MS)
        // Moved by the time since the last repeat (~50 ms), so the bar glides however often the remote repeats.
        val dur = engine.player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        val dt = (now - movedAt).coerceIn(0, 200)
        movedAt = now
        scrub = (scrub + (direction * speed(now - event.downTime, dur) * dt).toLong()).coerceIn(0, dur)
    }

    private fun letGo() {
        val to = scrub
        dropScrub()
        if (to != C.TIME_UNSET && engine.item != null) engine.seek(to, "exact")
        showBar(BAR_MS)
    }

    private fun dropScrub() {
        scrub = C.TIME_UNSET
        main.removeCallbacks(letGoLater)
        if (bar.visibility == View.VISIBLE) drawBar()
    }

    private var scrub = C.TIME_UNSET                         // the target while ← or → is held
    private var movedAt = 0L
    // No key-up seen (the remote's lost, a key-up that never came): the hold ends where it got to.
    private val letGoLater = Runnable { letGo() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus && scrub != C.TIME_UNSET) dropScrub()
    }

    // ---- Session.Actions (media keys while the pill or a list is in front) ----

    override fun play() = engine.play()
    override fun pause() = engine.pause()
    override fun stop() = shutDown("stop")
    override fun seekTo(ms: Long) = engine.seek(ms, null)
    override fun seekBy(ms: Long) = engine.seekBy(ms, null)
    override fun key(name: String) {
        link?.send(msg("key", "key" to name))
    }

    // ---- what's drawn ----

    private fun say(text: String?, ms: Long = 0) {
        main.removeCallbacks(unsay)
        message.text = text
        message.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (!text.isNullOrEmpty() && ms > 0) main.postDelayed(unsay, ms)
    }

    private val unsay = Runnable { say(null) }

    /** The time bar, for `ms` while playing; while paused it stays. */
    private fun showBar(ms: Long) {
        if (engine.item == null) return
        drawBar()
        bar.visibility = View.VISIBLE
        main.removeCallbacks(hideBar)
        if (engine.player.playWhenReady) main.postDelayed(hideBar, ms)
    }

    private val hideBar = Runnable {
        if (engine.player.playWhenReady) bar.visibility = View.GONE
    }

    private fun drawBar() {
        val p = engine.player
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        val at = if (scrub != C.TIME_UNSET) scrub else p.currentPosition
        val what = when {
            scrub != C.TIME_UNSET -> (if (scrub < p.currentPosition) "−" else "+") + clock(abs(scrub - p.currentPosition))
            !p.playWhenReady -> getString(R.string.paused)
            else -> null
        }
        title.text = listOfNotNull(engine.item?.title, what).joinToString(" · ")
        time.text = clock(at)
        duration.text = if (dur > 0) clock(dur) else ""
        progress.progress = if (dur > 0) (at * 1000 / dur).toInt() else 0
        progress.secondaryProgress = if (dur > 0) (p.bufferedPosition * 1000 / dur).toInt() else 0
        if (p.playbackState == Player.STATE_ENDED) bar.visibility = View.GONE
    }

    // Nothing loaded for a minute (the agent gave up, or it ended and nothing followed): the screen goes.
    private val idle = Runnable {
        if (engine.item == null || engine.player.playbackState == Player.STATE_ENDED) shutDown("idle")
    }

    private fun idleLater() {
        main.removeCallbacks(idle)
        main.postDelayed(idle, IDLE_MS)
    }

    companion object {
        private const val TAG = "Player"
        private const val BAR_MS = 4_000L
        private const val IDLE_MS = 60_000L
        private const val LET_GO_MS = 700L
        private val JUMP_KEYS = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)

        /**
         * How fast the target moves (film per real time) a hold that began `heldMs` ago: 20× when the repeats start,
         * doubling every second, up to a 15th of the file a second (8 min/s for a 2-hour film; at least 90×). Half an
         * hour of a 2-hour film takes ~7.5 s held; the first seconds stay fine enough to place a scene.
         */
        fun speed(heldMs: Long, durMs: Long): Double {
            val most = maxOf(90.0, durMs.coerceAtMost(24 * 3_600_000L) / 15_000.0)
            return minOf(most, 20.0 * Math.pow(2.0, (heldMs - 500).coerceAtLeast(0) / 1000.0))
        }

        fun clock(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}
