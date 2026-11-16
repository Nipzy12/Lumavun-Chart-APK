package com.example.analysis.supportResistance

import com.example.market.candle.Candle
import kotlin.math.abs
import kotlin.math.max

/**
 * A local, deterministic S/R proximity gate for automatic AI requests.
 *
 * Uses the SAME support/resistance levels as TechnicalSnapshotBuilder / chart.
 * This is not a trading signal and never places orders. The configured AI model
 * still has to validate a setup, freshness, risk and reward before any result.
 */
data class SnrAutoOpportunity(
    val type: String = "WAIT",
    val level: Double? = null,
    val reason: String = "No confirmed S/R reaction",
    val shouldAnalyze: Boolean = false
)

object SnrAutoAnalysisEngine {
    fun evaluate(
        candles: List<Candle>,
        supports: List<Double>,
        resistances: List<Double>,
        atr14: Double? = null
    ): SnrAutoOpportunity {
        val confirmed = candles.filter { it.isClosed && it.isValid() }
        if (confirmed.size < 20) return SnrAutoOpportunity(reason = "Insufficient confirmed candles")
        val current = confirmed.last()
        val previous = confirmed[confirmed.lastIndex - 1]
        if (current.close <= 0.0 || !current.close.isFinite()) {
            return SnrAutoOpportunity(reason = "Invalid close")
        }
        val tolerance = max((atr14 ?: 0.0).coerceAtLeast(0.0) * 0.45, current.close * 0.0012)

        val support = supports.asSequence().filter { it.isFinite() && it > 0.0 }
            .minByOrNull { abs(it - current.low) }
        val resistance = resistances.asSequence().filter { it.isFinite() && it > 0.0 }
            .minByOrNull { abs(it - current.high) }

        val supportRejection = support != null &&
            abs(current.low - support) <= tolerance &&
            current.close > support && current.close > current.open
        val supportBreak = support != null &&
            previous.close >= support - tolerance * 0.15 &&
            current.close < support - tolerance * 0.35

        val resistanceRejection = resistance != null &&
            abs(current.high - resistance) <= tolerance &&
            current.close < resistance && current.close < current.open
        val resistanceBreak = resistance != null &&
            previous.close <= resistance + tolerance * 0.15 &&
            current.close > resistance + tolerance * 0.35

        val supportEvent = supportRejection || supportBreak
        val resistanceEvent = resistanceRejection || resistanceBreak
        if (supportEvent && resistanceEvent) {
            return SnrAutoOpportunity(reason = "Conflicting S/R levels in one candle")
        }

        return when {
            supportRejection -> SnrAutoOpportunity(
                type = "SUPPORT_REJECTION", level = support,
                reason = "Confirmed bullish rejection at chart support", shouldAnalyze = true
            )
            resistanceRejection -> SnrAutoOpportunity(
                type = "RESISTANCE_REJECTION", level = resistance,
                reason = "Confirmed bearish rejection at chart resistance", shouldAnalyze = true
            )
            resistanceBreak -> SnrAutoOpportunity(
                type = "RESISTANCE_BREAKOUT", level = resistance,
                reason = "Confirmed close above chart resistance", shouldAnalyze = true
            )
            supportBreak -> SnrAutoOpportunity(
                type = "SUPPORT_BREAKDOWN", level = support,
                reason = "Confirmed close below chart support", shouldAnalyze = true
            )
            else -> SnrAutoOpportunity()
        }
    }
}
