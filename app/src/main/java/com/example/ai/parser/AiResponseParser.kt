package com.example.ai.parser

import com.example.ai.plan.BrokerVerificationStatus
import com.example.ai.plan.StructuredTradePlan
import com.example.ai.plan.TradeDirection
import com.example.ai.plan.TradeOrderType
import com.example.ai.plan.TradeSetupStatus
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

data class EntryZone(
    val low: Double,
    val high: Double
) {
    val min: Double get() = low
    val max: Double get() = high
}

data class AiAnalysisResult(
    val decision: String,                   // "LONG", "SHORT", "WAIT"
    val bias: String,                       // "BULLISH", "BEARISH", "NEUTRAL"
    val confidence: Int,                    // 0..100 (separate from setupQuality grade)
    val trend: String,                      // "BULLISH", "BEARISH", "SIDEWAYS", "TRANSITIONAL"
    val momentum: String,                   // "POSITIVE", "NEGATIVE", "NEUTRAL"
    val marketStructure: String,            // "HH_HL", "LH_LL", "HL", "LH", "SIDEWAYS", "TRANSITIONAL"
    val setupQuality: String,               // "A+", "A", "B", "WAIT" (also accepts legacy "HIGH", "MEDIUM", "LOW")
    val entryZone: EntryZone,
    val stopLoss: Double,
    val takeProfit: List<Double>,           // [TP1, TP2, TP3]
    val riskReward: Double,
    val support: List<Double>,
    val resistance: List<Double>,
    val higherTimeframeBias: String,        // "BULLISH", "BEARISH", "SIDEWAYS", "TRANSITIONAL"
    val primaryTimeframeBias: String,       // "BULLISH", "BEARISH", "SIDEWAYS", "TRANSITIONAL"
    val lowerTimeframeBias: String,         // "BULLISH", "BEARISH", "PULLBACK", "SIDEWAYS"
    val invalidIf: String,                  // Invalidation condition
    val reasoning: List<String>,            // Evidence bullets
    val summary: String,                    // Concise human-readable summary
    val rawJson: String,
    val marketRegime: String = "RANGING",   // "TRENDING", "RANGING", "BREAKOUT", "PULLBACK", "HIGH VOLATILITY", "LOW VOLATILITY", "CHOPPY"
    val mtfConfirmation: String = "",       // Multi-timeframe agreement/conflict summary
    val entryTrigger: String = "",          // Explicit entry trigger condition
    val expiryAt: Long = System.currentTimeMillis() + 3_600_000L,
    val setupSignature: String = "",        // Deduplication signature for the setup
    val providerBadge: String = "AI · Primary",
    val modelUsed: String = "built-in-v2",
    val analyzedAt: Long = System.currentTimeMillis(),
    val aiSystemMode: String = "AI_CONTINUOUS",
    val aiExecutionMode: String = "AUTO",
    val tradingStyleProfile: String = "NONE",
    val entryOrderType: String = "LIMIT",
    val selectedTimeframesSummary: String = "",
    val dataValidationWarning: String = "",
    val positionSizingGuidance: String = "",
    val setupDecisionState: String = "WAITING_FOR_CONFIRMATION",
    val latestBosSummary: String = "",
    val latestChochSummary: String = "",
    val equalHighsLowsSummary: String = "",
    val orderBlockCandidatesSummary: String = "",
    val liquiditySweepState: String = "NONE",
    val liquiditySweepSummary: String = "",
    val reclaimConfirmationSummary: String = "",
    val tradingSessionBadge: String = "",
    val tradingSessionSummary: String = "",
    val spreadValidationSummary: String = "",
    val eventRiskSummary: String = "",
    val heuristicAlignmentIndex: Int = 50,
    val tokenUsage: com.example.ai.usage.TokenUsageSnapshot? = null,
    val visionAssisted: Boolean = false,
    val visionAssistedSummary: String = "",
    val strategyId: String = "AI_HYBRID",
    val strategyName: String = "AI Hybrid",
    val tradePlan: StructuredTradePlan? = null
) {
    val analysisModeBadge: String
        get() = if (visionAssisted) "👁️ Hybrid Vision + Technical" else "📊 Technical Only"

    val isWait: Boolean get() = decision.equals("WAIT", ignoreCase = true)

    val invalidation: String get() = invalidIf

    val signalActionLabel: String
        get() = when {
            isWait -> "WAIT"
            decision.equals("LONG", ignoreCase = true) -> "BUY"
            decision.equals("SHORT", ignoreCase = true) -> "SELL"
            else -> "WAIT"
        }

    val signalDisplayBadge: String
        get() = when {
            isWait -> "WAIT"
            decision.equals("LONG", ignoreCase = true) -> "BUY (LONG)"
            decision.equals("SHORT", ignoreCase = true) -> "SELL (SHORT)"
            else -> "WAIT"
        }

    val effectiveEntryOrderType: String
        get() = if (isWait) {
            "WAIT"
        } else {
            entryOrderType.trim().uppercase(Locale.US)
                .takeIf { it in listOf("MARKET", "LIMIT", "STOP", "WAIT") }
                ?: "LIMIT"
        }

    val tradeDirection: TradeDirection
        get() = tradePlan?.direction ?: when {
            isWait -> TradeDirection.NEUTRAL
            decision.equals("LONG", ignoreCase = true) -> TradeDirection.BUY
            decision.equals("SHORT", ignoreCase = true) -> TradeDirection.SELL
            else -> TradeDirection.NEUTRAL
        }

    val tradeOrderType: TradeOrderType
        get() = tradePlan?.orderType ?: when {
            isWait -> TradeOrderType.NONE
            effectiveEntryOrderType == "MARKET" -> TradeOrderType.MARKET
            effectiveEntryOrderType == "STOP" -> TradeOrderType.STOP
            else -> TradeOrderType.LIMIT
        }

    val tradeSetupStatus: TradeSetupStatus
        get() = tradePlan?.setupStatus ?: when {
            isWait -> TradeSetupStatus.WAIT
            tradeOrderType == TradeOrderType.MARKET -> TradeSetupStatus.ACTIONABLE
            else -> TradeSetupStatus.PENDING
        }

    val isPendingOrderRecommendation: Boolean
        get() = tradePlan?.isPending ?: (tradeOrderType in listOf(TradeOrderType.LIMIT, TradeOrderType.STOP) && !isWait)

    val isActionableMarketRecommendation: Boolean
        get() = tradePlan?.isActionable ?: (tradeOrderType == TradeOrderType.MARKET && !isWait)

    val structuredOrderActionLabel: String
        get() = tradePlan?.orderActionLabel ?: when {
            isWait -> "WAIT"
            tradeDirection == TradeDirection.BUY -> "${tradeDirection.code} ${tradeOrderType.code}"
            tradeDirection == TradeDirection.SELL -> "${tradeDirection.code} ${tradeOrderType.code}"
            else -> "WAIT"
        }

    val modeAttributionBadge: String
        get() = if (aiSystemMode.equals("AI_TRADING_STYLE", ignoreCase = true)) {
            val profileLabel = when (tradingStyleProfile.trim().uppercase(Locale.US)) {
                "SCALPING" -> "Scalping"
                "SWING_TRADING" -> "Swing Trading"
                else -> "Day Trading"
            }
            "AI Gaya Trading · $profileLabel (Manual)"
        } else {
            val subLabel = if (aiExecutionMode.equals("MANUAL", ignoreCase = true)) "Manual" else "Auto"
            "AI Continuous · $subLabel"
        }

    /**
     * Requirement #1: Standardized Setup Quality Grade: A+, A, B, or WAIT,
     * strictly separate from AI confidence percentage.
     */
    val setupQualityGrade: String
        get() {
            if (isWait) return "WAIT"
            return when (setupQuality.trim().uppercase(Locale.US)) {
                "A+" -> "A+"
                "A", "HIGH" -> "A"
                "B", "MEDIUM" -> "B"
                "WAIT", "LOW", "NONE" -> "WAIT"
                else -> "B"
            }
        }

    val effectiveEntryTrigger: String
        get() = entryTrigger.ifBlank {
            if (isWait) {
                "Wait for multi-timeframe setup confirmation"
            } else {
                "Price holds within Entry Zone (${entryZone.low} - ${entryZone.high})"
            }
        }

    val marketStructureHumanReadable: String
        get() = when (marketStructure.uppercase(Locale.US)) {
            "HH_HL", "BULLISH" -> "Higher High / Higher Low"
            "LH_LL", "BEARISH" -> "Lower High / Lower Low"
            "HL" -> "Higher Low"
            "LH" -> "Lower High"
            "HH_LL", "LH_HL", "TRANSITIONAL" -> "Transitional Shift"
            else -> "Sideways / Range"
        }

    val trendHumanReadable: String
        get() = when (trend.uppercase(Locale.US)) {
            "UPTREND", "BULLISH" -> "Bullish"
            "DOWNTREND", "BEARISH" -> "Bearish"
            "TRANSITIONAL" -> "Transitional"
            else -> "Sideways"
        }

    val momentumHumanReadable: String
        get() = when (momentum.uppercase(Locale.US)) {
            "POSITIVE", "BULLISH", "STRONG" -> "Bullish"
            "NEGATIVE", "BEARISH", "WEAK" -> "Bearish"
            else -> "Neutral"
        }
}

object AiResponseParser {

    /**
     * Parses & strictly validates V2 (and legacy V1) AI JSON output.
     * Supports JSON repair if the LLM returns markdown fences or minor trailing commas.
     * Separates AI confidence (0-100) from setupQuality grade ("A+", "A", "B", "WAIT").
     */
    fun parse(
        rawText: String,
        providerBadge: String = "AI · Primary",
        modelUsed: String = "built-in-v2"
    ): AiAnalysisResult? {
        if (rawText.isBlank()) return null

        val extracted = extractJsonObjectString(rawText) ?: return null
        val obj = tryParseOrRepairJson(extracted) ?: return null

        return try {
            val rawDecision = obj.optString("decision", "").trim().uppercase(Locale.US)
            val rawBias = obj.optString("bias", "").trim().uppercase(Locale.US)

            val decision = when {
                rawDecision == "LONG" -> "LONG"
                rawDecision == "SHORT" -> "SHORT"
                rawDecision == "WAIT" || rawDecision == "HOLD" || rawDecision == "NEUTRAL" -> "WAIT"
                rawBias.contains("BULL") -> "LONG"
                rawBias.contains("BEAR") -> "SHORT"
                rawBias.contains("NEUT") || rawBias.contains("SIDE") || rawBias.contains("WAIT") -> "WAIT"
                else -> return null
            }

            val bias = when (decision) {
                "LONG" -> "BULLISH"
                "SHORT" -> "BEARISH"
                else -> "NEUTRAL"
            }

            if (!obj.has("confidence")) return null
            val rawConfidence = obj.optInt("confidence", -1)
            if (rawConfidence < 0) return null
            val confidence = rawConfidence.coerceIn(0, 100)

            val rawTrend = obj.optString("trend", "").trim().uppercase(Locale.US)
            val trend = when {
                rawTrend.contains("UP") || rawTrend.contains("BULL") -> "BULLISH"
                rawTrend.contains("DOWN") || rawTrend.contains("BEAR") -> "BEARISH"
                rawTrend.contains("TRANS") -> "TRANSITIONAL"
                rawTrend.contains("SIDE") || rawTrend.contains("NEUT") || rawTrend.contains("RANGE") -> "SIDEWAYS"
                rawTrend.isEmpty() && decision == "WAIT" -> "SIDEWAYS"
                else -> return null
            }

            val rawMomentum = obj.optString("momentum", "").trim().uppercase(Locale.US)
            val momentum = when {
                rawMomentum.contains("POS") || rawMomentum.contains("BULL") || rawMomentum.contains("INC") -> "POSITIVE"
                rawMomentum.contains("NEG") || rawMomentum.contains("BEAR") || rawMomentum.contains("DEC") -> "NEGATIVE"
                rawMomentum.isNotEmpty() -> "NEUTRAL"
                decision == "LONG" -> "POSITIVE"
                decision == "SHORT" -> "NEGATIVE"
                else -> "NEUTRAL"
            }

            val marketStructure = obj.optString(
                "marketStructure",
                obj.optString("market_structure", "SIDEWAYS")
            ).trim().uppercase(Locale.US).ifEmpty { "SIDEWAYS" }

            // Entry zone: supports V2 { "min", "max" } and V1 { "low", "high" }
            val entryObj = obj.optJSONObject("entryZone")
                ?: obj.optJSONObject("entry_zone")
                ?: obj.optJSONObject("entry")

            val entryLow: Double
            val entryHigh: Double
            if (entryObj != null) {
                val minVal = if (entryObj.has("min")) entryObj.optDouble("min", Double.NaN) else entryObj.optDouble("low", Double.NaN)
                val maxVal = if (entryObj.has("max")) entryObj.optDouble("max", Double.NaN) else entryObj.optDouble("high", Double.NaN)
                if (decision != "WAIT" && (minVal.isNaN() || maxVal.isNaN() || minVal <= 0.0 || maxVal <= 0.0)) {
                    return null
                }
                entryLow = if (minVal.isNaN() || minVal <= 0.0) 0.0 else minOf(minVal, maxVal)
                entryHigh = if (maxVal.isNaN() || maxVal <= 0.0) 0.0 else maxOf(minVal, maxVal)
            } else if (decision == "WAIT") {
                entryLow = 0.0
                entryHigh = 0.0
            } else {
                return null
            }

            val stopLoss = if (obj.has("stopLoss")) {
                obj.optDouble("stopLoss", Double.NaN)
            } else {
                obj.optDouble("stop_loss", Double.NaN)
            }
            if (decision != "WAIT" && (stopLoss.isNaN() || stopLoss <= 0.0)) {
                return null
            }
            val cleanStopLoss = if (stopLoss.isNaN() || stopLoss < 0.0) 0.0 else stopLoss

            val tpArr = obj.optJSONArray("takeProfit") ?: obj.optJSONArray("take_profit")
            val takeProfits = ArrayList<Double>()
            if (tpArr != null) {
                for (i in 0 until tpArr.length()) {
                    val tp = tpArr.optDouble(i, Double.NaN)
                    if (!tp.isNaN() && tp > 0.0) {
                        takeProfits.add(tp)
                    }
                }
            }
            if (decision != "WAIT" && takeProfits.isEmpty()) {
                return null
            }

            // Calculate actual mathematical R:R when levels exist
            val reportedRr = if (obj.has("riskReward")) {
                obj.optDouble("riskReward", Double.NaN)
            } else {
                obj.optDouble("risk_reward", Double.NaN)
            }
            val midEntry = (entryLow + entryHigh) / 2.0
            val riskDist = abs(midEntry - cleanStopLoss)
            val targetTp = takeProfits.firstOrNull() ?: 0.0
            val computedRr = if (midEntry > 0.0 && cleanStopLoss > 0.0 && targetTp > 0.0 && riskDist > 1e-8) {
                kotlin.math.round((abs(targetTp - midEntry) / riskDist) * 100.0) / 100.0
            } else if (!reportedRr.isNaN() && reportedRr > 0.0) {
                kotlin.math.round(reportedRr * 100.0) / 100.0
            } else {
                0.0
            }

            // Setup quality grade: A+, A, B, or WAIT (supports legacy HIGH/MEDIUM/LOW)
            val rawQuality = obj.optString(
                "setupQuality",
                obj.optString("setup_quality", "")
            ).trim().uppercase(Locale.US)

            val setupQuality = if (decision == "WAIT") {
                "WAIT"
            } else {
                when (rawQuality) {
                    "A+" -> "A+"
                    "A", "HIGH" -> if (computedRr >= 2.0 && confidence >= 80) "A+" else "A"
                    "B", "MEDIUM" -> "B"
                    "WAIT", "LOW", "NONE" -> "WAIT"
                    else -> when {
                        computedRr >= 2.0 && confidence >= 80 -> "A+"
                        computedRr >= 1.6 && confidence >= 72 -> "A"
                        else -> "B"
                    }
                }
            }

            val supportList = ArrayList<Double>()
            obj.optJSONArray("support")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optDouble(i, Double.NaN)
                    if (!s.isNaN() && s > 0.0) supportList.add(s)
                }
            }

            val resistanceList = ArrayList<Double>()
            obj.optJSONArray("resistance")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val r = arr.optDouble(i, Double.NaN)
                    if (!r.isNaN() && r > 0.0) resistanceList.add(r)
                }
            }

            val higherTfBias = obj.optString("higherTimeframeBias", trend).trim().uppercase(Locale.US).ifEmpty { trend }
            val primaryTfBias = obj.optString("primaryTimeframeBias", trend).trim().uppercase(Locale.US).ifEmpty { trend }
            val lowerTfBias = obj.optString("lowerTimeframeBias", "NEUTRAL").trim().uppercase(Locale.US).ifEmpty { "NEUTRAL" }

            val marketRegime = obj.optString("marketRegime", obj.optString("market_regime", "RANGING"))
                .trim().uppercase(Locale.US).replace("_", " ").ifEmpty { "RANGING" }

            val mtfConfirmation = obj.optString("mtfConfirmation", obj.optString("mtf_confirmation", "")).trim()
                .ifEmpty { "HTF:$higherTfBias | Primary:$primaryTfBias | LTF:$lowerTfBias" }

            val entryTrigger = obj.optString("entryTrigger", obj.optString("entry_trigger", "")).trim()
                .ifEmpty {
                    if (decision == "WAIT") "Wait for clean multi-timeframe setup"
                    else "Enter $decision within $entryLow - $entryHigh with structure hold"
                }

            val analyzedNow = System.currentTimeMillis()
            val expiryMinutes = obj.optInt("expiryMinutes", 180).coerceIn(5, 2880)
            val expiryAt = obj.optLong("expiryAt", analyzedNow + expiryMinutes * 60_000L)

            val setupSignature = obj.optString("setupSignature", "").trim().ifEmpty {
                buildSetupSignature(decision, entryLow, entryHigh, cleanStopLoss)
            }

            val aiSystemMode = obj.optString("aiSystemMode", "AI_CONTINUOUS").trim().ifEmpty { "AI_CONTINUOUS" }
            val aiExecutionMode = obj.optString("aiExecutionMode", "AUTO").trim().ifEmpty { "AUTO" }
            val tradingStyleProfile = obj.optString("tradingStyleProfile", "NONE").trim().ifEmpty { "NONE" }
            val rawEntryOrderType = obj.optString(
                "orderType",
                obj.optString(
                    "order_type",
                    obj.optString("entryOrderType", obj.optString("entryPlan", ""))
                )
            ).trim().uppercase(Locale.US)
            val entryOrderType = if (decision == "WAIT" || setupQuality == "WAIT") {
                "WAIT"
            } else {
                rawEntryOrderType.takeIf { it in listOf("MARKET", "LIMIT", "STOP", "WAIT") } ?: "LIMIT"
            }
            val selectedTimeframesSummary = obj.optString("selectedTimeframesSummary", "").trim()
            val dataValidationWarning = obj.optString("dataValidationWarning", "").trim()
            val positionSizingGuidance = obj.optString("positionSizingGuidance", "").trim()
            val rawDecisionState = obj.optString("setupDecisionState", "").trim()
            val setupDecisionState = rawDecisionState.ifEmpty {
                if (decision == "WAIT" || setupQuality == "WAIT") "WAITING_FOR_CONFIRMATION" else "VALID_CANDIDATE"
            }
            val latestBosSummary = obj.optString("latestBosSummary", "").trim()
            val latestChochSummary = obj.optString("latestChochSummary", "").trim()
            val equalHighsLowsSummary = obj.optString("equalHighsLowsSummary", "").trim()
            val orderBlockCandidatesSummary = obj.optString("orderBlockCandidatesSummary", "").trim()
            val liquiditySweepState = obj.optString("liquiditySweepState", "NONE").trim().ifEmpty { "NONE" }
            val liquiditySweepSummary = obj.optString("liquiditySweepSummary", "").trim()
            val reclaimConfirmationSummary = obj.optString("reclaimConfirmationSummary", "").trim()
            val tradingSessionBadge = obj.optString("tradingSessionBadge", "").trim()
            val tradingSessionSummary = obj.optString("tradingSessionSummary", "").trim()
            val spreadValidationSummary = obj.optString("spreadValidationSummary", "").trim()
            val eventRiskSummary = obj.optString("eventRiskSummary", "").trim()
            val heuristicAlignmentIndex = obj.optInt("heuristicAlignmentIndex", confidence).coerceIn(0, 100)
            val rawStrategyId = obj.optString("selectedAiStrategy", obj.optString("strategyId", "AI_HYBRID")).trim()
            val parsedStrategy = com.example.ai.strategy.AiStrategy.fromId(rawStrategyId)
            val parsedStrategyName = obj.optString("selectedAiStrategyName", obj.optString("strategyName", parsedStrategy.displayName)).trim()
                .ifEmpty { parsedStrategy.displayName }

            val invalidationRaw = obj.optString(
                "invalidation",
                obj.optString("invalid_if", "Price closes beyond invalidation level")
            ).trim()
            val invalidIf = sanitizeForbiddenPhrases(invalidationRaw.ifEmpty { "Price closes beyond stop loss level" })

            val reasoningList = ArrayList<String>()
            val reasoningArr = obj.optJSONArray("reasoning")
            if (reasoningArr != null) {
                for (i in 0 until reasoningArr.length()) {
                    val item = sanitizeForbiddenPhrases(reasoningArr.optString(i, "").trim())
                    if (item.isNotEmpty()) reasoningList.add(item)
                }
            }

            val rawSummary = sanitizeForbiddenPhrases(obj.optString("summary", "").trim())
            val summary = when {
                rawSummary.isNotEmpty() -> rawSummary
                reasoningList.isNotEmpty() -> reasoningList.joinToString(" · ")
                else -> return null
            }
            if (reasoningList.isEmpty() && summary.isNotEmpty()) {
                reasoningList.add(summary)
            }

            // Structured Trade Plan Parsing (V3.1A)
            val rawTradeDirection = obj.optString("tradeDirection", obj.optString("trade_direction", "")).trim()
            val parsedTradeDirection = when {
                decision == "WAIT" || setupQuality == "WAIT" -> TradeDirection.NEUTRAL
                rawTradeDirection.isNotBlank() -> TradeDirection.fromString(rawTradeDirection)
                decision == "LONG" -> TradeDirection.BUY
                decision == "SHORT" -> TradeDirection.SELL
                else -> TradeDirection.NEUTRAL
            }

            val parsedOrderType = when {
                decision == "WAIT" || setupQuality == "WAIT" || parsedTradeDirection == TradeDirection.NEUTRAL -> TradeOrderType.NONE
                entryOrderType == "MARKET" -> TradeOrderType.MARKET
                entryOrderType == "LIMIT" -> TradeOrderType.LIMIT
                entryOrderType == "STOP" -> TradeOrderType.STOP
                else -> TradeOrderType.LIMIT
            }

            val rawSetupStatus = obj.optString("setupStatus", obj.optString("setup_status", "")).trim()
            val parsedSetupStatus = when {
                decision == "WAIT" || setupQuality == "WAIT" || parsedTradeDirection == TradeDirection.NEUTRAL -> TradeSetupStatus.WAIT
                rawSetupStatus.isNotBlank() -> TradeSetupStatus.fromString(rawSetupStatus)
                parsedOrderType == TradeOrderType.MARKET -> TradeSetupStatus.ACTIONABLE
                else -> TradeSetupStatus.PENDING
            }

            val parsedMarketBias = obj.optString("marketBias", obj.optString("market_bias", bias)).trim().uppercase(Locale.US).ifEmpty { bias }

            val parsedRefPrice = if (obj.has("referencePrice")) {
                obj.optDouble("referencePrice", Double.NaN)
            } else if (obj.has("reference_price")) {
                obj.optDouble("reference_price", Double.NaN)
            } else if (obj.has("currentPrice")) {
                obj.optDouble("currentPrice", Double.NaN)
            } else {
                Double.NaN
            }

            val rawSetupRationale = obj.optString("setupRationale", obj.optString("setup_rationale", "")).trim()
            val setupRationale = rawSetupRationale.ifEmpty { summary }

            val supportingEvidenceList = ArrayList<String>()
            val supArr = obj.optJSONArray("supportingEvidence") ?: obj.optJSONArray("supporting_evidence")
            if (supArr != null) {
                for (i in 0 until supArr.length()) {
                    val s = sanitizeForbiddenPhrases(supArr.optString(i, "").trim())
                    if (s.isNotEmpty()) supportingEvidenceList.add(s)
                }
            }
            if (supportingEvidenceList.isEmpty()) {
                supportingEvidenceList.addAll(reasoningList)
            }

            val contradictingEvidenceList = ArrayList<String>()
            val conArr = obj.optJSONArray("contradictingEvidence") ?: obj.optJSONArray("contradicting_evidence")
            if (conArr != null) {
                for (i in 0 until conArr.length()) {
                    val c = sanitizeForbiddenPhrases(conArr.optString(i, "").trim())
                    if (c.isNotEmpty()) contradictingEvidenceList.add(c)
                }
            }

            val structuredTradePlan = if (parsedSetupStatus == TradeSetupStatus.WAIT || parsedTradeDirection == TradeDirection.NEUTRAL) {
                StructuredTradePlan(
                    strategy = parsedStrategy,
                    strategyId = parsedStrategy.id,
                    strategyName = parsedStrategyName,
                    symbol = obj.optString("symbol", "").trim(),
                    timeframe = obj.optString("timeframe", "").trim(),
                    marketBias = parsedMarketBias,
                    direction = TradeDirection.NEUTRAL,
                    orderType = TradeOrderType.NONE,
                    setupStatus = TradeSetupStatus.WAIT,
                    referencePrice = if (parsedRefPrice.isNaN() || parsedRefPrice <= 0.0) 0.0 else parsedRefPrice,
                    referenceTimestamp = analyzedNow,
                    entryZoneMin = null,
                    entryZoneMax = null,
                    stopLoss = null,
                    tp1 = null,
                    tp2 = null,
                    tp3 = null,
                    riskRewardTp1 = null,
                    riskRewardTp2 = null,
                    riskRewardTp3 = null,
                    setupRationale = setupRationale,
                    supportingEvidence = supportingEvidenceList,
                    contradictingEvidence = contradictingEvidenceList,
                    invalidationCondition = invalidIf,
                    brokerVerificationStatus = BrokerVerificationStatus.UNVERIFIED_BROKER_DATA
                )
            } else {
                StructuredTradePlan(
                    strategy = parsedStrategy,
                    strategyId = parsedStrategy.id,
                    strategyName = parsedStrategyName,
                    symbol = obj.optString("symbol", "").trim(),
                    timeframe = obj.optString("timeframe", "").trim(),
                    marketBias = parsedMarketBias,
                    direction = parsedTradeDirection,
                    orderType = parsedOrderType,
                    setupStatus = parsedSetupStatus,
                    referencePrice = if (parsedRefPrice.isNaN() || parsedRefPrice <= 0.0) (entryLow + entryHigh) / 2.0 else parsedRefPrice,
                    referenceTimestamp = analyzedNow,
                    entryZoneMin = entryLow,
                    entryZoneMax = entryHigh,
                    stopLoss = cleanStopLoss,
                    tp1 = takeProfits.firstOrNull(),
                    tp2 = takeProfits.getOrNull(1),
                    tp3 = takeProfits.getOrNull(2),
                    riskRewardTp1 = computedRr,
                    riskRewardTp2 = null,
                    riskRewardTp3 = null,
                    setupRationale = setupRationale,
                    supportingEvidence = supportingEvidenceList,
                    contradictingEvidence = contradictingEvidenceList,
                    invalidationCondition = invalidIf,
                    brokerVerificationStatus = BrokerVerificationStatus.UNVERIFIED_BROKER_DATA
                )
            }

            AiAnalysisResult(
                decision = if (setupQuality == "WAIT") "WAIT" else decision,
                bias = if (setupQuality == "WAIT") "NEUTRAL" else bias,
                confidence = confidence,
                trend = trend,
                momentum = momentum,
                marketStructure = marketStructure,
                setupQuality = setupQuality,
                entryZone = EntryZone(low = entryLow, high = entryHigh),
                stopLoss = cleanStopLoss,
                takeProfit = takeProfits.take(3),
                riskReward = computedRr,
                support = supportList,
                resistance = resistanceList,
                higherTimeframeBias = higherTfBias,
                primaryTimeframeBias = primaryTfBias,
                lowerTimeframeBias = lowerTfBias,
                invalidIf = invalidIf,
                reasoning = reasoningList,
                summary = summary,
                rawJson = obj.toString(2),
                marketRegime = marketRegime,
                mtfConfirmation = mtfConfirmation,
                entryTrigger = entryTrigger,
                expiryAt = expiryAt,
                setupSignature = setupSignature,
                providerBadge = providerBadge,
                modelUsed = modelUsed,
                analyzedAt = analyzedNow,
                aiSystemMode = aiSystemMode,
                aiExecutionMode = aiExecutionMode,
                tradingStyleProfile = tradingStyleProfile,
                entryOrderType = entryOrderType,
                selectedTimeframesSummary = selectedTimeframesSummary,
                dataValidationWarning = dataValidationWarning,
                positionSizingGuidance = positionSizingGuidance,
                setupDecisionState = setupDecisionState,
                latestBosSummary = latestBosSummary,
                latestChochSummary = latestChochSummary,
                equalHighsLowsSummary = equalHighsLowsSummary,
                orderBlockCandidatesSummary = orderBlockCandidatesSummary,
                liquiditySweepState = liquiditySweepState,
                liquiditySweepSummary = liquiditySweepSummary,
                reclaimConfirmationSummary = reclaimConfirmationSummary,
                tradingSessionBadge = tradingSessionBadge,
                tradingSessionSummary = tradingSessionSummary,
                spreadValidationSummary = spreadValidationSummary,
                eventRiskSummary = eventRiskSummary,
                heuristicAlignmentIndex = heuristicAlignmentIndex,
                strategyId = parsedStrategy.id,
                strategyName = parsedStrategyName,
                tradePlan = structuredTradePlan
            )
        } catch (_: Exception) {
            null
        }
    }

    fun buildSetupSignature(
        decision: String,
        entryLow: Double,
        entryHigh: Double,
        stopLoss: Double
    ): String {
        if (decision.equals("WAIT", ignoreCase = true)) return "WAIT"
        val mid = (entryLow + entryHigh) / 2.0
        val step = (mid * 0.003).coerceAtLeast(1e-6)
        val entryBucket = kotlin.math.round(mid / step).toLong()
        val slBucket = kotlin.math.round(stopLoss / step).toLong()
        return "${decision.uppercase(Locale.US)}_${entryBucket}_$slBucket"
    }

    private fun extractJsonObjectString(text: String): String? {
        val stripped = text
            .replace("```json", "")
            .replace("```JSON", "")
            .replace("```", "")
            .trim()
        val start = stripped.indexOf('{')
        val end = stripped.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) return null
        return stripped.substring(start, end + 1)
    }

    private fun tryParseOrRepairJson(jsonCandidate: String): JSONObject? {
        try {
            return JSONObject(jsonCandidate)
        } catch (_: Exception) {
            return try {
                val repaired = jsonCandidate
                    .replace(Regex(",\\s*\\}"), "}")
                    .replace(Regex(",\\s*\\]"), "]")
                JSONObject(repaired)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun sanitizeForbiddenPhrases(input: String): String {
        return input
            .replace(Regex("(?i)guaranteed profit"), "favorable risk/reward setup")
            .replace(Regex("(?i)pasti naik"), "bullish bias")
            .replace(Regex("(?i)pasti turun"), "bearish bias")
    }
}
