package com.example.analysis.smc

import com.example.analysis.smc.SmcEntryProV5Engine.Config
import com.example.analysis.smc.SmcEntryProV5Engine.Direction
import com.example.analysis.smc.SmcEntryProV5Engine.EntryChoice
import com.example.analysis.smc.SmcEntryProV5Engine.HtfMode
import com.example.analysis.smc.SmcEntryProV5Engine.Model
import com.example.analysis.smc.SmcEntryProV5Engine.Outcome
import com.example.analysis.smc.SmcEntryProV5Engine.QualityMode
import com.example.market.candle.Candle
import com.example.market.candle.Timeframe
import org.junit.Assert.*
import org.junit.Test

class SmcEntryProV5EngineTest {
    private fun candle(index: Int, o: Double, h: Double, l: Double, c: Double,
                       closed: Boolean = true): Candle {
        val start = 1_700_000_000_000L + index * Timeframe.M15.durationMs
        return Candle(start, o, h, l, c, 100.0, start + Timeframe.M15.durationMs - 1L, closed)
    }

    private val withoutHtf = Config(htfMode = HtfMode.OFF, swingLen = 1,
        qualityMode = QualityMode.LOOSE)

    @Test fun scoreIsHeuristicAndUsesPineWeights() {
        assertEquals(100, SmcEntryProV5Engine.qualityScore(
            impulseAtr = 1.5, bodyEfficiency = 0.85, structureOK = true,
            htfOK = true, confluenceOK = true, cleanDeparture = true))
        assertEquals(0, SmcEntryProV5Engine.qualityScore(
            impulseAtr = 0.0, bodyEfficiency = 0.1, structureOK = false,
            htfOK = false, confluenceOK = false, cleanDeparture = false))
        assertEquals(85, QualityMode.STRICT.threshold)
        assertEquals(75, QualityMode.NORMAL.threshold)
        assertEquals(65, QualityMode.LOOSE.threshold)
    }

    @Test fun midpointEntryAndRiskRewardMatchPineRules() {
        val p = SmcEntryProV5Engine.createPending(
            Model.M3_FVG, Direction.BUY, 24, 104.0, 100.0, 99.0,
            atr = 2.0, close = 107.0, tickSize = 0.01, cfg = withoutHtf)
        assertNotNull(p)
        assertEquals(102.0, p!!.entry, 1e-8)
        assertEquals(98.76, p.sl, 1e-8)
        assertEquals(108.48, p.tp, 1e-8)
        val proximal = SmcEntryProV5Engine.createPending(
            Model.M3_FVG, Direction.SELL, 25, 104.0, 100.0, 105.0,
            atr = 2.0, close = 97.0, tickSize = 0.01,
            cfg = withoutHtf.copy(entryChoice = EntryChoice.PROXIMAL))
        assertNotNull(proximal)
        assertEquals(100.0, proximal!!.entry, 1e-8)
        assertTrue(proximal.sl > proximal.entry)
        assertTrue(proximal.tp < proximal.entry)
    }

    @Test fun invalidPriceGeometryCannotCreateAPendingOrder() {
        assertNull(SmcEntryProV5Engine.createPending(
            Model.M2_OB, Direction.BUY, 3, 102.0, 100.0, 99.0,
            atr = 2.0, close = 99.0, tickSize = 0.01, cfg = withoutHtf))
        assertNull(SmcEntryProV5Engine.createPending(
            Model.M2_OB, Direction.BUY, 3, 102.0, 100.0, 99.0,
            atr = Double.NaN, close = 105.0, tickSize = 0.01, cfg = withoutHtf))
        assertEquals(0.0, SmcEntryProV5Engine.overlapRatio(105.0, 100.0, 99.0, 95.0), 1e-8)
    }

    @Test fun entryCandleNeverResolvesToTpAndStopWinsOnSameLaterCandle() {
        val trade = SmcEntryProV5Engine.Trade(
            Model.M1_SWEEP_OB, Direction.BUY, 20, 100.0, 97.0, 106.0, 20)
        SmcEntryProV5Engine.updateTrade(trade, 20, candle(20, 100.0, 110.0, 96.0, 102.0))
        assertEquals(Outcome.LIVE, trade.outcome)
        assertEquals(20, trade.endIndex)
        SmcEntryProV5Engine.updateTrade(trade, 21, candle(21, 100.0, 108.0, 95.0, 101.0))
        assertEquals(Outcome.SL, trade.outcome)
        assertEquals(21, trade.endIndex)
        SmcEntryProV5Engine.updateTrade(trade, 22, candle(22, 101.0, 120.0, 90.0, 110.0))
        assertEquals(21, trade.endIndex) // follow candle STOP ON HIT
    }

    @Test fun unclosedBarsNeverTriggerTradeExit() {
        val trade = SmcEntryProV5Engine.Trade(
            Model.M3_FVG, Direction.SELL, 20, 100.0, 104.0, 92.0, 20)
        SmcEntryProV5Engine.updateTrade(trade, 21, candle(21, 101.0, 106.0, 90.0, 95.0, closed = false))
        assertEquals(Outcome.LIVE, trade.outcome)
        assertEquals(20, trade.endIndex)
        SmcEntryProV5Engine.updateTrade(trade, 21, candle(21, 101.0, 103.0, 90.0, 95.0))
        assertEquals(Outcome.TP, trade.outcome)
        assertEquals(21, trade.endIndex)
    }

    @Test fun pendingCannotFillOnBirthCandle() {
        val candles = mutableListOf<Candle>()
        repeat(16) { i ->
            candles.add(candle(i, 100.0, 102.0, 98.0, 101.0))
        }
        candles.add(candle(16, 101.0, 105.0, 99.0, 102.0))
        candles.add(candle(17, 102.0, 104.0, 99.0, 101.0))
        candles.add(candle(18, 102.0, 109.0, 101.0, 108.0))
        candles.add(candle(19, 106.0, 109.0, 105.0, 107.0))
        candles.add(candle(20, 107.0, 114.0, 107.0, 113.0))
        candles.add(candle(21, 111.0, 113.0, 110.0, 112.5))
        val r = SmcEntryProV5Engine.calculate(candles, Timeframe.M15,
            withoutHtf.copy(useM1 = false, useM2 = false, useM3 = true))
        assertTrue("FVG zone should be identified after confirmed displacement",
            r.zones.any { it.kind == SmcEntryProV5Engine.ZoneKind.FVG && it.bornIndex == 21 })
        assertTrue("M3 pending must appear only on creation candle",
            r.pending.any { it.model == Model.M3_FVG && it.bornIndex == 21 })
        assertTrue("No same-candle pending entry", r.trades.isEmpty())
        val birthPending = r.pending.last()
        val entryCandle = candle(22, 110.5, 111.0, birthPending.entry - 0.1, 110.0)
        candles.add(entryCandle)
        val next = SmcEntryProV5Engine.calculate(candles, Timeframe.M15,
            withoutHtf.copy(useM1 = false, useM2 = false, useM3 = true))
        assertTrue("Next candle retest can fill", next.trades.any { it.model == Model.M3_FVG && it.entryIndex == 22 })
    }

    @Test fun higherTimeframeMustBeAboveChartTimeframe() {
        val candles = listOf(candle(0, 100.0, 102.0, 98.0, 101.0))
        val result = SmcEntryProV5Engine.calculate(candles, Timeframe.H4,
            Config(htfMode = HtfMode.BIAS, htfTimeframe = Timeframe.H4))
        assertTrue(result.htfUnavailable)
        assertTrue(result.trades.isEmpty())
    }
}
