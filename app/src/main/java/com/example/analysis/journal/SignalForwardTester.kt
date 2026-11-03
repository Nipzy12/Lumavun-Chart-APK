package com.example.analysis.journal

import com.example.analysis.signal.SignalEngine
import com.example.analysis.signal.SignalLifecycleStatus
import com.example.market.candle.Candle
import com.example.simulation.paper.PaperTradingEngine
import com.example.storage.watchlist.SignalJournalEntity
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

enum class SignalOutcomeStatus(val code: String, val displayBadge: String) {
    ACTIVE("ACTIVE", "ACTIVE"),
    ENTRY_TRIGGERED("ENTRY_TRIGGERED", "ENTRY TRIGGERED"),
    TP1_HIT("TP1_HIT", "TP1 HIT"),
    TP2_HIT("TP2_HIT", "TP2 HIT"),
    TP3_HIT("TP3_HIT", "TP3 HIT"),
    SL_HIT("SL_HIT", "SL HIT"),
    INVALIDATED("INVALIDATED", "INVALIDATED"),
    AMBIGUOUS("AMBIGUOUS", "AMBIGUOUS"),
    EXPIRED("EXPIRED", "EXPIRED"),
    CLOSED("CLOSED", "CLOSED"),
    CANCELLED("CANCELLED", "CANCELLED"),
    WAIT("WAIT", "WAIT")
}

data class ForwardEvaluationUpdate(
    val status: String,
    val highestTpReached: Int,
    val realizedR: Double,
    val resolvedAt: Long?
)

enum class SampleSizeClassification(val label: String, val hasSufficientSample: Boolean) {
    INSUFFICIENT("Insufficient sample size", false),
    EARLY_DATA("Early data", true),
    DEVELOPING("Developing statistics", true),
    MEANINGFUL("More meaningful sample", true);

    companion object {
        fun fromCount(totalSignals: Int): SampleSizeClassification {
            return when {
                totalSignals < 20 -> INSUFFICIENT
                totalSignals in 20..49 -> EARLY_DATA
                totalSignals in 50..99 -> DEVELOPING
                else -> MEANINGFUL
            }
        }
    }
}

enum class DateRangeFilter(val label: String, val durationMs: Long?) {
    ALL("All Time", null),
    DAYS_7("7D", 7L * 24 * 3600 * 1000L),
    DAYS_30("30D", 30L * 24 * 3600 * 1000L)
}

enum class DecisionSideFilter(val label: String) {
    ALL("ALL"),
    LONG("LONG"),
    SHORT("SHORT")
}

enum class SetupGradeFilter(val label: String, val code: String) {
    ALL("All Grades", "ALL"),
    A_PLUS("A+", "A+"),
    A("A", "A"),
    B("B", "B"),
    WAIT("WAIT", "WAIT")
}

enum class SignalStatusFilter(val label: String, val code: String) {
    ALL("All Status", "ALL"),
    ACTIVE("Active", "ACTIVE"),
    ENTRY_TRIGGERED("Triggered", "ENTRY_TRIGGERED"),
    TP_HIT("TP Hit", "TP_HIT"),
    SL_HIT("SL Hit", "SL_HIT"),
    INVALIDATED("Invalidated", "INVALIDATED"),
    EXPIRED("Expired", "EXPIRED")
}

enum class PerformanceScenario(val label: String) {
    OPTIMISTIC("Optimis"),
    PESSIMISTIC("Pesimis")
}

enum class SignalSourceFilter(val label: String, val code: String) {
    AI("AI Signals", "AI"),
    BASELINE("Baseline (EMA)", "BASELINE")
}

data class SourceComparisonSummary(
    val aiNetR: Double = 0.0,
    val aiExpectancyR: Double = 0.0,
    val aiSampleSize: Int = 0,
    val baselineNetR: Double = 0.0,
    val baselineExpectancyR: Double = 0.0,
    val baselineSampleSize: Int = 0
)

data class JournalFilterState(
    val symbolFilter: String = "ALL",
    val timeframeFilter: String = "ALL",
    val dateRangeFilter: DateRangeFilter = DateRangeFilter.ALL,
    val decisionFilter: DecisionSideFilter = DecisionSideFilter.ALL,
    val gradeFilter: SetupGradeFilter = SetupGradeFilter.ALL,
    val statusFilter: SignalStatusFilter = SignalStatusFilter.ALL,
    val scenario: PerformanceScenario = PerformanceScenario.OPTIMISTIC,
    val sourceFilter: SignalSourceFilter = SignalSourceFilter.AI
)

data class ConfidenceBucketStat(
    val label: String,
    val sampleSize: Int,
    val wins: Int,
    val winRatePct: Double,
    val hasSufficientData: Boolean = sampleSize >= SignalForwardTester.MIN_CONFIDENCE_BUCKET_SAMPLES
)

data class SignalPerformanceMetrics(
    val totalSignals: Int,
    val resolvedTradeCount: Int,
    val wins: Int,
    val losses: Int,
    val waitSignals: Int,
    val ambiguousCount: Int,
    val activeCount: Int,
    val expiredCount: Int,
    val winRate: Double,
    val wilsonLowerPct: Double = 0.0,
    val wilsonUpperPct: Double = 0.0,
    val averageRR: Double,
    val expectancyR: Double,
    val totalR: Double,
    val maxDrawdownR: Double,
    val profitFactor: Double,
    val bestTimeframe: String,
    val worstTimeframe: String,
    val bestSymbol: String,
    val worstSymbol: String,
    val longWinRate: Double,
    val shortWinRate: Double,
    val sampleClassification: SampleSizeClassification,
    val entryTriggeredCount: Int = 0,
    val invalidatedCount: Int = 0,
    val legacySignalCount: Int = 0,
    val scenario: PerformanceScenario = PerformanceScenario.OPTIMISTIC,
    val confidenceBuckets: List<ConfidenceBucketStat> = emptyList(),
    val sourceFilter: SignalSourceFilter = SignalSourceFilter.AI,
    val sourceComparison: SourceComparisonSummary = SourceComparisonSummary()
)

object SignalForwardTester {

    const val MAX_BARS_BEFORE_EXPIRY = 48
    const val DEFAULT_TIME_STOP_BARS = 96
    const val MIN_TRADES_FOR_GROUP_RANKING = 5
    const val MIN_CONFIDENCE_BUCKET_SAMPLES = 30
    private const val WILSON_Z_95 = 1.96

    // Configurable fixed partial exit fractions at TP1, TP2, TP3 (default 1/3 each)
    const val TP1_EXIT_FRACTION: Double = 1.0 / 3.0
    const val TP2_EXIT_FRACTION: Double = 1.0 / 3.0
    const val TP3_EXIT_FRACTION: Double = 1.0 / 3.0

    const val LEGACY_EXIT_MODEL_VERSION: Int = 1
    const val CURRENT_EXIT_MODEL_VERSION: Int = 2

    /**
     * Computes round-trip trading cost in R units using the shared default fee & slippage bps from PaperTradingEngine:
     * cost (in R) = ((feeBps * 2 + slippageBps * 2) / 10000 * entry) / risk
     */
    fun computeCostInR(
        entryPrice: Double,
        risk: Double,
        feeBps: Double = PaperTradingEngine.DEFAULT_FEE_RATE_BPS,
        slippageBps: Double = PaperTradingEngine.DEFAULT_SLIPPAGE_BPS
    ): Double {
        if (entryPrice <= 0.0 || risk <= 1e-8) return 0.0
        val roundTripRate = (feeBps * 2.0 + slippageBps * 2.0) / 10000.0
        return (roundTripRate * entryPrice) / risk
    }

    /**
     * Computes net realized R under the fixed 1/3 TP1, 1/3 TP2, 1/3 TP3 partial exit model:
     * realizedR = sum(fraction * R_tp_hit) + remainingFraction * stopOrExitR - costInR
     * where stopOrExitR is -1.0 before TP1, 0.0 at breakeven after TP1, or custom R on time-stop.
     */
    fun computeRealizedR(
        highestTpReached: Int,
        rTp1: Double,
        rTp2: Double,
        rTp3: Double,
        remainingExitR: Double,
        costInR: Double
    ): Double {
        var realized = 0.0
        var remainingFraction = 1.0
        if (highestTpReached >= 1) {
            realized += TP1_EXIT_FRACTION * rTp1
            remainingFraction -= TP1_EXIT_FRACTION
        }
        if (highestTpReached >= 2) {
            realized += TP2_EXIT_FRACTION * rTp2
            remainingFraction -= TP2_EXIT_FRACTION
        }
        if (highestTpReached >= 3) {
            realized += TP3_EXIT_FRACTION * rTp3
            remainingFraction = 0.0
        }
        remainingFraction = remainingFraction.coerceAtLeast(0.0)
        val grossR = realized + (remainingFraction * remainingExitR)
        return roundR(grossR - costInR)
    }

    /**
     * Requirement #4 & #8: Forward-tests a single signal strictly against SUBSEQUENT market candles
     * (`candle.openTime > signal.candleOpenTimeAtSignal`).
     *
     * Rules:
     * - Zero look-ahead bias: ignores any candle with openTime <= candleOpenTimeAtSignal.
     * - WAIT signals remain "WAIT" (never counted as wins or losses).
     * - Fixed exit model: closes 1/3 at TP1, 1/3 at TP2, 1/3 at TP3. Once TP1 is hit, the stop
     *   for the remaining position moves to entryPrice (breakeven).
     * - Pessimistic intrabar ordering after TP1: if both a next TP and the breakeven stop (entryMid)
     *   are touched inside the same candle after entry, assumes the breakeven stop was hit first
     *   and closes the trade as CLOSED at the previously reached TP level.
     */
    fun evaluateSignalAgainstCandles(
        signal: SignalJournalEntity,
        candles: List<Candle>,
        timeStopBars: Int = DEFAULT_TIME_STOP_BARS
    ): ForwardEvaluationUpdate? {
        if (signal.decision.equals("WAIT", ignoreCase = true) || signal.status == "WAIT") {
            return if (signal.status != "WAIT") {
                ForwardEvaluationUpdate("WAIT", 0, 0.0, signal.timestamp)
            } else {
                null
            }
        }

        // Terminal states do not change once resolved
        if (signal.status in listOf("TP3_HIT", "SL_HIT", "INVALIDATED", "AMBIGUOUS", "EXPIRED", "CLOSED", "CANCELLED")) {
            return null
        }

        // Strict forward-only filter: only candles AFTER the candle in which the signal was created
        val subsequentCandles = candles
            .filter { it.openTime > signal.candleOpenTimeAtSignal }
            .sortedBy { it.openTime }

        if (subsequentCandles.isEmpty()) return null

        val isLong = signal.decision.equals("LONG", ignoreCase = true)
        val entryMid = if (signal.entryPrice > 0.0) signal.entryPrice else (signal.entryMin + signal.entryMax) / 2.0
        val risk = abs(entryMid - signal.stopLoss).coerceAtLeast(1e-8)
        val costR = computeCostInR(entryMid, risk)

        val rTp1 = if (signal.tp1 > 0.0) abs(signal.tp1 - entryMid) / risk else 0.0
        val rTp2 = if (signal.tp2 > 0.0) abs(signal.tp2 - entryMid) / risk else 0.0
        val rTp3 = if (signal.tp3 > 0.0) abs(signal.tp3 - entryMid) / risk else 0.0

        // If subsequentCandles includes candles prior to signal.resolvedAt, let replay reconstruct highestTp chronologically
        val hasPreResolutionCandles = signal.resolvedAt != null &&
            subsequentCandles.first().openTime <= signal.resolvedAt
        var highestTp = if (hasPreResolutionCandles) 0 else signal.highestTpReached
        var currentStatus = if (hasPreResolutionCandles) "ACTIVE" else signal.status
        var realizedR = if (hasPreResolutionCandles) 0.0 else signal.realizedR
        var resolvedTime = if (hasPreResolutionCandles) null else signal.resolvedAt

        // Check if trade was already triggered at signal creation (priceAtSignal inside entryZone)
        // or already in an post-entry status before evaluating subsequent candles.
        val triggeredAtCreation = signal.priceAtSignal in signal.entryMin..signal.entryMax
        var entryTriggered = highestTp > 0 ||
            currentStatus in listOf("ENTRY_TRIGGERED", "TP1_HIT", "TP2_HIT") ||
            triggeredAtCreation
        if (entryTriggered && currentStatus == "ACTIVE") {
            currentStatus = "ENTRY_TRIGGERED"
        }

        for ((idx, candle) in subsequentCandles.withIndex()) {
            val hitSl = if (isLong) candle.low <= signal.stopLoss else candle.high >= signal.stopLoss
            val hitBreakevenStop = if (isLong) candle.low <= entryMid else candle.high >= entryMid
            val hitTp1 = signal.tp1 > 0.0 && (if (isLong) candle.high >= signal.tp1 else candle.low <= signal.tp1)
            val hitTp2 = signal.tp2 > 0.0 && (if (isLong) candle.high >= signal.tp2 else candle.low <= signal.tp2)
            val hitTp3 = signal.tp3 > 0.0 && (if (isLong) candle.high >= signal.tp3 else candle.low <= signal.tp3)

            if (!entryTriggered) {
                val touchedEntry = candle.low <= signal.entryMax && candle.high >= signal.entryMin
                // Tahap 3: Before entry is triggered, touching SL or touching TP1 (missed move) -> INVALIDATED
                if ((hitSl || hitTp1 || hitTp2 || hitTp3) && !touchedEntry) {
                    currentStatus = "INVALIDATED"
                    realizedR = 0.0
                    resolvedTime = candle.closeTime
                    break
                }
                if (touchedEntry) {
                    // TAHAP 2: Intrabar ambiguity when entry is newly triggered inside this candle.
                    // Because OHLC data does not reveal whether entry was touched before or after SL/TP1
                    // within the same bar, if the entry-triggering candle also reaches SL or TP1, mark AMBIGUOUS.
                    if (hitSl || hitTp1 || hitTp2 || hitTp3) {
                        currentStatus = "AMBIGUOUS"
                        realizedR = 0.0
                        resolvedTime = candle.closeTime
                        break
                    }
                    entryTriggered = true
                    if (currentStatus == "ACTIVE") {
                        currentStatus = "ENTRY_TRIGGERED"
                    }
                    continue
                } else {
                    // TAHAP 5: 48-bar expiry applies ONLY before entry is triggered
                    val isPastExpiry = signal.expiryAt > 0L && candle.closeTime > signal.expiryAt
                    if (isPastExpiry || idx + 1 >= MAX_BARS_BEFORE_EXPIRY) {
                        currentStatus = "EXPIRED"
                        realizedR = 0.0
                        resolvedTime = candle.closeTime
                        break
                    }
                    continue
                }
            }

            // Before TP1 is reached, stop is at initial stopLoss.
            // If both TP and initial SL are touched inside the same candle while highestTp == 0, mark AMBIGUOUS.
            if (highestTp == 0 && hitSl && (hitTp1 || hitTp2 || hitTp3)) {
                currentStatus = "AMBIGUOUS"
                realizedR = 0.0
                resolvedTime = candle.closeTime
                break
            }

            if (highestTp == 0 && hitSl) {
                currentStatus = "SL_HIT"
                realizedR = computeRealizedR(
                    highestTpReached = 0,
                    rTp1 = rTp1,
                    rTp2 = rTp2,
                    rTp3 = rTp3,
                    remainingExitR = -1.0,
                    costInR = costR
                )
                resolvedTime = candle.closeTime
                break
            }

            // Pessimistic intrabar rule after TP1: once highestTp >= 1, the stop for the remaining
            // position is at entryMid (breakeven). If a candle touches both the breakeven stop and
            // a higher TP (TP2/TP3), we pessimistically assume the breakeven stop was hit first.
            if (highestTp >= 1 && hitBreakevenStop) {
                currentStatus = "CLOSED"
                realizedR = computeRealizedR(
                    highestTpReached = highestTp,
                    rTp1 = rTp1,
                    rTp2 = rTp2,
                    rTp3 = rTp3,
                    remainingExitR = 0.0,
                    costInR = costR
                )
                resolvedTime = candle.closeTime
                break
            }

            if (hitTp3 && highestTp < 3) {
                highestTp = 3
                currentStatus = "TP3_HIT"
                realizedR = computeRealizedR(
                    highestTpReached = 3,
                    rTp1 = rTp1,
                    rTp2 = rTp2,
                    rTp3 = rTp3,
                    remainingExitR = 0.0,
                    costInR = costR
                )
                resolvedTime = candle.closeTime
                break
            } else if (hitTp2 && highestTp < 2) {
                highestTp = 2
                currentStatus = "TP2_HIT"
                realizedR = computeRealizedR(
                    highestTpReached = 2,
                    rTp1 = rTp1,
                    rTp2 = rTp2,
                    rTp3 = rTp3,
                    remainingExitR = 0.0,
                    costInR = costR
                )
                resolvedTime = candle.closeTime
            } else if (hitTp1 && highestTp < 1) {
                highestTp = 1
                currentStatus = "TP1_HIT"
                realizedR = computeRealizedR(
                    highestTpReached = 1,
                    rTp1 = rTp1,
                    rTp2 = rTp2,
                    rTp3 = rTp3,
                    remainingExitR = 0.0,
                    costInR = costR
                )
                resolvedTime = candle.closeTime
            }

            // TAHAP 5: Separate time-stop (default 96 bars) for trades that have already entered.
            // Closes any remaining position at this candle's close price, computes net R, and marks CLOSED.
            if (idx + 1 >= timeStopBars) {
                val closeMoveR = if (isLong) {
                    (candle.close - entryMid) / risk
                } else {
                    (entryMid - candle.close) / risk
                }
                currentStatus = "CLOSED"
                realizedR = computeRealizedR(
                    highestTpReached = highestTp,
                    rTp1 = rTp1,
                    rTp2 = rTp2,
                    rTp3 = rTp3,
                    remainingExitR = closeMoveR,
                    costInR = costR
                )
                resolvedTime = candle.closeTime
                break
            }
        }

        if (currentStatus == signal.status &&
            highestTp == signal.highestTpReached &&
            abs(realizedR - signal.realizedR) < 1e-6
        ) {
            return null
        }

        return ForwardEvaluationUpdate(
            status = currentStatus,
            highestTpReached = highestTp,
            realizedR = realizedR,
            resolvedAt = resolvedTime
        )
    }

    /**
     * Requirement #1, #3 & Tahap 3: Evaluates an active signal against a real-time price tick
     * using the shared `SignalEngine.transitionSignalStatusOnLivePrice` pure state machine.
     */
    fun evaluateSignalAgainstLivePrice(
        signal: SignalJournalEntity,
        livePrice: Double,
        nowMs: Long = System.currentTimeMillis()
    ): ForwardEvaluationUpdate? {
        if (livePrice <= 0.0 || signal.decision.equals("WAIT", ignoreCase = true)) return null
        val currentLifecycle = SignalLifecycleStatus.fromCode(signal.status)
        if (currentLifecycle.isTerminal) return null

        val isLong = signal.decision.equals("LONG", ignoreCase = true)
        val entryMid = if (signal.entryPrice > 0.0) signal.entryPrice else (signal.entryMin + signal.entryMax) / 2.0
        val risk = abs(entryMid - signal.stopLoss).coerceAtLeast(1e-8)
        val costR = computeCostInR(entryMid, risk)

        val rTp1 = if (signal.tp1 > 0.0) abs(signal.tp1 - entryMid) / risk else 0.0
        val rTp2 = if (signal.tp2 > 0.0) abs(signal.tp2 - entryMid) / risk else 0.0
        val rTp3 = if (signal.tp3 > 0.0) abs(signal.tp3 - entryMid) / risk else 0.0

        val next = SignalEngine.transitionSignalStatusOnLivePrice(
            currentStatus = currentLifecycle,
            highestTpReached = signal.highestTpReached,
            isLong = isLong,
            entryPrice = entryMid,
            entryMin = signal.entryMin,
            entryMax = signal.entryMax,
            stopLoss = signal.stopLoss,
            tp1 = signal.tp1,
            tp2 = signal.tp2,
            tp3 = signal.tp3,
            expiryAt = signal.expiryAt,
            livePrice = livePrice,
            nowMs = nowMs
        )

        val nextStatusCode = next.status.code
        val nextHighestTp = next.highestTpReached

        val (realizedR, resolvedTime) = when (next.status) {
            SignalLifecycleStatus.EXPIRED,
            SignalLifecycleStatus.INVALIDATED -> 0.0 to nowMs
            SignalLifecycleStatus.ENTRY_TRIGGERED -> signal.realizedR to signal.resolvedAt
            SignalLifecycleStatus.SL_HIT -> computeRealizedR(0, rTp1, rTp2, rTp3, -1.0, costR) to nowMs
            SignalLifecycleStatus.CLOSED -> computeRealizedR(nextHighestTp, rTp1, rTp2, rTp3, 0.0, costR) to nowMs
            SignalLifecycleStatus.TP1_HIT -> computeRealizedR(1, rTp1, rTp2, rTp3, 0.0, costR) to nowMs
            SignalLifecycleStatus.TP2_HIT -> computeRealizedR(2, rTp1, rTp2, rTp3, 0.0, costR) to nowMs
            SignalLifecycleStatus.TP3_HIT -> computeRealizedR(3, rTp1, rTp2, rTp3, 0.0, costR) to nowMs
            else -> signal.realizedR to signal.resolvedAt
        }

        if (nextStatusCode == signal.status &&
            nextHighestTp == signal.highestTpReached &&
            abs(realizedR - signal.realizedR) < 1e-6
        ) {
            return null
        }

        return ForwardEvaluationUpdate(
            status = nextStatusCode,
            highestTpReached = nextHighestTp,
            realizedR = realizedR,
            resolvedAt = resolvedTime
        )
    }

    /**
     * Requirement #3, #14 & #16: Calculates performance dashboard metrics with support for filtering by
     * Symbol, Timeframe, Date range, LONG/SHORT, Setup Grade, and Status.
     */
    fun filterSignals(
        allSignals: List<SignalJournalEntity>,
        filter: JournalFilterState,
        nowMs: Long = System.currentTimeMillis()
    ): List<SignalJournalEntity> {
        return allSignals.filter { sig ->
            val matchSymbol = filter.symbolFilter == "ALL" ||
                sig.symbol.equals(filter.symbolFilter, ignoreCase = true)
            val matchTf = filter.timeframeFilter == "ALL" ||
                sig.primaryTimeframe.equals(filter.timeframeFilter, ignoreCase = true)
            val matchDate = filter.dateRangeFilter.durationMs?.let { window ->
                sig.timestamp >= (nowMs - window)
            } ?: true
            val matchDecision = when (filter.decisionFilter) {
                DecisionSideFilter.ALL -> true
                DecisionSideFilter.LONG -> sig.decision.equals("LONG", ignoreCase = true) || sig.decision.equals("WAIT", ignoreCase = true)
                DecisionSideFilter.SHORT -> sig.decision.equals("SHORT", ignoreCase = true) || sig.decision.equals("WAIT", ignoreCase = true)
            }
            val normalizedGrade = when (sig.setupQuality.uppercase(Locale.US)) {
                "A+" -> "A+"
                "A", "HIGH" -> "A"
                "B", "MEDIUM" -> "B"
                else -> "WAIT"
            }
            val matchGrade = when (filter.gradeFilter) {
                SetupGradeFilter.ALL -> true
                SetupGradeFilter.A_PLUS -> normalizedGrade == "A+"
                SetupGradeFilter.A -> normalizedGrade == "A"
                SetupGradeFilter.B -> normalizedGrade == "B"
                SetupGradeFilter.WAIT -> normalizedGrade == "WAIT"
            }
            val matchStatus = when (filter.statusFilter) {
                SignalStatusFilter.ALL -> true
                SignalStatusFilter.ACTIVE -> sig.status == "ACTIVE"
                SignalStatusFilter.ENTRY_TRIGGERED -> sig.status == "ENTRY_TRIGGERED"
                SignalStatusFilter.TP_HIT -> sig.status in listOf("TP1_HIT", "TP2_HIT", "TP3_HIT")
                SignalStatusFilter.SL_HIT -> sig.status == "SL_HIT"
                SignalStatusFilter.INVALIDATED -> sig.status == "INVALIDATED"
                SignalStatusFilter.EXPIRED -> sig.status == "EXPIRED"
            }
            val matchSource = sig.source.ifBlank { "AI" }.equals(filter.sourceFilter.code, ignoreCase = true)
            matchSymbol && matchTf && matchDate && matchDecision && matchGrade && matchStatus && matchSource
        }
    }

    /**
     * Computes the 95% Wilson score confidence interval (lowerPct, upperPct) in [0.0, 100.0]
     * for `wins` successes out of `total` resolved trades.
     */
    fun computeWilson95Interval(wins: Int, total: Int): Pair<Double, Double> {
        if (total <= 0) return 0.0 to 0.0
        val n = total.toDouble()
        val p = (wins.toDouble() / n).coerceIn(0.0, 1.0)
        val z = WILSON_Z_95
        val z2 = z * z
        val denom = 1.0 + z2 / n
        val center = (p + z2 / (2.0 * n)) / denom
        val rad = (p * (1.0 - p) / n) + (z2 / (4.0 * n * n))
        val halfWidth = (z * sqrt(rad.coerceAtLeast(0.0))) / denom
        val lower = roundR(((center - halfWidth).coerceIn(0.0, 1.0)) * 100.0)
        val upper = roundR(((center + halfWidth).coerceIn(0.0, 1.0)) * 100.0)
        return lower to upper
    }

    private fun effectiveNetR(signal: SignalJournalEntity, scenario: PerformanceScenario): Double {
        return if (signal.status == "AMBIGUOUS" && scenario == PerformanceScenario.PESSIMISTIC) {
            -1.0
        } else {
            signal.realizedR
        }
    }

    fun calculatePerformance(
        allSignals: List<SignalJournalEntity>,
        filter: JournalFilterState,
        nowMs: Long = System.currentTimeMillis(),
        scenario: PerformanceScenario = filter.scenario,
        includeComparison: Boolean = true
    ): SignalPerformanceMetrics {
        val allFiltered = filterSignals(allSignals, filter, nowMs)
        val legacySignalCount = allFiltered.count { it.exitModelVersion < CURRENT_EXIT_MODEL_VERSION }
        // Exclude legacy (version 1) signals from current exit-model performance statistics
        val filtered = allFiltered.filter { it.exitModelVersion >= CURRENT_EXIT_MODEL_VERSION }

        val waitSignals = filtered.count { it.decision.equals("WAIT", ignoreCase = true) || it.status == "WAIT" }
        val directionalSignals = filtered.filter {
            val d = it.decision.uppercase(Locale.US)
            if (filter.decisionFilter == DecisionSideFilter.LONG) d == "LONG"
            else if (filter.decisionFilter == DecisionSideFilter.SHORT) d == "SHORT"
            else d == "LONG" || d == "SHORT"
        }

        val resolvedStatuses = if (scenario == PerformanceScenario.PESSIMISTIC) {
            listOf("TP1_HIT", "TP2_HIT", "TP3_HIT", "SL_HIT", "CLOSED", "AMBIGUOUS")
        } else {
            listOf("TP1_HIT", "TP2_HIT", "TP3_HIT", "SL_HIT", "CLOSED")
        }
        val resolvedTrades = directionalSignals.filter { it.status in resolvedStatuses }

        // Wins and losses are determined strictly from net realizedR (> 0 win, < 0 loss, 0 breakeven)
        val winsList = resolvedTrades.filter { effectiveNetR(it, scenario) > 0.0 }
        val lossesList = resolvedTrades.filter { effectiveNetR(it, scenario) < 0.0 }
        val ambiguousCount = directionalSignals.count { it.status == "AMBIGUOUS" }
        val activeCount = directionalSignals.count { it.status in listOf("ACTIVE", "ENTRY_TRIGGERED") }
        val entryTriggeredCount = directionalSignals.count { it.status == "ENTRY_TRIGGERED" }
        val invalidatedCount = directionalSignals.count { it.status == "INVALIDATED" }
        val expiredCount = directionalSignals.count { it.status == "EXPIRED" }

        val wins = winsList.size
        val losses = lossesList.size
        val resolvedCount = resolvedTrades.size

        val totalSignals = directionalSignals.size + waitSignals
        // TAHAP 4: SampleSizeClassification is computed strictly from resolved directional trades, NOT totalSignals
        val sampleClassification = SampleSizeClassification.fromCount(resolvedCount)

        val winRate = if (resolvedCount > 0) {
            roundR((wins.toDouble() / resolvedCount.toDouble()) * 100.0)
        } else {
            0.0
        }
        val (wilsonLower, wilsonUpper) = computeWilson95Interval(wins, resolvedCount)

        val averageRR = if (directionalSignals.isNotEmpty()) {
            roundR(directionalSignals.map { it.riskReward }.average())
        } else {
            0.0
        }

        val totalR = roundR(resolvedTrades.sumOf { effectiveNetR(it, scenario) })
        val expectancyR = if (resolvedCount > 0) {
            roundR(totalR / resolvedCount.toDouble())
        } else {
            0.0
        }

        val grossPosR = winsList.sumOf { effectiveNetR(it, scenario).coerceAtLeast(0.0) }
        val grossNegR = abs(lossesList.sumOf { effectiveNetR(it, scenario).coerceAtMost(0.0) })
        val profitFactor = when {
            grossNegR > 1e-6 -> roundR(grossPosR / grossNegR)
            grossPosR > 0.0 -> 99.0
            else -> 0.0
        }

        // TAHAP 4: Chronological Maximum Drawdown in net R ordered by resolvedAt (fallback to timestamp)
        val chronologicalResolved = resolvedTrades.sortedWith(
            compareBy<SignalJournalEntity> { it.resolvedAt ?: it.timestamp }.thenBy { it.timestamp }
        )
        var cumulativeR = 0.0
        var peakR = 0.0
        var maxDrawdown = 0.0
        for (trade in chronologicalResolved) {
            cumulativeR += effectiveNetR(trade, scenario)
            if (cumulativeR > peakR) {
                peakR = cumulativeR
            }
            val dd = peakR - cumulativeR
            if (dd > maxDrawdown) {
                maxDrawdown = dd
            }
        }
        val maxDrawdownR = roundR(maxDrawdown)

        // TAHAP 4: Best & Worst Timeframe by Total Net R (only for groups with >= 5 resolved trades)
        val byTf = resolvedTrades
            .groupBy { it.primaryTimeframe.uppercase(Locale.US) }
            .filter { (_, list) -> list.size >= MIN_TRADES_FOR_GROUP_RANKING }
        val tfScores = byTf.mapValues { (_, list) -> list.sumOf { effectiveNetR(it, scenario) } }
        val bestTf = tfScores.maxByOrNull { it.value }?.key ?: "--"
        val worstTf = tfScores.minByOrNull { it.value }?.key ?: "--"

        // TAHAP 4: Best & Worst Symbol by Total Net R (only for groups with >= 5 resolved trades)
        val bySym = resolvedTrades
            .groupBy { it.symbol.uppercase(Locale.US) }
            .filter { (_, list) -> list.size >= MIN_TRADES_FOR_GROUP_RANKING }
        val symScores = bySym.mapValues { (_, list) -> list.sumOf { effectiveNetR(it, scenario) } }
        val bestSym = symScores.maxByOrNull { it.value }?.key ?: "--"
        val worstSym = symScores.minByOrNull { it.value }?.key ?: "--"

        // Long Win Rate & Short Win Rate (based on effectiveNetR > 0.0)
        val longResolved = resolvedTrades.filter { it.decision.equals("LONG", ignoreCase = true) }
        val longWins = longResolved.count { effectiveNetR(it, scenario) > 0.0 }
        val longWinRate = if (longResolved.isNotEmpty()) {
            roundR((longWins.toDouble() / longResolved.size.toDouble()) * 100.0)
        } else {
            0.0
        }

        val shortResolved = resolvedTrades.filter { it.decision.equals("SHORT", ignoreCase = true) }
        val shortWins = shortResolved.count { effectiveNetR(it, scenario) > 0.0 }
        val shortWinRate = if (shortResolved.isNotEmpty()) {
            roundR((shortWins.toDouble() / shortResolved.size.toDouble()) * 100.0)
        } else {
            0.0
        }

        // TAHAP 6: Confidence Calibration Buckets (<60, 60-69, 70-79, 80+) with n >= 30 sufficiency check
        val bucketRanges: List<Pair<String, (Int) -> Boolean>> = listOf(
            "<60" to { c: Int -> c < 60 },
            "60-69" to { c: Int -> c in 60..69 },
            "70-79" to { c: Int -> c in 70..79 },
            "80+" to { c: Int -> c >= 80 }
        )
        val confidenceBuckets = bucketRanges.map { (label, predicate) ->
            val bucketTrades = resolvedTrades.filter { predicate(it.confidence) }
            val n = bucketTrades.size
            val bWins = bucketTrades.count { effectiveNetR(it, scenario) > 0.0 }
            val wr = if (n > 0) roundR((bWins.toDouble() / n.toDouble()) * 100.0) else 0.0
            ConfidenceBucketStat(
                label = label,
                sampleSize = n,
                wins = bWins,
                winRatePct = wr,
                hasSufficientData = n >= MIN_CONFIDENCE_BUCKET_SAMPLES
            )
        }

        val comparison = if (includeComparison) {
            if (filter.sourceFilter == SignalSourceFilter.AI) {
                val baseM = calculatePerformance(
                    allSignals = allSignals,
                    filter = filter.copy(sourceFilter = SignalSourceFilter.BASELINE),
                    nowMs = nowMs,
                    scenario = scenario,
                    includeComparison = false
                )
                SourceComparisonSummary(
                    aiNetR = totalR,
                    aiExpectancyR = expectancyR,
                    aiSampleSize = resolvedCount,
                    baselineNetR = baseM.totalR,
                    baselineExpectancyR = baseM.expectancyR,
                    baselineSampleSize = baseM.resolvedTradeCount
                )
            } else {
                val aiM = calculatePerformance(
                    allSignals = allSignals,
                    filter = filter.copy(sourceFilter = SignalSourceFilter.AI),
                    nowMs = nowMs,
                    scenario = scenario,
                    includeComparison = false
                )
                SourceComparisonSummary(
                    aiNetR = aiM.totalR,
                    aiExpectancyR = aiM.expectancyR,
                    aiSampleSize = aiM.resolvedTradeCount,
                    baselineNetR = totalR,
                    baselineExpectancyR = expectancyR,
                    baselineSampleSize = resolvedCount
                )
            }
        } else {
            SourceComparisonSummary()
        }

        return SignalPerformanceMetrics(
            totalSignals = totalSignals,
            resolvedTradeCount = resolvedCount,
            wins = wins,
            losses = losses,
            waitSignals = waitSignals,
            ambiguousCount = ambiguousCount,
            activeCount = activeCount,
            expiredCount = expiredCount,
            winRate = winRate,
            wilsonLowerPct = wilsonLower,
            wilsonUpperPct = wilsonUpper,
            averageRR = averageRR,
            expectancyR = expectancyR,
            totalR = totalR,
            maxDrawdownR = maxDrawdownR,
            profitFactor = profitFactor,
            bestTimeframe = bestTf,
            worstTimeframe = worstTf,
            bestSymbol = bestSym,
            worstSymbol = worstSym,
            longWinRate = longWinRate,
            shortWinRate = shortWinRate,
            sampleClassification = sampleClassification,
            entryTriggeredCount = entryTriggeredCount,
            invalidatedCount = invalidatedCount,
            legacySignalCount = legacySignalCount,
            scenario = scenario,
            confidenceBuckets = confidenceBuckets,
            sourceFilter = filter.sourceFilter,
            sourceComparison = comparison
        )
    }

    internal fun roundR(value: Double): Double {
        if (value.isNaN() || value.isInfinite()) return 0.0
        return kotlin.math.round(value * 100.0) / 100.0
    }
}
