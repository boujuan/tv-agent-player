package com.boujuan.tvagent.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.net.http.HttpEngine
import android.os.Build
import android.os.ext.SdkExtensions
import android.os.SystemClock
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpEngineDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The HTTP stacks a link can be read with, and each link's headers. Which stack is the default is decided by
 * measurement on the TV (`bench`, DESIGN.md), not assumed: the platform's HttpURLConnection, Android 14's built-in
 * Cronet (HttpEngine: HTTP/2 and QUIC, nothing added to the APK), or OkHttp (HTTP/2).
 */
class Http(private val context: Context) {
    enum class Stack { PLATFORM, HTTPENGINE, OKHTTP }

    private class Route(val headers: Map<String, String>, val stack: Stack)

    private val routes = ConcurrentHashMap<String, Route>()
    @Volatile var stack = Stack.PLATFORM
    val userAgent: String = Util.getUserAgent(context, "TVAgentPlayer")

    /** What the next reads of `url` send, and with which stack (null: the default). */
    fun route(url: String, headers: Map<String, String>, stack: Stack? = null) {
        routes[url] = Route(headers, stack ?: this.stack)
    }

    fun forget(url: String) {
        routes.remove(url)
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
    private val okhttp by lazy {
        val client = OkHttpClient.Builder().connectTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS).build()
        OkHttpDataSource.Factory(client).setUserAgent(userAgent)
    }

    fun factory(stack: Stack): DataSource.Factory = when (stack) {
        Stack.HTTPENGINE -> if (hasEngine) engine else platform
        Stack.OKHTTP -> okhttp
        Stack.PLATFORM -> platform
    }

    /** Counts for the playing file, for the `timing` event: requests, bytes, and when the first answer came. */
    class Stats(val url: String, private val since: Long) : TransferListener {
        @Volatile var requests = 0
        @Volatile var bytes = 0L
        @Volatile var firstByteMs = -1L

        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            if (!isNetwork || dataSpec.uri.toString() != url) return
            requests++
            if (firstByteMs < 0) firstByteMs = SystemClock.elapsedRealtime() - since
        }
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork && dataSpec.uri.toString() == url) bytes += bytesTransferred
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
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
            val route = routes[dataSpec.uri.toString()]
            val source = factory(route?.stack ?: stack).createDataSource()
            listeners.forEach(source::addTransferListener)
            stats?.let(source::addTransferListener)
            current = source
            val headers = route?.headers.orEmpty()
            return source.open(if (headers.isEmpty()) dataSpec else dataSpec.withAdditionalHeaders(headers))
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)
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
    }
}
