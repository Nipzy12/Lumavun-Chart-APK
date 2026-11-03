package com.example.ai.plan

import com.example.ai.strategy.AiStrategy
import java.util.Locale

/**
 * Directional intent of a structured trade plan.
 */
enum class TradeDirection(val code: String, val displayLabel: String) {
    BUY("BUY", "Buy"),
    SELL("SELL", "Sell"),
    NEUTRAL("NEUTRAL", "Neutral");

    companion object {
        fun fromString(raw: String?): TradeDirection {
            val clean = raw?.trim()?.uppercase(Locale.US) ?: return NEUTRAL
            return when {
                clean == "BUY" || clean == "LONG" || clean.contains("BULL") -> BUY
                clean == "SELL" || clean == "SHORT" || clean.contains("BEAR") -> SELL
                else -> NEUTRAL
            }
        }
    }
}

/**
 * Execution mechanism recommended for the trade setup.
 */
enum class TradeOrderType(val code: String, val displayLabel: String) {
    MARKET("MARKET", "Market"),
    LIMIT("LIMIT", "Limit"),
    STOP("STOP", "Stop"),
    NONE("NONE", "None");

    companion object {
        fun fromString(raw: String?): TradeOrderType {
            val clean = raw?.trim()?.uppercase(Locale.US) ?: return NONE
            return when (clean) {
                "MARKET" -> MARKET
                "LIMIT" -> LIMIT
                "STOP" -> STOP
                "NONE", "WAIT" -> NONE
                else -> NONE
            }
        }
    }
}

/**
 * Lifecycle and execution readiness status of the trade setup.
 */
enum class TradeSetupStatus(val code: String, val displayLabel: String) {
    ACTIONABLE("ACTIONABLE", "Actionable (Immediate)"),
    PENDING("PENDING", "Pending Order"),
    WAIT("WAIT", "Wait / No Trade"),
    INVALID("INVALID", "Invalid Setup");

    companion object {
        fun fromString(raw: String?): TradeSetupStatus {
            val clean = raw?.trim()?.uppercase(Locale.US) ?: return WAIT
            return when (clean) {
                "ACTIONABLE" -> ACTIONABLE
                "PENDING" -> PENDING
                "WAIT", "HOLD", "NO_TRADE" -> WAIT
                "INVALID", "REJECTED" -> INVALID
                else -> WAIT
            }
        }
    }
}

/**
 * Status of broker-specific execution readiness.
 * Analytical trade plans based on candle close prices are UNVERIFIED until live broker
 * spread, tick size, account leverage, and minimum stop distances are validated.
 */
enum class BrokerVerificationStatus(val code: String, val displayLabel: String) {
    UNVERIFIED_BROKER_DATA(
        code = "UNVERIFIED_BROKER_DATA",
        displayLabel = "Broker Execution Unverified (Analytical Only)"
    ),
    VERIFIED_SIMULATED(
        code = "VERIFIED_SIMULATED",
        displayLabel = "Verified in Paper Simulation"
    ),
    FAILED_CONSTRAINTS(
        code = "FAILED_CONSTRAINTS",
        displayLabel = "Failed Broker Constraints"
    )
}

/**
 * LUMAVUN Chart — AI Insight V3.1A: Structured Trade Plan.
 *
 * Encapsulates strongly typed MARKET, LIMIT, and STOP trade recommendations,
 * strictly separating market bias from trade direction and preventing unverified broker execution.
 */
data class StructuredTradePlan(
    val strategy: AiStrategy = AiStrategy.DEFAULT,
    val strategyId: String = strategy.id,
    val strategyName: String = strategy.displayName,
    val symbol: String,
    val timeframe: String,
    val marketBias: String, // "BULLISH", "BEARISH", "NEUTRAL", "TRANSITIONAL"
    val direction: TradeDirection,
    val orderType: TradeOrderType,
    val setupStatus: TradeSetupStatus,
    val referencePrice: Double,
    val referenceTimestamp: Long = System.currentTimeMillis(),
    val entryZoneMin: Double? = null,
    val entryZoneMax: Double? = null,
    val stopLoss: Double? = null,
    val tp1: Double? = null,
    val tp2: Double? = null,
    val tp3: Double? = null,
    val riskRewardTp1: Double? = null,
    val riskRewardTp2: Double? = null,
    val riskRewardTp3: Double? = null,
    val setupRationale: String = "",
    val supportingEvidence: List<String> = emptyList(),
    val contradictingEvidence: List<String> = emptyList(),
    val invalidationCondition: String = "",
    val dataFreshnessInfo: String = "",
    val brokerVerificationStatus: BrokerVerificationStatus = BrokerVerificationStatus.UNVERIFIED_BROKER_DATA,
    val validationMessages: List<String> = emptyList()
) {
    val isActionable: Boolean
        get() = setupStatus == TradeSetupStatus.ACTIONABLE && direction != TradeDirection.NEUTRAL && orderType == TradeOrderType.MARKET

    val isPending: Boolean
        get() = setupStatus == TradeSetupStatus.PENDING && direction != TradeDirection.NEUTRAL &&
            (orderType == TradeOrderType.LIMIT || orderType == TradeOrderType.STOP)

    val isWaitOrInvalid: Boolean
        get() = setupStatus == TradeSetupStatus.WAIT || setupStatus == TradeSetupStatus.INVALID || direction == TradeDirection.NEUTRAL

    /**
     * Clear compound order action label, e.g. "BUY MARKET", "BUY LIMIT", "BUY STOP", "SELL MARKET", "SELL LIMIT", "SELL STOP", "WAIT".
     */
    val orderActionLabel: String
        get() = when {
            isWaitOrInvalid -> "WAIT"
            direction == TradeDirection.BUY -> when (orderType) {
                TradeOrderType.MARKET -> "BUY MARKET"
                TradeOrderType.LIMIT -> "BUY LIMIT"
                TradeOrderType.STOP -> "BUY STOP"
                TradeOrderType.NONE -> "WAIT"
            }
            direction == TradeDirection.SELL -> when (orderType) {
                TradeOrderType.MARKET -> "SELL MARKET"
                TradeOrderType.LIMIT -> "SELL LIMIT"
                TradeOrderType.STOP -> "SELL STOP"
                TradeOrderType.NONE -> "WAIT"
            }
            else -> "WAIT"
        }

    /**
     * Visual UI badge including status clarity.
     */
    val orderDisplayBadge: String
        get() = when {
            isWaitOrInvalid -> "🟡 WAIT"
            direction == TradeDirection.BUY -> when (orderType) {
                TradeOrderType.MARKET -> "🟢 BUY MARKET"
                TradeOrderType.LIMIT -> "🔵 BUY LIMIT (Pending)"
                TradeOrderType.STOP -> "🟣 BUY STOP (Pending Breakout)"
                TradeOrderType.NONE -> "🟡 WAIT"
            }
            direction == TradeDirection.SELL -> when (orderType) {
                TradeOrderType.MARKET -> "🔴 SELL MARKET"
                TradeOrderType.LIMIT -> "🟠 SELL LIMIT (Pending)"
                TradeOrderType.STOP -> "🟣 SELL STOP (Pending Breakdown)"
                TradeOrderType.NONE -> "🟡 WAIT"
            }
            else -> "🟡 WAIT"
        }

    /**
     * Explicit non-executed pending warning banner text.
     */
    val executionNotice: String
        get() = when {
            isActionable -> "ACTIONABLE MARKET SETUP · Requires manual user confirmation prior to execution."
            isPending -> "PENDING ORDER SETUP · Not executed. Awaiting price trigger at entry zone."
            setupStatus == TradeSetupStatus.INVALID -> "INVALID SETUP · Failed risk guardian validation checks."
            else -> "NO ACTION · Market conditions do not meet strategy confluence criteria."
        }
}
