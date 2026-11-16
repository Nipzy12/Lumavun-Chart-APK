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
import com.example.analysis.smc.SmcEntryProV5Engine
import java.math.BigDecimal
import kotlin.math.max
import kotlin.math.min

/**
 * Draws only the nearest/recent SMC Pro v5 objects on the same native price
 * scale as the candles. No extended full-width historical price lines.
 * Completed trades stop at the exact TP/SL hit candle index.
 */
fun DrawScope.drawSmcEntryProV5Overlay(
    result: SmcEntryProV5Engine.Result,
    startIndex: Int,
    endIndexExclusive: Int,
    slotWidth: Float,
    plotWidth: Float,
    priceToY: (Double) -> Float,
    textMeasurer: TextMeasurer,
    showStructure: Boolean = true,
    showZones: Boolean = true
) {
    if (result.htfUnavailable || endIndexExclusive <= startIndex) return
    val green = Color(0xFF10B981)
    val red = Color(0xFFF43F5E)
    val tpColor = Color(0xFF22C55E)
    val slColor = Color(0xFFEF4444)
    val pointWidth = 1.1.dp.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()), 0f)
    fun x(index: Int): Float = ((index - startIndex) + 0.5f) * slotWidth
    fun inView(price: Double): Boolean {
        if (!price.isFinite()) return false
        val y = priceToY(price)
        return y >= 0f && y <= size.height
    }
    val occupiedLabels = ArrayList<Pair<Float, Float>>()
    fun label(price: Double, title: String, atX: Float, color: Color) {
        if (!inView(price) || atX < 0f || atX >= plotWidth) return
        val t = textMeasurer.measure(
            text = title,
            style = TextStyle(fontSize = 9.sp, color = color, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
        val labelX = atX.coerceIn(2f, (plotWidth - t.size.width - 4f).coerceAtLeast(2f))
        val labelY = (priceToY(price) - t.size.height - 2.dp.toPx())
            .coerceIn(0f, (size.height - t.size.height).coerceAtLeast(0f))
        // Avoid SMC/Sweep/BOS labels colliding on narrow mobile charts.
        if (occupiedLabels.any { (x, y) ->
                kotlin.math.abs(y - labelY) < 17.dp.toPx() &&
                    kotlin.math.abs(x - labelX) < (t.size.width + 16.dp.toPx())
            }
        ) return
        occupiedLabels.add(labelX to labelY)
        drawText(t, topLeft = Offset(labelX, labelY))
    }
    fun priceText(n: Double): String =
        BigDecimal.valueOf(n).setScale(5, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()

    // A bounded overlay by design: historic zones never flood the screen.
    if (showZones) {
        for (zone in result.zones.takeLast(4)) {
            if (zone.bornIndex > endIndexExclusive - 1) continue
            val left = max(0f, x(zone.originIndex))
            val right = min(plotWidth, x(endIndexExclusive - 1))
            if (left >= right) continue
            val top = priceToY(zone.top)
            val bottom = priceToY(zone.bottom)
            if (bottom < 0f || top > size.height || bottom <= top) continue
            val color = if (zone.direction == SmcEntryProV5Engine.Direction.BUY) green else red
            drawRect(
                color = color.copy(alpha = 0.10f),
                topLeft = Offset(left, top.coerceAtLeast(0f)),
                size = Size(right - left, min(size.height, bottom) - max(0f, top))
            )
            drawLine(color.copy(alpha = 0.45f), Offset(left, top), Offset(right, top), strokeWidth = pointWidth)
            drawLine(color.copy(alpha = 0.45f), Offset(left, bottom), Offset(right, bottom), strokeWidth = pointWidth)
        }
    }

    if (showStructure) {
        for (event in result.structures.takeLast(3)) {
            if (event.index < startIndex || event.originIndex >= endIndexExclusive || !inView(event.price)) continue
            val start = max(0f, x(event.originIndex))
            val end = min(plotWidth, x(event.index))
            if (end <= start) continue
            val color = if (event.direction == SmcEntryProV5Engine.Direction.BUY) green else red
            val y = priceToY(event.price)
            drawLine(color.copy(alpha = 0.45f), Offset(start, y), Offset(end, y), strokeWidth = 1f, pathEffect = dash)
            label(event.price, event.label, (start + end) / 2f, color)
        }
    }

    // Pending orders are highlighted, but do not masquerade as executed orders.
    for (pending in result.pending.takeLast(1)) {
        if (pending.bornIndex >= endIndexExclusive || !inView(pending.entry)) continue
        val left = max(0f, x(pending.bornIndex))
        val right = min(plotWidth, x(endIndexExclusive - 1))
        if (right <= left) continue
        val color = if (pending.direction == SmcEntryProV5Engine.Direction.BUY) green else red
        drawLine(color.copy(alpha = 0.75f), Offset(left, priceToY(pending.entry)),
            Offset(right, priceToY(pending.entry)), strokeWidth = pointWidth, pathEffect = dash)
        label(pending.entry, "PRO PENDING " + pending.model.name + " " + priceText(pending.entry),
            left, color)
    }

    // Limit clutter to two most recent filled setups; freezing uses endIndex.
    for (trade in result.trades.takeLast(1)) {
        if (trade.entryIndex >= endIndexExclusive || trade.endIndex < startIndex) continue
        val start = max(0f, x(trade.entryIndex - 6))
        val finalIndex = if (trade.outcome == SmcEntryProV5Engine.Outcome.LIVE) {
            endIndexExclusive - 1
        } else trade.endIndex
        val end = min(plotWidth, x(finalIndex))
        if (end <= start) continue
        val entryColor = if (trade.direction == SmcEntryProV5Engine.Direction.BUY) green else red
        val levels = listOf(
            Triple(trade.entry, "ENTRY", entryColor),
            Triple(trade.sl, if (trade.outcome == SmcEntryProV5Engine.Outcome.SL) "SL HIT" else "SL", slColor),
            Triple(trade.tp, if (trade.outcome == SmcEntryProV5Engine.Outcome.TP) "TP HIT" else "TP", tpColor)
        )
        for ((price, name, color) in levels) {
            if (!inView(price)) continue
            val y = priceToY(price)
            drawLine(color.copy(alpha = 0.9f), Offset(start, y), Offset(end, y),
                strokeWidth = 1.5.dp.toPx())
            label(price, "PRO " + name + " " + priceText(price), start, color)
        }
    }
}
