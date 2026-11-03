package com.example.chart.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.plan.TradeDirection
import com.example.ai.tracker.TrackedSetupStatus
import com.example.market.candle.formatCryptoPrice
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BullishGreen
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.NeutralAmber

/**
 * Compact in-chart badge overlay for validated trade plans and tracked pending setups.
 * Rendered at the top of the chart plotting area without covering candlesticks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompactTradePlanOverlayBadge(
    overlay: AiTradePlanChartOverlay,
    surfaceColor: Color,
    availableSetupsCount: Int = 1,
    onToggleVisibility: () -> Unit = {},
    onCycleNextSetup: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val dirColor = if (overlay.direction == TradeDirection.BUY) BullishGreen else BearishRed
    val statusColor = when (overlay.lifecycleStatus) {
        TrackedSetupStatus.PENDING -> CyanAccent
        TrackedSetupStatus.PRICE_TRIGGERED -> NeutralAmber
        TrackedSetupStatus.TP1_REACHED,
        TrackedSetupStatus.TP2_REACHED,
        TrackedSetupStatus.TP3_REACHED -> BullishGreen
        TrackedSetupStatus.STOP_LOSS_REACHED -> BearishRed
        TrackedSetupStatus.INVALIDATED,
        TrackedSetupStatus.EXPIRED,
        TrackedSetupStatus.CANCELLED,
        TrackedSetupStatus.AMBIGUOUS -> Color(0xFF94A3B8)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(surfaceColor.copy(alpha = 0.88f))
            .border(1.dp, dirColor.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp)
            .testTag("chart_trade_plan_overlay_badge"),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Top Row: Action, Strategy, Status, Setup Cycling & Close toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Text(
                    text = "${overlay.strategyName} · ${overlay.orderActionLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = dirColor,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )

                Text(
                    text = "[${overlay.lifecycleStatus.displayLabel}]",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
            }

            // Cycling button if multiple setups exist
            if (availableSetupsCount > 1) {
                Surface(
                    onClick = onCycleNextSetup,
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.testTag("cycle_trade_plan_overlay_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Next",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = CyanAccent,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next Setup",
                            tint = CyanAccent,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            // Hide overlay toggle
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onToggleVisibility)
                    .testTag("toggle_trade_plan_overlay_button")
                    .padding(2.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Hide AI Overlay",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Bottom Row: Levels Summary & Disclaimer
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            val entryRangeStr = if (overlay.entryZoneMin != overlay.entryZoneMax) {
                "${formatCryptoPrice(overlay.lowerEntryPrice)} - ${formatCryptoPrice(overlay.upperEntryPrice)}"
            } else {
                formatCryptoPrice(overlay.lowerEntryPrice)
            }

            Text(
                text = "Entry: $entryRangeStr",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false
            )

            Text(
                text = "SL: ${formatCryptoPrice(overlay.stopLoss)}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = BearishRed,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false
            )

            Text(
                text = "TP1: ${formatCryptoPrice(overlay.tp1)}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = BullishGreen,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false
            )

            if (overlay.tp2 != null && overlay.tp2 > 0.0) {
                Text(
                    text = "TP2: ${formatCryptoPrice(overlay.tp2)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = BullishGreen.copy(alpha = 0.85f),
                    maxLines = 1,
                    softWrap = false
                )
            }

            Text(
                text = "· Analytical",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
