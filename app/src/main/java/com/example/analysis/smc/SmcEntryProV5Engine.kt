package com.example.analysis.smc

import com.example.market.candle.Candle
import com.example.market.candle.Timeframe
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Native, closed-candle port of the core rules in the user-supplied Pine v6
 * "SMC Entry Models PRO v5 | Follow Candle".
 *
 * It is an analytical indicator, NOT an exchange/broker order executor.
 * No retroactive signal backfill: pivots are known only after right-side bars,
 * HTF bias uses previously CLOSED HTF groups, and pending orders may fill only
 * after the candidate's birth candle.
 */
object SmcEntryProV5Engine {
    enum class Direction { BUY, SELL }
    enum class Model { M1_SWEEP_OB, M1_SWEEP_FVG, M2_OB, M3_FVG }
    enum class ZoneKind { FVG, OB }
    enum class Outcome { LIVE, TP, SL }
    enum class QualityMode(val threshold: Int) { STRICT(85), NORMAL(75), LOOSE(65) }
    enum class HtfMode { OFF, BIAS }
    enum class EntryChoice { MIDPOINT, PROXIMAL }

    data class Config(
        val useM1: Boolean = true,
        val useM2: Boolean = true,
        val useM3: Boolean = true,
        val swingLen: Int = 3,
        val obLookback: Int = 12,
        val sweepWindow: Int = 14,
        val sweepMinAtr: Double = 0.04,
        val shiftBodyAtr: Double = 0.55,
        val nearFvgBars: Int = 6,
        val minFvgAtr: Double = 0.12,
        val displaceAtr: Double = 1.0,
        val minBodyRatio: Double = 0.60,
        val minBreakBodyAtr: Double = 0.70,
        val minBreakCloseAtr: Double = 0.06,
        val minDepartureAtr: Double = 0.08,
        val maxObOriginAge: Int = 8,
        val minZoneWidthAtr: Double = 0.04,
        val maxZoneWidthAtr: Double = 2.2,
        val useVolumeFilter: Boolean = false,
        val volumeMultiplier: Double = 1.2,
        val qualityMode: QualityMode = QualityMode.STRICT,
        val htfMode: HtfMode = HtfMode.BIAS,
        val htfTimeframe: Timeframe = Timeframe.H4,
        val entryChoice: EntryChoice = EntryChoice.MIDPOINT,
        val rrTarget: Double = 2.0,
        val slAtrBuffer: Double = 0.12,
        val maxStopAtr: Double = 10.0,
        val pendingLife: Int = 40,
        val maxRetests: Int = 0,
        val consumeDepth: Double = 0.95,
        val invalidByWick: Boolean = false,
        val invalidBufferAtr: Double = 0.03,
        val maxZoneAge: Int = 250,
        val maxZoneDistance: Double = 12.0,
        val requireFvgOB: Boolean = true,
        val zoneHistory: Int = 25,
        val setupHistory: Int = 12,
        val structureHistory: Int = 45
    )

    data class Structure(
        val index: Int,
        val originIndex: Int,
        val price: Double,
        val label: String,
        val direction: Direction
    )

    data class Zone(
        val kind: ZoneKind,
        val direction: Direction,
        val originIndex: Int,
        val bornIndex: Int,
        val top: Double,
        val bottom: Double,
        val score: Int,
        var tests: Int = 0,
        var wasTouching: Boolean = false
    )

    data class Pending(
        val model: Model,
        val direction: Direction,
        val bornIndex: Int,
        val zoneTop: Double,
        val zoneBottom: Double,
        val entry: Double,
        val sl: Double,
        val tp: Double,
        var tests: Int = 0,
        var wasTouching: Boolean = false
    )

    data class Trade(
        val model: Model,
        val direction: Direction,
        val entryIndex: Int,
        val entry: Double,
        val sl: Double,
        val tp: Double,
        var endIndex: Int,
        var outcome: Outcome = Outcome.LIVE
    )

    data class Result(
        val structures: List<Structure>,
        val zones: List<Zone>,
        val pending: List<Pending>,
        val trades: List<Trade>,
        val htfUnavailable: Boolean,
        val processedClosedBars: Int
    ) {
        val tpCount: Int get() = trades.count { it.outcome == Outcome.TP }
        val slCount: Int get() = trades.count { it.outcome == Outcome.SL }
    }

    fun qualityScore(
        impulseAtr: Double,
        bodyEfficiency: Double,
        structureOK: Boolean,
        htfOK: Boolean,
        confluenceOK: Boolean,
        cleanDeparture: Boolean,
        cfg: Config = Config()
    ): Int {
        var score = 0
        score += when {
            impulseAtr >= cfg.displaceAtr * 1.25 -> 25
            impulseAtr >= cfg.displaceAtr -> 20
            impulseAtr >= cfg.displaceAtr * 0.65 -> 10
            else -> 0
        }
        score += when {
            bodyEfficiency >= cfg.minBodyRatio + 0.10 -> 15
            bodyEfficiency >= cfg.minBodyRatio -> 10
            else -> 0
        }
        if (structureOK) score += 20
        if (htfOK) score += 15
        if (confluenceOK) score += 15
        if (cleanDeparture) score += 10
        return score
    }

    fun createPending(
        model: Model,
        direction: Direction,
        bornIndex: Int,
        top: Double,
        bottom: Double,
        stopReference: Double,
        atr: Double,
        close: Double,
        tickSize: Double,
        cfg: Config = Config()
    ): Pending? {
        if (!listOf(top, bottom, stopReference, atr, close).all { it.isFinite() }) return null
        if (top <= bottom || atr <= 0.0 || tickSize <= 0.0) return null
        val buy = direction == Direction.BUY
        val entry = if (cfg.entryChoice == EntryChoice.MIDPOINT) {
            (top + bottom) / 2.0
        } else if (buy) top else bottom
        val sl = if (buy) min(stopReference, bottom) - cfg.slAtrBuffer * atr
                 else max(stopReference, top) + cfg.slAtrBuffer * atr
        val risk = abs(entry - sl)
        if (risk < tickSize * 2 || risk > cfg.maxStopAtr * atr) return null
        if (buy && !(sl < entry && entry < close)) return null
        if (!buy && !(sl > entry && entry > close)) return null
        return Pending(
            model, direction, bornIndex, top, bottom,
            entry, sl, entry + if (buy) risk * cfg.rrTarget else -risk * cfg.rrTarget
        )
    }

    /**
     * Conservative OHLC sequencing. Once filled, an SL touch wins over a TP
     * touch on the same later candle. Entry-candle SL also wins immediately;
     * an entry-candle TP alone is not counted as a win without tick sequencing.
     */
    fun updateTrade(trade: Trade, candleIndex: Int, candle: Candle) {
        if (!candle.isClosed || trade.outcome != Outcome.LIVE || candleIndex <= trade.entryIndex) return
        val slHit = if (trade.direction == Direction.BUY) candle.low <= trade.sl else candle.high >= trade.sl
        val tpHit = if (trade.direction == Direction.BUY) candle.high >= trade.tp else candle.low <= trade.tp
        trade.endIndex = candleIndex
        if (slHit) trade.outcome = Outcome.SL else if (tpHit) trade.outcome = Outcome.TP
    }

    fun overlapRatio(aTop: Double, aBottom: Double, bTop: Double, bBottom: Double): Double {
        val smaller = min(aTop - aBottom, bTop - bBottom)
        return if (smaller > 0) max(0.0, min(aTop, bTop) - max(aBottom, bBottom)) / smaller else 0.0
    }

    private data class FvgMemory(
        var at: Int = -1,
        var top: Double = 0.0,
        var bottom: Double = 0.0,
        var live: Boolean = false,
        var touches: Int = 0,
        var touching: Boolean = false
    )

    private fun atr14(candles: List<Candle>): DoubleArray {
        val out = DoubleArray(candles.size) { Double.NaN }
        var sum = 0.0
        var previous = 0.0
        for (i in candles.indices) {
            val c = candles[i]
            if (!c.isClosed || !c.isValid()) break
            val tr = if (i == 0) c.high - c.low else max(c.high - c.low, max(abs(c.high - previous), abs(c.low - previous)))
            if (i < 14) {
                sum += tr
                if (i == 13) out[i] = sum / 14.0
            } else {
                out[i] = (out[i - 1] * 13.0 + tr) / 14.0
            }
            previous = c.close
        }
        return out
    }

    private fun confirmedPivot(
        candles: List<Candle>,
        center: Int,
        len: Int,
        highPivot: Boolean
    ): Boolean {
        if (center < len || center + len >= candles.size) return false
        val value = if (highPivot) candles[center].high else candles[center].low
        for (j in center - len..center + len) {
            if (j == center) continue
            if (!candles[j].isClosed) return false
            val other = if (highPivot) candles[j].high else candles[j].low
            if (highPivot && other >= value || !highPivot && other <= value) return false
        }
        return true
    }

    // The first incomplete window in chart history is deliberately discarded.
    // A higher-TF trend becomes visible only AFTER its source candle closes.
    private fun htfBias(candles: List<Candle>, ltf: Timeframe, cfg: Config): IntArray {
        val result = IntArray(candles.size)
        if (cfg.htfMode == HtfMode.OFF) return result
        val duration = cfg.htfTimeframe.durationMs
        if (duration <= ltf.durationMs || duration % ltf.durationMs != 0L) return result
        val required = (duration / ltf.durationMs).toInt()
        val closedGroups = mutableListOf<Candle>()
        var bucket = Long.MIN_VALUE
        var inGroup = mutableListOf<Candle>()
        var lastPivotHigh = Double.NaN
        var lastPivotLow = Double.NaN
        var pivotHighBroken = false
        var pivotLowBroken = false
        var trend = 0

        fun completeGroup() {
            if (inGroup.size == required && inGroup.all { it.isClosed }) {
                val first = inGroup.first()
                val last = inGroup.last()
                closedGroups.add(
                    Candle(first.openTime, first.open,
                        inGroup.maxOf { it.high }, inGroup.minOf { it.low },
                        last.close, inGroup.sumOf { it.volume }, last.closeTime, true)
                )
                val k = closedGroups.lastIndex
                val center = k - 3
                if (confirmedPivot(closedGroups, center, 3, true)) {
                    lastPivotHigh = closedGroups[center].high
                    pivotHighBroken = false
                }
                if (confirmedPivot(closedGroups, center, 3, false)) {
                    lastPivotLow = closedGroups[center].low
                    pivotLowBroken = false
                }
                val priorClose = closedGroups.getOrNull(k - 1)?.close ?: last.close
                if (lastPivotHigh.isFinite() && !pivotHighBroken && last.close > lastPivotHigh && priorClose <= lastPivotHigh) {
                    trend = 1; pivotHighBroken = true
                }
                if (lastPivotLow.isFinite() && !pivotLowBroken && last.close < lastPivotLow && priorClose >= lastPivotLow) {
                    trend = -1; pivotLowBroken = true
                }
            }
        }

        for (i in candles.indices) {
            val c = candles[i]
            if (!c.isClosed || !c.isValid()) break
            val nextBucket = c.openTime / duration
            if (nextBucket != bucket) {
                if (bucket != Long.MIN_VALUE) completeGroup()
                inGroup = mutableListOf()
                bucket = nextBucket
            }
            // Only the last fully completed HTF group can affect this LTF bar.
            result[i] = trend
            inGroup.add(c)
        }
        return result
    }

    fun calculate(
        candles: List<Candle>,
        timeframe: Timeframe,
        cfg: Config = Config(),
        tickSize: Double = 0.00001
    ): Result {
        if (candles.isEmpty() || tickSize <= 0.0) return Result(emptyList(), emptyList(), emptyList(), emptyList(), false, 0)
        val unusableHtf = cfg.htfMode != HtfMode.OFF && cfg.htfTimeframe.durationMs <= timeframe.durationMs
        if (unusableHtf) return Result(emptyList(), emptyList(), emptyList(), emptyList(), true, 0)
        val atrs = atr14(candles)
        val htf = htfBias(candles, timeframe, cfg)
        val structures = mutableListOf<Structure>()
        val zones = mutableListOf<Zone>()
        val pendings = mutableListOf<Pending>()
        val trades = mutableListOf<Trade>()
        val bullFvg = FvgMemory()
        val bearFvg = FvgMemory()
        var swingHigh = Double.NaN; var swingLow = Double.NaN
        var hiAt = -1; var loAt = -1
        var hiBroken = false; var loBroken = false
        var hiSwept = false; var loSwept = false
        var dir = 0
        var bullSweepAt = -1; var bearSweepAt = -1
        var bullSweepLow = 0.0; var bearSweepHigh = 0.0
        var bullBreakAt = -1; var bearBreakAt = -1
        var processed = 0
        val len = cfg.swingLen.coerceIn(1, 20)

        fun widthOK(top: Double, bottom: Double, atr: Double): Boolean =
            top.isFinite() && bottom.isFinite() && atr > 0.0 && top > bottom &&
                (top - bottom) / atr in cfg.minZoneWidthAtr..cfg.maxZoneWidthAtr

        fun insertZone(kind: ZoneKind, side: Direction, top: Double, bottom: Double, at: Int, born: Int, score: Int) {
            if (score < cfg.qualityMode.threshold || !widthOK(top, bottom, atrs[born])) return
            val related = zones.filter {
                it.direction == side && born - it.bornIndex <= cfg.nearFvgBars * 2 &&
                    overlapRatio(top, bottom, it.top, it.bottom) >= 0.65
            }
            if (related.any { it.score >= score }) return
            zones.removeAll(related)
            zones.add(Zone(kind, side, at, born, top, bottom, score))
            while (zones.size > cfg.zoneHistory) zones.removeAt(0)
        }

        fun enqueue(model: Model, side: Direction, top: Double, bottom: Double, stop: Double, at: Int, atr: Double, close: Double) {
            val p = createPending(model, side, at, top, bottom, stop, atr, close, tickSize, cfg) ?: return
            if (pendings.any { it.direction == side && it.model == model &&
                    overlapRatio(top, bottom, it.zoneTop, it.zoneBottom) >= 0.8 }) return
            pendings.add(p)
            while (pendings.size > 14) pendings.removeAt(0)
        }

        for (i in candles.indices) {
            val c = candles[i]
            if (!c.isClosed || !c.isValid()) break
            processed++
            if (i < 14 || !atrs[i].isFinite() || atrs[i] <= 0.0) continue
            val atr = atrs[i]
            val prev = candles[i - 1]
            val older = candles[i - 2]

            // Previously active trades extend to this closed candle, then freeze on hit.
            var busy = false
            for (trade in trades) {
                if (trade.outcome == Outcome.LIVE) {
                    busy = true
                    updateTrade(trade, i, c)
                }
            }

            // A limit can fill only AFTER birth; stop wins if touched on entry bar.
            for (j in pendings.indices.reversed()) {
                val p = pendings[j]
                val buy = p.direction == Direction.BUY
                val touched = i > p.bornIndex && c.low <= p.entry && c.high >= p.entry
                val touchedZone = i > p.bornIndex && c.low <= p.zoneTop && c.high >= p.zoneBottom
                if (touchedZone && !p.wasTouching) p.tests++
                p.wasTouching = touchedZone
                val expired = i - p.bornIndex > cfg.pendingLife
                val buffer = cfg.invalidBufferAtr * atr
                val invalid = i > p.bornIndex && if (cfg.invalidByWick) {
                    if (buy) c.low < p.zoneBottom - buffer else c.high > p.zoneTop + buffer
                } else {
                    if (buy) c.close < p.zoneBottom - buffer else c.close > p.zoneTop + buffer
                }
                val wrongBias = cfg.htfMode != HtfMode.OFF && htf[i] != if (buy) 1 else -1
                if (expired || wrongBias || (touchedZone && p.tests > cfg.maxRetests && !touched) || (invalid && !touched)) {
                    pendings.removeAt(j)
                } else if (touched && !busy) {
                    val slHit = if (buy) c.low <= p.sl else c.high >= p.sl
                    trades.add(Trade(p.model, p.direction, i, p.entry, p.sl, p.tp, i, if (slHit) Outcome.SL else Outcome.LIVE))
                    while (trades.size > cfg.setupHistory) trades.removeAt(0)
                    pendings.removeAt(j)
                    busy = true
                }
            }

            val center = i - len
            if (confirmedPivot(candles, center, len, true)) {
                swingHigh = candles[center].high; hiAt = center
                hiBroken = false; hiSwept = false
            }
            if (confirmedPivot(candles, center, len, false)) {
                swingLow = candles[center].low; loAt = center
                loBroken = false; loSwept = false
            }

            val sweepBull = !loSwept && swingLow.isFinite() && i > loAt &&
                c.low < swingLow - max(tickSize, atr * cfg.sweepMinAtr) && c.close > swingLow
            val sweepBear = !hiSwept && swingHigh.isFinite() && i > hiAt &&
                c.high > swingHigh + max(tickSize, atr * cfg.sweepMinAtr) && c.close < swingHigh
            if (sweepBull) {
                loSwept = true; bullSweepAt = i; bullSweepLow = c.low
                structures.add(Structure(i, loAt, swingLow, "SSL SWEEP", Direction.BUY))
            }
            if (sweepBear) {
                hiSwept = true; bearSweepAt = i; bearSweepHigh = c.high
                structures.add(Structure(i, hiAt, swingHigh, "BSL SWEEP", Direction.SELL))
            }
            val bullBreak = !hiBroken && swingHigh.isFinite() && i > hiAt && c.close > swingHigh && prev.close <= swingHigh
            val bearBreak = !loBroken && swingLow.isFinite() && i > loAt && c.close < swingLow && prev.close >= swingLow
            val oldDir = dir
            if (bullBreak) {
                hiBroken = true; dir = 1; bullBreakAt = i
                structures.add(Structure(i, hiAt, swingHigh, if (oldDir == -1) "CHoCH" else "BOS", Direction.BUY))
            }
            if (bearBreak) {
                loBroken = true; dir = -1; bearBreakAt = i
                structures.add(Structure(i, loAt, swingLow, if (oldDir == 1) "CHoCH" else "BOS", Direction.SELL))
            }
            while (structures.size > cfg.structureHistory) structures.removeAt(0)

            fun updateMemory(mem: FvgMemory, side: Direction) {
                if (!mem.live || i <= mem.at) return
                val touch = c.low <= mem.top && c.high >= mem.bottom
                if (touch && !mem.touching) mem.touches++
                mem.touching = touch
                val depth = if (touch) {
                    if (side == Direction.BUY) (mem.top - c.low) / (mem.top - mem.bottom)
                    else (c.high - mem.bottom) / (mem.top - mem.bottom)
                } else 0.0
                val htfWrong = cfg.htfMode != HtfMode.OFF && htf[i] != if (side == Direction.BUY) 1 else -1
                if (depth >= cfg.consumeDepth || mem.touches > cfg.maxRetests ||
                    i - mem.at > cfg.maxZoneAge || htfWrong) mem.live = false
            }
            updateMemory(bullFvg, Direction.BUY)
            updateMemory(bearFvg, Direction.SELL)

            for (j in zones.indices.reversed()) {
                val z = zones[j]
                val touch = i > z.bornIndex && c.low <= z.top && c.high >= z.bottom
                if (touch && !z.wasTouching) z.tests++
                z.wasTouching = touch
                val depth = if (touch) {
                    if (z.direction == Direction.BUY) (z.top - c.low) / (z.top - z.bottom)
                    else (c.high - z.bottom) / (z.top - z.bottom)
                } else 0.0
                val invalid = if (cfg.invalidByWick) {
                    if (z.direction == Direction.BUY) c.low < z.bottom - cfg.invalidBufferAtr * atr
                    else c.high > z.top + cfg.invalidBufferAtr * atr
                } else {
                    if (z.direction == Direction.BUY) c.close < z.bottom - cfg.invalidBufferAtr * atr
                    else c.close > z.top + cfg.invalidBufferAtr * atr
                }
                val tooFar = if (z.direction == Direction.BUY) max(0.0, c.close - z.top) / atr > cfg.maxZoneDistance
                             else max(0.0, z.bottom - c.close) / atr > cfg.maxZoneDistance
                val wrongHtf = cfg.htfMode != HtfMode.OFF && htf[i] != if (z.direction == Direction.BUY) 1 else -1
                if (i > z.bornIndex && (invalid || depth >= cfg.consumeDepth || z.tests > cfg.maxRetests ||
                            i - z.bornIndex > cfg.maxZoneAge || tooFar || wrongHtf)) zones.removeAt(j)
            }

            val volumeAvg = if (i >= 19) (i - 19..i).sumOf { candles[it].volume } / 20.0 else Double.NaN
            val volumeOK0 = !cfg.useVolumeFilter || (volumeAvg.isFinite() && volumeAvg > 0 && c.volume >= cfg.volumeMultiplier * volumeAvg)
            val volumeOK1 = !cfg.useVolumeFilter || (i >= 20 && (i - 20 until i).sumOf { candles[it].volume } / 20.0 * cfg.volumeMultiplier <= prev.volume)
            val agreeBull = cfg.htfMode == HtfMode.OFF || htf[i] == 1
            val agreeBear = cfg.htfMode == HtfMode.OFF || htf[i] == -1
            val recentBullBreak = bullBreakAt >= 0 && i - bullBreakAt <= cfg.nearFvgBars
            val recentBearBreak = bearBreakAt >= 0 && i - bearBreakAt <= cfg.nearFvgBars
            val recentBullSweep = bullSweepAt >= 0 && i - bullSweepAt <= cfg.sweepWindow
            val recentBearSweep = bearSweepAt >= 0 && i - bearSweepAt <= cfg.sweepWindow
            val b0 = abs(c.close - c.open); val b1 = abs(prev.close - prev.open)
            val e0 = b0 / max(c.high - c.low, tickSize)
            val e1 = b1 / max(prev.high - prev.low, tickSize)
            val displacement = atrs[i - 1].isFinite() && b1 >= cfg.displaceAtr * atrs[i - 1]
            val bullFvgRaw = c.low > older.high && c.low - older.high >= cfg.minFvgAtr * atr &&
                prev.close > prev.open && displacement
            val bearFvgRaw = c.high < older.low && older.low - c.high >= cfg.minFvgAtr * atr &&
                prev.close < prev.open && displacement
            val bullFvgScore = qualityScore(if (atrs[i - 1] > 0.0) b1 / atrs[i - 1] else 0.0, e1,
                dir == 1, agreeBull, recentBullBreak || recentBullSweep,
                c.close > (c.high + c.low) * 0.5, cfg)
            val bearFvgScore = qualityScore(if (atrs[i - 1] > 0.0) b1 / atrs[i - 1] else 0.0, e1,
                dir == -1, agreeBear, recentBearBreak || recentBearSweep,
                c.close < (c.high + c.low) * 0.5, cfg)
            val bullFVG = bullFvgRaw && e1 >= cfg.minBodyRatio && agreeBull && volumeOK1 &&
                widthOK(c.low, older.high, atr) && bullFvgScore >= cfg.qualityMode.threshold
            val bearFVG = bearFvgRaw && e1 >= cfg.minBodyRatio && agreeBear && volumeOK1 &&
                widthOK(older.low, c.high, atr) && bearFvgScore >= cfg.qualityMode.threshold
            if (bullFVG) {
                bullFvg.at = i; bullFvg.top = c.low; bullFvg.bottom = older.high
                bullFvg.live = true; bullFvg.touches = 0; bullFvg.touching = false
                insertZone(ZoneKind.FVG, Direction.BUY, c.low, older.high, i - 2, i, bullFvgScore)
            }
            if (bearFVG) {
                bearFvg.at = i; bearFvg.top = older.low; bearFvg.bottom = c.high
                bearFvg.live = true; bearFvg.touches = 0; bearFvg.touching = false
                insertZone(ZoneKind.FVG, Direction.SELL, older.low, c.high, i - 2, i, bearFvgScore)
            }
            fun opposing(buy: Boolean): Pair<Int, Candle>? {
                for (offset in 1..min(cfg.obLookback, cfg.maxObOriginAge)) {
                    val j = i - offset
                    if (j < 0) break
                    val candle = candles[j]
                    if (if (buy) candle.close < candle.open else candle.close > candle.open) return j to candle
                }
                return null
            }
            val obB = opposing(true); val obS = opposing(false)
            val bullNearFvg = bullFvg.live && bullFvg.at >= 0 && i - bullFvg.at <= cfg.nearFvgBars
            val bearNearFvg = bearFvg.live && bearFvg.at >= 0 && i - bearFvg.at <= cfg.nearFvgBars
            val bullObScore = qualityScore(b0 / atr, e0, bullBreak, agreeBull,
                bullNearFvg || recentBullSweep,
                obB != null && c.close > obB.second.high + atr * cfg.minDepartureAtr, cfg)
            val bearObScore = qualityScore(b0 / atr, e0, bearBreak, agreeBear,
                bearNearFvg || recentBearSweep,
                obS != null && c.close < obS.second.low - atr * cfg.minDepartureAtr, cfg)
            val bullStrong = bullBreak && c.close > c.open && c.close >= swingHigh + cfg.minBreakCloseAtr * atr &&
                b0 >= cfg.minBreakBodyAtr * atr && e0 >= cfg.minBodyRatio
            val bearStrong = bearBreak && c.close < c.open && c.close <= swingLow - cfg.minBreakCloseAtr * atr &&
                b0 >= cfg.minBreakBodyAtr * atr && e0 >= cfg.minBodyRatio
            val validBullOB = bullStrong && agreeBull && volumeOK0 && obB != null &&
                widthOK(obB.second.high, obB.second.low, atr) &&
                c.close > obB.second.high + cfg.minDepartureAtr * atr &&
                bullObScore >= cfg.qualityMode.threshold
            val validBearOB = bearStrong && agreeBear && volumeOK0 && obS != null &&
                widthOK(obS.second.high, obS.second.low, atr) &&
                c.close < obS.second.low - cfg.minDepartureAtr * atr &&
                bearObScore >= cfg.qualityMode.threshold
            if (validBullOB) insertZone(ZoneKind.OB, Direction.BUY, obB!!.second.high, obB.second.low, obB.first, i, bullObScore)
            if (validBearOB) insertZone(ZoneKind.OB, Direction.SELL, obS!!.second.high, obS.second.low, obS.first, i, bearObScore)

            // Priority on the shared retest candle is M1 > M2 > M3: the
            // newest enqueued candidate is inspected first.
            if (cfg.useM3 && bullFVG && dir == 1) {
                enqueue(Model.M3_FVG, Direction.BUY, c.low, older.high, min(prev.low, older.high), i, atr, c.close)
            }
            if (cfg.useM3 && bearFVG && dir == -1) {
                enqueue(Model.M3_FVG, Direction.SELL, older.low, c.high, max(prev.high, older.low), i, atr, c.close)
            }
            if (cfg.useM2 && validBullOB && oldDir == 1 && (!cfg.requireFvgOB || bullNearFvg)) {
                enqueue(Model.M2_OB, Direction.BUY, obB!!.second.high, obB.second.low, obB.second.low, i, atr, c.close)
            }
            if (cfg.useM2 && validBearOB && oldDir == -1 && (!cfg.requireFvgOB || bearNearFvg)) {
                enqueue(Model.M2_OB, Direction.SELL, obS!!.second.high, obS.second.low, obS.second.high, i, atr, c.close)
            }
            val shiftBull = bullBreak && c.close > c.open && b0 >= cfg.shiftBodyAtr * atr &&
                bullSweepAt >= 0 && i > bullSweepAt && i - bullSweepAt <= cfg.sweepWindow
            val shiftBear = bearBreak && c.close < c.open && b0 >= cfg.shiftBodyAtr * atr &&
                bearSweepAt >= 0 && i > bearSweepAt && i - bearSweepAt <= cfg.sweepWindow
            if (cfg.useM1 && shiftBull) {
                if (validBullOB) enqueue(Model.M1_SWEEP_OB, Direction.BUY, obB!!.second.high, obB.second.low, bullSweepLow, i, atr, c.close)
                else if (bullNearFvg) enqueue(Model.M1_SWEEP_FVG, Direction.BUY, bullFvg.top, bullFvg.bottom, bullSweepLow, i, atr, c.close)
            }
            if (cfg.useM1 && shiftBear) {
                if (validBearOB) enqueue(Model.M1_SWEEP_OB, Direction.SELL, obS!!.second.high, obS.second.low, bearSweepHigh, i, atr, c.close)
                else if (bearNearFvg) enqueue(Model.M1_SWEEP_FVG, Direction.SELL, bearFvg.top, bearFvg.bottom, bearSweepHigh, i, atr, c.close)
            }
            if (shiftBull) bullSweepAt = -1
            if (shiftBear) bearSweepAt = -1
        }
        return Result(structures.toList(), zones.toList(), pendings.toList(), trades.toList(), false, processed)
    }
}
