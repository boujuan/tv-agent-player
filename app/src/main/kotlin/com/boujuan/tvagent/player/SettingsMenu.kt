package com.boujuan.tvagent.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Settings lists, in a Menu: every option with its values, and About. A subtitle look being picked is shown at
 * once on a sample line (`preview`) and undone if it's left without OK. Used by the pause screen and by the settings
 * screen on the home.
 */
class SettingsMenu(private val context: Context, private val settings: Settings, private val menu: Menu,
                   private val preview: (Boolean) -> Unit, private val applied: () -> Unit,
                   private val agent: () -> String?) {

    fun open() {
        lateinit var page: Menu.Page
        page = Menu.Page(context.getString(R.string.settings), rows()) { k ->
            if (k == Settings.ALL.size) menu.push(about())
            else menu.push(values(Settings.ALL[k]) { page.rows = rows() })
            false
        }
        menu.push(page)
    }

    private fun rows() = Settings.ALL.map { o ->
        context.getString(R.string.entry, context.getString(o.label), o.show(context, settings[o]))
    } + context.getString(R.string.about)

    /** One option's values; OK keeps the picked one, Back or ← puts the old one back. */
    fun values(o: Settings.Option, names: List<String> = o.values.map { o.show(context, it) },
               values: IntArray = o.values, done: () -> Unit = {}): Menu.Page {
        val before = settings[o]
        val look = o in Settings.LOOK
        val now = values.indexOf(before)
        if (look) preview(true)
        return Menu.Page(context.getString(o.label), names, at = if (now >= 0) now else nearest(values, before), current = now,
            moved = { k ->
                if (look) {
                    settings[o] = values[k]
                    applied()
                }
            },
            left = {
                if (look) {
                    settings[o] = before
                    applied()
                    preview(false)
                }
            },
            picked = { k ->
                settings[o] = values[k]
                applied()
                if (look) preview(false)
                done()
                true
            })
    }

    private fun about(): Menu.Page {
        val lines = mutableListOf(
            context.getString(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            agent() ?: Pairing.load(context)?.let { context.getString(R.string.about_paired, it.hosts.joinToString(", "), it.port) }
                ?: context.getString(R.string.not_paired),
            context.getString(R.string.update_checking))
        val page = Menu.Page(context.getString(R.string.about), lines) { false }
        latest { version ->
            lines[2] = when {
                version == null -> context.getString(R.string.update_failed)
                newer(version, BuildConfig.VERSION_NAME) -> context.getString(R.string.update_newer, version)
                else -> context.getString(R.string.update_latest, version)
            }
            if (menu.top === page) menu.redraw(lines)
        }
        return page
    }

    companion object {
        private const val LATEST = "https://api.github.com/repos/boujuan/tv-agent-player/releases/latest"

        private fun nearest(values: IntArray, v: Int) = values.indices.minBy { kotlin.math.abs(values[it] - v) }

        /** The latest release's version on GitHub (null if it can't be had), asked only when About opens. */
        fun latest(done: (String?) -> Unit) {
            val main = Handler(Looper.getMainLooper())
            Thread({
                val version = try {
                    val c = URL(LATEST).openConnection() as HttpURLConnection
                    try {
                        c.connectTimeout = 5_000
                        c.readTimeout = 5_000
                        c.setRequestProperty("Accept", "application/vnd.github+json")
                        JSONObject(c.inputStream.bufferedReader().readText()).optString("tag_name").removePrefix("v").ifEmpty { null }
                    } finally {
                        c.disconnect()
                    }
                } catch (_: Exception) {
                    null
                }
                main.post { done(version) }
            }, "update-check").apply { priority = Thread.MIN_PRIORITY }.start()
        }

        /** "0.4.1" over "0.4.0"; a test build ("0.4.0-test") counts as its release's number. */
        fun newer(latest: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(latest)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }
    }
}
