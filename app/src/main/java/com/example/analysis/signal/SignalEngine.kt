package com.example.analysis.signal

import com.example.ai.parser.AiAnalysisResult
import com.example.ai.parser.EntryZone
import com.example.analysis.supportResistance.SupportResistanceEngine.roundPriceCleanly
import com.example.market.candle.Candle
import com.example.market.candle.Timeframe
import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.storage.watchlist.SignalJournalEntity
import java.util.Locale
import kotlin.math.abs

/**
 * Requirement #4: Complete Signal Lifecycle States.
 */
enum class SignalLifecycleStatus(val code: String, val displayLabel: String) {
    WAIT("WAIT", "WAIT"),
    ACTIVE("ACTIVE", "ACTIVE"),
    ENTRY_TRIGGERED("ENTRY_TRIGGERED", "ENTRY TRIGGERED"),
    TP1_HIT("TP1_HIT", "TP1 HIT"),
    TP2_HIT("TP2_HIT", "TP2 HIT"),
    TP3_HIT("TP3_HIT", "TP3 HIT"),
    SL_HIT("SL_HIT", "SL HIT"),
    INVALIDATED("INVALIDATED", "INVALIDATED"),
    EXPIRED("EXPIRED", "EXPIRED"),
    CLOSED("CLOSED", "CLOSED"),
    CANCELLED("CANCELLED", "CANCELLED"),
    AMBIGUOUS("AMBIGUOUS", "AMBIGUOUS");

    val isTerminal: Boolean
        get() = this in listOf(TP3_HIT, SL_HIT, INVALIDATED, EXPIRED, CLOSED, CANCELLED, AMBIGUOUS, WAIT)

    companion object {
        fun fromCode(code: String): SignalLifecycleStatus {
            return entries.firstOrNull {
                it.code.equals(code.trim(), ignoreCase = true) ||
                    it.displayLabel.equals(code.trim(), ignoreCase = true)
            } ?: ACTIVE
        }
    }
}

/**
 * Requirement #3 & #5: Chart Signal Overlay model for drawing horizontal levels
 * (ENTRY, SL, TP1, TP2, TP3) and optional Bot Position status directly on the candlestick chart.
 */
data class ChartSignalOverlay(
    val signalId: Long,
    val symbol: String,
    val timeframe: String,
    val candleOpenTime: Long,
    val candleCloseTime: Long,
    val decision: String,               // "LONG" or "SHORT"
    val confidence: Int,
    val setupQuality: String = "A",     // "A+", "A", "B"
    val entry: Double,
    val entryMin: Double,
    val entryMax: Double,
    val stopLoss: Double,
    val tp1: Double,
    val tp2: Double,
    val tp3: Double,
    val riskReward: Double,
    val expiryAt: Long = 0L,
    val status: SignalLifecycleStatus = SignalLifecycleStatus.ACTIVE,
    val highestTpReached: Int = 0,
    val isHistorical: Boolean = false,
    val entryTriggerText: String = "",
    val invalidationText: String = "",
    val marketRegime: String = "",
    val botActionLabel: String = "",
    val botUnrealizedPnlUsd: Double? = null,
    val source: String = "AI"
) {
    val isLong: Boolean get() = decision.equals("LONG", ignoreCase = true)
    val isShort: Boolean get() = decision.equals("SHORT", ignoreCase = true)
    val isBaseline: Boolean get() = source.equals("BASELINE", ignoreCase = true)
}

data class LivePriceStatusTransition(
    val status: SignalLifecycleStatus,
    val highestTpReached: Int
)

/**
 * Requirement #5 & #6: Signal Validation, Normalization, Overlay Construction, and Real-Time Lifecycle Tracking.
 */
object SignalEngine {

    // Minimum Risk/Reward unified with AiPromptEngine.SYSTEM_PROMPT (1.35)
    const val MIN_VALID_RISK_REWARD: Double = 1.35
    // Minimum confidence threshold from AI prompt (< 60 -> WAIT)
    const val MIN_DIRECTIONAL_CONFIDENCE: Int = 60

    /**
     * Requirement #5 & Tahap 3: Strictly validates Entry / SL / TP ordering, ATR-based entry proximity,
     * and ensures currentPrice has not already passed TP1 or SL.
     *
     * Rules:
     * - For LONG:  `SL < Entry < TP1 < TP2 < TP3` and `stopLoss < currentPrice < tp1`
     * - For SHORT: `TP3 < TP2 < TP1 < Entry < SL` and `tp1 < currentPrice < stopLoss`
     * - Entry distance to currentPrice must be `<= 1.5 * ATR14` (or `<= 2%` of currentPrice if ATR14 is unavailable).
     * - Risk/Reward to TP1 must be `>= 1.35` (unified with AI prompt minimum R:R).
     */
    fun isSetupMathematicallyValid(
        decision: String,
        entry: Double,
        stopLoss: Double,
        tp1: Double,
        tp2: Double,
        tp3: Double,
        currentPrice: Double,
        atr14: Double? = null
    ): Boolean {
        val cleanDecision = decision.trim().uppercase(Locale.US)
        if (cleanDecision != "LONG" && cleanDecision != "SHORT") return false

        if (entry <= 0.0 || stopLoss <= 0.0 || tp1 <= 0.0 || tp2 <= 0.0 || tp3 <= 0.0) {
            return false
        }
        if (entry.isNaN() || stopLoss.isNaN() || tp1.isNaN() || tp2.isNaN() || tp3.isNaN()) {
            return false
        }

        val strictOrderValid = if (cleanDecision == "LONG") {
            stopLoss < entry && entry < tp1 && tp1 < tp2 && tp2 < tp3
        } else {
            tp3 < tp2 && tp2 < tp1 && tp1 < entry && entry < stopLoss
        }
        if (!strictOrderValid) return false

        if (currentPrice > 0.0 && !currentPrice.isNaN()) {
            // Reject if current market price has already passed SL or TP1
            if (cleanDecision == "LONG") {
                if (currentPrice <= stopLoss || currentPrice >= tp1) return false
            } else {
                if (currentPrice >= stopLoss || currentPrice <= tp1) return false
            }

            // Entry distance tolerance: |entry - currentPrice| <= 1.5 * ATR14, fallback 2% of currentPrice
            val maxEntryDistance = if (atr14 != null && !atr14.isNaN() && atr14 > 0.0) {
                1.5 * atr14
            } else {
                currentPrice * 0.02
            }
            if (abs(entry - currentPrice) > maxEntryDistance) return false
        }

        val risk = abs(entry - stopLoss)
        val rewardTp1 = abs(tp1 - entry)
        if (risk <= 1e-8) return false
        val rr = rewardTp1 / risk
        return rr >= MIN_VALID_RISK_REWARD
    }

    /**
     * Validates an `AiAnalysisResult` against Requirement #5 & Tahap 3.
     * - Fills TP2/TP3 proportionally if an external provider returned fewer than 3 TPs.
     * - If `LONG` or `SHORT` violates ordering, ATR distance, or R:R >= 1.35, converts the signal to `WAIT`.
     */
    fun validateAndNormalizeResult(
        result: AiAnalysisResult,
        currentPrice: Double,
        atr14: Double? = null
    ): AiAnalysisResult {
        if (result.isWait) {
            return SignalValidationEngine.validateAndNormalize(result, null)
        }

        val (valid, reasons) = SignalValidationEngine.validateTradeGeometry(
            decision = result.decision,
            entryLow = result.entryZone.low,
            entryHigh = result.entryZone.high,
            stopLoss = result.stopLoss,
            takeProfits = result.takeProfit,
            currentPrice = currentPrice,
            atr14 = atr14,
            minRequiredRr = MIN_VALID_RISK_REWARD
        )

        if (!valid || result.confidence < MIN_DIRECTIONAL_CONFIDENCE) {
            val allReasons = mutableListOf<String>()
            if (result.confidence < MIN_DIRECTIONAL_CONFIDENCE) {
                allReasons.add("Confidence (${result.confidence}%) is below directional threshold ($MIN_DIRECTIONAL_CONFIDENCE%)")
            }
            allReasons.addAll(reasons)
            val reason = allReasons.firstOrNull() ?: "Validation rejected"
            return result.copy(
                decision = "WAIT",
                bias = "NEUTRAL",
                setupQuality = "WAIT",
                setupSignature = "WAIT",
                entryZone = EntryZone(0.0, 0.0),
                stopLoss = 0.0,
                takeProfit = emptyList(),
                riskReward = 0.0,
                reasoning = (allReasons + result.reasoning).distinct(),
                summary = "$reason. Converted to WAIT."
            )
        }

        val entryLow = minOf(result.entryZone.low, result.entryZone.high)
        val entryHigh = maxOf(result.entryZone.low, result.entryZone.high)
        val entryMid = (entryLow + entryHigh) / 2.0
        val risk = abs(entryMid - result.stopLoss)
        val tp1 = result.takeProfit.first()
        val recomputedRr = kotlin.math.round((abs(tp1 - entryMid) / risk) * 100.0) / 100.0

        return result.copy(
            entryZone = EntryZone(entryLow, entryHigh),
            takeProfit = result.takeProfit,
            riskReward = recomputedRr
        )
    }

    /**
     * Overload accepting MarketTechnicalSnapshot and AiAnalysisRequestContext.
     */
    fun validateAndNormalizeResult(
        result: AiAnalysisResult,
        snapshot: MarketTechnicalSnapshot?,
        context: com.example.ai.analyst.AiAnalysisRequestContext = com.example.ai.analyst.AiAnalysisRequestContext()
    ): AiAnalysisResult {
        return SignalValidationEngine.validateAndNormalize(result, snapshot, context)
    }

    /**
     * Builds a `ChartSignalOverlay` for the currently active AI result if it is a valid LONG or SHORT signal.
     */
    fun buildOverlayFromAiResult(
        symbol: String,
        timeframe: Timeframe,
        referenceCandle: Candle?,
        aiResult: AiAnalysisResult?,
        journalMatch: SignalJournalEntity? = null,
        atr14: Double? = null
    ): ChartSignalOverlay? {
        if (aiResult == null || aiResult.isWait) return null
        val entryMid = (aiResult.entryZone.low + aiResult.entryZone.high) / 2.0
        val tp1 = aiResult.takeProfit.getOrElse(0) { 0.0 }
        val tp2 = aiResult.takeProfit.getOrElse(1) { 0.0 }
        val tp3 = aiResult.takeProfit.getOrElse(2) { 0.0 }

        if (!isSetupMathematicallyValid(
                decision = aiResult.decision,
                entry = entryMid,
                stopLoss = aiResult.stopLoss,
                tp1 = tp1,
                tp2 = tp2,
                tp3 = tp3,
                currentPrice = referenceCandle?.close ?: entryMid,
                atr14 = atr14
            )
        ) {
            return null
        }

        val status = journalMatch?.status?.let { SignalLifecycleStatus.fromCode(it) }
            ?: SignalLifecycleStatus.ACTIVE

        return ChartSignalOverlay(
            signalId = journalMatch?.id ?: aiResult.analyzedAt,
            symbol = symbol.uppercase(Locale.US),
            timeframe = timeframe.label,
            candleOpenTime = journalMatch?.candleOpenTimeAtSignal ?: (referenceCandle?.openTime ?: aiResult.analyzedAt),
            candleCloseTime = journalMatch?.candleCloseTimeAtSignal ?: (referenceCandle?.closeTime ?: aiResult.analyzedAt),
            decision = aiResult.decision.uppercase(Locale.US),
            confidence = aiResult.confidence,
            setupQuality = aiResult.setupQualityGrade,
            entry = roundPriceCleanly(entryMid),
            entryMin = aiResult.entryZone.low,
            entryMax = aiResult.entryZone.high,
            stopLoss = aiResult.stopLoss,
            tp1 = tp1,
            tp2 = tp2,
            tp3 = tp3,
            riskReward = aiResult.riskReward,
            expiryAt = journalMatch?.expiryAt?.takeIf { it > 0L } ?: aiResult.expiryAt,
            status = status,
            highestTpReached = journalMatch?.highestTpReached ?: 0,
            isHistorical = false,
            entryTriggerText = aiResult.effectiveEntryTrigger,
            invalidationText = aiResult.invalidIf,
            marketRegime = aiResult.marketRegime
        )
    }

    fun buildOverlayFromJournalEntity(
        entity: SignalJournalEntity,
        isHistorical: Boolean = false
    ): ChartSignalOverlay? {
        if (entity.decision.equals("WAIT", ignoreCase = true)) return null
        val entryMid = if (entity.entryPrice > 0.0) {
            entity.entryPrice
        } else {
            (entity.entryMin + entity.entryMax) / 2.0
        }
        if (entryMid <= 0.0 || entity.stopLoss <= 0.0 || entity.tp1 <= 0.0) return null

        val isLong = entity.decision.equals("LONG", ignoreCase = true)
        val isShort = entity.decision.equals("SHORT", ignoreCase = true)
        if (!isLong && !isShort) return null
        if (isLong && !(entity.stopLoss < entryMid && entryMid < entity.tp1)) return null
        if (isShort && !(entity.tp1 < entryMid && entryMid < entity.stopLoss)) return null

        return ChartSignalOverlay(
            signalId = entity.id,
            symbol = entity.symbol,
            timeframe = entity.primaryTimeframe,
            candleOpenTime = entity.candleOpenTimeAtSignal,
            candleCloseTime = entity.candleCloseTimeAtSignal,
            decision = entity.decision.uppercase(Locale.US),
            confidence = entity.confidence,
            setupQuality = entity.setupQuality,
            entry = roundPriceCleanly(entryMid),
            entryMin = entity.entryMin,
            entryMax = entity.entryMax,
            stopLoss = entity.stopLoss,
            tp1 = entity.tp1,
            tp2 = entity.tp2,
            tp3 = entity.tp3,
            riskReward = entity.riskReward,
            expiryAt = entity.expiryAt,
            status = SignalLifecycleStatus.fromCode(entity.status),
            highestTpReached = entity.highestTpReached,
            isHistorical = isHistorical,
            entryTriggerText = entity.entryTrigger,
            invalidationText = entity.invalidation,
            marketRegime = entity.marketRegime,
            source = entity.source.ifBlank { "AI" }
        )
    }

    /**
     * Tahap 3: Unified pure state-machine function for signal status transition on a live price tick.
     * Shared by both `SignalEngine.evaluateLivePriceTick` (chart badge) and
     * `SignalForwardTester.evaluateSignalAgainstLivePrice` (signal journal).
     *
     * Rules:
     * - Before entry is triggered (`ACTIVE` and `highestTpReached == 0`):
     *   - If expired (`expiryAt > 0 && nowMs > expiryAt`) -> `EXPIRED`
     *   - If SL is touched before entry -> `INVALIDATED`
     *   - If TP1 is touched before entry (missed move) -> `INVALIDATED` (never `TP_HIT`)
     *   - If `livePrice` is inside `entryMin..entryMax` -> `ENTRY_TRIGGERED`
     *   - Otherwise remain `ACTIVE` (SL/TP are never evaluated as hits before `ENTRY_TRIGGERED`).
     * - After `ENTRY_TRIGGERED` (or `TP1_HIT` / `TP2_HIT`):
     *   - Before TP1 (`highestTpReached == 0`), if SL is touched -> `SL_HIT`
     *   - After TP1 (`highestTpReached >= 1`), stop moves to `entryPrice` (breakeven); if touched -> `CLOSED`
     *   - If TP3 / TP2 / TP1 is touched -> `TP3_HIT` / `TP2_HIT` / `TP1_HIT`
     */
    fun transitionSignalStatusOnLivePrice(
        currentStatus: SignalLifecycleStatus,
        highestTpReached: Int,
        isLong: Boolean,
        entryPrice: Double,
        entryMin: Double,
        entryMax: Double,
        stopLoss: Double,
        tp1: Double,
        tp2: Double,
        tp3: Double,
        expiryAt: Long,
        livePrice: Double,
        nowMs: Long = System.currentTimeMillis()
    ): LivePriceStatusTransition {
        if (livePrice <= 0.0 || currentStatus.isTerminal) {
            return LivePriceStatusTransition(currentStatus, highestTpReached)
        }

        val inEntryZone = livePrice in entryMin..entryMax
        val hitSl = if (isLong) livePrice <= stopLoss else livePrice >= stopLoss
        val hitBreakevenStop = if (isLong) livePrice <= entryPrice else livePrice >= entryPrice
        val hitTp1 = tp1 > 0.0 && (if (isLong) livePrice >= tp1 else livePrice <= tp1)
        val hitTp2 = tp2 > 0.0 && (if (isLong) livePrice >= tp2 else livePrice <= tp2)
        val hitTp3 = tp3 > 0.0 && (if (isLong) livePrice >= tp3 else livePrice <= tp3)

        // State 1: Signal is still ACTIVE (entry has not been triggered yet)
        if (currentStatus == SignalLifecycleStatus.ACTIVE && highestTpReached == 0) {
            if (expiryAt > 0L && nowMs > expiryAt) {
                return LivePriceStatusTransition(SignalLifecycleStatus.EXPIRED, 0)
            }
            if (hitSl) {
                return LivePriceStatusTransition(SignalLifecycleStatus.INVALIDATED, 0)
            }
            if (hitTp1 || hitTp2 || hitTp3) {
                // Move occurred without triggering entry first -> INVALIDATED (missed move)
                return LivePriceStatusTransition(SignalLifecycleStatus.INVALIDATED, 0)
            }
            if (inEntryZone) {
                return LivePriceStatusTransition(SignalLifecycleStatus.ENTRY_TRIGGERED, 0)
            }
            return LivePriceStatusTransition(SignalLifecycleStatus.ACTIVE, 0)
        }

        // State 2: Entry has been triggered (ENTRY_TRIGGERED, TP1_HIT, or TP2_HIT)
        if (highestTpReached == 0 && hitSl) {
            return LivePriceStatusTransition(SignalLifecycleStatus.SL_HIT, 0)
        }
        if (highestTpReached >= 1 && hitBreakevenStop) {
            return LivePriceStatusTransition(SignalLifecycleStatus.CLOSED, highestTpReached)
        }

        return when {
            hitTp3 && highestTpReached < 3 -> LivePriceStatusTransition(SignalLifecycleStatus.TP3_HIT, 3)
            hitTp2 && highestTpReached < 2 -> LivePriceStatusTransition(SignalLifecycleStatus.TP2_HIT, 2)
            hitTp1 && highestTpReached < 1 -> LivePriceStatusTransition(SignalLifecycleStatus.TP1_HIT, 1)
            else -> LivePriceStatusTransition(currentStatus, highestTpReached)
        }
    }

    /**
     * Requirement #1, #3, #4 & Tahap 3: Evaluates real-time price tick against an active signal overlay
     * using the shared `transitionSignalStatusOnLivePrice` pure state machine.
     */
    fun evaluateLivePriceTick(
        overlay: ChartSignalOverlay,
        livePrice: Double,
        nowMs: Long = System.currentTimeMillis()
    ): ChartSignalOverlay {
        val entryMid = if (overlay.entry > 0.0) overlay.entry else (overlay.entryMin + overlay.entryMax) / 2.0
        val next = transitionSignalStatusOnLivePrice(
            currentStatus = overlay.status,
            highestTpReached = overlay.highestTpReached,
            isLong = overlay.isLong,
            entryPrice = entryMid,
            entryMin = overlay.entryMin,
            entryMax = overlay.entryMax,
            stopLoss = overlay.stopLoss,
            tp1 = overlay.tp1,
            tp2 = overlay.tp2,
            tp3 = overlay.tp3,
            expiryAt = overlay.expiryAt,
            livePrice = livePrice,
            nowMs = nowMs
        )
        return if (next.status != overlay.status || next.highestTpReached != overlay.highestTpReached) {
            overlay.copy(
                status = next.status,
                highestTpReached = next.highestTpReached
            )
        } else {
            overlay
        }
    }
}
