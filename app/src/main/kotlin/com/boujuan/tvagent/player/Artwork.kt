package com.boujuan.tvagent.player

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * The screens' pictures (poster, backdrop, logo), fetched only while a screen that shows them is up, on a thread
 * of their own at the lowest priority, and decoded down to the size they're shown at. Kept for the item they're
 * for and the next one only; a missing or slow picture is simply not shown.
 */
class Artwork(private val userAgent: String) {
    private val work = Executors.newSingleThreadExecutor { Thread(it, "artwork").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val kept = LinkedHashMap<String, Entry>()          // main thread
    private class Entry(val item: String?, var bitmap: Bitmap?, val waiting: MutableList<(Bitmap) -> Unit> = ArrayList())

    /** `show` gets the picture at about `w`×`h` pixels (covering it: `cover`, else fitting inside), now or once it's in. */
    fun get(url: String, item: String?, w: Int, h: Int, cover: Boolean, show: (Bitmap) -> Unit) {
        val known = kept[url]
        if (known != null) {
            known.bitmap?.let(show) ?: known.waiting.add(show)
            return
        }
        val entry = Entry(item, null)
        entry.waiting += show
        kept[url] = entry
        work.execute {
            val bitmap = try {
                decode(download(url), w, h, cover)
            } catch (e: Exception) {
                Log.i(TAG, "picture not shown: ${e.javaClass.simpleName}")
                null
            }
            main.post {
                if (kept[url] !== entry) {                          // let go of meanwhile
                    bitmap?.recycle()
                    return@post
                }
                if (bitmap == null) {
                    kept.remove(url)
                    return@post
                }
                entry.bitmap = bitmap
                entry.waiting.forEach { it(bitmap) }
                entry.waiting.clear()
            }
        }
    }

    /** Lets go of one picture (a backdrop once its screen has gone); an ImageView still showing it keeps it. */
    fun drop(url: String?) {
        kept.remove(url ?: return)
    }

    /** Keeps only the pictures of these items (the playing one and the next). */
    fun keepOnly(vararg items: String?) {
        kept.entries.removeAll { it.value.item !in items }
    }

    fun release() {
        kept.clear()
        work.shutdownNow()
    }

    private fun download(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", userAgent)
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            val out = ByteArrayOutputStream(max(c.contentLength, 64 shl 10))
            val buf = ByteArray(32 shl 10)
            c.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw java.io.IOException("too big")
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val TAG = "Artwork"
        private const val TIMEOUT_MS = 5_000
        private const val MAX_BYTES = 8 shl 20

        /** Decoded straight to the size shown (the decoder skips the detail), into graphics memory, not the heap. */
        fun decode(bytes: ByteArray, w: Int, h: Int, cover: Boolean): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val (tw, th) = fit(info.size.width, info.size.height, w, h, cover)
                decoder.setTargetSize(tw, th)
            }

        /** The size an image of iw×ih is decoded to for a box of w×h: never larger than it is. */
        fun fit(iw: Int, ih: Int, w: Int, h: Int, cover: Boolean): Pair<Int, Int> {
            val sx = w.toDouble() / iw
            val sy = h.toDouble() / ih
            val s = min(1.0, if (cover) max(sx, sy) else min(sx, sy))
            return max(1, (iw * s).toInt()) to max(1, (ih * s).toInt())
        }
    }
}
