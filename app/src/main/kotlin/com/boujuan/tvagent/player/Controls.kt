package com.boujuan.tvagent.player

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewStub
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.media3.ui.AspectRatioFrameLayout
import org.json.JSONObject

/**
 * The controls under the time bar, as in Stremio's player: play/pause, back and ahead, the previous and next
 * episode (a series, with tv-agent), subtitles, audio, speed, picture size and Settings. ↓ goes into them while the
 * bar is up, ←/→ move, OK uses one, ↑ or Back leaves. Subtitles open their panel (SubtitlePanel); audio, speed,
 * picture size and Settings a list on the right (Menu). Both are inflated the first time they open, and the row is
 * only drawn again when what it shows changes. No Android focus: the keys come from the activity.
 */
class Controls(private val row: LinearLayout, private val menuStub: ViewStub, private val subsStub: ViewStub,
               private val engine: Engine, private val settings: Settings, private val host: Host) {

    interface Host {
        /** −1 back, 1 ahead, by the jump lengths set. */
        fun jump(direction: Int)
        /** "previous" or "next": the agent decides what that is. */
        fun episode(which: String)
        /** Episodes can be asked for: tv-agent is there and this is an episode. */
        fun episodes(): Boolean
        /** AspectRatioFrameLayout's resize mode for the picture. */
        var picture: Int
        fun say(text: String)
        /** A sample subtitle line instead of the film's, while a look is being picked. */
        fun sample(on: Boolean)
        /** The subtitles' look changed. */
        fun applied()
        /** What About says of tv-agent. */
        fun agent(): String?
    }

    private enum class Control(val icon: Int, val label: Int) {
        PLAY(R.drawable.ic_play, R.string.play), BACK(R.drawable.ic_rewind, R.string.jump_back_control),
        AHEAD(R.drawable.ic_forward, R.string.jump_ahead_control), PREVIOUS(R.drawable.ic_previous, R.string.previous_episode),
        NEXT(R.drawable.ic_next, R.string.next_episode), SUBTITLES(R.drawable.ic_subtitles, R.string.subtitles),
        AUDIO(R.drawable.ic_audio, R.string.audio), SPEED(R.drawable.ic_speed, R.string.speed),
        PICTURE(R.drawable.ic_aspect, R.string.picture), SETTINGS(R.drawable.ic_settings, R.string.settings)
    }

    private val context: Context = row.context
    private var shown: List<Control> = emptyList()
    private val views = HashMap<Control, ImageView>()
    private var entry = -1                      // the control picked with the arrows, -1: none
    private var drawnPlaying: Boolean? = null    // what the play control shows (drawn again only when it changes)
    private var menu: Menu? = null
    private var settingsMenu: SettingsMenu? = null
    private var subs: SubtitlePanel? = null

    /** The controls are in use (one picked, or a list or the panel open): the bar stays up. */
    val active get() = entry >= 0 || menu?.open == true || subs?.open == true

    /** The row as things are now: which controls there are, play or pause, the pick. */
    fun draw() {
        val want = Control.entries.filter { it != Control.PREVIOUS && it != Control.NEXT || host.episodes() }
        if (want != shown) build(want)
        val playing = engine.player.playWhenReady
        if (playing != drawnPlaying) views[Control.PLAY]?.let { v ->
            drawnPlaying = playing
            v.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            v.contentDescription = context.getString(if (playing) R.string.pause else R.string.play)
        }
        for ((k, c) in shown.withIndex()) views[c]?.isSelected = k == entry
    }

    private fun build(want: List<Control>) {
        shown = want
        entry = entry.coerceAtMost(want.size - 1)
        drawnPlaying = null
        row.removeAllViews()
        views.clear()
        val d = context.resources.displayMetrics.density
        for (c in want) {
            if (c == Control.SUBTITLES || c == Control.SETTINGS) row.addView(divider(d))
            val v = ImageView(context)
            v.layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()).apply { marginEnd = (8 * d).toInt() }
            v.setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            v.setBackgroundResource(R.drawable.control)
            v.imageTintList = context.getColorStateList(R.color.on_chip)
            v.setImageResource(c.icon)
            v.contentDescription = context.getString(c.label)
            views[c] = v
            row.addView(v)
        }
    }

    private fun divider(d: Float) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams((1 * d).toInt(), (22 * d).toInt()).apply {
            marginStart = (6 * d).toInt()
            marginEnd = (14 * d).toInt()
        }
        setBackgroundColor(0x4DFFFFFF)
    }

    /**
     * The remote while the bar is up: true when the controls took the key. With none picked only ↓ is theirs (it
     * picks play/pause); ←/→ and OK then do what they do while playing.
     */
    fun onKey(keyCode: Int): Boolean {
        subs?.takeIf { it.open }?.let { return it.onKey(keyCode) }
        menu?.takeIf { it.open }?.let { m ->
            val took = m.onKey(keyCode)
            if (!m.open) draw()
            return took
        }
        if (entry < 0) {
            if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false
            entry = 0
            draw()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> entry = (entry - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> entry = (entry + 1).coerceAtMost(shown.size - 1)
            KeyEvent.KEYCODE_DPAD_UP -> entry = -1
            KeyEvent.KEYCODE_DPAD_DOWN -> {}
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> shown.getOrNull(entry)?.let(::use)
            else -> return false
        }
        draw()
        return true
    }

    /** Back: closes the panel or the list that's open, or leaves the controls; false when none of that was so. */
    fun back(): Boolean {
        subs?.takeIf { it.open }?.let { return it.back() }
        menu?.takeIf { it.open }?.let { m ->
            m.back()
            if (!m.open) draw()
            return true
        }
        if (entry < 0) return false
        entry = -1
        draw()
        return true
    }

    /** Everything closed (the bar went, the item changed): a look being tried goes back. */
    fun close() {
        subs?.close()
        menu?.close()
        entry = -1
    }

    /** The tracks changed: the panel or an audio list that's open follows. */
    fun tracksChanged() {
        subs?.tracksChanged()
    }

    private fun use(c: Control) {
        when (c) {
            Control.PLAY -> engine.toggle()
            Control.BACK -> host.jump(-1)
            Control.AHEAD -> host.jump(1)
            Control.PREVIOUS -> host.episode("previous")
            Control.NEXT -> host.episode("next")
            Control.SUBTITLES -> subtitles().show()
            Control.AUDIO -> audio()
            Control.SPEED -> speed()
            Control.PICTURE -> picture()
            Control.SETTINGS -> settingsMenu().open()
        }
    }

    private fun audio() {
        val a = engine.tracks().getJSONArray("audio")
        val now = (0 until a.length()).firstOrNull { a.getJSONObject(it).optBoolean("selected") } ?: -1
        menu().push(Menu.Page(context.getString(R.string.audio), (0 until a.length()).map { PauseScreen.audioName(a.getJSONObject(it)) },
            at = now.coerceAtLeast(0), current = now) { k ->
            // The tracks may have changed meanwhile (a subtitle added): then there's nothing to apply.
            runCatching { engine.select(JSONObject().put("audio", k)) }
            true
        })
    }

    private fun speed() {
        val now = SPEEDS.indexOfFirst { it == engine.speed }
        menu().push(Menu.Page(context.getString(R.string.speed), context.resources.getStringArray(R.array.playback_speeds).toList(),
            at = if (now >= 0) now else SPEEDS.indexOf(1f), current = now) { k ->
            if (!engine.speed(SPEEDS[k])) {
                val codec = engine.audioFormat?.let { f -> PauseScreen.codecName(Codecs.codec(Codecs.original(f))) }
                host.say(context.getString(R.string.speed_bitstream, codec ?: context.getString(R.string.audio)))
            }
            true
        })
    }

    private fun picture() {
        val now = PICTURES.indexOf(host.picture)
        menu().push(Menu.Page(context.getString(R.string.picture), context.resources.getStringArray(R.array.pictures).toList(),
            at = now.coerceAtLeast(0), current = now) { k ->
            host.picture = PICTURES[k]
            true
        })
    }

    private fun menu(): Menu = menu ?: menuStub.inflate().let { v ->
        Menu(v, v.findViewById(R.id.pause_choices_title), v.findViewById(R.id.pause_choices_scroll),
            v.findViewById(R.id.pause_choices_list)).also { menu = it }
    }

    private fun settingsMenu(): SettingsMenu = settingsMenu
        ?: SettingsMenu(context, settings, menu(), host::sample, host::applied, host::agent).also { settingsMenu = it }

    private fun subtitles(): SubtitlePanel = subs
        ?: SubtitlePanel(subsStub.inflate(), engine, settings, host::sample, host::applied).also { subs = it }

    companion object {
        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).toList()
        // AspectRatioFrameLayout's modes, as the Picture size list names them
        private val PICTURES = listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
    }
}
