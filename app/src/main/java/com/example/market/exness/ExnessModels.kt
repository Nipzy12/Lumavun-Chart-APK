package com.example.market.exness

import com.example.market.candle.InstrumentCategory
import com.example.market.candle.MarketProvider
import com.example.market.candle.inferInstrumentCategory
import com.example.market.rest.ExchangeSymbolInfo
import java.util.Locale

/**
 * Exness Market Data & Simulation Configuration.
 * Note: Strictly market-data and simulation only.
 * Private trading credentials and real order execution are prohibited.
 * Backend-mediated flow uses gateway proxy URL and optional session token.
 */
data class ExnessConfig(
    val gatewayUrl: String = "",
    val sessionToken: String = "",
    val accountTier: String = "STANDARD", // STANDARD, RAW_SPREAD, PRO, ZERO
    val serverEnvironment: String = "DEMO", // DEMO or REAL
    val isEnabled: Boolean = false,
    val lastUpdateTimestamp: Long = 0L
) {
    val isConfigured: Boolean get() = gatewayUrl.isNotBlank()
}

/**
 * Normalized Exness Instrument Specification.
 */
data class ExnessInstrument(
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String,
    val displayName: String,
    val category: InstrumentCategory,
    val priceDigits: Int,
    val contractSize: Double,
    val minVolume: Double = 0.01,
    val typicalSpreadPips: Double = 1.0,
    val isSimSupported: Boolean = true
) {
    fun toExchangeSymbolInfo(): ExchangeSymbolInfo {
        return ExchangeSymbolInfo(
            symbol = symbol,
            baseAsset = baseAsset,
            quoteAsset = quoteAsset,
            status = "TRADING",
            category = category,
            provider = MarketProvider.EXNESS,
            displayName = displayName
        )
    }

    fun formatPrice(price: Double): String {
        if (price.isNaN() || price.isInfinite() || price <= 0.0) return "0.00"
        return String.format(Locale.US, "%.${priceDigits}f", price)
    }
}

object ExnessCatalog {
    val INSTRUMENTS = listOf(
        // Forex Majors & Crosses
        ExnessInstrument("EURUSD", "EUR", "USD", "Euro / US Dollar", InstrumentCategory.FOREX, priceDigits = 5, contractSize = 100_000.0, typicalSpreadPips = 0.6),
        ExnessInstrument("GBPUSD", "GBP", "USD", "British Pound / US Dollar", InstrumentCategory.FOREX, priceDigits = 5, contractSize = 100_000.0, typicalSpreadPips = 0.9),
        ExnessInstrument("USDJPY", "USD", "JPY", "US Dollar / Japanese Yen", InstrumentCategory.FOREX, priceDigits = 3, contractSize = 100_000.0, typicalSpreadPips = 0.8),
        ExnessInstrument("AUDUSD", "AUD", "USD", "Australian Dollar / US Dollar", InstrumentCategory.FOREX, priceDigits = 5, contractSize = 100_000.0, typicalSpreadPips = 0.9),
        ExnessInstrument("USDCAD", "USD", "CAD", "US Dollar / Canadian Dollar", InstrumentCategory.FOREX, priceDigits = 5, contractSize = 100_000.0, typicalSpreadPips = 1.0),
        ExnessInstrument("USDCHF", "USD", "CHF", "US Dollar / Swiss Franc", InstrumentCategory.FOREX, priceDigits = 5, contractSize = 100_000.0, typicalSpreadPips = 1.1),
        ExnessInstrument("NZDUSD", "NZD", "USD", "New Zealand Dollar / US Dollar", InstrumentCategory.FOREX, priceDigits = 5, contractSize = 100_000.0, typicalSpreadPips = 1.2),
        ExnessInstrument("EURJPY", "EUR", "JPY", "Euro / Japanese Yen", InstrumentCategory.FOREX, priceDigits = 3, contractSize = 100_000.0, typicalSpreadPips = 1.0),
        ExnessInstrument("GBPJPY", "GBP", "JPY", "British Pound / Japanese Yen", InstrumentCategory.FOREX, priceDigits = 3, contractSize = 100_000.0, typicalSpreadPips = 1.4),
        ExnessInstrument("EURGBP", "EUR", "GBP", "Euro / British Pound", InstrumentCategory.FOREX, priceDigits = 5, contractSize = 100_000.0, typicalSpreadPips = 0.8),

        // Metals & Commodities
        ExnessInstrument("XAUUSD", "XAU", "USD", "Gold / US Dollar", InstrumentCategory.COMMODITIES, priceDigits = 2, contractSize = 100.0, typicalSpreadPips = 1.2),
        ExnessInstrument("XAGUSD", "XAG", "USD", "Silver / US Dollar", InstrumentCategory.COMMODITIES, priceDigits = 3, contractSize = 5_000.0, typicalSpreadPips = 1.8),
        ExnessInstrument("XPTUSD", "XPT", "USD", "Platinum / US Dollar", InstrumentCategory.COMMODITIES, priceDigits = 2, contractSize = 100.0, typicalSpreadPips = 2.5),
        ExnessInstrument("USOIL", "USOIL", "USD", "WTI Crude Oil", InstrumentCategory.COMMODITIES, priceDigits = 2, contractSize = 1_000.0, typicalSpreadPips = 1.5),

        // Crypto CFDs
        ExnessInstrument("BTCUSD", "BTC", "USD", "Bitcoin / US Dollar", InstrumentCategory.CRYPTO, priceDigits = 2, contractSize = 1.0, typicalSpreadPips = 3.0),
        ExnessInstrument("ETHUSD", "ETH", "USD", "Ethereum / US Dollar", InstrumentCategory.CRYPTO, priceDigits = 2, contractSize = 1.0, typicalSpreadPips = 2.5),
        ExnessInstrument("SOLUSD", "SOL", "USD", "Solana / US Dollar", InstrumentCategory.CRYPTO, priceDigits = 2, contractSize = 1.0, typicalSpreadPips = 2.0),
        ExnessInstrument("XRPUSD", "XRP", "USD", "XRP / US Dollar", InstrumentCategory.CRYPTO, priceDigits = 4, contractSize = 1.0, typicalSpreadPips = 1.5),

        // Indices
        ExnessInstrument("US30", "US30", "USD", "Dow Jones 30", InstrumentCategory.INDICES, priceDigits = 2, contractSize = 1.0, typicalSpreadPips = 1.8),
        ExnessInstrument("US500", "US500", "USD", "S&P 500 Index", InstrumentCategory.INDICES, priceDigits = 2, contractSize = 1.0, typicalSpreadPips = 0.5),
        ExnessInstrument("USTEC", "USTEC", "USD", "Nasdaq 100 Index", InstrumentCategory.INDICES, priceDigits = 2, contractSize = 1.0, typicalSpreadPips = 1.2),
        ExnessInstrument("GER40", "GER40", "EUR", "DAX 40 Index", InstrumentCategory.INDICES, priceDigits = 2, contractSize = 1.0, typicalSpreadPips = 1.0)
    )

    private val instrumentMap = INSTRUMENTS.associateBy { it.symbol.uppercase(Locale.US) }

    fun findInstrument(symbol: String): ExnessInstrument? {
        val clean = symbol.trim().uppercase(Locale.US).replace("/", "").replace("-", "").removePrefix("EXNESS:")
        return instrumentMap[clean]
    }

    fun getPriceDigits(symbol: String): Int {
        return findInstrument(symbol)?.priceDigits ?: when {
            symbol.endsWith("JPY") -> 3
            symbol.startsWith("XAG") -> 3
            symbol in setOf("BTCUSD", "ETHUSD", "XAUUSD", "US30", "US500", "USTEC", "GER40") -> 2
            symbol.length == 6 && inferInstrumentCategory(symbol) == InstrumentCategory.FOREX -> 5
            else -> 2
        }
    }

    fun getAllSymbolInfos(): List<ExchangeSymbolInfo> {
        return INSTRUMENTS.map { it.toExchangeSymbolInfo() }
    }
}
