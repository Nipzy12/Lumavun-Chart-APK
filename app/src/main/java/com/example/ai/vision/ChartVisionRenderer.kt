package com.example.ai.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.market.candle.Candle
import com.example.market.candle.formatCryptoPrice
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Encapsulates rendered chart vision images for multimodal AI processing.
 */
data class ChartVisionBundle(
    val historicalChartBase64: String,
    val recentChartBase64: String,
    val candleCountHistorical: Int,
    val candleCountRecent: Int,
    val isAvailable: Boolean = true,
    val note: String = ""
)

/**
 * LUMAVUN Chart — AI Insight V2.2: Hybrid Chart Vision Renderer.
 *
 * Renders bounded, offscreen chart images directly into Bitmaps via Android Canvas:
 * 1. Historical View (~150–250 candles): macro trend, swing structure, major S/R, order blocks, price scale.
 * 2. Recent View (~50–75 candles): micro price action, recent swing highs/lows, liquidity sweeps, order blocks, price scale.
 *
 * Characteristics:
 * - Bounded resolution (720x440) for low memory footprint and fast base64 encoding.
 * - Entirely offscreen and decoupled from the Compose UI hierarchy.
 * - Bitmaps are immediately recycled after compression.
 * - Does not access Android MediaProjection or screen-capture permissions.
 * - Does not expose account information, API keys, or personal data.
 */
object ChartVisionRenderer {

    const val WIDTH = 720
    const val HEIGHT = 440
    const val TARGET_HISTORICAL_CANDLES = 200
    const val MIN_HISTORICAL_CANDLES = 120
    const val TARGET_RECENT_CANDLES = 75
    const val MIN_RECENT_CANDLES = 35

    // Colors matching LUMAVUN dark theme
    private const val COLOR_BG = 0xFF10141E.toInt()
    private const val COLOR_PANEL_HEADER = 0xFF161C2A.toInt()
    private const val COLOR_GRID = 0xFF1E2638.toInt()
    private const val COLOR_AXIS_TEXT = 0xFF8896B3.toInt()
    private const val COLOR_TITLE_TEXT = 0xFF00E5FF.toInt()
    private const val COLOR_SUB_TEXT = 0xFFBAC7DF.toInt()
    private const val COLOR_BULLISH = 0xFF00E676.toInt()
    private const val COLOR_BEARISH = 0xFFFF5252.toInt()
    private const val COLOR_SUPPORT = 0xFF00E5FF.toInt()
    private const val COLOR_RESISTANCE = 0xFFFFAB00.toInt()
    private const val COLOR_SWING_HIGH = 0xFFFF5252.toInt()
    private const val COLOR_SWING_LOW = 0xFF00E676.toInt()
    private const val COLOR_OB_BULLISH = 0x2E00E676.toInt()
    private const val COLOR_OB_BEARISH = 0x2EFF5252.toInt()
    private const val COLOR_SWEEP = 0xFFFFD700.toInt()

    fun renderVisionBundle(
        symbol: String,
        timeframeLabel: String,
        candles: List<Candle>,
        snapshot: MarketTechnicalSnapshot? = null
    ): ChartVisionBundle? {
        if (candles.isEmpty()) return null

        val available = candles.size
        val histCount = when {
            available >= TARGET_HISTORICAL_CANDLES -> TARGET_HISTORICAL_CANDLES
            available >= MIN_HISTORICAL_CANDLES -> available
            else -> min(available, TARGET_HISTORICAL_CANDLES)
        }
        val histCandles = candles.takeLast(histCount)

        val recentCount = when {
            available >= TARGET_RECENT_CANDLES -> TARGET_RECENT_CANDLES
            available >= MIN_RECENT_CANDLES -> available
            else -> min(available, TARGET_RECENT_CANDLES)
        }
        val recentCandles = candles.takeLast(recentCount)

        val histBase64 = renderChartImageBase64(
            symbol = symbol,
            timeframeLabel = timeframeLabel,
            viewTitle = "HISTORICAL MARKET STRUCTURE (${histCandles.size} CANDLES)",
            candles = histCandles,
            isHistoricalView = true,
            snapshot = snapshot
        ) ?: return null

        val recentBase64 = renderChartImageBase64(
            symbol = symbol,
            timeframeLabel = timeframeLabel,
            viewTitle = "RECENT PRICE ACTION & LIQUIDITY (${recentCandles.size} CANDLES)",
            candles = recentCandles,
            isHistoricalView = false,
            snapshot = snapshot
        ) ?: return null

        val note = if (available < MIN_HISTORICAL_CANDLES) {
            "Adapted to $available available candles (target: $TARGET_HISTORICAL_CANDLES)."
        } else {
            "Historical (${histCandles.size}c) & Recent (${recentCandles.size}c) offscreen views generated."
        }

        return ChartVisionBundle(
            historicalChartBase64 = histBase64,
            recentChartBase64 = recentBase64,
            candleCountHistorical = histCandles.size,
            candleCountRecent = recentCandles.size,
            isAvailable = true,
            note = note
        )
    }

    private fun renderChartImageBase64(
        symbol: String,
        timeframeLabel: String,
        viewTitle: String,
        candles: List<Candle>,
        isHistoricalView: Boolean,
        snapshot: MarketTechnicalSnapshot?
    ): String? {
        if (candles.isEmpty()) return null

        var bitmap: Bitmap? = null
        return try {
            bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            drawChartOntoCanvas(
                canvas = canvas,
                width = WIDTH.toFloat(),
                height = HEIGHT.toFloat(),
                symbol = symbol,
                timeframeLabel = timeframeLabel,
                viewTitle = viewTitle,
                candles = candles,
                isHistoricalView = isHistoricalView,
                snapshot = snapshot
            )

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            val bytes = stream.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        } finally {
            bitmap?.recycle()
        }
    }

    private fun drawChartOntoCanvas(
        canvas: Canvas,
        width: Float,
        height: Float,
        symbol: String,
        timeframeLabel: String,
        viewTitle: String,
        candles: List<Candle>,
        isHistoricalView: Boolean,
        snapshot: MarketTechnicalSnapshot?
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Fill background
        paint.color = COLOR_BG
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, width, height, paint)

        // Margins
        val headerHeight = 40f
        val footerHeight = 24f
        val priceAxisWidth = 85f

        val plotLeft = 12f
        val plotRight = width - priceAxisWidth
        val plotTop = headerHeight + 6f
        val plotBottom = height - footerHeight
        val plotWidth = plotRight - plotLeft
        val plotHeight = plotBottom - plotTop

        if (plotWidth <= 0f || plotHeight <= 0f) return

        // 2. Draw Top Header Bar
        paint.color = COLOR_PANEL_HEADER
        canvas.drawRect(0f, 0f, width, headerHeight, paint)

        paint.color = COLOR_TITLE_TEXT
        paint.textSize = 14f
        paint.isFakeBoldText = true
        canvas.drawText("${symbol.uppercase(Locale.US)} · ${timeframeLabel.uppercase(Locale.US)}", 14f, 24f, paint)

        paint.color = COLOR_SUB_TEXT
        paint.textSize = 11f
        paint.isFakeBoldText = false
        val titleWidth = paint.measureText(viewTitle)
        canvas.drawText(viewTitle, width - priceAxisWidth - titleWidth - 10f, 24f, paint)

        // 3. Compute Price Range
        var minPrice = Double.MAX_VALUE
        var maxPrice = Double.MIN_VALUE
        for (c in candles) {
            if (c.low < minPrice) minPrice = c.low
            if (c.high > maxPrice) maxPrice = c.high
        }

        // Add S/R and levels into range consideration
        val lastPrice = candles.lastOrNull()?.close ?: 0.0
        if (minPrice == Double.MAX_VALUE || maxPrice == Double.MIN_VALUE || minPrice >= maxPrice) {
            minPrice = (lastPrice * 0.99).coerceAtLeast(0.000001)
            maxPrice = (lastPrice * 1.01).coerceAtLeast(minPrice * 1.001)
        }

        // Add 5% padding top and bottom
        val priceSpan = (maxPrice - minPrice).coerceAtLeast(0.000001)
        val yMin = minPrice - priceSpan * 0.05
        val yMax = maxPrice + priceSpan * 0.05
        val paddedSpan = (yMax - yMin).coerceAtLeast(0.000001)

        fun priceToY(price: Double): Float {
            val norm = (price - yMin) / paddedSpan
            return (plotBottom - (norm * plotHeight).toFloat()).coerceIn(plotTop, plotBottom)
        }

        // 4. Draw Horizontal Grid and Price Axis Scale
        val gridLines = 5
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = COLOR_GRID

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_AXIS_TEXT
            textSize = 10f
        }

        for (i in 0..gridLines) {
            val fraction = i.toFloat() / gridLines
            val y = plotBottom - (fraction * plotHeight)
            val pVal = yMin + (fraction * paddedSpan)

            // Grid line
            paint.color = COLOR_GRID
            canvas.drawLine(plotLeft, y, plotRight, y, paint)

            // Price label
            val pStr = formatCryptoPrice(pVal)
            canvas.drawText(pStr, plotRight + 6f, y + 4f, textPaint)
        }

        // 5. Draw Support / Resistance & Order Block Zones if available
        if (snapshot != null) {
            // S/R levels
            val dashEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
            val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.2f
                pathEffect = dashEffect
            }

            snapshot.supportLevels.take(2).forEach { sup ->
                if (sup in yMin..yMax) {
                    val y = priceToY(sup)
                    levelPaint.color = COLOR_SUPPORT
                    canvas.drawLine(plotLeft, y, plotRight, y, levelPaint)
                    textPaint.color = COLOR_SUPPORT
                    canvas.drawText("SUP", plotLeft + 4f, y - 3f, textPaint)
                }
            }

            snapshot.resistanceLevels.take(2).forEach { res ->
                if (res in yMin..yMax) {
                    val y = priceToY(res)
                    levelPaint.color = COLOR_RESISTANCE
                    canvas.drawLine(plotLeft, y, plotRight, y, levelPaint)
                    textPaint.color = COLOR_RESISTANCE
                    canvas.drawText("RES", plotLeft + 4f, y - 3f, textPaint)
                }
            }

            // Order Block Candidate Zones
            val obPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }
            snapshot.marketStructure.activeOrderBlockCandidates.take(3).forEach { ob ->
                val topY = priceToY(ob.zoneHigh)
                val bottomY = priceToY(ob.zoneLow)
                obPaint.color = if (ob.isBullish) COLOR_OB_BULLISH else COLOR_OB_BEARISH
                canvas.drawRect(plotLeft, min(topY, bottomY), plotRight, max(topY, bottomY), obPaint)
            }
        }

        // 6. Draw Candlesticks
        val count = candles.size
        val slotWidth = plotWidth / count
        val bodyWidth = (slotWidth * 0.72f).coerceAtLeast(1f)
        val halfBody = bodyWidth / 2f

        val bullPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_BULLISH
            style = Paint.Style.FILL
        }
        val bearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_BEARISH
            style = Paint.Style.FILL
        }
        val wickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = if (count > 150) 1f else 1.5f
            style = Paint.Style.STROKE
        }

        for (i in 0 until count) {
            val c = candles[i]
            val cx = plotLeft + (i + 0.5f) * slotWidth
            val isBull = c.close >= c.open
            val pColor = if (isBull) COLOR_BULLISH else COLOR_BEARISH
            val pBody = if (isBull) bullPaint else bearPaint

            val openY = priceToY(c.open)
            val closeY = priceToY(c.close)
            val highY = priceToY(c.high)
            val lowY = priceToY(c.low)

            // Wick
            wickPaint.color = pColor
            canvas.drawLine(cx, highY, cx, lowY, wickPaint)

            // Body
            val topBody = min(openY, closeY)
            val bottomBody = max(openY, closeY).coerceAtLeast(topBody + 1f)
            canvas.drawRect(cx - halfBody, topBody, cx + halfBody, bottomBody, pBody)
        }

        // 7. Swing High / Swing Low Annotations
        drawSwingPoints(
            canvas = canvas,
            candles = candles,
            plotLeft = plotLeft,
            slotWidth = slotWidth,
            priceToY = ::priceToY,
            isHistoricalView = isHistoricalView
        )

        // 8. Liquidity Sweeps in Recent View
        if (!isHistoricalView && snapshot?.smcAnalysis?.latestActiveSweep != null) {
            val sweep = snapshot.smcAnalysis.latestActiveSweep
            val sweepY = priceToY(sweep.referenceLevel)
            val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_SWEEP
                strokeWidth = 1.5f
                style = Paint.Style.STROKE
            }
            canvas.drawLine(plotLeft + plotWidth * 0.4f, sweepY, plotRight, sweepY, sweepPaint)

            val sweepTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_SWEEP
                textSize = 9.5f
                isFakeBoldText = true
            }
            canvas.drawText("SWEEP: ${sweep.state.name}", plotRight - 110f, sweepY - 4f, sweepTextPaint)
        }

        // 9. Current Price Dashed Reference Line
        val currY = priceToY(lastPrice)
        val lastPricePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TITLE_TEXT
            strokeWidth = 1f
            pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
            style = Paint.Style.STROKE
        }
        canvas.drawLine(plotLeft, currY, plotRight, currY, lastPricePaint)

        // Current price tag on right axis
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TITLE_TEXT
            style = Paint.Style.FILL
        }
        val tagTop = currY - 8f
        val tagBottom = currY + 8f
        canvas.drawRoundRect(RectF(plotRight + 2f, tagTop, width - 4f, tagBottom), 3f, 3f, badgePaint)

        val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF001F26.toInt()
            textSize = 9.5f
            isFakeBoldText = true
        }
        canvas.drawText(formatCryptoPrice(lastPrice), plotRight + 5f, currY + 3.5f, badgeTextPaint)

        // 10. Bottom time/range label
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_AXIS_TEXT
            textSize = 10f
        }
        val timeLabel = "LUMAVUN Hybrid Vision Offscreen View · Bounded 720x440 · Scaled for AI Reasoning"
        canvas.drawText(timeLabel, plotLeft, height - 8f, footerPaint)
    }

    private fun drawSwingPoints(
        canvas: Canvas,
        candles: List<Candle>,
        plotLeft: Float,
        slotWidth: Float,
        priceToY: (Double) -> Float,
        isHistoricalView: Boolean
    ) {
        val window = if (isHistoricalView) 5 else 3
        val n = candles.size
        if (n < window * 2 + 1) return

        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f
            isFakeBoldText = true
        }

        var swingHighCount = 0
        var swingLowCount = 0
        val maxPerSide = if (isHistoricalView) 6 else 4

        for (i in window until (n - window)) {
            val curr = candles[i]
            var isSwingHigh = true
            var isSwingLow = true

            for (k in (i - window)..(i + window)) {
                if (k == i) continue
                if (candles[k].high >= curr.high) isSwingHigh = false
                if (candles[k].low <= curr.low) isSwingLow = false
            }

            val cx = plotLeft + (i + 0.5f) * slotWidth

            if (isSwingHigh && swingHighCount < maxPerSide) {
                swingHighCount++
                val y = priceToY(curr.high)
                markerPaint.color = COLOR_SWING_HIGH
                canvas.drawCircle(cx, y - 4f, 2.5f, markerPaint)
                labelPaint.color = COLOR_SWING_HIGH
                canvas.drawText("SH", cx - 6f, y - 8f, labelPaint)
            }

            if (isSwingLow && swingLowCount < maxPerSide) {
                swingLowCount++
                val y = priceToY(curr.low)
                markerPaint.color = COLOR_SWING_LOW
                canvas.drawCircle(cx, y + 4f, 2.5f, markerPaint)
                labelPaint.color = COLOR_SWING_LOW
                canvas.drawText("SL", cx - 6f, y + 15f, labelPaint)
            }
        }
    }
}
