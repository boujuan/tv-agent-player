package com.boujuan.tvagent.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** FlagOriginal read from a Matroska file's first bytes (MkvTracks), which Media3 doesn't read. */
class MkvTracksTest {
    private fun el(id: Int, vararg children: ByteArray, unknownSize: Boolean = false): ByteArray {
        val data = ByteArrayOutputStream().apply { children.forEach { write(it) } }.toByteArray()
        val out = ByteArrayOutputStream()
        val idBytes = when {
            id > 0xFFFFFF -> 4
            id > 0xFFFF -> 3
            id > 0xFF -> 2
            else -> 1
        }
        for (k in idBytes - 1 downTo 0) out.write((id shr (8 * k)) and 0xFF)
        if (unknownSize) out.write(byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1))
        else {
            out.write(0x40 or (data.size shr 8))                         // a 2-byte size
            out.write(data.size and 0xFF)
        }
        out.write(data)
        return out.toByteArray()
    }

    private fun uint(id: Int, v: Int) = el(id, byteArrayOf(v.toByte()))

    private fun track(number: Int, original: Int?): ByteArray =
        el(0xAE, uint(0xD7, number), uint(0x83, 2), *listOfNotNull(original?.let { uint(0x55AE, it) }).toTypedArray())

    private fun file(vararg tracks: ByteArray): ByteArray {
        val head = el(0x1A45DFA3, uint(0x4286, 1))
        val segment = el(0x18538067, el(0x114D9B74), el(0x1549A966, uint(0x2AD7B1, 1)), el(0x1654AE6B, *tracks),
            el(0x1F43B675, uint(0xE7, 0)), unknownSize = true)
        return head + segment
    }

    @Test
    fun theTracksMarkedOriginalAreFound() {
        val b = file(track(1, null), track(2, 1), track(3, 0), track(4, 1))
        assertEquals(setOf(2, 4), MkvTracks.originals(b, b.size))
    }

    @Test
    fun noneMarkedIsEmptyAndTooFewBytesIsNotKnownYet() {
        val b = file(track(1, null), track(2, 0))
        assertEquals(emptySet<Int>(), MkvTracks.originals(b, b.size))
        assertNull(MkvTracks.originals(b, 30))                                  // the Tracks not all there yet
        assertNull(MkvTracks.originals(byteArrayOf(1, 2, 3, 4), 4))               // not Matroska
    }
}
