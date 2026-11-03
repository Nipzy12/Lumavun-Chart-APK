package com.example.ai.tracker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.plan.BrokerVerificationStatus
import com.example.ai.plan.StructuredTradePlan
import com.example.ai.plan.TradeDirection
import com.example.ai.plan.TradeOrderType
import com.example.ai.plan.TradeSetupStatus
import com.example.ai.strategy.AiStrategy
import com.example.market.candle.Candle
import com.example.storage.watchlist.TerminalDao
import com.example.storage.watchlist.TerminalDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingSetupTrackerTest {

    private lateinit var db: TerminalDatabase
    private lateinit var dao: TerminalDao
    private lateinit var repository: TrackedSetupRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TerminalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.terminalDao()
        repository = TrackedSetupRepository(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createSampleBuyPlan(
        symbol: String = "BTCUSDT",
        timeframe: String = "1H",
        entryMin: Double = 94000.0,
        entryMax: Double = 94500.0,
        stopLoss: Double = 92000.0,
        tp1: Double = 98000.0,
        tp2: Double = 101000.0,
        tp3: Double = 105000.0
    ): StructuredTradePlan {
        return StructuredTradePlan(
            strategy = AiStrategy.AI_SMART,
            strategyId = AiStrategy.AI_SMART.id,
            strategyName = AiStrategy.AI_SMART.displayName,
            symbol = symbol,
            timeframe = timeframe,
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.LIMIT,
            setupStatus = TradeSetupStatus.PENDING,
            referencePrice = 95200.0,
            referenceTimestamp = System.currentTimeMillis(),
            entryZoneMin = entryMin,
            entryZoneMax = entryMax,
            stopLoss = stopLoss,
            tp1 = tp1,
            tp2 = tp2,
            tp3 = tp3,
            riskRewardTp1 = 1.6,
            setupRationale = "Order block retest confluence",
            supportingEvidence = listOf("Liquidity grab confirmed", "FVG unfilled"),
            invalidationCondition = "Loss of 92,000 structural low",
            dataFreshnessInfo = "Live Binance market feed",
            brokerVerificationStatus = BrokerVerificationStatus.UNVERIFIED_BROKER_DATA
        )
    }

    private fun createSampleSellPlan(
        symbol: String = "ETHUSDT",
        timeframe: String = "1H",
        entryMin: Double = 3400.0,
        entryMax: Double = 3450.0,
        stopLoss: Double = 3550.0,
        tp1: Double = 3200.0,
        tp2: Double = 3000.0
    ): StructuredTradePlan {
        return StructuredTradePlan(
            strategy = AiStrategy.AI_SMC,
            strategyId = AiStrategy.AI_SMC.id,
            strategyName = AiStrategy.AI_SMC.displayName,
            symbol = symbol,
            timeframe = timeframe,
            marketBias = "BEARISH",
            direction = TradeDirection.SELL,
            orderType = TradeOrderType.LIMIT,
            setupStatus = TradeSetupStatus.PENDING,
            referencePrice = 3350.0,
            referenceTimestamp = System.currentTimeMillis(),
            entryZoneMin = entryMin,
            entryZoneMax = entryMax,
            stopLoss = stopLoss,
            tp1 = tp1,
            tp2 = tp2,
            tp3 = null,
            riskRewardTp1 = 1.5,
            setupRationale = "Premium zone supply mitigation",
            supportingEvidence = listOf("Bearish BOS confirmed"),
            invalidationCondition = "Acceptance above 3,550",
            dataFreshnessInfo = "Live Binance market feed",
            brokerVerificationStatus = BrokerVerificationStatus.UNVERIFIED_BROKER_DATA
        )
    }

    @Test
    fun pendingSetup_creation_initializesCorrectly() {
        val plan = createSampleBuyPlan()
        val now = System.currentTimeMillis()
        val setup = TrackedPendingSetup.fromTradePlan(plan, currentPrice = 95200.0, timestampMs = now)

        assertNotNull("Setup must be created from valid plan", setup)
        assertEquals(TrackedSetupStatus.PENDING, setup!!.currentStatus)
        assertEquals("BTCUSDT", setup.symbol)
        assertEquals("1H", setup.timeframe)
        assertEquals(TradeDirection.BUY, setup.direction)
        assertEquals(TradeOrderType.LIMIT, setup.orderType)
        assertEquals(94000.0, setup.entryZoneMin, 0.001)
        assertEquals(94500.0, setup.entryZoneMax, 0.001)
        assertEquals(92000.0, setup.stopLoss, 0.001)
        assertEquals(98000.0, setup.tp1, 0.001)
        assertEquals(101000.0, setup.tp2!!, 0.001)
        assertEquals(105000.0, setup.tp3!!, 0.001)
        assertFalse("Setup must not be terminal at creation", setup.isTerminal)
        assertEquals(1, setup.transitionHistory.size)
        assertEquals(TrackedSetupStatus.PENDING, setup.transitionHistory.first().toStatus)
    }

    @Test
    fun entryZoneTouch_transitionsToPriceTriggered_notFilled() {
        val plan = createSampleBuyPlan()
        val now = 1_000_000L
        val setup = TrackedPendingSetup.fromTradePlan(plan, currentPrice = 95200.0, timestampMs = now)!!

        // Price above zone -> remains pending
        val stillPending = PendingSetupEvaluationEngine.evaluateLiveTick(setup, currentPrice = 94800.0, nowMs = now + 1000)
        assertEquals(TrackedSetupStatus.PENDING, stillPending.currentStatus)

        // Price enters zone -> PRICE_TRIGGERED (not FILLED or EXECUTED!)
        val triggered = PendingSetupEvaluationEngine.evaluateLiveTick(stillPending, currentPrice = 94250.0, nowMs = now + 2000)
        assertEquals(TrackedSetupStatus.PRICE_TRIGGERED, triggered.currentStatus)
        assertFalse("PRICE_TRIGGERED is not terminal", triggered.isTerminal)
        assertTrue(triggered.isTriggered)
        assertTrue(triggered.transitionHistory.last().reason.contains("entered proposed entry zone"))
        assertFalse("Must never claim FILLED or EXECUTED", triggered.transitionHistory.last().reason.contains("FILLED"))
        assertFalse("Must never claim broker execution", triggered.transitionHistory.last().reason.contains("EXECUTED"))
    }

    @Test
    fun takeProfit_tp1_tp2_tp3_sequentialTransitions() {
        val plan = createSampleBuyPlan()
        val now = 1_000_000L
        val setup = TrackedPendingSetup.fromTradePlan(plan, currentPrice = 95200.0, timestampMs = now)!!

        // 1. Entry triggered
        val triggered = PendingSetupEvaluationEngine.evaluateLiveTick(setup, currentPrice = 94200.0, nowMs = now + 1000)
        assertEquals(TrackedSetupStatus.PRICE_TRIGGERED, triggered.currentStatus)

        // 2. TP1 hit
        val tp1 = PendingSetupEvaluationEngine.evaluateLiveTick(triggered, currentPrice = 98150.0, nowMs = now + 2000)
        assertEquals(TrackedSetupStatus.TP1_REACHED, tp1.currentStatus)
        assertFalse(tp1.isTerminal)

        // 3. TP2 hit
        val tp2 = PendingSetupEvaluationEngine.evaluateLiveTick(tp1, currentPrice = 101200.0, nowMs = now + 3000)
        assertEquals(TrackedSetupStatus.TP2_REACHED, tp2.currentStatus)
        assertFalse(tp2.isTerminal)

        // 4. TP3 hit -> Terminal complete
        val tp3 = PendingSetupEvaluationEngine.evaluateLiveTick(tp2, currentPrice = 105500.0, nowMs = now + 4000)
        assertEquals(TrackedSetupStatus.TP3_REACHED, tp3.currentStatus)
        assertTrue(tp3.isTerminal)
        assertEquals(5, tp3.transitionHistory.size)
    }

    @Test
    fun stopLoss_transitionsToStopLossReached() {
        val plan = createSampleBuyPlan()
        val now = 1_000_000L
        val setup = TrackedPendingSetup.fromTradePlan(plan, currentPrice = 95200.0, timestampMs = now)!!

        // Entry triggered
        val triggered = PendingSetupEvaluationEngine.evaluateLiveTick(setup, currentPrice = 94200.0, nowMs = now + 1000)
        assertEquals(TrackedSetupStatus.PRICE_TRIGGERED, triggered.currentStatus)

        // Price collapses to SL (92,000)
        val slHit = PendingSetupEvaluationEngine.evaluateLiveTick(triggered, currentPrice = 91950.0, nowMs = now + 2000)
        assertEquals(TrackedSetupStatus.STOP_LOSS_REACHED, slHit.currentStatus)
        assertTrue(slHit.isTerminal)
        assertTrue(slHit.transitionHistory.last().reason.contains("reached stop loss level"))
    }

    @Test
    fun invalidation_breachStopLossBeforeEntry_transitionsToInvalidated() {
        val plan = createSampleBuyPlan()
        val now = 1_000_000L
        val setup = TrackedPendingSetup.fromTradePlan(plan, currentPrice = 95200.0, timestampMs = now)!!

        // While still PENDING, market gaps down straight through SL (91,500) without ever activating entry zone
        val invalidated = PendingSetupEvaluationEngine.evaluateLiveTick(setup, currentPrice = 91500.0, nowMs = now + 1000)
        assertEquals(TrackedSetupStatus.INVALIDATED, invalidated.currentStatus)
        assertTrue(invalidated.isTerminal)
        assertTrue(invalidated.transitionHistory.last().reason.contains("breached stop loss"))
    }

    @Test
    fun expiration_exceedingExpiresAt_transitionsToExpired() {
        val plan = createSampleBuyPlan()
        val now = 1_000_000L
        val setup = TrackedPendingSetup.fromTradePlan(plan, currentPrice = 95200.0, timestampMs = now)!!

        // Advance time past setup.expiresAt
        val pastExpiry = setup.expiresAt + 5000L
        val expired = PendingSetupEvaluationEngine.evaluateLiveTick(setup, currentPrice = 94800.0, nowMs = pastExpiry)
        assertEquals(TrackedSetupStatus.EXPIRED, expired.currentStatus)
        assertTrue(expired.isTerminal)
    }

    @Test
    fun duplicatePrevention_trackedSetupRepository_rejectsDuplicateActiveSetup() = runBlocking {
        val plan = createSampleBuyPlan(symbol = "BTCUSDT", timeframe = "1H")
        val now = 1_000_000L

        // First registration succeeds
        val res1 = repository.startTracking(plan, currentPrice = 95000.0, nowMs = now)
        assertTrue("First setup tracking must succeed", res1.isSuccess)

        // Second registration with identical active symbol + timeframe + strategy + direction fails
        val res2 = repository.startTracking(plan, currentPrice = 95000.0, nowMs = now + 1000)
        assertTrue("Duplicate setup tracking must fail", res2.isFailure)
        assertTrue(res2.exceptionOrNull()?.message?.contains("already actively tracked") == true)
    }

    @Test
    fun appRestartRestoration_trackedSetupRepository_persistsAndRestores() = runBlocking {
        val plan = createSampleBuyPlan(symbol = "SOLUSDT", timeframe = "15M")
        val now = 2_000_000L

        val started = repository.startTracking(plan, currentPrice = 145.0, nowMs = now).getOrThrow()
        assertNotNull(started)

        // Query active setups from DAO to simulate fresh app restart
        val restoredList = repository.getAllActiveTrackedSetupsOnce()
        assertEquals(1, restoredList.size)
        val restored = restoredList.first()

        assertEquals("SOLUSDT", restored.symbol)
        assertEquals("15M", restored.timeframe)
        assertEquals(TrackedSetupStatus.PENDING, restored.currentStatus)
        assertEquals(plan.entryZoneMin, restored.entryZoneMin)
        assertEquals(plan.stopLoss, restored.stopLoss)
        assertEquals(plan.tp1, restored.tp1)
        assertEquals(1, restored.transitionHistory.size)
    }

    @Test
    fun staleMarketData_safeHandling() {
        val plan = createSampleBuyPlan()
        val now = 1_000_000L
        val setup = TrackedPendingSetup.fromTradePlan(plan, currentPrice = 95200.0, timestampMs = now)!!

        // 1. NaN price is rejected
        val nanRes = PendingSetupEvaluationEngine.evaluateLiveTick(setup, Double.NaN, nowMs = now + 1000)
        assertEquals(TrackedSetupStatus.PENDING, nanRes.currentStatus)
        assertEquals(setup.lastVerifiedPrice, nanRes.lastVerifiedPrice, 0.001)

        // 2. Negative price is rejected
        val negRes = PendingSetupEvaluationEngine.evaluateLiveTick(setup, -100.0, nowMs = now + 1000)
        assertEquals(TrackedSetupStatus.PENDING, negRes.currentStatus)

        // 3. Retroactive timestamp (earlier than setup creation) is rejected
        val retroactiveRes = PendingSetupEvaluationEngine.evaluateLiveTick(setup, 94200.0, nowMs = now - 5000)
        assertEquals(TrackedSetupStatus.PENDING, retroactiveRes.currentStatus)
    }

    @Test
    fun ambiguousSameCandle_bothSlAndTpBreached_transitionsToAmbiguous() {
        val plan = createSampleBuyPlan(entryMin = 94000.0, entryMax = 94500.0, stopLoss = 92000.0, tp1 = 98000.0)
        val now = 1_000_000L
        val setup = TrackedPendingSetup.fromTradePlan(plan, currentPrice = 95200.0, timestampMs = now)!!

        // Trigger the setup first
        val triggered = PendingSetupEvaluationEngine.evaluateLiveTick(setup, 94250.0, nowMs = now + 1000)
        assertEquals(TrackedSetupStatus.PRICE_TRIGGERED, triggered.currentStatus)

        // Create a volatile flash candle touching BOTH SL (91,000 <= 92,000) AND TP1 (99,000 >= 98,000)
        val flashCandle = Candle(
            openTime = now + 2000,
            open = 94200.0,
            high = 99500.0, // Breached TP1
            low = 91000.0,  // Breached SL
            close = 94000.0,
            volume = 500.0,
            closeTime = now + 3000
        )

        val ambiguous = PendingSetupEvaluationEngine.evaluateCandle(triggered, flashCandle, nowMs = now + 3000)
        assertEquals(TrackedSetupStatus.AMBIGUOUS, ambiguous.currentStatus)
        assertTrue(ambiguous.isTerminal)
        assertTrue(ambiguous.transitionHistory.last().reason.contains("Intrabar ambiguity"))
    }

    @Test
    fun setupCancellation_userStopsTracking_transitionsToCancelled() {
        val plan = createSampleBuyPlan()
        val now = 1_000_000L
        val setup = TrackedPendingSetup.fromTradePlan(plan, currentPrice = 95200.0, timestampMs = now)!!

        val cancelled = PendingSetupEvaluationEngine.cancelSetup(setup, reason = "User manually closed", nowMs = now + 5000)
        assertEquals(TrackedSetupStatus.CANCELLED, cancelled.currentStatus)
        assertTrue(cancelled.isTerminal)
        assertTrue(cancelled.transitionHistory.last().reason.contains("User manually closed"))
    }

    @Test
    fun symbolTimeframeIsolation_ignoresUpdatesFromDifferentPairOrTimeframe() = runBlocking {
        val btcPlan = createSampleBuyPlan(symbol = "BTCUSDT", timeframe = "1H", entryMin = 94000.0, entryMax = 94500.0)
        val now = 1_000_000L
        repository.startTracking(btcPlan, currentPrice = 95000.0, nowMs = now)

        // Price update for ETHUSDT touching 94250 must NOT affect BTCUSDT!
        repository.evaluatePriceTick("ETHUSDT", currentPrice = 94250.0, nowMs = now + 1000)

        val active = repository.getAllActiveTrackedSetupsOnce().first()
        assertEquals("BTCUSDT", active.symbol)
        assertEquals(TrackedSetupStatus.PENDING, active.currentStatus)

        // Candle update for BTCUSDT 15M (different timeframe) must NOT affect 1H setup
        val candle15m = Candle(
            openTime = now + 2000,
            open = 94250.0,
            high = 94300.0,
            low = 94100.0,
            close = 94200.0,
            volume = 10.0,
            closeTime = now + 3000
        )
        repository.evaluateCandle("BTCUSDT", "15M", candle15m, nowMs = now + 3000)

        val stillPending = repository.getAllActiveTrackedSetupsOnce().first()
        assertEquals(TrackedSetupStatus.PENDING, stillPending.currentStatus)
    }

    @Test
    fun sellOrder_directionMonitoring_evaluatesCorrectly() {
        val sellPlan = createSampleSellPlan(
            symbol = "ETHUSDT",
            timeframe = "1H",
            entryMin = 3400.0,
            entryMax = 3450.0,
            stopLoss = 3550.0,
            tp1 = 3200.0,
            tp2 = 3000.0
        )
        val now = 1_000_000L
        val setup = TrackedPendingSetup.fromTradePlan(sellPlan, currentPrice = 3350.0, timestampMs = now)!!
        assertEquals(TrackedSetupStatus.PENDING, setup.currentStatus)

        // Price rallies into sell entry zone -> PRICE_TRIGGERED
        val triggered = PendingSetupEvaluationEngine.evaluateLiveTick(setup, currentPrice = 3425.0, nowMs = now + 1000)
        assertEquals(TrackedSetupStatus.PRICE_TRIGGERED, triggered.currentStatus)

        // Price drops to TP1 (3,200) -> TP1_REACHED
        val tp1 = PendingSetupEvaluationEngine.evaluateLiveTick(triggered, currentPrice = 3190.0, nowMs = now + 2000)
        assertEquals(TrackedSetupStatus.TP1_REACHED, tp1.currentStatus)

        // Price drops to TP2 (3,000) -> TP2_REACHED
        val tp2 = PendingSetupEvaluationEngine.evaluateLiveTick(tp1, currentPrice = 2990.0, nowMs = now + 3000)
        assertEquals(TrackedSetupStatus.TP2_REACHED, tp2.currentStatus)

        // If price had rallied above SL (3,550), it would reach STOP_LOSS_REACHED
        val slHit = PendingSetupEvaluationEngine.evaluateLiveTick(triggered, currentPrice = 3560.0, nowMs = now + 2500)
        assertEquals(TrackedSetupStatus.STOP_LOSS_REACHED, slHit.currentStatus)
        assertTrue(slHit.isTerminal)
    }
}
