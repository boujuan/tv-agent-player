package com.boujuan.tvagent.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.net.http.HttpEngine
import android.os.Build
import android.os.ext.SdkExtensions
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpEngineDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The HTTP stacks a link can be read with, and each link's headers. The default was chosen by measuring on the TV
 * against debrid links (`bench`, DESIGN.md): Android 14's built-in Cronet (HttpEngine) keeps one connection for the
 * header, the seek index and every seek, so a start is 0.3-0.7 s faster and a seek's request opens in ~230 ms, where
 * HttpURLConnection opens a new connection each time (~280 ms, up to 1.5 s). It adds nothing to the APK. OkHttp
 * measured between the two and was dropped.
 */
class Http(private val context: Context) {
    enum class Stack { PLATFORM, HTTPENGINE }

    private class Route(val headers: Map<String, String>, val stack: Stack)

    private val routes = ConcurrentHashMap<String, Route>()
    /** Each link's whole size, from a read from its start (a preload's too), for its bitrate. */
    val lengths = ConcurrentHashMap<String, Long>()
    @Volatile var stack = Stack.HTTPENGINE
    val userAgent: String = Util.getUserAgent(context, "TVAgentPlayer")

    /** What the next reads of `url` send, and with which stack (null: the default). */
    fun route(url: String, headers: Map<String, String>, stack: Stack? = null) {
        routes[url] = Route(headers, stack ?: this.stack)
    }

    fun forget(url: String) {
        routes.remove(url)
        lengths.remove(url)
        ranges.remove(url)
    }

    /**
     * Parts of a file read at once when it's loaded (the agent's `prefetch`: its seek index at the end, the cluster it
     * starts from), alongside Media3's own first read of the header, where Media3 asks for them one after another: the
     * index alone took ~0.7 s of every start (DESIGN.md, Starts). A read that falls inside one is answered from memory
     * as it arrives, and goes on from the network past its end; a range that fails only costs the bandwidth.
     */
    private class Range(val url: String, val at: Long, val bytes: Int) {
        val data = ByteArray(bytes)
        private val lock = Object()
        @Volatile var filled = 0
            private set
        @Volatile var over = false                  // read to its end, or stopped by an error
            private set

        fun add(n: Int) = synchronized(lock) {
            filled += n
            lock.notifyAll()
        }

        fun end() = synchronized(lock) {
            over = true
            lock.notifyAll()
        }

        /** Blocks until byte `i` of the range has come, or it's over: whether it has. */
        fun await(i: Int): Boolean = synchronized(lock) {
            while (filled <= i && !over) lock.wait(RANGE_WAIT_MS)
            filled > i
        }
    }

    private val ranges = ConcurrentHashMap<String, List<Range>>()
    private val fetcher = Executors.newFixedThreadPool(MAX_RANGES) { Thread(it, "prefetch") }

    /** The parts of `url` to read at once: (byte offset, length) pairs, at most MAX_RANGES and MAX_PREFETCH in all. */
    fun prefetch(url: String, wanted: List<Pair<Long, Int>>) {
        var total = 0L
        val list = wanted.filter { (at, bytes) -> at >= 0 && bytes > 0 }.take(MAX_RANGES)
            .takeWhile { (_, bytes) -> (total + bytes <= MAX_PREFETCH).also { if (it) total += bytes } }
            .map { (at, bytes) -> Range(url, at, bytes) }
        if (list.isEmpty()) return
        ranges[url] = list
        for (r in list) fetcher.execute { fill(r) }
    }

    private fun fill(r: Range) {
        val route = routes[r.url]
        val source = factory(route?.stack ?: stack).createDataSource()
        stats?.let(source::addTransferListener)
        try {
            source.open(DataSpec.Builder().setUri(r.url).setPosition(r.at).setLength(r.bytes.toLong())
                .setHttpRequestHeaders(route?.headers.orEmpty()).build())
            while (r.filled < r.bytes) {
                val n = source.read(r.data, r.filled, r.bytes - r.filled)
                if (n == C.RESULT_END_OF_INPUT) break
                r.add(n)
            }
        } catch (_: IOException) {
        } finally {
            try {
                source.close()
            } catch (_: IOException) {
            }
            r.end()
        }
    }

    private val platform by lazy {
        DefaultHttpDataSource.Factory().setUserAgent(userAgent).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(TIMEOUT_MS).setReadTimeoutMs(TIMEOUT_MS)
    }
    /** Android's HttpEngine comes with the Connectivity module (S extension 7, Android 14's first): else none. */
    val hasEngine = SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7

    private val engine by lazy { engineFactory() }

    @SuppressLint("NewApi")                         // only called when hasEngine
    private fun engineFactory(): DataSource.Factory {
        val engine = HttpEngine.Builder(context).setEnableHttp2(true).setEnableQuic(true).setEnableBrotli(true).build()
        return HttpEngineDataSource.Factory(engine, Executors.newFixedThreadPool(2)).setUserAgent(userAgent)
            .setConnectionTimeoutMs(TIMEOUT_MS).setReadTimeoutMs(TIMEOUT_MS)
    }
    fun factory(stack: Stack): DataSource.Factory = when (stack) {
        Stack.HTTPENGINE -> if (hasEngine) engine else platform
        Stack.PLATFORM -> platform
    }

    /** Cronet's native library loaded and its engine up before the first link (off the main thread). */
    fun warmUp() {
        if (hasEngine && stack == Stack.HTTPENGINE) Thread({ engine }, "http-warmup").start()
    }

    /**
     * What a load read from the network, for its `timing` event: requests, bytes, the first byte, and each of its
     * first reads (where in the file, when asked for, when the server answered, how much, when it stopped), to tell
     * which of a start's reads (the header, the index, the place to start from) its time goes to.
     */
    class Stats(val url: String, private val since: Long) : TransferListener {
        @Volatile var requests = 0
        @Volatile var bytes = 0L
        @Volatile var firstByteMs = -1L

        /** One read, in ms from the load; -1: not yet. Written by its loader's thread only. */
        class Read(val at: Long, val askedMs: Long) {
            @Volatile var answeredMs = -1L
            @Volatile var bytes = 0L
            @Volatile var doneMs = -1L
        }

        private val reading = IdentityHashMap<DataSpec, Read>()
        private val all = ArrayList<Read>()
        val reads: List<Read> get() = synchronized(this) { all.toList() }

        private fun now() = SystemClock.elapsedRealtime() - since

        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            if (!isNetwork || dataSpec.uri.toString() != url) return
            synchronized(this) {
                if (all.size < MAX_READS) Read(dataSpec.position, now()).also { all += it; reading[dataSpec] = it }
            }
        }

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            if (!isNetwork || dataSpec.uri.toString() != url) return
            requests++
            if (firstByteMs < 0) firstByteMs = now()
            synchronized(this) { reading[dataSpec] }?.answeredMs = now()
        }

        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (!isNetwork || dataSpec.uri.toString() != url) return
            bytes += bytesTransferred
            synchronized(this) { reading[dataSpec] }?.let { it.bytes += bytesTransferred }
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            synchronized(this) { reading.remove(dataSpec) }?.doneMs = now()
        }

        companion object {
            const val MAX_READS = 12
        }
    }

    @Volatile var stats: Stats? = null

    /** What the player and the preload read through: each open goes to its link's stack, with its headers. */
    val dataSourceFactory = DataSource.Factory { Routed() }

    private inner class Routed : DataSource {
        private val listeners = ArrayList<TransferListener>()
        private var current: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            val url = dataSpec.uri.toString()
            ranges[url]?.firstOrNull { dataSpec.position >= it.at && dataSpec.position < it.at + it.bytes }?.let { r ->
                current = FromRange(r, dataSpec)
                return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
                else lengths[url]?.let { it - dataSpec.position } ?: C.LENGTH_UNSET.toLong()
            }
            return openNetwork(dataSpec)
        }

        private fun openNetwork(dataSpec: DataSpec): Long {
            val route = routes[dataSpec.uri.toString()]
            val source = factory(route?.stack ?: stack).createDataSource()
            listeners.forEach(source::addTransferListener)
            stats?.let(source::addTransferListener)
            current = source
            val headers = route?.headers.orEmpty()
            val length = source.open(if (headers.isEmpty()) dataSpec else dataSpec.withAdditionalHeaders(headers))
            if (dataSpec.position == 0L && length > 0) lengths[dataSpec.uri.toString()] = length
            return length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)

        /**
         * A read inside a prefetched range: from memory as it arrives, then from the network at the first byte it
         * doesn't have (its end, or where it stopped).
         */
        private inner class FromRange(private val r: Range, private val spec: DataSpec) : DataSource {
            private var position = spec.position
            private var left = spec.length                  // C.LENGTH_UNSET: to the end of the file
            private var network: DataSource? = null

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (left == 0L) return C.RESULT_END_OF_INPUT
                network?.let { return count(it.read(buffer, offset, length)) }
                val i = (position - r.at).toInt()
                if (i >= r.bytes || !r.await(i)) {
                    val rest = spec.buildUpon().setPosition(position).setLength(left).build()
                    val inner = Routed().also { routed -> listeners.forEach(routed::addTransferListener) }
                    inner.openNetwork(rest)
                    network = inner
                    if (i >= r.bytes) ranges.computeIfPresent(r.url) { _, list -> (list - r).ifEmpty { null } }
                    return count(inner.read(buffer, offset, length))
                }
                var n = minOf(length, r.filled - i)
                if (left != C.LENGTH_UNSET.toLong()) n = minOf(n.toLong(), left).toInt()
                System.arraycopy(r.data, i, buffer, offset, n)
                return count(n)
            }

            private fun count(n: Int): Int {
                if (n > 0) {
                    position += n
                    if (left != C.LENGTH_UNSET.toLong()) left -= n
                }
                return n
            }

            override fun open(dataSpec: DataSpec): Long = throw UnsupportedOperationException()
            override fun addTransferListener(transferListener: TransferListener) {}
            override fun getUri(): Uri = spec.uri
            override fun getResponseHeaders(): Map<String, List<String>> = network?.responseHeaders ?: emptyMap()
            override fun close() {
                network?.close()
                network = null
            }
        }
        override fun getUri(): Uri? = current?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()
        override fun close() {
            try {
                current?.close()
            } finally {
                current = null
            }
        }
    }

    companion object {
        const val TIMEOUT_MS = 8_000
        const val MAX_RANGES = 4
        const val MAX_PREFETCH = 16 shl 20              // bytes, all ranges together
        private const val RANGE_WAIT_MS = 1_000L        // a wait's slice: a stopped range shows within a second
    }
}
