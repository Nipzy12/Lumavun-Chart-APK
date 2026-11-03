package com.example.analysis.supportResistance

import com.example.market.candle.Candle
import kotlin.math.abs
import kotlin.math.max

data class PriceLevelCluster(
    val price: Double,
    val touches: Int,
    val lastTouchIndex: Int
)

data class SupportResistanceResult(
    val supportLevels: List<Double>,
    val resistanceLevels: List<Double>,
    val toleranceUsed: Double
)

object SupportResistanceEngine {

    /**
     * Finds key Support and Resistance levels from historical OHLC data using fractal pivots
     * and clusters levels that are within [tolerance] of each other.
     */
    fun detectLevels(
        candles: List<Candle>,
        currentPrice: Double,
        atr14: Double? = null,
        maxLevelsPerSide: Int = 3
    ): SupportResistanceResult {
        if (candles.size < 10 || currentPrice <= 0.0) {
            return SupportResistanceResult(emptyList(), emptyList(), 0.0)
        }

        // Tolerance: 0.45 * ATR(14) or 0.4% of current price so close levels are merged cleanly
        val defaultPctTolerance = currentPrice * 0.004
        val tolerance = if (atr14 != null && atr14 > 0.0) {
            max(atr14 * 0.45, currentPrice * 0.0025)
        } else {
            defaultPctTolerance
        }

        val window = candles.takeLast(200)
        val rawCandidates = ArrayList<Pair<Double, Int>>() // price to index

        val lookback = 3
        for (i in lookback until (window.size - lookback)) {
            val c = window[i]
            var isSwingHigh = true
            var isSwingLow = true
            for (k in 1..lookback) {
                if (window[i - k].high > c.high || window[i + k].high > c.high) {
                    isSwingHigh = false
                }
                if (window[i - k].low < c.low || window[i + k].low < c.low) {
                    isSwingLow = false
                }
            }
            if (isSwingHigh) rawCandidates.add(c.high to i)
            if (isSwingLow) rawCandidates.add(c.low to i)
        }

        // Also include recent extreme high/low so bounds are always represented
        val maxHigh = window.maxByOrNull { it.high }
        val minLow = window.minByOrNull { it.low }
        if (maxHigh != null) rawCandidates.add(maxHigh.high to window.lastIndex)
        if (minLow != null) rawCandidates.add(minLow.low to window.lastIndex)

        // Cluster nearby price candidates within tolerance
        val clusters = ArrayList<PriceLevelCluster>()
        for ((price, idx) in rawCandidates) {
            val existingIdx = clusters.indexOfFirst { abs(it.price - price) <= tolerance }
            if (existingIdx >= 0) {
                val old = clusters[existingIdx]
                val totalTouches = old.touches + 1
                val weightedAvg = ((old.price * old.touches) + price) / totalTouches.toDouble()
                clusters[existingIdx] = PriceLevelCluster(
                    price = weightedAvg,
                    touches = totalTouches,
                    lastTouchIndex = max(old.lastTouchIndex, idx)
                )
            } else {
                clusters.add(PriceLevelCluster(price = price, touches = 1, lastTouchIndex = idx))
            }
        }

        // Separate into Support (< currentPrice) and Resistance (> currentPrice)
        // Sort by proximity to currentPrice & touch significance
        val supports = clusters
            .filter { it.price < currentPrice - (tolerance * 0.15) }
            .sortedByDescending { it.price } // Closest support first (e.g., 67,200 then 66,800)
            .take(maxLevelsPerSide)
            .map { roundPriceCleanly(it.price) }

        val resistances = clusters
            .filter { it.price > currentPrice + (tolerance * 0.15) }
            .sortedBy { it.price } // Closest resistance first (e.g., 67,900 then 68,500)
            .take(maxLevelsPerSide)
            .map { roundPriceCleanly(it.price) }

        return SupportResistanceResult(
            supportLevels = supports,
            resistanceLevels = resistances,
            toleranceUsed = tolerance
        )
    }

    fun roundPriceCleanly(price: Double): Double {
        if (price.isNaN() || price.isInfinite() || price <= 0.0) return 0.0
        return when {
            price >= 10000.0 -> kotlin.math.round(price * 100.0) / 100.0
            price >= 10.0 -> kotlin.math.round(price * 1000.0) / 1000.0
            price >= 0.1 -> kotlin.math.round(price * 100000.0) / 100000.0
            else -> kotlin.math.round(price * 1000000.0) / 1000000.0
        }
    }
}
