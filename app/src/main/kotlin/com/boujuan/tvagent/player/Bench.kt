package com.boujuan.tvagent.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * `bench`: the HTTP stacks against a real link, the way playback uses them (DESIGN.md, Speed): the time to the first
 * byte, the throughput of one long read, and range requests at random offsets (each seek is one). Nothing plays.
 * Runs on its own thread; the link is never logged.
 */
object Bench {
    private const val SEEK_READ = 1 shl 20              // what a seek reads before the picture can move: ~1 MB

    fun run(http: Http, cmd: JSONObject): JSONObject {
        val uri = Uri.parse(cmd.getString("url"))
        val headers = cmd.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } }.orEmpty()
        val seconds = cmd.optInt("seconds", 8).coerceIn(1, 30)
        val seeks = cmd.optInt("seeks", 6).coerceIn(0, 20)
        val stacks = cmd.optJSONArray("stacks")?.let { a -> (0 until a.length()).map { Http.Stack.valueOf(a.getString(it).uppercase()) } }
            ?: Http.Stack.entries
        val out = JSONObject()
        for (stack in stacks) {
            out.put(stack.name.lowercase(), try {
                one(http, stack, uri, headers, seconds, seeks)
            } catch (e: Exception) {
                JSONObject().put("error", Engine.scrub(e.javaClass.simpleName + ": " + (e.message ?: "")))
            })
        }
        return out
    }

    private fun one(http: Http, stack: Http.Stack, uri: Uri, headers: Map<String, String>, seconds: Int, seeks: Int): JSONObject {
        val factory = http.factory(stack)
        val buf = ByteArray(256 shl 10)
        // One long read from the start: the first byte, then the throughput.
        var source = factory.createDataSource()
        var t0 = SystemClock.elapsedRealtimeNanos()
        val length = source.open(DataSpec.Builder().setUri(uri).setHttpRequestHeaders(headers).build())
        val firstByteMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        t0 = SystemClock.elapsedRealtimeNanos()
        var bytes = 0L
        val until = t0 + seconds * 1_000_000_000L
        while (SystemClock.elapsedRealtimeNanos() < until) {
            val n = source.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            bytes += n
        }
        val readS = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9
        source.close()
        // Range requests at random offsets, each closed after ~1 MB as a seek's would be.
        val times = JSONArray()
        val rng = Random(42)
        val size = if (length > 0) length else 1L shl 30
        repeat(seeks) {
            val at = (rng.nextDouble() * (size - SEEK_READ * 2)).toLong().coerceAtLeast(0)
            source = factory.createDataSource()
            val s0 = SystemClock.elapsedRealtimeNanos()
            source.open(DataSpec.Builder().setUri(uri).setPosition(at).setHttpRequestHeaders(headers).build())
            val opened = (SystemClock.elapsedRealtimeNanos() - s0) / 1e6
            var got = 0
            while (got < SEEK_READ) {
                val n = source.read(buf, 0, minOf(buf.size, SEEK_READ - got))
                if (n == C.RESULT_END_OF_INPUT) break
                got += n
            }
            val total = (SystemClock.elapsedRealtimeNanos() - s0) / 1e6
            source.close()
            times.put(JSONObject().put("open_ms", opened.toInt()).put("mb_ms", total.toInt()))
        }
        return JSONObject().put("first_byte_ms", firstByteMs.toInt()).put("mbit_s", (bytes * 8 / readS / 1e6).toInt())
            .put("read_mb", bytes / 1_000_000).put("seeks", times)
    }
}
