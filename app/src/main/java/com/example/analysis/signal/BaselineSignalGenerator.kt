package com.example.analysis.signal

import com.example.ai.parser.AiAnalysisResult
import com.example.ai.parser.AiResponseParser
import com.example.ai.parser.EntryZone
import com.example.analysis.journal.SignalForwardTester
import com.example.analysis.marketStructure.StructureState
import com.example.analysis.supportResistance.SupportResistanceEngine.roundPriceCleanly
import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.analysis.technical.MtfConfirmationStatus
import com.example.storage.watchlist.SignalJournalEntity
import org.json.JSONObject
import java.util.Locale

/**
 * TAHAP 7: Deterministic, LLM-free baseline signal generator used as a benchmark against AI signals.
 *
 * Rules:
 * - Uses `longCandidate` / `shortCandidate` from `MarketTechnicalSnapshot`.
 * - `LONG` if `EMA20 > EMA50 > EMA200` and there is NO `higherPrimaryConflict`.
 * - `SHORT` if `EMA20 < EMA50 < EMA200` and there is NO `higherPrimaryConflict`.
 * - Otherwise returns `null` (no signal).
 */
object BaselineSignalGenerator {

    const val SOURCE_AI = "AI"
    const val SOURCE_BASELINE = "BASELINE"
    const val BASELINE_PROVIDER_BADGE = "Baseline · EMA"
    const val BASELINE_MODEL_ID = "baseline-ema-20-50-200"

    fun hasHigherPrimaryConflict(snapshot: MarketTechnicalSnapshot): Boolean {
        if (snapshot.mtfConfirmation.status == MtfConfirmationStatus.CONFLICT) {
            return true
        }
        val primaryBias = snapshot.primaryContext?.marketCondition ?: when (snapshot.marketStructure.state) {
            StructureState.BULLISH -> "BULLISH"
            StructureState.BEARISH -> "BEARISH"
            StructureState.TRANSITIONAL -> "TRANSITIONAL"
            StructureState.SIDEWAYS -> "SIDEWAYS"
        }
        val higherBias = snapshot.higherContext?.marketCondition ?: return false
        return (higherBias == "BULLISH" && primaryBias == "BEARISH") ||
            (higherBias == "BEARISH" && primaryBias == "BULLISH")
    }

    fun generate(
        snapshot: MarketTechnicalSnapshot,
        nowMs: Long = System.currentTimeMillis()
    ): AiAnalysisResult? {
        val ema20 = snapshot.ema20 ?: return null
        val ema50 = snapshot.ema50 ?: return null
        val ema200 = snapshot.ema200 ?: return null
        if (ema20.isNaN() || ema50.isNaN() || ema200.isNaN()) return null
        if (ema20 <= 0.0 || ema50 <= 0.0 || ema200 <= 0.0) return null

        if (hasHigherPrimaryConflict(snapshot)) {
            return null
        }

        val isLong = ema20 > ema50 && ema50 > ema200
        val isShort = ema20 < ema50 && ema50 < ema200
        if (!isLong && !isShort) {
            return null
        }

        val decision = if (isLong) "LONG" else "SHORT"
        val candidate = if (isLong) snapshot.longCandidate else snapshot.shortCandidate
        if (candidate == null) return null

        val primaryBias = snapshot.primaryContext?.marketCondition ?: if (isLong) "BULLISH" else "BEARISH"
        val higherBias = snapshot.higherContext?.marketCondition ?: primaryBias
        val lowerBias = snapshot.lowerContext?.marketCondition ?: "NEUTRAL"

        val expiryDurationMs = (snapshot.timeframe.durationMs * 12L).coerceIn(900_000L, 172_800_000L)
        val expiryAt = nowMs + expiryDurationMs
        val signature = "BASELINE_${snapshot.symbol.uppercase(Locale.US)}_${snapshot.timeframe.label}_${
            AiResponseParser.buildSetupSignature(decision, candidate.entryMin, candidate.entryMax, candidate.stopLoss)
        }"

        val summary = if (isLong) {
            "Baseline LONG: EMA20 > EMA50 > EMA200 without higher/primary timeframe conflict."
        } else {
            "Baseline SHORT: EMA20 < EMA50 < EMA200 without higher/primary timeframe conflict."
        }

        val rawJson = JSONObject().apply {
            put("source", SOURCE_BASELINE)
            put("decision", decision)
            put("ema20", ema20)
            put("ema50", ema50)
            put("ema200", ema200)
        }.toString()

        return AiAnalysisResult(
            decision = decision,
            bias = if (isLong) "BULLISH" else "BEARISH",
            confidence = 70,
            trend = if (isLong) "BULLISH" else "BEARISH",
            momentum = if (isLong) "POSITIVE" else "NEGATIVE",
            marketStructure = snapshot.marketStructure.schemaCode,
            setupQuality = "B",
            entryZone = EntryZone(low = candidate.entryMin, high = candidate.entryMax),
            stopLoss = candidate.stopLoss,
            takeProfit = listOf(candidate.tp1, candidate.tp2, candidate.tp3),
            riskReward = candidate.riskRewardTp1,
            support = snapshot.supportLevels,
            resistance = snapshot.resistanceLevels,
            higherTimeframeBias = higherBias,
            primaryTimeframeBias = primaryBias,
            lowerTimeframeBias = lowerBias,
            invalidIf = if (isLong) {
                "Price closes below ${roundPriceCleanly(candidate.stopLoss)}"
            } else {
                "Price closes above ${roundPriceCleanly(candidate.stopLoss)}"
            },
            reasoning = listOf(
                summary,
                "Candidate R:R 1:${candidate.riskRewardTp1}"
            ),
            summary = summary,
            rawJson = rawJson,
            marketRegime = snapshot.marketRegime.displayLabel,
            mtfConfirmation = snapshot.mtfConfirmation.summary,
            entryTrigger = "EMA20/EMA50/EMA200 aligned ($decision)",
            expiryAt = expiryAt,
            setupSignature = signature,
            providerBadge = BASELINE_PROVIDER_BADGE,
            modelUsed = BASELINE_MODEL_ID,
            analyzedAt = nowMs
        )
    }

    fun generateJournalEntity(
        snapshot: MarketTechnicalSnapshot,
        nowMs: Long = System.currentTimeMillis()
    ): SignalJournalEntity? {
        val result = generate(snapshot, nowMs) ?: return null
        val htfLabel = snapshot.higherContext?.timeframe?.label
            ?: snapshot.timeframe.higherTimeframe()?.label
            ?: "--"
        val ltfLabel = snapshot.lowerContext?.timeframe?.label
            ?: snapshot.timeframe.lowerTimeframe()?.label
            ?: "--"
        val entryMid = roundPriceCleanly((result.entryZone.min + result.entryZone.max) / 2.0)

        return SignalJournalEntity(
            timestamp = result.analyzedAt,
            candleOpenTimeAtSignal = snapshot.latestCandle.openTime,
            candleCloseTimeAtSignal = snapshot.latestCandle.closeTime,
            symbol = snapshot.symbol.uppercase(Locale.US),
            primaryTimeframe = snapshot.timeframe.label,
            higherTimeframe = htfLabel,
            lowerTimeframe = ltfLabel,
            priceAtSignal = snapshot.currentPrice,
            decision = result.decision,
            confidence = result.confidence,
            setupQuality = result.setupQualityGrade,
            entryPrice = entryMid,
            entryMin = result.entryZone.min,
            entryMax = result.entryZone.max,
            stopLoss = result.stopLoss,
            tp1 = result.takeProfit.getOrElse(0) { 0.0 },
            tp2 = result.takeProfit.getOrElse(1) { 0.0 },
            tp3 = result.takeProfit.getOrElse(2) { 0.0 },
            riskReward = result.riskReward,
            marketStructure = result.marketStructure,
            trend = result.trend,
            momentum = result.momentum,
            marketConditions = "${htfLabel}:${result.higherTimeframeBias} | ${snapshot.timeframe.label}:${result.primaryTimeframeBias} | Baseline EMA",
            aiProviderUsed = result.providerBadge,
            aiModelUsed = result.modelUsed,
            originalSnapshotJson = result.rawJson,
            marketRegime = result.marketRegime,
            mtfConfirmation = result.mtfConfirmation,
            entryTrigger = result.effectiveEntryTrigger,
            invalidation = result.invalidIf,
            expiryAt = result.expiryAt,
            setupSignature = result.setupSignature,
            botAction = "NONE",
            status = "ACTIVE",
            highestTpReached = 0,
            realizedR = 0.0,
            resolvedAt = null,
            exitModelVersion = SignalForwardTester.CURRENT_EXIT_MODEL_VERSION,
            source = SOURCE_BASELINE
        )
    }
}
