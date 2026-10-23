package com.boujuan.tvagent.player

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ProtocolTest {
    @Test
    fun linesAreReadWholeAndLimited() {
        val input = "{\"t\":\"play\",\"id\":1}\n{\"t\":\"ping\"}\n".byteInputStream()
        assertEquals("play", JSONObject(Link.readLine(input)!!).getString("t"))
        assertEquals("{\"t\":\"ping\"}", Link.readLine(input))
        assertNull(Link.readLine(input))
        assertThrows(IOException::class.java) { Link.readLine("cut".byteInputStream()) }
        assertThrows(IOException::class.java) { Link.readLine(ByteArray(Link.MAX_LINE + 1) { 'x'.code.toByte() }.inputStream()) }
    }

    @Test
    fun aLoadsPlan() {
        assertEquals(Selector.Plan("eng", listOf("eng", "spa")),
            Engine.plan(JSONObject("""{"audio":"eng","subs":["eng","spa"]}""")))
        assertEquals(Selector.Plan("spa", null), Engine.plan(JSONObject("""{"audio":"spa","subs":null}""")))
        assertEquals(Selector.Plan(null, null), Engine.plan(JSONObject("""{"audio":null}""")))
    }

    @Test
    fun errorTextNeverCarriesALink() {
        assertEquals("Response code: 403 for <link>",
            Engine.scrub("Response code: 403 for https://cdn.example/dl/KEY123/file.mkv?token=abc"))
    }

    @Test
    fun messagesLeaveNullsOut() {
        val m = msg("closed", "item" to null, "pos" to 5L, "why" to "back")
        assertEquals("""{"t":"closed","pos":5,"why":"back"}""", m.toString())
    }

    @Test
    fun clock() {
        assertEquals("0:05", PlayerActivity.clock(5_400))
        assertEquals("1:02:03", PlayerActivity.clock(3_723_000))
    }
}
