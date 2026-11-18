package com.example.analysis.signal

import com.example.ai.analyst.AiAnalysisRequestContext
import com.example.ai.parser.AiAnalysisResult
import com.example.ai.parser.EntryZone
import com.example.analysis.technical.MarketRegime
import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.analysis.technical.MtfConfirmationStatus
import com.example.storage.preferences.TradingStyleProfile
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * LUMAVUN AI Insight V2.1: Centralized, Deterministic Signal Validation Engine.
 *
 * Provides strict trade geometry verification, no-trade safety gates,
 * mathematical risk/reward recalculation, and safe failure behavior
 * converting any invalid setup to a structured WAIT decision.
 */
object SignalValidationEngine {

    const val MIN_RR_DAY_TRADING: Double = 1.35
    const val MIN_RR_SCALPING: Double = 1.45
    const val MIN_RR_SWING_TRADING: Double = 1.60
    const val MIN_DIRECTIONAL_CONFIDENCE: Int = 60
    const val MIN_CANDLES_REQUIRED: Int = 20
    const val MAX_STALE_PERIOD_MULTIPLIER: Long = 4L
    const val MIN_RISK_DISTANCE_EPSILON: Double = 1e-6
    const val MIN_RISK_DISTANCE_PCT: Double = 0.0002 // 2 bps minimum risk distance

    /**
     * Immutable outcome of a signal validation evaluation.
     */
    data class ValidationOutcome(
        val isValid: Boolean,
        val recomputedRiskReward: Double,
        val rejectionReasons: List<String>,
        val validatedResult: AiAnalysisResult
    )

    /**
     * Resolves the required minimum R:R based on the active trading style profile.
     */
    fun resolveMinimumRiskReward(profile: TradingStyleProfile?): Double {
        return when (profile) {
            TradingStyleProfile.SCALPING -> MIN_RR_SCALPING
            TradingStyleProfile.SWING_TRADING -> MIN_RR_SWING_TRADING
            else -> MIN_RR_DAY_TRADING
        }
    }

    /**
     * Pure validation function for a proposed trade geometry.
     * Checks positive finite prices, entry ordering, SL/TP side and sequence, and minimum risk distance.
     */
    fun validateTradeGeometry(
        decision: String,
        entryLow: Double,
        entryHigh: Double,
        stopLoss: Double,
        takeProfits: List<Double>,
        currentPrice: Double? = null,
        atr14: Double? = null,
        minRequiredRr: Double = MIN_RR_DAY_TRADING
    ): Pair<Boolean, List<String>> {
        val reasons = mutableListOf<String>()
        val cleanDecision = decision.trim().uppercase(Locale.US)

        if (cleanDecision == "WAIT") {
            return Pair(true, emptyList())
        }

        if (cleanDecision != "LONG" && cleanDecision != "SHORT") {
            reasons.add("Unknown decision '$decision' (must be LONG, SHORT, or WAIT)")
            return Pair(false, reasons)
        }

        val isLong = cleanDecision == "LONG"

        // 1. Finite Positive Price Checks
        if (!entryLow.isFinite() || entryLow <= 0.0) {
            reasons.add("Entry zone low ($entryLow) must be a finite positive number")
        }
        if (!entryHigh.isFinite() || entryHigh <= 0.0) {
            reasons.add("Entry zone high ($entryHigh) must be a finite positive number")
        }
        if (!stopLoss.isFinite() || stopLoss <= 0.0) {
            reasons.add("Stop loss ($stopLoss) must be a finite positive number")
        }
        if (takeProfits.isEmpty()) {
            reasons.add("Take-profit targets list is empty")
        } else {
            takeProfits.forEachIndexed { index, tp ->
                if (!tp.isFinite() || tp <= 0.0) {
                    reasons.add("Take-profit TP${index + 1} ($tp) must be a finite positive number")
                }
            }
        }

        if (reasons.isNotEmpty()) {
            return Pair(false, reasons)
        }

        // 2. Entry Zone Ordering
        if (entryLow > entryHigh) {
            reasons.add("Entry zone inverted: low ($entryLow) is greater than high ($entryHigh)")
        }

        // 3. Stop Loss Geometry
        if (isLong) {
            if (stopLoss >= entryLow) {
                reasons.add("LONG stop loss ($stopLoss) must be strictly below entry zone low ($entryLow)")
            }
        } else {
            if (stopLoss <= entryHigh) {
                reasons.add("SHORT stop loss ($stopLoss) must be strictly above entry zone high ($entryHigh)")
            }
        }

        // 4. Take Profit Geometry & Directional Ordering
        val tp1 = takeProfits.firstOrNull() ?: 0.0
        if (isLong) {
            if (tp1 <= entryHigh) {
                reasons.add("LONG TP1 ($tp1) must be strictly above entry zone high ($entryHigh)")
            }
            for (i in 1 until takeProfits.size) {
                if (takeProfits[i] <= takeProfits[i - 1]) {
                    reasons.add("LONG targets must be strictly ascending: TP$i (${takeProfits[i - 1]}) >= TP${i + 1} (${takeProfits[i]})")
                }
            }
        } else {
            if (tp1 >= entryLow) {
                reasons.add("SHORT TP1 ($tp1) must be strictly below entry zone low ($entryLow)")
            }
            for (i in 1 until takeProfits.size) {
                if (takeProfits[i] >= takeProfits[i - 1]) {
                    reasons.add("SHORT targets must be strictly descending: TP$i (${takeProfits[i - 1]}) <= TP${i + 1} (${takeProfits[i]})")
                }
            }
        }

        // 5. Minimum Risk Distance
        val entryMid = (entryLow + entryHigh) / 2.0
        val riskDistance = abs(entryMid - stopLoss)
        val minRiskThreshold = max(MIN_RISK_DISTANCE_EPSILON, entryMid * MIN_RISK_DISTANCE_PCT)
        if (riskDistance < minRiskThreshold) {
            reasons.add("Risk distance ($riskDistance) is below minimum allowed threshold ($minRiskThreshold)")
        }

        // 6. Current Price Checks (price not already past SL/TP1 & reasonable proximity)
        if (currentPrice != null && currentPrice > 0.0 && currentPrice.isFinite()) {
            if (isLong) {
                if (currentPrice <= stopLoss) {
                    reasons.add("Current market price ($currentPrice) has already hit or breached stop loss ($stopLoss)")
                }
                if (tp1 > 0.0 && currentPrice >= tp1) {
                    reasons.add("Current market price ($currentPrice) has already reached or exceeded TP1 ($tp1)")
                }
            } else {
                if (currentPrice >= stopLoss) {
                    reasons.add("Current market price ($currentPrice) has already hit or breached stop loss ($stopLoss)")
                }
                if (tp1 > 0.0 && currentPrice <= tp1) {
                    reasons.add("Current market price ($currentPrice) has already reached or exceeded TP1 ($tp1)")
                }
            }

            val maxAllowedEntryDistance = if (atr14 != null && atr14.isFinite() && atr14 > 0.0) {
                2.5 * atr14
            } else {
                currentPrice * 0.05
            }
            if (abs(entryMid - currentPrice) > maxAllowedEntryDistance) {
                reasons.add("Entry midpoint ($entryMid) is too distant from current market price ($currentPrice, max: $maxAllowedEntryDistance)")
            }
        }

        // 7. Exact worst-case R:R gate, consistent with the structured plan.
        // Midpoint remains useful for proximity, not for approving a range of fills.
        val worstEntry = if (isLong) entryHigh else entryLow
        val worstRisk = if (isLong) worstEntry - stopLoss else stopLoss - worstEntry
        val worstReward = if (isLong) tp1 - worstEntry else worstEntry - tp1
        val worstRr = if (worstRisk > MIN_RISK_DISTANCE_EPSILON && worstReward > 0.0) {
            worstReward / worstRisk
        } else 0.0
        if (!minRequiredRr.isFinite() || minRequiredRr <= 0.0) {
            reasons.add("Minimum required R:R must be finite and positive")
        } else if (!worstRr.isFinite() || worstRr < minRequiredRr) {
            reasons.add("Recalculated worst-case R:R (1:$worstRr) is below required minimum of 1:$minRequiredRr")
        }

        return Pair(reasons.isEmpty(), reasons)
    }

    /**
     * Centralized validation entry point.
     * Evaluates trade geometry, candle adequacy, multi-timeframe alignment,
     * market regime, volatility, and technical evidence.
     */
    fun validate(
        result: AiAnalysisResult,
        snapshot: MarketTechnicalSnapshot?,
        context: AiAnalysisRequestContext = AiAnalysisRequestContext(),
        currentTimeMs: Long = System.currentTimeMillis()
    ): ValidationOutcome {
        val decision = result.decision.trim().uppercase(Locale.US)

        // 1. If result is already a WAIT decision, sanitize trade levels and return valid WAIT
        if (decision == "WAIT") {
            val sanitizedWaitPlan = result.tradePlan?.copy(
                direction = com.example.ai.plan.TradeDirection.NEUTRAL,
                orderType = com.example.ai.plan.TradeOrderType.NONE,
                setupStatus = com.example.ai.plan.TradeSetupStatus.WAIT,
                entryZoneMin = null,
                entryZoneMax = null,
                stopLoss = null,
                tp1 = null,
                tp2 = null,
                tp3 = null,
                riskRewardTp1 = null,
                riskRewardTp2 = null,
                riskRewardTp3 = null,
                validationMessages = emptyList()
            ) ?: com.example.ai.plan.StructuredTradePlan(
                strategy = com.example.ai.strategy.AiStrategy.fromId(result.strategyId),
                strategyId = result.strategyId,
                strategyName = result.strategyName,
                symbol = snapshot?.symbol ?: "",
                timeframe = snapshot?.timeframe?.label ?: "",
                marketBias = "NEUTRAL",
                direction = com.example.ai.plan.TradeDirection.NEUTRAL,
                orderType = com.example.ai.plan.TradeOrderType.NONE,
                setupStatus = com.example.ai.plan.TradeSetupStatus.WAIT,
                referencePrice = snapshot?.currentPrice ?: 0.0,
                setupRationale = result.summary,
                supportingEvidence = result.reasoning
            )

            val sanitizedWait = result.copy(
                decision = "WAIT",
                bias = "NEUTRAL",
                setupQuality = "WAIT",
                setupSignature = "WAIT",
                entryZone = EntryZone(0.0, 0.0),
                stopLoss = 0.0,
                takeProfit = emptyList(),
                riskReward = 0.0,
                entryOrderType = "WAIT",
                setupDecisionState = "WAITING_FOR_CONFIRMATION",
                tradePlan = sanitizedWaitPlan
            )
            return ValidationOutcome(
                isValid = true,
                recomputedRiskReward = 0.0,
                rejectionReasons = emptyList(),
                validatedResult = sanitizedWait
            )
        }

        val rejectionReasons = mutableListOf<String>()
        val minRequiredRr = resolveMinimumRiskReward(context.tradingStyleProfile)

        // 2. Validate Trade Geometry
        val (geometryValid, geometryReasons) = validateTradeGeometry(
            decision = decision,
            entryLow = result.entryZone.low,
            entryHigh = result.entryZone.high,
            stopLoss = result.stopLoss,
            takeProfits = result.takeProfit,
            currentPrice = snapshot?.currentPrice,
            atr14 = snapshot?.atr14,
            minRequiredRr = minRequiredRr
        )
        rejectionReasons.addAll(geometryReasons)

        // 2b. Deterministic Trade Plan Validation (V3.1A)
        val activeStrategy = com.example.ai.strategy.AiStrategy.fromId(result.strategyId)
        val refP = snapshot?.currentPrice ?: ((result.entryZone.low + result.entryZone.high) / 2.0)
        val inZone = refP > 0.0 && refP in (result.entryZone.low * 0.995)..(result.entryZone.high * 1.005)
        val resolvedOrderType = when (result.effectiveEntryOrderType) {
            "MARKET" -> com.example.ai.plan.TradeOrderType.MARKET
            "STOP" -> com.example.ai.plan.TradeOrderType.STOP
            else -> if (inZone) com.example.ai.plan.TradeOrderType.MARKET else com.example.ai.plan.TradeOrderType.LIMIT
        }

        val planToValidate = result.tradePlan ?: com.example.ai.plan.StructuredTradePlan(
            strategy = activeStrategy,
            strategyId = result.strategyId,
            strategyName = result.strategyName,
            symbol = snapshot?.symbol ?: "",
            timeframe = snapshot?.timeframe?.label ?: "",
            marketBias = result.bias,
            direction = when (decision) {
                "LONG" -> com.example.ai.plan.TradeDirection.BUY
                "SHORT" -> com.example.ai.plan.TradeDirection.SELL
                else -> com.example.ai.plan.TradeDirection.NEUTRAL
            },
            orderType = resolvedOrderType,
            setupStatus = if (resolvedOrderType == com.example.ai.plan.TradeOrderType.MARKET) com.example.ai.plan.TradeSetupStatus.ACTIONABLE else com.example.ai.plan.TradeSetupStatus.PENDING,
            referencePrice = refP,
            entryZoneMin = result.entryZone.low,
            entryZoneMax = result.entryZone.high,
            stopLoss = result.stopLoss,
            tp1 = result.takeProfit.firstOrNull(),
            tp2 = result.takeProfit.getOrNull(1),
            tp3 = result.takeProfit.getOrNull(2),
            setupRationale = result.summary,
            supportingEvidence = result.reasoning
        )

        val planOutcome = TradePlanValidationEngine.validatePlan(
            plan = if (snapshot != null && snapshot.currentPrice > 0.0) planToValidate.copy(referencePrice = snapshot.currentPrice) else planToValidate,
            minRequiredRr = TradePlanValidationEngine.resolveMinimumRiskReward(activeStrategy, context.tradingStyleProfile),
            hasLiveBrokerFeed = context.hasVerifiedBidAsk
        )
        if (!planOutcome.isValid) {
            rejectionReasons.addAll(planOutcome.rejectionReasons)
        } else {
            val normalized = planOutcome.sanitizedPlan
            val expectedDirection = if (decision == "LONG") com.example.ai.plan.TradeDirection.BUY
                else com.example.ai.plan.TradeDirection.SELL
            if (normalized.isWaitOrInvalid || normalized.direction != expectedDirection ||
                normalized.entryZoneMin != result.entryZone.low ||
                normalized.entryZoneMax != result.entryZone.high ||
                normalized.stopLoss != result.stopLoss ||
                normalized.tp1 != result.takeProfit.firstOrNull() ||
                normalized.tp2 != result.takeProfit.getOrNull(1) ||
                normalized.tp3 != result.takeProfit.getOrNull(2)) {
                rejectionReasons.add("Structured trade plan conflicts with directional signal or price levels")
            }
        }

        // 3. No-Trade Safety Gates
        // Gate A: Confidence Threshold
        if (result.confidence < MIN_DIRECTIONAL_CONFIDENCE) {
            rejectionReasons.add("Confidence (${result.confidence}%) is below minimum actionable threshold ($MIN_DIRECTIONAL_CONFIDENCE%)")
        }

        // Gate B: Technical Evidence Requirement
        if (result.reasoning.isEmpty() && result.summary.isBlank()) {
            rejectionReasons.add("Missing required technical reasoning bullets and summary evidence")
        }

        // Gate C: Data Feed Valid for Entry
        if (!context.isDataValidForEntry) {
            rejectionReasons.add("Data feed not valid for new entries: ${context.dataStatusReason.ifBlank { "feed offline or closed" }}")
        }

        if (snapshot != null) {
            // Gate D: Insufficient Candle History
            if (snapshot.recentCandles.size < MIN_CANDLES_REQUIRED || snapshot.marketRegime == MarketRegime.INSUFFICIENT_DATA) {
                rejectionReasons.add("Insufficient candle history (${snapshot.recentCandles.size} < $MIN_CANDLES_REQUIRED) for reliable technical analysis")
            }

            // Gate E: Data Staleness (when timestamps are available)
            if (snapshot.latestCandle.openTime > 0L && currentTimeMs > 0L) {
                val tfPeriodMs = max(snapshot.timeframe.durationMs, 60_000L)
                val ageMs = currentTimeMs - snapshot.latestCandle.openTime
                if (ageMs > MAX_STALE_PERIOD_MULTIPLIER * tfPeriodMs) {
                    rejectionReasons.add("Market candle data is stale (age ${ageMs / 1000}s exceeds ${MAX_STALE_PERIOD_MULTIPLIER} candle periods)")
                }
            }
            if (snapshot.mtfConfirmation.isHigherTfStale) {
                rejectionReasons.add("Higher timeframe context data is stale or unconfirmed")
            }

            // Gate F: Conflicting Higher and Primary Timeframes
            if (snapshot.mtfConfirmation.status == MtfConfirmationStatus.CONFLICT) {
                rejectionReasons.add("Multi-timeframe conflict detected (${snapshot.mtfConfirmation.summary})")
            }
            val htfBias = snapshot.higherContext?.marketCondition ?: result.higherTimeframeBias
            val ptfBias = snapshot.primaryContext?.marketCondition ?: result.primaryTimeframeBias

            if (decision == "LONG") {
                if (htfBias.equals("BEARISH", ignoreCase = true)) {
                    rejectionReasons.add("Higher timeframe bias is BEARISH, conflicting with proposed LONG setup")
                }
                if (ptfBias.equals("BEARISH", ignoreCase = true)) {
                    rejectionReasons.add("Primary timeframe bias is BEARISH, conflicting with proposed LONG setup")
                }
            } else if (decision == "SHORT") {
                if (htfBias.equals("BULLISH", ignoreCase = true)) {
                    rejectionReasons.add("Higher timeframe bias is BULLISH, conflicting with proposed SHORT setup")
                }
                if (ptfBias.equals("BULLISH", ignoreCase = true)) {
                    rejectionReasons.add("Primary timeframe bias is BULLISH, conflicting with proposed SHORT setup")
                }
            }

            // Gate G: Choppy Market Structure
            if (snapshot.marketRegime == MarketRegime.CHOPPY) {
                rejectionReasons.add("Market regime is CHOPPY — directional trades blocked until structure clarifies")
            }

            // Gate H: Abnormal Volatility
            if (snapshot.isVolatilityAbnormal || snapshot.primaryContext?.volatilityState.equals("ABNORMAL", ignoreCase = true)) {
                rejectionReasons.add("Abnormal volatility detected relative to ATR — risk cannot be reliably controlled")
            }
        }

        // 4. Safe Failure Handling
        if (rejectionReasons.isNotEmpty()) {
            val failureSummary = "Safety rejection: ${rejectionReasons.first()}. Setup converted to WAIT."
            val combinedReasoning = (rejectionReasons + result.reasoning).distinct()

            val safeWaitPlan = planOutcome.sanitizedPlan.copy(
                setupStatus = com.example.ai.plan.TradeSetupStatus.INVALID,
                direction = com.example.ai.plan.TradeDirection.NEUTRAL,
                orderType = com.example.ai.plan.TradeOrderType.NONE,
                entryZoneMin = null,
                entryZoneMax = null,
                stopLoss = null,
                tp1 = null,
                tp2 = null,
                tp3 = null,
                riskRewardTp1 = null,
                riskRewardTp2 = null,
                riskRewardTp3 = null,
                validationMessages = rejectionReasons
            )

            val safeWaitResult = result.copy(
                decision = "WAIT",
                bias = "NEUTRAL",
                setupQuality = "WAIT",
                setupSignature = "WAIT",
                entryZone = EntryZone(0.0, 0.0),
                stopLoss = 0.0,
                takeProfit = emptyList(),
                riskReward = 0.0,
                entryOrderType = "WAIT",
                setupDecisionState = "WAITING_FOR_CONFIRMATION",
                reasoning = combinedReasoning,
                summary = failureSummary,
                tradePlan = safeWaitPlan
            )

            return ValidationOutcome(
                isValid = false,
                recomputedRiskReward = 0.0,
                rejectionReasons = rejectionReasons,
                validatedResult = safeWaitResult
            )
        }

        // 5. Valid Setup: Recalculate Risk/Reward & Setup Quality Deterministically
        val entryLow = minOf(result.entryZone.low, result.entryZone.high)
        val entryHigh = maxOf(result.entryZone.low, result.entryZone.high)
        val worstEntry = if (decision == "LONG") entryHigh else entryLow
        val rawWorstRr = abs(result.takeProfit.first() - worstEntry) / abs(worstEntry - result.stopLoss)
        val recomputedRr = planOutcome.sanitizedPlan.riskRewardTp1!!

        val maxAllowedGrade = when {
            rawWorstRr >= 2.0 && result.confidence >= 80 -> "A+"
            rawWorstRr >= 1.6 && result.confidence >= 72 -> "A"
            else -> "B"
        }

        val finalizedGrade = when (result.setupQuality.trim().uppercase(Locale.US)) {
            "A+" -> if (maxAllowedGrade == "A+") "A+" else maxAllowedGrade
            "A" -> if (maxAllowedGrade in listOf("A+", "A")) "A" else "B"
            else -> "B"
        }

        val validatedSuccessResult = result.copy(
            entryZone = EntryZone(entryLow, entryHigh),
            stopLoss = result.stopLoss,
            takeProfit = result.takeProfit,
            riskReward = recomputedRr,
            setupQuality = finalizedGrade,
            tradePlan = planOutcome.sanitizedPlan
        )

        return ValidationOutcome(
            isValid = true,
            recomputedRiskReward = recomputedRr,
            rejectionReasons = emptyList(),
            validatedResult = validatedSuccessResult
        )
    }

    /**
     * Convenience wrapper returning the sanitized AiAnalysisResult directly.
     */
    fun validateAndNormalize(
        result: AiAnalysisResult,
        snapshot: MarketTechnicalSnapshot?,
        context: AiAnalysisRequestContext = AiAnalysisRequestContext(),
        currentTimeMs: Long = System.currentTimeMillis()
    ): AiAnalysisResult {
        return validate(result, snapshot, context, currentTimeMs).validatedResult
    }
}
