package com.example.analysis.eventRisk

import com.example.market.calendar.CalendarLoadState
import com.example.market.calendar.EconomicCalendarEvent
import java.util.Locale
import kotlin.math.abs

enum class EventRiskStatus(val badgeText: String) {
    CLEAR("CLEAR"),
    BLOCKED_HIGH_IMPACT("HIGH IMPACT EVENT"),
    BLOCKED_MEDIUM_IMPACT("MEDIUM IMPACT EVENT"),
    DATA_UNAVAILABLE_BLOCKED("CALENDAR UNAVAILABLE (BLOCKED)"),
    DATA_UNAVAILABLE_WARNING("CALENDAR DATA UNAVAILABLE (UNCHECKED)")
}

data class EventRiskAssessment(
    val status: EventRiskStatus,
    val blocksNewBotEntries: Boolean,
    val blockingEvent: EconomicCalendarEvent? = null,
    val minutesFromNow: Long? = null,
    val statusSummary: String
)

/**
 * Requirement #5: Event Risk Engine.
 * - Connects Economic Calendar events to Bot Action (never rewrites the technical AI signal).
 * - Configurable pre-event and post-event blocking windows for High-Impact (and optional Medium-Impact) events.
 * - Evaluates currency relevance for the target crypto pair.
 * - Explicitly reports uncertainty if calendar data is unavailable and follows configurable safe policy.
 */
object EventRiskEngine {

    private val MAJOR_MACRO_CURRENCIES = setOf("USD", "US", "UNITED STATES", "FED", "FOMC", "EUR", "GBP", "JPY", "CNY")

    /**
     * Determines whether an economic event's currency/country is relevant to a given crypto pair.
     * - USD events are directly relevant to USDT/USDC/USD-quoted crypto pairs and global crypto liquidity.
     * - Other currencies are relevant if they match the base/quote asset or are High-Impact major macro events.
     */
    fun isCurrencyRelevantToSymbol(
        symbol: String,
        eventCurrency: String,
        eventCountry: String,
        eventImpact: String
    ): Boolean {
        val cleanSym = symbol.trim().uppercase(Locale.US)
        val cleanCcy = eventCurrency.trim().uppercase(Locale.US)
        val cleanCountry = eventCountry.trim().uppercase(Locale.US)

        // Direct USD / US macro relevance for USDT / USDC / USD pairs
        if (cleanCcy in setOf("USD", "US", "USDT", "ALL", "GLOBAL") ||
            cleanCountry.contains("UNITED STATES") ||
            cleanCountry == "US"
        ) {
            return true
        }

        // Direct match with symbol prefix/suffix (e.g.,BTCEUR -> EUR)
        if (cleanCcy.length >= 3 && (cleanSym.startsWith(cleanCcy) || cleanSym.endsWith(cleanCcy))) {
            return true
        }

        // High-impact G5 central bank / macro events impact broad crypto volatility
        if (eventImpact.equals("HIGH", ignoreCase = true) && cleanCcy in MAJOR_MACRO_CURRENCIES) {
            return true
        }

        return false
    }

    fun evaluateEventRisk(
        symbol: String,
        calendarState: CalendarLoadState,
        blockBeforeHighMinutes: Int = 30,
        blockAfterHighMinutes: Int = 15,
        filterMediumImpact: Boolean = false,
        blockBeforeMediumMinutes: Int = 15,
        blockAfterMediumMinutes: Int = 10,
        blockWhenCalendarUnavailable: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): EventRiskAssessment {
        val events = calendarState.availableEvents
        val isDataVerified = calendarState.isDataVerifiedAndAvailable || events.isNotEmpty()

        // Requirement #5: If event data is unavailable, show uncertainty clearly and follow configurable safe policy.
        // Never claim that event risk was checked when it was not!
        if (!isDataVerified) {
            return if (blockWhenCalendarUnavailable) {
                EventRiskAssessment(
                    status = EventRiskStatus.DATA_UNAVAILABLE_BLOCKED,
                    blocksNewBotEntries = true,
                    blockingEvent = null,
                    minutesFromNow = null,
                    statusSummary = "Event data unavailable — Safe Policy blocking new bot entries"
                )
            } else {
                EventRiskAssessment(
                    status = EventRiskStatus.DATA_UNAVAILABLE_WARNING,
                    blocksNewBotEntries = false,
                    blockingEvent = null,
                    minutesFromNow = null,
                    statusSummary = "Event data unavailable — Event risk NOT verified (Proceeding with caution)"
                )
            }
        }

        val relevantEvents = events.filter { ev ->
            isCurrencyRelevantToSymbol(symbol, ev.currency, ev.country, ev.impact)
        }

        val preHighMs = blockBeforeHighMinutes.coerceAtLeast(0) * 60_000L
        val postHighMs = blockAfterHighMinutes.coerceAtLeast(0) * 60_000L
        val preMedMs = blockBeforeMediumMinutes.coerceAtLeast(0) * 60_000L
        val postMedMs = blockAfterMediumMinutes.coerceAtLeast(0) * 60_000L

        // 1. Check High-Impact window first
        for (ev in relevantEvents) {
            if (ev.impact.equals("HIGH", ignoreCase = true)) {
                val windowStart = ev.eventTimeMs - preHighMs
                val windowEnd = ev.eventTimeMs + postHighMs
                if (nowMs in windowStart..windowEnd) {
                    val diffMin = (ev.eventTimeMs - nowMs) / 60_000L
                    val timingDesc = if (diffMin >= 0) "in ${diffMin}m" else "${abs(diffMin)}m ago"
                    return EventRiskAssessment(
                        status = EventRiskStatus.BLOCKED_HIGH_IMPACT,
                        blocksNewBotEntries = true,
                        blockingEvent = ev,
                        minutesFromNow = diffMin,
                        statusSummary = "HIGH IMPACT EVENT: ${ev.currency} ${ev.title} ($timingDesc)"
                    )
                }
            }
        }

        // 2. Check Medium-Impact window if enabled
        if (filterMediumImpact) {
            for (ev in relevantEvents) {
                if (ev.impact.equals("MEDIUM", ignoreCase = true)) {
                    val windowStart = ev.eventTimeMs - preMedMs
                    val windowEnd = ev.eventTimeMs + postMedMs
                    if (nowMs in windowStart..windowEnd) {
                        val diffMin = (ev.eventTimeMs - nowMs) / 60_000L
                        val timingDesc = if (diffMin >= 0) "in ${diffMin}m" else "${abs(diffMin)}m ago"
                        return EventRiskAssessment(
                            status = EventRiskStatus.BLOCKED_MEDIUM_IMPACT,
                            blocksNewBotEntries = true,
                            blockingEvent = ev,
                            minutesFromNow = diffMin,
                            statusSummary = "MEDIUM IMPACT EVENT: ${ev.currency} ${ev.title} ($timingDesc)"
                        )
                    }
                }
            }
        }

        return EventRiskAssessment(
            status = EventRiskStatus.CLEAR,
            blocksNewBotEntries = false,
            blockingEvent = null,
            minutesFromNow = null,
            statusSummary = "No blocking macro events in window (-${blockBeforeHighMinutes}m / +${blockAfterHighMinutes}m)"
        )
    }
}
