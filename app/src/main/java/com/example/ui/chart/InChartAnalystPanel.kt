package com.example.ui.chart

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.example.ai.strategy.AiStrategy
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.example.ai.usage.TokenUsageSource
import com.example.analysis.signal.ActiveTradeSetup
import com.example.analysis.signal.MarketAssessmentState
import com.example.analysis.signal.SetupLifecycleState
import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.market.candle.Timeframe
import com.example.market.candle.formatCryptoPrice
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BearishRedSoft
import com.example.ui.theme.BullishGreen
import com.example.ui.theme.BullishGreenSoft
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.NeutralAmber
import com.example.ui.theme.NeutralAmberSoft
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-Chart Analysis Panel
 *
 * Opens as a Material 3 [ModalBottomSheet] directly over the Chart screen without navigating away.
 * Displays real-time / cached AI insight, technical reasoning, bias, heuristic confidence,
 * validated trade levels (only for actionable setups), and provides an explicit "Analyze Now" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InChartAnalystPanel(
    symbol: String,
    timeframe: Timeframe,
    snapshot: MarketTechnicalSnapshot?,
    aiResult: AiAnalysisResult?,
    isAnalyzing: Boolean,
    errorMessage: String?,
    providerBadge: String,
    marketAssessment: MarketAssessmentState? = null,
    activeSetup: ActiveTradeSetup? = null,
    selectedStrategy: AiStrategy = AiStrategy.AI_HYBRID,
    onSelectStrategy: (AiStrategy) -> Unit = {},
    trackedSetups: List<com.example.ai.tracker.TrackedPendingSetup> = emptyList(),
    onStartTracking: ((com.example.ai.plan.StructuredTradePlan) -> Unit)? = null,
    onStopTracking: ((String) -> Unit)? = null,
    onAnalyzeNow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF101522),
        scrimColor = Color.Black.copy(alpha = 0.65f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFF334155)) },
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = modifier.testTag("in_chart_analyst_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Header with Symbol, Timeframe, and Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Analyst Chart",
                            style = MaterialTheme.typography.titleLarge,
                            color = CyanAccent,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("analyst_chart_panel_title")
                        )
                        Surface(
                            color = CyanAccent.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "IN-CHART",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanAccent,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = "$symbol · ${timeframe.label.uppercase(Locale.US)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("analyst_chart_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Analyst Chart",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 2. AI Strategy Hub Selector (V3.0A)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_strategy_hub_selector")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = CyanAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "AI Strategy Hub",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("strategy_hub_title")
                            )
                        }
                        Surface(
                            color = CyanAccent.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = selectedStrategy.shortBadge.uppercase(Locale.US),
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanAccent,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .testTag("selected_strategy_badge")
                            )
                        }
                    }

                    // Horizontal scrollable chip row showing all 8 strategies
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .testTag("strategy_chips_row"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AiStrategy.entries.forEach { strategy ->
                            val isSelected = strategy == selectedStrategy
                            Surface(
                                onClick = { onSelectStrategy(strategy) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) CyanAccent.copy(alpha = 0.18f) else Color(0xFF1E293B),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) CyanAccent else Color(0xFF334155)
                                ),
                                modifier = Modifier.testTag("strategy_chip_${strategy.id.lowercase(Locale.US)}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .background(CyanAccent, CircleShape)
                                        )
                                    }
                                    Text(
                                        text = strategy.displayName,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Selected Strategy Focus & Description Card
                    Surface(
                        color = Color(0xFF0F172A),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().testTag("strategy_description_card")
                    ) {
                        Text(
                            text = selectedStrategy.shortDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // 3. Action Bar: Explicit "Analyze Now"
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(
                            text = "${selectedStrategy.displayName} Analysis",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isAnalyzing) "Analyzing with ${selectedStrategy.displayName}..." else "Trigger institutional ${selectedStrategy.displayName} analysis for $symbol",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = onAnalyzeNow,
                        enabled = !isAnalyzing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanAccent,
                            contentColor = Color(0xFF001F26),
                            disabledContainerColor = CyanAccent.copy(alpha = 0.4f),
                            disabledContentColor = Color(0xFF001F26).copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("analyze_now_button")
                    ) {
                        if (isAnalyzing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF001F26)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = if (isAnalyzing) "Analyzing..." else "Analyze Now",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 3. Error Banner (if any)
            if (!errorMessage.isNullOrBlank()) {
                Surface(
                    color = BearishRedSoft,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, BearishRed.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = BearishRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = BearishRed,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 4. Main Body: Analysis Result or Empty State
            val effectiveResult = activeSetup?.sourceAiResult ?: aiResult

            if (effectiveResult != null) {
                // 4. Resolve Effective Structured Trade Plan & Outdated Status (V3.1B)
                val effectiveTradePlan = remember(effectiveResult, symbol, timeframe, snapshot) {
                    resolveEffectiveTradePlan(effectiveResult, symbol, timeframe, snapshot)
                }

                val isMatchingStrategy = effectiveResult.strategyId.isBlank() ||
                    effectiveResult.strategyId.equals(selectedStrategy.id, ignoreCase = true)
                val planSymbol = effectiveTradePlan?.symbol ?: ""
                val isMatchingSymbol = planSymbol.isBlank() || planSymbol.equals(symbol, ignoreCase = true)
                val planTf = effectiveTradePlan?.timeframe ?: ""
                val isMatchingTf = planTf.isBlank() || planTf.equals(timeframe.label, ignoreCase = true)
                val isAnalysisOutdated = !isMatchingStrategy || !isMatchingSymbol || !isMatchingTf

                // Outdated Analysis Alert Banner (V3.1B)
                if (isAnalysisOutdated) {
                    Surface(
                        color = Color(0xFF1E293B),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, NeutralAmber.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("outdated_analysis_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = NeutralAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Column {
                                Text(
                                    text = "OUTDATED CACHED PLAN",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeutralAmber,
                                    fontWeight = FontWeight.Bold
                                )
                                val reason = when {
                                    !isMatchingStrategy -> "Strategy changed to ${selectedStrategy.displayName} (cached: ${effectiveResult.strategyName})"
                                    !isMatchingSymbol -> "Active symbol changed to $symbol (cached: $planSymbol)"
                                    else -> "Timeframe changed to ${timeframe.label} (cached: $planTf)"
                                }
                                Text(
                                    text = "$reason. Tap 'Analyze Now' above to generate fresh trade plan.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }

                // Advanced Trade Plan Card (V3.1B & V3.2)
                if (effectiveTradePlan != null) {
                    val isPlanTracked = remember(trackedSetups, effectiveTradePlan) {
                        trackedSetups.any {
                            it.symbol.equals(effectiveTradePlan.symbol, ignoreCase = true) &&
                                it.timeframe.equals(effectiveTradePlan.timeframe, ignoreCase = true) &&
                                it.strategyId.equals(effectiveTradePlan.strategyId, ignoreCase = true) &&
                                !it.isTerminal
                        }
                    }

                    AdvancedTradePlanCard(
                        plan = effectiveTradePlan,
                        aiResult = effectiveResult,
                        isOutdated = isAnalysisOutdated,
                        selectedStrategy = selectedStrategy,
                        currentSymbol = symbol,
                        currentTimeframe = timeframe,
                        isCurrentlyTracked = isPlanTracked,
                        onStartTracking = onStartTracking,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 7. Multi-Timeframe Alignment
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth().testTag("analyst_mtf_card")
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "MULTI-TIMEFRAME ALIGNMENT",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanAccent,
                            fontWeight = FontWeight.Bold
                        )

                        val htfLabel = snapshot?.higherContext?.timeframe?.label
                            ?: timeframe.higherTimeframe()?.label
                            ?: "HTF"
                        val ptfLabel = timeframe.label.uppercase(Locale.US)

                        MtfRow(
                            timeframe = "Higher TF ($htfLabel)",
                            bias = effectiveResult.higherTimeframeBias
                        )
                        MtfRow(
                            timeframe = "Primary TF ($ptfLabel)",
                            bias = effectiveResult.primaryTimeframeBias
                        )
                        if (effectiveResult.lowerTimeframeBias.isNotBlank()) {
                            val ltfLabel = snapshot?.lowerContext?.timeframe?.label
                                ?: timeframe.lowerTimeframe()?.label
                                ?: "LTF"
                            MtfRow(
                                timeframe = "Lower TF ($ltfLabel)",
                                bias = effectiveResult.lowerTimeframeBias
                            )
                        }
                    }
                }

                // 8. Institutional SMC & Risk Filters
                if (effectiveResult.latestBosSummary.isNotBlank() ||
                    effectiveResult.latestChochSummary.isNotBlank() ||
                    effectiveResult.liquiditySweepSummary.isNotBlank() ||
                    effectiveResult.tradingSessionSummary.isNotBlank() ||
                    effectiveResult.spreadValidationSummary.isNotBlank()
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "ICT / SMC & RISK FILTERS",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanAccent,
                                fontWeight = FontWeight.Bold
                            )

                            if (effectiveResult.latestBosSummary.isNotBlank()) {
                                SmcItemRow(label = "BOS", detail = effectiveResult.latestBosSummary)
                            }
                            if (effectiveResult.latestChochSummary.isNotBlank()) {
                                SmcItemRow(label = "CHoCH", detail = effectiveResult.latestChochSummary)
                            }
                            if (effectiveResult.liquiditySweepSummary.isNotBlank()) {
                                SmcItemRow(label = "Liquidity Sweep", detail = effectiveResult.liquiditySweepSummary)
                            }
                            if (effectiveResult.tradingSessionSummary.isNotBlank()) {
                                SmcItemRow(label = "Session", detail = effectiveResult.tradingSessionSummary)
                            }
                            if (effectiveResult.spreadValidationSummary.isNotBlank()) {
                                SmcItemRow(label = "Spread Validation", detail = effectiveResult.spreadValidationSummary)
                            }
                        }
                    }
                }

                // 9. Provider & Token Usage Info
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⚡ ${effectiveResult.providerBadge} · ${effectiveResult.modelUsed}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanAccent
                        )

                        val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
                        Text(
                            text = "Analyzed ${timeFmt.format(Date(effectiveResult.analyzedAt))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (!isAnalyzing) {
                // Empty state when no analysis is cached
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth().testTag("analyst_empty_state_card")
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = CyanAccent,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "No Analysis Cached for $symbol",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Tap 'Analyze Now' above to run real-time institutional SMC/ICT multi-timeframe analysis.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            // 8. Tracked Pending Setups Section (V3.2 Smart Pending Setup Tracker)
            TrackedSetupsSection(
                trackedSetups = trackedSetups,
                activeSymbol = symbol,
                activeTimeframe = timeframe.label,
                onStopTracking = { setupId -> onStopTracking?.invoke(setupId) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun MtfRow(
    timeframe: String,
    bias: String
) {
    val biasColor = when (bias.uppercase(Locale.US)) {
        "BULLISH", "LONG", "UPTREND" -> BullishGreen
        "BEARISH", "SHORT", "DOWNTREND" -> BearishRed
        else -> NeutralAmber
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = timeframe,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Surface(
            color = biasColor.copy(alpha = 0.15f),
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = bias.uppercase(Locale.US),
                style = MaterialTheme.typography.labelSmall,
                color = biasColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun SmcItemRow(
    label: String,
    detail: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
