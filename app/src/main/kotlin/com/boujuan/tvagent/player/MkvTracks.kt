package com.boujuan.tvagent.player

/**
 * Which of a Matroska file's tracks it marks as the original (TrackEntry's FlagOriginal, 0x55AE): Media3 doesn't read
 * that flag, so it's read here from the first bytes of the file, which the player reads anyway (Http keeps a copy).
 * Media3 names a Matroska track by its TrackNumber (Format.id), so the two meet there.
 */
object MkvTracks {
    private const val EBML = 0x1A45DFA3
    private const val SEGMENT = 0x18538067
    private const val TRACKS = 0x1654AE6B
    private const val CLUSTER = 0x1F43B675
    private const val TRACK_ENTRY = 0xAE
    private const val TRACK_NUMBER = 0xD7
    private const val FLAG_ORIGINAL = 0x55AE

    /** The track numbers marked original (empty: none is); null until the bytes hold the whole Tracks element. */
    fun originals(b: ByteArray, n: Int): Set<Int>? {
        var e = element(b, 0, n) ?: return null
        if (e.id != EBML || e.size < 0) return null
        e = element(b, e.data + e.size.toInt(), n) ?: return null
        if (e.id != SEGMENT) return null
        var i = e.data
        while (true) {
            val c = element(b, i, n) ?: return null
            if (c.id == CLUSTER || c.size < 0) return null             // Tracks come before the first cluster
            val end = c.data + c.size
            if (c.id == TRACKS) return if (end > n) null else tracks(b, c.data, end.toInt())
            if (end >= n) return null
            i = end.toInt()
        }
    }

    private fun tracks(b: ByteArray, from: Int, to: Int): Set<Int> {
        val out = HashSet<Int>()
        var i = from
        while (i < to) {
            val entry = element(b, i, to) ?: break
            val end = (entry.data + entry.size).toInt()
            if (entry.id == TRACK_ENTRY && entry.size >= 0 && end <= to) {
                var number = -1
                var original = false
                var j = entry.data
                while (j < end) {
                    val f = element(b, j, end) ?: break
                    val fEnd = (f.data + f.size).toInt()
                    if (f.size < 0 || fEnd > end) break
                    when (f.id) {
                        TRACK_NUMBER -> number = uint(b, f.data, fEnd).toInt()
                        FLAG_ORIGINAL -> original = uint(b, f.data, fEnd) == 1L
                    }
                    j = fEnd
                }
                if (original && number > 0) out += number
            }
            if (entry.size < 0) break
            i = end
        }
        return out
    }

    /** An element's id, its data's size (-1: unknown) and where its data starts; null if the bytes stop first. */
    private class Element(val id: Int, val size: Long, val data: Int)

    private fun element(b: ByteArray, at: Int, n: Int): Element? {
        if (at >= n) return null
        val first = b[at].toInt() and 0xFF
        val idLength = Integer.numberOfLeadingZeros(first) - 23        // 1-4: the id keeps its marker bit
        if (idLength !in 1..4 || at + idLength >= n) return null
        var id = 0
        for (k in 0 until idLength) id = (id shl 8) or (b[at + k].toInt() and 0xFF)
        val s = at + idLength
        val head = b[s].toInt() and 0xFF
        val sizeLength = Integer.numberOfLeadingZeros(head) - 23       // 1-8
        if (sizeLength !in 1..8 || s + sizeLength > n) return null
        var size = (head and (0xFF shr sizeLength)).toLong()
        var unknown = size == (0xFFL shr sizeLength)
        for (k in 1 until sizeLength) {
            val v = b[s + k].toInt() and 0xFF
            size = (size shl 8) or v.toLong()
            unknown = unknown && v == 0xFF
        }
        return Element(id, if (unknown) -1 else size, s + sizeLength)
    }

    private fun uint(b: ByteArray, from: Int, to: Int): Long {
        var v = 0L
        for (k in from until to) v = (v shl 8) or (b[k].toLong() and 0xFF)
        return v
    }
}
