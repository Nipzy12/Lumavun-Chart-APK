package com.example.ai.tracker

import com.example.ai.plan.StructuredTradePlan
import com.example.ai.plan.TradeDirection
import com.example.ai.plan.TradeOrderType
import com.example.ai.strategy.AiStrategy
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Analytical tracking statuses for pending setups.
 * Strictly analytical monitoring milestones — NOT broker execution or order fill states.
 */
enum class TrackedSetupStatus(
    val code: String,
    val displayLabel: String,
    val isTerminal: Boolean
) {
    PENDING("PENDING", "Pending Trigger", false),
    PRICE_TRIGGERED("PRICE_TRIGGERED", "Price Triggered (Entry Touched)", false),
    TP1_REACHED("TP1_REACHED", "TP1 Reached", false),
    TP2_REACHED("TP2_REACHED", "TP2 Reached", false),
    TP3_REACHED("TP3_REACHED", "TP3 Reached (Complete)", true),
    STOP_LOSS_REACHED("STOP_LOSS_REACHED", "Stop Loss Reached", true),
    INVALIDATED("INVALIDATED", "Invalidated", true),
    EXPIRED("EXPIRED", "Expired", true),
    CANCELLED("CANCELLED", "Cancelled by User", true),
    AMBIGUOUS("AMBIGUOUS", "Ambiguous Intrabar SL/TP", true);

    companion object {
        fun fromString(raw: String?): TrackedSetupStatus {
            val clean = raw?.trim()?.uppercase(Locale.US) ?: return PENDING
            return entries.firstOrNull { it.code.equals(clean, ignoreCase = true) } ?: PENDING
        }
    }
}

/**
 * Audit record of a single deterministic status transition.
 */
data class SetupTransitionRecord(
    val timestamp: Long,
    val fromStatus: TrackedSetupStatus,
    val toStatus: TrackedSetupStatus,
    val priceAtTransition: Double,
    val reason: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("timestamp", timestamp)
            put("fromStatus", fromStatus.code)
            put("toStatus", toStatus.code)
            put("priceAtTransition", priceAtTransition)
            put("reason", reason)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): SetupTransitionRecord {
            return SetupTransitionRecord(
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                fromStatus = TrackedSetupStatus.fromString(json.optString("fromStatus")),
                toStatus = TrackedSetupStatus.fromString(json.optString("toStatus")),
                priceAtTransition = json.optDouble("priceAtTransition", 0.0),
                reason = json.optString("reason", "")
            )
        }

        fun listToJson(list: List<SetupTransitionRecord>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(jsonStr: String?): List<SetupTransitionRecord> {
            if (jsonStr.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(jsonStr)
                val result = mutableListOf<SetupTransitionRecord>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i)
                    if (obj != null) {
                        result.add(fromJson(obj))
                    }
                }
                result
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

/**
 * LUMAVUN Chart — AI Insight V3.2: Tracked Pending Setup.
 *
 * Represents an active or historical analytical trade setup tracked deterministically
 * across market ticks and candles. Never represents a broker order or live execution.
 */
data class TrackedPendingSetup(
    val setupId: String,
    val strategyId: String = AiStrategy.DEFAULT.id,
    val strategyName: String = AiStrategy.DEFAULT.displayName,
    val symbol: String,
    val timeframe: String,
    val direction: TradeDirection,
    val orderType: TradeOrderType,
    val entryZoneMin: Double,
    val entryZoneMax: Double,
    val stopLoss: Double,
    val tp1: Double,
    val tp2: Double? = null,
    val tp3: Double? = null,
    val riskRewardTp1: Double? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = createdAt + DEFAULT_EXPIRY_MS,
    val currentStatus: TrackedSetupStatus = TrackedSetupStatus.PENDING,
    val lastVerifiedPrice: Double = 0.0,
    val lastVerifiedTimestamp: Long = createdAt,
    val invalidationCondition: String = "",
    val transitionHistory: List<SetupTransitionRecord> = emptyList()
) {
    val isTerminal: Boolean
        get() = currentStatus.isTerminal

    val isPending: Boolean
        get() = currentStatus == TrackedSetupStatus.PENDING

    val isTriggered: Boolean
        get() = currentStatus == TrackedSetupStatus.PRICE_TRIGGERED ||
            currentStatus == TrackedSetupStatus.TP1_REACHED ||
            currentStatus == TrackedSetupStatus.TP2_REACHED

    companion object {
        const val DEFAULT_EXPIRY_MS: Long = 24 * 60 * 60 * 1000L // 24 hours

        /**
         * Resolves expiration duration based on timeframe.
         */
        fun resolveExpiryDurationMs(timeframeLabel: String): Long {
            return when (timeframeLabel.trim().uppercase(Locale.US)) {
                "1M" -> 60 * 60 * 1000L // 1 hour
                "5M" -> 2 * 60 * 60 * 1000L // 2 hours
                "15M" -> 6 * 60 * 60 * 1000L // 6 hours
                "30M" -> 12 * 60 * 60 * 1000L // 12 hours
                "1H" -> 24 * 60 * 60 * 1000L // 24 hours
                "4H" -> 72 * 60 * 60 * 1000L // 3 days
                "1D" -> 7 * 24 * 60 * 60 * 1000L // 7 days
                else -> DEFAULT_EXPIRY_MS
            }
        }

        /**
         * Creates a [TrackedPendingSetup] from a validated [StructuredTradePlan].
         */
        fun fromTradePlan(
            plan: StructuredTradePlan,
            currentPrice: Double,
            timestampMs: Long = System.currentTimeMillis()
        ): TrackedPendingSetup? {
            val min = plan.entryZoneMin ?: return null
            val max = plan.entryZoneMax ?: return null
            val sl = plan.stopLoss ?: return null
            val tp1 = plan.tp1 ?: return null

            val uniqueId = "SETUP_${plan.symbol.uppercase(Locale.US)}_${plan.timeframe.uppercase(Locale.US)}_${timestampMs}_${(1000..9999).random()}"
            val expiryDuration = resolveExpiryDurationMs(plan.timeframe)

            val initialTransition = SetupTransitionRecord(
                timestamp = timestampMs,
                fromStatus = TrackedSetupStatus.PENDING,
                toStatus = TrackedSetupStatus.PENDING,
                priceAtTransition = currentPrice,
                reason = "Setup tracking initialized from ${plan.strategyName} ${plan.orderActionLabel}"
            )

            return TrackedPendingSetup(
                setupId = uniqueId,
                strategyId = plan.strategyId,
                strategyName = plan.strategyName,
                symbol = plan.symbol.uppercase(Locale.US),
                timeframe = plan.timeframe,
                direction = plan.direction,
                orderType = plan.orderType,
                entryZoneMin = minOf(min, max),
                entryZoneMax = maxOf(min, max),
                stopLoss = sl,
                tp1 = tp1,
                tp2 = plan.tp2,
                tp3 = plan.tp3,
                riskRewardTp1 = plan.riskRewardTp1,
                createdAt = timestampMs,
                expiresAt = timestampMs + expiryDuration,
                currentStatus = TrackedSetupStatus.PENDING,
                lastVerifiedPrice = currentPrice,
                lastVerifiedTimestamp = timestampMs,
                invalidationCondition = plan.invalidationCondition,
                transitionHistory = listOf(initialTransition)
            )
        }
    }
}
