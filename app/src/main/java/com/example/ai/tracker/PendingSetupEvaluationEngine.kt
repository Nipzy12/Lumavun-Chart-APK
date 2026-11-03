package com.example.ai.tracker

import com.example.ai.plan.TradeDirection
import com.example.ai.plan.TradeOrderType
import com.example.market.candle.Candle
import com.example.market.candle.formatCryptoPrice

/**
 * LUMAVUN Chart — AI Insight V3.2: Deterministic Pending Setup Evaluation Engine.
 *
 * Evaluates pending trade setups purely through mathematical price level checks:
 * - Never makes network or AI calls.
 * - Produces PRICE_TRIGGERED upon entry touch (never claiming broker execution).
 * - Flags AMBIGUOUS if a single candle breaches both Stop Loss and Take Profit.
 * - Refuses historical candles predating setup creation.
 * - Guards against stale or invalid price feeds.
 * - Prevents duplicate transition records.
 */
object PendingSetupEvaluationEngine {

    const val MAX_STALE_TOLERANCE_MS: Long = 2 * 60 * 60 * 1000L // 2 hours

    /**
     * Evaluates a live price tick against a tracked setup.
     */
    fun evaluateLiveTick(
        setup: TrackedPendingSetup,
        currentPrice: Double,
        nowMs: Long = System.currentTimeMillis()
    ): TrackedPendingSetup {
        // 1. Terminal setups cannot transition further
        if (setup.isTerminal) return setup

        // 2. Data validity checks
        if (!currentPrice.isFinite() || currentPrice <= 0.0) return setup

        // 3. Retroactive rejection: Do not process ticks with timestamps prior to creation
        if (nowMs < setup.createdAt) return setup

        // 4. Stale check: If timestamp is wildly distant or older than last verified, ignore
        if (nowMs < setup.lastVerifiedTimestamp) return setup

        // 5. Expiration check: If current time exceeds setup expiration, mark EXPIRED
        if (nowMs >= setup.expiresAt) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.EXPIRED,
                price = currentPrice,
                timestamp = nowMs,
                reason = "Setup duration expired (${formatTimeDiff(setup.expiresAt - setup.createdAt)}) without reaching terminal target"
            )
        }

        // 6. State machine transitions based on current status
        return when (setup.currentStatus) {
            TrackedSetupStatus.PENDING -> evaluatePendingTick(setup, currentPrice, nowMs)
            TrackedSetupStatus.PRICE_TRIGGERED -> evaluateTriggeredTick(setup, currentPrice, nowMs)
            TrackedSetupStatus.TP1_REACHED -> evaluateTp1Tick(setup, currentPrice, nowMs)
            TrackedSetupStatus.TP2_REACHED -> evaluateTp2Tick(setup, currentPrice, nowMs)
            else -> setup.copy(
                lastVerifiedPrice = currentPrice,
                lastVerifiedTimestamp = nowMs
            )
        }
    }

    /**
     * Evaluates an incoming candle against a tracked setup.
     */
    fun evaluateCandle(
        setup: TrackedPendingSetup,
        candle: Candle,
        nowMs: Long = System.currentTimeMillis()
    ): TrackedPendingSetup {
        // 1. Terminal setups cannot transition further
        if (setup.isTerminal) return setup

        // 2. Candle validity check
        if (!candle.high.isFinite() || !candle.low.isFinite() || candle.high <= 0.0 || candle.low <= 0.0) {
            return setup
        }

        // 3. Retroactive rejection: Do NOT use historical candle data predating setup creation
        // A candle whose closeTime (or openTime) is before the setup was created cannot trigger it!
        val candleEffectiveTime = if (candle.closeTime > 0L) candle.closeTime else candle.openTime
        if (candleEffectiveTime < setup.createdAt) {
            return setup
        }

        val evalTime = if (candle.closeTime > 0L) candle.closeTime else nowMs

        // 4. Expiration check
        if (evalTime >= setup.expiresAt) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.EXPIRED,
                price = candle.close,
                timestamp = evalTime,
                reason = "Setup expired at candle open time (${candle.openTime})"
            )
        }

        // 5. Intrabar Ambiguity Check (Rule #3):
        // If a single candle touches BOTH Stop Loss and Take Profit (TP1), intrabar order cannot
        // be determined without tick data. Mark as AMBIGUOUS instead of guessing.
        if (setup.isTriggered || isPriceInEntryZone(setup, candle.open) || isCandleCrossingEntry(setup, candle)) {
            val touchedSl = when (setup.direction) {
                TradeDirection.BUY -> candle.low <= setup.stopLoss
                TradeDirection.SELL -> candle.high >= setup.stopLoss
                TradeDirection.NEUTRAL -> false
            }
            val touchedTp1 = when (setup.direction) {
                TradeDirection.BUY -> candle.high >= setup.tp1
                TradeDirection.SELL -> candle.low <= setup.tp1
                TradeDirection.NEUTRAL -> false
            }

            if (touchedSl && touchedTp1) {
                return transition(
                    setup = setup,
                    targetStatus = TrackedSetupStatus.AMBIGUOUS,
                    price = candle.close,
                    timestamp = evalTime,
                    reason = "Intrabar ambiguity: Candle low (${formatCryptoPrice(candle.low)}) and high (${formatCryptoPrice(candle.high)}) breached both SL (${formatCryptoPrice(setup.stopLoss)}) and TP1 (${formatCryptoPrice(setup.tp1)}) within same candle"
                )
            }
        }

        // 6. Process candle progression
        return when (setup.currentStatus) {
            TrackedSetupStatus.PENDING -> evaluatePendingCandle(setup, candle, evalTime)
            TrackedSetupStatus.PRICE_TRIGGERED -> evaluateTriggeredCandle(setup, candle, evalTime)
            TrackedSetupStatus.TP1_REACHED -> evaluateTp1Candle(setup, candle, evalTime)
            TrackedSetupStatus.TP2_REACHED -> evaluateTp2Candle(setup, candle, evalTime)
            else -> setup.copy(
                lastVerifiedPrice = candle.close,
                lastVerifiedTimestamp = evalTime
            )
        }
    }

    /**
     * Manually cancels an active tracked setup upon user request.
     */
    fun cancelSetup(
        setup: TrackedPendingSetup,
        reason: String = "Tracking stopped by user",
        nowMs: Long = System.currentTimeMillis()
    ): TrackedPendingSetup {
        if (setup.isTerminal) return setup
        return transition(
            setup = setup,
            targetStatus = TrackedSetupStatus.CANCELLED,
            price = setup.lastVerifiedPrice,
            timestamp = nowMs,
            reason = reason
        )
    }

    // --- Private Tick Helpers ---

    private fun evaluatePendingTick(
        setup: TrackedPendingSetup,
        price: Double,
        nowMs: Long
    ): TrackedPendingSetup {
        // Pre-entry invalidation: If price hits Stop Loss before even touching Entry Zone
        val invalidSlHit = when (setup.direction) {
            TradeDirection.BUY -> price <= setup.stopLoss
            TradeDirection.SELL -> price >= setup.stopLoss
            TradeDirection.NEUTRAL -> false
        }
        if (invalidSlHit) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.INVALIDATED,
                price = price,
                timestamp = nowMs,
                reason = "Price (${formatCryptoPrice(price)}) breached stop loss (${formatCryptoPrice(setup.stopLoss)}) prior to entry zone activation"
            )
        }

        // Entry zone touch check -> PRICE_TRIGGERED
        val touchesEntry = isPriceInEntryZone(setup, price)
        if (touchesEntry) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.PRICE_TRIGGERED,
                price = price,
                timestamp = nowMs,
                reason = "Price (${formatCryptoPrice(price)}) entered proposed entry zone [${formatCryptoPrice(setup.entryZoneMin)} - ${formatCryptoPrice(setup.entryZoneMax)}]"
            )
        }

        return setup.copy(
            lastVerifiedPrice = price,
            lastVerifiedTimestamp = nowMs
        )
    }

    private fun evaluateTriggeredTick(
        setup: TrackedPendingSetup,
        price: Double,
        nowMs: Long
    ): TrackedPendingSetup {
        // Check Stop Loss
        if (isStopLossHit(setup, price)) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.STOP_LOSS_REACHED,
                price = price,
                timestamp = nowMs,
                reason = "Price (${formatCryptoPrice(price)}) reached stop loss level (${formatCryptoPrice(setup.stopLoss)})"
            )
        }

        // Check TP3 (highest priority target)
        if (setup.tp3 != null && isTakeProfitHit(setup, price, setup.tp3)) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.TP3_REACHED,
                price = price,
                timestamp = nowMs,
                reason = "Price (${formatCryptoPrice(price)}) reached final target TP3 (${formatCryptoPrice(setup.tp3)})"
            )
        }

        // Check TP2
        if (setup.tp2 != null && isTakeProfitHit(setup, price, setup.tp2)) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.TP2_REACHED,
                price = price,
                timestamp = nowMs,
                reason = "Price (${formatCryptoPrice(price)}) reached target TP2 (${formatCryptoPrice(setup.tp2)})"
            )
        }

        // Check TP1
        if (isTakeProfitHit(setup, price, setup.tp1)) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.TP1_REACHED,
                price = price,
                timestamp = nowMs,
                reason = "Price (${formatCryptoPrice(price)}) reached target TP1 (${formatCryptoPrice(setup.tp1)})"
            )
        }

        return setup.copy(
            lastVerifiedPrice = price,
            lastVerifiedTimestamp = nowMs
        )
    }

    private fun evaluateTp1Tick(
        setup: TrackedPendingSetup,
        price: Double,
        nowMs: Long
    ): TrackedPendingSetup {
        // SL check
        if (isStopLossHit(setup, price)) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.STOP_LOSS_REACHED,
                price = price,
                timestamp = nowMs,
                reason = "Price (${formatCryptoPrice(price)}) pulled back to stop loss level (${formatCryptoPrice(setup.stopLoss)})"
            )
        }

        // TP3 check
        if (setup.tp3 != null && isTakeProfitHit(setup, price, setup.tp3)) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.TP3_REACHED,
                price = price,
                timestamp = nowMs,
                reason = "Price (${formatCryptoPrice(price)}) reached final target TP3 (${formatCryptoPrice(setup.tp3)})"
            )
        }

        // TP2 check
        if (setup.tp2 != null && isTakeProfitHit(setup, price, setup.tp2)) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.TP2_REACHED,
                price = price,
                timestamp = nowMs,
                reason = "Price (${formatCryptoPrice(price)}) reached target TP2 (${formatCryptoPrice(setup.tp2)})"
            )
        }

        return setup.copy(
            lastVerifiedPrice = price,
            lastVerifiedTimestamp = nowMs
        )
    }

    private fun evaluateTp2Tick(
        setup: TrackedPendingSetup,
        price: Double,
        nowMs: Long
    ): TrackedPendingSetup {
        // SL check
        if (isStopLossHit(setup, price)) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.STOP_LOSS_REACHED,
                price = price,
                timestamp = nowMs,
                reason = "Price (${formatCryptoPrice(price)}) pulled back to stop loss level (${formatCryptoPrice(setup.stopLoss)})"
            )
        }

        // TP3 check
        if (setup.tp3 != null && isTakeProfitHit(setup, price, setup.tp3)) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.TP3_REACHED,
                price = price,
                timestamp = nowMs,
                reason = "Price (${formatCryptoPrice(price)}) reached final target TP3 (${formatCryptoPrice(setup.tp3)})"
            )
        }

        return setup.copy(
            lastVerifiedPrice = price,
            lastVerifiedTimestamp = nowMs
        )
    }

    // --- Private Candle Helpers ---

    private fun evaluatePendingCandle(
        setup: TrackedPendingSetup,
        candle: Candle,
        evalTime: Long
    ): TrackedPendingSetup {
        // Pre-entry invalidation check using candle extremes
        val invalidSlHit = when (setup.direction) {
            TradeDirection.BUY -> candle.low <= setup.stopLoss
            TradeDirection.SELL -> candle.high >= setup.stopLoss
            TradeDirection.NEUTRAL -> false
        }

        val entryTouched = isCandleCrossingEntry(setup, candle)

        if (invalidSlHit && !entryTouched) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.INVALIDATED,
                price = candle.close,
                timestamp = evalTime,
                reason = "Candle breached stop loss (${formatCryptoPrice(setup.stopLoss)}) before entering entry zone"
            )
        }

        if (entryTouched) {
            val triggered = transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.PRICE_TRIGGERED,
                price = candle.close,
                timestamp = evalTime,
                reason = "Candle [${formatCryptoPrice(candle.low)} - ${formatCryptoPrice(candle.high)}] touched entry zone [${formatCryptoPrice(setup.entryZoneMin)} - ${formatCryptoPrice(setup.entryZoneMax)}]"
            )
            // Immediately check if the same candle continued into TP1 or SL
            return evaluateTriggeredCandle(triggered, candle, evalTime)
        }

        return setup.copy(
            lastVerifiedPrice = candle.close,
            lastVerifiedTimestamp = evalTime
        )
    }

    private fun evaluateTriggeredCandle(
        setup: TrackedPendingSetup,
        candle: Candle,
        evalTime: Long
    ): TrackedPendingSetup {
        // SL check
        if (isCandleHittingSl(setup, candle)) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.STOP_LOSS_REACHED,
                price = candle.close,
                timestamp = evalTime,
                reason = "Candle touched stop loss (${formatCryptoPrice(setup.stopLoss)})"
            )
        }

        // TP3 check
        if (setup.tp3 != null && isCandleHittingTp(setup, candle, setup.tp3)) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.TP3_REACHED,
                price = candle.close,
                timestamp = evalTime,
                reason = "Candle touched target TP3 (${formatCryptoPrice(setup.tp3)})"
            )
        }

        // TP2 check
        if (setup.tp2 != null && isCandleHittingTp(setup, candle, setup.tp2)) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.TP2_REACHED,
                price = candle.close,
                timestamp = evalTime,
                reason = "Candle touched target TP2 (${formatCryptoPrice(setup.tp2)})"
            )
        }

        // TP1 check
        if (isCandleHittingTp(setup, candle, setup.tp1)) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.TP1_REACHED,
                price = candle.close,
                timestamp = evalTime,
                reason = "Candle touched target TP1 (${formatCryptoPrice(setup.tp1)})"
            )
        }

        return setup.copy(
            lastVerifiedPrice = candle.close,
            lastVerifiedTimestamp = evalTime
        )
    }

    private fun evaluateTp1Candle(
        setup: TrackedPendingSetup,
        candle: Candle,
        evalTime: Long
    ): TrackedPendingSetup {
        if (isCandleHittingSl(setup, candle)) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.STOP_LOSS_REACHED,
                price = candle.close,
                timestamp = evalTime,
                reason = "Candle touched stop loss (${formatCryptoPrice(setup.stopLoss)})"
            )
        }

        if (setup.tp3 != null && isCandleHittingTp(setup, candle, setup.tp3)) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.TP3_REACHED,
                price = candle.close,
                timestamp = evalTime,
                reason = "Candle touched target TP3 (${formatCryptoPrice(setup.tp3)})"
            )
        }

        if (setup.tp2 != null && isCandleHittingTp(setup, candle, setup.tp2)) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.TP2_REACHED,
                price = candle.close,
                timestamp = evalTime,
                reason = "Candle touched target TP2 (${formatCryptoPrice(setup.tp2)})"
            )
        }

        return setup.copy(
            lastVerifiedPrice = candle.close,
            lastVerifiedTimestamp = evalTime
        )
    }

    private fun evaluateTp2Candle(
        setup: TrackedPendingSetup,
        candle: Candle,
        evalTime: Long
    ): TrackedPendingSetup {
        if (isCandleHittingSl(setup, candle)) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.STOP_LOSS_REACHED,
                price = candle.close,
                timestamp = evalTime,
                reason = "Candle touched stop loss (${formatCryptoPrice(setup.stopLoss)})"
            )
        }

        if (setup.tp3 != null && isCandleHittingTp(setup, candle, setup.tp3)) {
            return transition(
                setup = setup,
                targetStatus = TrackedSetupStatus.TP3_REACHED,
                price = candle.close,
                timestamp = evalTime,
                reason = "Candle touched target TP3 (${formatCryptoPrice(setup.tp3)})"
            )
        }

        return setup.copy(
            lastVerifiedPrice = candle.close,
            lastVerifiedTimestamp = evalTime
        )
    }

    // --- Boundary Checks ---

    private fun isPriceInEntryZone(setup: TrackedPendingSetup, price: Double): Boolean {
        return price >= setup.entryZoneMin && price <= setup.entryZoneMax
    }

    private fun isCandleCrossingEntry(setup: TrackedPendingSetup, candle: Candle): Boolean {
        return candle.low <= setup.entryZoneMax && candle.high >= setup.entryZoneMin
    }

    private fun isStopLossHit(setup: TrackedPendingSetup, price: Double): Boolean {
        return when (setup.direction) {
            TradeDirection.BUY -> price <= setup.stopLoss
            TradeDirection.SELL -> price >= setup.stopLoss
            TradeDirection.NEUTRAL -> false
        }
    }

    private fun isTakeProfitHit(setup: TrackedPendingSetup, price: Double, tpLevel: Double): Boolean {
        return when (setup.direction) {
            TradeDirection.BUY -> price >= tpLevel
            TradeDirection.SELL -> price <= tpLevel
            TradeDirection.NEUTRAL -> false
        }
    }

    private fun isCandleHittingSl(setup: TrackedPendingSetup, candle: Candle): Boolean {
        return when (setup.direction) {
            TradeDirection.BUY -> candle.low <= setup.stopLoss
            TradeDirection.SELL -> candle.high >= setup.stopLoss
            TradeDirection.NEUTRAL -> false
        }
    }

    private fun isCandleHittingTp(setup: TrackedPendingSetup, candle: Candle, tpLevel: Double): Boolean {
        return when (setup.direction) {
            TradeDirection.BUY -> candle.high >= tpLevel
            TradeDirection.SELL -> candle.low <= tpLevel
            TradeDirection.NEUTRAL -> false
        }
    }

    /**
     * Creates an immutable transition, avoiding duplicate state records.
     */
    private fun transition(
        setup: TrackedPendingSetup,
        targetStatus: TrackedSetupStatus,
        price: Double,
        timestamp: Long,
        reason: String
    ): TrackedPendingSetup {
        if (setup.currentStatus == targetStatus) {
            return setup.copy(
                lastVerifiedPrice = price,
                lastVerifiedTimestamp = timestamp
            )
        }

        val record = SetupTransitionRecord(
            timestamp = timestamp,
            fromStatus = setup.currentStatus,
            toStatus = targetStatus,
            priceAtTransition = price,
            reason = reason
        )

        return setup.copy(
            currentStatus = targetStatus,
            lastVerifiedPrice = price,
            lastVerifiedTimestamp = timestamp,
            transitionHistory = setup.transitionHistory + record
        )
    }

    private fun formatTimeDiff(ms: Long): String {
        val hours = ms / (60 * 60 * 1000)
        return "${hours}h"
    }
}
