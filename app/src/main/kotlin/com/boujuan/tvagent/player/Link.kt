package com.boujuan.tvagent.player

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * The connection to tv-agent (PROTOCOL.md): TLS to the paired address, the agent's certificate pinned, newline
 * JSON both ways. Commands arrive on the main thread; send() queues lines in order on a writer thread. It reconnects
 * by itself while the screen is up.
 */
class Link(private val pairing: Pairing, private val version: String, private val build: Int,
           private val listener: Listener) {

    interface Listener {
        /** Each command, on the main thread. */
        fun onCommand(cmd: JSONObject)
        /** Welcomed by the agent (again, after a drop): send the current state. */
        fun onUp()
        fun onDown()
        /** The agent refused this player ("unauthorized", "version"): it won't be retried quickly. */
        fun onRefused(error: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "agent-link-out") }
    private val reader = Thread(::run, "agent-link")
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var closing = false
    @Volatile private var sentAt = 0L
    @Volatile var up = false
        private set

    fun start() = reader.start()

    /** Queues a line for the agent; dropped while the link is down (the state is sent again when it's back). */
    fun send(msg: JSONObject) {
        if (!up || closing) return
        val line = (msg.toString() + "\n").toByteArray()
        writer.execute {                        // closing is only set on the main thread, as send() is called
            val s = socket ?: return@execute
            try {
                s.outputStream.write(line)
                s.outputStream.flush()
                sentAt = SystemClock.elapsedRealtime()
            } catch (e: IOException) {
                Log.w(TAG, "write: ${e.javaClass.simpleName}")
                closeQuietly(s)                 // the reader sees it and reconnects
            }
        }
    }

    // Nothing sent for PING_MS (nothing loaded, so no positions): a ping, so the agent doesn't take the silence for
    // a dead connection.
    private val ping = object : Runnable {
        override fun run() {
            if (!up || closing) return
            if (SystemClock.elapsedRealtime() - sentAt >= PING_MS) send(JSONObject().put("t", "ping"))
            main.postDelayed(this, PING_MS / 2)
        }
    }

    /** After what's queued has been written: no more reconnecting. */
    fun close() {
        main.removeCallbacks(ping)
        closing = true
        writer.execute { socket?.let(::closeQuietly) }
        writer.shutdown()
        reader.interrupt()
    }

    private fun run() {
        var delay = FIRST_RETRY_MS
        while (!closing) {
            var s: SSLSocket? = null
            var wasUp = false
            try {
                s = connect()
                val input = s.inputStream.buffered()
                val hello = JSONObject().put("t", "hello").put("v", PROTOCOL).put("token", pairing.token)
                    .put("features", JSONArray(FEATURES))
                    .put("app", version).put("build", build).put("sdk", Build.VERSION.SDK_INT)
                s.outputStream.write((hello.toString() + "\n").toByteArray())
                s.outputStream.flush()
                val answer = JSONObject(readLine(input) ?: throw EOFException())
                if (answer.optString("t") != "welcome") {
                    val error = answer.optString("error", "refused")
                    main.post { listener.onRefused(error) }
                    delay = REFUSED_RETRY_MS
                    throw IOException("refused: $error")
                }
                socket = s
                up = true
                wasUp = true
                delay = FIRST_RETRY_MS
                sentAt = SystemClock.elapsedRealtime()
                main.post { listener.onUp() }
                main.post(ping)
                while (!closing) {
                    val line = readLine(input) ?: break
                    val cmd = try {
                        JSONObject(line)
                    } catch (e: JSONException) {
                        Log.w(TAG, "not JSON: ${line.length} bytes")
                        continue
                    }
                    if (cmd.optString("t") == "ping") continue
                    main.post { listener.onCommand(cmd) }
                }
            } catch (e: Exception) {
                if (!closing) Log.i(TAG, "link: ${e.javaClass.simpleName}")      // never the message: no secrets
            } finally {
                up = false
                socket = null
                s?.let(::closeQuietly)
                if (wasUp) main.post { listener.onDown() }
            }
            if (closing) break
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                break
            }
            delay = minOf(delay * 2, MAX_RETRY_MS).coerceAtLeast(delay)
        }
    }

    /**
     * The first of the agent's addresses that answers, starting with the one that did last time: the home network's
     * when the computer is home, else another (Tailscale) when it's away.
     */
    private fun connect(): SSLSocket {
        var failure: IOException? = null
        for (host in order(pairing.hosts, lastHost)) {
            try {
                return connect(host).also { lastHost = host }
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: IOException("no address")
    }

    private fun connect(host: String): SSLSocket {
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.keepAlive = true
        try {
            raw.connect(InetSocketAddress(host, pairing.port), CONNECT_TIMEOUT_MS)
        } catch (e: IOException) {
            closeQuietly(raw)
            throw e
        }
        raw.soTimeout = SILENCE_MS
        val tls = SSLContext.getInstance("TLS")
        tls.init(null, arrayOf(Pin(pairing.certSha256)), null)
        val s = tls.socketFactory.createSocket(raw, host, pairing.port, true) as SSLSocket
        s.enabledProtocols = s.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        s.startHandshake()
        return s
    }

    /** Only the paired agent's certificate, by its SHA-256 (it's self-signed: the pin is the whole check). */
    @Suppress("CustomX509TrustManager")
    private class Pin(private val sha256: String) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty() || sha256(chain[0].encoded) != sha256) throw CertificateException("not the paired agent")
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("no client certificates")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        const val PROTOCOL = 1
        /** What this player does beyond protocol 1, for the agent to use (PROTOCOL.md, hello). */
        val FEATURES = listOf("markers", "subs_unless", "original")
        /** The agent's address that answered last, for this process (it lives on between screens). */
        @Volatile private var lastHost: String? = null
        private const val TAG = "AgentLink"
        private const val CONNECT_TIMEOUT_MS = 2_000      // per address: one that isn't there costs this much
        private const val SILENCE_MS = 30_000          // nothing at all from the agent for this long: dead
        private const val PING_MS = 10_000L
        private const val FIRST_RETRY_MS = 500L
        private const val MAX_RETRY_MS = 10_000L
        private const val REFUSED_RETRY_MS = 30_000L
        const val MAX_LINE = 4 shl 20

        /** The addresses to try: the one that answered last first, then the rest in the pairing's order. */
        fun order(hosts: List<String>, last: String?): List<String> =
            if (last != null && last in hosts) listOf(last) + (hosts - last) else hosts

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** One line (without the newline), null at the end of the stream; longer than MAX_LINE is an error. */
        fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream(256)
            while (true) {
                val b = input.read()
                if (b < 0) {
                    if (buf.size() == 0) return null
                    throw EOFException("cut mid-line")
                }
                if (b == '\n'.code) return buf.toString(Charsets.UTF_8.name())
                if (buf.size() >= MAX_LINE) throw IOException("line too long")
                buf.write(b)
            }
        }

        private fun closeQuietly(s: Socket) {
            try {
                s.close()
            } catch (_: IOException) {
            }
        }
    }
}
