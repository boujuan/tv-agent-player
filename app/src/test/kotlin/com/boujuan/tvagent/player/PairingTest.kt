package com.boujuan.tvagent.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingTest {
    private val pin = "bd934e07d6784766bbfb3948bf3467ef9b2cb0c9c1146fdf9528dc76c8eb1e08"

    @Test
    fun acceptsAGoodPairing() {
        val p = Pairing.of(" 192.168.1.20 ", 8770, "x".repeat(43), pin.uppercase().chunked(2).joinToString(":"))
        assertEquals(Pairing("192.168.1.20", 8770, "x".repeat(43), pin), p)
    }

    @Test
    fun refusesWhatCantWork() {
        assertNull(Pairing.of("", 8770, "x".repeat(43), pin))
        assertNull(Pairing.of("192.168.1.20", 0, "x".repeat(43), pin))
        assertNull(Pairing.of("192.168.1.20", 8770, "short", pin))
        assertNull(Pairing.of("192.168.1.20", 8770, "x".repeat(43), "abc"))
        assertNull(Pairing.of("192.168.1.20", 8770, null, pin))
    }
}
