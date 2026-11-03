package com.example.analysis.smc

import com.example.analysis.events.EventRiskAssessment
import com.example.analysis.marketStructure.EqualHighLowLevel
import com.example.analysis.marketStructure.MarketStructureAnalysis
import com.example.analysis.marketStructure.OrderBlockCandidate
import com.example.analysis.marketStructure.StructureBreakEvent
import com.example.analysis.marketStructure.StructureBreakType
import com.example.analysis.marketStructure.StructureState
import com.example.analysis.marketStructure.SwingPoint
import com.example.analysis.supportResistance.SupportResistanceEngine.roundPriceCleanly
import com.example.market.candle.Candle
import com.example.market.candle.Timeframe
import com.example.market.session.TradingSessionSnapshot
import com.example.market.session.TradingSessionType
import com.example.market.status.MarketLedState
import com.example.market.status.MarketStatusEvaluation
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class LiquidityReferenceType(val code: String, val displayLabel: String, val isHighPool: Boolean) {
    SWING_HIGH("SWING_HIGH", "Confirmed Swing High", true),
    SWING_LOW("SWING_LOW", "Confirmed Swing Low", false),
    EQH("EQH", "Equal Highs (EQH)", true),
    EQL("EQL", "Equal Lows (EQL)", false),
    PREV_SESSION_HIGH("PREV_SESSION_HIGH", "Previous Session High", true),
    PREV_SESSION_LOW("PREV_SESSION_LOW", "Previous Session Low", false),
    PDH("PDH", "Previous Day High (PDH)", true),
    PDL("PDL", "Previous Day Low (PDL)", false)
}

enum class SweepDetectionState(val code: String, val displayLabel: String) {
    NONE("NONE", "No Sweep"),
    POTENTIAL_SWEEP("POTENTIAL_SWEEP", "Potential Sweep (Pending Close Confirmation)"),
    CONFIRMED_SWEEP("CONFIRMED_SWEEP", "Confirmed Sweep (Reclaim / Rejection Closed)"),
    INVALIDATED("INVALIDATED", "Sweep Invalidated (Closed Beyond Extreme)"),
    EXPIRED("EXPIRED", "Sweep Expired")
}

data class LiquidityReferenceLevel(
    val type: LiquidityReferenceType,
    val price: Double,
    val referenceTimeMs: Long,
    val label: String
)

data class LiquiditySweepEvent(
    val dedupKey: String,
    val symbol: String,
    val timeframe: Timeframe,
    val isBullishSweep: Boolean, // true = Bullish sweep of a low; false = Bearish sweep of a high
    val referenceType: LiquidityReferenceType,
    val referenceLevel: Double,
    val sweepExtreme: Double,
    val sweepCandleOpenTime: Long,
    val detectedAtMs: Long,
    val state: SweepDetectionState,
    val isIntrabarObservation: Boolean,
    val reclaimOrRejectionPrice: Double,
    val confirmationCondition: String,
    val invalidationLevel: Double,
    val isInvalidated: Boolean = false,
    val invalidationReason: String = "",
    val barsSinceSweep: Int = 0
) {
    val directionLabel: String get() = if (isBullishSweep) "Bullish Sweep" else "Bearish Sweep"
    val summaryBadge: String
        get() = "$directionLabel of ${referenceType.code} @ ${roundPriceCleanly(referenceLevel)} (${state.code})"
}

data class SweepDetectionConfig(
    val minPenetrationAtrFraction: Double = 0.04,
    val maxPenetrationAtrFraction: Double = 1.35,
    val minRejectionWickRatio: Double = 0.25,
    val maxSweepExpiryBars: Int = 15,
    val requireCandleCloseForConfirmation: Boolean = true
)

enum class UnavailableSpreadPolicy(val code: String, val label: String) {
    ALLOW_WITH_WARNING("ALLOW_WITH_WARNING", "Allow with Conservative Warning"),
    BLOCK_NEW_ENTRIES("BLOCK_NEW_ENTRIES", "Block New Entries When Spread Unknown");

    companion object {
        fun fromCode(code: String): UnavailableSpreadPolicy {
            return entries.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }
                ?: ALLOW_WITH_WARNING
        }
    }
}

data class SpreadVolatilityConfig(
    val maxSpreadBpsScalping: Double = 18.0,
    val maxSpreadBpsDayTrading: Double = 30.0,
    val maxSpreadBpsSwingTrading: Double = 45.0,
    val maxSpreadToStopLossRatioPct: Double = 25.0, // Reject if spread > 25% of SL distance
    val maxSpreadToRewardRatioPct: Double = 18.0,   // Reject if spread > 18% of TP1 distance
    val unavailableSpreadPolicy: UnavailableSpreadPolicy = UnavailableSpreadPolicy.ALLOW_WITH_WARNING
)

data class SpreadVolatilityValidation(
    val symbol: String,
    val isBidAskAvailable: Boolean,
    val bidPrice: Double?,
    val askPrice: Double?,
    val spreadAbsolute: Double?,
    val spreadBps: Double?,
    val maxAllowedSpreadBps: Double,
    val spreadToStopLossPct: Double?,
    val spreadToTp1RewardPct: Double?,
    val isSpreadExcessive: Boolean,
    val isSpreadCheckPassed: Boolean,
    val spreadStatusSummary: String,
    val atr14: Double?,
    val volatilityState: String, // "LOW", "NORMAL", "HIGH", "ABNORMAL"
    val isVolatilityAcceptable: Boolean,
    val executionBuyPrice: Double,  // Ask when available, else currentPrice
    val executionSellPrice: Double, // Bid when available, else currentPrice
    val proposedStopLossDistance: Double,
    val recalculatedRiskUsd: Double,
    val recalculatedNotionalUsd: Double,
    val recalculatedQuantityUnits: Double
)

/**
 * Explicit setup validation states required by Section 8:
 * - REJECTED
 * - WAITING_FOR_CONFIRMATION
 * - VALID_CANDIDATE
 * - ENTRY_READY
 * - INVALIDATED
 */
enum class SetupDecisionState(val code: String, val displayLabel: String) {
    REJECTED("REJECTED", "REJECTED"),
    WAITING_FOR_CONFIRMATION("WAITING_FOR_CONFIRMATION", "WAITING FOR CONFIRMATION"),
    VALID_CANDIDATE("VALID_CANDIDATE", "VALID CANDIDATE"),
    ENTRY_READY("ENTRY_READY", "ENTRY READY"),
    INVALIDATED("INVALIDATED", "INVALIDATED");

    companion object {
        fun fromCode(code: String): SetupDecisionState {
            return entries.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }
                ?: WAITING_FOR_CONFIRMATION
        }
    }
}

/**
 * Section 8: Explainable Setup Validation Report.
 * Explicitly documents all 12 evaluation dimensions without arbitrary black-box score addition.
 */
data class SetupValidationReport(
    val decisionState: SetupDecisionState,
    val recommendedDirection: String, // "LONG", "SHORT", or "WAIT"
    val higherTimeframeBias: String,
    val currentMarketStructure: String,
    val latestBosSummary: String,
    val latestChochSummary: String,
    val relevantLiquidityLevels: List<String>,
    val sweepStateSummary: String,
    val reclaimRejectionConfirmation: String,
    val tradingSessionContext: String,
    val volatilityCondition: String,
    val spreadExecutionFeasibility: String,
    val economicEventRiskSummary: String,
    val entryTriggerDescription: String,
    val stopLossTakeProfitFeasibility: String,
    val mathematicalRiskReward: Double,
    val heuristicAlignmentIndex: Int, // Documented as heuristic rule-alignment index (0..100), NOT calibrated probability
    val heuristicIndexNote: String = "Heuristic rule-alignment index (not a calibrated statistical win probability)",
    val triggeredRules: List<String>,
    val blockingOrWarningReasons: List<String>,
    val conciseExplanation: String
)

data class SmcAnalysisSnapshot(
    val referenceLevels: List<LiquidityReferenceLevel>,
    val detectedSweeps: List<LiquiditySweepEvent>,
    val latestActiveSweep: LiquiditySweepEvent?,
    val sessionSnapshot: TradingSessionSnapshot,
    val spreadVolatilityValidation: SpreadVolatilityValidation,
    val setupValidationReport: SetupValidationReport
)

object SmcLiquiditySweepEngine {

    /**
     * Thread-safe deduplication registry keyed by `symbol_timeframe` -> set of emitted `dedupKey_state`
     * so repeated live ticks on the same candle do not re-emit duplicate sweep notifications/events.
     */
    private val emittedSweepStateRegistry = ConcurrentHashMap<String, MutableSet<String>>()

    fun clearDeduplicationRegistryForTest() {
        emittedSweepStateRegistry.clear()
    }

    /**
     * Checks whether a sweep event with its current state (`POTENTIAL_SWEEP` or `CONFIRMED_SWEEP`)
     * is newly observed (true on first emission, false on repeated ticks for the same sweep state).
     */
    fun registerSweepEmissionIfNew(event: LiquiditySweepEvent): Boolean {
        val streamKey = "${event.symbol.uppercase(Locale.US)}_${event.timeframe.label}"
        val eventStateToken = "${event.dedupKey}_${event.state.code}"
        val set = emittedSweepStateRegistry.getOrPut(streamKey) {
            ConcurrentHashMap.newKeySet()
        }
        return set.add(eventStateToken)
    }

    /**
     * Builds the tracked Liquidity Reference Levels from:
     * 1. Confirmed Swing Highs & Swing Lows (never provisional pivots)
     * 2. Active Equal Highs (EQH) & Equal Lows (EQL)
     * 3. Previous Completed Session High & Low
     * 4. Previous Day High (PDH) & Previous Day Low (PDL)
     */
    fun buildLiquidityReferenceLevels(
        structure: MarketStructureAnalysis,
        sessionSnapshot: TradingSessionSnapshot
    ): List<LiquidityReferenceLevel> {
        val levels = ArrayList<LiquidityReferenceLevel>()

        // 1. Equal Highs & Equal Lows (active only)
        for (eq in structure.activeEqualLevels) {
            levels.add(
                LiquidityReferenceLevel(
                    type = if (eq.isEqualHigh) LiquidityReferenceType.EQH else LiquidityReferenceType.EQL,
                    price = eq.averagePrice,
                    referenceTimeMs = eq.secondSwingOpenTime,
                    label = eq.displayLabel
                )
            )
        }

        // 2. Confirmed Swing Highs & Lows (most recent 4 highs and 4 lows)
        val recentConfirmedHighs = structure.confirmedSwings.filter { it.isHigh }.takeLast(4)
        for (sh in recentConfirmedHighs) {
            levels.add(
                LiquidityReferenceLevel(
                    type = LiquidityReferenceType.SWING_HIGH,
                    price = roundPriceCleanly(sh.price),
                    referenceTimeMs = sh.confirmedAtCandleOpenTime,
                    label = "Swing High (${sh.tag.name}) @ ${roundPriceCleanly(sh.price)}"
                )
            )
        }

        val recentConfirmedLows = structure.confirmedSwings.filter { !it.isHigh }.takeLast(4)
        for (sl in recentConfirmedLows) {
            levels.add(
                LiquidityReferenceLevel(
                    type = LiquidityReferenceType.SWING_LOW,
                    price = roundPriceCleanly(sl.price),
                    referenceTimeMs = sl.confirmedAtCandleOpenTime,
                    label = "Swing Low (${sl.tag.name}) @ ${roundPriceCleanly(sl.price)}"
                )
            )
        }

        // 3. Previous Session High & Low
        sessionSnapshot.previousSessionHighLow?.let { prevSess ->
            if (prevSess.high > 0.0 && prevSess.low > 0.0) {
                levels.add(
                    LiquidityReferenceLevel(
                        type = LiquidityReferenceType.PREV_SESSION_HIGH,
                        price = roundPriceCleanly(prevSess.high),
                        referenceTimeMs = prevSess.endEpochMs,
                        label = "${prevSess.sessionLabel} High @ ${roundPriceCleanly(prevSess.high)}"
                    )
                )
                levels.add(
                    LiquidityReferenceLevel(
                        type = LiquidityReferenceType.PREV_SESSION_LOW,
                        price = roundPriceCleanly(prevSess.low),
                        referenceTimeMs = prevSess.endEpochMs,
                        label = "${prevSess.sessionLabel} Low @ ${roundPriceCleanly(prevSess.low)}"
                    )
                )
            }
        }

        // 4. Previous Day High (PDH) & Previous Day Low (PDL)
        sessionSnapshot.previousDayHighLow?.let { pd ->
            if (pd.high > 0.0 && pd.low > 0.0) {
                levels.add(
                    LiquidityReferenceLevel(
                        type = LiquidityReferenceType.PDH,
                        price = roundPriceCleanly(pd.high),
                        referenceTimeMs = pd.endEpochMs,
                        label = "PDH (${pd.tradingDateLabel}) @ ${roundPriceCleanly(pd.high)}"
                    )
                )
                levels.add(
                    LiquidityReferenceLevel(
                        type = LiquidityReferenceType.PDL,
                        price = roundPriceCleanly(pd.low),
                        referenceTimeMs = pd.endEpochMs,
                        label = "PDL (${pd.tradingDateLabel}) @ ${roundPriceCleanly(pd.low)}"
                    )
                )
            }
        }

        return levels.distinctBy { "${it.type.code}_${roundPriceCleanly(it.price)}" }
    }

    /**
     * Section 3: Detects Bullish and Bearish Liquidity Sweeps across tracked reference levels.
     *
     * Explicit Rules:
     * - Bullish Sweep around a tracked Low (`L`):
     *   1. Penetration: `candle.low <= L - minPenetration` and `L - candle.low <= maxPenetration`.
     *   2. Reclaim:
     *      - If `candle.isClosed`:
     *        - Single-bar sweep & reclaim: `candle.close > L` and `lowerWickRatio >= minRejectionWickRatio`, OR
     *        - Two-bar sweep & reclaim: `nextCandle.isClosed && nextCandle.close > L` while `nextCandle.low >= candle.low`.
     *        -> Emits `CONFIRMED_SWEEP` (unless a subsequent candle closes below `sweepExtreme`, which marks it `INVALIDATED`).
     *      - If `!candle.isClosed` (intrabar live tick) or penetration occurred on the latest closed bar whose next-bar reclaim is still pending:
     *        -> Emits `POTENTIAL_SWEEP` (`isIntrabarObservation = !candle.isClosed`).
     * - Bearish Sweep around a tracked High (`H`):
     *   Symmetrical penetration above `H + minPenetration` (`<= maxPenetration`) followed by rejection/close below `H`.
     * - Expiry: Sweeps older than `config.maxSweepExpiryBars` candles are marked `EXPIRED` and excluded from active triggers.
     */
    fun detectLiquiditySweeps(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        referenceLevels: List<LiquidityReferenceLevel>,
        atr14: Double?,
        config: SweepDetectionConfig = SweepDetectionConfig(),
        nowMs: Long = System.currentTimeMillis()
    ): List<LiquiditySweepEvent> {
        if (candles.size < 5 || referenceLevels.isEmpty()) return emptyList()

        val latestPrice = candles.last().close
        val effectiveAtr = atr14?.takeIf { it > 0.0 && !it.isNaN() }
            ?: (latestPrice * 0.004).coerceAtLeast(1e-6)

        val minPenetration = max(effectiveAtr * config.minPenetrationAtrFraction, latestPrice * 0.00012)
        val maxPenetration = max(effectiveAtr * config.maxPenetrationAtrFraction, minPenetration * 6.0)
        val invalidationBuffer = effectiveAtr * 0.05

        val scanStartIdx = max(0, candles.size - (config.maxSweepExpiryBars + 6))
        val detectedByDedupKey = LinkedHashMap<String, LiquiditySweepEvent>()

        for (ref in referenceLevels) {
            val refPrice = ref.price
            if (refPrice <= 0.0) continue

            for (i in scanStartIdx until candles.size) {
                val bar = candles[i]
                // Only evaluate candles that opened strictly AFTER the reference level was established
                if (bar.openTime <= ref.referenceTimeMs) continue

                val barsAgo = (candles.lastIndex - i).coerceAtLeast(0)
                val barRange = (bar.high - bar.low).coerceAtLeast(1e-8)

                if (!ref.type.isHighPool) {
                    // Tracked LOW -> check for Bullish Liquidity Sweep
                    val penetrationDepth = refPrice - bar.low
                    if (penetrationDepth < minPenetration || penetrationDepth > maxPenetration) continue

                    val lowerWick = min(bar.open, bar.close) - bar.low
                    val lowerWickRatio = (lowerWick / barRange).coerceIn(0.0, 1.0)
                    val nextBar = candles.getOrNull(i + 1)

                    val singleBarConfirmed = bar.isClosed &&
                        bar.close > refPrice &&
                        lowerWickRatio >= config.minRejectionWickRatio

                    val twoBarConfirmed = bar.isClosed &&
                        bar.close <= refPrice &&
                        bar.close >= refPrice - effectiveAtr * 0.35 &&
                        nextBar != null &&
                        nextBar.isClosed &&
                        nextBar.close > refPrice &&
                        nextBar.low >= bar.low

                    val intrabarPotential = !bar.isClosed && bar.close >= bar.low + penetrationDepth * 0.5
                    val awaitingNextBarReclaim = bar.isClosed &&
                        i == candles.lastIndex &&
                        bar.close <= refPrice &&
                        bar.close >= refPrice - effectiveAtr * 0.35 &&
                        lowerWickRatio >= config.minRejectionWickRatio * 0.8

                    if (!singleBarConfirmed && !twoBarConfirmed && !intrabarPotential && !awaitingNextBarReclaim) {
                        continue
                    }

                    val confirmStartIdx = if (twoBarConfirmed) i + 2 else i + 1
                    val invalidationPrice = roundPriceCleanly(bar.low - invalidationBuffer)
                    var invalidated = false
                    var invalidReason = ""
                    for (subIdx in confirmStartIdx until candles.size) {
                        val subBar = candles[subIdx]
                        if (subBar.isClosed && subBar.close < invalidationPrice) {
                            invalidated = true
                            invalidReason = "Closed at ${roundPriceCleanly(subBar.close)} below sweep extreme ${roundPriceCleanly(bar.low)}"
                            break
                        }
                    }

                    val state = when {
                        invalidated -> SweepDetectionState.INVALIDATED
                        barsAgo > config.maxSweepExpiryBars -> SweepDetectionState.EXPIRED
                        (singleBarConfirmed || twoBarConfirmed) && config.requireCandleCloseForConfirmation ->
                            SweepDetectionState.CONFIRMED_SWEEP
                        else -> SweepDetectionState.POTENTIAL_SWEEP
                    }

                    val dedupKey = "${symbol.uppercase(Locale.US)}_${timeframe.label}_${ref.type.code}_${roundPriceCleanly(refPrice)}_${bar.openTime}"
                    val reclaimPrice = if (twoBarConfirmed && nextBar != null) nextBar.close else bar.close
                    val confirmCondition = when {
                        singleBarConfirmed -> "Closed back above ${roundPriceCleanly(refPrice)} at ${roundPriceCleanly(bar.close)} (lower wick ${(lowerWickRatio * 100).toInt()}%)"
                        twoBarConfirmed -> "2-bar reclaim closed above ${roundPriceCleanly(refPrice)} at ${roundPriceCleanly(reclaimPrice)}"
                        !bar.isClosed -> "Intrabar penetration to ${roundPriceCleanly(bar.low)}; awaiting ${timeframe.label} candle close above ${roundPriceCleanly(refPrice)}"
                        else -> "Swept to ${roundPriceCleanly(bar.low)}; awaiting reclaim close above ${roundPriceCleanly(refPrice)}"
                    }

                    detectedByDedupKey[dedupKey] = LiquiditySweepEvent(
                        dedupKey = dedupKey,
                        symbol = symbol,
                        timeframe = timeframe,
                        isBullishSweep = true,
                        referenceType = ref.type,
                        referenceLevel = roundPriceCleanly(refPrice),
                        sweepExtreme = roundPriceCleanly(bar.low),
                        sweepCandleOpenTime = bar.openTime,
                        detectedAtMs = nowMs,
                        state = state,
                        isIntrabarObservation = !bar.isClosed,
                        reclaimOrRejectionPrice = roundPriceCleanly(reclaimPrice),
                        confirmationCondition = confirmCondition,
                        invalidationLevel = invalidationPrice,
                        isInvalidated = invalidated,
                        invalidationReason = invalidReason,
                        barsSinceSweep = barsAgo
                    )
                } else {
                    // Tracked HIGH -> check for Bearish Liquidity Sweep
                    val penetrationDepth = bar.high - refPrice
                    if (penetrationDepth < minPenetration || penetrationDepth > maxPenetration) continue

                    val upperWick = bar.high - max(bar.open, bar.close)
                    val upperWickRatio = (upperWick / barRange).coerceIn(0.0, 1.0)
                    val nextBar = candles.getOrNull(i + 1)

                    val singleBarConfirmed = bar.isClosed &&
                        bar.close < refPrice &&
                        upperWickRatio >= config.minRejectionWickRatio

                    val twoBarConfirmed = bar.isClosed &&
                        bar.close >= refPrice &&
                        bar.close <= refPrice + effectiveAtr * 0.35 &&
                        nextBar != null &&
                        nextBar.isClosed &&
                        nextBar.close < refPrice &&
                        nextBar.high <= bar.high

                    val intrabarPotential = !bar.isClosed && bar.close <= bar.high - penetrationDepth * 0.5
                    val awaitingNextBarReclaim = bar.isClosed &&
                        i == candles.lastIndex &&
                        bar.close >= refPrice &&
                        bar.close <= refPrice + effectiveAtr * 0.35 &&
                        upperWickRatio >= config.minRejectionWickRatio * 0.8

                    if (!singleBarConfirmed && !twoBarConfirmed && !intrabarPotential && !awaitingNextBarReclaim) {
                        continue
                    }

                    val confirmStartIdx = if (twoBarConfirmed) i + 2 else i + 1
                    val invalidationPrice = roundPriceCleanly(bar.high + invalidationBuffer)
                    var invalidated = false
                    var invalidReason = ""
                    for (subIdx in confirmStartIdx until candles.size) {
                        val subBar = candles[subIdx]
                        if (subBar.isClosed && subBar.close > invalidationPrice) {
                            invalidated = true
                            invalidReason = "Closed at ${roundPriceCleanly(subBar.close)} above sweep extreme ${roundPriceCleanly(bar.high)}"
                            break
                        }
                    }

                    val state = when {
                        invalidated -> SweepDetectionState.INVALIDATED
                        barsAgo > config.maxSweepExpiryBars -> SweepDetectionState.EXPIRED
                        (singleBarConfirmed || twoBarConfirmed) && config.requireCandleCloseForConfirmation ->
                            SweepDetectionState.CONFIRMED_SWEEP
                        else -> SweepDetectionState.POTENTIAL_SWEEP
                    }

                    val dedupKey = "${symbol.uppercase(Locale.US)}_${timeframe.label}_${ref.type.code}_${roundPriceCleanly(refPrice)}_${bar.openTime}"
                    val rejectionPrice = if (twoBarConfirmed && nextBar != null) nextBar.close else bar.close
                    val confirmCondition = when {
                        singleBarConfirmed -> "Closed back below ${roundPriceCleanly(refPrice)} at ${roundPriceCleanly(bar.close)} (upper wick ${(upperWickRatio * 100).toInt()}%)"
                        twoBarConfirmed -> "2-bar rejection closed below ${roundPriceCleanly(refPrice)} at ${roundPriceCleanly(rejectionPrice)}"
                        !bar.isClosed -> "Intrabar penetration to ${roundPriceCleanly(bar.high)}; awaiting ${timeframe.label} candle close below ${roundPriceCleanly(refPrice)}"
                        else -> "Swept to ${roundPriceCleanly(bar.high)}; awaiting rejection close below ${roundPriceCleanly(refPrice)}"
                    }

                    detectedByDedupKey[dedupKey] = LiquiditySweepEvent(
                        dedupKey = dedupKey,
                        symbol = symbol,
                        timeframe = timeframe,
                        isBullishSweep = false,
                        referenceType = ref.type,
                        referenceLevel = roundPriceCleanly(refPrice),
                        sweepExtreme = roundPriceCleanly(bar.high),
                        sweepCandleOpenTime = bar.openTime,
                        detectedAtMs = nowMs,
                        state = state,
                        isIntrabarObservation = !bar.isClosed,
                        reclaimOrRejectionPrice = roundPriceCleanly(rejectionPrice),
                        confirmationCondition = confirmCondition,
                        invalidationLevel = invalidationPrice,
                        isInvalidated = invalidated,
                        invalidationReason = invalidReason,
                        barsSinceSweep = barsAgo
                    )
                }
            }
        }

        return detectedByDedupKey.values.sortedBy { it.sweepCandleOpenTime }
    }

    /**
     * Section 5: Volatility & Spread Validation + Dynamic Position Sizing.
     * - Never fabricates a spread value when `hasVerifiedBidAsk == false`.
     * - Evaluates spread vs max allowed bps, spread vs stop-loss distance, and spread vs TP1 reward.
     * - Uses Ask price for BUY execution and Bid price for SELL execution when available.
     * - Recalculates position size (`recalculatedQuantityUnits` & `recalculatedNotionalUsd`) whenever
     *   the stop-loss distance changes so dollar risk stays capped at `accountEquityUsd * (riskPerTradePercent / 100.0)`.
     */
    fun validateSpreadAndVolatility(
        symbol: String,
        currentPrice: Double,
        bidPrice: Double?,
        askPrice: Double?,
        hasVerifiedBidAsk: Boolean,
        atr14: Double?,
        volatilityState: String,
        proposedEntryPrice: Double,
        proposedStopLoss: Double,
        proposedTp1: Double,
        isLongSetup: Boolean,
        profileCode: String = "DAY_TRADING",
        accountEquityUsd: Double = 10_000.0,
        riskPerTradePercent: Double = 1.0,
        maxLeverage: Double = 5.0,
        config: SpreadVolatilityConfig = SpreadVolatilityConfig()
    ): SpreadVolatilityValidation {
        val cleanBid = bidPrice?.takeIf { hasVerifiedBidAsk && it > 0.0 && !it.isNaN() }
        val cleanAsk = askPrice?.takeIf { hasVerifiedBidAsk && it > 0.0 && !it.isNaN() && (cleanBid == null || it >= cleanBid) }
        val verifiedAvailable = cleanBid != null && cleanAsk != null && cleanAsk >= cleanBid

        val executionBuy = cleanAsk ?: currentPrice
        val executionSell = cleanBid ?: currentPrice
        val effectiveEntry = if (proposedEntryPrice > 0.0) {
            if (verifiedAvailable && abs(proposedEntryPrice - currentPrice) <= (cleanAsk!! - cleanBid!!) * 1.5) {
                if (isLongSetup) executionBuy else executionSell
            } else {
                proposedEntryPrice
            }
        } else {
            if (isLongSetup) executionBuy else executionSell
        }

        val slDistance = abs(effectiveEntry - proposedStopLoss).coerceAtLeast(effectiveEntry * 0.0005)
        val tp1Distance = abs(proposedTp1 - effectiveEntry).coerceAtLeast(effectiveEntry * 0.0005)

        val maxAllowedBps = when (profileCode.trim().uppercase(Locale.US)) {
            "SCALPING" -> config.maxSpreadBpsScalping
            "SWING_TRADING" -> config.maxSpreadBpsSwingTrading
            else -> config.maxSpreadBpsDayTrading
        }

        val spreadAbs = if (verifiedAvailable) (cleanAsk!! - cleanBid!!).coerceAtLeast(0.0) else null
        val midPrice = if (verifiedAvailable) ((cleanAsk!! + cleanBid!!) / 2.0).coerceAtLeast(1e-8) else currentPrice.coerceAtLeast(1e-8)
        val spreadBps = spreadAbs?.let { kotlin.math.round(((it / midPrice) * 10_000.0) * 100.0) / 100.0 }
        val spreadToSlPct = spreadAbs?.let { kotlin.math.round(((it / slDistance) * 100.0) * 100.0) / 100.0 }
        val spreadToTp1Pct = spreadAbs?.let { kotlin.math.round(((it / tp1Distance) * 100.0) * 100.0) / 100.0 }

        val isExcessive = if (verifiedAvailable) {
            (spreadBps != null && spreadBps > maxAllowedBps) ||
                (spreadToSlPct != null && spreadToSlPct > config.maxSpreadToStopLossRatioPct) ||
                (spreadToTp1Pct != null && spreadToTp1Pct > config.maxSpreadToRewardRatioPct)
        } else {
            false
        }

        val spreadPassed = when {
            verifiedAvailable -> !isExcessive
            config.unavailableSpreadPolicy == UnavailableSpreadPolicy.BLOCK_NEW_ENTRIES -> false
            else -> true
        }

        val spreadSummary = when {
            !verifiedAvailable -> {
                if (config.unavailableSpreadPolicy == UnavailableSpreadPolicy.BLOCK_NEW_ENTRIES) {
                    "Bid/Ask spread unavailable from provider — blocked by conservative spread policy"
                } else {
                    "Bid/Ask spread unavailable from provider (last-price feed only) — conservative warning active"
                }
            }
            isExcessive -> {
                "Excessive spread: ${roundPriceCleanly(spreadAbs ?: 0.0)} (${spreadBps} bps, ${spreadToSlPct}% of SL, ${spreadToTp1Pct}% of TP1; max allowed ${maxAllowedBps} bps)"
            }
            else -> {
                "Spread OK: ${roundPriceCleanly(spreadAbs ?: 0.0)} (${spreadBps} bps · ${spreadToSlPct}% of SL · Bid ${roundPriceCleanly(cleanBid!!)} / Ask ${roundPriceCleanly(cleanAsk!!)})"
            }
        }

        val volAcceptable = !volatilityState.equals("ABNORMAL", ignoreCase = true)

        // Dynamic Position Sizing: riskUsd = equity * (risk% / 100); notional = riskUsd / (slDistance / effectiveEntry)
        val safeEquity = accountEquityUsd.coerceAtLeast(100.0)
        val safeRiskPct = riskPerTradePercent.coerceIn(0.1, 5.0)
        val targetRiskUsd = safeEquity * (safeRiskPct / 100.0)
        val slDistanceFraction = (slDistance / effectiveEntry.coerceAtLeast(1e-8)).coerceAtLeast(0.0005)
        val rawNotionalUsd = targetRiskUsd / slDistanceFraction
        val maxCappedNotionalUsd = safeEquity * maxLeverage.coerceIn(1.0, 20.0)
        val finalNotionalUsd = kotlin.math.round(min(rawNotionalUsd, maxCappedNotionalUsd) * 100.0) / 100.0
        val finalQty = if (effectiveEntry > 0.0) {
            kotlin.math.round((finalNotionalUsd / effectiveEntry) * 100_000.0) / 100_000.0
        } else {
            0.0
        }
        val actualRiskUsd = kotlin.math.round((finalNotionalUsd * slDistanceFraction) * 100.0) / 100.0

        return SpreadVolatilityValidation(
            symbol = symbol,
            isBidAskAvailable = verifiedAvailable,
            bidPrice = cleanBid,
            askPrice = cleanAsk,
            spreadAbsolute = spreadAbs,
            spreadBps = spreadBps,
            maxAllowedSpreadBps = maxAllowedBps,
            spreadToStopLossPct = spreadToSlPct,
            spreadToTp1RewardPct = spreadToTp1Pct,
            isSpreadExcessive = isExcessive,
            isSpreadCheckPassed = spreadPassed,
            spreadStatusSummary = spreadSummary,
            atr14 = atr14,
            volatilityState = volatilityState,
            isVolatilityAcceptable = volAcceptable,
            executionBuyPrice = roundPriceCleanly(executionBuy),
            executionSellPrice = roundPriceCleanly(executionSell),
            proposedStopLossDistance = roundPriceCleanly(slDistance),
            recalculatedRiskUsd = actualRiskUsd,
            recalculatedNotionalUsd = finalNotionalUsd,
            recalculatedQuantityUnits = finalQty
        )
    }

    /**
     * Section 8: Explainable Setup Scoring and Decision Rules.
     *
     * Evaluates:
     * 1. Higher-timeframe bias
     * 2. Current market structure (Swing HH/HL/LH/LL, BOS, CHoCH, EQH/EQL, OB Candidate)
     * 3. Relevant liquidity levels
     * 4. Sweep state (`POTENTIAL_SWEEP`, `CONFIRMED_SWEEP`, `INVALIDATED`, `NONE`)
     * 5. Reclaim or rejection confirmation
     * 6. Trading session context (`Asia`, `London`, `New York`, `Overlap`, or `CLOSED`/`UNKNOWN`)
     * 7. Volatility conditions (`LOW`, `NORMAL`, `HIGH`, `ABNORMAL`)
     * 8. Spread and execution feasibility
     * 9. Economic-event risk
     * 10. Entry trigger
     * 11. Stop-loss and take-profit feasibility
     * 12. Mathematical Risk/Reward
     *
     * Explicit State Machine Rules:
     * - `INVALIDATED`: Active sweep or structure setup was invalidated by candle close beyond invalidation level.
     * - `REJECTED`: Market is CLOSED/STALE/ERROR/UNKNOWN, or Spread is excessive/blocked, or Abnormal Volatility,
     *   or High-Impact Economic Event blocks entry, or HTF/Primary structure strongly conflicts, or R:R < minRequiredRr.
     * - `WAITING_FOR_CONFIRMATION`:
     *   - Only a `POTENTIAL_SWEEP` exists (awaiting candle-close reclaim), OR
     *   - A `CONFIRMED_SWEEP` exists WITHOUT supporting structure/BOS/CHoCH/EMA alignment (a setup NEVER becomes
     *     `ENTRY_READY` solely because a liquidity sweep was detected!), OR
     *   - Structure is SIDEWAYS/TRANSITIONAL without confirmed BOS/CHoCH + reclaim.
     * - `VALID_CANDIDATE`:
     *   - Structure + HTF bias + R:R + Risk filters pass, and price is approaching the entry zone / waiting for final trigger.
     * - `ENTRY_READY`:
     *   - All critical risk/spread/session/data/event filters pass, R:R >= minRequiredRr, structure (BOS/CHoCH or trend alignment)
     *     is confirmed (plus confirmed sweep reclaim if sweep-based), and price is inside or confirming the entry trigger zone.
     */
    fun evaluateSetupDecision(
        symbol: String,
        timeframe: Timeframe,
        currentPrice: Double,
        higherTimeframeBias: String,
        primaryTimeframeBias: String,
        structure: MarketStructureAnalysis,
        referenceLevels: List<LiquidityReferenceLevel>,
        sweeps: List<LiquiditySweepEvent>,
        sessionSnapshot: TradingSessionSnapshot,
        spreadVolValidation: SpreadVolatilityValidation,
        eventRiskAssessment: EventRiskAssessment?,
        marketStatus: MarketStatusEvaluation?,
        proposedDirection: String, // "LONG", "SHORT", or "WAIT"
        entryMin: Double,
        entryMax: Double,
        stopLoss: Double,
        tp1: Double,
        minRequiredRr: Double = 1.35
    ): SetupValidationReport {
        val triggeredRules = ArrayList<String>()
        val blockingReasons = ArrayList<String>()

        val activeSweeps = sweeps.filter {
            it.state == SweepDetectionState.CONFIRMED_SWEEP || it.state == SweepDetectionState.POTENTIAL_SWEEP
        }
        val invalidatedSweeps = sweeps.filter { it.state == SweepDetectionState.INVALIDATED }
        val latestActiveSweep = activeSweeps.lastOrNull()
        val latestInvalidatedSweep = invalidatedSweeps.lastOrNull()

        val inferredDirection = when {
            proposedDirection in listOf("LONG", "SHORT") -> proposedDirection
            latestActiveSweep != null -> if (latestActiveSweep.isBullishSweep) "LONG" else "SHORT"
            structure.state == StructureState.BULLISH -> "LONG"
            structure.state == StructureState.BEARISH -> "SHORT"
            else -> "WAIT"
        }

        val isLong = inferredDirection == "LONG"
        val midEntry = if (entryMin > 0.0 && entryMax > 0.0) (entryMin + entryMax) / 2.0 else currentPrice
        val riskDist = abs(midEntry - stopLoss)
        val rewardDist = abs(tp1 - midEntry)
        val computedRr = if (riskDist > 1e-8 && tp1 > 0.0 && stopLoss > 0.0) {
            kotlin.math.round((rewardDist / riskDist) * 100.0) / 100.0
        } else {
            0.0
        }

        val slTpGeometryValid = if (inferredDirection == "LONG") {
            stopLoss > 0.0 && stopLoss < midEntry && midEntry < tp1 && currentPrice > stopLoss
        } else if (inferredDirection == "SHORT") {
            stopLoss > 0.0 && tp1 < midEntry && midEntry < stopLoss && currentPrice < stopLoss
        } else {
            false
        }

        // 1. Check Market Status & Schedule Availability
        val marketOpenAndFresh = marketStatus == null || (
            marketStatus.ledState == MarketLedState.OPEN &&
                marketStatus.isFeedHealthy &&
                marketStatus.isQuoteFresh &&
                marketStatus.isSessionOpen == true
            )
        if (!marketOpenAndFresh && marketStatus != null) {
            blockingReasons.add("Market/Data status is ${marketStatus.ledState.code} (${marketStatus.readableStatusText})")
        } else if (sessionSnapshot.instrumentMarketLedState == MarketLedState.UNKNOWN) {
            blockingReasons.add("Instrument trading schedule is UNKNOWN — blocking automatic directional assumption")
        } else if (sessionSnapshot.isInstrumentTradableNow == false) {
            blockingReasons.add("Instrument session is currently CLOSED (${sessionSnapshot.sessionStatusSummary})")
        } else {
            triggeredRules.add("Session active: ${sessionSnapshot.primarySession.displayLabel} (${sessionSnapshot.witaTimeFormatted})")
        }

        // 2. Check Economic Event Risk
        val eventBlocked = eventRiskAssessment?.shouldBlockNewBotEntries == true
        if (eventBlocked) {
            blockingReasons.add("Economic Event Risk block: ${eventRiskAssessment?.summaryMessage}")
        } else if (eventRiskAssessment != null && !eventRiskAssessment.isEventDataVerified) {
            triggeredRules.add("Event Calendar note: ${eventRiskAssessment.statusBadge}")
        } else {
            triggeredRules.add("Economic Event Risk: CLEAR")
        }

        // 3. Check Spread & Volatility
        if (!spreadVolValidation.isSpreadCheckPassed) {
            blockingReasons.add(spreadVolValidation.spreadStatusSummary)
        } else {
            triggeredRules.add(spreadVolValidation.spreadStatusSummary)
        }

        if (!spreadVolValidation.isVolatilityAcceptable) {
            blockingReasons.add("Abnormal volatility (${spreadVolValidation.volatilityState}) relative to 14-period ATR")
        } else {
            triggeredRules.add("Volatility ${spreadVolValidation.volatilityState} (ATR14=${spreadVolValidation.atr14?.let { roundPriceCleanly(it) } ?: "N/A"})")
        }

        // 4. Check Structure, BOS, CHoCH, and HTF Alignment
        val latestBos = structure.latestBos
        val latestChoch = structure.latestChoch
        val structureSupportsDirection = when (inferredDirection) {
            "LONG" -> structure.state == StructureState.BULLISH ||
                (latestBos?.isBullish == true) ||
                (latestChoch?.isBullish == true)
            "SHORT" -> structure.state == StructureState.BEARISH ||
                (latestBos?.isBullish == false) ||
                (latestChoch?.isBullish == false)
            else -> false
        }
        val htfConflict = (inferredDirection == "LONG" && higherTimeframeBias == "BEARISH") ||
            (inferredDirection == "SHORT" && higherTimeframeBias == "BULLISH")

        if (htfConflict && latestChoch == null) {
            blockingReasons.add("Higher-timeframe bias ($higherTimeframeBias) conflicts with $inferredDirection without confirmed CHoCH")
        } else if (structureSupportsDirection) {
            val breakNote = latestChoch?.label ?: latestBos?.label ?: structure.schemaCode
            triggeredRules.add("Structure aligned ($breakNote · HTF $higherTimeframeBias)")
        }

        // 5. Check Liquidity Sweep State & Reclaim Confirmation
        val matchingSweep = activeSweeps.lastOrNull {
            (isLong && it.isBullishSweep) || (!isLong && !it.isBullishSweep)
        }
        val hasConfirmedSweep = matchingSweep?.state == SweepDetectionState.CONFIRMED_SWEEP
        val hasPotentialSweepOnly = matchingSweep?.state == SweepDetectionState.POTENTIAL_SWEEP

        if (hasConfirmedSweep && matchingSweep != null) {
            triggeredRules.add("Confirmed ${matchingSweep.directionLabel} of ${matchingSweep.referenceType.code} @ ${matchingSweep.referenceLevel}")
        } else if (hasPotentialSweepOnly && matchingSweep != null) {
            blockingReasons.add("Potential ${matchingSweep.directionLabel} of ${matchingSweep.referenceType.code} is still awaiting candle-close confirmation")
        }

        // 6. Check SL/TP Geometry & Risk/Reward
        if (inferredDirection != "WAIT") {
            if (!slTpGeometryValid) {
                blockingReasons.add("Invalid SL/TP price geometry for $inferredDirection")
            } else if (computedRr < minRequiredRr) {
                blockingReasons.add("Mathematical R:R (1:$computedRr) is below required minimum (1:$minRequiredRr)")
            } else {
                triggeredRules.add("SL/TP geometry valid with R:R 1:$computedRr (min 1:$minRequiredRr)")
            }
        }

        // Determine explicit SetupDecisionState
        // Rule: A setup NEVER becomes ENTRY_READY solely because a liquidity sweep was detected!
        // It requires structure/trend confluence (structureSupportsDirection AND !htfConflict) + valid risk/spread/session/R:R.
        val hasStructureConfluence = structureSupportsDirection && !htfConflict &&
            (primaryTimeframeBias == (if (isLong) "BULLISH" else "BEARISH") || latestChoch != null || latestBos != null)

        val priceInEntryZone = currentPrice in (min(entryMin, entryMax) * 0.9985)..(max(entryMin, entryMax) * 1.0015)

        val decisionState: SetupDecisionState = when {
            latestInvalidatedSweep != null && latestActiveSweep == null &&
                latestInvalidatedSweep.barsSinceSweep <= 2 -> {
                SetupDecisionState.INVALIDATED
            }
            !marketOpenAndFresh ||
                sessionSnapshot.instrumentMarketLedState == MarketLedState.UNKNOWN ||
                sessionSnapshot.isInstrumentTradableNow == false ||
                eventBlocked ||
                !spreadVolValidation.isSpreadCheckPassed ||
                !spreadVolValidation.isVolatilityAcceptable ||
                (inferredDirection != "WAIT" && (!slTpGeometryValid || computedRr < minRequiredRr)) ||
                (htfConflict && latestChoch == null) -> {
                SetupDecisionState.REJECTED
            }
            inferredDirection == "WAIT" || hasPotentialSweepOnly || !hasStructureConfluence -> {
                // Even if hasConfirmedSweep == true, if !hasStructureConfluence, stay in WAITING_FOR_CONFIRMATION!
                if (hasConfirmedSweep && !hasStructureConfluence) {
                    blockingReasons.add("Confirmed liquidity sweep detected, but waiting for structural BOS/CHoCH or primary trend confluence (sweep alone cannot trigger entry)")
                }
                SetupDecisionState.WAITING_FOR_CONFIRMATION
            }
            priceInEntryZone && (hasConfirmedSweep || (latestBos != null || latestChoch != null)) -> {
                SetupDecisionState.ENTRY_READY
            }
            else -> {
                SetupDecisionState.VALID_CANDIDATE
            }
        }

        val finalRecommendedDirection = when (decisionState) {
            SetupDecisionState.ENTRY_READY, SetupDecisionState.VALID_CANDIDATE -> inferredDirection
            else -> "WAIT"
        }

        // Heuristic alignment index (documented clearly as a rule-alignment index, NOT a calibrated probability)
        var alignmentIndex = 50
        if (hasStructureConfluence) alignmentIndex += 15
        if (hasConfirmedSweep) alignmentIndex += 12
        if (latestBos != null || latestChoch != null) alignmentIndex += 8
        if (computedRr >= minRequiredRr + 0.4) alignmentIndex += 6
        if (sessionSnapshot.isLondonNewYorkOverlap || sessionSnapshot.primarySession in listOf(TradingSessionType.LONDON, TradingSessionType.NEW_YORK)) {
            alignmentIndex += 4
        }
        if (blockingReasons.isNotEmpty()) {
            alignmentIndex -= min(35, blockingReasons.size * 12)
        }
        alignmentIndex = alignmentIndex.coerceIn(15, 92)

        val sweepSummaryText = when {
            matchingSweep != null -> "${matchingSweep.summaryBadge} — ${matchingSweep.confirmationCondition}"
            latestInvalidatedSweep != null -> "Invalidated ${latestInvalidatedSweep.directionLabel}: ${latestInvalidatedSweep.invalidationReason}"
            else -> "No active liquidity sweep on recent ${timeframe.label} bars"
        }

        val reclaimText = matchingSweep?.confirmationCondition
            ?: "Awaiting sweep + closed-candle reclaim/rejection or structural break"

        val bosText = latestBos?.description ?: "None in recent window"
        val chochText = latestChoch?.description ?: "None in recent window"
        val liqLevelStrings = referenceLevels.take(6).map { it.label }

        val conciseExplanation = when (decisionState) {
            SetupDecisionState.ENTRY_READY ->
                "ENTRY READY ($finalRecommendedDirection): ${triggeredRules.joinToString(" · ")}"
            SetupDecisionState.VALID_CANDIDATE ->
                "VALID CANDIDATE ($finalRecommendedDirection): Structure & risk filters pass; waiting for price in entry zone (${roundPriceCleanly(entryMin)}–${roundPriceCleanly(entryMax)})."
            SetupDecisionState.WAITING_FOR_CONFIRMATION ->
                "WAITING FOR CONFIRMATION (WAIT): ${blockingReasons.firstOrNull() ?: "Waiting for confirmed BOS/CHoCH + liquidity reclaim."}"
            SetupDecisionState.REJECTED ->
                "REJECTED (WAIT): ${blockingReasons.joinToString("; ")}"
            SetupDecisionState.INVALIDATED ->
                "INVALIDATED (WAIT): ${latestInvalidatedSweep?.invalidationReason ?: "Setup closed beyond invalidation boundary."}"
        }

        return SetupValidationReport(
            decisionState = decisionState,
            recommendedDirection = finalRecommendedDirection,
            higherTimeframeBias = higherTimeframeBias,
            currentMarketStructure = "${structure.state.displayLabel} (${structure.schemaCode})",
            latestBosSummary = bosText,
            latestChochSummary = chochText,
            relevantLiquidityLevels = liqLevelStrings,
            sweepStateSummary = sweepSummaryText,
            reclaimRejectionConfirmation = reclaimText,
            tradingSessionContext = sessionSnapshot.sessionStatusSummary,
            volatilityCondition = "${spreadVolValidation.volatilityState} (ATR14: ${spreadVolValidation.atr14?.let { roundPriceCleanly(it) } ?: "N/A"})",
            spreadExecutionFeasibility = spreadVolValidation.spreadStatusSummary,
            economicEventRiskSummary = eventRiskAssessment?.summaryMessage ?: "No blocking macro events",
            entryTriggerDescription = if (finalRecommendedDirection == "WAIT") {
                "Wait for confirmed structure + liquidity reclaim and valid spread/session filters"
            } else {
                "Enter $finalRecommendedDirection (${if (isLong) "Ask ${spreadVolValidation.executionBuyPrice}" else "Bid ${spreadVolValidation.executionSellPrice}"}) inside ${roundPriceCleanly(entryMin)}–${roundPriceCleanly(entryMax)}"
            },
            stopLossTakeProfitFeasibility = if (slTpGeometryValid) {
                "SL ${roundPriceCleanly(stopLoss)} (dist ${spreadVolValidation.proposedStopLossDistance}) · TP1 ${roundPriceCleanly(tp1)} · R:R 1:$computedRr · Size ${spreadVolValidation.recalculatedQuantityUnits} units ($${spreadVolValidation.recalculatedNotionalUsd})"
            } else {
                "No active SL/TP while decision is WAIT"
            },
            mathematicalRiskReward = computedRr,
            heuristicAlignmentIndex = alignmentIndex,
            triggeredRules = triggeredRules,
            blockingOrWarningReasons = blockingReasons,
            conciseExplanation = conciseExplanation
        )
    }
}
