package com.example.analysis.smc

import com.example.market.candle.Candle
import com.example.market.candle.Timeframe
import kotlin.math.abs
import kotlin.math.max

/** Bounded historical SMC replay; neither AI backtest nor live execution. */
object SmcHistoricalReplay {
    data class Report(val processed: Int, val wins: Int, val losses: Int, val open: Int,
        val pending: Int, val grossR: Double, val netR: Double, val maxDrawdownR: Double,
        val tickAssumption: Double, val lastCloseTime: Long) {
        val resolved: Int get() = wins + losses
        val winRate: Double? get() = if (resolved == 0) null else wins * 100.0 / resolved
    }

    fun run(input: List<Candle>, timeframe: Timeframe, costBpsPerSide: Double,
        quality: SmcEntryProV5Engine.QualityMode = SmcEntryProV5Engine.QualityMode.STRICT,
        targetRr: Double = 2.0): Report? {
        val htf = timeframe.higherTimeframe() ?: return null
        if (!costBpsPerSide.isFinite() || costBpsPerSide !in 0.0..100.0 || !targetRr.isFinite() || targetRr !in 1.35..4.0) return null
        val candles = input.filter { it.isClosed }.takeLast(1000)
        if (candles.size < 60 || candles.zipWithNext().any { (a,b) -> b.openTime <= a.openTime } ||
            candles.any { !it.isValid() || !listOf(it.open,it.high,it.low,it.close,it.volume).all { n -> n.isFinite() } ||
                it.high < maxOf(it.open,it.close) || it.low > minOf(it.open,it.close) || it.closeTime <= it.openTime }) return null
        // Price-relative numerical assumption; true instrument tick size is not available here.
        val tick = (candles.first().close * 1e-7).coerceAtLeast(1e-10)
        val cfg = SmcEntryProV5Engine.Config(htfTimeframe = htf, setupHistory = candles.size, qualityMode = quality, rrTarget = targetRr)
        val result = SmcEntryProV5Engine.calculate(candles, timeframe, cfg, tick)
        if (result.htfUnavailable || result.processedClosedBars != candles.size) return null
        var gross = 0.0
        var net = 0.0
        var peak = 0.0
        var dd = 0.0
        for (trade in result.trades.sortedBy { it.endIndex }) {
            if (trade.outcome == SmcEntryProV5Engine.Outcome.LIVE) continue
            val risk = abs(trade.entry - trade.sl)
            val exit = if (trade.outcome == SmcEntryProV5Engine.Outcome.TP) trade.tp else trade.sl
            if (risk <= 0 || !risk.isFinite() || exit <= 0 || !exit.isFinite()) return null
            val r = if (trade.outcome == SmcEntryProV5Engine.Outcome.TP) abs(exit - trade.entry) / risk else -1.0
            val costR = (trade.entry + exit) * (costBpsPerSide / 10000.0) / risk
            if (!r.isFinite() || !costR.isFinite()) return null
            gross += r
            net += r - costR
            peak = max(peak, net)
            dd = max(dd, peak - net)
        }
        return Report(result.processedClosedBars, result.tpCount, result.slCount,
            result.trades.count { it.outcome == SmcEntryProV5Engine.Outcome.LIVE }, result.pending.size,
            gross, net, dd, tick, candles.last().closeTime)
    }
}
