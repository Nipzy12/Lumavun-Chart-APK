package com.example.market.calendar

/**
 * Directional bias for currencies and currency pairs.
 */
enum class BiasDirection(val symbol: String, val label: String) {
    BULLISH("↗", "Bullish"),
    BEARISH("↘", "Bearish"),
    NEUTRAL("↔", "Netral"),
    WAITING_DATA("⏱", "Menunggu data")
}

data class PairBiasItem(
    val pair: String,
    val direction: BiasDirection,
    val isInverse: Boolean = false
) {
    val displayLabel: String
        get() = when (direction) {
            BiasDirection.BULLISH -> "$pair ↗ Bullish"
            BiasDirection.BEARISH -> "$pair ↘ Bearish"
            BiasDirection.NEUTRAL -> "$pair ↔ Netral"
            BiasDirection.WAITING_DATA -> "$pair ⏱ Menunggu data"
        }
}

/**
 * Backward compatible badge model for UI rendering.
 */
data class PairBiasBadge(
    val label: String,
    val direction: BiasDirection
)

data class EventBiasResult(
    val currency: String,
    val currencyDirection: BiasDirection,
    val pairBiases: List<PairBiasItem>,
    val badges: List<PairBiasBadge>,
    val summaryText: String,
    val implicationMessage: String,
    val isPendingOrWaiting: Boolean
)

object CurrencyPairBiasEngine {

    /**
     * Set of supported currencies for bias tracking.
     */
    val ALL_WATCHABLE_CURRENCIES = listOf(
        "USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "NZD", "XAU", "OIL"
    )

    val DEFAULT_WATCHED_CURRENCIES = setOf(
        "USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "NZD", "XAU", "OIL"
    )

    /**
     * Determines whether an indicator is inverted (e.g. unemployment where higher is worse for the economy/currency).
     */
    fun isInvertedIndicator(title: String): Boolean {
        val lower = title.lowercase()
        return lower.contains("unemployment") ||
            lower.contains("jobless") ||
            lower.contains("claims") ||
            lower.contains("claimant")
    }

    /**
     * Extracts the first numeric value from a metric string like "4.06 million", "0.5% YoY", "220K".
     */
    fun extractNumber(raw: String): Double? {
        if (raw.isBlank() || raw == "--" || raw.equals("null", ignoreCase = true) || raw.equals("pending", ignoreCase = true)) {
            return null
        }
        val clean = raw.replace(",", "").replace("+", "").trim()
        val regex = Regex("""[-+]?[0-9]*\.?[0-9]+""")
        val match = regex.find(clean) ?: return null
        return match.value.toDoubleOrNull()
    }

    /**
     * Calculates the bias for a high-impact economic event based on Actual vs Forecast.
     * Returns null if the event is not high impact or currency is not in watchedCurrencies.
     */
    fun computeEventBias(
        event: EconomicCalendarEvent,
        watchedCurrencies: Set<String> = DEFAULT_WATCHED_CURRENCIES
    ): EventBiasResult? {
        if (!event.impact.equals("HIGH", ignoreCase = true)) {
            return null
        }

        val ccy = event.currency.uppercase().trim()
        if (watchedCurrencies.isNotEmpty() && !watchedCurrencies.contains(ccy)) {
            return null
        }

        val actualNum = extractNumber(event.actual)
        val forecastNum = extractNumber(event.forecast)
        val previousNum = extractNumber(event.previous)

        val isInverted = isInvertedIndicator(event.title)

        val ccyDirection: BiasDirection = when {
            // Actual not released yet -> waiting for data
            actualNum == null -> BiasDirection.WAITING_DATA

            // Both actual and forecast are present
            forecastNum != null -> when {
                actualNum > forecastNum -> if (isInverted) BiasDirection.BEARISH else BiasDirection.BULLISH
                actualNum < forecastNum -> if (isInverted) BiasDirection.BULLISH else BiasDirection.BEARISH
                else -> BiasDirection.NEUTRAL
            }

            // No forecast available, compare actual vs previous/prior if available
            previousNum != null -> when {
                actualNum > previousNum -> if (isInverted) BiasDirection.BEARISH else BiasDirection.BULLISH
                actualNum < previousNum -> if (isInverted) BiasDirection.BULLISH else BiasDirection.BEARISH
                else -> BiasDirection.NEUTRAL
            }

            else -> BiasDirection.NEUTRAL
        }

        val pairBiases = buildPairBiases(ccy, ccyDirection)
        val badges = pairBiases.map { PairBiasBadge(it.displayLabel, it.direction) }

        val summaryText = if (ccyDirection == BiasDirection.WAITING_DATA) {
            "⚡ Bias: Menunggu rilis data aktual"
        } else {
            "⚡ Bias: " + pairBiases.joinToString(" | ") { it.displayLabel }
        }

        val implicationMessage = buildImplicationMessage(ccy, ccyDirection, isInverted, actualNum != null && forecastNum == null && previousNum != null)

        return EventBiasResult(
            currency = ccy,
            currencyDirection = ccyDirection,
            pairBiases = pairBiases,
            badges = badges,
            summaryText = summaryText,
            implicationMessage = implicationMessage,
            isPendingOrWaiting = ccyDirection == BiasDirection.WAITING_DATA
        )
    }

    /**
     * Maps currency direction to affected pairs according to financial correlation rules:
     * - USD -> BTC/USDT (inverse), XAUUSD (inverse), USDX (direct)
     * - EUR -> EURUSD (direct)
     * - GBP -> GBPUSD (direct)
     * - JPY -> USDJPY (inverse)
     * - AUD -> AUDUSD (direct)
     * - CAD -> USDCAD (inverse)
     * - CHF -> USDCHF (inverse)
     * - NZD -> NZDUSD (direct)
     * - XAU -> XAUUSD (direct)
     * - OIL -> USOIL (direct)
     */
    private fun buildPairBiases(
        currency: String,
        direction: BiasDirection
    ): List<PairBiasItem> {
        val inverseDirection = when (direction) {
            BiasDirection.BULLISH -> BiasDirection.BEARISH
            BiasDirection.BEARISH -> BiasDirection.BULLISH
            BiasDirection.NEUTRAL -> BiasDirection.NEUTRAL
            BiasDirection.WAITING_DATA -> BiasDirection.WAITING_DATA
        }

        return when (currency) {
            "USD" -> listOf(
                PairBiasItem("BTC/USDT", inverseDirection, isInverse = true),
                PairBiasItem("XAUUSD", inverseDirection, isInverse = true),
                PairBiasItem("USDX", direction, isInverse = false)
            )
            "EUR" -> listOf(
                PairBiasItem("EURUSD", direction, isInverse = false)
            )
            "GBP" -> listOf(
                PairBiasItem("GBPUSD", direction, isInverse = false)
            )
            "JPY" -> listOf(
                PairBiasItem("USDJPY", inverseDirection, isInverse = true)
            )
            "AUD" -> listOf(
                PairBiasItem("AUDUSD", direction, isInverse = false)
            )
            "CAD" -> listOf(
                PairBiasItem("USDCAD", inverseDirection, isInverse = true)
            )
            "CHF" -> listOf(
                PairBiasItem("USDCHF", inverseDirection, isInverse = true)
            )
            "NZD" -> listOf(
                PairBiasItem("NZDUSD", direction, isInverse = false)
            )
            "XAU" -> listOf(
                PairBiasItem("XAUUSD", direction, isInverse = false)
            )
            "OIL" -> listOf(
                PairBiasItem("USOIL", direction, isInverse = false)
            )
            else -> listOf(
                PairBiasItem(currency, direction, isInverse = false)
            )
        }
    }

    private fun buildImplicationMessage(
        currency: String,
        direction: BiasDirection,
        isInverted: Boolean,
        comparedAgainstPrevious: Boolean
    ): String {
        if (direction == BiasDirection.WAITING_DATA) {
            return "Menunggu rilis data aktual untuk mengonfirmasi arah bias pasar dan volatilitas."
        }

        val suffix = if (comparedAgainstPrevious) " (analisis vs data prior/sebelumnya)" else ""

        return when (direction) {
            BiasDirection.BULLISH -> when (currency) {
                "USD" -> "Data USD menguat — potensi reli dolar, tekanan jual koreksi pada XAUUSD dan BTC/USDT$suffix."
                "EUR" -> "Data Zona Euro menguat melampaui ekspektasi — potensi momentum bullish bagi pair EURUSD$suffix."
                "GBP" -> "Data ekonomi Inggris positif — katalis penguatan tren bullish untuk pair GBPUSD$suffix."
                "JPY" -> "Penguatan Yen Jepang (JPY) memberikan tekanan bearish korektif terhadap pair USDJPY$suffix."
                "AUD" -> "Data ekonomi Australia solid — dorongan beli tren bullish untuk pair AUDUSD$suffix."
                "CAD" -> "Data ekonomi Kanada menguat — potensi tekanan jual korektif bagi pair USDCAD$suffix."
                "CHF" -> "Safe-haven Swiss Franc (CHF) menguat — potensi tekanan bearish terhadap pair USDCHF$suffix."
                "NZD" -> "Data Selandia Baru melampaui forecast — sentimen bullish bagi pergerakan NZDUSD$suffix."
                "XAU" -> "Sentimen komoditas emas menguat — katalis bullish bagi pergerakan XAUUSD$suffix."
                "OIL" -> "Sentimen energi positif — dorongan kenaikan harga bagi pair USOIL$suffix."
                else -> "Data $currency di atas ekspektasi — potensi penguatan mata uang$suffix."
            }
            BiasDirection.BEARISH -> when (currency) {
                "USD" -> "Data USD melemah — potensi pelemahan dolar, membuka peluang reli bagi XAUUSD dan BTC/USDT$suffix."
                "EUR" -> "Data Zona Euro di bawah ekspektasi — risiko koreksi penurunan bearish bagi pair EURUSD$suffix."
                "GBP" -> "Data ekonomi Inggris melambat — potensi tekanan jual bearish bagi pair GBPUSD$suffix."
                "JPY" -> "Pelemahan Yen Jepang (JPY) membuka ruang dorongan kenaikan bullish bagi pair USDJPY$suffix."
                "AUD" -> "Data Australia di bawah perkiraan — sentimen bearish bagi pergerakan pair AUDUSD$suffix."
                "CAD" -> "Data ekonomi Kanada melunak — potensi dorongan kenaikan bullish bagi pair USDCAD$suffix."
                "CHF" -> "Swiss Franc (CHF) melemah — potensi kenaikan teknikal bagi pair USDCHF$suffix."
                "NZD" -> "Data Selandia Baru meleset — sentimen pelemahan bearish bagi pergerakan NZDUSD$suffix."
                "XAU" -> "Sentimen emas tertekan — potensi koreksi bearish bagi pergerakan XAUUSD$suffix."
                "OIL" -> "Tekanan pasokan/permintaan — potensi pelemahan korektif bagi pair USOIL$suffix."
                else -> "Data $currency di bawah ekspektasi — potensi depresiasi mata uang$suffix."
            }
            BiasDirection.NEUTRAL -> {
                "Data rilis sejalan dengan perkiraan/prior — volatilitas terbatas, pergerakan harga cenderung netral."
            }
            BiasDirection.WAITING_DATA -> {
                "Menunggu rilis data aktual untuk mengonfirmasi arah volatilitas pasar."
            }
        }
    }
}
