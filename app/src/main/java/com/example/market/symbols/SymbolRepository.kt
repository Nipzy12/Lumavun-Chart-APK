package com.example.market.symbols

import com.example.market.biquote.BiQuoteClient
import com.example.market.candle.InstrumentCategory
import com.example.market.candle.MarketProvider
import com.example.market.candle.Ticker24h
import com.example.market.candle.inferInstrumentCategory
import com.example.market.candle.inferMarketProvider
import com.example.market.rest.BinanceRestClient
import com.example.market.rest.ExchangeSymbolInfo
import com.example.market.rest.MarketApiResult
import com.example.storage.watchlist.CachedSymbolEntity
import com.example.storage.watchlist.TerminalDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

sealed class SymbolValidationOutcome {
    data class Valid(val symbolInfo: ExchangeSymbolInfo) : SymbolValidationOutcome()
    data class InvalidSymbol(val message: String) : SymbolValidationOutcome()
    data class ApiError(val message: String) : SymbolValidationOutcome()
}

class SymbolRepository(
    private val dao: TerminalDao,
    private val restClient: BinanceRestClient,
    private val biQuoteClient: BiQuoteClient = BiQuoteClient()
) {
    companion object {
        val DEFAULT_PAIRS = listOf(
            "BTCUSDT",
            "ETHUSDT",
            "BNBUSDT",
            "SOLUSDT",
            "XRPUSDT",
            "DOGEUSDT"
        )

        val POPULAR_SEED_SYMBOLS = listOf(
            ExchangeSymbolInfo("BTCUSDT", "BTC", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Bitcoin / Tether"),
            ExchangeSymbolInfo("ETHUSDT", "ETH", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Ethereum / Tether"),
            ExchangeSymbolInfo("BNBUSDT", "BNB", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "BNB / Tether"),
            ExchangeSymbolInfo("SOLUSDT", "SOL", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Solana / Tether"),
            ExchangeSymbolInfo("XRPUSDT", "XRP", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "XRP / Tether"),
            ExchangeSymbolInfo("DOGEUSDT", "DOGE", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Dogecoin / Tether"),
            ExchangeSymbolInfo("ADAUSDT", "ADA", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Cardano / Tether"),
            ExchangeSymbolInfo("AVAXUSDT", "AVAX", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Avalanche / Tether"),
            ExchangeSymbolInfo("LINKUSDT", "LINK", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Chainlink / Tether"),
            ExchangeSymbolInfo("DOTUSDT", "DOT", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Polkadot / Tether"),
            ExchangeSymbolInfo("TRXUSDT", "TRX", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "TRON / Tether"),
            ExchangeSymbolInfo("TONUSDT", "TON", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Toncoin / Tether"),
            ExchangeSymbolInfo("SUIUSDT", "SUI", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Sui / Tether"),
            ExchangeSymbolInfo("LTCUSDT", "LTC", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Litecoin / Tether"),
            ExchangeSymbolInfo("NEARUSDT", "NEAR", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "NEAR Protocol / Tether"),
            ExchangeSymbolInfo("UNIUSDT", "UNI", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Uniswap / Tether"),
            ExchangeSymbolInfo("APTUSDT", "APT", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Aptos / Tether"),
            ExchangeSymbolInfo("ARBUSDT", "ARB", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Arbitrum / Tether"),
            ExchangeSymbolInfo("OPUSDT", "OP", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Optimism / Tether"),
            ExchangeSymbolInfo("INJUSDT", "INJ", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Injective / Tether"),
            ExchangeSymbolInfo("ATOMUSDT", "ATOM", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Cosmos / Tether"),
            ExchangeSymbolInfo("PEPEUSDT", "PEPE", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Pepe / Tether"),
            ExchangeSymbolInfo("1000PEPEUSDT", "1000PEPE", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "1000PEPE / Tether"),
            ExchangeSymbolInfo("SHIBUSDT", "SHIB", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Shiba Inu / Tether"),
            ExchangeSymbolInfo("FETUSDT", "FET", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Artificial Superintelligence / Tether"),
            ExchangeSymbolInfo("RENDERUSDT", "RENDER", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Render / Tether"),
            ExchangeSymbolInfo("AAVEUSDT", "AAVE", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Aave / Tether"),
            ExchangeSymbolInfo("SEIUSDT", "SEI", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Sei / Tether"),
            ExchangeSymbolInfo("TIAUSDT", "TIA", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Celestia / Tether"),
            ExchangeSymbolInfo("WIFUSDT", "WIF", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "dogwifhat / Tether"),
            ExchangeSymbolInfo("BONKUSDT", "BONK", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Bonk / Tether"),
            ExchangeSymbolInfo("PENDLEUSDT", "PENDLE", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Pendle / Tether"),
            ExchangeSymbolInfo("ONDOUSDT", "ONDO", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Ondo / Tether"),
            ExchangeSymbolInfo("JUPUSDT", "JUP", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Jupiter / Tether"),
            ExchangeSymbolInfo("ENAUSDT", "ENA", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Ethena / Tether"),
            ExchangeSymbolInfo("TAOUSDT", "TAO", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Bittensor / Tether"),
            ExchangeSymbolInfo("MKRUSDT", "MKR", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Maker / Tether"),
            ExchangeSymbolInfo("LDOUSDT", "LDO", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Lido DAO / Tether"),
            ExchangeSymbolInfo("FILUSDT", "FIL", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Filecoin / Tether"),
            ExchangeSymbolInfo("ICPUSDT", "ICP", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Internet Computer / Tether"),
            ExchangeSymbolInfo("ETCUSDT", "ETC", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Ethereum Classic / Tether"),
            ExchangeSymbolInfo("BCHUSDT", "BCH", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Bitcoin Cash / Tether"),
            ExchangeSymbolInfo("HBARUSDT", "HBAR", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Hedera / Tether"),
            ExchangeSymbolInfo("XLMUSDT", "XLM", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Stellar / Tether"),
            ExchangeSymbolInfo("ALGOUSDT", "ALGO", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Algorand / Tether"),
            ExchangeSymbolInfo("VETUSDT", "VET", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "VeChain / Tether"),
            ExchangeSymbolInfo("SANDUSDT", "SAND", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "The Sandbox / Tether"),
            ExchangeSymbolInfo("MANAUSDT", "MANA", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Decentraland / Tether"),
            ExchangeSymbolInfo("GALAUSDT", "GALA", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Gala / Tether"),
            ExchangeSymbolInfo("CRVUSDT", "CRV", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "Curve DAO / Tether"),
            ExchangeSymbolInfo("RUNEUSDT", "RUNE", "USDT", "TRADING", InstrumentCategory.CRYPTO, MarketProvider.BINANCE, "THORChain / Tether")
        )

        val BIQUOTE_SEED_SYMBOLS = listOf(
            // Forex
            ExchangeSymbolInfo("EURUSD", "EUR", "USD", "TRADING", InstrumentCategory.FOREX, MarketProvider.BIQUOTE, "Euro / US Dollar"),
            ExchangeSymbolInfo("GBPUSD", "GBP", "USD", "TRADING", InstrumentCategory.FOREX, MarketProvider.BIQUOTE, "British Pound / US Dollar"),
            ExchangeSymbolInfo("USDJPY", "USD", "JPY", "TRADING", InstrumentCategory.FOREX, MarketProvider.BIQUOTE, "US Dollar / Japanese Yen"),
            ExchangeSymbolInfo("AUDUSD", "AUD", "USD", "TRADING", InstrumentCategory.FOREX, MarketProvider.BIQUOTE, "Australian Dollar / US Dollar"),
            ExchangeSymbolInfo("USDCAD", "USD", "CAD", "TRADING", InstrumentCategory.FOREX, MarketProvider.BIQUOTE, "US Dollar / Canadian Dollar"),
            ExchangeSymbolInfo("USDCHF", "USD", "CHF", "TRADING", InstrumentCategory.FOREX, MarketProvider.BIQUOTE, "US Dollar / Swiss Franc"),
            ExchangeSymbolInfo("NZDUSD", "NZD", "USD", "TRADING", InstrumentCategory.FOREX, MarketProvider.BIQUOTE, "New Zealand Dollar / US Dollar"),
            ExchangeSymbolInfo("EURJPY", "EUR", "JPY", "TRADING", InstrumentCategory.FOREX, MarketProvider.BIQUOTE, "Euro / Japanese Yen"),
            ExchangeSymbolInfo("GBPJPY", "GBP", "JPY", "TRADING", InstrumentCategory.FOREX, MarketProvider.BIQUOTE, "British Pound / Japanese Yen"),
            // Metals / Commodities
            ExchangeSymbolInfo("XAUUSD", "XAU", "USD", "TRADING", InstrumentCategory.COMMODITIES, MarketProvider.BIQUOTE, "Gold Spot / US Dollar"),
            ExchangeSymbolInfo("XAGUSD", "XAG", "USD", "TRADING", InstrumentCategory.COMMODITIES, MarketProvider.BIQUOTE, "Silver Spot / US Dollar"),
            ExchangeSymbolInfo("WTI", "WTI", "USD", "TRADING", InstrumentCategory.COMMODITIES, MarketProvider.BIQUOTE, "WTI Crude Oil"),
            ExchangeSymbolInfo("BRENT", "BRENT", "USD", "TRADING", InstrumentCategory.COMMODITIES, MarketProvider.BIQUOTE, "Brent Crude Oil"),
            // Stocks
            ExchangeSymbolInfo("AAPL", "AAPL", "USD", "TRADING", InstrumentCategory.STOCKS, MarketProvider.BIQUOTE, "Apple Inc."),
            ExchangeSymbolInfo("NVDA", "NVDA", "USD", "TRADING", InstrumentCategory.STOCKS, MarketProvider.BIQUOTE, "NVIDIA Corporation"),
            ExchangeSymbolInfo("TSLA", "TSLA", "USD", "TRADING", InstrumentCategory.STOCKS, MarketProvider.BIQUOTE, "Tesla, Inc."),
            ExchangeSymbolInfo("MSFT", "MSFT", "USD", "TRADING", InstrumentCategory.STOCKS, MarketProvider.BIQUOTE, "Microsoft Corporation"),
            ExchangeSymbolInfo("AMZN", "AMZN", "USD", "TRADING", InstrumentCategory.STOCKS, MarketProvider.BIQUOTE, "Amazon.com, Inc."),
            ExchangeSymbolInfo("META", "META", "USD", "TRADING", InstrumentCategory.STOCKS, MarketProvider.BIQUOTE, "Meta Platforms, Inc."),
            ExchangeSymbolInfo("GOOGL", "GOOGL", "USD", "TRADING", InstrumentCategory.STOCKS, MarketProvider.BIQUOTE, "Alphabet Inc."),
            // Indices
            ExchangeSymbolInfo("US500", "US500", "PTS", "TRADING", InstrumentCategory.INDICES, MarketProvider.BIQUOTE, "S&P 500 Index"),
            ExchangeSymbolInfo("USTEC", "USTEC", "PTS", "TRADING", InstrumentCategory.INDICES, MarketProvider.BIQUOTE, "Nasdaq 100 Index"),
            ExchangeSymbolInfo("US30", "US30", "PTS", "TRADING", InstrumentCategory.INDICES, MarketProvider.BIQUOTE, "Dow Jones 30 Index"),
            ExchangeSymbolInfo("GER40", "GER40", "PTS", "TRADING", InstrumentCategory.INDICES, MarketProvider.BIQUOTE, "DAX 40 Index"),
            ExchangeSymbolInfo("UK100", "UK100", "PTS", "TRADING", InstrumentCategory.INDICES, MarketProvider.BIQUOTE, "FTSE 100 Index"),
            ExchangeSymbolInfo("JPN225", "JPN225", "PTS", "TRADING", InstrumentCategory.INDICES, MarketProvider.BIQUOTE, "Nikkei 225 Index")
        )

        val ALL_SEED_SYMBOLS: List<ExchangeSymbolInfo> = POPULAR_SEED_SYMBOLS + BIQUOTE_SEED_SYMBOLS +
            com.example.market.exness.ExnessCatalog.getAllSymbolInfos()

        /**
         * Normalizes a search query string (e.g., " btc/usdt " -> "BTCUSDT", "sol" -> "SOL").
         */
        fun normalizeSearchQuery(rawQuery: String): String {
            return rawQuery.trim()
                .uppercase(Locale.US)
                .replace("/", "")
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "")
        }

        /**
         * Normalizes user symbol input into a canonical Binance USDT symbol (e.g., "btc" -> "BTCUSDT",
         * "ETH/USDT" -> "ETHUSDT", "link-usdt" -> "LINKUSDT").
         * Returns empty string if the input contains invalid characters or an unsupported explicit quote asset.
         */
        fun normalizeUsdtSymbolInput(rawInput: String): String {
            val trimmed = rawInput.trim().uppercase(Locale.US)
            if (trimmed.isEmpty()) return ""

            if (trimmed.contains("/") || trimmed.contains("-") || trimmed.contains("_")) {
                val parts = trimmed.split("/", "-", "_").map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size == 2) {
                    val base = parts[0]
                    val quote = parts[1]
                    if (quote != "USDT") return ""
                    if (!base.matches(Regex("^[A-Z0-9]{1,15}$")) || base == "USDT") return ""
                    return "${base}USDT"
                }
            }

            val cleaned = trimmed.replace(" ", "")
            if (!cleaned.matches(Regex("^[A-Z0-9]{2,20}$"))) return ""
            if (cleaned == "USDT") return ""

            return if (cleaned.endsWith("USDT")) {
                val base = cleaned.removeSuffix("USDT")
                if (base.isEmpty() || !base.matches(Regex("^[A-Z0-9]{1,15}$"))) "" else cleaned
            } else {
                // Reject if user explicitly typed a non-USDT quote suffix like BTCBUSD or ETHUSDC
                val unsupportedQuotes = listOf("USDC", "FDUSD", "BUSD", "TUSD", "EUR")
                if (unsupportedQuotes.any { cleaned.endsWith(it) && cleaned.length > it.length + 1 }) {
                    return ""
                }
                "${cleaned}USDT"
            }
        }

        /**
         * Normalizes any multi-market symbol input (Crypto USDT, Forex, Metals, Stocks, Indices).
         */
        fun normalizeMultiMarketSymbolInput(
            rawInput: String,
            preferredCategory: InstrumentCategory = InstrumentCategory.ALL
        ): String {
            val trimmed = rawInput.trim().uppercase(Locale.US)
            if (trimmed.isEmpty()) return ""
            val cleaned = trimmed.replace("/", "").replace("-", "").replace("_", "").replace(" ", "")
            if (!cleaned.matches(Regex("^[A-Z0-9.]{1,20}$"))) return ""

            // Check if it matches a known BiQuote seed or non-crypto instrument directly
            if (BIQUOTE_SEED_SYMBOLS.any { it.symbol.equals(cleaned, ignoreCase = true) }) {
                return cleaned
            }
            val inferredCat = inferInstrumentCategory(cleaned)
            if (preferredCategory == InstrumentCategory.CRYPTO) {
                return normalizeUsdtSymbolInput(rawInput)
            }
            if (preferredCategory != InstrumentCategory.ALL && preferredCategory != InstrumentCategory.CRYPTO) {
                return cleaned
            }
            if (inferredCat != InstrumentCategory.CRYPTO) {
                return cleaned
            }
            if (cleaned.endsWith("USDT")) {
                return cleaned
            }
            // If user typed a known crypto base (or 2-6 chars while in ALL/CRYPTO), check if it's in crypto seeds
            if (POPULAR_SEED_SYMBOLS.any { it.baseAsset.equals(cleaned, ignoreCase = true) }) {
                return "${cleaned}USDT"
            }
            return cleaned
        }
    }

    fun searchSymbols(
        query: String,
        categoryFilter: InstrumentCategory = InstrumentCategory.ALL,
        providerFilter: MarketProvider? = null
    ): Flow<List<ExchangeSymbolInfo>> {
        val clean = normalizeSearchQuery(query)
        val baseSearch = if (clean.endsWith("USDT") && clean.length > 4) clean.removeSuffix("USDT") else clean
        return dao.searchCachedSymbols(
            pattern = "%$baseSearch%",
            exactQuery = baseSearch,
            prefixPattern = "$baseSearch%"
        ).map { entities ->
            val mappedFromDb = entities.map { e ->
                val cat = InstrumentCategory.fromCode(e.category).let {
                    if (it == InstrumentCategory.ALL) inferInstrumentCategory(e.symbol) else it
                }
                val prov = MarketProvider.fromCode(e.provider).let {
                    if (e.provider.isBlank()) inferMarketProvider(e.symbol, cat) else it
                }
                ExchangeSymbolInfo(
                    symbol = e.symbol,
                    baseAsset = e.baseAsset,
                    quoteAsset = e.quoteAsset,
                    status = "TRADING",
                    category = cat,
                    provider = prov,
                    displayName = e.displayName
                )
            }

            // Combine with seed catalog so all categories are immediately searchable even before full sync
            val combined = (mappedFromDb + ALL_SEED_SYMBOLS)
                .distinctBy { it.symbol.uppercase(Locale.US) }
                .filter { info ->
                    val matchesCategory = categoryFilter == InstrumentCategory.ALL || info.category == categoryFilter
                    val matchesProvider = providerFilter == null || info.provider == providerFilter
                    val matchesQuery = clean.isEmpty() ||
                        info.symbol.contains(clean, ignoreCase = true) ||
                        info.baseAsset.contains(baseSearch, ignoreCase = true) ||
                        info.displayName.contains(query.trim(), ignoreCase = true)
                    matchesCategory && matchesProvider && matchesQuery
                }
            combined
        }
    }

    /**
     * Validates a multi-market symbol (Crypto via Binance, or Forex/Metals/Stocks/Indices via BiQuote).
     */
    suspend fun validateAndResolveMultiMarketSymbol(
        rawInput: String,
        preferredCategory: InstrumentCategory = InstrumentCategory.ALL
    ): SymbolValidationOutcome {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return SymbolValidationOutcome.InvalidSymbol("Masukkan symbol terlebih dahulu.")
        }

        val candidate = normalizeMultiMarketSymbolInput(trimmed, preferredCategory)
        if (candidate.isEmpty()) {
            return SymbolValidationOutcome.InvalidSymbol(
                "Symbol '$rawInput' tidak valid."
            )
        }

        // 1. Check local cache first
        val cached = dao.getCachedSymbolByCode(candidate)
        if (cached != null) {
            val cat = InstrumentCategory.fromCode(cached.category)
            val prov = MarketProvider.fromCode(cached.provider)
            return SymbolValidationOutcome.Valid(
                ExchangeSymbolInfo(
                    symbol = cached.symbol,
                    baseAsset = cached.baseAsset,
                    quoteAsset = cached.quoteAsset,
                    status = "TRADING",
                    category = cat,
                    provider = prov,
                    displayName = cached.displayName
                )
            )
        }

        // 2. Check verified seed catalog
        val seedMatch = ALL_SEED_SYMBOLS.firstOrNull {
            it.symbol.equals(candidate, ignoreCase = true) ||
                (preferredCategory != InstrumentCategory.CRYPTO && it.baseAsset.equals(candidate, ignoreCase = true) && it.provider == MarketProvider.BIQUOTE)
        }
        if (seedMatch != null) {
            dao.insertCachedSymbols(
                listOf(
                    CachedSymbolEntity(
                        symbol = seedMatch.symbol,
                        baseAsset = seedMatch.baseAsset,
                        quoteAsset = seedMatch.quoteAsset,
                        updatedAt = System.currentTimeMillis(),
                        category = seedMatch.category.code,
                        provider = seedMatch.provider.code,
                        displayName = seedMatch.displayName
                    )
                )
            )
            return SymbolValidationOutcome.Valid(seedMatch)
        }

        // 3. If preferred category is non-crypto or symbol looks like non-USDT instrument, validate on BiQuote first
        val shouldTryBiQuoteFirst = preferredCategory in setOf(
            InstrumentCategory.FOREX,
            InstrumentCategory.COMMODITIES,
            InstrumentCategory.STOCKS,
            InstrumentCategory.INDICES
        ) || (!candidate.endsWith("USDT") && inferInstrumentCategory(candidate) != InstrumentCategory.CRYPTO)

        if (shouldTryBiQuoteFirst) {
            when (val bqRes = biQuoteClient.validateSymbol(candidate, preferredCategory)) {
                is MarketApiResult.Success -> {
                    val info = bqRes.data
                    dao.insertCachedSymbols(
                        listOf(
                            CachedSymbolEntity(
                                symbol = info.symbol,
                                baseAsset = info.baseAsset,
                                quoteAsset = info.quoteAsset,
                                updatedAt = System.currentTimeMillis(),
                                category = info.category.code,
                                provider = MarketProvider.BIQUOTE.code,
                                displayName = info.displayName
                            )
                        )
                    )
                    return SymbolValidationOutcome.Valid(info)
                }
                is MarketApiResult.Error -> {
                    if (bqRes.isOffline || bqRes.isRateLimited) {
                        return SymbolValidationOutcome.ApiError("Gagal memvalidasi $candidate: ${bqRes.message}")
                    }
                    if (preferredCategory != InstrumentCategory.ALL) {
                        return SymbolValidationOutcome.InvalidSymbol(
                            "Instrumen '$rawInput' tidak ditemukan di katalog ${preferredCategory.label} BiQuote."
                        )
                    }
                }
            }
        }

        // 4. Try Binance USDT validation
        val usdtOutcome = validateAndResolveUsdtSymbol(trimmed)
        if (usdtOutcome is SymbolValidationOutcome.Valid) {
            return usdtOutcome
        }

        // 5. Fallback to BiQuote search/validation if not yet tried
        if (!shouldTryBiQuoteFirst) {
            when (val bqRes = biQuoteClient.validateSymbol(trimmed, preferredCategory)) {
                is MarketApiResult.Success -> {
                    val info = bqRes.data
                    dao.insertCachedSymbols(
                        listOf(
                            CachedSymbolEntity(
                                symbol = info.symbol,
                                baseAsset = info.baseAsset,
                                quoteAsset = info.quoteAsset,
                                updatedAt = System.currentTimeMillis(),
                                category = info.category.code,
                                provider = MarketProvider.BIQUOTE.code,
                                displayName = info.displayName
                            )
                        )
                    )
                    return SymbolValidationOutcome.Valid(info)
                }
                is MarketApiResult.Error -> {}
            }
        }

        return usdtOutcome
    }

    /**
     * Validates that a symbol is an active USDT trading pair on Binance before adding to Watchlist.
     */
    suspend fun validateAndResolveUsdtSymbol(rawInput: String): SymbolValidationOutcome {
        val normalized = normalizeUsdtSymbolInput(rawInput)
        if (normalized.isEmpty()) {
            return SymbolValidationOutcome.InvalidSymbol(
                "Symbol '$rawInput' tidak valid. Gunakan pair USDT aktif (contoh: BTC, ETH, SOL) atau instrumen Forex/Metals/Stocks/Indices."
            )
        }

        // 1. Check local cache / verified seed first
        val cached = dao.getCachedSymbolByCode(normalized)
        if (cached != null && cached.quoteAsset == "USDT") {
            return SymbolValidationOutcome.Valid(
                ExchangeSymbolInfo(
                    symbol = cached.symbol,
                    baseAsset = cached.baseAsset,
                    quoteAsset = "USDT",
                    status = "TRADING",
                    category = InstrumentCategory.CRYPTO,
                    provider = MarketProvider.BINANCE,
                    displayName = cached.displayName
                )
            )
        }

        val seedMatch = POPULAR_SEED_SYMBOLS.firstOrNull { it.symbol.equals(normalized, ignoreCase = true) }
        if (seedMatch != null) {
            dao.insertCachedSymbols(
                listOf(
                    CachedSymbolEntity(
                        symbol = seedMatch.symbol,
                        baseAsset = seedMatch.baseAsset,
                        quoteAsset = "USDT",
                        updatedAt = System.currentTimeMillis(),
                        category = InstrumentCategory.CRYPTO.code,
                        provider = MarketProvider.BINANCE.code,
                        displayName = seedMatch.displayName
                    )
                )
            )
            return SymbolValidationOutcome.Valid(seedMatch)
        }

        // 2. Validate against live Binance /api/v3/exchangeInfo?symbol=...
        return when (val liveCheck = restClient.validateUsdtTradingSymbol(normalized)) {
            is MarketApiResult.Success -> {
                val info = liveCheck.data
                dao.insertCachedSymbols(
                    listOf(
                        CachedSymbolEntity(
                            symbol = info.symbol,
                            baseAsset = info.baseAsset,
                            quoteAsset = "USDT",
                            updatedAt = System.currentTimeMillis(),
                            category = InstrumentCategory.CRYPTO.code,
                            provider = MarketProvider.BINANCE.code,
                            displayName = info.displayName
                        )
                    )
                )
                SymbolValidationOutcome.Valid(info)
            }
            is MarketApiResult.Error -> {
                if (liveCheck.isOffline || liveCheck.isRateLimited) {
                    SymbolValidationOutcome.ApiError(
                        "Gagal memvalidasi $normalized: ${liveCheck.message}"
                    )
                } else {
                    SymbolValidationOutcome.InvalidSymbol(
                        "Symbol ${normalized.removeSuffix("USDT")}/USDT tidak ditemukan atau tidak aktif di market Binance USDT."
                    )
                }
            }
        }
    }

    /**
     * Enriches local cached symbols from dynamic tickers so all active market pairs are searchable immediately.
     */
    suspend fun syncSymbolsFromTickers(tickers: List<Ticker24h>) {
        if (tickers.isEmpty()) return
        val now = System.currentTimeMillis()
        val entities = tickers.mapNotNull { t ->
            val sym = t.symbol.uppercase(Locale.US)
            if (sym.isEmpty()) return@mapNotNull null
            if (t.provider == MarketProvider.BINANCE) {
                if (!sym.endsWith("USDT") || sym.length <= 4) return@mapNotNull null
                val base = sym.removeSuffix("USDT")
                CachedSymbolEntity(
                    symbol = sym,
                    baseAsset = base,
                    quoteAsset = "USDT",
                    updatedAt = now,
                    category = InstrumentCategory.CRYPTO.code,
                    provider = MarketProvider.BINANCE.code,
                    displayName = t.displayName
                )
            } else {
                val (base, quote) = BiQuoteClient.splitBaseQuote(sym, t.category)
                CachedSymbolEntity(
                    symbol = sym,
                    baseAsset = base,
                    quoteAsset = quote,
                    updatedAt = now,
                    category = t.category.code,
                    provider = MarketProvider.BIQUOTE.code,
                    displayName = t.displayName
                )
            }
        }
        if (entities.isNotEmpty()) {
            dao.insertCachedSymbols(entities)
        }
    }

    /**
     * Ensures local symbol cache is populated for both Binance (Crypto USDT) and BiQuote (Forex, Metals, Stocks, Indices).
     */
    suspend fun ensureSymbolsCached(forceRefresh: Boolean = false): Int {
        runCatching { dao.deleteNonUsdtCachedSymbols() }
        val count = dao.getCachedSymbolCount()
        if (count < ALL_SEED_SYMBOLS.size) {
            dao.insertCachedSymbols(
                ALL_SEED_SYMBOLS.map {
                    CachedSymbolEntity(
                        symbol = it.symbol,
                        baseAsset = it.baseAsset,
                        quoteAsset = it.quoteAsset,
                        updatedAt = 0L,
                        category = it.category.code,
                        provider = it.provider.code,
                        displayName = it.displayName
                    )
                }
            )
        }

        val lastUpdated = dao.getLastSymbolCacheTime() ?: 0L
        val isStale = (System.currentTimeMillis() - lastUpdated) > 24 * 60 * 60 * 1000L

        if (forceRefresh || count <= ALL_SEED_SYMBOLS.size || isStale) {
            val now = System.currentTimeMillis()
            // 1. Sync Binance USDT spot symbols
            when (val res = restClient.fetchExchangeSymbols()) {
                is MarketApiResult.Success -> {
                    val entities = res.data
                        .filter { it.status == "TRADING" && it.quoteAsset == "USDT" }
                        .map {
                            CachedSymbolEntity(
                                symbol = it.symbol,
                                baseAsset = it.baseAsset,
                                quoteAsset = "USDT",
                                updatedAt = now,
                                category = InstrumentCategory.CRYPTO.code,
                                provider = MarketProvider.BINANCE.code,
                                displayName = it.displayName
                            )
                        }
                    if (entities.isNotEmpty()) {
                        dao.insertCachedSymbols(entities)
                    }
                }
                is MarketApiResult.Error -> {}
            }

            // 2. Sync BiQuote multi-market catalogs (Forex, Commodity/Metals, Stock, Index)
            val biQuoteCategories = listOf(
                InstrumentCategory.FOREX,
                InstrumentCategory.COMMODITIES,
                InstrumentCategory.STOCKS,
                InstrumentCategory.INDICES
            )
            for (cat in biQuoteCategories) {
                when (val bqRes = biQuoteClient.fetchSymbolsByCategory(cat)) {
                    is MarketApiResult.Success -> {
                        val bqEntities = bqRes.data.map {
                            CachedSymbolEntity(
                                symbol = it.symbol,
                                baseAsset = it.baseAsset,
                                quoteAsset = it.quoteAsset,
                                updatedAt = now,
                                category = it.category.code,
                                provider = MarketProvider.BIQUOTE.code,
                                displayName = it.displayName
                            )
                        }
                        if (bqEntities.isNotEmpty()) {
                            dao.insertCachedSymbols(bqEntities)
                        }
                    }
                    is MarketApiResult.Error -> {}
                }
            }
        }
        return dao.getCachedSymbolCount()
    }
}
