package com.boujuan.tvagent.player

import android.content.Context
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewStub
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import org.json.JSONObject
import java.util.Date
import java.util.Locale

/**
 * The pause screen: over the paused picture, under the time bar and its controls (which change the tracks). What's
 * playing (`load`'s `info`), the clock and when it ends, and the badges in the corner. Inflated on the first pause,
 * GONE while playing; nothing in it runs while it's hidden.
 */
class PauseScreen(private val stub: ViewStub, private val artwork: Artwork, private val engine: Engine) {
    private val context: Context = stub.context
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

    private var shownFor: String? = null    // the item the info was filled for

    val showing get() = root?.visibility == View.VISIBLE

    /** Up on a pause (the remote's or the agent's, asked for at `since`, uptime), filled for what's loaded. */
    fun show(since: Long) {
        val item = engine.item ?: return
        if (showing) return update()
        val firstTime = root == null
        val v = root ?: inflate()
        if (shownFor != item.id || firstTime) fill(item)
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

    /** Tracks changed (the agent's `select`, a subtitle added): the badges follow. */
    fun tracksChanged() = update()

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
        v.visibility = View.GONE
        root = v
        return v
    }

    companion object {
        private const val TAG = "Pause"

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

        fun codecName(codec: String?) = when (codec) {
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
