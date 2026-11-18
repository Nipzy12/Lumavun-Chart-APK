package com.example.analysis.signal

import com.example.analysis.smc.V10StrategyResearch
import com.example.market.candle.Candle
import com.example.market.candle.Timeframe
import org.junit.Assert.*
import org.junit.Test

class V10ResearchTest {
    @Test fun criticalContradictionsVetoButMissingEvidenceIsNotInvented() {
        val missing = V10Supervisor.review(null)
        assertFalse(missing.blocksSimulationEntry)
        assertTrue(missing.reviews.all { it.verdict == V10Supervisor.Verdict.UNAVAILABLE })
        val e = TechnicalEvidenceScore.evaluate(mapOf(EvidenceCategory.MULTI_TIMEFRAME to EvidenceState.CONTRADICTS), 2.0, 1.35)
        assertTrue(V10Supervisor.review(e).blocksSimulationEntry)
    }
    @Test fun adverseFillRiskIsMeasuredWithoutRounding() {
        assertEquals(2.0,V10Supervisor.fillRiskReward("LONG",100.0,90.0,120.0,0.0)!!,1e-9)
        assertEquals(2.0,V10Supervisor.fillRiskReward("SHORT",100.0,110.0,80.0,0.0)!!,1e-9)
        assertTrue(V10Supervisor.fillRiskReward("LONG",100.0,90.0,113.6,20.0)!! < 1.35)
        assertNull(V10Supervisor.fillRiskReward("SHORT",Double.NaN,110.0,80.0,0.0))
    }
    @Test fun noOptimizerChoiceWithoutResolvedTrainingTrades() {
        val bars = (0 until 240).map { i ->
            val t = 1800000000000L + i * 60000L
            Candle(t,100.0,101.0,99.0,100.0,10.0,t+59999,true)
        }
        assertNull(V10StrategyResearch.evaluate(bars,Timeframe.M1,6.0))
        assertNull(V10StrategyResearch.evaluate(bars.reversed(),Timeframe.M1,6.0))
    }
}
