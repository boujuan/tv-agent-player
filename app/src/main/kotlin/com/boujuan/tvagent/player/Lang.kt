package com.boujuan.tvagent.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util

/**
 * The agent's track rules (tv-agent's languages.py), for a file's tracks as Media3 reads them. Media3 reads no MKV
 * role flags and only the legacy language element, so commentary, SDH and the two Chinese scripts are told apart by
 * the track's name.
 */
object Lang {
    private val SIMPLIFIED =
        Regex("simplified|简体|簡體|简|\\bchs\\b|hans|\\bsc\\b|zh-?cn|chinese \\(prc\\)|mandarin", RegexOption.IGNORE_CASE)
    private val TRADITIONAL = Regex(
        "traditional|繁體|繁体|繁|\\bcht\\b|hant|\\btc\\b|zh-?tw|zh-?hk|taiwan|hong ?kong|cantonese|big5", RegexOption.IGNORE_CASE)
    // Commentary and audio-description tracks: never the one to play by default.
    private val EXTRA_AUDIO = Regex("comment|коментар|комментар|director|descri|\\bAD\\b", RegexOption.IGNORE_CASE)
    private val SDH = Regex("(?i)\\bsdh\\b|\\bcc\\b|hearing|impaired|(?-i)\\bHI\\b")
    private val LATIN = Regex("latino|latin|\\blat\\b|latam|es-419", RegexOption.IGNORE_CASE)
    private val FORCED = Regex("forced|forzad", RegexOption.IGNORE_CASE)

    /** The agent's code ("eng", "spa", "chs"…) as Media3 normalises a file's language ("en", "es", "zh"). */
    fun code(want: String): String = when (val w = want.lowercase()) {
        "chs", "cht" -> "zh"
        else -> Util.normalizeLanguageCode(w).substringBefore('-')
    }

    fun matches(f: Format, want: String): Boolean {
        val lang = f.language?.let { Util.normalizeLanguageCode(it) } ?: return false
        if (lang.substringBefore('-') != code(want)) return false
        val hint = "${f.label.orEmpty()} $lang"
        return when (want.lowercase()) {
            "chs" -> SIMPLIFIED.containsMatchIn(hint) || lang == "zh-hans" || lang == "zh-cn"
            "cht" -> TRADITIONAL.containsMatchIn(hint) || lang == "zh-hant" || lang == "zh-tw" || lang == "zh-hk"
            else -> true
        }
    }

    fun isExtraAudio(f: Format): Boolean =
        f.roleFlags and (C.ROLE_FLAG_COMMENTARY or C.ROLE_FLAG_DESCRIBES_VIDEO) != 0 ||
            EXTRA_AUDIO.containsMatchIn(f.label.orEmpty())

    fun isForced(f: Format): Boolean =
        f.selectionFlags and C.SELECTION_FLAG_FORCED != 0 || FORCED.containsMatchIn(f.label.orEmpty())

    fun isSdh(f: Format): Boolean =
        f.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0 || SDH.containsMatchIn(f.label.orEmpty())

    private fun isLatin(f: Format, want: String): Boolean =
        code(want) == "es" && (LATIN.containsMatchIn(f.label.orEmpty()) || f.language?.lowercase() == "es-419")

    /**
     * The track in that language, not a commentary or audio description; Castilian before Latin American; then the
     * one the file marks default (a release's main track), the most channels, the better codec, as Kodi and Just
     * Player would.
     */
    fun pickAudio(tracks: List<Format>, want: String): Int? =
        tracks.indices.filter { matches(tracks[it], want) }
            .sortedWith(compareBy<Int>({ isExtraAudio(tracks[it]) }, { isLatin(tracks[it], want) },
                { tracks[it].selectionFlags and C.SELECTION_FLAG_DEFAULT == 0 }, { -tracks[it].channelCount },
                { -codecRank(tracks[it].sampleMimeType) }))
            .firstOrNull()

    private fun codecRank(mime: String?): Int = when (mime) {
        MimeTypes.AUDIO_TRUEHD -> 6
        MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_X -> 5
        MimeTypes.AUDIO_E_AC3_JOC -> 4
        MimeTypes.AUDIO_E_AC3 -> 3
        MimeTypes.AUDIO_DTS -> 2
        MimeTypes.AUDIO_AC3 -> 1
        else -> 0
    }

    /** The first language that has a full (not forced) track; plain before SDH; Castilian before Latin American. */
    fun pickText(tracks: List<Format>, wants: List<String>): Int? {
        for (want in wants) {
            tracks.indices.filter { matches(tracks[it], want) && !isForced(tracks[it]) }
                .sortedWith(compareBy({ isSdh(tracks[it]) }, { isLatin(tracks[it], want) }))
                .firstOrNull()?.let { return it }
        }
        return null
    }

    /** With subtitles off: a forced track in the audio's language, which translates the lines in other languages. */
    fun pickForced(tracks: List<Format>, audioLanguage: String?): Int? {
        val audio = audioLanguage?.let { Util.normalizeLanguageCode(it).substringBefore('-') } ?: return null
        return tracks.indices.firstOrNull { i ->
            val f = tracks[i]
            isForced(f) && f.language?.let { Util.normalizeLanguageCode(it).substringBefore('-') } == audio
        }
    }
}
