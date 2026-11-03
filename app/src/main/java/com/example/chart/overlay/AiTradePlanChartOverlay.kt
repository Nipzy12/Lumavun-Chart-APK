package com.example.chart.overlay

import com.example.ai.plan.StructuredTradePlan
import com.example.ai.plan.TradeDirection
import com.example.ai.plan.TradeOrderType
import com.example.ai.plan.TradeSetupStatus
import com.example.ai.strategy.AiStrategy
import com.example.ai.tracker.TrackedPendingSetup
import com.example.ai.tracker.TrackedSetupStatus
import java.util.Locale
import java.util.regex.Pattern

/**
 * LUMAVUN Chart — AI Insight V3.3: AI Trade Plan Chart Overlay Model.
 *
 * Immutable presentation model representing validated AI trade plans and tracked setups
 * rendered directly on the candlestick chart viewport.
 *
 * Strictly visual and analytical — never claims broker fills or executes live orders.
 */
data class AiTradePlanChartOverlay(
    val setupId: String,
    val symbol: String,
    val timeframe: String,
    val strategyId: String = AiStrategy.DEFAULT.id,
    val strategyName: String = AiStrategy.DEFAULT.displayName,
    val direction: TradeDirection,
    val orderType: TradeOrderType,
    val lifecycleStatus: TrackedSetupStatus,
    val entryZoneMin: Double,
    val entryZoneMax: Double,
    val stopLoss: Double,
    val tp1: Double,
    val tp2: Double? = null,
    val tp3: Double? = null,
    val invalidationLevel: Double? = null,
    val invalidationCondition: String = "",
    val isSelected: Boolean = true,
    val isVisible: Boolean = true,
    val isTracked: Boolean = false,
    val referencePrice: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis()
) {
    val upperEntryPrice: Double
        get() = maxOf(entryZoneMin, entryZoneMax)

    val lowerEntryPrice: Double
        get() = minOf(entryZoneMin, entryZoneMax)

    val isBuy: Boolean
        get() = direction == TradeDirection.BUY

    val isSell: Boolean
        get() = direction == TradeDirection.SELL

    val orderActionLabel: String
        get() = when (direction) {
            TradeDirection.BUY -> when (orderType) {
                TradeOrderType.MARKET -> "BUY MARKET"
                TradeOrderType.LIMIT -> "BUY LIMIT"
                TradeOrderType.STOP -> "BUY STOP"
                TradeOrderType.NONE -> "BUY"
            }
            TradeDirection.SELL -> when (orderType) {
                TradeOrderType.MARKET -> "SELL MARKET"
                TradeOrderType.LIMIT -> "SELL LIMIT"
                TradeOrderType.STOP -> "SELL STOP"
                TradeOrderType.NONE -> "SELL"
            }
            TradeDirection.NEUTRAL -> "WAIT"
        }
}

/**
 * Mapper and resolver for AI Trade Plan Chart Overlays.
 * Pure deterministic mapping and filtering — never initiates network or AI calls.
 */
object TradePlanOverlayMapper {

    private val NUMBER_PATTERN: Pattern = Pattern.compile("([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]+)?|[0-9]+(?:\\.[0-9]+)?)")

    /**
     * Attempts to extract a numeric price from an invalidation condition text.
     */
    fun extractNumericPrice(conditionText: String?, referencePrice: Double): Double? {
        if (conditionText.isNullOrBlank()) return null
        return try {
            val matcher = NUMBER_PATTERN.matcher(conditionText)
            var candidatePrice: Double? = null
            while (matcher.find()) {
                val raw = matcher.group(1)?.replace(",", "") ?: continue
                val parsed = raw.toDoubleOrNull() ?: continue
                if (parsed > 0.0) {
                    // Check if price is within plausible range (0.05x to 20x reference price if available)
                    if (referencePrice > 0.0) {
                        if (parsed in (referencePrice * 0.05)..(referencePrice * 20.0)) {
                            candidatePrice = parsed
                            break
                        }
                    } else {
                        candidatePrice = parsed
                        break
                    }
                }
            }
            candidatePrice
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Checks if two timeframe strings match (e.g. "15M", "15m", "1H", "1h").
     */
    fun timeframesMatch(tf1: String?, tf2: String?): Boolean {
        if (tf1 == null || tf2 == null) return false
        val clean1 = tf1.trim().lowercase(Locale.US)
        val clean2 = tf2.trim().lowercase(Locale.US)
        return clean1 == clean2
    }

    /**
     * Maps a [TrackedPendingSetup] to an [AiTradePlanChartOverlay].
     */
    fun fromTrackedSetup(
        setup: TrackedPendingSetup,
        isSelected: Boolean = false,
        isVisible: Boolean = true
    ): AiTradePlanChartOverlay {
        val invLevel = extractNumericPrice(setup.invalidationCondition, setup.lastVerifiedPrice)

        return AiTradePlanChartOverlay(
            setupId = setup.setupId,
            symbol = setup.symbol.uppercase(Locale.US),
            timeframe = setup.timeframe,
            strategyId = setup.strategyId,
            strategyName = setup.strategyName,
            direction = setup.direction,
            orderType = setup.orderType,
            lifecycleStatus = setup.currentStatus,
            entryZoneMin = setup.entryZoneMin,
            entryZoneMax = setup.entryZoneMax,
            stopLoss = setup.stopLoss,
            tp1 = setup.tp1,
            tp2 = setup.tp2,
            tp3 = setup.tp3,
            invalidationLevel = invLevel,
            invalidationCondition = setup.invalidationCondition,
            isSelected = isSelected,
            isVisible = isVisible,
            isTracked = true,
            referencePrice = setup.lastVerifiedPrice,
            createdAt = setup.createdAt
        )
    }

    /**
     * Maps a validated [StructuredTradePlan] to an [AiTradePlanChartOverlay].
     * Returns null if the plan is not actionable/pending or missing core levels.
     */
    fun fromTradePlan(
        plan: StructuredTradePlan,
        isSelected: Boolean = true,
        isTracked: Boolean = false,
        isVisible: Boolean = true
    ): AiTradePlanChartOverlay? {
        if (plan.isWaitOrInvalid) return null
        if (plan.direction == TradeDirection.NEUTRAL) return null

        val min = plan.entryZoneMin ?: return null
        val max = plan.entryZoneMax ?: return null
        val sl = plan.stopLoss ?: return null
        val tp1 = plan.tp1 ?: return null

        if (min <= 0.0 || max <= 0.0 || sl <= 0.0 || tp1 <= 0.0) return null
        if (min.isNaN() || max.isNaN() || sl.isNaN() || tp1.isNaN()) return null

        val mappedStatus = when (plan.setupStatus) {
            TradeSetupStatus.ACTIONABLE -> TrackedSetupStatus.PRICE_TRIGGERED
            TradeSetupStatus.PENDING -> TrackedSetupStatus.PENDING
            TradeSetupStatus.WAIT -> TrackedSetupStatus.INVALIDATED
            TradeSetupStatus.INVALID -> TrackedSetupStatus.INVALIDATED
        }

        val invLevel = extractNumericPrice(plan.invalidationCondition, plan.referencePrice)

        val uniqueId = "PLAN_${plan.symbol.uppercase(Locale.US)}_${plan.timeframe}_${plan.referenceTimestamp}"

        return AiTradePlanChartOverlay(
            setupId = uniqueId,
            symbol = plan.symbol.uppercase(Locale.US),
            timeframe = plan.timeframe,
            strategyId = plan.strategyId,
            strategyName = plan.strategyName,
            direction = plan.direction,
            orderType = plan.orderType,
            lifecycleStatus = mappedStatus,
            entryZoneMin = minOf(min, max),
            entryZoneMax = maxOf(min, max),
            stopLoss = sl,
            tp1 = tp1,
            tp2 = plan.tp2,
            tp3 = plan.tp3,
            invalidationLevel = invLevel,
            invalidationCondition = plan.invalidationCondition,
            isSelected = isSelected,
            isVisible = isVisible,
            isTracked = isTracked,
            referencePrice = plan.referencePrice,
            createdAt = plan.referenceTimestamp
        )
    }

    /**
     * Deterministically resolves which overlay to render for the active symbol and timeframe.
     *
     * Selection Priority:
     * 1. If [showOverlay] is false -> null.
     * 2. If [selectedSetupId] is specified and matches an existing tracked setup for (symbol, timeframe) -> that setup.
     * 3. An active (non-terminal) tracked setup for (symbol, timeframe), most recent first.
     * 4. The current validated AI [tradePlan] for (symbol, timeframe) if fresh (< 24h) and actionable/pending.
     * 5. Any recently resolved setup for (symbol, timeframe) within 6 hours.
     */
    fun resolveActiveOverlay(
        currentSymbol: String,
        currentTimeframe: String,
        selectedSetupId: String?,
        trackedSetups: List<TrackedPendingSetup>,
        tradePlan: StructuredTradePlan?,
        showOverlay: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): AiTradePlanChartOverlay? {
        if (!showOverlay) return null

        val matchingSetups = trackedSetups.filter {
            it.symbol.equals(currentSymbol, ignoreCase = true) &&
                timeframesMatch(it.timeframe, currentTimeframe)
        }

        // 1. Explicit user selection
        if (!selectedSetupId.isNullOrBlank()) {
            val explicitMatch = matchingSetups.firstOrNull { it.setupId == selectedSetupId }
            if (explicitMatch != null) {
                return fromTrackedSetup(explicitMatch, isSelected = true, isVisible = true)
            }
        }

        // 2. Best active tracked setup
        val activeTracked = matchingSetups
            .filter { !it.isTerminal }
            .maxByOrNull { it.createdAt }

        if (activeTracked != null) {
            return fromTrackedSetup(activeTracked, isSelected = true, isVisible = true)
        }

        // 3. Current validated AI trade plan
        if (tradePlan != null &&
            tradePlan.symbol.equals(currentSymbol, ignoreCase = true) &&
            timeframesMatch(tradePlan.timeframe, currentTimeframe) &&
            !tradePlan.isWaitOrInvalid &&
            (nowMs - tradePlan.referenceTimestamp) < 24 * 60 * 60 * 1000L
        ) {
            val mappedPlan = fromTradePlan(tradePlan, isSelected = true, isTracked = false, isVisible = true)
            if (mappedPlan != null) return mappedPlan
        }

        // 4. Fallback to most recent terminal setup within 6 hours
        val recentTerminal = matchingSetups
            .filter { (nowMs - it.lastVerifiedTimestamp) < 6 * 60 * 60 * 1000L }
            .maxByOrNull { it.lastVerifiedTimestamp }

        if (recentTerminal != null) {
            return fromTrackedSetup(recentTerminal, isSelected = false, isVisible = true)
        }

        return null
    }
}
