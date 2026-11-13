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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.analysis.journal.DateRangeFilter
import com.example.analysis.journal.DecisionSideFilter
import com.example.analysis.journal.JournalFilterState
import com.example.analysis.journal.PerformanceScenario
import com.example.analysis.journal.SetupGradeFilter
import com.example.analysis.journal.SignalPerformanceMetrics
import com.example.analysis.journal.SignalSourceFilter
import com.example.analysis.journal.SignalStatusFilter
import com.example.market.candle.Timeframe
import com.example.market.candle.formatCryptoPrice
import com.example.market.candle.formatSymbolPair
import com.example.storage.watchlist.SignalJournalEntity
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
 * Requirement #6, #7, #8: Lightweight AI Signal Journal List with Immutable Candle-Close Metadata & Forward-Test Outcome Status.
 */
@Composable
fun SignalJournalListSection(
    signals: List<SignalJournalEntity>,
    onSelectForChart: ((SignalJournalEntity) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("signal_journal_section"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .premiumPanel()
                .padding(10.dp)
        ) {
            Text(
                text = "Immutable forward-tested signal log. Original confirmed candle close, entry/SL/TP levels, and indicator snapshots are locked at creation; outcomes are tracked strictly on subsequent candles without look-ahead bias.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (signals.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No signals recorded yet. Run AI Analysis on confirmed candles to build your forward-test journal.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            var showAllSignals by remember { mutableStateOf(false) }
            val visibleSignals = if (showAllSignals) signals else signals.take(15)
            visibleSignals.forEach { sig ->
                SignalJournalCard(signal = sig, onSelectForChart = onSelectForChart)
            }
            if (signals.size > 15) {
                OutlinedButton(onClick = { showAllSignals = !showAllSignals }) {
                    Text(if (showAllSignals) "Show fewer signals" else "Show all ${signals.size} signals")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignalJournalCard(
    signal: SignalJournalEntity,
    onSelectForChart: ((SignalJournalEntity) -> Unit)? = null
) {
    var showSnapshot by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("dd MMM HH:mm", Locale.US) }

    val (decLabel, decColor, decBg) = when (signal.decision.uppercase(Locale.US)) {
        "LONG" -> Triple("🟢 LONG", BullishGreen, BullishGreenSoft)
        "SHORT" -> Triple("🔴 SHORT", BearishRed, BearishRedSoft)
        else -> Triple("🟡 WAIT", NeutralAmber, NeutralAmberSoft)
    }

    val (statusLabel, statusCol, statusBg) = when (signal.status.uppercase(Locale.US)) {
        "ENTRY_TRIGGERED" -> Triple("ENTRY TRIGGERED", CyanAccent, CyanAccent.copy(alpha = 0.16f))
        "TP1_HIT" -> Triple("TP1 HIT", BullishGreen, BullishGreenSoft)
        "TP2_HIT" -> Triple("TP2 HIT", BullishGreen, BullishGreenSoft)
        "TP3_HIT" -> Triple("TP3 HIT", BullishGreen, BullishGreenSoft)
        "SL_HIT" -> Triple("SL HIT", BearishRed, BearishRedSoft)
        "INVALIDATED" -> Triple("INVALIDATED", BearishRed, BearishRedSoft)
        "AMBIGUOUS" -> Triple("AMBIGUOUS", NeutralAmber, NeutralAmberSoft)
        "EXPIRED" -> Triple("EXPIRED", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
        "CLOSED" -> Triple("CLOSED", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
        "CANCELLED" -> Triple("CANCELLED", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
        "WAIT" -> Triple("WAIT LOGGED", NeutralAmber, NeutralAmberSoft)
        else -> Triple("ACTIVE", CyanAccent, CyanAccent.copy(alpha = 0.16f))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .premiumPanel()
            .padding(10.dp)
            .testTag("journal_item_${signal.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Row 1: Symbol · TF + Decision + Grade + Forward-Test Outcome Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "${formatSymbolPair(signal.symbol)} · ${signal.primaryTimeframe}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = "Grade ${signal.setupQuality}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (signal.setupQuality in listOf("A+", "A")) BullishGreen else NeutralAmber,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = signal.marketRegime,
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent,
                        maxLines = 1,
                        softWrap = false
                    )
                    val modeAttrLabel = when {
                        signal.aiSystemMode.equals("AI_TRADING_STYLE", ignoreCase = true) -> {
                            val prof = when (signal.tradingStyleProfile.uppercase(Locale.US)) {
                                "SCALPING" -> "Scalping"
                                "SWING_TRADING" -> "Swing Trading"
                                else -> "Day Trading"
                            }
                            "AI Gaya Trading · $prof"
                        }
                        signal.aiExecutionMode.equals("MANUAL", ignoreCase = true) -> "AI Continuous · Manual"
                        else -> "AI Continuous · Auto"
                    }
                    Text(
                        text = modeAttrLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                    if (!signal.decision.equals("WAIT", ignoreCase = true) && signal.entryOrderType.isNotBlank()) {
                        Text(
                            text = "Order: ${signal.entryOrderType.uppercase(Locale.US)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeutralAmber,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    if (signal.source.equals("BASELINE", ignoreCase = true)) {
                        Text(
                            text = "BASELINE",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanAccent,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    if (signal.exitModelVersion < 2) {
                        Text(
                            text = "legacy",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeutralAmber,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    if (signal.botAction.isNotBlank() && signal.botAction != "NONE") {
                        Text(
                            text = "Bot: ${signal.botAction}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeutralAmber,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    if (signal.tradingSessionLabel.isNotBlank()) {
                        Text(
                            text = "Session: ${signal.tradingSessionLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanAccent,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    if (signal.setupDecisionState.isNotBlank()) {
                        Text(
                            text = "State: ${signal.setupDecisionState}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (signal.setupDecisionState == "ENTRY_READY") BullishGreen else NeutralAmber,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                val candleCloseText = formatCryptoPrice(signal.priceAtSignal)
                Text(
                    text = "${dateFormat.format(Date(signal.timestamp))} · Price $${candleCloseText}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(color = decBg, shape = RoundedCornerShape(4.dp)) {
                    Text(
                        text = "$decLabel ${signal.confidence}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = decColor,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
                Surface(color = statusBg, shape = RoundedCornerShape(4.dp)) {
                    val rSuffix = if (signal.status in listOf("TP1_HIT", "TP2_HIT", "TP3_HIT", "SL_HIT", "CLOSED")) {
                        val sign = if (signal.realizedR >= 0.0) "+" else ""
                        " (${sign}${String.format(Locale.US, "%.2f", signal.realizedR)}R net)"
                    } else {
                        ""
                    }
                    Text(
                        text = "$statusLabel$rSuffix",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusCol,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }

        // Row 2: Entry / SL / TP / R:R if LONG or SHORT
        if (!signal.decision.equals("WAIT", ignoreCase = true)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                JournalFieldItem(
                    label = "Entry Zone",
                    value = "${formatCryptoPrice(signal.entryMin)} - ${formatCryptoPrice(signal.entryMax)}",
                    valueColor = CyanAccent,
                    modifier = Modifier.weight(1.3f)
                )
                JournalFieldItem(
                    label = "Stop Loss",
                    value = formatCryptoPrice(signal.stopLoss),
                    valueColor = BearishRed,
                    modifier = Modifier.weight(0.85f)
                )
                JournalFieldItem(
                    label = "R:R",
                    value = "1:${String.format(Locale.US, "%.1f", signal.riskReward)}",
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    alignEnd = true,
                    modifier = Modifier.weight(0.65f)
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "TP1 ${formatCryptoPrice(signal.tp1)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (signal.highestTpReached >= 1) BullishGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (signal.highestTpReached >= 1) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "TP2 ${formatCryptoPrice(signal.tp2)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (signal.highestTpReached >= 2) BullishGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (signal.highestTpReached >= 2) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "TP3 ${formatCryptoPrice(signal.tp3)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (signal.highestTpReached >= 3) BullishGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (signal.highestTpReached >= 3) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        // Row 3: Market structure/trend/momentum + Provider & Model + [ Snapshot ] toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Struct: ${signal.marketStructure} · Trend: ${signal.trend} · Mom: ${signal.momentum}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (signal.smcStructureSummary.isNotBlank()) {
                    Text(
                        text = "SMC: ${signal.smcStructureSummary}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (signal.liquiditySweepSummary.isNotBlank() && !signal.liquiditySweepState.equals("NONE", ignoreCase = true)) {
                    Text(
                        text = "Sweep [${signal.liquiditySweepState}]: ${signal.liquiditySweepSummary}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (signal.spreadStatusSummary.isNotBlank()) {
                    Text(
                        text = "Spread/Vol: ${signal.spreadStatusSummary}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "${signal.aiProviderUsed} · ${signal.aiModelUsed}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent.copy(alpha = 0.85f),
                    maxLines = 1,
                    softWrap = false
                )
                if (signal.statusReason.isNotBlank()) {
                    Text(
                        text = "[${signal.lifecycleState.ifBlank { signal.status }}] ${signal.statusReason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("journal_entry_status_reason_${signal.id}")
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onSelectForChart != null) {
                    val isActionable = !signal.decision.equals("WAIT", ignoreCase = true) &&
                        (signal.entryPrice > 0.0 || (signal.entryMin > 0.0 && signal.entryMax > 0.0)) &&
                        signal.stopLoss > 0.0 && signal.tp1 > 0.0
                    Text(
                        text = if (isActionable) "[ View on Chart ]" else "[ Non-actionable ]",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isActionable) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onSelectForChart(signal) }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .testTag("view_journal_on_chart_${signal.id}")
                    )
                }
                Text(
                    text = if (showSnapshot) "[ Hide Snapshot ]" else "[ Snapshot ]",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { showSnapshot = !showSnapshot }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }

        if (showSnapshot) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(8.dp)
            ) {
                Text(
                    text = signal.originalSnapshotJson.take(900),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun JournalFieldItem(
    label: String,
    value: String,
    valueColor: Color,
    alignEnd: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Requirement #15, #16, #19: Lightweight Performance Dashboard with Filters, Profit Factor,
 * Best/Worst Symbol & Timeframe, and Sample Size Classification.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignalPerformanceDashboardSection(
    metrics: SignalPerformanceMetrics,
    filter: JournalFilterState,
    availableSymbols: List<String>,
    paperAccountState: com.example.simulation.paper.PaperTradingAccountState = com.example.simulation.paper.PaperTradingAccountState(),
    onUpdateFilter: (JournalFilterState) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAdvancedFilters by remember { mutableStateOf(false) }
    val filteredClosedBotTrades = remember(paperAccountState.closedPositions, filter) {
        paperAccountState.closedPositions.filter { pos ->
            val symOk = filter.symbolFilter == "ALL" || pos.symbol.equals(filter.symbolFilter, ignoreCase = true)
            val tfOk = filter.timeframeFilter == "ALL" || pos.timeframe.equals(filter.timeframeFilter, ignoreCase = true)
            symOk && tfOk
        }
    }
    val filteredOpenBotTrades = remember(paperAccountState.openPositions, filter) {
        paperAccountState.openPositions.filter { pos ->
            val symOk = filter.symbolFilter == "ALL" || pos.symbol.equals(filter.symbolFilter, ignoreCase = true)
            val tfOk = filter.timeframeFilter == "ALL" || pos.timeframe.equals(filter.timeframeFilter, ignoreCase = true)
            symOk && tfOk
        }
    }
    val botWins = filteredClosedBotTrades.count { it.realizedPnlUsd > 0.0 }
    val botLosses = filteredClosedBotTrades.count { it.realizedPnlUsd < 0.0 }
    val botTotalTrades = filteredClosedBotTrades.size
    val botWinRate = if (botWins + botLosses > 0) (botWins.toDouble() / (botWins + botLosses).toDouble()) * 100.0 else 0.0
    val botRealizedPnl = filteredClosedBotTrades.sumOf { it.realizedPnlUsd } + filteredOpenBotTrades.sumOf { it.realizedPnlUsd }
    val botUnrealizedPnl = filteredOpenBotTrades.sumOf { it.unrealizedPnlUsd }
    val botGrossProfit = filteredClosedBotTrades.filter { it.realizedPnlUsd > 0.0 }.sumOf { it.realizedPnlUsd }
    val botGrossLoss = kotlin.math.abs(filteredClosedBotTrades.filter { it.realizedPnlUsd < 0.0 }.sumOf { it.realizedPnlUsd })
    val botProfitFactor = when {
        botGrossLoss > 1e-6 -> botGrossProfit / botGrossLoss
        botGrossProfit > 0.0 -> 99.0
        else -> 0.0
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("signal_performance_section"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Compact Filter Bar (Symbol, Timeframe, Date Range, Side)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .premiumPanel()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "FILTERS",
                style = MaterialTheme.typography.labelSmall,
                color = CyanAccent,
                fontWeight = FontWeight.Bold
            )

            // Symbol Filter Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val symbols = (listOf("ALL") + availableSymbols).distinct()
                symbols.forEach { sym ->
                    FilterChipButton(
                        label = if (sym == "ALL") "All Pairs" else formatSymbolPair(sym),
                        selected = filter.symbolFilter.equals(sym, ignoreCase = true),
                        onClick = { onUpdateFilter(filter.copy(symbolFilter = sym)) }
                    )
                }
            }

            // Timeframe Filter Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChipButton(
                    label = "All TF",
                    selected = filter.timeframeFilter == "ALL",
                    onClick = { onUpdateFilter(filter.copy(timeframeFilter = "ALL")) }
                )
                Timeframe.entries.forEach { tf ->
                    FilterChipButton(
                        label = tf.label,
                        selected = filter.timeframeFilter.equals(tf.label, ignoreCase = true),
                        onClick = { onUpdateFilter(filter.copy(timeframeFilter = tf.label)) }
                    )
                }
            }

            OutlinedButton(
                onClick = { showAdvancedFilters = !showAdvancedFilters },
                modifier = Modifier.testTag("performance_advanced_filter_toggle")
            ) {
                Text(if (showAdvancedFilters) "Hide advanced filters" else "More filters · Source / Status / Scenario")
            }
            if (showAdvancedFilters) {
            // Side (ALL / LONG / SHORT) & Date Range (All Time / 7D / 30D)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DecisionSideFilter.entries.forEach { side ->
                    FilterChipButton(
                        label = side.label,
                        selected = filter.decisionFilter == side,
                        onClick = { onUpdateFilter(filter.copy(decisionFilter = side)) }
                    )
                }
                DateRangeFilter.entries.forEach { dr ->
                    FilterChipButton(
                        label = dr.label,
                        selected = filter.dateRangeFilter == dr,
                        onClick = { onUpdateFilter(filter.copy(dateRangeFilter = dr)) }
                    )
                }
            }

            // Setup Grade (All Grades / A+ / A / B / WAIT) & Lifecycle Status Filters
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SetupGradeFilter.entries.forEach { grade ->
                    FilterChipButton(
                        label = grade.label,
                        selected = filter.gradeFilter == grade,
                        onClick = { onUpdateFilter(filter.copy(gradeFilter = grade)) }
                    )
                }
                SignalStatusFilter.entries.forEach { st ->
                    FilterChipButton(
                        label = st.label,
                        selected = filter.statusFilter == st,
                        onClick = { onUpdateFilter(filter.copy(statusFilter = st)) }
                    )
                }
            }

            // Tahap 4 & Tahap 7: Scenario Selector ("Optimis" vs "Pesimis") & Source Toggle ("AI" vs "Baseline")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Source:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                SignalSourceFilter.entries.forEach { src ->
                    FilterChipButton(
                        label = src.label,
                        selected = filter.sourceFilter == src,
                        onClick = { onUpdateFilter(filter.copy(sourceFilter = src)) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Skenario:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                PerformanceScenario.entries.forEach { scen ->
                    val desc = if (scen == PerformanceScenario.OPTIMISTIC) {
                        "${scen.label} (Excl. Ambiguous)"
                    } else {
                        "${scen.label} (Ambiguous = -1R)"
                    }
                    FilterChipButton(
                        label = desc,
                        selected = filter.scenario == scen,
                        onClick = { onUpdateFilter(filter.copy(scenario = scen)) }
                    )
                }
            }
            }
        }

        // Tahap 7: AI vs Baseline (EMA 20/50/200) Comparison Summary Card (Net R, Expectancy, Sample Size)
        val comp = metrics.sourceComparison
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .premiumPanel()
                .padding(12.dp)
                .testTag("ai_vs_baseline_comparison_card"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "AI VS BASELINE COMPARISON (${metrics.scenario.label.uppercase(Locale.US)})",
                style = MaterialTheme.typography.labelSmall,
                color = CyanAccent,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatCell(
                    label = "AI Net R (n=${comp.aiSampleSize})",
                    value = "${if (comp.aiNetR >= 0) "+" else ""}${String.format(Locale.US, "%.2f", comp.aiNetR)}R",
                    valueColor = if (comp.aiNetR >= 0.0) BullishGreen else BearishRed,
                    modifier = Modifier.weight(1f)
                )
                StatCell(
                    label = "AI Expectancy",
                    value = "${if (comp.aiExpectancyR >= 0) "+" else ""}${String.format(Locale.US, "%.2f", comp.aiExpectancyR)}R",
                    valueColor = if (comp.aiExpectancyR >= 0.0) BullishGreen else BearishRed,
                    modifier = Modifier.weight(1f),
                    alignEnd = true
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatCell(
                    label = "Baseline Net R (n=${comp.baselineSampleSize})",
                    value = "${if (comp.baselineNetR >= 0) "+" else ""}${String.format(Locale.US, "%.2f", comp.baselineNetR)}R",
                    valueColor = if (comp.baselineNetR >= 0.0) BullishGreen else BearishRed,
                    modifier = Modifier.weight(1f)
                )
                StatCell(
                    label = "Baseline Expectancy",
                    value = "${if (comp.baselineExpectancyR >= 0) "+" else ""}${String.format(Locale.US, "%.2f", comp.baselineExpectancyR)}R",
                    valueColor = if (comp.baselineExpectancyR >= 0.0) BullishGreen else BearishRed,
                    modifier = Modifier.weight(1f),
                    alignEnd = true
                )
            }
        }

        // 2. Sample Size Classification Notice (Requirement #16 & Tahap 4: based on resolved directional trades)
        val sampleTier = metrics.sampleClassification
        val sampleColor = if (sampleTier.hasSufficientSample) CyanAccent else NeutralAmber
        val sampleBg = if (sampleTier.hasSufficientSample) CyanAccent.copy(alpha = 0.14f) else NeutralAmberSoft
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(sampleBg)
                .border(1.dp, sampleColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .testTag("sample_size_status_banner"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = sampleTier.label,
                style = MaterialTheme.typography.titleSmall,
                color = sampleColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = "${metrics.resolvedTradeCount} resolved (${metrics.totalSignals} total)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false
            )
        }

        // 3. Raw Signal Counts Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .premiumPanel()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "FORWARD-TEST SIGNAL COUNTS (${metrics.scenario.label.uppercase(Locale.US)})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = 2,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCell("Total Signals", "${metrics.totalSignals}", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                StatCell("Wins", "${metrics.wins}", BullishGreen, Modifier.weight(1f))
                StatCell("Losses", "${metrics.losses}", BearishRed, Modifier.weight(1f))
                StatCell("Wait / Amb", "${metrics.waitSignals} / ${metrics.ambiguousCount}", NeutralAmber, Modifier.weight(1f), alignEnd = true)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            // 4. Performance Statistics (Gated on resolved directional trades >= 20 per Tahap 4)
            if (!sampleTier.hasSufficientSample) {
                Text(
                    text = "Insufficient sample size (< 20 resolved trades). Win Rate, Profit Factor, Expectancy, and Drawdown statistics are hidden until at least 20 directional trades are resolved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("insufficient_sample_notice")
                )
            } else {
                Text(
                    text = "HISTORICAL FORWARD-TEST PERFORMANCE (${sampleTier.label} · ${metrics.scenario.label})",
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
                    StatCell(
                        label = "Win Rate (Wilson 95%)",
                        value = "${String.format(Locale.US, "%.1f", metrics.winRate)}% (${String.format(Locale.US, "%.0f", metrics.wilsonLowerPct)}–${String.format(Locale.US, "%.0f", metrics.wilsonUpperPct)}%)",
                        valueColor = if (metrics.winRate >= 50.0) BullishGreen else BearishRed,
                        modifier = Modifier.weight(1.3f)
                    )
                    StatCell(
                        label = "Planned R:R",
                        value = "${String.format(Locale.US, "%.2f", metrics.averageRR)}R",
                        valueColor = CyanAccent,
                        modifier = Modifier.weight(0.85f)
                    )
                    StatCell(
                        label = "Profit Factor",
                        value = String.format(Locale.US, "%.2f", metrics.profitFactor),
                        valueColor = if (metrics.profitFactor >= 1.2) BullishGreen else NeutralAmber,
                        modifier = Modifier.weight(0.85f),
                        alignEnd = true
                    )
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    maxItemsInEachRow = 2,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatCell(
                        label = "Net Total R",
                        value = "${if (metrics.totalR >= 0) "+" else ""}${String.format(Locale.US, "%.2f", metrics.totalR)}R",
                        valueColor = if (metrics.totalR >= 0.0) BullishGreen else BearishRed,
                        modifier = Modifier.weight(1f)
                    )
                    StatCell(
                        label = "Max DD (Net R)",
                        value = "-${String.format(Locale.US, "%.2f", metrics.maxDrawdownR)}R",
                        valueColor = BearishRed,
                        modifier = Modifier.weight(1f)
                    )
                    StatCell(
                        label = "Net Expectancy",
                        value = "${if (metrics.expectancyR >= 0) "+" else ""}${String.format(Locale.US, "%.2f", metrics.expectancyR)}R",
                        valueColor = if (metrics.expectancyR >= 0.0) BullishGreen else BearishRed,
                        modifier = Modifier.weight(1f),
                        alignEnd = true
                    )
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    maxItemsInEachRow = 2,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatCell(
                        label = "Long / Short WR",
                        value = "${String.format(Locale.US, "%.0f", metrics.longWinRate)}% / ${String.format(Locale.US, "%.0f", metrics.shortWinRate)}%",
                        valueColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    StatCell(
                        label = "Best / Worst TF",
                        value = "${metrics.bestTimeframe} / ${metrics.worstTimeframe}",
                        valueColor = CyanAccent,
                        modifier = Modifier.weight(1f)
                    )
                    StatCell(
                        label = "Best / Worst Pair",
                        value = "${metrics.bestSymbol.removeSuffix("USDT")} / ${metrics.worstSymbol.removeSuffix("USDT")}",
                        valueColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        alignEnd = true
                    )
                }
            }

            if (metrics.confidenceBuckets.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Text(
                    text = "CONFIDENCE CALIBRATION (WIN RATE BY BUCKET)",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent,
                    fontWeight = FontWeight.Bold
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("confidence_calibration_table"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    metrics.confidenceBuckets.forEach { bucket ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Conf ${bucket.label} (n=${bucket.sampleSize})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                            val wrText = if (bucket.sampleSize > 0) {
                                "${String.format(Locale.US, "%.1f", bucket.winRatePct)}%"
                            } else {
                                "--"
                            }
                            val statusNote = if (!bucket.hasSufficientData) " · belum cukup data" else ""
                            Text(
                                text = "$wrText$statusNote",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (!bucket.hasSufficientData) {
                                    NeutralAmber
                                } else if (bucket.winRatePct >= 50.0) {
                                    BullishGreen
                                } else {
                                    BearishRed
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // 5. Recorded Simulated Bot Trades Performance Card (Requirement #3: Separate from unexecuted signals)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .premiumPanel()
                .padding(12.dp)
                .testTag("bot_simulation_performance_card"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "SIMULATED BOT TRADES PERFORMANCE (EXECUTED ONLY)",
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
                StatCell("Total Trades", "$botTotalTrades (${filteredOpenBotTrades.size} Open)", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                StatCell("Wins", "$botWins", BullishGreen, Modifier.weight(1f))
                StatCell("Losses", "$botLosses", BearishRed, Modifier.weight(1f))
                StatCell(
                    label = "Win Rate",
                    value = "${String.format(Locale.US, "%.1f", botWinRate)}%",
                    valueColor = if (botWinRate >= 50.0) BullishGreen else NeutralAmber,
                    modifier = Modifier.weight(1f),
                    alignEnd = true
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = 2,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val rSign = if (botRealizedPnl >= 0.0) "+" else ""
                StatCell(
                    label = "Realized PnL",
                    value = "$rSign$${String.format(Locale.US, "%.2f", botRealizedPnl)}",
                    valueColor = if (botRealizedPnl >= 0.0) BullishGreen else BearishRed,
                    modifier = Modifier.weight(1f)
                )
                val uSign = if (botUnrealizedPnl >= 0.0) "+" else ""
                StatCell(
                    label = "Unrealized PnL",
                    value = "$uSign$${String.format(Locale.US, "%.2f", botUnrealizedPnl)}",
                    valueColor = if (botUnrealizedPnl >= 0.0) BullishGreen else BearishRed,
                    modifier = Modifier.weight(1f)
                )
                StatCell(
                    label = "Profit Factor",
                    value = if (botTotalTrades > 0) String.format(Locale.US, "%.2f", botProfitFactor) else "--",
                    valueColor = if (botProfitFactor >= 1.2) BullishGreen else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                StatCell(
                    label = "Max Drawdown",
                    value = "${String.format(Locale.US, "%.2f", paperAccountState.maxDrawdownPct)}%",
                    valueColor = if (paperAccountState.maxDrawdownPct > 10.0) BearishRed else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    alignEnd = true
                )
            }
        }
    }
}

@Composable
private fun FilterChipButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) CyanAccent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(5.dp),
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (selected) CyanAccent else Color.Transparent,
                shape = RoundedCornerShape(5.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false
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
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = valueColor,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
