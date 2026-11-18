package com.example.analysis.signal

import com.example.ai.plan.StructuredTradePlan
import com.example.ai.plan.TradeDirection
import com.example.ai.plan.TradeOrderType
import com.example.ai.plan.TradeSetupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TradePlanRiskGuardianTest {
    private fun plan(
        direction: TradeDirection,
        entryMin: Double = 100.0,
        entryMax: Double = 110.0,
        stop: Double = 90.0,
        target: Double = 130.0,
        reference: Double = 105.0,
        type: TradeOrderType = TradeOrderType.MARKET
    ) = StructuredTradePlan(
        symbol = "TEST",
        timeframe = "M15",
        marketBias = if (direction == TradeDirection.BUY) "BULLISH" else "BEARISH",
        direction = direction,
        orderType = type,
        setupStatus = if (type == TradeOrderType.MARKET) TradeSetupStatus.ACTIONABLE else TradeSetupStatus.PENDING,
        referencePrice = reference,
        entryZoneMin = entryMin,
        entryZoneMax = entryMax,
        stopLoss = stop,
        tp1 = target
    )

    @Test
    fun `BUY zone with passing midpoint and failing worst entry is rejected`() {
        val result = TradePlanValidationEngine.validatePlan(plan(TradeDirection.BUY), 1.35)
        assertFalse(result.isValid)
        assertEquals(TradeSetupStatus.INVALID, result.sanitizedPlan.setupStatus)
        assertEquals("WAIT", result.sanitizedPlan.orderActionLabel)
        assertNull(result.sanitizedPlan.entryZoneMin)
        assertTrue(result.rejectionReasons.any { it.contains("worst-case R:R") })
    }

    @Test
    fun `SELL LIMIT zone with passing midpoint and failing worst entry is rejected`() {
        val result = TradePlanValidationEngine.validatePlan(
            plan(TradeDirection.SELL, stop = 120.0, target = 80.0, reference = 95.0, type = TradeOrderType.LIMIT),
            1.35
        )
        assertFalse(result.isValid)
        assertEquals("WAIT", result.sanitizedPlan.orderActionLabel)
        assertNull(result.sanitizedPlan.tp1)
    }

    @Test
    fun `ratio rounded up to minimum still fails exact threshold`() {
        val result = TradePlanValidationEngine.validatePlan(
            plan(TradeDirection.BUY, entryMax = 100.0, target = 113.49, reference = 100.0),
            1.35
        )
        assertFalse(result.isValid)
    }

    @Test
    fun `BUY and SELL at exact worst entry threshold remain valid`() {
        val buy = TradePlanValidationEngine.validatePlan(plan(TradeDirection.BUY, target = 137.0), 1.35)
        val sell = TradePlanValidationEngine.validatePlan(plan(TradeDirection.SELL, stop = 120.0, target = 73.0), 1.35)
        assertTrue(buy.isValid)
        assertTrue(sell.isValid)
        assertEquals(1.35, buy.sanitizedPlan.riskRewardTp1!!, 1e-9)
        assertEquals(1.35, sell.sanitizedPlan.riskRewardTp1!!, 1e-9)
    }

    @Test
    fun `invalid minimum threshold fails closed`() {
        for (threshold in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) {
            assertFalse(TradePlanValidationEngine.validatePlan(plan(TradeDirection.BUY), threshold).isValid)
        }
    }
}
