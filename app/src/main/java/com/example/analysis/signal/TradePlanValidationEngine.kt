package com.example.analysis.signal

import com.example.ai.plan.BrokerVerificationStatus
import com.example.ai.plan.StructuredTradePlan
import com.example.ai.plan.TradeDirection
import com.example.ai.plan.TradeOrderType
import com.example.ai.plan.TradeSetupStatus
import com.example.ai.strategy.AiStrategy
import com.example.storage.preferences.TradingStyleProfile
import java.util.Locale
import kotlin.math.round

/**
 * LUMAVUN Chart — AI Insight V3.1A: Deterministic Trade Plan Validation Engine.
 *
 * Implements strict geometry, boundary, order type, and risk guardian constraints
 * across the entire proposed entry zone for MARKET, LIMIT, and STOP orders.
 */
object TradePlanValidationEngine {

    const val MIN_RR_DEFAULT: Double = 1.35
    const val MIN_RR_SNIPER: Double = 2.00
    const val MARKET_PROXIMITY_TOLERANCE_PCT: Double = 0.012 // 1.2% maximum tolerance for MARKET entry relative to live reference
    const val MIN_RISK_DISTANCE_EPSILON: Double = 1e-6

    data class PlanValidationResult(
        val isValid: Boolean,
        val sanitizedPlan: StructuredTradePlan,
        val rejectionReasons: List<String>
    )

    /**
     * Resolves minimum acceptable Risk/Reward based on active strategy and trading profile.
     */
    fun resolveMinimumRiskReward(
        strategy: AiStrategy = AiStrategy.DEFAULT,
        profile: TradingStyleProfile? = null
    ): Double {
        if (strategy == AiStrategy.AI_SNIPER) return MIN_RR_SNIPER
        return when (profile) {
            TradingStyleProfile.SCALPING -> 1.45
            TradingStyleProfile.SWING_TRADING -> 1.60
            else -> MIN_RR_DEFAULT
        }
    }

    /**
     * Validates a proposed StructuredTradePlan deterministically.
     * Checks direction/order combinations, finite positive prices, entry boundaries,
     * order type geometry relative to reference price, SL/TP side across the entire entry zone,
     * worst-case risk/reward ratios, and stamps broker verification as unverified.
     */
    fun validatePlan(
        plan: StructuredTradePlan,
        minRequiredRr: Double = resolveMinimumRiskReward(plan.strategy),
        hasLiveBrokerFeed: Boolean = false
    ): PlanValidationResult {
        val reasons = mutableListOf<String>()

        // 1. Check WAIT or NEUTRAL plans
        if (plan.setupStatus == TradeSetupStatus.WAIT || plan.direction == TradeDirection.NEUTRAL || plan.orderType == TradeOrderType.NONE) {
            val sanitizedWait = plan.copy(
                direction = TradeDirection.NEUTRAL,
                orderType = TradeOrderType.NONE,
                setupStatus = TradeSetupStatus.WAIT,
                entryZoneMin = null,
                entryZoneMax = null,
                stopLoss = null,
                tp1 = null,
                tp2 = null,
                tp3 = null,
                riskRewardTp1 = null,
                riskRewardTp2 = null,
                riskRewardTp3 = null,
                brokerVerificationStatus = BrokerVerificationStatus.UNVERIFIED_BROKER_DATA,
                validationMessages = emptyList()
            )
            return PlanValidationResult(
                isValid = true,
                sanitizedPlan = sanitizedWait,
                rejectionReasons = emptyList()
            )
        }

        // 2. Reference Price Validity
        val refPrice = plan.referencePrice
        if (!refPrice.isFinite() || refPrice <= 0.0) {
            reasons.add("Reference price ($refPrice) must be a finite positive number")
        }

        // 3. Direction and Order Type Combinations
        when (plan.direction) {
            TradeDirection.BUY -> {
                if (plan.orderType !in listOf(TradeOrderType.MARKET, TradeOrderType.LIMIT, TradeOrderType.STOP)) {
                    reasons.add("Invalid order type '${plan.orderType.code}' for BUY direction (must be MARKET, LIMIT, or STOP)")
                }
            }
            TradeDirection.SELL -> {
                if (plan.orderType !in listOf(TradeOrderType.MARKET, TradeOrderType.LIMIT, TradeOrderType.STOP)) {
                    reasons.add("Invalid order type '${plan.orderType.code}' for SELL direction (must be MARKET, LIMIT, or STOP)")
                }
            }
            TradeDirection.NEUTRAL -> {
                if (plan.orderType != TradeOrderType.NONE) {
                    reasons.add("NEUTRAL direction cannot have order type '${plan.orderType.code}'")
                }
            }
        }

        // 4. Finite Positive Price Checks for Levels
        val entryMin = plan.entryZoneMin
        val entryMax = plan.entryZoneMax
        val stopLoss = plan.stopLoss
        val tp1 = plan.tp1

        if (entryMin == null || !entryMin.isFinite() || entryMin <= 0.0) {
            reasons.add("Entry zone minimum ($entryMin) must be a finite positive number")
        }
        if (entryMax == null || !entryMax.isFinite() || entryMax <= 0.0) {
            reasons.add("Entry zone maximum ($entryMax) must be a finite positive number")
        }
        if (stopLoss == null || !stopLoss.isFinite() || stopLoss <= 0.0) {
            reasons.add("Stop loss ($stopLoss) must be a finite positive number")
        }
        if (tp1 == null || !tp1.isFinite() || tp1 <= 0.0) {
            reasons.add("Take profit TP1 ($tp1) must be a finite positive number")
        }
        plan.tp2?.let {
            if (!it.isFinite() || it <= 0.0) reasons.add("TP2 ($it) must be a finite positive number")
        }
        plan.tp3?.let {
            if (!it.isFinite() || it <= 0.0) reasons.add("TP3 ($it) must be a finite positive number")
        }

        if (reasons.isNotEmpty()) {
            return failPlan(plan, reasons)
        }

        // Safe non-null unwrapped values
        val safeMin = minOf(entryMin!!, entryMax!!)
        val safeMax = maxOf(entryMin, entryMax)
        val safeSl = stopLoss!!
        val safeTp1 = tp1!!
        val safeTp2 = plan.tp2?.takeIf { it.isFinite() && it > 0.0 }
        val safeTp3 = plan.tp3?.takeIf { it.isFinite() && it > 0.0 }

        // 5. Entry Zone Ordering Check
        if (entryMin > entryMax) {
            reasons.add("Entry zone inverted: min ($entryMin) is greater than max ($entryMax)")
        }

        // 6. Order Type Geometry Relative to Reference Price (Evaluating the Entire Proposed Entry Zone)
        if (refPrice.isFinite() && refPrice > 0.0) {
            when (plan.orderType) {
                TradeOrderType.MARKET -> {
                    // Current price must be adjacent or within the entry zone
                    val maxAllowedDistance = refPrice * MARKET_PROXIMITY_TOLERANCE_PCT
                    val distanceToZone = when {
                        refPrice < safeMin -> safeMin - refPrice
                        refPrice > safeMax -> refPrice - safeMax
                        else -> 0.0
                    }
                    if (distanceToZone > maxAllowedDistance) {
                        reasons.add(
                            "MARKET order requires price near entry zone. Reference price ($refPrice) is too distant from zone [$safeMin - $safeMax] (dist: $distanceToZone > max: $maxAllowedDistance)"
                        )
                    }
                }
                TradeOrderType.LIMIT -> {
                    // BUY LIMIT: Proposed buy entry must be at or below current reference price
                    if (plan.direction == TradeDirection.BUY) {
                        if (safeMax > refPrice * 1.0005) {
                            reasons.add("BUY LIMIT entry zone [$safeMin - $safeMax] must be strictly below current reference price ($refPrice)")
                        }
                    } else if (plan.direction == TradeDirection.SELL) {
                        // SELL LIMIT: Proposed sell entry must be at or above current reference price
                        if (safeMin < refPrice * 0.9995) {
                            reasons.add("SELL LIMIT entry zone [$safeMin - $safeMax] must be strictly above current reference price ($refPrice)")
                        }
                    }
                }
                TradeOrderType.STOP -> {
                    // BUY STOP: Proposed buy breakout entry must be strictly above current reference price
                    if (plan.direction == TradeDirection.BUY) {
                        if (safeMin <= refPrice) {
                            reasons.add("BUY STOP breakout entry zone [$safeMin - $safeMax] must be strictly above current reference price ($refPrice)")
                        }
                    } else if (plan.direction == TradeDirection.SELL) {
                        // SELL STOP: Proposed sell breakdown entry must be strictly below current reference price
                        if (safeMax >= refPrice) {
                            reasons.add("SELL STOP breakdown entry zone [$safeMin - $safeMax] must be strictly below current reference price ($refPrice)")
                        }
                    }
                }
                TradeOrderType.NONE -> {
                    reasons.add("Order type NONE cannot be actionable")
                }
            }
        }

        // 7. Stop Loss & Take Profit Geometry Across the Entire Zone
        if (plan.direction == TradeDirection.BUY) {
            // BUY: SL must be strictly below lowest entry boundary
            if (safeSl >= safeMin) {
                reasons.add("BUY stop loss ($safeSl) must be strictly below lowest entry boundary ($safeMin)")
            }
            // BUY: TP1 must be strictly above highest entry boundary
            if (safeTp1 <= safeMax) {
                reasons.add("BUY TP1 ($safeTp1) must be strictly above highest entry boundary ($safeMax)")
            }
            // Ascending TP progression
            if (safeTp2 != null && safeTp2 <= safeTp1) {
                reasons.add("BUY TP2 ($safeTp2) must be strictly above TP1 ($safeTp1)")
            }
            if (safeTp3 != null && safeTp2 != null && safeTp3 <= safeTp2) {
                reasons.add("BUY TP3 ($safeTp3) must be strictly above TP2 ($safeTp2)")
            }
        } else if (plan.direction == TradeDirection.SELL) {
            // SELL: SL must be strictly above highest entry boundary
            if (safeSl <= safeMax) {
                reasons.add("SELL stop loss ($safeSl) must be strictly above highest entry boundary ($safeMax)")
            }
            // SELL: TP1 must be strictly below lowest entry boundary
            if (safeTp1 >= safeMin) {
                reasons.add("SELL TP1 ($safeTp1) must be strictly below lowest entry boundary ($safeMin)")
            }
            // Descending TP progression
            if (safeTp2 != null && safeTp2 >= safeTp1) {
                reasons.add("SELL TP2 ($safeTp2) must be strictly below TP1 ($safeTp1)")
            }
            if (safeTp3 != null && safeTp2 != null && safeTp3 >= safeTp2) {
                reasons.add("SELL TP3 ($safeTp3) must be strictly below TP2 ($safeTp2)")
            }
        }

        // 8. Conservative Risk/Reward Calculation Across Worst-Case Entry Boundary
        val worstCaseRisk: Double
        val worstCaseRewardTp1: Double
        val worstCaseRewardTp2: Double?
        val worstCaseRewardTp3: Double?

        if (plan.direction == TradeDirection.BUY) {
            // For BUY, worst entry is highest price in zone (safeMax)
            worstCaseRisk = safeMax - safeSl
            worstCaseRewardTp1 = safeTp1 - safeMax
            worstCaseRewardTp2 = safeTp2?.let { it - safeMax }
            worstCaseRewardTp3 = safeTp3?.let { it - safeMax }
        } else {
            // For SELL, worst entry is lowest price in zone (safeMin)
            worstCaseRisk = safeSl - safeMin
            worstCaseRewardTp1 = safeMin - safeTp1
            worstCaseRewardTp2 = safeTp2?.let { safeMin - it }
            worstCaseRewardTp3 = safeTp3?.let { safeMin - it }
        }

        if (worstCaseRisk < MIN_RISK_DISTANCE_EPSILON) {
            reasons.add("Calculated risk distance ($worstCaseRisk) is negligible or zero")
        }

        val recomputedRrTp1 = if (worstCaseRisk > MIN_RISK_DISTANCE_EPSILON && worstCaseRewardTp1 > 0.0) {
            round((worstCaseRewardTp1 / worstCaseRisk) * 100.0) / 100.0
        } else {
            0.0
        }

        val recomputedRrTp2 = if (worstCaseRisk > MIN_RISK_DISTANCE_EPSILON && (worstCaseRewardTp2 ?: 0.0) > 0.0) {
            round(((worstCaseRewardTp2 ?: 0.0) / worstCaseRisk) * 100.0) / 100.0
        } else null

        val recomputedRrTp3 = if (worstCaseRisk > MIN_RISK_DISTANCE_EPSILON && (worstCaseRewardTp3 ?: 0.0) > 0.0) {
            round(((worstCaseRewardTp3 ?: 0.0) / worstCaseRisk) * 100.0) / 100.0
        } else null

        // V9.1: every possible fill inside the advertised entry zone must meet the
        // minimum R:R. The rounded number is for display; it must not relax the gate.
        val rawWorstCaseRrTp1 = if (worstCaseRisk > MIN_RISK_DISTANCE_EPSILON && worstCaseRewardTp1 > 0.0) {
            worstCaseRewardTp1 / worstCaseRisk
        } else {
            0.0
        }

        if (!minRequiredRr.isFinite() || minRequiredRr <= 0.0) {
            reasons.add("Minimum required R:R must be finite and positive")
        } else if (!rawWorstCaseRrTp1.isFinite() || !recomputedRrTp1.isFinite() || rawWorstCaseRrTp1 < minRequiredRr) {
            reasons.add("Recalculated worst-case R:R (1:$rawWorstCaseRrTp1) is below required minimum of 1:$minRequiredRr or non-finite for ${plan.strategy.displayName}")
        }

        if (reasons.isNotEmpty()) {
            return failPlan(plan, reasons)
        }

        // 9. Valid Plan Resolution
        // Determine final setup status: MARKET -> ACTIONABLE, LIMIT/STOP -> PENDING
        val finalStatus = when (plan.orderType) {
            TradeOrderType.MARKET -> TradeSetupStatus.ACTIONABLE
            TradeOrderType.LIMIT, TradeOrderType.STOP -> TradeSetupStatus.PENDING
            TradeOrderType.NONE -> TradeSetupStatus.WAIT
        }

        val brokerStatus = if (hasLiveBrokerFeed) {
            BrokerVerificationStatus.VERIFIED_SIMULATED
        } else {
            BrokerVerificationStatus.UNVERIFIED_BROKER_DATA
        }

        val validatedPlan = plan.copy(
            entryZoneMin = safeMin,
            entryZoneMax = safeMax,
            stopLoss = safeSl,
            tp1 = safeTp1,
            tp2 = safeTp2,
            tp3 = safeTp3,
            riskRewardTp1 = recomputedRrTp1,
            riskRewardTp2 = recomputedRrTp2,
            riskRewardTp3 = recomputedRrTp3,
            setupStatus = finalStatus,
            brokerVerificationStatus = brokerStatus,
            validationMessages = emptyList()
        )

        return PlanValidationResult(
            isValid = true,
            sanitizedPlan = validatedPlan,
            rejectionReasons = emptyList()
        )
    }

    private fun failPlan(
        original: StructuredTradePlan,
        reasons: List<String>
    ): PlanValidationResult {
        val sanitizedInvalid = original.copy(
            setupStatus = TradeSetupStatus.INVALID,
            direction = TradeDirection.NEUTRAL,
            orderType = TradeOrderType.NONE,
            entryZoneMin = null,
            entryZoneMax = null,
            stopLoss = null,
            tp1 = null,
            tp2 = null,
            tp3 = null,
            riskRewardTp1 = null,
            riskRewardTp2 = null,
            riskRewardTp3 = null,
            brokerVerificationStatus = BrokerVerificationStatus.FAILED_CONSTRAINTS,
            validationMessages = reasons
        )
        return PlanValidationResult(
            isValid = false,
            sanitizedPlan = sanitizedInvalid,
            rejectionReasons = reasons
        )
    }
}
