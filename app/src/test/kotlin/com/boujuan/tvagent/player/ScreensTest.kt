package com.boujuan.tvagent.player

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the screens are told and make of it (PROTOCOL.md, `loading` and `info`). */
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

    @Test
    fun picturesAreDecodedNoLargerThanShown() {
        assertEquals(1280 to 720, Artwork.fit(3840, 2160, 1280, 720, cover = true))
        assertEquals(1280 to 853, Artwork.fit(1500, 1000, 1280, 720, cover = true))     // covers the box
        assertEquals(1080 to 720, Artwork.fit(1500, 1000, 1280, 720, cover = false))    // fits inside it
        assertEquals(500 to 300, Artwork.fit(500, 300, 1280, 720, cover = true))        // never enlarged
    }
}
