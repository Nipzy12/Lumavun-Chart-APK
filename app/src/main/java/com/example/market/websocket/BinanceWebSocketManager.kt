package com.example.market.websocket

import com.example.market.candle.Candle
import com.example.market.candle.Ticker24h
import com.example.market.candle.Timeframe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

enum class WsConnectionStatus(val label: String, val badgeText: String) {
    LIVE("LIVE", "🟢 LIVE"),
    RECONNECTING("RECONNECTING", "🟡 RECONNECTING"),
    DISCONNECTED("DISCONNECTED", "🔴 DISCONNECTED")
}

data class KlineStreamEvent(
    val symbol: String,
    val timeframe: Timeframe,
    val candle: Candle,
    val eventTime: Long
)

class BinanceWebSocketManager(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val UI_THROTTLE_MS = 160L

        // Official Binance Public WebSocket endpoints (no API key required)
        private val WS_ENDPOINTS = listOf(
            "wss://data-stream.binance.vision/stream?streams=",
            "wss://stream.binance.com:9443/stream?streams=",
            "wss://stream.binance.com:443/stream?streams="
        )
    }

    private val wsHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep-alive stream
        .pingInterval(20, TimeUnit.SECONDS)    // Built-in WebSocket heartbeat ping/pong
        .retryOnConnectionFailure(true)
        .build()

    private val _connectionStatus = MutableStateFlow(WsConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<WsConnectionStatus> = _connectionStatus.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    private val _klineEvents = MutableSharedFlow<KlineStreamEvent>(extraBufferCapacity = 64)
    val klineEvents: SharedFlow<KlineStreamEvent> = _klineEvents.asSharedFlow()

    private val _tickerEvents = MutableSharedFlow<Ticker24h>(extraBufferCapacity = 64)
    val tickerEvents: SharedFlow<Ticker24h> = _tickerEvents.asSharedFlow()

    // Duplicate & out-of-order event protection per stream key
    private val lastEventTimestampMap = ConcurrentHashMap<String, Long>()
    // UI throttle map per stream key for non-closed high-frequency ticks
    private val lastEmitTimestampMap = ConcurrentHashMap<String, Long>()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private val reconnectAttempts = AtomicInteger(0)
    private val isForeground = AtomicBoolean(true)
    private val isIntentionalDisconnect = AtomicBoolean(false)

    @Volatile
    private var endpointIndex = 0

    @Volatile
    private var activeSymbol: String = "BTCUSDT"

    @Volatile
    private var activeTimeframe: Timeframe = Timeframe.M15

    @Volatile
    private var watchlistSymbols: Set<String> = setOf(
        "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT", "DOGEUSDT"
    )

    @Volatile
    private var currentStreamQuery: String = ""

    /**
     * Subscribes to the active symbol's kline & 24hr ticker stream, plus miniTicker streams for watchlist pairs.
     */
    @Synchronized
    fun subscribe(
        symbol: String,
        timeframe: Timeframe,
        watchlist: Collection<String> = watchlistSymbols
    ) {
        val cleanSymbol = symbol.trim().uppercase(Locale.US)
        val cleanWatchlist = watchlist.map { it.trim().uppercase(Locale.US) }.toSet()

        val newQuery = buildStreamQuery(cleanSymbol, timeframe, cleanWatchlist)
        val hasChanged = newQuery != currentStreamQuery

        activeSymbol = cleanSymbol
        activeTimeframe = timeframe
        watchlistSymbols = cleanWatchlist

        if (!isForeground.get()) return

        if (hasChanged || webSocket == null || _connectionStatus.value == WsConnectionStatus.DISCONNECTED) {
            currentStreamQuery = newQuery
            reconnectAttempts.set(0)
            connectInternal(newQuery)
        }
    }

    /**
     * Updates watchlist streams without losing active chart subscription.
     */
    fun updateWatchlistStreams(watchlist: Collection<String>) {
        subscribe(activeSymbol, activeTimeframe, watchlist)
    }

    /**
     * Gracefully disconnects WebSocket when application enters background to save battery & data.
     */
    @Synchronized
    fun onAppBackground() {
        isForeground.set(false)
        isIntentionalDisconnect.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
        try {
            webSocket?.close(1000, "App entered background")
        } catch (_: Exception) {
        }
        webSocket = null
        _connectionStatus.value = WsConnectionStatus.DISCONNECTED
        _statusBannerMessage.value = null
    }

    /**
     * Automatically reconnects WebSocket when application returns to foreground.
     */
    @Synchronized
    fun onAppForeground() {
        val wasBackground = !isForeground.getAndSet(true)
        isIntentionalDisconnect.set(false)
        if (wasBackground || webSocket == null || _connectionStatus.value == WsConnectionStatus.DISCONNECTED) {
            reconnectAttempts.set(0)
            val query = buildStreamQuery(activeSymbol, activeTimeframe, watchlistSymbols)
            currentStreamQuery = query
            connectInternal(query)
        }
    }

    /**
     * Manual reconnect trigger (e.g., from Settings or status badge tap).
     */
    @Synchronized
    fun forceReconnect() {
        isIntentionalDisconnect.set(false)
        reconnectAttempts.set(0)
        val query = buildStreamQuery(activeSymbol, activeTimeframe, watchlistSymbols)
        currentStreamQuery = query
        connectInternal(query)
    }

    @Synchronized
    fun shutdown() {
        isIntentionalDisconnect.set(true)
        reconnectJob?.cancel()
        try {
            webSocket?.close(1000, "Shutdown")
        } catch (_: Exception) {
        }
        webSocket = null
        _connectionStatus.value = WsConnectionStatus.DISCONNECTED
    }

    private fun buildStreamQuery(
        primarySymbol: String,
        timeframe: Timeframe,
        watchlist: Set<String>
    ): String {
        val streams = LinkedHashSet<String>()
        val lowerPrimary = primarySymbol.lowercase(Locale.US)
        // Priority 1: Kline/candlestick stream for active symbol + timeframe
        streams.add("${lowerPrimary}@kline_${timeframe.binanceInterval}")
        // Priority 2: Full 24h ticker for active symbol
        streams.add("${lowerPrimary}@ticker")
        // Priority 3: Lightweight ticker streams for all watchlist pairs
        for (sym in watchlist) {
            val lower = sym.lowercase(Locale.US)
            if (lower.isNotEmpty()) {
                streams.add("${lower}@ticker")
            }
        }
        return streams.joinToString("/")
    }

    @Synchronized
    private fun connectInternal(streamQuery: String) {
        reconnectJob?.cancel()
        reconnectJob = null

        // Close any previous socket cleanly
        val oldSocket = webSocket
        webSocket = null
        try {
            oldSocket?.cancel()
        } catch (_: Exception) {
        }

        if (streamQuery.isEmpty() || !isForeground.get()) return

        isIntentionalDisconnect.set(false)
        if (reconnectAttempts.get() > 0) {
            _connectionStatus.value = WsConnectionStatus.RECONNECTING
            _statusBannerMessage.value = "Connection lost. Reconnecting..."
        } else {
            _connectionStatus.value = WsConnectionStatus.RECONNECTING
        }

        val baseEndpoint = WS_ENDPOINTS[endpointIndex % WS_ENDPOINTS.size]
        val fullUrl = "$baseEndpoint$streamQuery"

        val request = Request.Builder()
            .url(fullUrl)
            .build()

        try {
            webSocket = wsHttpClient.newWebSocket(request, createSocketListener(streamQuery))
        } catch (e: Exception) {
            scheduleReconnect(streamQuery)
        }
    }

    private fun createSocketListener(boundQuery: String): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (boundQuery != currentStreamQuery) return
                reconnectAttempts.set(0)
                _connectionStatus.value = WsConnectionStatus.LIVE
                _statusBannerMessage.value = null
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (boundQuery != currentStreamQuery) return
                if (_connectionStatus.value != WsConnectionStatus.LIVE) {
                    reconnectAttempts.set(0)
                    _connectionStatus.value = WsConnectionStatus.LIVE
                    _statusBannerMessage.value = null
                }
                handleIncomingJson(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                try {
                    webSocket.close(code, reason)
                } catch (_: Exception) {
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (boundQuery != currentStreamQuery || isIntentionalDisconnect.get() || !isForeground.get()) {
                    return
                }
                scheduleReconnect(boundQuery)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (boundQuery != currentStreamQuery || isIntentionalDisconnect.get() || !isForeground.get()) {
                    return
                }
                // Rotate endpoint on failure (e.g., if regional firewall blocks one host)
                endpointIndex = (endpointIndex + 1) % WS_ENDPOINTS.size
                scheduleReconnect(boundQuery)
            }
        }
    }

    private fun scheduleReconnect(streamQuery: String) {
        if (!isForeground.get() || isIntentionalDisconnect.get()) {
            _connectionStatus.value = WsConnectionStatus.DISCONNECTED
            return
        }

        val attempt = reconnectAttempts.incrementAndGet()
        _connectionStatus.value = WsConnectionStatus.RECONNECTING
        _statusBannerMessage.value = "Connection lost. Reconnecting..."

        // Exponential backoff: 1s, 2s, 4s, 8s, 16s, max 30s
        val shift = (attempt - 1).coerceIn(0, 5)
        val delayMs = min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS * (1L shl shift))

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (isForeground.get() && !isIntentionalDisconnect.get() && streamQuery == currentStreamQuery) {
                connectInternal(streamQuery)
            }
        }
    }

    /**
     * Parses combined stream payload `{"stream":"...","data":{...}}` or direct stream payload.
     * Protects against malformed JSON, duplicate timestamps, and throttles excessive UI updates.
     */
    internal fun handleIncomingJson(rawText: String) {
        try {
            val root = JSONObject(rawText)
            val dataObj = root.optJSONObject("data") ?: root
            val eventType = dataObj.optString("e")

            when (eventType) {
                "kline" -> parseAndEmitKline(dataObj)
                "24hrTicker" -> parseAndEmitTicker(dataObj)
                "24hrMiniTicker" -> parseAndEmitMiniTicker(dataObj)
            }
        } catch (_: Exception) {
            // Never crash on malformed WebSocket frame
        }
    }

    private fun parseAndEmitKline(data: JSONObject) {
        val eventTime = data.optLong("E", 0L)
        val symbol = data.optString("s").uppercase(Locale.US)
        val kObj = data.optJSONObject("k") ?: return

        val intervalStr = kObj.optString("i")
        val timeframe = Timeframe.fromInterval(intervalStr)
        val openTime = kObj.optLong("t", -1L)
        val closeTime = kObj.optLong("T", -1L)
        val open = kObj.optString("o").toDoubleOrNull() ?: return
        val high = kObj.optString("h").toDoubleOrNull() ?: return
        val low = kObj.optString("l").toDoubleOrNull() ?: return
        val close = kObj.optString("c").toDoubleOrNull() ?: return
        val volume = kObj.optString("v").toDoubleOrNull() ?: 0.0
        val isClosed = kObj.optBoolean("x", false)

        val streamKey = "kline_${symbol}_${timeframe.binanceInterval}"
        val lastEventTime = lastEventTimestampMap[streamKey] ?: 0L
        if (eventTime > 0L && eventTime < lastEventTime) {
            // Duplicate or out-of-order event protection
            return
        }
        lastEventTimestampMap[streamKey] = eventTime

        // Always emit closed candles immediately; throttle intra-candle ticks for low-CPU smoothness
        val now = System.currentTimeMillis()
        if (!isClosed) {
            val lastEmit = lastEmitTimestampMap[streamKey] ?: 0L
            if (now - lastEmit < UI_THROTTLE_MS) return
        }
        lastEmitTimestampMap[streamKey] = now

        val candle = Candle(
            openTime = openTime,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            closeTime = closeTime,
            isClosed = isClosed
        )
        if (!candle.isValid()) return

        _klineEvents.tryEmit(
            KlineStreamEvent(
                symbol = symbol,
                timeframe = timeframe,
                candle = candle,
                eventTime = eventTime
            )
        )
    }

    private fun parseAndEmitTicker(data: JSONObject) {
        val eventTime = data.optLong("E", 0L)
        val symbol = data.optString("s").uppercase(Locale.US)
        if (symbol.isEmpty()) return

        val streamKey = "ticker_$symbol"
        val lastEventTime = lastEventTimestampMap[streamKey] ?: 0L
        if (eventTime > 0L && eventTime <= lastEventTime) return
        lastEventTimestampMap[streamKey] = eventTime

        val now = System.currentTimeMillis()
        val lastEmit = lastEmitTimestampMap[streamKey] ?: 0L
        if (now - lastEmit < UI_THROTTLE_MS) return
        lastEmitTimestampMap[streamKey] = now

        val lastPrice = data.optString("c").toDoubleOrNull() ?: return
        if (lastPrice <= 0.0) return
        val priceChangePercent = data.optString("P").toDoubleOrNull() ?: 0.0
        val highPrice = data.optString("h").toDoubleOrNull() ?: lastPrice
        val lowPrice = data.optString("l").toDoubleOrNull() ?: lastPrice
        val volume = data.optString("v").toDoubleOrNull() ?: 0.0
        val quoteVolume = data.optString("q").toDoubleOrNull() ?: 0.0

        _tickerEvents.tryEmit(
            Ticker24h(
                symbol = symbol,
                lastPrice = lastPrice,
                priceChangePercent = priceChangePercent,
                highPrice = highPrice,
                lowPrice = lowPrice,
                volume = volume,
                quoteVolume = quoteVolume,
                updatedAt = now
            )
        )
    }

    private fun parseAndEmitMiniTicker(data: JSONObject) {
        val eventTime = data.optLong("E", 0L)
        val symbol = data.optString("s").uppercase(Locale.US)
        if (symbol.isEmpty()) return

        val streamKey = "miniticker_$symbol"
        val lastEventTime = lastEventTimestampMap[streamKey] ?: 0L
        if (eventTime > 0L && eventTime <= lastEventTime) return
        lastEventTimestampMap[streamKey] = eventTime

        val lastPrice = data.optString("c").toDoubleOrNull() ?: return
        val openPrice = data.optString("o").toDoubleOrNull() ?: lastPrice
        val highPrice = data.optString("h").toDoubleOrNull() ?: lastPrice
        val lowPrice = data.optString("l").toDoubleOrNull() ?: lastPrice
        val volume = data.optString("v").toDoubleOrNull() ?: 0.0
        val quoteVolume = data.optString("q").toDoubleOrNull() ?: 0.0
        val changePct = if (openPrice > 0.0) ((lastPrice - openPrice) / openPrice) * 100.0 else 0.0

        _tickerEvents.tryEmit(
            Ticker24h(
                symbol = symbol,
                lastPrice = lastPrice,
                priceChangePercent = changePct,
                highPrice = highPrice,
                lowPrice = lowPrice,
                volume = volume,
                quoteVolume = quoteVolume
            )
        )
    }
}
