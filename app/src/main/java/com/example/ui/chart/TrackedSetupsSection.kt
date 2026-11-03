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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.ai.plan.TradeDirection
import com.example.ai.plan.TradeOrderType
import com.example.ai.tracker.SetupTransitionRecord
import com.example.ai.tracker.TrackedPendingSetup
import com.example.ai.tracker.TrackedSetupStatus
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
 * LUMAVUN Chart — AI Insight V3.2: Tracked Setups Section.
 *
 * Displays active and historical analytical trade setups monitored purely through
 * deterministic market data (never claiming broker executions).
 */
@Composable
fun TrackedSetupsSection(
    trackedSetups: List<TrackedPendingSetup>,
    activeSymbol: String,
    activeTimeframe: String,
    onStopTracking: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAllSymbols by remember { mutableStateOf(false) }

    val filteredSetups = remember(trackedSetups, activeSymbol, showAllSymbols) {
        if (showAllSymbols) {
            trackedSetups
        } else {
            trackedSetups.filter { it.symbol.equals(activeSymbol, ignoreCase = true) }
        }
    }

    val activeCount = remember(trackedSetups) { trackedSetups.count { !it.isTerminal } }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("tracked_setups_section")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Title, Active Count Badge, and Symbol Filter Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Timeline,
                        contentDescription = null,
                        tint = CyanAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Tracked Setups",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("tracked_setups_title")
                    )
                    Surface(
                        color = if (activeCount > 0) CyanAccent.copy(alpha = 0.18f) else Color(0xFF1E293B),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "$activeCount ACTIVE",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (activeCount > 0) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .testTag("tracked_setups_active_badge")
                        )
                    }
                }

                // Filter toggle: Current Pair vs All
                Surface(
                    onClick = { showAllSymbols = !showAllSymbols },
                    shape = RoundedCornerShape(6.dp),
                    color = if (showAllSymbols) CyanAccent.copy(alpha = 0.15f) else Color(0xFF1E293B),
                    border = BorderStroke(1.dp, if (showAllSymbols) CyanAccent else Color(0xFF334155)),
                    modifier = Modifier.testTag("tracked_setups_filter_toggle")
                ) {
                    Text(
                        text = if (showAllSymbols) "Showing All" else activeSymbol,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (showAllSymbols) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Analytical Disclaimer Notice
            Surface(
                color = Color(0xFF0F172A),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Deterministic analytical monitoring only. Never executes real broker orders.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            }

            if (filteredSetups.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F172A))
                        .padding(16.dp)
                        .testTag("tracked_setups_empty_state"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (showAllSymbols) {
                            "No setups currently tracked across any pair."
                        } else {
                            "No active tracked setups for $activeSymbol. Tap 'Track Pending Setup' above to monitor price triggers deterministically."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 16.sp
                    )
                }
            } else {
                // Setups List
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    filteredSetups.forEach { setup ->
                        TrackedSetupItemCard(
                            setup = setup,
                            onStopTracking = { onStopTracking(setup.setupId) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual card displaying tracked setup metrics, status badge, targets, and history.
 */
@Composable
fun TrackedSetupItemCard(
    setup: TrackedPendingSetup,
    onStopTracking: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expandedHistory by remember { mutableStateOf(false) }

    val statusConfig = remember(setup.currentStatus, setup.direction) {
        resolveTrackedStatusDisplay(setup.currentStatus, setup.direction)
    }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val dateFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }

    Surface(
        color = Color(0xFF0B111E),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, statusConfig.borderColor),
        modifier = modifier
            .fillMaxWidth()
            .testTag("tracked_setup_card_${setup.setupId}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Row 1: Strategy Badge, Symbol · TF, and Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = CyanAccent.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = setup.strategyName,
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanAccent,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = "${setup.symbol} · ${setup.timeframe}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Status Badge
                Surface(
                    color = statusConfig.bgColor,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, statusConfig.borderColor)
                ) {
                    Text(
                        text = statusConfig.badgeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusConfig.textColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("tracked_setup_status_${setup.setupId}")
                    )
                }
            }

            // Row 2: Order Action & Direction
            val orderAction = when (setup.direction) {
                TradeDirection.BUY -> when (setup.orderType) {
                    TradeOrderType.LIMIT -> "BUY LIMIT (Pullback)"
                    TradeOrderType.STOP -> "BUY STOP (Breakout)"
                    TradeOrderType.MARKET -> "BUY MARKET"
                    TradeOrderType.NONE -> "BUY"
                }
                TradeDirection.SELL -> when (setup.orderType) {
                    TradeOrderType.LIMIT -> "SELL LIMIT (Rally)"
                    TradeOrderType.STOP -> "SELL STOP (Breakdown)"
                    TradeOrderType.MARKET -> "SELL MARKET"
                    TradeOrderType.NONE -> "SELL"
                }
                TradeDirection.NEUTRAL -> "WAIT"
            }
            Text(
                text = "Proposed Order: $orderAction",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Row 3: Levels Grid (Entry Zone, SL, TP1, R:R)
            Surface(
                color = Color(0xFF131D2E),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "ENTRY ZONE",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanAccent,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${formatCryptoPrice(setup.entryZoneMin)} - ${formatCryptoPrice(setup.entryZoneMax)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Column {
                        Text(
                            text = "STOP LOSS",
                            style = MaterialTheme.typography.labelSmall,
                            color = BearishRed,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = formatCryptoPrice(setup.stopLoss),
                            style = MaterialTheme.typography.labelSmall,
                            color = BearishRed,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "TARGET TP1",
                            style = MaterialTheme.typography.labelSmall,
                            color = BullishGreen,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = formatCryptoPrice(setup.tp1),
                            style = MaterialTheme.typography.labelSmall,
                            color = BullishGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Row 4: Last verified market price & timestamps
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val lastPriceStr = if (setup.lastVerifiedPrice > 0.0) formatCryptoPrice(setup.lastVerifiedPrice) else "Pending"
                Text(
                    text = "Last Price: $lastPriceStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )

                val createdStr = timeFmt.format(Date(setup.createdAt))
                val expiresStr = if (System.currentTimeMillis() >= setup.expiresAt) "EXPIRED" else timeFmt.format(Date(setup.expiresAt))
                Text(
                    text = "Created: $createdStr · Exp: $expiresStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }

            // Row 5: Action buttons (Expand transition history / Cancel tracking)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Toggle Transition History
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { expandedHistory = !expandedHistory }
                        .padding(vertical = 4.dp, horizontal = 2.dp)
                        .testTag("toggle_history_${setup.setupId}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "History (${setup.transitionHistory.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = if (expandedHistory) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = CyanAccent,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Cancel Button (only for active setups)
                if (!setup.isTerminal) {
                    Surface(
                        onClick = onStopTracking,
                        shape = RoundedCornerShape(4.dp),
                        color = BearishRedSoft,
                        border = BorderStroke(1.dp, BearishRed.copy(alpha = 0.5f)),
                        modifier = Modifier.testTag("cancel_tracking_${setup.setupId}")
                    ) {
                        Text(
                            text = "Cancel Tracking",
                            style = MaterialTheme.typography.labelSmall,
                            color = BearishRed,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Expandable Transition History Timeline
            AnimatedVisibility(
                visible = expandedHistory,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF090E17))
                        .padding(8.dp)
                        .testTag("history_timeline_${setup.setupId}"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Transition Audit History:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    setup.transitionHistory.forEach { record ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "•",
                                color = CyanAccent,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Column {
                                val tStr = timeFmt.format(Date(record.timestamp))
                                Text(
                                    text = "$tStr · ${record.fromStatus.displayLabel} → ${record.toStatus.displayLabel} @ ${formatCryptoPrice(record.priceAtTransition)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = record.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class TrackedStatusDisplay(
    val badgeLabel: String,
    val textColor: Color,
    val bgColor: Color,
    val borderColor: Color
)

private fun resolveTrackedStatusDisplay(
    status: TrackedSetupStatus,
    direction: TradeDirection
): TrackedStatusDisplay {
    return when (status) {
        TrackedSetupStatus.PENDING -> TrackedStatusDisplay(
            badgeLabel = "⏳ PENDING TRIGGER",
            textColor = NeutralAmber,
            bgColor = NeutralAmberSoft,
            borderColor = NeutralAmber.copy(alpha = 0.5f)
        )
        TrackedSetupStatus.PRICE_TRIGGERED -> TrackedStatusDisplay(
            badgeLabel = "⚡ PRICE TRIGGERED",
            textColor = CyanAccent,
            bgColor = CyanAccent.copy(alpha = 0.16f),
            borderColor = CyanAccent.copy(alpha = 0.6f)
        )
        TrackedSetupStatus.TP1_REACHED -> TrackedStatusDisplay(
            badgeLabel = "🎯 TP1 REACHED",
            textColor = BullishGreen,
            bgColor = BullishGreenSoft,
            borderColor = BullishGreen.copy(alpha = 0.6f)
        )
        TrackedSetupStatus.TP2_REACHED -> TrackedStatusDisplay(
            badgeLabel = "🎯 TP2 REACHED",
            textColor = BullishGreen,
            bgColor = BullishGreenSoft,
            borderColor = BullishGreen.copy(alpha = 0.6f)
        )
        TrackedSetupStatus.TP3_REACHED -> TrackedStatusDisplay(
            badgeLabel = "🏆 TP3 HIT (COMPLETED)",
            textColor = BullishGreen,
            bgColor = BullishGreenSoft,
            borderColor = BullishGreen.copy(alpha = 0.8f)
        )
        TrackedSetupStatus.STOP_LOSS_REACHED -> TrackedStatusDisplay(
            badgeLabel = "🛑 STOP LOSS REACHED",
            textColor = BearishRed,
            bgColor = BearishRedSoft,
            borderColor = BearishRed.copy(alpha = 0.7f)
        )
        TrackedSetupStatus.INVALIDATED -> TrackedStatusDisplay(
            badgeLabel = "⚠️ INVALIDATED",
            textColor = BearishRed,
            bgColor = BearishRedSoft,
            borderColor = BearishRed.copy(alpha = 0.7f)
        )
        TrackedSetupStatus.EXPIRED -> TrackedStatusDisplay(
            badgeLabel = "⌛ EXPIRED",
            textColor = Color(0xFF94A3B8),
            bgColor = Color(0xFF1E293B),
            borderColor = Color(0xFF475569)
        )
        TrackedSetupStatus.CANCELLED -> TrackedStatusDisplay(
            badgeLabel = "✖ CANCELLED",
            textColor = Color(0xFF94A3B8),
            bgColor = Color(0xFF1E293B),
            borderColor = Color(0xFF475569)
        )
        TrackedSetupStatus.AMBIGUOUS -> TrackedStatusDisplay(
            badgeLabel = "❓ AMBIGUOUS INTRABAR",
            textColor = NeutralAmber,
            bgColor = NeutralAmberSoft,
            borderColor = NeutralAmber.copy(alpha = 0.7f)
        )
    }
}
