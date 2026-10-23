package com.boujuan.tvagent.player

import androidx.media3.common.C
import androidx.media3.common.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The same cases as tv-agent's test_language_plan, plus what only the player decides (forced, commentary, script). */
class LangTest {
    private fun t(lang: String?, label: String? = null, flags: Int = 0): Format =
        Format.Builder().setLanguage(lang).setLabel(label).setSelectionFlags(flags).build()

    @Test
    fun audioByLanguage() {
        assertEquals(1, Lang.pickAudio(listOf(t("fre"), t("eng")), "eng"))
        assertNull(Lang.pickAudio(listOf(t("fre")), "eng"))
    }

    @Test
    fun castilianBeforeLatinAmerican() {
        assertEquals(1, Lang.pickAudio(listOf(t("spa", "Latino"), t("spa", "Castellano")), "spa"))
        assertEquals(1, Lang.pickText(listOf(t("spa", "Spanish (Latin America)"), t("spa", "Spanish")), listOf("spa")))
    }

    @Test
    fun theReleasesMainTrackAmongTheLanguagesOwn() {
        fun a(mime: String, ch: Int, flags: Int = 0) = Format.Builder().setLanguage("eng").setSampleMimeType(mime)
            .setChannelCount(ch).setSelectionFlags(flags).build()
        // Oppenheimer (TheBiscuitMan): E-AC3 first, the default DTS-HD MA second
        val opp = listOf(a("audio/eac3", 6), a("audio/vnd.dts.hd", 6, C.SELECTION_FLAG_DEFAULT), t("hin"))
        assertEquals(1, Lang.pickAudio(opp, "eng"))
        assertEquals(1, Lang.pickAudio(listOf(a("audio/ac3", 6), a("audio/true-hd", 8)), "eng"))     // more channels
        assertEquals(1, Lang.pickAudio(listOf(a("audio/ac3", 6), a("audio/eac3", 6)), "eng"))        // better codec
    }

    @Test
    fun neverCommentaryByDefault() {
        val tracks = listOf(t("eng", "Director's Commentary"), t("eng", "English Atmos"), t("eng", "Audio Description"))
        assertEquals(1, Lang.pickAudio(tracks, "eng"))
    }

    @Test
    fun plainSubtitlesBeforeSdhAndNeverForcedOnes() {
        val subs = listOf(t("fre"), t("eng", "SDH"), t("eng", "English"))
        assertEquals(2, Lang.pickText(subs, listOf("eng", "spa")))
        val forcedFirst = listOf(t("eng", "Forced", C.SELECTION_FLAG_FORCED or C.SELECTION_FLAG_DEFAULT), t("eng", "SDH"))
        assertEquals(1, Lang.pickText(forcedFirst, listOf("eng")))
        assertEquals(1, Lang.pickText(listOf(t("eng", "English HI"), t("spa")), listOf("spa", "eng")))
    }

    @Test
    fun theNextLanguageWhenTheFirstIsMissing() {
        assertEquals(1, Lang.pickText(listOf(t("fre"), t("spa")), listOf("eng", "spa")))
        assertNull(Lang.pickText(listOf(t("fre")), listOf("eng", "spa")))
    }

    @Test
    fun subtitlesOffStillShowForcedInTheAudioLanguage() {
        val subs = listOf(t("eng", "English"), t("eng", "Forced", C.SELECTION_FLAG_FORCED), t("spa", "Forzados"))
        assertEquals(1, Lang.pickForced(subs, "en"))
        assertEquals(2, Lang.pickForced(subs, "es"))
        assertNull(Lang.pickForced(subs, "fr"))
        assertNull(Lang.pickForced(subs, null))
    }

    @Test
    fun simplifiedAndTraditionalChineseByName() {
        val subs = listOf(t("chi", "繁體中文"), t("chi", "简体中文"), t("chi", "Chinese (Traditional)"))
        assertEquals(1, Lang.pickText(subs, listOf("chs")))
        assertEquals(0, Lang.pickText(subs, listOf("cht")))
        assertNull(Lang.pickText(listOf(t("chi", "Chinese")), listOf("chs")))
        assertEquals(0, Lang.pickText(listOf(t("zho", "CHS")), listOf("chs")))
    }

    @Test
    fun codesAsMedia3NormalisesThem() {
        assertEquals("en", Lang.code("eng"))
        assertEquals("es", Lang.code("spa"))
        assertEquals("zh", Lang.code("chs"))
        assertEquals("de", Lang.code("ger"))
    }
}
