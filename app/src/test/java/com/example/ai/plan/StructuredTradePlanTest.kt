package com.example.ai.plan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.analyst.AiAnalysisRequestContext
import com.example.ai.analyst.BuiltInAIProvider
import com.example.ai.parser.AiResponseParser
import com.example.ai.prompt.AiPromptEngine
import com.example.ai.strategy.AiStrategy
import com.example.ai.vision.ChartVisionBundle
import com.example.analysis.marketStructure.MarketStructureAnalysis
import com.example.analysis.marketStructure.StructureState
import com.example.analysis.signal.SignalValidationEngine
import com.example.analysis.signal.TradePlanValidationEngine
import com.example.analysis.technical.CandidateTradeLevels
import com.example.analysis.technical.MarketRegime
import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.analysis.technical.MtfConfirmation
import com.example.analysis.technical.MtfConfirmationStatus
import com.example.market.candle.Candle
import com.example.market.candle.Timeframe
import com.example.storage.preferences.TradingStyleProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StructuredTradePlanTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
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
            riskRewardTp1 = 2.0,
            riskRewardTp2 = 3.6
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

    // 1. BUY MARKET and SELL MARKET Validation
    @Test
    fun `validatePlan approves valid BUY MARKET and SELL MARKET setups`() {
        // BUY MARKET: price 60000 is inside entry zone 59950 - 60050, SL below, TP above
        val buyMarket = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.MARKET,
            setupStatus = TradeSetupStatus.ACTIONABLE,
            referencePrice = 60_000.0,
            entryZoneMin = 59_950.0,
            entryZoneMax = 60_050.0,
            stopLoss = 59_500.0,
            tp1 = 61_200.0,
            tp2 = 62_000.0,
            tp3 = 63_000.0
        )

        val buyOutcome = TradePlanValidationEngine.validatePlan(buyMarket, minRequiredRr = 1.35)
        assertTrue(buyOutcome.isValid)
        assertEquals(TradeSetupStatus.ACTIONABLE, buyOutcome.sanitizedPlan.setupStatus)
        assertEquals(TradeDirection.BUY, buyOutcome.sanitizedPlan.direction)
        assertEquals(TradeOrderType.MARKET, buyOutcome.sanitizedPlan.orderType)
        assertTrue(buyOutcome.sanitizedPlan.riskRewardTp1!! >= 1.35)
        assertEquals("BUY MARKET", buyOutcome.sanitizedPlan.orderActionLabel)
        assertEquals(BrokerVerificationStatus.UNVERIFIED_BROKER_DATA, buyOutcome.sanitizedPlan.brokerVerificationStatus)

        // SELL MARKET: price 60000 is inside entry zone 59950 - 60050, SL above, TP below
        val sellMarket = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BEARISH",
            direction = TradeDirection.SELL,
            orderType = TradeOrderType.MARKET,
            setupStatus = TradeSetupStatus.ACTIONABLE,
            referencePrice = 60_000.0,
            entryZoneMin = 59_950.0,
            entryZoneMax = 60_050.0,
            stopLoss = 60_550.0,
            tp1 = 58_800.0,
            tp2 = 58_000.0
        )

        val sellOutcome = TradePlanValidationEngine.validatePlan(sellMarket, minRequiredRr = 1.35)
        assertTrue(sellOutcome.isValid)
        assertEquals(TradeSetupStatus.ACTIONABLE, sellOutcome.sanitizedPlan.setupStatus)
        assertEquals(TradeDirection.SELL, sellOutcome.sanitizedPlan.direction)
        assertEquals(TradeOrderType.MARKET, sellOutcome.sanitizedPlan.orderType)
        assertEquals("SELL MARKET", sellOutcome.sanitizedPlan.orderActionLabel)
    }

    // 2. BUY LIMIT and SELL LIMIT Geometry
    @Test
    fun `validatePlan approves valid BUY LIMIT strictly below reference price and SELL LIMIT strictly above`() {
        val refPrice = 60_000.0

        // BUY LIMIT: entry zone 59400 - 59500 is strictly below 60000
        val buyLimit = StructuredTradePlan(
            strategy = AiStrategy.AI_SMC,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.LIMIT,
            setupStatus = TradeSetupStatus.PENDING,
            referencePrice = refPrice,
            entryZoneMin = 59_400.0,
            entryZoneMax = 59_500.0,
            stopLoss = 59_100.0,
            tp1 = 60_500.0
        )

        val buyLimitOutcome = TradePlanValidationEngine.validatePlan(buyLimit, minRequiredRr = 1.35)
        assertTrue(buyLimitOutcome.isValid)
        assertEquals(TradeSetupStatus.PENDING, buyLimitOutcome.sanitizedPlan.setupStatus)
        assertEquals("BUY LIMIT", buyLimitOutcome.sanitizedPlan.orderActionLabel)
        assertTrue(buyLimitOutcome.sanitizedPlan.isPending)

        // SELL LIMIT: entry zone 60500 - 60600 is strictly above 60000
        val sellLimit = StructuredTradePlan(
            strategy = AiStrategy.AI_ICT,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BEARISH",
            direction = TradeDirection.SELL,
            orderType = TradeOrderType.LIMIT,
            setupStatus = TradeSetupStatus.PENDING,
            referencePrice = refPrice,
            entryZoneMin = 60_500.0,
            entryZoneMax = 60_600.0,
            stopLoss = 61_000.0,
            tp1 = 59_500.0
        )

        val sellLimitOutcome = TradePlanValidationEngine.validatePlan(sellLimit, minRequiredRr = 1.35)
        assertTrue(sellLimitOutcome.isValid)
        assertEquals(TradeSetupStatus.PENDING, sellLimitOutcome.sanitizedPlan.setupStatus)
        assertEquals("SELL LIMIT", sellLimitOutcome.sanitizedPlan.orderActionLabel)
    }

    @Test
    fun `validatePlan rejects invalid BUY LIMIT above price or SELL LIMIT below price`() {
        val refPrice = 60_000.0

        // Invalid BUY LIMIT with entry zone above reference price
        val badBuyLimit = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.LIMIT,
            setupStatus = TradeSetupStatus.PENDING,
            referencePrice = refPrice,
            entryZoneMin = 60_200.0,
            entryZoneMax = 60_400.0,
            stopLoss = 59_800.0,
            tp1 = 61_500.0
        )
        val badBuyOutcome = TradePlanValidationEngine.validatePlan(badBuyLimit)
        assertFalse(badBuyOutcome.isValid)
        assertEquals(TradeSetupStatus.INVALID, badBuyOutcome.sanitizedPlan.setupStatus)
        assertTrue(badBuyOutcome.rejectionReasons.any { it.contains("BUY LIMIT entry zone") })

        // Invalid SELL LIMIT with entry zone below reference price
        val badSellLimit = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BEARISH",
            direction = TradeDirection.SELL,
            orderType = TradeOrderType.LIMIT,
            setupStatus = TradeSetupStatus.PENDING,
            referencePrice = refPrice,
            entryZoneMin = 59_200.0,
            entryZoneMax = 59_400.0,
            stopLoss = 59_800.0,
            tp1 = 58_000.0
        )
        val badSellOutcome = TradePlanValidationEngine.validatePlan(badSellLimit)
        assertFalse(badSellOutcome.isValid)
        assertEquals(TradeSetupStatus.INVALID, badSellOutcome.sanitizedPlan.setupStatus)
        assertTrue(badSellOutcome.rejectionReasons.any { it.contains("SELL LIMIT entry zone") })
    }

    // 3. BUY STOP and SELL STOP Geometry
    @Test
    fun `validatePlan approves valid BUY STOP breakout strictly above price and SELL STOP breakdown strictly below price`() {
        val refPrice = 60_000.0

        // BUY STOP: breakout above resistance at 60500 - 60600
        val buyStop = StructuredTradePlan(
            strategy = AiStrategy.AI_PRICE_ACTION,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.STOP,
            setupStatus = TradeSetupStatus.PENDING,
            referencePrice = refPrice,
            entryZoneMin = 60_500.0,
            entryZoneMax = 60_600.0,
            stopLoss = 60_200.0,
            tp1 = 61_500.0
        )
        val buyStopOutcome = TradePlanValidationEngine.validatePlan(buyStop, minRequiredRr = 1.35)
        assertTrue(buyStopOutcome.isValid)
        assertEquals(TradeSetupStatus.PENDING, buyStopOutcome.sanitizedPlan.setupStatus)
        assertEquals("BUY STOP", buyStopOutcome.sanitizedPlan.orderActionLabel)

        // SELL STOP: breakdown below support at 59400 - 59500
        val sellStop = StructuredTradePlan(
            strategy = AiStrategy.AI_PRICE_ACTION,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BEARISH",
            direction = TradeDirection.SELL,
            orderType = TradeOrderType.STOP,
            setupStatus = TradeSetupStatus.PENDING,
            referencePrice = refPrice,
            entryZoneMin = 59_400.0,
            entryZoneMax = 59_500.0,
            stopLoss = 59_800.0,
            tp1 = 58_500.0
        )
        val sellStopOutcome = TradePlanValidationEngine.validatePlan(sellStop, minRequiredRr = 1.35)
        assertTrue(sellStopOutcome.isValid)
        assertEquals(TradeSetupStatus.PENDING, sellStopOutcome.sanitizedPlan.setupStatus)
        assertEquals("SELL STOP", sellStopOutcome.sanitizedPlan.orderActionLabel)
    }

    @Test
    fun `validatePlan rejects invalid BUY STOP below price or SELL STOP above price`() {
        val refPrice = 60_000.0

        // BUY STOP incorrectly placed below reference price
        val badBuyStop = StructuredTradePlan(
            strategy = AiStrategy.AI_PRICE_ACTION,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.STOP,
            setupStatus = TradeSetupStatus.PENDING,
            referencePrice = refPrice,
            entryZoneMin = 59_500.0,
            entryZoneMax = 59_600.0,
            stopLoss = 59_200.0,
            tp1 = 60_500.0
        )
        val outcome = TradePlanValidationEngine.validatePlan(badBuyStop)
        assertFalse(outcome.isValid)
        assertTrue(outcome.rejectionReasons.any { it.contains("BUY STOP breakout entry zone") })

        // SELL STOP incorrectly placed above reference price
        val badSellStop = StructuredTradePlan(
            strategy = AiStrategy.AI_PRICE_ACTION,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BEARISH",
            direction = TradeDirection.SELL,
            orderType = TradeOrderType.STOP,
            setupStatus = TradeSetupStatus.PENDING,
            referencePrice = refPrice,
            entryZoneMin = 60_400.0,
            entryZoneMax = 60_500.0,
            stopLoss = 60_800.0,
            tp1 = 59_500.0
        )
        val sellOutcome = TradePlanValidationEngine.validatePlan(badSellStop)
        assertFalse(sellOutcome.isValid)
        assertTrue(sellOutcome.rejectionReasons.any { it.contains("SELL STOP breakdown entry zone") })
    }

    // 4. Valid and Invalid Entry Zones
    @Test
    fun `validatePlan rejects inverted or non-positive entry zones`() {
        val invertedZone = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.MARKET,
            setupStatus = TradeSetupStatus.ACTIONABLE,
            referencePrice = 60_000.0,
            entryZoneMin = 60_200.0,
            entryZoneMax = 59_800.0, // Inverted!
            stopLoss = 59_000.0,
            tp1 = 61_500.0
        )
        val invertedOutcome = TradePlanValidationEngine.validatePlan(invertedZone)
        assertFalse(invertedOutcome.isValid)
        assertTrue(invertedOutcome.rejectionReasons.any { it.contains("Entry zone inverted") })

        val negativeZone = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.MARKET,
            setupStatus = TradeSetupStatus.ACTIONABLE,
            referencePrice = 60_000.0,
            entryZoneMin = -59_800.0, // Negative!
            entryZoneMax = 60_100.0,
            stopLoss = 59_000.0,
            tp1 = 61_500.0
        )
        val negativeOutcome = TradePlanValidationEngine.validatePlan(negativeZone)
        assertFalse(negativeOutcome.isValid)
        assertTrue(negativeOutcome.rejectionReasons.any { it.contains("finite positive number") })
    }

    // 5. Incorrect Stop Loss Geometry
    @Test
    fun `validatePlan rejects stop loss on wrong side of entry zone`() {
        // BUY with stop loss above entryZoneMin
        val badBuySl = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.MARKET,
            setupStatus = TradeSetupStatus.ACTIONABLE,
            referencePrice = 60_000.0,
            entryZoneMin = 59_900.0,
            entryZoneMax = 60_100.0,
            stopLoss = 59_950.0, // Inside entry zone!
            tp1 = 61_500.0
        )
        val buyOutcome = TradePlanValidationEngine.validatePlan(badBuySl)
        assertFalse(buyOutcome.isValid)
        assertTrue(buyOutcome.rejectionReasons.any { it.contains("BUY stop loss") && it.contains("strictly below") })

        // SELL with stop loss below entryZoneMax
        val badSellSl = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BEARISH",
            direction = TradeDirection.SELL,
            orderType = TradeOrderType.MARKET,
            setupStatus = TradeSetupStatus.ACTIONABLE,
            referencePrice = 60_000.0,
            entryZoneMin = 59_900.0,
            entryZoneMax = 60_100.0,
            stopLoss = 60_050.0, // Inside entry zone!
            tp1 = 58_500.0
        )
        val sellOutcome = TradePlanValidationEngine.validatePlan(badSellSl)
        assertFalse(sellOutcome.isValid)
        assertTrue(sellOutcome.rejectionReasons.any { it.contains("SELL stop loss") && it.contains("strictly above") })
    }

    // 6. Incorrect Take Profit Geometry
    @Test
    fun `validatePlan rejects take profit on wrong side of entry zone or non-monotonic sequence`() {
        // BUY with TP1 below entryZoneMax
        val badBuyTp = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.MARKET,
            setupStatus = TradeSetupStatus.ACTIONABLE,
            referencePrice = 60_000.0,
            entryZoneMin = 59_900.0,
            entryZoneMax = 60_100.0,
            stopLoss = 59_400.0,
            tp1 = 60_050.0 // Below max entry!
        )
        val buyOutcome = TradePlanValidationEngine.validatePlan(badBuyTp)
        assertFalse(buyOutcome.isValid)
        assertTrue(buyOutcome.rejectionReasons.any { it.contains("BUY TP1") && it.contains("strictly above") })

        // BUY with non-ascending TPs (TP2 <= TP1)
        val badBuySequence = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.MARKET,
            setupStatus = TradeSetupStatus.ACTIONABLE,
            referencePrice = 60_000.0,
            entryZoneMin = 59_900.0,
            entryZoneMax = 60_100.0,
            stopLoss = 59_400.0,
            tp1 = 61_500.0,
            tp2 = 61_200.0 // Decreasing!
        )
        val seqOutcome = TradePlanValidationEngine.validatePlan(badBuySequence)
        assertFalse(seqOutcome.isValid)
        assertTrue(seqOutcome.rejectionReasons.any { it.contains("BUY TP2") && it.contains("strictly above") })
    }

    // 7. Malformed AI Responses
    @Test
    fun `AiResponseParser handles malformed or non-finite numbers safely`() {
        // Malformed JSON with NaN prices
        val nanJson = """
            {
              "decision": "LONG",
              "confidence": 80,
              "trend": "BULLISH",
              "entryZone": { "min": "NaN", "max": 60100.0 },
              "stopLoss": 59500.0,
              "takeProfit": [61500.0]
            }
        """.trimIndent()

        val parsedNan = AiResponseParser.parse(nanJson)
        assertNull(parsedNan) // Must reject malformed numbers completely

        // Negative stop loss
        val negSlJson = """
            {
              "decision": "LONG",
              "confidence": 80,
              "trend": "BULLISH",
              "entryZone": { "min": 59900.0, "max": 60100.0 },
              "stopLoss": -500.0,
              "takeProfit": [61500.0]
            }
        """.trimIndent()
        val parsedNeg = AiResponseParser.parse(negSlJson)
        assertNull(parsedNeg)
    }

    // 8. Missing Reference Prices
    @Test
    fun `TradePlanValidationEngine rejects missing or non-positive reference prices`() {
        val missingRefPlan = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.MARKET,
            setupStatus = TradeSetupStatus.ACTIONABLE,
            referencePrice = 0.0, // Non-positive reference price!
            entryZoneMin = 59_900.0,
            entryZoneMax = 60_100.0,
            stopLoss = 59_400.0,
            tp1 = 61_500.0
        )
        val outcome = TradePlanValidationEngine.validatePlan(missingRefPlan)
        assertFalse(outcome.isValid)
        assertTrue(outcome.rejectionReasons.any { it.contains("Reference price") })
    }

    // 9. Legacy LONG, SHORT, WAIT Compatibility
    @Test
    fun `AiResponseParser maps legacy LONG, SHORT, WAIT responses seamlessly to structured trade plans`() {
        // Legacy LONG response without V3.1A fields
        val legacyLongJson = """
            {
              "decision": "LONG",
              "confidence": 85,
              "trend": "BULLISH",
              "setupQuality": "A",
              "entryOrderType": "LIMIT",
              "entryZone": { "min": 59900.0, "max": 60000.0 },
              "stopLoss": 59400.0,
              "takeProfit": [61200.0, 62000.0],
              "reasoning": ["Bullish trend continuation"],
              "summary": "Valid long pullback"
            }
        """.trimIndent()

        val parsedLong = AiResponseParser.parse(legacyLongJson)
        assertNotNull(parsedLong)
        assertEquals("LONG", parsedLong!!.decision)
        assertNotNull(parsedLong.tradePlan)
        assertEquals(TradeDirection.BUY, parsedLong.tradePlan!!.direction)
        assertEquals(TradeOrderType.LIMIT, parsedLong.tradePlan!!.orderType)
        assertEquals(TradeSetupStatus.PENDING, parsedLong.tradePlan!!.setupStatus)

        // Legacy WAIT response
        val legacyWaitJson = """
            {
              "decision": "WAIT",
              "confidence": 40,
              "trend": "SIDEWAYS",
              "reasoning": ["Market is choppy"],
              "summary": "No edge detected"
            }
        """.trimIndent()

        val parsedWait = AiResponseParser.parse(legacyWaitJson)
        assertNotNull(parsedWait)
        assertEquals("WAIT", parsedWait!!.decision)
        assertNotNull(parsedWait.tradePlan)
        assertEquals(TradeDirection.NEUTRAL, parsedWait.tradePlan!!.direction)
        assertEquals(TradeOrderType.NONE, parsedWait.tradePlan!!.orderType)
        assertEquals(TradeSetupStatus.WAIT, parsedWait.tradePlan!!.setupStatus)
        assertNull(parsedWait.tradePlan!!.entryZoneMin)
    }

    // 10. Strategy-Specific Trade Plan Requests
    @Test
    fun `AiPromptEngine injects structured trade plan guidelines and strategy directives`() {
        val sniperPrompt = AiPromptEngine.buildSystemPrompt(
            selectedAiStrategy = AiStrategy.AI_SNIPER
        )
        assertTrue(sniperPrompt.contains("STRUCTURED TRADE PLAN ARCHITECTURE"))
        assertTrue(sniperPrompt.contains("BUY MARKET"))
        assertTrue(sniperPrompt.contains("BUY LIMIT"))
        assertTrue(sniperPrompt.contains("BUY STOP"))
        assertTrue(sniperPrompt.contains("SELL MARKET"))
        assertTrue(sniperPrompt.contains("SELL LIMIT"))
        assertTrue(sniperPrompt.contains("SELL STOP"))
        assertTrue(sniperPrompt.contains("STRATEGY DIRECTIVE: [AI_SNIPER] AI Sniper"))

        // Sniper enforces min R:R 2.0 in validation
        assertEquals(2.0, TradePlanValidationEngine.resolveMinimumRiskReward(AiStrategy.AI_SNIPER), 1e-6)
    }

    // 11. Chart Vision and Technical-Only Paths
    @Test
    fun `AiPromptEngine formats prompts correctly for both Chart Vision and Technical-Only paths`() {
        val dummyVisionBundle = ChartVisionBundle(
            historicalChartBase64 = "base64_historical_img",
            recentChartBase64 = "base64_recent_img",
            candleCountHistorical = 200,
            candleCountRecent = 75
        )

        // Vision path
        val visionPrompt = AiPromptEngine.buildSystemPrompt(
            selectedAiStrategy = AiStrategy.AI_HYBRID,
            visionBundle = dummyVisionBundle
        )
        assertTrue(visionPrompt.contains("HYBRID CHART VISION PERSPECTIVES"))
        assertTrue(visionPrompt.contains("STRUCTURED TRADE PLAN ARCHITECTURE"))

        // Technical-only path
        val techOnlyPrompt = AiPromptEngine.buildSystemPrompt(
            selectedAiStrategy = AiStrategy.AI_HYBRID,
            visionBundle = null
        )
        assertFalse(techOnlyPrompt.contains("HYBRID CHART VISION PERSPECTIVES"))
        assertTrue(techOnlyPrompt.contains("STRUCTURED TRADE PLAN ARCHITECTURE"))
    }

    // 12. Provider Failure and Fallback Behavior
    @Test
    fun `BuiltInAIProvider handles local fallback producing valid structured trade plan`() {
        val snapshot = createDummySnapshot()
        val provider = BuiltInAIProvider()
        val context = AiAnalysisRequestContext(
            selectedAiStrategy = AiStrategy.AI_HYBRID,
            tradingStyleProfile = TradingStyleProfile.DAY_TRADING
        )

        val localResult = provider.synthesizeV2MultiTimeframeAnalysis(snapshot, "AI · Primary", "built-in-v2", context)
        assertNotNull(localResult)
        assertNotNull(localResult!!.tradePlan)
        // Verified by SignalValidationEngine
        val validated = SignalValidationEngine.validateAndNormalize(localResult, snapshot, context)
        assertNotNull(validated.tradePlan)
        assertEquals(BrokerVerificationStatus.UNVERIFIED_BROKER_DATA, validated.tradePlan!!.brokerVerificationStatus)
    }
}
