package com.example.analysis.signal

import com.example.ai.parser.AiAnalysisResult
import com.example.ai.parser.AiResponseParser
import com.example.analysis.marketStructure.StructureState
import com.example.analysis.supportResistance.SupportResistanceEngine.roundPriceCleanly
import com.example.analysis.technical.MarketRegime
import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.market.candle.Timeframe
import com.example.market.status.MarketLedState
import com.example.market.status.MarketStatusEvaluation
import com.example.simulation.paper.PaperPosition
import com.example.simulation.paper.PaperTradingEngine
import com.example.storage.preferences.AiAnalysisSystem
import com.example.storage.preferences.AiAutoMonitorRuntimeState
import com.example.storage.preferences.AiContinuousSubmode
import com.example.storage.preferences.TradingStyleProfile
import com.example.storage.watchlist.SignalJournalEntity
import java.util.Locale
import kotlin.math.abs

/**
 * Explicit Setup Lifecycle State Machine for NexSync AI Lite Auto AI Setup Engine V2.
 *
 * Enforces validated transitions across:
 * - SCANNING
 * - CANDIDATE
 * - ACTIVE
 * - PARTIALLY_TRIGGERED
 * - ENTERED
 * - INVALIDATED
 * - EXPIRED
 * - CANCELLED
 * - COMPLETED
 */
enum class SetupLifecycleState(val code: String, val displayLabel: String) {
    SCANNING("SCANNING", "SCANNING"),
    CANDIDATE("CANDIDATE", "CANDIDATE"),
    ACTIVE("ACTIVE", "ACTIVE"),
    PARTIALLY_TRIGGERED("PARTIALLY_TRIGGERED", "PARTIALLY TRIGGERED"),
    ENTERED("ENTERED", "ENTERED"),
    INVALIDATED("INVALIDATED", "INVALIDATED"),
    EXPIRED("EXPIRED", "EXPIRED"),
    CANCELLED("CANCELLED", "CANCELLED"),
    COMPLETED("COMPLETED", "COMPLETED");

    val isTerminal: Boolean
        get() = this in listOf(INVALIDATED, EXPIRED, CANCELLED, COMPLETED)

    val isLiveTradable: Boolean
        get() = this in listOf(ACTIVE, PARTIALLY_TRIGGERED, ENTERED)

    /**
     * Validates whether a state transition from `this` to [target] is legal.
     * Prevents illegal jumps (such as terminal -> active, or scanning -> completed).
     */
    fun canTransitionTo(target: SetupLifecycleState): Boolean {
        if (this == target) return true
        if (this.isTerminal) return false
        return when (this) {
            SCANNING -> target in setOf(CANDIDATE, ACTIVE, CANCELLED)
            CANDIDATE -> target in setOf(SCANNING, ACTIVE, PARTIALLY_TRIGGERED, INVALIDATED, EXPIRED, CANCELLED)
            ACTIVE -> target in setOf(PARTIALLY_TRIGGERED, ENTERED, INVALIDATED, EXPIRED, CANCELLED)
            PARTIALLY_TRIGGERED -> target in setOf(ACTIVE, ENTERED, INVALIDATED, EXPIRED, CANCELLED)
            ENTERED -> target in setOf(COMPLETED, CANCELLED)
            INVALIDATED, EXPIRED, CANCELLED, COMPLETED -> false
        }
    }

    fun toSignalLifecycleStatus(
        currentForwardStatus: SignalLifecycleStatus = SignalLifecycleStatus.ACTIVE,
        highestTpReached: Int = 0
    ): SignalLifecycleStatus {
        return when (this) {
            SCANNING, CANDIDATE -> SignalLifecycleStatus.WAIT
            ACTIVE -> SignalLifecycleStatus.ACTIVE
            PARTIALLY_TRIGGERED -> SignalLifecycleStatus.ACTIVE
            ENTERED -> when {
                highestTpReached >= 2 -> SignalLifecycleStatus.TP2_HIT
                highestTpReached == 1 -> SignalLifecycleStatus.TP1_HIT
                currentForwardStatus in listOf(
                    SignalLifecycleStatus.TP1_HIT,
                    SignalLifecycleStatus.TP2_HIT,
                    SignalLifecycleStatus.ENTRY_TRIGGERED
                ) -> currentForwardStatus
                else -> SignalLifecycleStatus.ENTRY_TRIGGERED
            }
            INVALIDATED -> SignalLifecycleStatus.INVALIDATED
            EXPIRED -> SignalLifecycleStatus.EXPIRED
            CANCELLED -> SignalLifecycleStatus.CANCELLED
            COMPLETED -> when {
                currentForwardStatus in listOf(
                    SignalLifecycleStatus.TP3_HIT,
                    SignalLifecycleStatus.SL_HIT,
                    SignalLifecycleStatus.CLOSED,
                    SignalLifecycleStatus.TP2_HIT,
                    SignalLifecycleStatus.TP1_HIT
                ) -> currentForwardStatus
                highestTpReached >= 3 -> SignalLifecycleStatus.TP3_HIT
                else -> SignalLifecycleStatus.CLOSED
            }
        }
    }

    companion object {
        fun fromCode(code: String): SetupLifecycleState {
            val clean = code.trim().uppercase(Locale.US).replace(" ", "_")
            return entries.firstOrNull {
                it.code == clean || it.displayLabel.equals(code.trim(), ignoreCase = true)
            } ?: when (clean) {
                "TRIGGERED", "ENTRY_TRIGGERED", "TP1_HIT", "TP2_HIT" -> ENTERED
                "TP3_HIT", "SL_HIT", "CLOSED" -> COMPLETED
                "WAIT" -> SCANNING
                else -> ACTIVE
            }
        }
    }
}

/**
 * Executable Entry Plan for an Active Setup.
 */
data class SetupEntryPlan(
    val direction: String,              // "LONG" or "SHORT"
    val entryPrice: Double,             // Planned midpoint entry
    val entryMin: Double,
    val entryMax: Double,
    val entryTrigger: String,
    val stopLoss: Double,
    val tp1: Double,
    val tp2: Double,
    val tp3: Double,
    val riskReward: Double,
    val invalidationCondition: String
) {
    val isLong: Boolean get() = direction.equals("LONG", ignoreCase = true)
    val isShort: Boolean get() = direction.equals("SHORT", ignoreCase = true)

    fun isPriceInsideExactEntryZone(price: Double): Boolean {
        if (price <= 0.0) return false
        val low = minOf(entryMin, entryMax)
        val high = maxOf(entryMin, entryMax)
        return price in low..high
    }

    fun isPriceInsideTriggerBand(price: Double, toleranceFraction: Double = 0.0015): Boolean {
        if (price <= 0.0) return false
        val low = minOf(entryMin, entryMax) * (1.0 - toleranceFraction)
        val high = maxOf(entryMin, entryMax) * (1.0 + toleranceFraction)
        return price in low..high
    }
}

/**
 * State A: Market Assessment (Dynamic real-time market assessment that updates whenever
 * market structure, regime, price, or data feed changes).
 *
 * A `WAIT` Market Assessment never overwrites a valid [ActiveTradeSetup].
 */
data class MarketAssessmentState(
    val symbol: String,
    val timeframe: String,
    val bias: String,                       // "BULLISH", "BEARISH", "NEUTRAL"
    val higherTimeframeBias: String,
    val primaryTimeframeBias: String,
    val lowerTimeframeBias: String,
    val marketRegime: String,
    val marketStructure: String,
    val setupCondition: String,             // "SCANNING", "CANDIDATE", "ACTIVE_SETUP", "WAIT", "DATA_BLOCKED"
    val scanLifecycleState: SetupLifecycleState = SetupLifecycleState.SCANNING,
    val latestDecision: String = "WAIT",    // "LONG", "SHORT", "WAIT"
    val latestGrade: String = "WAIT",       // "A+", "A", "B", "WAIT"
    val confidence: Int = 50,
    val summary: String = "",
    val reasoning: List<String> = emptyList(),
    val dataStatusLabel: String = "OPEN",
    val dataStatusDetail: String = "Market Open · Feed Healthy",
    val marketLedState: MarketLedState = MarketLedState.OPEN,
    val isDataValidForEntry: Boolean = true,
    val evaluatedAt: Long = System.currentTimeMillis(),
    val isIntrabarEvaluation: Boolean = false,
    val latestAiResult: AiAnalysisResult? = null,
    val aiSystemMode: String = "AI_CONTINUOUS",
    val aiExecutionMode: String = "AUTO",
    val tradingStyleProfile: String = "NONE"
)

/**
 * State B: Active Setup (Published trade setup with full lifecycle tracking, entry plan,
 * conflict awareness, mode/profile attribution, and Simulation Bot linkage).
 */
data class ActiveTradeSetup(
    val setupId: Long,
    val setupSignature: String,
    val symbol: String,
    val timeframe: String,
    val direction: String,                  // "LONG" or "SHORT"
    val setupGrade: String,                 // "A+", "A", "B"
    val confidence: Int,
    val marketRegime: String,
    val higherTimeframeBias: String,
    val primaryTimeframeBias: String,
    val lowerTimeframeBias: String,
    val reasoning: List<String>,
    val summary: String,
    val entryPlan: SetupEntryPlan,
    val lifecycleState: SetupLifecycleState = SetupLifecycleState.ACTIVE,
    val forwardStatus: SignalLifecycleStatus = SignalLifecycleStatus.ACTIVE,
    val highestTpReached: Int = 0,
    val realizedR: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    val expiryAt: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
    val statusReason: String = "Setup published and waiting for price to enter Entry Zone",
    val hasConflict: Boolean = false,
    val conflictNote: String = "",
    val linkedBotPositionId: String? = null,
    val botAction: String = "MONITORING",
    val sourceAiResult: AiAnalysisResult,
    val aiSystemMode: String = sourceAiResult.aiSystemMode,
    val aiExecutionMode: String = sourceAiResult.aiExecutionMode,
    val tradingStyleProfile: String = sourceAiResult.tradingStyleProfile,
    val entryOrderType: String = sourceAiResult.effectiveEntryOrderType,
    val selectedTimeframesSummary: String = sourceAiResult.selectedTimeframesSummary,
    val dataValidationWarning: String = sourceAiResult.dataValidationWarning,
    val positionSizingGuidance: String = sourceAiResult.positionSizingGuidance
) {
    val isLong: Boolean get() = direction.equals("LONG", ignoreCase = true)
    val isShort: Boolean get() = direction.equals("SHORT", ignoreCase = true)
    val modeAttributionBadge: String get() = sourceAiResult.modeAttributionBadge
}

/**
 * Checkpoint for Continuous Market Monitoring to detect material changes intrabar
 * without spamming external LLM calls on every WebSocket tick.
 */
data class MonitorEvaluationCheckpoint(
    val symbol: String = "",
    val timeframe: Timeframe = Timeframe.M15,
    val lastLocalEvalMs: Long = 0L,
    val lastAiCallMs: Long = 0L,
    val lastEvaluatedPrice: Double = 0.0,
    val lastAtr14: Double = 0.0,
    val lastStructureState: StructureState = StructureState.SIDEWAYS,
    val lastStructureCode: String = "",
    val lastRegime: MarketRegime = MarketRegime.RANGING,
    val lastBreakoutPullbackCondition: String = "",
    val lastCandidateDirection: String = "WAIT",
    val lastCandidateSignature: String = "",
    val lastMaterialSignature: String = ""
)

data class ContinuousMonitorDecision(
    val updatedCheckpoint: MonitorEvaluationCheckpoint,
    val updatedAssessment: MarketAssessmentState,
    val shouldTriggerFullAiAnalysis: Boolean,
    val triggerReason: String = ""
)

data class SetupReconciliationOutcome(
    val assessment: MarketAssessmentState,
    val activeSetup: ActiveTradeSetup?,
    val previousSetupCancelledOrReplaced: ActiveTradeSetup? = null,
    val didPublishNewSetup: Boolean = false,
    val transitionReason: String = ""
)

data class LiveTickSetupEvaluation(
    val updatedSetup: ActiveTradeSetup,
    val didLifecycleChange: Boolean,
    val shouldAttemptBotAutoEntry: Boolean,
    val transitionReason: String
)

object AutoSetupEngineV2 {

    /** Minimum interval between automatic full AI / LLM evaluations during intrabar monitoring (15s). */
    const val MIN_INTRABAR_AI_COOLDOWN_MS: Long = 15_000L

    /** Minimum interval between lightweight local market assessment updates (2s). */
    const val MIN_LOCAL_ASSESSMENT_INTERVAL_MS: Long = 2_000L

    /**
     * Checks whether the current market & data feed status permits opening new entries.
     * Rules:
     * - Market CLOSED -> block new entries
     * - Data STALE or ERROR (DISCONNECTED) -> block new entries
     * - Schedule UNKNOWN -> do not assume market is open; block new automated entries
     */
    fun isMarketAndDataValidForNewEntry(marketStatus: MarketStatusEvaluation?): Boolean {
        if (marketStatus == null) return true
        return marketStatus.ledState == MarketLedState.OPEN &&
            marketStatus.isFeedHealthy &&
            marketStatus.isQuoteFresh &&
            marketStatus.isSessionOpen == true
    }

    /**
     * Resolves the accurate runtime state of AI Continuous Auto monitoring.
     * Never claims automatic monitoring is ACTIVE when the mode is Manual, AI Gaya Trading,
     * starting up without candles, stale, or disconnected.
     */
    fun resolveAutoMonitorRuntimeState(
        aiSystem: AiAnalysisSystem,
        continuousSubmode: AiContinuousSubmode,
        isAnalyzing: Boolean = false,
        hasCandles: Boolean,
        wsStatus: com.example.market.websocket.WsConnectionStatus,
        marketStatus: MarketStatusEvaluation?
    ): AiAutoMonitorRuntimeState {
        return resolveAutoMonitorRuntimeState(
            aiSystem = aiSystem,
            continuousSubmode = continuousSubmode,
            isAnalyzing = isAnalyzing,
            hasCandles = hasCandles,
            wsStatus = wsStatus.name,
            marketStatus = marketStatus
        )
    }

    fun resolveAutoMonitorRuntimeState(
        aiSystem: AiAnalysisSystem,
        continuousSubmode: AiContinuousSubmode,
        isAnalyzing: Boolean = false,
        hasCandles: Boolean,
        wsStatus: String,
        marketStatus: MarketStatusEvaluation?
    ): AiAutoMonitorRuntimeState {
        if (aiSystem != AiAnalysisSystem.AI_CONTINUOUS || continuousSubmode != AiContinuousSubmode.AUTO) {
            return AiAutoMonitorRuntimeState.STOPPED
        }
        val cleanWs = wsStatus.trim().lowercase(Locale.US)
        if (marketStatus?.ledState == MarketLedState.ERROR ||
            cleanWs.contains("disconnected") ||
            cleanWs.contains("offline") ||
            cleanWs.contains("error")
        ) {
            return AiAutoMonitorRuntimeState.DISCONNECTED
        }
        if (!hasCandles || cleanWs.contains("connecting") || cleanWs.contains("starting")) {
            return AiAutoMonitorRuntimeState.STARTING
        }
        if (marketStatus?.ledState == MarketLedState.STALE) {
            return AiAutoMonitorRuntimeState.STALE
        }
        if (marketStatus != null && !isMarketAndDataValidForNewEntry(marketStatus)) {
            return if (marketStatus.ledState == MarketLedState.CLOSED) {
                AiAutoMonitorRuntimeState.STOPPED
            } else {
                AiAutoMonitorRuntimeState.STALE
            }
        }
        if (isAnalyzing) {
            return AiAutoMonitorRuntimeState.ANALYZING
        }
        return AiAutoMonitorRuntimeState.RUNNING
    }

    /**
     * Requirement #8: Safe mode switching.
     * Preserves any active setup with clear attribution when switching between AI Continuous (Auto/Manual)
     * and AI Gaya Trading profiles without corrupting open setups or simulated positions.
     */
    fun evaluateSetupCompatibilityOnModeSwitch(
        currentSetup: ActiveTradeSetup?,
        targetSystem: AiAnalysisSystem,
        targetSubmode: AiContinuousSubmode,
        targetProfile: TradingStyleProfile?,
        nowMs: Long = System.currentTimeMillis()
    ): ActiveTradeSetup? {
        if (currentSetup == null) return null
        if (currentSetup.lifecycleState.isTerminal) return currentSetup
        val targetLabel = when (targetSystem) {
            AiAnalysisSystem.AI_CONTINUOUS -> "AI Continuous · ${targetSubmode.label}"
            AiAnalysisSystem.AI_TRADING_STYLE -> "AI Gaya Trading · ${(targetProfile ?: TradingStyleProfile.DAY_TRADING).label}"
        }
        return currentSetup.copy(
            updatedAt = nowMs,
            statusReason = "Preserved (${currentSetup.modeAttributionBadge}) while switched to $targetLabel"
        )
    }

    /**
     * Checks whether [setup] belongs to the currently selected AI analysis system and profile.
     * Prevents AI Gaya Trading from silently inheriting an AI Continuous setup, and prevents
     * one TradingStyleProfile (e.g. Scalping) from silently inheriting another profile's setup (e.g. Swing).
     */
    fun isSetupCompatibleWithMode(
        setup: ActiveTradeSetup?,
        aiSystem: AiAnalysisSystem,
        tradingStyleProfile: TradingStyleProfile
    ): Boolean {
        if (setup == null) return false
        if (!setup.aiSystemMode.equals(aiSystem.code, ignoreCase = true)) {
            return false
        }
        if (aiSystem == AiAnalysisSystem.AI_TRADING_STYLE) {
            return setup.tradingStyleProfile.equals(tradingStyleProfile.code, ignoreCase = true)
        }
        return true
    }

    /**
     * Validates and performs an explicit lifecycle state transition on an [ActiveTradeSetup].
     */
    fun transitionSetupState(
        setup: ActiveTradeSetup,
        targetState: SetupLifecycleState,
        reason: String,
        forwardStatusOverride: SignalLifecycleStatus? = null,
        highestTpReached: Int = setup.highestTpReached,
        realizedR: Double = setup.realizedR,
        nowMs: Long = System.currentTimeMillis()
    ): ActiveTradeSetup {
        if (!setup.lifecycleState.canTransitionTo(targetState)) {
            return setup
        }
        val nextForward = forwardStatusOverride
            ?: targetState.toSignalLifecycleStatus(setup.forwardStatus, highestTpReached)
        return setup.copy(
            lifecycleState = targetState,
            forwardStatus = nextForward,
            highestTpReached = highestTpReached,
            realizedR = realizedR,
            updatedAt = nowMs,
            statusReason = reason.ifBlank { setup.statusReason }
        )
    }

    /**
     * Builds an [ActiveTradeSetup] from a validated directional [AiAnalysisResult].
     */
    fun createActiveSetupFromAiResult(
        symbol: String,
        timeframe: Timeframe,
        aiResult: AiAnalysisResult,
        currentPrice: Double,
        setupId: Long = aiResult.analyzedAt,
        botAction: String = "MONITORING",
        nowMs: Long = System.currentTimeMillis()
    ): ActiveTradeSetup? {
        if (aiResult.isWait) return null
        val entryLow = minOf(aiResult.entryZone.low, aiResult.entryZone.high)
        val entryHigh = maxOf(aiResult.entryZone.low, aiResult.entryZone.high)
        val entryMid = roundPriceCleanly((entryLow + entryHigh) / 2.0)
        val tp1 = aiResult.takeProfit.getOrElse(0) { 0.0 }
        val tp2 = aiResult.takeProfit.getOrElse(1) { 0.0 }
        val tp3 = aiResult.takeProfit.getOrElse(2) { 0.0 }
        if (entryMid <= 0.0 || aiResult.stopLoss <= 0.0 || tp1 <= 0.0) return null

        val plan = SetupEntryPlan(
            direction = aiResult.decision.uppercase(Locale.US),
            entryPrice = entryMid,
            entryMin = entryLow,
            entryMax = entryHigh,
            entryTrigger = aiResult.effectiveEntryTrigger,
            stopLoss = aiResult.stopLoss,
            tp1 = tp1,
            tp2 = tp2,
            tp3 = tp3,
            riskReward = aiResult.riskReward,
            invalidationCondition = aiResult.invalidIf
        )

        val inZoneAtCreation = plan.isPriceInsideTriggerBand(currentPrice)
        val initialLifecycle = if (inZoneAtCreation) {
            SetupLifecycleState.PARTIALLY_TRIGGERED
        } else {
            SetupLifecycleState.ACTIVE
        }
        val initialReason = if (inZoneAtCreation) {
            "Price (${roundPriceCleanly(currentPrice)}) is inside Entry Zone (${roundPriceCleanly(entryLow)} - ${roundPriceCleanly(entryHigh)}) — ready for trigger execution"
        } else {
            "Active ${plan.direction} setup (Grade ${aiResult.setupQualityGrade}) waiting for price to reach ${roundPriceCleanly(entryLow)} - ${roundPriceCleanly(entryHigh)}"
        }

        val sig = aiResult.setupSignature.ifBlank {
            "${symbol.uppercase(Locale.US)}_${timeframe.label}_${AiResponseParserCompat.signature(plan.direction, entryLow, entryHigh, plan.stopLoss)}"
        }

        return ActiveTradeSetup(
            setupId = if (setupId > 0L) setupId else nowMs,
            setupSignature = sig,
            symbol = symbol.uppercase(Locale.US),
            timeframe = timeframe.label,
            direction = plan.direction,
            setupGrade = aiResult.setupQualityGrade,
            confidence = aiResult.confidence,
            marketRegime = aiResult.marketRegime,
            higherTimeframeBias = aiResult.higherTimeframeBias,
            primaryTimeframeBias = aiResult.primaryTimeframeBias,
            lowerTimeframeBias = aiResult.lowerTimeframeBias,
            reasoning = aiResult.reasoning,
            summary = aiResult.summary,
            entryPlan = plan,
            lifecycleState = initialLifecycle,
            forwardStatus = SignalLifecycleStatus.ACTIVE,
            highestTpReached = 0,
            realizedR = 0.0,
            createdAt = aiResult.analyzedAt,
            expiryAt = aiResult.expiryAt,
            updatedAt = nowMs,
            statusReason = initialReason,
            hasConflict = false,
            conflictNote = "",
            linkedBotPositionId = null,
            botAction = botAction,
            sourceAiResult = aiResult
        )
    }

    /**
     * Reconciles a newly completed AI analysis result (`WAIT`, `LONG`, or `SHORT`) against the
     * currently active trade setup.
     *
     * Key Guarantees:
     * 1. `WAIT` from Market Assessment NEVER deletes or overwrites a valid [ActiveTradeSetup].
     * 2. A new setup of the same direction does NOT overwrite an already active setup while it remains valid.
     * 3. An opposing setup is flagged as a conflict; if the existing setup is un-entered (`ACTIVE` or `PARTIALLY_TRIGGERED`)
     *    AND primary + HTF structure has decisively reversed with an equal-or-higher grade setup, the old setup
     *    transitions cleanly to `CANCELLED` (with reason recorded to Journal) before promoting the new setup.
     *    If the existing setup is already `ENTERED` (position open), it is NEVER replaced automatically.
     */
    fun reconcileNewAnalysis(
        currentActive: ActiveTradeSetup?,
        newResult: AiAnalysisResult,
        snapshot: MarketTechnicalSnapshot,
        marketStatus: MarketStatusEvaluation?,
        minAutoGrade: String,
        isAutomaticTrigger: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): SetupReconciliationOutcome {
        val dataValid = isMarketAndDataValidForNewEntry(marketStatus)
        val ledState = marketStatus?.ledState ?: MarketLedState.OPEN
        val statusLabel = marketStatus?.shortBadgeLabel ?: "OPEN"
        val statusDetail = marketStatus?.readableStatusText ?: "Market Open · Feed Healthy"

        val validExisting = currentActive?.takeIf {
            it.symbol.equals(snapshot.symbol, ignoreCase = true) &&
                it.timeframe.equals(snapshot.timeframe.label, ignoreCase = true) &&
                !it.lifecycleState.isTerminal
        }

        // Case 1: New result is WAIT (or blocked by grade filter on automatic trigger)
        val gradeQualified = !newResult.isWait &&
            (!isAutomaticTrigger || PaperTradingEngine.isGradeQualified(newResult.setupQualityGrade, minAutoGrade))

        if (newResult.isWait || !gradeQualified || !dataValid) {
            val conditionCode = when {
                !dataValid -> "DATA_BLOCKED"
                validExisting != null -> "ACTIVE_SETUP_PROTECTED"
                else -> "SCANNING"
            }
            val scanState = validExisting?.lifecycleState ?: SetupLifecycleState.SCANNING
            val assessmentReasons = buildList {
                if (!dataValid) {
                    add("New entries paused: $statusDetail")
                }
                if (!newResult.isWait && !gradeQualified) {
                    add("Candidate ${newResult.decision} (${newResult.setupQualityGrade}) is below minimum Auto AI grade ($minAutoGrade)")
                }
                addAll(newResult.reasoning)
            }.distinct()

            val assessment = MarketAssessmentState(
                symbol = snapshot.symbol,
                timeframe = snapshot.timeframe.label,
                bias = newResult.bias,
                higherTimeframeBias = newResult.higherTimeframeBias,
                primaryTimeframeBias = newResult.primaryTimeframeBias,
                lowerTimeframeBias = newResult.lowerTimeframeBias,
                marketRegime = newResult.marketRegime,
                marketStructure = newResult.marketStructure,
                setupCondition = conditionCode,
                scanLifecycleState = scanState,
                latestDecision = if (dataValid && gradeQualified) newResult.decision else "WAIT",
                latestGrade = if (dataValid && gradeQualified) newResult.setupQualityGrade else "WAIT",
                confidence = newResult.confidence,
                summary = if (!dataValid) {
                    "Data/Market status ($statusLabel): $statusDetail. New entries blocked."
                } else {
                    newResult.summary
                },
                reasoning = assessmentReasons,
                dataStatusLabel = statusLabel,
                dataStatusDetail = statusDetail,
                marketLedState = ledState,
                isDataValidForEntry = dataValid,
                evaluatedAt = nowMs,
                isIntrabarEvaluation = !snapshot.latestCandle.isClosed,
                latestAiResult = newResult
            )

            // CRITICAL: Preserve `validExisting` intact when Market Assessment is WAIT!
            return SetupReconciliationOutcome(
                assessment = assessment,
                activeSetup = validExisting,
                previousSetupCancelledOrReplaced = null,
                didPublishNewSetup = false,
                transitionReason = if (validExisting != null) {
                    "Market assessment returned WAIT; existing ${validExisting.direction} setup (${validExisting.lifecycleState.displayLabel}) preserved"
                } else {
                    "Scanning market — no active setup"
                }
            )
        }

        // Case 2: New result is a valid, grade-qualified LONG or SHORT setup and data is healthy
        val assessment = MarketAssessmentState(
            symbol = snapshot.symbol,
            timeframe = snapshot.timeframe.label,
            bias = newResult.bias,
            higherTimeframeBias = newResult.higherTimeframeBias,
            primaryTimeframeBias = newResult.primaryTimeframeBias,
            lowerTimeframeBias = newResult.lowerTimeframeBias,
            marketRegime = newResult.marketRegime,
            marketStructure = newResult.marketStructure,
            setupCondition = "ACTIVE_SETUP",
            scanLifecycleState = SetupLifecycleState.ACTIVE,
            latestDecision = newResult.decision,
            latestGrade = newResult.setupQualityGrade,
            confidence = newResult.confidence,
            summary = newResult.summary,
            reasoning = newResult.reasoning,
            dataStatusLabel = statusLabel,
            dataStatusDetail = statusDetail,
            marketLedState = ledState,
            isDataValidForEntry = true,
            evaluatedAt = nowMs,
            isIntrabarEvaluation = !snapshot.latestCandle.isClosed,
            latestAiResult = newResult
        )

        // If there is no active setup yet, publish the new setup
        if (validExisting == null) {
            val created = createActiveSetupFromAiResult(
                symbol = snapshot.symbol,
                timeframe = snapshot.timeframe,
                aiResult = newResult,
                currentPrice = snapshot.currentPrice,
                nowMs = nowMs
            )
            return SetupReconciliationOutcome(
                assessment = assessment,
                activeSetup = created,
                previousSetupCancelledOrReplaced = null,
                didPublishNewSetup = created != null,
                transitionReason = created?.statusReason ?: ""
            )
        }

        // If an active setup already exists in the SAME direction, keep the existing active setup!
        if (validExisting.direction.equals(newResult.decision, ignoreCase = true)) {
            val clearedConflict = if (validExisting.hasConflict) {
                validExisting.copy(
                    hasConflict = false,
                    conflictNote = "",
                    updatedAt = nowMs
                )
            } else {
                validExisting
            }
            return SetupReconciliationOutcome(
                assessment = assessment,
                activeSetup = clearedConflict,
                previousSetupCancelledOrReplaced = null,
                didPublishNewSetup = false,
                transitionReason = "Existing ${validExisting.direction} setup remains ${validExisting.lifecycleState.displayLabel}"
            )
        }

        // Case 3: New qualified setup is in the OPPOSITE direction to `validExisting` -> Conflict!
        val isOpposingStructureConfirmed = if (newResult.decision.equals("LONG", ignoreCase = true)) {
            snapshot.marketStructure.state == StructureState.BULLISH &&
                newResult.higherTimeframeBias == "BULLISH" &&
                newResult.primaryTimeframeBias == "BULLISH"
        } else {
            snapshot.marketStructure.state == StructureState.BEARISH &&
                newResult.higherTimeframeBias == "BEARISH" &&
                newResult.primaryTimeframeBias == "BEARISH"
        }

        val canCancelUnenteredOnReversal = validExisting.lifecycleState in listOf(
            SetupLifecycleState.ACTIVE,
            SetupLifecycleState.PARTIALLY_TRIGGERED
        ) && isOpposingStructureConfirmed &&
            PaperTradingEngine.isGradeQualified(newResult.setupQualityGrade, validExisting.setupGrade)

        return if (canCancelUnenteredOnReversal) {
            val cancelReason = "Cancelled: Confirmed opposing ${newResult.decision} (${newResult.setupQualityGrade}) structure reversal on ${snapshot.timeframe.label}"
            val cancelledOld = transitionSetupState(
                setup = validExisting,
                targetState = SetupLifecycleState.CANCELLED,
                reason = cancelReason,
                nowMs = nowMs
            )
            val replacement = createActiveSetupFromAiResult(
                symbol = snapshot.symbol,
                timeframe = snapshot.timeframe,
                aiResult = newResult,
                currentPrice = snapshot.currentPrice,
                nowMs = nowMs
            )?.copy(
                statusReason = "Replaced prior ${validExisting.direction} setup after confirmed ${newResult.decision} structure reversal"
            )
            SetupReconciliationOutcome(
                assessment = assessment,
                activeSetup = replacement,
                previousSetupCancelledOrReplaced = cancelledOld,
                didPublishNewSetup = replacement != null,
                transitionReason = cancelReason
            )
        } else {
            val conflictMsg = "Conflict detected: New ${newResult.decision} (${newResult.setupQualityGrade}) signal appeared while ${validExisting.direction} setup is ${validExisting.lifecycleState.displayLabel}. Keeping ${validExisting.direction} setup active until invalidation (${roundPriceCleanly(validExisting.entryPlan.stopLoss)}) or completion."
            val flaggedExisting = validExisting.copy(
                hasConflict = true,
                conflictNote = conflictMsg,
                updatedAt = nowMs
            )
            SetupReconciliationOutcome(
                assessment = assessment,
                activeSetup = flaggedExisting,
                previousSetupCancelledOrReplaced = null,
                didPublishNewSetup = false,
                transitionReason = conflictMsg
            )
        }
    }

    /**
     * Evaluates an [ActiveTradeSetup] against a real-time price tick (from WebSocket kline or ticker).
     * Drives the setup lifecycle (`ACTIVE` -> `PARTIALLY_TRIGGERED` -> `ENTERED` -> `COMPLETED` / `INVALIDATED` / `EXPIRED`)
     * and determines whether the Simulation Bot should execute its Entry Plan on this tick.
     */
    fun evaluateActiveSetupOnLiveTick(
        setup: ActiveTradeSetup,
        livePrice: Double,
        marketStatus: MarketStatusEvaluation?,
        isBotRunning: Boolean,
        hasOpenBotPositionForSetup: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): LiveTickSetupEvaluation {
        if (livePrice <= 0.0 || setup.lifecycleState.isTerminal) {
            return LiveTickSetupEvaluation(
                updatedSetup = setup,
                didLifecycleChange = false,
                shouldAttemptBotAutoEntry = false,
                transitionReason = setup.statusReason
            )
        }

        val plan = setup.entryPlan
        val isLong = setup.isLong
        val dataValidForEntry = isMarketAndDataValidForNewEntry(marketStatus)

        // 1. If Bot position is already open for this setup, ensure lifecycleState is at least ENTERED
        var workingSetup = if (hasOpenBotPositionForSetup &&
            setup.lifecycleState in listOf(SetupLifecycleState.ACTIVE, SetupLifecycleState.PARTIALLY_TRIGGERED)
        ) {
            transitionSetupState(
                setup = setup,
                targetState = SetupLifecycleState.ENTERED,
                reason = "Simulation Bot position open @ ${roundPriceCleanly(livePrice)}",
                forwardStatusOverride = SignalLifecycleStatus.ENTRY_TRIGGERED,
                nowMs = nowMs
            )
        } else {
            setup
        }

        val wasEnteredBeforeTick = workingSetup.lifecycleState == SetupLifecycleState.ENTERED ||
            workingSetup.forwardStatus in listOf(
                SignalLifecycleStatus.ENTRY_TRIGGERED,
                SignalLifecycleStatus.TP1_HIT,
                SignalLifecycleStatus.TP2_HIT
            )

        // 2. Pre-entry checks (ACTIVE or PARTIALLY_TRIGGERED before actual entry fill)
        if (!wasEnteredBeforeTick) {
            if (workingSetup.expiryAt > 0L && nowMs > workingSetup.expiryAt) {
                val reason = "Setup expired before price triggered Entry Zone (${roundPriceCleanly(plan.entryMin)} - ${roundPriceCleanly(plan.entryMax)})"
                val expired = transitionSetupState(
                    setup = workingSetup,
                    targetState = SetupLifecycleState.EXPIRED,
                    reason = reason,
                    forwardStatusOverride = SignalLifecycleStatus.EXPIRED,
                    nowMs = nowMs
                )
                return LiveTickSetupEvaluation(
                    updatedSetup = expired,
                    didLifecycleChange = true,
                    shouldAttemptBotAutoEntry = false,
                    transitionReason = reason
                )
            }

            val hitSlPreEntry = if (isLong) livePrice <= plan.stopLoss else livePrice >= plan.stopLoss
            if (hitSlPreEntry) {
                val reason = "Invalidated: Price (${roundPriceCleanly(livePrice)}) breached Stop Loss (${roundPriceCleanly(plan.stopLoss)}) before entry"
                val invalidated = transitionSetupState(
                    setup = workingSetup,
                    targetState = SetupLifecycleState.INVALIDATED,
                    reason = reason,
                    forwardStatusOverride = SignalLifecycleStatus.INVALIDATED,
                    nowMs = nowMs
                )
                return LiveTickSetupEvaluation(
                    updatedSetup = invalidated,
                    didLifecycleChange = true,
                    shouldAttemptBotAutoEntry = false,
                    transitionReason = reason
                )
            }

            val hitTp1PreEntry = plan.tp1 > 0.0 && (if (isLong) livePrice >= plan.tp1 else livePrice <= plan.tp1)
            if (hitTp1PreEntry) {
                val reason = "Invalidated: Target TP1 (${roundPriceCleanly(plan.tp1)}) reached before entry was triggered (missed move)"
                val invalidated = transitionSetupState(
                    setup = workingSetup,
                    targetState = SetupLifecycleState.INVALIDATED,
                    reason = reason,
                    forwardStatusOverride = SignalLifecycleStatus.INVALIDATED,
                    nowMs = nowMs
                )
                return LiveTickSetupEvaluation(
                    updatedSetup = invalidated,
                    didLifecycleChange = true,
                    shouldAttemptBotAutoEntry = false,
                    transitionReason = reason
                )
            }

            val inExactZone = plan.isPriceInsideExactEntryZone(livePrice)
            val inTriggerBand = plan.isPriceInsideTriggerBand(livePrice)

            if (inExactZone || inTriggerBand) {
                if (!dataValidForEntry) {
                    val blockMsg = marketStatus?.readableStatusText ?: "Market closed or data feed stale/disconnected"
                    val reason = "Entry Zone touched @ ${roundPriceCleanly(livePrice)}, but new entry blocked ($blockMsg)"
                    val partial = transitionSetupState(
                        setup = workingSetup,
                        targetState = SetupLifecycleState.PARTIALLY_TRIGGERED,
                        reason = reason,
                        forwardStatusOverride = SignalLifecycleStatus.ACTIVE,
                        nowMs = nowMs
                    )
                    return LiveTickSetupEvaluation(
                        updatedSetup = partial,
                        didLifecycleChange = partial.lifecycleState != setup.lifecycleState || partial.statusReason != setup.statusReason,
                        shouldAttemptBotAutoEntry = false,
                        transitionReason = reason
                    )
                }

                if (inExactZone) {
                    val reason = if (isBotRunning) {
                        "Entry Zone triggered @ ${roundPriceCleanly(livePrice)} — executing Simulation Bot entry check"
                    } else {
                        "Entry Zone triggered @ ${roundPriceCleanly(livePrice)} (${roundPriceCleanly(plan.entryMin)} - ${roundPriceCleanly(plan.entryMax)})"
                    }
                    val entered = transitionSetupState(
                        setup = workingSetup,
                        targetState = SetupLifecycleState.ENTERED,
                        reason = reason,
                        forwardStatusOverride = SignalLifecycleStatus.ENTRY_TRIGGERED,
                        nowMs = nowMs
                    )
                    return LiveTickSetupEvaluation(
                        updatedSetup = entered,
                        didLifecycleChange = true,
                        shouldAttemptBotAutoEntry = isBotRunning,
                        transitionReason = reason
                    )
                } else {
                    // Inside outer 0.15% trigger band -> PARTIALLY_TRIGGERED and allow Bot auto-entry if running
                    val reason = "Price (${roundPriceCleanly(livePrice)}) entered trigger band for Entry Zone (${roundPriceCleanly(plan.entryMin)} - ${roundPriceCleanly(plan.entryMax)})"
                    val partial = transitionSetupState(
                        setup = workingSetup,
                        targetState = SetupLifecycleState.PARTIALLY_TRIGGERED,
                        reason = reason,
                        forwardStatusOverride = SignalLifecycleStatus.ACTIVE,
                        nowMs = nowMs
                    )
                    return LiveTickSetupEvaluation(
                        updatedSetup = partial,
                        didLifecycleChange = partial.lifecycleState != setup.lifecycleState,
                        shouldAttemptBotAutoEntry = isBotRunning,
                        transitionReason = reason
                    )
                }
            }

            return LiveTickSetupEvaluation(
                updatedSetup = workingSetup,
                didLifecycleChange = workingSetup.lifecycleState != setup.lifecycleState,
                shouldAttemptBotAutoEntry = false,
                transitionReason = workingSetup.statusReason
            )
        }

        // 3. Post-entry checks (setup is already ENTERED): evaluate TP1, TP2, TP3, SL, or Breakeven stop
        val nextTransition = SignalEngine.transitionSignalStatusOnLivePrice(
            currentStatus = workingSetup.forwardStatus.let {
                if (it == SignalLifecycleStatus.ACTIVE) SignalLifecycleStatus.ENTRY_TRIGGERED else it
            },
            highestTpReached = workingSetup.highestTpReached,
            isLong = isLong,
            entryPrice = plan.entryPrice,
            entryMin = plan.entryMin,
            entryMax = plan.entryMax,
            stopLoss = plan.stopLoss,
            tp1 = plan.tp1,
            tp2 = plan.tp2,
            tp3 = plan.tp3,
            expiryAt = workingSetup.expiryAt,
            livePrice = livePrice,
            nowMs = nowMs
        )

        if (nextTransition.status == workingSetup.forwardStatus &&
            nextTransition.highestTpReached == workingSetup.highestTpReached
        ) {
            return LiveTickSetupEvaluation(
                updatedSetup = workingSetup,
                didLifecycleChange = workingSetup.lifecycleState != setup.lifecycleState,
                shouldAttemptBotAutoEntry = false,
                transitionReason = workingSetup.statusReason
            )
        }

        val (targetLifecycle, reasonText) = when (nextTransition.status) {
            SignalLifecycleStatus.TP1_HIT -> SetupLifecycleState.ENTERED to
                "TP1 (${roundPriceCleanly(plan.tp1)}) reached @ ${roundPriceCleanly(livePrice)} · Stop moved to Break-Even (${roundPriceCleanly(plan.entryPrice)})"
            SignalLifecycleStatus.TP2_HIT -> SetupLifecycleState.ENTERED to
                "TP2 (${roundPriceCleanly(plan.tp2)}) reached @ ${roundPriceCleanly(livePrice)} · Trailing toward TP3 (${roundPriceCleanly(plan.tp3)})"
            SignalLifecycleStatus.TP3_HIT -> SetupLifecycleState.COMPLETED to
                "Completed: Final target TP3 (${roundPriceCleanly(plan.tp3)}) reached @ ${roundPriceCleanly(livePrice)}"
            SignalLifecycleStatus.SL_HIT -> SetupLifecycleState.COMPLETED to
                "Completed: Stop Loss (${roundPriceCleanly(plan.stopLoss)}) hit after entry @ ${roundPriceCleanly(livePrice)}"
            SignalLifecycleStatus.CLOSED -> SetupLifecycleState.COMPLETED to
                "Completed: Remaining position closed at Break-Even (${roundPriceCleanly(plan.entryPrice)}) after TP${nextTransition.highestTpReached}"
            else -> workingSetup.lifecycleState to workingSetup.statusReason
        }

        val updated = transitionSetupState(
            setup = workingSetup,
            targetState = targetLifecycle,
            reason = reasonText,
            forwardStatusOverride = nextTransition.status,
            highestTpReached = nextTransition.highestTpReached,
            nowMs = nowMs
        )

        return LiveTickSetupEvaluation(
            updatedSetup = updated,
            didLifecycleChange = true,
            shouldAttemptBotAutoEntry = false,
            transitionReason = reasonText
        )
    }

    /**
     * Lightweight local intrabar technical evaluator for Continuous Market Monitoring.
     *
     * Evaluates whether market structure, regime, volatility, S/R levels, or candidate setup conditions
     * have changed materially on live ticks/candles, and decides when to trigger full AI analysis
     * using throttling, debounce, and signature deduplication.
     */
    fun evaluateContinuousMonitoring(
        checkpoint: MonitorEvaluationCheckpoint,
        snapshot: MarketTechnicalSnapshot,
        currentAssessment: MarketAssessmentState?,
        activeSetup: ActiveTradeSetup?,
        localCandidate: AiAnalysisResult?,
        marketStatus: MarketStatusEvaluation?,
        confirmedCandleJustClosed: Boolean,
        minAutoGrade: String,
        nowMs: Long = System.currentTimeMillis()
    ): ContinuousMonitorDecision {
        val dataValid = isMarketAndDataValidForNewEntry(marketStatus)
        val ledState = marketStatus?.ledState ?: MarketLedState.OPEN
        val statusLabel = marketStatus?.shortBadgeLabel ?: "OPEN"
        val statusDetail = marketStatus?.readableStatusText ?: "Market Open · Feed Healthy"

        val pairChanged = !checkpoint.symbol.equals(snapshot.symbol, ignoreCase = true) ||
            checkpoint.timeframe != snapshot.timeframe

        val price = snapshot.currentPrice
        val atr = snapshot.atr14 ?: (price * 0.005)
        val bpCondition = snapshot.primaryContext?.breakoutPullbackCondition ?: "NORMAL"
        val structState = snapshot.marketStructure.state
        val structCode = snapshot.marketStructure.schemaCode
        val regime = snapshot.marketRegime

        val candidateDir = localCandidate?.decision ?: "WAIT"
        val candidateGrade = localCandidate?.setupQualityGrade ?: "WAIT"
        val candidateSig = localCandidate?.setupSignature ?: "WAIT"
        val isCandidateQualified = localCandidate != null &&
            !localCandidate.isWait &&
            PaperTradingEngine.isGradeQualified(candidateGrade, minAutoGrade)

        // Detect material changes compared to last checkpoint
        val structureChanged = !pairChanged &&
            (structState != checkpoint.lastStructureState || structCode != checkpoint.lastStructureCode)
        val regimeChanged = !pairChanged && regime != checkpoint.lastRegime
        val breakoutOrRetestChanged = !pairChanged &&
            bpCondition != checkpoint.lastBreakoutPullbackCondition &&
            bpCondition in setOf("BREAKOUT_CONFIRMED", "BULLISH_PULLBACK", "BEARISH_PULLBACK", "NEAR_RESISTANCE", "NEAR_SUPPORT")
        val priceMovedMaterially = !pairChanged &&
            checkpoint.lastEvaluatedPrice > 0.0 &&
            abs(price - checkpoint.lastEvaluatedPrice) >= maxOf(atr * 0.45, checkpoint.lastEvaluatedPrice * 0.0035)
        val newQualifiedCandidateDetected = isCandidateQualified &&
            candidateSig != checkpoint.lastCandidateSignature

        val materialSignature = buildString {
            append(snapshot.symbol).append(':')
            append(snapshot.timeframe.label).append(':')
            append(structCode).append(':')
            append(regime.code).append(':')
            append(bpCondition).append(':')
            append(candidateDir).append(':')
            append(candidateGrade).append(':')
            append(candidateSig)
        }

        val elapsedSinceAi = nowMs - checkpoint.lastAiCallMs
        val isDuplicateMaterialState = materialSignature == checkpoint.lastMaterialSignature && !confirmedCandleJustClosed

        // Determine if full AI analysis should be triggered
        val hasMaterialChange = confirmedCandleJustClosed ||
            newQualifiedCandidateDetected ||
            structureChanged ||
            regimeChanged ||
            breakoutOrRetestChanged ||
            (priceMovedMaterially && isCandidateQualified)

        val shouldCallAi = dataValid &&
            hasMaterialChange &&
            !isDuplicateMaterialState &&
            (confirmedCandleJustClosed || elapsedSinceAi >= MIN_INTRABAR_AI_COOLDOWN_MS || checkpoint.lastAiCallMs == 0L)

        val triggerReason = when {
            !dataValid -> "Market/Data not valid for new entry ($statusLabel)"
            confirmedCandleJustClosed -> "Confirmed ${snapshot.timeframe.label} candle close"
            newQualifiedCandidateDetected -> "Intrabar candidate $candidateDir (Grade $candidateGrade) detected"
            structureChanged -> "Market structure shifted to $structCode"
            regimeChanged -> "Market regime shifted to ${regime.displayLabel}"
            breakoutOrRetestChanged -> "Key level condition: $bpCondition"
            priceMovedMaterially -> "Material intrabar price displacement (${roundPriceCleanly(price)})"
            else -> "Continuous monitoring"
        }

        val scanLifecycle = when {
            activeSetup != null && !activeSetup.lifecycleState.isTerminal -> activeSetup.lifecycleState
            isCandidateQualified -> SetupLifecycleState.CANDIDATE
            else -> SetupLifecycleState.SCANNING
        }

        val setupCondLabel = when {
            !dataValid -> "DATA_BLOCKED"
            activeSetup != null && !activeSetup.lifecycleState.isTerminal -> "ACTIVE_SETUP"
            isCandidateQualified -> "CANDIDATE"
            else -> "SCANNING"
        }

        val baseReasoning = localCandidate?.reasoning ?: currentAssessment?.reasoning.orEmpty()
        val updatedAssessment = MarketAssessmentState(
            symbol = snapshot.symbol,
            timeframe = snapshot.timeframe.label,
            bias = localCandidate?.bias ?: currentAssessment?.bias ?: when (structState) {
                StructureState.BULLISH -> "BULLISH"
                StructureState.BEARISH -> "BEARISH"
                else -> "NEUTRAL"
            },
            higherTimeframeBias = localCandidate?.higherTimeframeBias
                ?: snapshot.higherContext?.marketCondition
                ?: currentAssessment?.higherTimeframeBias
                ?: structState.name,
            primaryTimeframeBias = localCandidate?.primaryTimeframeBias
                ?: snapshot.primaryContext?.marketCondition
                ?: structState.name,
            lowerTimeframeBias = localCandidate?.lowerTimeframeBias
                ?: snapshot.lowerContext?.marketCondition
                ?: currentAssessment?.lowerTimeframeBias
                ?: "NEUTRAL",
            marketRegime = regime.displayLabel,
            marketStructure = structCode,
            setupCondition = setupCondLabel,
            scanLifecycleState = scanLifecycle,
            latestDecision = if (dataValid) candidateDir else "WAIT",
            latestGrade = if (dataValid) candidateGrade else "WAIT",
            confidence = localCandidate?.confidence ?: currentAssessment?.confidence ?: 50,
            summary = when {
                !dataValid -> "Data/Market status ($statusLabel): $statusDetail. New entries paused."
                isCandidateQualified && activeSetup == null -> "Candidate $candidateDir ($candidateGrade) detected intrabar: ${localCandidate?.summary.orEmpty()}"
                else -> localCandidate?.summary ?: currentAssessment?.summary ?: "Monitoring ${snapshot.symbol} ${snapshot.timeframe.label}"
            },
            reasoning = if (!dataValid) {
                (listOf("New entries blocked: $statusDetail") + baseReasoning).distinct()
            } else {
                baseReasoning
            },
            dataStatusLabel = statusLabel,
            dataStatusDetail = statusDetail,
            marketLedState = ledState,
            isDataValidForEntry = dataValid,
            evaluatedAt = nowMs,
            isIntrabarEvaluation = !snapshot.latestCandle.isClosed,
            latestAiResult = localCandidate ?: currentAssessment?.latestAiResult
        )

        val nextCheckpoint = checkpoint.copy(
            symbol = snapshot.symbol,
            timeframe = snapshot.timeframe,
            lastLocalEvalMs = nowMs,
            lastAiCallMs = if (shouldCallAi) nowMs else checkpoint.lastAiCallMs,
            lastEvaluatedPrice = price,
            lastAtr14 = atr,
            lastStructureState = structState,
            lastStructureCode = structCode,
            lastRegime = regime,
            lastBreakoutPullbackCondition = bpCondition,
            lastCandidateDirection = candidateDir,
            lastCandidateSignature = if (isCandidateQualified) candidateSig else checkpoint.lastCandidateSignature,
            lastMaterialSignature = if (shouldCallAi) materialSignature else checkpoint.lastMaterialSignature
        )

        return ContinuousMonitorDecision(
            updatedCheckpoint = nextCheckpoint,
            updatedAssessment = updatedAssessment,
            shouldTriggerFullAiAnalysis = shouldCallAi,
            triggerReason = triggerReason
        )
    }

    /**
     * Builds a [ChartSignalOverlay] directly from an [ActiveTradeSetup] so the chart overlay
     * stays visible and accurate as long as the setup is active, even when the latest
     * Market Assessment returns `WAIT`.
     */
    fun buildOverlayFromActiveSetup(
        setup: ActiveTradeSetup?,
        openBotPosition: PaperPosition? = null
    ): ChartSignalOverlay? {
        if (setup == null || setup.lifecycleState.isTerminal) return null
        val plan = setup.entryPlan
        return ChartSignalOverlay(
            signalId = setup.setupId,
            symbol = setup.symbol,
            timeframe = setup.timeframe,
            candleOpenTime = setup.createdAt,
            candleCloseTime = setup.createdAt,
            decision = setup.direction,
            confidence = setup.confidence,
            setupQuality = setup.setupGrade,
            entry = plan.entryPrice,
            entryMin = plan.entryMin,
            entryMax = plan.entryMax,
            stopLoss = plan.stopLoss,
            tp1 = plan.tp1,
            tp2 = plan.tp2,
            tp3 = plan.tp3,
            riskReward = plan.riskReward,
            expiryAt = setup.expiryAt,
            status = setup.forwardStatus,
            highestTpReached = setup.highestTpReached,
            isHistorical = false,
            entryTriggerText = plan.entryTrigger,
            invalidationText = plan.invalidationCondition,
            marketRegime = setup.marketRegime,
            botActionLabel = openBotPosition?.let { "BOT ${it.side}" } ?: "",
            botUnrealizedPnlUsd = openBotPosition?.unrealizedPnlUsd
        )
    }

    /**
     * Restores an [ActiveTradeSetup] from a persisted [SignalJournalEntity] so active setups
     * survive pair switching, timeframe switching, and app restarts.
     */
    fun restoreActiveSetupFromJournalEntity(
        entity: SignalJournalEntity,
        openBotPosition: PaperPosition? = null
    ): ActiveTradeSetup? {
        if (entity.decision.equals("WAIT", ignoreCase = true)) return null
        val forwardStatus = SignalLifecycleStatus.fromCode(entity.status)
        val lifecycle = if (entity.lifecycleState.isNotBlank()) {
            SetupLifecycleState.fromCode(entity.lifecycleState)
        } else {
            SetupLifecycleState.fromCode(entity.status)
        }
        if (lifecycle.isTerminal || forwardStatus.isTerminal) return null

        val entryMid = if (entity.entryPrice > 0.0) {
            entity.entryPrice
        } else {
            roundPriceCleanly((entity.entryMin + entity.entryMax) / 2.0)
        }
        if (entryMid <= 0.0 || entity.stopLoss <= 0.0 || entity.tp1 <= 0.0) return null

        val plan = SetupEntryPlan(
            direction = entity.decision.uppercase(Locale.US),
            entryPrice = entryMid,
            entryMin = entity.entryMin,
            entryMax = entity.entryMax,
            entryTrigger = entity.entryTrigger,
            stopLoss = entity.stopLoss,
            tp1 = entity.tp1,
            tp2 = entity.tp2,
            tp3 = entity.tp3,
            riskReward = entity.riskReward,
            invalidationCondition = entity.invalidation
        )

        val parsedFromSnapshot = AiResponseParser.parse(
            rawText = entity.originalSnapshotJson,
            providerBadge = entity.aiProviderUsed,
            modelUsed = entity.aiModelUsed
        )

        val reconstructedAi = AiAnalysisResult(
            decision = entity.decision.uppercase(Locale.US),
            bias = if (entity.decision.equals("LONG", ignoreCase = true)) "BULLISH" else "BEARISH",
            confidence = entity.confidence,
            trend = entity.trend,
            momentum = entity.momentum,
            marketStructure = entity.marketStructure,
            setupQuality = entity.setupQuality,
            entryZone = com.example.ai.parser.EntryZone(entity.entryMin, entity.entryMax),
            stopLoss = entity.stopLoss,
            takeProfit = listOfNotNull(
                entity.tp1.takeIf { it > 0.0 },
                entity.tp2.takeIf { it > 0.0 },
                entity.tp3.takeIf { it > 0.0 }
            ),
            riskReward = entity.riskReward,
            support = parsedFromSnapshot?.support ?: emptyList(),
            resistance = parsedFromSnapshot?.resistance ?: emptyList(),
            higherTimeframeBias = parsedFromSnapshot?.higherTimeframeBias ?: entity.trend,
            primaryTimeframeBias = parsedFromSnapshot?.primaryTimeframeBias ?: entity.trend,
            lowerTimeframeBias = parsedFromSnapshot?.lowerTimeframeBias ?: "PULLBACK",
            invalidIf = entity.invalidation,
            reasoning = parsedFromSnapshot?.reasoning?.takeIf { it.isNotEmpty() }
                ?: listOf(entity.marketConditions.ifBlank { "Restored active setup from Signal Journal" }),
            summary = parsedFromSnapshot?.summary?.takeIf { it.isNotBlank() }
                ?: "${entity.decision} setup (Grade ${entity.setupQuality} · ${entity.marketRegime})",
            rawJson = entity.originalSnapshotJson,
            marketRegime = entity.marketRegime,
            mtfConfirmation = entity.mtfConfirmation,
            entryTrigger = entity.entryTrigger,
            expiryAt = entity.expiryAt,
            setupSignature = entity.setupSignature,
            providerBadge = entity.aiProviderUsed,
            modelUsed = entity.aiModelUsed,
            analyzedAt = entity.timestamp,
            aiSystemMode = parsedFromSnapshot?.aiSystemMode ?: "AI_CONTINUOUS",
            aiExecutionMode = parsedFromSnapshot?.aiExecutionMode ?: "AUTO",
            tradingStyleProfile = parsedFromSnapshot?.tradingStyleProfile ?: "NONE",
            entryOrderType = parsedFromSnapshot?.entryOrderType ?: "LIMIT",
            selectedTimeframesSummary = parsedFromSnapshot?.selectedTimeframesSummary
                ?: "Entry: ${entity.primaryTimeframe} · HTF: ${entity.higherTimeframe} · LTF: ${entity.lowerTimeframe}",
            dataValidationWarning = parsedFromSnapshot?.dataValidationWarning ?: "",
            positionSizingGuidance = parsedFromSnapshot?.positionSizingGuidance ?: ""
        )

        return ActiveTradeSetup(
            setupId = entity.id,
            setupSignature = entity.setupSignature,
            symbol = entity.symbol,
            timeframe = entity.primaryTimeframe,
            direction = plan.direction,
            setupGrade = entity.setupQuality,
            confidence = entity.confidence,
            marketRegime = entity.marketRegime,
            higherTimeframeBias = entity.trend,
            primaryTimeframeBias = entity.trend,
            lowerTimeframeBias = "PULLBACK",
            reasoning = reconstructedAi.reasoning,
            summary = reconstructedAi.summary,
            entryPlan = plan,
            lifecycleState = if (openBotPosition != null) SetupLifecycleState.ENTERED else lifecycle,
            forwardStatus = if (openBotPosition != null && forwardStatus == SignalLifecycleStatus.ACTIVE) {
                SignalLifecycleStatus.ENTRY_TRIGGERED
            } else {
                forwardStatus
            },
            highestTpReached = entity.highestTpReached,
            realizedR = entity.realizedR,
            createdAt = entity.timestamp,
            expiryAt = entity.expiryAt,
            updatedAt = System.currentTimeMillis(),
            statusReason = entity.statusReason.ifBlank {
                "Active ${plan.direction} setup restored from Signal Journal"
            },
            hasConflict = false,
            conflictNote = "",
            linkedBotPositionId = openBotPosition?.id,
            botAction = entity.botAction,
            sourceAiResult = reconstructedAi
        )
    }
}

private object AiResponseParserCompat {
    fun signature(decision: String, entryLow: Double, entryHigh: Double, stopLoss: Double): String {
        val mid = (entryLow + entryHigh) / 2.0
        val step = (mid * 0.003).coerceAtLeast(1e-6)
        val entryBucket = kotlin.math.round(mid / step).toLong()
        val slBucket = kotlin.math.round(stopLoss / step).toLong()
        return "${decision.uppercase(Locale.US)}_${entryBucket}_$slBucket"
    }
}
