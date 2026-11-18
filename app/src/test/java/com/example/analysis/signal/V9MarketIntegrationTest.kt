package com.example.analysis.signal

import com.example.analysis.smc.SmcHistoricalReplay
import com.example.analysis.technical.TechnicalSnapshotBuilder
import com.example.market.candle.Candle
import com.example.market.candle.Timeframe
import org.junit.Assert.*
import org.junit.Test

class V9MarketIntegrationTest {
    private fun candles(n: Int): List<Candle> = (0 until n).map { i ->
        val start = 1800000000000L + i * 60000L
        Candle(start, 100.0, 101.0, 99.0, 100.0, 100.0, start + 59999L, true)
    }
    @Test fun snapshotKeepsEnoughCandlesForRiskGuardian() {
        val snapshot = TechnicalSnapshotBuilder.build("BTCUSDT", Timeframe.M1, candles(80))!!
        assertTrue(snapshot.recentCandles.size >= SignalValidationEngine.MIN_CANDLES_REQUIRED)
        assertEquals(80, snapshot.replayCandles.size)
    }
    @Test fun flatHistoryReportsNoInventedWins() {
        val report = SmcHistoricalReplay.run(candles(80), Timeframe.M1, 6.0)!!
        assertEquals(80, report.processed)
        assertEquals(0, report.resolved)
        assertNull(report.winRate)
        assertEquals(0.0, report.netR, 0.0)
    }
    @Test fun badOrInsufficientHistoryIsNotBacktested() {
        assertNull(SmcHistoricalReplay.run(candles(30), Timeframe.M1, 6.0))
        assertNull(SmcHistoricalReplay.run(candles(80).reversed(), Timeframe.M1, 6.0))
        assertNull(SmcHistoricalReplay.run(candles(80), Timeframe.D1, 6.0))
        assertNull(SmcHistoricalReplay.run(candles(80), Timeframe.M1, Double.NaN))
    }
}
