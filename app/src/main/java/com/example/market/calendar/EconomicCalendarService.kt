package com.example.market.calendar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

enum class CalendarImpactFilter(val label: String) {
    ALL("All"),
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low")
}

enum class CalendarTimeView(val label: String) {
    TODAY("Today"),
    TOMORROW("Tomorrow"),
    UPCOMING("Upcoming")
}

data class EconomicCalendarEvent(
    val id: String,
    val title: String,
    val currency: String,
    val country: String,
    val eventTimeMs: Long,
    val impact: String,             // "HIGH", "MEDIUM", "LOW"
    val forecast: String,
    val previous: String,
    val actual: String,
    val source: String = "FinanceCalendar"
) {
    fun formatLocalTime(timeZone: TimeZone = TimeZone.getDefault()): String {
        if (eventTimeMs <= 0L) return "--:--"
        val fmt = SimpleDateFormat("dd MMM HH:mm z", Locale.US)
        fmt.timeZone = timeZone
        return fmt.format(Date(eventTimeMs))
    }

    /**
     * Requirement #3:
     * - Returns actual value if released.
     * - Returns "Pending" if event timestamp has passed but actual is not yet released.
     * - Returns "--" if event is upcoming.
     */
    fun getDisplayActual(): String {
        if (actual.isNotBlank()) return actual
        val now = System.currentTimeMillis()
        return if (eventTimeMs > 0L && now > eventTimeMs + 60_000L) {
            "Pending"
        } else {
            "--"
        }
    }

    fun getDisplayForecast(): String = forecast.ifBlank { "--" }
    fun getDisplayPrevious(): String = previous.ifBlank { "--" }
}

sealed class CalendarLoadState {
    data object Loading : CalendarLoadState()
    data class Success(
        val events: List<EconomicCalendarEvent>,
        val lastUpdatedMs: Long,
        val fromCache: Boolean = false,
        val statusNotice: String? = null
    ) : CalendarLoadState()
    data class Empty(
        val message: String = "No economic events scheduled for the selected filter.",
        val lastUpdatedMs: Long = System.currentTimeMillis()
    ) : CalendarLoadState()
    data class Offline(
        val cachedEvents: List<EconomicCalendarEvent> = emptyList(),
        val message: String = "Offline — unable to reach FinanceCalendar API."
    ) : CalendarLoadState()
    data class RateLimited(
        val cachedEvents: List<EconomicCalendarEvent> = emptyList(),
        val message: String = "Rate limit reached (HTTP 429) — showing cached calendar data if available."
    ) : CalendarLoadState()
    data class ApiError(
        val cachedEvents: List<EconomicCalendarEvent> = emptyList(),
        val message: String
    ) : CalendarLoadState()

    val isDataVerifiedAndAvailable: Boolean
        get() = this is Success || this is Empty

    val availableEvents: List<EconomicCalendarEvent>
        get() = when (this) {
            is Success -> events
            is Offline -> cachedEvents
            is RateLimited -> cachedEvents
            is ApiError -> cachedEvents
            else -> emptyList()
        }
}

/**
 * Requirement #4: Economic Calendar client using the documented FinanceCalendar WP-JSON API:
 * `https://www.financecalendar.com/wp-json/fc/v1`
 *
 * - Normalizes timestamps to UTC epoch ms and displays in user's local timezone.
 * - Never hardcodes private API keys.
 * - Never invents or fabricates economic events when offline or when the API errors.
 */
class EconomicCalendarService(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val BASE_API_URL = "https://www.financecalendar.com/wp-json/fc/v1"
        const val CACHE_TTL_MS = 15L * 60_000L // 15 minutes

        /**
         * Parses JSON payload from FinanceCalendar WP-JSON (`/wp-json/fc/v1/events` or `/wp-json/fc/v1`).
         * Supports both direct JSON arrays and `{ "events": [...] }` / `{ "data": [...] }` wrappers.
         */
        fun parseEventsJson(rawBody: String, defaultSource: String = "FinanceCalendar"): List<EconomicCalendarEvent>? {
            val trimmed = rawBody.trim()
            if (trimmed.isEmpty()) return null

            return try {
                val array: JSONArray? = when {
                    trimmed.startsWith("[") -> JSONArray(trimmed)
                    trimmed.startsWith("{") -> {
                        val obj = JSONObject(trimmed)
                        // If WP REST namespace root response (has "namespace": "fc/v1" and "routes" without events array),
                        // return null so caller can try the next endpoint or report schema status
                        if (obj.has("namespace") && obj.has("routes") && !obj.has("events") && !obj.has("data")) {
                            return null
                        }
                        obj.optJSONArray("events")
                            ?: obj.optJSONArray("data")
                            ?: obj.optJSONArray("items")
                            ?: obj.optJSONArray("results")
                    }
                    else -> null
                }

                if (array == null) return null

                val parsed = ArrayList<EconomicCalendarEvent>(array.length())
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val name = item.optString("name", "").trim()
                    val title = item.optString("title", "")
                        .ifEmpty { name }
                        .ifEmpty { item.optString("event", "") }
                        .trim()
                    if (title.isEmpty()) continue

                    val rawCcy = item.optString("currency", "")
                        .ifEmpty { item.optString("ccy", "") }
                    val rawCountry = item.optString("country", "")
                        .ifEmpty { item.optString("region", "") }
                    val (currency, country) = inferCurrencyAndCountry(title, name, rawCcy, rawCountry)

                    val rawImpact = item.optString("impact", "")
                        .ifEmpty { item.optString("importance", "") }
                        .ifEmpty { item.optString("volatility", "") }
                        .trim()
                        .uppercase(Locale.US)

                    val impact = when {
                        rawImpact.contains("HIGH") || rawImpact == "3" || rawImpact.contains("RED") -> "HIGH"
                        rawImpact.contains("MED") || rawImpact == "2" || rawImpact.contains("ORANGE") || rawImpact.contains("YELLOW") -> "MEDIUM"
                        else -> "LOW"
                    }

                    val eventTimeMs = extractTimestampMs(item)
                    if (eventTimeMs <= 0L) continue

                    val forecast = extractValue(
                        item,
                        "consensus",
                        "forecast",
                        "forecast_value",
                        "estimate",
                        "expected"
                    )

                    val previous = extractValue(
                        item,
                        "prior",
                        "previous",
                        "previous_value",
                        "prev",
                        "last"
                    )

                    val actual = extractValue(
                        item,
                        "actual",
                        "actual_value",
                        "result",
                        "value"
                    )

                    val rawSource = item.optString("source", defaultSource).trim()
                    val source = if (rawSource.startsWith("http") || rawSource.contains("/")) {
                        defaultSource
                    } else {
                        rawSource.ifEmpty { defaultSource }
                    }
                    val id = item.optString("id", "${currency}_${eventTimeMs}_${title.hashCode()}")

                    parsed.add(
                        EconomicCalendarEvent(
                            id = id,
                            title = title,
                            currency = currency,
                            country = country,
                            eventTimeMs = eventTimeMs,
                            impact = impact,
                            forecast = forecast,
                            previous = previous,
                            actual = actual,
                            source = source
                        )
                    )
                }
                parsed.sortedBy { it.eventTimeMs }
            } catch (_: Exception) {
                null
            }
        }

        private fun extractValue(item: JSONObject, vararg candidateKeys: String): String {
            for (key in candidateKeys) {
                if (!item.has(key) || item.isNull(key)) continue
                val str = item.optString(key, "").trim()
                if (str.isNotEmpty() && !str.equals("null", ignoreCase = true) && !str.equals("none", ignoreCase = true)) {
                    return str
                }
            }
            return ""
        }

        internal fun inferCurrencyAndCountry(
            title: String,
            name: String,
            rawCcy: String,
            rawCountry: String
        ): Pair<String, String> {
            val ccyClean = rawCcy.trim().uppercase(Locale.US)
            val countryClean = rawCountry.trim()
            if (ccyClean.isNotEmpty() && ccyClean != "USD" && countryClean.isNotEmpty()) {
                return ccyClean to countryClean
            }
            val combined = "$title $name".lowercase(Locale.US)
            return when {
                combined.contains("australia") || combined.contains("rba") -> "AUD" to "Australia"
                combined.contains("china") || combined.contains("chinese") || combined.contains("pboc") -> "CNY" to "China"
                combined.contains("uk ") || combined.contains("united kingdom") || combined.contains("britain") || combined.contains("boe") || combined.contains("bank of england") -> "GBP" to "United Kingdom"
                combined.contains("eurozone") || combined.contains("euro area") || combined.contains("ecb") || combined.contains("germany") || combined.contains("france") || combined.contains("italy") -> "EUR" to "Eurozone"
                combined.contains("japan") || combined.contains("boj") || combined.contains("yen") -> "JPY" to "Japan"
                combined.contains("canada") || combined.contains("boc") -> "CAD" to "Canada"
                combined.contains("switzerland") || combined.contains("snb") || combined.contains("swiss") -> "CHF" to "Switzerland"
                combined.contains("new zealand") || combined.contains("rbnz") -> "NZD" to "New Zealand"
                combined.contains("gold") || combined.contains("xau") -> "XAU" to "Global"
                combined.contains("oil") || combined.contains("crude") || combined.contains("brent") || combined.contains("wti") || combined.contains("opec") || combined.contains("eia") -> "OIL" to "Global"
                combined.contains("us ") || combined.contains("united states") || combined.contains("fed ") || combined.contains("fomc") || combined.contains("treasury") -> "USD" to "United States"
                ccyClean.isNotEmpty() -> ccyClean to countryClean.ifEmpty { ccyClean }
                else -> "USD" to "United States"
            }
        }

        internal fun extractTimestampMs(item: JSONObject): Long {
            val numericCandidates = listOf("timestamp", "time_ms", "epoch", "unix", "date_unix")
            for (key in numericCandidates) {
                if (item.has(key)) {
                    val v = item.optLong(key, -1L)
                    if (v > 0L) {
                        return if (v < 10_000_000_000L) v * 1000L else v
                    }
                }
            }

            val rawTimeUtc = item.optString("time_utc", "").trim()
            val rawDate = item.optString("date", item.optString("datetime", "")).trim()
            val rawTime = item.optString("time", item.optString("event_time", "")).trim()

            val candidateStrings = mutableListOf<String>()
            if (rawTimeUtc.isNotEmpty()) candidateStrings.add(rawTimeUtc)
            if (rawDate.isNotEmpty() && rawTime.isNotEmpty() && !rawTime.contains("T") && !rawTime.startsWith("20")) {
                candidateStrings.add("$rawDate $rawTime")
            }
            if (rawDate.isNotEmpty() && !candidateStrings.contains(rawDate)) {
                candidateStrings.add(rawDate)
            }

            val patterns = listOf(
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd"
            )

            for (cand in candidateStrings) {
                cand.toLongOrNull()?.let { num ->
                    if (num > 0L) return if (num < 10_000_000_000L) num * 1000L else num
                }

                for (pattern in patterns) {
                    try {
                        val sdf = SimpleDateFormat(pattern, Locale.US)
                        if (!pattern.contains("X") && !pattern.contains("'Z'")) {
                            sdf.timeZone = TimeZone.getTimeZone("UTC")
                        }
                        val parsedDate = sdf.parse(cand)
                        if (parsedDate != null) return parsedDate.time
                    } catch (_: Exception) {
                    }
                }
            }
            return 0L
        }

        fun filterEvents(
            events: List<EconomicCalendarEvent>,
            impactFilter: CalendarImpactFilter,
            timeView: CalendarTimeView,
            nowMs: Long = System.currentTimeMillis(),
            localTimeZone: TimeZone = TimeZone.getDefault()
        ): List<EconomicCalendarEvent> {
            val cal = Calendar.getInstance(localTimeZone).apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startOfTodayMs = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val startOfTomorrowMs = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val endOfTomorrowMs = cal.timeInMillis

            return events.filter { ev ->
                val impactMatch = when (impactFilter) {
                    CalendarImpactFilter.ALL -> true
                    CalendarImpactFilter.HIGH -> ev.impact.equals("HIGH", ignoreCase = true)
                    CalendarImpactFilter.MEDIUM -> ev.impact.equals("MEDIUM", ignoreCase = true)
                    CalendarImpactFilter.LOW -> ev.impact.equals("LOW", ignoreCase = true)
                }

                val timeMatch = when (timeView) {
                    CalendarTimeView.TODAY -> ev.eventTimeMs in startOfTodayMs until startOfTomorrowMs
                    CalendarTimeView.TOMORROW -> ev.eventTimeMs in startOfTomorrowMs until endOfTomorrowMs
                    CalendarTimeView.UPCOMING -> ev.eventTimeMs >= (nowMs - 2 * 3600_000L)
                }

                impactMatch && timeMatch
            }.sortedBy { it.eventTimeMs }
        }

        fun serializeEventsToJson(events: List<EconomicCalendarEvent>): String {
            val arr = JSONArray()
            for (ev in events) {
                val obj = JSONObject()
                obj.put("id", ev.id)
                obj.put("title", ev.title)
                obj.put("currency", ev.currency)
                obj.put("country", ev.country)
                obj.put("timestamp", ev.eventTimeMs)
                obj.put("impact", ev.impact)
                obj.put("forecast", ev.forecast)
                obj.put("previous", ev.previous)
                obj.put("actual", ev.actual)
                obj.put("source", ev.source)
                arr.put(obj)
            }
            return arr.toString()
        }
    }

    suspend fun fetchCalendarEvents(
        cachedEvents: List<EconomicCalendarEvent> = emptyList()
    ): CalendarLoadState = withContext(Dispatchers.IO) {
        val candidateUrls = listOf(
            "$BASE_API_URL/calendar",
            "$BASE_API_URL/today"
        )

        var lastHttpCode = -1
        var lastErrorMsg: String? = null

        for (url in candidateUrls) {
            var attempts = 0
            val maxAttempts = 2
            while (attempts < maxAttempts) {
                attempts++
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .header("User-Agent", "NexSyncAILite-Android/2.0")
                        .get()
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        lastHttpCode = response.code
                        if (response.code == 429) {
                            return@withContext CalendarLoadState.RateLimited(
                                cachedEvents = cachedEvents
                            )
                        }
                        if (response.isSuccessful) {
                            val body = response.body?.string().orEmpty()
                            val parsed = parseEventsJson(body, "FinanceCalendar")
                            if (parsed != null && parsed.isNotEmpty()) {
                                val now = System.currentTimeMillis()
                                return@withContext CalendarLoadState.Success(
                                    events = parsed,
                                    lastUpdatedMs = now,
                                    fromCache = false
                                )
                            } else if (parsed != null && parsed.isEmpty()) {
                                lastErrorMsg = "No events returned by provider."
                            }
                        } else {
                            lastErrorMsg = "FinanceCalendar API error (HTTP ${response.code})"
                        }
                    }
                } catch (_: UnknownHostException) {
                    return@withContext CalendarLoadState.Offline(cachedEvents = cachedEvents)
                } catch (_: SocketTimeoutException) {
                    lastErrorMsg = "FinanceCalendar connection timed out"
                } catch (e: Exception) {
                    lastErrorMsg = e.message ?: "Unable to load economic calendar"
                }

                if (attempts < maxAttempts) {
                    try {
                        kotlinx.coroutines.delay(600L)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        if (cachedEvents.isNotEmpty()) {
            CalendarLoadState.ApiError(
                cachedEvents = cachedEvents,
                message = "${lastErrorMsg ?: "FinanceCalendar service unavailable"} — showing cached events."
            )
        } else {
            CalendarLoadState.ApiError(
                cachedEvents = emptyList(),
                message = lastErrorMsg ?: "FinanceCalendar feed returned no events."
            )
        }
    }
}
