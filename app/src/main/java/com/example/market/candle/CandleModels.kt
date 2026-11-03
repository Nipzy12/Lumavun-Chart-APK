package com.example.market.candle

import java.util.Locale

enum class Timeframe(val binanceInterval: String, val label: String, val durationMs: Long) {
    M1("1m", "1m", 60_000L),
    M5("5m", "5m", 300_000L),
    M15("15m", "15m", 900_000L),
    M30("30m", "30m", 1_800_000L),
    H1("1h", "1H", 3_600_000L),
    H4("4h", "4H", 14_400_000L),
    D1("1d", "1D", 86_400_000L);

    /**
     * Requirement #1: Multi-Timeframe Hierarchy
     * Examples:
     * - 15m -> Higher = 1H, Lower = 5m
     * - 1H  -> Higher = 4H, Lower = 15m
     * - 4H  -> Higher = 1D, Lower = 1H
     */
    fun higherTimeframe(): Timeframe? {
        return when (this) {
            M1 -> M5
            M5 -> M15
            M15 -> H1
            M30 -> H1
            H1 -> H4
            H4 -> D1
            D1 -> null
        }
    }

    fun lowerTimeframe(): Timeframe? {
        return when (this) {
            M1 -> null
            M5 -> M1
            M15 -> M5
            M30 -> M15
            H1 -> M15
            H4 -> H1
            D1 -> H4
        }
    }

    companion object {
        fun fromInterval(interval: String): Timeframe {
            return entries.firstOrNull {
                it.binanceInterval.equals(interval, ignoreCase = true) ||
                    it.label.equals(interval, ignoreCase = true)
            } ?: M15
        }
    }
}

data class Candle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long,
    val isClosed: Boolean = true
) {
    val isBullish: Boolean get() = close >= open

    fun isValid(): Boolean {
        return open > 0.0 && high > 0.0 && low > 0.0 && close > 0.0 &&
            !open.isNaN() && !high.isNaN() && !low.isNaN() && !close.isNaN() &&
            !volume.isNaN() && high >= low && openTime > 0L
    }
}

enum class MarketProvider(val code: String, val displayLabel: String) {
    BINANCE("BINANCE", "Binance"),
    BIQUOTE("BIQUOTE", "BiQuote"),
    EXNESS("EXNESS", "Exness");

    companion object {
        fun fromCode(code: String?): MarketProvider {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: BINANCE
        }
    }
}

enum class InstrumentCategory(
    val code: String,
    val label: String,
    val biquoteType: String,
    val shortBadge: String
) {
    ALL("ALL", "All", "", "ALL"),
    CRYPTO("CRYPTO", "Crypto", "Crypto", "CRYPTO"),
    FOREX("FOREX", "Forex", "Forex", "FX"),
    COMMODITIES("COMMODITIES", "Metals", "Commodity", "METAL"),
    STOCKS("STOCKS", "Stocks", "Stock", "STOCK"),
    INDICES("INDICES", "Indices", "Index", "INDEX");

    companion object {
        fun fromCode(code: String?): InstrumentCategory {
            if (code.isNullOrBlank()) return CRYPTO
            return entries.firstOrNull {
                it.code.equals(code, ignoreCase = true) ||
                    it.label.equals(code, ignoreCase = true) ||
                    it.biquoteType.equals(code, ignoreCase = true) ||
                    it.shortBadge.equals(code, ignoreCase = true)
            } ?: when (code.trim().uppercase(Locale.US)) {
                "METALS", "METAL", "COMMODITY", "COMMODITIES" -> COMMODITIES
                "STOCK", "STOCKS", "EQUITY", "EQUITIES" -> STOCKS
                "INDEX", "INDICES" -> INDICES
                "FOREX", "FX", "CURRENCY" -> FOREX
                else -> CRYPTO
            }
        }
    }
}

data class Ticker24h(
    val symbol: String,
    val lastPrice: Double,
    val priceChangePercent: Double,
    val highPrice: Double,
    val lowPrice: Double,
    val volume: Double,
    val quoteVolume: Double,
    val updatedAt: Long = System.currentTimeMillis(),
    val bid: Double = 0.0,
    val ask: Double = 0.0,
    val spread: Double = 0.0,
    val hasVerifiedBidAsk: Boolean = (bid > 0.0 && ask >= bid && (ask > bid || spread > 0.0)),
    val provider: MarketProvider = inferMarketProvider(symbol),
    val category: InstrumentCategory = inferInstrumentCategory(symbol),
    val displayName: String = "",
    val providerReportedMarketOpen: Boolean? = null,
    val isMarketOpen: Boolean = providerReportedMarketOpen ?: true
) {
    val bidPrice: Double get() = bid
    val askPrice: Double get() = ask
    val formattedPair: String
        get() = formatSymbolPair(symbol, category)
}

private val KNOWN_FOREX_CURRENCIES = setOf(
    "EUR", "USD", "GBP", "JPY", "AUD", "NZD", "CAD", "CHF", "SGD", "HKD", "NOK", "SEK", "TRY", "ZAR", "MXN", "CNH"
)

private val KNOWN_COMMODITY_SYMBOLS = setOf(
    "XAUUSD", "XAGUSD", "XPTUSD", "XPDUSD", "WTI", "USOIL", "UKOIL", "BRENT", "NATGAS", "XCUUSD", "COPPER"
)

private val KNOWN_INDEX_SYMBOLS = setOf(
    "US500", "SPX500", "SP500", "USTEC", "NAS100", "NDX100", "US30", "DJI30", "DJ30",
    "GER40", "DE40", "DAX40", "UK100", "FTSE100", "JPN225", "JP225", "NIKKEI225",
    "FRA40", "AUS200", "HK50", "EU50", "VIX", "DXY", "USDX"
)

private val KNOWN_STOCK_SYMBOLS = setOf(
    "AAPL", "NVDA", "TSLA", "MSFT", "AMZN", "META", "GOOGL", "GOOG", "AMD", "NFLX",
    "INTC", "COIN", "MSTR", "PLTR", "JPM", "V", "MA", "DIS", "BA", "BABA", "SPY", "QQQ"
)

fun inferInstrumentCategory(
    symbol: String,
    provider: MarketProvider? = null,
    rawType: String = ""
): InstrumentCategory {
    if (rawType.isNotBlank()) {
        val parsed = InstrumentCategory.fromCode(rawType)
        if (parsed != InstrumentCategory.ALL) return parsed
    }
    val clean = symbol.trim().uppercase(Locale.US).replace("/", "").replace("-", "").replace("_", "")
    if (clean.endsWith("USDT") || clean.endsWith("FDUSD") || clean.endsWith("USDC") || clean.endsWith("BUSD")) {
        return InstrumentCategory.CRYPTO
    }
    if (clean in KNOWN_COMMODITY_SYMBOLS || clean.startsWith("XAU") || clean.startsWith("XAG") ||
        clean.startsWith("XPT") || clean.startsWith("XPD") || clean.contains("OIL")
    ) {
        return InstrumentCategory.COMMODITIES
    }
    if (clean in KNOWN_INDEX_SYMBOLS) {
        return InstrumentCategory.INDICES
    }
    if (clean.length == 6) {
        val base = clean.substring(0, 3)
        val quote = clean.substring(3, 6)
        if (base in KNOWN_FOREX_CURRENCIES && quote in KNOWN_FOREX_CURRENCIES) {
            return InstrumentCategory.FOREX
        }
    }
    if (clean in KNOWN_STOCK_SYMBOLS) {
        return InstrumentCategory.STOCKS
    }
    if (provider == MarketProvider.BIQUOTE) {
        return InstrumentCategory.STOCKS
    }
    return InstrumentCategory.CRYPTO
}

fun inferMarketProvider(
    symbol: String,
    category: InstrumentCategory? = null
): MarketProvider {
    val clean = symbol.trim().uppercase(Locale.US).replace("/", "").replace("-", "")
    if (clean.startsWith("EXNESS:") || clean.endsWith("_EXN")) {
        return MarketProvider.EXNESS
    }
    if (clean.endsWith("USDT") && clean.length >= 5) {
        return MarketProvider.BINANCE
    }
    val resolvedCategory = category ?: inferInstrumentCategory(clean)
    return if (resolvedCategory == InstrumentCategory.CRYPTO && clean.endsWith("USDT")) {
        MarketProvider.BINANCE
    } else {
        MarketProvider.BIQUOTE
    }
}

fun formatSymbolPair(symbol: String, category: InstrumentCategory? = null): String {
    val upper = symbol.trim().uppercase(Locale.US)
    if (upper.contains("/")) return upper
    val cryptoQuotes = listOf("USDT", "FDUSD", "USDC", "BUSD", "BTC", "ETH", "BNB")
    for (q in cryptoQuotes) {
        if (upper.endsWith(q) && upper.length > q.length) {
            return "${upper.removeSuffix(q)}/$q"
        }
    }
    val cat = category ?: inferInstrumentCategory(upper)
    if ((cat == InstrumentCategory.FOREX || cat == InstrumentCategory.COMMODITIES) && upper.length == 6) {
        val base = upper.substring(0, 3)
        val quote = upper.substring(3, 6)
        if (quote in KNOWN_FOREX_CURRENCIES) {
            return "$base/$quote"
        }
    }
    return upper
}

fun formatCryptoPrice(price: Double): String {
    if (price.isNaN() || price.isInfinite() || price <= 0.0) return "0.00"
    return when {
        price >= 1000.0 -> String.format(Locale.US, "%,.2f", price)
        price >= 1.0 -> String.format(Locale.US, "%,.2f", price)
        price >= 0.01 -> String.format(Locale.US, "%.4f", price)
        else -> String.format(Locale.US, "%.6f", price)
    }
}

fun formatMarketPrice(
    price: Double,
    symbol: String = "",
    category: InstrumentCategory? = null
): String {
    if (price.isNaN() || price.isInfinite() || price <= 0.0) return "0.00"
    val clean = symbol.trim().uppercase(Locale.US).replace("/", "")
    if (clean.isEmpty()) return formatCryptoPrice(price)
    val cat = category ?: inferInstrumentCategory(clean)
    return when (cat) {
        InstrumentCategory.FOREX -> {
            if (clean.endsWith("JPY")) {
                String.format(Locale.US, "%.3f", price)
            } else {
                String.format(Locale.US, "%.5f", price)
            }
        }
        InstrumentCategory.COMMODITIES -> {
            when {
                clean.startsWith("XAG") -> String.format(Locale.US, "%,.3f", price)
                price >= 100.0 -> String.format(Locale.US, "%,.2f", price)
                else -> String.format(Locale.US, "%,.3f", price)
            }
        }
        InstrumentCategory.STOCKS, InstrumentCategory.INDICES -> {
            String.format(Locale.US, "%,.2f", price)
        }
        else -> formatCryptoPrice(price)
    }
}

fun formatInstrumentPrice(
    price: Double,
    symbol: String = "",
    category: InstrumentCategory? = null
): String = formatMarketPrice(price, symbol, category)

fun formatPriceWithPrefix(
    price: Double,
    symbol: String = "",
    category: InstrumentCategory? = null
): String {
    if (price.isNaN() || price.isInfinite() || price <= 0.0) return "--"
    val clean = symbol.trim().uppercase(Locale.US).replace("/", "")
    val cat = category ?: inferInstrumentCategory(clean)
    val formatted = formatMarketPrice(price, clean, cat)
    return when (cat) {
        InstrumentCategory.FOREX, InstrumentCategory.INDICES -> formatted
        else -> "$$formatted"
    }
}

fun formatCompactVolume(volume: Double): String {
    if (volume.isNaN() || volume.isInfinite() || volume <= 0.0) return "0"
    return when {
        volume >= 1_000_000_000.0 -> String.format(Locale.US, "%.2fB", volume / 1_000_000_000.0)
        volume >= 1_000_000.0 -> String.format(Locale.US, "%.2fM", volume / 1_000_000.0)
        volume >= 1_000.0 -> String.format(Locale.US, "%.2fK", volume / 1_000.0)
        else -> String.format(Locale.US, "%.2f", volume)
    }
}

object CandleMerger {
    private const val MAX_CANDLES_IN_MEMORY = 500

    /**
     * Merges a real-time WebSocket candle into the historical list without creating duplicates.
     * - If openTime matches the latest candle: updates in-place.
     * - If openTime is newer than the latest candle: appends new candle and trims to MAX_CANDLES_IN_MEMORY.
     * - If openTime belongs to an older existing candle: updates that index.
     */
    fun mergeRealtimeCandle(existing: List<Candle>, incoming: Candle): List<Candle> {
        if (!incoming.isValid()) return existing
        if (existing.isEmpty()) return listOf(incoming)

        val lastIdx = existing.lastIndex
        val lastCandle = existing[lastIdx]

        return when {
            incoming.openTime == lastCandle.openTime -> {
                val copy = ArrayList<Candle>(existing.size)
                copy.addAll(existing)
                copy[lastIdx] = incoming
                copy
            }
            incoming.openTime > lastCandle.openTime -> {
                val copy = ArrayList<Candle>(existing.size + 1)
                if (existing.size >= MAX_CANDLES_IN_MEMORY) {
                    copy.addAll(existing.subList(existing.size - MAX_CANDLES_IN_MEMORY + 1, existing.size))
                } else {
                    copy.addAll(existing)
                }
                copy.add(incoming)
                copy
            }
            else -> {
                val targetIdx = existing.binarySearchBy(incoming.openTime) { it.openTime }
                if (targetIdx >= 0) {
                    val copy = ArrayList<Candle>(existing.size)
                    copy.addAll(existing)
                    copy[targetIdx] = incoming
                    copy
                } else {
                    existing
                }
            }
        }
    }

    /**
     * Deduplicates and sorts raw historical candles by openTime ascending.
     */
    fun sanitizeHistoricalCandles(raw: List<Candle>): List<Candle> {
        if (raw.isEmpty()) return emptyList()
        val map = LinkedHashMap<Long, Candle>(raw.size)
        for (c in raw) {
            if (c.isValid()) {
                map[c.openTime] = c
            }
        }
        return map.values.sortedBy { it.openTime }.takeLast(MAX_CANDLES_IN_MEMORY)
    }
}
