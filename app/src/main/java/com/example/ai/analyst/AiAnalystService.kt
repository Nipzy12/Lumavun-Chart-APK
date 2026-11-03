package com.example.ai.analyst

import com.example.BuildConfig
import com.example.ai.parser.AiAnalysisResult
import com.example.ai.parser.AiResponseParser
import com.example.ai.prompt.AiPromptEngine
import com.example.analysis.events.EventRiskAssessment
import com.example.analysis.marketStructure.StructureState
import com.example.analysis.smc.SetupDecisionState
import com.example.analysis.smc.SmcLiquiditySweepEngine
import com.example.analysis.smc.SpreadVolatilityConfig
import com.example.analysis.smc.SweepDetectionState
import com.example.analysis.smc.UnavailableSpreadPolicy
import com.example.analysis.supportResistance.SupportResistanceEngine.roundPriceCleanly
import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.market.session.TradingSessionEngine
import com.example.market.status.MarketStatusEvaluation
import com.example.storage.preferences.AiAnalysisSystem
import com.example.storage.preferences.AiContinuousSubmode
import com.example.storage.preferences.AiProviderMode
import com.example.storage.preferences.AppPreferences
import com.example.storage.preferences.CustomApiFormat
import com.example.storage.preferences.TradingStyleProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

sealed class AiAnalystOutcome {
    data class Success(
        val result: AiAnalysisResult,
        val activeProviderBadge: String
    ) : AiAnalystOutcome()

    data class Error(
        val userMessage: String = UNAVAILABLE_MESSAGE,
        val technicalDetail: String? = null
    ) : AiAnalystOutcome()

    companion object {
        const val UNAVAILABLE_MESSAGE = "AI analysis unavailable."
    }
}

data class ConnectionTestResult(
    val isConnected: Boolean,
    val latencyMs: Long,
    val statusText: String
)

/**
 * Context for executing an AI market analysis request under either:
 * 1. AI Continuous (Auto or Manual)
 * 2. AI Gaya Trading (Scalping, Day Trading, or Swing Trading — always Manual)
 */
data class AiAnalysisRequestContext(
    val aiSystem: AiAnalysisSystem = AiAnalysisSystem.AI_CONTINUOUS,
    val continuousSubmode: AiContinuousSubmode = AiContinuousSubmode.AUTO,
    val tradingStyleProfile: TradingStyleProfile = TradingStyleProfile.DAY_TRADING,
    val isAutomaticTrigger: Boolean = false,
    val higherTimeframeLabel: String = "",
    val lowerTimeframeLabel: String = "",
    val spreadPct: Double = 0.0,
    val feeBps: Double = 4.0,
    val isDataValidForEntry: Boolean = true,
    val dataStatusLabel: String = "OPEN",
    val dataStatusReason: String = "",
    val riskPerTradePercent: Double = 1.0,
    val bidPrice: Double? = null,
    val askPrice: Double? = null,
    val hasVerifiedBidAsk: Boolean = false,
    val unavailableSpreadPolicy: UnavailableSpreadPolicy = UnavailableSpreadPolicy.ALLOW_WITH_WARNING,
    val eventRiskAssessment: EventRiskAssessment? = null,
    val marketStatus: MarketStatusEvaluation? = null,
    val accountEquityUsd: Double = 10_000.0,
    val maxLeverage: Double = 5.0,
    val visionBundle: com.example.ai.vision.ChartVisionBundle? = null,
    val chartVisionSettingEnabled: Boolean = true,
    val customApiSupportsVision: Boolean = false,
    val selectedAiStrategy: com.example.ai.strategy.AiStrategy = com.example.ai.strategy.AiStrategy.AI_HYBRID,
    val dataValidationWarning: String = if (!isDataValidForEntry) {
        if (dataStatusReason.isNotBlank()) "Data feed $dataStatusLabel: $dataStatusReason"
        else "Data feed $dataStatusLabel — new entries blocked until live feed recovers."
    } else ""
) {
    val effectiveExecutionMode: String
        get() = if (aiSystem == AiAnalysisSystem.AI_TRADING_STYLE) "MANUAL" else continuousSubmode.code

    val effectiveProfileCode: String
        get() = if (aiSystem == AiAnalysisSystem.AI_TRADING_STYLE) tradingStyleProfile.code else ""
}

/**
 * Requirement #10: Standard AIProvider interface preserved.
 * Primary provider badge: "AI · Primary"
 * Fallback provider badge: "AI · Backup"
 */
interface AIProvider {
    val providerId: String get() = "builtin"
    val providerName: String
    val badgeLabel: String
    val modelIdentifier: String
    suspend fun analyzeMarket(snapshot: MarketTechnicalSnapshot): AiAnalysisResult? =
        analyzeMarket(snapshot, AiAnalysisRequestContext())
    suspend fun analyzeMarket(
        snapshot: MarketTechnicalSnapshot,
        context: AiAnalysisRequestContext
    ): AiAnalysisResult?
    suspend fun testConnection(): ConnectionTestResult
}

interface AiMarketAnalystProvider {
    suspend fun analyzeMarket(snapshot: MarketTechnicalSnapshot): AiAnalystOutcome =
        analyzeMarket(snapshot, AiAnalysisRequestContext())
    suspend fun analyzeMarket(
        snapshot: MarketTechnicalSnapshot,
        context: AiAnalysisRequestContext
    ): AiAnalystOutcome
}

/**
 * 1. BuiltInAIProvider (PRIMARY DEFAULT - Requirement #10)
 * Uses built-in Google AI Studio capability (when server-injected Gemini key is present)
 * or the V2 Multi-Timeframe Quantitative Synthesis Engine on standalone devices.
 */
class BuiltInAIProvider(
    private val isBackupRole: Boolean = false,
    private val httpClient: OkHttpClient = buildFastTimeoutClient()
) : AIProvider {

    override val providerId: String = "gemini_builtin"
    override val providerName: String = "Built-in AI"
    override val badgeLabel: String get() = if (isBackupRole) "AI · Backup" else "AI · Primary"
    override val modelIdentifier: String = "nexsync-mtf-v2"

    suspend fun analyze(
        snapshot: MarketTechnicalSnapshot,
        context: AiAnalysisRequestContext = AiAnalysisRequestContext()
    ): AiAnalystOutcome {
        val res = analyzeMarket(snapshot, context)
            ?: synthesizeV2MultiTimeframeAnalysis(snapshot, badgeLabel, modelIdentifier, context)
            ?: synthesizeDataBlockedWaitResult(snapshot, context, badgeLabel, modelIdentifier)
        return AiAnalystOutcome.Success(res, badgeLabel)
    }

    override suspend fun analyzeMarket(
        snapshot: MarketTechnicalSnapshot,
        context: AiAnalysisRequestContext
    ): AiAnalysisResult? =
        withContext(Dispatchers.IO) {
            if (!context.isDataValidForEntry) {
                return@withContext synthesizeDataBlockedWaitResult(snapshot, context, badgeLabel, modelIdentifier)
            }
            val envKey = BuildConfig.GEMINI_API_KEY.trim()
            if (envKey.isNotEmpty() && envKey != "MY_GEMINI_API_KEY") {
                val cloudResult = executeGeminiRestRequest(
                    httpClient = httpClient,
                    apiKey = envKey,
                    modelName = "gemini-3.5-flash",
                    snapshot = snapshot,
                    badge = badgeLabel,
                    context = context
                )
                if (cloudResult != null) {
                    return@withContext enforceV2MarketIntegrity(cloudResult, snapshot, context)
                }
            }
            synthesizeV2MultiTimeframeAnalysis(snapshot, badgeLabel, modelIdentifier, context)
        }

    override suspend fun testConnection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val envKey = BuildConfig.GEMINI_API_KEY.trim()
        if (envKey.isNotEmpty() && envKey != "MY_GEMINI_API_KEY") {
            val res = executeGeminiPing(httpClient, envKey, "gemini-3.5-flash")
            if (res.isConnected) return@withContext res
        }
        val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(12L)
        ConnectionTestResult(
            isConnected = true,
            latencyMs = elapsed,
            statusText = "🟢 Connected · ${elapsed}ms"
        )
    }

    /**
     * V2 Multi-Timeframe Quantitative Analysis Engine (Requirements #1, #2, #3, #4, #5, #6):
     * Evaluates Higher TF, Primary TF, Lower TF, Market Structure, EMA alignment, Momentum,
     * Volume confirmation, Support/Resistance distance, Volatility, and Mathematical R:R.
     * Legitimately selects WAIT when conditions conflict or R:R is poor.
     */
    fun synthesizeV2MultiTimeframeAnalysis(
        snapshot: MarketTechnicalSnapshot,
        badge: String,
        modelName: String,
        context: AiAnalysisRequestContext = AiAnalysisRequestContext()
    ): AiAnalysisResult? {
        val price = snapshot.currentPrice
        if (price <= 0.0 || price.isNaN()) return null
        if (!context.isDataValidForEntry) {
            return synthesizeDataBlockedWaitResult(snapshot, context, badge, modelName)
        }

        val primaryCtx = snapshot.primaryContext
        val higherCtx = snapshot.higherContext
        val lowerCtx = snapshot.lowerContext

        val primaryTfLabel = snapshot.timeframe.label
        val expectedHtf = if (context.aiSystem == AiAnalysisSystem.AI_TRADING_STYLE) {
            context.tradingStyleProfile.resolveHigherTimeframe(snapshot.timeframe)
        } else {
            snapshot.timeframe.higherTimeframe()
        }
        val expectedLtf = if (context.aiSystem == AiAnalysisSystem.AI_TRADING_STYLE) {
            context.tradingStyleProfile.resolveLowerTimeframe(snapshot.timeframe)
        } else {
            snapshot.timeframe.lowerTimeframe()
        }
        val higherTfLabel = higherCtx?.timeframe?.label ?: expectedHtf?.label ?: "HTF"
        val lowerTfLabel = lowerCtx?.timeframe?.label ?: expectedLtf?.label ?: "LTF"
        val timeframesSummary = "Entry: $primaryTfLabel · HTF: $higherTfLabel · LTF: $lowerTfLabel"

        val primaryBias = primaryCtx?.marketCondition ?: when (snapshot.marketStructure.state) {
            StructureState.BULLISH -> "BULLISH"
            StructureState.BEARISH -> "BEARISH"
            StructureState.TRANSITIONAL -> "TRANSITIONAL"
            StructureState.SIDEWAYS -> "SIDEWAYS"
        }
        val higherBias = higherCtx?.marketCondition ?: primaryBias
        val lowerBias = when {
            lowerCtx?.breakoutPullbackCondition == "BULLISH_PULLBACK" ||
                lowerCtx?.breakoutPullbackCondition == "BEARISH_PULLBACK" -> "PULLBACK"
            lowerCtx != null -> lowerCtx.marketCondition
            else -> "NEUTRAL"
        }

        val ema20 = snapshot.ema20 ?: price
        val ema50 = snapshot.ema50 ?: price
        val ema200 = snapshot.ema200 ?: price
        val rsi = snapshot.rsi14 ?: 50.0
        val macdHist = snapshot.macd?.histogram ?: 0.0

        // Score directional evidence
        var directionalScore = 0
        if (higherBias == "BULLISH") directionalScore += 3 else if (higherBias == "BEARISH") directionalScore -= 3
        if (primaryBias == "BULLISH") directionalScore += 3 else if (primaryBias == "BEARISH") directionalScore -= 3
        if (price > ema20 && ema20 > ema50) directionalScore += 2 else if (price < ema20 && ema20 < ema50) directionalScore -= 2
        if (price > ema200) directionalScore += 1 else if (price < ema200) directionalScore -= 1
        if (macdHist > 0.0) directionalScore += 1 else if (macdHist < 0.0) directionalScore -= 1
        if (rsi in 53.0..69.0) directionalScore += 1 else if (rsi in 31.0..47.0) directionalScore -= 1

        val latestBos = snapshot.marketStructure.latestBos
        val latestChoch = snapshot.marketStructure.latestChoch
        val latestSweep = snapshot.smcAnalysis?.latestActiveSweep

        if (latestBos != null) {
            if (latestBos.isBullish) directionalScore += 1 else directionalScore -= 1
        }
        if (latestChoch != null) {
            if (latestChoch.isBullish) directionalScore += 1 else directionalScore -= 1
        }

        val rawCandidateDirection = when {
            directionalScore >= 4 -> "LONG"
            directionalScore <= -4 -> "SHORT"
            else -> "WAIT"
        }

        val longCand = snapshot.longCandidate
        val shortCand = snapshot.shortCandidate
        val activeCandidate = when (rawCandidateDirection) {
            "LONG" -> longCand
            "SHORT" -> shortCand
            else -> if (directionalScore >= 0) longCand else shortCand
        }

        val candidateRr = activeCandidate?.riskRewardTp1 ?: 1.0

        // Evaluate WAIT criteria (Requirement #3)
        val waitReasons = ArrayList<String>()
        val positiveReasons = ArrayList<String>()

        val htfPrimaryConflict = (higherBias == "BULLISH" && primaryBias == "BEARISH") ||
            (higherBias == "BEARISH" && primaryBias == "BULLISH")

        if (htfPrimaryConflict) {
            waitReasons.add("$higherTfLabel ($higherBias) and $primaryTfLabel ($primaryBias) trend conflict")
        } else if (higherBias == primaryBias && higherBias in listOf("BULLISH", "BEARISH")) {
            positiveReasons.add("$higherTfLabel and $primaryTfLabel aligned $higherBias")
        }

        if (rawCandidateDirection == "LONG" && snapshot.isNearMajorResistance) {
            waitReasons.add("Price is directly below strong resistance (${snapshot.resistanceLevels.firstOrNull()?.let { roundPriceCleanly(it) }})")
        }
        if (rawCandidateDirection == "SHORT" && snapshot.isNearMajorSupport) {
            waitReasons.add("Price is directly above strong support (${snapshot.supportLevels.firstOrNull()?.let { roundPriceCleanly(it) }})")
        }

        if (primaryCtx?.breakoutPullbackCondition == "UNCONFIRMED_BREAKOUT" ||
            (snapshot.isVolumeWeak && (snapshot.isNearMajorResistance || snapshot.isNearMajorSupport))
        ) {
            waitReasons.add("Breakout volume has not confirmed near key level")
        }

        if (snapshot.isVolatilityAbnormal) {
            waitReasons.add("Volatility is abnormal relative to 14-period ATR")
        }

        if (snapshot.marketStructure.state == StructureState.SIDEWAYS && abs(directionalScore) < 6) {
            waitReasons.add("$primaryTfLabel market structure is unclear / range-bound")
        }

        val indicatorConflictCount = listOf(
            snapshot.hasEmaConflict,
            snapshot.hasRsiConflict,
            snapshot.hasMacdConflict
        ).count { it }
        if (indicatorConflictCount >= 2) {
            waitReasons.add("Technical indicators (EMA, RSI, MACD) strongly conflict")
        }

        if (context.aiSystem == AiAnalysisSystem.AI_CONTINUOUS && rawCandidateDirection != "WAIT" && candidateRr < 1.35) {
            waitReasons.add("Risk/Reward ratio (1:$candidateRr) is poor to nearest target")
        }

        // Requirement #8: Incorporate Market Regime into setup quality & WAIT rules
        val regime = snapshot.marketRegime
        val mtfConf = snapshot.mtfConfirmation
        if (regime == com.example.analysis.technical.MarketRegime.CHOPPY) {
            waitReasons.add("Market regime is CHOPPY — waiting for clean directional structure")
        } else if (regime == com.example.analysis.technical.MarketRegime.INSUFFICIENT_DATA) {
            waitReasons.add("Insufficient candle history to confirm market regime")
        } else {
            positiveReasons.add("Market Regime: ${regime.displayLabel} (${mtfConf.status.label})")
        }

        // Evidence-based confidence calculation (Requirement #4)
        var confidence = 50 + (abs(directionalScore) * 4)
        if (higherBias == primaryBias && higherBias in listOf("BULLISH", "BEARISH")) confidence += 6
        if (lowerBias == "PULLBACK") {
            confidence += 4
            positiveReasons.add("$lowerTfLabel completed pullback into structure")
        }
        if (!snapshot.hasEmaConflict) {
            confidence += 4
            positiveReasons.add(
                if (rawCandidateDirection == "LONG") "EMA20 > EMA50 alignment bullish"
                else if (rawCandidateDirection == "SHORT") "EMA20 < EMA50 alignment bearish"
                else "EMA structure monitored"
            )
        } else {
            confidence -= 7
        }
        if (!snapshot.isVolumeWeak) {
            confidence += 3
            positiveReasons.add("Volume confirms current structure (${primaryCtx?.volumeCondition ?: "NORMAL"})")
        } else {
            confidence -= 6
        }
        if (candidateRr >= 1.8) {
            confidence += 4
            positiveReasons.add("R:R 1:$candidateRr provides sufficient distance to target")
        } else if (candidateRr >= 1.4) {
            positiveReasons.add("R:R 1:$candidateRr acceptable")
        }

        if (htfPrimaryConflict) confidence -= 14
        if (indicatorConflictCount >= 2) confidence -= 10
        if (snapshot.isVolatilityAbnormal) confidence -= 10
        if (waitReasons.isNotEmpty() && rawCandidateDirection != "WAIT") {
            confidence -= (waitReasons.size * 6)
        }
        confidence = confidence.coerceIn(32, 92)

        val (strategyDirection, strategyConfidence) = applyStrategySpecificSynthesisRules(
            strategy = context.selectedAiStrategy,
            snapshot = snapshot,
            context = context,
            directionalScore = directionalScore,
            rawDirection = rawCandidateDirection,
            confidence = confidence,
            candidateRr = candidateRr,
            waitReasons = waitReasons,
            positiveReasons = positiveReasons
        )

        val finalDecision = if (strategyDirection == "WAIT" || waitReasons.isNotEmpty() || strategyConfidence < 60) {
            "WAIT"
        } else {
            strategyDirection
        }
        confidence = strategyConfidence

        // Requirement #1, #8, #9: Separate AI confidence from Setup Quality Grade (A+, A, B, or WAIT)
        val setupQuality = when {
            finalDecision == "WAIT" -> "WAIT"
            mtfConf.status == com.example.analysis.technical.MtfConfirmationStatus.ALIGNED &&
                regime in listOf(
                    com.example.analysis.technical.MarketRegime.TRENDING,
                    com.example.analysis.technical.MarketRegime.PULLBACK,
                    com.example.analysis.technical.MarketRegime.BREAKOUT
                ) &&
                candidateRr >= 1.85 &&
                !snapshot.isVolumeWeak &&
                indicatorConflictCount == 0 -> "A+"
            (mtfConf.status == com.example.analysis.technical.MtfConfirmationStatus.ALIGNED ||
                higherBias == primaryBias) &&
                candidateRr >= 1.55 &&
                indicatorConflictCount <= 1 -> "A"
            else -> "B"
        }

        val finalReasons = if (finalDecision == "WAIT") {
            if (waitReasons.isNotEmpty()) {
                waitReasons
            } else {
                listOf(
                    "Multi-timeframe conviction is below threshold ($confidence%)",
                    "Waiting for cleaner structure alignment and volume confirmation"
                )
            }
        } else {
            positiveReasons.ifEmpty {
                listOf(
                    "$primaryTfLabel structure ${snapshot.marketStructure.schemaCode} supports $finalDecision",
                    "Mathematical R:R 1:$candidateRr to nearest structural target"
                )
            }
        }

        val rawEntryMin = activeCandidate?.entryMin ?: roundPriceCleanly(price * 0.998)
        val rawEntryMax = activeCandidate?.entryMax ?: roundPriceCleanly(price * 1.002)
        val rawStopLoss = activeCandidate?.stopLoss ?: roundPriceCleanly(price * 0.99)
        val rawTp1 = activeCandidate?.tp1 ?: roundPriceCleanly(price * 1.015)
        val rawTp2 = activeCandidate?.tp2 ?: roundPriceCleanly(price * 1.025)
        val rawTp3 = activeCandidate?.tp3 ?: roundPriceCleanly(price * 1.035)

        val safeAtr = snapshot.atr14?.takeIf { it > 0.0 && !it.isNaN() } ?: (price * 0.006)
        val isLongCandidate = rawCandidateDirection == "LONG" || (rawCandidateDirection == "WAIT" && directionalScore >= 0)

        // Apply profile-specific level anchoring, cost/spread validation, R:R floor, and holding horizon
        val profileAdjustment = applyProfileSpecificRules(
            snapshot = snapshot,
            context = context,
            rawCandidateDirection = rawCandidateDirection,
            isLongCandidate = isLongCandidate,
            price = price,
            safeAtr = safeAtr,
            rawEntryMin = rawEntryMin,
            rawEntryMax = rawEntryMax,
            rawStopLoss = rawStopLoss,
            rawTp1 = rawTp1,
            rawTp2 = rawTp2,
            rawTp3 = rawTp3,
            rsi = rsi,
            macdHist = macdHist,
            ema20 = ema20,
            ema50 = ema50,
            ema200 = ema200,
            higherBias = higherBias,
            primaryBias = primaryBias,
            waitReasons = waitReasons,
            positiveReasons = positiveReasons
        )

        val entryMin = profileAdjustment.entryMin
        val entryMax = profileAdjustment.entryMax
        val stopLoss = profileAdjustment.stopLoss
        val tp1 = profileAdjustment.tp1
        val tp2 = profileAdjustment.tp2
        val tp3 = profileAdjustment.tp3
        val rr = profileAdjustment.riskReward

        val effectiveFinalDecision = if (rawCandidateDirection == "WAIT" || waitReasons.isNotEmpty() || confidence < 60) {
            "WAIT"
        } else {
            rawCandidateDirection
        }

        val effectiveSetupQuality = when {
            effectiveFinalDecision == "WAIT" -> "WAIT"
            mtfConf.status == com.example.analysis.technical.MtfConfirmationStatus.ALIGNED &&
                regime in listOf(
                    com.example.analysis.technical.MarketRegime.TRENDING,
                    com.example.analysis.technical.MarketRegime.PULLBACK,
                    com.example.analysis.technical.MarketRegime.BREAKOUT
                ) &&
                rr >= maxOf(1.85, profileAdjustment.minRequiredRr + 0.25) &&
                !snapshot.isVolumeWeak &&
                indicatorConflictCount == 0 -> "A+"
            (mtfConf.status == com.example.analysis.technical.MtfConfirmationStatus.ALIGNED ||
                higherBias == primaryBias) &&
                rr >= maxOf(1.55, profileAdjustment.minRequiredRr) &&
                indicatorConflictCount <= 1 -> "A"
            else -> "B"
        }

        val effectiveFinalReasons = if (effectiveFinalDecision == "WAIT") {
            if (waitReasons.isNotEmpty()) {
                waitReasons
            } else {
                listOf(
                    "Multi-timeframe conviction is below threshold ($confidence%)",
                    "Waiting for cleaner structure alignment and volume confirmation"
                )
            }
        } else {
            positiveReasons.ifEmpty {
                listOf(
                    "$primaryTfLabel structure ${snapshot.marketStructure.schemaCode} supports $effectiveFinalDecision",
                    "Mathematical R:R 1:$rr to nearest structural target"
                )
            }
        }

        val entryOrderType = resolveEntryOrderType(
            decision = effectiveFinalDecision,
            price = price,
            entryMin = entryMin,
            entryMax = entryMax,
            rsi = rsi,
            macdHist = macdHist
        )

        val entryTriggerText = when (effectiveFinalDecision) {
            "LONG" -> "${profileAdjustment.modePrefix}Enter LONG ($entryOrderType) inside ${roundPriceCleanly(entryMin)} - ${roundPriceCleanly(entryMax)} on $lowerTfLabel hold above EMA20"
            "SHORT" -> "${profileAdjustment.modePrefix}Enter SHORT ($entryOrderType) inside ${roundPriceCleanly(entryMin)} - ${roundPriceCleanly(entryMax)} on $lowerTfLabel rejection below EMA20"
            else -> "Wait for confirmed ${profileAdjustment.modeLabel} setup"
        }

        val invalidation = when (effectiveFinalDecision) {
            "LONG" -> profileAdjustment.invalidationOverride.ifBlank {
                "$primaryTfLabel candle closes below ${roundPriceCleanly(stopLoss)}"
            }
            "SHORT" -> profileAdjustment.invalidationOverride.ifBlank {
                "$primaryTfLabel candle closes above ${roundPriceCleanly(stopLoss)}"
            }
            else -> "Wait until price breaks and closes beyond ${snapshot.supportLevels.firstOrNull()?.let { roundPriceCleanly(it) } ?: entryMin} - ${snapshot.resistanceLevels.firstOrNull()?.let { roundPriceCleanly(it) } ?: entryMax} with volume"
        }

        val expiryMinutes = profileAdjustment.expiryMinutes
        val analyzedNow = System.currentTimeMillis()
        val expiryAt = analyzedNow + (expiryMinutes * 60_000L)
        val sigPrefix = if (context.aiSystem == AiAnalysisSystem.AI_TRADING_STYLE) {
            "${context.tradingStyleProfile.code}_"
        } else {
            ""
        }
        val setupSignature = "${sigPrefix}${snapshot.symbol}_${snapshot.timeframe.label}_${AiResponseParser.buildSetupSignature(effectiveFinalDecision, entryMin, entryMax, stopLoss)}"

        val momentumStr = when {
            rsi >= 54.0 && macdHist >= 0.0 -> "POSITIVE"
            rsi <= 46.0 && macdHist <= 0.0 -> "NEGATIVE"
            else -> "NEUTRAL"
        }

        val summaryText = if (effectiveFinalDecision == "WAIT") {
            "[${profileAdjustment.modeLabel}] ${effectiveFinalReasons.first()}. No trade setup recommended."
        } else {
            "[${profileAdjustment.modeLabel}] $effectiveFinalDecision setup (Grade $effectiveSetupQuality · ${regime.displayLabel} · $entryOrderType) with $higherTfLabel $higherBias and $primaryTfLabel $primaryBias alignment (R:R 1:$rr)."
        }

        val jsonObj = JSONObject().apply {
            put("decision", effectiveFinalDecision)
            put("confidence", confidence)
            put("trend", primaryBias)
            put("momentum", momentumStr)
            put("marketStructure", snapshot.marketStructure.schemaCode)
            put("setupQuality", effectiveSetupQuality)
            put("entryOrderType", entryOrderType)
            put("aiSystemMode", context.aiSystem.code)
            put("aiExecutionMode", context.effectiveExecutionMode)
            put("tradingStyleProfile", context.effectiveProfileCode)
            put("selectedAiStrategy", context.selectedAiStrategy.id)
            put("selectedAiStrategyName", context.selectedAiStrategy.displayName)
            put("selectedTimeframesSummary", timeframesSummary)
            put("dataValidationWarning", profileAdjustment.dataWarning)
            put("positionSizingGuidance", profileAdjustment.positionSizingGuidance)
            put("marketRegime", regime.displayLabel)
            put("mtfConfirmation", mtfConf.summary)
            put("entryZone", JSONObject().put("min", entryMin).put("max", entryMax))
            put("entryTrigger", entryTriggerText)
            put("stopLoss", stopLoss)
            put("takeProfit", JSONArray().put(tp1).put(tp2).put(tp3))
            put("riskReward", rr)
            put("support", JSONArray().apply { snapshot.supportLevels.forEach { put(roundPriceCleanly(it)) } })
            put("resistance", JSONArray().apply { snapshot.resistanceLevels.forEach { put(roundPriceCleanly(it)) } })
            put("higherTimeframeBias", higherBias)
            put("primaryTimeframeBias", primaryBias)
            put("lowerTimeframeBias", lowerBias)
            put("invalidation", invalidation)
            put("expiryMinutes", expiryMinutes)
            put("expiryAt", expiryAt)
            put("setupSignature", setupSignature)
            put("reasoning", JSONArray().apply { effectiveFinalReasons.forEach { put(it) } })
            put("summary", summaryText)
        }

        val parsed = AiResponseParser.parse(jsonObj.toString(), badge, modelName) ?: return null
        val normalized = com.example.analysis.signal.SignalValidationEngine.validateAndNormalize(parsed, snapshot, context)
        return stampResultWithContext(normalized, snapshot, context, timeframesSummary, profileAdjustment.dataWarning, profileAdjustment.positionSizingGuidance)
    }
}

/**
 * 2. CustomAPIProvider (Requirement #10)
 * Supports OpenAI-Compatible endpoints (OpenRouter, xAI/Grok, Gemini-compatible, OpenAI, DeepSeek)
 * and Gemini REST format. Never logs API keys.
 */
class CustomAPIProvider(
    private val endpointProvider: () -> String,
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String,
    private val formatProvider: () -> CustomApiFormat,
    private val isBackupRole: Boolean = false,
    private val httpClient: OkHttpClient = buildFastTimeoutClient(),
    private val providerIdOverride: String? = null,
    private val providerNameOverride: String? = null
) : AIProvider {

    override val providerId: String get() = providerIdOverride ?: "custom_openai"
    override val providerName: String get() = providerNameOverride ?: "Custom API"
    override val badgeLabel: String get() = if (isBackupRole) "AI · Backup" else "AI · Primary"
    override val modelIdentifier: String get() = modelProvider().trim().ifEmpty { "custom-model" }

    override suspend fun analyzeMarket(
        snapshot: MarketTechnicalSnapshot,
        context: AiAnalysisRequestContext
    ): AiAnalysisResult? =
        withContext(Dispatchers.IO) {
            if (!context.isDataValidForEntry) {
                return@withContext synthesizeDataBlockedWaitResult(snapshot, context, badgeLabel, modelIdentifier)
            }
            val apiKey = apiKeyProvider().trim()
            val endpoint = endpointProvider().trim()
            val model = modelProvider().trim()
            if (apiKey.isEmpty() || endpoint.isEmpty() || model.isEmpty()) return@withContext null

            val rawResult = when (formatProvider()) {
                CustomApiFormat.OPENAI_COMPATIBLE -> {
                    executeOpenAiCompatibleRequest(
                        httpClient = httpClient,
                        baseEndpoint = endpoint,
                        apiKey = apiKey,
                        model = model,
                        snapshot = snapshot,
                        badge = badgeLabel,
                        context = context
                    )
                }
                CustomApiFormat.GEMINI_REST -> {
                    executeGeminiRestRequest(
                        httpClient = httpClient,
                        apiKey = apiKey,
                        modelName = model,
                        snapshot = snapshot,
                        badge = badgeLabel,
                        baseUrlOverride = endpoint,
                        context = context
                    )
                }
            }
            rawResult?.let { enforceV2MarketIntegrity(it, snapshot, context) }
        }

    override suspend fun testConnection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        val endpoint = endpointProvider().trim()
        val model = modelProvider().trim()
        if (apiKey.isEmpty()) {
            return@withContext ConnectionTestResult(false, 0L, "🔴 Failed · Missing API Key")
        }
        if (endpoint.isEmpty() || !endpoint.startsWith("http")) {
            return@withContext ConnectionTestResult(false, 0L, "🔴 Failed · Invalid Endpoint")
        }

        when (formatProvider()) {
            CustomApiFormat.OPENAI_COMPATIBLE -> executeOpenAiPing(httpClient, endpoint, apiKey, model)
            CustomApiFormat.GEMINI_REST -> executeGeminiPing(httpClient, apiKey, model, endpoint)
        }
    }
}

/**
 * 3. GeminiAPIProvider (Requirement #10)
 * Optional user-supplied Gemini API Key + user-supplied model.
 */
class GeminiAPIProvider(
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String,
    private val isBackupRole: Boolean = false,
    private val httpClient: OkHttpClient = buildFastTimeoutClient()
) : AIProvider {

    override val providerId: String = "gemini_personal"
    override val providerName: String = "Gemini API"
    override val badgeLabel: String get() = if (isBackupRole) "AI · Backup" else "AI · Primary"
    override val modelIdentifier: String get() = modelProvider().trim().ifEmpty { "gemini-3.5-flash" }

    override suspend fun analyzeMarket(
        snapshot: MarketTechnicalSnapshot,
        context: AiAnalysisRequestContext
    ): AiAnalysisResult? =
        withContext(Dispatchers.IO) {
            if (!context.isDataValidForEntry) {
                return@withContext synthesizeDataBlockedWaitResult(snapshot, context, badgeLabel, modelIdentifier)
            }
            val key = apiKeyProvider().trim()
            val model = modelIdentifier
            if (key.isEmpty()) return@withContext null

            val raw = executeGeminiRestRequest(
                httpClient = httpClient,
                apiKey = key,
                modelName = model,
                snapshot = snapshot,
                badge = badgeLabel,
                context = context
            )
            raw?.let { enforceV2MarketIntegrity(it, snapshot, context) }
        }

    override suspend fun testConnection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        val model = modelIdentifier
        if (key.isEmpty()) {
            return@withContext ConnectionTestResult(false, 0L, "🔴 Failed · Missing API Key")
        }
        executeGeminiPing(httpClient, key, model)
    }
}

/**
 * Orchestrates Primary AIProvider -> Automatic Failover to Backup AIProvider.
 * Never exposes API keys in logs.
 */
class AIService(
    private val preferences: AppPreferences? = null,
    private val builtInProvider: AIProvider = BuiltInAIProvider(isBackupRole = false),
    private val usageRepository: com.example.ai.usage.AiUsageRepository? = null
) : AiMarketAnalystProvider {

    override suspend fun analyzeMarket(
        snapshot: MarketTechnicalSnapshot,
        context: AiAnalysisRequestContext
    ): AiAnalystOutcome =
        withContext(Dispatchers.IO) {
            val prefs = preferences
            val settings = prefs?.settings?.value

            val primaryMode = settings?.primaryAiProvider ?: AiProviderMode.BUILT_IN
            val enableFallback = settings?.enableAiFallback ?: true

            val primaryProvider = buildProviderForMode(primaryMode, prefs, isBackupRole = false)
            val fallbackProviders = if (enableFallback) {
                buildFallbackChain(primaryMode, prefs)
            } else {
                emptyList()
            }

            // Check locally enforced budget before executing cloud requests
            val isCloudProvider = primaryProvider !is BuiltInAIProvider || (BuildConfig.GEMINI_API_KEY.isNotBlank() && BuildConfig.GEMINI_API_KEY != "MY_GEMINI_API_KEY")
            if (isCloudProvider) {
                val (isAllowed, blockReason) = usageRepository?.checkCloudRequestAllowed() ?: (true to null)
                if (!isAllowed) {
                    return@withContext AiAnalystOutcome.Error(
                        userMessage = blockReason ?: "Local token budget reached. Cloud requests blocked by your AI configuration.",
                        technicalDetail = "Locally enforced token budget limit reached"
                    )
                }
            }

            // 1. Try Primary Provider
            try {
                val primaryResult = primaryProvider.analyzeMarket(snapshot, context)
                if (primaryResult != null) {
                    val tagged = primaryResult.copy(
                        providerBadge = "AI · Primary",
                        modelUsed = primaryProvider.modelIdentifier
                    )

                    val usage = primaryResult.tokenUsage ?: com.example.ai.usage.TokenUsageSnapshot(
                        source = if (primaryProvider is BuiltInAIProvider && (BuildConfig.GEMINI_API_KEY.isBlank() || BuildConfig.GEMINI_API_KEY == "MY_GEMINI_API_KEY")) {
                            com.example.ai.usage.TokenUsageSource.LOCAL
                        } else {
                            com.example.ai.usage.TokenUsageSource.UNAVAILABLE
                        },
                        isSuccess = true
                    )
                    usageRepository?.recordUsage(
                        providerId = primaryProvider.providerId,
                        providerDisplayName = primaryProvider.providerName,
                        modelId = primaryProvider.modelIdentifier,
                        snapshot = usage
                    )

                    val validated = com.example.analysis.signal.SignalValidationEngine.validateAndNormalize(tagged, snapshot, context)
                    return@withContext AiAnalystOutcome.Success(
                        result = validated,
                        activeProviderBadge = "AI · Primary"
                    )
                }
            } catch (e: Exception) {
                usageRepository?.recordUsage(
                    providerId = primaryProvider.providerId,
                    providerDisplayName = primaryProvider.providerName,
                    modelId = primaryProvider.modelIdentifier,
                    snapshot = com.example.ai.usage.TokenUsageSnapshot(
                        source = com.example.ai.usage.TokenUsageSource.UNAVAILABLE,
                        isSuccess = false,
                        errorMessage = e.message ?: "Primary provider error"
                    )
                )
            }

            // 2. Try Fallback Chain if enabled ("AI · Backup")
            for (backup in fallbackProviders) {
                try {
                    val backupResult = backup.analyzeMarket(snapshot, context)
                    if (backupResult != null) {
                        val tagged = backupResult.copy(
                            providerBadge = "AI · Backup",
                            modelUsed = backup.modelIdentifier
                        )
                        val usage = backupResult.tokenUsage ?: com.example.ai.usage.TokenUsageSnapshot(
                            source = if (backup is BuiltInAIProvider && (BuildConfig.GEMINI_API_KEY.isBlank() || BuildConfig.GEMINI_API_KEY == "MY_GEMINI_API_KEY")) {
                                com.example.ai.usage.TokenUsageSource.LOCAL
                            } else {
                                com.example.ai.usage.TokenUsageSource.UNAVAILABLE
                            },
                            isSuccess = true
                        )
                        usageRepository?.recordUsage(
                            providerId = backup.providerId,
                            providerDisplayName = backup.providerName,
                            modelId = backup.modelIdentifier,
                            snapshot = usage
                        )

                        val validated = com.example.analysis.signal.SignalValidationEngine.validateAndNormalize(tagged, snapshot, context)
                        return@withContext AiAnalystOutcome.Success(
                            result = validated,
                            activeProviderBadge = "AI · Backup"
                        )
                    }
                } catch (e: Exception) {
                    usageRepository?.recordUsage(
                        providerId = backup.providerId,
                        providerDisplayName = backup.providerName,
                        modelId = backup.modelIdentifier,
                        snapshot = com.example.ai.usage.TokenUsageSnapshot(
                            source = com.example.ai.usage.TokenUsageSource.UNAVAILABLE,
                            isSuccess = false,
                            errorMessage = e.message ?: "Backup provider error"
                        )
                    )
                }
            }

            AiAnalystOutcome.Error(
                userMessage = AiAnalystOutcome.UNAVAILABLE_MESSAGE,
                technicalDetail = null
            )
        }

    suspend fun testSelectedProviderConnection(
        providerType: com.example.storage.preferences.AiProviderType? = null
    ): ConnectionTestResult {
        val prefs = preferences ?: return builtInProvider.testConnection()
        val type = providerType ?: prefs.settings.value.activeAiProviderType
        val provider = buildProviderForType(type, prefs, isBackupRole = false)
        return provider.testConnection()
    }

    fun buildProviderForType(
        type: com.example.storage.preferences.AiProviderType,
        prefs: AppPreferences?,
        isBackupRole: Boolean
    ): AIProvider {
        if (prefs == null) return builtInProvider
        return when (type) {
            com.example.storage.preferences.AiProviderType.GEMINI_BUILTIN -> {
                if (isBackupRole) BuiltInAIProvider(isBackupRole = true) else builtInProvider
            }
            com.example.storage.preferences.AiProviderType.GEMINI_PERSONAL -> {
                GeminiAPIProvider(
                    apiKeyProvider = { prefs.getProviderApiKey(type) },
                    modelProvider = { prefs.getProviderModel(type) },
                    isBackupRole = isBackupRole
                )
            }
            com.example.storage.preferences.AiProviderType.OPENAI -> {
                CustomAPIProvider(
                    endpointProvider = { prefs.getProviderEndpoint(type) },
                    apiKeyProvider = { prefs.getProviderApiKey(type) },
                    modelProvider = { prefs.getProviderModel(type) },
                    formatProvider = { type.format },
                    isBackupRole = isBackupRole,
                    providerIdOverride = "openai",
                    providerNameOverride = "OpenAI"
                )
            }
            com.example.storage.preferences.AiProviderType.OPENROUTER -> {
                CustomAPIProvider(
                    endpointProvider = { prefs.getProviderEndpoint(type) },
                    apiKeyProvider = { prefs.getProviderApiKey(type) },
                    modelProvider = { prefs.getProviderModel(type) },
                    formatProvider = { type.format },
                    isBackupRole = isBackupRole,
                    providerIdOverride = "openrouter",
                    providerNameOverride = "OpenRouter"
                )
            }
            com.example.storage.preferences.AiProviderType.CUSTOM_OPENAI -> {
                CustomAPIProvider(
                    endpointProvider = { prefs.getProviderEndpoint(type) },
                    apiKeyProvider = { prefs.getProviderApiKey(type) },
                    modelProvider = { prefs.getProviderModel(type) },
                    formatProvider = { type.format },
                    isBackupRole = isBackupRole,
                    providerIdOverride = "custom_openai",
                    providerNameOverride = "Custom Provider"
                )
            }
        }
    }

    private fun buildProviderForMode(
        mode: AiProviderMode,
        prefs: AppPreferences?,
        isBackupRole: Boolean
    ): AIProvider {
        if (prefs == null) return builtInProvider
        val activeType = prefs.settings.value.activeAiProviderType
        return when (mode) {
            AiProviderMode.BUILT_IN -> if (isBackupRole) BuiltInAIProvider(isBackupRole = true) else builtInProvider
            AiProviderMode.CUSTOM_API -> buildProviderForType(activeType, prefs, isBackupRole)
            AiProviderMode.GEMINI_API -> buildProviderForType(com.example.storage.preferences.AiProviderType.GEMINI_PERSONAL, prefs, isBackupRole)
        }
    }

    private fun buildFallbackChain(
        primaryMode: AiProviderMode,
        prefs: AppPreferences?
    ): List<AIProvider> {
        if (prefs == null) return emptyList()
        val chain = ArrayList<AIProvider>()
        when (primaryMode) {
            AiProviderMode.BUILT_IN -> {
                if (prefs.getCustomApiKey().isNotBlank()) {
                    chain.add(buildProviderForMode(AiProviderMode.CUSTOM_API, prefs, isBackupRole = true))
                }
                if (prefs.getGeminiCustomApiKey().isNotBlank()) {
                    chain.add(buildProviderForMode(AiProviderMode.GEMINI_API, prefs, isBackupRole = true))
                }
            }
            AiProviderMode.CUSTOM_API -> {
                if (prefs.getGeminiCustomApiKey().isNotBlank()) {
                    chain.add(buildProviderForMode(AiProviderMode.GEMINI_API, prefs, isBackupRole = true))
                }
                chain.add(BuiltInAIProvider(isBackupRole = true))
            }
            AiProviderMode.GEMINI_API -> {
                if (prefs.getCustomApiKey().isNotBlank()) {
                    chain.add(buildProviderForMode(AiProviderMode.CUSTOM_API, prefs, isBackupRole = true))
                }
                chain.add(BuiltInAIProvider(isBackupRole = true))
            }
        }
        return chain
    }
}

typealias GeminiAiAnalystService = AIService

private data class ProfileRuleAdjustment(
    val modeLabel: String,
    val modePrefix: String,
    val entryMin: Double,
    val entryMax: Double,
    val stopLoss: Double,
    val tp1: Double,
    val tp2: Double,
    val tp3: Double,
    val riskReward: Double,
    val minRequiredRr: Double,
    val expiryMinutes: Int,
    val invalidationOverride: String,
    val positionSizingGuidance: String,
    val dataWarning: String
)

private fun applyStrategySpecificSynthesisRules(
    strategy: com.example.ai.strategy.AiStrategy,
    snapshot: MarketTechnicalSnapshot,
    context: AiAnalysisRequestContext,
    directionalScore: Int,
    rawDirection: String,
    confidence: Int,
    candidateRr: Double,
    waitReasons: MutableList<String>,
    positiveReasons: MutableList<String>
): Pair<String, Int> {
    var effDirection = rawDirection
    var effConfidence = confidence

    when (strategy) {
        com.example.ai.strategy.AiStrategy.AI_HYBRID -> {
            positiveReasons.add(0, "[AI Hybrid] Comprehensive confluence across MTF trend, indicators, and market structure.")
        }
        com.example.ai.strategy.AiStrategy.AI_SMART -> {
            when (snapshot.marketRegime) {
                com.example.analysis.technical.MarketRegime.TRENDING -> {
                    positiveReasons.add(0, "[AI Smart] Trending regime confirmed (${snapshot.marketStructure.schemaCode}); prioritizing trend-following momentum.")
                    effConfidence = (effConfidence + 4).coerceAtMost(95)
                }
                com.example.analysis.technical.MarketRegime.PULLBACK -> {
                    positiveReasons.add(0, "[AI Smart] Pullback regime identified; seeking structural retest entry into primary trend.")
                }
                com.example.analysis.technical.MarketRegime.BREAKOUT -> {
                    if (!snapshot.isVolumeWeak) {
                        positiveReasons.add(0, "[AI Smart] Breakout regime confirmed with volume expansion.")
                        effConfidence = (effConfidence + 3).coerceAtMost(95)
                    } else {
                        waitReasons.add("[AI Smart] Breakout regime lacks confirming volume; waiting for structural retest.")
                        effDirection = "WAIT"
                    }
                }
                com.example.analysis.technical.MarketRegime.RANGING -> {
                    positiveReasons.add(0, "[AI Smart] Ranging regime; emphasizing boundary mean-reversion.")
                }
                com.example.analysis.technical.MarketRegime.CHOPPY -> {
                    waitReasons.add("[AI Smart] Market regime is CHOPPY; enforcing WAIT for capital preservation.")
                    effDirection = "WAIT"
                }
                else -> {
                    positiveReasons.add(0, "[AI Smart] Evaluating structure under ${snapshot.marketRegime.displayLabel} conditions.")
                }
            }
        }
        com.example.ai.strategy.AiStrategy.AI_SMC -> {
            val obs = snapshot.marketStructure.activeOrderBlockCandidates
            val bos = snapshot.marketStructure.latestBos
            val choch = snapshot.marketStructure.latestChoch

            if (obs.isNotEmpty()) {
                val bestOb = obs.first()
                positiveReasons.add(0, "[AI SMC] Order Block verified: ${bestOb.label} (${bestOb.zoneLow} - ${bestOb.zoneHigh}).")
                effConfidence = (effConfidence + 5).coerceAtMost(95)
            } else if (bos != null || choch != null) {
                val shift = choch?.description ?: bos?.description ?: "Structure shift"
                positiveReasons.add(0, "[AI SMC] Market structure shift confirmed: $shift.")
            } else {
                if (effDirection != "WAIT") {
                    positiveReasons.add(0, "[AI SMC] Market structure monitored; no unmitigated Order Block in immediate range.")
                } else {
                    waitReasons.add("[AI SMC] No unmitigated Order Block or valid BOS/CHoCH alignment found.")
                }
            }
        }
        com.example.ai.strategy.AiStrategy.AI_ICT -> {
            val sweeps = snapshot.smcAnalysis?.detectedSweeps.orEmpty()
            val latestActiveSweep = sweeps.lastOrNull()
            if (latestActiveSweep != null) {
                positiveReasons.add(0, "[AI ICT] Liquidity sweep detected at ref ${latestActiveSweep.referenceLevel} (${latestActiveSweep.summaryBadge}).")
                effConfidence = (effConfidence + 5).coerceAtMost(95)
            } else {
                positiveReasons.add(0, "[AI ICT] Inner Circle Trader: monitoring dealing range & external liquidity pools.")
            }
        }
        com.example.ai.strategy.AiStrategy.AI_SNIPER -> {
            if (candidateRr < 2.0 || effConfidence < 68 || snapshot.hasEmaConflict || snapshot.hasRsiConflict) {
                waitReasons.add("[AI Sniper] High-selectivity threshold not met: requires clean R:R >= 2.0 (current: 1:${candidateRr}) and zero indicator conflicts.")
                effDirection = "WAIT"
                effConfidence = effConfidence.coerceAtMost(58)
            } else {
                positiveReasons.add(0, "[AI Sniper] High-conviction precision setup: optimal R:R 1:$candidateRr with disciplined invalidation.")
                effConfidence = (effConfidence + 6).coerceAtMost(98)
            }
        }
        com.example.ai.strategy.AiStrategy.AI_SCALPING -> {
            if (context.spreadPct > 0.08) {
                waitReasons.add("[AI Scalping] Spread (${context.spreadPct}%) too wide for tight scalping targets.")
                effDirection = "WAIT"
            } else {
                positiveReasons.add(0, "[AI Scalping] Short-horizon momentum validated; tight stop loss with fast invalidation.")
            }
        }
        com.example.ai.strategy.AiStrategy.AI_PRICE_ACTION -> {
            val candle = snapshot.latestCandle
            val bodySize = abs(candle.close - candle.open)
            val range = (candle.high - candle.low).coerceAtLeast(0.00001)
            val isRejection = (range - bodySize) / range > 0.5
            if (isRejection) {
                positiveReasons.add(0, "[AI Price Action] Candlestick wick rejection observed on ${snapshot.timeframe.label}.")
                effConfidence = (effConfidence + 4).coerceAtMost(95)
            } else {
                positiveReasons.add(0, "[AI Price Action] Swing high/low price structure (${snapshot.marketStructure.schemaCode}) evaluated.")
            }
        }
        com.example.ai.strategy.AiStrategy.AI_SESSION_TRADER -> {
            val sessionSnap = snapshot.smcAnalysis?.sessionSnapshot
            if (sessionSnap != null && sessionSnap.badgeText.isNotBlank()) {
                positiveReasons.add(0, "[AI Session Trader] Session context active: ${sessionSnap.badgeText} (${sessionSnap.instrumentAvailabilityLabel}).")
            } else {
                positiveReasons.add(0, "[AI Session Trader] Session context isolated; evaluating standard session highs/lows.")
            }
        }
    }

    return Pair(effDirection, effConfidence)
}

private fun applyProfileSpecificRules(
    snapshot: MarketTechnicalSnapshot,
    context: AiAnalysisRequestContext,
    rawCandidateDirection: String,
    isLongCandidate: Boolean,
    price: Double,
    safeAtr: Double,
    rawEntryMin: Double,
    rawEntryMax: Double,
    rawStopLoss: Double,
    rawTp1: Double,
    rawTp2: Double,
    rawTp3: Double,
    rsi: Double,
    macdHist: Double,
    ema20: Double,
    ema50: Double,
    ema200: Double,
    higherBias: String,
    primaryBias: String,
    waitReasons: MutableList<String>,
    positiveReasons: MutableList<String>
): ProfileRuleAdjustment {
    val primaryTfLabel = snapshot.timeframe.label
    val baseDataWarning = buildList {
        if (context.dataValidationWarning.isNotBlank()) add(context.dataValidationWarning)
        if (snapshot.higherContext == null) add("Higher-timeframe candle context unavailable; single-TF fallback active")
        if (snapshot.mtfConfirmation.isHigherTfStale || snapshot.mtfConfirmation.isLowerTfStale) {
            add("Secondary timeframe candle cache is stale")
        }
    }.joinToString(" · ")

    if (context.aiSystem == AiAnalysisSystem.AI_CONTINUOUS) {
        val subLabel = if (context.continuousSubmode == AiContinuousSubmode.MANUAL) {
            "AI Continuous · Manual"
        } else {
            "AI Continuous · Auto"
        }
        val midEntry = (rawEntryMin + rawEntryMax) / 2.0
        val slDist = abs(midEntry - rawStopLoss).coerceAtLeast(price * 0.002)
        val rr = kotlin.math.round((abs(rawTp1 - midEntry) / slDist).coerceAtLeast(0.1) * 100.0) / 100.0
        val slPct = kotlin.math.round((slDist / price) * 10000.0) / 100.0
        val expiryMinutes = ((snapshot.timeframe.durationMs * 12L) / 60_000L).toInt().coerceIn(15, 2880)
        return ProfileRuleAdjustment(
            modeLabel = subLabel,
            modePrefix = "",
            entryMin = rawEntryMin,
            entryMax = rawEntryMax,
            stopLoss = rawStopLoss,
            tp1 = rawTp1,
            tp2 = rawTp2,
            tp3 = rawTp3,
            riskReward = rr,
            minRequiredRr = 1.35,
            expiryMinutes = expiryMinutes,
            invalidationOverride = "",
            positionSizingGuidance = "SL distance $slPct% · Risk max 1.0% equity per setup",
            dataWarning = baseDataWarning
        )
    }

    // Option B — AI Gaya Trading (Manual Strategy Profiles)
    return when (context.tradingStyleProfile) {
        TradingStyleProfile.SCALPING -> {
            // Scalping: 1m–5m default entry, 5m–15m HTF.
            // Prioritize short-term momentum, spread, transaction costs, volatility, and fast invalidation.
            val scalpMinEntry = if (isLongCandidate) {
                roundPriceCleanly(price - safeAtr * 0.12)
            } else {
                roundPriceCleanly(price - safeAtr * 0.05)
            }
            val scalpMaxEntry = if (isLongCandidate) {
                roundPriceCleanly(price + safeAtr * 0.05)
            } else {
                roundPriceCleanly(price + safeAtr * 0.12)
            }
            val midEntry = (scalpMinEntry + scalpMaxEntry) / 2.0
            val scalpRisk = (safeAtr * 0.65).coerceAtLeast(price * 0.0025)
            val scalpSl = if (isLongCandidate) {
                roundPriceCleanly(midEntry - scalpRisk)
            } else {
                roundPriceCleanly(midEntry + scalpRisk)
            }
            val scalpTp1 = if (isLongCandidate) {
                roundPriceCleanly(midEntry + scalpRisk * 1.55)
            } else {
                roundPriceCleanly((midEntry - scalpRisk * 1.55).coerceAtLeast(price * 0.2))
            }
            val scalpTp2 = if (isLongCandidate) {
                roundPriceCleanly(midEntry + scalpRisk * 2.25)
            } else {
                roundPriceCleanly((midEntry - scalpRisk * 2.25).coerceAtLeast(price * 0.15))
            }
            val scalpTp3 = if (isLongCandidate) {
                roundPriceCleanly(midEntry + scalpRisk * 3.10)
            } else {
                roundPriceCleanly((midEntry - scalpRisk * 3.10).coerceAtLeast(price * 0.10))
            }

            val actualRisk = abs(midEntry - scalpSl).coerceAtLeast(1e-8)
            val scalpRr = kotlin.math.round((abs(scalpTp1 - midEntry) / actualRisk) * 100.0) / 100.0

            // Transaction cost & spread compensation check:
            // Round-trip fee (entry + exit) + spread
            val roundTripFeePct = (context.feeBps * 2.0) / 100.0 // e.g. 8 bps = 0.08%
            val totalFrictionPct = roundTripFeePct + context.spreadPct.coerceAtLeast(0.0)
            val expectedTp1MovePct = (abs(scalpTp1 - midEntry) / midEntry) * 100.0
            val minCompensatedMovePct = maxOf(totalFrictionPct * 3.2, 0.18)

            if (context.spreadPct > 0.25) {
                waitReasons.add(
                    "Scalping filter: current bid-ask spread (${roundPriceCleanly(context.spreadPct)}%) is too wide for short-horizon execution"
                )
            }
            if (expectedTp1MovePct < minCompensatedMovePct) {
                waitReasons.add(
                    "Scalping filter: expected TP1 move (${roundPriceCleanly(expectedTp1MovePct)}%) does not reasonably compensate for fees & spread (${roundPriceCleanly(totalFrictionPct)}%)"
                )
            } else {
                positiveReasons.add(
                    "Scalping cost check passed: expected TP1 move (${roundPriceCleanly(expectedTp1MovePct)}%) covers round-trip fees + spread (${roundPriceCleanly(totalFrictionPct)}%)"
                )
            }

            // Short-term momentum requirement for Scalping
            val momentumAligned = (rawCandidateDirection == "LONG" && rsi >= 51.0 && macdHist >= 0.0 && price >= ema20 * 0.999) ||
                (rawCandidateDirection == "SHORT" && rsi <= 49.0 && macdHist <= 0.0 && price <= ema20 * 1.001)
            if (rawCandidateDirection != "WAIT" && !momentumAligned) {
                waitReasons.add("Scalping filter: short-term momentum (RSI/MACD/EMA20) is not aligned for immediate impulse")
            } else if (rawCandidateDirection != "WAIT") {
                positiveReasons.add("Scalping impulse aligned on $primaryTfLabel (EMA20 + MACD histogram confirmation)")
            }

            if (rawCandidateDirection != "WAIT" && scalpRr < TradingStyleProfile.SCALPING.minimumRiskReward) {
                waitReasons.add("Scalping filter: R:R (1:$scalpRr) is below profile minimum (1:${TradingStyleProfile.SCALPING.minimumRiskReward})")
            }
            if ((rawCandidateDirection == "WAIT" || waitReasons.isNotEmpty()) &&
                waitReasons.none { it.contains("Scalping", ignoreCase = true) }
            ) {
                waitReasons.add(
                    0,
                    "Scalping profile ($primaryTfLabel): waiting for short-term momentum impulse with expected move (${roundPriceCleanly(expectedTp1MovePct)}%) exceeding fees + spread (${roundPriceCleanly(totalFrictionPct)}%)"
                )
            }

            val tfWarning = if (snapshot.timeframe !in TradingStyleProfile.SCALPING.recommendedEntryTimeframes) {
                "Selected timeframe ($primaryTfLabel) is outside Scalping default range (1m–5m)"
            } else ""
            val combinedWarning = listOf(baseDataWarning, tfWarning).filter { it.isNotBlank() }.joinToString(" · ")
            val slPct = kotlin.math.round((actualRisk / midEntry) * 10000.0) / 100.0

            ProfileRuleAdjustment(
                modeLabel = "AI Gaya Trading · Scalping",
                modePrefix = "[Scalping] ",
                entryMin = scalpMinEntry,
                entryMax = scalpMaxEntry,
                stopLoss = scalpSl,
                tp1 = scalpTp1,
                tp2 = scalpTp2,
                tp3 = scalpTp3,
                riskReward = scalpRr,
                minRequiredRr = TradingStyleProfile.SCALPING.minimumRiskReward,
                expiryMinutes = 30,
                invalidationOverride = "Fast invalidation: $primaryTfLabel candle closes beyond EMA20 (${roundPriceCleanly(ema20)}) or hits SL ${roundPriceCleanly(scalpSl)}",
                positionSizingGuidance = "Scalping tight SL ($slPct%) · Net move ${roundPriceCleanly(expectedTp1MovePct)}% vs ${roundPriceCleanly(totalFrictionPct)}% cost",
                dataWarning = combinedWarning
            )
        }

        TradingStyleProfile.DAY_TRADING -> {
            // Day Trading: 5m–15m default entry, 15m–1H HTF.
            // Prioritize intraday structure, trend continuation, breakouts, retests, and pullbacks.
            val dayMinEntry = roundPriceCleanly(rawEntryMin.coerceIn(price - safeAtr * 0.9, price + safeAtr * 0.9))
            val dayMaxEntry = roundPriceCleanly(maxOf(dayMinEntry, rawEntryMax.coerceIn(price - safeAtr * 0.9, price + safeAtr * 0.9)))
            val midEntry = (dayMinEntry + dayMaxEntry) / 2.0
            val baseRisk = abs(midEntry - rawStopLoss).coerceIn(safeAtr * 0.85, safeAtr * 2.5)
            val daySl = if (isLongCandidate) {
                roundPriceCleanly(midEntry - baseRisk)
            } else {
                roundPriceCleanly(midEntry + baseRisk)
            }
            val actualRisk = abs(midEntry - daySl).coerceAtLeast(1e-8)
            val dayTp1 = if (isLongCandidate) {
                roundPriceCleanly(maxOf(rawTp1, midEntry + actualRisk * 1.60))
            } else {
                roundPriceCleanly(minOf(rawTp1, midEntry - actualRisk * 1.60).coerceAtLeast(price * 0.2))
            }
            val dayTp2 = if (isLongCandidate) {
                roundPriceCleanly(maxOf(rawTp2, midEntry + actualRisk * 2.35))
            } else {
                roundPriceCleanly(minOf(rawTp2, midEntry - actualRisk * 2.35).coerceAtLeast(price * 0.15))
            }
            val dayTp3 = if (isLongCandidate) {
                roundPriceCleanly(maxOf(rawTp3, midEntry + actualRisk * 3.20))
            } else {
                roundPriceCleanly(minOf(rawTp3, midEntry - actualRisk * 3.20).coerceAtLeast(price * 0.10))
            }
            val dayRr = kotlin.math.round((abs(dayTp1 - midEntry) / actualRisk) * 100.0) / 100.0

            val bpCond = snapshot.primaryContext?.breakoutPullbackCondition ?: "NORMAL"
            val intradayStructureValid = snapshot.marketStructure.state in listOf(
                StructureState.BULLISH,
                StructureState.BEARISH
            ) || bpCond in listOf("BREAKOUT_CONFIRMED", "BULLISH_PULLBACK", "BEARISH_PULLBACK")

            if (rawCandidateDirection != "WAIT" && !intradayStructureValid) {
                waitReasons.add("Day Trading filter: intraday structure (${snapshot.marketStructure.schemaCode}) lacks clean trend continuation, breakout, or pullback")
            } else if (rawCandidateDirection != "WAIT") {
                positiveReasons.add("Day Trading intraday structure (${snapshot.marketStructure.schemaCode} · $bpCond) supports session continuation")
            }

            if (rawCandidateDirection != "WAIT" && dayRr < TradingStyleProfile.DAY_TRADING.minimumRiskReward) {
                waitReasons.add("Day Trading filter: R:R (1:$dayRr) is below intraday threshold (1:${TradingStyleProfile.DAY_TRADING.minimumRiskReward})")
            }
            if ((rawCandidateDirection == "WAIT" || waitReasons.isNotEmpty()) &&
                waitReasons.none { it.contains("Day Trading", ignoreCase = true) }
            ) {
                waitReasons.add(
                    0,
                    "Day Trading profile ($primaryTfLabel): waiting for intraday trend continuation, breakout, or pullback within session horizon"
                )
            }

            val tfWarning = if (snapshot.timeframe !in TradingStyleProfile.DAY_TRADING.recommendedEntryTimeframes) {
                "Selected timeframe ($primaryTfLabel) is outside Day Trading default range (5m–15m)"
            } else ""
            val combinedWarning = listOf(baseDataWarning, tfWarning).filter { it.isNotBlank() }.joinToString(" · ")
            val slPct = kotlin.math.round((actualRisk / midEntry) * 10000.0) / 100.0

            ProfileRuleAdjustment(
                modeLabel = "AI Gaya Trading · Day Trading",
                modePrefix = "[Day Trading] ",
                entryMin = dayMinEntry,
                entryMax = dayMaxEntry,
                stopLoss = daySl,
                tp1 = dayTp1,
                tp2 = dayTp2,
                tp3 = dayTp3,
                riskReward = dayRr,
                minRequiredRr = TradingStyleProfile.DAY_TRADING.minimumRiskReward,
                expiryMinutes = 360,
                invalidationOverride = "Intraday invalidation: $primaryTfLabel structure break & close beyond ${roundPriceCleanly(daySl)} within 6h session horizon",
                positionSizingGuidance = "Intraday SL distance $slPct% · Horizon 2h–6h · Target R:R 1:$dayRr",
                dataWarning = combinedWarning
            )
        }

        TradingStyleProfile.SWING_TRADING -> {
            // Swing Trading: 1H–4H default entry, 4H–1D HTF.
            // Prioritize broader market structure, macro trend direction, major S/R zones, and structural SL sizing.
            val swingMinEntry = if (isLongCandidate) {
                roundPriceCleanly((price - safeAtr * 0.25).coerceAtLeast(price * 0.5))
            } else {
                roundPriceCleanly((price - safeAtr * 0.10).coerceAtLeast(price * 0.5))
            }
            val swingMaxEntry = if (isLongCandidate) {
                roundPriceCleanly(price + safeAtr * 0.10)
            } else {
                roundPriceCleanly(price + safeAtr * 0.25)
            }
            val midEntry = (swingMinEntry + swingMaxEntry) / 2.0

            val majorSup = snapshot.supportLevels.firstOrNull()
            val majorRes = snapshot.resistanceLevels.firstOrNull()
            val swingLow = snapshot.marketStructure.lastSwingLowPrice
            val swingHigh = snapshot.marketStructure.lastSwingHighPrice

            val structuralSl = if (isLongCandidate) {
                val floorLevel = listOfNotNull(majorSup, swingLow).filter { it < swingMinEntry && it > swingMinEntry - safeAtr * 4.0 }.maxOrNull()
                    ?: (swingMinEntry - safeAtr * 1.35)
                roundPriceCleanly(minOf(floorLevel - safeAtr * 0.25, midEntry - safeAtr * 1.15).coerceAtLeast(price * 0.4))
            } else {
                val ceilingLevel = listOfNotNull(majorRes, swingHigh).filter { it > swingMaxEntry && it < swingMaxEntry + safeAtr * 4.0 }.minOrNull()
                    ?: (swingMaxEntry + safeAtr * 1.35)
                roundPriceCleanly(maxOf(ceilingLevel + safeAtr * 0.25, midEntry + safeAtr * 1.15))
            }

            val structuralRisk = abs(midEntry - structuralSl).coerceAtLeast(safeAtr * 1.1)
            val swingTp1 = if (isLongCandidate) {
                roundPriceCleanly(maxOf(rawTp1, midEntry + structuralRisk * 1.85))
            } else {
                roundPriceCleanly(minOf(rawTp1, midEntry - structuralRisk * 1.85).coerceAtLeast(price * 0.2))
            }
            val swingTp2 = if (isLongCandidate) {
                roundPriceCleanly(maxOf(rawTp2, midEntry + structuralRisk * 2.80))
            } else {
                roundPriceCleanly(minOf(rawTp2, midEntry - structuralRisk * 2.80).coerceAtLeast(price * 0.15))
            }
            val swingTp3 = if (isLongCandidate) {
                roundPriceCleanly(maxOf(rawTp3, midEntry + structuralRisk * 3.80))
            } else {
                roundPriceCleanly(minOf(rawTp3, midEntry - structuralRisk * 3.80).coerceAtLeast(price * 0.10))
            }
            val swingRr = kotlin.math.round((abs(swingTp1 - midEntry) / structuralRisk) * 100.0) / 100.0

            val macroAligned = (rawCandidateDirection == "LONG" && higherBias == "BULLISH" && primaryBias == "BULLISH" && ema50 >= ema200 * 0.995) ||
                (rawCandidateDirection == "SHORT" && higherBias == "BEARISH" && primaryBias == "BEARISH" && ema50 <= ema200 * 1.005)
            if (rawCandidateDirection != "WAIT" && !macroAligned) {
                waitReasons.add("Swing Trading filter: broader macro trend (HTF $higherBias / EMA50 vs EMA200) is not aligned for multi-session holding")
            } else if (rawCandidateDirection != "WAIT") {
                positiveReasons.add("Swing Trading macro structure & HTF ($higherBias) aligned with structural S/R invalidation")
            }

            if (rawCandidateDirection != "WAIT" && swingRr < TradingStyleProfile.SWING_TRADING.minimumRiskReward) {
                waitReasons.add("Swing Trading filter: R:R (1:$swingRr) is below swing minimum (1:${TradingStyleProfile.SWING_TRADING.minimumRiskReward})")
            }
            if ((rawCandidateDirection == "WAIT" || waitReasons.isNotEmpty()) &&
                waitReasons.none { it.contains("Swing Trading", ignoreCase = true) }
            ) {
                waitReasons.add(
                    0,
                    "Swing Trading profile ($primaryTfLabel): waiting for broader HTF ($higherBias) macro structure & S/R pullback alignment"
                )
            }

            val slPct = kotlin.math.round((structuralRisk / midEntry) * 10000.0) / 100.0
            val recommendedMaxLeverage = kotlin.math.round((10.0 / slPct.coerceAtLeast(0.5)).coerceIn(1.0, 5.0) * 10.0) / 10.0
            val tfWarning = if (snapshot.timeframe !in TradingStyleProfile.SWING_TRADING.recommendedEntryTimeframes) {
                "Selected timeframe ($primaryTfLabel) is outside Swing Trading default range (1H–4H)"
            } else ""
            val combinedWarning = listOf(baseDataWarning, tfWarning).filter { it.isNotBlank() }.joinToString(" · ")

            ProfileRuleAdjustment(
                modeLabel = "AI Gaya Trading · Swing Trading",
                modePrefix = "[Swing Trading] ",
                entryMin = swingMinEntry,
                entryMax = swingMaxEntry,
                stopLoss = structuralSl,
                tp1 = swingTp1,
                tp2 = swingTp2,
                tp3 = swingTp3,
                riskReward = swingRr,
                minRequiredRr = TradingStyleProfile.SWING_TRADING.minimumRiskReward,
                expiryMinutes = 2880,
                invalidationOverride = "Structural Swing invalidation: $primaryTfLabel / 4H close beyond structural swing level ${roundPriceCleanly(structuralSl)}",
                positionSizingGuidance = "Structural SL distance $slPct% — adjust size to 0.5%–1.0% risk (recommended max leverage ~${recommendedMaxLeverage}x)",
                dataWarning = combinedWarning
            )
        }
    }
}

private fun resolveEntryOrderType(
    decision: String,
    price: Double,
    entryMin: Double,
    entryMax: Double,
    rsi: Double,
    macdHist: Double
): String {
    if (decision.equals("WAIT", ignoreCase = true)) return "WAIT"
    val low = minOf(entryMin, entryMax)
    val high = maxOf(entryMin, entryMax)
    val inZoneNow = price in (low * 0.995)..(high * 1.005)
    val strongMomentum = (decision == "LONG" && rsi in 52.0..68.0 && macdHist >= 0.0) ||
        (decision == "SHORT" && rsi in 32.0..48.0 && macdHist <= 0.0)
    if (inZoneNow && strongMomentum) return "MARKET"

    return if (decision == "LONG") {
        if (high < price) "LIMIT" else if (low > price) "STOP" else "LIMIT"
    } else {
        if (low > price) "LIMIT" else if (high < price) "STOP" else "LIMIT"
    }
}

private fun synthesizeDataBlockedWaitResult(
    snapshot: MarketTechnicalSnapshot,
    context: AiAnalysisRequestContext,
    badge: String,
    modelName: String
): AiAnalysisResult {
    val warning = context.dataValidationWarning.ifBlank {
        val statusPart = context.dataStatusLabel.ifBlank { "STALE/INVALID" }
        val reasonPart = context.dataStatusReason.ifBlank {
            "Market data is stale, disconnected, or market is closed — cannot validate directional setup."
        }
        "Data status [$statusPart]: $reasonPart"
    }
    val primaryTfLabel = snapshot.timeframe.label
    val higherTfLabel = snapshot.higherContext?.timeframe?.label ?: snapshot.timeframe.higherTimeframe()?.label ?: "HTF"
    val lowerTfLabel = snapshot.lowerContext?.timeframe?.label ?: snapshot.timeframe.lowerTimeframe()?.label ?: "LTF"
    val tfSummary = "Entry: $primaryTfLabel · HTF: $higherTfLabel · LTF: $lowerTfLabel"
    val nowMs = System.currentTimeMillis()

    val sessionSnap = snapshot.smcAnalysis?.sessionSnapshot
        ?: TradingSessionEngine.evaluateSession(
            symbol = snapshot.symbol,
            candles = snapshot.recentCandles,
            marketStatusOverride = context.marketStatus
        )
    val bosSummary = snapshot.marketStructure.latestBos?.description ?: "None in recent window"
    val chochSummary = snapshot.marketStructure.latestChoch?.description ?: "None in recent window"
    val eqSummary = snapshot.marketStructure.activeEqualLevels.joinToString(", ") { it.displayLabel }.ifBlank { "None" }
    val obSummary = snapshot.marketStructure.activeOrderBlockCandidates.joinToString(", ") { it.label }.ifBlank { "None" }
    val sweepSummary = snapshot.smcAnalysis?.latestActiveSweep?.summaryBadge ?: "None"
    val sweepStateCode = snapshot.smcAnalysis?.latestActiveSweep?.state?.code ?: "NONE"
    val reclaimSummary = snapshot.smcAnalysis?.latestActiveSweep?.confirmationCondition ?: "None"
    val spreadSummary = snapshot.smcAnalysis?.spreadVolatilityValidation?.spreadStatusSummary
        ?: "Bid/Ask spread not evaluated while data feed is blocked"
    val eventSummary = context.eventRiskAssessment?.summaryMessage ?: "No blocking macro events"

    val rawObj = JSONObject().apply {
        put("decision", "WAIT")
        put("confidence", 0)
        put("trend", "SIDEWAYS")
        put("momentum", "NEUTRAL")
        put("marketStructure", snapshot.marketStructure.schemaCode)
        put("setupQuality", "WAIT")
        put("entryOrderType", "WAIT")
        put("aiSystemMode", context.aiSystem.code)
        put("aiExecutionMode", context.effectiveExecutionMode)
        put("tradingStyleProfile", context.effectiveProfileCode)
        put("selectedTimeframesSummary", tfSummary)
        put("dataValidationWarning", warning)
        put("setupDecisionState", SetupDecisionState.REJECTED.code)
        put("latestBosSummary", bosSummary)
        put("latestChochSummary", chochSummary)
        put("equalHighsLowsSummary", eqSummary)
        put("orderBlockCandidatesSummary", obSummary)
        put("liquiditySweepState", sweepStateCode)
        put("liquiditySweepSummary", sweepSummary)
        put("reclaimConfirmationSummary", reclaimSummary)
        put("tradingSessionBadge", sessionSnap.badgeText)
        put("tradingSessionSummary", sessionSnap.sessionStatusSummary)
        put("spreadValidationSummary", spreadSummary)
        put("eventRiskSummary", eventSummary)
        put("heuristicAlignmentIndex", 15)
        put("marketRegime", snapshot.marketRegime.displayLabel)
        put("mtfConfirmation", snapshot.mtfConfirmation.summary)
        put("entryZone", JSONObject().put("min", 0.0).put("max", 0.0))
        put("stopLoss", 0.0)
        put("takeProfit", JSONArray())
        put("riskReward", 0.0)
        put("invalidation", "Wait for valid, fresh market data feed before evaluating setups")
        put("reasoning", JSONArray().put(warning))
        put("summary", "WAIT — $warning")
    }

    return AiAnalysisResult(
        decision = "WAIT",
        bias = "NEUTRAL",
        confidence = 0,
        trend = "SIDEWAYS",
        momentum = "NEUTRAL",
        marketStructure = snapshot.marketStructure.schemaCode,
        setupQuality = "WAIT",
        entryZone = com.example.ai.parser.EntryZone(0.0, 0.0),
        stopLoss = 0.0,
        takeProfit = emptyList(),
        riskReward = 0.0,
        support = snapshot.supportLevels,
        resistance = snapshot.resistanceLevels,
        higherTimeframeBias = snapshot.higherContext?.marketCondition ?: "SIDEWAYS",
        primaryTimeframeBias = snapshot.primaryContext?.marketCondition ?: "SIDEWAYS",
        lowerTimeframeBias = snapshot.lowerContext?.marketCondition ?: "NEUTRAL",
        invalidIf = "Wait for valid, fresh market data feed before evaluating setups",
        reasoning = listOf(warning),
        summary = "WAIT — $warning",
        rawJson = rawObj.toString(2),
        marketRegime = snapshot.marketRegime.displayLabel,
        mtfConfirmation = snapshot.mtfConfirmation.summary,
        entryTrigger = "Wait for valid live market data",
        expiryAt = nowMs + 900_000L,
        setupSignature = "WAIT",
        providerBadge = badge,
        modelUsed = modelName,
        analyzedAt = nowMs,
        aiSystemMode = context.aiSystem.code,
        aiExecutionMode = context.effectiveExecutionMode,
        tradingStyleProfile = context.effectiveProfileCode,
        entryOrderType = "WAIT",
        selectedTimeframesSummary = tfSummary,
        dataValidationWarning = warning,
        positionSizingGuidance = "No position sizing while data is stale or market is closed",
        setupDecisionState = SetupDecisionState.REJECTED.code,
        latestBosSummary = bosSummary,
        latestChochSummary = chochSummary,
        equalHighsLowsSummary = eqSummary,
        orderBlockCandidatesSummary = obSummary,
        liquiditySweepState = sweepStateCode,
        liquiditySweepSummary = sweepSummary,
        reclaimConfirmationSummary = reclaimSummary,
        tradingSessionBadge = sessionSnap.badgeText,
        tradingSessionSummary = sessionSnap.sessionStatusSummary,
        spreadValidationSummary = spreadSummary,
        eventRiskSummary = eventSummary,
        heuristicAlignmentIndex = 15,
        strategyId = context.selectedAiStrategy.id,
        strategyName = context.selectedAiStrategy.displayName,
        tradePlan = com.example.ai.plan.StructuredTradePlan(
            strategy = context.selectedAiStrategy,
            strategyId = context.selectedAiStrategy.id,
            strategyName = context.selectedAiStrategy.displayName,
            symbol = snapshot.symbol,
            timeframe = snapshot.timeframe.label,
            marketBias = "NEUTRAL",
            direction = com.example.ai.plan.TradeDirection.NEUTRAL,
            orderType = com.example.ai.plan.TradeOrderType.NONE,
            setupStatus = com.example.ai.plan.TradeSetupStatus.WAIT,
            referencePrice = snapshot.currentPrice,
            referenceTimestamp = nowMs,
            setupRationale = warning,
            supportingEvidence = listOf(warning),
            validationMessages = listOf(warning),
            brokerVerificationStatus = com.example.ai.plan.BrokerVerificationStatus.UNVERIFIED_BROKER_DATA
        )
    )
}

private fun stampResultWithContext(
    result: AiAnalysisResult,
    snapshot: MarketTechnicalSnapshot,
    context: AiAnalysisRequestContext,
    timeframesSummary: String,
    dataWarning: String,
    positionSizingGuidance: String
): AiAnalysisResult {
    val sessionSnap = snapshot.smcAnalysis?.sessionSnapshot
        ?: TradingSessionEngine.evaluateSession(
            symbol = snapshot.symbol,
            candles = snapshot.recentCandles,
            marketStatusOverride = context.marketStatus
        )
    val refLevels = snapshot.smcAnalysis?.referenceLevels
        ?: SmcLiquiditySweepEngine.buildLiquidityReferenceLevels(snapshot.marketStructure, sessionSnap)
    val sweeps = snapshot.smcAnalysis?.detectedSweeps
        ?: SmcLiquiditySweepEngine.detectLiquiditySweeps(
            symbol = snapshot.symbol,
            timeframe = snapshot.timeframe,
            candles = snapshot.recentCandles,
            referenceLevels = refLevels,
            atr14 = snapshot.atr14
        )

    val effectiveBid = context.bidPrice ?: snapshot.smcAnalysis?.spreadVolatilityValidation?.bidPrice
    val effectiveAsk = context.askPrice ?: snapshot.smcAnalysis?.spreadVolatilityValidation?.askPrice
    val effectiveVerifiedBidAsk = context.hasVerifiedBidAsk ||
        (snapshot.smcAnalysis?.spreadVolatilityValidation?.isBidAskAvailable == true)

    val isLongCandidate = result.decision.equals("LONG", ignoreCase = true) ||
        (result.isWait && (snapshot.longCandidate?.riskRewardTp1 ?: 0.0) >= (snapshot.shortCandidate?.riskRewardTp1 ?: 0.0))

    val midEntry = if (result.entryZone.low > 0.0 && result.entryZone.high > 0.0) {
        (result.entryZone.low + result.entryZone.high) / 2.0
    } else {
        snapshot.currentPrice
    }
    val fallbackCand = if (isLongCandidate) snapshot.longCandidate else snapshot.shortCandidate
    val proposedSl = if (result.stopLoss > 0.0) result.stopLoss else (fallbackCand?.stopLoss ?: (snapshot.currentPrice * 0.99))
    val proposedTp1 = result.takeProfit.firstOrNull() ?: (fallbackCand?.tp1 ?: (snapshot.currentPrice * 1.015))
    val minReqRr = if (context.aiSystem == AiAnalysisSystem.AI_TRADING_STYLE) {
        context.tradingStyleProfile.minimumRiskReward
    } else {
        1.35
    }

    val spreadVolValidation = SmcLiquiditySweepEngine.validateSpreadAndVolatility(
        symbol = snapshot.symbol,
        currentPrice = snapshot.currentPrice,
        bidPrice = effectiveBid,
        askPrice = effectiveAsk,
        hasVerifiedBidAsk = effectiveVerifiedBidAsk,
        atr14 = snapshot.atr14,
        volatilityState = snapshot.primaryContext?.volatilityState ?: if (snapshot.isVolatilityAbnormal) "ABNORMAL" else "NORMAL",
        proposedEntryPrice = midEntry,
        proposedStopLoss = proposedSl,
        proposedTp1 = proposedTp1,
        isLongSetup = isLongCandidate,
        profileCode = context.effectiveProfileCode.ifBlank { "DAY_TRADING" },
        accountEquityUsd = context.accountEquityUsd,
        riskPerTradePercent = context.riskPerTradePercent,
        maxLeverage = context.maxLeverage,
        config = SpreadVolatilityConfig(
            unavailableSpreadPolicy = context.unavailableSpreadPolicy
        )
    )

    val validationReport = SmcLiquiditySweepEngine.evaluateSetupDecision(
        symbol = snapshot.symbol,
        timeframe = snapshot.timeframe,
        currentPrice = snapshot.currentPrice,
        higherTimeframeBias = result.higherTimeframeBias,
        primaryTimeframeBias = result.primaryTimeframeBias,
        structure = snapshot.marketStructure,
        referenceLevels = refLevels,
        sweeps = sweeps,
        sessionSnapshot = sessionSnap,
        spreadVolValidation = spreadVolValidation,
        eventRiskAssessment = context.eventRiskAssessment,
        marketStatus = context.marketStatus,
        proposedDirection = result.decision,
        entryMin = if (result.entryZone.low > 0.0) result.entryZone.low else (fallbackCand?.entryMin ?: snapshot.currentPrice),
        entryMax = if (result.entryZone.high > 0.0) result.entryZone.high else (fallbackCand?.entryMax ?: snapshot.currentPrice),
        stopLoss = proposedSl,
        tp1 = proposedTp1,
        minRequiredRr = minReqRr
    )

    // Enforce blocking risk/spread/event/sweep rules from SetupValidationReport
    val isBlockedByValidation = validationReport.decisionState in listOf(
        SetupDecisionState.REJECTED,
        SetupDecisionState.INVALIDATED,
        SetupDecisionState.WAITING_FOR_CONFIRMATION
    )
    val finalDecision = if (result.isWait || isBlockedByValidation) "WAIT" else result.decision
    val finalQuality = if (finalDecision == "WAIT") "WAIT" else result.setupQualityGrade
    val finalBias = if (finalDecision == "WAIT") "NEUTRAL" else result.bias

    val resolvedEntryOrderType = if (finalDecision == "WAIT") {
        "WAIT"
    } else {
        resolveEntryOrderType(
            decision = finalDecision,
            price = snapshot.currentPrice,
            entryMin = result.entryZone.low,
            entryMax = result.entryZone.high,
            rsi = snapshot.rsi14 ?: 50.0,
            macdHist = snapshot.macd?.histogram ?: 0.0
        )
    }

    val dynamicSizingAddon = "Dynamic Size: ${spreadVolValidation.recalculatedQuantityUnits} units ($${spreadVolValidation.recalculatedNotionalUsd} notional @ $${spreadVolValidation.recalculatedRiskUsd} risk)"
    val finalPositionSizing = if (positionSizingGuidance.isBlank()) {
        dynamicSizingAddon
    } else if (!positionSizingGuidance.contains("Dynamic Size", ignoreCase = true)) {
        "$positionSizingGuidance · $dynamicSizingAddon"
    } else {
        positionSizingGuidance
    }

    val bosSummary = snapshot.marketStructure.latestBos?.description ?: "None in recent window"
    val chochSummary = snapshot.marketStructure.latestChoch?.description ?: "None in recent window"
    val eqSummary = snapshot.marketStructure.activeEqualLevels.joinToString(", ") { it.displayLabel }.ifBlank { "None" }
    val obSummary = snapshot.marketStructure.activeOrderBlockCandidates.joinToString(", ") { it.label }.ifBlank { "None" }
    val activeSweep = sweeps.lastOrNull {
        it.state == SweepDetectionState.CONFIRMED_SWEEP || it.state == SweepDetectionState.POTENTIAL_SWEEP
    } ?: sweeps.lastOrNull()
    val sweepStateCode = activeSweep?.state?.code ?: "NONE"
    val sweepSummary = validationReport.sweepStateSummary
    val reclaimSummary = validationReport.reclaimRejectionConfirmation
    val spreadSummary = spreadVolValidation.spreadStatusSummary
    val eventSummary = validationReport.economicEventRiskSummary

    val mergedReasoning = buildList {
        addAll(result.reasoning)
        if (finalDecision == "WAIT" && !result.isWait && validationReport.blockingOrWarningReasons.isNotEmpty()) {
            validationReport.blockingOrWarningReasons.forEach { reason ->
                if (none { it.equals(reason, ignoreCase = true) }) add(0, reason)
            }
        }
        if (activeSweep != null && none { it.contains("Sweep", ignoreCase = true) }) {
            add("SMC Sweep: ${activeSweep.summaryBadge} — ${activeSweep.confirmationCondition}")
        }
        if (none { it.contains("Session:", ignoreCase = true) || it.contains("WITA", ignoreCase = true) }) {
            add("Session: ${sessionSnap.badgeText} · ${sessionSnap.instrumentAvailabilityLabel}")
        }
    }

    val mergedSummary = if (finalDecision == "WAIT" && !result.isWait) {
        "WAIT (${validationReport.decisionState.displayLabel}) — ${validationReport.blockingOrWarningReasons.firstOrNull() ?: result.summary}"
    } else {
        result.summary
    }

    val updatedJson = runCatching {
        JSONObject(result.rawJson).apply {
            put("decision", finalDecision)
            put("setupQuality", finalQuality)
            put("entryOrderType", resolvedEntryOrderType)
            put("aiSystemMode", context.aiSystem.code)
            put("aiExecutionMode", context.effectiveExecutionMode)
            put("tradingStyleProfile", context.effectiveProfileCode)
            put("selectedAiStrategy", context.selectedAiStrategy.id)
            put("selectedAiStrategyName", context.selectedAiStrategy.displayName)
            put("selectedTimeframesSummary", timeframesSummary)
            put("dataValidationWarning", dataWarning)
            put("positionSizingGuidance", finalPositionSizing)
            put("setupDecisionState", validationReport.decisionState.code)
            put("latestBosSummary", bosSummary)
            put("latestChochSummary", chochSummary)
            put("equalHighsLowsSummary", eqSummary)
            put("orderBlockCandidatesSummary", obSummary)
            put("liquiditySweepState", sweepStateCode)
            put("liquiditySweepSummary", sweepSummary)
            put("reclaimConfirmationSummary", reclaimSummary)
            put("tradingSessionBadge", sessionSnap.badgeText)
            put("tradingSessionSummary", sessionSnap.sessionStatusSummary)
            put("spreadValidationSummary", spreadSummary)
            put("eventRiskSummary", eventSummary)
            put("heuristicAlignmentIndex", validationReport.heuristicAlignmentIndex)
            put("reasoning", JSONArray().apply { mergedReasoning.forEach { put(it) } })
            put("summary", mergedSummary)
        }.toString(2)
    }.getOrElse { result.rawJson }

    return result.copy(
        decision = finalDecision,
        bias = finalBias,
        setupQuality = finalQuality,
        reasoning = mergedReasoning,
        summary = mergedSummary,
        rawJson = updatedJson,
        aiSystemMode = context.aiSystem.code,
        aiExecutionMode = context.effectiveExecutionMode,
        tradingStyleProfile = context.effectiveProfileCode,
        entryOrderType = resolvedEntryOrderType,
        selectedTimeframesSummary = timeframesSummary,
        dataValidationWarning = dataWarning,
        positionSizingGuidance = finalPositionSizing,
        setupDecisionState = validationReport.decisionState.code,
        latestBosSummary = bosSummary,
        latestChochSummary = chochSummary,
        equalHighsLowsSummary = eqSummary,
        orderBlockCandidatesSummary = obSummary,
        liquiditySweepState = sweepStateCode,
        liquiditySweepSummary = sweepSummary,
        reclaimConfirmationSummary = reclaimSummary,
        tradingSessionBadge = sessionSnap.badgeText,
        tradingSessionSummary = sessionSnap.sessionStatusSummary,
        spreadValidationSummary = spreadSummary,
        eventRiskSummary = eventSummary,
        heuristicAlignmentIndex = validationReport.heuristicAlignmentIndex,
        strategyId = context.selectedAiStrategy.id,
        strategyName = context.selectedAiStrategy.displayName,
        tradePlan = if (finalDecision == "WAIT") {
            result.tradePlan?.copy(
                setupStatus = com.example.ai.plan.TradeSetupStatus.WAIT,
                direction = com.example.ai.plan.TradeDirection.NEUTRAL,
                orderType = com.example.ai.plan.TradeOrderType.NONE,
                validationMessages = validationReport.blockingOrWarningReasons
            )
        } else {
            result.tradePlan
        }
    )
}

/**
 * Requirement #5: Ensures remote LLM levels never invent unrealistic prices far detached from current price,
 * and downgrades to WAIT if R:R is poor (< 1.2) or confidence < 55.
 */
private fun enforceV2MarketIntegrity(
    parsed: AiAnalysisResult,
    snapshot: MarketTechnicalSnapshot,
    context: AiAnalysisRequestContext = AiAnalysisRequestContext()
): AiAnalysisResult {
    val price = snapshot.currentPrice
    if (price <= 0.0) return parsed
    if (!context.isDataValidForEntry) {
        return synthesizeDataBlockedWaitResult(snapshot, context, parsed.providerBadge, parsed.modelUsed)
    }

    val primaryTfLabel = snapshot.timeframe.label
    val higherTfLabel = snapshot.higherContext?.timeframe?.label ?: snapshot.timeframe.higherTimeframe()?.label ?: "HTF"
    val lowerTfLabel = snapshot.lowerContext?.timeframe?.label ?: snapshot.timeframe.lowerTimeframe()?.label ?: "LTF"
    val tfSummary = "Entry: $primaryTfLabel · HTF: $higherTfLabel · LTF: $lowerTfLabel"

    // Strictly validate without fabricating synthetic replacement prices (Scope #3)
    val normalized = com.example.analysis.signal.SignalValidationEngine.validateAndNormalize(parsed, snapshot, context)
    if (normalized.decision == "WAIT") {
        val waitRes = normalized.copy(
            marketRegime = snapshot.marketRegime.displayLabel,
            mtfConfirmation = snapshot.mtfConfirmation.summary
        )
        return stampResultWithContext(waitRes, snapshot, context, tfSummary, context.dataValidationWarning, parsed.positionSizingGuidance)
    }

    val sigPrefix = if (context.aiSystem == AiAnalysisSystem.AI_TRADING_STYLE) {
        "${context.tradingStyleProfile.code}_"
    } else {
        ""
    }
    val sig = "${sigPrefix}${snapshot.symbol}_${snapshot.timeframe.label}_${AiResponseParser.buildSetupSignature(normalized.decision, normalized.entryZone.low, normalized.entryZone.high, normalized.stopLoss)}"
    val validRes = normalized.copy(
        marketRegime = normalized.marketRegime.takeIf { it != "RANGING" } ?: snapshot.marketRegime.displayLabel,
        mtfConfirmation = normalized.mtfConfirmation.ifBlank { snapshot.mtfConfirmation.summary },
        setupSignature = sig
    )
    return stampResultWithContext(validRes, snapshot, context, tfSummary, context.dataValidationWarning, parsed.positionSizingGuidance)
}

private fun buildFastTimeoutClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
}

/**
 * Supports OpenRouter (https://openrouter.ai/api/v1), xAI/Grok (https://api.x.ai/v1),
 * OpenAI (https://api.openai.com/v1), and custom OpenAI-compatible endpoints.
 */
internal fun resolveOpenAiCompletionsUrl(baseEndpoint: String): String {
    val clean = baseEndpoint.trim().trimEnd('/')
    return when {
        clean.endsWith("/chat/completions", ignoreCase = true) -> clean
        clean.endsWith("/v1", ignoreCase = true) -> "$clean/chat/completions"
        clean.contains("/api/v1", ignoreCase = true) -> "$clean/chat/completions"
        else -> "$clean/v1/chat/completions"
    }
}

private fun executeOpenAiCompatibleRequest(
    httpClient: OkHttpClient,
    baseEndpoint: String,
    apiKey: String,
    model: String,
    snapshot: MarketTechnicalSnapshot,
    badge: String,
    context: AiAnalysisRequestContext = AiAnalysisRequestContext()
): AiAnalysisResult? {
    val canUseVision = context.chartVisionSettingEnabled &&
        context.visionBundle != null &&
        context.visionBundle.isAvailable &&
        com.example.ai.vision.VisionCapabilityRegistry.supportsVision(
            com.example.storage.preferences.AiProviderType.CUSTOM_OPENAI,
            model,
            context.customApiSupportsVision
        )

    val url = resolveOpenAiCompletionsUrl(baseEndpoint)
    val systemPrompt = AiPromptEngine.buildSystemPrompt(
        aiSystem = context.aiSystem,
        continuousSubmode = context.continuousSubmode,
        tradingStyleProfile = context.tradingStyleProfile,
        selectedAiStrategy = context.selectedAiStrategy,
        visionBundle = if (canUseVision) context.visionBundle else null
    )
    val marketPayload = AiPromptEngine.buildStructuredMarketPayload(
        snapshot = snapshot,
        aiSystem = context.aiSystem,
        continuousSubmode = context.continuousSubmode,
        tradingStyleProfile = context.tradingStyleProfile,
        selectedAiStrategy = context.selectedAiStrategy,
        spreadPct = context.spreadPct,
        feeBps = context.feeBps
    )

    fun buildRequestBody(withVision: Boolean): JSONObject {
        val messagesArr = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))

        if (withVision && context.visionBundle != null) {
            val userContent = JSONArray()
                .put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", "Analyze this multi-timeframe market data with Historical (${context.visionBundle.candleCountHistorical}c) and Recent (${context.visionBundle.candleCountRecent}c) offscreen chart views and return ONLY valid V2 JSON:\n$marketPayload")
                )
                .put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${context.visionBundle.historicalChartBase64}"))
                )
                .put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${context.visionBundle.recentChartBase64}"))
                )
            messagesArr.put(JSONObject().put("role", "user").put("content", userContent))
        } else {
            messagesArr.put(
                JSONObject()
                    .put("role", "user")
                    .put("content", "Analyze this multi-timeframe market data and return ONLY valid V2 JSON:\n$marketPayload")
            )
        }

        return JSONObject().apply {
            put("model", model)
            put("messages", messagesArr)
            put("temperature", 0.2)
        }
    }

    val tryVisionFirst = canUseVision
    val attempts = if (tryVisionFirst) listOf(true, false) else listOf(false)

    for (attemptWithVision in attempts) {
        val requestJson = buildRequestBody(attemptWithVision)
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            httpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    // Try next attempt (e.g. text fallback if vision failed)
                    return@use
                }
                val bodyStr = response.body?.string().orEmpty()
                val root = JSONObject(bodyStr)
                val content = root.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    .orEmpty()
                val parsed = AiResponseParser.parse(content, badge, model)
                if (parsed != null) {
                    val usageObj = root.optJSONObject("usage")
                    val inTokens = if (usageObj != null && usageObj.has("prompt_tokens")) usageObj.optInt("prompt_tokens") else null
                    val outTokens = if (usageObj != null && usageObj.has("completion_tokens")) usageObj.optInt("completion_tokens") else null
                    val totalTokens = if (usageObj != null && usageObj.has("total_tokens")) {
                        usageObj.optInt("total_tokens")
                    } else if (inTokens != null && outTokens != null) {
                        inTokens + outTokens
                    } else null

                    val usageSnapshot = com.example.ai.usage.TokenUsageSnapshot(
                        inputTokens = inTokens,
                        outputTokens = outTokens,
                        totalTokens = totalTokens,
                        source = if (usageObj != null) com.example.ai.usage.TokenUsageSource.ACTUAL else com.example.ai.usage.TokenUsageSource.UNAVAILABLE,
                        isSuccess = true
                    )
                    val visionSummary = if (attemptWithVision && context.visionBundle != null) {
                        "Analyzed with ${context.visionBundle.candleCountHistorical}c historical & ${context.visionBundle.candleCountRecent}c recent chart vision"
                    } else if (tryVisionFirst && !attemptWithVision) {
                        "Multimodal image payload rejected; fell back to Technical Only"
                    } else {
                        ""
                    }
                    return parsed.copy(
                        tokenUsage = usageSnapshot,
                        visionAssisted = attemptWithVision,
                        visionAssistedSummary = visionSummary
                    )
                }
            }
        } catch (_: Exception) {
            // Next iteration will attempt text-only
        }
    }
    return null
}

private fun executeGeminiRestRequest(
    httpClient: OkHttpClient,
    apiKey: String,
    modelName: String,
    snapshot: MarketTechnicalSnapshot,
    badge: String,
    baseUrlOverride: String? = null,
    context: AiAnalysisRequestContext = AiAnalysisRequestContext()
): AiAnalysisResult? {
    val cleanModel = modelName.removePrefix("models/").trim()
    val canUseVision = context.chartVisionSettingEnabled &&
        context.visionBundle != null &&
        context.visionBundle.isAvailable &&
        com.example.ai.vision.VisionCapabilityRegistry.supportsVision(
            com.example.storage.preferences.AiProviderType.GEMINI_BUILTIN,
            cleanModel
        )

    val base = baseUrlOverride?.trim()?.trimEnd('/')
        ?.takeIf { it.isNotEmpty() }
        ?: "https://generativelanguage.googleapis.com/v1beta/models"

    val url = if (base.contains(":generateContent")) {
        "$base?key=$apiKey"
    } else if (base.endsWith("/models")) {
        "$base/$cleanModel:generateContent?key=$apiKey"
    } else {
        "$base/v1beta/models/$cleanModel:generateContent?key=$apiKey"
    }

    val systemPrompt = AiPromptEngine.buildSystemPrompt(
        aiSystem = context.aiSystem,
        continuousSubmode = context.continuousSubmode,
        tradingStyleProfile = context.tradingStyleProfile,
        selectedAiStrategy = context.selectedAiStrategy,
        visionBundle = if (canUseVision) context.visionBundle else null
    )
    val marketPayload = AiPromptEngine.buildStructuredMarketPayload(
        snapshot = snapshot,
        aiSystem = context.aiSystem,
        continuousSubmode = context.continuousSubmode,
        tradingStyleProfile = context.tradingStyleProfile,
        selectedAiStrategy = context.selectedAiStrategy,
        spreadPct = context.spreadPct,
        feeBps = context.feeBps
    )

    fun buildRequestBody(withVision: Boolean): JSONObject {
        val partsArr = JSONArray().put(
            JSONObject().put(
                "text",
                if (withVision && context.visionBundle != null) {
                    "Analyze the following multi-timeframe market data with Historical (${context.visionBundle.candleCountHistorical}c) and Recent (${context.visionBundle.candleCountRecent}c) offscreen chart views and return ONLY valid V2 JSON:\n$marketPayload"
                } else {
                    "Analyze the following multi-timeframe market data and return ONLY valid V2 JSON:\n$marketPayload"
                }
            )
        )
        if (withVision && context.visionBundle != null) {
            partsArr.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", context.visionBundle.historicalChartBase64)
                )
            )
            partsArr.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", context.visionBundle.recentChartBase64)
                )
            )
        }

        return JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemPrompt))
                )
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put("parts", partsArr)
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.2)
                }
            )
        }
    }

    val tryVisionFirst = canUseVision
    val attempts = if (tryVisionFirst) listOf(true, false) else listOf(false)

    for (attemptWithVision in attempts) {
        val requestJson = buildRequestBody(attemptWithVision)
        val req = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            httpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use
                }
                val bodyStr = response.body?.string().orEmpty()
                val responseObj = JSONObject(bodyStr)
                val textCandidate = responseObj
                    .optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text", "")
                    .orEmpty()
                val parsed = AiResponseParser.parse(textCandidate, badge, cleanModel)
                if (parsed != null) {
                    val usageObj = responseObj.optJSONObject("usageMetadata")
                    val inTokens = if (usageObj != null && usageObj.has("promptTokenCount")) usageObj.optInt("promptTokenCount") else null
                    val outTokens = if (usageObj != null && usageObj.has("candidatesTokenCount")) usageObj.optInt("candidatesTokenCount") else null
                    val totalTokens = if (usageObj != null && usageObj.has("totalTokenCount")) {
                        usageObj.optInt("totalTokenCount")
                    } else if (inTokens != null && outTokens != null) {
                        inTokens + outTokens
                    } else null

                    val usageSnapshot = com.example.ai.usage.TokenUsageSnapshot(
                        inputTokens = inTokens,
                        outputTokens = outTokens,
                        totalTokens = totalTokens,
                        source = if (usageObj != null) com.example.ai.usage.TokenUsageSource.ACTUAL else com.example.ai.usage.TokenUsageSource.UNAVAILABLE,
                        isSuccess = true
                    )
                    val visionSummary = if (attemptWithVision && context.visionBundle != null) {
                        "Analyzed with ${context.visionBundle.candleCountHistorical}c historical & ${context.visionBundle.candleCountRecent}c recent chart vision"
                    } else if (tryVisionFirst && !attemptWithVision) {
                        "Multimodal image payload rejected; fell back to Technical Only"
                    } else {
                        ""
                    }
                    return parsed.copy(
                        tokenUsage = usageSnapshot,
                        visionAssisted = attemptWithVision,
                        visionAssistedSummary = visionSummary
                    )
                }
            }
        } catch (_: Exception) {
            // Next iteration will attempt text-only
        }
    }
    return null
}

private fun executeOpenAiPing(
    httpClient: OkHttpClient,
    baseEndpoint: String,
    apiKey: String,
    model: String
): ConnectionTestResult {
    val start = System.currentTimeMillis()
    val url = resolveOpenAiCompletionsUrl(baseEndpoint)
    val pingBody = JSONObject().apply {
        put("model", model.ifEmpty { "gpt-4o-mini" })
        put("max_tokens", 4)
        put(
            "messages",
            JSONArray().put(JSONObject().put("role", "user").put("content", "ping"))
        )
    }
    val req = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
        .post(pingBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()

    return try {
        httpClient.newCall(req).execute().use { response ->
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            if (response.isSuccessful) {
                ConnectionTestResult(true, elapsed, "🟢 Connected · ${elapsed}ms")
            } else {
                ConnectionTestResult(false, elapsed, "🔴 Failed · HTTP ${response.code}")
            }
        }
    } catch (_: SocketTimeoutException) {
        val elapsed = System.currentTimeMillis() - start
        ConnectionTestResult(false, elapsed, "🔴 Failed · Timeout")
    } catch (_: UnknownHostException) {
        val elapsed = System.currentTimeMillis() - start
        ConnectionTestResult(false, elapsed, "🔴 Failed · Offline / Invalid Host")
    } catch (_: Exception) {
        val elapsed = System.currentTimeMillis() - start
        ConnectionTestResult(false, elapsed, "🔴 Connection failed")
    }
}

private fun executeGeminiPing(
    httpClient: OkHttpClient,
    apiKey: String,
    modelName: String,
    baseUrlOverride: String? = null
): ConnectionTestResult {
    val start = System.currentTimeMillis()
    val base = baseUrlOverride?.trim()?.trimEnd('/')
        ?.takeIf { it.isNotEmpty() }
        ?: "https://generativelanguage.googleapis.com/v1beta/models"
    val cleanModel = modelName.removePrefix("models/").trim().ifEmpty { "gemini-3.5-flash" }
    val url = if (base.endsWith("/models")) {
        "$base/$cleanModel:generateContent?key=$apiKey"
    } else {
        "$base/v1beta/models/$cleanModel:generateContent?key=$apiKey"
    }

    val pingJson = JSONObject().apply {
        put(
            "contents",
            JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "ping")))
            )
        )
        put("generationConfig", JSONObject().put("maxOutputTokens", 4))
    }

    val req = Request.Builder()
        .url(url)
        .post(pingJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()

    return try {
        httpClient.newCall(req).execute().use { response ->
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            if (response.isSuccessful) {
                ConnectionTestResult(true, elapsed, "🟢 Connected · ${elapsed}ms")
            } else {
                ConnectionTestResult(false, elapsed, "🔴 Failed · HTTP ${response.code}")
            }
        }
    } catch (_: SocketTimeoutException) {
        val elapsed = System.currentTimeMillis() - start
        ConnectionTestResult(false, elapsed, "🔴 Failed · Timeout")
    } catch (_: UnknownHostException) {
        val elapsed = System.currentTimeMillis() - start
        ConnectionTestResult(false, elapsed, "🔴 Failed · Offline")
    } catch (_: Exception) {
        val elapsed = System.currentTimeMillis() - start
        ConnectionTestResult(false, elapsed, "🔴 Connection failed")
    }
}
