package com.example

import com.example.ai.analyst.BuiltInAIProvider
import com.example.ai.analyst.resolveOpenAiCompletionsUrl
import com.example.ai.parser.AiAnalysisResult
import com.example.ai.parser.AiResponseParser
import com.example.ai.parser.EntryZone
import com.example.ai.sync.AiModelSyncManager
import com.example.ai.usage.TokenUsageSnapshot
import com.example.ai.usage.TokenUsageSource
import com.example.storage.preferences.AiProviderType
import com.example.analysis.events.EventImpactLevel
import com.example.analysis.events.EventProtectionMode
import com.example.analysis.events.EventRiskManager
import com.example.analysis.events.UnavailableEventPolicy
import com.example.analysis.journal.JournalFilterState
import com.example.analysis.journal.SampleSizeClassification
import com.example.analysis.journal.SignalForwardTester
import com.example.analysis.marketStructure.MarketStructureEngine
import com.example.analysis.marketStructure.StructureState
import com.example.analysis.signal.SignalEngine
import com.example.analysis.signal.SignalLifecycleStatus
import com.example.analysis.supportResistance.SupportResistanceEngine
import com.example.analysis.technical.TechnicalSnapshotBuilder
import com.example.chart.indicators.TechnicalIndicators
import com.example.market.calendar.CalendarImpactFilter
import com.example.market.calendar.CalendarLoadState
import com.example.market.calendar.CalendarTimeView
import com.example.market.calendar.EconomicCalendarEvent
import com.example.market.calendar.EconomicCalendarService
import com.example.market.candle.Candle
import com.example.market.candle.CandleMerger
import com.example.market.candle.Timeframe
import com.example.market.clock.CandleClock
import com.example.market.clock.CandleConfirmationEngine
import com.example.simulation.paper.BotRunState
import com.example.simulation.paper.PaperPositionStatus
import com.example.simulation.paper.PaperTradingAccountState
import com.example.simulation.paper.PaperTradingConfig
import com.example.simulation.paper.PaperTradingEngine
import com.example.storage.watchlist.SignalJournalEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {

    @Test
    fun multiTimeframeHierarchy_matchesSpecification() {
        assertEquals(Timeframe.H1, Timeframe.M15.higherTimeframe())
        assertEquals(Timeframe.M5, Timeframe.M15.lowerTimeframe())

        assertEquals(Timeframe.H4, Timeframe.H1.higherTimeframe())
        assertEquals(Timeframe.M15, Timeframe.H1.lowerTimeframe())

        assertEquals(Timeframe.D1, Timeframe.H4.higherTimeframe())
        assertEquals(Timeframe.H1, Timeframe.H4.lowerTimeframe())
    }

    @Test
    fun candleClock_formatsAndSynchronizesWithBinanceCandleBoundaries() {
        // 15m candle: 15:00:00 -> 14:59, 15:14:59 -> 00:01, 15:15:00 -> new candle -> 14:59
        val tf15m = Timeframe.M15
        val base15mStart = 900_000L * 1_900_000L // exact multiple of 900_000ms (15m)

        val snapStart = CandleClock.computeCountdownSnapshot(
            symbol = "BTCUSDT",
            timeframe = tf15m,
            serverTimeMs = base15mStart + 200L,
            knownCandleOpenTime = base15mStart
        )
        assertEquals("14:59", snapStart.formatted)
        assertFalse(snapStart.didJustClose)

        val snapLastSec = CandleClock.computeCountdownSnapshot(
            symbol = "BTCUSDT",
            timeframe = tf15m,
            serverTimeMs = base15mStart + tf15m.durationMs - 1_000L,
            knownCandleOpenTime = base15mStart
        )
        assertEquals("00:01", snapLastSec.formatted)
        assertTrue(snapLastSec.isClosingSoon)

        val snapRollover = CandleClock.computeCountdownSnapshot(
            symbol = "BTCUSDT",
            timeframe = tf15m,
            serverTimeMs = base15mStart + tf15m.durationMs + 200L,
            knownCandleOpenTime = base15mStart
        )
        assertTrue("Should detect transition to new candle at 00:00 boundary", snapRollover.didJustClose)
        assertEquals(base15mStart + tf15m.durationMs, snapRollover.candleOpenTime)
        assertEquals("14:59", snapRollover.formatted)

        // 1h candle: 10:00 -> 59:59 ... 10:59:59 -> 00:01
        val tf1h = Timeframe.H1
        assertEquals("59:59", CandleClock.formatRemainingSeconds(3_599L, tf1h))
        assertEquals("00:01", CandleClock.formatRemainingSeconds(1L, tf1h))

        // 4h & 1D format as HH:MM:SS when hours > 0
        assertEquals("03:59:59", CandleClock.formatRemainingSeconds(14_399L, Timeframe.H4))
    }

    @Test
    fun candleConfirmationEngine_separatesLiveAndConfirmedCandlesAndPreventsDuplicateAnalysis() {
        val engine = CandleConfirmationEngine()
        val c1Closed = Candle(1000L, 67000.0, 67200.0, 66900.0, 67100.0, 12.0, 1999L, isClosed = true)
        val c2Closed = Candle(2000L, 67100.0, 67300.0, 67000.0, 67250.0, 15.0, 2999L, isClosed = true)
        val c3Live = Candle(3000L, 67250.0, 67900.0, 67200.0, 67850.0, 4.0, 3999L, isClosed = false)

        val split = engine.splitCandles(listOf(c1Closed, c2Closed, c3Live), currentServerTimeMs = 3500L)
        assertEquals(2, split.confirmedCandles.size)
        assertEquals(2000L, split.latestConfirmedCandle?.openTime)
        assertEquals(3000L, split.liveCandle?.openTime)

        // Official analysis candles must exclude the unclosed live candle c3Live
        val forAnalysis = engine.getConfirmedCandlesForAnalysis(listOf(c1Closed, c2Closed, c3Live), currentServerTimeMs = 3500L)
        assertEquals(2, forAnalysis.size)
        assertEquals(67250.0, forAnalysis.last().close, 0.001)

        // Duplicate candle-close detection via onRealtimeKlineMerged
        val closeEvent1 = engine.onRealtimeKlineMerged(
            symbol = "BTCUSDT",
            timeframe = Timeframe.M15,
            previousCandles = listOf(c1Closed),
            mergedCandles = listOf(c1Closed, c2Closed),
            incomingCandle = c2Closed,
            currentServerTimeMs = 3000L
        )
        assertNotNull(closeEvent1)
        val duplicateEvent = engine.onRealtimeKlineMerged(
            symbol = "BTCUSDT",
            timeframe = Timeframe.M15,
            previousCandles = listOf(c1Closed, c2Closed),
            mergedCandles = listOf(c1Closed, c2Closed),
            incomingCandle = c2Closed,
            currentServerTimeMs = 3005L
        )
        assertNull("Should not emit confirmed close event twice for the same candle", duplicateEvent)
    }

    @Test
    fun signalEngine_enforcesStrictLevelOrderingAndEvaluatesOverlayLifecycle() {
        // Test valid LONG signal: SL < Entry < TP1 < TP2 < TP3
        val validLongInput = AiAnalysisResult(
            decision = "LONG",
            bias = "BULLISH",
            trend = "UPTREND",
            marketStructure = "HH_HL",
            setupQuality = "HIGH",
            entryZone = EntryZone(67150.0, 67250.0),
            stopLoss = 66900.0, // Worst fill R:R = 550 / 350, above 1.35
            takeProfit = listOf(67800.0, 68300.0, 68900.0),
            riskReward = 1.8,
            confidence = 82,
            support = listOf(66800.0),
            resistance = listOf(67800.0),
            higherTimeframeBias = "BULLISH",
            primaryTimeframeBias = "BULLISH",
            lowerTimeframeBias = "PULLBACK",
            momentum = "STRONG",
            invalidIf = "Below SL",
            reasoning = listOf("Test"),
            summary = "Test",
            rawJson = "{}"
        )
        val validated = SignalEngine.validateAndNormalizeResult(validLongInput, currentPrice = 67200.0)
        assertFalse(validated.isWait)
        assertTrue(validated.stopLoss < validated.entryZone.low)
        assertTrue(validated.entryZone.high < validated.takeProfit[0])
        assertTrue(validated.takeProfit[0] < validated.takeProfit[1])
        assertTrue(validated.takeProfit[1] < validated.takeProfit[2])

        // The old fixture passes midpoint R:R but fails the new worst-fill minimum.
        val midpointOnlyLong = validLongInput.copy(stopLoss = 66800.0)
        val rejectedMidpoint = SignalEngine.validateAndNormalizeResult(midpointOnlyLong, currentPrice = 67200.0)
        assertTrue("Worst-fill R:R of 550 / 450 must be rejected", rejectedMidpoint.isWait)

        // Test invalid LONG signal (SL above Entry) is rejected and converted to WAIT
        val invalidLong = validLongInput.copy(stopLoss = 67500.0)
        val rejected = SignalEngine.validateAndNormalizeResult(invalidLong, currentPrice = 67200.0)
        assertTrue("Invalid price geometry must be converted to WAIT", rejected.isWait)

        // Tahap 6: Test confidence < 60 is converted to WAIT in validateAndNormalizeResult
        val lowConfidenceLong = validLongInput.copy(confidence = 58)
        val rejectedLowConf = SignalEngine.validateAndNormalizeResult(lowConfidenceLong, currentPrice = 67200.0)
        assertTrue("Confidence < 60 must be converted to WAIT", rejectedLowConf.isWait)
        assertEquals("WAIT", rejectedLowConf.decision)

        // Test ChartSignalOverlay live tick lifecycle progression
        val signalCandle = Candle(10_000L, 67100.0, 67300.0, 67000.0, 67200.0, 100.0, 10_999L, isClosed = true)
        val overlay = SignalEngine.buildOverlayFromAiResult(
            symbol = "BTCUSDT",
            timeframe = Timeframe.M15,
            referenceCandle = signalCandle,
            aiResult = validated
        )
        assertNotNull(overlay)
        assertEquals(SignalLifecycleStatus.ACTIVE, overlay?.status)

        // Test ChartSignalOverlay live tick lifecycle progression (Tahap 3 unified state machine):
        // 1. If TP1 is touched BEFORE entry is triggered -> INVALIDATED (missed move)
        val missedMoveOverlay = SignalEngine.evaluateLivePriceTick(overlay!!, livePrice = 67850.0)
        assertEquals(SignalLifecycleStatus.INVALIDATED, missedMoveOverlay.status)

        // 2. When entry is triggered first (inside 67150..67250), then TP1 is evaluated -> TP1_HIT
        val triggeredOverlay = SignalEngine.evaluateLivePriceTick(overlay, livePrice = 67200.0)
        assertEquals(SignalLifecycleStatus.ENTRY_TRIGGERED, triggeredOverlay.status)
        val afterTp1 = SignalEngine.evaluateLivePriceTick(triggeredOverlay, livePrice = 67850.0)
        assertEquals(SignalLifecycleStatus.TP1_HIT, afterTp1.status)
        assertEquals(1, afterTp1.highestTpReached)

        // 3. Reject signal if currentPrice has already passed TP1 or SL, or entry distance > 1.5 * ATR14
        assertFalse(
            "Should reject when currentPrice is already past TP1",
            SignalEngine.isSetupMathematicallyValid("LONG", 67200.0, 66800.0, 67800.0, 68300.0, 68900.0, currentPrice = 67850.0)
        )
        assertFalse(
            "Should reject when |entry - currentPrice| > 1.5 * ATR14",
            SignalEngine.isSetupMathematicallyValid("LONG", 67200.0, 66800.0, 67800.0, 68300.0, 68900.0, currentPrice = 66900.0, atr14 = 100.0)
        )
    }

    @Test
    fun paperTradingEngine_simulatesPositionSizingPartialTpBreakevenAndCustomBalance() {
        val initialConfig = PaperTradingConfig(
            startingBalanceUsd = 2500.0,
            riskPerTradePercent = 1.0, // $25 risk
            leverage = 3.0,
            feeRateBps = 4.0,
            slippageBps = 2.0,
            tp1ClosePercent = 30.0,
            tp2ClosePercent = 30.0,
            tp3ClosePercent = 40.0,
            moveSlToBreakEvenOnTp1 = true,
            autoExecuteConfirmedSignals = true
        )
        var accountState = PaperTradingEngine.setBotRunState(
            PaperTradingAccountState(
                config = initialConfig,
                balanceUsd = 2500.0,
                equityUsd = 2500.0,
                peakEquityUsd = 2500.0
            ),
            BotRunState.RUNNING
        )

        // Custom starting balance update works without preset restriction
        accountState = PaperTradingEngine.updateCustomStartingBalance(accountState, 5000.0)
        assertEquals(5000.0, accountState.config.startingBalanceUsd, 0.01)
        assertEquals(5000.0, accountState.balanceUsd, 0.01)

        val signal = AiAnalysisResult(
            decision = "LONG",
            bias = "BULLISH",
            trend = "UPTREND",
            marketStructure = "HH_HL",
            setupQuality = "A+",
            entryZone = EntryZone(67000.0, 67100.0),
            stopLoss = 66500.0,
            takeProfit = listOf(67800.0, 68400.0, 69100.0),
            riskReward = 2.0,
            confidence = 84,
            support = listOf(66500.0),
            resistance = listOf(67800.0),
            higherTimeframeBias = "BULLISH",
            primaryTimeframeBias = "BULLISH",
            lowerTimeframeBias = "BULLISH",
            momentum = "STRONG",
            invalidIf = "Below 66500",
            reasoning = listOf("Aligned"),
            summary = "Aligned",
            rawJson = "{}"
        )

        accountState = PaperTradingEngine.openPositionFromSignal(
            state = accountState,
            symbol = "BTCUSDT",
            timeframe = "15m",
            aiResult = signal,
            liveMarketPrice = 67050.0,
            signalId = 101L,
            nowMs = 10_000L
        )
        val pos = accountState.openPositions.firstOrNull()
        assertNotNull(pos)
        assertEquals(PaperPositionStatus.OPEN, pos?.status)

        // Stopping the bot must NOT erase existing open positions or history
        accountState = PaperTradingEngine.setBotRunState(accountState, BotRunState.STOPPED)
        assertEquals(1, accountState.openPositions.size)

        // Price hits TP1 (67800): 30% partial close executes, SL moves to Break-Even
        accountState = PaperTradingEngine.updateLiveMarketPrice(
            state = accountState,
            symbol = "BTCUSDT",
            livePrice = 67820.0,
            nowMs = 20_000L
        )

        val updatedPos = accountState.openPositions.firstOrNull()
        assertNotNull(updatedPos)
        assertTrue(updatedPos!!.tp1Hit)
        assertTrue("SL should move to Break-Even after TP1", updatedPos.slMovedToBreakEven)
        assertTrue(updatedPos.remainingNotionalUsd < updatedPos.initialNotionalUsd)

        // Price retraces to Break-Even entry price: remaining position closes at BE
        accountState = PaperTradingEngine.updateLiveMarketPrice(
            state = accountState,
            symbol = "BTCUSDT",
            livePrice = 67000.0,
            nowMs = 30_000L
        )

        assertTrue("Open position should be closed at BE", accountState.openPositions.isEmpty())
        val closedPos = accountState.closedPositions.first()
        assertEquals(PaperPositionStatus.CLOSED_BE, closedPos.status)
        assertTrue("Trade closed at BE after TP1 partial profit should have net positive PnL", closedPos.realizedPnlUsd > 0.0)
    }

    @Test
    fun economicCalendarAndEventRiskEngine_parseRealPayloadAndOverrideBotActionOnly() {
        val sampleApiJson = """
            [
              {
                "id": "fc_101",
                "title": "US Non-Farm Payrolls (NFP)",
                "currency": "USD",
                "country": "United States",
                "timestamp": 1760000000,
                "impact": "High",
                "forecast": "185K",
                "previous": "170K",
                "actual": ""
              },
              {
                "id": "fc_102",
                "title": "NZD Building Consents",
                "currency": "NZD",
                "country": "New Zealand",
                "timestamp": 1760000000,
                "impact": "High",
                "forecast": "1.2%",
                "previous": "0.8%",
                "actual": ""
              }
            ]
        """.trimIndent()

        val events = EconomicCalendarService.parseEventsJson(sampleApiJson)
        assertNotNull(events)
        assertEquals(2, events!!.size)
        assertEquals("USD", events[0].currency)
        assertEquals("HIGH", events[0].impact)
        assertEquals(1760000000_000L, events[0].eventTimeMs)

        // Verify currency relevance for BTCUSDT: USD is relevant, NZD is not
        assertTrue(
            EventRiskManager.isEventRelevantToPair(
                currency = events[0].currency,
                country = events[0].country,
                impact = EventImpactLevel.HIGH,
                symbol = "BTCUSDT"
            )
        )
        assertFalse(
            EventRiskManager.isEventRelevantToPair(
                currency = events[1].currency,
                country = events[1].country,
                impact = EventImpactLevel.HIGH,
                symbol = "BTCUSDT"
            )
        )

        // Evaluate EventRiskManager 10 minutes before the USD High-Impact event
        val calSuccess = CalendarLoadState.Success(
            events = events,
            lastUpdatedMs = 1760000000_000L - 600_000L
        )
        val risk = EventRiskManager.evaluateEventRiskFromCalendar(
            symbol = "BTCUSDT",
            calendarState = calSuccess,
            protectionMode = EventProtectionMode.BLOCK_HIGH_IMPACT,
            preBufferMinutes = 30,
            postBufferMinutes = 30,
            unavailablePolicy = UnavailableEventPolicy.ALLOW_WITH_WARNING,
            manualEventLockActive = false,
            nowMs = 1760000000_000L - 600_000L
        )
        assertTrue("Should block new bot entries during USD high-impact window", risk.shouldBlockNewBotEntries)
        assertEquals("WAIT — HIGH IMPACT EVENT", risk.botActionDirective)
        assertTrue(risk.isEventDataVerified)

        // Verify unavailable calendar data state never claims event risk was verified
        val unavailRisk = EventRiskManager.evaluateEventRiskFromCalendar(
            symbol = "BTCUSDT",
            calendarState = CalendarLoadState.Offline(cachedEvents = emptyList(), message = "No network"),
            protectionMode = EventProtectionMode.BLOCK_HIGH_IMPACT,
            preBufferMinutes = 30,
            postBufferMinutes = 30,
            unavailablePolicy = UnavailableEventPolicy.BLOCK_NEW_ENTRIES,
            manualEventLockActive = false,
            nowMs = 1760000000_000L
        )
        assertFalse("Must not claim event data was checked when offline", unavailRisk.isEventDataVerified)
        assertTrue("Safe policy BLOCK_NEW_ENTRIES should block bot entry when calendar is offline", unavailRisk.shouldBlockNewBotEntries)
        assertEquals("WAIT — EVENT DATA UNAVAILABLE", unavailRisk.botActionDirective)
    }

    @Test
    fun candleMerger_updatesSameOpenTimeAndAppendsNewCandleWithoutDuplicates() {
        val c1 = Candle(1000L, 67000.0, 67200.0, 66900.0, 67100.0, 12.0, 1999L, isClosed = true)
        val c2Open = Candle(2000L, 67100.0, 67300.0, 67050.0, 67250.0, 5.0, 2999L, isClosed = false)
        val c2Updated = Candle(2000L, 67100.0, 67450.0, 67050.0, 67420.0, 9.5, 2999L, isClosed = true)
        val c3New = Candle(3000L, 67420.0, 67500.0, 67380.0, 67480.0, 3.2, 3999L, isClosed = false)

        val step1 = CandleMerger.mergeRealtimeCandle(listOf(c1), c2Open)
        assertEquals(2, step1.size)
        assertEquals(67250.0, step1.last().close, 0.001)

        val step2 = CandleMerger.mergeRealtimeCandle(step1, c2Updated)
        assertEquals(2, step2.size)
        assertEquals(67420.0, step2.last().close, 0.001)
        assertTrue(step2.last().isClosed)

        val step3 = CandleMerger.mergeRealtimeCandle(step2, c3New)
        assertEquals(3, step3.size)
        assertEquals(3000L, step3.last().openTime)
    }

    @Test
    fun technicalIndicators_computesEmaRsiMacdAndAtrCorrectly() {
        val candles = (1..250).map { i ->
            val base = 60000.0 + (i * 25.0)
            Candle(
                openTime = i * 60_000L,
                open = base - 10.0,
                high = base + 40.0,
                low = base - 30.0,
                close = base + 15.0,
                volume = 100.0 + i,
                closeTime = (i + 1) * 60_000L - 1L,
                isClosed = true
            )
        }

        val series = TechnicalIndicators.calculateAll(candles)
        assertNotNull(series.latestEma20)
        assertNotNull(series.latestEma50)
        assertNotNull(series.latestEma200)
        assertNotNull(series.latestRsi14)
        assertNotNull(series.latestMacd?.macd)
        assertNotNull(series.latestMacd?.signal)
        assertNotNull(series.latestMacd?.histogram)
        assertNotNull(series.latestAtr14)

        assertTrue(series.latestEma20!! > series.latestEma50!!)
        assertTrue(series.latestEma50!! > series.latestEma200!!)
        assertTrue(series.latestRsi14!! > 70.0)
    }

    @Test
    fun marketStructure_detectsBullishHigherHighHigherLow() {
        val prices = listOf(
            100.0, 102.0, 105.0, 110.0, 106.0, 104.0, 103.0, 106.0, 109.0,
            114.0, 118.0, 113.0, 110.0, 108.0, 112.0, 117.0, 122.0, 126.0,
            121.0, 119.0, 116.0, 120.0, 125.0, 130.0, 128.0, 126.0, 127.0
        )
        val candles = prices.mapIndexed { idx, p ->
            Candle(
                openTime = (idx + 1) * 60_000L,
                open = p - 1.0,
                high = p + 2.0,
                low = p - 2.0,
                close = p,
                volume = 50.0,
                closeTime = (idx + 2) * 60_000L - 1L
            )
        }

        val structure = MarketStructureEngine.analyze(candles, pivotLookback = 2)
        assertEquals(StructureState.BULLISH, structure.state)
        assertEquals("HH_HL", structure.schemaCode)
        assertEquals("bullish bias", structure.biasLabel)
    }

    @Test
    fun supportResistance_clustersNearbyLevels() {
        val candles = (1..60).map { i ->
            val wave = kotlin.math.sin(i * 0.4) * 500.0
            val close = 67500.0 + wave
            Candle(
                openTime = i * 60_000L,
                open = close - 20.0,
                high = close + 80.0,
                low = close - 80.0,
                close = close,
                volume = 100.0,
                closeTime = (i + 1) * 60_000L - 1L
            )
        }
        val sr = SupportResistanceEngine.detectLevels(candles, currentPrice = 67500.0, atr14 = 120.0)
        assertTrue(sr.supportLevels.isNotEmpty())
        assertTrue(sr.resistanceLevels.isNotEmpty())
        assertTrue(sr.supportLevels.all { it < 67500.0 })
        assertTrue(sr.resistanceLevels.all { it > 67500.0 })
    }

    @Test
    fun aiResponseParser_parsesV2SchemaAndWaitDecision() {
        val v2Json = """
            {
              "decision": "LONG",
              "confidence": 82,
              "trend": "BULLISH",
              "marketStructure": "HH_HL",
              "setupQuality": "HIGH",
              "entryZone": {
                "min": 67150,
                "max": 67300
              },
              "stopLoss": 66780,
              "takeProfit": [
                67800,
                68300,
                68900
              ],
              "riskReward": 1.8,
              "support": [66800, 66200],
              "resistance": [67800, 68300],
              "higherTimeframeBias": "BULLISH",
              "primaryTimeframeBias": "BULLISH",
              "lowerTimeframeBias": "PULLBACK",
              "invalidation": "15m candle closes below 66780",
              "reasoning": [
                "1H bullish",
                "15m bullish",
                "5m completed pullback"
              ]
            }
        """.trimIndent()

        val parsed = AiResponseParser.parse(v2Json, "AI · Primary", "gemini-3.5-flash")
        assertNotNull(parsed)
        assertEquals("LONG", parsed?.decision)
        assertEquals(82, parsed?.confidence)
        assertEquals("A", parsed?.setupQualityGrade)
        assertEquals(67150.0, parsed?.entryZone?.min ?: 0.0, 0.01)
        assertEquals(67300.0, parsed?.entryZone?.max ?: 0.0, 0.01)
        assertEquals("PULLBACK", parsed?.lowerTimeframeBias)
        assertEquals(3, parsed?.reasoning?.size)

        val waitJson = """
            {
              "decision": "WAIT",
              "confidence": 58,
              "trend": "SIDEWAYS",
              "marketStructure": "SIDEWAYS",
              "setupQuality": "LOW",
              "higherTimeframeBias": "BULLISH",
              "primaryTimeframeBias": "BULLISH",
              "lowerTimeframeBias": "BEARISH",
              "invalidation": "Break above resistance with volume",
              "reasoning": [
                "Price is approaching strong resistance",
                "Breakout volume has not confirmed"
              ]
            }
        """.trimIndent()

        val parsedWait = AiResponseParser.parse(waitJson)
        assertNotNull(parsedWait)
        assertTrue(parsedWait!!.isWait)
        assertEquals("WAIT", parsedWait.decision)
        assertEquals(58, parsedWait.confidence)

        assertNull(AiResponseParser.parse("not valid json"))
        assertNull(AiResponseParser.parse("""{"decision": "INVALID"}"""))
    }

    @Test
    fun v2Engine_choosesWaitWhenHigherAndPrimaryTimeframeConflict() {
        val bullishCandles = (1..120).map { i ->
            val p = 65000.0 + (i * 20.0)
            Candle(i * 60_000L, p - 10, p + 25, p - 15, p + 10, 150.0, (i + 1) * 60_000L - 1L)
        }
        val bearishCandles = (1..120).map { i ->
            val p = 70000.0 - (i * 25.0)
            Candle(i * 3600_000L, p + 10, p + 15, p - 25, p - 10, 150.0, (i + 1) * 3600_000L - 1L)
        }

        val snapshot = TechnicalSnapshotBuilder.build(
            symbol = "BTCUSDT",
            timeframe = Timeframe.M15,
            candles = bullishCandles,
            higherTimeframeCandles = bearishCandles
        )
        assertNotNull(snapshot)

        val builtIn = BuiltInAIProvider()
        val result = builtIn.synthesizeV2MultiTimeframeAnalysis(snapshot!!, "AI · Primary", "nexsync-mtf-v2")
        assertNotNull(result)
        assertEquals("Should legitimately choose WAIT when HTF and Primary TF conflict", "WAIT", result?.decision)
    }

    @Test
    fun forwardTester_avoidsLookAheadBiasAndMarksSameCandleConflictAsAmbiguous() {
        val baseSignal = SignalJournalEntity(
            id = 1L,
            timestamp = 10_000L,
            candleOpenTimeAtSignal = 10_000L,
            symbol = "BTCUSDT",
            primaryTimeframe = "15m",
            higherTimeframe = "1H",
            lowerTimeframe = "5m",
            priceAtSignal = 67200.0,
            decision = "LONG",
            confidence = 80,
            setupQuality = "HIGH",
            entryMin = 67150.0,
            entryMax = 67300.0,
            stopLoss = 66780.0,
            tp1 = 67800.0,
            tp2 = 68300.0,
            tp3 = 68900.0,
            riskReward = 1.8,
            marketConditions = "1H:BULLISH | 15m:BULLISH",
            aiProviderUsed = "AI · Primary",
            aiModelUsed = "nexsync-mtf-v2",
            originalSnapshotJson = "{}"
        )

        // 1. Candle at or before signal creation (openTime <= 10_000L) MUST be ignored (no look-ahead / retroactive bias)
        val pastCandleHitTp = Candle(10_000L, 67200.0, 69000.0, 67100.0, 68950.0, 100.0, 10_999L)
        assertNull(SignalForwardTester.evaluateSignalAgainstCandles(baseSignal, listOf(pastCandleHitTp)))

        // 2. Subsequent candle where BOTH TP1 and SL occur inside the same candle -> MUST be AMBIGUOUS
        val ambiguousCandle = Candle(11_000L, 67200.0, 67900.0, 66700.0, 67500.0, 200.0, 11_999L)
        val ambOutcome = SignalForwardTester.evaluateSignalAgainstCandles(baseSignal, listOf(ambiguousCandle))
        assertNotNull(ambOutcome)
        assertEquals("AMBIGUOUS", ambOutcome?.status)
        assertEquals(0.0, ambOutcome?.realizedR ?: -1.0, 0.001)

        // 3. Subsequent candle hitting TP1 cleanly without hitting SL -> TP1_HIT (1/3 exited at TP1 minus cost)
        val cleanTp1Candle = Candle(11_000L, 67200.0, 67850.0, 67100.0, 67810.0, 150.0, 11_999L)
        val tp1Outcome = SignalForwardTester.evaluateSignalAgainstCandles(baseSignal, listOf(cleanTp1Candle))
        assertNotNull(tp1Outcome)
        assertEquals("TP1_HIT", tp1Outcome?.status)
        assertEquals(1, tp1Outcome?.highestTpReached)
        assertTrue("1/3 partial exit at TP1 minus fee/slippage should yield positive net R", (tp1Outcome?.realizedR ?: 0.0) > 0.2)

        // 4. Subsequent candle after TP1 retracing to entry breakeven -> CLOSED, preserving highestTpReached = 1
        val retraceToBeCandle = Candle(12_000L, 67800.0, 67820.0, 67150.0, 67200.0, 140.0, 12_999L)
        val beOutcome = SignalForwardTester.evaluateSignalAgainstCandles(
            baseSignal,
            listOf(cleanTp1Candle, retraceToBeCandle)
        )
        assertNotNull(beOutcome)
        assertEquals("CLOSED", beOutcome?.status)
        assertEquals(1, beOutcome?.highestTpReached)
        assertEquals(tp1Outcome?.realizedR ?: 0.0, beOutcome?.realizedR ?: -1.0, 0.001)

        // 5. TAHAP 2: Pullback signal where priceAtSignal (67500) is outside entryZone (67150..67300).
        // If the first candle touching entryZone also reaches TP1 (67800), it MUST be marked AMBIGUOUS (realizedR = 0).
        val unTriggeredSignal = baseSignal.copy(priceAtSignal = 67500.0, status = "ACTIVE")
        val sameBarEntryAndTp1 = Candle(11_000L, 67480.0, 67850.0, 67200.0, 67810.0, 180.0, 11_999L)
        val entryAmbOutcome = SignalForwardTester.evaluateSignalAgainstCandles(
            unTriggeredSignal,
            listOf(sameBarEntryAndTp1)
        )
        assertNotNull(entryAmbOutcome)
        assertEquals("AMBIGUOUS", entryAmbOutcome?.status)
        assertEquals(0.0, entryAmbOutcome?.realizedR ?: -1.0, 0.001)

        // 6. TAHAP 5: Entered trade must NOT expire at 48 bars; at 96 bars time-stop it closes at candle.close with status CLOSED
        val bars50InRange = (1..50).map { i ->
            Candle(10_000L + i * 1_000L, 67250.0, 67500.0, 67050.0, 67450.0, 100.0, 10_000L + i * 1_000L + 999L)
        }
        val after50BarsOutcome = SignalForwardTester.evaluateSignalAgainstCandles(
            baseSignal.copy(status = "ENTRY_TRIGGERED"),
            bars50InRange
        )
        assertNull("Entered trade must remain open after 50 bars (< 96 time-stop)", after50BarsOutcome)

        val bars96InRange = (1..96).map { i ->
            Candle(10_000L + i * 1_000L, 67250.0, 67550.0, 67050.0, 67500.0, 100.0, 10_000L + i * 1_000L + 999L)
        }
        val timeStopOutcome = SignalForwardTester.evaluateSignalAgainstCandles(baseSignal, bars96InRange)
        assertNotNull(timeStopOutcome)
        assertEquals("CLOSED", timeStopOutcome?.status)
        assertTrue("Time-stop at 67500 above entry 67225 should have positive net R", (timeStopOutcome?.realizedR ?: 0.0) > 0.3)
    }

    @Test
    fun performanceCalculator_classifiesSampleSizeAndComputesStatsAccurately() {
        assertEquals(SampleSizeClassification.INSUFFICIENT, SampleSizeClassification.fromCount(5))
        assertFalse(SampleSizeClassification.fromCount(19).hasSufficientSample)
        assertEquals(SampleSizeClassification.EARLY_DATA, SampleSizeClassification.fromCount(25))
        assertTrue(SampleSizeClassification.fromCount(25).hasSufficientSample)
        assertEquals(SampleSizeClassification.DEVELOPING, SampleSizeClassification.fromCount(60))
        assertEquals(SampleSizeClassification.MEANINGFUL, SampleSizeClassification.fromCount(120))

        val signals = (1..24).map { i ->
            val isWin = i <= 15
            SignalJournalEntity(
                id = i.toLong(),
                timestamp = i * 60_000L,
                candleOpenTimeAtSignal = i * 60_000L,
                symbol = "BTCUSDT",
                primaryTimeframe = if (i % 2 == 0) "15m" else "1H",
                higherTimeframe = "4H",
                lowerTimeframe = "5m",
                priceAtSignal = 67000.0,
                decision = if (i % 3 == 0) "SHORT" else "LONG",
                confidence = 78,
                setupQuality = "HIGH",
                entryMin = 66900.0,
                entryMax = 67100.0,
                stopLoss = 66500.0,
                tp1 = 67800.0,
                tp2 = 68400.0,
                tp3 = 69000.0,
                riskReward = 2.0,
                marketConditions = "BULLISH",
                aiProviderUsed = "AI · Primary",
                aiModelUsed = "nexsync-mtf-v2",
                originalSnapshotJson = "{}",
                status = if (isWin) "TP1_HIT" else "SL_HIT",
                highestTpReached = if (isWin) 1 else 0,
                realizedR = if (isWin) 1.6 else -1.0,
                resolvedAt = (i + 1) * 60_000L
            )
        }

        val metrics = SignalForwardTester.calculatePerformance(signals, JournalFilterState())
        assertEquals(24, metrics.totalSignals)
        assertEquals(15, metrics.wins)
        assertEquals(9, metrics.losses)
        assertEquals(62.5, metrics.winRate, 0.1)
        assertTrue(metrics.wilsonLowerPct in 40.0..50.0)
        assertTrue(metrics.wilsonUpperPct in 75.0..82.0)
        assertEquals(15.0, metrics.totalR, 0.1)
        assertTrue(metrics.profitFactor > 2.5)
        assertEquals(SampleSizeClassification.EARLY_DATA, metrics.sampleClassification)

        // Tahap 4: WAIT signals must NOT inflate SampleSizeClassification, and groups with < 5 trades return "--"
        val fewResolvedPlusManyWaits = signals.take(4) + (1..25).map { idx ->
            signals.first().copy(id = 100L + idx, decision = "WAIT", status = "WAIT", realizedR = 0.0)
        } + listOf(
            signals.first().copy(id = 201L, status = "AMBIGUOUS", realizedR = 0.0)
        )
        val optMetrics = SignalForwardTester.calculatePerformance(
            fewResolvedPlusManyWaits,
            JournalFilterState(scenario = com.example.analysis.journal.PerformanceScenario.OPTIMISTIC)
        )
        assertEquals(SampleSizeClassification.INSUFFICIENT, optMetrics.sampleClassification)
        assertEquals("--", optMetrics.bestTimeframe)
        assertEquals("--", optMetrics.bestSymbol)

        val pessMetrics = SignalForwardTester.calculatePerformance(
            fewResolvedPlusManyWaits,
            JournalFilterState(scenario = com.example.analysis.journal.PerformanceScenario.PESSIMISTIC)
        )
        assertEquals("Pessimistic scenario must include AMBIGUOUS as -1R loss", 5, pessMetrics.resolvedTradeCount)
        assertEquals(optMetrics.totalR - 1.0, pessMetrics.totalR, 0.01)
    }

    @Test
    fun customApiEndpointResolver_supportsOpenRouterAndXaiGrok() {
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            resolveOpenAiCompletionsUrl("https://openrouter.ai/api/v1")
        )
        assertEquals(
            "https://api.x.ai/v1/chat/completions",
            resolveOpenAiCompletionsUrl("https://api.x.ai/v1")
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            resolveOpenAiCompletionsUrl("https://api.openai.com/v1/")
        )
    }

    @Test
    fun mandatorySignalLogicSuite_coversExitModelAmbiguityLiveParityExpiryAndBaseline() {
        // Base LONG signal: entryMid = 100.0 (entryMin=99.5, entryMax=100.5), SL = 90.0 (risk = 10.0)
        // TP1 = 115.0 (1.5R), TP2 = 130.0 (3.0R), TP3 = 145.0 (4.5R)
        val triggeredLongSignal = SignalJournalEntity(
            id = 500L,
            timestamp = 1_000L,
            candleOpenTimeAtSignal = 1_000L,
            symbol = "BTCUSDT",
            primaryTimeframe = "15m",
            higherTimeframe = "1H",
            lowerTimeframe = "5m",
            priceAtSignal = 100.0, // Already inside entryMin..entryMax at signal creation
            decision = "LONG",
            confidence = 82,
            setupQuality = "A",
            entryPrice = 100.0,
            entryMin = 99.5,
            entryMax = 100.5,
            stopLoss = 90.0,
            tp1 = 115.0,
            tp2 = 130.0,
            tp3 = 145.0,
            riskReward = 1.5,
            marketConditions = "BULLISH",
            aiProviderUsed = "AI · Primary",
            aiModelUsed = "nexsync-mtf-v2",
            originalSnapshotJson = "{}"
        )

        // (i) Perhitungan biaya dalam R: ((feeBps*2 + slippageBps*2)/10000 * entry) / risk
        // Default feeBps = 4.0, slippageBps = 2.0 -> ((8 + 4)/10000 * 100.0) / 10.0 = 0.12 / 10.0 = 0.012 R
        val costR = SignalForwardTester.computeCostInR(entryPrice = 100.0, risk = 10.0)
        assertEquals(0.012, costR, 1e-6)

        // (a) TP1 lalu balik ke breakeven:
        // 1/3 exited at TP1 (1.5R) = 0.5R, remaining 2/3 stopped at breakeven (0R), minus costR (0.012) = 0.488 -> rounded 0.49R
        val cTp1 = Candle(2_000L, 100.0, 116.0, 99.0, 114.0, 100.0, 2_999L)
        val cBackToBe = Candle(3_000L, 114.0, 114.5, 99.8, 100.0, 100.0, 3_999L)
        val outcomeA = SignalForwardTester.evaluateSignalAgainstCandles(triggeredLongSignal, listOf(cTp1, cBackToBe))
        assertNotNull(outcomeA)
        assertEquals("CLOSED", outcomeA!!.status)
        assertEquals(1, outcomeA.highestTpReached)
        assertEquals(0.49, outcomeA.realizedR, 0.01)

        // (b) TP1 lalu TP2 lalu stop breakeven:
        // 1/3 * 1.5R (0.5R) + 1/3 * 3.0R (1.0R) + 1/3 * 0R = 1.5R - 0.012R = 1.488 -> rounded 1.49R
        val cTp2 = Candle(3_000L, 114.0, 131.0, 112.0, 128.0, 100.0, 3_999L)
        val cBeAfterTp2 = Candle(4_000L, 128.0, 129.0, 99.5, 100.0, 100.0, 4_999L)
        val outcomeB = SignalForwardTester.evaluateSignalAgainstCandles(triggeredLongSignal, listOf(cTp1, cTp2, cBeAfterTp2))
        assertNotNull(outcomeB)
        assertEquals("CLOSED", outcomeB!!.status)
        assertEquals(2, outcomeB.highestTpReached)
        assertEquals(1.49, outcomeB.realizedR, 0.01)

        // (c) Entry dan TP1 di candle yang sama (ketika entry belum terpicu saat sinyal dibuat) -> AMBIGUOUS
        val pullbackLongSignal = triggeredLongSignal.copy(priceAtSignal = 105.0, status = "ACTIVE")
        val sameCandleEntryAndTp1 = Candle(2_000L, 104.0, 116.0, 100.0, 115.5, 100.0, 2_999L)
        val outcomeC = SignalForwardTester.evaluateSignalAgainstCandles(pullbackLongSignal, listOf(sameCandleEntryAndTp1))
        assertNotNull(outcomeC)
        assertEquals("AMBIGUOUS", outcomeC!!.status)
        assertEquals(0.0, outcomeC.realizedR, 0.001)

        // (d) SL dan TP di candle yang sama sebelum TP apa pun -> AMBIGUOUS
        val sameCandleSlAndTp1 = Candle(2_000L, 100.0, 116.0, 89.0, 102.0, 100.0, 2_999L)
        val outcomeD = SignalForwardTester.evaluateSignalAgainstCandles(triggeredLongSignal, listOf(sameCandleSlAndTp1))
        assertNotNull(outcomeD)
        assertEquals("AMBIGUOUS", outcomeD!!.status)
        assertEquals(0.0, outcomeD.realizedR, 0.001)

        // (e) TP1 tersentuh sebelum entry -> INVALIDATED (move terlewat)
        val missedEntryCandle = Candle(2_000L, 105.0, 116.0, 104.0, 115.5, 100.0, 2_999L)
        val outcomeE = SignalForwardTester.evaluateSignalAgainstCandles(pullbackLongSignal, listOf(missedEntryCandle))
        assertNotNull(outcomeE)
        assertEquals("INVALIDATED", outcomeE!!.status)
        assertEquals(0.0, outcomeE.realizedR, 0.001)

        // (f) Trade yang sudah entry tidak jadi EXPIRED di bar 48
        val candles60InZone = (1..60).map { idx ->
            Candle(1_000L + idx * 1_000L, 101.0, 108.0, 95.0, 104.0, 50.0, 1_000L + idx * 1_000L + 999L)
        }
        val outcomeF = SignalForwardTester.evaluateSignalAgainstCandles(triggeredLongSignal, candles60InZone)
        assertEquals("ENTRY_TRIGGERED", outcomeF?.status)

        // Sedangkan sinyal yang BELUM entry menjadi EXPIRED setelah > 48 bar:
        val candles50NeverEntered = (1..50).map { idx ->
            Candle(1_000L + idx * 1_000L, 105.0, 108.0, 103.0, 106.0, 50.0, 1_000L + idx * 1_000L + 999L)
        }
        val unenteredExpired = SignalForwardTester.evaluateSignalAgainstCandles(pullbackLongSignal, candles50NeverEntered)
        assertEquals("EXPIRED", unenteredExpired?.status)

        // (g) Sample size tidak menghitung WAIT
        val tenResolved = (1..10).map { idx ->
            triggeredLongSignal.copy(
                id = idx.toLong(),
                status = "TP1_HIT",
                highestTpReached = 1,
                realizedR = 0.49,
                resolvedAt = 5_000L + idx
            )
        }
        val thirtyWaits = (1..30).map { idx ->
            triggeredLongSignal.copy(
                id = 100L + idx,
                decision = "WAIT",
                status = "WAIT",
                realizedR = 0.0
            )
        }
        val perfWithWaits = SignalForwardTester.calculatePerformance(tenResolved + thirtyWaits, JournalFilterState())
        assertEquals(40, perfWithWaits.totalSignals)
        assertEquals(10, perfWithWaits.resolvedTradeCount)
        assertEquals(SampleSizeClassification.INSUFFICIENT, perfWithWaits.sampleClassification)

        // (h) Hasil fungsi status live identik antara jalur chart (SignalEngine.evaluateLivePriceTick) dan journal (SignalForwardTester.evaluateSignalAgainstLivePrice)
        val unTriggeredEntity = pullbackLongSignal.copy(expiryAt = 50_000L)
        val chartOverlay = SignalEngine.buildOverlayFromJournalEntity(unTriggeredEntity)!!
        val testPrices = listOf(105.0, 116.0, 89.0, 100.0, 115.5, 130.5, 99.8)
        for (p in testPrices) {
            val ovOut = SignalEngine.evaluateLivePriceTick(chartOverlay, livePrice = p, nowMs = 5_000L)
            val jrOut = SignalForwardTester.evaluateSignalAgainstLivePrice(unTriggeredEntity, livePrice = p, nowMs = 5_000L)
            val jrStatus = jrOut?.status ?: unTriggeredEntity.status
            assertEquals("Chart badge and journal status must match for livePrice=$p", ovOut.status.code, jrStatus)
        }

        // Tahap 6 & 7: Confidence Calibration Buckets (n < 30 -> hasSufficientData = false) & BaselineSignalGenerator
        val bucket80Plus = perfWithWaits.confidenceBuckets.first { it.label == "80+" }
        assertEquals(10, bucket80Plus.sampleSize)
        assertFalse("Bucket with n < 30 must be flagged as insufficient data", bucket80Plus.hasSufficientData)

        // BaselineSignalGenerator: LONG when EMA20 > EMA50 > EMA200 and no HTF/Primary conflict
        val trendCandles = (1..220).map { i ->
            val base = 60000.0 + i * 30.0
            Candle(i * 60_000L, base - 10.0, base + 40.0, base - 25.0, base + 20.0, 120.0, (i + 1) * 60_000L - 1L)
        }
        val bullSnap = TechnicalSnapshotBuilder.build("BTCUSDT", Timeframe.M15, trendCandles)!!
        val baselineLong = com.example.analysis.signal.BaselineSignalGenerator.generateJournalEntity(bullSnap, nowMs = 100_000L)
        assertNotNull(baselineLong)
        assertEquals("LONG", baselineLong?.decision)
        assertEquals("BASELINE", baselineLong?.source)

        val bearCandles = (1..220).map { i ->
            val base = 80000.0 - i * 30.0
            Candle(i * 60_000L, base + 10.0, base + 25.0, base - 40.0, base - 20.0, 120.0, (i + 1) * 60_000L - 1L)
        }
        val bearSnap = TechnicalSnapshotBuilder.build("BTCUSDT", Timeframe.M15, bearCandles)!!
        val baselineShort = com.example.analysis.signal.BaselineSignalGenerator.generateJournalEntity(bearSnap, nowMs = 100_000L)
        assertNotNull(baselineShort)
        assertEquals("SHORT", baselineShort?.decision)
        assertEquals("BASELINE", baselineShort?.source)

        // When HTF conflicts with Primary TF, BaselineSignalGenerator returns null
        val conflictSnap = TechnicalSnapshotBuilder.build(
            symbol = "BTCUSDT",
            timeframe = Timeframe.M15,
            candles = trendCandles,
            higherTimeframeCandles = bearCandles
        )!!
        assertNull(com.example.analysis.signal.BaselineSignalGenerator.generate(conflictSnap))

        // Verify AI vs Baseline comparison in calculatePerformance
        val resolvedBaseline = baselineLong!!.copy(
            id = 901L,
            status = "SL_HIT",
            realizedR = -1.01,
            resolvedAt = 120_000L
        )
        val combinedComparison = SignalForwardTester.calculatePerformance(
            allSignals = tenResolved + listOf(resolvedBaseline),
            filter = JournalFilterState()
        )
        assertEquals(10, combinedComparison.sourceComparison.aiSampleSize)
        assertEquals(1, combinedComparison.sourceComparison.baselineSampleSize)
        assertEquals(-1.01, combinedComparison.sourceComparison.baselineNetR, 0.01)
    }

    @Test
    fun configurableIndicatorsAndFiveTabNavigationWorkDeterministically() {
        // 1. Verify 5-tab navigation (Watchlist, Chart, AI, Calendar, Profile)
        val tabs = com.example.ui.TerminalTab.entries
        assertEquals(5, tabs.size)
        assertEquals(
            listOf("WATCHLIST", "CHART", "AI", "CALENDAR", "PROFILE"),
            tabs.map { it.name }
        )

        // 2. Verify configurable chart indicators (SMA, Bollinger Bands, custom EMA/RSI/MACD/ATR)
        val candles = (1..220).map { i ->
            val base = 50000.0 + i * 25.0 + kotlin.math.sin(i * 0.4) * 180.0
            Candle(
                openTime = i * 60_000L,
                open = base - 15.0,
                high = base + 60.0,
                low = base - 45.0,
                close = base + 20.0,
                volume = 100.0 + (i % 15) * 10.0,
                closeTime = (i + 1) * 60_000L - 1L
            )
        }

        val customParams = com.example.chart.indicators.ChartIndicatorParams(
            ema1Period = 9,
            ema2Period = 21,
            ema3Period = 99,
            sma1Period = 14,
            sma2Period = 50,
            bbPeriod = 20,
            bbStdDev = 2.0,
            rsiPeriod = 7,
            macdFast = 8,
            macdSlow = 21,
            macdSignal = 5,
            atrPeriod = 10
        )

        val series = com.example.chart.indicators.TechnicalIndicators.calculateAll(candles, customParams)

        // Standard AI snapshot fields remain fixed (20/50/200)
        assertNull(series.ema20[18])
        assertNotNull(series.ema20[19])
        assertNull(series.ema50[48])
        assertNotNull(series.ema50[49])
        assertNull(series.ema200[198])
        assertNotNull(series.ema200[199])

        // Configurable chart series match custom periods
        assertNull(series.chartEma1[7])
        assertNotNull(series.chartEma1[8]) // period 9 -> index 8
        assertNull(series.chartEma2[19])
        assertNotNull(series.chartEma2[20]) // period 21 -> index 20
        assertNull(series.chartEma3[97])
        assertNotNull(series.chartEma3[98]) // period 99 -> index 98

        assertNull(series.chartSma1[12])
        assertNotNull(series.chartSma1[13]) // period 14 -> index 13
        assertNull(series.chartSma2[48])
        assertNotNull(series.chartSma2[49]) // period 50 -> index 49

        // Bollinger Bands (period 20, stdDev 2.0): upper > middle > lower when variance > 0
        assertNull(series.bollingerBands[18].middle)
        val bbLast = series.bollingerBands.last()
        assertNotNull(bbLast.upper)
        assertNotNull(bbLast.middle)
        assertNotNull(bbLast.lower)
        assertTrue(bbLast.upper!! > bbLast.middle!!)
        assertTrue(bbLast.middle!! > bbLast.lower!!)

        // Custom RSI(7), MACD(8,21,5), ATR(10) populated on chart series
        assertNotNull(series.rsi14.last())
        assertNotNull(series.macd.last().macd)
        assertNotNull(series.atr14.last())
    }

    @Test
    fun marketStatusServiceEvaluatesSchedulesFreshnessConnectionAndUnknownDeterministically() {
        // 1. Crypto (24/7): Healthy live feed + fresh quote -> GREEN (OPEN, pulsing)
        val wednesdayNyOpenMs = java.time.ZonedDateTime.of(
            2026, 10, 14, 10, 30, 0, 0, java.time.ZoneId.of("America/New_York")
        ).toInstant().toEpochMilli()

        val btcFreshStatus = com.example.market.status.MarketStatusService.evaluate(
            symbol = "BTCUSDT",
            category = com.example.market.candle.InstrumentCategory.CRYPTO,
            provider = com.example.market.candle.MarketProvider.BINANCE,
            fallbackQuoteTimestampMs = wednesdayNyOpenMs - 5_000L,
            connectionStatus = com.example.market.websocket.WsConnectionStatus.LIVE,
            nowMs = wednesdayNyOpenMs
        )
        assertEquals(com.example.market.status.MarketLedState.OPEN, btcFreshStatus.ledState)
        assertTrue(btcFreshStatus.shouldAnimatePulse)
        assertEquals("OPEN", btcFreshStatus.shortBadgeLabel)
        assertTrue(btcFreshStatus.readableStatusText.contains("Market Open", ignoreCase = true))

        // 2. Crypto (24/7) with stale quote (> 90s old) -> ORANGE (STALE, static)
        val btcStaleStatus = com.example.market.status.MarketStatusService.evaluate(
            symbol = "BTCUSDT",
            category = com.example.market.candle.InstrumentCategory.CRYPTO,
            provider = com.example.market.candle.MarketProvider.BINANCE,
            fallbackQuoteTimestampMs = wednesdayNyOpenMs - 180_000L,
            connectionStatus = com.example.market.websocket.WsConnectionStatus.LIVE,
            nowMs = wednesdayNyOpenMs
        )
        assertEquals(com.example.market.status.MarketLedState.STALE, btcStaleStatus.ledState)
        assertFalse(btcStaleStatus.shouldAnimatePulse)
        assertEquals("STALE", btcStaleStatus.shortBadgeLabel)

        // 3. Disconnected provider or feed error -> RED (ERROR, static)
        val btcErrorStatus = com.example.market.status.MarketStatusService.evaluate(
            symbol = "BTCUSDT",
            category = com.example.market.candle.InstrumentCategory.CRYPTO,
            provider = com.example.market.candle.MarketProvider.BINANCE,
            fallbackQuoteTimestampMs = wednesdayNyOpenMs - 2_000L,
            connectionStatus = com.example.market.websocket.WsConnectionStatus.DISCONNECTED,
            nowMs = wednesdayNyOpenMs
        )
        assertEquals(com.example.market.status.MarketLedState.ERROR, btcErrorStatus.ledState)
        assertFalse(btcErrorStatus.shouldAnimatePulse)
        assertEquals("FEED ERROR", btcErrorStatus.shortBadgeLabel)

        // 4. US Stock (AAPL) during NYSE regular session (Wednesday 10:30 EDT) -> GREEN (OPEN)
        val aaplOpenStatus = com.example.market.status.MarketStatusService.evaluate(
            symbol = "AAPL",
            category = com.example.market.candle.InstrumentCategory.STOCKS,
            provider = com.example.market.candle.MarketProvider.BIQUOTE,
            fallbackQuoteTimestampMs = wednesdayNyOpenMs - 15_000L,
            connectionStatus = com.example.market.websocket.WsConnectionStatus.LIVE,
            nowMs = wednesdayNyOpenMs
        )
        assertEquals(com.example.market.status.MarketLedState.OPEN, aaplOpenStatus.ledState)
        assertTrue(aaplOpenStatus.shouldAnimatePulse)

        // 5. US Stock (AAPL) after NYSE close (Wednesday 18:00 EDT) -> GRAY (CLOSED, static)
        val wednesdayNyAfterHoursMs = java.time.ZonedDateTime.of(
            2026, 10, 14, 18, 0, 0, 0, java.time.ZoneId.of("America/New_York")
        ).toInstant().toEpochMilli()
        val aaplClosedStatus = com.example.market.status.MarketStatusService.evaluate(
            symbol = "AAPL",
            category = com.example.market.candle.InstrumentCategory.STOCKS,
            provider = com.example.market.candle.MarketProvider.BIQUOTE,
            fallbackQuoteTimestampMs = wednesdayNyAfterHoursMs - 10_000L,
            connectionStatus = com.example.market.websocket.WsConnectionStatus.LIVE,
            nowMs = wednesdayNyAfterHoursMs
        )
        assertEquals(com.example.market.status.MarketLedState.CLOSED, aaplClosedStatus.ledState)
        assertFalse(aaplClosedStatus.shouldAnimatePulse)
        assertEquals("CLOSED", aaplClosedStatus.shortBadgeLabel)

        // 6. Forex (EURUSD) on Saturday -> GRAY (CLOSED), while Crypto (BTCUSDT) on Saturday -> GREEN (OPEN)
        val saturdayNyMs = java.time.ZonedDateTime.of(
            2026, 10, 17, 14, 0, 0, 0, java.time.ZoneId.of("America/New_York")
        ).toInstant().toEpochMilli()
        val eurSaturdayStatus = com.example.market.status.MarketStatusService.evaluate(
            symbol = "EURUSD",
            category = com.example.market.candle.InstrumentCategory.FOREX,
            provider = com.example.market.candle.MarketProvider.BIQUOTE,
            fallbackQuoteTimestampMs = saturdayNyMs - 10_000L,
            connectionStatus = com.example.market.websocket.WsConnectionStatus.LIVE,
            nowMs = saturdayNyMs
        )
        assertEquals(com.example.market.status.MarketLedState.CLOSED, eurSaturdayStatus.ledState)

        val btcSaturdayStatus = com.example.market.status.MarketStatusService.evaluate(
            symbol = "BTCUSDT",
            category = com.example.market.candle.InstrumentCategory.CRYPTO,
            provider = com.example.market.candle.MarketProvider.BINANCE,
            fallbackQuoteTimestampMs = saturdayNyMs - 5_000L,
            connectionStatus = com.example.market.websocket.WsConnectionStatus.LIVE,
            nowMs = saturdayNyMs
        )
        assertEquals(com.example.market.status.MarketLedState.OPEN, btcSaturdayStatus.ledState)

        // 7. Metals/Commodities daily maintenance break (17:15 America/New_York on Wednesday) -> CLOSED for XAUUSD, but OPEN for EURUSD
        val wednesday1715NyMs = java.time.ZonedDateTime.of(
            2026, 10, 14, 17, 15, 0, 0, java.time.ZoneId.of("America/New_York")
        ).toInstant().toEpochMilli()
        val xauMaintenanceStatus = com.example.market.status.MarketStatusService.evaluate(
            symbol = "XAUUSD",
            category = com.example.market.candle.inferInstrumentCategory("XAUUSD"),
            provider = com.example.market.candle.MarketProvider.BIQUOTE,
            fallbackQuoteTimestampMs = wednesday1715NyMs - 10_000L,
            connectionStatus = com.example.market.websocket.WsConnectionStatus.LIVE,
            nowMs = wednesday1715NyMs
        )
        assertEquals(com.example.market.status.MarketLedState.CLOSED, xauMaintenanceStatus.ledState)

        val eur1715Status = com.example.market.status.MarketStatusService.evaluate(
            symbol = "EURUSD",
            category = com.example.market.candle.InstrumentCategory.FOREX,
            provider = com.example.market.candle.MarketProvider.BIQUOTE,
            fallbackQuoteTimestampMs = wednesday1715NyMs - 10_000L,
            connectionStatus = com.example.market.websocket.WsConnectionStatus.LIVE,
            nowMs = wednesday1715NyMs
        )
        assertEquals(com.example.market.status.MarketLedState.OPEN, eur1715Status.ledState)

        // 8. Different indices schedules: DE40 (Frankfurt) vs JP225 (Tokyo) vs US500 (CME)
        val wednesday0400NyMs = java.time.ZonedDateTime.of(
            2026, 10, 14, 4, 0, 0, 0, java.time.ZoneId.of("America/New_York") // 10:00 Berlin
        ).toInstant().toEpochMilli()
        val de40Status = com.example.market.status.MarketStatusService.evaluate(
            symbol = "DE40",
            category = com.example.market.candle.InstrumentCategory.INDICES,
            provider = com.example.market.candle.MarketProvider.BIQUOTE,
            fallbackQuoteTimestampMs = wednesday0400NyMs - 10_000L,
            connectionStatus = com.example.market.websocket.WsConnectionStatus.LIVE,
            nowMs = wednesday0400NyMs
        )
        assertEquals(com.example.market.status.MarketLedState.OPEN, de40Status.ledState)

        // 9. Holiday calendar check: New Year's Day (2026-01-01, Thursday) closes Forex/Stocks/Metals
        val newYearsDayMs = java.time.ZonedDateTime.of(
            2026, 1, 1, 11, 0, 0, 0, java.time.ZoneId.of("America/New_York")
        ).toInstant().toEpochMilli()
        val nyseHolidayStatus = com.example.market.status.MarketStatusService.evaluate(
            symbol = "NVDA",
            category = com.example.market.candle.InstrumentCategory.STOCKS,
            provider = com.example.market.candle.MarketProvider.BIQUOTE,
            fallbackQuoteTimestampMs = newYearsDayMs - 5_000L,
            connectionStatus = com.example.market.websocket.WsConnectionStatus.LIVE,
            nowMs = newYearsDayMs
        )
        assertEquals(com.example.market.status.MarketLedState.CLOSED, nyseHolidayStatus.ledState)

        // 10. Unknown instrument schedule -> UNKNOWN (never fabricated)
        val unknownInstrumentStatus = com.example.market.status.MarketStatusService.evaluate(
            symbol = "CUSTOM_UNMAPPED_ASSET",
            category = com.example.market.candle.InstrumentCategory.STOCKS,
            provider = com.example.market.candle.MarketProvider.BIQUOTE,
            fallbackQuoteTimestampMs = wednesdayNyOpenMs - 5_000L,
            connectionStatus = com.example.market.websocket.WsConnectionStatus.LIVE,
            nowMs = wednesdayNyOpenMs
        )
        assertEquals(com.example.market.status.MarketLedState.UNKNOWN, unknownInstrumentStatus.ledState)
        assertEquals("UNKNOWN", unknownInstrumentStatus.shortBadgeLabel)
    }

    @Test
    fun autoSetupEngineV2_enforcesLifecycleTransitionsSeparatesAssessmentFromActiveSetupAndExecutesBotPlan() {
        val nowMs = 1_760_000_000_000L

        // 1. Validate explicit SetupLifecycleState state machine & illegal jump rejection
        assertTrue(com.example.analysis.signal.SetupLifecycleState.SCANNING.canTransitionTo(com.example.analysis.signal.SetupLifecycleState.CANDIDATE))
        assertTrue(com.example.analysis.signal.SetupLifecycleState.CANDIDATE.canTransitionTo(com.example.analysis.signal.SetupLifecycleState.ACTIVE))
        assertTrue(com.example.analysis.signal.SetupLifecycleState.ACTIVE.canTransitionTo(com.example.analysis.signal.SetupLifecycleState.PARTIALLY_TRIGGERED))
        assertTrue(com.example.analysis.signal.SetupLifecycleState.PARTIALLY_TRIGGERED.canTransitionTo(com.example.analysis.signal.SetupLifecycleState.ENTERED))
        assertTrue(com.example.analysis.signal.SetupLifecycleState.ENTERED.canTransitionTo(com.example.analysis.signal.SetupLifecycleState.COMPLETED))
        // Illegal jumps must be rejected
        assertFalse(com.example.analysis.signal.SetupLifecycleState.SCANNING.canTransitionTo(com.example.analysis.signal.SetupLifecycleState.COMPLETED))
        assertFalse(com.example.analysis.signal.SetupLifecycleState.INVALIDATED.canTransitionTo(com.example.analysis.signal.SetupLifecycleState.ACTIVE))
        assertFalse(com.example.analysis.signal.SetupLifecycleState.EXPIRED.canTransitionTo(com.example.analysis.signal.SetupLifecycleState.ENTERED))
        assertFalse(com.example.analysis.signal.SetupLifecycleState.ENTERED.canTransitionTo(com.example.analysis.signal.SetupLifecycleState.INVALIDATED))

        // Build bullish snapshot for BTCUSDT 15m
        val bullCandles = (1..220).map { i ->
            val base = 90_000.0 + i * 20.0
            Candle(
                openTime = nowMs - (220 - i) * 900_000L,
                open = base - 10.0,
                high = base + 35.0,
                low = base - 25.0,
                close = base + 15.0,
                volume = 150.0,
                closeTime = nowMs - (220 - i) * 900_000L + 899_999L,
                isClosed = i < 220
            )
        }
        val snapshot = TechnicalSnapshotBuilder.build("BTCUSDT", Timeframe.M15, bullCandles)!!

        val healthyMarketStatus = com.example.market.status.MarketStatusService.evaluate(
            symbol = "BTCUSDT",
            category = com.example.market.candle.InstrumentCategory.CRYPTO,
            provider = com.example.market.candle.MarketProvider.BINANCE,
            fallbackQuoteTimestampMs = nowMs - 2_000L,
            connectionStatus = com.example.market.websocket.WsConnectionStatus.LIVE,
            nowMs = nowMs
        )
        assertTrue(com.example.analysis.signal.AutoSetupEngineV2.isMarketAndDataValidForNewEntry(healthyMarketStatus))

        // 2. Publish initial LONG Grade A setup (Entry Zone: 93,800 - 94,000 while price is ~94,415)
        val longAiResult = AiAnalysisResult(
            decision = "LONG",
            bias = "BULLISH",
            confidence = 86,
            trend = "BULLISH",
            momentum = "POSITIVE",
            marketStructure = "HH_HL",
            setupQuality = "A",
            entryZone = EntryZone(93800.0, 94000.0),
            stopLoss = 93200.0,
            takeProfit = listOf(95100.0, 95900.0, 96800.0),
            riskReward = 2.1,
            support = listOf(93200.0, 92500.0),
            resistance = listOf(95100.0, 95900.0),
            higherTimeframeBias = "BULLISH",
            primaryTimeframeBias = "BULLISH",
            lowerTimeframeBias = "PULLBACK",
            invalidIf = "15m close below 93,200",
            reasoning = listOf("HTF & Primary bullish alignment", "Pullback entry zone above key support"),
            summary = "High-probability pullback LONG setup",
            rawJson = "{}",
            marketRegime = "TRENDING",
            entryTrigger = "Pullback into 93,800 - 94,000 EMA20 zone",
            analyzedAt = nowMs,
            expiryAt = nowMs + 12 * 900_000L,
            setupSignature = "BTCUSDT_15m_LONG_93900_93200"
        )

        val pubOutcome = com.example.analysis.signal.AutoSetupEngineV2.reconcileNewAnalysis(
            currentActive = null,
            newResult = longAiResult,
            snapshot = snapshot,
            marketStatus = healthyMarketStatus,
            minAutoGrade = "A",
            isAutomaticTrigger = true,
            nowMs = nowMs
        )
        assertTrue("Valid Grade A LONG setup must be published", pubOutcome.didPublishNewSetup)
        assertNotNull(pubOutcome.activeSetup)
        assertEquals(com.example.analysis.signal.SetupLifecycleState.ACTIVE, pubOutcome.activeSetup!!.lifecycleState)
        assertEquals("LONG", pubOutcome.activeSetup!!.direction)
        assertEquals("ACTIVE_SETUP", pubOutcome.assessment.setupCondition)

        // 3. Subsequent WAIT assessment MUST NOT overwrite or delete the valid ActiveSetup!
        val waitAiResult = AiAnalysisResult(
            decision = "WAIT",
            bias = "BULLISH",
            confidence = 55,
            trend = "BULLISH",
            momentum = "NEUTRAL",
            marketStructure = "HH_HL",
            setupQuality = "WAIT",
            entryZone = EntryZone(0.0, 0.0),
            stopLoss = 0.0,
            takeProfit = emptyList(),
            riskReward = 0.0,
            support = listOf(93200.0),
            resistance = listOf(95100.0),
            higherTimeframeBias = "BULLISH",
            primaryTimeframeBias = "NEUTRAL",
            lowerTimeframeBias = "NEUTRAL",
            invalidIf = "N/A",
            reasoning = listOf("Waiting for price to pull back into entry zone"),
            summary = "Wait for pullback",
            rawJson = "{}",
            marketRegime = "CONSOLIDATING"
        )

        val waitRecon = com.example.analysis.signal.AutoSetupEngineV2.reconcileNewAnalysis(
            currentActive = pubOutcome.activeSetup,
            newResult = waitAiResult,
            snapshot = snapshot,
            marketStatus = healthyMarketStatus,
            minAutoGrade = "A",
            isAutomaticTrigger = true,
            nowMs = nowMs + 15_000L
        )
        assertFalse("WAIT must not publish a new setup", waitRecon.didPublishNewSetup)
        assertNotNull("Active LONG setup must be preserved when Market Assessment is WAIT", waitRecon.activeSetup)
        assertEquals("LONG", waitRecon.activeSetup!!.direction)
        assertEquals(com.example.analysis.signal.SetupLifecycleState.ACTIVE, waitRecon.activeSetup!!.lifecycleState)
        assertEquals("WAIT", waitRecon.assessment.latestDecision)
        assertEquals("ACTIVE_SETUP_PROTECTED", waitRecon.assessment.setupCondition)

        // 4. Opposing SHORT signal while structure is still BULLISH -> flags conflict, keeps LONG active
        val opposingShortAi = longAiResult.copy(
            decision = "SHORT",
            bias = "BEARISH",
            entryZone = com.example.ai.parser.EntryZone(94600.0, 94800.0),
            stopLoss = 95300.0,
            takeProfit = listOf(93500.0, 92800.0, 92000.0)
        )
        val conflictRecon = com.example.analysis.signal.AutoSetupEngineV2.reconcileNewAnalysis(
            currentActive = waitRecon.activeSetup,
            newResult = opposingShortAi,
            snapshot = snapshot, // snapshot structure is still BULLISH
            marketStatus = healthyMarketStatus,
            minAutoGrade = "A",
            isAutomaticTrigger = true,
            nowMs = nowMs + 30_000L
        )
        assertNotNull(conflictRecon.activeSetup)
        assertEquals("LONG", conflictRecon.activeSetup!!.direction)
        assertTrue("Opposing setup without confirmed structure reversal must flag conflict", conflictRecon.activeSetup!!.hasConflict)
        assertTrue(conflictRecon.activeSetup!!.conflictNote.contains("Conflict detected"))

        // 5. Live WebSocket tick drives ACTIVE -> PARTIALLY_TRIGGERED -> ENTERED -> TP1_HIT -> COMPLETED (TP3_HIT)
        val partialEval = com.example.analysis.signal.AutoSetupEngineV2.evaluateActiveSetupOnLiveTick(
            setup = pubOutcome.activeSetup!!,
            livePrice = 94080.0, // Within 0.15% trigger band of 94,000
            marketStatus = healthyMarketStatus,
            isBotRunning = true,
            nowMs = nowMs + 45_000L
        )
        assertEquals(com.example.analysis.signal.SetupLifecycleState.PARTIALLY_TRIGGERED, partialEval.updatedSetup.lifecycleState)
        assertTrue(partialEval.shouldAttemptBotAutoEntry)

        val enteredEval = com.example.analysis.signal.AutoSetupEngineV2.evaluateActiveSetupOnLiveTick(
            setup = partialEval.updatedSetup,
            livePrice = 93920.0, // Inside exact entry zone [93800, 94000]
            marketStatus = healthyMarketStatus,
            isBotRunning = true,
            nowMs = nowMs + 60_000L
        )
        assertEquals(com.example.analysis.signal.SetupLifecycleState.ENTERED, enteredEval.updatedSetup.lifecycleState)
        assertEquals(com.example.analysis.signal.SignalLifecycleStatus.ENTRY_TRIGGERED, enteredEval.updatedSetup.forwardStatus)
        assertTrue(enteredEval.shouldAttemptBotAutoEntry)

        // Bot auto-entry executes when market/data is healthy and price is in Entry Zone
        val runningBotState = PaperTradingEngine.setBotRunState(PaperTradingAccountState(), com.example.simulation.paper.BotRunState.RUNNING)
        val autoEntryOut = PaperTradingEngine.evaluateAutoEntry(
            state = runningBotState,
            symbol = "BTCUSDT",
            timeframe = "15m",
            aiResult = enteredEval.updatedSetup.sourceAiResult,
            liveMarketPrice = 93920.0,
            signalId = enteredEval.updatedSetup.setupId,
            marketDataBlocked = false,
            nowMs = nowMs + 60_000L
        )
        assertNotNull("Simulation Bot must open virtual position when setup enters Entry Zone", autoEntryOut.openedPosition)
        assertEquals("LONG", autoEntryOut.openedPosition!!.side)

        // Hit TP1 (95,100) -> remains ENTERED with forwardStatus = TP1_HIT
        val tp1Eval = com.example.analysis.signal.AutoSetupEngineV2.evaluateActiveSetupOnLiveTick(
            setup = enteredEval.updatedSetup,
            livePrice = 95150.0,
            marketStatus = healthyMarketStatus,
            isBotRunning = true,
            hasOpenBotPositionForSetup = true,
            nowMs = nowMs + 120_000L
        )
        assertEquals(com.example.analysis.signal.SetupLifecycleState.ENTERED, tp1Eval.updatedSetup.lifecycleState)
        assertEquals(com.example.analysis.signal.SignalLifecycleStatus.TP1_HIT, tp1Eval.updatedSetup.forwardStatus)
        assertEquals(1, tp1Eval.updatedSetup.highestTpReached)

        // Hit TP3 (96,800) -> transitions to COMPLETED with forwardStatus = TP3_HIT
        val tp3Eval = com.example.analysis.signal.AutoSetupEngineV2.evaluateActiveSetupOnLiveTick(
            setup = tp1Eval.updatedSetup,
            livePrice = 96850.0,
            marketStatus = healthyMarketStatus,
            isBotRunning = true,
            hasOpenBotPositionForSetup = false,
            nowMs = nowMs + 180_000L
        )
        assertEquals(com.example.analysis.signal.SetupLifecycleState.COMPLETED, tp3Eval.updatedSetup.lifecycleState)
        assertEquals(com.example.analysis.signal.SignalLifecycleStatus.TP3_HIT, tp3Eval.updatedSetup.forwardStatus)

        // 6. Verify Market CLOSED / STALE / DISCONNECTED blocks Bot entry while open positions continue risk management
        val staleMarketStatus = com.example.market.status.MarketStatusService.evaluate(
            symbol = "BTCUSDT",
            category = com.example.market.candle.InstrumentCategory.CRYPTO,
            provider = com.example.market.candle.MarketProvider.BINANCE,
            fallbackQuoteTimestampMs = nowMs - 300_000L, // Stale (> 90s)
            connectionStatus = com.example.market.websocket.WsConnectionStatus.LIVE,
            nowMs = nowMs
        )
        assertFalse(com.example.analysis.signal.AutoSetupEngineV2.isMarketAndDataValidForNewEntry(staleMarketStatus))

        val blockedBotOut = PaperTradingEngine.evaluateAutoEntry(
            state = runningBotState,
            symbol = "BTCUSDT",
            timeframe = "15m",
            aiResult = longAiResult,
            liveMarketPrice = 93900.0,
            signalId = 777L,
            marketDataBlocked = true,
            marketDataReason = staleMarketStatus.readableStatusText,
            nowMs = nowMs
        )
        assertNull("Bot must block new entry when data feed is STALE", blockedBotOut.openedPosition)
        assertEquals("SKIPPED_MARKET_STATUS", blockedBotOut.botActionCode)

        // 7. Continuous Monitoring throttling & deduplication prevents calling LLM on every tick
        val cp0 = com.example.analysis.signal.MonitorEvaluationCheckpoint(symbol = "BTCUSDT", timeframe = Timeframe.M15)
        val mon1 = com.example.analysis.signal.AutoSetupEngineV2.evaluateContinuousMonitoring(
            checkpoint = cp0,
            snapshot = snapshot,
            currentAssessment = null,
            activeSetup = null,
            localCandidate = longAiResult,
            marketStatus = healthyMarketStatus,
            confirmedCandleJustClosed = false,
            minAutoGrade = "A",
            nowMs = nowMs
        )
        assertTrue("First material intrabar candidate must trigger full AI analysis", mon1.shouldTriggerFullAiAnalysis)

        // Immediate tick 1 second later with identical material signature must NOT trigger another LLM call
        val mon2 = com.example.analysis.signal.AutoSetupEngineV2.evaluateContinuousMonitoring(
            checkpoint = mon1.updatedCheckpoint,
            snapshot = snapshot,
            currentAssessment = mon1.updatedAssessment,
            activeSetup = pubOutcome.activeSetup,
            localCandidate = longAiResult,
            marketStatus = healthyMarketStatus,
            confirmedCandleJustClosed = false,
            minAutoGrade = "A",
            nowMs = nowMs + 1_000L
        )
        assertFalse("Duplicate intrabar tick within cooldown must be throttled/deduplicated", mon2.shouldTriggerFullAiAnalysis)
    }

    @Test
    fun economicCalendar_realFinanceCalendarParsingAndPairBiasEngine() {
        val jsonPayload = """
            {
              "from": "2026-10-13 00:00:00",
              "to": "2026-10-13 23:59:59",
              "count": 3,
              "events": [
                {
                  "date": "2026-10-13",
                  "time_utc": "2026-10-13T14:00:00+00:00",
                  "time_et": "10:00",
                  "all_day": false,
                  "name": "US CPI",
                  "title": "US CPI Report October 2026",
                  "impact": "high",
                  "category": "economic-indicators",
                  "consensus": "3.1%",
                  "prior": "2.9%",
                  "actual": "3.4%",
                  "source": "https://www.financecalendar.com"
                },
                {
                  "date": "2026-10-13",
                  "time_utc": "2026-10-13T16:00:00+00:00",
                  "name": "US Unemployment Rate",
                  "title": "US Unemployment Rate",
                  "impact": "high",
                  "consensus": "4.0%",
                  "prior": "3.9%",
                  "actual": "4.3%"
                },
                {
                  "date": "2027-10-25",
                  "time_utc": "2027-10-25T08:00:00+00:00",
                  "name": "EUR Industrial Production",
                  "title": "Eurozone Industrial Production",
                  "impact": "high",
                  "consensus": null,
                  "prior": "0.5%",
                  "actual": null
                }
              ]
            }
        """.trimIndent()

        val parsed = EconomicCalendarService.parseEventsJson(jsonPayload)
        assertNotNull(parsed)
        assertEquals(3, parsed!!.size)

        val cpiEvent = parsed[0]
        assertEquals("USD", cpiEvent.currency)
        assertEquals("HIGH", cpiEvent.impact)
        assertEquals("3.1%", cpiEvent.forecast)
        assertEquals("2.9%", cpiEvent.previous)
        assertEquals("3.4%", cpiEvent.actual)
        // URL must be sanitized, never leaking raw endpoint
        assertFalse(cpiEvent.source.startsWith("http"))
        assertTrue(cpiEvent.eventTimeMs > 0L)

        // 1. Pair Bias for USD CPI (Actual 3.4% > Forecast 3.1% -> USD Bullish, BTC/XAU Bearish)
        val biasCpi = com.example.market.calendar.CurrencyPairBiasEngine.computeEventBias(cpiEvent)
        assertNotNull(biasCpi)
        assertEquals(com.example.market.calendar.BiasDirection.BULLISH, biasCpi!!.currencyDirection)
        val btcBias = biasCpi.pairBiases.first { it.pair == "BTC/USDT" }
        assertEquals(com.example.market.calendar.BiasDirection.BEARISH, btcBias.direction)
        val xauBias = biasCpi.pairBiases.first { it.pair == "XAUUSD" }
        assertEquals(com.example.market.calendar.BiasDirection.BEARISH, xauBias.direction)
        val usdxBias = biasCpi.pairBiases.first { it.pair == "USDX" }
        assertEquals(com.example.market.calendar.BiasDirection.BULLISH, usdxBias.direction)

        // 2. Inverted Indicator: Unemployment Rate (Actual 4.3% > Forecast 4.0% -> Higher is bad -> USD Bearish)
        val unempEvent = parsed[1]
        val biasUnemp = com.example.market.calendar.CurrencyPairBiasEngine.computeEventBias(unempEvent)
        assertNotNull(biasUnemp)
        assertEquals(com.example.market.calendar.BiasDirection.BEARISH, biasUnemp!!.currencyDirection)

        // 3. Upcoming Event (Actual is null & in future -> display '--' and WAITING_DATA "Menunggu data")
        val eurEvent = parsed[2]
        assertEquals("--", eurEvent.getDisplayForecast())
        assertEquals("--", eurEvent.getDisplayActual())
        val biasEur = com.example.market.calendar.CurrencyPairBiasEngine.computeEventBias(eurEvent)
        assertNotNull(biasEur)
        assertTrue(biasEur!!.isPendingOrWaiting)
        assertEquals(com.example.market.calendar.BiasDirection.WAITING_DATA, biasEur.currencyDirection)

        // Past event without actual returns "Pending"
        val pastEventWithoutActual = eurEvent.copy(eventTimeMs = System.currentTimeMillis() - 3600_000L)
        assertEquals("Pending", pastEventWithoutActual.getDisplayActual())

        // 4. Timezone Formatting
        val witaTime = cpiEvent.formatLocalTime(com.example.storage.preferences.CalendarTimezoneOption.WITA.getTimeZone())
        val wibTime = cpiEvent.formatLocalTime(com.example.storage.preferences.CalendarTimezoneOption.WIB.getTimeZone())
        assertNotNull(witaTime)
        assertNotNull(wibTime)
        assertTrue(witaTime != wibTime) // WITA is 1 hour ahead of WIB
    }

    @Test
    fun modelSyncManager_validatesEndpointSecurity() {
        val manager = AiModelSyncManager()

        // 1. Remote HTTP must be rejected
        val insecureRemote = manager.validateEndpointSecurity("http://api.openai.com/v1")
        assertFalse("Remote HTTP must be rejected", insecureRemote.isValid)
        assertTrue(insecureRemote.errorMessage?.contains("Remote endpoints must use HTTPS") == true)

        // 2. Remote HTTPS must be accepted
        val secureRemote = manager.validateEndpointSecurity("https://api.openai.com/v1")
        assertTrue("Remote HTTPS must be accepted", secureRemote.isValid)
        assertNull(secureRemote.errorMessage)

        // 3. Localhost / emulator HTTP is allowed for local LLMs (e.g. Ollama, LM Studio)
        val localhostHttp = manager.validateEndpointSecurity("http://localhost:11434/v1")
        assertTrue("Localhost HTTP must be accepted", localhostHttp.isValid)

        val ipHttp = manager.validateEndpointSecurity("http://127.0.0.1:11434/v1")
        assertTrue("127.0.0.1 HTTP must be accepted", ipHttp.isValid)

        val emuHttp = manager.validateEndpointSecurity("http://10.0.2.2:8080/v1")
        assertTrue("Android emulator 10.0.2.2 HTTP must be accepted", emuHttp.isValid)

        // 4. Blank or non-http/https is rejected
        assertFalse(manager.validateEndpointSecurity("").isValid)
        assertFalse(manager.validateEndpointSecurity("ftp://example.com").isValid)
    }

    @Test
    fun aiProviderType_configurationsAndDefaultsAreValid() {
        val builtin = AiProviderType.GEMINI_BUILTIN
        assertEquals("GEMINI_BUILTIN", builtin.code)
        assertEquals("Gemini Built-in", builtin.displayName)
        assertFalse("Builtin does not require manual API key", builtin.requiresApiKey)

        val personal = AiProviderType.GEMINI_PERSONAL
        assertEquals("GEMINI_PERSONAL", personal.code)
        assertTrue(personal.requiresApiKey)

        val openai = AiProviderType.OPENAI
        assertEquals("OPENAI", openai.code)
        assertTrue(openai.requiresApiKey)

        val openrouter = AiProviderType.OPENROUTER
        assertEquals("OPENROUTER", openrouter.code)
        assertTrue(openrouter.requiresApiKey)

        val custom = AiProviderType.CUSTOM_OPENAI
        assertEquals("CUSTOM_OPENAI", custom.code)
        assertFalse("Custom may be local unauthenticated endpoint", custom.requiresApiKey)
    }

    @Test
    fun tokenUsageParsing_extractsActualTokensFromOpenAiAndGeminiFormats() {
        // OpenAI Format: { "usage": { "prompt_tokens": 512, "completion_tokens": 128, "total_tokens": 640 } }
        val openAiJson = org.json.JSONObject("""
            {
                "choices": [{"message": {"content": "{}"}}],
                "usage": {
                    "prompt_tokens": 512,
                    "completion_tokens": 128,
                    "total_tokens": 640
                }
            }
        """.trimIndent())

        val usageObj = openAiJson.optJSONObject("usage")
        assertNotNull(usageObj)
        val openAiUsage = TokenUsageSnapshot(
            inputTokens = usageObj?.optInt("prompt_tokens"),
            outputTokens = usageObj?.optInt("completion_tokens"),
            totalTokens = usageObj?.optInt("total_tokens"),
            source = TokenUsageSource.ACTUAL,
            isSuccess = true
        )
        assertEquals(512, openAiUsage.inputTokens)
        assertEquals(128, openAiUsage.outputTokens)
        assertEquals(640, openAiUsage.totalTokens)
        assertEquals(TokenUsageSource.ACTUAL, openAiUsage.source)

        // Gemini Format: { "usageMetadata": { "promptTokenCount": 350, "candidatesTokenCount": 90, "totalTokenCount": 440 } }
        val geminiJson = org.json.JSONObject("""
            {
                "candidates": [{"content": {"parts": [{"text": "{}"}]}}],
                "usageMetadata": {
                    "promptTokenCount": 350,
                    "candidatesTokenCount": 90,
                    "totalTokenCount": 440
                }
            }
        """.trimIndent())

        val meta = geminiJson.optJSONObject("usageMetadata")
        assertNotNull(meta)
        val geminiUsage = TokenUsageSnapshot(
            inputTokens = meta?.optInt("promptTokenCount"),
            outputTokens = meta?.optInt("candidatesTokenCount"),
            totalTokens = meta?.optInt("totalTokenCount"),
            source = TokenUsageSource.ACTUAL,
            isSuccess = true
        )
        assertEquals(350, geminiUsage.inputTokens)
        assertEquals(90, geminiUsage.outputTokens)
        assertEquals(440, geminiUsage.totalTokens)
        assertEquals(TokenUsageSource.ACTUAL, geminiUsage.source)
    }
}

