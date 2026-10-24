package com.boujuan.tvagent.player

import android.content.Context
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewStub
import android.view.accessibility.CaptioningManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.ui.SubtitleView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.Locale

/**
 * The pause screen: over the paused picture, under the time bar. What's playing (`load`'s `info`), the clock and
 * when it ends, the corner badges, and a menu worked with the remote's arrows: audio track, subtitles, their size
 * and position. Track changes go through Engine.select, so the agent gets its `tracks` event as for its own
 * `select`. Inflated on the first pause, GONE while playing; nothing in it runs while it's hidden.
 */
class PauseScreen(private val stub: ViewStub, private val artwork: Artwork, private val engine: Engine,
                  private val subtitles: SubtitleView) {
    private val context: Context = stub.context
    private val prefs = context.getSharedPreferences("screen", Context.MODE_PRIVATE)
    private var root: View? = null
    private lateinit var clock: TextView
    private lateinit var ends: TextView
    private lateinit var badges: LinearLayout
    private lateinit var logo: ImageView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var plot: TextView
    private lateinit var origin: TextView
    private lateinit var description: TextView
    private lateinit var menu: LinearLayout
    private lateinit var choices: View
    private lateinit var choicesTitle: TextView
    private lateinit var choicesScroll: ScrollView
    private lateinit var choicesList: LinearLayout

    private var entry = -1                  // the menu entry picked with the arrows, -1: none
    private var open: Entry? = null         // the entry whose choices are shown
    private var choice = 0
    private var shownFor: String? = null    // the item the info was filled for

    val showing get() = root?.visibility == View.VISIBLE

    init {
        applySubtitleStyle()
    }

    /** Up on a pause (the remote's or the agent's, asked for at `since`, uptime), filled for what's loaded. */
    fun show(since: Long) {
        val item = engine.item ?: return
        if (showing) return update()
        val firstTime = root == null
        val v = root ?: inflate()
        if (shownFor != item.id || firstTime) fill(item)
        entry = -1
        closeChoices()
        drawMenu()
        update()
        v.visibility = View.VISIBLE
        // How long a pause takes to show, for DESIGN.md: to the frame that has it.
        v.viewTreeObserver.registerFrameCommitCallback {
            Log.i(TAG, "pause screen up in ${SystemClock.uptimeMillis() - since} ms${if (firstTime) " (inflated)" else ""}")
        }
    }

    fun hide() {
        val v = root ?: return
        if (v.visibility == View.GONE) return
        v.visibility = View.GONE
        closeChoices()
        entry = -1
    }

    /** The clock and the end time (every few seconds while paused, from Engine's tick); badges as tracks change. */
    fun update() {
        if (!showing) return
        val p = engine.player
        val now = Date()
        clock.text = DateFormat.getTimeFormat(context).format(now)
        val dur = p.duration
        if (dur != C.TIME_UNSET && dur > 0) {
            val left = ((dur - p.currentPosition) / p.playbackParameters.speed).toLong().coerceAtLeast(0)
            ends.text = context.getString(R.string.ends_at, DateFormat.getTimeFormat(context).format(Date(now.time + left)))
            ends.visibility = View.VISIBLE
        } else ends.visibility = View.GONE
        fillBadges(badges, Badges.of(engine.videoFormat, engine.audioFormat, engine.item?.info?.badges.orEmpty()))
    }

    /** Tracks changed (the agent's `select`, a subtitle added): the menu's values follow. */
    fun tracksChanged() {
        if (!showing) return
        drawMenu()
        update()
    }

    private fun fill(item: Engine.Item) {
        shownFor = item.id
        val info = item.info ?: Info(title = item.title)
        title.text = info.title ?: item.title
        set(subtitle, info.subtitle)
        set(plot, info.plot)
        set(origin, info.origin())
        set(description, info.description)
        logo.setImageDrawable(null)
        logo.visibility = View.GONE
        title.visibility = View.VISIBLE
        val url = info.logo ?: return
        logo.tag = url
        val wide = context.resources.displayMetrics.widthPixels
        artwork.get(url, item.id, wide / 3, wide / 10, cover = false) { bitmap ->
            if (logo.tag != url || shownFor != item.id) return@get
            logo.setImageBitmap(bitmap)
            logo.visibility = View.VISIBLE
            title.visibility = View.GONE
        }
    }

    private fun set(view: TextView, text: String?) {
        view.text = text
        view.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // ---- the menu ----

    private enum class Entry { AUDIO, SUBTITLES, SIZE, PLACE }

    private fun entries(): List<Entry> {
        val tracks = engine.tracks()
        return listOfNotNull(Entry.AUDIO.takeIf { tracks.getJSONArray("audio").length() > 1 }, Entry.SUBTITLES,
            Entry.SIZE, Entry.PLACE)
    }

    private fun drawMenu() {
        val tracks = engine.tracks()
        val list = entries()
        menu.removeAllViews()
        val inflater = LayoutInflater.from(context)
        for ((k, e) in list.withIndex()) {
            val chip = inflater.inflate(R.layout.chip, menu, false) as TextView
            chip.text = context.getString(R.string.entry, context.getString(label(e)), value(e, tracks))
            chip.isSelected = k == entry
            menu.addView(chip)
        }
    }

    private fun label(e: Entry) = when (e) {
        Entry.AUDIO -> R.string.audio
        Entry.SUBTITLES -> R.string.subtitles
        Entry.SIZE -> R.string.subtitle_size
        Entry.PLACE -> R.string.subtitle_place
    }

    private fun value(e: Entry, tracks: JSONObject): String = when (e) {
        Entry.AUDIO -> selected(tracks.getJSONArray("audio"))?.let(::audioName) ?: "—"
        Entry.SUBTITLES -> selected(tracks.getJSONArray("text"))?.takeUnless { it.optBoolean("forced") }?.let(::textName)
            ?: context.getString(R.string.off)
        Entry.SIZE -> context.resources.getStringArray(R.array.sizes)[size()]
        Entry.PLACE -> context.resources.getStringArray(R.array.places)[place()]
    }

    /** What each entry offers, and which of them is the current one. */
    private fun options(e: Entry): Pair<List<String>, Int> {
        val tracks = engine.tracks()
        return when (e) {
            Entry.AUDIO -> {
                val a = tracks.getJSONArray("audio")
                (0 until a.length()).map { audioName(a.getJSONObject(it)) } to indexSelected(a)
            }
            Entry.SUBTITLES -> {
                val t = tracks.getJSONArray("text")
                val on = indexSelected(t).takeIf { it >= 0 && !t.getJSONObject(it).optBoolean("forced") }
                (listOf(context.getString(if (hasForced(t)) R.string.forced_only else R.string.off)) +
                    (0 until t.length()).map { textName(t.getJSONObject(it)) }) to (on?.plus(1) ?: 0)
            }
            Entry.SIZE -> context.resources.getStringArray(R.array.sizes).toList() to size()
            Entry.PLACE -> context.resources.getStringArray(R.array.places).toList() to place()
        }
    }

    private fun apply(e: Entry, k: Int) {
        when (e) {
            // The tracks may have changed meanwhile (a subtitle added): then there's nothing to apply.
            Entry.AUDIO -> runCatching { engine.select(JSONObject().put("audio", k)) }
            Entry.SUBTITLES -> runCatching { engine.select(JSONObject().put("text", if (k == 0) JSONObject.NULL else k - 1)) }
            Entry.SIZE -> prefs.edit().putInt("size", k).apply()
            Entry.PLACE -> prefs.edit().putInt("place", k).apply()
        }
        if (e == Entry.SIZE || e == Entry.PLACE) applySubtitleStyle()
    }

    private fun openChoices(e: Entry) {
        val (names, current) = options(e)
        open = e
        choice = current.coerceAtLeast(0)
        choicesTitle.setText(label(e))
        choicesList.removeAllViews()
        val inflater = LayoutInflater.from(context)
        for ((k, name) in names.withIndex()) {
            val row = inflater.inflate(R.layout.choice, choicesList, false) as TextView
            row.text = if (k == current) "$name  ✓" else name
            choicesList.addView(row)
        }
        choices.visibility = View.VISIBLE
        drawChoice()
    }

    private fun closeChoices() {
        open = null
        if (root != null) {
            choices.visibility = View.GONE
            choicesList.removeAllViews()
        }
    }

    private fun drawChoice() {
        for (k in 0 until choicesList.childCount) choicesList.getChildAt(k).isSelected = k == choice
        val row = choicesList.getChildAt(choice) ?: return
        choicesScroll.post { choicesScroll.smoothScrollTo(0, (row.top - choicesScroll.height / 3).coerceAtLeast(0)) }
    }

    /**
     * The remote while the screen is up: true when the menu took the key. Down goes into the menu, Up or Back out of
     * it; with nothing picked, the keys do what they do while playing (OK resumes, ←/→ jump).
     */
    fun onKey(keyCode: Int): Boolean {
        if (!showing) return false
        val opened = open
        if (opened != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> if (choice > 0) choice--
                KeyEvent.KEYCODE_DPAD_DOWN -> if (choice < choicesList.childCount - 1) choice++
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    apply(opened, choice)
                    closeChoices()
                    drawMenu()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    closeChoices()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {}
                else -> return false
            }
            drawChoice()
            return true
        }
        val list = entries()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> if (entry < 0) entry = 0
            KeyEvent.KEYCODE_DPAD_UP -> if (entry >= 0) entry = -1 else return false
            KeyEvent.KEYCODE_DPAD_LEFT -> if (entry >= 0) entry = (entry - 1).coerceAtLeast(0) else return false
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (entry >= 0) entry = (entry + 1).coerceAtMost(list.size - 1) else return false
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (entry >= 0) list.getOrNull(entry)?.let(::openChoices) else return false
            else -> return false
        }
        drawMenu()
        return true
    }

    /** Back: closes the choices, then leaves the menu; false when there was neither (Back then stops, as always). */
    fun back(): Boolean {
        if (!showing) return false
        if (open != null) {
            closeChoices()
            return true
        }
        if (entry >= 0) {
            entry = -1
            drawMenu()
            return true
        }
        return false
    }

    // ---- subtitles' size and position (kept between plays) ----

    private fun size() = prefs.getInt("size", 1).coerceIn(0, SIZES.size - 1)
    private fun place() = prefs.getInt("place", 1).coerceIn(0, PLACES.size - 1)

    private fun applySubtitleStyle() {
        val scale = context.getSystemService(CaptioningManager::class.java)?.fontScale ?: 1f
        subtitles.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale * SIZES[size()])
        subtitles.setBottomPaddingFraction(PLACES[place()])
    }

    private fun inflate(): View {
        val v = stub.inflate()
        clock = v.findViewById(R.id.pause_clock)
        ends = v.findViewById(R.id.pause_ends)
        badges = v.findViewById(R.id.pause_badges)
        logo = v.findViewById(R.id.pause_logo)
        title = v.findViewById(R.id.pause_title)
        subtitle = v.findViewById(R.id.pause_subtitle)
        plot = v.findViewById(R.id.pause_plot)
        origin = v.findViewById(R.id.pause_origin)
        description = v.findViewById(R.id.pause_description)
        menu = v.findViewById(R.id.pause_menu)
        choices = v.findViewById(R.id.pause_choices)
        choicesTitle = v.findViewById(R.id.pause_choices_title)
        choicesScroll = v.findViewById(R.id.pause_choices_scroll)
        choicesList = v.findViewById(R.id.pause_choices_list)
        v.visibility = View.GONE
        root = v
        return v
    }

    companion object {
        private const val TAG = "Pause"
        private val SIZES = floatArrayOf(0.8f, 1f, 1.25f, 1.5f)
        // Lower puts a scope film's lines into the black bar under the picture.
        private val PLACES = floatArrayOf(0.03f, SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION, 0.16f)

        /** The badges as small framed labels, in `into` (the pause screen's corner, the time bar). */
        fun fillBadges(into: LinearLayout, names: List<String>) {
            if (into.childCount == names.size && names.indices.all { (into.getChildAt(it) as TextView).text == names[it] }) return
            into.removeAllViews()
            val inflater = LayoutInflater.from(into.context)
            for (name in names) {
                val badge = inflater.inflate(R.layout.badge, into, false) as TextView
                badge.text = name
                into.addView(badge)
            }
        }

        private fun selected(a: JSONArray): JSONObject? = indexSelected(a).takeIf { it >= 0 }?.let(a::getJSONObject)
        private fun indexSelected(a: JSONArray): Int = (0 until a.length()).firstOrNull { a.getJSONObject(it).optBoolean("selected") } ?: -1
        private fun hasForced(a: JSONArray) = (0 until a.length()).any { a.getJSONObject(it).optBoolean("forced") }

        /** "English · TrueHD 7.1", "Castellano · DD 5.1": the track's own name (or its language), codec and channels. */
        fun audioName(t: JSONObject): String =
            listOfNotNull(name(t), listOfNotNull(codecName(Info.text(t, "codec")), channels(t.optInt("ch"))).joinToString(" ").ifEmpty { null })
                .joinToString(" · ")

        fun textName(t: JSONObject): String {
            val label = name(t)
            val marks = listOfNotNull("forced".takeIf { t.optBoolean("forced") && "forced" !in label.lowercase() },
                "SDH".takeIf { t.optBoolean("sdh") && "sdh" !in label.lowercase() })
            return if (marks.isEmpty()) label else "$label (${marks.joinToString(", ")})"
        }

        private fun name(t: JSONObject): String = Info.text(t, "label") ?: Info.text(t, "lang")?.let(::language)
            ?: "#${t.optInt("i") + 1}"

        fun language(code: String): String =
            Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty { code }

        private fun codecName(codec: String?) = when (codec) {
            "eac3" -> "DD+"
            "ac3" -> "DD"
            "truehd" -> "TrueHD"
            "dts-hd" -> "DTS-HD"
            "dts" -> "DTS"
            "dts-x" -> "DTS:X"
            null -> null
            else -> codec.uppercase()
        }

        private fun channels(ch: Int) = when (ch) {
            8 -> "7.1"
            6 -> "5.1"
            2 -> "2.0"
            1 -> "mono"
            0 -> null
            else -> "$ch ch"
        }
    }
}
