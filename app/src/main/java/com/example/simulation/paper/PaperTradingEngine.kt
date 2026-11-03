package com.example.simulation.paper

import com.example.ai.parser.AiAnalysisResult
import com.example.analysis.signal.SignalEngine
import com.example.analysis.supportResistance.SupportResistanceEngine.roundPriceCleanly
import com.example.storage.preferences.PersistedBotState
import com.example.storage.watchlist.BotPositionEntity
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

enum class BotRunState(val code: String, val displayLabel: String) {
    RUNNING("RUNNING", "RUNNING"),
    PAUSED("PAUSED", "PAUSED"),
    STOPPED("STOPPED", "STOPPED");

    companion object {
        fun fromCode(code: String): BotRunState {
            return entries.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) } ?: STOPPED
        }
    }
}

/**
 * Requirement #2: Bot Trading (Simulasi) Configuration.
 * Virtual trading only — NEVER submits real orders to Binance.
 * Supports custom starting balance, risk %, position sizing, leverage, fees, slippage,
 * max open positions, daily loss limit, cooldown, minimum setup grade, and TP1/TP2/TP3 allocation.
 */
data class PaperTradingConfig(
    val startingBalanceUsd: Double = 10_000.0,
    val riskPerTradePercent: Double = 1.0,      // e.g. 1% of account balance risked per trade
    val leverage: Double = 5.0,                 // 1x..20x simulated leverage
    val feeRateBps: Double = PaperTradingEngine.DEFAULT_FEE_RATE_BPS,               // 4 bps (0.04%) per fill
    val slippageBps: Double = PaperTradingEngine.DEFAULT_SLIPPAGE_BPS,              // 2 bps (0.02%) simulated slippage
    val maxOpenPositions: Int = 3,              // Maximum concurrent open virtual positions
    val dailyLossLimitPct: Double = 5.0,        // Max daily realized + unrealized loss % before auto-pause
    val cooldownMinutes: Int = 15,              // Minimum cooldown between virtual entries
    val minSetupGrade: String = "A",            // Minimum setup quality grade ("A+", "A", "B")
    val moveSlToBreakEvenOnTp1: Boolean = true,
    // Default TP allocation: TP1 30%, TP2 30%, TP3 40%, configurable
    val tp1ClosePercent: Double = 30.0,
    val tp2ClosePercent: Double = 30.0,
    val tp3ClosePercent: Double = 40.0,
    val autoExecuteConfirmedSignals: Boolean = false
)

enum class PaperPositionStatus(val code: String, val label: String) {
    OPEN("OPEN", "OPEN"),
    CLOSED_TP3("CLOSED_TP3", "CLOSED · TP3"),
    CLOSED_SL("CLOSED_SL", "CLOSED · SL"),
    CLOSED_BE("CLOSED_BE", "CLOSED · BE"),
    CLOSED_MANUAL("CLOSED_MANUAL", "CLOSED · MANUAL");

    companion object {
        fun fromCode(code: String): PaperPositionStatus {
            return entries.firstOrNull {
                it.code.equals(code.trim(), ignoreCase = true) ||
                    it.label.equals(code.trim(), ignoreCase = true)
            } ?: CLOSED_MANUAL
        }
    }
}

data class PaperPosition(
    val id: String,
    val signalId: Long = 0L,
    val setupSignature: String = "",
    val symbol: String,
    val timeframe: String,
    val side: String,                           // "LONG" or "SHORT"
    val setupGrade: String = "A",               // "A+", "A", "B"
    val marketRegime: String = "TRENDING",
    val entryPrice: Double,
    val currentPrice: Double,
    val stopLoss: Double,
    val initialStopLoss: Double,
    val tp1: Double,
    val tp2: Double,
    val tp3: Double,
    val initialNotionalUsd: Double,
    val remainingNotionalUsd: Double,
    val marginUsedUsd: Double,
    val leverage: Double,
    val riskPercent: Double,
    val tp1AllocationPct: Double = 30.0,
    val tp2AllocationPct: Double = 30.0,
    val tp3AllocationPct: Double = 40.0,
    val moveSlToBreakEvenOnTp1: Boolean = true,
    val tp1Hit: Boolean = false,
    val tp2Hit: Boolean = false,
    val tp3Hit: Boolean = false,
    val slMovedToBreakEven: Boolean = false,
    val realizedPnlUsd: Double = 0.0,
    val unrealizedPnlUsd: Double = 0.0,
    val feesPaidUsd: Double = 0.0,
    val openedAt: Long = System.currentTimeMillis(),
    val closedAt: Long? = null,
    val status: PaperPositionStatus = PaperPositionStatus.OPEN,
    val closeReason: String = "",
    val lastProcessedPriceTick: Double = 0.0,
    val lastProcessedTimestamp: Long = 0L
) {
    val isLong: Boolean get() = side.equals("LONG", ignoreCase = true)
    val totalNetPnlUsd: Double get() = realizedPnlUsd + unrealizedPnlUsd

    fun toEntity(): BotPositionEntity = BotPositionEntity(
        id = id,
        signalId = signalId,
        setupSignature = setupSignature,
        symbol = symbol,
        timeframe = timeframe,
        side = side,
        setupGrade = setupGrade,
        marketRegime = marketRegime,
        entryPrice = entryPrice,
        currentPrice = currentPrice,
        stopLoss = stopLoss,
        initialStopLoss = initialStopLoss,
        tp1 = tp1,
        tp2 = tp2,
        tp3 = tp3,
        initialNotionalUsd = initialNotionalUsd,
        remainingNotionalUsd = remainingNotionalUsd,
        marginUsedUsd = marginUsedUsd,
        leverage = leverage,
        riskPercent = riskPercent,
        tp1AllocationPct = tp1AllocationPct,
        tp2AllocationPct = tp2AllocationPct,
        tp3AllocationPct = tp3AllocationPct,
        moveSlToBreakEvenOnTp1 = moveSlToBreakEvenOnTp1,
        tp1Hit = tp1Hit,
        tp2Hit = tp2Hit,
        tp3Hit = tp3Hit,
        slMovedToBreakEven = slMovedToBreakEven,
        realizedPnlUsd = realizedPnlUsd,
        unrealizedPnlUsd = unrealizedPnlUsd,
        feesPaidUsd = feesPaidUsd,
        openedAt = openedAt,
        closedAt = closedAt,
        status = status.code,
        closeReason = closeReason,
        lastProcessedPriceTick = lastProcessedPriceTick,
        lastProcessedTimestamp = lastProcessedTimestamp
    )

    companion object {
        fun fromEntity(e: BotPositionEntity): PaperPosition = PaperPosition(
            id = e.id,
            signalId = e.signalId,
            setupSignature = e.setupSignature,
            symbol = e.symbol,
            timeframe = e.timeframe,
            side = e.side,
            setupGrade = e.setupGrade,
            marketRegime = e.marketRegime,
            entryPrice = e.entryPrice,
            currentPrice = e.currentPrice,
            stopLoss = e.stopLoss,
            initialStopLoss = e.initialStopLoss,
            tp1 = e.tp1,
            tp2 = e.tp2,
            tp3 = e.tp3,
            initialNotionalUsd = e.initialNotionalUsd,
            remainingNotionalUsd = e.remainingNotionalUsd,
            marginUsedUsd = e.marginUsedUsd,
            leverage = e.leverage,
            riskPercent = e.riskPercent,
            tp1AllocationPct = e.tp1AllocationPct,
            tp2AllocationPct = e.tp2AllocationPct,
            tp3AllocationPct = e.tp3AllocationPct,
            moveSlToBreakEvenOnTp1 = e.moveSlToBreakEvenOnTp1,
            tp1Hit = e.tp1Hit,
            tp2Hit = e.tp2Hit,
            tp3Hit = e.tp3Hit,
            slMovedToBreakEven = e.slMovedToBreakEven,
            realizedPnlUsd = e.realizedPnlUsd,
            unrealizedPnlUsd = e.unrealizedPnlUsd,
            feesPaidUsd = e.feesPaidUsd,
            openedAt = e.openedAt,
            closedAt = e.closedAt,
            status = PaperPositionStatus.fromCode(e.status),
            closeReason = e.closeReason,
            lastProcessedPriceTick = e.lastProcessedPriceTick,
            lastProcessedTimestamp = e.lastProcessedTimestamp
        )
    }
}

data class BotPositionTransitionEvent(
    val position: PaperPosition,
    val eventType: String, // "TP1_HIT", "TP2_HIT", "TP3_HIT", "SL_HIT", "BE_HIT"
    val triggerPrice: Double,
    val realizedPnlDeltaUsd: Double
)

data class BotAutoEntryOutcome(
    val updatedState: PaperTradingAccountState,
    val openedPosition: PaperPosition?,
    val botActionCode: String, // "ENTERED", "PENDING_TRIGGER", "SKIPPED_BOT_NOT_RUNNING", "SKIPPED_GRADE", "SKIPPED_RISK_LIMIT", "SKIPPED_COOLDOWN", "SKIPPED_MAX_POS", "SKIPPED_DUPLICATE", "SKIPPED_EVENT", "SKIPPED_REGIME", "SKIPPED_WAIT"
    val statusDetail: String
)

data class PaperTradingAccountState(
    val config: PaperTradingConfig = PaperTradingConfig(),
    val botRunState: BotRunState = BotRunState.STOPPED,
    val balanceUsd: Double = 10_000.0,
    val equityUsd: Double = 10_000.0,
    val peakEquityUsd: Double = 10_000.0,
    val maxDrawdownPct: Double = 0.0,
    val openPositions: List<PaperPosition> = emptyList(),
    val closedPositions: List<PaperPosition> = emptyList(),
    val lastEntryTimestamp: Long = 0L,
    val lastBotStatusMessage: String = "Bot Simulasi Ready (Stopped)"
) {
    val unrealizedPnlUsd: Double
        get() = round2(openPositions.sumOf { it.unrealizedPnlUsd })

    val totalRealizedPnlUsd: Double
        get() = round2(balanceUsd - config.startingBalanceUsd)

    val winCount: Int
        get() = closedPositions.count { it.realizedPnlUsd > 0.0 }

    val lossCount: Int
        get() = closedPositions.count { it.realizedPnlUsd < 0.0 }

    val winRatePct: Double
        get() {
            val total = winCount + lossCount
            return if (total > 0) round2((winCount.toDouble() / total.toDouble()) * 100.0) else 0.0
        }

    val profitFactor: Double
        get() {
            val grossProfit = closedPositions.filter { it.realizedPnlUsd > 0.0 }.sumOf { it.realizedPnlUsd }
            val grossLoss = abs(closedPositions.filter { it.realizedPnlUsd < 0.0 }.sumOf { it.realizedPnlUsd })
            return when {
                grossLoss > 1e-6 -> round2(grossProfit / grossLoss)
                grossProfit > 0.0 -> 99.0
                else -> 0.0
            }
        }

    val expectancyUsd: Double
        get() = if (closedPositions.isNotEmpty()) {
            round2(closedPositions.sumOf { it.realizedPnlUsd } / closedPositions.size.toDouble())
        } else {
            0.0
        }

    fun getDailyLossPct(nowMs: Long = System.currentTimeMillis()): Double {
        val startOfDayUtc = getStartOfUtcDayMs(nowMs)
        val todayRealizedPnl = closedPositions
            .filter { (it.closedAt ?: 0L) >= startOfDayUtc }
            .sumOf { it.realizedPnlUsd }
        val netTodayPnl = todayRealizedPnl + unrealizedPnlUsd
        if (netTodayPnl >= 0.0 || config.startingBalanceUsd <= 0.0) return 0.0
        return round2((abs(netTodayPnl) / config.startingBalanceUsd) * 100.0)
    }

    fun isDailyLossLimitReached(nowMs: Long = System.currentTimeMillis()): Boolean {
        return getDailyLossPct(nowMs) >= config.dailyLossLimitPct
    }

    fun getCooldownRemainingSeconds(nowMs: Long = System.currentTimeMillis()): Long {
        if (lastEntryTimestamp <= 0L || config.cooldownMinutes <= 0) return 0L
        val elapsedMs = nowMs - lastEntryTimestamp
        val requiredMs = config.cooldownMinutes * 60_000L
        return if (elapsedMs >= requiredMs) 0L else ((requiredMs - elapsedMs) / 1000L)
    }

    fun toPersistedBotState(): PersistedBotState = PersistedBotState(
        runState = botRunState.code,
        startingBalanceUsd = config.startingBalanceUsd,
        currentBalanceUsd = balanceUsd,
        peakEquityUsd = peakEquityUsd,
        maxDrawdownPct = maxDrawdownPct,
        riskPerTradePct = config.riskPerTradePercent,
        leverage = config.leverage,
        feeBps = config.feeRateBps,
        slippageBps = config.slippageBps,
        maxOpenPositions = config.maxOpenPositions,
        dailyLossLimitPct = config.dailyLossLimitPct,
        cooldownMinutes = config.cooldownMinutes,
        minSetupGrade = config.minSetupGrade,
        moveSlToBreakEvenOnTp1 = config.moveSlToBreakEvenOnTp1,
        tp1AllocationPct = config.tp1ClosePercent,
        tp2AllocationPct = config.tp2ClosePercent,
        tp3AllocationPct = config.tp3ClosePercent,
        lastEntryTimestamp = lastEntryTimestamp
    )

    companion object {
        private fun round2(v: Double): Double =
            if (v.isNaN() || v.isInfinite()) 0.0 else kotlin.math.round(v * 100.0) / 100.0

        private fun getStartOfUtcDayMs(nowMs: Long): Long {
            return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
    }
}

/**
 * Requirement #2: Bot Trading (Simulasi) Engine.
 * Virtual trading only — NEVER connects to Binance order execution APIs.
 * Supports Start / Pause / Stop lifecycle without erasing positions or history on Stop.
 */
object PaperTradingEngine {

    const val DEFAULT_FEE_RATE_BPS: Double = 4.0
    const val DEFAULT_SLIPPAGE_BPS: Double = 2.0

    fun restoreAccountState(
        persisted: PersistedBotState,
        allPositions: List<BotPositionEntity>
    ): PaperTradingAccountState {
        val parsedPositions = allPositions.map { PaperPosition.fromEntity(it) }
        val openList = parsedPositions.filter { it.status == PaperPositionStatus.OPEN }
            .sortedByDescending { it.openedAt }
        val closedList = parsedPositions.filter { it.status != PaperPositionStatus.OPEN }
            .sortedByDescending { it.closedAt ?: it.openedAt }

        val config = PaperTradingConfig(
            startingBalanceUsd = persisted.startingBalanceUsd.coerceAtLeast(10.0),
            riskPerTradePercent = persisted.riskPerTradePct.coerceIn(0.1, 20.0),
            leverage = persisted.leverage.coerceIn(1.0, 50.0),
            feeRateBps = persisted.feeBps.coerceIn(0.0, 50.0),
            slippageBps = persisted.slippageBps.coerceIn(0.0, 50.0),
            maxOpenPositions = persisted.maxOpenPositions.coerceIn(1, 20),
            dailyLossLimitPct = persisted.dailyLossLimitPct.coerceIn(0.5, 50.0),
            cooldownMinutes = persisted.cooldownMinutes.coerceIn(0, 1440),
            minSetupGrade = persisted.minSetupGrade,
            moveSlToBreakEvenOnTp1 = persisted.moveSlToBreakEvenOnTp1,
            tp1ClosePercent = persisted.tp1AllocationPct,
            tp2ClosePercent = persisted.tp2AllocationPct,
            tp3ClosePercent = persisted.tp3AllocationPct,
            autoExecuteConfirmedSignals = BotRunState.fromCode(persisted.runState) == BotRunState.RUNNING
        )

        val runState = BotRunState.fromCode(persisted.runState)
        val unrealized = openList.sumOf { it.unrealizedPnlUsd }
        val equity = round2(persisted.currentBalanceUsd + unrealized)
        val peak = maxOf(persisted.peakEquityUsd, equity)
        val dd = if (peak > 0.0) round2(((peak - equity) / peak) * 100.0) else 0.0

        return PaperTradingAccountState(
            config = config,
            botRunState = runState,
            balanceUsd = round2(persisted.currentBalanceUsd),
            equityUsd = equity,
            peakEquityUsd = peak,
            maxDrawdownPct = maxOf(persisted.maxDrawdownPct, dd),
            openPositions = openList,
            closedPositions = closedList,
            lastEntryTimestamp = persisted.lastEntryTimestamp,
            lastBotStatusMessage = when (runState) {
                BotRunState.RUNNING -> "Bot Simulasi Running · Monitoring setups"
                BotRunState.PAUSED -> "Bot Simulasi Paused · Managing open positions"
                BotRunState.STOPPED -> "Bot Simulasi Stopped · Positions & history preserved"
            }
        )
    }

    /**
     * Requirement #2: Start, Pause, and Stop controls.
     * Stopping the bot NEVER erases existing open positions or history.
     */
    fun setBotRunState(
        state: PaperTradingAccountState,
        targetRunState: BotRunState
    ): PaperTradingAccountState {
        val updatedConfig = state.config.copy(
            autoExecuteConfirmedSignals = targetRunState == BotRunState.RUNNING
        )
        val msg = when (targetRunState) {
            BotRunState.RUNNING -> "Bot Running · Auto-entry active for Grade ${updatedConfig.minSetupGrade}+ setups"
            BotRunState.PAUSED -> "Bot Paused · New entries paused, active positions still managed"
            BotRunState.STOPPED -> "Bot Stopped · Active positions & history preserved"
        }
        return state.copy(
            config = updatedConfig,
            botRunState = targetRunState,
            lastBotStatusMessage = msg
        )
    }

    /**
     * Requirement #2: Allows user to set a custom starting virtual balance.
     * Adjusts current balance by the delta if positions exist, or resets cleanly if requested.
     */
    fun updateCustomStartingBalance(
        state: PaperTradingAccountState,
        customBalanceUsd: Double
    ): PaperTradingAccountState {
        val cleanStart = customBalanceUsd.coerceIn(10.0, 100_000_000.0)
        val realizedSoFar = state.balanceUsd - state.config.startingBalanceUsd
        val newBalance = (cleanStart + realizedSoFar).coerceAtLeast(0.0)
        val newEquity = round2(newBalance + state.openPositions.sumOf { it.unrealizedPnlUsd })
        val newPeak = maxOf(cleanStart, newEquity)
        val newConfig = state.config.copy(startingBalanceUsd = round2(cleanStart))

        return state.copy(
            config = newConfig,
            balanceUsd = round2(newBalance),
            equityUsd = newEquity,
            peakEquityUsd = round2(newPeak),
            lastBotStatusMessage = "Updated custom starting balance to $${round2(cleanStart)}"
        )
    }

    fun resetAccount(config: PaperTradingConfig = PaperTradingConfig()): PaperTradingAccountState {
        return PaperTradingAccountState(
            config = config,
            botRunState = stateOrDefaultRunState(config),
            balanceUsd = config.startingBalanceUsd,
            equityUsd = config.startingBalanceUsd,
            peakEquityUsd = config.startingBalanceUsd,
            maxDrawdownPct = 0.0,
            openPositions = emptyList(),
            closedPositions = emptyList(),
            lastEntryTimestamp = 0L,
            lastBotStatusMessage = "Virtual account reset to $${round2(config.startingBalanceUsd)}"
        )
    }

    private fun stateOrDefaultRunState(config: PaperTradingConfig): BotRunState {
        return if (config.autoExecuteConfirmedSignals) BotRunState.RUNNING else BotRunState.STOPPED
    }

    fun isGradeQualified(signalGrade: String, minRequiredGrade: String): Boolean {
        val rank = gradeRank(signalGrade)
        val minRank = gradeRank(minRequiredGrade)
        return rank >= minRank && rank > 0
    }

    private fun gradeRank(grade: String): Int = when (grade.trim().uppercase(Locale.US)) {
        "A+" -> 3
        "A", "HIGH" -> 2
        "B", "MEDIUM" -> 1
        else -> 0
    }

    /**
     * Requirement #1 & #2: Evaluates whether the Bot should automatically open a virtual position
     * for a confirmed AI signal when price enters the entry zone and all risk checks pass.
     * Keeps AI Signal strictly separate from Bot Action.
     */
    fun evaluateAutoEntry(
        state: PaperTradingAccountState,
        symbol: String,
        timeframe: String,
        aiResult: AiAnalysisResult,
        liveMarketPrice: Double,
        signalId: Long = 0L,
        eventRiskBlocked: Boolean = false,
        eventRiskReason: String = "",
        marketDataBlocked: Boolean = false,
        marketDataReason: String = "",
        spreadBlocked: Boolean = false,
        spreadBlockReason: String = "",
        bidPrice: Double? = null,
        askPrice: Double? = null,
        hasVerifiedBidAsk: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): BotAutoEntryOutcome {
        if (aiResult.isWait) {
            return BotAutoEntryOutcome(state, null, "SKIPPED_WAIT", "Signal is WAIT — no bot entry.")
        }

        if (state.botRunState != BotRunState.RUNNING && !state.config.autoExecuteConfirmedSignals) {
            return BotAutoEntryOutcome(
                state,
                null,
                "SKIPPED_BOT_NOT_RUNNING",
                "Bot is ${state.botRunState.displayLabel} — signal recorded without bot entry."
            )
        }

        // 0. Market Session & Data Feed Freshness Gate
        if (marketDataBlocked) {
            val msg = "Blocked by Market/Data Status: ${marketDataReason.ifBlank { "Market closed or data feed stale/disconnected" }}"
            return BotAutoEntryOutcome(
                state.copy(lastBotStatusMessage = msg),
                null,
                "SKIPPED_MARKET_STATUS",
                msg
            )
        }

        // 0b. Spread / Volatility Filter Gate
        if (spreadBlocked) {
            val msg = "Blocked by Spread/Volatility Filter: ${spreadBlockReason.ifBlank { "Excessive or unavailable bid/ask spread" }}"
            return BotAutoEntryOutcome(
                state.copy(lastBotStatusMessage = msg),
                null,
                "SKIPPED_SPREAD",
                msg
            )
        }

        // 1. Setup Grade Filter
        val grade = aiResult.setupQualityGrade
        if (!isGradeQualified(grade, state.config.minSetupGrade)) {
            val msg = "Skipped: Setup Grade $grade is below Bot minimum (${state.config.minSetupGrade})"
            return BotAutoEntryOutcome(
                state.copy(lastBotStatusMessage = msg),
                null,
                "SKIPPED_GRADE",
                msg
            )
        }

        // 2. Market Regime Filter
        val regimeUpper = aiResult.marketRegime.uppercase(Locale.US)
        if (regimeUpper.contains("CHOPPY") || regimeUpper.contains("INSUFFICIENT")) {
            val msg = "Skipped: Unfavorable market regime (${aiResult.marketRegime})"
            return BotAutoEntryOutcome(
                state.copy(lastBotStatusMessage = msg),
                null,
                "SKIPPED_REGIME",
                msg
            )
        }

        // 3. Economic / Crypto Event Risk Filter
        if (eventRiskBlocked) {
            val msg = "Blocked by Event Filter: ${eventRiskReason.ifBlank { "High-impact event window active" }}"
            return BotAutoEntryOutcome(
                state.copy(lastBotStatusMessage = msg),
                null,
                "SKIPPED_EVENT",
                msg
            )
        }

        // 4. Signal Expiry check
        if (aiResult.expiryAt > 0L && nowMs > aiResult.expiryAt) {
            val msg = "Skipped: Signal setup expired"
            return BotAutoEntryOutcome(
                state.copy(lastBotStatusMessage = msg),
                null,
                "SKIPPED_EXPIRED",
                msg
            )
        }

        // 5. Daily Loss Limit check
        if (state.isDailyLossLimitReached(nowMs)) {
            val pausedState = state.copy(
                botRunState = BotRunState.PAUSED,
                config = state.config.copy(autoExecuteConfirmedSignals = false),
                lastBotStatusMessage = "Auto-Paused: Daily loss limit (${state.config.dailyLossLimitPct}%) reached"
            )
            return BotAutoEntryOutcome(
                pausedState,
                null,
                "SKIPPED_RISK_LIMIT",
                pausedState.lastBotStatusMessage
            )
        }

        // 6. Duplicate setup / position check
        val cleanSymbol = symbol.uppercase(Locale.US)
        val sigKey = aiResult.setupSignature.ifBlank {
            "${cleanSymbol}_${timeframe}_${aiResult.decision}_${roundPriceCleanly(aiResult.stopLoss)}"
        }
        val alreadyEnteredSetup = (state.openPositions + state.closedPositions).any {
            (it.setupSignature.isNotBlank() && it.setupSignature == sigKey) ||
                (signalId > 0L && it.signalId == signalId)
        }
        val existingOpenForPair = state.openPositions.any {
            it.symbol.equals(cleanSymbol, ignoreCase = true) &&
                it.timeframe.equals(timeframe, ignoreCase = true)
        }
        if (alreadyEnteredSetup || existingOpenForPair) {
            return BotAutoEntryOutcome(
                state,
                null,
                "SKIPPED_DUPLICATE",
                "Position already active or executed for this setup"
            )
        }

        // 7. Max Open Positions check
        if (state.openPositions.size >= state.config.maxOpenPositions) {
            val msg = "Skipped: Max open positions (${state.config.maxOpenPositions}) reached"
            return BotAutoEntryOutcome(
                state.copy(lastBotStatusMessage = msg),
                null,
                "SKIPPED_MAX_POS",
                msg
            )
        }

        // 8. Cooldown check
        val cooldownSec = state.getCooldownRemainingSeconds(nowMs)
        if (cooldownSec > 0L) {
            val msg = "Cooldown active (${cooldownSec}s remaining before next bot entry)"
            return BotAutoEntryOutcome(
                state.copy(lastBotStatusMessage = msg),
                null,
                "SKIPPED_COOLDOWN",
                msg
            )
        }

        // 9. Entry Trigger check: price must be inside or touching entryZone (with 0.15% trigger tolerance)
        val zoneLow = minOf(aiResult.entryZone.low, aiResult.entryZone.high)
        val zoneHigh = maxOf(aiResult.entryZone.low, aiResult.entryZone.high)
        val triggerLow = zoneLow * 0.9985
        val triggerHigh = zoneHigh * 1.0015
        if (liveMarketPrice !in triggerLow..triggerHigh) {
            val msg = "Monitoring ${aiResult.decision} ($cleanSymbol): waiting for price (${roundPriceCleanly(liveMarketPrice)}) to enter ${roundPriceCleanly(zoneLow)} - ${roundPriceCleanly(zoneHigh)}"
            return BotAutoEntryOutcome(
                state.copy(lastBotStatusMessage = msg),
                null,
                "PENDING_TRIGGER",
                msg
            )
        }

        // All checks passed -> Execute virtual entry!
        val prevOpenIds = state.openPositions.map { it.id }.toSet()
        val nextState = openPositionFromSignal(
            state = state,
            symbol = cleanSymbol,
            timeframe = timeframe,
            aiResult = aiResult,
            liveMarketPrice = liveMarketPrice,
            signalId = signalId,
            setupSignature = sigKey,
            bidPrice = bidPrice,
            askPrice = askPrice,
            hasVerifiedBidAsk = hasVerifiedBidAsk,
            nowMs = nowMs
        )
        val newPos = nextState.openPositions.firstOrNull { it.id !in prevOpenIds }
        return if (newPos != null) {
            BotAutoEntryOutcome(
                updatedState = nextState,
                openedPosition = newPos,
                botActionCode = "ENTERED",
                statusDetail = "Opened ${newPos.side} $cleanSymbol @ ${newPos.entryPrice}"
            )
        } else {
            BotAutoEntryOutcome(
                updatedState = nextState,
                openedPosition = null,
                botActionCode = "SKIPPED_INVALID_GEOMETRY",
                statusDetail = "Setup geometry rejected by risk engine"
            )
        }
    }

    /**
     * Opens a simulated paper position from a validated LONG or SHORT `AiAnalysisResult`.
     * Uses Ask price for BUY (LONG) and Bid price for SELL (SHORT) when verified Bid/Ask is available.
     * Sizes the position so that hitting `stopLoss` loses `riskPerTradePercent` of `balanceUsd`,
     * capped by `balanceUsd * leverage`.
     */
    fun openPositionFromSignal(
        state: PaperTradingAccountState,
        symbol: String,
        timeframe: String,
        aiResult: AiAnalysisResult,
        liveMarketPrice: Double,
        signalId: Long = 0L,
        setupSignature: String = aiResult.setupSignature,
        bidPrice: Double? = null,
        askPrice: Double? = null,
        hasVerifiedBidAsk: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): PaperTradingAccountState {
        if (aiResult.isWait || liveMarketPrice <= 0.0 || state.balanceUsd <= 0.0) return state

        val cleanSymbol = symbol.uppercase(Locale.US)
        if (state.openPositions.size >= state.config.maxOpenPositions) return state

        val alreadyOpen = state.openPositions.any {
            it.symbol.equals(cleanSymbol, ignoreCase = true) &&
                it.timeframe.equals(timeframe, ignoreCase = true) &&
                it.side.equals(aiResult.decision, ignoreCase = true)
        }
        if (alreadyOpen) return state

        val isLong = aiResult.decision.equals("LONG", ignoreCase = true)
        val baseQuotePrice = if (hasVerifiedBidAsk) {
            if (isLong) {
                askPrice?.takeIf { it > 0.0 && !it.isNaN() } ?: liveMarketPrice
            } else {
                bidPrice?.takeIf { it > 0.0 && !it.isNaN() } ?: liveMarketPrice
            }
        } else {
            liveMarketPrice
        }
        val slipFactor = (state.config.slippageBps / 10_000.0).coerceIn(0.0, 0.01)
        val executedEntry = roundPriceCleanly(
            if (isLong) baseQuotePrice * (1.0 + slipFactor) else baseQuotePrice * (1.0 - slipFactor)
        )

        val sl = aiResult.stopLoss
        val tp1 = aiResult.takeProfit.getOrElse(0) { 0.0 }
        val tp2 = aiResult.takeProfit.getOrElse(1) { 0.0 }
        val tp3 = aiResult.takeProfit.getOrElse(2) { 0.0 }

        val plannedEntry = ((aiResult.entryZone.low + aiResult.entryZone.high) / 2.0)
            .takeIf { it > 0.0 } ?: executedEntry
        val executionWithinBounds = if (isLong) {
            executedEntry > sl && executedEntry < tp1
        } else {
            executedEntry < sl && executedEntry > tp1
        }

        if (!executionWithinBounds || !SignalEngine.isSetupMathematicallyValid(
                decision = aiResult.decision,
                entry = plannedEntry,
                stopLoss = sl,
                tp1 = tp1,
                tp2 = tp2,
                tp3 = tp3,
                currentPrice = liveMarketPrice
            )
        ) {
            return state
        }

        val slDistanceFraction = (abs(executedEntry - sl) / executedEntry).coerceAtLeast(0.002)
        val riskCapitalUsd = state.balanceUsd * (state.config.riskPerTradePercent / 100.0)
        val desiredNotionalUsd = riskCapitalUsd / slDistanceFraction
        val maxAllowedNotionalUsd = state.balanceUsd * state.config.leverage
        val notionalUsd = round2(desiredNotionalUsd.coerceIn(10.0, maxAllowedNotionalUsd))
        val marginUsd = round2(notionalUsd / state.config.leverage.coerceAtLeast(1.0))

        val entryFeeUsd = round2(notionalUsd * (state.config.feeRateBps / 10_000.0))
        val updatedBalance = round2(state.balanceUsd - entryFeeUsd)

        // Normalize TP allocations so they sum to 100%
        val rawTp1Pct = state.config.tp1ClosePercent.coerceIn(10.0, 80.0)
        val rawTp2Pct = state.config.tp2ClosePercent.coerceIn(10.0, 80.0)
        val rawTp3Pct = (100.0 - rawTp1Pct - rawTp2Pct).coerceIn(10.0, 80.0)

        val newPos = PaperPosition(
            id = "${cleanSymbol}_${timeframe}_${nowMs}",
            signalId = signalId,
            setupSignature = setupSignature.ifBlank { aiResult.setupSignature },
            symbol = cleanSymbol,
            timeframe = timeframe,
            side = aiResult.decision.uppercase(Locale.US),
            setupGrade = aiResult.setupQualityGrade,
            marketRegime = aiResult.marketRegime,
            entryPrice = executedEntry,
            currentPrice = liveMarketPrice,
            stopLoss = sl,
            initialStopLoss = sl,
            tp1 = tp1,
            tp2 = tp2,
            tp3 = tp3,
            initialNotionalUsd = notionalUsd,
            remainingNotionalUsd = notionalUsd,
            marginUsedUsd = marginUsd,
            leverage = state.config.leverage,
            riskPercent = state.config.riskPerTradePercent,
            tp1AllocationPct = rawTp1Pct,
            tp2AllocationPct = rawTp2Pct,
            tp3AllocationPct = rawTp3Pct,
            moveSlToBreakEvenOnTp1 = state.config.moveSlToBreakEvenOnTp1,
            realizedPnlUsd = -entryFeeUsd,
            unrealizedPnlUsd = 0.0,
            feesPaidUsd = entryFeeUsd,
            openedAt = nowMs,
            lastProcessedPriceTick = liveMarketPrice,
            lastProcessedTimestamp = nowMs
        )

        return recomputeEquityAndDrawdown(
            state.copy(
                balanceUsd = updatedBalance,
                openPositions = listOf(newPos) + state.openPositions,
                lastEntryTimestamp = nowMs,
                lastBotStatusMessage = "Executed ${newPos.side} $cleanSymbol (Grade ${newPos.setupGrade}) @ $executedEntry"
            )
        )
    }

    /**
     * Updates open positions for `symbol` with the latest real-time market price.
     * Ensures each position is processed at most once per unique market update.
     */
    fun updateLiveMarketPrice(
        state: PaperTradingAccountState,
        symbol: String,
        livePrice: Double,
        nowMs: Long = System.currentTimeMillis()
    ): PaperTradingAccountState {
        return updateLiveMarketPriceWithEvents(state, symbol, livePrice, nowMs).first
    }

    fun updateLiveMarketPriceWithEvents(
        state: PaperTradingAccountState,
        symbol: String,
        livePrice: Double,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<PaperTradingAccountState, List<BotPositionTransitionEvent>> {
        if (livePrice <= 0.0 || state.openPositions.isEmpty()) return state to emptyList()

        val cleanSymbol = symbol.uppercase(Locale.US)
        val feeFraction = (state.config.feeRateBps / 10_000.0).coerceIn(0.0, 0.01)
        val slipFraction = (state.config.slippageBps / 10_000.0).coerceIn(0.0, 0.01)

        var runningBalance = state.balanceUsd
        val remainingOpen = ArrayList<PaperPosition>()
        val newlyClosed = ArrayList<PaperPosition>()
        val events = ArrayList<BotPositionTransitionEvent>()

        for (pos in state.openPositions) {
            if (!pos.symbol.equals(cleanSymbol, ignoreCase = true)) {
                remainingOpen.add(pos)
                continue
            }

            // Deduplicate identical tick at identical timestamp
            if (abs(pos.lastProcessedPriceTick - livePrice) < 1e-10 && pos.lastProcessedTimestamp == nowMs) {
                remainingOpen.add(pos)
                continue
            }

            val isLong = pos.isLong
            val hitSl = if (isLong) livePrice <= pos.stopLoss else livePrice >= pos.stopLoss

            if (hitSl) {
                val exitPrice = if (isLong) pos.stopLoss * (1.0 - slipFraction) else pos.stopLoss * (1.0 + slipFraction)
                val closePnl = computeSlicePnl(isLong, pos.entryPrice, exitPrice, pos.remainingNotionalUsd)
                val closeFee = pos.remainingNotionalUsd * feeFraction
                val netSlice = round2(closePnl - closeFee)
                runningBalance = round2(runningBalance + netSlice)

                val finalStatus = if (pos.slMovedToBreakEven) {
                    PaperPositionStatus.CLOSED_BE
                } else {
                    PaperPositionStatus.CLOSED_SL
                }

                val closedPos = pos.copy(
                    currentPrice = roundPriceCleanly(livePrice),
                    remainingNotionalUsd = 0.0,
                    realizedPnlUsd = round2(pos.realizedPnlUsd + netSlice),
                    unrealizedPnlUsd = 0.0,
                    feesPaidUsd = round2(pos.feesPaidUsd + closeFee),
                    closedAt = nowMs,
                    status = finalStatus,
                    closeReason = if (pos.slMovedToBreakEven) "Break-Even Stop Hit" else "Stop Loss Hit",
                    lastProcessedPriceTick = livePrice,
                    lastProcessedTimestamp = nowMs
                )
                newlyClosed.add(closedPos)
                events.add(
                    BotPositionTransitionEvent(
                        position = closedPos,
                        eventType = if (pos.slMovedToBreakEven) "BE_HIT" else "SL_HIT",
                        triggerPrice = pos.stopLoss,
                        realizedPnlDeltaUsd = netSlice
                    )
                )
                continue
            }

            var updatedPos = pos.copy(
                currentPrice = roundPriceCleanly(livePrice),
                lastProcessedPriceTick = livePrice,
                lastProcessedTimestamp = nowMs
            )

            // Partial TP1 (default 30% of initial notional)
            val hitTp1 = !updatedPos.tp1Hit && updatedPos.tp1 > 0.0 &&
                (if (isLong) livePrice >= updatedPos.tp1 else livePrice <= updatedPos.tp1)
            if (hitTp1) {
                val tp1Slice = round2(
                    (updatedPos.initialNotionalUsd * (updatedPos.tp1AllocationPct / 100.0))
                        .coerceAtMost(updatedPos.remainingNotionalUsd)
                )
                val exitPrice = if (isLong) updatedPos.tp1 * (1.0 - slipFraction) else updatedPos.tp1 * (1.0 + slipFraction)
                val pnl = computeSlicePnl(isLong, updatedPos.entryPrice, exitPrice, tp1Slice)
                val fee = tp1Slice * feeFraction
                val netSlice = round2(pnl - fee)
                runningBalance = round2(runningBalance + netSlice)

                val newSl = if (updatedPos.moveSlToBreakEvenOnTp1) updatedPos.entryPrice else updatedPos.stopLoss
                updatedPos = updatedPos.copy(
                    tp1Hit = true,
                    stopLoss = newSl,
                    slMovedToBreakEven = updatedPos.moveSlToBreakEvenOnTp1,
                    remainingNotionalUsd = round2((updatedPos.remainingNotionalUsd - tp1Slice).coerceAtLeast(0.0)),
                    realizedPnlUsd = round2(updatedPos.realizedPnlUsd + netSlice),
                    feesPaidUsd = round2(updatedPos.feesPaidUsd + fee)
                )
                events.add(
                    BotPositionTransitionEvent(
                        position = updatedPos,
                        eventType = "TP1_HIT",
                        triggerPrice = updatedPos.tp1,
                        realizedPnlDeltaUsd = netSlice
                    )
                )
            }

            // Partial TP2 (default 30% of initial notional)
            val hitTp2 = !updatedPos.tp2Hit && updatedPos.tp2 > 0.0 &&
                (if (isLong) livePrice >= updatedPos.tp2 else livePrice <= updatedPos.tp2)
            if (hitTp2 && updatedPos.remainingNotionalUsd > 0.0) {
                val tp2Slice = round2(
                    (updatedPos.initialNotionalUsd * (updatedPos.tp2AllocationPct / 100.0))
                        .coerceAtMost(updatedPos.remainingNotionalUsd)
                )
                val exitPrice = if (isLong) updatedPos.tp2 * (1.0 - slipFraction) else updatedPos.tp2 * (1.0 + slipFraction)
                val pnl = computeSlicePnl(isLong, updatedPos.entryPrice, exitPrice, tp2Slice)
                val fee = tp2Slice * feeFraction
                val netSlice = round2(pnl - fee)
                runningBalance = round2(runningBalance + netSlice)

                updatedPos = updatedPos.copy(
                    tp2Hit = true,
                    remainingNotionalUsd = round2((updatedPos.remainingNotionalUsd - tp2Slice).coerceAtLeast(0.0)),
                    realizedPnlUsd = round2(updatedPos.realizedPnlUsd + netSlice),
                    feesPaidUsd = round2(updatedPos.feesPaidUsd + fee)
                )
                events.add(
                    BotPositionTransitionEvent(
                        position = updatedPos,
                        eventType = "TP2_HIT",
                        triggerPrice = updatedPos.tp2,
                        realizedPnlDeltaUsd = netSlice
                    )
                )
            }

            // Final TP3 (remaining 40% of initial notional)
            val hitTp3 = !updatedPos.tp3Hit && updatedPos.tp3 > 0.0 &&
                (if (isLong) livePrice >= updatedPos.tp3 else livePrice <= updatedPos.tp3)
            if (hitTp3 && updatedPos.remainingNotionalUsd > 0.0) {
                val tp3Slice = updatedPos.remainingNotionalUsd
                val exitPrice = if (isLong) updatedPos.tp3 * (1.0 - slipFraction) else updatedPos.tp3 * (1.0 + slipFraction)
                val pnl = computeSlicePnl(isLong, updatedPos.entryPrice, exitPrice, tp3Slice)
                val fee = tp3Slice * feeFraction
                val netSlice = round2(pnl - fee)
                runningBalance = round2(runningBalance + netSlice)

                val closedPos = updatedPos.copy(
                    tp3Hit = true,
                    remainingNotionalUsd = 0.0,
                    realizedPnlUsd = round2(updatedPos.realizedPnlUsd + netSlice),
                    unrealizedPnlUsd = 0.0,
                    feesPaidUsd = round2(updatedPos.feesPaidUsd + fee),
                    closedAt = nowMs,
                    status = PaperPositionStatus.CLOSED_TP3,
                    closeReason = "TP3 Target Reached"
                )
                newlyClosed.add(closedPos)
                events.add(
                    BotPositionTransitionEvent(
                        position = closedPos,
                        eventType = "TP3_HIT",
                        triggerPrice = updatedPos.tp3,
                        realizedPnlDeltaUsd = netSlice
                    )
                )
                continue
            }

            val unrealized = round2(
                computeSlicePnl(isLong, updatedPos.entryPrice, livePrice, updatedPos.remainingNotionalUsd)
            )
            remainingOpen.add(updatedPos.copy(unrealizedPnlUsd = unrealized))
        }

        val nextState = recomputeEquityAndDrawdown(
            state.copy(
                balanceUsd = runningBalance,
                openPositions = remainingOpen,
                closedPositions = newlyClosed + state.closedPositions
            )
        )
        return nextState to events
    }

    fun closePositionManually(
        state: PaperTradingAccountState,
        positionId: String,
        exitMarketPrice: Double,
        nowMs: Long = System.currentTimeMillis()
    ): PaperTradingAccountState {
        val target = state.openPositions.firstOrNull { it.id == positionId } ?: return state
        val effPrice = if (exitMarketPrice > 0.0) exitMarketPrice else target.currentPrice
        val feeFraction = (state.config.feeRateBps / 10_000.0).coerceIn(0.0, 0.01)
        val slipFraction = (state.config.slippageBps / 10_000.0).coerceIn(0.0, 0.01)

        val execExit = if (target.isLong) effPrice * (1.0 - slipFraction) else effPrice * (1.0 + slipFraction)
        val closePnl = computeSlicePnl(target.isLong, target.entryPrice, execExit, target.remainingNotionalUsd)
        val closeFee = target.remainingNotionalUsd * feeFraction
        val netSlice = round2(closePnl - closeFee)

        val closedPos = target.copy(
            currentPrice = roundPriceCleanly(effPrice),
            remainingNotionalUsd = 0.0,
            realizedPnlUsd = round2(target.realizedPnlUsd + netSlice),
            unrealizedPnlUsd = 0.0,
            feesPaidUsd = round2(target.feesPaidUsd + closeFee),
            closedAt = nowMs,
            status = PaperPositionStatus.CLOSED_MANUAL,
            closeReason = "Closed Manually"
        )

        return recomputeEquityAndDrawdown(
            state.copy(
                balanceUsd = round2(state.balanceUsd + netSlice),
                openPositions = state.openPositions.filterNot { it.id == positionId },
                closedPositions = listOf(closedPos) + state.closedPositions,
                lastBotStatusMessage = "Closed ${target.side} ${target.symbol} manually"
            )
        )
    }

    private fun computeSlicePnl(
        isLong: Boolean,
        entryPrice: Double,
        exitPrice: Double,
        notionalUsd: Double
    ): Double {
        if (entryPrice <= 0.0 || notionalUsd <= 0.0) return 0.0
        val pctMove = if (isLong) {
            (exitPrice - entryPrice) / entryPrice
        } else {
            (entryPrice - exitPrice) / entryPrice
        }
        return notionalUsd * pctMove
    }

    private fun recomputeEquityAndDrawdown(state: PaperTradingAccountState): PaperTradingAccountState {
        val unrealized = state.openPositions.sumOf { it.unrealizedPnlUsd }
        val equity = round2(state.balanceUsd + unrealized)
        val peak = maxOf(state.peakEquityUsd, equity)
        val ddPct = if (peak > 0.0) {
            round2(((peak - equity) / peak) * 100.0)
        } else {
            0.0
        }
        val maxDd = maxOf(state.maxDrawdownPct, ddPct)
        return state.copy(
            equityUsd = equity,
            peakEquityUsd = peak,
            maxDrawdownPct = maxDd
        )
    }

    private fun round2(v: Double): Double =
        if (v.isNaN() || v.isInfinite()) 0.0 else kotlin.math.round(v * 100.0) / 100.0
}
