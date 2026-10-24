package com.boujuan.tvagent.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewStub
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject

/**
 * The Skip intro / Skip recap button and the Next episode card, bottom right over the picture; MarkerWatch decides
 * when. While the time bar is hidden the button is white and OK presses it; with the bar up it's a chip among the
 * controls instead (Controls asks `action`). An `auto` intro or recap is skipped by itself, with a short toast.
 * The card counts down while the film plays and then asks the agent for the next episode itself.
 *
 * Inflated the first time something shows. The checks ride Engine's position tick (a second apart while playing),
 * plus one timer set when an automatic skip is under a second and a half away, and the card's countdown while it
 * shows: nothing runs for a file without markers.
 */
class MarkerOverlay(private val stub: ViewStub, private val engine: Engine, private val host: Host) {
    interface Host {
        fun send(event: JSONObject)
        /** The time bar is up (its controls offer the button). */
        fun barShown(): Boolean
        /** The pause screen is up: nothing of this shows over it. */
        fun paused(): Boolean
    }

    private val main = Handler(Looper.getMainLooper())
    private val context = stub.context
    private var root: View? = null
    private lateinit var skipView: TextView
    private lateinit var skippedView: TextView
    private lateinit var card: View
    private lateinit var cardTitle: TextView
    private lateinit var countdown: ProgressBar
    private lateinit var hint: TextView

    private var watch: MarkerWatch? = null
    private var watchFor: Engine.Item? = null
    private var button: Marker? = null              // whose Skip button shows
    private var cardShown = false
    private var leftMs = 0L                         // the card's countdown left, ms
    private var lastCount = 0L                      // when it was last counted down (uptime)
    private var lift = 0f

    /** At the position tick, after a seek (`seekTo`), or when the markers change. */
    fun update(seekTo: Long? = null) {
        val item = engine.item ?: return clear()
        if (watchFor !== item) {
            clear()
            watchFor = item
            watch = MarkerWatch(item.markers, item.next)
        }
        if (item.markers.isEmpty() && button == null && !cardShown) return      // most files: nothing to look at
        val w = watch ?: return
        w.markers = item.markers
        w.next = item.next
        val pos = seekTo ?: engine.player.currentPosition
        val step = w.at(pos, seek = seekTo != null)
        step.skip?.let { skip(it, "auto", pos) }
        button = step.button
        if (step.card && !cardShown) startCard(item.next!!)
        else if (!step.card && cardShown) stopCard()
        draw()
        main.removeCallbacks(exact)
        val due = w.nextAutoStart(pos)
        if (due != null && engine.player.isPlaying && due - pos < EXACT_WITHIN_MS)
            main.postDelayed(exact, ((due - pos) / engine.speed).toLong().coerceAtLeast(0))
    }

    private val exact = Runnable { update() }

    /** What the controls' chip offers now ("Skip intro", "Next episode"), or null. */
    fun action(): String? = when {
        button != null -> label(button!!)
        cardShown -> context.getString(R.string.next_episode)
        else -> null
    }

    /** The chip pressed, or OK while the bar is hidden: true when there was something to do. */
    fun use(): Boolean {
        val b = button
        when {
            b != null -> skip(b, "button", engine.player.currentPosition)
            cardShown -> accept("button")
            else -> return false
        }
        return true
    }

    /** Back: the button hidden for this marker, or the card dismissed; false when neither showed. */
    fun back(): Boolean {
        val w = watch ?: return false
        val b = button
        when {
            b != null && !host.barShown() -> {
                w.hide(b)
                button = null
            }
            cardShown -> {
                w.cardDone()
                stopCard()
            }
            else -> return false
        }
        draw()
        return true
    }

    /** Whether OK while the bar is hidden is this overlay's (the button or the card shows). */
    fun takesOk(): Boolean = !host.barShown() && !host.paused() && (button != null || cardShown)

    /** Raised by `px` over the time bar while it's up; drawn again as it comes and goes. */
    fun lift(px: Float) {
        lift = px
        root?.translationY = -px
        draw()
    }

    private fun skip(m: Marker, how: String, from: Long) {
        engine.seek(m.end, "exact")
        host.send(msg("skipped", "item" to engine.item?.id, "kind" to m.kind, "from" to from, "to" to m.end, "how" to how))
        if (how == "auto") toast(context.getString(if (m.kind == Marker.RECAP) R.string.skipped_recap else R.string.skipped_intro))
        button = null
    }

    private fun accept(how: String) {
        watch?.cardDone()
        stopCard()
        draw()
        host.send(msg("next_episode", "item" to engine.item?.id, "how" to how))
    }

    private fun startCard(next: NextUp) {
        inflate()
        cardShown = true
        cardTitle.text = next.title
        leftMs = next.countdownMs
        lastCount = SystemClock.uptimeMillis()
        countdown.visibility = if (next.countdownMs > 0) View.VISIBLE else View.GONE
        main.post(count)
    }

    private fun stopCard() {
        cardShown = false
        main.removeCallbacks(count)
    }

    // The card's countdown: only while the film plays; at 0 the next episode is asked for
    private val count = object : Runnable {
        override fun run() {
            val next = watchFor?.next ?: return
            val now = SystemClock.uptimeMillis()
            if (engine.player.isPlaying && next.countdownMs > 0) leftMs -= now - lastCount
            lastCount = now
            if (next.countdownMs > 0 && leftMs <= 0) return accept("countdown")
            countdown.progress = if (next.countdownMs > 0) (1000 * leftMs / next.countdownMs).toInt() else 0
            hint.text = if (next.countdownMs > 0) context.getString(R.string.next_in, ((leftMs + 999) / 1000).toInt())
            else context.getString(R.string.next_now)
            main.postDelayed(this, COUNT_MS)
        }
    }

    private fun toast(text: String) {
        inflate()
        skippedView.text = text
        main.removeCallbacks(untoast)
        main.postDelayed(untoast, TOAST_MS)
        draw()
    }

    private val untoast = Runnable {
        root?.let { skippedView.text = null }
        draw()
    }

    private fun label(m: Marker) = context.getString(if (m.kind == Marker.RECAP) R.string.skip_recap else R.string.skip_intro)

    private fun draw() {
        val b = button
        val hidden = host.paused()
        if (root == null && (b == null || hidden) && !cardShown) return
        inflate()
        skipView.visibility = if (b != null && !hidden && !host.barShown()) View.VISIBLE else View.GONE
        if (b != null) skipView.text = label(b)
        skipView.isSelected = true                  // OK presses it: white, as a picked control
        skippedView.visibility = if (!skippedView.text.isNullOrEmpty() && !hidden && skipView.visibility != View.VISIBLE)
            View.VISIBLE else View.GONE
        card.visibility = if (cardShown && !hidden) View.VISIBLE else View.GONE
        root?.visibility = if (skipView.visibility == View.VISIBLE || skippedView.visibility == View.VISIBLE ||
            card.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun clear() {
        main.removeCallbacks(exact)
        stopCard()
        button = null
        watch = null
        watchFor = null
        root?.visibility = View.GONE
    }

    private fun inflate() {
        if (root != null) return
        val v = stub.inflate()
        skipView = v.findViewById(R.id.skip)
        skippedView = v.findViewById(R.id.skipped)
        card = v.findViewById(R.id.next_card)
        cardTitle = v.findViewById(R.id.next_title)
        countdown = v.findViewById(R.id.next_countdown)
        hint = v.findViewById(R.id.next_hint)
        v.translationY = -lift
        root = v
    }

    companion object {
        private const val EXACT_WITHIN_MS = 1_500L  // an automatic skip this close is timed exactly
        private const val COUNT_MS = 200L
        private const val TOAST_MS = 2_500L
    }
}
