package com.example.storage.preferences

import java.util.TimeZone

/**
 * Supported timezone preferences for the Economic Calendar.
 */
enum class CalendarTimezoneOption(
    val code: String,
    val displayLabel: String,
    val shortLabel: String,
    val timeZoneId: String? // null represents device system default
) {
    DEVICE("DEVICE", "Perangkat (Device)", "Perangkat", null),
    UTC("UTC", "UTC (Coordinated Universal Time)", "UTC", "UTC"),
    WIB("WIB", "WIB (Asia/Jakarta)", "WIB", "Asia/Jakarta"),
    WITA("WITA", "WITA (Asia/Makassar)", "WITA", "Asia/Makassar"),
    WIT("WIT", "WIT (Asia/Jayapura)", "WIT", "Asia/Jayapura"),
    LONDON("LONDON", "London (Europe/London)", "London", "Europe/London"),
    NEW_YORK("NEW_YORK", "New York (America/New_York)", "New York", "America/New_York"),
    TOKYO("TOKYO", "Tokyo (Asia/Tokyo)", "Tokyo", "Asia/Tokyo");

    fun getTimeZone(): TimeZone {
        return if (timeZoneId == null) TimeZone.getDefault() else TimeZone.getTimeZone(timeZoneId)
    }

    /**
     * Header badge formatted as e.g. "🕐 WITA", "🕐 UTC", "🕐 WIB", "🕐 Perangkat".
     */
    val headerBadge: String
        get() = "🕐 $shortLabel"

    companion object {
        fun fromCode(code: String?): CalendarTimezoneOption {
            return entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) } ?: DEVICE
        }
    }
}
