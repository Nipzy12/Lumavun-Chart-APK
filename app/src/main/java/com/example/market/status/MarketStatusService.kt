package com.example.market.status

import com.example.market.candle.InstrumentCategory
import com.example.market.candle.MarketProvider
import com.example.market.candle.Ticker24h
import com.example.market.candle.inferInstrumentCategory
import com.example.market.candle.inferMarketProvider
import com.example.market.websocket.WsConnectionStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Visual & semantic state for the Market Status LED indicator in Watchlist and Chart.
 */
enum class MarketLedState(
    val code: String,
    val badgeLabel: String,
    val shouldAnimatePulse: Boolean
) {
    /** HIJAU: Market aktif dan data feed sehat. Titik berkedip pelan (1.5-2.0s siklus). */
    OPEN("OPEN", "OPEN", true),

    /** ABU-ABU: Market tutup sesuai jadwal sesi bursa / libur / status provider. Titik statis. */
    CLOSED("CLOSED", "CLOSED", false),

    /** ORANYE: Market buka tetapi data terlambat atau stale. Titik statis. */
    STALE("STALE", "STALE", false),

    /** MERAH: Koneksi provider terputus atau feed mengalami error. Titik statis. */
    ERROR("ERROR", "FEED ERROR", false),

    /** NETRAL / ABU-ABU: Jadwal perdagangan instrumen tidak diketahui. Jangan mengarang jadwal. */
    UNKNOWN("UNKNOWN", "UNKNOWN", false)
}

/**
 * Exchange session schedule classification per instrument.
 * Instruments within the same category (e.g., Indices or Commodities or Stocks)
 * follow their actual exchange schedule and timezone rather than a single category-wide schedule.
 */
enum class MarketSessionScheduleType(
    val exchangeLabel: String,
    val timezoneId: String
) {
    CRYPTO_24_7("Crypto Spot 24/7", "UTC"),
    FOREX_24_5("Global FX Session", "America/New_York"),
    CME_METALS_ENERGY("CME Globex Metals/Energy", "America/New_York"),
    ICE_BRENT("ICE Europe Brent", "America/New_York"),
    US_EQUITIES_NYSE_NASDAQ("NYSE / NASDAQ Regular", "America/New_York"),
    US_INDICES_CME("US Index Futures/CFD", "America/New_York"),
    EU_INDEX_EUREX("Eurex / Xetra Index", "Europe/Berlin"),
    UK_INDEX_FTSE("London FTSE Index", "Europe/London"),
    JP_INDEX_NIKKEI("Tokyo / Nikkei Index", "Asia/Tokyo"),
    HK_INDEX_HKEX("HKEX Index", "Asia/Hong_Kong"),
    AU_INDEX_ASX("Sydney ASX Index", "Australia/Sydney"),
    UNKNOWN_SCHEDULE("Unknown Schedule", "UTC")
}

data class MarketStatusEvaluation(
    val symbol: String,
    val category: InstrumentCategory,
    val provider: MarketProvider,
    val scheduleType: MarketSessionScheduleType,
    val ledState: MarketLedState,
    val shortBadgeLabel: String,
    val readableStatusText: String,
    val exchangeTimezoneId: String,
    val isSessionOpen: Boolean?,
    val isHoliday: Boolean,
    val isFeedHealthy: Boolean,
    val isQuoteFresh: Boolean,
    val quoteAgeMs: Long?,
    val staleThresholdMs: Long
) {
    val shouldAnimatePulse: Boolean
        get() = ledState.shouldAnimatePulse
}

/**
 * Deterministic Market Status Service for NexSync AI Lite.
 *
 * Determines instrument market status from:
 * 1. Asset class & instrument metadata (symbol, category, provider).
 * 2. Exchange trading session schedule & true exchange timezone (`America/New_York`, `Europe/Berlin`, `Europe/London`, `Asia/Tokyo`, `UTC`, etc.).
 * 3. Exchange holiday calendar when available.
 * 4. Provider connection status (`WsConnectionStatus`) and feed error state.
 * 5. Last quote timestamp and provider-appropriate freshness/stale threshold.
 * 6. Provider-reported market status (`providerReportedMarketOpen`) when explicitly supplied.
 *
 * Key Guarantees:
 * - Crypto is 24/7, but only turns GREEN (`OPEN`) when the data feed is healthy and the quote is fresh.
 * - Never concludes a market is `CLOSED` merely because no new tick arrived; thinly traded instruments
 *   in an active session stay `OPEN` (if fresh) or `STALE` (if quote timestamp is stale).
 * - If an instrument's trading schedule is unknown and not reported by the provider, returns `UNKNOWN`
 *   rather than inventing a schedule.
 */
object MarketStatusService {

    /** Binance 24/7 real-time stream + REST freshness threshold (90 seconds). */
    const val BINANCE_STALE_THRESHOLD_MS = 90_000L

    /** BiQuote multi-market SignalR + REST polling freshness threshold (3 minutes). */
    const val BIQUOTE_STALE_THRESHOLD_MS = 180_000L

    /** BiQuote equities/indices freshness threshold for slower-ticking instruments (5 minutes). */
    const val BIQUOTE_SLOW_ASSET_STALE_THRESHOLD_MS = 300_000L

    /** Exness heartbeat & quote freshness threshold (15 seconds). */
    const val EXNESS_STALE_THRESHOLD_MS = 15_000L

    private val VERIFIED_FX_CURRENCIES = setOf(
        "EUR", "USD", "GBP", "JPY", "AUD", "NZD", "CAD", "CHF",
        "SGD", "HKD", "NOK", "SEK", "TRY", "ZAR", "MXN", "CNH"
    )

    private val VERIFIED_US_STOCKS = setOf(
        "AAPL", "NVDA", "TSLA", "MSFT", "AMZN", "META", "GOOGL", "GOOG",
        "AMD", "NFLX", "INTC", "COIN", "MSTR", "PLTR", "JPM", "V", "MA",
        "DIS", "BA", "BABA", "SPY", "QQQ", "BRK.B", "BRKB", "AVGO", "LLY",
        "UNH", "XOM", "COST", "HD", "PG", "JNJ", "ABBV", "CRM", "BAC", "ORCL"
    )

    private val VERIFIED_CME_METALS_ENERGY = setOf(
        "XAUUSD", "XAGUSD", "XPTUSD", "XPDUSD", "WTI", "USOIL", "NATGAS", "XCUUSD", "COPPER"
    )

    private val VERIFIED_ICE_BRENT = setOf(
        "BRENT", "UKOIL"
    )

    private val VERIFIED_US_INDICES = setOf(
        "US500", "SPX500", "SP500", "USTEC", "NAS100", "NDX100",
        "US30", "DJI30", "DJ30", "VIX", "DXY", "USDX", "US2000", "RUT2000"
    )

    private val VERIFIED_EU_INDICES = setOf(
        "GER40", "DE40", "DAX40", "FRA40", "EU50", "STOXX50"
    )

    private val VERIFIED_UK_INDICES = setOf(
        "UK100", "FTSE100"
    )

    private val VERIFIED_JP_INDICES = setOf(
        "JPN225", "JP225", "NIKKEI225"
    )

    private val VERIFIED_HK_INDICES = setOf(
        "HK50", "HSI50"
    )

    private val VERIFIED_AU_INDICES = setOf(
        "AUS200", "ASX200"
    )

    private val VERIFIED_CRYPTO_QUOTES = listOf(
        "USDT", "FDUSD", "USDC", "BUSD", "BTC", "ETH", "BNB"
    )

    /**
     * Resolves the specific exchange session schedule for a given symbol and category.
     * Never assumes all symbols in a category share an identical schedule.
     */
    fun resolveScheduleType(
        symbol: String,
        category: InstrumentCategory = inferInstrumentCategory(symbol)
    ): MarketSessionScheduleType {
        val clean = symbol.trim().uppercase(Locale.US)
            .replace("/", "")
            .replace("-", "")
            .replace("_", "")
        if (clean.isEmpty()) return MarketSessionScheduleType.UNKNOWN_SCHEDULE

        return when (category) {
            InstrumentCategory.CRYPTO -> {
                val isKnownCryptoQuote = VERIFIED_CRYPTO_QUOTES.any { q ->
                    clean.endsWith(q) && clean.length > q.length
                }
                if (isKnownCryptoQuote) {
                    MarketSessionScheduleType.CRYPTO_24_7
                } else {
                    MarketSessionScheduleType.UNKNOWN_SCHEDULE
                }
            }

            InstrumentCategory.FOREX -> {
                if (clean.length == 6) {
                    val base = clean.substring(0, 3)
                    val quote = clean.substring(3, 6)
                    if (base in VERIFIED_FX_CURRENCIES && quote in VERIFIED_FX_CURRENCIES && base != quote) {
                        MarketSessionScheduleType.FOREX_24_5
                    } else {
                        MarketSessionScheduleType.UNKNOWN_SCHEDULE
                    }
                } else {
                    MarketSessionScheduleType.UNKNOWN_SCHEDULE
                }
            }

            InstrumentCategory.COMMODITIES -> {
                when {
                    clean in VERIFIED_ICE_BRENT -> MarketSessionScheduleType.ICE_BRENT
                    clean in VERIFIED_CME_METALS_ENERGY -> MarketSessionScheduleType.CME_METALS_ENERGY
                    clean.length == 6 && (clean.startsWith("XAU") || clean.startsWith("XAG") ||
                        clean.startsWith("XPT") || clean.startsWith("XPD")) &&
                        clean.substring(3, 6) in VERIFIED_FX_CURRENCIES ->
                        MarketSessionScheduleType.CME_METALS_ENERGY
                    else -> MarketSessionScheduleType.UNKNOWN_SCHEDULE
                }
            }

            InstrumentCategory.STOCKS -> {
                if (clean in VERIFIED_US_STOCKS) {
                    MarketSessionScheduleType.US_EQUITIES_NYSE_NASDAQ
                } else {
                    MarketSessionScheduleType.UNKNOWN_SCHEDULE
                }
            }

            InstrumentCategory.INDICES -> {
                when (clean) {
                    in VERIFIED_US_INDICES -> MarketSessionScheduleType.US_INDICES_CME
                    in VERIFIED_EU_INDICES -> MarketSessionScheduleType.EU_INDEX_EUREX
                    in VERIFIED_UK_INDICES -> MarketSessionScheduleType.UK_INDEX_FTSE
                    in VERIFIED_JP_INDICES -> MarketSessionScheduleType.JP_INDEX_NIKKEI
                    in VERIFIED_HK_INDICES -> MarketSessionScheduleType.HK_INDEX_HKEX
                    in VERIFIED_AU_INDICES -> MarketSessionScheduleType.AU_INDEX_ASX
                    else -> MarketSessionScheduleType.UNKNOWN_SCHEDULE
                }
            }

            InstrumentCategory.ALL -> {
                val inferred = inferInstrumentCategory(clean)
                if (inferred == InstrumentCategory.ALL) {
                    MarketSessionScheduleType.UNKNOWN_SCHEDULE
                } else {
                    resolveScheduleType(clean, inferred)
                }
            }
        }
    }

    /**
     * Returns the provider-appropriate stale threshold in milliseconds.
     */
    fun staleThresholdFor(
        provider: MarketProvider,
        category: InstrumentCategory
    ): Long {
        return when (provider) {
            MarketProvider.BINANCE -> BINANCE_STALE_THRESHOLD_MS
            MarketProvider.BIQUOTE -> when (category) {
                InstrumentCategory.STOCKS, InstrumentCategory.INDICES -> BIQUOTE_SLOW_ASSET_STALE_THRESHOLD_MS
                else -> BIQUOTE_STALE_THRESHOLD_MS
            }
            MarketProvider.EXNESS -> EXNESS_STALE_THRESHOLD_MS
        }
    }

    /**
     * Evaluates the complete Market Status for a symbol in Watchlist or Chart.
     */
    fun evaluate(
        symbol: String,
        category: InstrumentCategory? = null,
        provider: MarketProvider? = null,
        ticker: Ticker24h? = null,
        fallbackQuoteTimestampMs: Long? = null,
        connectionStatus: WsConnectionStatus = WsConnectionStatus.LIVE,
        hasFeedError: Boolean = false,
        providerReportedMarketOpen: Boolean? = ticker?.providerReportedMarketOpen,
        nowMs: Long = System.currentTimeMillis()
    ): MarketStatusEvaluation {
        val cleanSymbol = symbol.trim().uppercase(Locale.US)
        val resolvedCategory = category
            ?: ticker?.category
            ?: inferInstrumentCategory(cleanSymbol)
        val resolvedProvider = provider
            ?: ticker?.provider
            ?: inferMarketProvider(cleanSymbol, resolvedCategory)

        val scheduleType = resolveScheduleType(cleanSymbol, resolvedCategory)
        val staleThresholdMs = staleThresholdFor(resolvedProvider, resolvedCategory)

        val quoteTimestampMs: Long? = when {
            ticker != null && ticker.lastPrice > 0.0 && ticker.updatedAt > 0L -> ticker.updatedAt
            fallbackQuoteTimestampMs != null && fallbackQuoteTimestampMs > 0L -> fallbackQuoteTimestampMs
            else -> null
        }

        val quoteAgeMs: Long? = quoteTimestampMs?.let { ts ->
            (nowMs - ts).coerceAtLeast(0L)
        }
        val isQuoteFresh = quoteAgeMs != null && quoteAgeMs <= staleThresholdMs
        val isFeedHealthy = !hasFeedError && connectionStatus != WsConnectionStatus.DISCONNECTED

        // 1. Check schedule & holiday calendar in the exchange's native timezone
        val sessionEval = evaluateSessionSchedule(
            scheduleType = scheduleType,
            nowMs = nowMs
        )

        // 2. Combine provider-reported status (if explicitly reported) with exchange schedule
        val effectiveSessionOpen: Boolean? = when {
            providerReportedMarketOpen != null -> providerReportedMarketOpen
            else -> sessionEval.isOpen
        }

        // Case A: Schedule is UNKNOWN and provider did not explicitly report market status
        if (effectiveSessionOpen == null) {
            return MarketStatusEvaluation(
                symbol = cleanSymbol,
                category = resolvedCategory,
                provider = resolvedProvider,
                scheduleType = scheduleType,
                ledState = MarketLedState.UNKNOWN,
                shortBadgeLabel = MarketLedState.UNKNOWN.badgeLabel,
                readableStatusText = "Schedule Unknown · ${resolvedProvider.displayLabel}",
                exchangeTimezoneId = scheduleType.timezoneId,
                isSessionOpen = null,
                isHoliday = false,
                isFeedHealthy = isFeedHealthy,
                isQuoteFresh = isQuoteFresh,
                quoteAgeMs = quoteAgeMs,
                staleThresholdMs = staleThresholdMs
            )
        }

        // Case B: Market session is CLOSED (by exchange schedule, holiday, or provider report)
        if (!effectiveSessionOpen) {
            val closedReason = when {
                providerReportedMarketOpen == false && sessionEval.isOpen == true ->
                    "Market Closed (Provider Status)"
                sessionEval.isHoliday ->
                    "Market Closed · Holiday (${sessionEval.reason})"
                else ->
                    "Market Closed · ${sessionEval.reason}"
            }
            return MarketStatusEvaluation(
                symbol = cleanSymbol,
                category = resolvedCategory,
                provider = resolvedProvider,
                scheduleType = scheduleType,
                ledState = MarketLedState.CLOSED,
                shortBadgeLabel = MarketLedState.CLOSED.badgeLabel,
                readableStatusText = closedReason,
                exchangeTimezoneId = scheduleType.timezoneId,
                isSessionOpen = false,
                isHoliday = sessionEval.isHoliday,
                isFeedHealthy = isFeedHealthy,
                isQuoteFresh = isQuoteFresh,
                quoteAgeMs = quoteAgeMs,
                staleThresholdMs = staleThresholdMs
            )
        }

        // Case C: Market session is OPEN.
        // Never infer CLOSED just because no new tick arrived; check provider connection & quote freshness.
        if (!isFeedHealthy) {
            val errText = if (connectionStatus == WsConnectionStatus.DISCONNECTED) {
                "Provider Disconnected · ${resolvedProvider.displayLabel}"
            } else {
                "Data Feed Error · ${resolvedProvider.displayLabel}"
            }
            return MarketStatusEvaluation(
                symbol = cleanSymbol,
                category = resolvedCategory,
                provider = resolvedProvider,
                scheduleType = scheduleType,
                ledState = MarketLedState.ERROR,
                shortBadgeLabel = MarketLedState.ERROR.badgeLabel,
                readableStatusText = errText,
                exchangeTimezoneId = scheduleType.timezoneId,
                isSessionOpen = true,
                isHoliday = false,
                isFeedHealthy = false,
                isQuoteFresh = isQuoteFresh,
                quoteAgeMs = quoteAgeMs,
                staleThresholdMs = staleThresholdMs
            )
        }

        val isReconnectingStale = connectionStatus == WsConnectionStatus.RECONNECTING &&
            (quoteAgeMs == null || quoteAgeMs > staleThresholdMs / 2)

        if (!isQuoteFresh || isReconnectingStale) {
            val staleDetail = when {
                quoteAgeMs == null -> "Market Open · Waiting for Quote"
                connectionStatus == WsConnectionStatus.RECONNECTING -> "Market Open · Feed Reconnecting"
                else -> "Market Open · Quote Stale (${formatAgeShort(quoteAgeMs)})"
            }
            return MarketStatusEvaluation(
                symbol = cleanSymbol,
                category = resolvedCategory,
                provider = resolvedProvider,
                scheduleType = scheduleType,
                ledState = MarketLedState.STALE,
                shortBadgeLabel = MarketLedState.STALE.badgeLabel,
                readableStatusText = staleDetail,
                exchangeTimezoneId = scheduleType.timezoneId,
                isSessionOpen = true,
                isHoliday = false,
                isFeedHealthy = true,
                isQuoteFresh = false,
                quoteAgeMs = quoteAgeMs,
                staleThresholdMs = staleThresholdMs
            )
        }

        // Market is OPEN, feed is healthy, and quote meets freshness threshold -> GREEN LED
        return MarketStatusEvaluation(
            symbol = cleanSymbol,
            category = resolvedCategory,
            provider = resolvedProvider,
            scheduleType = scheduleType,
            ledState = MarketLedState.OPEN,
            shortBadgeLabel = MarketLedState.OPEN.badgeLabel,
            readableStatusText = "Market Open · ${sessionEval.reason}",
            exchangeTimezoneId = scheduleType.timezoneId,
            isSessionOpen = true,
            isHoliday = false,
            isFeedHealthy = true,
            isQuoteFresh = true,
            quoteAgeMs = quoteAgeMs,
            staleThresholdMs = staleThresholdMs
        )
    }

    private data class SessionEvalResult(
        val isOpen: Boolean?,
        val isHoliday: Boolean,
        val reason: String
    )

    private fun evaluateSessionSchedule(
        scheduleType: MarketSessionScheduleType,
        nowMs: Long
    ): SessionEvalResult {
        if (scheduleType == MarketSessionScheduleType.UNKNOWN_SCHEDULE) {
            return SessionEvalResult(isOpen = null, isHoliday = false, reason = "Unknown Schedule")
        }
        if (scheduleType == MarketSessionScheduleType.CRYPTO_24_7) {
            return SessionEvalResult(isOpen = true, isHoliday = false, reason = "24/7 Active")
        }

        val zone = ZoneId.of(scheduleType.timezoneId)
        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val date = zdt.toLocalDate()
        val time = zdt.toLocalTime()
        val dow = zdt.dayOfWeek

        // Check exchange holiday calendar for the current local date in exchange timezone
        val holidayName = ExchangeHolidayCalendar.getHolidayName(date, scheduleType)
        if (holidayName != null) {
            return SessionEvalResult(
                isOpen = false,
                isHoliday = true,
                reason = holidayName
            )
        }

        return when (scheduleType) {
            MarketSessionScheduleType.FOREX_24_5 -> {
                // Opens Sunday 17:00 ET, closes Friday 17:00 ET
                val isOpen = when (dow) {
                    DayOfWeek.SATURDAY -> false
                    DayOfWeek.SUNDAY -> !time.isBefore(LocalTime.of(17, 0))
                    DayOfWeek.FRIDAY -> time.isBefore(LocalTime.of(17, 0))
                    else -> true
                }
                if (isOpen) {
                    SessionEvalResult(true, false, "FX 24/5 Session")
                } else {
                    SessionEvalResult(false, false, "FX Weekend Close (Opens Sun 17:00 ET)")
                }
            }

            MarketSessionScheduleType.CME_METALS_ENERGY,
            MarketSessionScheduleType.US_INDICES_CME -> {
                // Opens Sunday 18:00 ET, closes Friday 17:00 ET, daily maintenance break 17:00–18:00 ET Mon–Thu
                val inDailyBreak = dow in setOf(
                    DayOfWeek.MONDAY,
                    DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY
                ) && !time.isBefore(LocalTime.of(17, 0)) && time.isBefore(LocalTime.of(18, 0))

                val isOpen = when {
                    dow == DayOfWeek.SATURDAY -> false
                    dow == DayOfWeek.SUNDAY -> !time.isBefore(LocalTime.of(18, 0))
                    dow == DayOfWeek.FRIDAY -> time.isBefore(LocalTime.of(17, 0))
                    inDailyBreak -> false
                    else -> true
                }
                val reason = when {
                    isOpen -> "${scheduleType.exchangeLabel} Active"
                    inDailyBreak -> "Daily Maintenance Break (17:00–18:00 ET)"
                    else -> "Weekend Close (Opens Sun 18:00 ET)"
                }
                SessionEvalResult(isOpen, false, reason)
            }

            MarketSessionScheduleType.ICE_BRENT -> {
                // Opens Sunday 20:00 ET, closes Friday 17:00 ET, daily break 17:00–20:00 ET Mon–Thu
                val inDailyBreak = dow in setOf(
                    DayOfWeek.MONDAY,
                    DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY
                ) && !time.isBefore(LocalTime.of(17, 0)) && time.isBefore(LocalTime.of(20, 0))

                val isOpen = when {
                    dow == DayOfWeek.SATURDAY -> false
                    dow == DayOfWeek.SUNDAY -> !time.isBefore(LocalTime.of(20, 0))
                    dow == DayOfWeek.FRIDAY -> time.isBefore(LocalTime.of(17, 0))
                    inDailyBreak -> false
                    else -> true
                }
                val reason = when {
                    isOpen -> "ICE Brent Session Active"
                    inDailyBreak -> "ICE Daily Break (17:00–20:00 ET)"
                    else -> "Weekend Close (Opens Sun 20:00 ET)"
                }
                SessionEvalResult(isOpen, false, reason)
            }

            MarketSessionScheduleType.US_EQUITIES_NYSE_NASDAQ -> {
                // Regular NYSE / NASDAQ session: Monday–Friday 09:30–16:00 America/New_York
                val isWeekday = dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY
                val inRegularHours = !time.isBefore(LocalTime.of(9, 30)) && time.isBefore(LocalTime.of(16, 0))
                val isOpen = isWeekday && inRegularHours
                val reason = if (isOpen) {
                    "NYSE/NASDAQ Open (09:30–16:00 ET)"
                } else {
                    "NYSE/NASDAQ Closed (Regular 09:30–16:00 ET)"
                }
                SessionEvalResult(isOpen, false, reason)
            }

            MarketSessionScheduleType.EU_INDEX_EUREX -> {
                // Eurex / Frankfurt DAX/EuroStoxx: Monday–Friday 01:15–22:00 Europe/Berlin
                val isWeekday = dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY
                val inSession = !time.isBefore(LocalTime.of(1, 15)) && time.isBefore(LocalTime.of(22, 0))
                val isOpen = isWeekday && inSession
                val reason = if (isOpen) {
                    "Eurex Session Open (01:15–22:00 CET)"
                } else {
                    "Eurex Closed (01:15–22:00 Europe/Berlin)"
                }
                SessionEvalResult(isOpen, false, reason)
            }

            MarketSessionScheduleType.UK_INDEX_FTSE -> {
                // London FTSE: Monday–Friday 01:00–21:00 Europe/London
                val isWeekday = dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY
                val inSession = !time.isBefore(LocalTime.of(1, 0)) && time.isBefore(LocalTime.of(21, 0))
                val isOpen = isWeekday && inSession
                val reason = if (isOpen) {
                    "London FTSE Open (01:00–21:00 UK)"
                } else {
                    "London FTSE Closed (01:00–21:00 Europe/London)"
                }
                SessionEvalResult(isOpen, false, reason)
            }

            MarketSessionScheduleType.JP_INDEX_NIKKEI -> {
                // Tokyo Nikkei 225: Monday–Friday 08:30–15:30 JST & overnight session 16:30–06:00 JST
                val isOpen = when (dow) {
                    DayOfWeek.SATURDAY -> time.isBefore(LocalTime.of(6, 0))
                    DayOfWeek.SUNDAY -> false
                    DayOfWeek.MONDAY -> !time.isBefore(LocalTime.of(8, 30)) &&
                        (time.isBefore(LocalTime.of(15, 30)) || !time.isBefore(LocalTime.of(16, 30)))
                    else -> time.isBefore(LocalTime.of(6, 0)) ||
                        (!time.isBefore(LocalTime.of(8, 30)) && time.isBefore(LocalTime.of(15, 30))) ||
                        !time.isBefore(LocalTime.of(16, 30))
                }
                val reason = if (isOpen) {
                    "Nikkei Session Open (Asia/Tokyo)"
                } else {
                    "Nikkei Session Closed (Asia/Tokyo)"
                }
                SessionEvalResult(isOpen, false, reason)
            }

            MarketSessionScheduleType.HK_INDEX_HKEX -> {
                // HKEX: Monday–Friday 09:15–16:30 HKT & 17:15–03:00 HKT
                val isOpen = when (dow) {
                    DayOfWeek.SATURDAY -> time.isBefore(LocalTime.of(3, 0))
                    DayOfWeek.SUNDAY -> false
                    DayOfWeek.MONDAY -> (!time.isBefore(LocalTime.of(9, 15)) && time.isBefore(LocalTime.of(16, 30))) ||
                        !time.isBefore(LocalTime.of(17, 15))
                    else -> time.isBefore(LocalTime.of(3, 0)) ||
                        (!time.isBefore(LocalTime.of(9, 15)) && time.isBefore(LocalTime.of(16, 30))) ||
                        !time.isBefore(LocalTime.of(17, 15))
                }
                val reason = if (isOpen) "HKEX Session Open" else "HKEX Session Closed"
                SessionEvalResult(isOpen, false, reason)
            }

            MarketSessionScheduleType.AU_INDEX_ASX -> {
                // ASX 200: Monday–Friday 09:50–16:30 & 17:10–07:00 Australia/Sydney
                val isOpen = when (dow) {
                    DayOfWeek.SATURDAY -> time.isBefore(LocalTime.of(7, 0))
                    DayOfWeek.SUNDAY -> false
                    DayOfWeek.MONDAY -> (!time.isBefore(LocalTime.of(9, 50)) && time.isBefore(LocalTime.of(16, 30))) ||
                        !time.isBefore(LocalTime.of(17, 10))
                    else -> time.isBefore(LocalTime.of(7, 0)) ||
                        (!time.isBefore(LocalTime.of(9, 50)) && time.isBefore(LocalTime.of(16, 30))) ||
                        !time.isBefore(LocalTime.of(17, 10))
                }
                val reason = if (isOpen) "ASX Session Open" else "ASX Session Closed"
                SessionEvalResult(isOpen, false, reason)
            }

            MarketSessionScheduleType.CRYPTO_24_7,
            MarketSessionScheduleType.UNKNOWN_SCHEDULE -> {
                SessionEvalResult(null, false, "Unknown")
            }
        }
    }

    private fun formatAgeShort(ageMs: Long): String {
        val sec = (ageMs / 1000L).coerceAtLeast(1L)
        return when {
            sec < 60L -> "${sec}s ago"
            sec < 3600L -> "${sec / 60L}m ago"
            else -> "${sec / 3600L}h ago"
        }
    }
}

/**
 * Exchange holiday calendar supporting major US (NYSE/NASDAQ/CME), European (Eurex/UK),
 * Asian (Tokyo/HK/Sydney), and Global FX holidays.
 */
object ExchangeHolidayCalendar {

    fun getHolidayName(
        date: LocalDate,
        scheduleType: MarketSessionScheduleType
    ): String? {
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
            return null
        }

        // Universal New Year's Day & Christmas Day check
        if (date.month == Month.JANUARY && date.dayOfMonth == 1) {
            return "New Year's Day"
        }

        return when (scheduleType) {
            MarketSessionScheduleType.FOREX_24_5 -> {
                if (date.month == Month.DECEMBER && date.dayOfMonth == 25) "Christmas Day" else null
            }

            MarketSessionScheduleType.US_EQUITIES_NYSE_NASDAQ,
            MarketSessionScheduleType.US_INDICES_CME,
            MarketSessionScheduleType.CME_METALS_ENERGY,
            MarketSessionScheduleType.ICE_BRENT -> {
                getUsExchangeHoliday(date, fullCloseOnly = scheduleType != MarketSessionScheduleType.US_EQUITIES_NYSE_NASDAQ)
            }

            MarketSessionScheduleType.EU_INDEX_EUREX -> {
                when {
                    isGoodFriday(date) -> "Good Friday"
                    isEasterMonday(date) -> "Easter Monday"
                    date.month == Month.MAY && date.dayOfMonth == 1 -> "Labour Day"
                    date.month == Month.DECEMBER && date.dayOfMonth == 24 -> "Christmas Eve"
                    date.month == Month.DECEMBER && date.dayOfMonth == 25 -> "Christmas Day"
                    date.month == Month.DECEMBER && date.dayOfMonth == 26 -> "Boxing Day"
                    date.month == Month.DECEMBER && date.dayOfMonth == 31 -> "New Year's Eve"
                    else -> null
                }
            }

            MarketSessionScheduleType.UK_INDEX_FTSE -> {
                when {
                    isGoodFriday(date) -> "Good Friday"
                    isEasterMonday(date) -> "Easter Monday"
                    date.month == Month.DECEMBER && date.dayOfMonth == 25 -> "Christmas Day"
                    date.month == Month.DECEMBER && date.dayOfMonth == 26 -> "Boxing Day"
                    else -> null
                }
            }

            MarketSessionScheduleType.JP_INDEX_NIKKEI -> {
                when {
                    date.month == Month.JANUARY && date.dayOfMonth in 1..3 -> "Japanese New Year Holiday"
                    date.month == Month.DECEMBER && date.dayOfMonth == 31 -> "Year-End Exchange Holiday"
                    else -> null
                }
            }

            MarketSessionScheduleType.HK_INDEX_HKEX,
            MarketSessionScheduleType.AU_INDEX_ASX -> {
                when {
                    isGoodFriday(date) -> "Good Friday"
                    isEasterMonday(date) -> "Easter Monday"
                    date.month == Month.DECEMBER && date.dayOfMonth == 25 -> "Christmas Day"
                    date.month == Month.DECEMBER && date.dayOfMonth == 26 -> "Boxing Day"
                    else -> null
                }
            }

            MarketSessionScheduleType.CRYPTO_24_7,
            MarketSessionScheduleType.UNKNOWN_SCHEDULE -> null
        }
    }

    private fun getUsExchangeHoliday(date: LocalDate, fullCloseOnly: Boolean): String? {
        // Observed New Year's Day (if Jan 1 is Saturday -> observed Friday Dec 31; if Sunday -> Monday Jan 2)
        if (date.month == Month.JANUARY && date.dayOfMonth == 2 && date.dayOfWeek == DayOfWeek.MONDAY) {
            return "New Year's Day (Observed)"
        }
        // Christmas Day (Dec 25, or observed Dec 24 Fri / Dec 26 Mon)
        if (date.month == Month.DECEMBER) {
            if (date.dayOfMonth == 25) return "Christmas Day"
            if (date.dayOfMonth == 24 && date.dayOfWeek == DayOfWeek.FRIDAY) return "Christmas Day (Observed)"
            if (date.dayOfMonth == 26 && date.dayOfWeek == DayOfWeek.MONDAY) return "Christmas Day (Observed)"
        }
        // Good Friday (both NYSE/NASDAQ and CME Globex closed)
        if (isGoodFriday(date)) {
            return "Good Friday"
        }

        if (fullCloseOnly) {
            // CME Futures/Metals have abbreviated sessions on other US holidays, full close on New Year, Good Friday, Christmas
            return null
        }

        // NYSE / NASDAQ full holiday schedule:
        // 1. Martin Luther King Jr. Day: 3rd Monday in January
        val mlkDay = LocalDate.of(date.year, Month.JANUARY, 1)
            .with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.MONDAY))
        if (date == mlkDay) return "Martin Luther King Jr. Day"

        // 2. Presidents' Day: 3rd Monday in February
        val presidentsDay = LocalDate.of(date.year, Month.FEBRUARY, 1)
            .with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.MONDAY))
        if (date == presidentsDay) return "Presidents' Day"

        // 3. Memorial Day: Last Monday in May
        val memorialDay = LocalDate.of(date.year, Month.MAY, 1)
            .with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY))
        if (date == memorialDay) return "Memorial Day"

        // 4. Juneteenth: June 19 (or observed Fri June 18 / Mon June 20)
        if (date.month == Month.JUNE) {
            if (date.dayOfMonth == 19) return "Juneteenth"
            if (date.dayOfMonth == 18 && date.dayOfWeek == DayOfWeek.FRIDAY) return "Juneteenth (Observed)"
            if (date.dayOfMonth == 20 && date.dayOfWeek == DayOfWeek.MONDAY) return "Juneteenth (Observed)"
        }

        // 5. Independence Day: July 4 (or observed Fri July 3 / Mon July 5)
        if (date.month == Month.JULY) {
            if (date.dayOfMonth == 4) return "Independence Day"
            if (date.dayOfMonth == 3 && date.dayOfWeek == DayOfWeek.FRIDAY) return "Independence Day (Observed)"
            if (date.dayOfMonth == 5 && date.dayOfWeek == DayOfWeek.MONDAY) return "Independence Day (Observed)"
        }

        // 6. Labor Day: 1st Monday in September
        val laborDay = LocalDate.of(date.year, Month.SEPTEMBER, 1)
            .with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY))
        if (date == laborDay) return "Labor Day"

        // 7. Thanksgiving Day: 4th Thursday in November
        val thanksgiving = LocalDate.of(date.year, Month.NOVEMBER, 1)
            .with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY))
        if (date == thanksgiving) return "Thanksgiving Day"

        return null
    }

    /**
     * Anonymous Gregorian Easter algorithm to compute Good Friday & Easter Monday for any year.
     */
    private fun computeEasterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }

    private fun isGoodFriday(date: LocalDate): Boolean {
        return date == computeEasterSunday(date.year).minusDays(2)
    }

    private fun isEasterMonday(date: LocalDate): Boolean {
        return date == computeEasterSunday(date.year).plusDays(1)
    }
}
