package com.boujuan.tvagent.player

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.text.TextOutput
import org.json.JSONArray
import java.io.IOException
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The playback: one ExoPlayer and its preload manager, the agent's commands applied to them, and what happens sent
 * back as events (PROTOCOL.md). Main thread only, like ExoPlayer.
 */
class Engine(private val context: Context, private val host: Host) : Player.Listener, AnalyticsListener {

    interface Host {
        fun send(event: JSONObject)
        fun onVideoAspect(ratio: Float)
        /** Playing or about to (keep the screen on), or not. */
        fun onActive(active: Boolean)
        fun onCues(cues: List<Cue>)
        /** Position, state or title changed: the time bar and the media session follow. */
        fun onChanged()
        /** The picture of a new load is up: the loading screen goes. */
        fun onFirstFrame()
    }

    /** The file loaded now, as the agent named it, and what the pause screen says about it (`info`). */
    class Item(val url: String, val id: String?, val title: String?, val loadedAt: Long, val info: Info?)

    val http = Http(context)
    private val main = Handler(Looper.getMainLooper())
    private val loading = Loading.build()
    private val selectors = ArrayList<Selector>(2)     // the player's, then the preload's (made in that order)
    private val routes = ArrayList<AudioRoute>(2)      // the same
    private val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
    private var preloadStart = 0L
    private val delay = TextDelay()
    private val builder = DefaultPreloadManager.Builder(context) { _: Int ->
        DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(preloadStart, PRELOAD_MS)
    }
        .setDataSourceFactory(http.dataSourceFactory)
        .setRenderersFactory(object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioOutputProvider(AudioRoute(context).also(routes::add)).build()

            override fun buildTextRenderers(context: Context, output: TextOutput, outputLooper: Looper,
                                            extensionRendererMode: Int, out: ArrayList<Renderer>) {
                val from = out.size
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
                for (i in from until out.size) out[i] = Delayed(out[i], delay)
            }
        }.setMediaCodecSelector(Codecs.selector).setEnableDecoderFallback(true))
        .setTrackSelectorFactory { Selector(it).also(selectors::add) }
        .setLoadControl(loading)
    val player: ExoPlayer = builder.buildExoPlayer(
        ExoPlayer.Builder(context)
            .setAudioAttributes(attributes, true)
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setUsePlatformDiagnostics(true))         // `dumpsys media.metrics`: frames, drops, freezes per file
    private val preloads = builder.build()
    private val selector = player.trackSelector as Selector
    private val preloadSelector = selectors.first { it !== selector }
    private val route = routes.first()
    private val external = ExternalSubs()
    private val work = Executors.newSingleThreadExecutor { Thread(it, "subs").apply { priority = Thread.MIN_PRIORITY } }

    var item: Item? = null
        private set
    private var preloaded: Preload? = null
    private class Preload(val item: MediaItem, val url: String, val id: String?, val plan: Selector.Plan,
                          val info: Info?, val since: Long, var readyMs: Long = -1)

    // Timing of the current load and seek (the `timing` and `first_frame` events)
    private var stats: Http.Stats? = null
    private var preparedMs = -1L
    private var firstFrameMs = -1L
    private var usedPreload = false
    private var seekAt = 0L
    private var loadsSinceSeek = 0
    private var seekByTarget = C.TIME_UNSET
    private var seekByAt = 0L
    /** The playing video and audio formats (the badges are made from them). */
    var videoFormat: Format? = null
        private set
    private var videoDecoder: String? = null
    private var decoderInitMs = 0L
    private var bitrateKnown = false
    var audioFormat: Format? = null
        private set
    private var released = false

    init {
        http.warmUp()
        player.addListener(this)
        player.addAnalyticsListener(this)
        preloads.addListener(object : PreloadManagerListener {
            override fun onCompleted(mediaItem: MediaItem) {
                val p = preloaded?.takeIf { it.item == mediaItem } ?: return
                p.readyMs = SystemClock.elapsedRealtime() - p.since
                host.send(msg("preloaded", "item" to p.id, "ms" to p.readyMs))
            }

            override fun onError(exception: PreloadException) {
                val p = preloaded?.takeIf { it.item == exception.mediaItem } ?: return
                host.send(msg("error", "item" to p.id, "code" to "PRELOAD_FAILED",
                    "message" to scrub(exception.cause?.javaClass?.simpleName ?: "preload failed"), "fatal" to false))
                dropPreload()
            }
        })
    }

    fun attach(surface: SurfaceView) = player.setVideoSurfaceView(surface)

    // ---- commands ----

    fun load(cmd: JSONObject) {
        val url = cmd.getString("url")
        require(url.startsWith("http://") || url.startsWith("https://")) { "only http(s) links" }
        val previous = item
        close("replaced")
        if (previous != null && previous.url != url && preloaded?.url != previous.url) http.forget(previous.url)
        val now = SystemClock.elapsedRealtime()
        val start = cmd.optLong("start_ms", 0).coerceAtLeast(0)
        val ready = preloaded?.takeIf { p -> p.url == url }
        val it = Item(url, cmd.optString("item").ifEmpty { null }, cmd.optString("title").ifEmpty { null }, now,
            Info.of(cmd.optJSONObject("info")) ?: ready?.info)
        item = it
        http.route(url, headers(cmd), stackOf(cmd))
        stats = Http.Stats(url, now).also { http.stats = it }
        preparedMs = -1; firstFrameMs = -1; seekAt = 0; videoFormat = null; videoDecoder = null; audioFormat = null
        loadErrorSent = false
        forgetBitstream()
        manualDelayMs = null
        normalSpeed()
        external.clear()
        host.onCues(emptyList())
        bitrateKnown = cmd.optLong("bitrate", 0) > 0
        cap(cmd.optLong("bitrate", 0))
        selector.plan = plan(cmd)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        val source = ready?.let { p -> preloads.getMediaSource(p.item) }
        usedPreload = source != null
        if (source != null) player.setMediaSource(source, start)
        else player.setMediaItem(MediaItem.Builder().setUri(url).setMediaId(it.id ?: url.hashCode().toString()).build(), start)
        if (ready == null) dropPreload()
        player.playWhenReady = cmd.optBoolean("play", true)
        player.prepare()
        host.onChanged()
        sendPos()
    }

    fun preload(cmd: JSONObject) {
        val url = cmd.getString("url")
        require(url.startsWith("http://") || url.startsWith("https://")) { "only http(s) links" }
        dropPreload()
        http.route(url, headers(cmd), stackOf(cmd))
        val mediaItem = MediaItem.Builder().setUri(url).setMediaId("preload:" + (cmd.optString("item").ifEmpty { url.hashCode().toString() })).build()
        val p = Preload(mediaItem, url, cmd.optString("item").ifEmpty { null }, plan(cmd), Info.of(cmd.optJSONObject("info")),
            SystemClock.elapsedRealtime())
        preloadSelector.plan = p.plan
        preloadStart = cmd.optLong("start_ms", 0).coerceAtLeast(0)
        preloaded = p
        preloads.add(mediaItem, 0)
        preloads.invalidate()
    }

    private fun dropPreload() {
        val p = preloaded ?: return
        preloaded = null
        preloads.remove(p.item)
        if (p.url != item?.url) http.forget(p.url)
    }

    fun play() {
        player.play()
        host.onChanged()                            // the pause screen goes now, not at the next tick
    }

    fun pause() {
        player.pause()
        host.onChanged()
    }

    fun toggle() {
        if (player.playWhenReady) pause() else play()
    }

    fun seek(ms: Long, mode: String?) {
        player.setSeekParameters(seekParameters(mode, SeekParameters.EXACT))
        seekAt = SystemClock.elapsedRealtime()
        loadsSinceSeek = 0
        player.seekTo(ms.coerceAtLeast(0))
        sendPos()
        host.onChanged()
    }

    /** Fast `seek_by`s add up from the previous target, not from where the picture has got to. */
    fun seekBy(deltaMs: Long, mode: String?) {
        val now = SystemClock.elapsedRealtime()
        val from = if (seekByTarget != C.TIME_UNSET && now - seekByAt < MERGE_SEEKS_MS) seekByTarget else player.currentPosition
        val dur = player.duration
        var target = (from + deltaMs).coerceAtLeast(0)
        if (dur != C.TIME_UNSET && target > dur) target = dur
        seekByTarget = target
        seekByAt = now
        player.setSeekParameters(seekParameters(mode, SeekParameters.CLOSEST_SYNC))
        seekAt = now
        loadsSinceSeek = 0
        player.seekTo(target)
        sendPos()
        host.onChanged()
    }

    private fun seekParameters(mode: String?, default: SeekParameters) = when (mode) {
        "exact" -> SeekParameters.EXACT
        "sync" -> SeekParameters.CLOSEST_SYNC
        "prev" -> SeekParameters.PREVIOUS_SYNC
        "next" -> SeekParameters.NEXT_SYNC
        else -> default
    }

    fun volume(level: Double) {
        player.volume = level.toFloat().coerceIn(0f, 1f)
    }

    /**
     * The subtitles' delay now (later when positive): set by hand for this file, else, for an added track, how far
     * tv-agent measured it from this file. The file's own tracks are only ever moved by hand.
     */
    val subtitleDelayMs: Long get() = ExternalSubs.delay(manualDelayMs, autoDelayMs)

    /** The agent's measurement for the added track shown; null when none was made or a file's own track shows. */
    val autoDelayMs: Long? get() = external.tracks.getOrNull(external.shown)?.autoDelayMs

    /** Set by hand (the subtitles panel): it wins over a measurement for the rest of this file; null undoes it. */
    var manualDelayMs: Long? = null
        set(ms) {
            field = ms
            delay.ms = ms ?: 0
            followSubs()
        }

    /**
     * `sub_delay`: an added track's delay, measured by tv-agent after it was added. Kept within
     * ExternalSubs.MAX_AUTO_DELAY_MS whatever comes; a file's own track can't be given one.
     */
    fun subDelay(cmd: JSONObject) {
        val track = external.tracks.getOrNull(cmd.getInt("text") - refs(C.TRACK_TYPE_TEXT).size)
            ?: throw IllegalArgumentException("not an added subtitle")
        track.autoDelayMs = ExternalSubs.measured(cmd.getLong("ms"))
        followSubs()
    }

    val speed get() = player.playbackParameters.speed

    /**
     * The speed, from the controls. A bitstream can't be sped up (Media3 keeps it at normal speed), so while it isn't
     * normal the TV decodes the sound (AudioRoute.pcmOnly) and it goes back to a bitstream at normal speed. False,
     * nothing changed, when the TV has no decoder for it (TrueHD).
     */
    fun speed(s: Float): Boolean {
        val f = audioFormat
        if (s != 1f && passthrough && (f == null || !Codecs.decodes(f.sampleMimeType))) return false
        player.setPlaybackSpeed(s)
        val pcm = s != 1f && (passthrough || route.pcmOnly)
        if (pcm != route.pcmOnly) {
            route.pcmOnly = pcm
            reopenAudio()
        }
        return true
    }

    private fun normalSpeed() {
        if (player.playbackParameters.speed != 1f) player.setPlaybackSpeed(1f)
        route.pcmOnly = false
    }

    /** The route read again (on the playback thread) and the audio renderer alone off and on: the picture isn't touched. */
    private fun reopenAudio() {
        Handler(player.playbackLooper).post(route::refresh)
        val params = player.trackSelectionParameters
        player.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        player.trackSelectionParameters = params
    }

    /** The tracks as the agent sees them (PROTOCOL.md, Track); `i` is what `select` takes. */
    fun tracks(): JSONObject {
        val audio = JSONArray()
        val text = JSONArray()
        for ((g, t) in refs(C.TRACK_TYPE_AUDIO)) audio.put(track(audio.length(), g, t))
        for ((g, t) in refs(C.TRACK_TYPE_TEXT)) {
            text.put(track(text.length(), g, t).put("selected", g.isTrackSelected(t) && external.shown < 0))
        }
        external.tracks.forEachIndexed { k, x ->
            text.put(JSONObject().put("i", text.length()).put("lang", x.lang).put("label", x.label)
                .put("codec", x.format).put("default", false).put("forced", false).put("sdh", false)
                .put("external", true).put("selected", external.shown == k).put("supported", true))
        }
        return JSONObject().put("audio", audio).put("text", text)
    }

    private fun refs(type: Int): List<kotlin.Pair<Tracks.Group, Int>> =
        player.currentTracks.groups.filter { it.type == type }.flatMap { g -> (0 until g.length).map { g to it } }

    private fun track(i: Int, g: Tracks.Group, t: Int): JSONObject {
        val f = g.getTrackFormat(t)
        val o = JSONObject().put("i", i).put("lang", f.language ?: JSONObject.NULL).put("label", f.label ?: JSONObject.NULL)
            .put("codec", Codecs.codec(Codecs.original(f)) ?: JSONObject.NULL)
            .put("default", f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0).put("forced", Lang.isForced(f))
            .put("sdh", Lang.isSdh(f)).put("external", false).put("selected", g.isTrackSelected(t))
            .put("supported", g.isTrackSupported(t))
        if (g.type == C.TRACK_TYPE_AUDIO) o.put("ch", f.channelCount).put("commentary", Lang.isExtraAudio(f))
        return o
    }

    /** `select`: an audio index, and/or a text index or null (off: a forced track in the audio's language stays). */
    fun select(cmd: JSONObject) {
        val params = player.trackSelectionParameters.buildUpon()
        if (cmd.has("audio")) {
            val (g, t) = refs(C.TRACK_TYPE_AUDIO).getOrNull(cmd.getInt("audio")) ?: throw IllegalArgumentException("no such audio track")
            params.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, t))
        }
        if (cmd.has("text")) {
            if (cmd.isNull("text")) {
                external.shown = -1
                params.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                selector.replan(selector.plan.copy(subs = null))
            } else {
                val i = cmd.getInt("text")
                val inside = refs(C.TRACK_TYPE_TEXT)
                when {
                    i < inside.size -> {
                        val (g, t) = inside[i]
                        external.shown = -1
                        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, t))
                    }
                    i - inside.size < external.tracks.size -> {
                        external.shown = i - inside.size
                        params.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    }
                    else -> throw IllegalArgumentException("no such text track")
                }
            }
            if (external.shown < 0) host.onCues(emptyList())
            followSubs()
        }
        player.trackSelectionParameters = params.build()
        main.post { host.send(tracks().put("t", "tracks").put("item", item?.id)) }
    }

    /**
     * `add_sub`: parsed off the main thread, then shown (or listed) without touching the playing file. `done` gets
     * the new track's index, or an error.
     */
    fun addSub(cmd: JSONObject, done: (Int?, String?) -> Unit) {
        val forItem = item ?: throw IllegalStateException("nothing loaded")
        val lang = cmd.optString("lang", "und")
        val label = cmd.optString("label").ifEmpty { lang }
        val show = cmd.optBoolean("show", true)
        val autoDelay = if (cmd.has("delay_ms")) ExternalSubs.measured(cmd.getLong("delay_ms")) else null
        val format = cmd.optString("format").ifEmpty { null }
        val inline = if (cmd.has("text")) cmd.getString("text") else null
        val url = cmd.optString("url").ifEmpty { null }
        if (inline == null && url == null) throw IllegalArgumentException("url or text")
        work.execute {
            val result = try {
                val bytes = inline?.toByteArray(Charsets.UTF_8) ?: download(url!!)
                ExternalSubs.parse(bytes, format, lang, label)
            } catch (e: Exception) {
                Log.i(TAG, "add_sub: ${e.javaClass.simpleName}")
                null
            }
            main.post {
                if (item !== forItem || released) return@post done(null, "another file is loaded")
                if (result == null || result.events.isEmpty()) return@post done(null, "no subtitles in it")
                result.autoDelayMs = autoDelay
                external.tracks += result
                val index = refs(C.TRACK_TYPE_TEXT).size + external.tracks.size - 1
                if (show) select(JSONObject().put("text", index))
                else host.send(tracks().put("t", "tracks").put("item", item?.id))
                done(index, null)
            }
        }
    }

    private fun download(url: String): ByteArray {
        val source = http.factory(Http.Stack.PLATFORM).createDataSource()
        try {
            source.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse(url)))
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 shl 10)
            while (true) {
                val n = source.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
                if (out.size() > Link.MAX_LINE) throw java.io.IOException("too big")
            }
            val bytes = out.toByteArray()
            return if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
                java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes() else bytes
        } finally {
            source.close()
        }
    }

    fun status(): JSONObject = stateFields(posFields(JSONObject()))

    // ---- the end of an item, and of the player ----

    /** `closed` for what's loaded (the last word about it), then nothing is loaded. */
    fun close(why: String) {
        val it = item ?: return
        host.send(posFields(msg("closed", "item" to it.id, "why" to why)))
        item = null
        http.stats = null
        forgetBitstream()
        if (why != "replaced") {
            player.stop()
            player.clearMediaItems()
            http.forget(it.url)
            external.clear()
            host.onCues(emptyList())
        }
    }

    /** Frees the decoders (the TV's single Dolby Vision decoder among them) for the next app. */
    fun release(why: String) {
        if (released) return
        close(why)
        released = true
        main.removeCallbacks(tick)
        main.removeCallbacks(subsTick)
        main.removeCallbacks(collect)
        main.removeCallbacks(checkRoute)
        preloads.release()
        player.release()
        work.shutdownNow()
    }

    // ---- events ----

    private fun sendPos() {
        val it = item ?: return
        host.send(posFields(msg("pos", "item" to it.id)))
    }

    private fun posFields(o: JSONObject): JSONObject {
        val dur = player.duration
        return o.put("pos", player.currentPosition).put("dur", if (dur == C.TIME_UNSET) 0 else dur)
            .put("buf", (player.bufferedPosition - player.currentPosition).coerceAtLeast(0))
    }

    private fun stateFields(o: JSONObject): JSONObject = o.put("state", stateName()).put("playing", player.isPlaying)
        .put("item", item?.id ?: JSONObject.NULL)

    private fun stateName() = when (player.playbackState) {
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> "idle"
    }

    /** What the agent needs after it (re)connects. */
    fun sendAll() {
        if (item == null) return
        host.send(stateFields(JSONObject().put("t", "state")))
        sendPos()
        if (!player.currentTracks.isEmpty) host.send(tracks().put("t", "tracks").put("item", item?.id))
    }

    private val tick = object : Runnable {
        override fun run() {
            if (item != null) {
                sendPos()
                host.onChanged()
            }
            main.postDelayed(this, if (player.isPlaying) 1_000 else 10_000)
        }
    }

    // Subtitles from elsewhere follow the position while they're shown: 20 times a second while the picture moves.
    private val subsTick = object : Runnable {
        override fun run() {
            external.cuesAt((player.currentPosition - subtitleDelayMs) * 1000)?.let(host::onCues)
            if (external.shown >= 0) main.postDelayed(this, if (player.isPlaying) 50 else 250)
        }
    }

    private fun followSubs() {
        main.removeCallbacks(subsTick)
        if (external.shown >= 0) main.post(subsTick)
    }

    init {
        main.post(tick)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        host.send(stateFields(JSONObject().put("t", "state")))
        host.onActive(isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING))
        main.removeCallbacks(tick)
        main.post(tick)
        followSubs()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val it = item ?: return
        host.send(stateFields(JSONObject().put("t", "state")))
        host.onActive(player.playWhenReady && playbackState != Player.STATE_ENDED && playbackState != Player.STATE_IDLE)
        host.onChanged()
        if (playbackState == Player.STATE_READY && preparedMs >= 0 && firstFrameMs >= 0 && stats != null) sendTiming(it)
        if (playbackState == Player.STATE_ENDED) host.send(posFields(msg("ended", "item" to it.id)))
    }

    override fun onTracksChanged(tracks: Tracks) {
        val it = item ?: return
        if (tracks.isEmpty) return
        if (preparedMs < 0) preparedMs = SystemClock.elapsedRealtime() - it.loadedAt
        // No bitrate from the agent: the file's size over its length, now that both are known (Budget).
        val length = http.lengths[it.url] ?: -1
        if (!bitrateKnown && length > 0 && player.duration > 0) {
            bitrateKnown = true
            cap(length * 8 * 1000 / player.duration)
        }
        if (switchAt == 0L) host.send(tracks().put("t", "tracks").put("item", it.id))   // a switch shows no audio for a moment
    }

    override fun onCues(cueGroup: CueGroup) {
        if (external.shown < 0) host.onCues(cueGroup.cues)
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        if (videoSize.width > 0 && videoSize.height > 0) {
            host.onVideoAspect(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val it = item
        val http = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        Log.w(TAG, "playback error ${error.errorCodeName}${http?.let { c -> " (HTTP $c)" } ?: ""}")
        host.send(msg("error", "item" to it?.id, "code" to error.errorCodeName, "http" to http,
            "message" to scrub(error.cause?.javaClass?.simpleName ?: error.errorCodeName), "fatal" to true))
        close("error")
    }

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        val it = item ?: return
        val now = SystemClock.elapsedRealtime()
        if (firstFrameMs < 0) {
            firstFrameMs = now - it.loadedAt
            host.send(msg("first_frame", "item" to it.id, "after" to "load", "ms" to firstFrameMs))
            host.onFirstFrame()
            if (player.playbackState == Player.STATE_READY && preparedMs >= 0) sendTiming(it)
        } else if (seekAt > 0) {
            host.send(msg("first_frame", "item" to it.id, "after" to "seek", "ms" to now - seekAt,
                "in_buffer" to (loadsSinceSeek == 0)))
            seekAt = 0
        }
    }

    private fun sendTiming(it: Item) {
        val s = stats ?: return
        stats = null                                // once per load
        val ms = JSONObject().put("first_byte", s.firstByteMs).put("prepared", preparedMs)
            .put("first_frame", firstFrameMs).put("ready", SystemClock.elapsedRealtime() - it.loadedAt)
        host.send(msg("timing", "item" to it.id, "ms" to ms, "bytes" to s.bytes, "requests" to s.requests,
            "preloaded" to usedPreload, "cap_mb" to loading.cap / (1 shl 20)))
    }

    override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
                               mediaLoadData: MediaLoadData, retryCount: Int) {
        if (seekAt > 0) loadsSinceSeek++
    }

    /**
     * A read that failed and will be retried: told to the agent once per file (`fatal` false), so it can say why on
     * the TV now rather than after Media3's retries (a name the TV can't look up waits ~30 s to become fatal).
     */
    override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
                             mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
        val it = item ?: return
        if (wasCanceled || loadErrorSent) return
        loadErrorSent = true
        val cause = generateSequence<Throwable>(error) { e -> e.cause }.last()
        val http = generateSequence<Throwable>(error) { e -> e.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        Log.w(TAG, "load error ${cause.javaClass.simpleName}${http?.let { c -> " (HTTP $c)" } ?: ""}")
        host.send(msg("error", "item" to it.id, "code" to "LOAD_FAILED", "cause" to cause.javaClass.simpleName,
            "http" to http, "message" to scrub(cause.message ?: cause.javaClass.simpleName), "fatal" to false))
    }

    private var loadErrorSent = false

    // Media3 starts the decoder before it reports the format that made it: whichever comes second sends `video`.
    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                                           initializedTimestampMs: Long, initializationDurationMs: Long) {
        videoDecoder = decoderName
        decoderInitMs = initializationDurationMs
        videoFormat?.let(::sendVideo)
    }

    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
                                           decoderReuseEvaluation: DecoderReuseEvaluation?) {
        videoFormat = format
        if (videoDecoder != null) sendVideo(format)
    }

    private fun sendVideo(f: Format) {
        val decoder = videoDecoder ?: return
        Log.i(TAG, "video: $decoder ${f.codecs} ${f.width}x${f.height} ${Codecs.hdr(f)} init $decoderInitMs ms")
        host.send(msg("video", "item" to item?.id, "decoder" to decoder, "mime" to f.sampleMimeType,
            "codecs" to f.codecs, "w" to f.width, "h" to f.height, "fps" to f.frameRate.toDouble(),
            "hdr" to Codecs.hdr(f), "dv_profile" to Codecs.dvProfile(f), "init_ms" to decoderInitMs))
    }

    override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
                                           decoderReuseEvaluation: DecoderReuseEvaluation?) {
        audioFormat = format
    }

    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        val f = audioFormat
        val pcm = Codecs.isPcm(audioTrackConfig.encoding)
        passthrough = !pcm
        val out = if (pcm) "pcm" else "passthrough"
        Log.i(TAG, "audio: ${f?.sampleMimeType} ${f?.channelCount} ch → $out ${Codecs.encoding(audioTrackConfig.encoding)}")
        val event = msg("audio", "item" to item?.id, "mime" to f?.sampleMimeType, "ch" to f?.channelCount,
            "rate" to audioTrackConfig.sampleRate, "out" to out, "encoding" to Codecs.encoding(audioTrackConfig.encoding),
            "offload" to audioTrackConfig.offload)
        val now = SystemClock.elapsedRealtime()
        if (!pcm) {
            hadBitstream = true
            sinkError = null
            if (lostAt > 0) {
                Log.i(TAG, "bitstream back after ${now - lostAt} ms of PCM")
                event.put("dropout", "restored").put("pcm_ms", now - lostAt)
                lostAt = 0
                main.removeCallbacks(checkRoute)
                if (switchAt > 0) {
                    restored = event                    // sent once it plays, with the gap
                    item?.let { host.send(tracks().put("t", "tracks").put("item", it.id)) }
                    return
                }
            }
        } else if (switchAt > 0) {
            // Switched back, and the route took only PCM after all: tried again later.
            Log.w(TAG, "bitstream: still PCM after switching back")
            switchAt = 0
            item?.let { host.send(tracks().put("t", "tracks").put("item", it.id)) }
            main.removeCallbacks(checkRoute)
            main.postDelayed(checkRoute, ROUTE_CHECK_MS * 10)
        } else if (f != null && f.sampleMimeType != MimeTypes.AUDIO_RAW && hadBitstream && sinkError != null && lostAt == 0L) {
            // Decoded where it went out as a bitstream before, after a write or start that failed: Media3 now takes
            // the route for stereo PCM (AudioRoute). Checked until the route takes the format again.
            Log.w(TAG, "bitstream lost ($sinkError): ${f.sampleMimeType} decoded to PCM")
            lostAt = now
            event.put("dropout", "lost").put("cause", sinkError)
            main.removeCallbacks(checkRoute)
            main.postDelayed(checkRoute, ROUTE_CHECK_MS)
        }
        host.send(event)
    }

    override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
        val event = restored ?: return
        restored = null
        host.send(event.put("gap_ms", (playoutStartSystemTimeMs - switchAt).coerceAtLeast(0)))
        switchAt = 0
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        val cause = AudioRoute.cause(audioSinkError) ?: return
        Log.w(TAG, "audio sink: $cause")
        sinkError = cause
    }

    // ---- the bitstream back after the TV's audio dropped it (AudioRoute) ----

    private var passthrough = false                     // the sound goes out as a bitstream now
    private var hadBitstream = false                    // this item's audio has gone out as a bitstream
    private var sinkError: String? = null               // the last audio write or start that failed, this item
    private var lostAt = 0L                             // decoded to PCM since then, else 0
    private var switches = 0
    private var switchAt = 0L                           // System.currentTimeMillis() at the switch back, else 0
    private var restored: JSONObject? = null            // its `audio` event, until the sound plays

    /**
     * Once a second while it's PCM: when the route takes the format again, the route is read again (refresh, on the
     * playback thread) and the audio renderer alone goes off and on, so it starts again as a bitstream. The picture
     * isn't touched; the audio is read from memory (12 s are kept behind the position).
     */
    private val checkRoute = object : Runnable {
        override fun run() {
            val f = audioFormat
            if (lostAt == 0L || item == null || released || f == null) return
            if (!AudioCapabilities.getCapabilities(context, attributes, null, emptyList()).isPassthroughPlaybackSupported(f, attributes)) {
                main.postDelayed(this, ROUTE_CHECK_MS)
                return
            }
            if (switches++ >= MAX_SWITCHES) {            // it keeps dropping: PCM it stays
                Log.w(TAG, "bitstream: not switching back again")
                return
            }
            Log.i(TAG, "bitstream: the route takes ${f.sampleMimeType} again, switching back")
            switchAt = System.currentTimeMillis()
            reopenAudio()
        }
    }

    private fun forgetBitstream() {
        hadBitstream = false
        sinkError = null
        lostAt = 0
        switches = 0
        switchAt = 0
        restored = null
        main.removeCallbacks(checkRoute)
    }

    /**
     * The buffer's cap for this file. A smaller one than the last file's frees blocks the runtime won't collect by
     * itself while playing (the player allocates almost nothing new), so a collection follows once the old file
     * has been let go.
     */
    private fun cap(bitrate: Long) {
        val before = loading.cap
        loading.cap = Budget.bytes(bitrate, Runtime.getRuntime().maxMemory(), availableMemory())
        if (loading.cap < before - (32 shl 20)) {
            main.removeCallbacks(collect)
            main.postDelayed(collect, 4_000)
        }
    }

    private val collect = Runnable { Thread({ Runtime.getRuntime().gc() }, "gc").start() }

    private fun availableMemory(): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.availMem
    }

    companion object {
        private const val TAG = "Engine"
        private const val PRELOAD_MS = 4_000L
        private const val MERGE_SEEKS_MS = 1_000L
        private const val ROUTE_CHECK_MS = 1_000L
        private const val MAX_SWITCHES = 3

        private fun headers(cmd: JSONObject): Map<String, String> {
            val h = cmd.optJSONObject("headers") ?: return emptyMap()
            return h.keys().asSequence().associateWith { h.getString(it) }
        }

        private fun stackOf(cmd: JSONObject): Http.Stack? =
            cmd.optString("http").ifEmpty { null }?.let { Http.Stack.valueOf(it.uppercase()) }

        fun plan(cmd: JSONObject): Selector.Plan {
            val audio = if (cmd.isNull("audio")) null else cmd.optString("audio").ifEmpty { null }
            val subs = cmd.optJSONArray("subs")?.let { a -> (0 until a.length()).map { a.getString(it) } }
            return Selector.Plan(audio, subs)
        }

        /** Error text without anything that looks like a link (they carry the debrid key). */
        fun scrub(text: String): String = text.replace(Regex("[a-z][a-z0-9+.-]*://\\S+", RegexOption.IGNORE_CASE), "<link>")
    }
}

/** The subtitle delay, read by the text renderers on the playback thread. */
class TextDelay {
    @Volatile var ms = 0L
}

/** A text renderer given the position less the delay: its lines come that much later (sooner when negative). */
private class Delayed(renderer: Renderer, private val delay: TextDelay) : ForwardingRenderer(renderer) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) =
        super.render(positionUs - delay.ms * 1000, elapsedRealtimeUs)
}
