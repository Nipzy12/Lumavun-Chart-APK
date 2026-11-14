package com.example.chart.chart

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.analysis.marketStructure.MarketStructureAnalysis
import com.example.analysis.marketStructure.MarketStructureEngine
import com.example.analysis.marketStructure.OrderBlockCandidate
import com.example.analysis.marketStructure.StructureBreakType
import com.example.analysis.marketStructure.SwingPoint
import com.example.analysis.signal.ChartSignalOverlay
import com.example.chart.overlay.AiTradePlanChartOverlay
import com.example.chart.overlay.CompactTradePlanOverlayBadge
import com.example.chart.overlay.TradePlanOverlayMapper
import com.example.chart.overlay.drawTradePlanOverlay
import com.example.analysis.smc.LiquiditySweepEvent
import com.example.analysis.smc.SmcAnalysisSnapshot
import com.example.analysis.smc.SmcEntryProV5Engine
import com.example.chart.overlay.drawSmcEntryProV5Overlay
import com.example.analysis.technical.MarketTechnicalSnapshot
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import com.example.chart.indicators.IndicatorSeries
import com.example.chart.indicators.MacdPoint
import com.example.market.candle.Candle
import com.example.market.candle.formatCompactVolume
import com.example.market.candle.formatCryptoPrice
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BullishGreen
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.Ema200Color
import com.example.ui.theme.Ema20Color
import com.example.ui.theme.Ema50Color
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.MacdLineColor
import com.example.ui.theme.MacdSignalColor
import com.example.ui.theme.NeutralAmber
import com.example.ui.theme.RsiLineColor
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ChartEngineMode(val label: String) {
    NATIVE_LIGHTWEIGHT("Canvas"),
    TV_LIGHTWEIGHT_JS("TV-LWC")
}

enum class DragLock { NONE, HORIZONTAL, VERTICAL }

/**
 * Immutable precomputed AMD session zone (Asia Accumulation + London Manipulation sweep)
 */
data class CachedAmdSessionZone(
    val startOpenTime: Long,
    val endOpenTime: Long,
    val firstCandleIndex: Int,
    val lastCandleIndex: Int,
    val high: Double,
    val low: Double,
    val manipulationIndex: Int? = null,
    val manipulationOpenTime: Long? = null,
    val manipulationPrice: Double? = null,
    val isHighSweep: Boolean = false
)

private val tokyoZoneId: ZoneId by lazy { ZoneId.of("Asia/Tokyo") }

private val DashEffectSr = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
private val DashEffectSmc = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
private val DashEffectAmd = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f)

private val ChartPivotLabelStyle = TextStyle(
    fontFamily = JetBrainsMonoFontFamily,
    fontSize = 8.5.sp,
    fontWeight = FontWeight.Bold,
    fontFeatureSettings = "tnum"
)

private val ChartIctObStyle = TextStyle(
    fontFamily = JetBrainsMonoFontFamily,
    fontSize = 7.5.sp,
    fontWeight = FontWeight.Bold,
    color = Color.White
)

private val ChartAmdSessionStyle = TextStyle(
    fontFamily = JetBrainsMonoFontFamily,
    fontSize = 8.sp,
    fontWeight = FontWeight.Bold,
    color = Color(0xFF94A3B8)
)

private val ChartAmdManipStyle = TextStyle(
    fontFamily = JetBrainsMonoFontFamily,
    fontSize = 7.5.sp,
    fontWeight = FontWeight.Bold,
    color = Color(0xFFF59E0B)
)

private val ChartSmcBreakStyle = TextStyle(
    fontFamily = JetBrainsMonoFontFamily,
    fontSize = 7.5.sp,
    fontWeight = FontWeight.Bold,
    color = Color.White
)

private val ChartSmcTagBaseStyle = TextStyle(
    fontFamily = JetBrainsMonoFontFamily,
    fontSize = 7.5.sp,
    fontWeight = FontWeight.Bold
)

private fun computeAmdSessionZones(candles: List<Candle>): List<CachedAmdSessionZone> {
    if (candles.isEmpty()) return emptyList()
    val zones = ArrayList<CachedAmdSessionZone>()

    var inAsia = false
    var asiaStartIdx = -1
    val asiaCandles = ArrayList<Candle>()

    for (i in candles.indices) {
        val c = candles[i]
        val instant = Instant.ofEpochMilli(c.openTime)
        val zdt = instant.atZone(tokyoZoneId)
        val time = zdt.toLocalTime()
        // Tokyo 09:00 - 18:00
        val isAsiaTime = !time.isBefore(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(18, 0))
        if (isAsiaTime) {
            if (!inAsia) {
                inAsia = true
                asiaStartIdx = i
                asiaCandles.clear()
            }
            asiaCandles.add(c)
        } else {
            if (inAsia) {
                createCachedAmdZone(asiaStartIdx, i - 1, asiaCandles, candles)?.let { zones.add(it) }
                inAsia = false
                asiaCandles.clear()
            }
        }
    }
    if (inAsia) {
        createCachedAmdZone(asiaStartIdx, candles.size - 1, asiaCandles, candles)?.let { zones.add(it) }
    }
    return zones
}

private fun createCachedAmdZone(
    firstIdx: Int,
    lastIdx: Int,
    blockCandles: List<Candle>,
    allCandles: List<Candle>
): CachedAmdSessionZone? {
    if (blockCandles.isEmpty()) return null
    var maxH = -Double.MAX_VALUE
    var minL = Double.MAX_VALUE
    for (i in blockCandles.indices) {
        val c = blockCandles[i]
        if (c.high > maxH) maxH = c.high
        if (c.low < minL) minL = c.low
    }
    if (maxH < minL || maxH == -Double.MAX_VALUE) return null

    var manipIdx: Int? = null
    var manipOpenTime: Long? = null
    var manipPrice: Double? = null
    var isHighSweep = false

    val followStart = lastIdx + 1
    val followEnd = min(allCandles.size, followStart + 24)
    for (mIdx in followStart until followEnd) {
        val mc = allCandles[mIdx]
        val sweptHigh = mc.high > maxH && mc.close < maxH
        val sweptLow = mc.low < minL && mc.close > minL
        if (sweptHigh || sweptLow) {
            manipIdx = mIdx
            manipOpenTime = mc.openTime
            manipPrice = if (sweptHigh) mc.high else mc.low
            isHighSweep = sweptHigh
            break
        }
    }

    return CachedAmdSessionZone(
        startOpenTime = blockCandles.first().openTime,
        endOpenTime = blockCandles.last().openTime,
        firstCandleIndex = firstIdx,
        lastCandleIndex = lastIdx,
        high = maxH,
        low = minL,
        manipulationIndex = manipIdx,
        manipulationOpenTime = manipOpenTime,
        manipulationPrice = manipPrice,
        isHighSweep = isHighSweep
    )
}

/**
 * High-performance text layout measurement cache to avoid repeating expensive harfbuzz
 * layouts every frame during panning/zooming.
 */
class ChartLabelMeasurer(val textMeasurer: TextMeasurer) {
    private val cache = HashMap<Pair<String, TextStyle>, androidx.compose.ui.text.TextLayoutResult>(64)

    fun measure(text: String, style: TextStyle): androidx.compose.ui.text.TextLayoutResult {
        val key = Pair(text, style)
        return cache.getOrPut(key) {
            textMeasurer.measure(text = text, style = style)
        }
    }
}

/**
 * Professional Mobile Trading Terminal Chart Component with Signal Overlay (Requirement #3):
 * - Auto-scales strictly to visible Candlestick High/Low range (+ active signal levels when nearby).
 * - Renders horizontal ENTRY, SL, TP1, TP2, TP3 lines with compact labels for the active signal by default.
 * - Optional `showSignalHistory` draws recent historical signals faintly without cluttering the chart.
 * - Compact Signal Info Badge (`LONG 78% ·Entry ... | SL ... | TP1 ...`) without covering the candlesticks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LightweightCandleChart(
    symbol: String,
    timeframeKey: String,
    candles: List<Candle>,
    indicators: IndicatorSeries,
    supportLevels: List<Double>,
    resistanceLevels: List<Double>,
    showEma: Boolean,
    showVolume: Boolean,
    showRsi: Boolean,
    showMacd: Boolean,
    showSupportResistance: Boolean,
    showSmc: Boolean = false,
    showSmcProV5: Boolean = false,
    smcProConfig: SmcEntryProV5Engine.Config = SmcEntryProV5Engine.Config(),
    showIct: Boolean = false,
    showAmd: Boolean = false,
    snapshot: MarketTechnicalSnapshot? = null,
    isFullscreen: Boolean,
    darkMode: Boolean,
    onToggleFullscreen: () -> Unit,
    activeSignalOverlay: ChartSignalOverlay? = null,
    historicalSignalOverlays: List<ChartSignalOverlay> = emptyList(),
    selectedJournalOverlay: ChartSignalOverlay? = null,
    onClearSelectedJournalOverlay: () -> Unit = {},
    showSignalOverlay: Boolean = true,
    showSignalHistory: Boolean = false,
    showSma: Boolean = false,
    showBollingerBands: Boolean = false,
    showAtr: Boolean = true,
    tradePlanOverlay: AiTradePlanChartOverlay? = null,
    showTradePlanOverlay: Boolean = true,
    onToggleTradePlanOverlay: () -> Unit = {},
    availableTrackedSetups: List<com.example.ai.tracker.TrackedPendingSetup> = emptyList(),
    onSelectTrackedSetup: (String) -> Unit = {},
    onOpenIndicatorsDialog: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var engineMode by remember { mutableStateOf(ChartEngineMode.NATIVE_LIGHTWEIGHT) }

    val marketStructure = remember(candles.size, candles.firstOrNull()?.openTime, candles.lastOrNull()?.openTime, snapshot) {
        snapshot?.marketStructure ?: if (candles.isNotEmpty()) MarketStructureEngine.analyze(candles) else null
    }
    val smcAnalysis = remember(snapshot?.smcAnalysis) {
        snapshot?.smcAnalysis
    }

    // Heavy indicator evaluation is memoized by CLOSED candles, not live ticks.
    val smcProV5 = remember(
        showSmcProV5, smcProConfig, symbol, timeframeKey, candles.size,
        candles.firstOrNull()?.openTime,
        candles.lastOrNull { it.isClosed }?.closeTime,
        candles.lastOrNull { it.isClosed }?.close
    ) {
        if (showSmcProV5 && candles.isNotEmpty()) {
            SmcEntryProV5Engine.calculate(candles, com.example.market.candle.Timeframe.fromInterval(timeframeKey), smcProConfig)
        } else null
    }

    val openTimeToIndex: Map<Long, Int> = remember(candles.size, candles.firstOrNull()?.openTime, candles.lastOrNull()?.openTime) {
        val map = HashMap<Long, Int>(candles.size)
        for (i in candles.indices) {
            map[candles[i].openTime] = i
        }
        map
    }

    val cachedAmdZones = remember(candles.size, candles.firstOrNull()?.openTime, candles.lastOrNull()?.openTime, showAmd) {
        if (!showAmd || candles.isEmpty()) emptyList() else computeAmdSessionZones(candles)
    }

    var visibleBarsFloat by remember(symbol, timeframeKey) { mutableFloatStateOf(70f) }
    var priceScale by remember { mutableFloatStateOf(1f) }
    var priceOffset by remember { mutableStateOf(0.0) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var isLiveMode by remember(symbol, timeframeKey) { mutableStateOf(true) }
    var manualRightCandleOpenTime by remember(symbol, timeframeKey) { mutableLongStateOf(-1L) }
    var panPixelRemainder by remember(symbol, timeframeKey) { mutableFloatStateOf(0f) }
    var crosshairOpenTime by remember(symbol, timeframeKey) { mutableStateOf<Long?>(null) }

    val totalCandles = candles.size
    val visibleDataBars = visibleBarsFloat.roundToInt().coerceIn(20, 200)

    val rightPaddingBars = if (isLiveMode) {
        (visibleDataBars * 0.10f).roundToInt().coerceIn(5, 16)
    } else {
        0
    }
    val totalSlotsInViewport = (visibleDataBars + rightPaddingBars).coerceAtLeast(24)

    val endIndexExclusive: Int = if (totalCandles == 0) {
        0
    } else if (isLiveMode || manualRightCandleOpenTime <= 0L) {
        totalCandles
    } else {
        val foundIdx = findCandleIndexByOpenTime(candles, manualRightCandleOpenTime)
        (foundIdx + 1).coerceIn(1, totalCandles)
    }

    val startIndex: Int = (endIndexExclusive - visibleDataBars).coerceAtLeast(0)

    val currentVisiblePriceSpan = remember(candles, startIndex, endIndexExclusive, priceScale) {
        if (startIndex >= endIndexExclusive || candles.isEmpty()) {
            100.0
        } else {
            var minP = Double.MAX_VALUE
            var maxP = -Double.MAX_VALUE
            for (i in startIndex until endIndexExclusive) {
                val c = candles[i]
                if (c.low < minP) minP = c.low
                if (c.high > maxP) maxP = c.high
            }
            if (minP >= maxP || minP == Double.MAX_VALUE) {
                val ref = candles.lastOrNull()?.close ?: 1.0
                minP = ref * 0.995
                maxP = ref * 1.005
            }
            val rawSpan = (maxP - minP).coerceAtLeast(minP * 0.0005)
            val scaledSpan = rawSpan / priceScale.coerceAtLeast(0.1f)
            (scaledSpan * 1.25).coerceAtLeast(1e-6)
        }
    }

    val inspectedIdx: Int? = remember(candles, crosshairOpenTime) {
        val targetTime = crosshairOpenTime ?: return@remember null
        val idx = findCandleIndexByOpenTime(candles, targetTime)
        if (idx in candles.indices) idx else null
    }
    val activeIdx = inspectedIdx ?: (endIndexExclusive - 1).coerceIn(0, (totalCandles - 1).coerceAtLeast(0))
    val activeCandle = candles.getOrNull(activeIdx)
    val rankedSupports = remember(supportLevels, activeCandle?.close) {
        val currentPrice = activeCandle?.close ?: 0.0
        supportLevels.sortedBy { abs(it - currentPrice) }.mapIndexed { idx, price -> price to "S${idx + 1}" }
    }
    val rankedResistances = remember(resistanceLevels, activeCandle?.close) {
        val currentPrice = activeCandle?.close ?: 0.0
        resistanceLevels.sortedBy { abs(it - currentPrice) }.mapIndexed { idx, price -> price to "R${idx + 1}" }
    }

    val surfaceColor = if (darkMode) Color(0xFF0A0E17) else Color(0xFFFFFFFF)
    val toolbarBgColor = if (darkMode) Color(0xFF0F1624) else Color(0xFFF1F5F9)
    val gridColor = if (darkMode) Color(0xFF162032) else Color(0xFFE2E8F0)
    val axisTextColor = if (darkMode) Color(0xFF8B9BB4) else Color(0xFF475569)

    fun switchToLiveMode() {
        isLiveMode = true
        manualRightCandleOpenTime = -1L
        panPixelRemainder = 0f
    }

    fun resetChartZoomAndGoLive() {
        visibleBarsFloat = 70f
        priceScale = 1f
        priceOffset = 0.0
        isLiveMode = true
        manualRightCandleOpenTime = -1L
        panPixelRemainder = 0f
        crosshairOpenTime = null
    }

    fun zoomByFactor(factor: Float) {
        visibleBarsFloat = (visibleBarsFloat * factor).coerceIn(20f, 200f)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(surfaceColor)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .testTag("candlestick_chart_container")
    ) {
        val mainChartWeight = when {
            showRsi && showMacd -> 0.64f
            showRsi -> 0.82f
            showMacd -> 0.80f
            else -> 1.0f
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(mainChartWeight)
        ) {
            if (candles.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Loading market candles...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = axisTextColor,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            } else if (engineMode == ChartEngineMode.TV_LIGHTWEIGHT_JS) {
                TradingViewLightweightWebView(
                    symbol = symbol,
                    timeframeKey = timeframeKey,
                    candles = candles,
                    indicators = indicators,
                    showEma = showEma,
                    showVolume = showVolume,
                    isLiveMode = isLiveMode,
                    darkMode = darkMode,
                    onUserManualPan = { isLiveMode = false },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val textMeasurer = rememberTextMeasurer()
                val labelMeasurer = remember(textMeasurer) { ChartLabelMeasurer(textMeasurer) }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(symbol, timeframeKey, totalCandles) {
                            detectTapGestures(
                                onDoubleTap = {
                                    resetChartZoomAndGoLive()
                                },
                                onLongPress = { offset ->
                                    val rightAxisPx = 68.dp.toPx()
                                    val plotW = (size.width - rightAxisPx).coerceAtLeast(1f)
                                    if (offset.x <= plotW && candles.isNotEmpty()) {
                                        val slotIdx = ((offset.x / plotW) * totalSlotsInViewport).toInt()
                                        val candleIdx = (startIndex + slotIdx).coerceIn(startIndex, (endIndexExclusive - 1).coerceAtLeast(0))
                                        crosshairOpenTime = candles.getOrNull(candleIdx)?.openTime
                                    }
                                },
                                onTap = { offset ->
                                    if (crosshairOpenTime != null) {
                                        crosshairOpenTime = null
                                    } else {
                                        val rightAxisPx = 68.dp.toPx()
                                        val plotW = (size.width - rightAxisPx).coerceAtLeast(1f)
                                        if (offset.x <= plotW && candles.isNotEmpty()) {
                                            val slotIdx = ((offset.x / plotW) * totalSlotsInViewport).toInt()
                                            val candleIdx = (startIndex + slotIdx).coerceIn(startIndex, (endIndexExclusive - 1).coerceAtLeast(0))
                                            crosshairOpenTime = candles.getOrNull(candleIdx)?.openTime
                                        }
                                    }
                                }
                            )
                        }
                        .pointerInput(symbol, timeframeKey, totalCandles) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var currentDragLock = DragLock.NONE
                                var cumulativePanX = 0f
                                var cumulativePanY = 0f
                                do {
                                    val event = awaitPointerEvent()
                                    val pointerCount = event.changes.count { it.pressed }
                                    if (pointerCount >= 2) {
                                        currentDragLock = DragLock.NONE
                                        val zoom = event.calculateZoom()
                                        if (abs(zoom - 1f) > 0.005f) {
                                            visibleBarsFloat = (visibleBarsFloat / zoom).coerceIn(20f, 200f)
                                            priceScale = (priceScale * zoom).coerceIn(0.2f, 10f)
                                        }
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    } else if (pointerCount == 1) {
                                        val pan = event.calculatePan()
                                        val rightAxisPx = 68.dp.toPx()
                                        val canvasW = canvasSize.width.toFloat().coerceAtLeast(1f)
                                        val plotW = (canvasW - rightAxisPx).coerceAtLeast(1f)

                                        if (crosshairOpenTime != null) {
                                            val fingerX = event.changes.first().position.x.coerceIn(0f, plotW)
                                            val slotIdx = ((fingerX / plotW) * totalSlotsInViewport).toInt()
                                            val candleIdx = (startIndex + slotIdx).coerceIn(startIndex, (endIndexExclusive - 1).coerceAtLeast(0))
                                            crosshairOpenTime = candles.getOrNull(candleIdx)?.openTime
                                        } else {
                                            // Direction lock evaluation to prevent wild diagonal wandering
                                            if (currentDragLock == DragLock.NONE) {
                                                cumulativePanX += abs(pan.x)
                                                cumulativePanY += abs(pan.y)
                                                if (cumulativePanX > 5f || cumulativePanY > 5f) {
                                                    currentDragLock = if (cumulativePanX > cumulativePanY * 1.15f) DragLock.HORIZONTAL else DragLock.VERTICAL
                                                }
                                            }

                                            // 1 jari (Pan Vertikal): jika bukan dikunci horizontal
                                            if (currentDragLock != DragLock.HORIZONTAL && abs(pan.y) > 0.5f) {
                                                val canvasH = canvasSize.height.toFloat().coerceAtLeast(1f)
                                                val deltaPrice = (pan.y / canvasH) * currentVisiblePriceSpan
                                                priceOffset += deltaPrice
                                            }

                                            // 1 jari (Pan Horizontal): jika bukan dikunci vertikal
                                            if (currentDragLock != DragLock.VERTICAL && abs(pan.x) > 0.5f && candles.isNotEmpty()) {
                                                val slotPx = (plotW / totalSlotsInViewport.toFloat()).coerceAtLeast(3f)
                                                panPixelRemainder += pan.x
                                                val deltaBars = (panPixelRemainder / slotPx).toInt()
                                                if (deltaBars != 0) {
                                                    panPixelRemainder -= deltaBars * slotPx
                                                    val currentEndIdx = if (isLiveMode || manualRightCandleOpenTime <= 0L) {
                                                        candles.lastIndex
                                                    } else {
                                                        openTimeToIndex[manualRightCandleOpenTime] ?: findCandleIndexByOpenTime(candles, manualRightCandleOpenTime)
                                                    }
                                                    val minEndIdx = min(candles.lastIndex, 1)
                                                    val newEndIdx = (currentEndIdx - deltaBars).coerceIn(minEndIdx, candles.lastIndex)

                                                    if (newEndIdx < candles.lastIndex) {
                                                        isLiveMode = false
                                                        manualRightCandleOpenTime = candles[newEndIdx].openTime
                                                    } else {
                                                        isLiveMode = true
                                                        manualRightCandleOpenTime = -1L
                                                    }
                                                }
                                            }
                                        }
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .testTag("candlestick_canvas")
                ) {
                    drawMainCandlestickChart(
                        candles = candles,
                        indicators = indicators,
                        startIndex = startIndex,
                        endIndexExclusive = endIndexExclusive,
                        totalSlotsInViewport = totalSlotsInViewport,
                        crosshairIndex = inspectedIdx,
                        showSupportResistance = if (showSupportResistance) true else false,
                        showSmc = showSmc,
                        smcProV5 = smcProV5,
                        showIct = showIct,
                        showAmd = showAmd,
                        marketStructure = marketStructure,
                        smcAnalysis = smcAnalysis,
                        openTimeToIndex = openTimeToIndex,
                        cachedAmdZones = cachedAmdZones,
                        rankedSupports = if (showSupportResistance) rankedSupports else emptyList(),
                        rankedResistances = if (showSupportResistance) rankedResistances else emptyList(),
                        activeSignal = if (showSignalOverlay) activeSignalOverlay else null,
                        historicalSignals = if (showSignalOverlay && showSignalHistory) historicalSignalOverlays.take(5) else emptyList(),
                        selectedJournalOverlay = selectedJournalOverlay,
                        tradePlanOverlay = if (showTradePlanOverlay) tradePlanOverlay else null,
                        showEma = showEma,
                        showSma = showSma,
                        showBollingerBands = showBollingerBands,
                        showVolume = showVolume,
                        gridColor = gridColor,
                        axisTextColor = axisTextColor,
                        textMeasurer = textMeasurer,
                        labelMeasurer = labelMeasurer,
                        priceScale = priceScale,
                        priceOffset = priceOffset
                    )
                }
            }

            // Top-Left Overlay Stack: Indicator Legends (EMA, SMA, BB, ATR) + Compact Signal Overlay Summary
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = 6.dp, end = 72.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (activeIdx in candles.indices && inspectedIdx == null) {
                    val params = indicators.params
                    val eFast = indicators.chartEma1.getOrNull(activeIdx)
                    val eMid = indicators.chartEma2.getOrNull(activeIdx)
                    val eSlow = indicators.chartEma3.getOrNull(activeIdx)
                    if (showEma && (eFast != null || eMid != null || eSlow != null)) {
                        FlowRow(
                            modifier = Modifier
                                .background(surfaceColor.copy(alpha = 0.78f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                .testTag("ema_legend_overlay"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            eFast?.let {
                                Text(
                                    text = "EMA${params.ema1Period} ${formatCryptoPrice(it)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Ema20Color,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            eMid?.let {
                                Text(
                                    text = "EMA${params.ema2Period} ${formatCryptoPrice(it)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Ema50Color,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            eSlow?.let {
                                Text(
                                    text = "EMA${params.ema3Period} ${formatCryptoPrice(it)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Ema200Color,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    val sFast = indicators.chartSma1.getOrNull(activeIdx)
                    val sSlow = indicators.chartSma2.getOrNull(activeIdx)
                    if (showSma && (sFast != null || sSlow != null)) {
                        FlowRow(
                            modifier = Modifier
                                .background(surfaceColor.copy(alpha = 0.78f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                .testTag("sma_legend_overlay"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            sFast?.let {
                                Text(
                                    text = "SMA${params.sma1Period} ${formatCryptoPrice(it)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF2DD4BF),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            sSlow?.let {
                                Text(
                                    text = "SMA${params.sma2Period} ${formatCryptoPrice(it)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFF472B6),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    val bbPoint = indicators.bollingerBands.getOrNull(activeIdx)
                    val atrVal = indicators.chartAtr.getOrNull(activeIdx)
                    if ((showBollingerBands && bbPoint?.middle != null) || (showAtr && atrVal != null)) {
                        FlowRow(
                            modifier = Modifier
                                .background(surfaceColor.copy(alpha = 0.78f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                .testTag("bb_atr_legend_overlay"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (showBollingerBands && bbPoint?.upper != null && bbPoint.lower != null) {
                                Text(
                                    text = "BB(${params.bbPeriod}) ${formatCryptoPrice(bbPoint.upper)} / ${formatCryptoPrice(bbPoint.lower)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF818CF8),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            if (showAtr && atrVal != null) {
                                Text(
                                    text = "ATR(${params.atrPeriod}) ${formatCryptoPrice(atrVal)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeutralAmber,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    if ((showSmc || showIct || showAmd) && inspectedIdx == null) {
                        FlowRow(
                            modifier = Modifier
                                .background(surfaceColor.copy(alpha = 0.78f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                .testTag("smc_ict_amd_legend_overlay"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (showSmc) {
                                Text(
                                    text = "SMC [Swings/BOS/CHoCH]",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyanAccent,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            if (showIct) {
                                Text(
                                    text = "ICT [OB/Sweep]",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BullishGreen,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            if (showAmd) {
                                Text(
                                    text = "AMD [Asia Range/Manip]",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeutralAmber,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                // Requirement #3: Compact Signal Overlay Info Pill on Chart (does not cover candlesticks)
                if (showSignalOverlay && activeSignalOverlay != null && inspectedIdx == null) {
                    CompactSignalOverlayBadge(
                        signal = activeSignalOverlay,
                        surfaceColor = surfaceColor
                    )
                }

                // Selected Journal Signal Overlay Info Pill (e.g. baseline or historical journal entry selected by user)
                if (selectedJournalOverlay != null && inspectedIdx == null) {
                    CompactJournalOverlayBadge(
                        signal = selectedJournalOverlay,
                        surfaceColor = surfaceColor,
                        onClear = onClearSelectedJournalOverlay
                    )
                }

                // V3.3: AI Trade Plan Overlay Badge with Lifecycle Status & Setup Cycling
                if (showTradePlanOverlay && tradePlanOverlay != null && inspectedIdx == null) {
                    val currentSetupIndex = availableTrackedSetups.indexOfFirst { it.setupId == tradePlanOverlay.setupId }
                    CompactTradePlanOverlayBadge(
                        overlay = tradePlanOverlay,
                        surfaceColor = surfaceColor,
                        availableSetupsCount = availableTrackedSetups.size,
                        onToggleVisibility = onToggleTradePlanOverlay,
                        onCycleNextSetup = {
                            if (availableTrackedSetups.isNotEmpty()) {
                                val nextIdx = if (currentSetupIndex >= 0) (currentSetupIndex + 1) % availableTrackedSetups.size else 0
                                onSelectTrackedSetup(availableTrackedSetups[nextIdx].setupId)
                            }
                        }
                    )
                }
            }

            if (inspectedIdx != null && activeCandle != null) {
                CrosshairOverlayCard(
                    candle = activeCandle,
                    onDismiss = { crosshairOpenTime = null },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = 6.dp, end = 72.dp)
                )
            }
        }

        // 2. RSI Sub-Panel
        if (showRsi && candles.isNotEmpty()) {
            HorizontalDivider(color = gridColor, thickness = 1.dp)
            val latestRsi = indicators.chartRsi.getOrNull(activeIdx)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.18f)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .testTag("rsi_subchart")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "RSI ${indicators.params.rsiPeriod}",
                            style = MaterialTheme.typography.labelSmall,
                            color = axisTextColor,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = latestRsi?.let { String.format(Locale.US, "%.1f", it) } ?: "--",
                            style = MaterialTheme.typography.labelSmall,
                            color = RsiLineColor,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Text(
                        text = "70 / 50 / 30",
                        style = MaterialTheme.typography.labelSmall,
                        color = axisTextColor.copy(alpha = 0.7f),
                        maxLines = 1,
                        softWrap = false
                    )
                }
                val textMeasurer = rememberTextMeasurer()
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRsiPanel(
                        rsiSeries = indicators.chartRsi,
                        startIndex = startIndex,
                        endIndexExclusive = endIndexExclusive,
                        totalSlotsInViewport = totalSlotsInViewport,
                        crosshairIndex = inspectedIdx,
                        gridColor = gridColor,
                        axisTextColor = axisTextColor,
                        textMeasurer = textMeasurer
                    )
                }
            }
        }

        // 3. MACD Sub-Panel
        if (showMacd && candles.isNotEmpty()) {
            HorizontalDivider(color = gridColor, thickness = 1.dp)
            val latestMacd = indicators.chartMacd.getOrNull(activeIdx)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.20f)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .testTag("macd_subchart")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "MACD(${indicators.params.macdFast},${indicators.params.macdSlow},${indicators.params.macdSignal}) ${latestMacd?.macd?.let { String.format(Locale.US, "%.2f", it) } ?: "--"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MacdLineColor,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = "Sig ${latestMacd?.signal?.let { String.format(Locale.US, "%.2f", it) } ?: "--"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MacdSignalColor,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = "Hist ${latestMacd?.histogram?.let { String.format(Locale.US, "%.2f", it) } ?: "--"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if ((latestMacd?.histogram ?: 0.0) >= 0.0) BullishGreen else BearishRed,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawMacdPanel(
                        macdSeries = indicators.chartMacd,
                        startIndex = startIndex,
                        endIndexExclusive = endIndexExclusive,
                        totalSlotsInViewport = totalSlotsInViewport,
                        crosshairIndex = inspectedIdx,
                        gridColor = gridColor
                    )
                }
            }
        }

        // 4. Docked Chart Toolbar at Bottom of Chart Container
        HorizontalDivider(color = gridColor, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(toolbarBgColor)
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DockedToolbarButton(
                    text = "[ + ]",
                    tag = "chart_zoom_in_button",
                    onClick = { zoomByFactor(0.8f) }
                )
                DockedToolbarButton(
                    text = "[ − ]",
                    tag = "chart_zoom_out_button",
                    onClick = { zoomByFactor(1.25f) }
                )
                DockedToolbarButton(
                    text = "[ ↻ ]",
                    tag = "chart_reset_button",
                    onClick = { resetChartZoomAndGoLive() }
                )
                DockedToolbarButton(
                    text = "[ fx ]",
                    tag = "chart_toolbar_indicators_button",
                    isHighlighted = showEma || showSma || showBollingerBands || showVolume || showRsi || showMacd || showAtr || showSupportResistance || showSmc || showIct || showAmd,
                    onClick = onOpenIndicatorsDialog
                )
                DockedToolbarButton(
                    text = if (isFullscreen) "[ ✕ ]" else "[ ⛶ ]",
                    tag = "chart_fullscreen_button",
                    isHighlighted = isFullscreen,
                    onClick = onToggleFullscreen
                )
            }

            DockedToolbarButton(
                text = if (isLiveMode) "[ LIVE ]" else "[ ↗ LIVE ]",
                tag = "chart_go_to_live_button",
                isHighlighted = true,
                highlightColor = if (isLiveMode) BullishGreen else NeutralAmber,
                onClick = { switchToLiveMode() }
            )
        }
    }
}

/**
 * Requirement #3: Compact Signal Information Overlay on Chart:
 * Shows Direction (`LONG`/`SHORT`), `Confidence 78%`, `Status`, `Entry`, `SL`, `TP1`
 * without covering the candle chart.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactSignalOverlayBadge(
    signal: ChartSignalOverlay,
    surfaceColor: Color
) {
    val sideColor = if (signal.isLong) BullishGreen else BearishRed
    val statusColor = when {
        signal.status.code.startsWith("TP") -> BullishGreen
        signal.status.code == "SL_HIT" -> BearishRed
        else -> CyanAccent
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(surfaceColor.copy(alpha = 0.84f))
            .border(1.dp, sideColor.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .testTag("chart_signal_overlay_badge"),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            val sourcePrefix = if (signal.isBaseline) "[BASELINE EMA] " else ""
            Text(
                text = "$sourcePrefix${signal.decision} · Grade ${signal.setupQuality}",
                style = MaterialTheme.typography.labelSmall,
                color = sideColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = "Conf ${signal.confidence}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = "[${signal.status.displayLabel}]",
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
            if (signal.botActionLabel.isNotBlank()) {
                Text(
                    text = signal.botActionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralAmber,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = "Entry ${formatCryptoPrice(signal.entry)}",
                style = MaterialTheme.typography.labelSmall,
                color = CyanAccent,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = "SL ${formatCryptoPrice(signal.stopLoss)}",
                style = MaterialTheme.typography.labelSmall,
                color = BearishRed,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = "TP1 ${formatCryptoPrice(signal.tp1)}",
                style = MaterialTheme.typography.labelSmall,
                color = BullishGreen,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactJournalOverlayBadge(
    signal: ChartSignalOverlay,
    surfaceColor: Color,
    onClear: () -> Unit
) {
    val sideColor = if (signal.isLong) BullishGreen else BearishRed
    val srcLabel = if (signal.isBaseline) "BASELINE JOURNAL" else "AI JOURNAL"

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(surfaceColor.copy(alpha = 0.90f))
            .border(1.dp, CyanAccent.copy(alpha = 0.7f), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .testTag("chart_journal_overlay_badge"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = "[$srcLabel]",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "${signal.decision} · Grade ${signal.setupQuality}",
                    style = MaterialTheme.typography.labelSmall,
                    color = sideColor,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "[${signal.status.displayLabel}]",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = "Entry: ${formatCryptoPrice(signal.entry)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent,
                    fontFamily = JetBrainsMonoFontFamily,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "SL: ${formatCryptoPrice(signal.stopLoss)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = BearishRed,
                    fontFamily = JetBrainsMonoFontFamily,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "TP1: ${formatCryptoPrice(signal.tp1)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = BullishGreen,
                    fontFamily = JetBrainsMonoFontFamily,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        Text(
            text = "✕",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable { onClear() }
                .padding(4.dp)
                .testTag("clear_journal_overlay_btn")
        )
    }
}

@Composable
private fun DockedToolbarButton(
    text: String,
    tag: String,
    isHighlighted: Boolean = false,
    highlightColor: Color = CyanAccent,
    onClick: () -> Unit
) {
    val bg = if (isHighlighted) {
        highlightColor.copy(alpha = 0.16f)
    } else {
        Color.Transparent
    }
    val textCol = if (isHighlighted) highlightColor else MaterialTheme.colorScheme.onSurface
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .defaultMinSize(minWidth = 40.dp, minHeight = 34.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag(tag)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = textCol,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrosshairOverlayCard(
    candle: Candle,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US) }
    val cColor = if (candle.isBullish) BullishGreen else BearishRed

    Surface(
        color = Color(0xEB0F172A),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
            .border(1.dp, CyanAccent.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .clickable { onDismiss() }
            .testTag("crosshair_info_overlay")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = dateFormat.format(Date(candle.openTime)),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFCBD5E1),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "O ${formatCryptoPrice(candle.open)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFE2E8F0),
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "H ${formatCryptoPrice(candle.high)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = BullishGreen,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "L ${formatCryptoPrice(candle.low)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = BearishRed,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "C ${formatCryptoPrice(candle.close)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = cColor,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "Vol ${formatCompactVolume(candle.volume)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

private fun findCandleIndexByOpenTime(candles: List<Candle>, openTime: Long): Int {
    if (candles.isEmpty()) return 0
    val idx = candles.binarySearchBy(openTime) { it.openTime }
    return if (idx >= 0) {
        idx
    } else {
        (-idx - 2).coerceIn(0, candles.lastIndex)
    }
}

private fun DrawScope.drawMainCandlestickChart(
    candles: List<Candle>,
    indicators: IndicatorSeries,
    startIndex: Int,
    endIndexExclusive: Int,
    totalSlotsInViewport: Int,
    crosshairIndex: Int?,
    showSupportResistance: Boolean,
    showSmc: Boolean,
    smcProV5: SmcEntryProV5Engine.Result?,
    showIct: Boolean,
    showAmd: Boolean,
    marketStructure: MarketStructureAnalysis?,
    smcAnalysis: SmcAnalysisSnapshot?,
    openTimeToIndex: Map<Long, Int>,
    cachedAmdZones: List<CachedAmdSessionZone>,
    rankedSupports: List<Pair<Double, String>>,
    rankedResistances: List<Pair<Double, String>>,
    activeSignal: ChartSignalOverlay?,
    historicalSignals: List<ChartSignalOverlay>,
    tradePlanOverlay: AiTradePlanChartOverlay? = null,
    selectedJournalOverlay: ChartSignalOverlay? = null,
    showEma: Boolean,
    showSma: Boolean,
    showBollingerBands: Boolean,
    showVolume: Boolean,
    gridColor: Color,
    axisTextColor: Color,
    textMeasurer: TextMeasurer,
    labelMeasurer: ChartLabelMeasurer,
    priceScale: Float = 1f,
    priceOffset: Double = 0.0
) {
    if (startIndex >= endIndexExclusive || candles.isEmpty()) return

    val rightAxisWidth = 68.dp.toPx()
    val plotWidth = (size.width - rightAxisWidth).coerceAtLeast(10f)
    val totalHeight = size.height

    var minPrice = Double.MAX_VALUE
    var maxPrice = -Double.MAX_VALUE
    var maxVolume = 0.0

    for (i in startIndex until endIndexExclusive) {
        val c = candles[i]
        if (c.low < minPrice) minPrice = c.low
        if (c.high > maxPrice) maxPrice = c.high
        if (c.volume > maxVolume) maxVolume = c.volume
        if (showBollingerBands) {
            val bb = indicators.bollingerBands.getOrNull(i)
            bb?.lower?.let { if (it < minPrice) minPrice = it }
            bb?.upper?.let { if (it > maxPrice) maxPrice = it }
        }
    }

    if (minPrice >= maxPrice || minPrice == Double.MAX_VALUE) {
        val ref = candles.lastOrNull()?.close ?: 1.0
        minPrice = ref * 0.995
        maxPrice = ref * 1.005
    }

    val candleSpan = (maxPrice - minPrice).coerceAtLeast(minPrice * 0.0005)
    // Expand slightly if activeSignal or selectedJournalOverlay levels are close to visible candle range
    val focusSignal = activeSignal ?: selectedJournalOverlay
    if (focusSignal != null) {
        val maxExpandDistance = candleSpan * 1.35
        val candidateLevels = listOf(
            focusSignal.entry,
            focusSignal.stopLoss,
            focusSignal.tp1,
            focusSignal.tp2,
            focusSignal.tp3
        )
        for (lvl in candidateLevels) {
            if (lvl > 0.0) {
                if (lvl < minPrice && (minPrice - lvl) <= maxExpandDistance) {
                    minPrice = lvl
                }
                if (lvl > maxPrice && (lvl - maxPrice) <= maxExpandDistance) {
                    maxPrice = lvl
                }
            }
        }
    }

    val centerPrice = (maxPrice + minPrice) / 2.0 + priceOffset
    val scaledSpan = (maxPrice - minPrice).coerceAtLeast(minPrice * 0.0005) / priceScale.coerceAtLeast(0.1f)
    val halfSpan = scaledSpan / 2.0
    val scaledMax = (centerPrice + halfSpan) + (scaledSpan * 0.09)
    val scaledMin = (centerPrice - halfSpan) - (scaledSpan * 0.16)
    val priceSpan = (scaledMax - scaledMin).coerceAtLeast(1e-8)

    val safePriceSpan = if (priceSpan > 0.0 && !priceSpan.isNaN() && !priceSpan.isInfinite()) priceSpan else 1.0
    fun priceToY(p: Double): Float {
        if (p.isNaN() || p.isInfinite()) return totalHeight / 2f
        val ratio = ((scaledMax - p) / safePriceSpan).toFloat()
        if (ratio.isNaN() || ratio.isInfinite()) return totalHeight / 2f
        return (ratio * totalHeight).coerceIn(-totalHeight * 2f, totalHeight * 3f)
    }

    val labelStyle = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = 9.5.sp,
        color = axisTextColor,
        fontFeatureSettings = "tnum"
    )

    val gridSteps = 5
    for (g in 1 until gridSteps) {
        val frac = g.toFloat() / gridSteps
        val y = frac * totalHeight
        val priceAtGrid = scaledMax - (frac * priceSpan)
        drawLine(
            color = gridColor,
            start = Offset(0f, y),
            end = Offset(plotWidth, y),
            strokeWidth = 1f
        )
        drawText(
            textMeasurer = textMeasurer,
            text = formatCryptoPrice(priceAtGrid),
            style = labelStyle,
            topLeft = Offset(plotWidth + 4.dp.toPx(), (y - 6.dp.toPx()).coerceIn(2f, (totalHeight - 14.dp.toPx()).coerceAtLeast(2f))),
            overflow = TextOverflow.Clip,
            softWrap = false,
            maxLines = 1
        )
    }

    drawLine(
        color = gridColor,
        start = Offset(plotWidth, 0f),
        end = Offset(plotWidth, totalHeight),
        strokeWidth = 1f
    )

    val slotWidth = plotWidth / totalSlotsInViewport.toFloat()
    val bodyWidth = (slotWidth * 0.74f).coerceIn(2f, 22f)
    val volumeOverlayMaxH = totalHeight * 0.125f

    clipRect(left = 0f, top = 0f, right = plotWidth, bottom = totalHeight) {
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)

        // BAGIAN 5: Visualisasi AMD (Accumulation - Manipulation - Distribution)
        if (showAmd && cachedAmdZones.isNotEmpty()) {
            drawAmdSessionZones(
                zones = cachedAmdZones,
                openTimeToIndex = openTimeToIndex,
                startIndex = startIndex,
                endIndexExclusive = endIndexExclusive,
                slotWidth = slotWidth,
                plotWidth = plotWidth,
                totalHeight = totalHeight,
                scaledMin = scaledMin,
                scaledMax = scaledMax,
                priceToY = ::priceToY,
                labelMeasurer = labelMeasurer,
                textMeasurer = textMeasurer
            )
        }

        // BAGIAN 4: Visualisasi ICT (Order Blocks & Liquidity Sweeps)
        if (showIct) {
            drawIctOverlays(
                candles = candles,
                openTimeToIndex = openTimeToIndex,
                startIndex = startIndex,
                endIndexExclusive = endIndexExclusive,
                slotWidth = slotWidth,
                plotWidth = plotWidth,
                totalHeight = totalHeight,
                scaledMin = scaledMin,
                scaledMax = scaledMax,
                priceToY = ::priceToY,
                labelMeasurer = labelMeasurer,
                textMeasurer = textMeasurer,
                marketStructure = marketStructure,
                smcAnalysis = smcAnalysis
            )
        }

        // BAGIAN 1: Support & Resistance horizontal lines with S1/S2/S3 & R1/R2/R3 labels
        if (showSupportResistance) {
            val pivotLabelStyle = ChartPivotLabelStyle.copy(color = axisTextColor)
            val occupiedPillTops = ArrayList<Float>(8)
            val minPillSpacing = 14.dp.toPx()

            for ((sup, label) in rankedSupports) {
                if (sup in scaledMin..scaledMax) {
                    val y = priceToY(sup)
                    drawLine(
                        color = BullishGreen.copy(alpha = 0.5f),
                        start = Offset(0f, y),
                        end = Offset(plotWidth, y),
                        strokeWidth = 1.2f,
                        pathEffect = DashEffectSr
                    )
                    val textLayout = labelMeasurer.measure(label, pivotLabelStyle)
                    val padH = 4.dp.toPx()
                    val padV = 2.dp.toPx()
                    val pillW = textLayout.size.width + padH * 2
                    val pillH = textLayout.size.height + padV * 2
                    val pillLeft = (plotWidth - pillW - 3.dp.toPx()).coerceAtLeast(0f)
                    val pillTop = (y - pillH / 2f).coerceIn(2f, totalHeight - pillH - 2f)

                    val hasCollision = occupiedPillTops.any { abs(it - pillTop) < minPillSpacing }
                    if (!hasCollision) {
                        occupiedPillTops.add(pillTop)
                        drawRoundRect(
                            color = Color(0xFF0F172A),
                            topLeft = Offset(pillLeft, pillTop),
                            size = Size(pillW, pillH),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                        )
                        drawRoundRect(
                            color = BullishGreen.copy(alpha = 0.8f),
                            topLeft = Offset(pillLeft, pillTop),
                            size = Size(pillW, pillH),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                            style = Stroke(width = 1f)
                        )
                        drawText(
                            textMeasurer = textMeasurer,
                            text = label,
                            style = pivotLabelStyle,
                            topLeft = Offset(pillLeft + padH, pillTop + padV)
                        )
                    }
                }
            }
            for ((res, label) in rankedResistances) {
                if (res in scaledMin..scaledMax) {
                    val y = priceToY(res)
                    drawLine(
                        color = BearishRed.copy(alpha = 0.5f),
                        start = Offset(0f, y),
                        end = Offset(plotWidth, y),
                        strokeWidth = 1.2f,
                        pathEffect = DashEffectSr
                    )
                    val textLayout = labelMeasurer.measure(label, pivotLabelStyle)
                    val padH = 4.dp.toPx()
                    val padV = 2.dp.toPx()
                    val pillW = textLayout.size.width + padH * 2
                    val pillH = textLayout.size.height + padV * 2
                    val pillLeft = (plotWidth - pillW - 3.dp.toPx()).coerceAtLeast(0f)
                    val pillTop = (y - pillH / 2f).coerceIn(2f, totalHeight - pillH - 2f)

                    val hasCollision = occupiedPillTops.any { abs(it - pillTop) < minPillSpacing }
                    if (!hasCollision) {
                        occupiedPillTops.add(pillTop)
                        drawRoundRect(
                            color = Color(0xFF0F172A),
                            topLeft = Offset(pillLeft, pillTop),
                            size = Size(pillW, pillH),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                        )
                        drawRoundRect(
                            color = BearishRed.copy(alpha = 0.8f),
                            topLeft = Offset(pillLeft, pillTop),
                            size = Size(pillW, pillH),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                            style = Stroke(width = 1f)
                        )
                        drawText(
                            textMeasurer = textMeasurer,
                            text = label,
                            style = pivotLabelStyle,
                            topLeft = Offset(pillLeft + padH, pillTop + padV)
                        )
                    }
                }
            }
        }

        // Optional Historical Signal Overlays (faint, only when Show Signal History = ON)
        for (histSig in historicalSignals) {
            drawSignalOverlayLines(
                signal = histSig,
                scaledMin = scaledMin,
                scaledMax = scaledMax,
                plotWidth = plotWidth,
                totalHeight = totalHeight,
                priceToY = ::priceToY,
                textMeasurer = textMeasurer,
                isHistorical = true
            )
        }

        // 1. Subtle Volume Overlay at bottom 12.5% of chart height
        if (showVolume && maxVolume > 0.0) {
            for (i in startIndex until endIndexExclusive) {
                val localIdx = i - startIndex
                val centerX = (localIdx + 0.5f) * slotWidth
                val c = candles[i]
                val col = if (c.isBullish) BullishGreen.copy(alpha = 0.22f) else BearishRed.copy(alpha = 0.22f)
                val volRatio = (c.volume / maxVolume).toFloat().coerceIn(0f, 1f)
                val barH = volRatio * volumeOverlayMaxH
                drawRect(
                    color = col,
                    topLeft = Offset(centerX - bodyWidth / 2f, totalHeight - barH),
                    size = Size(bodyWidth, barH)
                )
            }
        }

        // 2. Candlesticks centered across the main chart height
        for (i in startIndex until endIndexExclusive) {
            val localIdx = i - startIndex
            val centerX = (localIdx + 0.5f) * slotWidth
            val c = candles[i]
            val color = if (c.isBullish) BullishGreen else BearishRed

            val highY = priceToY(c.high)
            val lowY = priceToY(c.low)
            drawLine(
                color = color,
                start = Offset(centerX, highY),
                end = Offset(centerX, lowY),
                strokeWidth = max(1.2f, bodyWidth * 0.16f)
            )

            val openY = priceToY(c.open)
            val closeY = priceToY(c.close)
            val topY = min(openY, closeY)
            val bottomY = max(openY, closeY)
            val bodyH = (bottomY - topY).coerceAtLeast(1.8f)

            drawRect(
                color = color,
                topLeft = Offset(centerX - bodyWidth / 2f, topY),
                size = Size(bodyWidth, bodyH)
            )
        }

        // 3. Bollinger Bands Overlay (Upper, Basis, Lower + Translucent Band Fill)
        if (showBollingerBands) {
            val bbColor = Color(0xFF818CF8)
            val fillPath = Path()
            val lowerPoints = mutableListOf<Offset>()
            var startedFill = false
            for (i in startIndex until endIndexExclusive) {
                val pt = indicators.bollingerBands.getOrNull(i)
                val up = pt?.upper
                val low = pt?.lower
                if (up != null && low != null) {
                    val x = (i - startIndex + 0.5f) * slotWidth
                    val upY = priceToY(up)
                    val lowY = priceToY(low)
                    if (!startedFill) {
                        fillPath.moveTo(x, upY)
                        startedFill = true
                    } else {
                        fillPath.lineTo(x, upY)
                    }
                    lowerPoints.add(Offset(x, lowY))
                }
            }
            if (startedFill && lowerPoints.isNotEmpty()) {
                for ( idx in lowerPoints.indices.reversed()) {
                    val pt = lowerPoints[idx]
                    fillPath.lineTo(pt.x, pt.y)
                }
                fillPath.close()
                drawPath(path = fillPath, color = bbColor.copy(alpha = 0.09f))
            }

            drawSeriesPath(
                series = indicators.bollingerBands.map { it.upper },
                startIndex = startIndex,
                endIndexExclusive = endIndexExclusive,
                slotWidth = slotWidth,
                valToY = ::priceToY,
                color = bbColor.copy(alpha = 0.85f),
                strokeWidth = 1.2.dp.toPx()
            )
            drawSeriesPath(
                series = indicators.bollingerBands.map { it.middle },
                startIndex = startIndex,
                endIndexExclusive = endIndexExclusive,
                slotWidth = slotWidth,
                valToY = ::priceToY,
                color = bbColor.copy(alpha = 0.55f),
                strokeWidth = 1.dp.toPx()
            )
            drawSeriesPath(
                series = indicators.bollingerBands.map { it.lower },
                startIndex = startIndex,
                endIndexExclusive = endIndexExclusive,
                slotWidth = slotWidth,
                valToY = ::priceToY,
                color = bbColor.copy(alpha = 0.85f),
                strokeWidth = 1.2.dp.toPx()
            )
        }

        // 3B. SMA Fast & Slow Overlay Lines
        if (showSma) {
            drawSeriesPath(
                series = indicators.chartSma1,
                startIndex = startIndex,
                endIndexExclusive = endIndexExclusive,
                slotWidth = slotWidth,
                valToY = ::priceToY,
                color = Color(0xFF2DD4BF),
                strokeWidth = 1.4.dp.toPx()
            )
            drawSeriesPath(
                series = indicators.chartSma2,
                startIndex = startIndex,
                endIndexExclusive = endIndexExclusive,
                slotWidth = slotWidth,
                valToY = ::priceToY,
                color = Color(0xFFF472B6),
                strokeWidth = 1.4.dp.toPx()
            )
        }

        // 3C. EMA Fast / Mid / Slow Overlay Lines
        if (showEma) {
            drawSeriesPath(
                series = indicators.chartEma1,
                startIndex = startIndex,
                endIndexExclusive = endIndexExclusive,
                slotWidth = slotWidth,
                valToY = ::priceToY,
                color = Ema20Color,
                strokeWidth = 1.5.dp.toPx()
            )
            drawSeriesPath(
                series = indicators.chartEma2,
                startIndex = startIndex,
                endIndexExclusive = endIndexExclusive,
                slotWidth = slotWidth,
                valToY = ::priceToY,
                color = Ema50Color,
                strokeWidth = 1.5.dp.toPx()
            )
            drawSeriesPath(
                series = indicators.chartEma3,
                startIndex = startIndex,
                endIndexExclusive = endIndexExclusive,
                slotWidth = slotWidth,
                valToY = ::priceToY,
                color = Ema200Color,
                strokeWidth = 1.5.dp.toPx()
            )
        }

        // 4. Requirement #3: Active Signal Overlay Horizontal Lines (ENTRY, SL, TP1, TP2, TP3)
        if (activeSignal != null) {
            drawSignalOverlayLines(
                signal = activeSignal,
                scaledMin = scaledMin,
                scaledMax = scaledMax,
                plotWidth = plotWidth,
                totalHeight = totalHeight,
                priceToY = ::priceToY,
                textMeasurer = textMeasurer,
                isHistorical = false
            )
        }

        // 4A. Selected Journal Signal Overlay Lines (when viewing historical/baseline journal setup on chart)
        if (selectedJournalOverlay != null && selectedJournalOverlay != activeSignal) {
            drawSignalOverlayLines(
                signal = selectedJournalOverlay,
                scaledMin = scaledMin,
                scaledMax = scaledMax,
                plotWidth = plotWidth,
                totalHeight = totalHeight,
                priceToY = ::priceToY,
                textMeasurer = textMeasurer,
                isHistorical = false
            )
        }

        // 4B. V3.3: AI Trade Plan Overlay Lines & Zones (Entry Band, SL, TP1-3, Invalidation)
        if (tradePlanOverlay != null && tradePlanOverlay.isVisible) {
            drawTradePlanOverlay(
                overlay = tradePlanOverlay,
                scaledMin = scaledMin,
                scaledMax = scaledMax,
                plotWidth = plotWidth,
                totalHeight = totalHeight,
                priceToY = ::priceToY,
                textMeasurer = textMeasurer
            )
        }

        // BAGIAN 3: Visualisasi SMC (Swings, BOS & CHoCH)
        if (showSmc) {
            drawSmcOverlays(
                candles = candles,
                openTimeToIndex = openTimeToIndex,
                startIndex = startIndex,
                endIndexExclusive = endIndexExclusive,
                slotWidth = slotWidth,
                plotWidth = plotWidth,
                totalHeight = totalHeight,
                scaledMin = scaledMin,
                scaledMax = scaledMax,
                priceToY = ::priceToY,
                labelMeasurer = labelMeasurer,
                textMeasurer = textMeasurer,
                marketStructure = marketStructure
            )
        }

        // Native SMC Entry PRO v5 shares the candle price scale; never adjusts autoscaling.
        if (smcProV5 != null && !smcProV5.htfUnavailable) {
            drawSmcEntryProV5Overlay(
                result = smcProV5,
                startIndex = startIndex,
                endIndexExclusive = endIndexExclusive,
                slotWidth = slotWidth,
                plotWidth = plotWidth,
                priceToY = ::priceToY,
                textMeasurer = textMeasurer
            )
        }

        // 5. Latest Price Line
        val latestCandle = candles.last()
        if (latestCandle.close in scaledMin..scaledMax) {
            val latestY = priceToY(latestCandle.close)
            val latestColor = if (latestCandle.isBullish) BullishGreen else BearishRed
            drawLine(
                color = latestColor.copy(alpha = 0.8f),
                start = Offset(0f, latestY),
                end = Offset(plotWidth, latestY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
            )
        }

        // 6. Crosshair Lines
        if (crosshairIndex != null && crosshairIndex in startIndex until endIndexExclusive) {
            val localIdx = crosshairIndex - startIndex
            val crossX = (localIdx + 0.5f) * slotWidth
            val inspected = candles[crosshairIndex]
            val crossY = priceToY(inspected.close)

            val crossDash = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
            drawLine(
                color = CyanAccent.copy(alpha = 0.85f),
                start = Offset(crossX, 0f),
                end = Offset(crossX, totalHeight),
                strokeWidth = 1.dp.toPx(),
                pathEffect = crossDash
            )
            drawLine(
                color = CyanAccent.copy(alpha = 0.85f),
                start = Offset(0f, crossY),
                end = Offset(plotWidth, crossY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = crossDash
            )
            drawCircle(
                color = CyanAccent,
                radius = 3.5.dp.toPx(),
                center = Offset(crossX, crossY)
            )
        }
    }

    // Latest Price Axis Tag on Right Price Scale
    val latestCandle = candles.last()
    if (latestCandle.close in scaledMin..scaledMax) {
        val latestY = priceToY(latestCandle.close)
        val latestColor = if (latestCandle.isBullish) BullishGreen else BearishRed
        val badgeH = 16.dp.toPx()
        val badgeTop = (latestY - badgeH / 2f).coerceIn(0f, (totalHeight - badgeH).coerceAtLeast(0f))
        drawRect(
            color = latestColor,
            topLeft = Offset(plotWidth, badgeTop),
            size = Size(rightAxisWidth, badgeH)
        )
        drawText(
            textMeasurer = textMeasurer,
            text = formatCryptoPrice(latestCandle.close),
            style = ChartPivotLabelStyle.copy(color = Color.White),
            topLeft = Offset(plotWidth + 3.dp.toPx(), badgeTop + 2.dp.toPx()),
            overflow = TextOverflow.Clip,
            softWrap = false,
            maxLines = 1
        )
    }
}

/**
 * BAGIAN 5: AMD (Accumulation, Manipulation, Distribution) Session Visualization
 * - Accumulation (Asia Session): Faint background box with dashed border covering Asia High/Low range.
 * - Manipulation (London Open): Highlights candle/area sweeping the Asia session High or Low.
 * Optimized: Uses precomputed CachedAmdSessionZone models to eliminate date/time allocations during frame rendering.
 */
private fun DrawScope.drawAmdSessionZones(
    zones: List<CachedAmdSessionZone>,
    openTimeToIndex: Map<Long, Int>,
    startIndex: Int,
    endIndexExclusive: Int,
    slotWidth: Float,
    plotWidth: Float,
    totalHeight: Float,
    scaledMin: Double,
    scaledMax: Double,
    priceToY: (Double) -> Float,
    labelMeasurer: ChartLabelMeasurer,
    textMeasurer: TextMeasurer
) {
    if (zones.isEmpty()) return
    val dashBorder = DashEffectAmd
    val labelStyle = ChartAmdSessionStyle
    val manipStyle = ChartAmdManipStyle

    for (zone in zones) {
        val firstIdx = openTimeToIndex[zone.startOpenTime] ?: zone.firstCandleIndex
        val lastIdx = openTimeToIndex[zone.endOpenTime] ?: zone.lastCandleIndex
        // Viewport culling: skip zones completely outside visible bars
        if (lastIdx < startIndex || firstIdx >= endIndexExclusive) continue
        if (zone.high < scaledMin || zone.low > scaledMax) continue

        val topY = priceToY(zone.high)
        val bottomY = priceToY(zone.low)
        val rectTop = min(topY, bottomY)
        val rectH = abs(bottomY - topY).coerceAtLeast(3f)

        val leftX = ((firstIdx - startIndex).toFloat() * slotWidth).coerceIn(0f, plotWidth)
        val rightX = (((lastIdx - startIndex) + 1f) * slotWidth).coerceIn(0f, plotWidth)
        if (rightX <= leftX) continue

        // 1. Accumulation box: background gray faint (alpha 0.05), dashed border
        drawRect(
            color = Color(0xFF94A3B8).copy(alpha = 0.05f),
            topLeft = Offset(leftX, rectTop),
            size = Size(rightX - leftX, rectH)
        )
        drawRect(
            color = Color(0xFF94A3B8).copy(alpha = 0.35f),
            topLeft = Offset(leftX, rectTop),
            size = Size(rightX - leftX, rectH),
            style = Stroke(width = 1f, pathEffect = dashBorder)
        )

        // Pill label for Accumulation
        val label = "ASIA ACCUMULATION"
        val textLayout = labelMeasurer.measure(label, labelStyle)
        val pillW = textLayout.size.width + 6.dp.toPx()
        val pillH = textLayout.size.height + 4.dp.toPx()
        val pillLeft = (leftX + 4.dp.toPx()).coerceAtMost(plotWidth - pillW)
        val pillTop = (rectTop + 2.dp.toPx()).coerceIn(2f, totalHeight - pillH)

        drawRoundRect(
            color = Color(0xFF0F172A).copy(alpha = 0.85f),
            topLeft = Offset(pillLeft, pillTop),
            size = Size(pillW, pillH),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
        )
        drawText(
            textMeasurer = textMeasurer,
            text = label,
            style = labelStyle,
            topLeft = Offset(pillLeft + 3.dp.toPx(), pillTop + 2.dp.toPx())
        )

        // 2. Manipulation sweep (London Open)
        val manipIdx = zone.manipulationOpenTime?.let { openTimeToIndex[it] } ?: zone.manipulationIndex
        if (manipIdx != null && manipIdx in startIndex until endIndexExclusive && zone.manipulationPrice != null) {
            val cx = (manipIdx - startIndex + 0.5f) * slotWidth
            val sweepY = priceToY(zone.manipulationPrice)
            val manipLabel = if (zone.isHighSweep) "MANIPULATION (HIGH SWEEP)" else "MANIPULATION (LOW SWEEP)"
            val manipColor = Color(0xFFF59E0B) // Amber
            val mLayout = labelMeasurer.measure(manipLabel, manipStyle)
            val mW = mLayout.size.width + 6.dp.toPx()
            val mH = mLayout.size.height + 4.dp.toPx()
            val mLeft = (cx - mW / 2f).coerceIn(2f, plotWidth - mW - 2f)
            val mTop = if (zone.isHighSweep) (sweepY - mH - 4.dp.toPx()).coerceAtLeast(2f) else (sweepY + 4.dp.toPx()).coerceAtMost(totalHeight - mH - 2f)

            drawRoundRect(
                color = Color(0xFF0F172A).copy(alpha = 0.9f),
                topLeft = Offset(mLeft, mTop),
                size = Size(mW, mH),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
            )
            drawRoundRect(
                color = manipColor.copy(alpha = 0.8f),
                topLeft = Offset(mLeft, mTop),
                size = Size(mW, mH),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                style = Stroke(width = 1f)
            )
            drawText(
                textMeasurer = textMeasurer,
                text = manipLabel,
                style = manipStyle,
                topLeft = Offset(mLeft + 3.dp.toPx(), mTop + 2.dp.toPx())
            )
        }
    }
}

/**
 * BAGIAN 4: ICT (Inner Circle Trader) Overlay Visualization
 * - Order Blocks: Rectangular zone (alpha 0.18) spanning from OB origin candle to max 50 candles right.
 * - Liquidity Sweeps: Diamond marker and label at swept reference levels.
 * Optimized: Uses openTimeToIndex hash map lookup and labelMeasurer layout caching.
 */
private fun DrawScope.drawIctOverlays(
    candles: List<Candle>,
    openTimeToIndex: Map<Long, Int>,
    startIndex: Int,
    endIndexExclusive: Int,
    slotWidth: Float,
    plotWidth: Float,
    totalHeight: Float,
    scaledMin: Double,
    scaledMax: Double,
    priceToY: (Double) -> Float,
    labelMeasurer: ChartLabelMeasurer,
    textMeasurer: TextMeasurer,
    marketStructure: MarketStructureAnalysis?,
    smcAnalysis: SmcAnalysisSnapshot?
) {
    val obStyle = ChartIctObStyle

    // 1. Order Blocks from MarketStructureAnalysis.activeOrderBlockCandidates
    val orderBlocks = marketStructure?.activeOrderBlockCandidates.orEmpty()
    for (ob in orderBlocks) {
        if (ob.zoneHigh < scaledMin || ob.zoneLow > scaledMax) continue
        val originIdx = openTimeToIndex[ob.originCandleOpenTime] ?: findCandleIndexByOpenTime(candles, ob.originCandleOpenTime)
        if (originIdx < 0) continue

        // Limit block extension to max 50 candles to the right
        val rightCandleIdx = min(candles.size - 1, originIdx + 50)
        if (rightCandleIdx < startIndex || originIdx >= endIndexExclusive) continue

        val leftX = ((originIdx - startIndex).toFloat() * slotWidth).coerceIn(0f, plotWidth)
        val rightX = (((rightCandleIdx - startIndex) + 1f) * slotWidth).coerceIn(0f, plotWidth)
        if (rightX <= leftX) continue

        val topY = priceToY(ob.zoneHigh)
        val bottomY = priceToY(ob.zoneLow)
        val rectTop = min(topY, bottomY)
        val rectH = abs(bottomY - topY).coerceAtLeast(3f)

        val obColor = if (ob.isBullish) BullishGreen else BearishRed
        drawRect(
            color = obColor.copy(alpha = 0.18f),
            topLeft = Offset(leftX, rectTop),
            size = Size(rightX - leftX, rectH)
        )
        drawRect(
            color = obColor.copy(alpha = 0.55f),
            topLeft = Offset(leftX, rectTop),
            size = Size(rightX - leftX, rectH),
            style = Stroke(width = 1f)
        )

        val obLabel = if (ob.isBullish) "+OB" else "-OB"
        val layout = labelMeasurer.measure(obLabel, obStyle)
        if (rightX - leftX > layout.size.width + 4.dp.toPx() && rectH > layout.size.height) {
            drawText(
                textMeasurer = textMeasurer,
                text = obLabel,
                style = obStyle.copy(color = obColor),
                topLeft = Offset(leftX + 2.dp.toPx(), rectTop + 1.dp.toPx())
            )
        }
    }

    // 2. Liquidity Sweeps from SmcLiquiditySweepEngine
    val sweeps = smcAnalysis?.detectedSweeps.orEmpty()
    for (sweep in sweeps) {
        val swIdx = openTimeToIndex[sweep.sweepCandleOpenTime] ?: findCandleIndexByOpenTime(candles, sweep.sweepCandleOpenTime)
        if (swIdx !in startIndex until endIndexExclusive) continue

        val x = (swIdx - startIndex + 0.5f) * slotWidth
        val y = priceToY(sweep.sweepExtreme)
        if (y !in 0f..totalHeight) continue

        // Draw small diamond marker (rhombus)
        val diamondRadius = 4.dp.toPx()
        val path = Path().apply {
            moveTo(x, y - diamondRadius)
            lineTo(x + diamondRadius, y)
            lineTo(x, y + diamondRadius)
            lineTo(x - diamondRadius, y)
            close()
        }
        val markerColor = if (sweep.isBullishSweep) CyanAccent else NeutralAmber
        drawPath(path = path, color = markerColor)
        drawPath(path = path, color = Color.White, style = Stroke(width = 1f))

        // Small label near sweep marker
        val sweepLabel = if (sweep.isBullishSweep) "SWEEP (L)" else "SWEEP (H)"
        val sweepStyle = ChartSmcTagBaseStyle.copy(color = markerColor, fontSize = 7.sp)
        val sLayout = labelMeasurer.measure(sweepLabel, sweepStyle)
        val sLeft = (x - sLayout.size.width / 2f).coerceIn(2f, plotWidth - sLayout.size.width - 2f)
        val sTop = if (sweep.isBullishSweep) (y + diamondRadius + 2.dp.toPx()).coerceAtMost(totalHeight - 12.dp.toPx())
                   else (y - diamondRadius - sLayout.size.height - 2.dp.toPx()).coerceAtLeast(2f)

        drawText(
            textMeasurer = textMeasurer,
            text = sweepLabel,
            style = sweepStyle,
            topLeft = Offset(sLeft, sTop)
        )
    }
}

/**
 * BAGIAN 3: SMC (Smart Money Concepts) Overlay Visualization
 * - Swing High & Swing Low: Circles above/below pivot candle with HH, LH, HL, LL tags.
 * - BOS & CHoCH: Dashed broken level lines and colored labels near break candles.
 * Optimized: Uses openTimeToIndex hash map to fix coordinate drift and labelMeasurer layout caching.
 */
private fun DrawScope.drawSmcOverlays(
    candles: List<Candle>,
    openTimeToIndex: Map<Long, Int>,
    startIndex: Int,
    endIndexExclusive: Int,
    slotWidth: Float,
    plotWidth: Float,
    totalHeight: Float,
    scaledMin: Double,
    scaledMax: Double,
    priceToY: (Double) -> Float,
    labelMeasurer: ChartLabelMeasurer,
    textMeasurer: TextMeasurer,
    marketStructure: MarketStructureAnalysis?
) {
    if (marketStructure == null) return
    val dashEffect = DashEffectSmc

    // 1. Swing High & Swing Low points
    val swings = marketStructure.recentSwings
    for (sw in swings) {
        val idx = openTimeToIndex[sw.openTime] ?: sw.candleIndex
        if (idx !in startIndex until endIndexExclusive) continue
        if (sw.price !in scaledMin..scaledMax) continue

        val x = (idx - startIndex + 0.5f) * slotWidth
        val y = priceToY(sw.price)
        val circleRadius = 3.2.dp.toPx()
        val circleColor = if (sw.isHigh) CyanAccent.copy(alpha = 0.85f) else Color(0xFF94A3B8).copy(alpha = 0.85f)

        val circleY = if (sw.isHigh) (y - circleRadius - 2.dp.toPx()).coerceAtLeast(circleRadius)
                      else (y + circleRadius + 2.dp.toPx()).coerceAtMost(totalHeight - circleRadius)

        drawCircle(
            color = circleColor,
            radius = circleRadius,
            center = Offset(x, circleY)
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = circleRadius,
            center = Offset(x, circleY),
            style = Stroke(width = 1f)
        )

        // Tag text e.g. "HH", "LH", "HL", "LL"
        val tagText = sw.tag.name
        val tagStyle = ChartSmcTagBaseStyle.copy(color = circleColor)
        val tagLayout = labelMeasurer.measure(tagText, tagStyle)
        val tagX = (x - tagLayout.size.width / 2f).coerceIn(2f, plotWidth - tagLayout.size.width - 2f)
        val tagY = if (sw.isHigh) (circleY - tagLayout.size.height - 1.dp.toPx()).coerceAtLeast(1f)
                   else (circleY + circleRadius + 1.dp.toPx()).coerceAtMost(totalHeight - tagLayout.size.height)

        drawText(
            textMeasurer = textMeasurer,
            text = tagText,
            style = tagStyle,
            topLeft = Offset(tagX, tagY)
        )
    }

    // 2. BOS & CHoCH Structure Breaks
    val breaks = marketStructure.structureBreaks
    val occupiedBreakTops = ArrayList<Float>(breaks.size)
    val minBreakSpacing = 12.dp.toPx()

    for (sb in breaks) {
        val bIdx = openTimeToIndex[sb.breakCandleOpenTime] ?: sb.breakCandleIndex
        if (bIdx !in startIndex until endIndexExclusive) continue
        if (sb.brokenLevelPrice !in scaledMin..scaledMax) continue

        val breakX = (bIdx - startIndex + 0.5f) * slotWidth
        val brokenY = priceToY(sb.brokenLevelPrice)

        val breakColor = when (sb.type) {
            StructureBreakType.BOS -> if (sb.isBullish) Color(0xFF10B981) else Color(0xFFEF4444)
            StructureBreakType.CHOCH -> if (sb.isBullish) Color(0xFFA855F7) else Color(0xFFF97316)
        }
        val breakLabel = when (sb.type) {
            StructureBreakType.BOS -> "BOS"
            StructureBreakType.CHOCH -> "CHoCH"
        }

        // Anchor startLineX to the actual broken swing pivot candle
        val originSwingIdx = openTimeToIndex[sb.brokenSwingOpenTime]
        val startLineX = if (originSwingIdx != null) {
            ((originSwingIdx - startIndex + 0.5f) * slotWidth).coerceIn(0f, plotWidth)
        } else {
            (breakX - 6 * slotWidth).coerceAtLeast(0f)
        }
        val endLineX = (breakX + 2 * slotWidth).coerceAtMost(plotWidth)
        drawLine(
            color = breakColor.copy(alpha = 0.65f),
            start = Offset(startLineX, brokenY),
            end = Offset(endLineX, brokenY),
            strokeWidth = 1.2f,
            pathEffect = dashEffect
        )

        // Label pill with collision avoidance
        val labelStyle = ChartSmcBreakStyle
        val layout = labelMeasurer.measure(breakLabel, labelStyle)
        val padH = 3.dp.toPx()
        val padV = 1.5.dp.toPx()
        val pillW = layout.size.width + padH * 2
        val pillH = layout.size.height + padV * 2
        val pillLeft = (breakX - pillW / 2f).coerceIn(2f, plotWidth - pillW - 2f)
        val pillTop = (brokenY - pillH / 2f).coerceIn(2f, totalHeight - pillH - 2f)

        val hasCollision = occupiedBreakTops.any { abs(it - pillTop) < minBreakSpacing }
        if (!hasCollision) {
            occupiedBreakTops.add(pillTop)
            drawRoundRect(
                color = Color(0xFF0F172A),
                topLeft = Offset(pillLeft, pillTop),
                size = Size(pillW, pillH),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
            )
            drawRoundRect(
                color = breakColor,
                topLeft = Offset(pillLeft, pillTop),
                size = Size(pillW, pillH),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                style = Stroke(width = 1f)
            )
            drawText(
                textMeasurer = textMeasurer,
                text = breakLabel,
                style = labelStyle.copy(color = breakColor),
                topLeft = Offset(pillLeft + padH, pillTop + padV)
            )
        }
    }
}

/**
 * Requirement #3: Draws horizontal signal lines (ENTRY, SL, TP1, TP2, TP3) with compact price tags.
 */
private fun DrawScope.drawSignalOverlayLines(
    signal: ChartSignalOverlay,
    scaledMin: Double,
    scaledMax: Double,
    plotWidth: Float,
    totalHeight: Float,
    priceToY: (Double) -> Float,
    textMeasurer: TextMeasurer,
    isHistorical: Boolean
) {
    val lineAlpha = if (isHistorical) 0.34f else 0.88f
    val strokeW = if (isHistorical) 1.dp.toPx() else 1.4.dp.toPx()
    val dashEffect = if (isHistorical) {
        PathEffect.dashPathEffect(floatArrayOf(4f, 6f), 0f)
    } else {
        PathEffect.dashPathEffect(floatArrayOf(10f, 5f), 0f)
    }

    data class LevelSpec(val tag: String, val price: Double, val color: Color)

    val isBaseline = signal.isBaseline
    val basePrefix = if (isBaseline) "BASE " else ""
    val histPrefix = if (isHistorical) (if (isBaseline) "HIST BASE " else "HIST AI ") else basePrefix

    val levels = listOf(
        LevelSpec("${histPrefix}ENTRY", signal.entry, if (isBaseline) Color(0xFF38BDF8) else CyanAccent),
        LevelSpec("${histPrefix}SL", signal.stopLoss, BearishRed),
        LevelSpec("${histPrefix}TP1", signal.tp1, BullishGreen),
        LevelSpec("${histPrefix}TP2", signal.tp2, BullishGreen),
        LevelSpec("${histPrefix}TP3", signal.tp3, BullishGreen)
    )

    val tagTextStyle = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        fontFeatureSettings = "tnum"
    )

    for (spec in levels) {
        if (spec.price <= 0.0 || spec.price !in scaledMin..scaledMax) continue
        val y = priceToY(spec.price)
        drawLine(
            color = spec.color.copy(alpha = lineAlpha),
            start = Offset(0f, y),
            end = Offset(plotWidth, y),
            strokeWidth = strokeW,
            pathEffect = dashEffect
        )

        if (!isHistorical) {
            val labelText = "${spec.tag} ${formatCryptoPrice(spec.price)}"
            val measured = textMeasurer.measure(labelText, style = tagTextStyle)
            val pillW = measured.size.width + 8.dp.toPx()
            val pillH = (measured.size.height + 3.dp.toPx()).coerceAtLeast(13.dp.toPx())
            val pillLeft = (plotWidth - pillW - 4.dp.toPx()).coerceAtLeast(4f)
            val pillTop = (y - pillH / 2f).coerceIn(1f, (totalHeight - pillH - 1f).coerceAtLeast(1f))

            drawRect(
                color = spec.color.copy(alpha = 0.85f),
                topLeft = Offset(pillLeft, pillTop),
                size = Size(pillW, pillH)
            )
            drawText(
                textMeasurer = textMeasurer,
                text = labelText,
                style = tagTextStyle,
                topLeft = Offset(pillLeft + 4.dp.toPx(), pillTop + 1.5.dp.toPx()),
                overflow = TextOverflow.Clip,
                softWrap = false,
                maxLines = 1
            )
        }
    }
}

private fun DrawScope.drawSeriesPath(
    series: List<Double?>,
    startIndex: Int,
    endIndexExclusive: Int,
    slotWidth: Float,
    valToY: (Double) -> Float,
    color: Color,
    strokeWidth: Float
) {
    val path = Path()
    var started = false
    for (i in startIndex until endIndexExclusive) {
        val v = series.getOrNull(i) ?: continue
        val x = (i - startIndex + 0.5f) * slotWidth
        val y = valToY(v)
        if (!started) {
            path.moveTo(x, y)
            started = true
        } else {
            path.lineTo(x, y)
        }
    }
    if (started) {
        drawPath(path = path, color = color, style = Stroke(width = strokeWidth))
    }
}

private fun DrawScope.drawRsiPanel(
    rsiSeries: List<Double?>,
    startIndex: Int,
    endIndexExclusive: Int,
    totalSlotsInViewport: Int,
    crosshairIndex: Int?,
    gridColor: Color,
    axisTextColor: Color,
    textMeasurer: TextMeasurer
) {
    val rightAxisWidth = 68.dp.toPx()
    val plotWidth = (size.width - rightAxisWidth).coerceAtLeast(10f)
    val h = size.height
    val slotWidth = plotWidth / totalSlotsInViewport.toFloat()

    fun rsiToY(v: Double): Float {
        return ((100.0 - v.coerceIn(0.0, 100.0)) / 100.0 * h).toFloat()
    }

    val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
    val y70 = rsiToY(70.0)
    val y50 = rsiToY(50.0)
    val y30 = rsiToY(30.0)

    drawLine(BearishRed.copy(alpha = 0.45f), Offset(0f, y70), Offset(plotWidth, y70), 1f, pathEffect = dash)
    drawLine(axisTextColor.copy(alpha = 0.3f), Offset(0f, y50), Offset(plotWidth, y50), 1f, pathEffect = dash)
    drawLine(BullishGreen.copy(alpha = 0.45f), Offset(0f, y30), Offset(plotWidth, y30), 1f, pathEffect = dash)
    drawLine(gridColor, Offset(plotWidth, 0f), Offset(plotWidth, h), 1f)

    val labelStyle = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 8.sp, color = axisTextColor)
    drawText(
        textMeasurer = textMeasurer,
        text = "70",
        style = labelStyle,
        topLeft = Offset(plotWidth + 4.dp.toPx(), (y70 - 5.dp.toPx()).coerceAtLeast(0f))
    )
    drawText(
        textMeasurer = textMeasurer,
        text = "50",
        style = labelStyle,
        topLeft = Offset(plotWidth + 4.dp.toPx(), (y50 - 5.dp.toPx()).coerceAtLeast(0f))
    )
    drawText(
        textMeasurer = textMeasurer,
        text = "30",
        style = labelStyle,
        topLeft = Offset(plotWidth + 4.dp.toPx(), (y30 - 5.dp.toPx()).coerceIn(0f, (h - 10.dp.toPx()).coerceAtLeast(0f)))
    )

    clipRect(left = 0f, top = 0f, right = plotWidth, bottom = h) {
        drawSeriesPath(
            series = rsiSeries,
            startIndex = startIndex,
            endIndexExclusive = endIndexExclusive,
            slotWidth = slotWidth,
            valToY = ::rsiToY,
            color = RsiLineColor,
            strokeWidth = 1.5.dp.toPx()
        )
        if (crosshairIndex != null && crosshairIndex in startIndex until endIndexExclusive) {
            val crossX = (crosshairIndex - startIndex + 0.5f) * slotWidth
            drawLine(CyanAccent.copy(alpha = 0.6f), Offset(crossX, 0f), Offset(crossX, h), 1f)
        }
    }
}

private fun DrawScope.drawMacdPanel(
    macdSeries: List<MacdPoint>,
    startIndex: Int,
    endIndexExclusive: Int,
    totalSlotsInViewport: Int,
    crosshairIndex: Int?,
    gridColor: Color
) {
    val rightAxisWidth = 68.dp.toPx()
    val plotWidth = (size.width - rightAxisWidth).coerceAtLeast(10f)
    val h = size.height
    val slotWidth = plotWidth / totalSlotsInViewport.toFloat()
    val barWidth = (slotWidth * 0.65f).coerceIn(1.5f, 16f)

    var maxAbs = 1e-8
    for (i in startIndex until endIndexExclusive) {
        val pt = macdSeries.getOrNull(i) ?: continue
        pt.macd?.let { maxAbs = max(maxAbs, abs(it)) }
        pt.signal?.let { maxAbs = max(maxAbs, abs(it)) }
        pt.histogram?.let { maxAbs = max(maxAbs, abs(it)) }
    }
    maxAbs *= 1.15

    val zeroY = h / 2f
    drawLine(gridColor, Offset(0f, zeroY), Offset(plotWidth, zeroY), 1f)
    drawLine(gridColor, Offset(plotWidth, 0f), Offset(plotWidth, h), 1f)

    fun valToY(v: Double): Float {
        return (zeroY - (v / maxAbs).toFloat() * (h / 2f)).coerceIn(0f, h)
    }

    clipRect(left = 0f, top = 0f, right = plotWidth, bottom = h) {
        for (i in startIndex until endIndexExclusive) {
            val hist = macdSeries.getOrNull(i)?.histogram ?: continue
            val centerX = (i - startIndex + 0.5f) * slotWidth
            val y = valToY(hist)
            val top = min(zeroY, y)
            val barH = abs(y - zeroY).coerceAtLeast(1f)
            val col = if (hist >= 0.0) BullishGreen.copy(alpha = 0.6f) else BearishRed.copy(alpha = 0.6f)
            drawRect(
                color = col,
                topLeft = Offset(centerX - barWidth / 2f, top),
                size = Size(barWidth, barH)
            )
        }

        drawSeriesPath(
            series = macdSeries.map { it.macd },
            startIndex = startIndex,
            endIndexExclusive = endIndexExclusive,
            slotWidth = slotWidth,
            valToY = ::valToY,
            color = MacdLineColor,
            strokeWidth = 1.4.dp.toPx()
        )
        drawSeriesPath(
            series = macdSeries.map { it.signal },
            startIndex = startIndex,
            endIndexExclusive = endIndexExclusive,
            slotWidth = slotWidth,
            valToY = ::valToY,
            color = MacdSignalColor,
            strokeWidth = 1.4.dp.toPx()
        )

        if (crosshairIndex != null && crosshairIndex in startIndex until endIndexExclusive) {
            val crossX = (crosshairIndex - startIndex + 0.5f) * slotWidth
            drawLine(CyanAccent.copy(alpha = 0.6f), Offset(crossX, 0f), Offset(crossX, h), 1f)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun TradingViewLightweightWebView(
    symbol: String,
    timeframeKey: String,
    candles: List<Candle>,
    indicators: IndicatorSeries,
    showEma: Boolean,
    showVolume: Boolean,
    isLiveMode: Boolean,
    darkMode: Boolean,
    onUserManualPan: () -> Unit,
    modifier: Modifier = Modifier
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isPageReady by remember { mutableStateOf(false) }
    var loadedDatasetKey by remember { mutableStateOf("") }

    val currentDatasetKey = "$symbol-$timeframeKey"
    val latestCandle = candles.lastOrNull()

    val htmlTemplate = remember(darkMode) {
        val bgHex = if (darkMode) "#0A0E17" else "#FFFFFF"
        val textHex = if (darkMode) "#8B9BB4" else "#475569"
        val gridHex = if (darkMode) "#162032" else "#E2E8F0"
        """
        <!DOCTYPE html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
          <style>
            *, *::before, *::after { box-sizing: border-box; }
            html, body, #tvchart { margin: 0; padding: 0; width: 100%; height: 100%; background: $bgHex; overflow: hidden; touch-action: none; }
          </style>
          <script src="https://unpkg.com/lightweight-charts@4.2.0/dist/lightweight-charts.standalone.production.js"></script>
        </head>
        <body>
          <div id="tvchart"></div>
          <script>
            let chart, candleSeries, volumeSeries, ema20Series, ema50Series, ema200Series;
            function initChart() {
              if (typeof LightweightCharts === 'undefined') return;
              const container = document.getElementById('tvchart');
              if (!container || container.clientWidth === 0 || container.clientHeight === 0) return;
              chart = LightweightCharts.createChart(container, {
                width: container.clientWidth,
                height: container.clientHeight,
                layout: { background: { type: 'solid', color: '$bgHex' }, textColor: '$textHex' },
                grid: { vertLines: { color: '$gridHex' }, horzLines: { color: '$gridHex' } },
                crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
                handleScroll: { mouseWheel: true, pressedMouseMove: true, horzTouchDrag: true, vertTouchDrag: false },
                handleScale: { axisPressedMouseMove: true, mouseWheel: true, pinch: true },
                rightPriceScale: {
                  borderColor: '$gridHex',
                  scaleMargins: { top: 0.08, bottom: 0.16 }
                },
                timeScale: {
                  borderColor: '$gridHex',
                  timeVisible: true,
                  secondsVisible: false,
                  rightOffset: 7,
                  barSpacing: 9,
                  minBarSpacing: 2,
                  shiftVisibleRangeOnNewBar: true
                }
              });
              candleSeries = chart.addCandlestickSeries({
                upColor: '#10B981', downColor: '#EF4444',
                borderUpColor: '#10B981', borderDownColor: '#EF4444',
                wickUpColor: '#10B981', wickDownColor: '#EF4444'
              });
              volumeSeries = chart.addHistogramSeries({
                priceFormat: { type: 'volume' },
                priceScaleId: ''
              });
              volumeSeries.priceScale().applyOptions({ scaleMargins: { top: 0.86, bottom: 0 } });
              ema20Series = chart.addLineSeries({ color: '#38BDF8', lineWidth: 1, priceLineVisible: false, autoscaleInfoProvider: () => null });
              ema50Series = chart.addLineSeries({ color: '#F59E0B', lineWidth: 1, priceLineVisible: false, autoscaleInfoProvider: () => null });
              ema200Series = chart.addLineSeries({ color: '#C084FC', lineWidth: 1, priceLineVisible: false, autoscaleInfoProvider: () => null });
              new ResizeObserver(entries => {
                if (!entries.length || !chart) return;
                const rect = entries[0].contentRect;
                if (rect.width > 0 && rect.height > 0) {
                  chart.resize(rect.width, rect.height);
                }
              }).observe(container);
            }
            window.setFullChartData = function(payload) {
              if (!candleSeries) initChart();
              if (!candleSeries) return;
              candleSeries.setData(payload.candles || []);
              volumeSeries.setData(payload.volumes || []);
              ema20Series.setData(payload.ema20 || []);
              ema50Series.setData(payload.ema50 || []);
              ema200Series.setData(payload.ema200 || []);
              chart.timeScale().scrollToRealTime();
            };
            window.updateRealtimePoint = function(pt) {
              if (!candleSeries) return;
              candleSeries.update(pt.candle);
              if (pt.volume) volumeSeries.update(pt.volume);
            };
            window.scrollToLive = function() {
              if (chart) chart.timeScale().scrollToRealTime();
            };
            window.onload = initChart;
          </script>
        </body>
        </html>
        """.trimIndent()
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                                onUserManualPan()
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    false
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        isPageReady = true
                    }
                }
                loadDataWithBaseURL("https://unpkg.com/", htmlTemplate, "text/html", "UTF-8", null)
                webViewRef = this
            }
        }
    )

    LaunchedEffect(isLiveMode) {
        if (isLiveMode) {
            webViewRef?.evaluateJavascript("window.scrollToLive && window.scrollToLive();", null)
        }
    }

    LaunchedEffect(isPageReady, currentDatasetKey, showEma, showVolume) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (!isPageReady || candles.isEmpty()) return@LaunchedEffect

        val candleArr = JSONArray()
        val volArr = JSONArray()
        val ema20Arr = JSONArray()
        val ema50Arr = JSONArray()
        val ema200Arr = JSONArray()

        for (i in candles.indices) {
            val c = candles[i]
            val tSec = c.openTime / 1000L
            candleArr.put(
                JSONObject()
                    .put("time", tSec)
                    .put("open", c.open)
                    .put("high", c.high)
                    .put("low", c.low)
                    .put("close", c.close)
            )
            if (showVolume) {
                volArr.put(
                    JSONObject()
                        .put("time", tSec)
                        .put("value", c.volume)
                        .put("color", if (c.isBullish) "rgba(16,185,129,0.25)" else "rgba(239,68,68,0.25)")
                )
            }
            if (showEma) {
                indicators.ema20.getOrNull(i)?.let {
                    ema20Arr.put(JSONObject().put("time", tSec).put("value", it))
                }
                indicators.ema50.getOrNull(i)?.let {
                    ema50Arr.put(JSONObject().put("time", tSec).put("value", it))
                }
                indicators.ema200.getOrNull(i)?.let {
                    ema200Arr.put(JSONObject().put("time", tSec).put("value", it))
                }
            }
        }

        val payload = JSONObject()
            .put("candles", candleArr)
            .put("volumes", volArr)
            .put("ema20", ema20Arr)
            .put("ema50", ema50Arr)
            .put("ema200", ema200Arr)

        loadedDatasetKey = currentDatasetKey
        wv.evaluateJavascript("window.setFullChartData && window.setFullChartData($payload);", null)
    }

    LaunchedEffect(latestCandle?.openTime, latestCandle?.close, latestCandle?.volume) {
        val wv = webViewRef ?: return@LaunchedEffect
        val c = latestCandle ?: return@LaunchedEffect
        if (!isPageReady || loadedDatasetKey != currentDatasetKey) return@LaunchedEffect

        val tSec = c.openTime / 1000L
        val pt = JSONObject().apply {
            put(
                "candle",
                JSONObject()
                    .put("time", tSec)
                    .put("open", c.open)
                    .put("high", c.high)
                    .put("low", c.low)
                    .put("close", c.close)
            )
            if (showVolume) {
                put(
                    "volume",
                    JSONObject()
                        .put("time", tSec)
                        .put("value", c.volume)
                        .put("color", if (c.isBullish) "rgba(16,185,129,0.25)" else "rgba(239,68,68,0.25)")
                )
            }
        }
        wv.evaluateJavascript("window.updateRealtimePoint && window.updateRealtimePoint($pt);", null)
    }
}
