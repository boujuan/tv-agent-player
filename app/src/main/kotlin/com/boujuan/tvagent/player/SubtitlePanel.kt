package com.boujuan.tvagent.player

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * The subtitles panel, see-through over the top of the picture: the languages on the left (Off first), the chosen
 * language's tracks in the middle (the file's and the ones added from add-ons), the subtitles' timing and look on
 * the right. ←/→ go between the columns, ↑/↓ within one. OK on a language goes to its tracks, on a track shows it,
 * on a setting starts changing it: ↑/↓ or ←/→ change it at once (the look on a sample line), OK keeps it, Back puts
 * it back. Back closes the panel. A track goes through Engine.select, so the agent gets its `tracks` event.
 */
class SubtitlePanel(private val root: View, private val engine: Engine, private val settings: Settings,
                    private val sample: (Boolean) -> Unit, private val applied: () -> Unit) {
    private val context: Context = root.context
    private val columns = listOf(Column(R.id.subs_lang_scroll, R.id.subs_lang_list),
        Column(R.id.subs_track_scroll, R.id.subs_track_list), Column(R.id.subs_set_scroll, R.id.subs_set_list))

    private inner class Column(scrollId: Int, listId: Int) {
        val scroll: ScrollView = root.findViewById(scrollId)
        val list: LinearLayout = root.findViewById(listId)
        var at = 0

        init {
            // A ScrollView takes the arrows to scroll: here they move the pick.
            scroll.isFocusable = false
            scroll.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }

    /** A language: its name and its tracks' indexes in `tracks().text`; the first is Off (no tracks). */
    private class Language(val name: String, val tracks: List<Int>)

    private var languages: List<Language> = emptyList()
    private var text = JSONArray()
    private var focus = 0                       // the column the arrows are in
    private var adjusting: Setting? = null      // the setting being changed, with its value before
    private var before = 0L
    private var beforeDelay: Long? = null       // the delay set by hand before, null: none (Back puts that back)

    /** The right column's rows: the delay (this file's), then the look (kept on the TV). */
    private sealed class Setting {
        object Delay : Setting()
        class Look(val option: Settings.Option) : Setting()
    }

    private val rows: List<Setting> = listOf(Setting.Delay) + LOOK.map { Setting.Look(it) }

    val open get() = root.visibility == View.VISIBLE

    fun show() {
        read()
        val selected = selectedTrack()
        columns[0].at = languages.indexOfFirst { selected in it.tracks }.takeIf { it >= 0 } ?: 0
        columns[1].at = trackRow(selected)
        columns[2].at = 0
        focus = if (columns[0].at > 0) 1 else 0
        root.visibility = View.VISIBLE
        draw()
    }

    fun close() {
        if (!open) return
        stopAdjusting(keep = false)
        root.visibility = View.GONE
    }

    /** The tracks changed while it's open (one added, the agent's `select`): the columns follow. */
    fun tracksChanged() {
        if (!open) return
        read()
        columns[0].at = columns[0].at.coerceAtMost(languages.size - 1)
        draw()
    }

    /** The remote while it's open: it takes every arrow and OK. */
    fun onKey(keyCode: Int): Boolean {
        adjusting?.let { s ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT -> step(s, 1)
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT -> step(s, -1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> stopAdjusting(keep = true)
                else -> return false
            }
            draw()
            return true
        }
        val c = columns[focus]
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (c.at > 0) move(c.at - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (c.at < size(focus) - 1) move(c.at + 1)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (focus > 0) focus--
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (focus < 2) focus++
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> pick()
            else -> return false
        }
        draw()
        return true
    }

    /** Back: a setting being changed goes back to what it was; else the panel closes. */
    fun back(): Boolean {
        if (!open) return false
        if (adjusting != null) {
            stopAdjusting(keep = false)
            draw()
        } else close()
        return true
    }

    private fun move(to: Int) {
        columns[focus].at = to
        if (focus == 0) columns[1].at = trackRow(selectedTrack())     // that language's tracks, its shown one picked
    }

    private fun pick() {
        val off = columns[0].at == 0
        when (focus) {
            0 -> if (off) choose(null) else focus = 1
            1 -> if (off) choose(null) else languages.getOrNull(columns[0].at)?.tracks?.getOrNull(columns[1].at)?.let(::choose)
            2 -> rows.getOrNull(columns[2].at)?.let(::startAdjusting)
        }
    }

    private fun choose(track: Int?) {
        // The tracks may have changed meanwhile (one added): then there's nothing to apply.
        runCatching { engine.select(JSONObject().put("text", track ?: JSONObject.NULL)) }
        read()
    }

    // ---- the settings column ----

    private fun startAdjusting(s: Setting) {
        adjusting = s
        beforeDelay = engine.manualDelayMs
        before = when (s) {
            Setting.Delay -> engine.subtitleDelayMs
            is Setting.Look -> settings[s.option].toLong()
        }
        if (s is Setting.Look) sample(true)
    }

    private fun stopAdjusting(keep: Boolean) {
        val s = adjusting ?: return
        adjusting = null
        if (!keep) when (s) {
            Setting.Delay -> engine.manualDelayMs = beforeDelay
            is Setting.Look -> {
                settings[s.option] = before.toInt()
                applied()
            }
        }
        if (s is Setting.Look) sample(false)
    }

    private fun step(s: Setting, by: Int) {
        when (s) {
            // From what shows now (a measurement too): set by hand, it stays for this file
            Setting.Delay -> engine.manualDelayMs = (engine.subtitleDelayMs + by * DELAY_STEP_MS).coerceIn(-DELAY_MAX_MS, DELAY_MAX_MS)
            is Setting.Look -> {
                val values = s.option.values
                val k = (values.indexOf(settings[s.option]).takeIf { it >= 0 } ?: 0) + by
                settings[s.option] = values[k.coerceIn(0, values.size - 1)]
                applied()
            }
        }
    }

    // ---- drawing ----

    private fun read() {
        text = engine.tracks().getJSONArray("text")
        val byLang = LinkedHashMap<String, MutableList<Int>>()
        for (i in 0 until text.length()) {
            val lang = Info.text(text.getJSONObject(i), "lang") ?: "und"
            byLang.getOrPut(lang) { ArrayList() } += i
        }
        languages = listOf(Language(context.getString(R.string.off), emptyList())) + byLang.map { (lang, tracks) ->
            Language(if (lang == "und") "?" else PauseScreen.language(lang), tracks)
        }
    }

    /** The shown track (a forced one shown with subtitles off counts as off), or null. */
    private fun selectedTrack(): Int? = (0 until text.length()).firstOrNull {
        text.getJSONObject(it).optBoolean("selected") && !text.getJSONObject(it).optBoolean("forced")
    }

    /** The middle column's row for a track among the left column's language, else its first. */
    private fun trackRow(track: Int?): Int =
        languages.getOrNull(columns[0].at)?.tracks?.indexOf(track)?.takeIf { it >= 0 } ?: 0

    private fun size(column: Int) = when (column) {
        0 -> languages.size
        1 -> languages.getOrNull(columns[0].at)?.tracks?.size?.coerceAtLeast(1) ?: 1
        else -> rows.size
    }

    private fun draw() {
        val shown = selectedTrack()
        fill(0, languages.mapIndexed { k, l ->
            val count = if (l.tracks.size > 1) "  (${l.tracks.size})" else ""
            l.name + count to (if (k == 0) shown == null else shown in l.tracks)
        })
        val lang = languages.getOrNull(columns[0].at)
        fill(1, when {
            lang == null || columns[0].at == 0 ->
                listOf(context.getString(if (text.length() == 0) R.string.no_subtitles else R.string.subtitles_off) to (shown == null))
            else -> lang.tracks.map { i ->
                val t = text.getJSONObject(i)
                val codec = Info.text(t, "codec")?.uppercase()
                listOfNotNull(PauseScreen.textName(t), codec, "add-on".takeIf { t.optBoolean("external") }).joinToString(" · ") to (i == shown)
            }
        })
        fill(2, rows.map { s ->
            val (label, value) = when (s) {
                Setting.Delay -> context.getString(R.string.subtitle_delay) to delayText()
                is Setting.Look -> context.getString(s.option.label) to s.option.show(context, settings[s.option])
            }
            val shownValue = if (s === adjusting) context.getString(R.string.adjust, value) else value
            context.getString(R.string.entry, label, shownValue) to false
        })
    }

    /** "Synced −0.5 s" for the agent's measurement, else the delay set by hand, or none. */
    private fun delayText(): String {
        val auto = engine.autoDelayMs
        val ms = engine.subtitleDelayMs
        return when {
            engine.manualDelayMs == null && auto != null -> context.getString(R.string.delay_synced, auto / 1000.0)
            ms == 0L -> context.getString(R.string.delay_none)
            else -> context.getString(R.string.delay_value, ms / 1000.0)
        }
    }

    /** A column's rows, its pick white while the arrows are in it, the current one ticked. */
    private fun fill(column: Int, items: List<Pair<String, Boolean>>) {
        val c = columns[column]
        c.at = c.at.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        c.list.removeAllViews()
        val inflater = LayoutInflater.from(context)
        for ((k, item) in items.withIndex()) {
            val row = inflater.inflate(R.layout.choice, c.list, false) as TextView
            row.text = if (item.second) "${item.first}  ✓" else item.first
            row.isSelected = column == focus && k == c.at
            row.alpha = if (column == focus || item.second) 1f else 0.7f
            c.list.addView(row)
        }
        val row = c.list.getChildAt(c.at) ?: return
        c.scroll.post { c.scroll.smoothScrollTo(0, (row.top - c.scroll.height / 3).coerceAtLeast(0)) }
    }

    companion object {
        private const val DELAY_STEP_MS = 100L
        private const val DELAY_MAX_MS = 10_000L
        private val LOOK = listOf(Settings.SUB_SIZE, Settings.SUB_PLACE, Settings.SUB_COLOR, Settings.SUB_BACKGROUND,
            Settings.SUB_EDGE, Settings.SUB_FONT)
    }
}
