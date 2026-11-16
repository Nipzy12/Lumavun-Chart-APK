package com.example.ui.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.parser.AiAnalysisResult
import com.example.analysis.signal.ActiveTradeSetup
import com.example.analysis.signal.ChartSignalOverlay
import com.example.analysis.smc.SmcEntryProV5Engine
import com.example.analysis.signal.MarketAssessmentState
import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.chart.chart.LightweightCandleChart
import com.example.chart.overlay.AiTradePlanChartOverlay
import com.example.chart.overlay.TradePlanOverlayMapper
import com.example.chart.indicators.ChartIndicatorParams
import com.example.chart.indicators.IndicatorSeries
import com.example.market.candle.Candle
import com.example.market.candle.MarketProvider
import com.example.market.candle.Ticker24h
import com.example.market.candle.Timeframe
import com.example.market.candle.formatPriceWithPrefix
import com.example.market.candle.formatSymbolPair
import com.example.market.candle.inferInstrumentCategory
import com.example.market.candle.inferMarketProvider
import com.example.market.clock.CandleCountdownState
import com.example.market.status.MarketStatusEvaluation
import com.example.market.status.MarketStatusService
import com.example.market.websocket.WsConnectionStatus
import com.example.storage.preferences.TerminalSettings
import com.example.ui.aiPanel.CompactAiAnalystCard
import com.example.ui.components.MarketStatusLedDot
import com.example.ui.components.premiumPanel
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.components.ledColorFor
import com.example.ui.components.ledSoftBgFor
import com.example.ui.indicators.TradingViewIndicatorsDialog
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BearishRedSoft
import com.example.ui.theme.BullishGreen
import com.example.ui.theme.BullishGreenSoft
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.NeutralAmber
import com.example.ui.theme.NeutralAmberSoft
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Primary Trading Terminal Chart Screen with Real-Time Candle Close Countdown (Requirement #1)
 * and Signal Overlay on Chart (Requirement #3):
 *
 * Mobile-First Responsive Flex Layout (360x640, 360x800, 390x844, 412x915):
 * 1. Compact Header: BTC/USDT  $86,004.47  -0.27%   ⏱ 08:37  [ LIVE ]
 * 2. Timeframe Selector: 1m  5m  15m  30m  1H  4H  1D
 * 3. LARGE CHART (Modifier.weight(1f) -> 60-70% of viewport height, or ~85% in Fullscreen)
 * 4. Compact Indicator Controls: [ EMA ] [ VOL ] [ RSI ] [ MACD ] [ S/R ] [ SIG ] [ HIST ]
 * 5. Compact AI Summary Card (hidden in Fullscreen Chart Mode)
 */
@Composable
fun ChartScreen(
    activeSymbol: String,
    activeTimeframe: Timeframe,
    currentPrice: Double,
    priceChangePercent: Double,
    activeTicker: Ticker24h?,
    candles: List<Candle>,
    indicators: IndicatorSeries,
    snapshot: MarketTechnicalSnapshot?,
    settings: TerminalSettings,
    connectionStatus: WsConnectionStatus,
    reconnectingBannerMessage: String?,
    restErrorMessage: String?,
    aiResult: AiAnalysisResult?,
    isAiAnalyzing: Boolean,
    aiErrorMessage: String?,
    activeAiProviderBadge: String,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onSelectTimeframe: (Timeframe) -> Unit,
    onToggleEma: () -> Unit,
    onToggleVolume: () -> Unit,
    onToggleRsi: () -> Unit,
    onToggleMacd: () -> Unit,
    onToggleSupportResistance: () -> Unit,
    onToggleSmc: () -> Unit = {},
    onToggleIct: () -> Unit = {},
    onToggleAmd: () -> Unit = {},
    onOpenFullAiTab: () -> Unit,
    onRunAiAnalysis: () -> Unit = {},
    onOpenMarketsSearch: () -> Unit,
    onForceReconnect: () -> Unit,
    countdownFlow: StateFlow<CandleCountdownState>? = null,
    activeSignalOverlay: ChartSignalOverlay? = null,
    historicalSignalOverlays: List<ChartSignalOverlay> = emptyList(),
    selectedJournalOverlay: ChartSignalOverlay? = null,
    onClearSelectedJournalOverlay: () -> Unit = {},
    latestBaselineSignal: com.example.storage.watchlist.SignalJournalEntity? = null,
    onToggleSignalOverlay: () -> Unit = {},
    onToggleSignalHistory: () -> Unit = {},
    onToggleSma: () -> Unit = {},
    onToggleBollingerBands: () -> Unit = {},
    onToggleAtr: () -> Unit = {},
    onSetPerTimeframeIndicators: (Boolean) -> Unit = {},
    onUpdateIndicatorParams: (ChartIndicatorParams) -> Unit = {},
    botActionSummary: String = "STOPPED",
    marketAssessment: MarketAssessmentState? = null,
    activeSetup: ActiveTradeSetup? = null,
    onSelectAiStrategy: (com.example.ai.strategy.AiStrategy) -> Unit = {},
    trackedSetups: List<com.example.ai.tracker.TrackedPendingSetup> = emptyList(),
    onStartTracking: ((com.example.ai.plan.StructuredTradePlan) -> Unit)? = null,
    onStopTracking: ((String) -> Unit)? = null,
    tradePlanOverlay: AiTradePlanChartOverlay? = null,
    onToggleTradePlanOverlay: () -> Unit = {},
    onSelectTrackedSetup: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showIndicatorsDialog by remember { mutableStateOf(false) }
    val smcPrefs = LocalContext.current.getSharedPreferences("lumavun_smc_entry_pro_v5", android.content.Context.MODE_PRIVATE)
    var showSmcProV5 by rememberSaveable { mutableStateOf(smcPrefs.getBoolean("enabled", false)) }
    var smcProQuality by rememberSaveable { mutableStateOf(smcPrefs.getString("quality", "STRICT") ?: "STRICT") }
    var smcProHtfBias by rememberSaveable { mutableStateOf(smcPrefs.getBoolean("htf_bias", true)) }
    val smcProConfig = remember(smcProQuality, smcProHtfBias, activeTimeframe) {
        SmcEntryProV5Engine.Config(
            qualityMode = SmcEntryProV5Engine.QualityMode.valueOf(smcProQuality),
            htfMode = if (smcProHtfBias && activeTimeframe != Timeframe.D1)
                SmcEntryProV5Engine.HtfMode.BIAS else SmcEntryProV5Engine.HtfMode.OFF,
            htfTimeframe = if (activeTimeframe.durationMs >= Timeframe.H4.durationMs) Timeframe.D1 else Timeframe.H4
        )
    }
    var showAnalystPanel by rememberSaveable { mutableStateOf(false) }

    if (showIndicatorsDialog) {
        TradingViewIndicatorsDialog(
            settings = settings,
            activeTimeframeLabel = activeTimeframe.label,
            onDismiss = { showIndicatorsDialog = false },
            onToggleEma = onToggleEma,
            onToggleSma = onToggleSma,
            onToggleBollingerBands = onToggleBollingerBands,
            onToggleVolume = onToggleVolume,
            onToggleRsi = onToggleRsi,
            onToggleMacd = onToggleMacd,
            onToggleAtr = onToggleAtr,
            onToggleSupportResistance = onToggleSupportResistance,
            onToggleSmc = onToggleSmc,
            onToggleIct = onToggleIct,
            onToggleAmd = onToggleAmd,
            showSmcProV5 = showSmcProV5,
            onToggleSmcProV5 = {
                showSmcProV5 = !showSmcProV5
                smcPrefs.edit().putBoolean("enabled", showSmcProV5).apply()
            },
            smcProQuality = smcProQuality,
            onChangeSmcProQuality = {
                smcProQuality = it
                smcPrefs.edit().putString("quality", it).apply()
            },
            smcProHtfBias = smcProHtfBias,
            onChangeSmcProHtfBias = {
                smcProHtfBias = it
                smcPrefs.edit().putBoolean("htf_bias", it).apply()
            },
            onToggleSignalOverlay = onToggleSignalOverlay,
            onToggleSignalHistory = onToggleSignalHistory,
            onToggleTradePlanOverlay = onToggleTradePlanOverlay,
            onSetPerTimeframeIndicators = onSetPerTimeframeIndicators,
            onUpdateIndicatorParams = onUpdateIndicatorParams
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 3.dp)
            .testTag("chart_terminal_screen"),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        if (!isFullscreen && !reconnectingBannerMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(NeutralAmberSoft)
                    .clickable { onForceReconnect() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag("reconnecting_banner")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = reconnectingBannerMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = NeutralAmber,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Retry",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        if (!isFullscreen && !restErrorMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(BearishRedSoft)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag("rest_error_banner")
            ) {
                Text(
                    text = restErrorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = BearishRed,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 1. Compact Header: Symbol + Market Status LED + Price + % Change + ⏱ Candle Countdown + Readable Status Badge
        val marketStatus = remember(
            activeSymbol,
            activeTicker?.updatedAt,
            activeTicker?.providerReportedMarketOpen,
            currentPrice,
            candles.size,
            connectionStatus,
            restErrorMessage
        ) {
            val fallbackQuoteTs = when {
                activeTicker != null && activeTicker.updatedAt > 0L -> activeTicker.updatedAt
                currentPrice > 0.0 || candles.isNotEmpty() -> System.currentTimeMillis()
                else -> System.currentTimeMillis()
            }
            MarketStatusService.evaluate(
                symbol = activeSymbol,
                category = activeTicker?.category ?: inferInstrumentCategory(activeSymbol),
                provider = activeTicker?.provider ?: inferMarketProvider(
                    activeSymbol,
                    activeTicker?.category ?: inferInstrumentCategory(activeSymbol)
                ),
                ticker = activeTicker,
                fallbackQuoteTimestampMs = fallbackQuoteTs,
                connectionStatus = connectionStatus,
                hasFeedError = !restErrorMessage.isNullOrBlank() && candles.isEmpty(),
                providerReportedMarketOpen = activeTicker?.providerReportedMarketOpen
            )
        }

        CompactTerminalHeader(
            symbol = activeSymbol,
            timeframe = activeTimeframe,
            currentPrice = currentPrice,
            priceChangePercent = priceChangePercent,
            connectionStatus = connectionStatus,
            marketStatus = marketStatus,
            countdownFlow = countdownFlow,
            isFullscreen = isFullscreen,
            onPairClick = onOpenMarketsSearch,
            onStatusClick = onForceReconnect,
            onExitFullscreen = onToggleFullscreen
        )

        // 2. Compact Timeframe Bar: 1m 5m 15m 30m 1H 4H 1D
        TimeframeSelectorRow(
            selectedTimeframe = activeTimeframe,
            onSelectTimeframe = onSelectTimeframe
        )

        // 3. LARGE CHART (Modifier.weight(1f) ensures 55-70% of viewport in normal mode and ~85% in fullscreen)
        LightweightCandleChart(
            symbol = activeSymbol,
            timeframeKey = activeTimeframe.binanceInterval,
            countdownFlow = countdownFlow,
            candles = candles,
            indicators = indicators,
            supportLevels = snapshot?.supportLevels.orEmpty(),
            resistanceLevels = snapshot?.resistanceLevels.orEmpty(),
            showEma = settings.showEma,
            showVolume = settings.showVolume,
            showRsi = settings.showRsi,
            showMacd = settings.showMacd,
            showSupportResistance = settings.showSupportResistance,
            showSmc = settings.showSmc,
            showSmcProV5 = showSmcProV5,
            smcProConfig = smcProConfig,
            showIct = settings.showIct,
            showAmd = settings.showAmd,
            snapshot = snapshot,
            isFullscreen = isFullscreen,
            darkMode = settings.darkMode,
            onToggleFullscreen = onToggleFullscreen,
            activeSignalOverlay = activeSignalOverlay,
            historicalSignalOverlays = historicalSignalOverlays,
            selectedJournalOverlay = selectedJournalOverlay,
            onClearSelectedJournalOverlay = onClearSelectedJournalOverlay,
            showSignalOverlay = settings.showSignalOverlay,
            showSignalHistory = settings.showSignalHistory,
            showSma = settings.showSma,
            showBollingerBands = settings.showBollingerBands,
            showAtr = settings.showAtr,
            tradePlanOverlay = tradePlanOverlay,
            showTradePlanOverlay = settings.showTradePlanOverlay,
            onToggleTradePlanOverlay = onToggleTradePlanOverlay,
            availableTrackedSetups = trackedSetups.filter {
                it.symbol.equals(activeSymbol, ignoreCase = true) &&
                    TradePlanOverlayMapper.timeframesMatch(it.timeframe, activeTimeframe.label)
            },
            onSelectTrackedSetup = onSelectTrackedSetup,
            onOpenIndicatorsDialog = { showIndicatorsDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        // Full-screen-first chart: AI is one unobtrusive action instead of a tall card.
        if (!isFullscreen) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(CyanAccent.copy(alpha = 0.065f))
                    .clickable { showAnalystPanel = true }
                    .padding(horizontal = 11.dp, vertical = 8.dp)
                    .testTag("analyst_chart_button"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        modifier = Modifier.size(6.dp)
                            .background(
                                if (isAiAnalyzing) NeutralAmber else CyanAccent,
                                androidx.compose.foundation.shape.CircleShape
                            )
                    )
                    Text(
                        text = if (isAiAnalyzing) "AI sedang menganalisis"
                            else "LUMAVUN AI · " + botActionSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "Insight  ›",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        // In-Chart Analysis Panel (ModalBottomSheet on top of the Chart screen)
        if (showAnalystPanel) {
            InChartAnalystPanel(
                symbol = activeSymbol,
                timeframe = activeTimeframe,
                snapshot = snapshot,
                aiResult = aiResult,
                isAnalyzing = isAiAnalyzing,
                errorMessage = aiErrorMessage,
                providerBadge = aiResult?.providerBadge ?: activeAiProviderBadge,
                marketAssessment = marketAssessment,
                activeSetup = activeSetup,
                selectedStrategy = settings.selectedAiStrategy,
                onSelectStrategy = onSelectAiStrategy,
                trackedSetups = trackedSetups,
                onStartTracking = onStartTracking,
                onStopTracking = onStopTracking,
                onAnalyzeNow = onRunAiAnalysis,
                onDismiss = { showAnalystPanel = false }
            )
        }
    }
}

/**
 * Compact Header designed for 360dp mobile viewports:
 * BTC/USDT   $86,004.47   -0.27%     ⏱ 08:37  [ LIVE ]
 */
@Composable
private fun CompactTerminalHeader(
    symbol: String,
    timeframe: Timeframe,
    currentPrice: Double,
    priceChangePercent: Double,
    connectionStatus: WsConnectionStatus,
    marketStatus: MarketStatusEvaluation,
    countdownFlow: StateFlow<CandleCountdownState>?,
    isFullscreen: Boolean,
    onPairClick: () -> Unit,
    onStatusClick: () -> Unit,
    onExitFullscreen: () -> Unit
) {
    val positive = priceChangePercent >= 0.0
    val changeColor = if (positive) BullishGreen else BearishRed
    val sign = if (positive) "+" else ""
    val statusColor = ledColorFor(marketStatus.ledState)
    val provider = marketStatus.provider

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .premiumPanel(cornerRadius = 15)
            .padding(horizontal = 11.dp, vertical = 9.dp)
            .testTag("chart_pro_market_header"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                    .clickable(onClick = onPairClick).testTag("active_pair_selector"),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MarketStatusLedDot(status = marketStatus, dotSize = 7.dp, testTag = "chart_market_status_led")
                Text(
                    text = formatSymbolPair(symbol),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Switch symbol",
                    tint = CyanAccent,
                    modifier = Modifier.size(15.dp)
                )
                Surface(
                    color = if (provider == MarketProvider.EXNESS)
                        Color(0x33F3C969) else CyanAccent.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.testTag("chart_provider_badge")
                ) {
                    Text(
                        text = provider.code,
                        color = if (provider == MarketProvider.EXNESS) Color(0xFFF3C969) else CyanAccent,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
                        maxLines = 1
                    )
                }
            }
            Surface(
                color = ledSoftBgFor(marketStatus.ledState),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.border(1.dp, statusColor.copy(alpha = 0.46f), RoundedCornerShape(8.dp))
                    .clickable(onClick = onStatusClick).testTag("connection_status_badge")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)
                        .testTag("chart_market_status_badge"),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MarketStatusLedDot(
                        status = marketStatus,
                        dotSize = 6.dp,
                        testTag = "chart_badge_status_led"
                    )
                    Text(
                        text = marketStatus.shortBadgeLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.testTag("chart_market_status_label")
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = if (currentPrice > 0.0) formatPriceWithPrefix(currentPrice, symbol) else "--",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 19.sp,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontFeatureSettings = "tnum"
                ),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("live_price_text")
            )
            Surface(
                color = changeColor.copy(alpha = 0.12f),
                shape = RoundedCornerShape(7.dp)
            ) {
                Text(
                    text = "$sign${String.format(Locale.US, "%.2f", priceChangePercent)}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = changeColor,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)
                )
            }
            // Countdown is rendered immediately below the live price label on the right price axis.
            if (isFullscreen) {
                Surface(
                    color = CyanAccent.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(7.dp),
                    modifier = Modifier.clickable(onClick = onExitFullscreen)
                        .testTag("exit_fullscreen_header_button")
                ) {
                    Text(
                        text = "Exit",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * Requirement #1 & #18: Isolated 1-Second Candle Close Countdown Composable.
 * Collects `countdownFlow` inside its own small Composable scope so 1Hz countdown ticks
 * ONLY recompose this badge and NEVER re-render the chart or full screen!
 * Under 10 seconds (`isClosingSoon`), visually emphasizes slightly in Amber.
 */
@Composable
private fun IsolatedCandleCountdownBadge(
    countdownFlow: StateFlow<CandleCountdownState>,
    fallbackTimeframe: Timeframe
) {
    val countdown by countdownFlow.collectAsStateWithLifecycle()
    val isUrgent = countdown.isClosingSoon
    val bgCol = if (isUrgent) {
        NeutralAmberSoft
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }
    val textCol = if (isUrgent) {
        NeutralAmber
    } else {
        CyanAccent
    }
    val borderCol = if (isUrgent) {
        NeutralAmber.copy(alpha = 0.65f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }

    Surface(
        color = bgCol,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier
            .border(1.dp, borderCol, RoundedCornerShape(4.dp))
            .testTag("candle_countdown_badge")
    ) {
        Text(
            text = "⏱ ${countdown.formatted}",
            style = MaterialTheme.typography.labelSmall,
            color = textCol,
            fontWeight = if (isUrgent) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun TimeframeSelectorRow(
    selectedTimeframe: Timeframe,
    onSelectTimeframe: (Timeframe) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth()
            .premiumPanel(cornerRadius = 12)
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .testTag("timeframe_selector_row"),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Timeframe.entries.forEach { timeframe ->
            val selected = timeframe == selectedTimeframe
            val shape = RoundedCornerShape(8.dp)
            Box(
                modifier = Modifier.weight(1f).clip(shape)
                    .background(if (selected) CyanAccent.copy(alpha = 0.19f) else Color.Transparent)
                    .then(if (selected) Modifier.border(1.dp, CyanAccent.copy(alpha = 0.60f), shape) else Modifier)
                    .clickable { onSelectTimeframe(timeframe) }
                    .padding(vertical = 8.dp)
                    .testTag("timeframe_chip_${timeframe.binanceInterval}"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = timeframe.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}
