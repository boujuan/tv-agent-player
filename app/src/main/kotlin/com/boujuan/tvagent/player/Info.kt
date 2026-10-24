package com.boujuan.tvagent.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.json.JSONObject

/**
 * What the loading and pause screens show about an item: `load`'s and `preload`'s `info`, or the fields of a
 * `loading` (PROTOCOL.md). Every field may be missing; an old agent sends none.
 */
data class Info(
    val title: String? = null, val subtitle: String? = null, val plot: String? = null,
    val poster: String? = null, val backdrop: String? = null, val logo: String? = null,
    val source: String? = null, val description: String? = null, val debrid: String? = null, val size: Long = 0,
    val release: String? = null, val badges: List<String> = emptyList(),
) {
    /** A later message about the same item: what it has replaces what this has. */
    fun with(newer: Info) = Info(newer.title ?: title, newer.subtitle ?: subtitle, newer.plot ?: plot,
        newer.poster ?: poster, newer.backdrop ?: backdrop, newer.logo ?: logo, newer.source ?: source,
        newer.description ?: description, newer.debrid ?: debrid, if (newer.size > 0) newer.size else size,
        newer.release ?: release, newer.badges.ifEmpty { badges })

    /** "Torrentio · Real-Debrid · 62.0 GB · TRiNiTY": where the file comes from. */
    fun origin(): String? = listOfNotNull(source, debrid, if (size > 0) gigabytes(size) else null, release)
        .joinToString(" · ").ifEmpty { null }

    companion object {
        fun of(o: JSONObject?): Info? {
            o ?: return null
            val badges = o.optJSONArray("badges")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).lowercase().ifBlank { null } } }
            return Info(text(o, "title"), text(o, "subtitle"), text(o, "plot"), image(o, "poster"), image(o, "backdrop"),
                image(o, "logo"), text(o, "source"), text(o, "description")?.let(Engine::scrub), text(o, "debrid"),
                o.optLong("size", 0).coerceAtLeast(0), text(o, "release"), badges.orEmpty())
        }

        fun text(o: JSONObject, key: String): String? = if (o.isNull(key)) null else o.optString(key).trim().ifEmpty { null }

        /** Artwork is public (Cinemeta, metahub, TMDB): an http(s) link or nothing. */
        private fun image(o: JSONObject, key: String): String? =
            text(o, key)?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

        fun gigabytes(bytes: Long): String = "%.1f GB".format(java.util.Locale.ROOT, bytes / 1e9)
    }
}

/** The corner badges: what the playing tracks are, plus what only the agent knows from the release (its `badges`). */
object Badges {
    fun of(video: Format?, audio: Format?, agent: List<String>): List<String> {
        val out = ArrayList<String>(8)
        if (video != null) {
            val w = video.width
            val h = video.height
            when {
                w >= 3200 || h >= 2000 -> out += "4K"
                w >= 1800 || h >= 1000 -> out += "1080p"
                w >= 1200 || h >= 700 -> out += "720p"
            }
            when (Codecs.hdr(video)) {
                "dv" -> out += "DV"
                "hdr10" -> if ("hdr10+" !in agent) out += "HDR10"
                "hlg" -> out += "HLG"
            }
            if ("hdr10+" in agent && Codecs.hdr(video) != "sdr") out += "HDR10+"
        }
        if (audio != null) {
            val mime = audio.sampleMimeType
            val label = audio.label.orEmpty().lowercase()
            when (mime) {
                MimeTypes.AUDIO_TRUEHD -> out += "TrueHD"
                MimeTypes.AUDIO_DTS_HD -> out += if ("hra" in label || "high res" in label) "DTS-HD HRA" else "DTS-HD MA"
                MimeTypes.AUDIO_DTS -> out += "DTS"
                MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> out += "DD+"
                MimeTypes.AUDIO_AC3 -> out += "DD"
            }
            // The release's Atmos or DTS:X is the main track's: it's shown only on a track of a codec that can carry it.
            val dolby = mime == MimeTypes.AUDIO_TRUEHD || mime == MimeTypes.AUDIO_E_AC3 || mime == MimeTypes.AUDIO_E_AC3_JOC
            val dts = mime == MimeTypes.AUDIO_DTS_HD || mime == MimeTypes.AUDIO_DTS || mime == MimeTypes.AUDIO_DTS_X
            if (mime == MimeTypes.AUDIO_E_AC3_JOC || dolby && ("atmos" in label || "atmos" in agent)) out += "Atmos"
            if (mime == MimeTypes.AUDIO_DTS_X || dts && ("dts:x" in label || "dts-x" in label || "dtsx" in agent)) out += "DTS:X"
            when (audio.channelCount) {
                8 -> out += "7.1"
                6 -> out += "5.1"
            }
        }
        if ("imax" in agent) out += "IMAX"
        if ("remux" in agent) out += "REMUX"
        return out
    }
}
