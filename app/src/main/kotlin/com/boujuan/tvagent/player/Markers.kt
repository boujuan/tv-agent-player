package com.boujuan.tvagent.player

import org.json.JSONArray
import org.json.JSONObject

/**
 * Where a file's intro, recap and credits are, from tv-agent (`markers` in `load` and `preload`, or the `markers`
 * command when they're found later), and what the Next episode card offers. Only data: MarkerWatch decides what to
 * show at a position.
 */
class Marker(val kind: String, val start: Long, val end: Long, val auto: Boolean) {
    val skippable get() = kind == INTRO || kind == RECAP

    companion object {
        const val INTRO = "intro"
        const val RECAP = "recap"
        const val CREDITS = "credits"

        /** The markers in a `load`, `preload` or `markers` command; ones that don't make sense are left out. */
        fun list(a: JSONArray?): List<Marker> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val o = a!!.optJSONObject(i) ?: return@mapNotNull null
            val kind = o.optString("kind")
            val start = o.optLong("start", -1)
            val end = o.optLong("end", -1)
            Marker(kind, start, end, o.optBoolean("auto") && kind != CREDITS)
                .takeIf { kind in setOf(INTRO, RECAP, CREDITS) && start >= 0 && (end > start || kind == CREDITS) }
        }
    }
}

/** What the Next episode card offers: the episode's name and how long it counts down (0: no countdown). */
class NextUp(val title: String, val countdownMs: Long) {
    companion object {
        fun of(o: JSONObject?): NextUp? =
            o?.let { NextUp(Info.text(it, "title") ?: return null, it.optLong("countdown_ms", 0).coerceAtLeast(0)) }
    }
}

/**
 * For one load: at each position (the position tick, a second apart while playing; or a seek), which marker's Skip
 * button shows, whether one is skipped by itself, and whether the Next episode card shows. An `auto` intro or recap
 * is skipped when playing reaches its start, once per load, never when a seek lands inside it. Back hides a marker's
 * button, or the card, for the rest of the load.
 */
class MarkerWatch(var markers: List<Marker>, var next: NextUp?) {
    /** What to show now: the marker to skip by itself (once), the one whose button shows, and the card. */
    class Step(val skip: Marker?, val button: Marker?, val card: Boolean)

    private val done = HashSet<Marker>()            // auto ones skipped
    private val hidden = HashSet<Marker>()          // buttons hidden with Back
    var cardGone = false                            // the card dismissed or accepted
        private set
    private var last = -1L

    /** At `pos` (ms): reached by playing, or by a seek (`seek`: nothing is skipped by itself). */
    fun at(pos: Long, seek: Boolean = false): Step {
        val from = last
        last = pos
        val skip = if (seek || from < 0) null else markers.firstOrNull { m ->
            m.auto && m.skippable && m !in done && from < m.start && pos >= m.start - EARLY_MS && pos < m.end
        }
        skip?.let { done += it }
        val button = markers.firstOrNull { m ->
            m.skippable && !m.auto && m !in hidden && pos >= m.start && pos < m.end - BUTTON_ENDS_MS
        }
        val credits = markers.firstOrNull { it.kind == Marker.CREDITS }
        return Step(skip, button, next != null && credits != null && pos >= credits.start && !cardGone)
    }

    /** Back on a button: hidden for the rest of the load. */
    fun hide(m: Marker) {
        hidden += m
    }

    /** The card dismissed (Back) or accepted: not shown again in this load. */
    fun cardDone() {
        cardGone = true
    }

    /** When the next auto skip is due (ms in the file) after `pos`, to look exactly then; null: none to come. */
    fun nextAutoStart(pos: Long): Long? =
        markers.filter { it.auto && it.skippable && it !in done && it.start > pos }.minOfOrNull { it.start }

    companion object {
        const val BUTTON_ENDS_MS = 2_000L           // the button goes this long before the marker's end
        const val EARLY_MS = 300L                   // a look this close before the start counts (timer rounding)
    }
}
