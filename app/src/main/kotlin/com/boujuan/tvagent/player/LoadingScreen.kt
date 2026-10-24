package com.boujuan.tvagent.player

import android.view.View
import android.view.ViewStub
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject

/**
 * The loading screen (PROTOCOL.md, `loading`): up while the agent finds the stream and the file opens, gone at the
 * first frame. Inflated on the first `loading`; while hidden it's GONE, with its pictures let go.
 */
class LoadingScreen(private val stub: ViewStub, private val artwork: Artwork) {
    private var root: View? = null
    private lateinit var backdrop: ImageView
    private lateinit var poster: ImageView
    private lateinit var logo: ImageView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var heard: TextView
    private lateinit var step: TextView

    /** The item it's for, while it's up. */
    var item: String? = null
        private set
    private var info = Info()
    private var said: String? = null

    val showing get() = root?.visibility == View.VISIBLE

    fun show(cmd: JSONObject) {
        val id = Info.text(cmd, "item")
        val same = showing && id == item
        val news = Info.of(cmd) ?: Info()
        info = if (same) info.with(news) else news
        said = Info.text(cmd, "heard") ?: said.takeIf { same }
        item = id
        val v = root ?: inflate()
        title.text = info.title
        subtitle.text = info.subtitle
        subtitle.visibility = if (info.subtitle == null) View.GONE else View.VISIBLE
        heard.text = said?.let { v.context.getString(R.string.heard, it) }
        heard.visibility = if (said == null) View.GONE else View.VISIBLE
        val s = Info.text(cmd, "step")
        if (s != null || !same) step.text = s
        if (v.visibility != View.VISIBLE || v.alpha < 1f) {
            v.animate().cancel()
            if (v.visibility != View.VISIBLE) v.alpha = 0f
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).setDuration(FADE_MS)
        }
        // Pictures at about the size they're drawn: the backdrop under a scrim needs less than the screen's pixels.
        val wide = v.resources.displayMetrics.widthPixels
        picture(backdrop, info.backdrop, wide * 2 / 3, wide * 3 / 8, cover = true)
        picture(poster, info.poster.takeIf { info.backdrop == null }, wide / 5, wide * 3 / 10, cover = false)
        picture(logo, info.logo, wide / 3, wide / 9, cover = false) { title.visibility = View.GONE }
        title.visibility = if (logo.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /** At the first frame, on `osd ""` or `stop`: fades out over the picture, and lets the pictures go. */
    fun hide() {
        val v = root ?: return
        if (v.visibility != View.VISIBLE) return
        item = null
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            v.visibility = View.GONE
            for (image in listOf(backdrop, poster, logo)) {
                image.setImageDrawable(null)
                image.tag = null
                image.visibility = View.GONE
            }
            artwork.drop(info.backdrop)                     // the big one; the logo and poster stay for the pause screen
        }
    }

    /** `url` into `view` once it's in, if the screen still wants that picture then. */
    private fun picture(view: ImageView, url: String?, w: Int, h: Int, cover: Boolean, shown: () -> Unit = {}) {
        if (view.tag == url && url != null) return
        view.tag = url
        view.setImageDrawable(null)
        view.visibility = View.GONE
        url ?: return
        artwork.get(url, item, w, h, cover) { bitmap ->
            if (view.tag != url || !showing) return@get
            view.setImageBitmap(bitmap)
            view.visibility = View.VISIBLE
            shown()
        }
    }

    private fun inflate(): View {
        val v = stub.inflate()
        backdrop = v.findViewById(R.id.loading_backdrop)
        poster = v.findViewById(R.id.loading_poster)
        logo = v.findViewById(R.id.loading_logo)
        title = v.findViewById(R.id.loading_title)
        subtitle = v.findViewById(R.id.loading_subtitle)
        heard = v.findViewById(R.id.loading_heard)
        step = v.findViewById(R.id.loading_step)
        v.visibility = View.GONE
        root = v
        return v
    }

    companion object {
        private const val FADE_MS = 200L
    }
}
