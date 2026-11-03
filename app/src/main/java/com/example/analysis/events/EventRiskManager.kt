package com.example.analysis.events

import com.example.market.calendar.CalendarLoadState
import com.example.market.calendar.EconomicCalendarEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

enum class EventImpactLevel(val code: String, val displayLabel: String) {
    HIGH("HIGH", "HIGH IMPACT"),
    MEDIUM("MEDIUM", "MEDIUM IMPACT"),
    LOW("LOW", "LOW IMPACT");

    companion object {
        fun fromCode(raw: String): EventImpactLevel {
            return when (raw.trim().uppercase(Locale.US)) {
                "HIGH", "3" -> HIGH
                "MEDIUM", "MED", "2" -> MEDIUM
                else -> LOW
            }
        }
    }
}

enum class EventProtectionMode(val code: String, val label: String) {
    OFF("OFF", "Off"),
    WARN_ONLY("WARN_ONLY", "Warn Only"),
    BLOCK_HIGH_IMPACT("BLOCK_HIGH_IMPACT", "Block High-Impact"),
    BLOCK_ALL("BLOCK_ALL", "Block High & Med");

    companion object {
        fun fromCode(code: String): EventProtectionMode {
            return entries.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }
                ?: BLOCK_HIGH_IMPACT
        }
    }
}

enum class UnavailableEventPolicy(val code: String, val label: String) {
    ALLOW_WITH_WARNING("ALLOW_WITH_WARNING", "Warn & Allow"),
    BLOCK_NEW_ENTRIES("BLOCK_NEW_ENTRIES", "Safe Block");

    companion object {
        fun fromCode(code: String): UnavailableEventPolicy {
            return entries.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }
                ?: ALLOW_WITH_WARNING
        }
    }
}

data class MacroCalendarEvent(
    val id: String,
    val title: String,
    val category: String,               // Currency / Country code e.g. "USD", "EUR", "JPY"
    val impact: EventImpactLevel,
    val timestampMs: Long,
    val preWindowMinutes: Int = 30,
    val postWindowMinutes: Int = 30,
    val affectedSymbols: List<String> = emptyList(),
    val forecast: String = "",
    val previous: String = "",
    val actual: String = "",
    val source: String = "FinanceCalendar",
    val isCustomUserEvent: Boolean = false
)

data class EventRiskAssessment(
    val isEventDataVerified: Boolean = false,
    val isEventWindowActive: Boolean = false,
    val isApproachingSoon: Boolean = false,
    val shouldBlockNewBotEntries: Boolean = false,
    val activeOrNextEvent: MacroCalendarEvent? = null,
    val minutesToStart: Long? = null,
    val minutesRemainingInWindow: Long? = null,
    val statusBadge: String = "EVENT DATA UNVERIFIED",
    val summaryMessage: String = "Waiting for Economic Calendar sync...",
    val botActionDirective: String = "READY"
)

/**
 * Requirement #4 & #5: Event Risk Engine connected to Economic Calendar (`EconomicCalendarService`).
 * - Connects economic calendar events to Bot Action without rewriting the technical AI signal.
 * - Evaluates currency & pair relevance rather than assuming every economic event equally affects every crypto pair.
 * - Never invents events or claims event risk was checked when calendar data is unavailable.
 * - Supports a configurable safe policy when event data is unavailable.
 */
object EventRiskManager {

    /**
     * Determines whether an economic event for `currency` / `country` with `impact` is relevant
     * to a given crypto trading pair (`symbol`, e.g., `BTCUSDT`, `ETHUSDT`, `DOGEUSDT`, `BTCEUR`).
     */
    fun isEventRelevantToPair(
        currency: String,
        country: String = "",
        impact: EventImpactLevel,
        symbol: String
    ): Boolean {
        val cleanSym = symbol.trim().uppercase(Locale.US)
            .replace("/", "")
            .replace("-", "")
            .replace("_", "")
        val cleanCcy = currency.trim().uppercase(Locale.US)
        val cleanCountry = country.trim().uppercase(Locale.US)

        val isBullionUsd = cleanSym.startsWith("XAU") || cleanSym.startsWith("XAG")
        val isUsdEvent = cleanCcy == "USD" || cleanCountry.contains("UNITED STATES") || cleanCountry == "US"

        // Section 6: For XAU/USD (and XAG/USD), prioritize HIGH and MEDIUM impact USD economic events
        if (isBullionUsd) {
            if (isUsdEvent) {
                return impact in listOf(EventImpactLevel.HIGH, EventImpactLevel.MEDIUM)
            }
            if (impact == EventImpactLevel.HIGH && cleanCcy in setOf("EUR", "JPY", "GBP", "CNY", "CHF")) {
                return true
            }
            return cleanCcy.length >= 3 && cleanSym.endsWith(cleanCcy) && impact != EventImpactLevel.LOW
        }

        // 1. Direct non-USD currency match in pair symbol (e.g., EUR in BTCEUR / EURUSD, GBP in GBPUSD)
        if (!isUsdEvent && cleanCcy.length >= 3 && cleanSym.contains(cleanCcy)) {
            return impact != EventImpactLevel.LOW
        }

        val isUsdQuoted = cleanSym.endsWith("USDT") ||
            cleanSym.endsWith("USDC") ||
            cleanSym.endsWith("FDUSD") ||
            cleanSym.endsWith("USD")

        val isMajorMacroCrypto = cleanSym.startsWith("BTC") ||
            cleanSym.startsWith("ETH") ||
            cleanSym.startsWith("SOL") ||
            cleanSym.startsWith("BNB")

        // 2. USD / US macro events directly impact USD-quoted pairs;
        // High-impact USD events impact all USD-quoted pairs; Medium-impact USD events impact FX USD pairs & major macro crypto
        if (isUsdEvent) {
            return when (impact) {
                EventImpactLevel.HIGH -> isUsdQuoted || isMajorMacroCrypto
                EventImpactLevel.MEDIUM -> isMajorMacroCrypto || (cleanSym.endsWith("USD") && !cleanSym.endsWith("USDT"))
                EventImpactLevel.LOW -> false
            }
        }

        // 3. High-impact global liquidity bank decisions (EUR/ECB, JPY/BOJ, CNY/PBoC, GBP/BoE)
        // have secondary volatility spillover primarily onto major macro assets (BTC, ETH)
        if (impact == EventImpactLevel.HIGH && cleanCcy in setOf("EUR", "JPY", "CNY", "GBP")) {
            return isMajorMacroCrypto
        }

        // 4. Other regional currency events (e.g., AUD, CAD, NZD, CHF, or medium/low non-USD events)
        // do not have equal relevance to unrelated USDT altcoin pairs
        return false
    }

    fun toMacroEvent(
        ev: EconomicCalendarEvent,
        preBufferMinutes: Int = 30,
        postBufferMinutes: Int = 30
    ): MacroCalendarEvent {
        return MacroCalendarEvent(
            id = ev.id,
            title = ev.title,
            category = ev.currency.ifBlank { ev.country.ifBlank { "USD" } },
            impact = EventImpactLevel.fromCode(ev.impact),
            timestampMs = ev.eventTimeMs,
            preWindowMinutes = preBufferMinutes,
            postWindowMinutes = postBufferMinutes,
            forecast = ev.forecast,
            previous = ev.previous,
            actual = ev.actual,
            source = ev.source
        )
    }

    fun evaluateEventRiskFromCalendar(
        symbol: String,
        calendarState: CalendarLoadState,
        protectionMode: EventProtectionMode,
        preBufferMinutes: Int = 30,
        postBufferMinutes: Int = 30,
        unavailablePolicy: UnavailableEventPolicy = UnavailableEventPolicy.ALLOW_WITH_WARNING,
        manualEventLockActive: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): EventRiskAssessment {
        // 1. Explicit manual event lock override (for user-triggered event protection or testing)
        if (manualEventLockActive) {
            val manualEvent = MacroCalendarEvent(
                id = "manual_lock",
                title = "Manual High-Impact Event Lock",
                category = "USD",
                impact = EventImpactLevel.HIGH,
                timestampMs = nowMs,
                preWindowMinutes = preBufferMinutes,
                postWindowMinutes = postBufferMinutes,
                isCustomUserEvent = true
            )
            val shouldBlock = protectionMode != EventProtectionMode.OFF &&
                protectionMode != EventProtectionMode.WARN_ONLY
            return EventRiskAssessment(
                isEventDataVerified = true,
                isEventWindowActive = true,
                isApproachingSoon = true,
                shouldBlockNewBotEntries = shouldBlock,
                activeOrNextEvent = manualEvent,
                minutesToStart = 0L,
                minutesRemainingInWindow = postBufferMinutes.toLong(),
                statusBadge = if (shouldBlock) "WAIT — HIGH IMPACT EVENT" else "EVENT WARNING",
                summaryMessage = if (shouldBlock) {
                    "High-impact event lock active — new Bot entries blocked; open positions remain managed."
                } else {
                    "High-impact event lock active (Warn Only mode)."
                },
                botActionDirective = if (shouldBlock) "WAIT — HIGH IMPACT EVENT" else "WARN — HIGH IMPACT EVENT"
            )
        }

        // 2. If protection is explicitly turned OFF by user
        if (protectionMode == EventProtectionMode.OFF) {
            return EventRiskAssessment(
                isEventDataVerified = calendarState.isDataVerifiedAndAvailable,
                isEventWindowActive = false,
                isApproachingSoon = false,
                shouldBlockNewBotEntries = false,
                activeOrNextEvent = null,
                minutesToStart = null,
                minutesRemainingInWindow = null,
                statusBadge = "FILTER OFF",
                summaryMessage = "Event Risk Filter is disabled in settings.",
                botActionDirective = "READY"
            )
        }

        // 3. Requirement #5: If event data is unavailable, show uncertainty clearly and follow configurable safe policy.
        // NEVER claim that event risk was checked when it was not.
        val isVerified = calendarState.isDataVerifiedAndAvailable
        val rawEvents = calendarState.availableEvents

        if (!isVerified && rawEvents.isEmpty()) {
            val blockOnUnverified = unavailablePolicy == UnavailableEventPolicy.BLOCK_NEW_ENTRIES &&
                protectionMode != EventProtectionMode.WARN_ONLY
            val reasonText = when (calendarState) {
                is CalendarLoadState.Loading -> "Syncing Economic Calendar feed — event risk not yet verified."
                is CalendarLoadState.Offline -> "Offline: Economic Calendar unavailable — event risk not verified."
                is CalendarLoadState.RateLimited -> "Calendar API rate-limited — event risk not verified."
                is CalendarLoadState.ApiError -> "Calendar feed unavailable (${calendarState.message}) — event risk not verified."
                else -> "Economic Calendar data unavailable — event risk not verified."
            }
            return EventRiskAssessment(
                isEventDataVerified = false,
                isEventWindowActive = false,
                isApproachingSoon = false,
                shouldBlockNewBotEntries = blockOnUnverified,
                activeOrNextEvent = null,
                minutesToStart = null,
                minutesRemainingInWindow = null,
                statusBadge = if (blockOnUnverified) "BLOCKED · UNVERIFIED DATA" else "UNVERIFIED · EVENT DATA",
                summaryMessage = reasonText,
                botActionDirective = if (blockOnUnverified) {
                    "WAIT — EVENT DATA UNAVAILABLE"
                } else {
                    "CAUTION — EVENT DATA UNVERIFIED"
                }
            )
        }

        // 4. Filter events relevant to this crypto pair and within upcoming/recent window
        val relevantMacroEvents = rawEvents
            .map { toMacroEvent(it, preBufferMinutes, postBufferMinutes) }
            .filter { ev ->
                isEventRelevantToPair(
                    currency = ev.category,
                    country = ev.category,
                    impact = ev.impact,
                    symbol = symbol
                )
            }
            .filter { ev ->
                ev.timestampMs + (postBufferMinutes * 60_000L) >= nowMs - 5 * 60_000L
            }
            .sortedBy { it.timestampMs }

        if (relevantMacroEvents.isEmpty()) {
            val suffix = if (!isVerified) " (cached feed)" else ""
            return EventRiskAssessment(
                isEventDataVerified = isVerified,
                isEventWindowActive = false,
                isApproachingSoon = false,
                shouldBlockNewBotEntries = false,
                activeOrNextEvent = null,
                minutesToStart = null,
                minutesRemainingInWindow = null,
                statusBadge = if (isVerified) "CLEAR · VERIFIED" else "CLEAR · CACHED",
                summaryMessage = "No relevant high/medium-impact events affecting $symbol$suffix.",
                botActionDirective = "READY"
            )
        }

        // 5. Check if `nowMs` falls inside any relevant event's [eventTime - preBuffer, eventTime + postBuffer]
        for (ev in relevantMacroEvents) {
            val windowStart = ev.timestampMs - (preBufferMinutes * 60_000L)
            val windowEnd = ev.timestampMs + (postBufferMinutes * 60_000L)
            if (nowMs in windowStart..windowEnd) {
                val minsToStart = (ev.timestampMs - nowMs) / 60_000L
                val minsRemaining = ((windowEnd - nowMs) / 60_000L).coerceAtLeast(1L)
                val shouldBlock = when (protectionMode) {
                    EventProtectionMode.OFF -> false
                    EventProtectionMode.WARN_ONLY -> false
                    EventProtectionMode.BLOCK_HIGH_IMPACT -> ev.impact == EventImpactLevel.HIGH
                    EventProtectionMode.BLOCK_ALL -> ev.impact in listOf(EventImpactLevel.HIGH, EventImpactLevel.MEDIUM)
                }
                val timingText = if (minsToStart > 0) {
                    "starts in ${minsToStart}m"
                } else {
                    "active (${minsRemaining}m left in post-event window)"
                }
                val directive = if (shouldBlock) {
                    "WAIT — ${ev.impact.code} IMPACT EVENT"
                } else {
                    "WARN — ${ev.impact.code} IMPACT EVENT"
                }
                return EventRiskAssessment(
                    isEventDataVerified = isVerified,
                    isEventWindowActive = true,
                    isApproachingSoon = true,
                    shouldBlockNewBotEntries = shouldBlock,
                    activeOrNextEvent = ev,
                    minutesToStart = minsToStart,
                    minutesRemainingInWindow = minsRemaining,
                    statusBadge = directive,
                    summaryMessage = "${ev.category} ${ev.title} (${ev.impact.displayLabel}) $timingText",
                    botActionDirective = directive
                )
            }
        }

        // 6. Check next approaching relevant event
        val nextEv = relevantMacroEvents.firstOrNull { it.timestampMs > nowMs } ?: relevantMacroEvents.first()
        val minsToStart = ((nextEv.timestampMs - nowMs) / 60_000L).coerceAtLeast(0L)
        val approaching = minsToStart <= 90L

        return EventRiskAssessment(
            isEventDataVerified = isVerified,
            isEventWindowActive = false,
            isApproachingSoon = approaching,
            shouldBlockNewBotEntries = false,
            activeOrNextEvent = nextEv,
            minutesToStart = minsToStart,
            minutesRemainingInWindow = null,
            statusBadge = if (approaching) "UPCOMING · ${minsToStart}m" else "CLEAR · VERIFIED",
            summaryMessage = "Next relevant event for $symbol: ${nextEv.category} ${nextEv.title} (${nextEv.impact.code}) in ${formatDurationMinutes(minsToStart)}",
            botActionDirective = "READY"
        )
    }

    fun formatEventTimeLocal(timestampMs: Long): String {
        if (timestampMs <= 0L) return "--:--"
        val sdf = SimpleDateFormat("dd MMM HH:mm z", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        return sdf.format(Date(timestampMs))
    }

    fun formatEventTimeUtc(timestampMs: Long): String {
        if (timestampMs <= 0L) return "--:--"
        val sdf = SimpleDateFormat("HH:mm 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return sdf.format(Date(timestampMs))
    }

    private fun formatDurationMinutes(minutes: Long): String {
        val absMins = abs(minutes)
        val h = absMins / 60L
        val m = absMins % 60L
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
