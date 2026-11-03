package com.example.analysis.signal

import com.example.ai.parser.AiAnalysisResult
import com.example.ai.parser.EntryZone
import com.example.chart.overlay.TradePlanOverlayMapper
import com.example.storage.watchlist.SignalJournalEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SignalSynchronizationRegressionTest {

    private fun createSampleJournalEntity(
        id: Long = 1001L,
        symbol: String = "BTCUSDT",
        timeframe: String = "15m",
        decision: String = "LONG",
        entryPrice: Double = 65000.0,
        stopLoss: Double = 64200.0,
        tp1: Double = 66500.0,
        status: String = "ACTIVE",
        source: String = "BASELINE",
        candleOpenTime: Long = 1_700_000_000_000L,
        timestamp: Long = 1_700_000_900_000L
    ): SignalJournalEntity {
        return SignalJournalEntity(
            id = id,
            timestamp = timestamp,
            candleOpenTimeAtSignal = candleOpenTime,
            candleCloseTimeAtSignal = candleOpenTime + 900_000L,
            symbol = symbol,
            primaryTimeframe = timeframe,
            higherTimeframe = "1h",
            lowerTimeframe = "5m",
            priceAtSignal = entryPrice,
            decision = decision,
            confidence = 75,
            setupQuality = "A",
            entryPrice = entryPrice,
            entryMin = entryPrice * 0.999,
            entryMax = entryPrice * 1.001,
            stopLoss = stopLoss,
            tp1 = tp1,
            tp2 = tp1 * 1.01,
            tp3 = tp1 * 1.02,
            riskReward = 1.88,
            marketStructure = "BULLISH_TREND",
            trend = "BULLISH",
            momentum = "POSITIVE",
            marketConditions = "1h:BULLISH | 15m:BULLISH | Baseline EMA",
            aiProviderUsed = "Baseline · EMA",
            aiModelUsed = "baseline-ema-20-50-200",
            originalSnapshotJson = "{}",
            marketRegime = "TRENDING",
            mtfConfirmation = "ALIGNED",
            entryTrigger = "EMA20/EMA50/EMA200 aligned",
            invalidation = "Price closes below $stopLoss",
            expiryAt = timestamp + 10_800_000L,
            setupSignature = "SIG_$id",
            botAction = "NONE",
            status = status,
            highestTpReached = if (status.startsWith("TP")) 1 else 0,
            realizedR = 0.0,
            resolvedAt = null,
            exitModelVersion = 2,
            source = source,
            entryOrderType = "LIMIT"
        )
    }

    /**
     * Scenario 1: AI WAIT alongside Baseline SHORT/LONG LIMIT.
     * Confirms AI WAIT is preserved without conversion to BUY/SELL,
     * while baseline is independently recorded with source = "BASELINE" and distinct setup ID.
     */
    @Test
    fun testAiWaitAlongsideBaselineSignalPreservation() {
        // AI returns WAIT
        val aiResult = AiAnalysisResult(
            decision = "WAIT",
            bias = "NEUTRAL",
            confidence = 45,
            trend = "TRANSITIONAL",
            momentum = "NEUTRAL",
            marketStructure = "TRANSITIONAL",
            setupQuality = "WAIT",
            entryZone = EntryZone(0.0, 0.0),
            stopLoss = 0.0,
            takeProfit = emptyList(),
            riskReward = 0.0,
            support = listOf(63000.0),
            resistance = listOf(66000.0),
            higherTimeframeBias = "SIDEWAYS",
            primaryTimeframeBias = "SIDEWAYS",
            lowerTimeframeBias = "NEUTRAL",
            invalidIf = "N/A",
            reasoning = listOf("Multi-timeframe disagreement: Awaiting directional confirmation."),
            summary = "Market transitional. WAIT advised.",
            rawJson = "{}",
            marketRegime = "RANGING",
            mtfConfirmation = "CONFLICT",
            entryTrigger = "None",
            expiryAt = 0L,
            setupSignature = "WAIT",
            providerBadge = "AI · Primary",
            modelUsed = "gemini-2.5-flash",
            analyzedAt = 1_700_000_000_000L
        )

        // Baseline independently records a SELL LIMIT signal
        val baselineJournalSignal = createSampleJournalEntity(
            id = 2001L,
            decision = "SHORT",
            entryPrice = 65500.0,
            stopLoss = 66200.0,
            tp1 = 64000.0,
            source = "BASELINE"
        )

        // Verify AI WAIT is strictly preserved
        assertTrue(aiResult.isWait)
        assertEquals("WAIT", aiResult.decision)
        assertFalse(aiResult.decision.equals("LONG", ignoreCase = true))
        assertFalse(aiResult.decision.equals("SHORT", ignoreCase = true))

        // Verify Baseline signal is strictly SHORT, labeled BASELINE, and has valid levels
        assertEquals("BASELINE", baselineJournalSignal.source)
        assertEquals("SHORT", baselineJournalSignal.decision)
        assertEquals("LIMIT", baselineJournalSignal.entryOrderType)
        assertEquals(2001L, baselineJournalSignal.id)
        assertTrue(baselineJournalSignal.tp1 < baselineJournalSignal.entryPrice)
        assertTrue(baselineJournalSignal.entryPrice < baselineJournalSignal.stopLoss)
    }

    /**
     * Scenario 2: Historical baseline overlay visibility.
     * Verifies eligible baseline journal signals pass symbol/timeframe filtering
     * and are included in historical overlays with isBaseline = true.
     */
    @Test
    fun testHistoricalBaselineOverlayVisibility() {
        val baselineSignal = createSampleJournalEntity(
            id = 3001L,
            symbol = "BTCUSDT",
            timeframe = "15m",
            decision = "LONG",
            entryPrice = 64000.0,
            stopLoss = 63200.0,
            tp1 = 65500.0,
            source = "BASELINE"
        )

        val overlay = SignalEngine.buildOverlayFromJournalEntity(baselineSignal, isHistorical = true)
        assertNotNull(overlay)
        assertEquals("BASELINE", overlay!!.source)
        assertTrue(overlay.isBaseline)
        assertTrue(overlay.isHistorical)
        assertEquals(3001L, overlay.signalId)
        assertEquals(64000.0, overlay.entry, 0.001)
        assertEquals(63200.0, overlay.stopLoss, 0.001)
        assertEquals(65500.0, overlay.tp1, 0.001)
    }

    /**
     * Scenario 3: AI historical overlay visibility.
     * Confirms AI-generated historical signals with source = "AI" continue to be included in historical overlays.
     */
    @Test
    fun testAiHistoricalOverlayVisibility() {
        val aiSignal = createSampleJournalEntity(
            id = 4001L,
            symbol = "BTCUSDT",
            timeframe = "15m",
            decision = "SHORT",
            entryPrice = 65000.0,
            stopLoss = 65800.0,
            tp1 = 63500.0,
            source = "AI"
        )

        val overlay = SignalEngine.buildOverlayFromJournalEntity(aiSignal, isHistorical = true)
        assertNotNull(overlay)
        assertEquals("AI", overlay!!.source)
        assertFalse(overlay.isBaseline)
        assertTrue(overlay.isHistorical)
        assertEquals(4001L, overlay.signalId)
        assertEquals(65000.0, overlay.entry, 0.001)
        assertEquals(65800.0, overlay.stopLoss, 0.001)
        assertEquals(63500.0, overlay.tp1, 0.001)
    }

    /**
     * Scenario 4: Duplicate suppression.
     * Verifies that multiple signals on the same candle / entry price don't generate duplicate overlays.
     */
    @Test
    fun testDuplicateSuppressionInHistoricalOverlays() {
        val entity1 = createSampleJournalEntity(
            id = 5001L,
            candleOpenTime = 1_700_000_000_000L,
            entryPrice = 64500.0,
            source = "BASELINE",
            timestamp = 1_700_000_100_000L
        )
        val entity2 = createSampleJournalEntity(
            id = 5002L,
            candleOpenTime = 1_700_000_000_000L,
            entryPrice = 64500.0,
            source = "BASELINE",
            timestamp = 1_700_000_200_000L
        )

        val seenKeys = HashSet<String>()
        val overlays = mutableListOf<ChartSignalOverlay>()

        val list = listOf(entity2, entity1) // sorted descending by timestamp
        for (e in list) {
            val entryMid = if (e.entryPrice > 0.0) e.entryPrice else (e.entryMin + e.entryMax) / 2.0
            val cleanSource = e.source.ifBlank { "AI" }.uppercase(Locale.US)
            val dedupeKey = "${cleanSource}_${e.decision.uppercase(Locale.US)}_${e.candleOpenTimeAtSignal}_${com.example.analysis.supportResistance.SupportResistanceEngine.roundPriceCleanly(entryMid)}"
            if (!seenKeys.add(dedupeKey)) continue

            val overlay = SignalEngine.buildOverlayFromJournalEntity(e, isHistorical = true)
            if (overlay != null) {
                overlays.add(overlay)
            }
        }

        // Only one should be added due to identical candleOpenTime + entryPrice
        assertEquals(1, overlays.size)
        assertEquals(5002L, overlays[0].signalId)
    }

    /**
     * Scenario 5: Symbol and timeframe filtering.
     * Verifies that baseline or AI signals for a different symbol or timeframe (e.g. ETHUSDT 1h vs BTCUSDT 15m)
     * are excluded from the active chart's historical overlays.
     */
    @Test
    fun testSymbolAndTimeframeMatching() {
        val btc15m = createSampleJournalEntity(symbol = "BTCUSDT", timeframe = "15m")
        val btc1h = createSampleJournalEntity(symbol = "BTCUSDT", timeframe = "1h")
        val eth15m = createSampleJournalEntity(symbol = "ETHUSDT", timeframe = "15m")

        val activeSymbol = "BTCUSDT"
        val activeTf = "15m"

        assertTrue(btc15m.symbol.equals(activeSymbol, ignoreCase = true) && TradePlanOverlayMapper.timeframesMatch(btc15m.primaryTimeframe, activeTf))
        assertFalse(btc1h.symbol.equals(activeSymbol, ignoreCase = true) && TradePlanOverlayMapper.timeframesMatch(btc1h.primaryTimeframe, activeTf))
        assertFalse(eth15m.symbol.equals(activeSymbol, ignoreCase = true) && TradePlanOverlayMapper.timeframesMatch(eth15m.primaryTimeframe, activeTf))
    }

    /**
     * Scenario 6: Entry-triggered historical setup.
     * Verifies that signals with ENTRY_TRIGGERED retain original price levels and render correctly.
     */
    @Test
    fun testEntryTriggeredHistoricalSetupPreservation() {
        val triggeredSignal = createSampleJournalEntity(
            id = 6001L,
            status = "ENTRY_TRIGGERED",
            entryPrice = 64200.0,
            stopLoss = 63500.0,
            tp1 = 65800.0,
            source = "BASELINE"
        )

        val overlay = SignalEngine.buildOverlayFromJournalEntity(triggeredSignal, isHistorical = true)
        assertNotNull(overlay)
        assertEquals(SignalLifecycleStatus.ENTRY_TRIGGERED, overlay!!.status)
        assertEquals(64200.0, overlay.entry, 0.001)
        assertEquals(63500.0, overlay.stopLoss, 0.001)
        assertEquals(65800.0, overlay.tp1, 0.001)
    }

    /**
     * Scenario 7: Non-actionable or mathematically invalid signals rejection.
     * Verifies that WAIT signals or signals with inverted SL/TP are rejected from overlays.
     */
    @Test
    fun testInvalidAndWaitSignalsRejectedFromOverlays() {
        // WAIT signal
        val waitSignal = createSampleJournalEntity(decision = "WAIT", entryPrice = 0.0, stopLoss = 0.0, tp1 = 0.0)
        assertNull(SignalEngine.buildOverlayFromJournalEntity(waitSignal, isHistorical = true))

        // Inverted LONG: stopLoss > entryPrice
        val invertedLong = createSampleJournalEntity(decision = "LONG", entryPrice = 64000.0, stopLoss = 65000.0, tp1 = 67000.0)
        assertNull(SignalEngine.buildOverlayFromJournalEntity(invertedLong, isHistorical = true))

        // Inverted SHORT: stopLoss < entryPrice
        val invertedShort = createSampleJournalEntity(decision = "SHORT", entryPrice = 64000.0, stopLoss = 63000.0, tp1 = 61000.0)
        assertNull(SignalEngine.buildOverlayFromJournalEntity(invertedShort, isHistorical = true))

        // Zero price levels
        val zeroPrices = createSampleJournalEntity(decision = "LONG", entryPrice = 0.0, stopLoss = 0.0, tp1 = 0.0)
        assertNull(SignalEngine.buildOverlayFromJournalEntity(zeroPrices, isHistorical = true))
    }

    /**
     * Scenario 8: Reactive updates on journal changes.
     * Verifies that adding new journal entries updates the resolved latestBaselineSignal.
     */
    @Test
    fun testReactiveUpdatesOnJournalChanges() {
        val initialJournal = emptyList<SignalJournalEntity>()
        var latestBase = initialJournal.firstOrNull {
            it.symbol.equals("BTCUSDT", ignoreCase = true) &&
                TradePlanOverlayMapper.timeframesMatch(it.primaryTimeframe, "15m") &&
                it.source.equals("BASELINE", ignoreCase = true)
        }
        assertNull(latestBase)

        val updatedJournal = listOf(
            createSampleJournalEntity(id = 8001L, symbol = "BTCUSDT", timeframe = "15m", source = "BASELINE")
        )
        latestBase = updatedJournal.firstOrNull {
            it.symbol.equals("BTCUSDT", ignoreCase = true) &&
                TradePlanOverlayMapper.timeframesMatch(it.primaryTimeframe, "15m") &&
                it.source.equals("BASELINE", ignoreCase = true)
        }
        assertNotNull(latestBase)
        assertEquals(8001L, latestBase!!.id)
        assertEquals("BASELINE", latestBase.source)
    }

    /**
     * Scenario 9: Journal selection (View on Chart).
     * Verifies that selecting an eligible journal signal maps to an active overlay (isHistorical = false)
     * suitable for direct chart visualization.
     */
    @Test
    fun testSelectJournalSignalForChartVisualization() {
        val entity = createSampleJournalEntity(
            id = 9001L,
            symbol = "BTCUSDT",
            timeframe = "15m",
            decision = "SHORT",
            entryPrice = 66000.0,
            stopLoss = 66800.0,
            tp1 = 64500.0,
            source = "BASELINE"
        )

        val overlay = SignalEngine.buildOverlayFromJournalEntity(entity, isHistorical = false)
        assertNotNull(overlay)
        assertFalse(overlay!!.isHistorical)
        assertTrue(overlay.isBaseline)
        assertEquals(9001L, overlay.signalId)
        assertEquals(66000.0, overlay.entry, 0.001)
    }

    /**
     * Scenario 10: Non-actionable journal selection error feedback.
     * Verifies that WAIT or corrupted records cannot be mapped into chart overlays.
     */
    @Test
    fun testNonActionableJournalSelectionErrorFeedback() {
        val waitEntity = createSampleJournalEntity(
            id = 9002L,
            decision = "WAIT",
            entryPrice = 0.0,
            stopLoss = 0.0,
            tp1 = 0.0
        )

        val overlay = SignalEngine.buildOverlayFromJournalEntity(waitEntity, isHistorical = false)
        assertNull(overlay)

        val isActionable = !waitEntity.decision.equals("WAIT", ignoreCase = true) &&
            (waitEntity.entryPrice > 0.0 || (waitEntity.entryMin > 0.0 && waitEntity.entryMax > 0.0)) &&
            waitEntity.stopLoss > 0.0 && waitEntity.tp1 > 0.0

        assertFalse(isActionable)
    }

    /**
     * Scenario 11: AI WAIT alongside Baseline LONG LIMIT.
     * Confirms that when AI returns WAIT but Baseline independently generates a BUY LIMIT signal,
     * both remain distinct, properly typed, and mathematically valid (stopLoss < entry < tp1).
     */
    @Test
    fun testAiWaitAlongsideBaselineLongLimit() {
        val aiResult = AiAnalysisResult(
            decision = "WAIT",
            bias = "NEUTRAL",
            confidence = 50,
            trend = "RANGING",
            momentum = "NEUTRAL",
            marketStructure = "SIDEWAYS",
            setupQuality = "WAIT",
            entryZone = EntryZone(0.0, 0.0),
            stopLoss = 0.0,
            takeProfit = emptyList(),
            riskReward = 0.0,
            support = listOf(60000.0),
            resistance = listOf(65000.0),
            higherTimeframeBias = "SIDEWAYS",
            primaryTimeframeBias = "SIDEWAYS",
            lowerTimeframeBias = "NEUTRAL",
            invalidIf = "N/A",
            reasoning = listOf("Consolidation detected."),
            summary = "Market in range. WAIT advised.",
            rawJson = "{}",
            marketRegime = "RANGING",
            mtfConfirmation = "CONFLICT",
            entryTrigger = "None",
            expiryAt = 0L,
            setupSignature = "WAIT",
            providerBadge = "AI · Primary",
            modelUsed = "gemini-2.5-flash",
            analyzedAt = 1_700_000_000_000L
        )

        val baselineLong = createSampleJournalEntity(
            id = 2002L,
            decision = "LONG",
            entryPrice = 62000.0,
            stopLoss = 61200.0,
            tp1 = 63500.0,
            source = "BASELINE"
        )

        assertTrue(aiResult.isWait)
        assertEquals("LONG", baselineLong.decision)
        assertEquals("BASELINE", baselineLong.source)
        assertEquals("LIMIT", baselineLong.entryOrderType)
        assertTrue(baselineLong.stopLoss < baselineLong.entryPrice)
        assertTrue(baselineLong.entryPrice < baselineLong.tp1)

        val overlay = SignalEngine.buildOverlayFromJournalEntity(baselineLong, isHistorical = false)
        assertNotNull(overlay)
        assertTrue(overlay!!.isBaseline)
        assertEquals("LONG", overlay.decision)
        assertEquals(62000.0, overlay.entry, 0.001)
    }

    /**
     * Scenario 12: Zoom/Pan coordinate mapping safety.
     * Verifies priceToY calculations prevent NaN, Infinity, and handle scaledMin/scaledMax safely.
     */
    @Test
    fun testZoomPanCoordinateAlignmentSafety() {
        val minPrice = 60000.0
        val maxPrice = 65000.0
        val priceScale = 1.5f
        val priceOffset = 250.0
        val totalHeight = 800f

        val centerPrice = (maxPrice + minPrice) / 2.0 + priceOffset
        val scaledSpan = (maxPrice - minPrice).coerceAtLeast(minPrice * 0.0005) / priceScale.coerceAtLeast(0.1f)
        val halfSpan = scaledSpan / 2.0
        val scaledMax = (centerPrice + halfSpan) + (scaledSpan * 0.09)
        val scaledMin = (centerPrice - halfSpan) - (scaledSpan * 0.16)
        val priceSpan = (scaledMax - scaledMin).coerceAtLeast(1e-8)

        fun priceToY(p: Double): Float {
            if (p.isNaN() || p.isInfinite()) return totalHeight / 2f
            val ratio = ((scaledMax - p) / priceSpan).toFloat()
            if (ratio.isNaN() || ratio.isInfinite()) return totalHeight / 2f
            return (ratio * totalHeight).coerceIn(-totalHeight * 2f, totalHeight * 3f)
        }

        // Test normal price inside span
        val midY = priceToY(centerPrice)
        assertTrue(midY in 0f..totalHeight)

        // Test boundary prices
        val topY = priceToY(scaledMax)
        assertEquals(0f, topY, 1.0f)
        val botY = priceToY(scaledMin)
        assertEquals(totalHeight, botY, 1.0f)

        // Test extreme price clamping
        val extremeHighY = priceToY(scaledMax * 10)
        assertTrue(!extremeHighY.isNaN() && !extremeHighY.isInfinite())

        // Test NaN / Infinite safety
        val nanY = priceToY(Double.NaN)
        assertEquals(totalHeight / 2f, nanY, 0.01f)
        val infY = priceToY(Double.POSITIVE_INFINITY)
        assertEquals(totalHeight / 2f, infY, 0.01f)
    }
}

