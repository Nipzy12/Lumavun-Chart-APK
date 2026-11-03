package com.example.market.session

import com.example.market.candle.Candle
import com.example.market.candle.InstrumentCategory
import com.example.market.candle.MarketProvider
import com.example.market.candle.inferInstrumentCategory
import com.example.market.candle.inferMarketProvider
import com.example.market.status.MarketLedState
import com.example.market.status.MarketSessionScheduleType
import com.example.market.status.MarketStatusEvaluation
import com.example.market.status.MarketStatusService
import com.example.market.websocket.WsConnectionStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Global Trading Session definitions using proper IANA time-zone identifiers
 * so Daylight Saving Time (BST/GMT in London, EDT/EST in New York) is handled automatically.
 *
 * Session context is strictly informational/contextual and NEVER acts as an automatic BUY/SELL signal.
 */
enum class TradingSessionType(
    val code: String,
    val displayLabel: String,
    val shortBadge: String,
    val primaryZoneId: String
) {
    ASIA("ASIA", "Asia Session", "ASIA", "Asia/Tokyo"),
    LONDON("LONDON", "London Session", "LONDON", "Europe/London"),
    LONDON_NY_OVERLAP("LONDON_NY_OVERLAP", "London–NY Overlap", "LDN/NY OVERLAP", "America/New_York"),
    NEW_YORK("NEW_YORK", "New York Session", "NEW YORK", "America/New_York"),
    OFF_SESSION("OFF_SESSION", "Inter-Session / Rollover", "ROLLOVER", "America/New_York")
}

/**
 * Configurable session schedule parameters in each session's native local exchange timezone.
 * - Asia: evaluated in `Asia/Tokyo` (default 09:00–18:00 JST)
 * - London: evaluated in `Europe/London` (default 08:00–17:00 London local time, auto-adjusting for GMT/BST)
 * - New York: evaluated in `America/New_York` (default 08:00–17:00 NY local time, auto-adjusting for EST/EDT)
 * - Overlap: active whenever both London and New York sessions are simultaneously open.
 */
data class TradingSessionConfig(
    val displayZoneId: String = TradingSessionEngine.WITA_ZONE_ID,
    val asiaStartLocal: LocalTime = LocalTime.of(9, 0),
    val asiaEndLocal: LocalTime = LocalTime.of(18, 0),
    val londonStartLocal: LocalTime = LocalTime.of(8, 0),
    val londonEndLocal: LocalTime = LocalTime.of(17, 0),
    val newYorkStartLocal: LocalTime = LocalTime.of(8, 0),
    val newYorkEndLocal: LocalTime = LocalTime.of(17, 0)
)

data class SessionHighLowReference(
    val sessionType: TradingSessionType,
    val sessionLabel: String,
    val high: Double,
    val low: Double,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val candleCount: Int,
    val isCompleted: Boolean
)

data class PreviousDayHighLowReference(
    val tradingDateLabel: String,
    val high: Double,
    val low: Double,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val candleCount: Int
)

data class TradingSessionSnapshot(
    val symbol: String,
    val evaluationTimeMs: Long,
    val primarySession: TradingSessionType,
    val activeSessions: List<TradingSessionType>,
    val isLondonNewYorkOverlap: Boolean,
    val isInstrumentTradableNow: Boolean?,
    val instrumentMarketLedState: MarketLedState,
    val instrumentAvailabilityLabel: String,
    val sessionStatusSummary: String,
    val witaTimeFormatted: String,
    val londonTimeFormatted: String,
    val newYorkTimeFormatted: String,
    val isLondonDstActive: Boolean,
    val isNewYorkDstActive: Boolean,
    val currentSessionHighLow: SessionHighLowReference? = null,
    val previousSessionHighLow: SessionHighLowReference? = null,
    val previousDayHighLow: PreviousDayHighLowReference? = null
) {
    /**
     * Distinguishes a global FX/Bullion session clock label from actual instrument availability.
     * E.g., if `scheduleType == UNKNOWN_SCHEDULE`, `instrumentAvailabilityLabel` is `UNKNOWN`.
     * If weekend/closed, `isInstrumentTradableNow == false`.
     */
    val badgeText: String
        get() = when {
            instrumentMarketLedState == MarketLedState.UNKNOWN ->
                "UNKNOWN SCHEDULE · ${primarySession.shortBadge}"
            isInstrumentTradableNow == false ->
                "MARKET CLOSED · ${primarySession.shortBadge}"
            isLondonNewYorkOverlap ->
                "LDN–NY OVERLAP (${witaTimeFormatted})"
            else ->
                "${primarySession.shortBadge} (${witaTimeFormatted})"
        }
}

object TradingSessionEngine {

    const val WITA_ZONE_ID = "Asia/Makassar"
    const val LONDON_ZONE_ID = "Europe/London"
    const val NEW_YORK_ZONE_ID = "America/New_York"
    const val TOKYO_ZONE_ID = "Asia/Tokyo"

    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    fun evaluateSession(
        symbol: String,
        candles: List<Candle> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
        config: TradingSessionConfig = TradingSessionConfig(),
        marketStatusOverride: MarketStatusEvaluation? = null,
        category: InstrumentCategory = inferInstrumentCategory(symbol),
        provider: MarketProvider = inferMarketProvider(symbol, category)
    ): TradingSessionSnapshot {
        val instant = Instant.ofEpochMilli(nowMs)

        val witaZone = runCatching { ZoneId.of(config.displayZoneId) }.getOrElse { ZoneId.of(WITA_ZONE_ID) }
        val londonZone = ZoneId.of(LONDON_ZONE_ID)
        val nyZone = ZoneId.of(NEW_YORK_ZONE_ID)
        val tokyoZone = ZoneId.of(TOKYO_ZONE_ID)

        val witaZdt = instant.atZone(witaZone)
        val londonZdt = instant.atZone(londonZone)
        val nyZdt = instant.atZone(nyZone)
        val tokyoZdt = instant.atZone(tokyoZone)

        val isLondonDst = londonZone.rules.isDaylightSavings(instant)
        val isNyDst = nyZone.rules.isDaylightSavings(instant)

        val marketStatus = marketStatusOverride ?: MarketStatusService.evaluate(
            symbol = symbol,
            category = category,
            provider = provider,
            fallbackQuoteTimestampMs = candles.lastOrNull()?.closeTime ?: nowMs,
            connectionStatus = WsConnectionStatus.LIVE,
            nowMs = nowMs
        )

        val isWeekendInNy = isGlobalFxWeekend(nyZdt)
        val isAsiaOpen = !isWeekendInNy && isLocalTimeInWindow(tokyoZdt.toLocalTime(), config.asiaStartLocal, config.asiaEndLocal)
        val isLondonOpen = !isWeekendInNy && isLocalTimeInWindow(londonZdt.toLocalTime(), config.londonStartLocal, config.londonEndLocal)
        val isNyOpen = !isWeekendInNy && isLocalTimeInWindow(nyZdt.toLocalTime(), config.newYorkStartLocal, config.newYorkEndLocal)
        val isOverlap = isLondonOpen && isNyOpen

        val activeList = buildList {
            if (isOverlap) add(TradingSessionType.LONDON_NY_OVERLAP)
            if (isLondonOpen) add(TradingSessionType.LONDON)
            if (isNyOpen) add(TradingSessionType.NEW_YORK)
            if (isAsiaOpen) add(TradingSessionType.ASIA)
        }

        val primarySession = when {
            isOverlap -> TradingSessionType.LONDON_NY_OVERLAP
            isNyOpen -> TradingSessionType.NEW_YORK
            isLondonOpen -> TradingSessionType.LONDON
            isAsiaOpen -> TradingSessionType.ASIA
            else -> TradingSessionType.OFF_SESSION
        }

        val availabilityLabel = when (marketStatus.ledState) {
            MarketLedState.UNKNOWN -> "UNKNOWN"
            MarketLedState.CLOSED -> "CLOSED"
            MarketLedState.STALE -> "OPEN (STALE FEED)"
            MarketLedState.ERROR -> "FEED ERROR"
            MarketLedState.OPEN -> "OPEN"
        }

        val witaFormatted = "${witaZdt.format(TIME_FMT)} WITA"
        val londonOffsetHours = londonZdt.offset.totalSeconds / 3600
        val nyOffsetHours = nyZdt.offset.totalSeconds / 3600
        val londonTzShort = if (isLondonDst) "BST (UTC+$londonOffsetHours)" else "GMT (UTC+0)"
        val nyTzShort = if (isNyDst) "EDT (UTC$nyOffsetHours)" else "EST (UTC$nyOffsetHours)"

        val londonFormatted = "${londonZdt.format(TIME_FMT)} $londonTzShort"
        val nyFormatted = "${nyZdt.format(TIME_FMT)} $nyTzShort"

        val currentSessionRef = computeCurrentSessionHighLow(
            candles = candles,
            nowMs = nowMs,
            primarySession = primarySession,
            config = config
        )
        val prevSessionRef = computePreviousCompletedSessionHighLow(
            candles = candles,
            nowMs = nowMs,
            config = config
        )
        val prevDayRef = computePreviousDayHighLow(
            candles = candles,
            nowMs = nowMs,
            referenceZone = if (marketStatus.scheduleType == MarketSessionScheduleType.CRYPTO_24_7) {
                ZoneId.of("UTC")
            } else {
                nyZone
            }
        )

        val summary = buildString {
            when (marketStatus.ledState) {
                MarketLedState.UNKNOWN -> {
                    append("Instrument schedule UNKNOWN — session clock (${primarySession.displayLabel}) shown for reference only.")
                }
                MarketLedState.CLOSED -> {
                    append("Market CLOSED (${marketStatus.readableStatusText}). Session clock: ${primarySession.displayLabel}.")
                }
                else -> {
                    append("${primarySession.displayLabel} active · $witaFormatted · LDN $londonFormatted · NY $nyFormatted")
                }
            }
            if (prevSessionRef != null) {
                append(" · Prev ${prevSessionRef.sessionType.shortBadge} H/L: ${prevSessionRef.high} / ${prevSessionRef.low}")
            }
        }

        return TradingSessionSnapshot(
            symbol = symbol,
            evaluationTimeMs = nowMs,
            primarySession = primarySession,
            activeSessions = activeList,
            isLondonNewYorkOverlap = isOverlap,
            isInstrumentTradableNow = marketStatus.isSessionOpen,
            instrumentMarketLedState = marketStatus.ledState,
            instrumentAvailabilityLabel = availabilityLabel,
            sessionStatusSummary = summary,
            witaTimeFormatted = witaFormatted,
            londonTimeFormatted = londonFormatted,
            newYorkTimeFormatted = nyFormatted,
            isLondonDstActive = isLondonDst,
            isNewYorkDstActive = isNyDst,
            currentSessionHighLow = currentSessionRef,
            previousSessionHighLow = prevSessionRef,
            previousDayHighLow = prevDayRef
        )
    }

    /**
     * Checks whether the global FX/Metals weekend closure is active in New York local time
     * (Friday >= 17:00 NY through Sunday < 17:00 NY).
     */
    fun isGlobalFxWeekend(nyZdt: ZonedDateTime): Boolean {
        val dow = nyZdt.dayOfWeek
        val time = nyZdt.toLocalTime()
        return when (dow) {
            DayOfWeek.SATURDAY -> true
            DayOfWeek.FRIDAY -> !time.isBefore(LocalTime.of(17, 0))
            DayOfWeek.SUNDAY -> time.isBefore(LocalTime.of(17, 0))
            else -> false
        }
    }

    private fun isLocalTimeInWindow(current: LocalTime, start: LocalTime, end: LocalTime): Boolean {
        return if (!start.isAfter(end)) {
            !current.isBefore(start) && current.isBefore(end)
        } else {
            // Overnight window crossing midnight
            !current.isBefore(start) || current.isBefore(end)
        }
    }

    private fun computeCurrentSessionHighLow(
        candles: List<Candle>,
        nowMs: Long,
        primarySession: TradingSessionType,
        config: TradingSessionConfig
    ): SessionHighLowReference? {
        if (candles.isEmpty() || primarySession == TradingSessionType.OFF_SESSION) return null
        val instant = Instant.ofEpochMilli(nowMs)
        val (zone, startLocal, endLocal) = when (primarySession) {
            TradingSessionType.ASIA -> Triple(ZoneId.of(TOKYO_ZONE_ID), config.asiaStartLocal, config.asiaEndLocal)
            TradingSessionType.LONDON -> Triple(ZoneId.of(LONDON_ZONE_ID), config.londonStartLocal, config.londonEndLocal)
            TradingSessionType.LONDON_NY_OVERLAP -> Triple(ZoneId.of(NEW_YORK_ZONE_ID), config.newYorkStartLocal, LocalTime.of(12, 0))
            TradingSessionType.NEW_YORK -> Triple(ZoneId.of(NEW_YORK_ZONE_ID), config.newYorkStartLocal, config.newYorkEndLocal)
            TradingSessionType.OFF_SESSION -> return null
        }

        val zdt = instant.atZone(zone)
        var sessionStartZdt = zdt.toLocalDate().atTime(startLocal).atZone(zone)
        if (sessionStartZdt.toInstant().toEpochMilli() > nowMs) {
            sessionStartZdt = sessionStartZdt.minusDays(1)
        }
        val sessionEndZdt = sessionStartZdt.toLocalDate().atTime(endLocal).atZone(zone).let {
            if (it.isBefore(sessionStartZdt)) it.plusDays(1) else it
        }

        val startMs = sessionStartZdt.toInstant().toEpochMilli()
        val endMs = sessionEndZdt.toInstant().toEpochMilli()

        val sessionCandles = candles.filter { it.openTime in startMs..nowMs }
        if (sessionCandles.isEmpty()) return null

        return SessionHighLowReference(
            sessionType = primarySession,
            sessionLabel = "Current ${primarySession.shortBadge}",
            high = sessionCandles.maxOf { it.high },
            low = sessionCandles.minOf { it.low },
            startEpochMs = startMs,
            endEpochMs = endMs,
            candleCount = sessionCandles.size,
            isCompleted = false
        )
    }

    /**
     * Tracks the most recently completed major session's High and Low (Asia, London, or New York)
     * from candle history so Liquidity Sweep Detection can monitor sweeps of previous session highs/lows.
     */
    fun computePreviousCompletedSessionHighLow(
        candles: List<Candle>,
        nowMs: Long,
        config: TradingSessionConfig = TradingSessionConfig()
    ): SessionHighLowReference? {
        if (candles.isEmpty()) return null
        val instant = Instant.ofEpochMilli(nowMs)

        val candidates = ArrayList<SessionHighLowReference>()
        val sessionDefs = listOf(
            Triple(TradingSessionType.ASIA, ZoneId.of(TOKYO_ZONE_ID), config.asiaStartLocal to config.asiaEndLocal),
            Triple(TradingSessionType.LONDON, ZoneId.of(LONDON_ZONE_ID), config.londonStartLocal to config.londonEndLocal),
            Triple(TradingSessionType.NEW_YORK, ZoneId.of(NEW_YORK_ZONE_ID), config.newYorkStartLocal to config.newYorkEndLocal)
        )

        for ((sessionType, zone, window) in sessionDefs) {
            val (startLocal, endLocal) = window
            val localNow = instant.atZone(zone)
            for (daysBack in 0..3) {
                val date: LocalDate = localNow.toLocalDate().minusDays(daysBack.toLong())
                val startZdt = date.atTime(startLocal).atZone(zone)
                var endZdt = date.atTime(endLocal).atZone(zone)
                if (!endZdt.isAfter(startZdt)) {
                    endZdt = endZdt.plusDays(1)
                }
                val startMs = startZdt.toInstant().toEpochMilli()
                val endMs = endZdt.toInstant().toEpochMilli()
                if (endMs <= nowMs) {
                    val bars = candles.filter { it.openTime >= startMs && it.openTime < endMs }
                    if (bars.isNotEmpty()) {
                        candidates.add(
                            SessionHighLowReference(
                                sessionType = sessionType,
                                sessionLabel = "Prev ${sessionType.shortBadge}",
                                high = bars.maxOf { it.high },
                                low = bars.minOf { it.low },
                                startEpochMs = startMs,
                                endEpochMs = endMs,
                                candleCount = bars.size,
                                isCompleted = true
                            )
                        )
                        break
                    }
                }
            }
        }

        return candidates.maxByOrNull { it.endEpochMs }
    }

    /**
     * Computes the Previous Day High (PDH) and Previous Day Low (PDL) using the instrument's
     * session day boundary (`America/New_York` for FX/Metals/Equities or `UTC` for Crypto).
     */
    fun computePreviousDayHighLow(
        candles: List<Candle>,
        nowMs: Long,
        referenceZone: ZoneId = ZoneId.of(NEW_YORK_ZONE_ID)
    ): PreviousDayHighLowReference? {
        if (candles.isEmpty()) return null
        val currentZdt = Instant.ofEpochMilli(nowMs).atZone(referenceZone)
        val todayStartMs = currentZdt.toLocalDate().atStartOfDay(referenceZone).toInstant().toEpochMilli()

        for (daysBack in 1..4) {
            val targetDate = currentZdt.toLocalDate().minusDays(daysBack.toLong())
            val dayStartMs = targetDate.atStartOfDay(referenceZone).toInstant().toEpochMilli()
            val dayEndMs = targetDate.plusDays(1).atStartOfDay(referenceZone).toInstant().toEpochMilli()
            if (dayEndMs > todayStartMs) continue

            val dayBars = candles.filter { it.openTime >= dayStartMs && it.openTime < dayEndMs }
            if (dayBars.isNotEmpty()) {
                return PreviousDayHighLowReference(
                    tradingDateLabel = targetDate.format(DATE_FMT),
                    high = dayBars.maxOf { it.high },
                    low = dayBars.minOf { it.low },
                    startEpochMs = dayStartMs,
                    endEpochMs = dayEndMs - 1L,
                    candleCount = dayBars.size
                )
            }
        }
        return null
    }
}
