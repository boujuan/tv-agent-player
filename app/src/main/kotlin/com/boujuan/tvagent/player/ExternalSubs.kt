package com.boujuan.tvagent.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Subtitles from elsewhere (the agent's add-ons), drawn over the picture by position. Media3 can only add a
 * subtitle file to a playing item by preparing the whole source again (a rebuffer of the 4K file), so these are
 * parsed here with Media3's own parsers (same timing and styles as a file's tracks) and shown by the player.
 */
class ExternalSubs {
    class Track(val lang: String, val label: String, val format: String, val events: List<CuesWithTiming>)

    val tracks = ArrayList<Track>()
    /** The track shown, or -1. */
    var shown = -1
    private var lastKey: List<CuesWithTiming> = emptyList()

    fun clear() {
        tracks.clear()
        shown = -1
        lastKey = emptyList()
    }

    /** The cues at `positionUs` of the shown track, or null when they're the same as last asked. */
    fun cuesAt(positionUs: Long): List<Cue>? {
        val active = tracks.getOrNull(shown)?.events?.let { active(it, positionUs) } ?: emptyList()
        if (active == lastKey) return null          // the same events (compared by identity)
        lastKey = active
        return active.flatMap { it.cues }
    }

    companion object {
        /** Parses a whole file (any charset, format guessed from its content if not given). Off the main thread. */
        fun parse(data: ByteArray, format: String?, lang: String, label: String): Track {
            val text = decode(data, lang)
            val kind = format?.lowercase() ?: guess(text)
            val mime = when (kind) {
                "vtt", "webvtt" -> MimeTypes.TEXT_VTT
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            val parser = DefaultSubtitleParserFactory().create(Format.Builder().setSampleMimeType(mime).build())
            val events = ArrayList<CuesWithTiming>()
            parser.parse(text.toByteArray(Charsets.UTF_8), SubtitleParser.OutputOptions.allCues()) { events += it }
            events.sortBy { it.startTimeUs }
            return Track(lang, label, kind, events)
        }

        fun guess(text: String): String {
            val head = text.trimStart(BOM, ' ', '\n', '\r').take(400)
            return when {
                head.startsWith("WEBVTT") -> "vtt"
                head.contains("[Script Info]", ignoreCase = true) -> "ass"
                head.startsWith("<?xml") || head.contains("<tt") -> "ttml"
                else -> "srt"
            }
        }

        /**
         * UTF-8 when it is; otherwise the usual legacy charset for the language (GB18030 for Chinese, Windows-1252
         * for Spanish, English and the like): add-on subtitles are often not UTF-8.
         */
        fun decode(data: ByteArray, lang: String): String {
            if (data.size >= 2 && (data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte() ||
                    data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte())) return String(data, Charsets.UTF_16)
            try {
                return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
            } catch (_: CharacterCodingException) {
            }
            val legacy = when (Lang.code(lang)) {
                "zh" -> "GB18030"
                "ja" -> "Shift_JIS"
                "ko" -> "EUC-KR"
                "ru", "uk", "bg" -> "windows-1251"
                "el" -> "windows-1253"
                "tr" -> "windows-1254"
                "pl", "cs", "hu", "ro" -> "windows-1250"
                else -> "windows-1252"
            }
            return String(data, Charset.forName(legacy))
        }

        /** The events covering `positionUs` (several can overlap in SSA), by binary search on start time. */
        fun active(events: List<CuesWithTiming>, positionUs: Long): List<CuesWithTiming> {
            var lo = 0
            var hi = events.size
            while (lo < hi) {                       // first event starting after positionUs
                val mid = (lo + hi) ushr 1
                if (events[mid].startTimeUs <= positionUs) lo = mid + 1 else hi = mid
            }
            val found = ArrayList<CuesWithTiming>(2)
            var i = lo - 1
            while (i >= 0 && lo - i <= LOOK_BACK) {
                val e = events[i]
                // No end (some formats): until the next event starts.
                val end = if (e.endTimeUs == C.TIME_UNSET) events.getOrNull(i + 1)?.startTimeUs ?: Long.MAX_VALUE
                else e.endTimeUs
                if (positionUs < end) found += e
                i--
            }
            found.reverse()
            return found
        }

        private const val BOM = '\uFEFF'
        private const val LOOK_BACK = 16            // overlapping events farther back than this are ignored
    }
}
