package com.example.market.clock

import com.example.market.candle.Candle
import com.example.market.candle.Timeframe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class CandleCountdownState(
    val symbol: String = "BTCUSDT",
    val timeframe: Timeframe = Timeframe.M15,
    val candleOpenTime: Long = 0L,
    val candleCloseTime: Long = 0L,
    val remainingMs: Long = 0L,
    val remainingSeconds: Long = 0L,
    val formatted: String = "--:--",
    val isClosingSoon: Boolean = false,
    val didJustClose: Boolean = false
)

data class CandleBoundaryClosedEvent(
    val symbol: String,
    val timeframe: Timeframe,
    val closedCandleOpenTime: Long,
    val closedCandleCloseTime: Long,
    val newCandleOpenTime: Long,
    val newCandleCloseTime: Long,
    val serverTimeMs: Long
)

/**
 * Requirement #1: Real-time Candle Close Countdown Engine.
 *
 * - Synchronized with Binance candle open/close timestamps and Binance server time offset.
 * - Never calculates countdown from local WebSocket arrival delay; uses `remaining = candleCloseBoundary - currentServerTime`.
 * - Continues accurately even when WebSocket messages are sparse or after device sleep/resume.
 * - Single lightweight 1Hz coroutine timer that updates ONLY `countdownState` without causing full-app recompositions.
 */
class CandleClock(
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val serverTimeOffsetMs = AtomicLong(0L)

    @Volatile
    private var activeSymbol: String = "BTCUSDT"

    @Volatile
    private var activeTimeframe: Timeframe = Timeframe.M15

    @Volatile
    private var trackedCandleOpenTime: Long = 0L

    @Volatile
    private var trackedCandleCloseBoundaryMs: Long = 0L

    @Volatile
    private var lastEmittedClosedOpenTime: Long = -1L

    private var timerJob: Job? = null

    private val _countdownState = MutableStateFlow(CandleCountdownState())
    val countdownState: StateFlow<CandleCountdownState> = _countdownState.asStateFlow()

    private val _candleBoundaryClosedEvents = MutableSharedFlow<CandleBoundaryClosedEvent>(extraBufferCapacity = 16)
    val candleBoundaryClosedEvents: SharedFlow<CandleBoundaryClosedEvent> = _candleBoundaryClosedEvents.asSharedFlow()

    companion object {
        /**
         * Pure deterministic calculation of remaining candle time at a given `serverTimeMs`.
         *
         * Examples verified:
         * - 15m:
         *   15:00:00 -> 14:59
         *   15:14:59 -> 00:01
         *   15:15:00 -> new candle -> 14:59
         * - 1h:
         *   10:00:00 -> 59:59
         *   10:59:59 -> 00:01
         *   11:00:00 -> new candle -> 59:59
         */
        fun computeCountdownSnapshot(
            symbol: String,
            timeframe: Timeframe,
            serverTimeMs: Long,
            knownCandleOpenTime: Long = 0L
        ): CandleCountdownState {
            val durationMs = timeframe.durationMs
            val totalSeconds = (durationMs / 1000L).coerceAtLeast(1L)

            val alignedCurrentOpenTime = (serverTimeMs / durationMs) * durationMs
            val alignedCurrentCloseBoundary = alignedCurrentOpenTime + durationMs

            val didTransitionToNewCandle = knownCandleOpenTime > 0L &&
                serverTimeMs >= (knownCandleOpenTime + durationMs)

            val effectiveOpenTime = if (knownCandleOpenTime > 0L && !didTransitionToNewCandle) {
                knownCandleOpenTime
            } else {
                alignedCurrentOpenTime
            }
            val effectiveCloseBoundary = effectiveOpenTime + durationMs
            val rawRemainingMs = (effectiveCloseBoundary - serverTimeMs).coerceAtLeast(0L)

            val remainingSeconds = (rawRemainingMs / 1000L).coerceIn(0L, totalSeconds - 1L)
            val formatted = formatRemainingSeconds(remainingSeconds, timeframe)
            val isClosingSoon = remainingSeconds in 0L..9L

            return CandleCountdownState(
                symbol = symbol,
                timeframe = timeframe,
                candleOpenTime = effectiveOpenTime,
                candleCloseTime = effectiveCloseBoundary - 1L,
                remainingMs = rawRemainingMs,
                remainingSeconds = remainingSeconds,
                formatted = formatted,
                isClosingSoon = isClosingSoon,
                didJustClose = didTransitionToNewCandle
            )
        }

        fun formatRemainingSeconds(remainingSeconds: Long, timeframe: Timeframe = Timeframe.M15): String {
            val clamped = remainingSeconds.coerceAtLeast(0L)
            val hours = clamped / 3600L
            val minutes = (clamped % 3600L) / 60L
            val seconds = clamped % 60L

            return if (hours > 0L || timeframe == Timeframe.H4 || timeframe == Timeframe.D1) {
                if (hours > 0L) {
                    String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
                } else {
                    String.format(Locale.US, "%02d:%02d", minutes, seconds)
                }
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }
    }

    fun currentServerTimeMs(): Long {
        return timeProvider() + serverTimeOffsetMs.get()
    }

    /**
     * Updates Binance server clock offset when a valid Binance event timestamp (`E`) is received.
     * Ignores implausible offsets (> 1 hour) caused by stale/replay test fixtures so local clock stays sane.
     */
    fun updateServerTimeFromBinance(binanceServerTimeMs: Long) {
        if (binanceServerTimeMs <= 0L) return
        val localNow = timeProvider()
        val delta = binanceServerTimeMs - localNow
        if (kotlin.math.abs(delta) <= 3_600_000L) {
            serverTimeOffsetMs.set(delta)
        }
    }

    /**
     * Synchronizes the clock with the active pair, timeframe, and latest candle from REST or WebSocket.
     */
    @Synchronized
    fun sync(
        symbol: String,
        timeframe: Timeframe,
        latestCandle: Candle? = null,
        binanceEventTimeMs: Long = 0L
    ) {
        if (binanceEventTimeMs > 0L) {
            updateServerTimeFromBinance(binanceEventTimeMs)
        }

        val cleanSymbol = symbol.trim().uppercase(Locale.US)
        val symbolOrTfChanged = cleanSymbol != activeSymbol || timeframe != activeTimeframe
        activeSymbol = cleanSymbol
        activeTimeframe = timeframe

        val nowServer = currentServerTimeMs()
        val durationMs = timeframe.durationMs

        if (latestCandle != null && latestCandle.openTime > 0L) {
            val candleCloseBoundary = if (latestCandle.closeTime > latestCandle.openTime) {
                latestCandle.closeTime + 1L
            } else {
                latestCandle.openTime + durationMs
            }
            // If the provided candle is still the active live candle, lock onto its exact open/close timestamp;
            // otherwise align to the current server timeframe boundary.
            if (nowServer in latestCandle.openTime until candleCloseBoundary) {
                trackedCandleOpenTime = latestCandle.openTime
                trackedCandleCloseBoundaryMs = candleCloseBoundary
            } else {
                val alignedOpen = (nowServer / durationMs) * durationMs
                trackedCandleOpenTime = alignedOpen
                trackedCandleCloseBoundaryMs = alignedOpen + durationMs
            }
        } else if (symbolOrTfChanged || trackedCandleOpenTime <= 0L) {
            val alignedOpen = (nowServer / durationMs) * durationMs
            trackedCandleOpenTime = alignedOpen
            trackedCandleCloseBoundaryMs = alignedOpen + durationMs
        }

        if (symbolOrTfChanged) {
            lastEmittedClosedOpenTime = -1L
        }

        tickOnce(nowServer)
    }

    /**
     * Starts a single lightweight 1-second coroutine loop.
     * Calling `start` multiple times cancels any previous job so duplicate timers can never exist.
     */
    @Synchronized
    fun start(scope: CoroutineScope) {
        timerJob?.cancel()
        timerJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val nowServer = currentServerTimeMs()
                tickOnce(nowServer)
                val sleepMs = (1000L - (nowServer % 1000L)).coerceIn(200L, 1000L)
                delay(sleepMs)
            }
        }
    }

    @Synchronized
    fun stop() {
        timerJob?.cancel()
        timerJob = null
    }

    /**
     * Advances the clock to `serverTimeMs`, emits a `CandleBoundaryClosedEvent` if the current candle
     * just reached 00:00 and closed, and immediately rolls over to the new candle's countdown.
     */
    @Synchronized
    fun tickOnce(serverTimeMs: Long = currentServerTimeMs()): CandleCountdownState {
        val tf = activeTimeframe
        val sym = activeSymbol
        val durationMs = tf.durationMs

        if (trackedCandleOpenTime <= 0L) {
            trackedCandleOpenTime = (serverTimeMs / durationMs) * durationMs
            trackedCandleCloseBoundaryMs = trackedCandleOpenTime + durationMs
        }

        val previousOpenTime = trackedCandleOpenTime
        val previousCloseBoundary = trackedCandleCloseBoundaryMs

        val snapshot = computeCountdownSnapshot(
            symbol = sym,
            timeframe = tf,
            serverTimeMs = serverTimeMs,
            knownCandleOpenTime = previousOpenTime
        )

        if (snapshot.didJustClose) {
            val newOpen = snapshot.candleOpenTime
            val newCloseBoundary = newOpen + durationMs
            trackedCandleOpenTime = newOpen
            trackedCandleCloseBoundaryMs = newCloseBoundary

            if (lastEmittedClosedOpenTime != previousOpenTime) {
                lastEmittedClosedOpenTime = previousOpenTime
                _candleBoundaryClosedEvents.tryEmit(
                    CandleBoundaryClosedEvent(
                        symbol = sym,
                        timeframe = tf,
                        closedCandleOpenTime = previousOpenTime,
                        closedCandleCloseTime = previousCloseBoundary - 1L,
                        newCandleOpenTime = newOpen,
                        newCandleCloseTime = newCloseBoundary - 1L,
                        serverTimeMs = serverTimeMs
                    )
                )
            }
        }

        _countdownState.value = snapshot
        return snapshot
    }
}
