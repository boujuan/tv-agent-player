package com.boujuan.tvagent.player

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
