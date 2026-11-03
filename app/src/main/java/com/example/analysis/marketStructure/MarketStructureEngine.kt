package com.example.analysis.marketStructure

import com.example.analysis.supportResistance.SupportResistanceEngine.roundPriceCleanly
import com.example.market.candle.Candle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class StructureState(
    val code: String,
    val displayLabel: String,
    val biasTerminology: String,
    val swingDescription: String
) {
    BULLISH(
        code = "BULLISH",
        displayLabel = "BULLISH",
        biasTerminology = "bullish bias",
        swingDescription = "Higher High / Higher Low"
    ),
    BEARISH(
        code = "BEARISH",
        displayLabel = "BEARISH",
        biasTerminology = "bearish bias",
        swingDescription = "Lower High / Lower Low"
    ),
    SIDEWAYS(
        code = "SIDEWAYS",
        displayLabel = "SIDEWAYS",
        biasTerminology = "neutral bias",
        swingDescription = "Consolidation / Range"
    ),
    TRANSITIONAL(
        code = "TRANSITIONAL",
        displayLabel = "TRANSITIONAL",
        biasTerminology = "transitional bias",
        swingDescription = "Structure Shift / Transitional"
    )
}

enum class SwingTag {
    HH, // Higher High
    LH, // Lower High
    HL, // Higher Low
    LL  // Lower Low
}

/**
 * Distinguishes confirmed pivots (all right-hand confirmation candles have closed, no look-ahead bias)
 * from provisional pivots (extremum inside the trailing unconfirmed lookback window).
 */
enum class PivotConfirmationStatus(val code: String, val label: String) {
    CONFIRMED("CONFIRMED", "Confirmed"),
    PROVISIONAL("PROVISIONAL", "Provisional")
}

data class SwingPoint(
    val candleIndex: Int,
    val openTime: Long,
    val price: Double,
    val isHigh: Boolean,
    val tag: SwingTag,
    val confirmationStatus: PivotConfirmationStatus = PivotConfirmationStatus.CONFIRMED,
    val confirmedAtCandleOpenTime: Long = openTime
) {
    val isConfirmed: Boolean get() = confirmationStatus == PivotConfirmationStatus.CONFIRMED
}

/**
 * Structural Break Type:
 * - BOS (Break of Structure): Trend-continuation break where a closed candle body closes beyond
 *   the most recent confirmed Swing High (in an established bullish/HH-HL structure) or below
 *   the most recent confirmed Swing Low (in an established bearish/LH-LL structure).
 * - CHOCH (Change of Character): First counter-trend structural break where a closed candle body
 *   closes below the most recent confirmed Higher Low / internal swing low after a bullish sequence
 *   (Bearish CHoCH), or closes above the most recent confirmed Lower High / internal swing high
 *   after a bearish sequence (Bullish CHoCH).
 */
enum class StructureBreakType(val code: String, val displayLabel: String) {
    BOS("BOS", "BOS (Break of Structure)"),
    CHOCH("CHOCH", "CHoCH (Change of Character)")
}

data class StructureBreakEvent(
    val type: StructureBreakType,
    val isBullish: Boolean,
    val brokenLevelPrice: Double,
    val brokenSwingOpenTime: Long,
    val breakCandleIndex: Int,
    val breakCandleOpenTime: Long,
    val breakClosePrice: Double,
    val isBodyCloseConfirmed: Boolean,
    val description: String
) {
    val label: String
        get() = "${if (isBullish) "Bullish" else "Bearish"} ${type.code}"
}

/**
 * Equal Highs (EQH) or Equal Lows (EQL) detected from confirmed swing pivots within a
 * configurable ATR-scaled / symbol-appropriate tolerance, invalidated once a candle closes beyond them.
 */
data class EqualHighLowLevel(
    val isEqualHigh: Boolean, // true = EQH, false = EQL
    val averagePrice: Double,
    val firstSwingOpenTime: Long,
    val secondSwingOpenTime: Long,
    val toleranceUsed: Double,
    val isInvalidated: Boolean = false,
    val invalidatedAtOpenTime: Long? = null
) {
    val typeCode: String get() = if (isEqualHigh) "EQH" else "EQL"
    val displayLabel: String get() = "$typeCode @ ${roundPriceCleanly(averagePrice)}"
}

/**
 * Documented, testable candle-derived Order-Block Candidate Zone:
 * - Defined strictly as the last opposing-color candle immediately preceding an impulsive
 *   displacement move (displacement range >= 1.25 * ATR) that causes a confirmed BOS or CHoCH.
 * - Never claims to identify actual institutional orders or hidden order-book depth.
 * - Invalidated when a subsequent closed candle closes beyond the distal boundary of the zone.
 */
data class OrderBlockCandidate(
    val isBullish: Boolean, // Bullish demand OB candidate vs Bearish supply OB candidate
    val zoneLow: Double,
    val zoneHigh: Double,
    val originCandleOpenTime: Long,
    val displacementBreakType: StructureBreakType,
    val isMitigated: Boolean = false,
    val isInvalidated: Boolean = false,
    val invalidatedAtOpenTime: Long? = null
) {
    val label: String
        get() = "${if (isBullish) "Bullish" else "Bearish"} OB Candidate (${roundPriceCleanly(zoneLow)}–${roundPriceCleanly(zoneHigh)})"
}

data class MarketStructureAnalysis(
    val state: StructureState,
    val schemaCode: String, // e.g., "HH_HL", "LH_LL", "HL", "LH", "SIDEWAYS", "TRANSITIONAL"
    val swingSummary: String,
    val biasLabel: String,
    val lastHighTag: SwingTag?,
    val lastLowTag: SwingTag?,
    val lastSwingHighPrice: Double?,
    val lastSwingLowPrice: Double?,
    val recentSwings: List<SwingPoint>,
    val confirmedSwings: List<SwingPoint> = recentSwings.filter { it.isConfirmed },
    val provisionalSwings: List<SwingPoint> = recentSwings.filter { !it.isConfirmed },
    val structureBreaks: List<StructureBreakEvent> = emptyList(),
    val latestBos: StructureBreakEvent? = null,
    val latestChoch: StructureBreakEvent? = null,
    val equalHighsLows: List<EqualHighLowLevel> = emptyList(),
    val orderBlockCandidates: List<OrderBlockCandidate> = emptyList()
) {
    val activeEqualLevels: List<EqualHighLowLevel>
        get() = equalHighsLows.filter { !it.isInvalidated }

    val activeOrderBlockCandidates: List<OrderBlockCandidate>
        get() = orderBlockCandidates.filter { !it.isInvalidated }
}

object MarketStructureEngine {

    /**
     * Computes a symbol- and timeframe-appropriate price tolerance for Equal Highs / Equal Lows
     * based on ATR14 (default 0.18 * ATR14) with a safe percentage fallback (0.08% of price).
     */
    fun computeDynamicTolerance(
        referencePrice: Double,
        atr14: Double? = null,
        atrFraction: Double = 0.18,
        minPctFallback: Double = 0.0008
    ): Double {
        if (referencePrice <= 0.0) return 0.0
        val atrTol = if (atr14 != null && !atr14.isNaN() && atr14 > 0.0) {
            atr14 * atrFraction.coerceIn(0.03, 0.75)
        } else {
            0.0
        }
        val pctTol = referencePrice * minPctFallback.coerceIn(0.0001, 0.01)
        return max(atrTol, pctTol)
    }

    /**
     * Detects Confirmed and Provisional Swing Highs/Lows, BOS, CHoCH, Equal Highs/Lows (EQH/EQL),
     * and Order-Block Candidate Zones without look-ahead bias.
     *
     * Anti-Look-Ahead Guarantee:
     * - A pivot at index `i` is marked `CONFIRMED` only if `i + pivotLookback <= lastClosedIndex`,
     *   meaning all `pivotLookback` right-hand confirmation candles have already closed.
     * - Its `confirmedAtCandleOpenTime` is set to the openTime of candle `i + pivotLookback`.
     * - BOS and CHoCH detection at candle `k` only evaluates against swings whose
     *   confirmation index `i + pivotLookback < k` (strictly known prior to candle `k`).
     */
    fun analyze(
        candles: List<Candle>,
        pivotLookback: Int = 3,
        atr14: Double? = null,
        equalLevelAtrTolerance: Double = 0.18
    ): MarketStructureAnalysis {
        if (candles.size < (pivotLookback * 2 + 5)) {
            return MarketStructureAnalysis(
                state = StructureState.SIDEWAYS,
                schemaCode = "SIDEWAYS",
                swingSummary = "Insufficient swings",
                biasLabel = StructureState.SIDEWAYS.biasTerminology,
                lastHighTag = null,
                lastLowTag = null,
                lastSwingHighPrice = candles.maxOfOrNull { it.high },
                lastSwingLowPrice = candles.minOfOrNull { it.low },
                recentSwings = emptyList()
            )
        }

        val recentWindow = candles.takeLast(120)
        val lastClosedIdx = recentWindow.indexOfLast { it.isClosed }.takeIf { it >= 0 } ?: (recentWindow.lastIndex - 1)
        val maxConfirmedPivotIdx = (lastClosedIdx - pivotLookback).coerceAtLeast(0)

        val confirmedHighs = ArrayList<Pair<Int, Candle>>()
        val confirmedLows = ArrayList<Pair<Int, Candle>>()

        for (i in pivotLookback..min(maxConfirmedPivotIdx, recentWindow.lastIndex - pivotLookback)) {
            val candidate = recentWindow[i]
            var isPivotHigh = true
            var isPivotLow = true

            for (offset in 1..pivotLookback) {
                if (recentWindow[i - offset].high > candidate.high ||
                    recentWindow[i + offset].high >= candidate.high
                ) {
                    isPivotHigh = false
                }
                if (recentWindow[i - offset].low < candidate.low ||
                    recentWindow[i + offset].low <= candidate.low
                ) {
                    isPivotLow = false
                }
            }

            if (isPivotHigh) confirmedHighs.add(i to candidate)
            if (isPivotLow) confirmedLows.add(i to candidate)
        }

        val confirmedSwings = ArrayList<SwingPoint>()
        var prevHigh: Double? = null
        for ((idx, c) in confirmedHighs) {
            val p = prevHigh
            val tag = if (p == null || c.high >= p) SwingTag.HH else SwingTag.LH
            val confirmIdx = (idx + pivotLookback).coerceAtMost(recentWindow.lastIndex)
            // Keep backward compatibility: tag sequence begins once at least 1 prior pivot exists or include first if needed
            if (p != null) {
                confirmedSwings.add(
                    SwingPoint(
                        candleIndex = idx,
                        openTime = c.openTime,
                        price = c.high,
                        isHigh = true,
                        tag = tag,
                        confirmationStatus = PivotConfirmationStatus.CONFIRMED,
                        confirmedAtCandleOpenTime = recentWindow[confirmIdx].openTime
                    )
                )
            }
            prevHigh = c.high
        }

        var prevLow: Double? = null
        for ((idx, c) in confirmedLows) {
            val p = prevLow
            val tag = if (p == null || c.low >= p) SwingTag.HL else SwingTag.LL
            val confirmIdx = (idx + pivotLookback).coerceAtMost(recentWindow.lastIndex)
            if (p != null) {
                confirmedSwings.add(
                    SwingPoint(
                        candleIndex = idx,
                        openTime = c.openTime,
                        price = c.low,
                        isHigh = false,
                        tag = tag,
                        confirmationStatus = PivotConfirmationStatus.CONFIRMED,
                        confirmedAtCandleOpenTime = recentWindow[confirmIdx].openTime
                    )
                )
            }
            prevLow = c.low
        }

        confirmedSwings.sortBy { it.candleIndex }

        // Detect Provisional Pivots in the trailing unconfirmed window (indices > maxConfirmedPivotIdx)
        val provisionalSwings = ArrayList<SwingPoint>()
        val trailingStart = max(pivotLookback, maxConfirmedPivotIdx + 1)
        for (i in trailingStart until recentWindow.size) {
            val candidate = recentWindow[i]
            var isLocalHigh = true
            var isLocalLow = true
            for (left in 1..pivotLookback) {
                if (recentWindow[i - left].high > candidate.high) isLocalHigh = false
                if (recentWindow[i - left].low < candidate.low) isLocalLow = false
            }
            for (right in (i + 1) until recentWindow.size) {
                if (recentWindow[right].high >= candidate.high) isLocalHigh = false
                if (recentWindow[right].low <= candidate.low) isLocalLow = false
            }
            if (isLocalHigh) {
                val refHigh = prevHigh ?: candidate.high
                provisionalSwings.add(
                    SwingPoint(
                        candleIndex = i,
                        openTime = candidate.openTime,
                        price = candidate.high,
                        isHigh = true,
                        tag = if (candidate.high >= refHigh) SwingTag.HH else SwingTag.LH,
                        confirmationStatus = PivotConfirmationStatus.PROVISIONAL,
                        confirmedAtCandleOpenTime = candidate.openTime
                    )
                )
            }
            if (isLocalLow) {
                val refLow = prevLow ?: candidate.low
                provisionalSwings.add(
                    SwingPoint(
                        candleIndex = i,
                        openTime = candidate.openTime,
                        price = candidate.low,
                        isHigh = false,
                        tag = if (candidate.low >= refLow) SwingTag.HL else SwingTag.LL,
                        confirmationStatus = PivotConfirmationStatus.PROVISIONAL,
                        confirmedAtCandleOpenTime = candidate.openTime
                    )
                )
            }
        }

        val lastHigh = confirmedSwings.lastOrNull { it.isHigh }
        val lastLow = confirmedSwings.lastOrNull { !it.isHigh }

        val highTag = lastHigh?.tag
        val lowTag = lastLow?.tag

        val fallbackHigh = confirmedHighs.lastOrNull()?.second?.high ?: recentWindow.takeLast(20).maxOfOrNull { it.high }
        val fallbackLow = confirmedLows.lastOrNull()?.second?.low ?: recentWindow.takeLast(20).minOfOrNull { it.low }

        val effectiveAtr = atr14 ?: estimateAtr14(recentWindow)

        // Detect BOS, CHoCH, and Order Block Candidates without look-ahead bias
        val (breakEvents, obCandidates) = detectStructureBreaksAndOrderBlocks(
            candles = recentWindow,
            confirmedHighs = confirmedHighs,
            confirmedLows = confirmedLows,
            pivotLookback = pivotLookback,
            effectiveAtr = effectiveAtr
        )

        // Detect Equal Highs (EQH) & Equal Lows (EQL)
        val eqLevels = detectEqualHighsAndLows(
            candles = recentWindow,
            confirmedHighs = confirmedHighs,
            confirmedLows = confirmedLows,
            pivotLookback = pivotLookback,
            effectiveAtr = effectiveAtr,
            atrToleranceFraction = equalLevelAtrTolerance
        )

        val latestBos = breakEvents.lastOrNull { it.type == StructureBreakType.BOS }
        val latestChoch = breakEvents.lastOrNull { it.type == StructureBreakType.CHOCH }

        val state: StructureState
        val schemaCode: String
        val swingSummary: String

        when {
            highTag == SwingTag.HH && lowTag == SwingTag.HL -> {
                state = StructureState.BULLISH
                schemaCode = "HH_HL"
                swingSummary = "Higher High / Higher Low"
            }
            highTag == SwingTag.LH && lowTag == SwingTag.LL -> {
                state = StructureState.BEARISH
                schemaCode = "LH_LL"
                swingSummary = "Lower High / Lower Low"
            }
            highTag == SwingTag.HH && lowTag == SwingTag.LL -> {
                state = StructureState.TRANSITIONAL
                schemaCode = "HH_LL"
                swingSummary = "Higher High / Lower Low (Transitional)"
            }
            highTag == SwingTag.LH && lowTag == SwingTag.HL -> {
                state = StructureState.TRANSITIONAL
                schemaCode = "LH_HL"
                swingSummary = "Lower High / Higher Low (Transitional)"
            }
            else -> {
                state = StructureState.SIDEWAYS
                schemaCode = "SIDEWAYS"
                swingSummary = "Consolidation / Range"
            }
        }

        val combinedRecent = (confirmedSwings.takeLast(8) + provisionalSwings.takeLast(2))
            .sortedBy { it.candleIndex }

        return MarketStructureAnalysis(
            state = state,
            schemaCode = schemaCode,
            swingSummary = swingSummary,
            biasLabel = state.biasTerminology,
            lastHighTag = highTag,
            lastLowTag = lowTag,
            lastSwingHighPrice = lastHigh?.price ?: fallbackHigh,
            lastSwingLowPrice = lastLow?.price ?: fallbackLow,
            recentSwings = confirmedSwings.takeLast(8),
            confirmedSwings = confirmedSwings.takeLast(12),
            provisionalSwings = provisionalSwings.takeLast(4),
            structureBreaks = breakEvents.takeLast(6),
            latestBos = latestBos,
            latestChoch = latestChoch,
            equalHighsLows = eqLevels.takeLast(6),
            orderBlockCandidates = obCandidates.takeLast(4)
        )
    }

    /**
     * Explicit, deterministic BOS & CHoCH detection without look-ahead bias:
     * - At any closed candle index `k`, only pivots whose confirmation bar `pivotIdx + pivotLookback < k`
     *   are visible.
     * - Tracks the prevailing structural bias (`BULLISH`, `BEARISH`, or `NEUTRAL`):
     *   - When a closed candle closes above the active confirmed Swing High (`c.close > activeHigh.price`):
     *     - If prevailing bias was `BEARISH` -> emits **Bullish CHoCH** and shifts prevailing bias to `BULLISH`.
     *     - Otherwise (`BULLISH` or initial `NEUTRAL` with Higher Low) -> emits **Bullish BOS** and sets bias to `BULLISH`.
     *   - When a closed candle closes below the active confirmed Swing Low (`c.close < activeLow.price`):
     *     - If prevailing bias was `BULLISH` -> emits **Bearish CHoCH** and shifts prevailing bias to `BEARISH`.
     *     - Otherwise (`BEARISH` or initial `NEUTRAL` with Lower High) -> emits **Bearish BOS** and sets bias to `BEARISH`.
     */
    private fun detectStructureBreaksAndOrderBlocks(
        candles: List<Candle>,
        confirmedHighs: List<Pair<Int, Candle>>,
        confirmedLows: List<Pair<Int, Candle>>,
        pivotLookback: Int,
        effectiveAtr: Double
    ): Pair<List<StructureBreakEvent>, List<OrderBlockCandidate>> {
        val breaks = ArrayList<StructureBreakEvent>()
        val rawObs = ArrayList<OrderBlockCandidate>()

        var brokenHighOpenTime = -1L
        var brokenLowOpenTime = -1L
        var prevailingTrend = 0 // +1 = Bullish, -1 = Bearish, 0 = Neutral

        for (k in (pivotLookback * 2) until candles.size) {
            val bar = candles[k]
            if (!bar.isClosed) continue

            // Only pivots confirmed strictly before bar k (idx + pivotLookback < k) are known at bar k
            val latestKnownHigh = confirmedHighs.lastOrNull { (idx, _) -> idx + pivotLookback < k }
            val latestKnownLow = confirmedLows.lastOrNull { (idx, _) -> idx + pivotLookback < k }

            if (latestKnownHigh != null && latestKnownHigh.second.openTime != brokenHighOpenTime) {
                val swingCandle = latestKnownHigh.second
                if (bar.close > swingCandle.high) {
                    val breakType = if (prevailingTrend == -1) {
                        StructureBreakType.CHOCH
                    } else {
                        StructureBreakType.BOS
                    }
                    breaks.add(
                        StructureBreakEvent(
                            type = breakType,
                            isBullish = true,
                            brokenLevelPrice = swingCandle.high,
                            brokenSwingOpenTime = swingCandle.openTime,
                            breakCandleIndex = k,
                            breakCandleOpenTime = bar.openTime,
                            breakClosePrice = bar.close,
                            isBodyCloseConfirmed = true,
                            description = "Bullish ${breakType.code}: closed at ${roundPriceCleanly(bar.close)} above confirmed swing high ${roundPriceCleanly(swingCandle.high)}"
                        )
                    )
                    brokenHighOpenTime = swingCandle.openTime
                    prevailingTrend = 1

                    // Order Block Candidate rule: last bearish candle within 5 bars before the break
                    // if displacement range across the break move >= 1.15 * ATR
                    val displacement = bar.close - candles[max(0, k - 3)].low
                    if (effectiveAtr <= 0.0 || displacement >= effectiveAtr * 1.15) {
                        val originIdx = (k - 1 downTo max(0, k - 6)).firstOrNull { idx ->
                            candles[idx].close < candles[idx].open
                        }
                        if (originIdx != null) {
                            val obBar = candles[originIdx]
                            rawObs.add(
                                OrderBlockCandidate(
                                    isBullish = true,
                                    zoneLow = obBar.low,
                                    zoneHigh = max(obBar.open, obBar.close),
                                    originCandleOpenTime = obBar.openTime,
                                    displacementBreakType = breakType
                                )
                            )
                        }
                    }
                }
            }

            if (latestKnownLow != null && latestKnownLow.second.openTime != brokenLowOpenTime) {
                val swingCandle = latestKnownLow.second
                if (bar.close < swingCandle.low) {
                    val breakType = if (prevailingTrend == 1) {
                        StructureBreakType.CHOCH
                    } else {
                        StructureBreakType.BOS
                    }
                    breaks.add(
                        StructureBreakEvent(
                            type = breakType,
                            isBullish = false,
                            brokenLevelPrice = swingCandle.low,
                            brokenSwingOpenTime = swingCandle.openTime,
                            breakCandleIndex = k,
                            breakCandleOpenTime = bar.openTime,
                            breakClosePrice = bar.close,
                            isBodyCloseConfirmed = true,
                            description = "Bearish ${breakType.code}: closed at ${roundPriceCleanly(bar.close)} below confirmed swing low ${roundPriceCleanly(swingCandle.low)}"
                        )
                    )
                    brokenLowOpenTime = swingCandle.openTime
                    prevailingTrend = -1

                    // Bearish Order Block Candidate: last bullish candle within 5 bars before the break
                    val displacement = candles[max(0, k - 3)].high - bar.close
                    if (effectiveAtr <= 0.0 || displacement >= effectiveAtr * 1.15) {
                        val originIdx = (k - 1 downTo max(0, k - 6)).firstOrNull { idx ->
                            candles[idx].close > candles[idx].open
                        }
                        if (originIdx != null) {
                            val obBar = candles[originIdx]
                            rawObs.add(
                                OrderBlockCandidate(
                                    isBullish = false,
                                    zoneLow = min(obBar.open, obBar.close),
                                    zoneHigh = obBar.high,
                                    originCandleOpenTime = obBar.openTime,
                                    displacementBreakType = breakType
                                )
                            )
                        }
                    }
                }
            }
        }

        // Update Order Block Candidate mitigation / invalidation status across subsequent candles
        val evaluatedObs = rawObs.map { ob ->
            var mitigated = false
            var invalidated = false
            var invalidatedTime: Long? = null
            for (c in candles) {
                if (c.openTime <= ob.originCandleOpenTime + 1L) continue
                if (ob.isBullish) {
                    if (c.low <= ob.zoneHigh && c.low >= ob.zoneLow) mitigated = true
                    if (c.isClosed && c.close < ob.zoneLow) {
                        invalidated = true
                        invalidatedTime = c.openTime
                        break
                    }
                } else {
                    if (c.high >= ob.zoneLow && c.high <= ob.zoneHigh) mitigated = true
                    if (c.isClosed && c.close > ob.zoneHigh) {
                        invalidated = true
                        invalidatedTime = c.openTime
                        break
                    }
                }
            }
            ob.copy(
                isMitigated = mitigated,
                isInvalidated = invalidated,
                invalidatedAtOpenTime = invalidatedTime
            )
        }

        return breaks to evaluatedObs
    }

    private fun detectEqualHighsAndLows(
        candles: List<Candle>,
        confirmedHighs: List<Pair<Int, Candle>>,
        confirmedLows: List<Pair<Int, Candle>>,
        pivotLookback: Int,
        effectiveAtr: Double,
        atrToleranceFraction: Double
    ): List<EqualHighLowLevel> {
        val results = ArrayList<EqualHighLowLevel>()
        val latestPrice = candles.lastOrNull()?.close ?: 0.0
        val tolerance = computeDynamicTolerance(
            referencePrice = latestPrice,
            atr14 = effectiveAtr,
            atrFraction = atrToleranceFraction
        )
        if (tolerance <= 0.0) return emptyList()

        // Equal Highs (EQH) between consecutive or nearby confirmed swing highs
        for (i in 1 until confirmedHighs.size) {
            val (idx1, c1) = confirmedHighs[i - 1]
            val (idx2, c2) = confirmedHighs[i]
            if (abs(c1.high - c2.high) <= tolerance) {
                val avgHigh = (c1.high + c2.high) / 2.0
                val confirmAfterIdx = (idx2 + pivotLookback).coerceAtMost(candles.lastIndex)
                // Check if invalidated by a decisive close > avgHigh + tolerance
                val invalidatingBar = candles.drop(confirmAfterIdx + 1).firstOrNull {
                    it.isClosed && it.close > avgHigh + tolerance
                }
                results.add(
                    EqualHighLowLevel(
                        isEqualHigh = true,
                        averagePrice = roundPriceCleanly(avgHigh),
                        firstSwingOpenTime = c1.openTime,
                        secondSwingOpenTime = c2.openTime,
                        toleranceUsed = roundPriceCleanly(tolerance),
                        isInvalidated = invalidatingBar != null,
                        invalidatedAtOpenTime = invalidatingBar?.openTime
                    )
                )
            }
        }

        // Equal Lows (EQL) between consecutive or nearby confirmed swing lows
        for (i in 1 until confirmedLows.size) {
            val (idx1, c1) = confirmedLows[i - 1]
            val (idx2, c2) = confirmedLows[i]
            if (abs(c1.low - c2.low) <= tolerance) {
                val avgLow = (c1.low + c2.low) / 2.0
                val confirmAfterIdx = (idx2 + pivotLookback).coerceAtMost(candles.lastIndex)
                val invalidatingBar = candles.drop(confirmAfterIdx + 1).firstOrNull {
                    it.isClosed && it.close < avgLow - tolerance
                }
                results.add(
                    EqualHighLowLevel(
                        isEqualHigh = false,
                        averagePrice = roundPriceCleanly(avgLow),
                        firstSwingOpenTime = c1.openTime,
                        secondSwingOpenTime = c2.openTime,
                        toleranceUsed = roundPriceCleanly(tolerance),
                        isInvalidated = invalidatingBar != null,
                        invalidatedAtOpenTime = invalidatingBar?.openTime
                    )
                )
            }
        }

        return results.sortedBy { it.secondSwingOpenTime }
    }

    private fun estimateAtr14(candles: List<Candle>): Double {
        if (candles.size < 2) return (candles.lastOrNull()?.let { it.high - it.low } ?: 0.0)
        val window = candles.takeLast(15)
        var sumTr = 0.0
        var count = 0
        for (i in 1 until window.size) {
            val c = window[i]
            val prevClose = window[i - 1].close
            val tr = maxOf(
                c.high - c.low,
                abs(c.high - prevClose),
                abs(c.low - prevClose)
            )
            sumTr += tr
            count++
        }
        return if (count > 0) sumTr / count else 0.0
    }
}
