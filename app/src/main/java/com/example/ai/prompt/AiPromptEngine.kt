package com.example.ai.prompt

import com.example.analysis.supportResistance.SupportResistanceEngine.roundPriceCleanly
import com.example.analysis.technical.CandidateTradeLevels
import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.analysis.technical.TimeframeContextSummary
import com.example.market.candle.inferInstrumentCategory
import com.example.market.candle.inferMarketProvider
import com.example.ai.strategy.AiStrategy
import com.example.storage.preferences.AiAnalysisSystem
import com.example.storage.preferences.AiContinuousSubmode
import com.example.storage.preferences.TradingStyleProfile
import org.json.JSONArray
import org.json.JSONObject

object AiPromptEngine {

    const val SYSTEM_PROMPT = """You are the LUMAVUN AI Insight V3.1A Advanced Trade Planner & Multi-Timeframe Trading Analyst (supporting Crypto, Forex, Metals/Commodities, Stocks, and Indices).

Analyze the supplied multi-timeframe market data objectively.
Do not guarantee future price movement. Do not claim certainty.
Never force a trade. Return WAIT when there is insufficient evidence.

VALID DECISIONS:
- "LONG"
- "SHORT"
- "WAIT"

STRUCTURED TRADE PLAN ARCHITECTURE:
Separate market bias from proposed trade direction. A bearish market bias does not automatically require a SELL recommendation.
1. Trade Direction:
   - "BUY": High-probability long setup supported by technical confluence.
   - "SELL": High-probability short setup supported by technical confluence.
   - "NEUTRAL": No clear edge or setup does not meet minimum quality requirements.

2. Order Type Selection Guidelines:
   - "BUY MARKET": Confirmed bullish setup suitable for immediate execution at current reference price.
   - "SELL MARKET": Confirmed bearish setup suitable for immediate execution at current reference price.
   - "BUY LIMIT": Proposed buy entry strictly BELOW the current reference price (pullback to demand/support).
   - "SELL LIMIT": Proposed sell entry strictly ABOVE the current reference price (rally to supply/resistance).
   - "BUY STOP": Proposed buy breakout entry strictly ABOVE the current reference price (e.g. above resistance/swing high).
   - "SELL STOP": Proposed sell breakdown entry strictly BELOW the current reference price (e.g. below support/swing low).
   - "NONE": Required for WAIT / NEUTRAL decisions.

3. Setup Status:
   - "ACTIONABLE": Confirmed immediate market execution setup.
   - "PENDING": Valid pending limit or stop setup waiting for price trigger.
   - "WAIT": Insufficient confluence, unclear structure, or adverse risk.
   - "INVALID": Criteria violated.

SETUP QUALITY GRADES (Separate from confidence 0-100):
- "A+": Multi-timeframe aligned (HTF + Primary + LTF), favorable regime, strong structure/volume, R:R >= 2.0.
- "A": HTF & Primary aligned, clean structure & indicator support, R:R >= 1.6.
- "B": Valid directional setup with acceptable structure and R:R >= 1.35.
- "WAIT": Conditions do not meet setup quality requirements.

WAIT is a valid and often preferred decision. You MUST choose "WAIT" (and tradeDirection "NEUTRAL", orderType "NONE") when ANY of the following apply:
1. Higher timeframe and primary timeframe conflict.
2. Market regime is CHOPPY or INSUFFICIENT_DATA.
3. An immediate MARKET entry would chase resistance (BUY) or support (SELL), AND no separately confirmed LIMIT pullback/retest with valid geometry and reward is available.
4. Volume does not confirm a breakout (when hasVolumeData is true).
5. Mathematical Risk/Reward (riskReward) is poor (< 1.35, or < 2.0 for AI Sniper).
6. Market structure is unclear or sideways without a clean edge.
7. Volatility is abnormal.
8. Technical indicators strongly conflict.
9. No clean entry zone exists or confidence is below 60.

ENTRY / STOP LOSS / TAKE PROFIT RULES:
- Do NOT invent unrealistic price levels.
- Anchor entryZone, stopLoss, and takeProfit to the provided candidateLevels, support/resistance, swing highs/lows, and ATR.
- Evaluate geometry across the ENTIRE entry zone:
  * BUY: stopLoss < entryZone.min, and tp1 > entryZone.max.
  * SELL: stopLoss > entryZone.max, and tp1 < entryZone.min.
- Provide a clear setupRationale, supportingEvidence, contradictingEvidence, invalidation condition, and expiryMinutes.
- Calculate riskReward mathematically from worst-case entry zone boundary, stopLoss, and takeProfit.

CONFIRMED CONDITIONAL LIMIT SCENARIOS (PREFER WHEN VERIFIED, NOT AS A WAIT BYPASS):
- Review the last confirmed CLOSED candle against the provided support/resistance levels BEFORE rejecting every non-market entry.
- After a confirmed bullish support rejection or resistance breakout, propose BUY LIMIT at the measured pullback/retest level only when the entire entry zone is BELOW referencePrice.
- After a confirmed bearish resistance rejection or support breakdown, propose SELL LIMIT at the measured rally/retest level only when the entire entry zone is ABOVE referencePrice.
- Use measured opposing support/resistance as TP1. Do not invent the missing target or fabricate a candle confirmation. SL must invalidate the S/R setup beyond the entry zone.
- Preserve WAIT for stale/closed market data, contradictory HTF, unconfirmed candles, abnormal volatility, inadequate R:R, or levels that are not actually in the input.
- A PENDING LIMIT is an analytical plan waiting for price to revisit an entry zone; NEVER claim it is already filled, broker-verified, or executed.
- If a validated pending setup exists but the current MARKET price is unsuitable, choose PENDING BUY LIMIT / SELL LIMIT rather than defaulting to WAIT solely because price has not reached the intended entry.

Return ONLY valid JSON matching this exact V3.1A schema:
{
  "decision": "LONG" | "SHORT" | "WAIT",
  "tradeDirection": "BUY" | "SELL" | "NEUTRAL",
  "orderType": "MARKET" | "LIMIT" | "STOP" | "NONE",
  "setupStatus": "ACTIONABLE" | "PENDING" | "WAIT" | "INVALID",
  "marketBias": "BULLISH" | "BEARISH" | "SIDEWAYS" | "TRANSITIONAL",
  "confidence": <integer 0-100>,
  "setupQuality": "A+" | "A" | "B" | "WAIT",
  "entryOrderType": "MARKET" | "LIMIT" | "STOP" | "WAIT",
  "marketRegime": "TRENDING" | "RANGING" | "BREAKOUT" | "PULLBACK" | "HIGH VOLATILITY" | "LOW VOLATILITY" | "CHOPPY",
  "mtfConfirmation": "<summary of HTF, Primary TF, and LTF agreement or conflict>",
  "trend": "BULLISH" | "BEARISH" | "SIDEWAYS" | "TRANSITIONAL",
  "marketStructure": "HH_HL" | "LH_LL" | "HL" | "LH" | "SIDEWAYS" | "TRANSITIONAL",
  "referencePrice": <number>,
  "entryZone": {
    "min": <number>,
    "max": <number>
  },
  "entryTrigger": "<explicit trigger condition for entry>",
  "stopLoss": <number>,
  "takeProfit": [
    <number>,
    <number>,
    <number>
  ],
  "riskReward": <number>,
  "support": [<number>, <number>],
  "resistance": [<number>, <number>],
  "higherTimeframeBias": "BULLISH" | "BEARISH" | "SIDEWAYS" | "TRANSITIONAL",
  "primaryTimeframeBias": "BULLISH" | "BEARISH" | "SIDEWAYS" | "TRANSITIONAL",
  "lowerTimeframeBias": "BULLISH" | "BEARISH" | "PULLBACK" | "SIDEWAYS",
  "setupRationale": "<detailed explanation of why this setup and order type are suitable>",
  "supportingEvidence": [
    "<evidence bullet 1>",
    "<evidence bullet 2>"
  ],
  "contradictingEvidence": [
    "<risk factor or caution bullet>"
  ],
  "invalidation": "<clear invalidation condition>",
  "expiryMinutes": <integer>,
  "positionSizingGuidance": "<risk and position sizing guidance>",
  "reasoning": [
    "<evidence bullet 1>",
    "<evidence bullet 2>",
    "<evidence bullet 3>"
  ],
  "summary": "<concise trade plan summary>"
}"""

    fun buildSystemPrompt(
        aiSystem: AiAnalysisSystem = AiAnalysisSystem.AI_CONTINUOUS,
        continuousSubmode: AiContinuousSubmode = AiContinuousSubmode.AUTO,
        tradingStyleProfile: TradingStyleProfile = TradingStyleProfile.DAY_TRADING,
        selectedAiStrategy: AiStrategy = AiStrategy.AI_HYBRID,
        visionBundle: com.example.ai.vision.ChartVisionBundle? = null
    ): String {
        val strategyDirective = """
        STRATEGY DIRECTIVE: [${selectedAiStrategy.id}] ${selectedAiStrategy.displayName}
        Strategy Focus: ${selectedAiStrategy.shortDescription}
        Analysis Instructions:
        ${selectedAiStrategy.analysisInstructions}

        STRATEGY TRUTH & EVIDENCE RULES:
        - Ground all market reasoning strictly in verified indicators, candle behavior, and visible chart features.
        - Do NOT claim a concept (e.g. Order Block, Liquidity Sweep, Fair Value Gap, Breakout, or Session High/Low) has been detected unless genuine evidence exists in the provided data.
        - If conditions required for ${selectedAiStrategy.displayName} are absent, unclear, or conflicting, select "WAIT" with a concise explanation.
        """.trimIndent()

        val workflowAddendum = when (aiSystem) {
            AiAnalysisSystem.AI_CONTINUOUS -> {
                if (continuousSubmode == AiContinuousSubmode.AUTO) {
                    """
                    WORKFLOW MODE: AI Continuous · Auto (Setup Engine V2)
                    - Evaluate live market conditions for multi-timeframe setup discovery and lifecycle tracking.
                    - Require clean HTF + Primary TF alignment and R:R >= 1.35.
                    """.trimIndent()
                } else {
                    """
                    WORKFLOW MODE: AI Continuous · Manual (On-Demand Assessment)
                    - Perform an immediate on-demand market assessment using the latest valid market data.
                    - Return complete setup rationale, entryOrderType (MARKET/LIMIT/WAIT), entry zone, SL, TP, invalidation, and R:R if validated, or WAIT if not sufficiently validated.
                    """.trimIndent()
                }
            }
            AiAnalysisSystem.AI_TRADING_STYLE -> {
                when (tradingStyleProfile) {
                    TradingStyleProfile.SCALPING -> """
                    WORKFLOW MODE: AI Gaya Trading · SCALPING (Manual Profile)
                    - Default Entry Timeframe: 1m–5m | Higher-Timeframe Context: 5m–15m.
                    - Prioritize short-term momentum (RSI/MACD impulse + EMA20 hold), tight spread, transaction fees, volatility, and fast invalidation.
                    - Reject setups (return WAIT) if expected movement to TP1 does not reasonably compensate for spread + round-trip fees, or if R:R < 1.45.
                    - Holding horizon: 15–45 minutes.
                    """.trimIndent()
                    TradingStyleProfile.DAY_TRADING -> """
                    WORKFLOW MODE: AI Gaya Trading · DAY TRADING (Manual Profile)
                    - Default Entry Timeframe: 5m–15m | Higher-Timeframe Context: 15m–1H.
                    - Prioritize intraday market structure (HH_HL / LH_LL), trend continuation, breakouts, retests, and pullbacks.
                    - Evaluate whether the setup remains valid within the intended intraday session horizon (expiry 120–480 minutes) and require R:R >= 1.55.
                    """.trimIndent()
                    TradingStyleProfile.SWING_TRADING -> """
                    WORKFLOW MODE: AI Gaya Trading · SWING TRADING (Manual Profile)
                    - Default Entry Timeframe: 1H–4H | Higher-Timeframe Context: 4H–1D.
                    - Prioritize broader macro market structure, EMA50/EMA200 trend direction, major support/resistance zones, and structural pullbacks.
                    - Anchor stopLoss beyond the actual structural swing invalidation level and provide position sizing guidance adjusted to the wider stop distance. Require R:R >= 1.80 and multi-day horizon (1440–4320 minutes).
                    """.trimIndent()
                }
            }
        }

        val visionAddendum = if (visionBundle != null && visionBundle.isAvailable) {
            """
            
            HYBRID CHART VISION PERSPECTIVES (${visionBundle.candleCountHistorical}c Historical & ${visionBundle.candleCountRecent}c Recent):
            Two offscreen rendered chart perspectives are provided in this request:
            1. Historical Chart View (~150-250 candles): Broader macro trend, swing structure, major S/R levels, and supply/demand zones.
            2. Recent Price Action View (~50-75 candles): Immediate microstructure, recent swing highs/lows, wicks/rejections, and liquidity sweeps.

            HYBRID REASONING & TECHNICAL AUTHORITY RULES:
            - The structured numeric technical dataset is the AUTHORITATIVE source for exact prices, indicator levels, candidate entry zones, stop losses, and take-profit targets.
            - Use the visual chart views for contextual pattern recognition: trend slope, candle exhaustion, sweep wicks, pattern formation, and visual congestion.
            - If visual observations conflict with technical numbers, explicitly disclose the conflict in your reasoning bullets and default to conservative WAIT.
            """.trimIndent()
        } else {
            ""
        }

        return if (visionAddendum.isNotBlank()) {
            "$SYSTEM_PROMPT\n\n$strategyDirective\n\n$workflowAddendum\n\n$visionAddendum"
        } else {
            "$SYSTEM_PROMPT\n\n$strategyDirective\n\n$workflowAddendum"
        }
    }

    /**
     * Serializes the multi-timeframe MarketTechnicalSnapshot into a compact, structured JSON payload
     * including Higher TF, Primary TF, Lower TF, Market Regime, MTF Confirmation, and mathematically anchored candidate setups.
     */
    fun buildStructuredMarketPayload(
        snapshot: MarketTechnicalSnapshot,
        aiSystem: AiAnalysisSystem = AiAnalysisSystem.AI_CONTINUOUS,
        continuousSubmode: AiContinuousSubmode = AiContinuousSubmode.AUTO,
        tradingStyleProfile: TradingStyleProfile = TradingStyleProfile.DAY_TRADING,
        selectedAiStrategy: AiStrategy = AiStrategy.AI_HYBRID,
        spreadPct: Double = 0.0,
        feeBps: Double = 4.0
    ): String {
        val root = JSONObject()
        val category = inferInstrumentCategory(snapshot.symbol)
        val provider = inferMarketProvider(snapshot.symbol, category)
        val hasVolumeData = snapshot.recentCandles.any { it.volume > 0.0 }
        root.put("symbol", snapshot.symbol)
        root.put("assetCategory", category.code)
        root.put("dataProvider", provider.code)
        root.put("hasVolumeData", hasVolumeData)
        root.put("aiSystemMode", aiSystem.code)
        root.put(
            "aiExecutionMode",
            if (aiSystem == AiAnalysisSystem.AI_TRADING_STYLE) "MANUAL" else continuousSubmode.code
        )
        root.put(
            "tradingStyleProfile",
            if (aiSystem == AiAnalysisSystem.AI_TRADING_STYLE) tradingStyleProfile.code else "NONE"
        )
        root.put("selectedAiStrategy", selectedAiStrategy.id)
        root.put("selectedAiStrategyName", selectedAiStrategy.displayName)
        root.put("spreadPct", roundPriceCleanly(spreadPct))
        root.put("estimatedRoundTripFeeBps", roundPriceCleanly(feeBps * 2.0))
        root.put("primaryTimeframe", snapshot.timeframe.binanceInterval)
        root.put("higherTimeframe", snapshot.higherContext?.timeframe?.binanceInterval ?: JSONObject.NULL)
        root.put("lowerTimeframe", snapshot.lowerContext?.timeframe?.binanceInterval ?: JSONObject.NULL)
        root.put("currentPrice", roundPriceCleanly(snapshot.currentPrice))
        root.put("marketRegime", snapshot.marketRegime.displayLabel)
        root.put("mtfConfirmationStatus", snapshot.mtfConfirmation.status.label)
        root.put("mtfConfirmationSummary", snapshot.mtfConfirmation.summary)

        // Multi-Timeframe Structured Summaries (Requirement #1, #2, #9)
        val mtfObj = JSONObject()
        snapshot.higherContext?.let { mtfObj.put("higherTimeframe", serializeTimeframeContext(it)) }
        snapshot.primaryContext?.let { mtfObj.put("primaryTimeframe", serializeTimeframeContext(it)) }
        snapshot.lowerContext?.let { mtfObj.put("lowerTimeframe", serializeTimeframeContext(it)) }
        root.put("multiTimeframeAnalysis", mtfObj)

        // Candidate Trade Setups anchored to real market data (Requirement #5)
        val candidatesObj = JSONObject()
        snapshot.longCandidate?.let { candidatesObj.put("longCandidate", serializeCandidate(it)) }
        snapshot.shortCandidate?.let { candidatesObj.put("shortCandidate", serializeCandidate(it)) }
        root.put("candidateSetups", candidatesObj)

        // Primary timeframe indicators & structure
        root.put("EMA20", snapshot.ema20?.let { roundPriceCleanly(it) } ?: JSONObject.NULL)
        root.put("EMA50", snapshot.ema50?.let { roundPriceCleanly(it) } ?: JSONObject.NULL)
        root.put("EMA200", snapshot.ema200?.let { roundPriceCleanly(it) } ?: JSONObject.NULL)
        root.put("RSI14", snapshot.rsi14?.let { kotlin.math.round(it * 100.0) / 100.0 } ?: JSONObject.NULL)

        // ICT / SMC Market Structure, Liquidity Sweeps, Trading Session & Spread Validation
        val smcObj = JSONObject().apply {
            put("structureState", snapshot.marketStructure.state.code)
            put("structureSchema", snapshot.marketStructure.schemaCode)
            put("latestBos", snapshot.marketStructure.latestBos?.description ?: JSONObject.NULL)
            put("latestChoch", snapshot.marketStructure.latestChoch?.description ?: JSONObject.NULL)
            put(
                "equalHighsLows",
                JSONArray().apply {
                    snapshot.marketStructure.activeEqualLevels.forEach { put(it.displayLabel) }
                }
            )
            put(
                "orderBlockCandidates",
                JSONArray().apply {
                    snapshot.marketStructure.activeOrderBlockCandidates.forEach { put(it.label) }
                }
            )
            snapshot.smcAnalysis?.let { smc ->
                put("tradingSessionBadge", smc.sessionSnapshot.badgeText)
                put("tradingSessionSummary", smc.sessionSnapshot.sessionStatusSummary)
                put("witaTime", smc.sessionSnapshot.witaTimeFormatted)
                put("londonTime", smc.sessionSnapshot.londonTimeFormatted)
                put("newYorkTime", smc.sessionSnapshot.newYorkTimeFormatted)
                put("latestSweepState", smc.latestActiveSweep?.state?.code ?: "NONE")
                put("latestSweepSummary", smc.latestActiveSweep?.summaryBadge ?: "None")
                put("reclaimConfirmation", smc.latestActiveSweep?.confirmationCondition ?: "None")
                put("spreadValidationSummary", smc.spreadVolatilityValidation.spreadStatusSummary)
                put("setupDecisionState", smc.setupValidationReport.decisionState.code)
                put("heuristicAlignmentIndex", smc.setupValidationReport.heuristicAlignmentIndex)
            }
        }
        root.put("smcSessionAndRiskContext", smcObj)

        val macdObj = JSONObject().apply {
            put("macd", snapshot.macd?.macd?.let { roundPriceCleanly(it) } ?: JSONObject.NULL)
            put("signal", snapshot.macd?.signal?.let { roundPriceCleanly(it) } ?: JSONObject.NULL)
            put("histogram", snapshot.macd?.histogram?.let { roundPriceCleanly(it) } ?: JSONObject.NULL)
        }
        root.put("MACD", macdObj)
        root.put("ATR14", snapshot.atr14?.let { roundPriceCleanly(it) } ?: JSONObject.NULL)

        val supArr = JSONArray()
        snapshot.supportLevels.forEach { supArr.put(roundPriceCleanly(it)) }
        root.put("supportLevels", supArr)

        val resArr = JSONArray()
        snapshot.resistanceLevels.forEach { resArr.put(roundPriceCleanly(it)) }
        root.put("resistanceLevels", resArr)

        val conflictsObj = JSONObject().apply {
            put("emaConflict", snapshot.hasEmaConflict)
            put("rsiConflict", snapshot.hasRsiConflict)
            put("macdConflict", snapshot.hasMacdConflict)
            put("volumeWeak", snapshot.isVolumeWeak)
            put("nearMajorResistance", snapshot.isNearMajorResistance)
            put("nearMajorSupport", snapshot.isNearMajorSupport)
            put("volatilityAbnormal", snapshot.isVolatilityAbnormal)
            put(
                "higherPrimaryConflict",
                snapshot.higherContext != null &&
                    snapshot.primaryContext != null &&
                    snapshot.higherContext.marketCondition != snapshot.primaryContext.marketCondition &&
                    snapshot.higherContext.marketCondition in listOf("BULLISH", "BEARISH") &&
                    snapshot.primaryContext.marketCondition in listOf("BULLISH", "BEARISH")
            )
        }
        root.put("signalConflicts", conflictsObj)

        // Compact last 10 candles on primary timeframe
        val recentArr = JSONArray()
        for (c in snapshot.recentCandles.takeLast(10)) {
            recentArr.put(
                JSONObject()
                    .put("t", c.openTime)
                    .put("o", roundPriceCleanly(c.open))
                    .put("h", roundPriceCleanly(c.high))
                    .put("l", roundPriceCleanly(c.low))
                    .put("c", roundPriceCleanly(c.close))
                    .put("v", roundPriceCleanly(c.volume))
            )
        }
        root.put("recentCandles", recentArr)

        return root.toString()
    }

    private fun serializeTimeframeContext(ctx: TimeframeContextSummary): JSONObject {
        return JSONObject().apply {
            put("timeframe", ctx.timeframe.label)
            put("trend", ctx.trend)
            put("marketCondition", ctx.marketCondition)
            put("structure", ctx.structureCode)
            put("emaAlignment", ctx.emaAlignment)
            put("ema20", ctx.ema20?.let { roundPriceCleanly(it) } ?: JSONObject.NULL)
            put("ema50", ctx.ema50?.let { roundPriceCleanly(it) } ?: JSONObject.NULL)
            put("ema200", ctx.ema200?.let { roundPriceCleanly(it) } ?: JSONObject.NULL)
            put("rsi14", ctx.rsi14?.let { kotlin.math.round(it * 10.0) / 10.0 } ?: JSONObject.NULL)
            put("macdState", ctx.macdState)
            put("volumeCondition", ctx.volumeCondition)
            put("atr14", ctx.atr14?.let { roundPriceCleanly(it) } ?: JSONObject.NULL)
            put("volatilityState", ctx.volatilityState)
            put("breakoutPullbackCondition", ctx.breakoutPullbackCondition)
            put("support", JSONArray().apply { ctx.supportLevels.forEach { put(roundPriceCleanly(it)) } })
            put("resistance", JSONArray().apply { ctx.resistanceLevels.forEach { put(roundPriceCleanly(it)) } })
        }
    }

    private fun serializeCandidate(cand: CandidateTradeLevels): JSONObject {
        return JSONObject().apply {
            put("direction", cand.direction)
            put("entryZone", JSONObject().put("min", cand.entryMin).put("max", cand.entryMax))
            put("stopLoss", cand.stopLoss)
            put("takeProfit", JSONArray().put(cand.tp1).put(cand.tp2).put(cand.tp3))
            put("riskRewardTp1", cand.riskRewardTp1)
            put("riskRewardTp2", cand.riskRewardTp2)
        }
    }
}
