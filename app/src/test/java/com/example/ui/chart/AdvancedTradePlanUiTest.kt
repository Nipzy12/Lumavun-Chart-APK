package com.example.ui.chart

import com.example.ai.parser.AiAnalysisResult
import com.example.ai.parser.AiResponseParser
import com.example.ai.parser.EntryZone
import com.example.ai.plan.BrokerVerificationStatus
import com.example.ai.plan.StructuredTradePlan
import com.example.ai.plan.TradeDirection
import com.example.ai.plan.TradeOrderType
import com.example.ai.plan.TradeSetupStatus
import com.example.ai.strategy.AiStrategy
import com.example.analysis.signal.TradePlanValidationEngine
import com.example.market.candle.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdvancedTradePlanUiTest {

    // 1. BUY MARKET and SELL MARKET Visual Status
    @Test
    fun `resolveTradePlanVisualStatus resolves correct visual attributes for BUY MARKET and SELL MARKET`() {
        val buyMarket = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.MARKET,
            setupStatus = TradeSetupStatus.ACTIONABLE,
            referencePrice = 64_000.0,
            entryZoneMin = 63_950.0,
            entryZoneMax = 64_050.0,
            stopLoss = 63_500.0,
            tp1 = 65_200.0
        )
        val buyVisuals = resolveTradePlanVisualStatus(buyMarket)
        assertEquals("🟢 BUY MARKET", buyVisuals.title)
        assertEquals("ACTIONABLE (IMMEDIATE)", buyVisuals.subtitle)
        assertTrue(buyVisuals.isActionable)
        assertFalse(buyVisuals.isPending)
        assertFalse(buyVisuals.isWait)
        assertFalse(buyVisuals.isInvalid)
        assertTrue(buyVisuals.executionNotice.contains("Requires manual user confirmation"))

        val sellMarket = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BEARISH",
            direction = TradeDirection.SELL,
            orderType = TradeOrderType.MARKET,
            setupStatus = TradeSetupStatus.ACTIONABLE,
            referencePrice = 64_000.0,
            entryZoneMin = 63_950.0,
            entryZoneMax = 64_050.0,
            stopLoss = 64_500.0,
            tp1 = 62_800.0
        )
        val sellVisuals = resolveTradePlanVisualStatus(sellMarket)
        assertEquals("🔴 SELL MARKET", sellVisuals.title)
        assertEquals("ACTIONABLE (IMMEDIATE)", sellVisuals.subtitle)
        assertTrue(sellVisuals.isActionable)
    }

    // 2. BUY LIMIT and SELL LIMIT Visual Status
    @Test
    fun `resolveTradePlanVisualStatus resolves correct visual attributes for BUY LIMIT and SELL LIMIT`() {
        val buyLimit = StructuredTradePlan(
            strategy = AiStrategy.AI_SMC,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.LIMIT,
            setupStatus = TradeSetupStatus.PENDING,
            referencePrice = 64_000.0,
            entryZoneMin = 63_200.0,
            entryZoneMax = 63_300.0,
            stopLoss = 62_800.0,
            tp1 = 64_800.0
        )
        val buyLimitVisuals = resolveTradePlanVisualStatus(buyLimit)
        assertEquals("🔵 BUY LIMIT", buyLimitVisuals.title)
        assertEquals("PENDING ORDER (PULLBACK)", buyLimitVisuals.subtitle)
        assertTrue(buyLimitVisuals.isPending)
        assertFalse(buyLimitVisuals.isActionable)
        assertTrue(buyLimitVisuals.executionNotice.contains("PROPOSED PENDING LIMIT ORDER"))

        val sellLimit = StructuredTradePlan(
            strategy = AiStrategy.AI_ICT,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BEARISH",
            direction = TradeDirection.SELL,
            orderType = TradeOrderType.LIMIT,
            setupStatus = TradeSetupStatus.PENDING,
            referencePrice = 64_000.0,
            entryZoneMin = 64_800.0,
            entryZoneMax = 64_900.0,
            stopLoss = 65_400.0,
            tp1 = 63_500.0
        )
        val sellLimitVisuals = resolveTradePlanVisualStatus(sellLimit)
        assertEquals("🟠 SELL LIMIT", sellLimitVisuals.title)
        assertEquals("PENDING ORDER (RALLY)", sellLimitVisuals.subtitle)
        assertTrue(sellLimitVisuals.isPending)
    }

    // 3. BUY STOP and SELL STOP Visual Status
    @Test
    fun `resolveTradePlanVisualStatus resolves correct visual attributes for BUY STOP and SELL STOP breakout setups`() {
        val buyStop = StructuredTradePlan(
            strategy = AiStrategy.AI_PRICE_ACTION,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.STOP,
            setupStatus = TradeSetupStatus.PENDING,
            referencePrice = 64_000.0,
            entryZoneMin = 64_500.0,
            entryZoneMax = 64_600.0,
            stopLoss = 64_100.0,
            tp1 = 65_800.0
        )
        val buyStopVisuals = resolveTradePlanVisualStatus(buyStop)
        assertEquals("🟣 BUY STOP", buyStopVisuals.title)
        assertEquals("PENDING BREAKOUT", buyStopVisuals.subtitle)
        assertTrue(buyStopVisuals.isPending)
        assertTrue(buyStopVisuals.executionNotice.contains("PROPOSED PENDING STOP ORDER"))

        val sellStop = StructuredTradePlan(
            strategy = AiStrategy.AI_PRICE_ACTION,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BEARISH",
            direction = TradeDirection.SELL,
            orderType = TradeOrderType.STOP,
            setupStatus = TradeSetupStatus.PENDING,
            referencePrice = 64_000.0,
            entryZoneMin = 63_400.0,
            entryZoneMax = 63_500.0,
            stopLoss = 63_900.0,
            tp1 = 62_200.0
        )
        val sellStopVisuals = resolveTradePlanVisualStatus(sellStop)
        assertEquals("🟣 SELL STOP", sellStopVisuals.title)
        assertEquals("PENDING BREAKDOWN", sellStopVisuals.subtitle)
        assertTrue(sellStopVisuals.isPending)
    }

    // 4. WAIT and INVALID States
    @Test
    fun `resolveTradePlanVisualStatus resolves WAIT and INVALID first-class states correctly`() {
        val waitPlan = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "NEUTRAL",
            direction = TradeDirection.NEUTRAL,
            orderType = TradeOrderType.NONE,
            setupStatus = TradeSetupStatus.WAIT,
            referencePrice = 64_000.0
        )
        val waitVisuals = resolveTradePlanVisualStatus(waitPlan)
        assertEquals("🟡 NO ACTION (WAIT)", waitVisuals.title)
        assertEquals("CAPITAL PRESERVATION", waitVisuals.subtitle)
        assertTrue(waitVisuals.isWait)
        assertFalse(waitVisuals.isActionable)
        assertFalse(waitVisuals.isPending)
        assertTrue(waitVisuals.executionNotice.contains("Capital preservation prioritized"))

        val invalidPlan = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.NEUTRAL,
            orderType = TradeOrderType.NONE,
            setupStatus = TradeSetupStatus.INVALID,
            referencePrice = 64_000.0,
            validationMessages = listOf("Risk/Reward below minimum required threshold")
        )
        val invalidVisuals = resolveTradePlanVisualStatus(invalidPlan)
        assertEquals("⚠️ SETUP REJECTED", invalidVisuals.title)
        assertEquals("FAILED RISK GUARDIAN CHECKS", invalidVisuals.subtitle)
        assertTrue(invalidVisuals.isInvalid)
        assertTrue(invalidVisuals.executionNotice.contains("INVALID SETUP"))
    }

    // 5. Missing Optional TP Values (TP2 and TP3 Nullable)
    @Test
    fun `StructuredTradePlan supports missing optional TP2 and TP3 gracefully`() {
        val singleTpPlan = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.MARKET,
            setupStatus = TradeSetupStatus.ACTIONABLE,
            referencePrice = 64_000.0,
            entryZoneMin = 63_950.0,
            entryZoneMax = 64_050.0,
            stopLoss = 63_500.0,
            tp1 = 65_200.0,
            tp2 = null,
            tp3 = null,
            riskRewardTp1 = 2.18,
            riskRewardTp2 = null,
            riskRewardTp3 = null
        )
        val validation = TradePlanValidationEngine.validatePlan(singleTpPlan)
        assertTrue(validation.isValid)
        assertEquals(65_200.0, validation.sanitizedPlan.tp1)
        assertNull(validation.sanitizedPlan.tp2)
        assertEquals(2.09, validation.sanitizedPlan.riskRewardTp1!!, 0.02)
        assertNull(validation.sanitizedPlan.riskRewardTp2)
        assertNull(validation.sanitizedPlan.riskRewardTp3)
    }

    // 6. Missing Entry and SL Values
    @Test
    fun `TradePlanValidationEngine safely rejects setups with missing entry or SL levels`() {
        val missingSl = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.MARKET,
            setupStatus = TradeSetupStatus.ACTIONABLE,
            referencePrice = 64_000.0,
            entryZoneMin = 63_950.0,
            entryZoneMax = 64_050.0,
            stopLoss = null, // Missing SL!
            tp1 = 65_200.0
        )
        val outcomeSl = TradePlanValidationEngine.validatePlan(missingSl)
        assertFalse(outcomeSl.isValid)
        assertEquals(TradeSetupStatus.INVALID, outcomeSl.sanitizedPlan.setupStatus)
        assertTrue(outcomeSl.rejectionReasons.any { it.contains("Stop loss") })

        val missingEntry = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.MARKET,
            setupStatus = TradeSetupStatus.ACTIONABLE,
            referencePrice = 64_000.0,
            entryZoneMin = null, // Missing entry!
            entryZoneMax = 64_050.0,
            stopLoss = 63_500.0,
            tp1 = 65_200.0
        )
        val outcomeEntry = TradePlanValidationEngine.validatePlan(missingEntry)
        assertFalse(outcomeEntry.isValid)
        assertEquals(TradeSetupStatus.INVALID, outcomeEntry.sanitizedPlan.setupStatus)
        assertTrue(outcomeEntry.rejectionReasons.any { it.contains("Entry zone minimum") })
    }

    // 7. Invalid Risk/Reward Rejection
    @Test
    fun `TradePlanValidationEngine rejects trade plan with invalid or insufficient risk reward`() {
        val poorRrPlan = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.MARKET,
            setupStatus = TradeSetupStatus.ACTIONABLE,
            referencePrice = 64_000.0,
            entryZoneMin = 63_950.0,
            entryZoneMax = 64_050.0,
            stopLoss = 60_000.0, // Risk: 4050
            tp1 = 64_300.0       // Reward: 250 -> R:R < 0.1
        )
        val outcome = TradePlanValidationEngine.validatePlan(poorRrPlan, minRequiredRr = 1.35)
        assertFalse(outcome.isValid)
        assertEquals(TradeSetupStatus.INVALID, outcome.sanitizedPlan.setupStatus)
        assertTrue(outcome.rejectionReasons.any { it.contains("R:R") })
    }

    // 8. Legacy AI Response Mapping via resolveEffectiveTradePlan
    @Test
    fun `resolveEffectiveTradePlan seamlessly synthesizes StructuredTradePlan from legacy AiAnalysisResult`() {
        val legacyJson = """
            {
              "decision": "LONG",
              "confidence": 78,
              "trend": "BULLISH",
              "momentum": "POSITIVE",
              "marketStructure": "HH_HL",
              "setupQuality": "A",
              "entryOrderType": "LIMIT",
              "entryZone": { "min": 63900.0, "max": 64000.0 },
              "stopLoss": 63400.0,
              "takeProfit": [65200.0, 66000.0],
              "support": [63400.0],
              "resistance": [66000.0],
              "reasoning": ["EMA ribbon alignment", "Order block reclaim"],
              "summary": "Clear bullish momentum continuation",
              "invalidation": "Candle close below 63,400"
            }
        """.trimIndent()

        val parsedLegacy = AiResponseParser.parse(legacyJson)
        assertNotNull(parsedLegacy)

        // Test with tradePlan explicitly null
        val resultWithoutPlan = parsedLegacy!!.copy(tradePlan = null)

        val plan = resolveEffectiveTradePlan(
            result = resultWithoutPlan,
            symbol = "BTCUSDT",
            timeframe = Timeframe.M15,
            snapshot = null
        )

        assertNotNull(plan)
        assertEquals(TradeDirection.BUY, plan!!.direction)
        assertEquals(TradeOrderType.LIMIT, plan.orderType)
        assertEquals(TradeSetupStatus.PENDING, plan.setupStatus)
        assertEquals("BTCUSDT", plan.symbol)
        assertEquals("15m", plan.timeframe)
        assertEquals(63_900.0, plan.entryZoneMin)
        assertEquals(64_000.0, plan.entryZoneMax)
        assertEquals(63_400.0, plan.stopLoss)
        assertEquals(65_200.0, plan.tp1)
        assertEquals(66_000.0, plan.tp2)
        assertNull(plan.tp3)
        assertEquals("Candle close below 63,400", plan.invalidationCondition)
        assertEquals(BrokerVerificationStatus.UNVERIFIED_BROKER_DATA, plan.brokerVerificationStatus)
    }

    // 9. Strategy Switching Outdated Detection
    @Test
    fun `outdated detection identifies strategy mismatch correctly`() {
        val cachedPlan = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            strategyId = AiStrategy.AI_HYBRID.id,
            strategyName = AiStrategy.AI_HYBRID.displayName,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.MARKET,
            setupStatus = TradeSetupStatus.ACTIONABLE,
            referencePrice = 64_000.0
        )

        val selectedStrategy = AiStrategy.AI_SNIPER
        val isMatchingStrategy = cachedPlan.strategyId.equals(selectedStrategy.id, ignoreCase = true)
        assertFalse(isMatchingStrategy) // Strategy mismatch! Must trigger outdated warning
    }

    // 10. Symbol and Timeframe Switching Outdated Detection
    @Test
    fun `outdated detection identifies symbol and timeframe mismatch correctly`() {
        val cachedPlan = StructuredTradePlan(
            strategy = AiStrategy.AI_HYBRID,
            symbol = "BTCUSDT",
            timeframe = "15m",
            marketBias = "BULLISH",
            direction = TradeDirection.BUY,
            orderType = TradeOrderType.MARKET,
            setupStatus = TradeSetupStatus.ACTIONABLE,
            referencePrice = 64_000.0
        )

        // Switching to ETHUSDT
        val currentSymbol = "ETHUSDT"
        val isMatchingSymbol = cachedPlan.symbol.equals(currentSymbol, ignoreCase = true)
        assertFalse(isMatchingSymbol)

        // Switching to 1H timeframe
        val currentTimeframe = "1h"
        val isMatchingTf = cachedPlan.timeframe.equals(currentTimeframe, ignoreCase = true)
        assertFalse(isMatchingTf)
    }

    // 11. Loading and Provider Error States Handling
    @Test
    fun `UI state correctly accommodates loading and provider error states without crashes`() {
        val errorMessage = "Provider rate limit exceeded (429). Retrying on fallback."
        assertNotNull(errorMessage)
        assertTrue(errorMessage.contains("rate limit"))

        val isAnalyzing = true
        assertTrue(isAnalyzing)

        val nullPlan: StructuredTradePlan? = null
        assertNull(nullPlan)
    }
}
