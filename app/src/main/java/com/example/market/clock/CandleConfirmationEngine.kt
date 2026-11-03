package com.example.market.clock

import com.example.market.candle.Candle
import com.example.market.candle.Timeframe
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Explicit separation between CONFIRMED (CLOSED) candles and the currently forming LIVE candle.
 */
data class ConfirmedMarketSplit(
    val confirmedCandles: List<Candle>,
    val liveCandle: Candle?,
    val latestConfirmedCandle: Candle?
)

data class ConfirmedCandleCloseEvent(
    val symbol: String,
    val timeframe: Timeframe,
    val confirmedCandle: Candle,
    val confirmedCandles: List<Candle>,
    val allCandlesWithLive: List<Candle>
)

/**
 * Requirement #2 & #6: Candle Confirmation Engine.
 *
 * - Strictly separates LIVE (unclosed) candles from CONFIRMED/CLOSED historical candles.
 * - Ensures the AI Signal Engine primarily analyzes confirmed candle data and never mistakes
 *   an unfinished live candle for a confirmed historical close.
 * - Detects candle close transitions both from Binance WebSocket (`k.x == true` or new candle openTime)
 *   and from `CandleClock` reaching `00:00`.
 */
class CandleConfirmationEngine {

    private val lastConfirmedOpenTimeByStream = ConcurrentHashMap<String, Long>()

    private fun streamKey(symbol: String, timeframe: Timeframe): String {
        return "${symbol.trim().uppercase(Locale.US)}_${timeframe.binanceInterval}"
    }

    /**
     * Splits a raw candle list into confirmed (closed) historical candles and the active live candle.
     */
    fun splitCandles(
        candles: List<Candle>,
        currentServerTimeMs: Long = System.currentTimeMillis()
    ): ConfirmedMarketSplit {
        if (candles.isEmpty()) {
            return ConfirmedMarketSplit(
                confirmedCandles = emptyList(),
                liveCandle = null,
                latestConfirmedCandle = null
            )
        }

        val last = candles.last()
        val isLastStillLive = !last.isClosed && last.closeTime > currentServerTimeMs

        return if (isLastStillLive) {
            val confirmed = candles.dropLast(1).map {
                if (it.isClosed) it else it.copy(isClosed = true)
            }
            ConfirmedMarketSplit(
                confirmedCandles = confirmed,
                liveCandle = last,
                latestConfirmedCandle = confirmed.lastOrNull()
            )
        } else {
            // Check if any trailing candle is explicitly marked unclosed
            val confirmed = if (!last.isClosed && candles.size > 1) {
                candles.dropLast(1).map { if (it.isClosed) it else it.copy(isClosed = true) }
            } else {
                candles.map { if (it.isClosed) it else it.copy(isClosed = true) }
            }
            val live = if (!last.isClosed) last else null
            ConfirmedMarketSplit(
                confirmedCandles = confirmed,
                liveCandle = live,
                latestConfirmedCandle = confirmed.lastOrNull()
            )
        }
    }

    /**
     * Returns strictly confirmed candles for AI analysis so an unfinished live candle
     * is never treated as a confirmed historical candle.
     */
    fun getConfirmedCandlesForAnalysis(
        candles: List<Candle>,
        currentServerTimeMs: Long = System.currentTimeMillis()
    ): List<Candle> {
        if (candles.isEmpty()) return emptyList()
        val split = splitCandles(candles, currentServerTimeMs)
        return split.confirmedCandles.ifEmpty { candles }
    }

    /**
     * Evaluates an incoming WebSocket kline update and returns a `ConfirmedCandleCloseEvent`
     * if and only if a candle just became confirmed/closed for `(symbol, timeframe)`.
     */
    fun onRealtimeKlineMerged(
        symbol: String,
        timeframe: Timeframe,
        previousCandles: List<Candle>,
        mergedCandles: List<Candle>,
        incomingCandle: Candle,
        currentServerTimeMs: Long = System.currentTimeMillis()
    ): ConfirmedCandleCloseEvent? {
        if (mergedCandles.isEmpty()) return null
        val key = streamKey(symbol, timeframe)
        val prevLast = previousCandles.lastOrNull()

        // Case 1: WebSocket explicitly flagged `incomingCandle.isClosed == true` (`k.x == true`)
        if (incomingCandle.isClosed) {
            val prevRecorded = lastConfirmedOpenTimeByStream[key] ?: -1L
            if (incomingCandle.openTime > prevRecorded) {
                lastConfirmedOpenTimeByStream[key] = incomingCandle.openTime
                val confirmedList = mergedCandles.filter { it.openTime <= incomingCandle.openTime }
                    .map { it.copy(isClosed = true) }
                return ConfirmedCandleCloseEvent(
                    symbol = symbol.uppercase(Locale.US),
                    timeframe = timeframe,
                    confirmedCandle = incomingCandle.copy(isClosed = true),
                    confirmedCandles = confirmedList,
                    allCandlesWithLive = mergedCandles
                )
            }
        }

        // Case 2: A brand-new live candle arrived with `openTime > prevLast.openTime`,
        // which confirms that `prevLast` has now closed.
        if (prevLast != null && incomingCandle.openTime > prevLast.openTime) {
            val prevRecorded = lastConfirmedOpenTimeByStream[key] ?: -1L
            if (prevLast.openTime > prevRecorded) {
                lastConfirmedOpenTimeByStream[key] = prevLast.openTime
                val closedPrev = prevLast.copy(isClosed = true)
                val updatedAll = mergedCandles.map { c ->
                    if (c.openTime == closedPrev.openTime) closedPrev else c
                }
                val confirmedList = updatedAll.filter { it.openTime <= closedPrev.openTime }
                    .map { it.copy(isClosed = true) }
                return ConfirmedCandleCloseEvent(
                    symbol = symbol.uppercase(Locale.US),
                    timeframe = timeframe,
                    confirmedCandle = closedPrev,
                    confirmedCandles = confirmedList,
                    allCandlesWithLive = updatedAll
                )
            }
        }

        return null
    }

    /**
     * Handles candle boundary close when `CandleClock` hits `00:00` even before the next WebSocket frame arrives.
     * Confirms the closing candle and spawns the initial flat live candle for the new period so the chart
     * and confirmation pipeline immediately recognize the closed candle.
     */
    fun onClockBoundaryReached(
        symbol: String,
        timeframe: Timeframe,
        currentCandles: List<Candle>,
        boundaryEvent: CandleBoundaryClosedEvent
    ): ConfirmedCandleCloseEvent? {
        if (currentCandles.isEmpty()) return null
        val key = streamKey(symbol, timeframe)
        val last = currentCandles.last()

        if (last.openTime != boundaryEvent.closedCandleOpenTime) {
            return null
        }

        val confirmedCandle = last.copy(isClosed = true)
        val newLiveCandle = Candle(
            openTime = boundaryEvent.newCandleOpenTime,
            open = confirmedCandle.close,
            high = confirmedCandle.close,
            low = confirmedCandle.close,
            close = confirmedCandle.close,
            volume = 0.0,
            closeTime = boundaryEvent.newCandleCloseTime,
            isClosed = false
        )

        val updatedAll = ArrayList<Candle>(currentCandles.size + 1)
        for (i in 0 until currentCandles.lastIndex) {
            updatedAll.add(currentCandles[i].copy(isClosed = true))
        }
        updatedAll.add(confirmedCandle)
        updatedAll.add(newLiveCandle)

        val prevRecorded = lastConfirmedOpenTimeByStream[key] ?: -1L
        val shouldEmitTrigger = confirmedCandle.openTime > prevRecorded
        if (shouldEmitTrigger) {
            lastConfirmedOpenTimeByStream[key] = confirmedCandle.openTime
        }

        val confirmedList = updatedAll.dropLast(1)
        return if (shouldEmitTrigger) {
            ConfirmedCandleCloseEvent(
                symbol = symbol.uppercase(Locale.US),
                timeframe = timeframe,
                confirmedCandle = confirmedCandle,
                confirmedCandles = confirmedList,
                allCandlesWithLive = updatedAll
            )
        } else {
            null
        }
    }
}
