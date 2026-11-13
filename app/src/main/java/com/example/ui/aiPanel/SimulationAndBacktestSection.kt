package com.example.ui.aiPanel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ai.parser.AiAnalysisResult
import com.example.analysis.events.EventImpactLevel
import com.example.analysis.events.EventProtectionMode
import com.example.analysis.events.EventRiskAssessment
import com.example.analysis.events.EventRiskManager
import com.example.analysis.events.MacroCalendarEvent
import com.example.market.candle.Timeframe
import com.example.market.candle.formatCryptoPrice
import com.example.market.candle.formatSymbolPair
import com.example.simulation.paper.BotRunState
import com.example.simulation.paper.PaperPosition
import com.example.simulation.paper.PaperPositionStatus
import com.example.simulation.paper.PaperTradingAccountState
import com.example.simulation.paper.PaperTradingConfig
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BearishRedSoft
import com.example.ui.theme.BullishGreen
import com.example.ui.theme.BullishGreenSoft
import com.example.ui.theme.CyanAccent
import com.example.ui.components.premiumPanel
import com.example.ui.theme.NeutralAmber
import com.example.ui.theme.NeutralAmberSoft
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Requirement #2, #3, #6: Bot Trading (Simulasi) & Economic/Crypto Event Calendar Filter Section.
 * Replaces legacy Paper Trading and removes Backtesting.
 * Virtual trading only — NEVER submits real orders to Binance.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SimulationAndBacktestSection(
    symbol: String,
    timeframe: Timeframe,
    currentPrice: Double,
    aiResult: AiAnalysisResult?,
    paperAccountState: PaperTradingAccountState,
    eventRiskAssessment: EventRiskAssessment,
    upcomingEvents: List<MacroCalendarEvent>,
    eventProtectionMode: EventProtectionMode,
    unavailableEventPolicy: com.example.analysis.events.UnavailableEventPolicy = com.example.analysis.events.UnavailableEventPolicy.ALLOW_WITH_WARNING,
    eventPreBufferMinutes: Int = 30,
    eventPostBufferMinutes: Int = 30,
    manualEventLockActive: Boolean,
    onSetBotRunState: (BotRunState) -> Unit,
    onApplyCustomStartingBalance: (Double) -> Unit,
    onUpdatePaperConfig: (PaperTradingConfig) -> Unit,
    onOpenVirtualTradeFromCurrentSignal: () -> Unit,
    onCloseVirtualTradeManually: (String) -> Unit,
    onResetPaperAccount: () -> Unit,
    onSetEventProtectionMode: (EventProtectionMode) -> Unit,
    onSetUnavailableEventPolicy: (com.example.analysis.events.UnavailableEventPolicy) -> Unit = {},
    onSetEventBufferMinutes: (Int, Int) -> Unit = { _, _ -> },
    onToggleManualEventLock: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val cfg = paperAccountState.config
    var customBalanceInput by remember {
        mutableStateOf(String.format(Locale.US, "%.0f", cfg.startingBalanceUsd))
    }
    LaunchedEffect(cfg.startingBalanceUsd) {
        customBalanceInput = String.format(Locale.US, "%.0f", cfg.startingBalanceUsd)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("bot_trading_simulation_section"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Virtual Trading Only Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(NeutralAmberSoft)
                .border(1.dp, NeutralAmber.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .testTag("simulation_disclaimer_banner")
        ) {
            Text(
                text = "BOT TRADING (SIMULASI) — 100% Virtual Execution Only. Never submits real orders to Binance. Stopping the bot preserves all active positions and history.",
                style = MaterialTheme.typography.labelSmall,
                color = NeutralAmber,
                fontWeight = FontWeight.SemiBold
            )
        }

        // 2. Bot Trading (Simulasi) Main Control Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .premiumPanel()
                .padding(12.dp)
                .testTag("paper_trading_card"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header + Bot Run State Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "BOT TRADING (SIMULASI)",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Auto Virtual Execution · TP1 ${cfg.tp1ClosePercent.toInt()}% / TP2 ${cfg.tp2ClosePercent.toInt()}% / TP3 ${cfg.tp3ClosePercent.toInt()}%",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                }

                val (runBg, runCol) = when (paperAccountState.botRunState) {
                    BotRunState.RUNNING -> BullishGreenSoft to BullishGreen
                    BotRunState.PAUSED -> NeutralAmberSoft to NeutralAmber
                    BotRunState.STOPPED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    color = runBg,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .border(1.dp, runCol.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .testTag("bot_run_state_badge")
                ) {
                    Text(
                        text = "● ${paperAccountState.botRunState.displayLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = runCol,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Live Bot Status Line
            Text(
                text = paperAccountState.lastBotStatusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("bot_status_message")
            )

            // Start / Pause / Stop Controls (Requirement #2)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val isRunning = paperAccountState.botRunState == BotRunState.RUNNING
                val isPaused = paperAccountState.botRunState == BotRunState.PAUSED
                val isStopped = paperAccountState.botRunState == BotRunState.STOPPED

                Button(
                    onClick = { onSetBotRunState(BotRunState.RUNNING) },
                    enabled = !isRunning,
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BullishGreen,
                        contentColor = Color(0xFF001F10)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("bot_start_button")
                ) {
                    Text(
                        text = "▶ Start",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                OutlinedButton(
                    onClick = { onSetBotRunState(BotRunState.PAUSED) },
                    enabled = !isPaused,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("bot_pause_button")
                ) {
                    Text(
                        text = "⏸ Pause",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeutralAmber,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                OutlinedButton(
                    onClick = { onSetBotRunState(BotRunState.STOPPED) },
                    enabled = !isStopped,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("bot_stop_button")
                ) {
                    Text(
                        text = "■ Stop",
                        style = MaterialTheme.typography.labelSmall,
                        color = BearishRed,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

            // Custom Starting Virtual Balance Input (Requirement #2: no preset balance selection required)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = customBalanceInput,
                    onValueChange = { input ->
                        customBalanceInput = input.filter { it.isDigit() || it == '.' }
                    },
                    label = {
                        Text(
                            text = "Custom Starting Virtual Balance (USDT)",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("custom_starting_balance_input")
                )

                Button(
                    onClick = {
                        val parsed = customBalanceInput.toDoubleOrNull()
                        if (parsed != null && parsed >= 10.0) {
                            onApplyCustomStartingBalance(parsed)
                        }
                    },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanAccent,
                        contentColor = Color(0xFF001F26)
                    ),
                    modifier = Modifier.testTag("apply_custom_balance_button")
                ) {
                    Text(
                        text = "Set Balance",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Bot Simulation Performance Dashboard (Separated from Signal Performance per Requirement #3)
            Text(
                text = "BOT SIMULATION PERFORMANCE",
                style = MaterialTheme.typography.labelSmall,
                color = CyanAccent,
                fontWeight = FontWeight.Bold
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = 2,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SimMetricItem(
                    label = "Balance (Realized)",
                    value = "$${String.format(Locale.US, "%,.2f", paperAccountState.balanceUsd)}",
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                SimMetricItem(
                    label = "Live Equity",
                    value = "$${String.format(Locale.US, "%,.2f", paperAccountState.equityUsd)}",
                    valueColor = if (paperAccountState.equityUsd >= cfg.startingBalanceUsd) BullishGreen else BearishRed,
                    modifier = Modifier.weight(1f)
                )
                val uPnl = paperAccountState.unrealizedPnlUsd
                val uSign = if (uPnl >= 0) "+" else ""
                SimMetricItem(
                    label = "Unrealized PnL",
                    value = "$uSign$${String.format(Locale.US, "%.2f", uPnl)}",
                    valueColor = if (uPnl >= 0) BullishGreen else BearishRed,
                    alignEnd = true,
                    modifier = Modifier.weight(1f)
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = 2,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val rPnl = paperAccountState.totalRealizedPnlUsd
                val rSign = if (rPnl >= 0) "+" else ""
                SimMetricItem(
                    label = "Realized PnL",
                    value = "$rSign$${String.format(Locale.US, "%.2f", rPnl)}",
                    valueColor = if (rPnl >= 0) BullishGreen else BearishRed,
                    modifier = Modifier.weight(1f)
                )
                SimMetricItem(
                    label = "Bot Win Rate",
                    value = "${String.format(Locale.US, "%.1f", paperAccountState.winRatePct)}% (${paperAccountState.winCount}W/${paperAccountState.lossCount}L)",
                    valueColor = if (paperAccountState.winRatePct >= 50.0) BullishGreen else NeutralAmber,
                    modifier = Modifier.weight(1f)
                )
                SimMetricItem(
                    label = "Profit Factor",
                    value = String.format(Locale.US, "%.2f", paperAccountState.profitFactor),
                    valueColor = if (paperAccountState.profitFactor >= 1.2) BullishGreen else MaterialTheme.colorScheme.onSurface,
                    alignEnd = true,
                    modifier = Modifier.weight(1f)
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = 2,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val exp = paperAccountState.expectancyUsd
                val expSign = if (exp >= 0) "+" else ""
                SimMetricItem(
                    label = "Expectancy / Trade",
                    value = "$expSign$${String.format(Locale.US, "%.2f", exp)}",
                    valueColor = if (exp >= 0) BullishGreen else BearishRed,
                    modifier = Modifier.weight(1f)
                )
                SimMetricItem(
                    label = "Max Drawdown",
                    value = "${String.format(Locale.US, "%.2f", paperAccountState.maxDrawdownPct)}%",
                    valueColor = if (paperAccountState.maxDrawdownPct > 10.0) BearishRed else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                val dailyLoss = paperAccountState.getDailyLossPct()
                SimMetricItem(
                    label = "Daily Loss / Limit",
                    value = "${String.format(Locale.US, "%.1f", dailyLoss)}% / ${cfg.dailyLossLimitPct.toInt()}%",
                    valueColor = if (dailyLoss >= cfg.dailyLossLimitPct) BearishRed else MaterialTheme.colorScheme.onSurface,
                    alignEnd = true,
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

            // Bot Risk & Execution Settings
            Text(
                text = "BOT RISK & EXECUTION PARAMETERS",
                style = MaterialTheme.typography.labelSmall,
                color = CyanAccent,
                fontWeight = FontWeight.Bold
            )

            // Min Setup Quality Grade for Auto-Entry
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Min Setup Grade:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                listOf("A+", "A", "B").forEach { grade ->
                    SimChip(
                        label = "Grade $grade",
                        selected = cfg.minSetupGrade.equals(grade, ignoreCase = true),
                        onClick = { onUpdatePaperConfig(cfg.copy(minSetupGrade = grade)) }
                    )
                }
            }

            // Risk % per Trade + Simulated Leverage
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Risk/Trade:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                listOf(0.5, 1.0, 2.0, 3.0).forEach { rPct ->
                    SimChip(
                        label = "${rPct}%",
                        selected = cfg.riskPerTradePercent == rPct,
                        onClick = { onUpdatePaperConfig(cfg.copy(riskPerTradePercent = rPct)) }
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Leverage:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                listOf(1.0, 3.0, 5.0, 10.0, 20.0).forEach { lev ->
                    SimChip(
                        label = "${lev.toInt()}x",
                        selected = cfg.leverage == lev,
                        onClick = { onUpdatePaperConfig(cfg.copy(leverage = lev)) }
                    )
                }
            }

            // Max Open Positions + Daily Loss Limit + Cooldown
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Max Open:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                listOf(1, 2, 3, 5).forEach { maxPos ->
                    SimChip(
                        label = "$maxPos",
                        selected = cfg.maxOpenPositions == maxPos,
                        onClick = { onUpdatePaperConfig(cfg.copy(maxOpenPositions = maxPos)) }
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Daily Loss Cap:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                listOf(3.0, 5.0, 10.0).forEach { dCap ->
                    SimChip(
                        label = "${dCap.toInt()}%",
                        selected = cfg.dailyLossLimitPct == dCap,
                        onClick = { onUpdatePaperConfig(cfg.copy(dailyLossLimitPct = dCap)) }
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Cooldown:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                listOf(0, 5, 15, 30).forEach { cd ->
                    SimChip(
                        label = "${cd}m",
                        selected = cfg.cooldownMinutes == cd,
                        onClick = { onUpdatePaperConfig(cfg.copy(cooldownMinutes = cd)) }
                    )
                }
            }

            // TP1/TP2/TP3 Allocation + Break-Even on TP1 + Fee/Slippage
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TP Split:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SimChip(
                    label = "30/30/40%",
                    selected = cfg.tp1ClosePercent == 30.0 && cfg.tp2ClosePercent == 30.0,
                    onClick = {
                        onUpdatePaperConfig(
                            cfg.copy(tp1ClosePercent = 30.0, tp2ClosePercent = 30.0, tp3ClosePercent = 40.0)
                        )
                    }
                )
                SimChip(
                    label = "40/30/30%",
                    selected = cfg.tp1ClosePercent == 40.0 && cfg.tp2ClosePercent == 30.0,
                    onClick = {
                        onUpdatePaperConfig(
                            cfg.copy(tp1ClosePercent = 40.0, tp2ClosePercent = 30.0, tp3ClosePercent = 30.0)
                        )
                    }
                )
                SimChip(
                    label = "50/25/25%",
                    selected = cfg.tp1ClosePercent == 50.0 && cfg.tp2ClosePercent == 25.0,
                    onClick = {
                        onUpdatePaperConfig(
                            cfg.copy(tp1ClosePercent = 50.0, tp2ClosePercent = 25.0, tp3ClosePercent = 25.0)
                        )
                    }
                )
                SimChip(
                    label = if (cfg.moveSlToBreakEvenOnTp1) "SL→BE on TP1: ON" else "SL→BE on TP1: OFF",
                    selected = cfg.moveSlToBreakEvenOnTp1,
                    onClick = {
                        onUpdatePaperConfig(cfg.copy(moveSlToBreakEvenOnTp1 = !cfg.moveSlToBreakEvenOnTp1))
                    }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Fee/Slippage:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SimChip(
                    label = "Std (4 bps / 2 bps)",
                    selected = cfg.feeRateBps == 4.0 && cfg.slippageBps == 2.0,
                    onClick = { onUpdatePaperConfig(cfg.copy(feeRateBps = 4.0, slippageBps = 2.0)) }
                )
                SimChip(
                    label = "Taker (10 bps / 5 bps)",
                    selected = cfg.feeRateBps == 10.0 && cfg.slippageBps == 5.0,
                    onClick = { onUpdatePaperConfig(cfg.copy(feeRateBps = 10.0, slippageBps = 5.0)) }
                )
                SimChip(
                    label = "Zero (0 bps / 0 bps)",
                    selected = cfg.feeRateBps == 0.0 && cfg.slippageBps == 0.0,
                    onClick = { onUpdatePaperConfig(cfg.copy(feeRateBps = 0.0, slippageBps = 0.0)) }
                )
            }

            // Manual Simulate Current Setup & Reset Account Buttons
            val canExecuteAiSignal = aiResult != null && !aiResult.isWait && currentPrice > 0.0
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenVirtualTradeFromCurrentSignal,
                    enabled = canExecuteAiSignal,
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanAccent,
                        contentColor = Color(0xFF001F26)
                    ),
                    modifier = Modifier
                        .weight(1.35f)
                        .testTag("execute_virtual_signal_button")
                ) {
                    Text(
                        text = if (canExecuteAiSignal) {
                            "Simulate ${aiResult?.decision} (${formatSymbolPair(symbol)})"
                        } else {
                            "Waiting for LONG/SHORT Setup"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                OutlinedButton(
                    onClick = onResetPaperAccount,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .weight(0.65f)
                        .testTag("reset_paper_account_button")
                ) {
                    Text(
                        text = "Reset Account",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeutralAmber,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Active & Historical Bot Positions
            val allPositions = buildList {
                addAll(paperAccountState.openPositions)
                addAll(paperAccountState.closedPositions.take(15))
            }
            if (allPositions.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Text(
                    text = "BOT POSITIONS & TRANSACTION LOG (${paperAccountState.openPositions.size} OPEN · ${paperAccountState.closedPositions.size} CLOSED)",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent,
                    fontWeight = FontWeight.Bold
                )
                allPositions.forEach { pos ->
                    VirtualPositionItemCard(
                        position = pos,
                        onCloseManually = { onCloseVirtualTradeManually(pos.id) }
                    )
                }
            }
        }

        // 3. Economic / Crypto Event Calendar Filter Card (Requirement #6)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .premiumPanel()
                .padding(12.dp)
                .testTag("event_risk_calendar_card"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ECONOMIC & CRYPTO EVENT CALENDAR FILTER",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = eventRiskAssessment.summaryMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                val badgeColor = when {
                    eventRiskAssessment.shouldBlockNewBotEntries -> BearishRed
                    eventRiskAssessment.isEventWindowActive || eventRiskAssessment.isApproachingSoon -> NeutralAmber
                    else -> BullishGreen
                }
                Surface(
                    color = badgeColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .border(1.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .testTag("event_risk_status_badge")
                ) {
                    Text(
                        text = eventRiskAssessment.statusBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            // Event Protection Mode Selector + Manual Lock Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EventProtectionMode.entries.forEach { mode ->
                    SimChip(
                        label = mode.label,
                        selected = eventProtectionMode == mode,
                        onClick = { onSetEventProtectionMode(mode) }
                    )
                }
                SimChip(
                    label = if (manualEventLockActive) "Event Lock: ON" else "Simulate Event Lock",
                    selected = manualEventLockActive,
                    onClick = { onToggleManualEventLock(!manualEventLockActive) }
                )
            }

            // Pre/Post Event Window & Unavailable Data Safe Policy (Requirement #5)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Window:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                listOf(15, 30, 60).forEach { mins ->
                    SimChip(
                        label = "±${mins}m",
                        selected = eventPreBufferMinutes == mins && eventPostBufferMinutes == mins,
                        onClick = { onSetEventBufferMinutes(mins, mins) }
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "If Data Unavailable:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                com.example.analysis.events.UnavailableEventPolicy.entries.forEach { pol ->
                    SimChip(
                        label = pol.label,
                        selected = unavailableEventPolicy == pol,
                        onClick = { onSetUnavailableEventPolicy(pol) }
                    )
                }
            }

            // Upcoming Macro & Crypto Events Schedule
            upcomingEvents.take(4).forEach { ev ->
                val impColor = when (ev.impact) {
                    EventImpactLevel.HIGH -> BearishRed
                    EventImpactLevel.MEDIUM -> NeutralAmber
                    EventImpactLevel.LOW -> CyanAccent
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = ev.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${ev.category} · ${EventRiskManager.formatEventTimeUtc(ev.timestampMs)} (±${ev.preWindowMinutes}m window)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = ev.impact.code,
                        style = MaterialTheme.typography.labelSmall,
                        color = impColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VirtualPositionItemCard(
    position: PaperPosition,
    onCloseManually: () -> Unit
) {
    val isOpen = position.status == PaperPositionStatus.OPEN
    val sideColor = if (position.isLong) BullishGreen else BearishRed
    val netPnl = position.totalNetPnlUsd
    val pnlColor = if (netPnl >= 0.0) BullishGreen else BearishRed
    val pnlSign = if (netPnl >= 0.0) "+" else ""
    val timeFormatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .border(
                1.dp,
                if (isOpen) CyanAccent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                RoundedCornerShape(6.dp)
            )
            .padding(8.dp)
            .testTag("virtual_position_item_${position.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "${position.side} ${formatSymbolPair(position.symbol)} (${position.timeframe})",
                    style = MaterialTheme.typography.labelMedium,
                    color = sideColor,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Grade ${position.setupGrade}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "[${position.status.label}]",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOpen) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "$pnlSign$${String.format(Locale.US, "%.2f", netPnl)}",
                style = MaterialTheme.typography.labelMedium,
                color = pnlColor,
                fontWeight = FontWeight.Bold
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "Entry $${formatCryptoPrice(position.entryPrice)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Mark $${formatCryptoPrice(position.currentPrice)}",
                style = MaterialTheme.typography.labelSmall,
                color = CyanAccent
            )
            Text(
                text = "SL $${formatCryptoPrice(position.stopLoss)}${if (position.slMovedToBreakEven) " (BE)" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = if (position.slMovedToBreakEven) NeutralAmber else BearishRed
            )
            Text(
                text = "Size $${String.format(Locale.US, "%.0f", position.remainingNotionalUsd)}/${String.format(Locale.US, "%.0f", position.initialNotionalUsd)} (${position.leverage.toInt()}x)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                TpBadge("TP1", position.tp1Hit)
                TpBadge("TP2", position.tp2Hit)
                TpBadge("TP3", position.tp3Hit)
                Text(
                    text = timeFormatter.format(Date(position.openedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isOpen) {
                Surface(
                    color = BearishRedSoft,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .border(1.dp, BearishRed.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .clickable { onCloseManually() }
                        .testTag("close_virtual_position_button")
                ) {
                    Text(
                        text = "Close Position",
                        style = MaterialTheme.typography.labelSmall,
                        color = BearishRed,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TpBadge(label: String, hit: Boolean) {
    val bg = if (hit) BullishGreenSoft else MaterialTheme.colorScheme.surface
    val col = if (hit) BullishGreen else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = bg,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.border(1.dp, col.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
    ) {
        Text(
            text = if (hit) "$label ✓" else label,
            style = MaterialTheme.typography.labelSmall,
            color = col,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun SimChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) CyanAccent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant
    val borderCol = if (selected) CyanAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val textCol = if (selected) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .border(1.dp, borderCol, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textCol,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun SimMetricItem(
    label: String,
    value: String,
    valueColor: Color,
    alignEnd: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(6.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
