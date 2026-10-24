package com.boujuan.tvagent.player

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewStub
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.common.C
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A picture of where a held ←/→ is going, above the time bar at the target (PROTOCOL.md, `thumb_req` and `thumb`).
 * The agent makes it from the file on the computer: this TV can't decode a second 4K frame while the film plays
 * (DESIGN.md, "Previews while seeking"). One request at a time; the next once the target has moved a 50th of the film,
 * or 0.4 s after the last. Inflated with the first picture; GONE, its picture let go, when the key comes up. An answer
 * to anything but the request waited for is dropped.
 */
class SeekPreview(private val stub: ViewStub, private val send: (JSONObject) -> Unit) {
    private var view: ImageView? = null
    private val density = stub.resources.displayMetrics.density
    /** The picture's width in pixels: what the agent is asked for, drawn 1:1. */
    private val wide = (WIDTH_DP * density).roundToInt()
    private val border = (BORDER_DP * density).roundToInt()

    private val pace = Pace()
    private var centre = 0f
    private var bottom = 0f

    val showing get() = view?.visibility == View.VISIBLE

    /** The target moved (each repeat of a held ←/→): asks for its picture if it's time to. */
    fun want(item: String?, target: Long, dur: Long, now: Long) {
        if (item != null && item != pace.item) hide()
        if (pace.ask(item, target, dur, now)) send(msg("thumb_req", "item" to item, "ms" to target, "w" to wide))
    }

    /** The agent's `thumb`: shown if it answers the request waited for while ←/→ is still held. */
    fun got(cmd: JSONObject, holding: Boolean) {
        if (!pace.answered(cmd.optString("item"), cmd.optLong("ms", -1))) return
        val jpeg = cmd.optString("jpeg")
        if (!holding || jpeg.isEmpty()) return
        val bytes = Base64.decode(jpeg, Base64.DEFAULT)
        val picture = try {
            Artwork.decode(bytes, wide, wide, cover = false)
        } catch (e: Exception) {
            return
        }
        val v = view ?: (stub.inflate() as ImageView).also { view = it }
        v.layoutParams = (v.layoutParams as FrameLayout.LayoutParams).apply {
            width = picture.width + 2 * border
            height = picture.height + 2 * border
        }
        v.setImageBitmap(picture)
        v.visibility = View.VISIBLE
        move()
        Log.i(TAG, "preview in ${SystemClock.uptimeMillis() - pace.askedAt} ms (${bytes.size} B)")
    }

    /** Where it goes: centred on x, its bottom at y (window pixels), kept on screen. */
    fun place(x: Float, y: Float) {
        centre = x
        bottom = y
        if (showing) move()
    }

    private fun move() {
        val v = view ?: return
        val w = v.layoutParams.width
        val edge = MARGIN_DP * density
        v.translationX = (centre - w / 2f).coerceIn(edge, maxOf(edge, (v.parent as View).width - w - edge))
        v.translationY = bottom - v.layoutParams.height
    }

    /** The key came up (or the hold ended otherwise): gone, and a late answer is dropped. */
    fun hide() {
        pace.reset()
        val v = view ?: return
        if (v.visibility == View.GONE) return
        v.visibility = View.GONE
        v.setImageDrawable(null)
    }

    /** When to ask: one request at a time, each for a target far enough from the last or a while after it. */
    class Pace {
        var item: String? = null
            private set
        private var asked = C.TIME_UNSET                     // the last request's target
        var askedAt = 0L                                     // when (uptime ms)
            private set
        private var waiting = false                          // for the answer to that request

        /** Whether to ask now for `target`'s picture (and it's then the one waited for). */
        fun ask(item: String?, target: Long, dur: Long, now: Long): Boolean {
            if (item == null || dur <= 0) return false
            if (item != this.item) {
                reset()
                this.item = item
            }
            if (waiting && now - askedAt < TIMEOUT_MS || target == asked) return false
            if (asked != C.TIME_UNSET && abs(target - asked) < dur / STEPS && now - askedAt < EVERY_MS) return false
            asked = target
            askedAt = now
            waiting = true
            return true
        }

        /** Whether an answer is to the request waited for (each is taken once; others are stale). */
        fun answered(item: String?, ms: Long): Boolean {
            if (!waiting || item != this.item || ms != asked) return false
            waiting = false
            return true
        }

        fun reset() {
            asked = C.TIME_UNSET
            waiting = false
        }
    }

    companion object {
        private const val TAG = "Preview"
        private const val WIDTH_DP = 160                     // 320 px on the TV's 1920×1080 UI: a sixth of the width
        private const val BORDER_DP = 1
        private const val MARGIN_DP = 24
        private const val STEPS = 50                         // a 50th of the film: 3 min of a 2.5-hour one
        private const val EVERY_MS = 400L
        private const val TIMEOUT_MS = 2_000L                // an answer that never came: ask again
    }
}
