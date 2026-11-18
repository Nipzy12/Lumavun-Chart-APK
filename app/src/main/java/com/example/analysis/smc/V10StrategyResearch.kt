package com.example.analysis.smc

import com.example.market.candle.Candle
import com.example.market.candle.Timeframe

/** Chronological holdout, not a full walk-forward or a proof of profitability. */
object V10StrategyResearch {
    data class Candidate(val quality: SmcEntryProV5Engine.QualityMode, val targetRr: Double)
    data class Result(val selected: Candidate, val training: SmcHistoricalReplay.Report, val holdout: SmcHistoricalReplay.Report)
    fun evaluate(history: List<Candle>, timeframe: Timeframe, costs: Double): Result? {
        val bars = history.filter { it.isClosed }.takeLast(1000)
        if (bars.size < 200 || bars.zipWithNext().any { (a,b) -> b.openTime <= a.openTime }) return null
        val cut = bars.size * 7 / 10
        val train = bars.take(cut)
        val test = bars.drop(cut)
        val candidates = listOf(SmcEntryProV5Engine.QualityMode.STRICT, SmcEntryProV5Engine.QualityMode.NORMAL)
            .flatMap { mode -> listOf(1.5,2.0,2.5).map { Candidate(mode,it) } }
        val ranked = candidates.mapNotNull { c ->
            SmcHistoricalReplay.run(train,timeframe,costs,c.quality,c.targetRr)?.takeIf { it.resolved >= 5 }?.let { c to it }
        }
        // Select ONLY on training; holdout is evaluated once and never used to tune the choice.
        val best = ranked.maxByOrNull { (_,r) -> (r.netR-r.maxDrawdownR)/r.resolved } ?: return null
        val holdout = SmcHistoricalReplay.run(test,timeframe,costs,best.first.quality,best.first.targetRr) ?: return null
        return Result(best.first,best.second,holdout)
    }
}
