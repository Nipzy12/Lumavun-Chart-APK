package com.example.analysis.signal

import com.example.ai.analyst.AiAnalysisRequestContext
import com.example.ai.parser.AiAnalysisResult
import com.example.ai.parser.EntryZone
import com.example.analysis.marketStructure.MarketStructureAnalysis
import com.example.analysis.marketStructure.StructureState
import com.example.analysis.technical.MarketRegime
import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.analysis.technical.MtfConfirmation
import com.example.analysis.technical.MtfConfirmationStatus
import com.example.analysis.technical.TimeframeContextSummary
import com.example.market.candle.Candle
import com.example.market.candle.Timeframe
import com.example.storage.preferences.TradingStyleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalValidationEngineTest {

    private fun createSampleCandles(count: Int = 30, baseTime: Long = 1_000_000L): List<Candle> {
        val candles = mutableListOf<Candle>()
        for (i in 0 until count) {
            val t = baseTime + (i * 3_600_000L)
            candles.add(
                Candle(
                    openTime = t,
                    open = 94000.0 + i * 10,
                    high = 94500.0 + i * 10,
                    low = 93800.0 + i * 10,
                    close = 94300.0 + i * 10,
                    volume = 1500.0,
                    closeTime = t + 3_600_000L - 1L,
                    isClosed = true
                )
            )
        }
        return candles
    }

    private fun createSampleSnapshot(
        currentPrice: Double = 94250.0,
        candles: List<Candle> = createSampleCandles(),
        marketRegime: MarketRegime = MarketRegime.TRENDING,
        mtfStatus: MtfConfirmationStatus = MtfConfirmationStatus.ALIGNED,
        htfBias: String = "BULLISH",
        ptfBias: String = "BULLISH",
        isVolatilityAbnormal: Boolean = false,
        latestCandleOpenTime: Long = 1_000_000L + (30 * 3_600_000L)
    ): MarketTechnicalSnapshot {
        val latestCandle = candles.lastOrNull()?.copy(openTime = latestCandleOpenTime) ?: Candle(
            openTime = latestCandleOpenTime,
            open = currentPrice,
            high = currentPrice + 200,
            low = currentPrice - 200,
            close = currentPrice,
            volume = 1000.0,
            closeTime = latestCandleOpenTime + 3_600_000L - 1L
        )

        val mtf = MtfConfirmation(
            status = mtfStatus,
            summary = "HTF:$htfBias | Primary:$ptfBias",
            hasHigherTfData = true,
            hasLowerTfData = true
        )

        val dummyStructure = MarketStructureAnalysis(
            state = if (ptfBias == "BULLISH") StructureState.BULLISH else if (ptfBias == "BEARISH") StructureState.BEARISH else StructureState.SIDEWAYS,
            schemaCode = if (ptfBias == "BULLISH") "HH_HL" else "SIDEWAYS",
            swingSummary = "Clear swings",
            biasLabel = ptfBias,
            lastHighTag = null,
            lastLowTag = null,
            lastSwingHighPrice = currentPrice + 1000,
            lastSwingLowPrice = currentPrice - 1000,
            recentSwings = emptyList()
        )

        val primaryContext = TimeframeContextSummary(
            timeframe = Timeframe.H1,
            currentPrice = currentPrice,
            trend = ptfBias,
            marketCondition = ptfBias,
            structureCode = "HH_HL",
            emaAlignment = if (ptfBias == "BULLISH") "BULLISH" else "MIXED",
            ema20 = currentPrice - 100,
            ema50 = currentPrice - 300,
            ema200 = currentPrice - 1000,
            rsi14 = 58.0,
            macdState = "BULLISH",
            macdHistogram = 15.0,
            volumeCondition = "NORMAL",
            atr14 = 400.0,
            volatilityState = if (isVolatilityAbnormal) "ABNORMAL" else "NORMAL",
            breakoutPullbackCondition = "NORMAL",
            supportLevels = listOf(currentPrice - 800),
            resistanceLevels = listOf(currentPrice + 1500),
            lastSwingHigh = currentPrice + 1000,
            lastSwingLow = currentPrice - 800,
            latestCandleCloseTime = latestCandle.closeTime
        )

        val higherContext = TimeframeContextSummary(
            timeframe = Timeframe.H4,
            currentPrice = currentPrice,
            trend = htfBias,
            marketCondition = htfBias,
            structureCode = "HH_HL",
            emaAlignment = if (htfBias == "BULLISH") "BULLISH" else "BEARISH",
            ema20 = currentPrice - 200,
            ema50 = currentPrice - 500,
            ema200 = currentPrice - 1500,
            rsi14 = 60.0,
            macdState = "BULLISH",
            macdHistogram = 25.0,
            volumeCondition = "NORMAL",
            atr14 = 1200.0,
            volatilityState = "NORMAL",
            breakoutPullbackCondition = "NORMAL",
            supportLevels = listOf(currentPrice - 1500),
            resistanceLevels = listOf(currentPrice + 3000),
            lastSwingHigh = currentPrice + 2000,
            lastSwingLow = currentPrice - 1500,
            latestCandleCloseTime = latestCandle.closeTime
        )

        return MarketTechnicalSnapshot(
            symbol = "BTCUSDT",
            timeframe = Timeframe.H1,
            currentPrice = currentPrice,
            latestCandle = latestCandle,
            recentCandles = candles,
            ema20 = currentPrice - 100,
            ema50 = currentPrice - 300,
            ema200 = currentPrice - 1000,
            rsi14 = 58.0,
            macd = null,
            atr14 = 400.0,
            currentVolume = 1200.0,
            volumeSma20 = 1000.0,
            supportLevels = listOf(currentPrice - 800),
            resistanceLevels = listOf(currentPrice + 1500),
            marketStructure = dummyStructure,
            hasEmaConflict = false,
            hasRsiConflict = false,
            hasMacdConflict = false,
            isVolumeWeak = false,
            isNearMajorResistance = false,
            isNearMajorSupport = false,
            isVolatilityAbnormal = isVolatilityAbnormal,
            marketRegime = marketRegime,
            mtfConfirmation = mtf,
            primaryContext = primaryContext,
            higherContext = higherContext,
            lowerContext = null
        )
    }

    private fun createCandidateResult(
        decision: String = "LONG",
        entryLow: Double = 94000.0,
        entryHigh: Double = 94500.0,
        stopLoss: Double = 92500.0,
        takeProfit: List<Double> = listOf(98000.0, 102000.0),
        confidence: Int = 85,
        setupQuality: String = "A+",
        reportedRr: Double = 2.0,
        reasoning: List<String> = listOf("MTF Trend aligned", "Liquidity swept and reclaimed"),
        summary: String = "Valid high probability setup"
    ): AiAnalysisResult {
        return AiAnalysisResult(
            decision = decision,
            bias = if (decision == "LONG") "BULLISH" else if (decision == "SHORT") "BEARISH" else "NEUTRAL",
            confidence = confidence,
            trend = "BULLISH",
            momentum = "POSITIVE",
            marketStructure = "HH_HL",
            setupQuality = setupQuality,
            entryZone = EntryZone(entryLow, entryHigh),
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            riskReward = reportedRr,
            support = listOf(93000.0),
            resistance = listOf(99000.0),
            higherTimeframeBias = "BULLISH",
            primaryTimeframeBias = "BULLISH",
            lowerTimeframeBias = "BULLISH",
            invalidIf = "Break below stop loss",
            reasoning = reasoning,
            summary = summary,
            rawJson = "{}"
        )
    }

    // 1. Valid LONG & SHORT setups
    @Test
    fun `validate valid LONG setup passes validation with recomputed risk reward`() {
        val snapshot = createSampleSnapshot(currentPrice = 94250.0)
        val validLong = createCandidateResult(
            decision = "LONG",
            entryLow = 94000.0,
            entryHigh = 94500.0,
            stopLoss = 92500.0,
            takeProfit = listOf(98000.0, 102000.0)
        )

        val outcome = SignalValidationEngine.validate(
            result = validLong,
            snapshot = snapshot,
            currentTimeMs = snapshot.latestCandle.openTime + 60_000L
        )

        assertTrue("Valid LONG must pass validation", outcome.isValid)
        assertEquals("LONG", outcome.validatedResult.decision)
        assertEquals(94000.0, outcome.validatedResult.entryZone.low, 0.001)
        assertEquals(94500.0, outcome.validatedResult.entryZone.high, 0.001)
        assertEquals(92500.0, outcome.validatedResult.stopLoss, 0.001)
        // Worst BUY fill: 94500, Risk: 2000, Reward: 3500 => R:R = 1.75
        assertEquals(1.75, outcome.recomputedRiskReward, 0.001)
        assertEquals(1.75, outcome.validatedResult.riskReward, 0.001)
        assertEquals(1.75, outcome.validatedResult.tradePlan!!.riskRewardTp1!!, 0.001)
        assertEquals("A", outcome.validatedResult.setupQuality)
        assertTrue(outcome.rejectionReasons.isEmpty())
    }

    @Test
    fun `validate valid SHORT setup passes validation with recomputed risk reward`() {
        val snapshot = createSampleSnapshot(
            currentPrice = 94250.0,
            htfBias = "BEARISH",
            ptfBias = "BEARISH"
        )
        val validShort = createCandidateResult(
            decision = "SHORT",
            entryLow = 94000.0,
            entryHigh = 94500.0,
            stopLoss = 96000.0,
            takeProfit = listOf(91000.0, 88000.0)
        )

        val outcome = SignalValidationEngine.validate(
            result = validShort,
            snapshot = snapshot,
            currentTimeMs = snapshot.latestCandle.openTime + 60_000L
        )

        assertTrue("Valid SHORT must pass validation", outcome.isValid)
        assertEquals("SHORT", outcome.validatedResult.decision)
        assertEquals(96000.0, outcome.validatedResult.stopLoss, 0.001)
        // Worst SELL fill: 94000, Risk: 2000, Reward: 3000 => R:R = 1.50
        assertEquals(1.50, outcome.recomputedRiskReward, 0.001)
        assertEquals(1.50, outcome.validatedResult.riskReward, 0.001)
        assertEquals(1.50, outcome.validatedResult.tradePlan!!.riskRewardTp1!!, 0.001)
        assertEquals("B", outcome.validatedResult.setupQuality)
    }

    @Test
    fun `geometry rejects midpoint-only risk reward for both directions`() {
        val buy = SignalValidationEngine.validateTradeGeometry(
            "LONG", 100.0, 110.0, 90.0, listOf(130.0)
        )
        val sell = SignalValidationEngine.validateTradeGeometry(
            "SHORT", 100.0, 110.0, 120.0, listOf(80.0)
        )
        assertFalse(buy.first)
        assertFalse(sell.first)
        assertTrue(buy.second.any { it.contains("worst-case R:R") })
        assertTrue(sell.second.any { it.contains("worst-case R:R") })
    }

    @Test
    fun `geometry rejects risk reward rounded above its actual threshold`() {
        val result = SignalValidationEngine.validateTradeGeometry(
            "LONG", 100.0, 100.0, 90.0, listOf(113.49)
        )
        assertFalse(result.first)
    }

    @Test
    fun `directional result cannot carry a contradictory neutral or modified structured plan`() {
        val snapshot = createSampleSnapshot(currentPrice = 94250.0)
        val now = snapshot.latestCandle.openTime + 60_000L
        val candidate = createCandidateResult()
        val valid = SignalValidationEngine.validate(candidate, snapshot, currentTimeMs = now)
        assertTrue(valid.isValid)
        val plan = valid.validatedResult.tradePlan!!
        val contradictory = listOf(
            plan.copy(direction = com.example.ai.plan.TradeDirection.NEUTRAL,
                orderType = com.example.ai.plan.TradeOrderType.NONE,
                setupStatus = com.example.ai.plan.TradeSetupStatus.WAIT),
            plan.copy(entryZoneMax = 94400.0)
        )
        for (other in contradictory) {
            val outcome = SignalValidationEngine.validate(
                candidate.copy(tradePlan = other), snapshot, currentTimeMs = now
            )
            assertFalse(outcome.isValid)
            assertEquals("WAIT", outcome.validatedResult.decision)
            assertTrue(outcome.rejectionReasons.any { it.contains("Structured trade plan conflicts") })
        }
    }

    // 2. Reversed Stop Loss
    @Test
    fun `validate reversed stop loss on LONG rejects and converts to structured WAIT`() {
        val snapshot = createSampleSnapshot(currentPrice = 94250.0)
        // Inverted SL: SL is above Entry for a LONG
        val invalidLong = createCandidateResult(
            decision = "LONG",
            entryLow = 94000.0,
            entryHigh = 94500.0,
            stopLoss = 96000.0,
            takeProfit = listOf(98000.0)
        )

        val outcome = SignalValidationEngine.validate(invalidLong, snapshot)

        assertFalse("Reversed stop loss must fail validation", outcome.isValid)
        assertEquals("WAIT", outcome.validatedResult.decision)
        assertEquals("WAIT", outcome.validatedResult.setupQuality)
        assertEquals(0.0, outcome.validatedResult.stopLoss, 0.001)
        assertEquals(0.0, outcome.validatedResult.entryZone.low, 0.001)
        assertEquals(0.0, outcome.validatedResult.entryZone.high, 0.001)
        assertTrue(outcome.validatedResult.takeProfit.isEmpty())
        assertTrue("Rejection reason must mention stop loss below entry", outcome.rejectionReasons.any { it.contains("stop loss") })
    }

    @Test
    fun `validate reversed stop loss on SHORT rejects and converts to structured WAIT`() {
        val snapshot = createSampleSnapshot(currentPrice = 94250.0, htfBias = "BEARISH", ptfBias = "BEARISH")
        // Inverted SL: SL is below Entry for a SHORT
        val invalidShort = createCandidateResult(
            decision = "SHORT",
            entryLow = 94000.0,
            entryHigh = 94500.0,
            stopLoss = 92000.0,
            takeProfit = listOf(90000.0)
        )

        val outcome = SignalValidationEngine.validate(invalidShort, snapshot)

        assertFalse("Reversed stop loss on SHORT must fail validation", outcome.isValid)
        assertEquals("WAIT", outcome.validatedResult.decision)
        assertEquals("WAIT", outcome.validatedResult.setupQuality)
        assertTrue(outcome.rejectionReasons.any { it.contains("SHORT stop loss") })
    }

    // 3. Take Profit on the Wrong Side
    @Test
    fun `validate take profit on wrong side for LONG rejects and converts to WAIT`() {
        val snapshot = createSampleSnapshot(currentPrice = 94250.0)
        // TP1 is below entry for a LONG
        val invalidLong = createCandidateResult(
            decision = "LONG",
            entryLow = 94000.0,
            entryHigh = 94500.0,
            stopLoss = 92000.0,
            takeProfit = listOf(93000.0)
        )

        val outcome = SignalValidationEngine.validate(invalidLong, snapshot)

        assertFalse(outcome.isValid)
        assertEquals("WAIT", outcome.validatedResult.decision)
        assertTrue(outcome.rejectionReasons.any { it.contains("LONG TP1") && it.contains("above entry") })
    }

    @Test
    fun `validate take profit on wrong side for SHORT rejects and converts to WAIT`() {
        val snapshot = createSampleSnapshot(currentPrice = 94250.0, htfBias = "BEARISH", ptfBias = "BEARISH")
        // TP1 is above entry for a SHORT
        val invalidShort = createCandidateResult(
            decision = "SHORT",
            entryLow = 94000.0,
            entryHigh = 94500.0,
            stopLoss = 96000.0,
            takeProfit = listOf(97000.0)
        )

        val outcome = SignalValidationEngine.validate(invalidShort, snapshot)

        assertFalse(outcome.isValid)
        assertEquals("WAIT", outcome.validatedResult.decision)
        assertTrue(outcome.rejectionReasons.any { it.contains("SHORT TP1") && it.contains("below entry") })
    }

    // 4. Unordered Targets
    @Test
    fun `validate unordered targets on LONG rejects and converts to WAIT`() {
        val snapshot = createSampleSnapshot(currentPrice = 94250.0)
        // TP2 is less than TP1 for a LONG
        val invalidLong = createCandidateResult(
            decision = "LONG",
            entryLow = 94000.0,
            entryHigh = 94500.0,
            stopLoss = 92000.0,
            takeProfit = listOf(98000.0, 97000.0)
        )

        val outcome = SignalValidationEngine.validate(invalidLong, snapshot)

        assertFalse(outcome.isValid)
        assertEquals("WAIT", outcome.validatedResult.decision)
        assertTrue(outcome.rejectionReasons.any { it.contains("LONG targets must be strictly ascending") })
    }

    @Test
    fun `validate unordered targets on SHORT rejects and converts to WAIT`() {
        val snapshot = createSampleSnapshot(currentPrice = 94250.0, htfBias = "BEARISH", ptfBias = "BEARISH")
        // TP2 is greater than TP1 for a SHORT
        val invalidShort = createCandidateResult(
            decision = "SHORT",
            entryLow = 94000.0,
            entryHigh = 94500.0,
            stopLoss = 96000.0,
            takeProfit = listOf(91000.0, 92000.0)
        )

        val outcome = SignalValidationEngine.validate(invalidShort, snapshot)

        assertFalse(outcome.isValid)
        assertEquals("WAIT", outcome.validatedResult.decision)
        assertTrue(outcome.rejectionReasons.any { it.contains("SHORT targets must be strictly descending") })
    }

    // 5. Zero, Negative, NaN, and Infinite Prices
    @Test
    fun `validate zero negative NaN and infinite prices rejects and converts to WAIT`() {
        val snapshot = createSampleSnapshot()

        val zeroPrice = createCandidateResult(decision = "LONG", entryLow = 0.0)
        assertFalse(SignalValidationEngine.validate(zeroPrice, snapshot).isValid)

        val negativePrice = createCandidateResult(decision = "LONG", stopLoss = -500.0)
        assertFalse(SignalValidationEngine.validate(negativePrice, snapshot).isValid)

        val nanPrice = createCandidateResult(decision = "LONG", takeProfit = listOf(Double.NaN))
        assertFalse(SignalValidationEngine.validate(nanPrice, snapshot).isValid)

        val infinitePrice = createCandidateResult(decision = "LONG", stopLoss = Double.POSITIVE_INFINITY)
        assertFalse(SignalValidationEngine.validate(infinitePrice, snapshot).isValid)
    }

    // 6. Invalid Entry Ranges
    @Test
    fun `validate inverted entry ranges rejects and converts to WAIT`() {
        val snapshot = createSampleSnapshot()
        // Low is greater than high
        val invertedEntry = createCandidateResult(
            decision = "LONG",
            entryLow = 95000.0,
            entryHigh = 94000.0,
            stopLoss = 92000.0,
            takeProfit = listOf(98000.0)
        )

        val outcome = SignalValidationEngine.validate(invertedEntry, snapshot)

        assertFalse(outcome.isValid)
        assertEquals("WAIT", outcome.validatedResult.decision)
        assertTrue(outcome.rejectionReasons.any { it.contains("Entry zone inverted") })
    }

    // 7. Insufficient Risk/Reward
    @Test
    fun `validate insufficient risk reward rejects based on trading style minimums`() {
        val snapshot = createSampleSnapshot(currentPrice = 94250.0)
        // Entry Mid: 94250, SL: 93500 (Risk: 750), TP1: 95000 (Reward: 750) => R:R = 1.0 (Required >= 1.35)
        val poorRrResult = createCandidateResult(
            decision = "LONG",
            entryLow = 94000.0,
            entryHigh = 94500.0,
            stopLoss = 93500.0,
            takeProfit = listOf(95000.0)
        )

        val outcomeDay = SignalValidationEngine.validate(poorRrResult, snapshot)
        assertFalse("R:R 1.0 must fail Day Trading minimum of 1.35", outcomeDay.isValid)
        assertEquals("WAIT", outcomeDay.validatedResult.decision)
        assertTrue(outcomeDay.rejectionReasons.any { it.contains("below required minimum of 1:1.35") })

        // Scalping profile requires >= 1.45
        // Entry Mid: 94250, SL: 93250 (Risk: 1000), TP1: 95650 (Reward: 1400) => R:R = 1.40
        val scalpingResult = createCandidateResult(
            decision = "LONG",
            entryLow = 94000.0,
            entryHigh = 94500.0,
            stopLoss = 93250.0,
            takeProfit = listOf(95650.0)
        )
        val scalpingContext = AiAnalysisRequestContext(tradingStyleProfile = TradingStyleProfile.SCALPING)
        val outcomeScalp = SignalValidationEngine.validate(scalpingResult, snapshot, scalpingContext)
        assertFalse("R:R 1.40 must fail Scalping minimum of 1.45", outcomeScalp.isValid)
        assertTrue(outcomeScalp.rejectionReasons.any { it.contains("below required minimum of 1:1.45") })
    }

    // 8. Conflicting Timeframe Data
    @Test
    fun `validate conflicting higher timeframe bias rejects proposed trade and returns WAIT`() {
        // Higher timeframe is BEARISH while proposing LONG
        val conflictSnapshot = createSampleSnapshot(
            currentPrice = 94250.0,
            htfBias = "BEARISH",
            ptfBias = "BULLISH",
            mtfStatus = MtfConfirmationStatus.CONFLICT
        )
        val proposedLong = createCandidateResult(
            decision = "LONG",
            entryLow = 94000.0,
            entryHigh = 94500.0,
            stopLoss = 92000.0,
            takeProfit = listOf(98000.0)
        )

        val outcome = SignalValidationEngine.validate(proposedLong, conflictSnapshot)

        assertFalse("Conflicting HTF must trigger safe failure", outcome.isValid)
        assertEquals("WAIT", outcome.validatedResult.decision)
        assertEquals("WAIT", outcome.validatedResult.setupQuality)
        assertTrue(outcome.rejectionReasons.any { it.contains("BEARISH, conflicting with proposed LONG") || it.contains("Multi-timeframe conflict") })
    }

    // 9. Stale Market Data
    @Test
    fun `validate stale market candle data rejects proposed trade and returns WAIT`() {
        val staleOpenTime = 1_000_000L
        val currentEvaluationTime = staleOpenTime + (10 * 3_600_000L) // 10 hours later on H1 candle (> 4 periods)
        val staleSnapshot = createSampleSnapshot(latestCandleOpenTime = staleOpenTime)
        val proposedLong = createCandidateResult(
            decision = "LONG",
            entryLow = 94000.0,
            entryHigh = 94500.0,
            stopLoss = 92000.0,
            takeProfit = listOf(98000.0)
        )

        val outcome = SignalValidationEngine.validate(
            result = proposedLong,
            snapshot = staleSnapshot,
            currentTimeMs = currentEvaluationTime
        )

        assertFalse("Stale data must reject actionable setup", outcome.isValid)
        assertEquals("WAIT", outcome.validatedResult.decision)
        assertTrue(outcome.rejectionReasons.any { it.contains("stale") })
    }

    // 10. Valid WAIT Responses
    @Test
    fun `validate valid WAIT response preserves WAIT state without error`() {
        val waitResult = createCandidateResult(
            decision = "WAIT",
            entryLow = 0.0,
            entryHigh = 0.0,
            stopLoss = 0.0,
            takeProfit = emptyList(),
            confidence = 50,
            setupQuality = "WAIT"
        )
        val snapshot = createSampleSnapshot()

        val outcome = SignalValidationEngine.validate(waitResult, snapshot)

        assertTrue("Valid WAIT must be accepted as valid outcome", outcome.isValid)
        assertEquals("WAIT", outcome.validatedResult.decision)
        assertEquals("WAIT", outcome.validatedResult.setupQuality)
        assertEquals(0.0, outcome.validatedResult.stopLoss, 0.001)
        assertTrue(outcome.validatedResult.takeProfit.isEmpty())
        assertTrue(outcome.rejectionReasons.isEmpty())
    }

    // 11. Cloud Provider and Local Fallback Consistency
    @Test
    fun `validate cloud provider and local fallback results enforce identical safety gates without price fabrication`() {
        val snapshot = createSampleSnapshot(currentPrice = 94250.0)

        // Simulate an LLM returning an entry zone 30% away from current price
        val hallucinatedCloudResult = createCandidateResult(
            decision = "LONG",
            entryLow = 130000.0,
            entryHigh = 131000.0,
            stopLoss = 125000.0,
            takeProfit = listOf(145000.0)
        )

        val cloudOutcome = SignalValidationEngine.validate(hallucinatedCloudResult, snapshot)

        assertFalse("Hallucinated cloud trade prices must fail validation", cloudOutcome.isValid)
        assertEquals("WAIT", cloudOutcome.validatedResult.decision)
        // Must NOT fabricate replacement prices to force an entry
        assertEquals(0.0, cloudOutcome.validatedResult.stopLoss, 0.001)
        assertEquals(0.0, cloudOutcome.validatedResult.entryZone.low, 0.001)
        assertTrue(cloudOutcome.validatedResult.takeProfit.isEmpty())
        assertTrue(cloudOutcome.rejectionReasons.any { it.contains("too distant") })
    }
}
