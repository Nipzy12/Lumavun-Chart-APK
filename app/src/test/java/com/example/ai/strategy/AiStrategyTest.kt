package com.example.ai.strategy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.analyst.AiAnalysisRequestContext
import com.example.ai.analyst.BuiltInAIProvider
import com.example.ai.parser.AiAnalysisResult
import com.example.ai.parser.AiResponseParser
import com.example.ai.parser.EntryZone
import com.example.ai.prompt.AiPromptEngine
import com.example.ai.vision.ChartVisionBundle
import com.example.analysis.marketStructure.MarketStructureAnalysis
import com.example.analysis.marketStructure.StructureState
import com.example.analysis.signal.SignalValidationEngine
import com.example.analysis.technical.CandidateTradeLevels
import com.example.analysis.technical.MarketRegime
import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.analysis.technical.MtfConfirmation
import com.example.analysis.technical.MtfConfirmationStatus
import com.example.market.candle.Candle
import com.example.market.candle.Timeframe
import com.example.storage.preferences.AiAnalysisSystem
import com.example.storage.preferences.AiContinuousSubmode
import com.example.storage.preferences.AppPreferences
import com.example.storage.preferences.TradingStyleProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AiStrategyTest {

    private lateinit var context: Context
    private lateinit var preferences: AppPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        preferences = AppPreferences(context)
    }

    private fun createDummyCandles(count: Int = 30): List<Candle> {
        val now = System.currentTimeMillis()
        return (0 until count).map { i ->
            val p = 60_000.0 + (i * 10.0)
            Candle(
                openTime = now - ((count - i) * 60_000L),
                open = p,
                high = p + 20.0,
                low = p - 20.0,
                close = p + 5.0,
                volume = 100.0,
                closeTime = now - ((count - i - 1) * 60_000L),
                isClosed = true
            )
        }
    }

    private fun createDummySnapshot(
        price: Double = 60_000.0,
        regime: MarketRegime = MarketRegime.TRENDING,
        longCandidate: CandidateTradeLevels? = CandidateTradeLevels(
            direction = "LONG",
            entryMin = 59_900.0,
            entryMax = 60_000.0,
            stopLoss = 59_500.0,
            tp1 = 61_000.0,
            tp2 = 61_800.0,
            tp3 = 62_500.0,
            riskRewardTp1 = 2.2,
            riskRewardTp2 = 4.0
        )
    ): MarketTechnicalSnapshot {
        val candles = createDummyCandles(30)
        return MarketTechnicalSnapshot(
            symbol = "BTCUSDT",
            timeframe = Timeframe.M15,
            currentPrice = price,
            latestCandle = candles.last(),
            recentCandles = candles,
            ema20 = price - 50.0,
            ema50 = price - 120.0,
            ema200 = price - 400.0,
            rsi14 = 58.0,
            macd = null,
            atr14 = 250.0,
            currentVolume = 150.0,
            volumeSma20 = 120.0,
            supportLevels = listOf(59_500.0, 58_800.0),
            resistanceLevels = listOf(61_500.0, 62_000.0),
            marketStructure = MarketStructureAnalysis(
                state = StructureState.BULLISH,
                schemaCode = "HH_HL",
                swingSummary = "Clear swings",
                biasLabel = "BULLISH",
                lastHighTag = null,
                lastLowTag = null,
                lastSwingHighPrice = price + 500.0,
                lastSwingLowPrice = price - 500.0,
                recentSwings = emptyList()
            ),
            hasEmaConflict = false,
            hasRsiConflict = false,
            hasMacdConflict = false,
            isVolumeWeak = false,
            isNearMajorResistance = false,
            isNearMajorSupport = false,
            isVolatilityAbnormal = false,
            marketRegime = regime,
            mtfConfirmation = MtfConfirmation(
                status = MtfConfirmationStatus.ALIGNED,
                summary = "HTF & Primary aligned Bullish",
                hasHigherTfData = true,
                hasLowerTfData = true
            ),
            primaryContext = null,
            higherContext = null,
            lowerContext = null,
            longCandidate = longCandidate,
            shortCandidate = null,
            smcAnalysis = null
        )
    }

    @Test
    fun `all nine strategies exist with required stable IDs and defaults`() {
        val expectedIds = listOf(
            "AI_HYBRID",
            "AI_SMART",
            "AI_SNR",
            "AI_SMC",
            "AI_ICT",
            "AI_SNIPER",
            "AI_SCALPING",
            "AI_PRICE_ACTION",
            "AI_SESSION_TRADER"
        )
        val actualIds = AiStrategy.entries.map { it.id }
        assertEquals(expectedIds, actualIds)
        assertEquals(9, AiStrategy.entries.size)
        assertEquals(AiStrategy.AI_HYBRID, AiStrategy.DEFAULT)

        AiStrategy.entries.forEach { strategy ->
            assertTrue(strategy.displayName.isNotBlank())
            assertTrue(strategy.shortBadge.isNotBlank())
            assertTrue(strategy.shortDescription.isNotBlank())
            assertTrue(strategy.analysisInstructions.isNotBlank())
        }
    }

    @Test
    fun `AiStrategy fromId parses IDs, display names, badges, case-insensitively with safe fallback`() {
        assertEquals(AiStrategy.AI_HYBRID, AiStrategy.fromId("AI_HYBRID"))
        assertEquals(AiStrategy.AI_HYBRID, AiStrategy.fromId("ai_hybrid"))
        assertEquals(AiStrategy.AI_SMART, AiStrategy.fromId("AI_SMART"))
        assertEquals(AiStrategy.AI_SMC, AiStrategy.fromId("AI_SMC"))
        assertEquals(AiStrategy.AI_ICT, AiStrategy.fromId("AI_ICT"))
        assertEquals(AiStrategy.AI_SNIPER, AiStrategy.fromId("AI_SNIPER"))
        assertEquals(AiStrategy.AI_SCALPING, AiStrategy.fromId("AI_SCALPING"))
        assertEquals(AiStrategy.AI_PRICE_ACTION, AiStrategy.fromId("AI_PRICE_ACTION"))
        assertEquals(AiStrategy.AI_SESSION_TRADER, AiStrategy.fromId("AI_SESSION_TRADER"))

        // Lookup by display name & badge
        assertEquals(AiStrategy.AI_SMC, AiStrategy.fromId("AI SMC"))
        assertEquals(AiStrategy.AI_SNIPER, AiStrategy.fromId("Sniper"))

        // Unknown / null fallbacks to DEFAULT
        assertEquals(AiStrategy.AI_HYBRID, AiStrategy.fromId(null))
        assertEquals(AiStrategy.AI_HYBRID, AiStrategy.fromId(""))
        assertEquals(AiStrategy.AI_HYBRID, AiStrategy.fromId("UNKNOWN_STRATEGY_XYZ"))
    }

    @Test
    fun `AppPreferences persists and restores selectedAiStrategy correctly`() {
        // Defaults to AI_HYBRID
        assertEquals(AiStrategy.AI_HYBRID, preferences.settings.value.selectedAiStrategy)

        // Switch to AI_SMC
        preferences.setSelectedAiStrategy(AiStrategy.AI_SMC)
        assertEquals(AiStrategy.AI_SMC, preferences.settings.value.selectedAiStrategy)

        // Switch to AI_SNIPER
        preferences.setSelectedAiStrategy(AiStrategy.AI_SNIPER)
        assertEquals(AiStrategy.AI_SNIPER, preferences.settings.value.selectedAiStrategy)

        // Re-instantiate AppPreferences (simulating app restart)
        val restartedPrefs = AppPreferences(context)
        assertEquals(AiStrategy.AI_SNIPER, restartedPrefs.settings.value.selectedAiStrategy)

        // Restore to default
        preferences.setSelectedAiStrategy(AiStrategy.AI_HYBRID)
        assertEquals(AiStrategy.AI_HYBRID, preferences.settings.value.selectedAiStrategy)
    }

    @Test
    fun `AiPromptEngine builds system prompt containing active strategy directive and rules`() {
        val hybridPrompt = AiPromptEngine.buildSystemPrompt(
            selectedAiStrategy = AiStrategy.AI_HYBRID
        )
        assertTrue(hybridPrompt.contains("STRATEGY DIRECTIVE: [AI_HYBRID] AI Hybrid"))
        assertTrue(hybridPrompt.contains("STRATEGY TRUTH & EVIDENCE RULES"))
        assertTrue(hybridPrompt.contains("Inner Circle Trader (ICT)"))

        val sniperPrompt = AiPromptEngine.buildSystemPrompt(
            selectedAiStrategy = AiStrategy.AI_SNIPER
        )
        assertTrue(sniperPrompt.contains("STRATEGY DIRECTIVE: [AI_SNIPER] AI Sniper"))
        assertTrue(sniperPrompt.contains("Risk/Reward >= 2.0"))

        val smcPrompt = AiPromptEngine.buildSystemPrompt(
            selectedAiStrategy = AiStrategy.AI_SMC
        )
        assertTrue(smcPrompt.contains("STRATEGY DIRECTIVE: [AI_SMC] AI SMC"))
        assertTrue(smcPrompt.contains("Order Blocks"))
    }

    @Test
    fun `AiPromptEngine supports vision-enabled and technical-only paths for all strategies`() {
        val dummyVisionBundle = ChartVisionBundle(
            historicalChartBase64 = "dummy_base64_hist",
            recentChartBase64 = "dummy_base64_recent",
            candleCountHistorical = 200,
            candleCountRecent = 75
        )

        AiStrategy.entries.forEach { strategy ->
            // Vision-enabled path
            val visionPrompt = AiPromptEngine.buildSystemPrompt(
                selectedAiStrategy = strategy,
                visionBundle = dummyVisionBundle
            )
            assertTrue(visionPrompt.contains("HYBRID CHART VISION PERSPECTIVES"))
            assertTrue(visionPrompt.contains("[${strategy.id}]"))

            // Technical-only path
            val techOnlyPrompt = AiPromptEngine.buildSystemPrompt(
                selectedAiStrategy = strategy,
                visionBundle = null
            )
            assertFalse(techOnlyPrompt.contains("HYBRID CHART VISION PERSPECTIVES"))
            assertTrue(techOnlyPrompt.contains("[${strategy.id}]"))
        }
    }

    @Test
    fun `AiPromptEngine buildStructuredMarketPayload embeds selectedAiStrategy metadata`() {
        val snapshot = createDummySnapshot()
        val payloadJson = AiPromptEngine.buildStructuredMarketPayload(
            snapshot = snapshot,
            selectedAiStrategy = AiStrategy.AI_ICT
        )
        val json = JSONObject(payloadJson)
        assertEquals("AI_ICT", json.getString("selectedAiStrategy"))
        assertEquals("AI ICT", json.getString("selectedAiStrategyName"))
    }

    @Test
    fun `AiResponseParser parses strategyId and strategyName from JSON`() {
        val json = """
            {
              "decision": "LONG",
              "confidence": 85,
              "trend": "BULLISH",
              "setupQuality": "A",
              "entryZone": { "min": 60000.0, "max": 60100.0 },
              "stopLoss": 59500.0,
              "takeProfit": [61500.0, 62500.0],
              "selectedAiStrategy": "AI_SNIPER",
              "selectedAiStrategyName": "AI Sniper",
              "reasoning": ["Confluence aligned"],
              "summary": "Sniper long setup"
            }
        """.trimIndent()

        val parsed = AiResponseParser.parse(json)
        assertNotNull(parsed)
        assertEquals("LONG", parsed!!.decision)
        assertEquals("AI_SNIPER", parsed.strategyId)
        assertEquals("AI Sniper", parsed.strategyName)
    }

    @Test
    fun `AiResponseParser handles legacy JSON without strategy fields by falling back to AI_HYBRID`() {
        val json = """
            {
              "decision": "WAIT",
              "confidence": 45,
              "trend": "SIDEWAYS",
              "reasoning": ["Market range-bound"],
              "summary": "No edge"
            }
        """.trimIndent()

        val parsed = AiResponseParser.parse(json)
        assertNotNull(parsed)
        assertEquals("WAIT", parsed!!.decision)
        assertEquals("AI_HYBRID", parsed.strategyId)
        assertEquals("AI Hybrid", parsed.strategyName)
    }

    @Test
    fun `SignalValidationEngine preserves strategy attribution during validation and normalization`() {
        val snapshot = createDummySnapshot()
        val parsed = AiAnalysisResult(
            decision = "LONG",
            bias = "BULLISH",
            confidence = 82,
            trend = "BULLISH",
            momentum = "POSITIVE",
            marketStructure = "HH_HL",
            setupQuality = "A",
            entryZone = EntryZone(low = 59_900.0, high = 60_000.0),
            stopLoss = 59_400.0,
            takeProfit = listOf(61_200.0, 62_000.0),
            riskReward = 2.18,
            support = listOf(59_400.0),
            resistance = listOf(62_000.0),
            higherTimeframeBias = "BULLISH",
            primaryTimeframeBias = "BULLISH",
            lowerTimeframeBias = "BULLISH",
            invalidIf = "Close below 59400",
            reasoning = listOf("Strong trend"),
            summary = "Valid Long",
            rawJson = "{}",
            strategyId = "AI_SMC",
            strategyName = "AI SMC"
        )

        val validated = SignalValidationEngine.validateAndNormalize(parsed, snapshot)
        assertEquals("LONG", validated.decision)
        assertEquals("AI_SMC", validated.strategyId)
        assertEquals("AI SMC", validated.strategyName)
    }

    @Test
    fun `BuiltInAIProvider applies Sniper strategy strict risk-reward constraint in local fallback`() {
        // Long candidate with poor R:R (1.25)
        val poorRrCandidate = CandidateTradeLevels(
            direction = "LONG",
            entryMin = 59_900.0,
            entryMax = 60_000.0,
            stopLoss = 59_200.0,
            tp1 = 60_900.0,
            tp2 = 61_500.0,
            tp3 = 62_000.0,
            riskRewardTp1 = 1.25,
            riskRewardTp2 = 2.0
        )
        val snapshot = createDummySnapshot(longCandidate = poorRrCandidate)
        val provider = BuiltInAIProvider()

        // With AI_SNIPER: must reject to WAIT because R:R < 2.0
        val sniperContext = AiAnalysisRequestContext(
            selectedAiStrategy = AiStrategy.AI_SNIPER
        )
        val sniperResult = provider.synthesizeV2MultiTimeframeAnalysis(snapshot, "AI · Primary", "built-in-v2", sniperContext)
        assertNotNull(sniperResult)
        assertEquals("WAIT", sniperResult!!.decision)
        assertTrue(sniperResult.reasoning.any { it.contains("AI Sniper") })
        assertEquals("AI_SNIPER", sniperResult.strategyId)
        assertEquals("AI Sniper", sniperResult.strategyName)
    }

    @Test
    fun `BuiltInAIProvider adapts to market regime with AI_SMART strategy`() {
        val choppySnapshot = createDummySnapshot(regime = MarketRegime.CHOPPY)
        val provider = BuiltInAIProvider()
        val smartContext = AiAnalysisRequestContext(
            selectedAiStrategy = AiStrategy.AI_SMART
        )

        val smartResult = provider.synthesizeV2MultiTimeframeAnalysis(choppySnapshot, "AI · Primary", "built-in-v2", smartContext)
        assertNotNull(smartResult)
        assertEquals("WAIT", smartResult!!.decision)
        assertTrue(smartResult.reasoning.any { it.contains("AI Smart") && it.contains("CHOPPY") })
        assertEquals("AI_SMART", smartResult.strategyId)
    }
}
