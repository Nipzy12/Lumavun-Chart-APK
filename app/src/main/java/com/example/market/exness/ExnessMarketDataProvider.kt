package com.example.market.exness

import com.example.market.candle.Candle
import com.example.market.candle.CandleMerger
import com.example.market.candle.MarketProvider
import com.example.market.candle.Ticker24h
import com.example.market.candle.Timeframe
import com.example.market.provider.MarketDataProvider
import com.example.market.provider.ProviderConnectionStatus
import com.example.market.rest.BinanceRestClient
import com.example.market.rest.ExchangeSymbolInfo
import com.example.market.rest.MarketApiResult
import com.example.market.websocket.KlineStreamEvent
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Exness Market Data Provider.
 * Strictly market-data and simulation only.
 * Interacts with backend institutional/trading gateway proxy for live prices, Bid/Ask, and OHLCV history.
 *
 * Implements:
 * - Deterministic ProviderConnectionStatus (Connected, Stale >15s, Disconnected, Unconfigured, Error)
 * - Safe lifecycle with exponential backoff and timeout
 * - Stale quote heartbeat detection (15s threshold)
 * - Normalized Ticker24h with explicit Bid/Ask spreads and Exness price digits
 * - Unconfigured guard: Never silently substitutes Binance data as Exness
 */
class ExnessMarketDataProvider(
    private var config: ExnessConfig = ExnessConfig(),
    private val httpClient: OkHttpClient = defaultHttpClient()
) : MarketDataProvider {

    companion object {
        const val STALE_THRESHOLD_MS = 15_000L

        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(12, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    override val provider: MarketProvider = MarketProvider.EXNESS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionStatus = MutableStateFlow<ProviderConnectionStatus>(
        if (config.isConfigured) ProviderConnectionStatus.Connecting
        else ProviderConnectionStatus.Unconfigured("Gateway proxy Exness belum dikonfigurasi. Atur URL proxy di Pengaturan > Data Pasar.")
    )
    override val connectionStatus: StateFlow<ProviderConnectionStatus> = _connectionStatus.asStateFlow()

    private val _klineEvents = MutableSharedFlow<KlineStreamEvent>(extraBufferCapacity = 64)
    override val klineEvents: SharedFlow<KlineStreamEvent> = _klineEvents.asSharedFlow()

    private val _tickerEvents = MutableSharedFlow<Ticker24h>(extraBufferCapacity = 128)
    override val tickerEvents: SharedFlow<Ticker24h> = _tickerEvents.asSharedFlow()

    private val lastQuoteTimestamp = AtomicLong(0L)
    private var heartbeatJob: Job? = null
    private var pollingJob: Job? = null
    private var activeWebSocket: WebSocket? = null

    @Volatile
    private var currentSubscribedSymbol: String = "EURUSD"
    @Volatile
    private var currentSubscribedTf: Timeframe = Timeframe.M15
    @Volatile
    private var currentWatchlist: List<String> = emptyList()

    init {
        startHeartbeatMonitor()
    }

    fun updateConfig(newConfig: ExnessConfig) {
        config = newConfig
        if (!config.isConfigured) {
            disconnectInternal("Gateway URL kosong")
            _connectionStatus.value = ProviderConnectionStatus.Unconfigured(
                "Gateway proxy Exness belum dikonfigurasi. Atur URL proxy di Pengaturan > Data Pasar."
            )
        } else {
            // Re-connect with new configuration
            if (currentSubscribedSymbol.isNotBlank()) {
                subscribe(currentSubscribedSymbol, currentSubscribedTf, currentWatchlist)
            }
        }
    }

    override suspend fun getSymbols(): MarketApiResult<List<ExchangeSymbolInfo>> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            // Provide standard known Exness instruments catalogue
            return@withContext MarketApiResult.Success(ExnessCatalog.getAllSymbolInfos())
        }
        val cleanUrl = config.gatewayUrl.trimEnd('/')
        val reqBuilder = Request.Builder()
            .url("$cleanUrl/api/symbols")
            .get()
        if (config.sessionToken.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${config.sessionToken.trim()}")
        }
        try {
            val response = httpClient.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) {
                // Fallback to static catalog if gateway symbols endpoint is unavailable
                return@withContext MarketApiResult.Success(ExnessCatalog.getAllSymbolInfos())
            }
            val body = response.body?.string() ?: ""
            val parsed = parseSymbolsJson(body)
            if (parsed.isNotEmpty()) {
                MarketApiResult.Success(parsed)
            } else {
                MarketApiResult.Success(ExnessCatalog.getAllSymbolInfos())
            }
        } catch (e: Exception) {
            // Graceful fallback to static catalog with error awareness
            MarketApiResult.Success(ExnessCatalog.getAllSymbolInfos())
        }
    }

    override suspend fun getHistoricalCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int
    ): MarketApiResult<List<Candle>> = withContext(Dispatchers.IO) {
        val cleanSymbol = symbol.trim().uppercase(Locale.US).removePrefix("EXNESS:")
        if (!config.isConfigured) {
            return@withContext MarketApiResult.Error(
                "Gateway proxy Exness belum dikonfigurasi. Hubungkan gateway di Pengaturan.",
                isOffline = true
            )
        }

        val cleanUrl = config.gatewayUrl.trimEnd('/')
        val intervalParam = timeframe.binanceInterval
        val reqBuilder = Request.Builder()
            .url("$cleanUrl/api/candles?symbol=$cleanSymbol&interval=$intervalParam&limit=$limit")
            .get()
        if (config.sessionToken.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${config.sessionToken.trim()}")
        }

        try {
            val response = httpClient.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) {
                return@withContext MarketApiResult.Error(
                    "Gateway Exness HTTP ${response.code}: ${response.message}"
                )
            }
            val body = response.body?.string() ?: ""
            val candles = parseCandlesJson(body)
            if (candles.isEmpty()) {
                MarketApiResult.Error("Tidak ada data candle dari gateway Exness untuk $cleanSymbol")
            } else {
                lastQuoteTimestamp.set(System.currentTimeMillis())
                _connectionStatus.value = ProviderConnectionStatus.Connected
                MarketApiResult.Success(candles)
            }
        } catch (e: UnknownHostException) {
            _connectionStatus.value = ProviderConnectionStatus.Disconnected("Host gateway tidak ditemukan")
            MarketApiResult.Error("Host gateway Exness tidak ditemukan (${e.message})", isOffline = true)
        } catch (e: SocketTimeoutException) {
            _connectionStatus.value = ProviderConnectionStatus.Disconnected("Koneksi gateway timeout")
            MarketApiResult.Error("Koneksi gateway Exness timeout", isOffline = true)
        } catch (e: Exception) {
            _connectionStatus.value = ProviderConnectionStatus.Error(e.message ?: "Network error")
            MarketApiResult.Error("Gagal mengambil data candle Exness: ${e.message}")
        }
    }

    override suspend fun getLatestQuote(symbol: String): MarketApiResult<Ticker24h> = withContext(Dispatchers.IO) {
        val cleanSymbol = symbol.trim().uppercase(Locale.US).removePrefix("EXNESS:")
        if (!config.isConfigured) {
            return@withContext MarketApiResult.Error(
                "Gateway proxy Exness belum dikonfigurasi.",
                isOffline = true
            )
        }

        val cleanUrl = config.gatewayUrl.trimEnd('/')
        val reqBuilder = Request.Builder()
            .url("$cleanUrl/api/quote?symbol=$cleanSymbol")
            .get()
        if (config.sessionToken.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${config.sessionToken.trim()}")
        }

        try {
            val response = httpClient.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) {
                return@withContext MarketApiResult.Error("Gateway quote HTTP ${response.code}")
            }
            val body = response.body?.string() ?: ""
            val ticker = parseQuoteJson(body, cleanSymbol)
            if (ticker != null) {
                lastQuoteTimestamp.set(System.currentTimeMillis())
                _connectionStatus.value = ProviderConnectionStatus.Connected
                _tickerEvents.tryEmit(ticker)
                MarketApiResult.Success(ticker)
            } else {
                MarketApiResult.Error("Format quote Exness tidak valid")
            }
        } catch (e: Exception) {
            MarketApiResult.Error("Gagal mengambil quote Exness: ${e.message}")
        }
    }

    override fun subscribe(
        symbol: String,
        timeframe: Timeframe,
        watchlistSymbols: List<String>
    ) {
        val cleanSymbol = symbol.trim().uppercase(Locale.US).removePrefix("EXNESS:")
        currentSubscribedSymbol = cleanSymbol
        currentSubscribedTf = timeframe
        currentWatchlist = watchlistSymbols.map { it.trim().uppercase(Locale.US).removePrefix("EXNESS:") }

        if (!config.isConfigured) {
            _connectionStatus.value = ProviderConnectionStatus.Unconfigured(
                "Gateway proxy Exness belum dikonfigurasi. Atur URL proxy di Pengaturan > Data Pasar."
            )
            return
        }

        disconnectInternal()
        _connectionStatus.value = ProviderConnectionStatus.Connecting

        startWebSocketStream(cleanSymbol, timeframe)
        startPollingFallback(cleanSymbol, timeframe)
    }

    override fun unsubscribe() {
        disconnectInternal("Unsubscribed")
        _connectionStatus.value = if (config.isConfigured) {
            ProviderConnectionStatus.Disconnected("Unsubscribed")
        } else {
            ProviderConnectionStatus.Unconfigured("Gateway proxy Exness belum dikonfigurasi.")
        }
    }

    override fun dispose() {
        unsubscribe()
        heartbeatJob?.cancel()
    }

    /**
     * Stale heartbeat monitor: Checks whether quotes have arrived within the last 15 seconds.
     */
    private fun startHeartbeatMonitor() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(2_000L)
                val current = _connectionStatus.value
                if (current is ProviderConnectionStatus.Connected) {
                    val last = lastQuoteTimestamp.get()
                    if (last > 0L && System.currentTimeMillis() - last > STALE_THRESHOLD_MS) {
                        _connectionStatus.value = ProviderConnectionStatus.Stale
                    }
                }
            }
        }
    }

    private fun startWebSocketStream(symbol: String, timeframe: Timeframe) {
        val cleanUrl = config.gatewayUrl.trimEnd('/')
        val wsUrl = if (cleanUrl.startsWith("https://", ignoreCase = true)) {
            cleanUrl.replaceFirst("https://", "wss://", ignoreCase = true) + "/ws"
        } else if (cleanUrl.startsWith("http://", ignoreCase = true)) {
            cleanUrl.replaceFirst("http://", "ws://", ignoreCase = true) + "/ws"
        } else {
            return
        }

        val reqBuilder = Request.Builder().url(wsUrl)
        if (config.sessionToken.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${config.sessionToken.trim()}")
        }

        activeWebSocket = httpClient.newWebSocket(reqBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastQuoteTimestamp.set(System.currentTimeMillis())
                _connectionStatus.value = ProviderConnectionStatus.Connected
                // Send subscription payload
                val subMsg = JSONObject().apply {
                    put("action", "subscribe")
                    put("symbol", symbol)
                    put("interval", timeframe.binanceInterval)
                    put("accountTier", config.accountTier)
                }.toString()
                webSocket.send(subMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWebSocketMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Connection failed - fallback polling handles continuity
                if (_connectionStatus.value is ProviderConnectionStatus.Connected) {
                    _connectionStatus.value = ProviderConnectionStatus.Disconnected(t.message ?: "WS Failure")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (_connectionStatus.value is ProviderConnectionStatus.Connected) {
                    _connectionStatus.value = ProviderConnectionStatus.Disconnected(reason)
                }
            }
        })
    }

    private fun handleWebSocketMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")
            when (type) {
                "quote", "tick" -> {
                    val symbol = json.optString("symbol", currentSubscribedSymbol)
                    val bid = json.optDouble("bid", 0.0)
                    val ask = json.optDouble("ask", 0.0)
                    val last = json.optDouble("last", (bid + ask) / 2.0)
                    val high = json.optDouble("high", last)
                    val low = json.optDouble("low", last)
                    val change = json.optDouble("priceChangePercent", 0.0)
                    val volume = json.optDouble("volume", 0.0)
                    val spread = max(0.0, ask - bid)
                    val instrument = ExnessCatalog.findInstrument(symbol)

                    val ticker = Ticker24h(
                        symbol = symbol,
                        lastPrice = last,
                        priceChangePercent = change,
                        highPrice = high,
                        lowPrice = low,
                        volume = volume,
                        quoteVolume = volume * last,
                        updatedAt = System.currentTimeMillis(),
                        bid = bid,
                        ask = ask,
                        spread = spread,
                        hasVerifiedBidAsk = (bid > 0.0 && ask >= bid),
                        provider = MarketProvider.EXNESS,
                        category = instrument?.category ?: com.example.market.candle.inferInstrumentCategory(symbol),
                        displayName = instrument?.displayName ?: symbol
                    )
                    lastQuoteTimestamp.set(System.currentTimeMillis())
                    _connectionStatus.value = ProviderConnectionStatus.Connected
                    _tickerEvents.tryEmit(ticker)
                }
                "candle", "kline" -> {
                    val klineJson = json.optJSONObject("kline") ?: json
                    val openTime = klineJson.optLong("openTime", 0L)
                    val open = klineJson.optDouble("open", 0.0)
                    val high = klineJson.optDouble("high", 0.0)
                    val low = klineJson.optDouble("low", 0.0)
                    val close = klineJson.optDouble("close", 0.0)
                    val volume = klineJson.optDouble("volume", 0.0)
                    val closeTime = klineJson.optLong("closeTime", openTime + currentSubscribedTf.durationMs)
                    val isClosed = klineJson.optBoolean("isClosed", false)

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
                    lastQuoteTimestamp.set(System.currentTimeMillis())
                    _connectionStatus.value = ProviderConnectionStatus.Connected
                    _klineEvents.tryEmit(
                        KlineStreamEvent(
                            symbol = json.optString("symbol", currentSubscribedSymbol),
                            timeframe = currentSubscribedTf,
                            candle = candle,
                            eventTime = System.currentTimeMillis()
                        )
                    )
                }
            }
        } catch (_: Exception) {}
    }

    private fun startPollingFallback(symbol: String, timeframe: Timeframe) {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            var backoffMs = 3_000L
            while (isActive) {
                delay(backoffMs)
                if (!config.isConfigured) break

                val quoteRes = getLatestQuote(symbol)
                if (quoteRes is MarketApiResult.Success) {
                    backoffMs = 3_000L // Reset backoff on success
                } else {
                    backoffMs = (backoffMs * 1.5).toLong().coerceAtMost(15_000L)
                }
            }
        }
    }

    private fun disconnectInternal(reason: String = "") {
        try {
            activeWebSocket?.close(1000, reason.ifBlank { "Normal closure" })
        } catch (_: Exception) {}
        activeWebSocket = null
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun parseCandlesJson(raw: String): List<Candle> {
        val list = mutableListOf<Candle>()
        try {
            val array = if (raw.trim().startsWith("[")) {
                JSONArray(raw)
            } else {
                JSONObject(raw).optJSONArray("candles") ?: JSONArray()
            }
            for (i in 0 until array.length()) {
                val item = array.opt(i)
                if (item is JSONObject) {
                    val candle = Candle(
                        openTime = item.optLong("openTime", item.optLong("time", 0L)),
                        open = item.optDouble("open", 0.0),
                        high = item.optDouble("high", 0.0),
                        low = item.optDouble("low", 0.0),
                        close = item.optDouble("close", 0.0),
                        volume = item.optDouble("volume", 0.0),
                        closeTime = item.optLong("closeTime", 0L),
                        isClosed = item.optBoolean("isClosed", true)
                    )
                    if (candle.isValid()) list.add(candle)
                } else if (item is JSONArray && item.length() >= 6) {
                    // Standard OHLCV array format [time, open, high, low, close, volume]
                    val candle = Candle(
                        openTime = item.optLong(0, 0L),
                        open = item.optDouble(1, 0.0),
                        high = item.optDouble(2, 0.0),
                        low = item.optDouble(3, 0.0),
                        close = item.optDouble(4, 0.0),
                        volume = item.optDouble(5, 0.0),
                        closeTime = item.optLong(6, item.optLong(0, 0L) + 60_000L),
                        isClosed = true
                    )
                    if (candle.isValid()) list.add(candle)
                }
            }
        } catch (_: Exception) {}
        return CandleMerger.sanitizeHistoricalCandles(list)
    }

    private fun parseQuoteJson(raw: String, symbol: String): Ticker24h? {
        try {
            val json = JSONObject(raw)
            val bid = json.optDouble("bid", 0.0)
            val ask = json.optDouble("ask", 0.0)
            val last = json.optDouble("last", (bid + ask) / 2.0)
            if (last <= 0.0 && bid <= 0.0) return null

            val high = json.optDouble("high", last)
            val low = json.optDouble("low", last)
            val change = json.optDouble("priceChangePercent", 0.0)
            val volume = json.optDouble("volume", 0.0)
            val spread = max(0.0, ask - bid)
            val instrument = ExnessCatalog.findInstrument(symbol)

            return Ticker24h(
                symbol = symbol,
                lastPrice = if (last > 0.0) last else (bid + ask) / 2.0,
                priceChangePercent = change,
                highPrice = high,
                lowPrice = low,
                volume = volume,
                quoteVolume = volume * last,
                updatedAt = System.currentTimeMillis(),
                bid = bid,
                ask = ask,
                spread = spread,
                hasVerifiedBidAsk = (bid > 0.0 && ask >= bid),
                provider = MarketProvider.EXNESS,
                category = instrument?.category ?: com.example.market.candle.inferInstrumentCategory(symbol),
                displayName = instrument?.displayName ?: symbol
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun parseSymbolsJson(raw: String): List<ExchangeSymbolInfo> {
        val list = mutableListOf<ExchangeSymbolInfo>()
        try {
            val array = if (raw.trim().startsWith("[")) {
                JSONArray(raw)
            } else {
                JSONObject(raw).optJSONArray("symbols") ?: JSONArray()
            }
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val sym = obj.optString("symbol", "")
                if (sym.isNotBlank()) {
                    val base = obj.optString("baseAsset", "")
                    val quote = obj.optString("quoteAsset", "")
                    val catStr = obj.optString("category", "")
                    val cat = com.example.market.candle.InstrumentCategory.fromCode(catStr)
                    val name = obj.optString("displayName", sym)
                    list.add(
                        ExchangeSymbolInfo(
                            symbol = sym,
                            baseAsset = base,
                            quoteAsset = quote,
                            status = "TRADING",
                            category = cat,
                            provider = MarketProvider.EXNESS,
                            displayName = name
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return list
    }
}
