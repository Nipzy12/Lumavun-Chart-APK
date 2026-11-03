package com.example.ui.chart

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.sp
import com.example.ai.parser.AiAnalysisResult
import com.example.ai.plan.BrokerVerificationStatus
import com.example.ai.plan.StructuredTradePlan
import com.example.ai.plan.TradeDirection
import com.example.ai.plan.TradeOrderType
import com.example.ai.plan.TradeSetupStatus
import com.example.ai.strategy.AiStrategy
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
 * Visual styling configuration for a trade plan status.
 */
data class TradePlanVisualStatus(
    val title: String,
    val subtitle: String,
    val primaryColor: Color,
    val backgroundColor: Color,
    val borderColor: Color,
    val executionNotice: String,
    val isPending: Boolean,
    val isActionable: Boolean,
    val isWait: Boolean,
    val isInvalid: Boolean
)

/**
 * Resolves the visual status attributes for a given trade plan.
 */
fun resolveTradePlanVisualStatus(plan: StructuredTradePlan): TradePlanVisualStatus {
    return when (plan.setupStatus) {
        TradeSetupStatus.INVALID -> TradePlanVisualStatus(
            title = "⚠️ SETUP REJECTED",
            subtitle = "FAILED RISK GUARDIAN CHECKS",
            primaryColor = BearishRed,
            backgroundColor = BearishRedSoft,
            borderColor = BearishRed.copy(alpha = 0.6f),
            executionNotice = "INVALID SETUP · Failed deterministic safety validation checks. Do not execute.",
            isPending = false,
            isActionable = false,
            isWait = false,
            isInvalid = true
        )
        TradeSetupStatus.WAIT -> TradePlanVisualStatus(
            title = "🟡 NO ACTION (WAIT)",
            subtitle = "CAPITAL PRESERVATION",
            primaryColor = NeutralAmber,
            backgroundColor = NeutralAmberSoft,
            borderColor = NeutralAmber.copy(alpha = 0.5f),
            executionNotice = "NO ACTION · Market structure does not satisfy confluence criteria. Capital preservation prioritized.",
            isPending = false,
            isActionable = false,
            isWait = true,
            isInvalid = false
        )
        TradeSetupStatus.ACTIONABLE -> {
            if (plan.direction == TradeDirection.BUY) {
                TradePlanVisualStatus(
                    title = "🟢 BUY MARKET",
                    subtitle = "ACTIONABLE (IMMEDIATE)",
                    primaryColor = BullishGreen,
                    backgroundColor = BullishGreenSoft,
                    borderColor = BullishGreen.copy(alpha = 0.6f),
                    executionNotice = "ACTIONABLE MARKET SETUP · Requires manual user confirmation prior to broker execution.",
                    isPending = false,
                    isActionable = true,
                    isWait = false,
                    isInvalid = false
                )
            } else {
                TradePlanVisualStatus(
                    title = "🔴 SELL MARKET",
                    subtitle = "ACTIONABLE (IMMEDIATE)",
                    primaryColor = BearishRed,
                    backgroundColor = BearishRedSoft,
                    borderColor = BearishRed.copy(alpha = 0.6f),
                    executionNotice = "ACTIONABLE MARKET SETUP · Requires manual user confirmation prior to broker execution.",
                    isPending = false,
                    isActionable = true,
                    isWait = false,
                    isInvalid = false
                )
            }
        }
        TradeSetupStatus.PENDING -> {
            when (plan.orderType) {
                TradeOrderType.LIMIT -> {
                    if (plan.direction == TradeDirection.BUY) {
                        TradePlanVisualStatus(
                            title = "🔵 BUY LIMIT",
                            subtitle = "PENDING ORDER (PULLBACK)",
                            primaryColor = CyanAccent,
                            backgroundColor = CyanAccent.copy(alpha = 0.12f),
                            borderColor = CyanAccent.copy(alpha = 0.5f),
                            executionNotice = "PROPOSED PENDING LIMIT ORDER · Not executed. Awaiting price retrace to buy entry zone.",
                            isPending = true,
                            isActionable = false,
                            isWait = false,
                            isInvalid = false
                        )
                    } else {
                        TradePlanVisualStatus(
                            title = "🟠 SELL LIMIT",
                            subtitle = "PENDING ORDER (RALLY)",
                            primaryColor = NeutralAmber,
                            backgroundColor = NeutralAmberSoft,
                            borderColor = NeutralAmber.copy(alpha = 0.5f),
                            executionNotice = "PROPOSED PENDING LIMIT ORDER · Not executed. Awaiting price retrace to sell entry zone.",
                            isPending = true,
                            isActionable = false,
                            isWait = false,
                            isInvalid = false
                        )
                    }
                }
                TradeOrderType.STOP -> {
                    val label = if (plan.direction == TradeDirection.BUY) "🟣 BUY STOP" else "🟣 SELL STOP"
                    val sub = if (plan.direction == TradeDirection.BUY) "PENDING BREAKOUT" else "PENDING BREAKDOWN"
                    val desc = if (plan.direction == TradeDirection.BUY) "breakout above" else "breakdown below"
                    TradePlanVisualStatus(
                        title = label,
                        subtitle = sub,
                        primaryColor = Color(0xFFC084FC),
                        backgroundColor = Color(0xFF2E1065).copy(alpha = 0.45f),
                        borderColor = Color(0xFFC084FC).copy(alpha = 0.6f),
                        executionNotice = "PROPOSED PENDING STOP ORDER · Not executed. Awaiting confirmed $desc trigger level.",
                        isPending = true,
                        isActionable = false,
                        isWait = false,
                        isInvalid = false
                    )
                }
                else -> TradePlanVisualStatus(
                    title = "🟡 WAIT",
                    subtitle = "NO ORDER",
                    primaryColor = NeutralAmber,
                    backgroundColor = NeutralAmberSoft,
                    borderColor = NeutralAmber.copy(alpha = 0.5f),
                    executionNotice = "NO ACTION · Market criteria not met.",
                    isPending = false,
                    isActionable = false,
                    isWait = true,
                    isInvalid = false
                )
            }
        }
    }
}

/**
 * Resolves a non-null [StructuredTradePlan] from either the structured field or synthesizes
 * one from legacy [AiAnalysisResult] properties to ensure 100% backward compatibility.
 */
fun resolveEffectiveTradePlan(
    result: AiAnalysisResult?,
    symbol: String,
    timeframe: Timeframe,
    snapshot: MarketTechnicalSnapshot?
): StructuredTradePlan? {
    if (result == null) return null
    if (result.tradePlan != null) return result.tradePlan

    val isWait = result.isWait
    val dir = when (result.decision.uppercase(Locale.US)) {
        "LONG", "BUY" -> TradeDirection.BUY
        "SHORT", "SELL" -> TradeDirection.SELL
        else -> TradeDirection.NEUTRAL
    }
    val orderType = when {
        isWait || dir == TradeDirection.NEUTRAL -> TradeOrderType.NONE
        result.entryOrderType.equals("MARKET", ignoreCase = true) -> TradeOrderType.MARKET
        result.entryOrderType.equals("STOP", ignoreCase = true) -> TradeOrderType.STOP
        else -> TradeOrderType.LIMIT
    }
    val status = when {
        isWait || dir == TradeDirection.NEUTRAL -> TradeSetupStatus.WAIT
        orderType == TradeOrderType.MARKET -> TradeSetupStatus.ACTIONABLE
        else -> TradeSetupStatus.PENDING
    }
    val refP = snapshot?.currentPrice ?: ((result.entryZone.low + result.entryZone.high) / 2.0)
    return StructuredTradePlan(
        strategy = AiStrategy.fromId(result.strategyId),
        strategyId = result.strategyId,
        strategyName = result.strategyName,
        symbol = symbol,
        timeframe = timeframe.label,
        marketBias = result.bias,
        direction = dir,
        orderType = orderType,
        setupStatus = status,
        referencePrice = refP,
        referenceTimestamp = result.analyzedAt,
        entryZoneMin = if (isWait) null else result.entryZone.low,
        entryZoneMax = if (isWait) null else result.entryZone.high,
        stopLoss = if (isWait) null else result.stopLoss,
        tp1 = if (isWait) null else result.takeProfit.firstOrNull(),
        tp2 = if (isWait) null else result.takeProfit.getOrNull(1),
        tp3 = if (isWait) null else result.takeProfit.getOrNull(2),
        riskRewardTp1 = if (isWait) null else result.riskReward,
        setupRationale = result.summary,
        supportingEvidence = result.reasoning,
        contradictingEvidence = if (result.dataValidationWarning.isNotBlank()) listOf(result.dataValidationWarning) else emptyList(),
        invalidationCondition = result.invalidIf,
        dataFreshnessInfo = if (result.spreadValidationSummary.isNotBlank()) result.spreadValidationSummary else "Real-time Market Feed",
        brokerVerificationStatus = BrokerVerificationStatus.UNVERIFIED_BROKER_DATA,
        validationMessages = emptyList()
    )
}

/**
 * LUMAVUN Chart — AI Insight V3.1B: Advanced Trade Plan Card.
 *
 * Professional, high-density trade plan component displaying:
 * - Selected AI Strategy, Symbol, and Timeframe
 * - Market Bias clearly separated from Trade Direction
 * - Proposed Order: BUY/SELL MARKET, BUY/SELL LIMIT, BUY/SELL STOP, or WAIT
 * - Setup Status: ACTIONABLE, PENDING, WAIT, INVALID
 * - Reference Price and Analysis Timestamp
 * - Entry Zone, Stop Loss, TP1, TP2, TP3, Risk/Reward Ratios
 * - Expandable "Why this setup?" (Evidence & Rationale)
 * - Expandable "When is this setup invalid?" (Invalidation & Constraints)
 * - Strict non-executed pending warning banner (never claiming broker execution)
 */
@Composable
fun AdvancedTradePlanCard(
    plan: StructuredTradePlan,
    aiResult: AiAnalysisResult,
    isOutdated: Boolean = false,
    selectedStrategy: AiStrategy = AiStrategy.AI_HYBRID,
    currentSymbol: String = plan.symbol,
    currentTimeframe: Timeframe = Timeframe.M15,
    isCurrentlyTracked: Boolean = false,
    onStartTracking: ((StructuredTradePlan) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val visualStatus = remember(plan) { resolveTradePlanVisualStatus(plan) }
    var whySetupExpanded by remember { mutableStateOf(true) }
    var invalidationExpanded by remember { mutableStateOf(true) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag("analyst_decision_card")
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, visualStatus.borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("advanced_trade_plan_card")
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Order Recommendation & Setup Status Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(visualStatus.backgroundColor)
                                .border(1.dp, visualStatus.borderColor, RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = visualStatus.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = visualStatus.primaryColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("trade_plan_order_badge")
                            )
                        }

                        Surface(
                            color = visualStatus.primaryColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = visualStatus.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = visualStatus.primaryColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                                    .testTag("trade_plan_status_badge")
                            )
                        }
                    }

                    // Confidence / Heuristic Score
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Heuristic Score",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Box(modifier = Modifier.testTag("analyst_confidence_score")) {
                            Text(
                                text = "${aiResult.confidence}%",
                                style = MaterialTheme.typography.titleLarge,
                                color = CyanAccent,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("trade_plan_confidence_score")
                            )
                        }
                    }
                }

            // 2. Metadata Bar: Strategy, Market Bias, Symbol, Timeframe, Reference Price
            Surface(
                color = Color(0xFF0F172A),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Strategy Badge
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                color = CyanAccent.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = plan.strategyName.ifBlank { selectedStrategy.displayName },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyanAccent,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }

                            Text(
                                text = "${plan.symbol.ifBlank { currentSymbol }} · ${plan.timeframe.ifBlank { currentTimeframe.label }.uppercase(Locale.US)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Market Bias (Explicitly separated from Trade Recommendation)
                        val biasColor = when (plan.marketBias.trim().uppercase(Locale.US)) {
                            "BULLISH", "LONG" -> BullishGreen
                            "BEARISH", "SHORT" -> BearishRed
                            else -> NeutralAmber
                        }
                        Surface(
                            color = biasColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, biasColor.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = "BIAS: ${plan.marketBias.uppercase(Locale.US)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = biasColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .testTag("trade_plan_bias_badge")
                            )
                        }
                    }

                    // Reference Price & Timestamp
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val refText = if (plan.referencePrice > 0.0) formatCryptoPrice(plan.referencePrice) else "Not available"
                        Text(
                            text = "Ref Price: $refText",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
                        val timeStr = timeFmt.format(Date(plan.referenceTimestamp))
                        Text(
                            text = "Timestamp: $timeStr",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 3. Execution Safeguard & Broker Status Notice
            Surface(
                color = visualStatus.backgroundColor,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, visualStatus.borderColor.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("trade_plan_execution_notice")
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (visualStatus.isInvalid) Icons.Default.Warning else Icons.Default.Info,
                        contentDescription = null,
                        tint = visualStatus.primaryColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = visualStatus.executionNotice,
                        style = MaterialTheme.typography.bodySmall,
                        color = visualStatus.primaryColor,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            // 4. Entry Zone, Stop Loss, and Take Profit Levels Grid
            TradePlanLevelsGrid(
                plan = plan,
                visualStatus = visualStatus
            )

            // 5. Expandable Section: "Why this setup?" (Evidence & Rationale)
            ExpandableEvidenceSection(
                title = "Why this setup?",
                isExpanded = whySetupExpanded,
                onToggle = { whySetupExpanded = !whySetupExpanded },
                rationale = plan.setupRationale.ifBlank { aiResult.summary },
                supportingEvidence = plan.supportingEvidence.ifEmpty { aiResult.reasoning },
                contradictingEvidence = plan.contradictingEvidence,
                testTagPrefix = "why_setup"
            )

            // 6. Expandable Section: "When is this setup invalid?" (Invalidation & Constraints)
            ExpandableInvalidationSection(
                title = "When is this setup invalid?",
                isExpanded = invalidationExpanded,
                onToggle = { invalidationExpanded = !invalidationExpanded },
                invalidationCondition = plan.invalidationCondition.ifBlank { aiResult.invalidIf },
                validationMessages = plan.validationMessages,
                dataFreshnessInfo = plan.dataFreshnessInfo,
                brokerStatus = plan.brokerVerificationStatus,
                testTagPrefix = "invalidation"
            )

            // 7. Track Pending Setup Action Button (V3.2 Smart Pending Setup Tracker)
            if (!plan.isWaitOrInvalid && onStartTracking != null) {
                Button(
                    onClick = { onStartTracking(plan) },
                    enabled = !isCurrentlyTracked,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCurrentlyTracked) Color(0xFF1E293B) else visualStatus.primaryColor,
                        contentColor = if (isCurrentlyTracked) CyanAccent else Color(0xFF001F26),
                        disabledContainerColor = Color(0xFF1E293B),
                        disabledContentColor = CyanAccent
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("track_trade_plan_button")
                ) {
                    Icon(
                        imageVector = if (isCurrentlyTracked) Icons.Default.CheckCircle else Icons.Default.Timeline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isCurrentlyTracked) "✓ Tracking Active (Deterministic Monitor)" else "Track This Setup (Deterministic)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
}

/**
 * Reusable compact layout for entry zone, stop loss, and take-profit targets.
 * Cleanly handles nullable TP2/TP3, validated R:R, and WAIT/INVALID withholding.
 */
@Composable
fun TradePlanLevelsGrid(
    plan: StructuredTradePlan,
    visualStatus: TradePlanVisualStatus,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF0F172A),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("trade_plan_levels_grid")
    ) {
        if (visualStatus.isWait || visualStatus.isInvalid) {
            // Explain why no levels are shown for WAIT or INVALID
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = if (visualStatus.isWait) "LEVELS WITHHELD (WAIT)" else "LEVELS REVOKED (INVALID)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = visualStatus.primaryColor
                )
                Text(
                    text = if (visualStatus.isWait) {
                        "Entry zone, stop loss, and take-profit targets are withheld for WAIT setups to prevent premature or unvalidated order placement."
                    } else {
                        "Proposed trade levels failed safety verification checks (geometry, R:R ratio, or spread) and have been revoked."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        } else {
            // Actionable or Pending setup with validated levels
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ORDER PARAMETERS (${plan.orderType.code})",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (plan.isPending) "Awaiting Entry Trigger" else "Actionable Now",
                        style = MaterialTheme.typography.labelSmall,
                        color = visualStatus.primaryColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Row 1: Entry Zone, Stop Loss, TP1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val entryText = if (plan.entryZoneMin != null && plan.entryZoneMax != null) {
                        "${formatCryptoPrice(plan.entryZoneMin)} – ${formatCryptoPrice(plan.entryZoneMax)}"
                    } else "Not available"

                    TradePlanMetricItem(
                        label = "Entry Zone (${plan.orderType.code})",
                        value = entryText,
                        color = CyanAccent,
                        testTag = "trade_plan_entry_zone",
                        modifier = Modifier.weight(1.3f)
                    )

                    val slText = plan.stopLoss?.let { formatCryptoPrice(it) } ?: "Not available"
                    TradePlanMetricItem(
                        label = "Stop Loss",
                        value = slText,
                        color = BearishRed,
                        testTag = "trade_plan_stop_loss",
                        modifier = Modifier.weight(0.9f)
                    )

                    val tp1Text = plan.tp1?.let { formatCryptoPrice(it) } ?: "—"
                    TradePlanMetricItem(
                        label = "TP1 Target",
                        value = tp1Text,
                        color = BullishGreen,
                        alignEnd = true,
                        testTag = "trade_plan_tp1",
                        modifier = Modifier.weight(0.9f)
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                // Row 2: TP2, TP3, Risk/Reward
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val tp2Text = plan.tp2?.let { formatCryptoPrice(it) } ?: "—"
                    TradePlanMetricItem(
                        label = "TP2 Target",
                        value = tp2Text,
                        color = BullishGreen,
                        testTag = "trade_plan_tp2",
                        modifier = Modifier.weight(1f)
                    )

                    val tp3Text = plan.tp3?.let { formatCryptoPrice(it) } ?: "—"
                    TradePlanMetricItem(
                        label = "TP3 Target",
                        value = tp3Text,
                        color = BullishGreen,
                        testTag = "trade_plan_tp3",
                        modifier = Modifier.weight(1f)
                    )

                    val rrText = if (plan.riskRewardTp1 != null && plan.riskRewardTp1 > 0.0) {
                        "1:${String.format(Locale.US, "%.2f", plan.riskRewardTp1)}"
                    } else "—"

                    TradePlanMetricItem(
                        label = "Risk / Reward",
                        value = rrText,
                        color = MaterialTheme.colorScheme.onSurface,
                        alignEnd = true,
                        testTag = "trade_plan_risk_reward",
                        modifier = Modifier.weight(1f)
                    )
                }

                // Broker constraint notice
                Text(
                    text = "⚠️ ${plan.brokerVerificationStatus.displayLabel}. Analytical calculation based on candle close; live broker execution unverified.",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun TradePlanMetricItem(
    label: String,
    value: String,
    color: Color,
    testTag: String,
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
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(testTag)
        )
    }
}

/**
 * Expandable section for setup evidence and rationale.
 */
@Composable
fun ExpandableEvidenceSection(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    rationale: String,
    supportingEvidence: List<String>,
    contradictingEvidence: List<String>,
    testTagPrefix: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF0F172A),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("${testTagPrefix}_section")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .testTag("${testTagPrefix}_toggle"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = CyanAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Strategy Rationale
                    if (rationale.isNotBlank()) {
                        Text(
                            text = rationale,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 16.sp,
                            modifier = Modifier.testTag("${testTagPrefix}_rationale")
                        )
                    }

                    // Supporting Evidence
                    if (supportingEvidence.isNotEmpty()) {
                        Text(
                            text = "Supporting Technical Evidence:",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanAccent,
                            fontWeight = FontWeight.Bold
                        )
                        supportingEvidence.forEach { point ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CyanAccent,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = point,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }

                    // Contradicting Evidence / Caveats (if any)
                    if (contradictingEvidence.isNotEmpty()) {
                        Surface(
                            color = NeutralAmberSoft,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("${testTagPrefix}_contradicting")
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.WarningAmber,
                                        contentDescription = null,
                                        tint = NeutralAmber,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "Contradicting Evidence & Caveats:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NeutralAmber,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                contradictingEvidence.forEach { caveat ->
                                    Text(
                                        text = "• $caveat",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Expandable section for invalidation criteria and risk constraints.
 */
@Composable
fun ExpandableInvalidationSection(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    invalidationCondition: String,
    validationMessages: List<String>,
    dataFreshnessInfo: String,
    brokerStatus: BrokerVerificationStatus,
    testTagPrefix: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF0F172A),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("${testTagPrefix}_section")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .testTag("${testTagPrefix}_toggle"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = BearishRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Explicit Invalidation Condition
                    if (invalidationCondition.isNotBlank()) {
                        Surface(
                            color = BearishRedSoft,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("${testTagPrefix}_condition_card")
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = "Invalidation Condition:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BearishRed,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = invalidationCondition,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BearishRed,
                                    lineHeight = 15.sp,
                                    modifier = Modifier.testTag("${testTagPrefix}_condition_text")
                                )
                            }
                        }
                    }

                    // Validation Warnings / Rejection Reasons
                    if (validationMessages.isNotEmpty()) {
                        Text(
                            text = "Validation Gate Check Warnings:",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeutralAmber,
                            fontWeight = FontWeight.Bold
                        )
                        validationMessages.forEach { msg ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "⚠️",
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NeutralAmber,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }

                    // Data Freshness & Broker Constraints
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Data Freshness & Broker Constraints:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "• Feed: ${dataFreshnessInfo.ifBlank { "Live market websocket connection active" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                        Text(
                            text = "• Execution Status: ${brokerStatus.displayLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}
