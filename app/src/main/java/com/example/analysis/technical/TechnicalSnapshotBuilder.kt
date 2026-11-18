package com.example.analysis.technical

import com.example.analysis.marketStructure.MarketStructureAnalysis
import com.example.analysis.marketStructure.MarketStructureEngine
import com.example.analysis.marketStructure.StructureState
import com.example.analysis.events.EventRiskAssessment
import com.example.analysis.smc.SmcAnalysisSnapshot
import com.example.analysis.smc.SmcLiquiditySweepEngine
import com.example.analysis.smc.SpreadVolatilityConfig
import com.example.analysis.smc.SweepDetectionConfig
import com.example.analysis.smc.SweepDetectionState
import com.example.analysis.supportResistance.SupportResistanceEngine
import com.example.analysis.supportResistance.SupportResistanceEngine.roundPriceCleanly
import com.example.analysis.supportResistance.SupportResistanceResult
import com.example.chart.indicators.IndicatorSeries
import com.example.chart.indicators.MacdPoint
import com.example.chart.indicators.TechnicalIndicators
import com.example.market.candle.Candle
import com.example.market.candle.Timeframe
import com.example.market.session.TradingSessionConfig
import com.example.market.session.TradingSessionEngine
import com.example.market.status.MarketStatusEvaluation
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Requirement #8: Market Regime Classification based on available price and indicator data.
 * Avoids unsupported claims when candle data is insufficient.
 */
enum class MarketRegime(val code: String, val displayLabel: String) {
    TRENDING("TRENDING", "TRENDING"),
    RANGING("RANGING", "RANGING"),
    BREAKOUT("BREAKOUT", "BREAKOUT"),
    PULLBACK("PULLBACK", "PULLBACK"),
    HIGH_VOLATILITY("HIGH_VOLATILITY", "HIGH VOLATILITY"),
    LOW_VOLATILITY("LOW_VOLATILITY", "LOW VOLATILITY"),
    CHOPPY("CHOPPY", "CHOPPY"),
    INSUFFICIENT_DATA("INSUFFICIENT_DATA", "INSUFFICIENT DATA");

    companion object {
        fun fromCode(raw: String): MarketRegime {
            val clean = raw.trim().uppercase(Locale.US).replace(" ", "_")
            return entries.firstOrNull { it.code == clean || it.displayLabel.equals(raw.trim(), ignoreCase = true) }
                ?: RANGING
        }
    }
}

/**
 * Requirement #9: Multi-Timeframe Confirmation status combining Higher-TF trend,
 * Primary-TF setup, and Lower-TF trigger while handling missing or stale data safely.
 */
enum class MtfConfirmationStatus(val label: String) {
    ALIGNED("Aligned"),
    PARTIAL("Partial Agreement"),
    CONFLICT("Timeframe Conflict"),
    MISSING_DATA("Partial / Missing TF Data")
}

data class MtfConfirmation(
    val status: MtfConfirmationStatus,
    val summary: String,
    val hasHigherTfData: Boolean,
    val hasLowerTfData: Boolean,
    val isHigherTfStale: Boolean = false,
    val isLowerTfStale: Boolean = false,
    val directionalAgreementSide: String = "NEUTRAL" // "LONG", "SHORT", "NEUTRAL"
)

/**
 * Requirement #2 & #9: Multi-Timeframe Summary for Higher TF, Primary TF, and Lower TF.
 */
data class TimeframeContextSummary(
    val timeframe: Timeframe,
    val currentPrice: Double,
    val trend: String,                  // "BULLISH", "BEARISH", "SIDEWAYS", "TRANSITIONAL", "PULLBACK"
    val marketCondition: String,        // "BULLISH", "BEARISH", "SIDEWAYS", "TRANSITIONAL"
    val structureCode: String,          // "HH_HL", "LH_LL", "HL", "LH", "SIDEWAYS", "TRANSITIONAL"
    val emaAlignment: String,           // "BULLISH", "BEARISH", "MIXED"
    val ema20: Double?,
    val ema50: Double?,
    val ema200: Double?,
    val rsi14: Double?,
    val macdState: String,              // "BULLISH", "TURNING_BULLISH", "BEARISH", "TURNING_BEARISH", "NEUTRAL"
    val macdHistogram: Double?,
    val volumeCondition: String,        // "INCREASING", "NORMAL", "DECREASING", "WEAK"
    val atr14: Double?,
    val volatilityState: String,        // "LOW", "NORMAL", "HIGH", "ABNORMAL"
    val breakoutPullbackCondition: String, // "BREAKOUT_CONFIRMED", "UNCONFIRMED_BREAKOUT", "BULLISH_PULLBACK", "BEARISH_PULLBACK", "NEAR_RESISTANCE", "NEAR_SUPPORT", "RANGE_BOUND", "NORMAL"
    val supportLevels: List<Double>,
    val resistanceLevels: List<Double>,
    val lastSwingHigh: Double?,
    val lastSwingLow: Double?,
    val latestCandleCloseTime: Long = 0L
)

/**
 * Requirement #5: Candidate Entry/SL/TP levels computed mathematically from actual market structure,
 * ATR, swing highs/lows, and support/resistance.
 */
data class CandidateTradeLevels(
    val direction: String,              // "LONG" or "SHORT"
    val entryMin: Double,
    val entryMax: Double,
    val stopLoss: Double,
    val tp1: Double,
    val tp2: Double,
    val tp3: Double,
    val riskRewardTp1: Double,
    val riskRewardTp2: Double
)

data class MarketTechnicalSnapshot(
    val symbol: String,
    val timeframe: Timeframe,
    val currentPrice: Double,
    val latestCandle: Candle,
    val recentCandles: List<Candle>,
    val ema20: Double?,
    val ema50: Double?,
    val ema200: Double?,
    val rsi14: Double?,
    val macd: MacdPoint?,
    val atr14: Double?,
    val currentVolume: Double,
    val volumeSma20: Double?,
    val supportLevels: List<Double>,
    val resistanceLevels: List<Double>,
    val marketStructure: MarketStructureAnalysis,
    val hasEmaConflict: Boolean,
    val hasRsiConflict: Boolean,
    val hasMacdConflict: Boolean,
    val isVolumeWeak: Boolean,
    val isNearMajorResistance: Boolean,
    val isNearMajorSupport: Boolean = false,
    val isVolatilityAbnormal: Boolean = false,
    val marketRegime: MarketRegime = MarketRegime.RANGING,
    val mtfConfirmation: MtfConfirmation = MtfConfirmation(
        status = MtfConfirmationStatus.MISSING_DATA,
        summary = "Primary timeframe only",
        hasHigherTfData = false,
        hasLowerTfData = false
    ),
    val primaryContext: TimeframeContextSummary? = null,
    val higherContext: TimeframeContextSummary? = null,
    val lowerContext: TimeframeContextSummary? = null,
    val longCandidate: CandidateTradeLevels? = null,
    val shortCandidate: CandidateTradeLevels? = null,
    val smcAnalysis: SmcAnalysisSnapshot? = null,
    val replayCandles: List<Candle> = emptyList()
)

object TechnicalSnapshotBuilder {

    /**
     * Requirement #8: Classifies Market Regime from price, indicator, and swing structure data.
     * Avoids unsupported claims when candle history is insufficient (< 20 candles).
     */
    fun classifyMarketRegime(
        candles: List<Candle>,
        primarySummary: TimeframeContextSummary?,
        structure: MarketStructureAnalysis,
        hasEmaConflict: Boolean,
        hasRsiConflict: Boolean,
        hasMacdConflict: Boolean
    ): MarketRegime {
        if (candles.size < 20 || primarySummary == null) {
            return MarketRegime.INSUFFICIENT_DATA
        }

        val volState = primarySummary.volatilityState
        val bpCondition = primarySummary.breakoutPullbackCondition
        val emaAlign = primarySummary.emaAlignment
        val rsi = primarySummary.rsi14 ?: 50.0

        // 1. Extreme volatility overrides
        if (volState == "ABNORMAL" || volState == "HIGH") {
            return MarketRegime.HIGH_VOLATILITY
        }
        if (volState == "LOW") {
            return MarketRegime.LOW_VOLATILITY
        }

        // 2. Breakout or Pullback conditions
        if (bpCondition == "BREAKOUT_CONFIRMED") {
            return MarketRegime.BREAKOUT
        }
        if (bpCondition == "BULLISH_PULLBACK" || bpCondition == "BEARISH_PULLBACK") {
            return MarketRegime.PULLBACK
        }

        // 3. Check for Choppy (overlapping candles + indicator conflicts + neutral RSI whipsaw)
        val conflictCount = listOf(hasEmaConflict, hasRsiConflict, hasMacdConflict).count { it }
        val recent8 = candles.takeLast(8)
        var directionFlips = 0
        for (i in 1 until recent8.size) {
            val prevBull = recent8[i - 1].close >= recent8[i - 1].open
            val currBull = recent8[i].close >= recent8[i].open
            if (prevBull != currBull) directionFlips++
        }
        if (conflictCount >= 2 && (directionFlips >= 5 || structure.state == StructureState.TRANSITIONAL)) {
            return MarketRegime.CHOPPY
        }

        // 4. Trending vs Ranging
        if ((structure.state == StructureState.BULLISH && emaAlign == "BULLISH" && rsi >= 51.0) ||
            (structure.state == StructureState.BEARISH && emaAlign == "BEARISH" && rsi <= 49.0)
        ) {
            return MarketRegime.TRENDING
        }

        if (structure.state == StructureState.TRANSITIONAL && conflictCount >= 1) {
            return MarketRegime.CHOPPY
        }

        return MarketRegime.RANGING
    }

    /**
     * Requirement #9: Evaluates Multi-Timeframe Confirmation across Higher TF, Primary TF, and Lower TF.
     * Safely detects missing or stale timeframe data.
     */
    fun evaluateMtfConfirmation(
        primaryTf: Timeframe,
        primarySummary: TimeframeContextSummary?,
        higherSummary: TimeframeContextSummary?,
        lowerSummary: TimeframeContextSummary?,
        referenceTimeMs: Long = System.currentTimeMillis()
    ): MtfConfirmation {
        if (primarySummary == null) {
            return MtfConfirmation(
                status = MtfConfirmationStatus.MISSING_DATA,
                summary = "Insufficient primary timeframe data",
                hasHigherTfData = false,
                hasLowerTfData = false
            )
        }

        val htfExpected = primaryTf.higherTimeframe()
        val ltfExpected = primaryTf.lowerTimeframe()

        val hasHtf = higherSummary != null
        val hasLtf = lowerSummary != null

        val htfStale = higherSummary != null && higherSummary.latestCandleCloseTime > 0L &&
            (referenceTimeMs - higherSummary.latestCandleCloseTime) > (higherSummary.timeframe.durationMs * 3L)
        val ltfStale = lowerSummary != null && lowerSummary.latestCandleCloseTime > 0L &&
            (referenceTimeMs - lowerSummary.latestCandleCloseTime) > (lowerSummary.timeframe.durationMs * 4L)

        val pCond = primarySummary.marketCondition
        val hCond = higherSummary?.marketCondition
        val lCond = lowerSummary?.marketCondition
        val lBp = lowerSummary?.breakoutPullbackCondition

        val htfLabel = higherSummary?.timeframe?.label ?: htfExpected?.label ?: "HTF"
        val ptfLabel = primaryTf.label
        val ltfLabel = lowerSummary?.timeframe?.label ?: ltfExpected?.label ?: "LTF"

        if (!hasHtf && htfExpected != null) {
            return MtfConfirmation(
                status = MtfConfirmationStatus.MISSING_DATA,
                summary = "$ptfLabel $pCond ($htfLabel data unavailable — single-TF evaluation)",
                hasHigherTfData = false,
                hasLowerTfData = hasLtf,
                isHigherTfStale = false,
                isLowerTfStale = ltfStale,
                directionalAgreementSide = when (pCond) {
                    "BULLISH" -> "LONG"
                    "BEARISH" -> "SHORT"
                    else -> "NEUTRAL"
                }
            )
        }

        val htfPrimaryConflict = (hCond == "BULLISH" && pCond == "BEARISH") ||
            (hCond == "BEARISH" && pCond == "BULLISH")

        if (htfPrimaryConflict) {
            return MtfConfirmation(
                status = MtfConfirmationStatus.CONFLICT,
                summary = "Conflict: $htfLabel is $hCond while $ptfLabel is $pCond",
                hasHigherTfData = hasHtf,
                hasLowerTfData = hasLtf,
                isHigherTfStale = htfStale,
                isLowerTfStale = ltfStale,
                directionalAgreementSide = "NEUTRAL"
            )
        }

        val bothBullish = hCond == "BULLISH" && pCond == "BULLISH"
        val bothBearish = hCond == "BEARISH" && pCond == "BEARISH"

        if (bothBullish || bothBearish) {
            val side = if (bothBullish) "LONG" else "SHORT"
            val ltfTriggerAligned = !hasLtf ||
                (bothBullish && (lCond == "BULLISH" || lBp == "BULLISH_PULLBACK" || lBp == "BREAKOUT_CONFIRMED")) ||
                (bothBearish && (lCond == "BEARISH" || lBp == "BEARISH_PULLBACK" || lBp == "BREAKOUT_CONFIRMED"))

            val staleNote = if (htfStale || ltfStale) " (Warning: secondary TF cache stale)" else ""
            return if (ltfTriggerAligned && !htfStale) {
                val ltfDesc = if (hasLtf) " + $ltfLabel ${lBp?.takeIf { it != "NORMAL" } ?: lCond}" else ""
                MtfConfirmation(
                    status = MtfConfirmationStatus.ALIGNED,
                    summary = "Aligned: $htfLabel $hCond + $ptfLabel $pCond$ltfDesc$staleNote",
                    hasHigherTfData = hasHtf,
                    hasLowerTfData = hasLtf,
                    isHigherTfStale = htfStale,
                    isLowerTfStale = ltfStale,
                    directionalAgreementSide = side
                )
            } else {
                MtfConfirmation(
                    status = MtfConfirmationStatus.PARTIAL,
                    summary = "Partial: $htfLabel & $ptfLabel $pCond, waiting for $ltfLabel trigger ($lCond)$staleNote",
                    hasHigherTfData = hasHtf,
                    hasLowerTfData = hasLtf,
                    isHigherTfStale = htfStale,
                    isLowerTfStale = ltfStale,
                    directionalAgreementSide = side
                )
            }
        }

        return MtfConfirmation(
            status = MtfConfirmationStatus.PARTIAL,
            summary = "Mixed / Transitional: $htfLabel ${hCond ?: "--"} · $ptfLabel $pCond",
            hasHigherTfData = hasHtf,
            hasLowerTfData = hasLtf,
            isHigherTfStale = htfStale,
            isLowerTfStale = ltfStale,
            directionalAgreementSide = when (pCond) {
                "BULLISH" -> "LONG"
                "BEARISH" -> "SHORT"
                else -> "NEUTRAL"
            }
        )
    }

    fun buildTimeframeSummary(
        timeframe: Timeframe,
        candles: List<Candle>,
        indicators: IndicatorSeries = TechnicalIndicators.calculateAll(candles),
        referenceTrend: String? = null
    ): TimeframeContextSummary? {
        if (candles.isEmpty()) return null
        val latest = candles.last()
        val price = latest.close

        val ema20 = indicators.latestEma20
        val ema50 = indicators.latestEma50
        val ema200 = indicators.latestEma200
        val rsi14 = indicators.latestRsi14
        val macd = indicators.latestMacd
        val prevMacd = if (indicators.macd.size >= 2) indicators.macd[indicators.macd.lastIndex - 1] else null
        val atr14 = indicators.latestAtr14
        val volSma20 = indicators.latestVolumeSma20

        val structure = MarketStructureEngine.analyze(candles)
        val sr = SupportResistanceEngine.detectLevels(candles, price, atr14)

        val emaAlignment = when {
            ema20 != null && ema50 != null && ema200 != null && price >= ema20 && ema20 > ema50 && ema50 >= ema200 -> "BULLISH"
            ema20 != null && ema50 != null && price > ema20 && ema20 > ema50 -> "BULLISH"
            ema20 != null && ema50 != null && ema200 != null && price <= ema20 && ema20 < ema50 && ema50 <= ema200 -> "BEARISH"
            ema20 != null && ema50 != null && price < ema20 && ema20 < ema50 -> "BEARISH"
            else -> "MIXED"
        }

        val hist = macd?.histogram ?: 0.0
        val prevHist = prevMacd?.histogram ?: hist
        val macdState = when {
            hist > 0.0 && hist >= prevHist -> "BULLISH"
            hist > 0.0 && hist < prevHist -> "TURNING_BEARISH"
            hist < 0.0 && hist > prevHist -> "TURNING_BULLISH"
            hist < 0.0 -> "BEARISH"
            else -> "NEUTRAL"
        }

        val prevVol = if (candles.size >= 2) candles[candles.lastIndex - 1].volume else latest.volume
        val volumeCondition = when {
            volSma20 != null && volSma20 > 0.0 && latest.volume >= volSma20 * 1.25 -> "INCREASING"
            volSma20 != null && volSma20 > 0.0 && latest.volume < volSma20 * 0.72 -> "DECREASING"
            latest.volume > prevVol * 1.15 -> "INCREASING"
            latest.volume < prevVol * 0.80 -> "DECREASING"
            else -> "NORMAL"
        }

        val atrPct = if (atr14 != null && price > 0.0) (atr14 / price) * 100.0 else 0.6
        val lastBarRange = latest.high - latest.low
        val volatilityState = when {
            atrPct > 3.8 || (atr14 != null && atr14 > 0.0 && lastBarRange > atr14 * 2.8) -> "ABNORMAL"
            atrPct > 2.0 -> "HIGH"
            atrPct < 0.18 -> "LOW"
            else -> "NORMAL"
        }

        val rawMarketCondition = when {
            structure.state == StructureState.BULLISH && emaAlignment == "BULLISH" -> "BULLISH"
            structure.state == StructureState.BEARISH && emaAlignment == "BEARISH" -> "BEARISH"
            structure.state == StructureState.BULLISH && emaAlignment == "BEARISH" -> "TRANSITIONAL"
            structure.state == StructureState.BEARISH && emaAlignment == "BULLISH" -> "TRANSITIONAL"
            structure.state == StructureState.TRANSITIONAL -> "TRANSITIONAL"
            emaAlignment == "BULLISH" && (rsi14 ?: 50.0) >= 52.0 -> "BULLISH"
            emaAlignment == "BEARISH" && (rsi14 ?: 50.0) <= 48.0 -> "BEARISH"
            else -> "SIDEWAYS"
        }

        val nearestRes = sr.resistanceLevels.firstOrNull()
        val nearestSup = sr.supportLevels.firstOrNull()
        val proxThreshold = atr14?.times(0.55) ?: (price * 0.0035)
        val nearRes = nearestRes != null && (nearestRes - price) in 0.0..proxThreshold
        val nearSup = nearestSup != null && (price - nearestSup) in 0.0..proxThreshold

        val breakoutPullback = when {
            nearRes && volumeCondition in listOf("DECREASING", "NORMAL") -> "UNCONFIRMED_BREAKOUT"
            nearRes && volumeCondition == "INCREASING" && rawMarketCondition == "BULLISH" -> "BREAKOUT_CONFIRMED"
            referenceTrend == "BULLISH" && (rawMarketCondition == "BEARISH" || (ema20 != null && price <= ema20 * 1.003 && (ema50 == null || price >= ema50 * 0.995))) -> "BULLISH_PULLBACK"
            referenceTrend == "BEARISH" && (rawMarketCondition == "BULLISH" || (ema20 != null && price >= ema20 * 0.997 && (ema50 == null || price <= ema50 * 1.005))) -> "BEARISH_PULLBACK"
            nearRes -> "NEAR_RESISTANCE"
            nearSup -> "NEAR_SUPPORT"
            rawMarketCondition == "SIDEWAYS" -> "RANGE_BOUND"
            else -> "NORMAL"
        }

        val effectiveTrend = when {
            breakoutPullback == "BULLISH_PULLBACK" || breakoutPullback == "BEARISH_PULLBACK" -> "PULLBACK"
            else -> rawMarketCondition
        }

        return TimeframeContextSummary(
            timeframe = timeframe,
            currentPrice = price,
            trend = effectiveTrend,
            marketCondition = rawMarketCondition,
            structureCode = structure.schemaCode,
            emaAlignment = emaAlignment,
            ema20 = ema20,
            ema50 = ema50,
            ema200 = ema200,
            rsi14 = rsi14,
            macdState = macdState,
            macdHistogram = macd?.histogram,
            volumeCondition = volumeCondition,
            atr14 = atr14,
            volatilityState = volatilityState,
            breakoutPullbackCondition = breakoutPullback,
            supportLevels = sr.supportLevels,
            resistanceLevels = sr.resistanceLevels,
            lastSwingHigh = structure.lastSwingHighPrice,
            lastSwingLow = structure.lastSwingLowPrice,
            latestCandleCloseTime = latest.closeTime
        )
    }

    fun build(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        indicators: IndicatorSeries = TechnicalIndicators.calculateAll(candles),
        higherTimeframeCandles: List<Candle>? = null,
        lowerTimeframeCandles: List<Candle>? = null,
        bidPrice: Double? = null,
        askPrice: Double? = null,
        hasVerifiedBidAsk: Boolean = false,
        marketStatus: MarketStatusEvaluation? = null,
        eventRiskAssessment: EventRiskAssessment? = null,
        profileCode: String = "DAY_TRADING",
        accountEquityUsd: Double = 10_000.0,
        riskPerTradePercent: Double = 1.0,
        maxLeverage: Double = 5.0,
        equalLevelAtrTolerance: Double = 0.18,
        sweepConfig: SweepDetectionConfig = SweepDetectionConfig(),
        spreadConfig: SpreadVolatilityConfig = SpreadVolatilityConfig(),
        sessionConfig: TradingSessionConfig = TradingSessionConfig(),
        evaluationTimeMs: Long? = null
    ): MarketTechnicalSnapshot? {
        if (candles.isEmpty()) return null
        val latest = candles.last()
        val price = latest.close

        val ema20 = indicators.latestEma20
        val ema50 = indicators.latestEma50
        val ema200 = indicators.latestEma200
        val rsi14 = indicators.latestRsi14
        val macd = indicators.latestMacd
        val atr14 = indicators.latestAtr14
        val volSma20 = indicators.latestVolumeSma20

        val structure = MarketStructureEngine.analyze(
            candles = candles,
            atr14 = atr14,
            equalLevelAtrTolerance = equalLevelAtrTolerance
        )
        val sr: SupportResistanceResult = SupportResistanceEngine.detectLevels(
            candles = candles,
            currentPrice = price,
            atr14 = atr14
        )

        val higherTf = timeframe.higherTimeframe()
        val lowerTf = timeframe.lowerTimeframe()

        val higherSummary = if (higherTf != null && !higherTimeframeCandles.isNullOrEmpty()) {
            buildTimeframeSummary(higherTf, higherTimeframeCandles)
        } else {
            null
        }

        val primarySummary = buildTimeframeSummary(
            timeframe = timeframe,
            candles = candles,
            indicators = indicators,
            referenceTrend = higherSummary?.marketCondition
        )

        val lowerSummary = if (lowerTf != null && !lowerTimeframeCandles.isNullOrEmpty()) {
            buildTimeframeSummary(
                timeframe = lowerTf,
                candles = lowerTimeframeCandles,
                referenceTrend = primarySummary?.marketCondition
            )
        } else {
            null
        }

        val emaBullishAligned = ema20 != null && ema50 != null && price > ema20 && ema20 > ema50
        val emaBearishAligned = ema20 != null && ema50 != null && price < ema20 && ema20 < ema50
        val hasEmaConflict = !emaBullishAligned && !emaBearishAligned

        val hasRsiConflict = rsi14 != null && (
            (emaBullishAligned && (rsi14 > 72.0 || rsi14 < 45.0)) ||
                (emaBearishAligned && (rsi14 < 28.0 || rsi14 > 55.0))
            )

        val macdHist = macd?.histogram
        val hasMacdConflict = macdHist != null && (
            (emaBullishAligned && macdHist < 0.0) ||
                (emaBearishAligned && macdHist > 0.0)
            )

        val isVolumeWeak = volSma20 != null && volSma20 > 0.0 && latest.volume < (volSma20 * 0.75)

        val nearestResistance = sr.resistanceLevels.firstOrNull()
        val nearestSupport = sr.supportLevels.firstOrNull()
        val threshold = atr14?.times(0.55) ?: (price * 0.0035)
        val isNearMajorResistance = nearestResistance != null && (nearestResistance - price) in 0.0..threshold
        val isNearMajorSupport = nearestSupport != null && (price - nearestSupport) in 0.0..threshold
        val isVolatilityAbnormal = primarySummary?.volatilityState == "ABNORMAL"

        val marketRegime = classifyMarketRegime(
            candles = candles,
            primarySummary = primarySummary,
            structure = structure,
            hasEmaConflict = hasEmaConflict,
            hasRsiConflict = hasRsiConflict,
            hasMacdConflict = hasMacdConflict
        )

        val refTimeMs = evaluationTimeMs
            ?: latest.closeTime.takeIf { it > 0L }
            ?: System.currentTimeMillis()

        val mtfConfirmation = evaluateMtfConfirmation(
            primaryTf = timeframe,
            primarySummary = primarySummary,
            higherSummary = higherSummary,
            lowerSummary = lowerSummary,
            referenceTimeMs = refTimeMs
        )

        val longCandidate = computeLongCandidate(
            price = price,
            atr = atr14 ?: (price * 0.007),
            ema20 = ema20,
            supports = sr.supportLevels,
            resistances = sr.resistanceLevels,
            swingLow = structure.lastSwingLowPrice,
            swingHigh = structure.lastSwingHighPrice
        )

        val shortCandidate = computeShortCandidate(
            price = price,
            atr = atr14 ?: (price * 0.007),
            ema20 = ema20,
            supports = sr.supportLevels,
            resistances = sr.resistanceLevels,
            swingLow = structure.lastSwingLowPrice,
            swingHigh = structure.lastSwingHighPrice
        )

        val sessionSnap = TradingSessionEngine.evaluateSession(
            symbol = symbol,
            candles = candles,
            nowMs = refTimeMs,
            config = sessionConfig,
            marketStatusOverride = marketStatus
        )
        val refLevels = SmcLiquiditySweepEngine.buildLiquidityReferenceLevels(
            structure = structure,
            sessionSnapshot = sessionSnap
        )
        val detectedSweeps = SmcLiquiditySweepEngine.detectLiquiditySweeps(
            symbol = symbol,
            timeframe = timeframe,
            candles = candles,
            referenceLevels = refLevels,
            atr14 = atr14,
            config = sweepConfig,
            nowMs = refTimeMs
        )
        val latestActiveSweep = detectedSweeps.lastOrNull {
            it.state == SweepDetectionState.CONFIRMED_SWEEP || it.state == SweepDetectionState.POTENTIAL_SWEEP
        }

        val primaryBias = primarySummary?.marketCondition ?: structure.state.code
        val higherBias = higherSummary?.marketCondition ?: primaryBias
        val preferLong = when {
            latestActiveSweep != null -> latestActiveSweep.isBullishSweep
            primaryBias == "BEARISH" -> false
            else -> true
        }
        val activeCandidate = if (preferLong) longCandidate else shortCandidate
        val candMidEntry = (activeCandidate.entryMin + activeCandidate.entryMax) / 2.0

        val spreadVolValidation = SmcLiquiditySweepEngine.validateSpreadAndVolatility(
            symbol = symbol,
            currentPrice = price,
            bidPrice = bidPrice,
            askPrice = askPrice,
            hasVerifiedBidAsk = hasVerifiedBidAsk,
            atr14 = atr14,
            volatilityState = primarySummary?.volatilityState ?: "NORMAL",
            proposedEntryPrice = candMidEntry,
            proposedStopLoss = activeCandidate.stopLoss,
            proposedTp1 = activeCandidate.tp1,
            isLongSetup = preferLong,
            profileCode = profileCode,
            accountEquityUsd = accountEquityUsd,
            riskPerTradePercent = riskPerTradePercent,
            maxLeverage = maxLeverage,
            config = spreadConfig
        )

        val proposedDirection = when {
            primaryBias == "BULLISH" || (latestActiveSweep?.isBullishSweep == true && primaryBias != "BEARISH") -> "LONG"
            primaryBias == "BEARISH" || (latestActiveSweep?.isBullishSweep == false && primaryBias != "BULLISH") -> "SHORT"
            else -> "WAIT"
        }

        val setupValidationReport = SmcLiquiditySweepEngine.evaluateSetupDecision(
            symbol = symbol,
            timeframe = timeframe,
            currentPrice = price,
            higherTimeframeBias = higherBias,
            primaryTimeframeBias = primaryBias,
            structure = structure,
            referenceLevels = refLevels,
            sweeps = detectedSweeps,
            sessionSnapshot = sessionSnap,
            spreadVolValidation = spreadVolValidation,
            eventRiskAssessment = eventRiskAssessment,
            marketStatus = marketStatus,
            proposedDirection = proposedDirection,
            entryMin = activeCandidate.entryMin,
            entryMax = activeCandidate.entryMax,
            stopLoss = activeCandidate.stopLoss,
            tp1 = activeCandidate.tp1
        )

        val smcSnapshot = SmcAnalysisSnapshot(
            referenceLevels = refLevels,
            detectedSweeps = detectedSweeps,
            latestActiveSweep = latestActiveSweep,
            sessionSnapshot = sessionSnap,
            spreadVolatilityValidation = spreadVolValidation,
            setupValidationReport = setupValidationReport
        )

        return MarketTechnicalSnapshot(
            symbol = symbol,
            timeframe = timeframe,
            currentPrice = price,
            latestCandle = latest,
            // Keep enough real history for the validator's 20-candle minimum.
            recentCandles = candles.takeLast(60),
            replayCandles = candles.filter { it.isClosed }.takeLast(1000),
            ema20 = ema20,
            ema50 = ema50,
            ema200 = ema200,
            rsi14 = rsi14,
            macd = macd,
            atr14 = atr14,
            currentVolume = latest.volume,
            volumeSma20 = volSma20,
            supportLevels = sr.supportLevels,
            resistanceLevels = sr.resistanceLevels,
            marketStructure = structure,
            hasEmaConflict = hasEmaConflict,
            hasRsiConflict = hasRsiConflict,
            hasMacdConflict = hasMacdConflict,
            isVolumeWeak = isVolumeWeak,
            isNearMajorResistance = isNearMajorResistance,
            isNearMajorSupport = isNearMajorSupport,
            isVolatilityAbnormal = isVolatilityAbnormal,
            marketRegime = marketRegime,
            mtfConfirmation = mtfConfirmation,
            primaryContext = primarySummary,
            higherContext = higherSummary,
            lowerContext = lowerSummary,
            longCandidate = longCandidate,
            shortCandidate = shortCandidate,
            smcAnalysis = smcSnapshot
        )
    }

    /**
     * Requirement #5: Candidate LONG levels anchored to actual Support, Swing Low, EMA20, and ATR.
     */
    private fun computeLongCandidate(
        price: Double,
        atr: Double,
        ema20: Double?,
        supports: List<Double>,
        resistances: List<Double>,
        swingLow: Double?,
        swingHigh: Double?
    ): CandidateTradeLevels {
        val safeAtr = atr.takeIf { it > 0.0 && !it.isNaN() } ?: (price * 0.007)
        val sup1 = supports.firstOrNull() ?: (price - safeAtr * 1.3)
        val sup2 = supports.getOrNull(1) ?: (price - safeAtr * 2.4)
        val res1 = resistances.firstOrNull() ?: (price + safeAtr * 1.5)
        val res2 = resistances.getOrNull(1) ?: (price + safeAtr * 2.5)

        val pullbackAnchor = if (ema20 != null && ema20 in (price - safeAtr * 1.2)..price) {
            ema20
        } else {
            max(sup1, price - safeAtr * 0.45)
        }
        val entryMin = roundPriceCleanly(min(pullbackAnchor, price - safeAtr * 0.15))
        val entryMax = roundPriceCleanly(max(pullbackAnchor, price + safeAtr * 0.08))
        val entryMid = (entryMin + entryMax) / 2.0

        val structuralFloor = listOfNotNull(sup1, swingLow).filter { it < entryMin }.maxOrNull()
            ?: (entryMin - safeAtr * 0.9)
        val rawSl = min(structuralFloor - safeAtr * 0.25, entryMin - safeAtr * 0.75)
        val stopLoss = roundPriceCleanly(rawSl.coerceAtLeast(price * 0.5))

        val risk = max(entryMid - stopLoss, safeAtr * 0.4)

        val rawTp1 = max(entryMax + safeAtr * 0.45, if (res1 > entryMax + safeAtr * 0.35) res1 else (entryMid + risk * 1.5))
        val rawTp2 = max(rawTp1 + safeAtr * 0.85, max(res2, entryMid + risk * 2.2))
        val rawTp3 = max(rawTp2 + safeAtr * 1.0, max(swingHigh ?: 0.0, entryMid + risk * 3.0))

        val tp1 = roundPriceCleanly(rawTp1)
        val tp2 = roundPriceCleanly(max(rawTp2, tp1 + safeAtr * 0.5))
        val tp3 = roundPriceCleanly(max(rawTp3, tp2 + safeAtr * 0.6))

        val rr1 = kotlin.math.round(((tp1 - entryMid) / risk).coerceAtLeast(0.1) * 100.0) / 100.0
        val rr2 = kotlin.math.round(((tp2 - entryMid) / risk).coerceAtLeast(0.1) * 100.0) / 100.0

        return CandidateTradeLevels(
            direction = "LONG",
            entryMin = entryMin,
            entryMax = entryMax,
            stopLoss = stopLoss,
            tp1 = tp1,
            tp2 = tp2,
            tp3 = tp3,
            riskRewardTp1 = rr1,
            riskRewardTp2 = rr2
        )
    }

    /**
     * Requirement #5: Candidate SHORT levels anchored to actual Resistance, Swing High, EMA20, and ATR.
     */
    private fun computeShortCandidate(
        price: Double,
        atr: Double,
        ema20: Double?,
        supports: List<Double>,
        resistances: List<Double>,
        swingLow: Double?,
        swingHigh: Double?
    ): CandidateTradeLevels {
        val safeAtr = atr.takeIf { it > 0.0 && !it.isNaN() } ?: (price * 0.007)
        val sup1 = supports.firstOrNull() ?: (price - safeAtr * 1.5)
        val sup2 = supports.getOrNull(1) ?: (price - safeAtr * 2.5)
        val res1 = resistances.firstOrNull() ?: (price + safeAtr * 1.3)

        val pullbackAnchor = if (ema20 != null && ema20 in price..(price + safeAtr * 1.2)) {
            ema20
        } else {
            min(res1, price + safeAtr * 0.45)
        }
        val entryMin = roundPriceCleanly(min(pullbackAnchor, price - safeAtr * 0.08))
        val entryMax = roundPriceCleanly(max(pullbackAnchor, price + safeAtr * 0.15))
        val entryMid = (entryMin + entryMax) / 2.0

        val structuralCeiling = listOfNotNull(res1, swingHigh).filter { it > entryMax }.minOrNull()
            ?: (entryMax + safeAtr * 0.9)
        val rawSl = max(structuralCeiling + safeAtr * 0.25, entryMax + safeAtr * 0.75)
        val stopLoss = roundPriceCleanly(rawSl)

        val risk = max(stopLoss - entryMid, safeAtr * 0.4)

        val rawTp1 = min(entryMin - safeAtr * 0.45, if (sup1 < entryMin - safeAtr * 0.35) sup1 else (entryMid - risk * 1.5))
        val rawTp2 = min(rawTp1 - safeAtr * 0.85, min(sup2, entryMid - risk * 2.2))
        val rawTp3 = min(rawTp2 - safeAtr * 1.0, min(swingLow ?: Double.MAX_VALUE, entryMid - risk * 3.0))

        val tp1 = roundPriceCleanly(rawTp1.coerceAtLeast(price * 0.3))
        val tp2 = roundPriceCleanly(min(rawTp2, tp1 - safeAtr * 0.5).coerceAtLeast(price * 0.25))
        val tp3 = roundPriceCleanly(min(rawTp3, tp2 - safeAtr * 0.6).coerceAtLeast(price * 0.2))

        val rr1 = kotlin.math.round(((entryMid - tp1) / risk).coerceAtLeast(0.1) * 100.0) / 100.0
        val rr2 = kotlin.math.round(((entryMid - tp2) / risk).coerceAtLeast(0.1) * 100.0) / 100.0

        return CandidateTradeLevels(
            direction = "SHORT",
            entryMin = entryMin,
            entryMax = entryMax,
            stopLoss = stopLoss,
            tp1 = tp1,
            tp2 = tp2,
            tp3 = tp3,
            riskRewardTp1 = rr1,
            riskRewardTp2 = rr2
        )
    }
}
