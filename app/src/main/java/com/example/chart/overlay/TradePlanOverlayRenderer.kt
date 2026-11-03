package com.example.chart.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.plan.TradeDirection
import com.example.ai.tracker.TrackedSetupStatus
import com.example.market.candle.formatCryptoPrice
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BullishGreen
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NeutralAmber

// Pre-allocated static PathEffect singletons to avoid per-frame allocations
private val DashEffectSl = PathEffect.dashPathEffect(floatArrayOf(8f, 5f), 0f)
private val DashEffectTp1 = PathEffect.dashPathEffect(floatArrayOf(10f, 5f), 0f)
private val DashEffectTp2 = PathEffect.dashPathEffect(floatArrayOf(7f, 5f), 0f)
private val DashEffectTp3 = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
private val DashEffectEntryBound = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
private val DashEffectInvalidation = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)

// Reusable text styles
private val OverlayPillTextStyle = TextStyle(
    fontFamily = JetBrainsMonoFontFamily,
    fontSize = 8.sp,
    fontWeight = FontWeight.Bold,
    color = Color.White,
    fontFeatureSettings = "tnum"
)

private val OverlayBadgeTextStyle = TextStyle(
    fontFamily = JetBrainsMonoFontFamily,
    fontSize = 7.5.sp,
    fontWeight = FontWeight.Bold,
    color = Color.White,
    fontFeatureSettings = "tnum"
)

private val InvalidationColor = Color(0xFFA855F7) // Purple warning
private val BuyEntryColor = CyanAccent
private val SellEntryColor = Color(0xFFF97316) // Vibrant Orange/Amber

/**
 * Renders the AI Trade Plan and pending setup overlay on the chart Canvas.
 *
 * Guaranteed Properties:
 * 1. Zero network/AI calls during render pass.
 * 2. Perfect alignment with candle prices using dynamic [priceToY].
 * 3. Clips strictly to [plotWidth] (chart plot area) without bleeding over the price axis.
 * 4. Filters out out-of-range prices ([scaledMin]..[scaledMax]) to prevent stuck or misleading lines.
 * 5. Uses text labels alongside colors for accessibility.
 */
fun DrawScope.drawTradePlanOverlay(
    overlay: AiTradePlanChartOverlay,
    scaledMin: Double,
    scaledMax: Double,
    plotWidth: Float,
    totalHeight: Float,
    priceToY: (Double) -> Float,
    textMeasurer: TextMeasurer
) {
    if (!overlay.isVisible) return

    val upperEntry = overlay.upperEntryPrice
    val lowerEntry = overlay.lowerEntryPrice
    val entryAccentColor = if (overlay.direction == TradeDirection.BUY) BuyEntryColor else SellEntryColor

    // 1. ENTRY ZONE (Semi-transparent price band & boundary lines)
    val entryIsVisible = !(upperEntry < scaledMin || lowerEntry > scaledMax)
    if (entryIsVisible && upperEntry > 0.0 && lowerEntry > 0.0) {
        val rawTopY = priceToY(upperEntry)
        val rawBottomY = priceToY(lowerEntry)
        val bandTop = minOf(rawTopY, rawBottomY).coerceIn(0f, totalHeight)
        val bandBottom = maxOf(rawTopY, rawBottomY).coerceIn(0f, totalHeight)
        val bandHeight = maxOf(1f, bandBottom - bandTop)

        // Semi-transparent band fill
        drawRect(
            color = entryAccentColor.copy(alpha = 0.16f),
            topLeft = Offset(0f, bandTop),
            size = Size(plotWidth, bandHeight)
        )

        // Upper boundary line
        if (upperEntry in scaledMin..scaledMax) {
            val upperY = priceToY(upperEntry)
            drawLine(
                color = entryAccentColor.copy(alpha = 0.85f),
                start = Offset(0f, upperY),
                end = Offset(plotWidth, upperY),
                strokeWidth = 1.2.dp.toPx(),
                pathEffect = DashEffectEntryBound
            )
        }

        // Lower boundary line (if not identical to upper)
        if (lowerEntry in scaledMin..scaledMax && lowerEntry != upperEntry) {
            val lowerY = priceToY(lowerEntry)
            drawLine(
                color = entryAccentColor.copy(alpha = 0.85f),
                start = Offset(0f, lowerY),
                end = Offset(plotWidth, lowerY),
                strokeWidth = 1.2.dp.toPx(),
                pathEffect = DashEffectEntryBound
            )
        }

        // Entry zone label pill (Left side)
        val entryAction = "${overlay.direction.code} ${overlay.orderType.code} ENTRY"
        val entryPriceRange = if (upperEntry != lowerEntry) {
            "[${formatCryptoPrice(lowerEntry)} - ${formatCryptoPrice(upperEntry)}]"
        } else {
            formatCryptoPrice(lowerEntry)
        }
        val entryText = "$entryAction $entryPriceRange"
        val measuredEntry = textMeasurer.measure(entryText, style = OverlayPillTextStyle)
        val entryPillW = measuredEntry.size.width + 10.dp.toPx()
        val entryPillH = (measuredEntry.size.height + 4.dp.toPx()).coerceAtLeast(14.dp.toPx())
        val entryPillTop = (bandTop + 2.dp.toPx()).coerceIn(2f, (totalHeight - entryPillH - 2f).coerceAtLeast(2f))

        drawRect(
            color = entryAccentColor.copy(alpha = 0.85f),
            topLeft = Offset(6.dp.toPx(), entryPillTop),
            size = Size(entryPillW, entryPillH)
        )
        drawText(
            textMeasurer = textMeasurer,
            text = entryText,
            style = OverlayPillTextStyle.copy(color = Color.Black),
            topLeft = Offset(11.dp.toPx(), entryPillTop + 2.dp.toPx()),
            overflow = TextOverflow.Clip,
            softWrap = false,
            maxLines = 1
        )

        // Status badge on the entry zone (adjacent or below)
        val statusLabel = "[${overlay.strategyName} · ${overlay.lifecycleStatus.displayLabel}]"
        val measuredStatus = textMeasurer.measure(statusLabel, style = OverlayBadgeTextStyle)
        val statusPillW = measuredStatus.size.width + 8.dp.toPx()
        val statusPillH = (measuredStatus.size.height + 3.dp.toPx()).coerceAtLeast(13.dp.toPx())
        val statusPillLeft = 6.dp.toPx() + entryPillW + 6.dp.toPx()

        if (statusPillLeft + statusPillW < plotWidth - 70.dp.toPx()) {
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

            drawRect(
                color = Color(0xFF0F172A).copy(alpha = 0.85f),
                topLeft = Offset(statusPillLeft, entryPillTop),
                size = Size(statusPillW, statusPillH)
            )
            drawText(
                textMeasurer = textMeasurer,
                text = statusLabel,
                style = OverlayBadgeTextStyle.copy(color = statusColor),
                topLeft = Offset(statusPillLeft + 4.dp.toPx(), entryPillTop + 1.5.dp.toPx()),
                overflow = TextOverflow.Clip,
                softWrap = false,
                maxLines = 1
            )
        }
    }

    // 2. STOP LOSS LINE & PILL
    if (overlay.stopLoss in scaledMin..scaledMax && overlay.stopLoss > 0.0) {
        val slY = priceToY(overlay.stopLoss)
        drawLine(
            color = BearishRed.copy(alpha = 0.90f),
            start = Offset(0f, slY),
            end = Offset(plotWidth, slY),
            strokeWidth = 1.4.dp.toPx(),
            pathEffect = DashEffectSl
        )

        val slText = "SL ${formatCryptoPrice(overlay.stopLoss)}"
        val measuredSl = textMeasurer.measure(slText, style = OverlayPillTextStyle)
        val pillW = measuredSl.size.width + 8.dp.toPx()
        val pillH = (measuredSl.size.height + 3.dp.toPx()).coerceAtLeast(13.dp.toPx())
        val pillLeft = (plotWidth - pillW - 4.dp.toPx()).coerceAtLeast(4f)
        val pillTop = (slY - pillH / 2f).coerceIn(2f, (totalHeight - pillH - 2f).coerceAtLeast(2f))

        drawRect(
            color = BearishRed.copy(alpha = 0.90f),
            topLeft = Offset(pillLeft, pillTop),
            size = Size(pillW, pillH)
        )
        drawText(
            textMeasurer = textMeasurer,
            text = slText,
            style = OverlayPillTextStyle,
            topLeft = Offset(pillLeft + 4.dp.toPx(), pillTop + 1.5.dp.toPx()),
            overflow = TextOverflow.Clip,
            softWrap = false,
            maxLines = 1
        )
    }

    // 3. TAKE PROFIT 1 LINE & PILL
    if (overlay.tp1 in scaledMin..scaledMax && overlay.tp1 > 0.0) {
        val tp1Y = priceToY(overlay.tp1)
        drawLine(
            color = BullishGreen.copy(alpha = 0.90f),
            start = Offset(0f, tp1Y),
            end = Offset(plotWidth, tp1Y),
            strokeWidth = 1.4.dp.toPx(),
            pathEffect = DashEffectTp1
        )

        val tp1Text = "TP1 ${formatCryptoPrice(overlay.tp1)}"
        val measuredTp1 = textMeasurer.measure(tp1Text, style = OverlayPillTextStyle)
        val pillW = measuredTp1.size.width + 8.dp.toPx()
        val pillH = (measuredTp1.size.height + 3.dp.toPx()).coerceAtLeast(13.dp.toPx())
        val pillLeft = (plotWidth - pillW - 4.dp.toPx()).coerceAtLeast(4f)
        val pillTop = (tp1Y - pillH / 2f).coerceIn(2f, (totalHeight - pillH - 2f).coerceAtLeast(2f))

        drawRect(
            color = BullishGreen.copy(alpha = 0.90f),
            topLeft = Offset(pillLeft, pillTop),
            size = Size(pillW, pillH)
        )
        drawText(
            textMeasurer = textMeasurer,
            text = tp1Text,
            style = OverlayPillTextStyle,
            topLeft = Offset(pillLeft + 4.dp.toPx(), pillTop + 1.5.dp.toPx()),
            overflow = TextOverflow.Clip,
            softWrap = false,
            maxLines = 1
        )
    }

    // 4. TAKE PROFIT 2 (Optional)
    val tp2 = overlay.tp2
    if (tp2 != null && tp2 > 0.0 && !tp2.isNaN() && tp2 in scaledMin..scaledMax) {
        val tp2Y = priceToY(tp2)
        drawLine(
            color = BullishGreen.copy(alpha = 0.85f),
            start = Offset(0f, tp2Y),
            end = Offset(plotWidth, tp2Y),
            strokeWidth = 1.2.dp.toPx(),
            pathEffect = DashEffectTp2
        )

        val tp2Text = "TP2 ${formatCryptoPrice(tp2)}"
        val measuredTp2 = textMeasurer.measure(tp2Text, style = OverlayPillTextStyle)
        val pillW = measuredTp2.size.width + 8.dp.toPx()
        val pillH = (measuredTp2.size.height + 3.dp.toPx()).coerceAtLeast(13.dp.toPx())
        val pillLeft = (plotWidth - pillW - 4.dp.toPx()).coerceAtLeast(4f)
        val pillTop = (tp2Y - pillH / 2f).coerceIn(2f, (totalHeight - pillH - 2f).coerceAtLeast(2f))

        drawRect(
            color = BullishGreen.copy(alpha = 0.85f),
            topLeft = Offset(pillLeft, pillTop),
            size = Size(pillW, pillH)
        )
        drawText(
            textMeasurer = textMeasurer,
            text = tp2Text,
            style = OverlayPillTextStyle,
            topLeft = Offset(pillLeft + 4.dp.toPx(), pillTop + 1.5.dp.toPx()),
            overflow = TextOverflow.Clip,
            softWrap = false,
            maxLines = 1
        )
    }

    // 5. TAKE PROFIT 3 (Optional)
    val tp3 = overlay.tp3
    if (tp3 != null && tp3 > 0.0 && !tp3.isNaN() && tp3 in scaledMin..scaledMax) {
        val tp3Y = priceToY(tp3)
        drawLine(
            color = BullishGreen.copy(alpha = 0.80f),
            start = Offset(0f, tp3Y),
            end = Offset(plotWidth, tp3Y),
            strokeWidth = 1.1.dp.toPx(),
            pathEffect = DashEffectTp3
        )

        val tp3Text = "TP3 ${formatCryptoPrice(tp3)}"
        val measuredTp3 = textMeasurer.measure(tp3Text, style = OverlayPillTextStyle)
        val pillW = measuredTp3.size.width + 8.dp.toPx()
        val pillH = (measuredTp3.size.height + 3.dp.toPx()).coerceAtLeast(13.dp.toPx())
        val pillLeft = (plotWidth - pillW - 4.dp.toPx()).coerceAtLeast(4f)
        val pillTop = (tp3Y - pillH / 2f).coerceIn(2f, (totalHeight - pillH - 2f).coerceAtLeast(2f))

        drawRect(
            color = BullishGreen.copy(alpha = 0.80f),
            topLeft = Offset(pillLeft, pillTop),
            size = Size(pillW, pillH)
        )
        drawText(
            textMeasurer = textMeasurer,
            text = tp3Text,
            style = OverlayPillTextStyle,
            topLeft = Offset(pillLeft + 4.dp.toPx(), pillTop + 1.5.dp.toPx()),
            overflow = TextOverflow.Clip,
            softWrap = false,
            maxLines = 1
        )
    }

    // 6. INVALIDATION LEVEL (Optional dashed warning line)
    val invLevel = overlay.invalidationLevel
    if (invLevel != null && invLevel > 0.0 && !invLevel.isNaN() && invLevel in scaledMin..scaledMax) {
        val invY = priceToY(invLevel)
        drawLine(
            color = InvalidationColor.copy(alpha = 0.85f),
            start = Offset(0f, invY),
            end = Offset(plotWidth, invY),
            strokeWidth = 1.2.dp.toPx(),
            pathEffect = DashEffectInvalidation
        )

        val invText = "INV ${formatCryptoPrice(invLevel)}"
        val measuredInv = textMeasurer.measure(invText, style = OverlayPillTextStyle)
        val pillW = measuredInv.size.width + 8.dp.toPx()
        val pillH = (measuredInv.size.height + 3.dp.toPx()).coerceAtLeast(13.dp.toPx())
        val pillLeft = (plotWidth - pillW - 4.dp.toPx()).coerceAtLeast(4f)
        val pillTop = (invY - pillH / 2f).coerceIn(2f, (totalHeight - pillH - 2f).coerceAtLeast(2f))

        drawRect(
            color = InvalidationColor.copy(alpha = 0.85f),
            topLeft = Offset(pillLeft, pillTop),
            size = Size(pillW, pillH)
        )
        drawText(
            textMeasurer = textMeasurer,
            text = invText,
            style = OverlayPillTextStyle,
            topLeft = Offset(pillLeft + 4.dp.toPx(), pillTop + 1.5.dp.toPx()),
            overflow = TextOverflow.Clip,
            softWrap = false,
            maxLines = 1
        )
    }
}
