package com.example.ai.vision

import com.example.ai.analyst.AiAnalysisRequestContext
import com.example.ai.parser.AiAnalysisResult
import com.example.ai.parser.EntryZone
import com.example.ai.prompt.AiPromptEngine
import com.example.analysis.marketStructure.MarketStructureAnalysis
import com.example.analysis.marketStructure.StructureState
import com.example.analysis.signal.SignalValidationEngine
import com.example.analysis.technical.MarketRegime
import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.analysis.technical.MtfConfirmation
import com.example.analysis.technical.MtfConfirmationStatus
import com.example.market.candle.Candle
import com.example.market.candle.Timeframe
import com.example.storage.preferences.AiProviderMode
import com.example.storage.preferences.AiProviderType
import com.example.storage.preferences.ChartVisionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChartVisionTest {

    private fun generateCandles(count: Int, startPrice: Double = 50_000.0): List<Candle> {
        val list = mutableListOf<Candle>()
        var curr = startPrice
        val now = System.currentTimeMillis()
        val intervalMs = 60_000L

        for (i in 0 until count) {
            val open = curr
            val isBull = (i % 2 == 0)
            val change = if (isBull) (i * 2.5) % 80 else -((i * 2.1) % 75)
            val close = (open + change).coerceAtLeast(100.0)
            val high = maxOf(open, close) + 15.0
            val low = minOf(open, close) - 15.0
            val openTime = now - ((count - i) * intervalMs)

            list.add(
                Candle(
                    openTime = openTime,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = 120.0 + (i % 30),
                    closeTime = openTime + intervalMs - 1,
                    isClosed = true
                )
            )
            curr = close
        }
        return list
    }

    private fun createDummySnapshot(currentPrice: Double = 50_000.0): MarketTechnicalSnapshot {
        val candles = generateCandles(30, currentPrice)
        val latestCandle = candles.last()
        val dummyStructure = MarketStructureAnalysis(
            state = StructureState.BULLISH,
            schemaCode = "HH_HL",
            swingSummary = "Clear swings",
            biasLabel = "BULLISH",
            lastHighTag = null,
            lastLowTag = null,
            lastSwingHighPrice = currentPrice + 500.0,
            lastSwingLowPrice = currentPrice - 500.0,
            recentSwings = emptyList()
        )
        return MarketTechnicalSnapshot(
            symbol = "BTCUSDT",
            timeframe = Timeframe.M15,
            currentPrice = currentPrice,
            latestCandle = latestCandle,
            recentCandles = candles,
            ema20 = currentPrice - 100.0,
            ema50 = currentPrice - 200.0,
            ema200 = currentPrice - 500.0,
            rsi14 = 55.0,
            macd = null,
            atr14 = 300.0,
            currentVolume = 1000.0,
            volumeSma20 = 1000.0,
            supportLevels = listOf(49_500.0, 49_000.0),
            resistanceLevels = listOf(50_500.0, 51_000.0),
            marketStructure = dummyStructure,
            hasEmaConflict = false,
            hasRsiConflict = false,
            hasMacdConflict = false,
            isVolumeWeak = false,
            isNearMajorResistance = false,
            isNearMajorSupport = false,
            isVolatilityAbnormal = false,
            marketRegime = MarketRegime.TRENDING,
            mtfConfirmation = MtfConfirmation(
                status = MtfConfirmationStatus.ALIGNED,
                summary = "All timeframes aligned bullish",
                hasHigherTfData = true,
                hasLowerTfData = true
            )
        )
    }

    @Test
    fun `renderVisionBundle generates valid historical and recent chart base64 images`() {
        val candles = generateCandles(250)
        val snapshot = createDummySnapshot()

        val bundle = ChartVisionRenderer.renderVisionBundle(
            symbol = "BTCUSDT",
            timeframeLabel = "15m",
            candles = candles,
            snapshot = snapshot
        )

        assertNotNull("Vision bundle should not be null", bundle)
        assertTrue("Bundle should be available", bundle!!.isAvailable)
        assertEquals(200, bundle.candleCountHistorical)
        assertEquals(75, bundle.candleCountRecent)
        assertTrue("Historical image base64 should not be empty", bundle.historicalChartBase64.isNotBlank())
        assertTrue("Recent image base64 should not be empty", bundle.recentChartBase64.isNotBlank())
    }

    @Test
    fun `renderVisionBundle adapts gracefully when candle count is small`() {
        val smallCandles = generateCandles(45)

        val bundle = ChartVisionRenderer.renderVisionBundle(
            symbol = "ETHUSDT",
            timeframeLabel = "5m",
            candles = smallCandles
        )

        assertNotNull(bundle)
        assertTrue(bundle!!.isAvailable)
        assertEquals(45, bundle.candleCountHistorical)
        assertEquals(45, bundle.candleCountRecent)
        assertTrue(bundle.note.contains("Adapted to 45 available candles"))
        assertTrue(bundle.historicalChartBase64.isNotBlank())
        assertTrue(bundle.recentChartBase64.isNotBlank())
    }

    @Test
    fun `renderVisionBundle returns null for empty candle list`() {
        val bundle = ChartVisionRenderer.renderVisionBundle(
            symbol = "BTCUSDT",
            timeframeLabel = "1h",
            candles = emptyList()
        )
        assertNull("Empty candle list must return null bundle", bundle)
    }

    @Test
    fun `VisionCapabilityRegistry correctly verifies Gemini models for multimodal input`() {
        assertTrue(VisionCapabilityRegistry.supportsVision(AiProviderType.GEMINI_BUILTIN, "gemini-2.5-flash"))
        assertTrue(VisionCapabilityRegistry.supportsVision(AiProviderType.GEMINI_BUILTIN, "gemini-3.5-flash"))
        assertTrue(VisionCapabilityRegistry.supportsVision(AiProviderType.GEMINI_BUILTIN, "gemini-1.5-flash"))
        assertTrue(VisionCapabilityRegistry.supportsVision(AiProviderType.GEMINI_BUILTIN, "gemini-1.5-pro"))
        assertTrue(VisionCapabilityRegistry.supportsVision(AiProviderType.GEMINI_PERSONAL, "models/gemini-2.5-flash"))
    }

    @Test
    fun `VisionCapabilityRegistry correctly verifies OpenAI vision models`() {
        assertTrue(VisionCapabilityRegistry.supportsVision(AiProviderType.OPENAI, "gpt-4o"))
        assertTrue(VisionCapabilityRegistry.supportsVision(AiProviderType.OPENAI, "gpt-4o-mini"))
        assertTrue(VisionCapabilityRegistry.supportsVision(AiProviderType.OPENAI, "gpt-4-turbo"))
        assertTrue(VisionCapabilityRegistry.supportsVision(AiProviderType.OPENAI, "chatgpt-4o-latest"))

        // Text-only OpenAI models
        assertFalse(VisionCapabilityRegistry.supportsVision(AiProviderType.OPENAI, "gpt-3.5-turbo"))
        assertFalse(VisionCapabilityRegistry.supportsVision(AiProviderType.OPENAI, "o1-mini"))
    }

    @Test
    fun `VisionCapabilityRegistry rejects text-only OpenRouter models`() {
        assertFalse(VisionCapabilityRegistry.supportsVision(AiProviderType.OPENROUTER, "deepseek/deepseek-chat"))
        assertFalse(VisionCapabilityRegistry.supportsVision(AiProviderType.OPENROUTER, "deepseek/deepseek-r1"))
        assertFalse(VisionCapabilityRegistry.supportsVision(AiProviderType.OPENROUTER, "meta-llama/llama-3.3-70b-instruct"))

        // Verified multimodal on OpenRouter
        assertTrue(VisionCapabilityRegistry.supportsVision(AiProviderType.OPENROUTER, "google/gemini-2.5-flash"))
        assertTrue(VisionCapabilityRegistry.supportsVision(AiProviderType.OPENROUTER, "openai/gpt-4o"))
    }

    @Test
    fun `VisionCapabilityRegistry honors Custom API vision toggle`() {
        // When toggle is off and model is not an explicit gpt-4o vision model
        assertFalse(VisionCapabilityRegistry.supportsVision(AiProviderType.CUSTOM_OPENAI, "my-local-llama", customApiSupportsVision = false))

        // When toggle is explicitly turned on by the user
        assertTrue(VisionCapabilityRegistry.supportsVision(AiProviderType.CUSTOM_OPENAI, "my-local-llama", customApiSupportsVision = true))
    }

    @Test
    fun `AiPromptEngine appends hybrid vision instructions when bundle is present`() {
        val bundle = ChartVisionBundle(
            historicalChartBase64 = "dummyBase64Hist",
            recentChartBase64 = "dummyBase64Recent",
            candleCountHistorical = 200,
            candleCountRecent = 75,
            isAvailable = true
        )

        val prompt = AiPromptEngine.buildSystemPrompt(visionBundle = bundle)

        assertTrue(prompt.contains("HYBRID CHART VISION PERSPECTIVES"))
        assertTrue(prompt.contains("200c Historical & 75c Recent"))
        assertTrue(prompt.contains("AUTHORITATIVE source for exact prices"))
        assertTrue(prompt.contains("disclose the conflict in your reasoning bullets and default to conservative WAIT"))
    }

    @Test
    fun `AiPromptEngine preserves clean text-only prompt when bundle is null`() {
        val prompt = AiPromptEngine.buildSystemPrompt(visionBundle = null)
        assertFalse(prompt.contains("HYBRID CHART VISION PERSPECTIVES"))
        assertTrue(prompt.contains("VALID DECISIONS"))
    }

    @Test
    fun `AiAnalysisResult exposes correct analysisModeBadge for hybrid vs technical only`() {
        val hybridResult = AiAnalysisResult(
            decision = "LONG",
            bias = "BULLISH",
            confidence = 82,
            trend = "BULLISH",
            momentum = "POSITIVE",
            marketStructure = "HH_HL",
            setupQuality = "A",
            entryZone = EntryZone(50_000.0, 50_100.0),
            stopLoss = 49_500.0,
            takeProfit = listOf(50_800.0, 51_500.0),
            riskReward = 1.6,
            support = listOf(49_500.0),
            resistance = listOf(51_500.0),
            higherTimeframeBias = "BULLISH",
            primaryTimeframeBias = "BULLISH",
            lowerTimeframeBias = "PULLBACK",
            invalidIf = "Close below 49500",
            reasoning = listOf("Visual sweep and EMA hold"),
            summary = "Bullish continuation",
            rawJson = "{}",
            visionAssisted = true,
            visionAssistedSummary = "Analyzed with 200c historical & 75c recent chart vision"
        )
        assertEquals("👁️ Hybrid Vision + Technical", hybridResult.analysisModeBadge)
        assertTrue(hybridResult.visionAssisted)

        val techOnlyResult = hybridResult.copy(
            visionAssisted = false,
            visionAssistedSummary = ""
        )
        assertEquals("📊 Technical Only", techOnlyResult.analysisModeBadge)
        assertFalse(techOnlyResult.visionAssisted)
    }

    @Test
    fun `SignalValidationEngine enforces strict trade geometry on vision-assisted setups`() {
        val snapshot = createDummySnapshot(currentPrice = 50_000.0)
        val context = AiAnalysisRequestContext()

        // Setup with inverted stop loss (SL above entry for LONG)
        val invalidLong = AiAnalysisResult(
            decision = "LONG",
            bias = "BULLISH",
            confidence = 85,
            trend = "BULLISH",
            momentum = "POSITIVE",
            marketStructure = "HH_HL",
            setupQuality = "A+",
            entryZone = EntryZone(50_000.0, 50_200.0),
            stopLoss = 51_000.0, // Invalid: above entry!
            takeProfit = listOf(52_000.0),
            riskReward = 2.0,
            support = listOf(49_000.0),
            resistance = listOf(52_000.0),
            higherTimeframeBias = "BULLISH",
            primaryTimeframeBias = "BULLISH",
            lowerTimeframeBias = "BULLISH",
            invalidIf = "Inversion",
            reasoning = listOf("Strong bullish bias"),
            summary = "Invalid geometry",
            rawJson = "{}",
            visionAssisted = true,
            visionAssistedSummary = "Analyzed with chart vision"
        )

        val validated = SignalValidationEngine.validateAndNormalize(invalidLong, snapshot, context)

        // Must reject to WAIT with clean reason and preserve visionAssisted attribution
        assertEquals("WAIT", validated.decision)
        assertEquals("WAIT", validated.setupQualityGrade)
        assertTrue(validated.visionAssisted)
        assertTrue("Should reject due to stop loss above entry", validated.reasoning.any { it.contains("stop loss", ignoreCase = true) })
    }

    @Test
    fun `ChartVisionMode enum defaults and parsing work correctly`() {
        assertEquals(ChartVisionMode.AUTO, ChartVisionMode.fromCode("AUTO"))
        assertEquals(ChartVisionMode.OFF, ChartVisionMode.fromCode("OFF"))
        assertEquals(ChartVisionMode.AUTO, ChartVisionMode.fromCode(null))
        assertEquals(ChartVisionMode.AUTO, ChartVisionMode.fromCode("UNKNOWN"))
        assertEquals("Auto when supported", ChartVisionMode.AUTO.displayLabel)
        assertEquals("Off", ChartVisionMode.OFF.displayLabel)
    }
}
