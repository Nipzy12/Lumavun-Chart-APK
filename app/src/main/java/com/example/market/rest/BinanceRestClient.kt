package com.example.market.rest

import com.example.market.candle.Candle
import com.example.market.candle.CandleMerger
import com.example.market.candle.InstrumentCategory
import com.example.market.candle.MarketProvider
import com.example.market.candle.Ticker24h
import com.example.market.candle.Timeframe
import com.example.market.candle.inferInstrumentCategory
import com.example.market.candle.inferMarketProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

sealed class MarketApiResult<out T> {
    data class Success<T>(val data: T) : MarketApiResult<T>()
    data class Error(val message: String, val isOffline: Boolean = false, val isRateLimited: Boolean = false) : MarketApiResult<Nothing>()
}

data class ExchangeSymbolInfo(
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String,
    val status: String,
    val category: InstrumentCategory = inferInstrumentCategory(symbol),
    val provider: MarketProvider = inferMarketProvider(symbol, category),
    val displayName: String = ""
)

class BinanceRestClient(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    companion object {
        const val DEFAULT_CANDLE_LIMIT = 350

        // Official Binance public market data REST endpoints (data-api.binance.vision is globally accessible)
        private val BASE_ENDPOINTS = listOf(
            "https://data-api.binance.vision",
            "https://api.binance.com",
            "https://api-gcp.binance.com",
            "https://api1.binance.com",
            "https://api2.binance.com"
        )

        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(12, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    @Volatile
    private var preferredEndpointIndex = 0

    /**
     * Fetches historical OHLCV candles from Binance REST API.
     * Used ONLY for initial chart load or timeframe/symbol switch (300-500 candles).
     */
    suspend fun fetchHistoricalCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int = DEFAULT_CANDLE_LIMIT
    ): MarketApiResult<List<Candle>> = withContext(Dispatchers.IO) {
        val cleanSymbol = symbol.trim().uppercase(Locale.US)
        if (cleanSymbol.isEmpty() || !cleanSymbol.matches(Regex("^[A-Z0-9]{4,20}$"))) {
            return@withContext MarketApiResult.Error("Invalid symbol: $symbol")
        }
        val safeLimit = limit.coerceIn(50, 500)
        val path = "/api/v3/klines?symbol=$cleanSymbol&interval=${timeframe.binanceInterval}&limit=$safeLimit"

        when (val rawResult = executeWithFailover(path)) {
            is MarketApiResult.Error -> rawResult
            is MarketApiResult.Success -> {
                try {
                    val jsonArray = JSONArray(rawResult.data)
                    if (jsonArray.length() == 0) {
                        return@withContext MarketApiResult.Error("Empty historical candle data for $cleanSymbol.")
                    }
                    val candles = ArrayList<Candle>(jsonArray.length())
                    val now = System.currentTimeMillis()
                    for (i in 0 until jsonArray.length()) {
                        val row = jsonArray.optJSONArray(i) ?: continue
                        if (row.length() < 7) continue
                        val openTime = row.optLong(0, -1L)
                        val open = row.optString(1).toDoubleOrNull() ?: continue
                        val high = row.optString(2).toDoubleOrNull() ?: continue
                        val low = row.optString(3).toDoubleOrNull() ?: continue
                        val close = row.optString(4).toDoubleOrNull() ?: continue
                        val volume = row.optString(5).toDoubleOrNull() ?: 0.0
                        val closeTime = row.optLong(6, openTime + timeframe.durationMs - 1L)

                        val candle = Candle(
                            openTime = openTime,
                            open = open,
                            high = high,
                            low = low,
                            close = close,
                            volume = volume,
                            closeTime = closeTime,
                            isClosed = closeTime <= now
                        )
                        if (candle.isValid()) {
                            candles.add(candle)
                        }
                    }
                    val sanitized = CandleMerger.sanitizeHistoricalCandles(candles)
                    if (sanitized.isEmpty()) {
                        MarketApiResult.Error("No valid historical candles returned for $cleanSymbol.")
                    } else {
                        MarketApiResult.Success(sanitized)
                    }
                } catch (e: Exception) {
                    MarketApiResult.Error("Malformed candle response from Binance API.")
                }
            }
        }
    }

    /**
     * Fetches initial 24h ticker snapshot for a list of symbols so the watchlist and header
     * have immediate data before WebSocket ticks arrive.
     */
    suspend fun fetchInitialTickers(symbols: List<String>): MarketApiResult<List<Ticker24h>> = withContext(Dispatchers.IO) {
        val cleanSymbols = symbols.map { it.trim().uppercase(Locale.US) }
            .filter { it.matches(Regex("^[A-Z0-9]{4,20}$")) }
            .distinct()
        if (cleanSymbols.isEmpty()) return@withContext MarketApiResult.Success(emptyList())

        val symbolsParam = cleanSymbols.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")
        val encoded = java.net.URLEncoder.encode(symbolsParam, "UTF-8")
        val path = "/api/v3/ticker/24hr?symbols=$encoded"

        when (val res = executeWithFailover(path)) {
            is MarketApiResult.Error -> res
            is MarketApiResult.Success -> {
                try {
                    val arr = JSONArray(res.data)
                    val list = ArrayList<Ticker24h>(arr.length())
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        parseTickerJson(obj)?.let { list.add(it) }
                    }
                    MarketApiResult.Success(list)
                } catch (e: Exception) {
                    MarketApiResult.Error("Failed to parse 24h ticker data.")
                }
            }
        }
    }

    /**
     * Requirement #4: Fetches dynamic USDT 24h market tickers from Binance for Market Discovery
     * (All USDT, Top Volume, Top Gainers, Top Losers) and Search & Add Symbol enrichment.
     */
    suspend fun fetchDynamicUsdtTickers(maxCount: Int = 250): MarketApiResult<List<Ticker24h>> = withContext(Dispatchers.IO) {
        val path = "/api/v3/ticker/24hr"
        when (val res = executeWithFailover(path)) {
            is MarketApiResult.Error -> res
            is MarketApiResult.Success -> {
                try {
                    val arr = JSONArray(res.data)
                    val list = ArrayList<Ticker24h>(300)
                    val excludedPrefixes = setOf("USDC", "FDUSD", "TUSD", "USDP", "DAI", "BUSD", "EUR", "AEUR")
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val sym = obj.optString("symbol", "")
                        if (!sym.endsWith("USDT") || sym.length < 5) continue
                        val base = sym.removeSuffix("USDT")
                        if (base in excludedPrefixes || base.endsWith("UP") || base.endsWith("DOWN") ||
                            base.endsWith("BULL") || base.endsWith("BEAR")
                        ) {
                            continue
                        }
                        val parsed = parseTickerJson(obj) ?: continue
                        if (parsed.quoteVolume > 0.0) {
                            list.add(parsed)
                        }
                    }
                    val sortedByVolume = list.sortedByDescending { it.quoteVolume }.take(maxCount)
                    MarketApiResult.Success(sortedByVolume)
                } catch (e: Exception) {
                    MarketApiResult.Error("Failed to parse dynamic USDT market tickers.")
                }
            }
        }
    }

    /**
     * Fetches a single 24h ticker from Binance for a specific USDT symbol.
     * Never returns dummy prices on error.
     */
    suspend fun fetchSingleTicker(symbol: String): MarketApiResult<Ticker24h> = withContext(Dispatchers.IO) {
        val clean = symbol.trim().uppercase(Locale.US)
        if (!clean.matches(Regex("^[A-Z0-9]{4,20}$"))) {
            return@withContext MarketApiResult.Error("Invalid symbol format: $symbol")
        }
        val path = "/api/v3/ticker/24hr?symbol=$clean"
        when (val res = executeWithFailover(path)) {
            is MarketApiResult.Error -> res
            is MarketApiResult.Success -> {
                try {
                    val obj = JSONObject(res.data)
                    val parsed = parseTickerJson(obj)
                    if (parsed != null) {
                        MarketApiResult.Success(parsed)
                    } else {
                        MarketApiResult.Error("Ticker data unavailable or symbol $clean is not actively trading.")
                    }
                } catch (e: Exception) {
                    MarketApiResult.Error("Failed to parse ticker response for $clean.")
                }
            }
        }
    }

    /**
     * Validates that a specific USDT pair exists on Binance and has active TRADING status.
     */
    suspend fun validateUsdtTradingSymbol(symbol: String): MarketApiResult<ExchangeSymbolInfo> = withContext(Dispatchers.IO) {
        val clean = symbol.trim().uppercase(Locale.US)
        if (!clean.endsWith("USDT") || !clean.matches(Regex("^[A-Z0-9]{5,20}$"))) {
            return@withContext MarketApiResult.Error("Symbol must be a valid USDT pair (e.g., BTCUSDT, SOLUSDT).")
        }
        val path = "/api/v3/exchangeInfo?symbol=$clean"
        when (val res = executeWithFailover(path)) {
            is MarketApiResult.Error -> res
            is MarketApiResult.Success -> {
                try {
                    val root = JSONObject(res.data)
                    val symbolsArr = root.optJSONArray("symbols")
                        ?: return@withContext MarketApiResult.Error("Symbol $clean not found on Binance.")
                    for (i in 0 until symbolsArr.length()) {
                        val sObj = symbolsArr.optJSONObject(i) ?: continue
                        val sym = sObj.optString("symbol").uppercase(Locale.US)
                        val status = sObj.optString("status").uppercase(Locale.US)
                        val quote = sObj.optString("quoteAsset").uppercase(Locale.US)
                        val base = sObj.optString("baseAsset").uppercase(Locale.US)
                        val spotAllowed = sObj.optBoolean("isSpotTradingAllowed", true)
                        if (sym == clean) {
                            return@withContext if (status == "TRADING" && quote == "USDT" && spotAllowed) {
                                MarketApiResult.Success(
                                    ExchangeSymbolInfo(
                                        symbol = sym,
                                        baseAsset = base.ifEmpty { sym.removeSuffix("USDT") },
                                        quoteAsset = "USDT",
                                        status = status
                                    )
                                )
                            } else {
                                MarketApiResult.Error("Symbol $clean is not active for USDT spot trading (status: $status).")
                            }
                        }
                    }
                    MarketApiResult.Error("Symbol $clean not found on Binance.")
                } catch (e: Exception) {
                    MarketApiResult.Error("Failed to validate symbol $clean.")
                }
            }
        }
    }

    /**
     * Fetches available active USDT spot trading symbols from Binance exchangeInfo for local caching and Pair Search.
     */
    suspend fun fetchExchangeSymbols(): MarketApiResult<List<ExchangeSymbolInfo>> = withContext(Dispatchers.IO) {
        val primaryPath = "/api/v3/exchangeInfo?permissions=SPOT"
        val res = when (val firstAttempt = executeWithFailover(primaryPath)) {
            is MarketApiResult.Success -> firstAttempt
            is MarketApiResult.Error -> executeWithFailover("/api/v3/exchangeInfo")
        }
        when (res) {
            is MarketApiResult.Error -> res
            is MarketApiResult.Success -> {
                try {
                    val root = JSONObject(res.data)
                    val symbolsArr = root.optJSONArray("symbols")
                        ?: return@withContext MarketApiResult.Error("Missing symbols in exchangeInfo")
                    val result = ArrayList<ExchangeSymbolInfo>(500)
                    for (i in 0 until symbolsArr.length()) {
                        val sObj = symbolsArr.optJSONObject(i) ?: continue
                        val status = sObj.optString("status")
                        val quote = sObj.optString("quoteAsset")
                        val spotAllowed = sObj.optBoolean("isSpotTradingAllowed", true)
                        if (status == "TRADING" && quote == "USDT" && spotAllowed) {
                            val symbol = sObj.optString("symbol")
                            val base = sObj.optString("baseAsset")
                            if (symbol.isNotEmpty() && base.isNotEmpty() &&
                                !base.endsWith("UP") && !base.endsWith("DOWN") &&
                                !base.endsWith("BULL") && !base.endsWith("BEAR")
                            ) {
                                result.add(
                                    ExchangeSymbolInfo(
                                        symbol = symbol,
                                        baseAsset = base,
                                        quoteAsset = quote,
                                        status = status
                                    )
                                )
                            }
                        }
                    }
                    MarketApiResult.Success(result)
                } catch (e: Exception) {
                    MarketApiResult.Error("Failed to parse exchange symbols.")
                }
            }
        }
    }

    private fun parseTickerJson(obj: JSONObject): Ticker24h? {
        val symbol = obj.optString("symbol")
        if (symbol.isEmpty()) return null
        val lastPrice = obj.optString("lastPrice").toDoubleOrNull() ?: return null
        if (lastPrice <= 0.0) return null
        val changePct = obj.optString("priceChangePercent").toDoubleOrNull() ?: 0.0
        val high = obj.optString("highPrice").toDoubleOrNull() ?: lastPrice
        val low = obj.optString("lowPrice").toDoubleOrNull() ?: lastPrice
        val vol = obj.optString("volume").toDoubleOrNull() ?: 0.0
        val quoteVol = obj.optString("quoteVolume").toDoubleOrNull() ?: 0.0
        return Ticker24h(
            symbol = symbol,
            lastPrice = lastPrice,
            priceChangePercent = changePct,
            highPrice = high,
            lowPrice = low,
            volume = vol,
            quoteVolume = quoteVol
        )
    }

    private fun executeWithFailover(path: String): MarketApiResult<String> {
        var lastErrorMsg = "Unable to connect to Binance API."
        var isOffline = false
        var isRateLimited = false

        val count = BASE_ENDPOINTS.size
        val startIndex = preferredEndpointIndex

        for (offset in 0 until count) {
            val idx = (startIndex + offset) % count
            val baseUrl = BASE_ENDPOINTS[idx]
            val fullUrl = "$baseUrl$path"

            val request = Request.Builder()
                .url(fullUrl)
                .header("Accept", "application/json")
                .get()
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    val code = response.code
                    val bodyStr = response.body?.string().orEmpty()

                    if (response.isSuccessful && bodyStr.isNotEmpty()) {
                        preferredEndpointIndex = idx
                        return MarketApiResult.Success(bodyStr)
                    } else if (code == 429 || code == 418) {
                        isRateLimited = true
                        lastErrorMsg = "Binance API rate limit reached (HTTP $code). Please wait a moment."
                    } else if (code == 400) {
                        val apiMsg = try {
                            JSONObject(bodyStr).optString("msg", "Invalid symbol or request parameter.")
                        } catch (_: Exception) {
                            "Invalid symbol or request parameter."
                        }
                        return MarketApiResult.Error("Binance API: $apiMsg")
                    } else {
                        lastErrorMsg = "Binance API HTTP $code"
                    }
                }
            } catch (e: UnknownHostException) {
                isOffline = true
                lastErrorMsg = "Network offline. Showing cached data if available."
            } catch (e: SocketTimeoutException) {
                lastErrorMsg = "Connection timed out. Trying backup endpoint..."
            } catch (e: IOException) {
                lastErrorMsg = "Network error: ${e.localizedMessage ?: "Connection failed"}"
            } catch (e: Exception) {
                lastErrorMsg = "Unexpected error: ${e.localizedMessage ?: "Unknown"}"
            }
        }

        return MarketApiResult.Error(
            message = lastErrorMsg,
            isOffline = isOffline,
            isRateLimited = isRateLimited
        )
    }
}
