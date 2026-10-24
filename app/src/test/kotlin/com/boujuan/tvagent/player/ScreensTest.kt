package com.boujuan.tvagent.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the loading and pause screens are told and make of it (PROTOCOL.md, `loading` and `info`). */
class ScreensTest {
    private val dune = JSONObject("""{"title": "Dune: Part Two", "subtitle": "2024 · 2 h 46 min", "plot": "Paul Atreides unites…",
        "poster": "https://images.metahub.space/poster/medium/tt15239678/img", "backdrop": "ftp://nope", "logo": "",
        "source": "Torrentio", "description": "Dune.Part.Two.2160p.REMUX https://real-debrid.com/d/KEY",
        "debrid": "Real-Debrid", "size": 66571993088, "release": "FraMeSToR", "badges": ["Atmos", "remux"]}""")

    @Test
    fun infoTakesWhatsThere() {
        val info = Info.of(dune)!!
        assertEquals("Dune: Part Two", info.title)
        assertEquals("https://images.metahub.space/poster/medium/tt15239678/img", info.poster)
        assertNull(info.backdrop)                                   // only http(s) pictures
        assertNull(info.logo)
        assertEquals("Dune.Part.Two.2160p.REMUX <link>", info.description)     // never a link
        assertEquals("Torrentio · Real-Debrid · 66.6 GB · FraMeSToR", info.origin())
        assertEquals(listOf("atmos", "remux"), info.badges)
        assertNull(Info.of(JSONObject()).let { it!!.origin() })
    }

    @Test
    fun aLaterLoadingChangesOnlyWhatItHas() {
        val first = Info.of(JSONObject("""{"title": "Dune: Part Two", "subtitle": "2024"}"""))!!
        val later = first.with(Info.of(JSONObject("""{"step": "Checking the link…"}"""))!!)
        assertEquals("Dune: Part Two", later.title)
        assertEquals("2024", later.subtitle)
    }

    private fun video(w: Int, h: Int, mime: String = MimeTypes.VIDEO_H265, transfer: Int = C.COLOR_TRANSFER_ST2084) =
        Format.Builder().setSampleMimeType(mime).setWidth(w).setHeight(h)
            .setColorInfo(ColorInfo.Builder().setColorTransfer(transfer).build()).build()

    private fun audio(mime: String, ch: Int, label: String? = null) =
        Format.Builder().setSampleMimeType(mime).setChannelCount(ch).setLabel(label).build()

    @Test
    fun badgesFromTheTracksAndTheRelease() {
        // Dune Part Two, the P7 REMUX: TrueHD Atmos 7.1, "REMUX" from the release's name
        assertEquals(listOf("4K", "DV", "TrueHD", "Atmos", "7.1", "REMUX"),
            Badges.of(video(3840, 2160, MimeTypes.VIDEO_DOLBY_VISION), audio(MimeTypes.AUDIO_TRUEHD, 8, "TrueHD Atmos 7.1"),
                listOf("atmos", "remux")))
        // Oppenheimer, scope 3840x1600, DTS-HD MA 5.1
        assertEquals(listOf("4K", "DV", "DTS-HD MA", "5.1"),
            Badges.of(video(3840, 1600, MimeTypes.VIDEO_DOLBY_VISION), audio(MimeTypes.AUDIO_DTS_HD, 6, "DTS-HD MA (From Blu-Ray)"), emptyList()))
        // A DV + HDR10+ WEB-DL with E-AC3 JOC
        assertEquals(listOf("4K", "DV", "HDR10+", "DD+", "Atmos", "5.1"),
            Badges.of(video(3840, 2160, MimeTypes.VIDEO_DOLBY_VISION), audio(MimeTypes.AUDIO_E_AC3_JOC, 6), listOf("hdr10+")))
        assertEquals(listOf("1080p", "HDR10", "DD", "5.1"), Badges.of(video(1920, 1080), audio(MimeTypes.AUDIO_AC3, 6), emptyList()))
        assertEquals(listOf("1080p", "HDR10+"), Badges.of(video(1920, 800), null, listOf("hdr10+")))
        // The release's Atmos isn't the Spanish DD 5.1 track's
        assertEquals(listOf("DD", "5.1"), Badges.of(null, audio(MimeTypes.AUDIO_AC3, 6), listOf("atmos")))
        assertEquals(listOf("720p"), Badges.of(video(1280, 720, transfer = C.COLOR_TRANSFER_SDR), audio(MimeTypes.AUDIO_AAC, 2), emptyList()))
    }

    @Test
    fun theMenusNamesForTracks() {
        assertEquals("English · TrueHD 7.1", PauseScreen.audioName(JSONObject("""{"i": 0, "lang": "en", "codec": "truehd", "ch": 8}""")))
        assertEquals("Castellano · DD 5.1", PauseScreen.audioName(JSONObject("""{"i": 1, "lang": "es", "label": "Castellano", "codec": "ac3", "ch": 6}""")))
        assertEquals("Spanish (forced)", PauseScreen.textName(JSONObject("""{"i": 2, "lang": "spa", "forced": true}""")))
        assertEquals("English SDH", PauseScreen.textName(JSONObject("""{"i": 3, "lang": "en", "label": "English SDH", "sdh": true}""")))
        assertEquals("#5", PauseScreen.textName(JSONObject("""{"i": 4, "lang": null}""")))
    }

    @Test
    fun picturesAreDecodedNoLargerThanShown() {
        assertEquals(1280 to 720, Artwork.fit(3840, 2160, 1280, 720, cover = true))
        assertEquals(1280 to 853, Artwork.fit(1500, 1000, 1280, 720, cover = true))     // covers the box
        assertEquals(1080 to 720, Artwork.fit(1500, 1000, 1280, 720, cover = false))    // fits inside it
        assertEquals(500 to 300, Artwork.fit(500, 300, 1280, 720, cover = true))        // never enlarged
    }

    @Test
    fun aboutTellsANewerRelease() {
        assertTrue(SettingsMenu.newer("0.4.1", "0.4.0"))
        assertTrue(SettingsMenu.newer("0.10.0", "0.9.9"))
        assertFalse(SettingsMenu.newer("0.4.0", "0.4.0"))
        assertFalse(SettingsMenu.newer("0.4.0", "0.4.0-test"))       // a test build is its release's number
        assertFalse(SettingsMenu.newer("0.3.9", "0.4.0"))
    }

    @Test
    fun everySettingStartsAtOneOfItsValues() {
        for (o in Settings.ALL) assertTrue(o.key, o.default in o.values)
        for (size in Settings.SIZES) assertTrue("$size %", size in Settings.SUB_SIZE.values)
    }
}
