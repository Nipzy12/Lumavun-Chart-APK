package com.example.analysis.signal

import com.example.ai.analyst.AiAnalysisRequestContext
import com.example.ai.parser.AiAnalysisResult
import com.example.ai.parser.EntryZone
import com.example.ai.plan.BrokerVerificationStatus
import com.example.ai.plan.StructuredTradePlan
import com.example.ai.plan.TradeDirection
import com.example.ai.plan.TradeOrderType
import com.example.ai.plan.TradeSetupStatus
import com.example.analysis.supportResistance.SnrAutoAnalysisEngine
import com.example.analysis.technical.MarketRegime
import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.analysis.technical.MtfConfirmationStatus
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Converts an otherwise WAIT-only analysis to a conditional, unfilled LIMIT *proposal*
 * only when a recently CLOSED candle confirms a real chart S/R reaction.
 *
 * Does NOT convert blocked/unknown WAIT signals indiscriminately, place a broker
 * order, or pretend a pending recommendation has already been executed.
 */
object ConfirmedLimitRecommendationEngine {
    const val MODEL_ID = "confirmed-snr-limit-v1"

    fun propose(
        originalWait: AiAnalysisResult,
        snapshot: MarketTechnicalSnapshot,
        context: AiAnalysisRequestContext,
        nowMs: Long = System.currentTimeMillis()
    ): AiAnalysisResult? {
        if (!originalWait.isWait || !context.isDataValidForEntry) return null
        if (context.eventRiskAssessment?.shouldBlockNewBotEntries == true) return null
        if (snapshot.marketRegime in listOf(MarketRegime.CHOPPY, MarketRegime.INSUFFICIENT_DATA)) return null
        if (snapshot.isVolatilityAbnormal || snapshot.mtfConfirmation.isHigherTfStale ||
            snapshot.mtfConfirmation.status == MtfConfirmationStatus.CONFLICT
        ) return null
        if (listOf(snapshot.hasEmaConflict, snapshot.hasRsiConflict, snapshot.hasMacdConflict).count { it } >= 2) return null

        val confirmed = snapshot.recentCandles.filter { it.isClosed && it.isValid() }
        if (confirmed.size < 20) return null
        val candle = confirmed.last()
        val period = max(snapshot.timeframe.durationMs, 60_000L)
        val age = nowMs - candle.closeTime
        if (age < 0L || age > period * 2L) return null

        val price = snapshot.currentPrice
        val atr = snapshot.atr14?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        if (!price.isFinite() || price <= 0.0 || abs(price - candle.close) > atr * 1.25) return null

        val reaction = SnrAutoAnalysisEngine.evaluate(
            candles = confirmed,
            supports = snapshot.supportLevels,
            resistances = snapshot.resistanceLevels,
            atr14 = atr
        )
        if (!reaction.shouldAnalyze) return null
        val level = reaction.level?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val isBuy = reaction.type in setOf("SUPPORT_REJECTION", "RESISTANCE_BREAKOUT")
        val isSell = reaction.type in setOf("RESISTANCE_REJECTION", "SUPPORT_BREAKDOWN")
        if (!isBuy && !isSell) return null

        val primaryBias = snapshot.primaryContext?.marketCondition ?: snapshot.marketStructure.state.name
        val higherBias = snapshot.higherContext?.marketCondition
        if (isBuy && (primaryBias == "BEARISH" || higherBias == "BEARISH")) return null
        if (isSell && (primaryBias == "BULLISH" || higherBias == "BULLISH")) return null

        val halfZone = max(atr * 0.07, price * 0.00012)
        val entryLow = level - halfZone
        val entryHigh = level + halfZone
        // True LIMIT order: BUY must be below the current price, SELL above it.
        if (entryLow <= 0.0 || (isBuy && entryHigh >= price) ||
            (isSell && entryLow <= price)) return null
        if (abs(price - level) > atr * 2.5) return null

        val stopDistance = max(atr * 0.7, price * 0.0009)
        val sl = if (isBuy) entryLow - stopDistance else entryHigh + stopDistance
        if (sl <= 0.0) return null

        // Never manufacture imaginary targets: TP1 must be the measured opposing S/R.
        val tp = if (isBuy) {
            snapshot.resistanceLevels.filter { it.isFinite() && it > price + halfZone }.minOrNull()
        } else {
            snapshot.supportLevels.filter { it.isFinite() && it > 0.0 && it < price - halfZone }.maxOrNull()
        } ?: return null

        val worstRisk = if (isBuy) entryHigh - sl else sl - entryLow
        val worstReward = if (isBuy) tp - entryHigh else entryLow - tp
        val minimumRr = max(
            SignalValidationEngine.resolveMinimumRiskReward(context.tradingStyleProfile),
            TradePlanValidationEngine.resolveMinimumRiskReward(
                context.selectedAiStrategy, context.tradingStyleProfile
            )
        )
        if (worstRisk <= 0.0 || worstReward / worstRisk < minimumRr) return null

        // This is a heuristic *evidence score*, not a forecast success probability.
        val aligned = higherBias != null &&
            higherBias == (if (isBuy) "BULLISH" else "BEARISH")
        val score = (64 + (if (aligned) 7 else 0) +
            (if (snapshot.mtfConfirmation.status == MtfConfirmationStatus.ALIGNED) 4 else 0) +
            (if (!snapshot.isVolumeWeak) 3 else 0)).coerceAtMost(79)
        val side = if (isBuy) "BUY" else "SELL"
        val direction = if (isBuy) "LONG" else "SHORT"
        val expiryAt = nowMs + min(period * 5L, 24L * 60L * 60L * 1000L)
        val rr = worstReward / worstRisk
        val reason = "Closed candle ${snapshot.timeframe.label}: ${reaction.reason} at " +
            String.format(Locale.US, "%.6f", level)
        val notes = listOf(
            reason,
            "LIMIT is pending, not filled: price must revisit the measured entry zone.",
            "Measured opposing S/R target; R:R at the worst entry boundary " +
                String.format(Locale.US, "1:%.2f", rr),
            "Technical-only conditional setup. No live broker order or execution."
        )
        val summary = "$side LIMIT · PENDING CONFIRMED SETUP; $reason. " +
            "Not a live order. Recheck spread and level validity before acting."
        val signature = "${snapshot.symbol}:${snapshot.timeframe.label}:${candle.openTime}:" +
            "${reaction.type}:${String.format(Locale.US, "%.6f", level)}"
        val order = StructuredTradePlan(
            strategy = context.selectedAiStrategy,
            symbol = snapshot.symbol,
            timeframe = snapshot.timeframe.label,
            marketBias = if (isBuy) "BULLISH" else "BEARISH",
            direction = if (isBuy) TradeDirection.BUY else TradeDirection.SELL,
            orderType = TradeOrderType.LIMIT,
            setupStatus = TradeSetupStatus.PENDING,
            referencePrice = price,
            referenceTimestamp = nowMs,
            entryZoneMin = entryLow,
            entryZoneMax = entryHigh,
            stopLoss = sl,
            tp1 = tp,
            setupRationale = summary,
            supportingEvidence = notes,
            invalidationCondition = if (isBuy)
                "Cancel if confirmed candle closes below $sl"
                else "Cancel if confirmed candle closes above $sl",
            brokerVerificationStatus = BrokerVerificationStatus.UNVERIFIED_BROKER_DATA
        )
        val rawJson = JSONObject().apply {
            put("decision", direction)
            put("tradeDirection", side)
            put("orderType", "LIMIT")
            put("setupStatus", "PENDING")
            put("setupDecisionState", "CONFIRMED_PENDING_LIMIT")
            put("confidence", score)
            put("setupQuality", "B")
            put("entryZone", JSONObject().put("min", entryLow).put("max", entryHigh))
            put("stopLoss", sl)
            put("takeProfit", JSONArray().put(tp))
            put("riskReward", rr)
            put("referencePrice", price)
            put("symbol", snapshot.symbol)
            put("timeframe", snapshot.timeframe.label)
            put("selectedAiStrategy", context.selectedAiStrategy.id)
            put("marketRegime", snapshot.marketRegime.code)
            put("setupSignature", signature)
            put("expiryAt", expiryAt)
            put("reasoning", JSONArray(notes))
            put("summary", summary)
        }.toString()
        val candidate = originalWait.copy(
            decision = direction,
            bias = if (isBuy) "BULLISH" else "BEARISH",
            confidence = score,
            setupQuality = "B",
            entryZone = EntryZone(entryLow, entryHigh),
            stopLoss = sl,
            takeProfit = listOf(tp),
            riskReward = rr,
            support = snapshot.supportLevels,
            resistance = snapshot.resistanceLevels,
            invalidIf = order.invalidationCondition,
            reasoning = notes,
            summary = summary,
            rawJson = rawJson,
            providerBadge = "Technical · S/R",
            modelUsed = MODEL_ID,
            analyzedAt = nowMs,
            entryOrderType = "LIMIT",
            setupDecisionState = "CONFIRMED_PENDING_LIMIT",
            setupSignature = signature,
            expiryAt = expiryAt,
            strategyId = context.selectedAiStrategy.id,
            strategyName = context.selectedAiStrategy.displayName,
            tradePlan = order,
            higherTimeframeBias = higherBias ?: "NEUTRAL",
            primaryTimeframeBias = primaryBias
        )
        val verified = SignalValidationEngine.validateAndNormalize(
            candidate, snapshot, context, currentTimeMs = nowMs
        )
        return verified.takeIf {
            !it.isWait && it.tradePlan?.isPending == true &&
                it.tradePlan?.orderType == TradeOrderType.LIMIT
        }
    }
}
