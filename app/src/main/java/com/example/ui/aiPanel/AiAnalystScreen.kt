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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.example.ai.usage.TokenUsageSource
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
import androidx.compose.ui.unit.sp
import com.example.ai.parser.AiAnalysisResult
import com.example.analysis.events.EventProtectionMode
import com.example.analysis.events.EventRiskAssessment
import com.example.analysis.events.MacroCalendarEvent
import com.example.analysis.events.UnavailableEventPolicy
import com.example.analysis.journal.JournalFilterState
import com.example.analysis.journal.SignalForwardTester
import com.example.analysis.marketStructure.StructureState
import com.example.analysis.signal.ActiveTradeSetup
import com.example.analysis.signal.ChartSignalOverlay
import com.example.analysis.signal.MarketAssessmentState
import com.example.analysis.signal.SetupLifecycleState
import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.market.candle.Timeframe
import com.example.market.candle.formatCryptoPrice
import com.example.market.candle.formatSymbolPair
import com.example.simulation.paper.BotRunState
import com.example.simulation.paper.PaperTradingAccountState
import com.example.simulation.paper.PaperTradingConfig
import com.example.storage.preferences.AiAnalysisSystem
import com.example.storage.preferences.AiAutoMonitorRuntimeState
import com.example.storage.preferences.AiContinuousSubmode
import com.example.storage.preferences.TradingStyleProfile
import com.example.storage.watchlist.SignalJournalEntity
import com.example.ui.indicators.MarketStructureAndLevelsCard
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BearishRedSoft
import com.example.ui.theme.BullishGreen
import com.example.ui.theme.BullishGreenSoft
import com.example.ui.theme.CyanAccent
import com.example.ui.components.premiumPanel
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NeutralAmber
import com.example.ui.theme.NeutralAmberSoft
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AiPanelSection(val label: String) {
    ANALYSIS("Analysis"),
    SIMULATION("Bot Trading (Simulasi)"),
    JOURNAL("Journal"),
    PERFORMANCE("Performance")
}

/**
 * Compact AI Summary Card for Chart Page (Preserves existing responsive mobile layout + supports LONG / SHORT / WAIT).
 */
@Composable
fun CompactAiAnalystCard(
    symbol: String,
    timeframe: Timeframe,
    snapshot: MarketTechnicalSnapshot?,
    aiResult: AiAnalysisResult?,
    isAnalyzing: Boolean,
    errorMessage: String?,
    providerBadge: String,
    botActionSummary: String = "STOPPED",
    marketAssessment: MarketAssessmentState? = null,
    activeSetup: ActiveTradeSetup? = null,
    activeModeAttributionLabel: String = "AI Continuous · Auto",
    latestBaselineSignal: SignalJournalEntity? = null,
    onOpenAnalystPanel: () -> Unit = {},
    onOpenFullAiTab: () -> Unit = onOpenAnalystPanel,
    modifier: Modifier = Modifier
) {
    val liveSetup = activeSetup?.takeIf { !it.lifecycleState.isTerminal }
    val effectiveAiResult = liveSetup?.sourceAiResult ?: aiResult
    val fallbackDecision = when (snapshot?.marketStructure?.state) {
        StructureState.BULLISH -> "LONG"
        StructureState.BEARISH -> "SHORT"
        else -> "WAIT"
    }
    val decisionStr = liveSetup?.direction ?: effectiveAiResult?.decision ?: fallbackDecision
    val (decisionBadge, decisionColor, _) = resolveDecisionVisuals(decisionStr)
    val pendingLimitBadge = effectiveAiResult
        ?.takeIf { it.tradePlan?.isPending == true }
        ?.structuredOrderActionLabel
    val conciseStatus = resolveConciseSetupStatus(liveSetup, effectiveAiResult, marketAssessment)
    val gradeText = liveSetup?.setupGrade ?: effectiveAiResult?.setupQualityGrade?.takeIf { it != "WAIT" }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .premiumPanel()
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .testTag("compact_ai_analyst_card"),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // Row 1: Prominent Decision + Concise Status + Heuristic Score + [ Analyst Chart ] Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Prominent Decision + Grade
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (gradeText != null && !decisionStr.equals("WAIT", ignoreCase = true)) {
                            "${pendingLimitBadge ?: decisionBadge} · $gradeText"
                        } else {
                            pendingLimitBadge ?: decisionBadge
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = decisionColor,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.testTag("compact_decision_text")
                    )

                    // Concise Setup Status: SCANNING, PENDING, ENTERED, or INVALIDATED
                    val statusColor = when (conciseStatus.uppercase(Locale.US)) {
                        "ENTERED" -> BullishGreen
                        "PENDING" -> CyanAccent
                        "INVALIDATED" -> BearishRed
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        color = statusColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.testTag("compact_setup_lifecycle_badge")
                    ) {
                        Text(
                            text = conciseStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }

                // Confidence heuristic score (only when available, labeled as heuristic)
                if (effectiveAiResult != null && effectiveAiResult.confidence > 0) {
                    Text(
                        text = "Score: ${effectiveAiResult.confidence}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.testTag("compact_heuristic_score")
                    )
                }
            }

            // Action button renamed to "Analyst Chart"
            Surface(
                color = CyanAccent.copy(alpha = 0.16f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .border(1.dp, CyanAccent.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                    .clickable { onOpenAnalystPanel() }
                    .testTag("analyst_chart_button")
                    .testTag("open_ai_analysis_button")
            ) {
                Text(
                    text = if (isAnalyzing) "Analyzing..." else "Analyst Chart",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                )
            }
        }

        // Row 2: Actionable levels (Entry, SL, TP1) for LONG/SHORT, or Short Market State for WAIT
        if (effectiveAiResult != null && !effectiveAiResult.isWait) {
            val tp1 = effectiveAiResult.takeProfit.firstOrNull()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactMetricColumn(
                    label = "Entry",
                    value = "${formatCryptoPrice(effectiveAiResult.entryZone.low)} - ${formatCryptoPrice(effectiveAiResult.entryZone.high)}",
                    valueColor = CyanAccent,
                    modifier = Modifier.weight(1.35f)
                )
                CompactMetricColumn(
                    label = "SL",
                    value = formatCryptoPrice(effectiveAiResult.stopLoss),
                    valueColor = BearishRed,
                    modifier = Modifier.weight(0.85f)
                )
                CompactMetricColumn(
                    label = "TP1",
                    value = tp1?.let { formatCryptoPrice(it) } ?: "--",
                    valueColor = BullishGreen,
                    alignEnd = true,
                    modifier = Modifier.weight(0.85f)
                )
            }
        } else if (effectiveAiResult != null && effectiveAiResult.isWait) {
            val waitMarketState = resolveWaitMarketState(effectiveAiResult, snapshot)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "AI Hybrid: WAIT — awaiting confirmation",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeutralAmber,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            color = NeutralAmber.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.testTag("compact_wait_state_badge")
                        ) {
                            Text(
                                text = waitMarketState,
                                style = MaterialTheme.typography.labelSmall,
                                color = NeutralAmber,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                if (latestBaselineSignal != null && !latestBaselineSignal.decision.equals("WAIT", ignoreCase = true)) {
                    val baseSide = latestBaselineSignal.decision.uppercase(Locale.US)
                    val baseCol = if (baseSide == "LONG") BullishGreen else BearishRed
                    val orderType = if (latestBaselineSignal.entryOrderType.isNotBlank()) " ${latestBaselineSignal.entryOrderType.uppercase(Locale.US)}" else ""
                    val isTriggered = latestBaselineSignal.status.equals("ENTRY_TRIGGERED", ignoreCase = true)
                    val statusText = if (isTriggered) "price triggered (analytical)" else latestBaselineSignal.status.lowercase(Locale.US).replace('_', ' ')

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("baseline_dual_display_row"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Baseline EMA: $baseSide$orderType — $statusText",
                            style = MaterialTheme.typography.labelSmall,
                            color = baseCol,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Entry ${formatCryptoPrice(if (latestBaselineSignal.entryPrice > 0.0) latestBaselineSignal.entryPrice else latestBaselineSignal.entryMin)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanAccent,
                            fontFamily = JetBrainsMonoFontFamily,
                            maxLines = 1
                        )
                    }
                }
            }
        } else if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.labelSmall,
                color = BearishRed,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            val waitMarketState = resolveWaitMarketState(null, snapshot)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "State: $waitMarketState · Tap Analyst Chart to inspect",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun resolveWaitMarketState(aiResult: AiAnalysisResult?, snapshot: MarketTechnicalSnapshot?): String {
    if (aiResult != null) {
        val htf = aiResult.higherTimeframeBias.trim().uppercase(Locale.US)
        val ptf = aiResult.primaryTimeframeBias.trim().uppercase(Locale.US)
        return when {
            htf != ptf && htf in listOf("BULLISH", "BEARISH") && ptf in listOf("BULLISH", "BEARISH") -> "MTF Transitional"
            aiResult.marketRegime.isNotBlank() && !aiResult.marketRegime.equals("RANGING", ignoreCase = true) -> aiResult.marketRegime
            htf in listOf("SIDEWAYS", "NEUTRAL", "TRANSITIONAL") || ptf in listOf("SIDEWAYS", "NEUTRAL", "TRANSITIONAL") -> "MTF Transitional"
            else -> "MTF Transitional"
        }
    }
    val struct = snapshot?.marketStructure?.state?.name ?: "TRANSITIONAL"
    return if (struct == "SIDEWAYS") "Consolidation Range" else "MTF Transitional"
}

private fun resolveConciseSetupStatus(
    liveSetup: ActiveTradeSetup?,
    effectiveAiResult: AiAnalysisResult?,
    marketAssessment: MarketAssessmentState?
): String {
    return when {
        liveSetup != null -> when (liveSetup.lifecycleState) {
            com.example.analysis.signal.SetupLifecycleState.ENTERED -> "ENTERED"
            com.example.analysis.signal.SetupLifecycleState.ACTIVE,
            com.example.analysis.signal.SetupLifecycleState.CANDIDATE,
            com.example.analysis.signal.SetupLifecycleState.PARTIALLY_TRIGGERED -> "PENDING"
            com.example.analysis.signal.SetupLifecycleState.INVALIDATED -> "INVALIDATED"
            com.example.analysis.signal.SetupLifecycleState.EXPIRED -> "EXPIRED"
            com.example.analysis.signal.SetupLifecycleState.CANCELLED -> "CANCELLED"
            com.example.analysis.signal.SetupLifecycleState.COMPLETED -> "COMPLETED"
            com.example.analysis.signal.SetupLifecycleState.SCANNING -> "SCANNING"
        }
        effectiveAiResult?.setupDecisionState?.equals("ENTRY_READY", ignoreCase = true) == true -> "PENDING"
        effectiveAiResult?.setupDecisionState?.equals("ACTIVE", ignoreCase = true) == true -> "ENTERED"
        effectiveAiResult?.setupDecisionState?.equals("INVALIDATED", ignoreCase = true) == true -> "INVALIDATED"
        marketAssessment?.scanLifecycleState != null && marketAssessment.scanLifecycleState != com.example.analysis.signal.SetupLifecycleState.SCANNING -> {
            when (marketAssessment.scanLifecycleState) {
                com.example.analysis.signal.SetupLifecycleState.ENTERED -> "ENTERED"
                com.example.analysis.signal.SetupLifecycleState.ACTIVE,
                com.example.analysis.signal.SetupLifecycleState.CANDIDATE -> "PENDING"
                com.example.analysis.signal.SetupLifecycleState.INVALIDATED -> "INVALIDATED"
                else -> marketAssessment.scanLifecycleState.displayLabel
            }
        }
        effectiveAiResult != null && !effectiveAiResult.isWait -> "PENDING"
        else -> "SCANNING"
    }
}

@Composable
private fun CompactMetricColumn(
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
 * Dedicated V2 AI Page matching Requirements #1-#19:
 * Compact sections for Multi-Timeframe Analysis (LONG / SHORT / WAIT), Paper Trading & Backtest,
 * Signal Journal, and Performance Dashboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FullAiAnalystScreen(
    symbol: String,
    timeframe: Timeframe,
    snapshot: MarketTechnicalSnapshot?,
    aiResult: AiAnalysisResult?,
    activeSignalOverlay: ChartSignalOverlay? = null,
    marketAssessment: MarketAssessmentState? = null,
    activeSetup: ActiveTradeSetup? = null,
    isAnalyzing: Boolean,
    autoAiAnalysis: Boolean,
    aiAnalysisSystem: AiAnalysisSystem = if (autoAiAnalysis) AiAnalysisSystem.AI_CONTINUOUS else AiAnalysisSystem.AI_CONTINUOUS,
    aiContinuousSubmode: AiContinuousSubmode = if (autoAiAnalysis) AiContinuousSubmode.AUTO else AiContinuousSubmode.MANUAL,
    tradingStyleProfile: TradingStyleProfile = TradingStyleProfile.DAY_TRADING,
    autoMonitorRuntimeState: AiAutoMonitorRuntimeState = if (autoAiAnalysis) AiAutoMonitorRuntimeState.RUNNING else AiAutoMonitorRuntimeState.STOPPED,
    activeModeAttributionLabel: String = if (aiAnalysisSystem == AiAnalysisSystem.AI_TRADING_STYLE) {
        "AI Gaya Trading · ${tradingStyleProfile.label}"
    } else {
        "AI Continuous · ${aiContinuousSubmode.label}"
    },
    modeSwitchStatusBanner: String? = null,
    errorMessage: String?,
    providerBadge: String,
    signalJournal: List<SignalJournalEntity> = emptyList(),
    isCurrentSignalSaved: Boolean = false,
    paperAccountState: PaperTradingAccountState = PaperTradingAccountState(),
    eventRiskAssessment: EventRiskAssessment = EventRiskAssessment(),
    upcomingEvents: List<MacroCalendarEvent> = emptyList(),
    eventProtectionMode: EventProtectionMode = EventProtectionMode.BLOCK_HIGH_IMPACT,
    unavailableEventPolicy: UnavailableEventPolicy = UnavailableEventPolicy.ALLOW_WITH_WARNING,
    eventPreBufferMinutes: Int = 30,
    eventPostBufferMinutes: Int = 30,
    manualEventLockActive: Boolean = false,
    botActionSummary: String = "STOPPED",
    onToggleAutoAi: (Boolean) -> Unit,
    onSelectAiAnalysisSystem: (AiAnalysisSystem) -> Unit = {},
    onSelectAiContinuousSubmode: (AiContinuousSubmode) -> Unit = { sub ->
        onToggleAutoAi(sub == AiContinuousSubmode.AUTO)
    },
    onSelectTradingStyleProfile: (TradingStyleProfile) -> Unit = {},
    onSelectTimeframe: (Timeframe) -> Unit = {},
    onAnalyzeClick: () -> Unit,
    onSaveSignalToJournal: () -> Unit = {},
    onSetBotRunState: (BotRunState) -> Unit = {},
    onApplyCustomStartingBalance: (Double) -> Unit = {},
    onUpdatePaperConfig: (PaperTradingConfig) -> Unit = {},
    onOpenVirtualTradeFromCurrentSignal: () -> Unit = {},
    onCloseVirtualTradeManually: (String) -> Unit = {},
    onResetPaperAccount: () -> Unit = {},
    onSetEventProtectionMode: (EventProtectionMode) -> Unit = {},
    onSetUnavailableEventPolicy: (UnavailableEventPolicy) -> Unit = {},
    onSetEventBufferMinutes: (Int, Int) -> Unit = { _, _ -> },
    onToggleManualEventLock: (Boolean) -> Unit = {},
    onSelectJournalSignal: ((SignalJournalEntity) -> Unit)? = null,
    onOpenAiConfig: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var activeSection by remember { mutableStateOf(AiPanelSection.ANALYSIS) }
    var showReasoning by remember(aiResult?.analyzedAt) { mutableStateOf(false) }
    var journalFilter by remember { mutableStateOf(JournalFilterState()) }

    val performanceMetrics = remember(signalJournal, journalFilter) {
        SignalForwardTester.calculatePerformance(signalJournal, journalFilter)
    }
    val availableSymbols = remember(signalJournal, symbol) {
        (listOf(symbol) + signalJournal.map { it.symbol }).distinct()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("full_ai_analyst_screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Header + Provider Badge ("AI · Primary" / "AI · Backup") + Auto AI Switch
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .premiumPanel()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "LUMAVUN  /  AI INSIGHT",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyanAccent,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                    Surface(
                        color = CyanAccent.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.testTag("ai_provider_badge")
                    ) {
                        Text(
                            text = aiResult?.providerBadge ?: providerBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanAccent,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (onOpenAiConfig != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .clickable { onOpenAiConfig() }
                                .testTag("ai_config_header_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SmartToy,
                                    contentDescription = "AI Configuration",
                                    tint = CyanAccent,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "Config",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyanAccent,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${formatSymbolPair(symbol)} · ${timeframe.label.uppercase(Locale.US)}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = activeModeAttributionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    modifier = Modifier.testTag("ai_active_mode_header_badge")
                )
            }

            if (aiAnalysisSystem == AiAnalysisSystem.AI_CONTINUOUS) {
                val isAutoSubmode = aiContinuousSubmode == AiContinuousSubmode.AUTO && autoAiAnalysis
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "CONTINUOUS MODE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = if (isAutoSubmode) "AUTO ON" else "MANUAL MODE",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isAutoSubmode) BullishGreen else NeutralAmber,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.testTag("header_continuous_submode_status")
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(
                        checked = isAutoSubmode,
                        onCheckedChange = { enabled ->
                            onSelectAiContinuousSubmode(
                                if (enabled) AiContinuousSubmode.AUTO else AiContinuousSubmode.MANUAL
                            )
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = BullishGreen
                        ),
                        modifier = Modifier.testTag("auto_ai_switch")
                    )
                }
            } else {
                // Requirement #2 Option B & #7: AI Gaya Trading is always manual and MUST NOT have an Auto toggle
                Column(horizontalAlignment = Alignment.End) {
                    Surface(
                        color = NeutralAmberSoft,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .border(1.dp, NeutralAmber.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                            .testTag("trading_style_manual_only_badge")
                    ) {
                        Text(
                            text = "MANUAL PROFILE",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeutralAmber,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = tradingStyleProfile.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        // 2. Compact Section Selector: [ Analysis ] | [ Sim & Backtest ] | [ Journal (N) ] | [ Performance ]
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            maxItemsInEachRow = 2,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AiPanelSection.entries.forEach { sec ->
                val isSelected = activeSection == sec
                val labelText = when (sec) {
                    AiPanelSection.ANALYSIS -> "Analysis"
                    AiPanelSection.SIMULATION -> "Simulation"
                    AiPanelSection.JOURNAL -> "Journal (${signalJournal.size})"
                    AiPanelSection.PERFORMANCE -> "Performance"
                }
                Surface(
                    color = if (isSelected) CyanAccent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .border(
                            1.dp,
                            if (isSelected) CyanAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { activeSection = sec }
                        .testTag("ai_subtab_${sec.name.lowercase()}")
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 3.dp)
                    ) {
                        Text(
                            text = labelText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        when (activeSection) {
            AiPanelSection.ANALYSIS -> {
                // Requirement #2, #3, #7, #8: Dedicated AI System Selection Interface (AI Continuous vs AI Gaya Trading)
                AiSystemSelectionCard(
                    symbol = symbol,
                    activeTimeframe = timeframe,
                    aiAnalysisSystem = aiAnalysisSystem,
                    aiContinuousSubmode = aiContinuousSubmode,
                    tradingStyleProfile = tradingStyleProfile,
                    autoMonitorRuntimeState = autoMonitorRuntimeState,
                    modeSwitchStatusBanner = modeSwitchStatusBanner,
                    lastAnalyzedAt = aiResult?.analyzedAt,
                    onSelectSystem = onSelectAiAnalysisSystem,
                    onSelectContinuousSubmode = onSelectAiContinuousSubmode,
                    onSelectTradingStyleProfile = onSelectTradingStyleProfile,
                    onSelectTimeframe = onSelectTimeframe
                )

                if (errorMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(BearishRedSoft)
                            .border(1.dp, BearishRed.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                            .testTag("full_ai_error_box")
                    ) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.titleSmall,
                            color = BearishRed,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (marketAssessment != null) {
                    MarketAssessmentSummaryCard(
                        assessment = marketAssessment,
                        hasActiveSetup = activeSetup != null && !activeSetup.lifecycleState.isTerminal
                    )
                }

                V9EvidencePanel(aiResult?.technicalEvidence, snapshot)

                if (aiResult != null) {
                    val liveSetup = activeSetup?.takeIf { !it.lifecycleState.isTerminal }
                    val (decBadge, decColor, decBg) = resolveDecisionVisuals(liveSetup?.direction ?: aiResult.decision)
                    val isConfirmedLimit = aiResult.modelUsed == com.example.analysis.signal.ConfirmedLimitRecommendationEngine.MODEL_ID
                    val visibleOrderBadge = if (aiResult.tradePlan?.isPending == true)
                        aiResult.structuredOrderActionLabel else decBadge
                    val htfLabel = snapshot?.higherContext?.timeframe?.label
                        ?: timeframe.higherTimeframe()?.label
                        ?: "HTF"
                    val ltfLabel = snapshot?.lowerContext?.timeframe?.label
                        ?: timeframe.lowerTimeframe()?.label
                        ?: "LTF"
                    val lifecycleDisplay = activeSetup?.lifecycleState?.displayLabel
                        ?: activeSignalOverlay?.status?.displayLabel
                        ?: if (aiResult.isWait) SetupLifecycleState.SCANNING.displayLabel else SetupLifecycleState.ACTIVE.displayLabel

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .premiumPanel()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Decision + Lifecycle Status + Confidence + Setup Quality
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(decBg)
                                            .border(1.dp, decColor.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = visibleOrderBadge,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = decColor,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }

                                    Surface(
                                        color = CyanAccent.copy(alpha = 0.16f),
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.testTag("active_setup_lifecycle_badge")
                                    ) {
                                        Text(
                                            text = lifecycleDisplay,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CyanAccent,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }

                                    val decisionStateColor = when (aiResult.setupDecisionState.uppercase(Locale.US)) {
                                        "ENTRY_READY", "ACTIVE" -> BullishGreen
                                        "REJECTED", "INVALIDATED", "EXPIRED" -> BearishRed
                                        else -> NeutralAmber
                                    }
                                    Surface(
                                        color = decisionStateColor.copy(alpha = 0.16f),
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.testTag("setup_decision_state_badge")
                                    ) {
                                        Text(
                                            text = aiResult.setupDecisionState,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = decisionStateColor,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Setup Grade: ${aiResult.setupQualityGrade} · Regime: ${aiResult.marketRegime}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (aiResult.setupQualityGrade in listOf("A+", "A")) BullishGreen else NeutralAmber,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = if (isConfirmedLimit) "Skor teknikal" else "AI Confidence",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = "${aiResult.confidence}%",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        // Requirement #5: Mode Attribution + Selected Timeframes + Entry Order Type + Timestamp
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = aiResult.analysisModeBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (aiResult.visionAssisted) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("ai_result_vision_mode_badge")
                                )
                                if (aiResult.visionAssistedSummary.isNotBlank()) {
                                    Text(
                                        text = aiResult.visionAssistedSummary,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Strategy: ${aiResult.strategyName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyanAccent,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("ai_result_strategy_badge")
                                )
                                Text(
                                    text = "Mode: ${aiResult.modeAttributionBadge}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.testTag("ai_result_mode_attribution_badge")
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Entry Type: ${aiResult.effectiveEntryOrderType}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (aiResult.isWait) NeutralAmber else BullishGreen,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("ai_result_entry_order_type")
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val tfSummary = aiResult.selectedTimeframesSummary.ifBlank {
                                    "Primary: ${timeframe.label} · Higher: $htfLabel · Lower: $ltfLabel"
                                }
                                Text(
                                    text = tfSummary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("ai_result_timeframes_summary")
                                )
                                val tsFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
                                Text(
                                    text = "Analyzed ${tsFmt.format(Date(aiResult.analyzedAt))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("ai_last_analyzed_timestamp")
                                )
                            }
                            if (aiResult.dataValidationWarning.isNotBlank()) {
                                Text(
                                    text = "⚠ ${aiResult.dataValidationWarning}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BearishRed,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.testTag("ai_result_data_warning")
                                )
                            }
                        }

                        if (aiResult.tokenUsage != null) {
                            val usage = aiResult.tokenUsage
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ai_result_token_usage_row")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "⚡ ${aiResult.providerBadge} · ${aiResult.modelUsed}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyanAccent,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    val tokenInfo = when {
                                        usage.totalTokens != null -> "${usage.totalTokens} tokens (${usage.source.label})"
                                        usage.source == TokenUsageSource.LOCAL -> "Local Synthesis"
                                        else -> usage.source.label
                                    }
                                    Text(
                                        text = tokenInfo,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (usage.isSuccess) BullishGreen else BearishRed,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Requirement #1 & #5: Separate AI SIGNAL from BOT ACTION
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("ai_signal_vs_bot_action_banner"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "AI SIGNAL: ${aiResult.decision} ${aiResult.setupQualityGrade}",
                                style = MaterialTheme.typography.labelSmall,
                                color = decColor,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                            Text(
                                text = "BOT ACTION: $botActionSummary",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (botActionSummary.contains("WAIT") || botActionSummary.contains("BLOCK")) NeutralAmber else CyanAccent,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        if (activeSetup != null) {
                            Text(
                                text = "Lifecycle Status: ${activeSetup.statusReason}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("active_setup_status_reason")
                            )
                            if (activeSetup.hasConflict && activeSetup.conflictNote.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(NeutralAmberSoft)
                                        .border(1.dp, NeutralAmber.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .testTag("active_setup_conflict_banner")
                                ) {
                                    Text(
                                        text = activeSetup.conflictNote,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NeutralAmber,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                        // Multi-Timeframe Alignment Block (Requirements #1, #2, #12)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "MULTI-TIMEFRAME ALIGNMENT",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanAccent,
                                fontWeight = FontWeight.Bold
                            )
                            MtfAlignmentRow(
                                label = "Higher TF ($htfLabel)",
                                bias = aiResult.higherTimeframeBias,
                                detail = snapshot?.higherContext?.let {
                                    "${it.structureCode} · RSI ${it.rsi14?.let { r -> String.format(Locale.US, "%.0f", r) } ?: "--"} · Vol ${it.volumeCondition}"
                                }
                            )
                            MtfAlignmentRow(
                                label = "Primary TF (${timeframe.label.uppercase(Locale.US)})",
                                bias = aiResult.primaryTimeframeBias,
                                detail = snapshot?.primaryContext?.let {
                                    "${it.structureCode} · RSI ${it.rsi14?.let { r -> String.format(Locale.US, "%.0f", r) } ?: "--"} · Vol ${it.volumeCondition}"
                                }
                            )
                            if (timeframe.lowerTimeframe() != null) {
                                MtfAlignmentRow(
                                    label = "Lower TF ($ltfLabel)",
                                    bias = aiResult.lowerTimeframeBias,
                                    detail = snapshot?.lowerContext?.let {
                                        "${it.structureCode} · RSI ${it.rsi14?.let { r -> String.format(Locale.US, "%.0f", r) } ?: "--"} · Vol ${it.volumeCondition}"
                                    }
                                )
                            }
                        }

                        // ICT / SMC + Liquidity Sweep + Trading Session + Spread/Event Risk Validation Block
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .padding(10.dp)
                                .testTag("ai_smc_session_validation_card"),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "ICT / SMC · LIQUIDITY SWEEP · SESSION · RISK FILTERS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyanAccent,
                                    fontWeight = FontWeight.Bold
                                )
                                if (aiResult.tradingSessionBadge.isNotBlank()) {
                                    Text(
                                        text = aiResult.tradingSessionBadge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = BullishGreen,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.testTag("ai_result_session_badge")
                                    )
                                }
                            }

                            if (aiResult.tradingSessionSummary.isNotBlank()) {
                                Text(
                                    text = "Session: ${aiResult.tradingSessionSummary}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("ai_result_session_wita_clock")
                                )
                            }
                            if (aiResult.latestBosSummary.isNotBlank()) {
                                Text(
                                    text = "BOS: ${aiResult.latestBosSummary}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.testTag("ai_result_bos_summary")
                                )
                            }
                            if (aiResult.latestChochSummary.isNotBlank()) {
                                Text(
                                    text = "CHoCH: ${aiResult.latestChochSummary}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.testTag("ai_result_choch_summary")
                                )
                            }
                            if (aiResult.equalHighsLowsSummary.isNotBlank()) {
                                Text(
                                    text = "EQH / EQL: ${aiResult.equalHighsLowsSummary}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (aiResult.orderBlockCandidatesSummary.isNotBlank()) {
                                Text(
                                    text = "Order Blocks: ${aiResult.orderBlockCandidatesSummary}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (aiResult.liquiditySweepSummary.isNotBlank()) {
                                val sweepCol = when (aiResult.liquiditySweepState.uppercase(Locale.US)) {
                                    "CONFIRMED_SWEEP" -> BullishGreen
                                    "POTENTIAL_SWEEP" -> NeutralAmber
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Text(
                                    text = "Liquidity Sweep [${aiResult.liquiditySweepState}]: ${aiResult.liquiditySweepSummary}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = sweepCol,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.testTag("ai_result_liquidity_sweep_summary")
                                )
                            }
                            if (aiResult.reclaimConfirmationSummary.isNotBlank()) {
                                Text(
                                    text = "Reclaim / Confirmation: ${aiResult.reclaimConfirmationSummary}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (aiResult.spreadValidationSummary.isNotBlank()) {
                                val isSpreadFail = aiResult.spreadValidationSummary.contains("EXCEED", ignoreCase = true) ||
                                    aiResult.spreadValidationSummary.contains("BLOCK", ignoreCase = true) ||
                                    aiResult.spreadValidationSummary.contains("REJECT", ignoreCase = true)
                                Text(
                                    text = "Spread & Volatility: ${aiResult.spreadValidationSummary}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSpreadFail) BearishRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("ai_result_spread_summary")
                                )
                            }
                            if (aiResult.eventRiskSummary.isNotBlank()) {
                                Text(
                                    text = "Event Filter: ${aiResult.eventRiskSummary}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (aiResult.eventRiskSummary.contains("BLOCK", ignoreCase = true)) BearishRed else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (aiResult.isWait) {
                            // WAIT State Layout (Requirement #12)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(NeutralAmberSoft)
                                    .border(1.dp, NeutralAmber.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                    .padding(10.dp)
                                    .testTag("ai_wait_state_card"),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Reason:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = NeutralAmber,
                                    fontWeight = FontWeight.Bold
                                )
                                aiResult.reasoning.forEach { reason ->
                                    Text(
                                        text = "• $reason",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "No trade setup recommended.",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = NeutralAmber,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            // LONG / SHORT Setup Layout (Requirement #5 & #12)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                AiFieldBlock(
                                    label = "Entry (${aiResult.effectiveEntryOrderType})",
                                    value = "${formatCryptoPrice(aiResult.entryZone.low)} - ${formatCryptoPrice(aiResult.entryZone.high)}",
                                    valueColor = CyanAccent,
                                    modifier = Modifier.weight(1.25f)
                                )
                                AiFieldBlock(
                                    label = "SL",
                                    value = formatCryptoPrice(aiResult.stopLoss),
                                    valueColor = BearishRed,
                                    alignEnd = true,
                                    modifier = Modifier.weight(0.95f)
                                )
                            }

                            if (aiResult.positionSizingGuidance.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .testTag("ai_result_position_sizing_guidance")
                                ) {
                                    Text(
                                        text = "Sizing & Risk: ${aiResult.positionSizingGuidance}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            // TP1, TP2, TP3 + R:R
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Take Profit Targets",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Text(
                                        text = "R:R 1:${String.format(Locale.US, "%.1f", aiResult.riskReward)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyanAccent,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                aiResult.takeProfit.forEachIndexed { index, tpPrice ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "TP${index + 1}",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = BullishGreen,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        Text(
                                            text = formatCryptoPrice(tpPrice),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }

                            // Support & Resistance
                            val supportsToShow = aiResult.support.ifEmpty { snapshot?.supportLevels.orEmpty() }
                            val resistancesToShow = aiResult.resistance.ifEmpty { snapshot?.resistanceLevels.orEmpty() }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Support",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    if (supportsToShow.isEmpty()) {
                                        Text("--", style = MaterialTheme.typography.titleSmall, color = BullishGreen)
                                    } else {
                                        supportsToShow.take(2).forEach { s ->
                                            Text(
                                                text = formatCryptoPrice(s),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = BullishGreen,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    Text(
                                        text = "Resistance",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    if (resistancesToShow.isEmpty()) {
                                        Text("--", style = MaterialTheme.typography.titleSmall, color = BearishRed)
                                    } else {
                                        resistancesToShow.take(2).forEach { r ->
                                            Text(
                                                text = formatCryptoPrice(r),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = BearishRed,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }

                            // [ View Reasoning ] Toggle + [ Save to Journal ] + [ Simulate Setup ]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = CyanAccent.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .border(1.dp, CyanAccent.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                        .clickable { showReasoning = !showReasoning }
                                        .testTag("view_reasoning_button")
                                ) {
                                    Text(
                                        text = if (showReasoning) "[ Hide Reasoning ]" else "[ View Reasoning ]",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = CyanAccent,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }

                                OutlinedButton(
                                    onClick = onSaveSignalToJournal,
                                    enabled = !isCurrentSignalSaved,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.testTag("save_signal_journal_button")
                                ) {
                                    Text(
                                        text = if (isCurrentSignalSaved) "✓ Saved in Journal" else "Save to Journal",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isCurrentSignalSaved) BullishGreen else CyanAccent,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }

                            if (showReasoning) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Evidence & Reasoning",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyanAccent,
                                        fontWeight = FontWeight.Bold
                                    )
                                    aiResult.reasoning.forEach { r ->
                                        Text(
                                            text = "• $r",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    if (aiResult.entryTrigger.isNotBlank()) {
                                        Text(
                                            text = "Entry Trigger: ${aiResult.entryTrigger}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CyanAccent
                                        )
                                    }
                                    Text(
                                        text = "Invalidation: ${aiResult.invalidation}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = BearishRed
                                    )
                                    if (aiResult.expiryAt > 0L) {
                                        val expFmt = SimpleDateFormat("dd MMM HH:mm", Locale.US)
                                        Text(
                                            text = "Setup Expiry: ${expFmt.format(Date(aiResult.expiryAt))}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        if (aiResult.isWait) {
                            OutlinedButton(
                                onClick = onSaveSignalToJournal,
                                enabled = !isCurrentSignalSaved,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("save_wait_journal_button")
                            ) {
                                Text(
                                    text = if (isCurrentSignalSaved) "✓ WAIT Logged in Journal" else "[ Log WAIT to Signal Journal ]",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isCurrentSignalSaved) BullishGreen else NeutralAmber,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                } else {
                    MarketStructureAndLevelsCard(snapshot = snapshot)
                }

                // [ ANALYZE AGAIN ] / [ AI ANALYZE ] Button
                Button(
                    onClick = onAnalyzeClick,
                    enabled = !isAnalyzing,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanAccent,
                        contentColor = Color(0xFF001F26)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("full_ai_analyze_button")
                ) {
                    if (isAnalyzing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF001F26)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ANALYZING SETUP QUALITY...",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    } else {
                        Text(
                            text = when {
                                aiAnalysisSystem == AiAnalysisSystem.AI_TRADING_STYLE -> {
                                    "[ ANALYZE ${tradingStyleProfile.label.uppercase(Locale.US)} (${timeframe.label.uppercase(Locale.US)}) ]"
                                }
                                aiContinuousSubmode == AiContinuousSubmode.MANUAL -> {
                                    "[ ANALYZE MANUAL (${timeframe.label.uppercase(Locale.US)}) ]"
                                }
                                aiResult != null -> "[ ANALYZE AGAIN ]"
                                else -> "[ AI ANALYZE ]"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            AiPanelSection.SIMULATION -> {
                V9ReplayPanel(symbol, timeframe, snapshot,
                    paperAccountState.config.feeRateBps + paperAccountState.config.slippageBps)
                SimulationAndBacktestSection(
                    symbol = symbol,
                    timeframe = timeframe,
                    currentPrice = snapshot?.currentPrice ?: 0.0,
                    aiResult = aiResult,
                    paperAccountState = paperAccountState,
                    eventRiskAssessment = eventRiskAssessment,
                    upcomingEvents = upcomingEvents,
                    eventProtectionMode = eventProtectionMode,
                    unavailableEventPolicy = unavailableEventPolicy,
                    eventPreBufferMinutes = eventPreBufferMinutes,
                    eventPostBufferMinutes = eventPostBufferMinutes,
                    manualEventLockActive = manualEventLockActive,
                    onSetBotRunState = onSetBotRunState,
                    onApplyCustomStartingBalance = onApplyCustomStartingBalance,
                    onUpdatePaperConfig = onUpdatePaperConfig,
                    onOpenVirtualTradeFromCurrentSignal = onOpenVirtualTradeFromCurrentSignal,
                    onCloseVirtualTradeManually = onCloseVirtualTradeManually,
                    onResetPaperAccount = onResetPaperAccount,
                    onSetEventProtectionMode = onSetEventProtectionMode,
                    onSetUnavailableEventPolicy = onSetUnavailableEventPolicy,
                    onSetEventBufferMinutes = onSetEventBufferMinutes,
                    onToggleManualEventLock = onToggleManualEventLock
                )
            }

            AiPanelSection.JOURNAL -> {
                SignalJournalListSection(
                    signals = signalJournal,
                    onSelectForChart = onSelectJournalSignal
                )
            }

            AiPanelSection.PERFORMANCE -> {
                SignalPerformanceDashboardSection(
                    metrics = performanceMetrics,
                    filter = journalFilter,
                    availableSymbols = availableSymbols,
                    paperAccountState = paperAccountState,
                    onUpdateFilter = { journalFilter = it }
                )
            }
        }

        // Requirement #13 & #19: Exact Disclaimer
        Text(
            text = "AI analysis is informational and does not guarantee trading results. Simulation / Backtest Only — Historical and virtual results do not guarantee future performance.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .testTag("ai_disclaimer_text")
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiSystemSelectionCard(
    symbol: String,
    activeTimeframe: Timeframe,
    aiAnalysisSystem: AiAnalysisSystem,
    aiContinuousSubmode: AiContinuousSubmode,
    tradingStyleProfile: TradingStyleProfile,
    autoMonitorRuntimeState: AiAutoMonitorRuntimeState,
    modeSwitchStatusBanner: String?,
    lastAnalyzedAt: Long?,
    onSelectSystem: (AiAnalysisSystem) -> Unit,
    onSelectContinuousSubmode: (AiContinuousSubmode) -> Unit,
    onSelectTradingStyleProfile: (TradingStyleProfile) -> Unit,
    onSelectTimeframe: (Timeframe) -> Unit
) {
    val tsFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .premiumPanel()
            .padding(10.dp)
            .testTag("ai_system_selection_card"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PILIH SISTEM ANALISIS AI",
                style = MaterialTheme.typography.labelSmall,
                color = CyanAccent,
                fontWeight = FontWeight.Bold
            )

            // Runtime Monitoring Badge (Requirement #7)
            val runtimeColor = when (autoMonitorRuntimeState) {
                AiAutoMonitorRuntimeState.RUNNING, AiAutoMonitorRuntimeState.ACTIVE -> BullishGreen
                AiAutoMonitorRuntimeState.ANALYZING, AiAutoMonitorRuntimeState.STARTING -> CyanAccent
                AiAutoMonitorRuntimeState.STALE -> NeutralAmber
                AiAutoMonitorRuntimeState.DISCONNECTED -> BearishRed
                AiAutoMonitorRuntimeState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val runtimeText = if (aiAnalysisSystem == AiAnalysisSystem.AI_CONTINUOUS &&
                aiContinuousSubmode == AiContinuousSubmode.AUTO
            ) {
                "AUTO MONITOR: ${autoMonitorRuntimeState.badgeLabel}"
            } else {
                "AUTO MONITOR: STOPPED (MANUAL)"
            }
            Surface(
                color = runtimeColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .border(1.dp, runtimeColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .testTag("ai_auto_monitor_runtime_badge")
            ) {
                Text(
                    text = runtimeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = runtimeColor,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // Top-level Two Options: Option A (AI Continuous) | Option B (AI Gaya Trading)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AiAnalysisSystem.entries.forEach { sys ->
                val selected = aiAnalysisSystem == sys
                val testTagName = when (sys) {
                    AiAnalysisSystem.AI_CONTINUOUS -> "ai_system_option_continuous"
                    AiAnalysisSystem.AI_TRADING_STYLE -> "ai_system_option_trading_style"
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (selected) CyanAccent.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        )
                        .border(
                            1.dp,
                            if (selected) CyanAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { onSelectSystem(sys) }
                        .padding(8.dp)
                        .testTag(testTagName),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = sys.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) CyanAccent else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = sys.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        when (aiAnalysisSystem) {
            AiAnalysisSystem.AI_CONTINUOUS -> {
                // Submodes: A. Auto | B. Manual
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AiContinuousSubmode.entries.forEach { sub ->
                        val isSubSelected = aiContinuousSubmode == sub
                        val subTag = when (sub) {
                            AiContinuousSubmode.AUTO -> "ai_continuous_submode_auto"
                            AiContinuousSubmode.MANUAL -> "ai_continuous_submode_manual"
                        }
                        val subColor = when {
                            !isSubSelected -> MaterialTheme.colorScheme.onSurfaceVariant
                            sub == AiContinuousSubmode.AUTO -> BullishGreen
                            else -> CyanAccent
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isSubSelected) subColor.copy(alpha = 0.14f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                                .border(
                                    1.dp,
                                    if (isSubSelected) subColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable { onSelectContinuousSubmode(sub) }
                                .padding(8.dp)
                                .testTag(subTag),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Submode: ${sub.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = subColor,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                            Text(
                                text = sub.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            AiAnalysisSystem.AI_TRADING_STYLE -> {
                // Option B — AI Gaya Trading (Always Manual: Scalping, Day Trading, Swing Trading)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TradingStyleProfile.entries.forEach { profile ->
                            val isProfSelected = tradingStyleProfile == profile
                            val profTag = when (profile) {
                                TradingStyleProfile.SCALPING -> "trading_style_profile_scalping"
                                TradingStyleProfile.DAY_TRADING -> "trading_style_profile_day_trading"
                                TradingStyleProfile.SWING_TRADING -> "trading_style_profile_swing_trading"
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isProfSelected) CyanAccent.copy(alpha = 0.16f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    )
                                    .border(
                                        1.dp,
                                        if (isProfSelected) CyanAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { onSelectTradingStyleProfile(profile) }
                                    .padding(7.dp)
                                    .testTag(profTag),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = profile.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isProfSelected) CyanAccent else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = "Entry: ${profile.entryTimeframeRangeLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isProfSelected) BullishGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = "HTF: ${profile.higherTimeframeContextLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    // Profile Details + Manual Timeframe Adjustment Row
                    val resolvedHtf = tradingStyleProfile.resolveHigherTimeframe(activeTimeframe)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(8.dp)
                            .testTag("trading_style_profile_detail_box"),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "${tradingStyleProfile.label} Profile · Default TF ${tradingStyleProfile.entryTimeframeRangeLabel} · Context HTF ${tradingStyleProfile.higherTimeframeContextLabel} (Active: ${activeTimeframe.label} / HTF ${resolvedHtf.label})",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanAccent,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tradingStyleProfile.focusSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Entry TF:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            listOf(Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.H1, Timeframe.H4).forEach { tfOption ->
                                val isRecommended = tfOption in tradingStyleProfile.recommendedEntryTimeframes
                                val isSelectedTf = tfOption == activeTimeframe
                                Surface(
                                    color = when {
                                        isSelectedTf -> CyanAccent.copy(alpha = 0.22f)
                                        isRecommended -> BullishGreen.copy(alpha = 0.12f)
                                        else -> MaterialTheme.colorScheme.surface
                                    },
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier
                                        .border(
                                            1.dp,
                                            when {
                                                isSelectedTf -> CyanAccent
                                                isRecommended -> BullishGreen.copy(alpha = 0.5f)
                                                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                            },
                                            RoundedCornerShape(4.dp)
                                        )
                                        .clickable { onSelectTimeframe(tfOption) }
                                        .testTag("ai_profile_tf_${tfOption.binanceInterval}")
                                ) {
                                    Text(
                                        text = if (isRecommended) "${tfOption.label} ★" else tfOption.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelectedTf) CyanAccent else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (isSelectedTf || isRecommended) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!modeSwitchStatusBanner.isNullOrBlank()) {
            Text(
                text = modeSwitchStatusBanner,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("ai_mode_switch_banner")
            )
        } else if (lastAnalyzedAt != null && lastAnalyzedAt > 0L) {
            Text(
                text = "Last Analysis (${formatSymbolPair(symbol)}): ${tsFmt.format(Date(lastAnalyzedAt))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MtfAlignmentRow(
    label: String,
    bias: String,
    detail: String?
) {
    val biasCol = colorForBiasText(bias)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = bias.uppercase(Locale.US),
            style = MaterialTheme.typography.labelMedium,
            color = biasCol,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun AiFieldBlock(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
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
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = valueColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MarketAssessmentSummaryCard(
    assessment: MarketAssessmentState,
    hasActiveSetup: Boolean
) {
    val biasColor = colorForBiasText(assessment.bias)
    val dataColor = when {
        !assessment.isDataValidForEntry -> BearishRed
        assessment.dataStatusLabel.equals("OPEN", ignoreCase = true) -> BullishGreen
        else -> NeutralAmber
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .premiumPanel()
            .padding(10.dp)
            .testTag("market_assessment_card"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "MARKET ASSESSMENT",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    color = CyanAccent.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = if (assessment.isIntrabarEvaluation) "INTRABAR LIVE" else "CANDLE CLOSE",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            Surface(
                color = dataColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.testTag("market_assessment_data_badge")
            ) {
                Text(
                    text = "FEED: ${assessment.dataStatusLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = dataColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            CompactMetricColumn(
                label = "Market Bias",
                value = assessment.bias,
                valueColor = biasColor,
                modifier = Modifier.weight(1f)
            )
            CompactMetricColumn(
                label = "Regime",
                value = assessment.marketRegime,
                valueColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            CompactMetricColumn(
                label = "Monitor State",
                value = if (hasActiveSetup && assessment.latestDecision == "WAIT") {
                    "PROTECTED"
                } else {
                    assessment.scanLifecycleState.displayLabel
                },
                valueColor = if (hasActiveSetup) BullishGreen else CyanAccent,
                alignEnd = true,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun resolveDecisionVisuals(decisionOrBias: String): Triple<String, Color, Color> {
    return when (decisionOrBias.uppercase(Locale.US)) {
        "LONG", "BULLISH" -> Triple("🟢 LONG", BullishGreen, BullishGreenSoft)
        "SHORT", "BEARISH" -> Triple("🔴 SHORT", BearishRed, BearishRedSoft)
        "BUY" -> Triple("🟢 BUY", BullishGreen, BullishGreenSoft)
        "SELL" -> Triple("🔴 SELL", BearishRed, BearishRedSoft)
        else -> Triple("🟡 WAIT", NeutralAmber, NeutralAmberSoft)
    }
}

private fun colorForBiasText(bias: String): Color {
    return when (bias.uppercase(Locale.US)) {
        "BULLISH", "LONG", "UPTREND" -> BullishGreen
        "BEARISH", "SHORT", "DOWNTREND" -> BearishRed
        else -> NeutralAmber
    }
}
