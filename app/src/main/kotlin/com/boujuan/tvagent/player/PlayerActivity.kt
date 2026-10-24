package com.boujuan.tvagent.player

import android.app.Activity
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewStub
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.SubtitleView
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The player screen. tv-agent opens it (`am start`, no extras) and drives it over the link; the remote works on it
 * directly too. It plays through onPause (the voice pill and the lists open over it) and lets everything go in
 * onStop, after telling the agent where it was: the next play opens a fresh one in the still-warm process.
 * ExternalPlayerActivity is the same screen playing another app's link by itself.
 */
open class PlayerActivity : Activity(), Link.Listener, Engine.Host, Session.Actions {
    /** Playing another app's link by itself (ExternalPlayerActivity): no tv-agent, the caller gets the position. */
    protected open val standalone = false

    private lateinit var engine: Engine
    private lateinit var session: Session
    private var link: Link? = null
    private val main = Handler(Looper.getMainLooper())
    private var shut = false

    private lateinit var frame: AspectRatioFrameLayout
    private lateinit var subtitles: SubtitleView
    private lateinit var message: TextView
    private lateinit var bar: View
    private lateinit var barTop: View
    private lateinit var title: TextView
    private lateinit var time: TextView
    private lateinit var progress: DefaultTimeBar
    private lateinit var barBadges: LinearLayout
    private lateinit var controls: Controls
    private lateinit var artwork: Artwork
    private lateinit var loading: LoadingScreen
    private lateinit var pause: PauseScreen
    private lateinit var settings: Settings
    private lateinit var preview: SeekPreview

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Codecs.warmUp()
        setContentView(R.layout.player)
        frame = findViewById(R.id.frame)
        subtitles = findViewById(R.id.subtitles)
        message = findViewById(R.id.message)
        bar = findViewById(R.id.bar)
        barTop = findViewById(R.id.bar_top)
        title = findViewById(R.id.title)
        time = findViewById(R.id.time)
        progress = findViewById(R.id.progress)
        progress.isFocusable = false                    // it takes ←/→ when focused (dispatchKeyEvent)
        barBadges = findViewById(R.id.bar_badges)
        bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeSubtitles() }   // shown, hidden, or a row more or less
        settings = Settings(this)
        settings.styleSubtitles(subtitles)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            if (!controls.back()) shutDown("back")
        }
        engine = Engine(this, this)
        engine.attach(findViewById<SurfaceView>(R.id.surface))
        artwork = Artwork(engine.http.userAgent)
        loading = LoadingScreen(findViewById<ViewStub>(R.id.loading_stub), artwork)
        pause = PauseScreen(findViewById<ViewStub>(R.id.pause_stub), artwork, engine)
        controls = Controls(findViewById(R.id.controls), findViewById(R.id.menu_stub), findViewById(R.id.subs_stub),
            engine, settings, controlsHost)
        preview = SeekPreview(findViewById(R.id.preview_stub)) { link?.send(it) }
        session = Session(this, engine.player, this)
        if (standalone) {
            open(intent)
            idleLater()
            return
        }
        val paired = Pairing.load(this)
        pairing = paired
        if (paired == null) {
            say(getString(R.string.not_paired))
        } else {
            say(getString(R.string.connecting))
            link = Link(paired, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, this).also { it.start() }
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
        if (standalone) setResult(RESULT_OK, result())
        main.removeCallbacksAndMessages(null)
        // Left while a start was on the way (its loading screen up, its file not loaded yet): the agent hears which
        // start it was, so it stops it, where it used to try loading it into the closed screen and fail (16:55, 2026-10-24).
        loading.item?.takeIf { loading.showing && it != engine.item?.id }?.let { starting ->
            link?.send(msg("closed", "item" to starting, "why" to why, "pos" to 0, "dur" to 0))
        }
        engine.release(why)
        session.release()
        artwork.release()
        link?.close()
        // The buffer's blocks back to Android now, not once the runtime gets round to it (~30 s in the background):
        // the next app (the screensaver's Dolby Vision, another player) wants that memory.
        Thread({ Runtime.getRuntime().gc() }, "release-gc").start()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!isFinishing) finish()
    }

    // ---- another app's link (ExternalPlayerActivity) ----

    /**
     * The caller's link (an http(s) video: Stremio's own server on 127.0.0.1:11470, or a debrid link), from where it
     * says: `position` or `startfrom`, in ms, as VLC and MX Player take them. No title comes with it.
     */
    private fun open(intent: Intent) {
        val uri = intent.data?.takeIf { it.scheme == "http" || it.scheme == "https" }
        if (uri == null) {
            say(getString(R.string.not_a_link))
            return
        }
        val start = listOf("position", "startfrom").firstNotNullOfOrNull { k ->
            @Suppress("DEPRECATION") (intent.extras?.get(k) as? Number)?.toLong()
        } ?: 0
        engine.load(JSONObject().put("url", uri.toString()).put("start_ms", start).put("title", titleOf(uri)))
    }

    /** The file's name from the link, when it has one ("Dune.Part.Two.2024.2160p…mkv"; Stremio's own links don't). */
    private fun titleOf(uri: Uri): String? = uri.lastPathSegment?.substringBeforeLast('.')
        ?.takeIf { name -> name.length > 3 && name.any { it.isLetter() } }?.replace('.', ' ')

    /**
     * What the caller gets back, as MX Player gives it: the position and length (Stremio saves them as progress,
     * reading `extra_position` first), and `end_by` "playback_completion" at the end (Stremio then goes on to the
     * next episode).
     */
    private fun result(): Intent {
        val p = engine.player
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        val ended = p.playbackState == Player.STATE_ENDED
        val pos = if (ended) dur else p.currentPosition.coerceAtLeast(0)
        return Intent("com.mxtech.intent.result.VIEW").putExtra("extra_position", pos).putExtra("extra_duration", dur)
            .putExtra("position", pos.toInt()).putExtra("duration", dur.toInt())
            .putExtra("end_by", if (ended) "playback_completion" else "user")
    }

    // ---- the agent ----

    override fun onUp() {
        connected = true
        if (engine.item == null) say(null)
        engine.sendAll()
    }

    override fun onDown() {
        connected = false
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
                    controls.close()
                    engine.load(cmd)
                    say(null)
                    artwork.keepOnly(engine.item?.id, loading.item)
                    main.removeCallbacks(idle)
                }
                "loading" -> {
                    controls.close()
                    pause.hide()
                    loading.show(cmd)
                    artwork.keepOnly(loading.item, engine.item?.id)
                }
                "preload" -> engine.preload(cmd)
                "play" -> engine.play()
                "pause", "toggle" -> {
                    pauseAsked = SystemClock.uptimeMillis()
                    if (t == "pause") engine.pause() else engine.toggle()
                }
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
                "thumb" -> preview.got(cmd, holding = scrub != C.TIME_UNSET)
                "osd" -> {
                    val text = cmd.optString("text").ifEmpty { null }
                    if (text == null) loading.hide()
                    say(text, cmd.optLong("ms", 0))
                }
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
        if (standalone) when (event.optString("t")) {
            "ended" -> {
                main.post { shutDown("ended") }         // the caller goes on (Stremio: to the next episode)
                return
            }
            "error" -> if (event.optBoolean("fatal")) say(getString(R.string.cant_play, event.optString("code")))
        }
        when (event.optString("t")) {
            "closed", "ended" -> idleLater()
            "tracks" -> {
                pause.tracksChanged()
                controls.tracksChanged()
            }
        }
    }

    override fun onFirstFrame() {
        loading.hide()
        showBar(BAR_MS, badges = settings[Settings.BADGES] == 1)
    }

    override fun onVideoAspect(ratio: Float) = frame.setAspectRatio(ratio)

    override fun onActive(active: Boolean) {
        if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onCues(cues: List<Cue>) {
        shownCues = cues
        if (!sampling) subtitles.setCues(cues)
    }

    /**
     * A sample line instead of the film's while a subtitle look is being picked (`on`), so its effect shows; the
     * film's own lines are hidden the rest of the time the pause screen is up (the time bar would cover them).
     */
    private fun sample(on: Boolean) {
        sampling = on
        subtitles.setCues(if (on) listOf(Cue.Builder().setText(getString(R.string.sample_subtitle)).build()) else shownCues)
        subtitles.visibility = if (on || !pause.showing) View.VISIBLE else View.INVISIBLE
    }

    /**
     * Subtitles above the time bar while it's up (in Stremio's player they ran over it), else where they're set.
     * The bar's height is known once it's laid out, so this runs then (and when it's hidden).
     */
    private fun placeSubtitles() {
        val h = subtitles.height
        val above = if (bar.visibility == View.VISIBLE && h > 0 && bar.height > 0)
            (bar.height - bar.paddingTop + SUBTITLE_GAP_DP * resources.displayMetrics.density) / h else 0f
        if (above != lifted) {
            lifted = above
            settings.styleSubtitles(subtitles, above)
        }
    }

    private var lifted = 0f

    private val controlsHost = object : Controls.Host {
        override fun jump(direction: Int) = engine.seekBy(
            if (direction < 0) -1000L * settings[Settings.JUMP_BACK] else 1000L * settings[Settings.JUMP_FORWARD], null)
        override fun episode(which: String) = key(which)
        // An episode's id is "tt…:season:episode", then the agent's "#run" part
        override fun episodes() = connected && engine.item?.id?.substringBefore('#')?.count { it == ':' } == 2
        override var picture: Int
            get() = frame.resizeMode
            set(mode) {
                frame.resizeMode = mode
            }
        override fun say(text: String) = this@PlayerActivity.say(text, SAY_MS)
        override fun sample(on: Boolean) = this@PlayerActivity.sample(on)
        override fun applied() = settings.styleSubtitles(subtitles, lifted)
        override fun agent() = pairing?.takeIf { connected }?.let { getString(R.string.about_connected, it.hosts.joinToString(", ")) }
    }

    private var shownCues: List<Cue> = emptyList()
    private var sampling = false
    private var connected = false
    private var pairing: Pairing? = null

    override fun onChanged() {
        session.update(engine.item?.let { it.title ?: "" })
        val p = engine.player
        val going = p.playWhenReady
        val paused = engine.item != null && !going && !loading.showing &&
            p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE
        if (paused) pause.show(pauseAsked.takeIf { it > 0 } ?: SystemClock.uptimeMillis()) else pause.hide()
        if (pause.showing) barBadges.visibility = View.GONE     // the pause screen has them in its corner
        if (!sampling) subtitles.visibility = if (pause.showing) View.INVISIBLE else View.VISIBLE
        pauseAsked = 0
        if (going != wasGoing) showBar(BAR_MS)          // paused: it stays; resumed: it goes after a moment
        else if (bar.visibility == View.VISIBLE) drawBar()
        wasGoing = going
    }

    private var wasGoing = true
    private var pauseAsked = 0L                     // when the pause shown next was asked for (uptime), for its log

    // ---- the remote, while the player is in front ----

    /**
     * The remote's keys come here before any view. Media3's time bar is focusable and took ←/→ to move its own
     * thumb, so with a control picked they moved the bar instead of the pick (0.6.0); no view gets them now.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in REMOTE_KEYS) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode, event)
            KeyEvent.ACTION_UP -> onKeyUp(event.keyCode, event)
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (controls.takes(keyCode, bar.visibility == View.VISIBLE)) {
            controls.onKey(keyCode)
            lastKeyAt = event.eventTime
            showBar(BAR_MS)
            return true
        }
        lastKeyAt = event.eventTime
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> if (event.repeatCount == 0) {
                pauseAsked = event.eventTime
                engine.toggle()
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> engine.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                pauseAsked = event.eventTime
                engine.pause()
            }
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
            engine.seekBy(if (direction < 0) -1000L * settings[Settings.JUMP_BACK] else 1000L * settings[Settings.JUMP_FORWARD], null)
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
        val factor = Settings.speedFactor(settings[Settings.HELD_SPEED])
        scrub = (scrub + (direction * speed(now - event.downTime, dur) * factor * dt).toLong()).coerceIn(0, dur)
        preview.want(engine.item?.id, scrub, dur.takeIf { it != Long.MAX_VALUE } ?: 0, now)
    }

    private fun letGo() {
        val to = scrub
        dropScrub()
        if (to != C.TIME_UNSET && engine.item != null) engine.seek(to, "exact")
        showBar(BAR_MS)
    }

    private fun dropScrub() {
        scrub = C.TIME_UNSET
        preview.hide()
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

    /**
     * The time bar, for `ms` while playing; while paused it stays, and while its controls are in use (for a while
     * after the last key). With `badges` (after a load) it has them too, unless the pause screen has.
     */
    private fun showBar(ms: Long, badges: Boolean = false) {
        if (engine.item == null) return
        if (badges) PauseScreen.fillBadges(barBadges, Badges.of(engine.videoFormat, engine.audioFormat, engine.item?.info?.badges.orEmpty()))
        if (badges || bar.visibility != View.VISIBLE)
            barBadges.visibility = if (badges && barBadges.childCount > 0 && !pause.showing) View.VISIBLE else View.GONE
        drawBar()
        bar.visibility = View.VISIBLE
        main.removeCallbacks(hideBar)
        if (engine.player.playWhenReady) main.postDelayed(hideBar, ms)
    }

    private val hideBar: Runnable = Runnable {
        if (!engine.player.playWhenReady) return@Runnable
        val idle = SystemClock.uptimeMillis() - lastKeyAt
        if (controls.active && idle < CONTROLS_MS) {
            main.postDelayed(hideBar, CONTROLS_MS - idle)
            return@Runnable
        }
        controls.close()
        bar.visibility = View.GONE
        barBadges.visibility = View.GONE
        placeSubtitles()
    }

    private var lastKeyAt = 0L

    private fun drawBar() {
        val p = engine.player
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        val at = if (scrub != C.TIME_UNSET) scrub else p.currentPosition
        val what = when {
            scrub != C.TIME_UNSET -> (if (scrub < p.currentPosition) "−" else "+") + clock(abs(scrub - p.currentPosition))
            !p.playWhenReady -> getString(R.string.paused)
            else -> null
        }
        // Over the pause screen the title is already there, above.
        title.text = if (pause.showing) what.takeIf { scrub != C.TIME_UNSET }
        else listOfNotNull(engine.item?.title, what).joinToString(" · ")
        barTop.visibility = if (title.text.isNullOrEmpty() && barBadges.visibility != View.VISIBLE) View.GONE else View.VISIBLE
        pause.update()
        time.text = if (dur > 0) getString(R.string.time_of, clock(at), clock(dur)) else clock(at)
        progress.setDuration(dur)
        progress.setPosition(at)
        progress.setBufferedPosition(p.bufferedPosition)
        controls.draw()
        if (p.playbackState == Player.STATE_ENDED) bar.visibility = View.GONE
        if (scrub != C.TIME_UNSET && dur > 0) placePreview(scrub, dur)
    }

    /** The held ←/→'s picture: over the target's place on the bar, above the title. */
    private fun placePreview(at: Long, dur: Long) {
        progress.getLocationInWindow(spot)
        val x = spot[0] + progress.width * (at.toFloat() / dur)
        (bar.parent as View).getLocationInWindow(spot)
        preview.place(x - spot[0], bar.top + bar.paddingTop - PREVIEW_GAP_DP * resources.displayMetrics.density)
    }

    private val spot = IntArray(2)

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
        private const val CONTROLS_MS = 10_000L           // the bar stays this long after the last key in its controls
        private const val SAY_MS = 6_000L
        private const val SUBTITLE_GAP_DP = 8
        private const val IDLE_MS = 60_000L
        private const val LET_GO_MS = 700L
        private const val PREVIEW_GAP_DP = 8
        // The keys the player screen handles itself (dispatchKeyEvent); Back has its own callback
        private val REMOTE_KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
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
