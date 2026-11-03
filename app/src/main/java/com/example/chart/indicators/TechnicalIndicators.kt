package com.example.chart.indicators

import com.example.market.candle.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class MacdPoint(
    val macd: Double?,
    val signal: Double?,
    val histogram: Double?
)

data class BollingerPoint(
    val upper: Double?,
    val middle: Double?,
    val lower: Double?
)

data class ChartIndicatorParams(
    val ema1Period: Int = 20,
    val ema2Period: Int = 50,
    val ema3Period: Int = 200,
    val sma1Period: Int = 20,
    val sma2Period: Int = 50,
    val bbPeriod: Int = 20,
    val bbStdDev: Double = 2.0,
    val rsiPeriod: Int = 14,
    val macdFast: Int = 12,
    val macdSlow: Int = 26,
    val macdSignal: Int = 9,
    val atrPeriod: Int = 14
)

data class IndicatorSeries(
    val ema20: List<Double?>,
    val ema50: List<Double?>,
    val ema200: List<Double?>,
    val rsi14: List<Double?>,
    val macd: List<MacdPoint>,
    val atr14: List<Double?>,
    val volumeSma20: List<Double?>,
    val sma20: List<Double?> = emptyList(),
    val sma50: List<Double?> = emptyList(),
    val bollingerBands: List<BollingerPoint> = emptyList(),
    val chartEma1: List<Double?> = ema20,
    val chartEma2: List<Double?> = ema50,
    val chartEma3: List<Double?> = ema200,
    val chartSma1: List<Double?> = sma20,
    val chartSma2: List<Double?> = sma50,
    val chartRsi: List<Double?> = rsi14,
    val chartMacd: List<MacdPoint> = macd,
    val chartAtr: List<Double?> = atr14,
    val params: ChartIndicatorParams = ChartIndicatorParams()
) {
    val latestEma20: Double? get() = ema20.lastOrNull()
    val latestEma50: Double? get() = ema50.lastOrNull()
    val latestEma200: Double? get() = ema200.lastOrNull()
    val latestRsi14: Double? get() = rsi14.lastOrNull()
    val latestMacd: MacdPoint? get() = macd.lastOrNull()
    val latestAtr14: Double? get() = atr14.lastOrNull()
    val latestVolumeSma20: Double? get() = volumeSma20.lastOrNull()
    val latestSma20: Double? get() = sma20.lastOrNull()
    val latestSma50: Double? get() = sma50.lastOrNull()
    val latestBollinger: BollingerPoint? get() = bollingerBands.lastOrNull()
    val latestChartRsi: Double? get() = chartRsi.lastOrNull()
    val latestChartMacd: MacdPoint? get() = chartMacd.lastOrNull()
    val latestChartAtr: Double? get() = chartAtr.lastOrNull()

    companion object {
        val EMPTY = IndicatorSeries(
            ema20 = emptyList(),
            ema50 = emptyList(),
            ema200 = emptyList(),
            rsi14 = emptyList(),
            macd = emptyList(),
            atr14 = emptyList(),
            volumeSma20 = emptyList()
        )
    }
}

object TechnicalIndicators {

    fun calculateAll(
        candles: List<Candle>,
        params: ChartIndicatorParams = ChartIndicatorParams()
    ): IndicatorSeries {
        if (candles.isEmpty()) return IndicatorSeries.EMPTY
        val closes = DoubleArray(candles.size) { candles[it].close }
        val volumes = DoubleArray(candles.size) { candles[it].volume }

        val stdEma20 = computeEma(closes, 20)
        val stdEma50 = computeEma(closes, 50)
        val stdEma200 = computeEma(closes, 200)
        val stdRsi14 = computeRsi(closes, 14)
        val stdMacd = computeMacd(closes, fastPeriod = 12, slowPeriod = 26, signalPeriod = 9)
        val stdAtr14 = computeAtr(candles, 14)
        val stdVolSma20 = computeSma(volumes, 20)
        val stdSma20 = computeSma(closes, 20)
        val stdSma50 = computeSma(closes, 50)

        val e1P = params.ema1Period.coerceIn(2, 500)
        val e2P = params.ema2Period.coerceIn(2, 500)
        val e3P = params.ema3Period.coerceIn(2, 500)
        val s1P = params.sma1Period.coerceIn(2, 500)
        val s2P = params.sma2Period.coerceIn(2, 500)
        val bbP = params.bbPeriod.coerceIn(2, 200)
        val bbMult = params.bbStdDev.coerceIn(0.5, 5.0)
        val rsiP = params.rsiPeriod.coerceIn(2, 100)
        val mFast = params.macdFast.coerceIn(2, 100)
        val mSlow = params.macdSlow.coerceIn(mFast + 1, 200)
        val mSig = params.macdSignal.coerceIn(2, 100)
        val atrP = params.atrPeriod.coerceIn(2, 100)

        return IndicatorSeries(
            ema20 = stdEma20,
            ema50 = stdEma50,
            ema200 = stdEma200,
            rsi14 = stdRsi14,
            macd = stdMacd,
            atr14 = stdAtr14,
            volumeSma20 = stdVolSma20,
            sma20 = stdSma20,
            sma50 = stdSma50,
            bollingerBands = computeBollingerBands(closes, bbP, bbMult),
            chartEma1 = if (e1P == 20) stdEma20 else computeEma(closes, e1P),
            chartEma2 = if (e2P == 50) stdEma50 else computeEma(closes, e2P),
            chartEma3 = if (e3P == 200) stdEma200 else computeEma(closes, e3P),
            chartSma1 = if (s1P == 20) stdSma20 else computeSma(closes, s1P),
            chartSma2 = if (s2P == 50) stdSma50 else computeSma(closes, s2P),
            chartRsi = if (rsiP == 14) stdRsi14 else computeRsi(closes, rsiP),
            chartMacd = if (mFast == 12 && mSlow == 26 && mSig == 9) stdMacd else computeMacd(closes, mFast, mSlow, mSig),
            chartAtr = if (atrP == 14) stdAtr14 else computeAtr(candles, atrP),
            params = params.copy(
                ema1Period = e1P,
                ema2Period = e2P,
                ema3Period = e3P,
                sma1Period = s1P,
                sma2Period = s2P,
                bbPeriod = bbP,
                bbStdDev = bbMult,
                rsiPeriod = rsiP,
                macdFast = mFast,
                macdSlow = mSlow,
                macdSignal = mSig,
                atrPeriod = atrP
            )
        )
    }

    /**
     * Standard Bollinger Bands (SMA middle band +/- stdDevMultiplier * population standard deviation).
     * Returns null bands when candle data is insufficient (< period).
     */
    fun computeBollingerBands(
        closes: DoubleArray,
        period: Int = 20,
        stdDevMultiplier: Double = 2.0
    ): List<BollingerPoint> {
        val n = closes.size
        if (n == 0 || period <= 1) return emptyList()
        val result = ArrayList<BollingerPoint>(n)
        if (n < period) {
            for (i in 0 until n) {
                result.add(BollingerPoint(null, null, null))
            }
            return result
        }

        val sma = computeSma(closes, period)
        for (i in 0 until n) {
            val mid = sma[i]
            if (mid == null || i < period - 1) {
                result.add(BollingerPoint(null, null, null))
            } else {
                var sumSq = 0.0
                for (j in (i - period + 1)..i) {
                    val diff = closes[j] - mid
                    sumSq += diff * diff
                }
                val stdDev = sqrt(sumSq / period.toDouble())
                val offset = stdDevMultiplier * stdDev
                result.add(
                    BollingerPoint(
                        upper = mid + offset,
                        middle = mid,
                        lower = mid - offset
                    )
                )
            }
        }
        return result
    }

    /**
     * Standard Exponential Moving Average (EMA) seeded with the initial Simple Moving Average (SMA).
     */
    fun computeEma(values: DoubleArray, period: Int): List<Double?> {
        val n = values.size
        if (n == 0 || period <= 0) return emptyList()
        val result = ArrayList<Double?>(n)
        if (n < period) {
            for (i in 0 until n) result.add(null)
            return result
        }

        var sum = 0.0
        for (i in 0 until period) {
            sum += values[i]
            if (i < period - 1) {
                result.add(null)
            }
        }
        var prevEma = sum / period.toDouble()
        result.add(prevEma)

        val multiplier = 2.0 / (period + 1.0)
        for (i in period until n) {
            val current = (values[i] - prevEma) * multiplier + prevEma
            result.add(current)
            prevEma = current
        }
        return result
    }

    /**
     * Simple Moving Average (SMA).
     */
    fun computeSma(values: DoubleArray, period: Int): List<Double?> {
        val n = values.size
        if (n == 0 || period <= 0) return emptyList()
        val result = ArrayList<Double?>(n)
        var windowSum = 0.0
        for (i in 0 until n) {
            windowSum += values[i]
            if (i >= period) {
                windowSum -= values[i - period]
            }
            if (i >= period - 1) {
                result.add(windowSum / period.toDouble())
            } else {
                result.add(null)
            }
        }
        return result
    }

    /**
     * Wilder's Relative Strength Index (RSI-14).
     */
    fun computeRsi(closes: DoubleArray, period: Int = 14): List<Double?> {
        val n = closes.size
        if (n == 0) return emptyList()
        val result = ArrayList<Double?>(n)
        if (n <= period) {
            for (i in 0 until n) result.add(null)
            return result
        }

        result.add(null) // index 0 has no change
        var gainSum = 0.0
        var lossSum = 0.0

        for (i in 1..period) {
            val delta = closes[i] - closes[i - 1]
            if (delta >= 0) gainSum += delta else lossSum -= delta
            if (i < period) {
                result.add(null)
            }
        }

        var avgGain = gainSum / period.toDouble()
        var avgLoss = lossSum / period.toDouble()

        result.add(rsiFromAverages(avgGain, avgLoss))

        for (i in (period + 1) until n) {
            val delta = closes[i] - closes[i - 1]
            val gain = if (delta > 0) delta else 0.0
            val loss = if (delta < 0) -delta else 0.0

            avgGain = ((avgGain * (period - 1)) + gain) / period.toDouble()
            avgLoss = ((avgLoss * (period - 1)) + loss) / period.toDouble()

            result.add(rsiFromAverages(avgGain, avgLoss))
        }

        return result
    }

    private fun rsiFromAverages(avgGain: Double, avgLoss: Double): Double {
        if (avgLoss == 0.0) return if (avgGain == 0.0) 50.0 else 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    /**
     * MACD (12, 26, 9) -> MACD Line, Signal Line, Histogram.
     */
    fun computeMacd(
        closes: DoubleArray,
        fastPeriod: Int = 12,
        slowPeriod: Int = 26,
        signalPeriod: Int = 9
    ): List<MacdPoint> {
        val n = closes.size
        if (n == 0) return emptyList()

        val fastEma = computeEma(closes, fastPeriod)
        val slowEma = computeEma(closes, slowPeriod)

        val macdLine = ArrayList<Double?>(n)
        val validMacdValues = ArrayList<Double>()
        val validMacdIndices = ArrayList<Int>()

        for (i in 0 until n) {
            val f = fastEma[i]
            val s = slowEma[i]
            if (f != null && s != null) {
                val m = f - s
                macdLine.add(m)
                validMacdValues.add(m)
                validMacdIndices.add(i)
            } else {
                macdLine.add(null)
            }
        }

        val signalSeriesOnValid = computeEma(validMacdValues.toDoubleArray(), signalPeriod)
        val signalByCandleIndex = arrayOfNulls<Double>(n)
        for (k in validMacdIndices.indices) {
            signalByCandleIndex[validMacdIndices[k]] = signalSeriesOnValid[k]
        }

        val output = ArrayList<MacdPoint>(n)
        for (i in 0 until n) {
            val m = macdLine[i]
            val sig = signalByCandleIndex[i]
            val hist = if (m != null && sig != null) m - sig else null
            output.add(MacdPoint(macd = m, signal = sig, histogram = hist))
        }
        return output
    }

    /**
     * Wilder's Average True Range (ATR-14).
     */
    fun computeAtr(candles: List<Candle>, period: Int = 14): List<Double?> {
        val n = candles.size
        if (n == 0) return emptyList()
        val result = ArrayList<Double?>(n)
        if (n < period) {
            for (i in 0 until n) result.add(null)
            return result
        }

        val tr = DoubleArray(n)
        tr[0] = candles[0].high - candles[0].low
        for (i in 1 until n) {
            val highLow = candles[i].high - candles[i].low
            val highPrevClose = abs(candles[i].high - candles[i - 1].close)
            val lowPrevClose = abs(candles[i].low - candles[i - 1].close)
            tr[i] = max(highLow, max(highPrevClose, lowPrevClose))
        }

        var sumTr = 0.0
        for (i in 0 until period) {
            sumTr += tr[i]
            if (i < period - 1) {
                result.add(null)
            }
        }

        var prevAtr = sumTr / period.toDouble()
        result.add(prevAtr)

        for (i in period until n) {
            val currentAtr = ((prevAtr * (period - 1)) + tr[i]) / period.toDouble()
            result.add(currentAtr)
            prevAtr = currentAtr
        }
        return result
    }
}
