package com.example.market.biquote

import com.example.market.candle.Candle
import com.example.market.candle.CandleMerger
import com.example.market.candle.InstrumentCategory
import com.example.market.candle.MarketProvider
import com.example.market.candle.Ticker24h
import com.example.market.candle.Timeframe
import com.example.market.candle.inferInstrumentCategory
import com.example.market.rest.ExchangeSymbolInfo
import com.example.market.rest.MarketApiResult
import com.example.market.websocket.KlineStreamEvent
import com.example.market.websocket.WsConnectionStatus
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Public Market Data Client for BiQuote (https://biquote.io).
 * Supports multi-market instruments: Forex, Commodities/Metals, Stocks, Indices, and validated Crypto.
 *
 * Relevant Endpoints:
 * - GET /health
 * - GET /api/active
 * - GET /api/symbols?type=Forex | Commodity | Stock | Index
 * - GET /api/symbols/search?q={query}
 * - GET /api/{symbol}
 * - GET /api/latest?symbols=EURUSD&symbols=XAUUSD
 * - GET /api/{symbol}/ohlc?interval=1h&limit=200
 * - SignalR Hub: /hubs/tick (with automatic REST polling fallback)
 */
class BiQuoteClient(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    /**
     * Checks BiQuote service health via GET /health.
     */
    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        when (val res = executeGet("/health")) {
            is MarketApiResult.Success -> true
            is MarketApiResult.Error -> false
        }
    }

    /**
     * Fetches active market quotes/instruments from GET /api/active.
     */
    suspend fun fetchActiveQuotes(): MarketApiResult<List<Ticker24h>> = withContext<MarketApiResult<List<Ticker24h>>>(Dispatchers.IO) {
        when (val res = executeGet("/api/active")) {
            is MarketApiResult.Error -> res
            is MarketApiResult.Success -> {
                val parsed = parseTickersPayload(res.data)
                MarketApiResult.Success(parsed)
            }
        }
    }

    /**
     * Fetches symbol catalog for a specific category via GET /api/symbols?type={Forex|Commodity|Stock|Index}.
     */
    suspend fun fetchSymbolsByCategory(category: InstrumentCategory): MarketApiResult<List<ExchangeSymbolInfo>> = withContext<MarketApiResult<List<ExchangeSymbolInfo>>>(Dispatchers.IO) {
        val typeParam = category.biquoteType.ifBlank { "Forex" }
        val encoded = URLEncoder.encode(typeParam, "UTF-8")
        when (val res = executeGet("/api/symbols?type=$encoded")) {
            is MarketApiResult.Error -> res
            is MarketApiResult.Success -> {
                val list = parseSymbolsCatalogPayload(res.data, defaultCategory = category)
                MarketApiResult.Success(list)
            }
        }
    }

    /**
     * Searches symbols dynamically on BiQuote via GET /api/symbols/search?q={query}.
     */
    suspend fun searchSymbols(query: String): MarketApiResult<List<ExchangeSymbolInfo>> = withContext<MarketApiResult<List<ExchangeSymbolInfo>>>(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext MarketApiResult.Success(emptyList())
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        when (val res = executeGet("/api/symbols/search?q=$encoded")) {
            is MarketApiResult.Error -> res
            is MarketApiResult.Success -> {
                val list = parseSymbolsCatalogPayload(res.data, defaultCategory = null)
                MarketApiResult.Success(list)
            }
        }
    }

    /**
     * Fetches a single symbol's latest quote and metadata via GET /api/{symbol}.
     */
    suspend fun fetchSingleSymbolQuote(symbol: String): MarketApiResult<Ticker24h> = withContext<MarketApiResult<Ticker24h>>(Dispatchers.IO) {
        val clean = normalizeBiQuoteSymbol(symbol)
        if (clean.isEmpty()) {
            return@withContext MarketApiResult.Error("Invalid symbol: $symbol")
        }
        val encoded = URLEncoder.encode(clean, "UTF-8")
        when (val res = executeGet("/api/$encoded")) {
            is MarketApiResult.Error -> res
            is MarketApiResult.Success -> {
                val tickers = parseTickersPayload(res.data, fallbackSymbol = clean)
                val first = tickers.firstOrNull()
                if (first != null && first.lastPrice > 0.0) {
                    MarketApiResult.Success(first)
                } else {
                    MarketApiResult.Error("Quote data unavailable for $clean on BiQuote.")
                }
            }
        }
    }

    /**
     * Fetches batch latest quotes via GET /api/latest?symbols=EURUSD&symbols=XAUUSD.
     */
    suspend fun fetchLatestQuotes(symbols: List<String>): MarketApiResult<List<Ticker24h>> = withContext<MarketApiResult<List<Ticker24h>>>(Dispatchers.IO) {
        val cleanList = symbols.map { normalizeBiQuoteSymbol(it) }.filter { it.isNotEmpty() }.distinct()
        if (cleanList.isEmpty()) return@withContext MarketApiResult.Success(emptyList())

        val queryParams = cleanList.joinToString("&") { "symbols=${URLEncoder.encode(it, "UTF-8")}" }
        val path = "/api/latest?$queryParams"

        when (val res = executeGet(path)) {
            is MarketApiResult.Success -> {
                val parsed = parseTickersPayload(res.data)
                if (parsed.isNotEmpty()) {
                    return@withContext MarketApiResult.Success(parsed)
                }
            }
            is MarketApiResult.Error -> {
                // Fallback to comma-separated or individual /api/{symbol} if batch query fails
            }
        }

        // Fallback: try comma-separated or individual symbol fetches
        val commaQuery = cleanList.joinToString(",")
        when (val altRes = executeGet("/api/latest?symbols=${URLEncoder.encode(commaQuery, "UTF-8")}")) {
            is MarketApiResult.Success -> {
                val parsed = parseTickersPayload(altRes.data)
                if (parsed.isNotEmpty()) {
                    return@withContext MarketApiResult.Success(parsed)
                }
            }
            is MarketApiResult.Error -> {}
        }

        val collected = ArrayList<Ticker24h>()
        var lastError: MarketApiResult.Error? = null
        for (sym in cleanList.take(12)) {
            when (val single = fetchSingleSymbolQuote(sym)) {
                is MarketApiResult.Success -> collected.add(single.data)
                is MarketApiResult.Error -> lastError = single
            }
        }
        if (collected.isNotEmpty()) {
            MarketApiResult.Success(collected)
        } else {
            lastError ?: MarketApiResult.Error("Unable to fetch latest BiQuote quotes.")
        }
    }

    /**
     * Fetches historical OHLC candles from BiQuote via GET /api/{symbol}/ohlc?interval=1h&limit=200.
     */
    suspend fun fetchHistoricalCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int = 250
    ): MarketApiResult<List<Candle>> = withContext<MarketApiResult<List<Candle>>>(Dispatchers.IO) {
        val clean = normalizeBiQuoteSymbol(symbol)
        if (clean.isEmpty()) {
            return@withContext MarketApiResult.Error("Invalid symbol: $symbol")
        }
        val safeLimit = limit.coerceIn(50, 350)
        val primaryInterval = mapTimeframeToBiQuoteInterval(timeframe)
        val encodedSym = URLEncoder.encode(clean, "UTF-8")
        val path = "/api/$encodedSym/ohlc?interval=$primaryInterval&limit=$safeLimit"

        val response = when (val firstAttempt = executeGet(path)) {
            is MarketApiResult.Success -> firstAttempt
            is MarketApiResult.Error -> {
                // Try uppercase interval label (e.g. 1H, 4H, 1D) if lowercase returned 400/404
                val altInterval = timeframe.label
                if (!altInterval.equals(primaryInterval, ignoreCase = false)) {
                    executeGet("/api/$encodedSym/ohlc?interval=$altInterval&limit=$safeLimit")
                } else {
                    firstAttempt
                }
            }
        }

        when (response) {
            is MarketApiResult.Error -> response
            is MarketApiResult.Success -> {
                val candles = parseOhlcPayload(response.data, timeframe)
                val sanitized = CandleMerger.sanitizeHistoricalCandles(candles)
                if (sanitized.isEmpty()) {
                    MarketApiResult.Error("No OHLC candle data available for $clean (${timeframe.label}) from BiQuote.")
                } else {
                    MarketApiResult.Success(sanitized)
                }
            }
        }
    }

    /**
     * Validates a symbol on BiQuote by checking `/api/{symbol}` or `/api/symbols/search?q={symbol}`.
     */
    suspend fun validateSymbol(
        rawSymbol: String,
        preferredCategory: InstrumentCategory? = null
    ): MarketApiResult<ExchangeSymbolInfo> = withContext<MarketApiResult<ExchangeSymbolInfo>>(Dispatchers.IO) {
        val clean = normalizeBiQuoteSymbol(rawSymbol)
        if (clean.isEmpty()) {
            return@withContext MarketApiResult.Error("Invalid symbol format: $rawSymbol")
        }

        // 1. Direct check on /api/{symbol}
        when (val quoteRes = fetchSingleSymbolQuote(clean)) {
            is MarketApiResult.Success -> {
                val ticker = quoteRes.data
                val category = if (preferredCategory != null && preferredCategory != InstrumentCategory.ALL) {
                    preferredCategory
                } else {
                    ticker.category
                }
                val (base, quote) = splitBaseQuote(ticker.symbol, category)
                return@withContext MarketApiResult.Success(
                    ExchangeSymbolInfo(
                        symbol = ticker.symbol,
                        baseAsset = base,
                        quoteAsset = quote,
                        status = "TRADING",
                        category = category,
                        provider = MarketProvider.BIQUOTE,
                        displayName = ticker.displayName
                    )
                )
            }
            is MarketApiResult.Error -> {
                if (quoteRes.isOffline || quoteRes.isRateLimited) {
                    return@withContext quoteRes
                }
            }
        }

        // 2. Fallback search via /api/symbols/search?q={clean}
        when (val searchRes = searchSymbols(clean)) {
            is MarketApiResult.Success -> {
                val match = searchRes.data.firstOrNull {
                    it.symbol.equals(clean, ignoreCase = true) ||
                        it.baseAsset.equals(clean, ignoreCase = true)
                }
                if (match != null) {
                    return@withContext MarketApiResult.Success(match)
                }
                MarketApiResult.Error("Symbol $clean tidak ditemukan di katalog BiQuote.")
            }
            is MarketApiResult.Error -> searchRes
        }
    }

    internal fun executeGet(path: String): MarketApiResult<String> {
        val fullUrl = if (path.startsWith("http")) path else "$BASE_URL$path"
        val request = Request.Builder()
            .url(fullUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "NexSyncAI-Lite-Android/2.0")
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful && bodyStr.isNotEmpty()) {
                    MarketApiResult.Success(bodyStr)
                } else if (code == 429) {
                    MarketApiResult.Error(
                        message = "BiQuote API rate limit reached (HTTP 429). Please wait a moment.",
                        isRateLimited = true
                    )
                } else if (code == 404) {
                    MarketApiResult.Error("Symbol or endpoint not found on BiQuote (HTTP 404).")
                } else {
                    MarketApiResult.Error("BiQuote API HTTP $code")
                }
            }
        } catch (e: UnknownHostException) {
            MarketApiResult.Error(
                message = "Network offline. Showing cached market data if available.",
                isOffline = true
            )
        } catch (e: SocketTimeoutException) {
            MarketApiResult.Error("BiQuote connection timed out.")
        } catch (e: IOException) {
            MarketApiResult.Error("BiQuote network error: ${e.localizedMessage ?: "Connection failed"}")
        } catch (e: Exception) {
            MarketApiResult.Error("BiQuote unexpected error: ${e.localizedMessage ?: "Unknown"}")
        }
    }

    companion object {
        const val BASE_URL = "https://biquote.io"
        const val SIGNALR_HUB_PATH = "/hubs/tick"

        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(12, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        fun mapTimeframeToBiQuoteInterval(timeframe: Timeframe): String {
            return when (timeframe) {
                Timeframe.M1 -> "1m"
                Timeframe.M5 -> "5m"
                Timeframe.M15 -> "15m"
                Timeframe.M30 -> "30m"
                Timeframe.H1 -> "1h"
                Timeframe.H4 -> "4h"
                Timeframe.D1 -> "1d"
            }
        }

        fun normalizeBiQuoteSymbol(raw: String): String {
            return raw.trim()
                .uppercase(Locale.US)
                .replace("/", "")
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "")
                .takeIf { it.matches(Regex("^[A-Z0-9.]{1,20}$")) }
                .orEmpty()
        }

        fun splitBaseQuote(symbol: String, category: InstrumentCategory): Pair<String, String> {
            val clean = symbol.trim().uppercase(Locale.US).replace("/", "")
            if (clean.length == 6 && (category == InstrumentCategory.FOREX || category == InstrumentCategory.COMMODITIES)) {
                return clean.substring(0, 3) to clean.substring(3, 6)
            }
            if (clean.endsWith("USDT") && clean.length > 4) {
                return clean.removeSuffix("USDT") to "USDT"
            }
            if (clean.endsWith("USD") && clean.length > 3 &&
                (category == InstrumentCategory.FOREX || category == InstrumentCategory.COMMODITIES || category == InstrumentCategory.CRYPTO)
            ) {
                return clean.removeSuffix("USD") to "USD"
            }
            return clean to when (category) {
                InstrumentCategory.STOCKS -> "USD"
                InstrumentCategory.INDICES -> "PTS"
                InstrumentCategory.COMMODITIES -> "USD"
                InstrumentCategory.FOREX -> "FX"
                else -> "USD"
            }
        }

        fun parseSymbolsCatalogPayload(
            rawJson: String,
            defaultCategory: InstrumentCategory?
        ): List<ExchangeSymbolInfo> {
            val list = ArrayList<ExchangeSymbolInfo>()
            try {
                val tokener = JSONTokener(rawJson.trim())
                val root = tokener.nextValue()
                val arr: JSONArray? = when (root) {
                    is JSONArray -> root
                    is JSONObject -> {
                        root.optJSONArray("symbols")
                            ?: root.optJSONArray("data")
                            ?: root.optJSONArray("items")
                            ?: root.optJSONArray("results")
                    }
                    else -> null
                }

                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val entry = arr.opt(i)
                        when (entry) {
                            is String -> {
                                val sym = normalizeBiQuoteSymbol(entry)
                                if (sym.isNotEmpty()) {
                                    val cat = defaultCategory ?: inferInstrumentCategory(sym, MarketProvider.BIQUOTE)
                                    val (base, quote) = splitBaseQuote(sym, cat)
                                    list.add(
                                        ExchangeSymbolInfo(
                                            symbol = sym,
                                            baseAsset = base,
                                            quoteAsset = quote,
                                            status = "TRADING",
                                            category = cat,
                                            provider = MarketProvider.BIQUOTE
                                        )
                                    )
                                }
                            }
                            is JSONObject -> {
                                parseSingleSymbolCatalogObject(entry, defaultCategory)?.let { list.add(it) }
                            }
                        }
                    }
                } else if (root is JSONObject) {
                    val keys = root.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = root.optJSONObject(key)
                        if (child != null) {
                            parseSingleSymbolCatalogObject(child, defaultCategory, fallbackSymbol = key)?.let {
                                list.add(it)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return list.distinctBy { it.symbol }
        }

        private fun parseSingleSymbolCatalogObject(
            obj: JSONObject,
            defaultCategory: InstrumentCategory?,
            fallbackSymbol: String = ""
        ): ExchangeSymbolInfo? {
            val rawSym = obj.optString("symbol")
                .ifBlank { obj.optString("ticker") }
                .ifBlank { obj.optString("code") }
                .ifBlank { obj.optString("id") }
                .ifBlank { fallbackSymbol }
            val sym = normalizeBiQuoteSymbol(rawSym)
            if (sym.isEmpty()) return null

            val rawType = obj.optString("type")
                .ifBlank { obj.optString("category") }
                .ifBlank { obj.optString("assetClass") }
                .ifBlank { obj.optString("market") }
            val cat = if (rawType.isNotBlank()) {
                inferInstrumentCategory(sym, MarketProvider.BIQUOTE, rawType)
            } else {
                defaultCategory ?: inferInstrumentCategory(sym, MarketProvider.BIQUOTE)
            }

            val displayName = obj.optString("name")
                .ifBlank { obj.optString("description") }
                .ifBlank { obj.optString("displayName") }
                .ifBlank { obj.optString("title") }

            val (defaultBase, defaultQuote) = splitBaseQuote(sym, cat)
            val base = obj.optString("baseAsset").ifBlank { obj.optString("base") }.ifBlank { defaultBase }
            val quote = obj.optString("quoteAsset").ifBlank { obj.optString("quote") }.ifBlank { obj.optString("currency") }.ifBlank { defaultQuote }

            return ExchangeSymbolInfo(
                symbol = sym,
                baseAsset = base.uppercase(Locale.US),
                quoteAsset = quote.uppercase(Locale.US),
                status = "TRADING",
                category = cat,
                provider = MarketProvider.BIQUOTE,
                displayName = displayName
            )
        }

        fun parseTickersPayload(
            rawJson: String,
            fallbackSymbol: String = ""
        ): List<Ticker24h> {
            val results = ArrayList<Ticker24h>()
            try {
                val trimmed = rawJson.trim()
                if (trimmed.isEmpty()) return emptyList()
                val root = JSONTokener(trimmed).nextValue()
                when (root) {
                    is JSONArray -> {
                        for (i in 0 until root.length()) {
                            val obj = root.optJSONObject(i) ?: continue
                            parseSingleQuoteObject(obj)?.let { results.add(it) }
                        }
                    }
                    is JSONObject -> {
                        // Direct single quote object?
                        val direct = parseSingleQuoteObject(root, fallbackSymbol)
                        if (direct != null) {
                            results.add(direct)
                        } else {
                            val arr = root.optJSONArray("data")
                                ?: root.optJSONArray("quotes")
                                ?: root.optJSONArray("symbols")
                                ?: root.optJSONArray("items")
                                ?: root.optJSONArray("active")
                                ?: root.optJSONArray("results")
                            if (arr != null) {
                                for (i in 0 until arr.length()) {
                                    val obj = arr.optJSONObject(i) ?: continue
                                    parseSingleQuoteObject(obj)?.let { results.add(it) }
                                }
                            } else {
                                // Map of symbol -> quote object (e.g. {"EURUSD": {...}, "XAUUSD": {...}})
                                val dataObj = root.optJSONObject("data") ?: root.optJSONObject("quotes") ?: root
                                val keys = dataObj.keys()
                                while (keys.hasNext()) {
                                    val k = keys.next()
                                    val child = dataObj.optJSONObject(k) ?: continue
                                    parseSingleQuoteObject(child, fallbackSymbol = k)?.let { results.add(it) }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return results
        }

        fun parseSingleQuoteObject(
            obj: JSONObject,
            fallbackSymbol: String = ""
        ): Ticker24h? {
            val rawSym = obj.optString("symbol")
                .ifBlank { obj.optString("s") }
                .ifBlank { obj.optString("ticker") }
                .ifBlank { obj.optString("instrument") }
                .ifBlank { obj.optString("pair") }
                .ifBlank { fallbackSymbol }
            val sym = normalizeBiQuoteSymbol(rawSym)
            if (sym.isEmpty()) return null

            val bid = optPositiveDouble(obj, "bid", "b", "bidPrice")
            val ask = optPositiveDouble(obj, "ask", "a", "askPrice")
            val directPrice = optPositiveDouble(obj, "price", "lastPrice", "last", "mid", "close", "c", "p", "rate")
            val lastPrice = when {
                directPrice > 0.0 -> directPrice
                bid > 0.0 && ask > 0.0 -> (bid + ask) / 2.0
                bid > 0.0 -> bid
                ask > 0.0 -> ask
                else -> return null
            }

            val hasExplicitSpread = obj.has("spread") && !obj.isNull("spread")
            val rawSpread = if (hasExplicitSpread) obj.optDouble("spread", 0.0).coerceAtLeast(0.0) else 0.0
            val hasGenuineBidAsk = (bid > 0.0 && ask >= bid) || (hasExplicitSpread && rawSpread > 0.0)
            val resolvedBid = when {
                bid > 0.0 -> bid
                hasExplicitSpread && rawSpread > 0.0 -> (lastPrice - rawSpread / 2.0).coerceAtLeast(0.0)
                else -> 0.0
            }
            val resolvedAsk = when {
                ask > 0.0 -> ask
                hasExplicitSpread && rawSpread > 0.0 -> lastPrice + rawSpread / 2.0
                else -> 0.0
            }
            val spread = when {
                hasExplicitSpread && rawSpread > 0.0 -> rawSpread
                resolvedBid > 0.0 && resolvedAsk >= resolvedBid -> resolvedAsk - resolvedBid
                else -> 0.0
            }

            val openPrice = optPositiveDouble(obj, "open", "openPrice", "o", "previousClose", "prevClose")
            val rawChangePct = optSignedDouble(
                obj,
                "changePercent",
                "priceChangePercent",
                "changePct",
                "pctChange",
                "percentChange",
                "cp",
                "P"
            )
            val changePct = when {
                rawChangePct != null -> rawChangePct
                openPrice > 0.0 -> ((lastPrice - openPrice) / openPrice) * 100.0
                else -> 0.0
            }

            val high = optPositiveDouble(obj, "high", "highPrice", "h", "dayHigh").takeIf { it > 0.0 }
                ?: maxOf(lastPrice, bid, ask)
            val low = optPositiveDouble(obj, "low", "lowPrice", "l", "dayLow").takeIf { it > 0.0 }
                ?: minOf(lastPrice, if (bid > 0.0) bid else lastPrice, if (ask > 0.0) ask else lastPrice)
            val vol = optPositiveDouble(obj, "volume", "vol", "v", "tickVolume")
            val quoteVol = optPositiveDouble(obj, "quoteVolume", "turnover", "qv").takeIf { it > 0.0 } ?: vol

            val rawType = obj.optString("type")
                .ifBlank { obj.optString("category") }
                .ifBlank { obj.optString("assetClass") }
            val category = inferInstrumentCategory(sym, MarketProvider.BIQUOTE, rawType)
            val displayName = obj.optString("name")
                .ifBlank { obj.optString("displayName") }
                .ifBlank { obj.optString("description") }
            val providerReportedOpen: Boolean? = when {
                obj.has("isMarketOpen") && !obj.isNull("isMarketOpen") ->
                    obj.optBoolean("isMarketOpen", true)
                obj.has("marketOpen") && !obj.isNull("marketOpen") ->
                    obj.optBoolean("marketOpen", true)
                obj.has("market_open") && !obj.isNull("market_open") ->
                    obj.optBoolean("market_open", true)
                obj.has("status") && !obj.isNull("status") -> {
                    when (obj.optString("status").trim().uppercase(Locale.US)) {
                        "OPEN", "TRADING", "ACTIVE" -> true
                        "CLOSED", "CLOSE", "HALTED", "BREAK", "OFFLINE" -> false
                        else -> null
                    }
                }
                obj.has("marketStatus") && !obj.isNull("marketStatus") -> {
                    when (obj.optString("marketStatus").trim().uppercase(Locale.US)) {
                        "OPEN", "TRADING", "ACTIVE" -> true
                        "CLOSED", "CLOSE", "HALTED", "BREAK", "OFFLINE" -> false
                        else -> null
                    }
                }
                else -> null
            }
            val isMarketOpen = providerReportedOpen ?: true
            val ts = parseFlexibleTimestampMs(obj, System.currentTimeMillis())

            return Ticker24h(
                symbol = sym,
                lastPrice = lastPrice,
                priceChangePercent = changePct,
                highPrice = high,
                lowPrice = low,
                volume = vol,
                quoteVolume = quoteVol,
                updatedAt = ts,
                bid = resolvedBid,
                ask = resolvedAsk,
                spread = spread,
                hasVerifiedBidAsk = hasGenuineBidAsk,
                provider = MarketProvider.BIQUOTE,
                category = category,
                displayName = displayName,
                providerReportedMarketOpen = providerReportedOpen,
                isMarketOpen = isMarketOpen
            )
        }

        fun parseOhlcPayload(
            rawJson: String,
            timeframe: Timeframe
        ): List<Candle> {
            val candles = ArrayList<Candle>()
            try {
                val trimmed = rawJson.trim()
                if (trimmed.isEmpty()) return emptyList()
                val root = JSONTokener(trimmed).nextValue()
                val arr = when (root) {
                    is JSONArray -> root
                    is JSONObject -> {
                        root.optJSONArray("candles")
                            ?: root.optJSONArray("ohlc")
                            ?: root.optJSONArray("data")
                            ?: root.optJSONArray("bars")
                            ?: root.optJSONArray("items")
                            ?: root.optJSONArray("results")
                    }
                    else -> null
                } ?: return emptyList()

                val now = System.currentTimeMillis()
                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)
                    when (item) {
                        is JSONObject -> {
                            val openTime = parseFlexibleTimestampMs(item, -1L)
                            if (openTime <= 0L) continue
                            val open = optPositiveDouble(item, "open", "o", "Open")
                            val high = optPositiveDouble(item, "high", "h", "High")
                            val low = optPositiveDouble(item, "low", "l", "Low")
                            val close = optPositiveDouble(item, "close", "c", "Close")
                            val volume = optPositiveDouble(item, "volume", "v", "Volume", "tickVolume")
                            if (open <= 0.0 || high <= 0.0 || low <= 0.0 || close <= 0.0) continue
                            val closeTime = item.optLong("closeTime", openTime + timeframe.durationMs - 1L)
                            val c = Candle(
                                openTime = openTime,
                                open = open,
                                high = maxOf(high, open, close),
                                low = minOf(low, open, close),
                                close = close,
                                volume = volume.coerceAtLeast(0.0),
                                closeTime = closeTime,
                                isClosed = closeTime <= now
                            )
                            if (c.isValid()) {
                                candles.add(c)
                            }
                        }
                        is JSONArray -> {
                            if (item.length() < 5) continue
                            val rawTime = item.optLong(0, -1L)
                            val openTime = normalizeEpochMs(rawTime)
                            val open = item.optString(1).toDoubleOrNull() ?: continue
                            val high = item.optString(2).toDoubleOrNull() ?: continue
                            val low = item.optString(3).toDoubleOrNull() ?: continue
                            val close = item.optString(4).toDoubleOrNull() ?: continue
                            val volume = if (item.length() >= 6) (item.optString(5).toDoubleOrNull() ?: 0.0) else 0.0
                            val closeTime = openTime + timeframe.durationMs - 1L
                            val c = Candle(
                                openTime = openTime,
                                open = open,
                                high = maxOf(high, open, close),
                                low = minOf(low, open, close),
                                close = close,
                                volume = volume.coerceAtLeast(0.0),
                                closeTime = closeTime,
                                isClosed = closeTime <= now
                            )
                            if (c.isValid()) {
                                candles.add(c)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return candles
        }

        private fun optPositiveDouble(obj: JSONObject, vararg keys: String): Double {
            for (k in keys) {
                if (!obj.has(k) || obj.isNull(k)) continue
                val v = obj.optString(k).toDoubleOrNull() ?: obj.optDouble(k, Double.NaN)
                if (!v.isNaN() && !v.isInfinite() && v > 0.0) {
                    return v
                }
            }
            return 0.0
        }

        private fun optSignedDouble(obj: JSONObject, vararg keys: String): Double? {
            for (k in keys) {
                if (!obj.has(k) || obj.isNull(k)) continue
                val raw = obj.optString(k).replace("%", "").trim()
                val v = raw.toDoubleOrNull() ?: obj.optDouble(k, Double.NaN)
                if (!v.isNaN() && !v.isInfinite()) {
                    return v
                }
            }
            return null
        }

        private fun normalizeEpochMs(rawEpoch: Long): Long {
            if (rawEpoch <= 0L) return -1L
            return if (rawEpoch < 10_000_000_000L) rawEpoch * 1000L else rawEpoch
        }

        private fun parseFlexibleTimestampMs(obj: JSONObject, fallback: Long): Long {
            val timeKeys = listOf("openTime", "timestamp", "time", "t", "date", "datetime", "updatedAt", "ts")
            for (k in timeKeys) {
                if (!obj.has(k) || obj.isNull(k)) continue
                val num = obj.optLong(k, -1L)
                if (num > 100_000_000L) {
                    return normalizeEpochMs(num)
                }
                val str = obj.optString(k, "").trim()
                if (str.isNotEmpty()) {
                    str.toLongOrNull()?.let { parsedLong ->
                        if (parsedLong > 100_000_000L) return normalizeEpochMs(parsedLong)
                    }
                    parseIsoTimestampMs(str)?.let { return it }
                }
            }
            return fallback
        }

        private fun parseIsoTimestampMs(iso: String): Long? {
            val patterns = listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm"
            )
            for (pattern in patterns) {
                try {
                    val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val date = sdf.parse(iso)
                    if (date != null) return date.time
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}

/**
 * Real-time tick & candle updater for BiQuote instruments.
 * Connects to SignalR hub `/hubs/tick` using the SignalR JSON protocol handshake (`{"protocol":"json","version":1}\u001e`)
 * and automatically supplements/falls back to non-blocking REST polling (`/api/latest` + `/api/{symbol}/ohlc`)
 * so charts, watchlist rows, AI setup detection, and simulated bot positions receive continuous live ticks.
 */
class BiQuoteRealtimeManager(
    private val client: BiQuoteClient,
    private val httpClient: OkHttpClient = BiQuoteClient.defaultHttpClient()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionStatus = MutableStateFlow(WsConnectionStatus.LIVE)
    val connectionStatus: StateFlow<WsConnectionStatus> = _connectionStatus.asStateFlow()

    fun updateConnectionStatus(status: WsConnectionStatus) {
        _connectionStatus.value = status
    }

    private val _klineEvents = MutableSharedFlow<KlineStreamEvent>(extraBufferCapacity = 64)
    val klineEvents: SharedFlow<KlineStreamEvent> = _klineEvents.asSharedFlow()

    private val _tickerEvents = MutableSharedFlow<Ticker24h>(extraBufferCapacity = 128)
    val tickerEvents: SharedFlow<Ticker24h> = _tickerEvents.asSharedFlow()

    @Volatile
    private var activeSymbol: String = ""
    @Volatile
    private var activeTimeframe: Timeframe = Timeframe.M15
    @Volatile
    private var watchlistSymbols: List<String> = emptyList()
    @Volatile
    private var isActiveMode: Boolean = false
    @Volatile
    private var latestBarTemplate: Candle? = null

    private var webSocket: WebSocket? = null
    private var pollingJob: Job? = null

    fun setLatestCandleBaseline(candle: Candle?) {
        latestBarTemplate = candle
    }

    fun subscribe(
        symbol: String,
        timeframe: Timeframe,
        nonCryptoWatchlistSymbols: List<String>
    ) {
        activeSymbol = BiQuoteClient.normalizeBiQuoteSymbol(symbol)
        activeTimeframe = timeframe
        watchlistSymbols = nonCryptoWatchlistSymbols.map { BiQuoteClient.normalizeBiQuoteSymbol(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        isActiveMode = true

        connectSignalRWebSocket()
        startPollingLoop()
    }

    fun updateWatchlistSymbols(nonCryptoWatchlistSymbols: List<String>) {
        watchlistSymbols = nonCryptoWatchlistSymbols.map { BiQuoteClient.normalizeBiQuoteSymbol(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (watchlistSymbols.isNotEmpty() && pollingJob?.isActive != true) {
            startPollingLoop()
        }
    }

    fun stopActiveChartSubscription() {
        isActiveMode = false
        activeSymbol = ""
    }

    private fun connectSignalRWebSocket() {
        runCatching { webSocket?.cancel() }
        webSocket = null

        scope.launch {
            val wsUrl = negotiateSignalRUrl() ?: "wss://biquote.io/hubs/tick"
            val request = Request.Builder()
                .url(wsUrl)
                .build()

            webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _connectionStatus.value = WsConnectionStatus.LIVE
                    // SignalR JSON handshake terminated with ASCII 0x1E
                    webSocket.send("{\"protocol\":\"json\",\"version\":1}\u001e")
                    val symbolsToSub = (listOf(activeSymbol) + watchlistSymbols).filter { it.isNotEmpty() }.distinct()
                    for (sym in symbolsToSub) {
                        val subMsg = "{\"type\":1,\"target\":\"Subscribe\",\"arguments\":[\"$sym\"]}\u001e"
                        webSocket.send(subMsg)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleSignalRMessages(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // Graceful fallback to REST polling
                    if (isActiveMode) {
                        _connectionStatus.value = WsConnectionStatus.LIVE
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isActiveMode) {
                        _connectionStatus.value = WsConnectionStatus.DISCONNECTED
                    }
                }
            })
        }
    }

    private fun negotiateSignalRUrl(): String? {
        return try {
            val negotiateReq = Request.Builder()
                .url("${BiQuoteClient.BASE_URL}${BiQuoteClient.SIGNALR_HUB_PATH}/negotiate?negotiateVersion=1")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(negotiateReq).execute().use { res ->
                if (!res.isSuccessful) return null
                val body = res.body?.string().orEmpty()
                val json = JSONObject(body)
                val token = json.optString("connectionToken").ifBlank { json.optString("connectionId") }
                if (token.isNotBlank()) {
                    "wss://biquote.io${BiQuoteClient.SIGNALR_HUB_PATH}?id=${URLEncoder.encode(token, "UTF-8")}"
                } else {
                    "wss://biquote.io${BiQuoteClient.SIGNALR_HUB_PATH}"
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun handleSignalRMessages(rawPayload: String) {
        val frames = rawPayload.split('\u001e')
        for (frame in frames) {
            val trimmed = frame.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) continue
            try {
                val json = JSONObject(trimmed)
                val type = json.optInt("type", -1)
                if (type == 6) {
                    // SignalR ping -> respond with ping
                    webSocket?.send("{\"type\":6}\u001e")
                    continue
                }
                val args = json.optJSONArray("arguments")
                if (args != null && args.length() > 0) {
                    for (i in 0 until args.length()) {
                        val arg = args.opt(i)
                        when (arg) {
                            is JSONObject -> BiQuoteClient.parseSingleQuoteObject(arg)?.let { onLiveTickerReceived(it) }
                            is JSONArray -> {
                                for (j in 0 until arg.length()) {
                                    val child = arg.optJSONObject(j) ?: continue
                                    BiQuoteClient.parseSingleQuoteObject(child)?.let { onLiveTickerReceived(it) }
                                }
                            }
                            is String -> {
                                BiQuoteClient.parseTickersPayload(arg).forEach { onLiveTickerReceived(it) }
                            }
                        }
                    }
                } else {
                    BiQuoteClient.parseSingleQuoteObject(json)?.let { onLiveTickerReceived(it) }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun onLiveTickerReceived(ticker: Ticker24h) {
        _connectionStatus.value = WsConnectionStatus.LIVE
        _tickerEvents.tryEmit(ticker)

        if (isActiveMode && ticker.symbol.equals(activeSymbol, ignoreCase = true) && ticker.lastPrice > 0.0) {
            val tf = activeTimeframe
            val now = ticker.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            val bucketOpenTime = (now / tf.durationMs) * tf.durationMs
            val bucketCloseTime = bucketOpenTime + tf.durationMs - 1L
            val baseline = latestBarTemplate

            val syntheticCandle = if (baseline != null && baseline.openTime == bucketOpenTime) {
                baseline.copy(
                    high = maxOf(baseline.high, ticker.lastPrice),
                    low = minOf(baseline.low, ticker.lastPrice),
                    close = ticker.lastPrice,
                    isClosed = false
                )
            } else {
                Candle(
                    openTime = bucketOpenTime,
                    open = ticker.lastPrice,
                    high = ticker.lastPrice,
                    low = ticker.lastPrice,
                    close = ticker.lastPrice,
                    volume = ticker.volume.coerceAtLeast(0.0),
                    closeTime = bucketCloseTime,
                    isClosed = false
                )
            }
            latestBarTemplate = syntheticCandle
            _klineEvents.tryEmit(
                KlineStreamEvent(
                    symbol = ticker.symbol,
                    timeframe = tf,
                    candle = syntheticCandle,
                    eventTime = now
                )
            )
        }
    }

    private fun startPollingLoop() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                val symbolsToPoll = buildList {
                    if (isActiveMode && activeSymbol.isNotEmpty()) add(activeSymbol)
                    addAll(watchlistSymbols)
                }.distinct()

                if (symbolsToPoll.isNotEmpty()) {
                    when (val res = client.fetchLatestQuotes(symbolsToPoll)) {
                        is MarketApiResult.Success -> {
                            _connectionStatus.value = WsConnectionStatus.LIVE
                            res.data.forEach { onLiveTickerReceived(it) }
                        }
                        is MarketApiResult.Error -> {
                            if (res.isOffline) {
                                _connectionStatus.value = WsConnectionStatus.DISCONNECTED
                            }
                        }
                    }
                }
                delay(if (isActiveMode) 4_000L else 10_000L)
            }
        }
    }
}
