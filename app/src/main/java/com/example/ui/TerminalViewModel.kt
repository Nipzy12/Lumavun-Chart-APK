package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.analyst.AIService
import com.example.ai.analyst.AiAnalysisRequestContext
import com.example.ai.analyst.AiAnalystOutcome
import com.example.ai.analyst.AiMarketAnalystProvider
import com.example.ai.analyst.BuiltInAIProvider
import com.example.ai.parser.AiAnalysisResult
import com.example.ai.parser.AiResponseParser
import com.example.ai.sync.AiModelInfo
import com.example.ai.sync.AiModelSyncManager
import com.example.ai.sync.ModelSyncResult
import com.example.ai.usage.AiUsageDashboardData
import com.example.ai.usage.AiUsageRepository
import com.example.storage.preferences.AiProviderType
import com.example.storage.watchlist.AiTokenUsageEntity
import com.example.analysis.events.EventProtectionMode
import com.example.analysis.events.EventRiskAssessment
import com.example.analysis.events.EventRiskManager
import com.example.analysis.events.MacroCalendarEvent
import com.example.analysis.events.UnavailableEventPolicy
import com.example.analysis.signal.ActiveTradeSetup
import com.example.analysis.signal.AutoSetupEngineV2
import com.example.analysis.signal.BaselineSignalGenerator
import com.example.analysis.signal.ChartSignalOverlay
import com.example.analysis.signal.MarketAssessmentState
import com.example.analysis.signal.MonitorEvaluationCheckpoint
import com.example.analysis.signal.SetupLifecycleState
import com.example.analysis.signal.SignalEngine
import com.example.analysis.signal.SignalLifecycleStatus
import com.example.analysis.signal.SignalValidationEngine
import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.analysis.technical.TechnicalSnapshotBuilder
import com.example.chart.indicators.ChartIndicatorParams
import com.example.chart.indicators.IndicatorSeries
import com.example.chart.indicators.TechnicalIndicators
import com.example.market.biquote.BiQuoteClient
import com.example.market.biquote.BiQuoteRealtimeManager
import com.example.market.calendar.CalendarLoadState
import com.example.market.calendar.EconomicCalendarEvent
import com.example.market.calendar.EconomicCalendarService
import com.example.market.candle.Candle
import com.example.market.candle.CandleMerger
import com.example.market.candle.InstrumentCategory
import com.example.market.candle.MarketProvider
import com.example.market.candle.Ticker24h
import com.example.market.candle.Timeframe
import com.example.market.candle.formatCryptoPrice
import com.example.market.candle.formatPriceWithPrefix
import com.example.market.candle.formatSymbolPair
import com.example.market.candle.inferInstrumentCategory
import com.example.market.candle.inferMarketProvider
import com.example.market.exness.ExnessCatalog
import com.example.market.exness.ExnessConfig
import com.example.market.exness.ExnessMarketDataProvider
import com.example.market.provider.BinanceMarketDataProvider
import com.example.market.provider.MarketDataProvider
import com.example.market.provider.ProviderConnectionStatus
import com.example.market.clock.CandleClock
import com.example.market.clock.CandleConfirmationEngine
import com.example.market.clock.CandleCountdownState
import com.example.market.rest.BinanceRestClient
import com.example.market.rest.ExchangeSymbolInfo
import com.example.market.rest.MarketApiResult
import com.example.market.status.MarketStatusEvaluation
import com.example.market.status.MarketStatusService
import com.example.market.symbols.SymbolRepository
import com.example.market.symbols.SymbolValidationOutcome
import com.example.market.websocket.BinanceWebSocketManager
import com.example.market.websocket.KlineStreamEvent
import com.example.market.websocket.WsConnectionStatus
import com.example.notifications.MarketMonitorForegroundService
import com.example.notifications.TerminalNotificationHelper
import com.example.simulation.paper.BotRunState
import com.example.simulation.paper.PaperTradingAccountState
import com.example.simulation.paper.PaperTradingConfig
import com.example.simulation.paper.PaperTradingEngine
import com.example.storage.preferences.AiAnalysisSystem
import com.example.storage.preferences.AiAutoMonitorRuntimeState
import com.example.storage.preferences.AiContinuousSubmode
import com.example.storage.preferences.AiProviderMode
import com.example.storage.preferences.AppPreferences
import com.example.storage.preferences.CalendarTimezoneOption
import com.example.ai.vision.ChartVisionRenderer
import com.example.storage.preferences.ChartVisionMode
import com.example.storage.preferences.CustomApiFormat
import com.example.storage.preferences.TerminalSettings
import com.example.storage.preferences.TradingStyleProfile
import com.example.storage.watchlist.SignalJournalEntity
import com.example.storage.watchlist.TerminalDatabase
import com.example.storage.watchlist.WatchlistEntity
import com.example.storage.watchlist.WatchlistRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

enum class TerminalTab(val title: String, val badgeIcon: String = "") {
    WATCHLIST("Daftar Pantau"),
    CHART("Chart"),
    AI("AI"),
    CALENDAR("Kalender"),
    PROFILE("Profil");

    companion object {
        val MARKETS: TerminalTab get() = WATCHLIST
        val SETTINGS: TerminalTab get() = PROFILE
    }
}

data class TerminalUiState(
    val selectedTab: TerminalTab = TerminalTab.CHART,
    val isChartFullscreen: Boolean = false,
    val activeSymbol: String = "BTCUSDT",
    val activeTimeframe: Timeframe = Timeframe.M15,
    val currentPrice: Double = 0.0,
    val priceChangePercent: Double = 0.0,
    val candles: List<Candle> = emptyList(),
    val indicators: IndicatorSeries = IndicatorSeries.EMPTY,
    val snapshot: MarketTechnicalSnapshot? = null,
    val liveTickers: Map<String, Ticker24h> = emptyMap(),
    val isLoadingCandles: Boolean = false,
    val restErrorMessage: String? = null,
    val aiResult: AiAnalysisResult? = null,
    val marketAssessment: MarketAssessmentState? = null,
    val activeSetup: ActiveTradeSetup? = null,
    val activeSignalOverlay: ChartSignalOverlay? = null,
    val historicalSignalOverlays: List<ChartSignalOverlay> = emptyList(),
    val latestBaselineSignal: SignalJournalEntity? = null,
    val selectedJournalOverlay: ChartSignalOverlay? = null,
    val isCurrentSignalSaved: Boolean = false,
    val isAiAnalyzing: Boolean = false,
    val aiErrorMessage: String? = null,
    val aiErrorDetail: String? = null,
    val activeAiProviderBadge: String = "AI · Primary",
    val aiAnalysisSystem: AiAnalysisSystem = AiAnalysisSystem.AI_CONTINUOUS,
    val aiContinuousSubmode: AiContinuousSubmode = AiContinuousSubmode.AUTO,
    val tradingStyleProfile: TradingStyleProfile = TradingStyleProfile.DAY_TRADING,
    val autoMonitorRuntimeState: AiAutoMonitorRuntimeState = AiAutoMonitorRuntimeState.RUNNING,
    val activeModeAttributionLabel: String = "AI Continuous · Auto",
    val modeSwitchStatusBanner: String? = null,
    val isTestingAiConnection: Boolean = false,
    val aiConnectionTestStatus: String? = null,
    val cachedSymbolCount: Int = 30,
    val eventRiskAssessment: EventRiskAssessment = EventRiskAssessment(),
    val upcomingMacroEvents: List<MacroCalendarEvent> = emptyList(),
    val botActionSummary: String = "STOPPED",
    val isLoadingWatchlistData: Boolean = false,
    val watchlistFeedbackMessage: String? = null,
    val isWatchlistFeedbackError: Boolean = false,
    val selectedAiStrategy: com.example.ai.strategy.AiStrategy = com.example.ai.strategy.AiStrategy.AI_HYBRID,
    val activeMarketProvider: MarketProvider = MarketProvider.BINANCE,
    val exnessConnectionStatus: ProviderConnectionStatus = ProviderConnectionStatus.Unconfigured("Gateway proxy Exness belum dikonfigurasi."),
    val isMarketDataStale: Boolean = false
)

class TerminalViewModel(
    application: Application,
    private val restClient: BinanceRestClient,
    private val wsManager: BinanceWebSocketManager,
    private val calendarService: EconomicCalendarService,
    customAiAnalystProvider: AiMarketAnalystProvider?
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        restClient = BinanceRestClient(),
        wsManager = BinanceWebSocketManager(),
        calendarService = EconomicCalendarService(),
        customAiAnalystProvider = null
    )

    private val appContext = application.applicationContext
    private val database = TerminalDatabase.getInstance(application)
    private val dao = database.terminalDao()
    val preferences = AppPreferences(application)
    private val biQuoteClient = BiQuoteClient()
    private val biQuoteRealtime = BiQuoteRealtimeManager(biQuoteClient)
    val exnessProvider = ExnessMarketDataProvider(preferences.settings.value.exnessConfig)
    val binanceProvider = BinanceMarketDataProvider(restClient, wsManager)
    val exnessConnectionStatus: StateFlow<ProviderConnectionStatus> = exnessProvider.connectionStatus
    private val watchlistRepo = WatchlistRepository(dao)
    private val symbolRepo = SymbolRepository(dao, restClient, biQuoteClient)

    val aiUsageRepository = AiUsageRepository(dao, preferences)
    val aiModelSyncManager = AiModelSyncManager()
    val trackedSetupRepo = com.example.ai.tracker.TrackedSetupRepository(dao)

    val trackedSetups: StateFlow<List<com.example.ai.tracker.TrackedPendingSetup>> = trackedSetupRepo.allTrackedSetupsFlow
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeTrackedSetups: StateFlow<List<com.example.ai.tracker.TrackedPendingSetup>> = trackedSetupRepo.activeTrackedSetupsFlow
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val builtInProvider = BuiltInAIProvider()
    private val defaultAiService = AIService(
        preferences = preferences,
        builtInProvider = builtInProvider,
        usageRepository = aiUsageRepository
    )
    private val aiAnalystService: AiMarketAnalystProvider = customAiAnalystProvider ?: defaultAiService

    val aiUsageDashboardData: StateFlow<AiUsageDashboardData> = aiUsageRepository.dashboardDataFlow
        .catch { emit(AiUsageDashboardData()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiUsageDashboardData())

    val recentAiUsageRecords: StateFlow<List<AiTokenUsageEntity>> = aiUsageRepository.recentUsageFlow
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isSyncingModels = MutableStateFlow(false)
    val isSyncingModels: StateFlow<Boolean> = _isSyncingModels.asStateFlow()

    private val _modelSyncStatusMessage = MutableStateFlow<String?>(null)
    val modelSyncStatusMessage: StateFlow<String?> = _modelSyncStatusMessage.asStateFlow()

    private val _syncedModelsMap = MutableStateFlow<Map<AiProviderType, List<AiModelInfo>>>(emptyMap())
    val syncedModelsMap: StateFlow<Map<AiProviderType, List<AiModelInfo>>> = _syncedModelsMap.asStateFlow()


    // Requirement #1 & #20: Dedicated CandleClock synchronized with Binance candle timestamps
    val candleClock = CandleClock()
    val candleCountdown: StateFlow<CandleCountdownState> = candleClock.countdownState

    // Requirement #2 & #20: Explicit separation of LIVE (UNCLOSED) vs CONFIRMED (CLOSED) candles
    val confirmationEngine = CandleConfirmationEngine()

    // Requirement #2: Bot Trading (Simulasi) StateFlow persisted across app restarts
    private val _paperAccountState = MutableStateFlow(PaperTradingAccountState())
    val paperAccountState: StateFlow<PaperTradingAccountState> = _paperAccountState.asStateFlow()

    // Requirement #4: Economic Calendar StateFlow
    private val _calendarState = MutableStateFlow<CalendarLoadState>(CalendarLoadState.Loading)
    val calendarState: StateFlow<CalendarLoadState> = _calendarState.asStateFlow()

    val settings: StateFlow<TerminalSettings> = preferences.settings
    val connectionStatus: StateFlow<WsConnectionStatus> = wsManager.connectionStatus
    val biQuoteConnectionStatus: StateFlow<WsConnectionStatus> = biQuoteRealtime.connectionStatus
    val reconnectingBanner: StateFlow<String?> = wsManager.statusBannerMessage

    private val safeHandler = CoroutineExceptionHandler { _, _ -> }

    val watchlist: StateFlow<List<WatchlistEntity>> = watchlistRepo.watchlistFlow
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val signalJournal: StateFlow<List<SignalJournalEntity>> = watchlistRepo.signalJournalFlow
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedWatchlistCategory = MutableStateFlow(InstrumentCategory.ALL)
    val selectedWatchlistCategory: StateFlow<InstrumentCategory> = _selectedWatchlistCategory.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<ExchangeSymbolInfo>> = combine(
        _searchQuery,
        _selectedWatchlistCategory
    ) { q, cat -> q to cat }
        .flatMapLatest { (q, cat) ->
            symbolRepo.searchSymbols(q, cat).catch { emit(emptyList()) }
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(
        TerminalUiState(
            activeSymbol = preferences.getLastOpenedSymbol(),
            activeTimeframe = preferences.getLastOpenedTimeframeEnum(),
            activeAiProviderBadge = "AI · Primary",
            aiAnalysisSystem = preferences.settings.value.aiAnalysisSystem,
            aiContinuousSubmode = preferences.settings.value.aiContinuousSubmode,
            tradingStyleProfile = preferences.settings.value.tradingStyleProfile,
            activeModeAttributionLabel = when (preferences.settings.value.aiAnalysisSystem) {
                AiAnalysisSystem.AI_CONTINUOUS -> "AI Continuous · ${preferences.settings.value.aiContinuousSubmode.label}"
                AiAnalysisSystem.AI_TRADING_STYLE -> "AI Gaya Trading · ${preferences.settings.value.tradingStyleProfile.label}"
            },
            selectedAiStrategy = preferences.settings.value.selectedAiStrategy,
            activeMarketProvider = preferences.settings.value.selectedMarketProvider,
            exnessConnectionStatus = exnessProvider.connectionStatus.value
        )
    )
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    private val _selectedOverlaySetupId = MutableStateFlow<String?>(null)
    val selectedOverlaySetupId: StateFlow<String?> = _selectedOverlaySetupId.asStateFlow()

    val tradePlanOverlay: StateFlow<com.example.chart.overlay.AiTradePlanChartOverlay?> = combine(
        _uiState,
        trackedSetups,
        _selectedOverlaySetupId,
        preferences.settings
    ) { currentUiState, setups, selectedId, currentSettings ->
        com.example.chart.overlay.TradePlanOverlayMapper.resolveActiveOverlay(
            currentSymbol = currentUiState.activeSymbol,
            currentTimeframe = currentUiState.activeTimeframe.label,
            selectedSetupId = selectedId,
            trackedSetups = setups,
            tradePlan = currentUiState.aiResult?.tradePlan,
            showOverlay = currentSettings.showTradePlanOverlay
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun selectOverlaySetup(setupId: String?) {
        _selectedOverlaySetupId.value = setupId
    }

    fun toggleTradePlanOverlay() {
        preferences.toggleTradePlanOverlay()
    }

    private val _selectedJournalSignalId = MutableStateFlow<Long?>(null)
    val selectedJournalSignalId: StateFlow<Long?> = _selectedJournalSignalId.asStateFlow()

    fun selectJournalSignal(id: Long?) {
        _selectedJournalSignalId.value = id
        val state = _uiState.value
        val entry = if (id != null) signalJournal.value.firstOrNull { it.id == id } else null
        val overlay = entry?.let { e ->
            if (!e.decision.equals("WAIT", ignoreCase = true) &&
                e.symbol.equals(state.activeSymbol, ignoreCase = true) &&
                com.example.chart.overlay.TradePlanOverlayMapper.timeframesMatch(e.primaryTimeframe, state.activeTimeframe.label)
            ) {
                SignalEngine.buildOverlayFromJournalEntity(e, isHistorical = false)
            } else null
        }
        _uiState.update { it.copy(selectedJournalOverlay = overlay) }
    }

    fun clearSelectedJournalSignal() {
        _selectedJournalSignalId.value = null
        _uiState.update { it.copy(selectedJournalOverlay = null) }
    }

    fun selectJournalSignalAndNavigateToChart(entity: SignalJournalEntity) {
        if (entity.decision.equals("WAIT", ignoreCase = true)) {
            _uiState.update {
                it.copy(
                    watchlistFeedbackMessage = "Cannot preview WAIT record: No actionable Entry/SL/TP levels.",
                    isWatchlistFeedbackError = true
                )
            }
            return
        }
        val entryMid = if (entity.entryPrice > 0.0) entity.entryPrice else (entity.entryMin + entity.entryMax) / 2.0
        if (entryMid <= 0.0 || entity.stopLoss <= 0.0 || entity.tp1 <= 0.0) {
            _uiState.update {
                it.copy(
                    watchlistFeedbackMessage = "Cannot preview on chart: Incomplete price levels (Entry, SL, or TP missing).",
                    isWatchlistFeedbackError = true
                )
            }
            return
        }
        val isLong = entity.decision.equals("LONG", ignoreCase = true)
        val isShort = entity.decision.equals("SHORT", ignoreCase = true)
        if (isLong && !(entity.stopLoss < entryMid && entryMid < entity.tp1)) {
            _uiState.update {
                it.copy(
                    watchlistFeedbackMessage = "Cannot preview on chart: Invalid price levels ordering for LONG setup.",
                    isWatchlistFeedbackError = true
                )
            }
            return
        }
        if (isShort && !(entity.tp1 < entryMid && entryMid < entity.stopLoss)) {
            _uiState.update {
                it.copy(
                    watchlistFeedbackMessage = "Cannot preview on chart: Invalid price levels ordering for SHORT setup.",
                    isWatchlistFeedbackError = true
                )
            }
            return
        }

        val overlay = SignalEngine.buildOverlayFromJournalEntity(entity, isHistorical = false)
        _selectedJournalSignalId.value = entity.id

        val cleanSym = entity.symbol.uppercase(Locale.US)
        val tf = Timeframe.fromInterval(entity.primaryTimeframe)
        if (!cleanSym.equals(_uiState.value.activeSymbol, ignoreCase = true) || tf != _uiState.value.activeTimeframe) {
            selectSymbol(cleanSym)
            selectTimeframe(tf)
        }

        _uiState.update {
            it.copy(
                selectedJournalOverlay = overlay,
                selectedTab = TerminalTab.CHART
            )
        }
    }

    private var candleLoadJob: Job? = null
    private var aiAnalysisJob: Job? = null
    private var aiRequestGeneration: Long = 0L
    private var lastIndicatorRecalcTimeMs: Long = 0L
    private var lastIndicatorCandleOpenTime: Long = -1L
    private var lastContinuousSetupCheckMs: Long = 0L
    private var lastAutoDetectedSetupSignature: String = ""
    private var monitorCheckpoint = MonitorEvaluationCheckpoint()

    fun isAutoAiMonitoringActive(cfg: TerminalSettings = preferences.settings.value): Boolean {
        return cfg.aiAnalysisSystem == AiAnalysisSystem.AI_CONTINUOUS &&
            cfg.aiContinuousSubmode == AiContinuousSubmode.AUTO
    }

    private fun buildModeAttributionLabel(cfg: TerminalSettings = preferences.settings.value): String {
        return when (cfg.aiAnalysisSystem) {
            AiAnalysisSystem.AI_CONTINUOUS -> "AI Continuous · ${cfg.aiContinuousSubmode.label}"
            AiAnalysisSystem.AI_TRADING_STYLE -> "AI Gaya Trading · ${cfg.tradingStyleProfile.label}"
        }
    }

    private fun evaluateActiveMarketStatus(
        state: TerminalUiState = _uiState.value,
        nowMs: Long = System.currentTimeMillis()
    ): MarketStatusEvaluation {
        val sym = state.activeSymbol
        val activeTicker = state.liveTickers[sym]
        val category = activeTicker?.category ?: inferInstrumentCategory(sym)
        val provider = activeTicker?.provider ?: inferMarketProvider(sym, category)
        val connStatus = when (provider) {
            MarketProvider.BIQUOTE -> biQuoteConnectionStatus.value
            MarketProvider.BINANCE -> connectionStatus.value
            MarketProvider.EXNESS -> when (exnessProvider.connectionStatus.value) {
                is ProviderConnectionStatus.Connected -> WsConnectionStatus.LIVE
                is ProviderConnectionStatus.Connecting -> WsConnectionStatus.RECONNECTING
                is ProviderConnectionStatus.Stale -> WsConnectionStatus.RECONNECTING
                is ProviderConnectionStatus.Disconnected,
                is ProviderConnectionStatus.Unconfigured,
                is ProviderConnectionStatus.Error -> WsConnectionStatus.DISCONNECTED
            }
        }
        val fallbackQuoteTs = when {
            activeTicker != null && activeTicker.updatedAt > 0L -> activeTicker.updatedAt
            state.currentPrice > 0.0 || state.candles.isNotEmpty() -> nowMs
            else -> nowMs
        }
        return MarketStatusService.evaluate(
            symbol = sym,
            category = category,
            provider = provider,
            ticker = activeTicker,
            fallbackQuoteTimestampMs = fallbackQuoteTs,
            connectionStatus = connStatus,
            hasFeedError = !state.restErrorMessage.isNullOrBlank() && state.candles.isEmpty(),
            providerReportedMarketOpen = activeTicker?.providerReportedMarketOpen,
            nowMs = nowMs
        )
    }

    init {
        TerminalNotificationHelper.ensureChannels(appContext)
        loadCachedSyncedModels()
        initializeTerminal()
        refreshExnessCatalog()
        observeWebSocketStreams()
        observeClockBoundaryEvents()
        observeWatchlistChanges()
        observeJournalForHistoricalOverlays()
    }

    private fun initializeTerminal() {
        viewModelScope.launch(safeHandler) {
            runCatching { watchlistRepo.ensureDefaultWatchlist() }

            // Restore persisted Bot Trading (Simulasi) state & positions across app restart
            withContext(Dispatchers.IO) {
                runCatching {
                    val persistedBot = preferences.readPersistedBotState()
                    val savedPositions = watchlistRepo.getAllBotPositionsOnce()
                    val restored = PaperTradingEngine.restoreAccountState(persistedBot, savedPositions)
                    _paperAccountState.value = restored
                }
            }

            val initialSymbol = preferences.getLastOpenedSymbol()
            val initialTimeframe = preferences.getLastOpenedTimeframeEnum()
            candleClock.sync(initialSymbol, initialTimeframe)
            candleClock.start(viewModelScope)
            loadMarketPairAndTimeframe(initialSymbol, initialTimeframe)

            // Load cached & live Economic Calendar
            refreshEconomicCalendar(initialLoad = true)

            launch(Dispatchers.IO + safeHandler) {
                runCatching {
                    val savedWatchlist = dao.getWatchlistOnce()
                    val binanceSymbols = (
                        savedWatchlist.filter { inferMarketProvider(it.symbol) == MarketProvider.BINANCE }.map { it.symbol } +
                            SymbolRepository.DEFAULT_PAIRS
                        ).distinct()
                    val biQuoteSymbols = savedWatchlist
                        .filter { inferMarketProvider(it.symbol) == MarketProvider.BIQUOTE }
                        .map { it.symbol }
                        .distinct()

                    when (val tickerRes = restClient.fetchInitialTickers(binanceSymbols)) {
                        is MarketApiResult.Success -> {
                            val map = tickerRes.data.associateBy { it.symbol }
                            _uiState.update { state ->
                                val activeTicker = map[state.activeSymbol]
                                state.copy(
                                    liveTickers = state.liveTickers + map,
                                    currentPrice = activeTicker?.lastPrice ?: state.currentPrice,
                                    priceChangePercent = activeTicker?.priceChangePercent ?: state.priceChangePercent
                                )
                            }
                            watchlistRepo.updateTickersBatch(tickerRes.data)
                        }
                        is MarketApiResult.Error -> {}
                    }

                    if (biQuoteSymbols.isNotEmpty()) {
                        when (val bqRes = biQuoteClient.fetchLatestQuotes(biQuoteSymbols)) {
                            is MarketApiResult.Success -> {
                                biQuoteRealtime.updateConnectionStatus(WsConnectionStatus.LIVE)
                                val bqMap = bqRes.data.associateBy { it.symbol }
                                _uiState.update { state ->
                                    val activeTicker = bqMap[state.activeSymbol]
                                    state.copy(
                                        liveTickers = state.liveTickers + bqMap,
                                        currentPrice = activeTicker?.lastPrice ?: state.currentPrice,
                                        priceChangePercent = activeTicker?.priceChangePercent ?: state.priceChangePercent
                                    )
                                }
                                watchlistRepo.updateTickersBatch(bqRes.data)
                            }
                            is MarketApiResult.Error -> {
                                if (bqRes.isOffline) {
                                    biQuoteRealtime.updateConnectionStatus(WsConnectionStatus.DISCONNECTED)
                                }
                            }
                        }
                    }

                    // Also fetch active multi-market quotes from BiQuote /api/active for discovery
                    when (val activeBq = biQuoteClient.fetchActiveQuotes()) {
                        is MarketApiResult.Success -> {
                            biQuoteRealtime.updateConnectionStatus(WsConnectionStatus.LIVE)
                            if (activeBq.data.isNotEmpty()) {
                                val bqMap = activeBq.data.associateBy { it.symbol }
                                _uiState.update { state ->
                                    state.copy(liveTickers = bqMap + state.liveTickers)
                                }
                                symbolRepo.syncSymbolsFromTickers(activeBq.data)
                            }
                        }
                        is MarketApiResult.Error -> {}
                    }

                    // Also fetch dynamic top USDT market tickers for Market Discovery & Search
                    when (val dynRes = restClient.fetchDynamicUsdtTickers(maxCount = 250)) {
                        is MarketApiResult.Success -> {
                            val dynMap = dynRes.data.associateBy { it.symbol }
                            _uiState.update { state ->
                                state.copy(liveTickers = dynMap + state.liveTickers)
                            }
                            symbolRepo.syncSymbolsFromTickers(dynRes.data)
                        }
                        is MarketApiResult.Error -> {}
                    }

                    val cachedCount = symbolRepo.ensureSymbolsCached(forceRefresh = false)
                    _uiState.update { it.copy(cachedSymbolCount = cachedCount) }
                }
            }

            syncForegroundMonitoringService()
        }
    }

    fun refreshEconomicCalendar(initialLoad: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO + safeHandler) {
            runCatching {
                val (cachedJson, cachedTs) = preferences.getCachedCalendarPayload()
                val cachedEvents: List<EconomicCalendarEvent> = if (cachedJson.isNotBlank()) {
                    EconomicCalendarService.parseEventsJson(cachedJson).orEmpty()
                } else {
                    emptyList()
                }

                if (initialLoad && cachedEvents.isNotEmpty()) {
                    _calendarState.value = CalendarLoadState.Success(
                        events = cachedEvents,
                        lastUpdatedMs = cachedTs,
                        fromCache = true
                    )
                    recomputeEventRiskAndBotDirective()
                } else if (!initialLoad) {
                    _calendarState.value = CalendarLoadState.Loading
                    recomputeEventRiskAndBotDirective()
                }

                val fetchedState = calendarService.fetchCalendarEvents(cachedEvents = cachedEvents)
                if (fetchedState is CalendarLoadState.Success && fetchedState.events.isNotEmpty()) {
                    val serialized = EconomicCalendarService.serializeEventsToJson(fetchedState.events)
                    preferences.saveCachedCalendarPayload(serialized, fetchedState.lastUpdatedMs)
                }
                _calendarState.value = fetchedState
                recomputeEventRiskAndBotDirective()
            }
        }
    }

    private fun recomputeEventRiskAndBotDirective(nowMs: Long = System.currentTimeMillis()) {
        val cfg = preferences.settings.value
        val activeSymbol = _uiState.value.activeSymbol
        val calState = _calendarState.value
        val mode = EventProtectionMode.fromCode(cfg.eventProtectionMode)
        val unavailPolicy = UnavailableEventPolicy.fromCode(cfg.unavailableEventPolicy)

        val assessment = EventRiskManager.evaluateEventRiskFromCalendar(
            symbol = activeSymbol,
            calendarState = calState,
            protectionMode = mode,
            preBufferMinutes = cfg.eventPreBufferMinutes,
            postBufferMinutes = cfg.eventPostBufferMinutes,
            unavailablePolicy = unavailPolicy,
            manualEventLockActive = cfg.manualEventLockActive,
            nowMs = nowMs
        )

        val upcomingList = calState.availableEvents
            .map { EventRiskManager.toMacroEvent(it, cfg.eventPreBufferMinutes, cfg.eventPostBufferMinutes) }
            .filter { it.timestampMs + cfg.eventPostBufferMinutes * 60_000L >= nowMs - 15 * 60_000L }
            .sortedBy { it.timestampMs }

        if (assessment.isEventWindowActive && cfg.notificationsEnabled && cfg.notifyRiskEvents) {
            val evTitle = assessment.activeOrNextEvent?.title ?: "High-Impact Event"
            TerminalNotificationHelper.notifyRiskOrEventWarning(
                context = appContext,
                title = "Event Risk Alert · $activeSymbol",
                message = "${assessment.statusBadge}: ${assessment.summaryMessage}",
                dedupKey = "EVENT_${activeSymbol}_$evTitle"
            )
        }

        val marketStatus = evaluateActiveMarketStatus(_uiState.value, nowMs)
        val activeProvider = inferMarketProvider(activeSymbol)
        val activeWsStatus = when (activeProvider) {
            MarketProvider.BIQUOTE -> biQuoteConnectionStatus.value
            MarketProvider.BINANCE -> connectionStatus.value
            MarketProvider.EXNESS -> when (exnessProvider.connectionStatus.value) {
                is ProviderConnectionStatus.Connected -> WsConnectionStatus.LIVE
                is ProviderConnectionStatus.Connecting -> WsConnectionStatus.RECONNECTING
                is ProviderConnectionStatus.Stale -> WsConnectionStatus.RECONNECTING
                is ProviderConnectionStatus.Disconnected,
                is ProviderConnectionStatus.Unconfigured,
                is ProviderConnectionStatus.Error -> WsConnectionStatus.DISCONNECTED
            }
        }
        val runtimeMonitorState = AutoSetupEngineV2.resolveAutoMonitorRuntimeState(
            aiSystem = cfg.aiAnalysisSystem,
            continuousSubmode = cfg.aiContinuousSubmode,
            isAnalyzing = _uiState.value.isAiAnalyzing,
            hasCandles = _uiState.value.candles.isNotEmpty(),
            wsStatus = activeWsStatus,
            marketStatus = marketStatus
        )
        val botDirective = resolveBotActionSummary(
            botState = _paperAccountState.value,
            aiResult = _uiState.value.aiResult,
            activeSetup = _uiState.value.activeSetup,
            marketStatus = marketStatus,
            eventRisk = assessment,
            activeSymbol = activeSymbol,
            activeTimeframe = _uiState.value.activeTimeframe.label
        )

        _uiState.update { curr ->
            curr.copy(
                eventRiskAssessment = assessment,
                upcomingMacroEvents = upcomingList,
                botActionSummary = botDirective,
                aiAnalysisSystem = cfg.aiAnalysisSystem,
                aiContinuousSubmode = cfg.aiContinuousSubmode,
                tradingStyleProfile = cfg.tradingStyleProfile,
                autoMonitorRuntimeState = runtimeMonitorState,
                activeModeAttributionLabel = buildModeAttributionLabel(cfg)
            )
        }
    }

    private fun resolveBotActionSummary(
        botState: PaperTradingAccountState,
        aiResult: AiAnalysisResult?,
        activeSetup: ActiveTradeSetup? = null,
        marketStatus: MarketStatusEvaluation? = null,
        eventRisk: EventRiskAssessment,
        activeSymbol: String,
        activeTimeframe: String
    ): String {
        val openPos = botState.openPositions.firstOrNull {
            it.symbol.equals(activeSymbol, ignoreCase = true) &&
                it.timeframe.equals(activeTimeframe, ignoreCase = true)
        }
        if (openPos != null) {
            return "OPEN ${openPos.side} @ $${formatCryptoPrice(openPos.entryPrice)}"
        }
        if (marketStatus != null && !AutoSetupEngineV2.isMarketAndDataValidForNewEntry(marketStatus)) {
            return "BLOCKED · ${marketStatus.shortBadgeLabel}"
        }
        if (eventRisk.shouldBlockNewBotEntries) {
            return eventRisk.botActionDirective
        }
        if (botState.botRunState == BotRunState.STOPPED) {
            return "STOPPED"
        }
        if (botState.botRunState == BotRunState.PAUSED) {
            return if (botState.isDailyLossLimitReached()) {
                "PAUSED — DAILY LOSS LIMIT"
            } else {
                "PAUSED"
            }
        }
        val liveSetup = activeSetup?.takeIf { !it.lifecycleState.isTerminal }
        if (liveSetup != null) {
            if (!PaperTradingEngine.isGradeQualified(liveSetup.setupGrade, botState.config.minSetupGrade)) {
                return "WAIT — GRADE ${liveSetup.setupGrade} < ${botState.config.minSetupGrade}"
            }
            return when (liveSetup.lifecycleState) {
                SetupLifecycleState.PARTIALLY_TRIGGERED -> "RUNNING · TRIGGER BAND ACTIVE"
                SetupLifecycleState.ENTERED -> "RUNNING · ENTRY TRIGGERED"
                else -> "RUNNING · MONITORING ENTRY ZONE"
            }
        }
        if (aiResult == null || aiResult.isWait) {
            return "RUNNING · SCANNING MARKET"
        }
        if (!PaperTradingEngine.isGradeQualified(aiResult.setupQualityGrade, botState.config.minSetupGrade)) {
            return "WAIT — GRADE ${aiResult.setupQualityGrade} < ${botState.config.minSetupGrade}"
        }
        return if (!eventRisk.isEventDataVerified) {
            "MONITORING TRIGGER (EVENT UNVERIFIED)"
        } else {
            "RUNNING · MONITORING ENTRY TRIGGER"
        }
    }

    private fun observeWatchlistChanges() {
        viewModelScope.launch {
            watchlist.collect { items ->
                if (items.isNotEmpty()) {
                    val binanceSymbols = items
                        .filter { inferMarketProvider(it.symbol) == MarketProvider.BINANCE }
                        .map { it.symbol }
                    val biQuoteSymbols = items
                        .filter { inferMarketProvider(it.symbol) == MarketProvider.BIQUOTE }
                        .map { it.symbol }
                    if (binanceSymbols.isNotEmpty()) {
                        wsManager.updateWatchlistStreams(binanceSymbols)
                    }
                    biQuoteRealtime.updateWatchlistSymbols(biQuoteSymbols)
                }
            }
        }
    }

    private fun observeJournalForHistoricalOverlays() {
        viewModelScope.launch {
            signalJournal.collect { entries ->
                val state = _uiState.value
                val histOverlays = buildHistoricalOverlaysForActivePair(
                    entries = entries,
                    symbol = state.activeSymbol,
                    timeframe = state.activeTimeframe
                )
                val latestBase = entries.firstOrNull {
                    it.symbol.equals(state.activeSymbol, ignoreCase = true) &&
                        com.example.chart.overlay.TradePlanOverlayMapper.timeframesMatch(it.primaryTimeframe, state.activeTimeframe.label) &&
                        it.source.equals("BASELINE", ignoreCase = true)
                }
                val selOverlay = _selectedJournalSignalId.value?.let { selId ->
                    entries.firstOrNull { it.id == selId }?.let { e ->
                        if (!e.decision.equals("WAIT", ignoreCase = true) &&
                            e.symbol.equals(state.activeSymbol, ignoreCase = true) &&
                            com.example.chart.overlay.TradePlanOverlayMapper.timeframesMatch(e.primaryTimeframe, state.activeTimeframe.label)
                        ) {
                            SignalEngine.buildOverlayFromJournalEntity(e, isHistorical = false)
                        } else null
                    }
                }
                _uiState.update {
                    it.copy(
                        historicalSignalOverlays = histOverlays,
                        latestBaselineSignal = latestBase,
                        selectedJournalOverlay = selOverlay
                    )
                }
            }
        }
    }

    private fun buildHistoricalOverlaysForActivePair(
        entries: List<SignalJournalEntity>,
        symbol: String,
        timeframe: Timeframe
    ): List<ChartSignalOverlay> {
        val nowMs = System.currentTimeMillis()
        val seenKeys = HashSet<String>()
        val result = ArrayList<ChartSignalOverlay>()

        val eligible = entries
            .asSequence()
            .filter { e ->
                e.symbol.equals(symbol, ignoreCase = true) &&
                    com.example.chart.overlay.TradePlanOverlayMapper.timeframesMatch(e.primaryTimeframe, timeframe.label) &&
                    !e.decision.equals("WAIT", ignoreCase = true) &&
                    (e.decision.equals("LONG", ignoreCase = true) || e.decision.equals("SHORT", ignoreCase = true)) &&
                    (e.source.equals("AI", ignoreCase = true) || e.source.equals("BASELINE", ignoreCase = true)) &&
                    e.timestamp in 1L..(nowMs + 60_000L)
            }
            .sortedByDescending { it.timestamp }

        for (e in eligible) {
            val entryMid = if (e.entryPrice > 0.0) e.entryPrice else (e.entryMin + e.entryMax) / 2.0
            if (entryMid <= 0.0 || e.stopLoss <= 0.0 || e.tp1 <= 0.0) continue

            val isLong = e.decision.equals("LONG", ignoreCase = true)
            val isShort = e.decision.equals("SHORT", ignoreCase = true)
            if (isLong && !(e.stopLoss < entryMid && entryMid < e.tp1)) continue
            if (isShort && !(e.tp1 < entryMid && entryMid < e.stopLoss)) continue

            val cleanSource = e.source.ifBlank { "AI" }.uppercase(Locale.US)
            val dedupeKey = "${cleanSource}_${e.decision.uppercase(Locale.US)}_${e.candleOpenTimeAtSignal}_${com.example.analysis.supportResistance.SupportResistanceEngine.roundPriceCleanly(entryMid)}"
            if (!seenKeys.add(dedupeKey)) continue

            val overlay = SignalEngine.buildOverlayFromJournalEntity(e, isHistorical = true)
            if (overlay != null) {
                result.add(overlay)
                if (result.size >= 5) break
            }
        }

        return result
    }

    /**
     * Observes `CandleClock` reaching `00:00` so that even if a WebSocket message is momentarily
     * delayed, the closing candle is immediately recognized as CONFIRMED and a new live candle starts.
     */
    private fun observeClockBoundaryEvents() {
        viewModelScope.launch {
            candleClock.candleBoundaryClosedEvents.collect { boundaryEvent ->
                val state = _uiState.value
                if (!boundaryEvent.symbol.equals(state.activeSymbol, ignoreCase = true) ||
                    boundaryEvent.timeframe != state.activeTimeframe
                ) {
                    return@collect
                }
                val closeEvent = confirmationEngine.onClockBoundaryReached(
                    symbol = state.activeSymbol,
                    timeframe = state.activeTimeframe,
                    currentCandles = state.candles,
                    boundaryEvent = boundaryEvent
                )
                if (closeEvent != null) {
                    _uiState.update { it.copy(candles = closeEvent.allCandlesWithLive) }
                    if (preferences.settings.value.autoAiAnalysis) {
                        runAiAnalysis(isAutomaticTrigger = true)
                    }
                }
            }
        }
    }

    private fun observeWebSocketStreams() {
        // Real-time Kline / Candlestick updates from Binance WebSocket, BiQuote & Exness Provider
        viewModelScope.launch {
            merge(wsManager.klineEvents, biQuoteRealtime.klineEvents, exnessProvider.klineEvents).collect { event ->
                val state = _uiState.value
                if (!event.symbol.equals(state.activeSymbol, ignoreCase = true) ||
                    event.timeframe != state.activeTimeframe
                ) {
                    return@collect
                }

                // Synchronize CandleClock with Binance eventTime & candle timing
                candleClock.sync(
                    symbol = event.symbol,
                    timeframe = event.timeframe,
                    latestCandle = event.candle,
                    binanceEventTimeMs = event.eventTime
                )

                val mergedCandles = withContext(Dispatchers.Default) {
                    CandleMerger.mergeRealtimeCandle(state.candles, event.candle)
                }

                val confirmedCloseEvent = confirmationEngine.onRealtimeKlineMerged(
                    symbol = event.symbol,
                    timeframe = event.timeframe,
                    previousCandles = state.candles,
                    mergedCandles = mergedCandles,
                    incomingCandle = event.candle,
                    currentServerTimeMs = candleClock.currentServerTimeMs()
                )

                val now = System.currentTimeMillis()
                val shouldRecalcIndicators = event.candle.isClosed ||
                    confirmedCloseEvent != null ||
                    event.candle.openTime != lastIndicatorCandleOpenTime ||
                    state.indicators.ema20.isEmpty() ||
                    (now - lastIndicatorRecalcTimeMs) >= 600L

                val recomputedIndicators = if (shouldRecalcIndicators) {
                    lastIndicatorRecalcTimeMs = now
                    lastIndicatorCandleOpenTime = event.candle.openTime
                    val indParams = currentIndicatorParams()
                    withContext(Dispatchers.Default) {
                        TechnicalIndicators.calculateAll(mergedCandles, indParams)
                    }
                } else {
                    state.indicators
                }

                val recomputedSnapshot = if (shouldRecalcIndicators) {
                    withContext(Dispatchers.Default) {
                        val existingHigher = state.snapshot?.higherContext
                        val existingLower = state.snapshot?.lowerContext
                        val activeTicker = state.liveTickers[state.activeSymbol]
                        val mktStatus = evaluateActiveMarketStatus(state, now)
                        val botCfg = _paperAccountState.value.config
                        val built = TechnicalSnapshotBuilder.build(
                            symbol = state.activeSymbol,
                            timeframe = state.activeTimeframe,
                            candles = mergedCandles,
                            indicators = recomputedIndicators,
                            bidPrice = activeTicker?.bidPrice,
                            askPrice = activeTicker?.askPrice,
                            hasVerifiedBidAsk = activeTicker?.hasVerifiedBidAsk == true,
                            marketStatus = mktStatus,
                            eventRiskAssessment = state.eventRiskAssessment,
                            profileCode = preferences.settings.value.tradingStyleProfile.code,
                            accountEquityUsd = _paperAccountState.value.equityUsd,
                            riskPerTradePercent = botCfg.riskPerTradePercent,
                            maxLeverage = botCfg.leverage,
                            evaluationTimeMs = now
                        )?.copy(
                            higherContext = existingHigher,
                            lowerContext = existingLower
                        )
                        built?.smcAnalysis?.latestActiveSweep?.let { sweep ->
                            com.example.analysis.smc.SmcLiquiditySweepEngine.registerSweepEmissionIfNew(sweep)
                        }
                        built
                    }
                } else {
                    state.snapshot?.copy(
                        currentPrice = event.candle.close,
                        latestCandle = event.candle
                    )
                }

                // 1. Update Bot Trading (Simulasi) open positions on live market price tick
                processLiveBotPriceUpdate(
                    symbol = state.activeSymbol,
                    livePrice = event.candle.close,
                    nowMs = now
                )

                // 2. Evaluate ActiveSetup lifecycle & Simulation Bot auto-entry on live price tick
                val (updatedActiveSetup, updatedOverlay) = evaluateActiveSetupAndOverlayOnLiveTick(
                    symbol = state.activeSymbol,
                    timeframe = state.activeTimeframe,
                    livePrice = event.candle.close,
                    nowMs = now
                )

                // 2b. Evaluate Tracked Pending Setups (V3.2 Smart Pending Setup Tracker)
                launch(Dispatchers.IO) {
                    trackedSetupRepo.evaluateCandle(
                        symbol = state.activeSymbol,
                        timeframe = state.activeTimeframe.label,
                        candle = event.candle,
                        nowMs = now
                    )
                    trackedSetupRepo.evaluatePriceTick(
                        symbol = state.activeSymbol,
                        currentPrice = event.candle.close,
                        nowMs = now
                    )
                }

                recomputeEventRiskAndBotDirective(now)

                _uiState.update { curr ->
                    val openFirst = mergedCandles.firstOrNull()?.open ?: event.candle.open
                    val fallbackChange = if (openFirst > 0.0) {
                        ((event.candle.close - openFirst) / openFirst) * 100.0
                    } else {
                        curr.priceChangePercent
                    }
                    val tickerChange = curr.liveTickers[curr.activeSymbol]?.priceChangePercent ?: fallbackChange

                    curr.copy(
                        currentPrice = event.candle.close,
                        priceChangePercent = tickerChange,
                        candles = mergedCandles,
                        indicators = recomputedIndicators,
                        snapshot = recomputedSnapshot,
                        activeSetup = updatedActiveSetup,
                        activeSignalOverlay = updatedOverlay,
                        restErrorMessage = null
                    )
                }

                // 3. Forward-test active signals in background (both candle & live price)
                if (shouldRecalcIndicators) {
                    launch(Dispatchers.IO) {
                        watchlistRepo.evaluateActiveSignalsOnLiveTick(
                            symbol = state.activeSymbol,
                            timeframe = state.activeTimeframe,
                            livePrice = event.candle.close,
                            nowMs = now
                        )
                        watchlistRepo.evaluateActiveSignalsForPair(
                            symbol = state.activeSymbol,
                            timeframe = state.activeTimeframe,
                            candles = mergedCandles
                        )
                    }
                }

                // 4. Continuous Market Monitoring (Intrabar + Confirmed Candle Close)
                val currentSettings = preferences.settings.value
                val elapsedSinceLocalCheck = now - monitorCheckpoint.lastLocalEvalMs
                if (recomputedSnapshot != null &&
                    (confirmedCloseEvent != null || elapsedSinceLocalCheck >= AutoSetupEngineV2.MIN_LOCAL_ASSESSMENT_INTERVAL_MS)
                ) {
                    val liveCandidate = withContext(Dispatchers.Default) {
                        builtInProvider.synthesizeV2MultiTimeframeAnalysis(
                            snapshot = recomputedSnapshot,
                            badge = "AI · Primary",
                            modelName = "nexsync-mtf-v2"
                        )
                    }
                    val marketStatus = evaluateActiveMarketStatus(_uiState.value, now)
                    val monitorDecision = AutoSetupEngineV2.evaluateContinuousMonitoring(
                        checkpoint = monitorCheckpoint,
                        snapshot = recomputedSnapshot,
                        currentAssessment = _uiState.value.marketAssessment,
                        activeSetup = _uiState.value.activeSetup,
                        localCandidate = liveCandidate,
                        marketStatus = marketStatus,
                        confirmedCandleJustClosed = confirmedCloseEvent != null,
                        minAutoGrade = currentSettings.autoSignalMinGrade,
                        nowMs = now
                    )
                    monitorCheckpoint = monitorDecision.updatedCheckpoint
                    _uiState.update { it.copy(marketAssessment = monitorDecision.updatedAssessment) }

                    // Continuous Mode Baseline Signal Synchronization
                    if (confirmedCloseEvent != null) {
                        launch(Dispatchers.IO) {
                            val baseCandidate = BaselineSignalGenerator.generate(recomputedSnapshot, now)
                            if (baseCandidate != null && !baseCandidate.isWait) {
                                val alreadyHasBase = signalJournal.value.any {
                                    it.symbol.equals(state.activeSymbol, ignoreCase = true) &&
                                        com.example.chart.overlay.TradePlanOverlayMapper.timeframesMatch(it.primaryTimeframe, state.activeTimeframe.label) &&
                                        it.source.equals(BaselineSignalGenerator.SOURCE_BASELINE, ignoreCase = true) &&
                                        it.candleOpenTimeAtSignal == recomputedSnapshot.latestCandle.openTime
                                }
                                if (!alreadyHasBase) {
                                    watchlistRepo.recordSignalToJournal(
                                        snapshot = recomputedSnapshot,
                                        result = baseCandidate,
                                        botAction = "NONE",
                                        source = BaselineSignalGenerator.SOURCE_BASELINE,
                                        lifecycleState = SetupLifecycleState.ACTIVE.code,
                                        statusReason = "Continuous Baseline EMA reference (${baseCandidate.decision})"
                                    )
                                }
                            }
                        }
                    }

                    if (isAutoAiMonitoringActive(currentSettings) &&
                        !_uiState.value.isAiAnalyzing &&
                        monitorDecision.shouldTriggerFullAiAnalysis
                    ) {
                        lastContinuousSetupCheckMs = now
                        lastAutoDetectedSetupSignature = liveCandidate?.setupSignature.orEmpty()
                        runAiAnalysis(isAutomaticTrigger = true)
                    }
                }
            }
        }

        // Real-time 24h Ticker updates from Binance WebSocket, BiQuote & Exness Provider
        viewModelScope.launch {
            merge(wsManager.tickerEvents, biQuoteRealtime.tickerEvents, exnessProvider.tickerEvents).collect { ticker ->
                val now = System.currentTimeMillis()
                _uiState.update { curr ->
                    val updatedMap = curr.liveTickers.toMutableMap()
                    updatedMap[ticker.symbol] = ticker
                    val isActive = ticker.symbol.equals(curr.activeSymbol, ignoreCase = true)
                    curr.copy(
                        liveTickers = updatedMap,
                        currentPrice = if (isActive) ticker.lastPrice else curr.currentPrice,
                        priceChangePercent = if (isActive) ticker.priceChangePercent else curr.priceChangePercent
                    )
                }
                // Keep open bot positions for non-chart watchlist symbols updated too
                processLiveBotPriceUpdate(
                    symbol = ticker.symbol,
                    livePrice = ticker.lastPrice,
                    nowMs = now
                )

                // Evaluate Tracked Pending Setups on live price ticks
                launch(Dispatchers.IO) {
                    trackedSetupRepo.evaluatePriceTick(
                        symbol = ticker.symbol,
                        currentPrice = ticker.lastPrice,
                        nowMs = now
                    )
                }

                // Also evaluate ActiveSetup on live ticker updates for the active symbol
                val currState = _uiState.value
                if (ticker.symbol.equals(currState.activeSymbol, ignoreCase = true) && ticker.lastPrice > 0.0) {
                    val (updatedActiveSetup, updatedOverlay) = evaluateActiveSetupAndOverlayOnLiveTick(
                        symbol = currState.activeSymbol,
                        timeframe = currState.activeTimeframe,
                        livePrice = ticker.lastPrice,
                        nowMs = now
                    )
                    if (updatedActiveSetup != currState.activeSetup || updatedOverlay != currState.activeSignalOverlay) {
                        _uiState.update {
                            it.copy(
                                activeSetup = updatedActiveSetup,
                                activeSignalOverlay = updatedOverlay
                            )
                        }
                        recomputeEventRiskAndBotDirective(now)
                    }
                }
                watchlistRepo.updateTicker(ticker)
            }
        }

        // Observe Exness connection status & data staleness
        viewModelScope.launch {
            exnessProvider.connectionStatus.collect { exStatus ->
                _uiState.update { curr ->
                    curr.copy(
                        exnessConnectionStatus = exStatus,
                        isMarketDataStale = exStatus is ProviderConnectionStatus.Stale
                    )
                }
            }
        }
    }

    private fun evaluateActiveSetupAndOverlayOnLiveTick(
        symbol: String,
        timeframe: Timeframe,
        livePrice: Double,
        nowMs: Long
    ): Pair<ActiveTradeSetup?, ChartSignalOverlay?> {
        val state = _uiState.value
        val currentSetup = state.activeSetup
        val openBotBefore = _paperAccountState.value.openPositions.firstOrNull {
            it.symbol.equals(symbol, ignoreCase = true) &&
                it.timeframe.equals(timeframe.label, ignoreCase = true)
        }

        if (currentSetup != null &&
            currentSetup.symbol.equals(symbol, ignoreCase = true) &&
            currentSetup.timeframe.equals(timeframe.label, ignoreCase = true) &&
            !currentSetup.lifecycleState.isTerminal
        ) {
            val marketStatus = evaluateActiveMarketStatus(state, nowMs)
            val tickEval = AutoSetupEngineV2.evaluateActiveSetupOnLiveTick(
                setup = currentSetup,
                livePrice = livePrice,
                marketStatus = marketStatus,
                isBotRunning = _paperAccountState.value.botRunState == BotRunState.RUNNING,
                hasOpenBotPositionForSetup = openBotBefore != null,
                nowMs = nowMs
            )

            var workingSetup = tickEval.updatedSetup
            if (tickEval.shouldAttemptBotAutoEntry && openBotBefore == null) {
                val botAct = tryAutoBotEntryForSignal(
                    symbol = symbol,
                    timeframe = timeframe.label,
                    aiResult = workingSetup.sourceAiResult,
                    livePrice = livePrice,
                    signalId = workingSetup.setupId,
                    nowMs = nowMs
                )
                workingSetup = workingSetup.copy(botAction = botAct)
            }

            val openBotAfter = _paperAccountState.value.openPositions.firstOrNull {
                it.symbol.equals(symbol, ignoreCase = true) &&
                    it.timeframe.equals(timeframe.label, ignoreCase = true)
            }
            if (openBotAfter != null) {
                workingSetup = workingSetup.copy(
                    lifecycleState = if (workingSetup.lifecycleState in listOf(
                            SetupLifecycleState.ACTIVE,
                            SetupLifecycleState.PARTIALLY_TRIGGERED
                        )
                    ) {
                        SetupLifecycleState.ENTERED
                    } else {
                        workingSetup.lifecycleState
                    },
                    linkedBotPositionId = openBotAfter.id,
                    botAction = "ENTERED (${openBotAfter.id})"
                )
            }

            val nextOverlay = AutoSetupEngineV2.buildOverlayFromActiveSetup(workingSetup, openBotAfter)

            if (tickEval.didLifecycleChange || workingSetup.lifecycleState != currentSetup.lifecycleState) {
                val persistedSetup = workingSetup
                viewModelScope.launch(Dispatchers.IO) {
                    watchlistRepo.updateSignalSetupLifecycle(
                        setupId = persistedSetup.setupId,
                        lifecycleState = persistedSetup.lifecycleState,
                        forwardStatus = persistedSetup.forwardStatus,
                        statusReason = persistedSetup.statusReason,
                        highestTpReached = persistedSetup.highestTpReached,
                        realizedR = persistedSetup.realizedR
                    )
                }
                if (nextOverlay != null) {
                    handleSignalOverlayLifecycleNotification(nextOverlay)
                } else if (workingSetup.lifecycleState in listOf(
                        SetupLifecycleState.INVALIDATED,
                        SetupLifecycleState.EXPIRED
                    )
                ) {
                    val cfg = preferences.settings.value
                    if (cfg.notificationsEnabled && cfg.notifySignalInvalidation) {
                        TerminalNotificationHelper.notifySignalInvalidatedOrExpired(
                            context = appContext,
                            symbol = workingSetup.symbol,
                            timeframe = workingSetup.timeframe,
                            decision = workingSetup.direction,
                            status = workingSetup.lifecycleState.code,
                            signalId = workingSetup.setupId
                        )
                    }
                }
            }

            return workingSetup to nextOverlay
        }

        // Legacy fallback if activeSignalOverlay exists without activeSetup
        val updatedOverlay = state.activeSignalOverlay?.let { overlay ->
            val prevStatus = overlay.status
            val nextOverlay = SignalEngine.evaluateLivePriceTick(
                overlay = overlay,
                livePrice = livePrice,
                nowMs = nowMs
            ).copy(
                botActionLabel = openBotBefore?.let { "BOT ${it.side}" } ?: "",
                botUnrealizedPnlUsd = openBotBefore?.unrealizedPnlUsd
            )
            if (nextOverlay.status != prevStatus) {
                handleSignalOverlayLifecycleNotification(nextOverlay)
            }
            nextOverlay
        }

        val activeAi = state.aiResult
        if (activeAi != null && !activeAi.isWait && _paperAccountState.value.botRunState == BotRunState.RUNNING) {
            tryAutoBotEntryForSignal(
                symbol = symbol,
                timeframe = timeframe.label,
                aiResult = activeAi,
                livePrice = livePrice,
                signalId = updatedOverlay?.signalId ?: 0L,
                nowMs = nowMs
            )
        }

        return currentSetup to updatedOverlay
    }

    private fun handleSignalOverlayLifecycleNotification(overlay: ChartSignalOverlay) {
        val cfg = preferences.settings.value
        if (!cfg.notificationsEnabled) return

        when (overlay.status.code) {
            "TP1_HIT", "TP2_HIT", "TP3_HIT", "SL_HIT" -> {
                if (cfg.notifyTpSlHits) {
                    TerminalNotificationHelper.notifyTpOrSlHit(
                        context = appContext,
                        symbol = overlay.symbol,
                        timeframe = overlay.timeframe,
                        side = overlay.decision,
                        eventLabel = overlay.status.displayLabel,
                        price = overlay.entry,
                        pnlText = "Signal Target Update",
                        uniqueEventId = "SIG_${overlay.signalId}"
                    )
                }
            }
            "INVALIDATED", "EXPIRED" -> {
                if (cfg.notifySignalInvalidation) {
                    TerminalNotificationHelper.notifySignalInvalidatedOrExpired(
                        context = appContext,
                        symbol = overlay.symbol,
                        timeframe = overlay.timeframe,
                        decision = overlay.decision,
                        status = overlay.status.code,
                        signalId = overlay.signalId
                    )
                }
            }
        }
    }

    private fun processLiveBotPriceUpdate(
        symbol: String,
        livePrice: Double,
        nowMs: Long
    ) {
        val currentBot = _paperAccountState.value
        if (currentBot.openPositions.none { it.symbol.equals(symbol, ignoreCase = true) }) return

        val (updatedState, transitionEvents) = PaperTradingEngine.updateLiveMarketPriceWithEvents(
            state = currentBot,
            symbol = symbol,
            livePrice = livePrice,
            nowMs = nowMs
        )

        // Check if daily loss limit was reached after update
        val finalState = if (updatedState.botRunState == BotRunState.RUNNING &&
            updatedState.isDailyLossLimitReached(nowMs)
        ) {
            val paused = updatedState.copy(
                botRunState = BotRunState.PAUSED,
                config = updatedState.config.copy(autoExecuteConfirmedSignals = false),
                lastBotStatusMessage = "Auto-Paused: Daily loss limit (${updatedState.config.dailyLossLimitPct}%) reached"
            )
            val cfg = preferences.settings.value
            if (cfg.notificationsEnabled && cfg.notifyRiskEvents) {
                TerminalNotificationHelper.notifyRiskOrEventWarning(
                    context = appContext,
                    title = "Bot Auto-Paused · Daily Loss Limit",
                    message = paused.lastBotStatusMessage,
                    dedupKey = "DAILY_LOSS_${nowMs / 3_600_000L}"
                )
            }
            paused
        } else {
            updatedState
        }

        _paperAccountState.value = finalState
        preferences.savePersistedBotState(finalState.toPersistedBotState())

        if (transitionEvents.isNotEmpty()) {
            val cfg = preferences.settings.value
            viewModelScope.launch(Dispatchers.IO) {
                transitionEvents.forEach { ev ->
                    watchlistRepo.upsertBotPosition(ev.position.toEntity())
                    if (cfg.notificationsEnabled && cfg.notifyTpSlHits) {
                        val sign = if (ev.realizedPnlDeltaUsd >= 0) "+" else ""
                        val pnlStr = "Realized ${sign}$${String.format(Locale.US, "%.2f", ev.realizedPnlDeltaUsd)}"
                        TerminalNotificationHelper.notifyTpOrSlHit(
                            context = appContext,
                            symbol = ev.position.symbol,
                            timeframe = ev.position.timeframe,
                            side = ev.position.side,
                            eventLabel = ev.eventType.replace("_", " "),
                            price = ev.triggerPrice,
                            pnlText = pnlStr,
                            uniqueEventId = ev.position.id
                        )
                    }
                }
            }
        }
    }

    private fun tryAutoBotEntryForSignal(
        symbol: String,
        timeframe: String,
        aiResult: AiAnalysisResult,
        livePrice: Double,
        signalId: Long,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        val state = _uiState.value
        val currentBot = _paperAccountState.value
        val eventRisk = state.eventRiskAssessment
        val marketStatus = evaluateActiveMarketStatus(state, nowMs)
        val isExnessStaleOrOffline = state.activeMarketProvider == MarketProvider.EXNESS &&
            exnessProvider.connectionStatus.value.isStaleOrOffline
        val marketDataBlocked = !AutoSetupEngineV2.isMarketAndDataValidForNewEntry(marketStatus) || isExnessStaleOrOffline
        val marketDataReasonText = if (isExnessStaleOrOffline) {
            "Exness market data is disconnected or stale"
        } else {
            marketStatus.readableStatusText
        }
        val activeTicker = state.liveTickers[symbol.uppercase(Locale.US)]
        val spreadVal = state.snapshot?.smcAnalysis?.spreadVolatilityValidation
        val spreadBlocked = spreadVal != null && (!spreadVal.isSpreadCheckPassed || !spreadVal.isVolatilityAcceptable)

        val outcome = PaperTradingEngine.evaluateAutoEntry(
            state = currentBot,
            symbol = symbol,
            timeframe = timeframe,
            aiResult = aiResult,
            liveMarketPrice = livePrice,
            signalId = signalId,
            eventRiskBlocked = eventRisk.shouldBlockNewBotEntries,
            eventRiskReason = eventRisk.botActionDirective,
            marketDataBlocked = marketDataBlocked,
            marketDataReason = marketDataReasonText,
            spreadBlocked = spreadBlocked,
            spreadBlockReason = spreadVal?.spreadStatusSummary.orEmpty(),
            bidPrice = activeTicker?.bidPrice ?: spreadVal?.bidPrice,
            askPrice = activeTicker?.askPrice ?: spreadVal?.askPrice,
            hasVerifiedBidAsk = (activeTicker?.hasVerifiedBidAsk == true) || (spreadVal?.isBidAskAvailable == true),
            nowMs = nowMs
        )

        if (outcome.updatedState != currentBot) {
            _paperAccountState.value = outcome.updatedState
            preferences.savePersistedBotState(outcome.updatedState.toPersistedBotState())
        }

        val opened = outcome.openedPosition
        if (opened != null) {
            val cfg = preferences.settings.value
            viewModelScope.launch(Dispatchers.IO) {
                watchlistRepo.upsertBotPosition(opened.toEntity())
                if (signalId > 0L) {
                    watchlistRepo.updateSignalBotAction(
                        signalId = signalId,
                        botAction = "ENTERED (${opened.id})"
                    )
                }
            }
            if (cfg.notificationsEnabled && cfg.notifyBotEntries) {
                TerminalNotificationHelper.notifyBotEntry(
                    context = appContext,
                    symbol = opened.symbol,
                    timeframe = opened.timeframe,
                    side = opened.side,
                    setupGrade = opened.setupGrade,
                    entryPrice = opened.entryPrice,
                    notionalUsd = opened.initialNotionalUsd,
                    positionId = opened.id
                )
            }
        }

        return outcome.botActionCode
    }

    fun selectTab(tab: TerminalTab) {
        _uiState.update { it.copy(selectedTab = tab, isChartFullscreen = false) }
    }

    fun toggleChartFullscreen() {
        _uiState.update { it.copy(isChartFullscreen = !it.isChartFullscreen) }
    }

    fun setChartFullscreen(fullscreen: Boolean) {
        _uiState.update { it.copy(isChartFullscreen = fullscreen) }
    }

    fun selectSymbol(symbol: String, switchToChartTab: Boolean = true) {
        val clean = symbol.trim().uppercase(Locale.US)
        if (clean.isEmpty()) return
        preferences.setLastOpenedSymbol(clean)
        val currentTf = _uiState.value.activeTimeframe
        if (switchToChartTab) {
            _uiState.update { it.copy(selectedTab = TerminalTab.CHART) }
        }
        if (!clean.equals(_uiState.value.activeSymbol, ignoreCase = true) || _uiState.value.candles.isEmpty()) {
            loadMarketPairAndTimeframe(clean, currentTf)
        }
    }

    fun selectTimeframe(timeframe: Timeframe) {
        preferences.setLastOpenedTimeframe(timeframe)
        preferences.applyTimeframeIndicatorProfile(timeframe)
        val currentSym = _uiState.value.activeSymbol
        if (timeframe != _uiState.value.activeTimeframe || _uiState.value.candles.isEmpty()) {
            loadMarketPairAndTimeframe(currentSym, timeframe)
        }
    }

    private fun loadMarketPairAndTimeframe(symbol: String, timeframe: Timeframe) {
        val cleanSymbol = symbol.trim().uppercase(Locale.US)
        preferences.setLastOpenedSymbol(cleanSymbol)
        preferences.setLastOpenedTimeframe(timeframe)
        candleLoadJob?.cancel()
        aiAnalysisJob?.cancel()
        aiRequestGeneration++

        val allWatchlist = watchlist.value.map { it.symbol }.ifEmpty { SymbolRepository.DEFAULT_PAIRS }
        val binanceWatchlist = allWatchlist.filter { inferMarketProvider(it) == MarketProvider.BINANCE }
            .ifEmpty { SymbolRepository.DEFAULT_PAIRS }
        val biQuoteWatchlist = allWatchlist.filter { inferMarketProvider(it) == MarketProvider.BIQUOTE }
        val selectedProvider = preferences.settings.value.selectedMarketProvider
        val provider = if (selectedProvider == MarketProvider.EXNESS ||
            cleanSymbol.startsWith("EXNESS:") ||
            (ExnessCatalog.findInstrument(cleanSymbol) != null && selectedProvider == MarketProvider.EXNESS)
        ) {
            MarketProvider.EXNESS
        } else {
            inferMarketProvider(cleanSymbol)
        }

        when (provider) {
            MarketProvider.BINANCE -> {
                biQuoteRealtime.stopActiveChartSubscription()
                exnessProvider.unsubscribe()
                wsManager.subscribe(cleanSymbol, timeframe, binanceWatchlist)
            }
            MarketProvider.EXNESS -> {
                biQuoteRealtime.stopActiveChartSubscription()
                wsManager.shutdown()
                exnessProvider.subscribe(cleanSymbol, timeframe, allWatchlist)
            }
            MarketProvider.BIQUOTE -> {
                wsManager.shutdown()
                exnessProvider.unsubscribe()
                biQuoteRealtime.subscribe(cleanSymbol, timeframe, biQuoteWatchlist)
            }
        }
        candleClock.sync(cleanSymbol, timeframe)

        candleLoadJob = viewModelScope.launch {
            monitorCheckpoint = MonitorEvaluationCheckpoint(symbol = cleanSymbol, timeframe = timeframe)
            val histOverlays = buildHistoricalOverlaysForActivePair(
                entries = signalJournal.value,
                symbol = cleanSymbol,
                timeframe = timeframe
            )
            val openBotForPair = _paperAccountState.value.openPositions.firstOrNull {
                it.symbol.equals(cleanSymbol, ignoreCase = true) &&
                    it.timeframe.equals(timeframe.label, ignoreCase = true)
            }
            val restoredSetup = withContext(Dispatchers.IO) {
                val activeEntity = watchlistRepo.getLatestActiveSetupForPair(cleanSymbol, timeframe)
                activeEntity?.let { AutoSetupEngineV2.restoreActiveSetupFromJournalEntity(it, openBotForPair) }
            }
            val restoredOverlay = AutoSetupEngineV2.buildOverlayFromActiveSetup(restoredSetup, openBotForPair)

            val latestBase = signalJournal.value.firstOrNull {
                it.symbol.equals(cleanSymbol, ignoreCase = true) &&
                    com.example.chart.overlay.TradePlanOverlayMapper.timeframesMatch(it.primaryTimeframe, timeframe.label) &&
                    it.source.equals("BASELINE", ignoreCase = true)
            }
            val selOverlay = _selectedJournalSignalId.value?.let { selId ->
                signalJournal.value.firstOrNull { it.id == selId }?.let { e ->
                    if (!e.decision.equals("WAIT", ignoreCase = true) &&
                        e.symbol.equals(cleanSymbol, ignoreCase = true) &&
                        com.example.chart.overlay.TradePlanOverlayMapper.timeframesMatch(e.primaryTimeframe, timeframe.label)
                    ) {
                        SignalEngine.buildOverlayFromJournalEntity(e, isHistorical = false)
                    } else null
                }
            }

            _uiState.update {
                it.copy(
                    activeSymbol = cleanSymbol,
                    activeTimeframe = timeframe,
                    activeMarketProvider = provider,
                    isLoadingCandles = true,
                    isCurrentSignalSaved = restoredSetup != null,
                    activeSetup = restoredSetup,
                    activeSignalOverlay = restoredOverlay,
                    marketAssessment = null,
                    historicalSignalOverlays = histOverlays,
                    latestBaselineSignal = latestBase,
                    selectedJournalOverlay = selOverlay,
                    restErrorMessage = null,
                    aiErrorMessage = null,
                    aiErrorDetail = null
                )
            }
            recomputeEventRiskAndBotDirective()

            // 1. Load cached AI analysis if available for this pair + timeframe (preserving activeSetup if present)
            val cachedAi = withContext(Dispatchers.IO) {
                watchlistRepo.getCachedAiAnalysis(cleanSymbol, timeframe)
            }
            val parsedCachedAi = cachedAi?.rawJson?.let { AiResponseParser.parse(it, "AI · Primary") }
            val effectiveAiResult = restoredSetup?.sourceAiResult ?: parsedCachedAi
            _uiState.update { it.copy(aiResult = effectiveAiResult) }

            // 2. Load offline cached candles first for instant startup / offline capability
            val offlineCandles = withContext(Dispatchers.IO) {
                watchlistRepo.getOfflineCachedCandles(cleanSymbol, timeframe, provider = provider)
            }
            if (offlineCandles.isNotEmpty()) {
                applyCandlesToState(cleanSymbol, timeframe, offlineCandles, parsedCachedAi)
            }

            // 3. Fetch historical candles from the appropriate provider (Binance, Exness, or BiQuote)
            val candleResult = when (provider) {
                MarketProvider.BINANCE -> {
                    restClient.fetchHistoricalCandles(cleanSymbol, timeframe, limit = 350)
                }
                MarketProvider.EXNESS -> {
                    launch(Dispatchers.IO) {
                        when (val qRes = exnessProvider.getLatestQuote(cleanSymbol)) {
                            is MarketApiResult.Success -> {
                                val ticker = qRes.data
                                _uiState.update { state ->
                                    state.copy(
                                        liveTickers = state.liveTickers + (ticker.symbol to ticker),
                                        currentPrice = ticker.lastPrice,
                                        priceChangePercent = ticker.priceChangePercent
                                    )
                                }
                                watchlistRepo.updateTicker(ticker)
                            }
                            is MarketApiResult.Error -> {}
                        }
                    }
                    exnessProvider.getHistoricalCandles(cleanSymbol, timeframe, limit = 250)
                }
                MarketProvider.BIQUOTE -> {
                    launch(Dispatchers.IO) {
                        when (val qRes = biQuoteClient.fetchSingleSymbolQuote(cleanSymbol)) {
                            is MarketApiResult.Success -> {
                                val ticker = qRes.data
                                _uiState.update { state ->
                                    state.copy(
                                        liveTickers = state.liveTickers + (ticker.symbol to ticker),
                                        currentPrice = ticker.lastPrice,
                                        priceChangePercent = ticker.priceChangePercent
                                    )
                                }
                                watchlistRepo.updateTicker(ticker)
                            }
                            is MarketApiResult.Error -> {}
                        }
                    }
                    biQuoteClient.fetchHistoricalCandles(cleanSymbol, timeframe, limit = 250)
                }
            }

            when (candleResult) {
                is MarketApiResult.Success -> {
                    if (provider == MarketProvider.BIQUOTE) {
                        biQuoteRealtime.setLatestCandleBaseline(candleResult.data.lastOrNull())
                    }
                    applyCandlesToState(cleanSymbol, timeframe, candleResult.data, _uiState.value.aiResult)
                    _uiState.update { it.copy(isLoadingCandles = false, restErrorMessage = null) }
                    withContext(Dispatchers.IO) {
                        watchlistRepo.cacheCandles(cleanSymbol, timeframe, candleResult.data, provider = provider)
                        watchlistRepo.evaluateActiveSignalsForPair(cleanSymbol, timeframe, candleResult.data)
                    }
                }
                is MarketApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingCandles = false,
                            restErrorMessage = candleResult.message
                        )
                    }
                }
            }
        }
    }

    private suspend fun applyCandlesToState(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        currentAiResult: AiAnalysisResult? = _uiState.value.aiResult
    ) {
        candleClock.sync(symbol, timeframe, candles.lastOrNull())
        val split = confirmationEngine.splitCandles(
            candles = candles,
            currentServerTimeMs = candleClock.currentServerTimeMs()
        )

        val indParams = currentIndicatorParams()
        val indicators = withContext(Dispatchers.Default) {
            TechnicalIndicators.calculateAll(candles, indParams)
        }
        val snapshot = withContext(Dispatchers.Default) {
            val currState = _uiState.value
            val activeTicker = currState.liveTickers[symbol.uppercase(Locale.US)]
            val mktStatus = evaluateActiveMarketStatus(currState)
            val botCfg = _paperAccountState.value.config
            TechnicalSnapshotBuilder.build(
                symbol = symbol,
                timeframe = timeframe,
                candles = candles,
                indicators = indicators,
                bidPrice = activeTicker?.bidPrice,
                askPrice = activeTicker?.askPrice,
                hasVerifiedBidAsk = activeTicker?.hasVerifiedBidAsk == true,
                marketStatus = mktStatus,
                eventRiskAssessment = currState.eventRiskAssessment,
                profileCode = preferences.settings.value.tradingStyleProfile.code,
                accountEquityUsd = _paperAccountState.value.equityUsd,
                riskPerTradePercent = botCfg.riskPerTradePercent,
                maxLeverage = botCfg.leverage
            )
        }
        val lastClose = candles.lastOrNull()?.close ?: 0.0
        val firstOpen = candles.firstOrNull()?.open ?: lastClose
        val fallbackPct = if (firstOpen > 0.0) ((lastClose - firstOpen) / firstOpen) * 100.0 else 0.0

        val openBot = _paperAccountState.value.openPositions.firstOrNull {
            it.symbol.equals(symbol, ignoreCase = true) &&
                it.timeframe.equals(timeframe.label, ignoreCase = true)
        }

        val currentActiveSetup = _uiState.value.activeSetup?.takeIf {
            it.symbol.equals(symbol, ignoreCase = true) &&
                it.timeframe.equals(timeframe.label, ignoreCase = true) &&
                !it.lifecycleState.isTerminal
        }

        val activeOverlay = if (currentActiveSetup != null) {
            AutoSetupEngineV2.buildOverlayFromActiveSetup(currentActiveSetup, openBot)
        } else if (currentAiResult != null && !currentAiResult.isWait) {
            val confirmedCandle = split.latestConfirmedCandle ?: candles.lastOrNull()
            SignalEngine.buildOverlayFromAiResult(
                symbol = symbol,
                timeframe = timeframe,
                referenceCandle = confirmedCandle,
                aiResult = currentAiResult,
                atr14 = snapshot?.atr14
            )?.copy(
                botActionLabel = openBot?.let { "BOT ${it.side}" } ?: "",
                botUnrealizedPnlUsd = openBot?.unrealizedPnlUsd
            )
        } else {
            null
        }

        val initialAssessment = if (snapshot != null) {
            val nowMs = System.currentTimeMillis()
            val localCandidate = withContext(Dispatchers.Default) {
                builtInProvider.synthesizeV2MultiTimeframeAnalysis(
                    snapshot = snapshot,
                    badge = "AI · Primary",
                    modelName = "nexsync-mtf-v2"
                )
            }
            val decision = AutoSetupEngineV2.evaluateContinuousMonitoring(
                checkpoint = monitorCheckpoint,
                snapshot = snapshot,
                currentAssessment = _uiState.value.marketAssessment,
                activeSetup = currentActiveSetup,
                localCandidate = localCandidate,
                marketStatus = evaluateActiveMarketStatus(_uiState.value, nowMs),
                confirmedCandleJustClosed = false,
                minAutoGrade = preferences.settings.value.autoSignalMinGrade,
                nowMs = nowMs
            )
            monitorCheckpoint = decision.updatedCheckpoint
            decision.updatedAssessment
        } else {
            _uiState.value.marketAssessment
        }

        _uiState.update { curr ->
            val liveTicker = curr.liveTickers[symbol]
            curr.copy(
                currentPrice = liveTicker?.lastPrice ?: lastClose,
                priceChangePercent = liveTicker?.priceChangePercent ?: fallbackPct,
                candles = candles,
                indicators = indicators,
                snapshot = snapshot,
                marketAssessment = initialAssessment,
                activeSignalOverlay = activeOverlay
            )
        }
        recomputeEventRiskAndBotDirective()
    }

    private suspend fun fetchContextCandlesForTimeframe(
        symbol: String,
        tf: Timeframe?
    ): List<Candle>? {
        if (tf == null) return null
        return withContext(Dispatchers.IO) {
            val selectedProvider = preferences.settings.value.selectedMarketProvider
            val provider = if (selectedProvider == MarketProvider.EXNESS ||
                symbol.startsWith("EXNESS:") ||
                (ExnessCatalog.findInstrument(symbol) != null && selectedProvider == MarketProvider.EXNESS)
            ) MarketProvider.EXNESS else inferMarketProvider(symbol)

            val res = when (provider) {
                MarketProvider.BINANCE -> restClient.fetchHistoricalCandles(symbol, tf, limit = 220)
                MarketProvider.EXNESS -> exnessProvider.getHistoricalCandles(symbol, tf, limit = 200)
                MarketProvider.BIQUOTE -> biQuoteClient.fetchHistoricalCandles(symbol, tf, limit = 200)
            }
            when (res) {
                is MarketApiResult.Success -> {
                    watchlistRepo.cacheCandles(symbol, tf, res.data, provider = provider)
                    res.data
                }
                is MarketApiResult.Error -> {
                    watchlistRepo.getOfflineCachedCandles(symbol, tf, provider = provider).takeIf { it.isNotEmpty() }
                }
            }
        }
    }

    /**
     * Requirement #1, #2, #3, #5:
     * Evaluates setup quality using market structure, EMA, RSI, MACD, volume, S/R, ATR, and MTF confirmation.
     * Separates AI Signal (`LONG` / `SHORT` / `WAIT` + Grade `A+`/`A`/`B`/`WAIT`) from Bot Action.
     */
    fun runAiAnalysis(isAutomaticTrigger: Boolean = false) {
        val cfg = preferences.settings.value
        if (isAutomaticTrigger && !isAutoAiMonitoringActive(cfg)) {
            return
        }
        val state = _uiState.value
        if (state.isAiAnalyzing && isAutomaticTrigger) return

        if (state.candles.isEmpty()) {
            if (!isAutomaticTrigger) {
                _uiState.update {
                    it.copy(
                        aiErrorMessage = AiAnalystOutcome.UNAVAILABLE_MESSAGE,
                        aiErrorDetail = "Waiting for candle & indicator data before running analysis."
                    )
                }
            }
            return
        }

        aiAnalysisJob?.cancel()
        val requestGen = ++aiRequestGeneration
        val requestSymbol = state.activeSymbol
        val requestTimeframe = state.activeTimeframe
        val currentSystem = cfg.aiAnalysisSystem
        val currentSubmode = cfg.aiContinuousSubmode
        val currentProfile = cfg.tradingStyleProfile

        aiAnalysisJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isAiAnalyzing = true,
                    autoMonitorRuntimeState = if (isAutoAiMonitoringActive(cfg)) {
                        AiAutoMonitorRuntimeState.ANALYZING
                    } else {
                        AiAutoMonitorRuntimeState.STOPPED
                    },
                    isCurrentSignalSaved = false,
                    aiErrorMessage = null,
                    aiErrorDetail = null
                )
            }

            val serverNow = candleClock.currentServerTimeMs()

            // Use full available candles (confirmed + live market data) so live setup quality is detected immediately
            val analysisCandles = withContext(Dispatchers.Default) {
                if (state.candles.size >= 30) {
                    state.candles
                } else {
                    confirmationEngine.getConfirmedCandlesForAnalysis(state.candles, serverNow)
                }
            }

            val higherTf = if (currentSystem == AiAnalysisSystem.AI_TRADING_STYLE) {
                currentProfile.resolveHigherTimeframe(requestTimeframe)
            } else {
                requestTimeframe.higherTimeframe()
            }
            val lowerTf = requestTimeframe.lowerTimeframe()

            val higherDeferred = async { fetchContextCandlesForTimeframe(requestSymbol, higherTf) }
            val lowerDeferred = async { fetchContextCandlesForTimeframe(requestSymbol, lowerTf) }

            val rawHigherCandles = higherDeferred.await()
            val rawLowerCandles = lowerDeferred.await()

            val confirmedHigherCandles = rawHigherCandles?.let {
                confirmationEngine.getConfirmedCandlesForAnalysis(it, serverNow)
            }
            val confirmedLowerCandles = rawLowerCandles?.let {
                confirmationEngine.getConfirmedCandlesForAnalysis(it, serverNow)
            }

            val analysisIndicators = withContext(Dispatchers.Default) {
                TechnicalIndicators.calculateAll(analysisCandles)
            }

            val nowBeforeCall = System.currentTimeMillis()
            val preMarketStatus = evaluateActiveMarketStatus(_uiState.value, nowBeforeCall)
            val activeTicker = _uiState.value.liveTickers[requestSymbol.uppercase(Locale.US)]
            val currentEventRisk = _uiState.value.eventRiskAssessment
            val botCfg = _paperAccountState.value.config

            val mtfSnapshot = withContext(Dispatchers.Default) {
                TechnicalSnapshotBuilder.build(
                    symbol = requestSymbol,
                    timeframe = requestTimeframe,
                    candles = analysisCandles,
                    indicators = analysisIndicators,
                    higherTimeframeCandles = confirmedHigherCandles,
                    lowerTimeframeCandles = confirmedLowerCandles,
                    bidPrice = activeTicker?.bidPrice,
                    askPrice = activeTicker?.askPrice,
                    hasVerifiedBidAsk = activeTicker?.hasVerifiedBidAsk == true,
                    marketStatus = preMarketStatus,
                    eventRiskAssessment = currentEventRisk,
                    profileCode = currentProfile.code,
                    accountEquityUsd = _paperAccountState.value.equityUsd,
                    riskPerTradePercent = botCfg.riskPerTradePercent,
                    maxLeverage = botCfg.leverage,
                    evaluationTimeMs = nowBeforeCall
                )
            }

            // Discard outdated in-flight results if symbol, timeframe, or AI mode changed
            if (requestGen != aiRequestGeneration ||
                !_uiState.value.activeSymbol.equals(requestSymbol, ignoreCase = true) ||
                _uiState.value.activeTimeframe != requestTimeframe
            ) {
                return@launch
            }

            if (mtfSnapshot == null) {
                _uiState.update {
                    it.copy(
                        isAiAnalyzing = false,
                        aiErrorMessage = if (isAutomaticTrigger) null else AiAnalystOutcome.UNAVAILABLE_MESSAGE
                    )
                }
                recomputeEventRiskAndBotDirective()
                return@launch
            }

            val isDataValidForAnalysis = AutoSetupEngineV2.isMarketAndDataValidForNewEntry(preMarketStatus)
            val spreadPctVal = if (activeTicker?.hasVerifiedBidAsk == true &&
                activeTicker.bidPrice != null &&
                activeTicker.askPrice != null &&
                activeTicker.lastPrice > 0.0
            ) {
                ((activeTicker.askPrice - activeTicker.bidPrice).coerceAtLeast(0.0) / activeTicker.lastPrice) * 100.0
            } else {
                0.0
            }
            val currentSettings = preferences.settings.value
            val isChartVisionActive = !isAutomaticTrigger &&
                currentSettings.chartVisionMode == ChartVisionMode.AUTO

            val visionBundle = if (isChartVisionActive) {
                withContext(Dispatchers.Default) {
                    ChartVisionRenderer.renderVisionBundle(
                        symbol = requestSymbol,
                        timeframeLabel = requestTimeframe.label,
                        candles = analysisCandles,
                        snapshot = mtfSnapshot
                    )
                }
            } else {
                null
            }

            val requestContext = AiAnalysisRequestContext(
                aiSystem = currentSystem,
                continuousSubmode = currentSubmode,
                tradingStyleProfile = currentProfile,
                isAutomaticTrigger = isAutomaticTrigger,
                higherTimeframeLabel = higherTf?.label ?: requestTimeframe.higherTimeframe()?.label ?: "HTF",
                lowerTimeframeLabel = lowerTf?.label ?: "LTF",
                spreadPct = spreadPctVal,
                feeBps = botCfg.feeRateBps,
                isDataValidForEntry = isDataValidForAnalysis,
                dataStatusLabel = preMarketStatus.ledState.name,
                dataStatusReason = preMarketStatus.readableStatusText,
                riskPerTradePercent = botCfg.riskPerTradePercent,
                bidPrice = activeTicker?.bidPrice,
                askPrice = activeTicker?.askPrice,
                hasVerifiedBidAsk = activeTicker?.hasVerifiedBidAsk == true,
                eventRiskAssessment = currentEventRisk,
                marketStatus = preMarketStatus,
                accountEquityUsd = _paperAccountState.value.equityUsd,
                maxLeverage = botCfg.leverage,
                visionBundle = visionBundle,
                chartVisionSettingEnabled = currentSettings.chartVisionMode == ChartVisionMode.AUTO,
                customApiSupportsVision = currentSettings.customApiSupportsVision,
                selectedAiStrategy = cfg.selectedAiStrategy
            )

            val outcome = aiAnalystService.analyzeMarket(mtfSnapshot, requestContext)
            if (requestGen != aiRequestGeneration ||
                !_uiState.value.activeSymbol.equals(requestSymbol, ignoreCase = true) ||
                _uiState.value.activeTimeframe != requestTimeframe
            ) {
                return@launch
            }

            when (outcome) {
                is AiAnalystOutcome.Success -> {
                    val nowMs = System.currentTimeMillis()
                    val rawValidated = SignalValidationEngine.validateAndNormalize(
                        result = outcome.result,
                        snapshot = mtfSnapshot,
                        context = requestContext,
                        currentTimeMs = nowMs
                    )

                    val currentSettings = preferences.settings.value
                    val marketStatus = evaluateActiveMarketStatus(_uiState.value, nowMs)

                    // Reconcile Market Assessment vs Active Setup (WAIT never overwrites a valid ActiveSetup)
                    val reconciliation = AutoSetupEngineV2.reconcileNewAnalysis(
                        currentActive = _uiState.value.activeSetup,
                        newResult = rawValidated,
                        snapshot = mtfSnapshot,
                        marketStatus = marketStatus,
                        minAutoGrade = currentSettings.autoSignalMinGrade,
                        isAutomaticTrigger = isAutomaticTrigger,
                        nowMs = nowMs
                    )

                    recomputeEventRiskAndBotDirective(nowMs)
                    val eventRisk = _uiState.value.eventRiskAssessment

                    val initialBotDirective = if (!AutoSetupEngineV2.isMarketAndDataValidForNewEntry(marketStatus)) {
                        "BLOCKED · ${marketStatus.shortBadgeLabel}"
                    } else if (eventRisk.shouldBlockNewBotEntries) {
                        eventRisk.botActionDirective
                    } else if (_paperAccountState.value.botRunState == BotRunState.RUNNING) {
                        "MONITORING"
                    } else {
                        _paperAccountState.value.botRunState.code
                    }

                    var finalActiveSetup = reconciliation.activeSetup

                    val recordedId = withContext(Dispatchers.IO) {
                        // Persist cancelled setup transition if an opposing structure reversal replaced it
                        reconciliation.previousSetupCancelledOrReplaced?.let { cancelled ->
                            watchlistRepo.updateSignalSetupLifecycle(
                                setupId = cancelled.setupId,
                                lifecycleState = SetupLifecycleState.CANCELLED,
                                forwardStatus = SignalLifecycleStatus.CANCELLED,
                                statusReason = cancelled.statusReason
                            )
                        }

                        // Cache raw JSON only if we don't overwrite an active setup with WAIT
                        val resultToCache = finalActiveSetup?.sourceAiResult ?: rawValidated
                        watchlistRepo.saveAiAnalysisCache(
                            symbol = mtfSnapshot.symbol,
                            timeframe = mtfSnapshot.timeframe,
                            rawJson = resultToCache.rawJson
                        )

                        // Also generate and record deterministic Baseline signal (source = "BASELINE")
                        val baselineResult = BaselineSignalGenerator.generate(mtfSnapshot, serverNow)
                        if (baselineResult != null) {
                            watchlistRepo.recordSignalToJournal(
                                snapshot = mtfSnapshot,
                                result = baselineResult,
                                botAction = "NONE",
                                source = BaselineSignalGenerator.SOURCE_BASELINE,
                                lifecycleState = if (baselineResult.isWait) {
                                    SetupLifecycleState.SCANNING.code
                                } else {
                                    SetupLifecycleState.ACTIVE.code
                                },
                                statusReason = "Baseline technical reference (${baselineResult.decision})"
                            )
                        }

                        if (reconciliation.didPublishNewSetup && finalActiveSetup != null) {
                            watchlistRepo.recordSignalToJournal(
                                snapshot = mtfSnapshot,
                                result = finalActiveSetup!!.sourceAiResult,
                                botAction = initialBotDirective,
                                source = BaselineSignalGenerator.SOURCE_AI,
                                lifecycleState = finalActiveSetup!!.lifecycleState.code,
                                statusReason = finalActiveSetup!!.statusReason
                            )
                        } else if (finalActiveSetup != null && finalActiveSetup!!.hasConflict) {
                            watchlistRepo.updateSignalSetupLifecycle(
                                setupId = finalActiveSetup!!.setupId,
                                lifecycleState = finalActiveSetup!!.lifecycleState,
                                forwardStatus = finalActiveSetup!!.forwardStatus,
                                statusReason = finalActiveSetup!!.conflictNote,
                                highestTpReached = finalActiveSetup!!.highestTpReached,
                                realizedR = finalActiveSetup!!.realizedR
                            )
                            finalActiveSetup!!.setupId
                        } else {
                            finalActiveSetup?.setupId ?: 0L
                        }
                    }

                    if (reconciliation.didPublishNewSetup && finalActiveSetup != null && recordedId > 0L) {
                        finalActiveSetup = finalActiveSetup.copy(
                            setupId = recordedId,
                            botAction = initialBotDirective
                        )
                    }

                    // Send confirmed signal notification if enabled and new setup was published
                    if (reconciliation.didPublishNewSetup && finalActiveSetup != null && recordedId > 0L &&
                        currentSettings.notificationsEnabled && currentSettings.notifyNewSignals
                    ) {
                        val pubResult = finalActiveSetup.sourceAiResult
                        val tp1 = pubResult.takeProfit.firstOrNull() ?: 0.0
                        TerminalNotificationHelper.notifyConfirmedSignal(
                            context = appContext,
                            symbol = mtfSnapshot.symbol,
                            timeframe = mtfSnapshot.timeframe.label,
                            decision = pubResult.decision,
                            setupGrade = pubResult.setupQualityGrade,
                            confidence = pubResult.confidence,
                            entryRange = "${formatCryptoPrice(pubResult.entryZone.low)}-${formatCryptoPrice(pubResult.entryZone.high)}",
                            stopLoss = formatCryptoPrice(pubResult.stopLoss),
                            tp1 = formatCryptoPrice(tp1),
                            setupSignature = pubResult.setupSignature
                        )
                    }

                    // Evaluate automatic Bot Trading (Simulasi) entry if Bot is RUNNING and we have an active setup
                    val activeForBot = finalActiveSetup
                    if (activeForBot != null &&
                        !activeForBot.lifecycleState.isTerminal &&
                        _paperAccountState.value.botRunState == BotRunState.RUNNING
                    ) {
                        val botAct = tryAutoBotEntryForSignal(
                            symbol = mtfSnapshot.symbol,
                            timeframe = mtfSnapshot.timeframe.label,
                            aiResult = activeForBot.sourceAiResult,
                            livePrice = mtfSnapshot.currentPrice,
                            signalId = activeForBot.setupId,
                            nowMs = nowMs
                        )
                        val openBotNow = _paperAccountState.value.openPositions.firstOrNull {
                            it.symbol.equals(mtfSnapshot.symbol, ignoreCase = true) &&
                                it.timeframe.equals(mtfSnapshot.timeframe.label, ignoreCase = true)
                        }
                        if (openBotNow != null && activeForBot.lifecycleState != SetupLifecycleState.ENTERED) {
                            val enteredSetup = AutoSetupEngineV2.transitionSetupState(
                                setup = activeForBot,
                                targetState = SetupLifecycleState.ENTERED,
                                reason = "Simulation Bot position opened (${openBotNow.side} @ ${formatCryptoPrice(openBotNow.entryPrice)})",
                                forwardStatusOverride = SignalLifecycleStatus.ENTRY_TRIGGERED,
                                nowMs = nowMs
                            ).copy(
                                linkedBotPositionId = openBotNow.id,
                                botAction = "ENTERED (${openBotNow.id})"
                            )
                            finalActiveSetup = enteredSetup
                            withContext(Dispatchers.IO) {
                                watchlistRepo.updateSignalSetupLifecycle(
                                    setupId = enteredSetup.setupId,
                                    lifecycleState = enteredSetup.lifecycleState,
                                    forwardStatus = enteredSetup.forwardStatus,
                                    statusReason = enteredSetup.statusReason
                                )
                            }
                        } else {
                            finalActiveSetup = activeForBot.copy(botAction = botAct)
                        }
                    }

                    val openBot = _paperAccountState.value.openPositions.firstOrNull {
                        it.symbol.equals(mtfSnapshot.symbol, ignoreCase = true) &&
                            it.timeframe.equals(mtfSnapshot.timeframe.label, ignoreCase = true)
                    }

                    val overlay = AutoSetupEngineV2.buildOverlayFromActiveSetup(
                        setup = finalActiveSetup,
                        openBotPosition = openBot
                    )

                    // Primary display result:
                    // - In Manual mode (AI Continuous Manual or AI Gaya Trading), show the newly requested analysis result directly
                    //   while still preserving any live ActiveSetup in the background / activeSetup state!
                    // - In Auto mode, preserve activeSetup's AI result when an active setup is live so WAIT doesn't overwrite it.
                    val effectiveAiDisplay = if (!isAutomaticTrigger) {
                        rawValidated
                    } else {
                        finalActiveSetup?.sourceAiResult
                            ?: reconciliation.assessment.latestAiResult
                            ?: rawValidated
                    }

                    _uiState.update {
                        it.copy(
                            isAiAnalyzing = false,
                            snapshot = mtfSnapshot,
                            aiResult = effectiveAiDisplay,
                            marketAssessment = reconciliation.assessment,
                            activeSetup = finalActiveSetup,
                            activeSignalOverlay = overlay,
                            isCurrentSignalSaved = recordedId > 0L || finalActiveSetup != null,
                            activeAiProviderBadge = outcome.activeProviderBadge,
                            aiErrorMessage = null,
                            aiErrorDetail = null
                        )
                    }
                    recomputeEventRiskAndBotDirective(nowMs)
                }

                is AiAnalystOutcome.Error -> {
                    _uiState.update {
                        it.copy(
                            isAiAnalyzing = false,
                            snapshot = mtfSnapshot,
                            aiErrorMessage = if (isAutomaticTrigger) null else outcome.userMessage,
                            aiErrorDetail = if (isAutomaticTrigger) null else outcome.technicalDetail
                        )
                    }
                    recomputeEventRiskAndBotDirective()
                }
            }
        }
    }

    fun saveCurrentSignalToJournal() {
        val state = _uiState.value
        if (state.isCurrentSignalSaved) return
        val snapshot = state.snapshot ?: return
        val aiResult = state.aiResult ?: return

        viewModelScope.launch(Dispatchers.IO) {
            watchlistRepo.recordSignalToJournal(
                snapshot = snapshot,
                result = aiResult,
                botAction = _uiState.value.botActionSummary
            )
            _uiState.update { it.copy(isCurrentSignalSaved = true) }
        }
    }

    // --- Bot Trading (Simulasi) Actions (Requirement #2) ---
    fun setBotRunState(targetState: BotRunState) {
        val updated = PaperTradingEngine.setBotRunState(_paperAccountState.value, targetState)
        _paperAccountState.value = updated
        preferences.savePersistedBotState(updated.toPersistedBotState())
        recomputeEventRiskAndBotDirective()
        syncForegroundMonitoringService()

        val cfg = preferences.settings.value
        if (cfg.notificationsEnabled && cfg.notifyRiskEvents) {
            TerminalNotificationHelper.notifyRiskOrEventWarning(
                context = appContext,
                title = "Bot Trading (Simulasi) · ${targetState.displayLabel}",
                message = updated.lastBotStatusMessage,
                dedupKey = "BOT_STATE_${targetState.code}_${System.currentTimeMillis() / 30_000L}"
            )
        }
    }

    fun applyCustomStartingBalance(customBalanceUsd: Double) {
        val updated = PaperTradingEngine.updateCustomStartingBalance(
            state = _paperAccountState.value,
            customBalanceUsd = customBalanceUsd
        )
        _paperAccountState.value = updated
        preferences.savePersistedBotState(updated.toPersistedBotState())
    }

    fun updatePaperTradingConfig(config: PaperTradingConfig) {
        val current = _paperAccountState.value
        val updated = current.copy(config = config)
        _paperAccountState.value = updated
        preferences.savePersistedBotState(updated.toPersistedBotState())
        recomputeEventRiskAndBotDirective()
    }

    /**
     * LUMAVUN Chart — AI Insight V3.2: Start tracking a validated pending setup deterministically.
     */
    fun startTrackingTradePlan(plan: com.example.ai.plan.StructuredTradePlan) {
        val state = _uiState.value
        val price = if (state.currentPrice > 0.0) state.currentPrice else (state.snapshot?.currentPrice ?: plan.referencePrice)
        viewModelScope.launch(Dispatchers.IO) {
            trackedSetupRepo.startTracking(plan, price)
        }
    }

    /**
     * LUMAVUN Chart — AI Insight V3.2: Stop tracking an active pending setup.
     */
    fun stopTrackingSetup(setupId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            trackedSetupRepo.stopTracking(setupId)
        }
    }

    fun openVirtualTradeFromCurrentSignal() {
        val state = _uiState.value
        val activeSetup = state.activeSetup?.takeIf { !it.lifecycleState.isTerminal }
        val result = activeSetup?.sourceAiResult ?: state.aiResult ?: return
        if (result.isWait) return
        val price = if (state.currentPrice > 0.0) state.currentPrice else (state.snapshot?.currentPrice ?: return)

        val marketStatus = evaluateActiveMarketStatus(state)
        if (!AutoSetupEngineV2.isMarketAndDataValidForNewEntry(marketStatus)) {
            val blockedState = _paperAccountState.value.copy(
                lastBotStatusMessage = "Blocked: ${marketStatus.readableStatusText}"
            )
            _paperAccountState.value = blockedState
            recomputeEventRiskAndBotDirective()
            return
        }

        val currentBot = _paperAccountState.value
        val prevOpenIds = currentBot.openPositions.map { it.id }.toSet()
        val signalId = activeSetup?.setupId ?: state.activeSignalOverlay?.signalId ?: 0L
        val nextState = PaperTradingEngine.openPositionFromSignal(
            state = currentBot,
            symbol = state.activeSymbol,
            timeframe = state.activeTimeframe.label,
            aiResult = result,
            liveMarketPrice = price,
            signalId = signalId
        )
        _paperAccountState.value = nextState
        preferences.savePersistedBotState(nextState.toPersistedBotState())

        val newPos = nextState.openPositions.firstOrNull { it.id !in prevOpenIds }
        if (newPos != null) {
            if (activeSetup != null) {
                val enteredSetup = AutoSetupEngineV2.transitionSetupState(
                    setup = activeSetup,
                    targetState = SetupLifecycleState.ENTERED,
                    reason = "Virtual position opened (${newPos.side} @ ${formatCryptoPrice(newPos.entryPrice)})",
                    forwardStatusOverride = SignalLifecycleStatus.ENTRY_TRIGGERED
                ).copy(
                    linkedBotPositionId = newPos.id,
                    botAction = "ENTERED (${newPos.id})"
                )
                val updatedOverlay = AutoSetupEngineV2.buildOverlayFromActiveSetup(enteredSetup, newPos)
                _uiState.update {
                    it.copy(
                        activeSetup = enteredSetup,
                        activeSignalOverlay = updatedOverlay
                    )
                }
                viewModelScope.launch(Dispatchers.IO) {
                    watchlistRepo.upsertBotPosition(newPos.toEntity())
                    watchlistRepo.updateSignalSetupLifecycle(
                        setupId = enteredSetup.setupId,
                        lifecycleState = enteredSetup.lifecycleState,
                        forwardStatus = enteredSetup.forwardStatus,
                        statusReason = enteredSetup.statusReason
                    )
                }
            } else {
                viewModelScope.launch(Dispatchers.IO) {
                    watchlistRepo.upsertBotPosition(newPos.toEntity())
                }
            }
            val cfg = preferences.settings.value
            if (cfg.notificationsEnabled && cfg.notifyBotEntries) {
                TerminalNotificationHelper.notifyBotEntry(
                    context = appContext,
                    symbol = newPos.symbol,
                    timeframe = newPos.timeframe,
                    side = newPos.side,
                    setupGrade = newPos.setupGrade,
                    entryPrice = newPos.entryPrice,
                    notionalUsd = newPos.initialNotionalUsd,
                    positionId = newPos.id
                )
            }
        }
        recomputeEventRiskAndBotDirective()
    }

    fun closeVirtualTradeManually(positionId: String = "") {
        val currentBot = _paperAccountState.value
        val targetPos = if (positionId.isNotBlank()) {
            currentBot.openPositions.firstOrNull { it.id == positionId }
        } else {
            currentBot.openPositions.firstOrNull()
        } ?: return

        val livePrice = _uiState.value.liveTickers[targetPos.symbol]?.lastPrice
            ?: if (targetPos.symbol.equals(_uiState.value.activeSymbol, ignoreCase = true)) _uiState.value.currentPrice else targetPos.currentPrice

        val nextState = PaperTradingEngine.closePositionManually(
            state = currentBot,
            positionId = targetPos.id,
            exitMarketPrice = livePrice
        )
        _paperAccountState.value = nextState
        preferences.savePersistedBotState(nextState.toPersistedBotState())

        val closedEntity = nextState.closedPositions.firstOrNull { it.id == targetPos.id }?.toEntity()
        if (closedEntity != null) {
            viewModelScope.launch(Dispatchers.IO) {
                watchlistRepo.upsertBotPosition(closedEntity)
            }
        }
        recomputeEventRiskAndBotDirective()
    }

    fun resetPaperTradingAccount() {
        val resetState = PaperTradingEngine.resetAccount(_paperAccountState.value.config)
        _paperAccountState.value = resetState
        preferences.savePersistedBotState(resetState.toPersistedBotState())
        viewModelScope.launch(Dispatchers.IO) {
            watchlistRepo.clearAllBotPositions()
        }
        recomputeEventRiskAndBotDirective()
    }

    // --- Event Risk Engine Configuration (Requirement #5) ---
    fun setEventProtectionMode(mode: EventProtectionMode) {
        preferences.setEventProtectionMode(mode.code)
        recomputeEventRiskAndBotDirective()
    }

    fun setUnavailableEventPolicy(policy: UnavailableEventPolicy) {
        preferences.setUnavailableEventPolicy(policy.code)
        recomputeEventRiskAndBotDirective()
    }

    fun setEventBufferMinutes(preMinutes: Int, postMinutes: Int) {
        preferences.setEventBufferMinutes(preMinutes, postMinutes)
        recomputeEventRiskAndBotDirective()
    }

    fun setManualEventLockActive(active: Boolean) {
        preferences.setManualEventLockActive(active)
        recomputeEventRiskAndBotDirective()
    }

    fun testAiProviderConnection(providerType: AiProviderType? = null) {
        if (_uiState.value.isTestingAiConnection) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isTestingAiConnection = true,
                    aiConnectionTestStatus = "Testing connection..."
                )
            }
            val result = defaultAiService.testSelectedProviderConnection(providerType)
            _uiState.update {
                it.copy(
                    isTestingAiConnection = false,
                    aiConnectionTestStatus = result.statusText
                )
            }
        }
    }

    // --- Watchlist, Favorites & Pair Search Actions ---
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.trim().length >= 2) {
            viewModelScope.launch(Dispatchers.IO + safeHandler) {
                when (val bqSearch = biQuoteClient.searchSymbols(query.trim())) {
                    is MarketApiResult.Success -> {
                        if (bqSearch.data.isNotEmpty()) {
                            val now = System.currentTimeMillis()
                            dao.insertCachedSymbols(
                                bqSearch.data.map {
                                    com.example.storage.watchlist.CachedSymbolEntity(
                                        symbol = it.symbol,
                                        baseAsset = it.baseAsset,
                                        quoteAsset = it.quoteAsset,
                                        updatedAt = now,
                                        category = it.category.code,
                                        provider = it.provider.code,
                                        displayName = it.displayName
                                    )
                                }
                            )
                        }
                    }
                    is MarketApiResult.Error -> {}
                }
            }
        }
    }

    fun setWatchlistCategory(category: InstrumentCategory) {
        _selectedWatchlistCategory.value = category
    }

    fun dismissWatchlistFeedback() {
        _uiState.update { it.copy(watchlistFeedbackMessage = null, isWatchlistFeedbackError = false) }
    }

    fun toggleFavoriteSymbol(symbol: String) {
        preferences.toggleFavoriteSymbol(symbol)
    }

    fun addPairToWatchlist(symbol: String) {
        val rawTrimmed = symbol.trim()
        if (rawTrimmed.isEmpty()) {
            _uiState.update {
                it.copy(
                    watchlistFeedbackMessage = "Masukkan nama symbol terlebih dahulu (contoh: BTC, EURUSD, XAUUSD, AAPL).",
                    isWatchlistFeedbackError = true
                )
            }
            return
        }

        val preferredCategory = _selectedWatchlistCategory.value
        val normalizedCandidate = SymbolRepository.normalizeMultiMarketSymbolInput(rawTrimmed, preferredCategory)
        if (normalizedCandidate.isEmpty()) {
            _uiState.update {
                it.copy(
                    watchlistFeedbackMessage = "Symbol '$rawTrimmed' tidak valid.",
                    isWatchlistFeedbackError = true
                )
            }
            return
        }

        // Duplicate check before network call
        val existingWatchlist = watchlist.value
        if (existingWatchlist.any { it.symbol.equals(normalizedCandidate, ignoreCase = true) }) {
            _uiState.update {
                it.copy(
                    watchlistFeedbackMessage = "${formatSymbolPair(normalizedCandidate)} sudah ada di Watchlist.",
                    isWatchlistFeedbackError = true
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingWatchlistData = true,
                    watchlistFeedbackMessage = "Memvalidasi ${formatSymbolPair(normalizedCandidate)}...",
                    isWatchlistFeedbackError = false
                )
            }

            val validation = withContext(Dispatchers.IO) {
                symbolRepo.validateAndResolveMultiMarketSymbol(rawTrimmed, preferredCategory)
            }

            when (validation) {
                is SymbolValidationOutcome.InvalidSymbol -> {
                    _uiState.update {
                        it.copy(
                            isLoadingWatchlistData = false,
                            watchlistFeedbackMessage = validation.message,
                            isWatchlistFeedbackError = true
                        )
                    }
                }
                is SymbolValidationOutcome.ApiError -> {
                    _uiState.update {
                        it.copy(
                            isLoadingWatchlistData = false,
                            watchlistFeedbackMessage = validation.message,
                            isWatchlistFeedbackError = true
                        )
                    }
                }
                is SymbolValidationOutcome.Valid -> {
                    val info = validation.symbolInfo
                    val validSym = info.symbol
                    val existingTicker = _uiState.value.liveTickers[validSym]
                    val outcome = withContext(Dispatchers.IO) {
                        watchlistRepo.addPair(validSym, existingTicker, info)
                    }

                    when (outcome) {
                        is com.example.storage.watchlist.AddPairOutcome.Duplicate -> {
                            _uiState.update {
                                it.copy(
                                    isLoadingWatchlistData = false,
                                    watchlistFeedbackMessage = "${formatSymbolPair(validSym, info.category)} sudah ada di Watchlist.",
                                    isWatchlistFeedbackError = true
                                )
                            }
                        }
                        is com.example.storage.watchlist.AddPairOutcome.Invalid -> {
                            _uiState.update {
                                it.copy(
                                    isLoadingWatchlistData = false,
                                    watchlistFeedbackMessage = outcome.message,
                                    isWatchlistFeedbackError = true
                                )
                            }
                        }
                        is com.example.storage.watchlist.AddPairOutcome.Added -> {
                            _uiState.update {
                                it.copy(
                                    isLoadingWatchlistData = false,
                                    watchlistFeedbackMessage = "${formatSymbolPair(validSym, info.category)} (${info.category.label}) berhasil ditambahkan ke Watchlist.",
                                    isWatchlistFeedbackError = false
                                )
                            }
                            if (info.provider == MarketProvider.BINANCE) {
                                when (val res = restClient.fetchInitialTickers(listOf(validSym))) {
                                    is MarketApiResult.Success -> {
                                        val ticker = res.data.firstOrNull()
                                        if (ticker != null) {
                                            _uiState.update { state ->
                                                state.copy(liveTickers = state.liveTickers + (ticker.symbol to ticker))
                                            }
                                            watchlistRepo.updateTicker(ticker)
                                        }
                                    }
                                    is MarketApiResult.Error -> {
                                        when (val single = restClient.fetchSingleTicker(validSym)) {
                                            is MarketApiResult.Success -> {
                                                val ticker = single.data
                                                _uiState.update { state ->
                                                    state.copy(liveTickers = state.liveTickers + (ticker.symbol to ticker))
                                                }
                                                watchlistRepo.updateTicker(ticker)
                                            }
                                            is MarketApiResult.Error -> {}
                                        }
                                    }
                                }
                            } else {
                                when (val bqRes = biQuoteClient.fetchSingleSymbolQuote(validSym)) {
                                    is MarketApiResult.Success -> {
                                        val ticker = bqRes.data
                                        _uiState.update { state ->
                                            state.copy(liveTickers = state.liveTickers + (ticker.symbol to ticker))
                                        }
                                        watchlistRepo.updateTicker(ticker)
                                    }
                                    is MarketApiResult.Error -> {}
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun refreshWatchlistData() {
        if (_uiState.value.isLoadingWatchlistData) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingWatchlistData = true,
                    watchlistFeedbackMessage = null,
                    isWatchlistFeedbackError = false
                )
            }
            val savedSymbols = withContext(Dispatchers.IO) {
                val saved = dao.getWatchlistOnce().map { it.symbol }
                saved.ifEmpty { SymbolRepository.DEFAULT_PAIRS }
            }
            val binanceSymbols = savedSymbols.filter { inferMarketProvider(it) == MarketProvider.BINANCE }
            val biQuoteSymbols = savedSymbols.filter { inferMarketProvider(it) == MarketProvider.BIQUOTE }

            val collectedTickers = ArrayList<Ticker24h>()
            var lastErrorMsg: String? = null

            if (binanceSymbols.isNotEmpty()) {
                when (val res = restClient.fetchInitialTickers(binanceSymbols)) {
                    is MarketApiResult.Success -> collectedTickers.addAll(res.data)
                    is MarketApiResult.Error -> lastErrorMsg = res.message
                }
            }

            if (biQuoteSymbols.isNotEmpty()) {
                when (val bqRes = biQuoteClient.fetchLatestQuotes(biQuoteSymbols)) {
                    is MarketApiResult.Success -> collectedTickers.addAll(bqRes.data)
                    is MarketApiResult.Error -> if (lastErrorMsg == null) lastErrorMsg = bqRes.message
                }
            }

            if (collectedTickers.isNotEmpty()) {
                val map = collectedTickers.associateBy { it.symbol }
                withContext(Dispatchers.IO) {
                    watchlistRepo.updateTickersBatch(collectedTickers)
                }
                _uiState.update { state ->
                    val activeTicker = map[state.activeSymbol]
                    state.copy(
                        isLoadingWatchlistData = false,
                        liveTickers = state.liveTickers + map,
                        currentPrice = activeTicker?.lastPrice ?: state.currentPrice,
                        priceChangePercent = activeTicker?.priceChangePercent ?: state.priceChangePercent
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoadingWatchlistData = false,
                        watchlistFeedbackMessage = lastErrorMsg,
                        isWatchlistFeedbackError = lastErrorMsg != null
                    )
                }
            }
        }
    }

    fun removePairFromWatchlist(symbol: String) {
        viewModelScope.launch {
            watchlistRepo.removePair(symbol)
        }
    }

    fun moveWatchlistPairUp(symbol: String) {
        viewModelScope.launch {
            watchlistRepo.movePair(symbol, -1)
        }
    }

    fun moveWatchlistPairDown(symbol: String) {
        viewModelScope.launch {
            watchlistRepo.movePair(symbol, +1)
        }
    }

    private fun currentIndicatorParams(): ChartIndicatorParams {
        val s = preferences.settings.value
        return ChartIndicatorParams(
            ema1Period = s.ema1Period,
            ema2Period = s.ema2Period,
            ema3Period = s.ema3Period,
            sma1Period = s.sma1Period,
            sma2Period = s.sma2Period,
            bbPeriod = s.bbPeriod,
            bbStdDev = s.bbStdDev,
            rsiPeriod = s.rsiPeriod,
            macdFast = s.macdFast,
            macdSlow = s.macdSlow,
            macdSignal = s.macdSignal,
            atrPeriod = s.atrPeriod
        )
    }

    private fun recalculateChartIndicatorsAsync() {
        val currentCandles = _uiState.value.candles
        if (currentCandles.isEmpty()) return
        val params = currentIndicatorParams()
        viewModelScope.launch {
            val updated = withContext(Dispatchers.Default) {
                TechnicalIndicators.calculateAll(currentCandles, params)
            }
            _uiState.update { it.copy(indicators = updated) }
        }
    }

    // --- Settings, Notifications & Chart Overlay Actions ---
    fun toggleEma() = preferences.setShowEma(!preferences.settings.value.showEma, _uiState.value.activeTimeframe)
    fun toggleSma() = preferences.setShowSma(!preferences.settings.value.showSma, _uiState.value.activeTimeframe)
    fun toggleBollingerBands() = preferences.setShowBollingerBands(!preferences.settings.value.showBollingerBands, _uiState.value.activeTimeframe)
    fun toggleRsi() = preferences.setShowRsi(!preferences.settings.value.showRsi, _uiState.value.activeTimeframe)
    fun toggleMacd() = preferences.setShowMacd(!preferences.settings.value.showMacd, _uiState.value.activeTimeframe)
    fun toggleAtr() = preferences.setShowAtr(!preferences.settings.value.showAtr, _uiState.value.activeTimeframe)
    fun toggleVolume() = preferences.setShowVolume(!preferences.settings.value.showVolume, _uiState.value.activeTimeframe)
    fun toggleSupportResistance() = preferences.setShowSupportResistance(!preferences.settings.value.showSupportResistance, _uiState.value.activeTimeframe)
    fun toggleSmc() = preferences.setShowSmc(!preferences.settings.value.showSmc, _uiState.value.activeTimeframe)
    fun toggleIct() = preferences.setShowIct(!preferences.settings.value.showIct, _uiState.value.activeTimeframe)
    fun toggleAmd() = preferences.setShowAmd(!preferences.settings.value.showAmd, _uiState.value.activeTimeframe)
    fun toggleSignalOverlay() = preferences.setShowSignalOverlay(!preferences.settings.value.showSignalOverlay)
    fun toggleSignalHistory() = preferences.setShowSignalHistory(!preferences.settings.value.showSignalHistory)

    fun setShowEma(visible: Boolean) = preferences.setShowEma(visible, _uiState.value.activeTimeframe)
    fun setShowSma(visible: Boolean) = preferences.setShowSma(visible, _uiState.value.activeTimeframe)
    fun setShowBollingerBands(visible: Boolean) = preferences.setShowBollingerBands(visible, _uiState.value.activeTimeframe)
    fun setShowRsi(visible: Boolean) = preferences.setShowRsi(visible, _uiState.value.activeTimeframe)
    fun setShowMacd(visible: Boolean) = preferences.setShowMacd(visible, _uiState.value.activeTimeframe)
    fun setShowAtr(visible: Boolean) = preferences.setShowAtr(visible, _uiState.value.activeTimeframe)
    fun setShowVolume(visible: Boolean) = preferences.setShowVolume(visible, _uiState.value.activeTimeframe)
    fun setShowSupportResistance(visible: Boolean) = preferences.setShowSupportResistance(visible, _uiState.value.activeTimeframe)
    fun setShowSmc(visible: Boolean) = preferences.setShowSmc(visible, _uiState.value.activeTimeframe)
    fun setShowIct(visible: Boolean) = preferences.setShowIct(visible, _uiState.value.activeTimeframe)
    fun setShowAmd(visible: Boolean) = preferences.setShowAmd(visible, _uiState.value.activeTimeframe)
    fun setPerTimeframeIndicators(enabled: Boolean) = preferences.setPerTimeframeIndicators(enabled, _uiState.value.activeTimeframe)

    fun updateIndicatorParams(params: ChartIndicatorParams) {
        preferences.updateIndicatorParams(
            ema1Period = params.ema1Period,
            ema2Period = params.ema2Period,
            ema3Period = params.ema3Period,
            sma1Period = params.sma1Period,
            sma2Period = params.sma2Period,
            bbPeriod = params.bbPeriod,
            bbStdDev = params.bbStdDev,
            rsiPeriod = params.rsiPeriod,
            macdFast = params.macdFast,
            macdSlow = params.macdSlow,
            macdSignal = params.macdSignal,
            atrPeriod = params.atrPeriod
        )
        recalculateChartIndicatorsAsync()
    }

    fun setDefaultTimeframe(tf: Timeframe) {
        preferences.setDefaultTimeframe(tf)
        selectTimeframe(tf)
    }

    fun setDefaultSymbol(symbol: String) {
        preferences.setDefaultSymbol(symbol)
        selectSymbol(symbol, switchToChartTab = false)
    }

    fun setCalendarTimezone(option: CalendarTimezoneOption) {
        preferences.setCalendarTimezone(option)
    }

    fun toggleWatchedCurrency(currency: String) {
        preferences.toggleWatchedCurrency(currency)
    }

    fun setAutoAiAnalysis(enabled: Boolean) {
        aiAnalysisJob?.cancel()
        aiRequestGeneration++
        preferences.setAutoAiAnalysis(enabled)
        handleSafeModeTransition()
        syncForegroundMonitoringService()
    }

    fun setAiAnalysisSystem(system: AiAnalysisSystem) {
        if (preferences.settings.value.aiAnalysisSystem == system) return
        aiAnalysisJob?.cancel()
        aiRequestGeneration++
        preferences.setAiAnalysisSystem(system)
        handleSafeModeTransition()
        if (system == AiAnalysisSystem.AI_TRADING_STYLE) {
            val profile = preferences.settings.value.tradingStyleProfile
            if (_uiState.value.activeTimeframe !in profile.recommendedEntryTimeframes) {
                selectTimeframe(profile.defaultEntryTimeframe)
            }
        }
        syncForegroundMonitoringService()
    }

    fun setAiContinuousSubmode(submode: AiContinuousSubmode) {
        if (preferences.settings.value.aiContinuousSubmode == submode &&
            preferences.settings.value.aiAnalysisSystem == AiAnalysisSystem.AI_CONTINUOUS
        ) {
            return
        }
        aiAnalysisJob?.cancel()
        aiRequestGeneration++
        preferences.setAiAnalysisSystem(AiAnalysisSystem.AI_CONTINUOUS)
        preferences.setAiContinuousSubmode(submode)
        handleSafeModeTransition()
        syncForegroundMonitoringService()
    }

    fun selectAiStrategy(strategy: com.example.ai.strategy.AiStrategy) {
        preferences.setSelectedAiStrategy(strategy)
        _uiState.update { it.copy(selectedAiStrategy = strategy) }
    }

    fun setTradingStyleProfile(profile: TradingStyleProfile, applyDefaultTimeframe: Boolean = true) {
        aiAnalysisJob?.cancel()
        aiRequestGeneration++
        preferences.setAiAnalysisSystem(AiAnalysisSystem.AI_TRADING_STYLE)
        preferences.setTradingStyleProfile(profile)
        handleSafeModeTransition()
        if (applyDefaultTimeframe && _uiState.value.activeTimeframe != profile.defaultEntryTimeframe) {
            selectTimeframe(profile.defaultEntryTimeframe)
        }
        syncForegroundMonitoringService()
    }

    private fun handleSafeModeTransition() {
        val nowMs = System.currentTimeMillis()
        val cfg = preferences.settings.value
        val currentSetup = _uiState.value.activeSetup
        val preservedSetup = AutoSetupEngineV2.evaluateSetupCompatibilityOnModeSwitch(
            currentSetup = currentSetup,
            targetSystem = cfg.aiAnalysisSystem,
            targetSubmode = cfg.aiContinuousSubmode,
            targetProfile = if (cfg.aiAnalysisSystem == AiAnalysisSystem.AI_TRADING_STYLE) cfg.tradingStyleProfile else null,
            nowMs = nowMs
        )
        val banner = when {
            preservedSetup != null && !preservedSetup.lifecycleState.isTerminal -> {
                "Active ${preservedSetup.direction} setup (${preservedSetup.modeAttributionBadge}) preserved while in ${buildModeAttributionLabel(cfg)}."
            }
            !isAutoAiMonitoringActive(cfg) -> {
                "Auto monitoring paused — ${buildModeAttributionLabel(cfg)} analyzes on manual request."
            }
            else -> {
                "Continuous Auto monitoring active (${buildModeAttributionLabel(cfg)})."
            }
        }
        _uiState.update {
            it.copy(
                isAiAnalyzing = false,
                activeSetup = preservedSetup,
                aiAnalysisSystem = cfg.aiAnalysisSystem,
                aiContinuousSubmode = cfg.aiContinuousSubmode,
                tradingStyleProfile = cfg.tradingStyleProfile,
                activeModeAttributionLabel = buildModeAttributionLabel(cfg),
                modeSwitchStatusBanner = banner
            )
        }
        recomputeEventRiskAndBotDirective(nowMs)
    }

    fun setAutoSignalMinGrade(grade: String) = preferences.setAutoSignalMinGrade(grade)
    fun setNotificationsEnabled(enabled: Boolean) = preferences.setNotificationsEnabled(enabled)
    fun setNotifyNewSignals(enabled: Boolean) = preferences.setNotifyNewSignals(enabled)
    fun setNotifyBotEntries(enabled: Boolean) = preferences.setNotifyBotEntries(enabled)
    fun setNotifyTpSlHits(enabled: Boolean) = preferences.setNotifyTpSlHits(enabled)
    fun setNotifySignalInvalidation(enabled: Boolean) = preferences.setNotifySignalInvalidation(enabled)
    fun setNotifyRiskEvents(enabled: Boolean) = preferences.setNotifyRiskEvents(enabled)

    fun setForegroundMonitoringEnabled(enabled: Boolean) {
        preferences.setForegroundMonitoringEnabled(enabled)
        syncForegroundMonitoringService()
    }

    private fun syncForegroundMonitoringService() {
        val cfg = preferences.settings.value
        val botRunning = _paperAccountState.value.botRunState == BotRunState.RUNNING
        if (cfg.foregroundMonitoringEnabled || botRunning) {
            val state = _uiState.value
            MarketMonitorForegroundService.startOrUpdate(
                context = appContext,
                symbol = state.activeSymbol,
                timeframe = state.activeTimeframe.label,
                botStatus = _paperAccountState.value.botRunState.displayLabel,
                summary = "Auto Signal (${cfg.autoSignalMinGrade}+) & Bot (${_paperAccountState.value.botRunState.displayLabel}) · ${state.activeSymbol} ${state.activeTimeframe.label}"
            )
        } else {
            MarketMonitorForegroundService.stop(appContext)
        }
    }

    fun setDarkMode(dark: Boolean) = preferences.setDarkMode(dark)
    fun setUserName(name: String) = preferences.setUserName(name)
    fun setThemeMode(mode: String) = preferences.setThemeMode(mode)
    fun setKeepScreenOn(enabled: Boolean) = preferences.setKeepScreenOn(enabled)
    fun setLanguage(lang: String) = preferences.setLanguage(lang)

    fun setPrimaryAiProvider(mode: AiProviderMode) {
        preferences.setPrimaryAiProvider(mode)
        _uiState.update {
            it.copy(
                activeAiProviderBadge = "AI · Primary",
                aiConnectionTestStatus = null
            )
        }
    }

    fun setEnableAiFallback(enabled: Boolean) {
        preferences.setEnableAiFallback(enabled)
    }

    fun setChartVisionMode(mode: ChartVisionMode) {
        preferences.setChartVisionMode(mode)
    }

    fun setCustomApiSupportsVision(supports: Boolean) {
        preferences.setCustomApiSupportsVision(supports)
    }

    fun saveCustomApiConfig(
        endpoint: String,
        apiKey: String?,
        model: String,
        format: CustomApiFormat
    ) {
        preferences.saveCustomApiConfig(endpoint, apiKey, model, format)
        _uiState.update { it.copy(aiConnectionTestStatus = null) }
    }

    fun clearCustomApiKey() {
        preferences.clearCustomApiKey()
        _uiState.update { it.copy(aiConnectionTestStatus = null) }
    }

    fun saveGeminiCustomConfig(apiKey: String?, model: String) {
        preferences.saveGeminiCustomConfig(apiKey, model)
        _uiState.update { it.copy(aiConnectionTestStatus = null) }
    }

    fun clearGeminiCustomKey() {
        preferences.clearGeminiCustomKey()
        _uiState.update { it.copy(aiConnectionTestStatus = null) }
    }

    private fun loadCachedSyncedModels() {
        val initialMap = mutableMapOf<AiProviderType, List<AiModelInfo>>()
        for (p in AiProviderType.entries) {
            val raw = preferences.getSyncedModelsJson(p)
            if (raw.isNotBlank()) {
                try {
                    val arr = org.json.JSONArray(raw)
                    val list = mutableListOf<AiModelInfo>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        list.add(
                            AiModelInfo(
                                id = obj.getString("id"),
                                displayName = obj.optString("displayName", obj.getString("id")),
                                description = obj.optString("description", ""),
                                contextWindow = if (obj.has("contextWindow")) obj.getInt("contextWindow") else null,
                                supportsChat = if (obj.has("supportsChat")) obj.getBoolean("supportsChat") else null,
                                providerCode = obj.optString("providerCode", "")
                            )
                        )
                    }
                    if (list.isNotEmpty()) initialMap[p] = list
                } catch (_: Exception) {}
            }
        }
        _syncedModelsMap.value = initialMap
    }

    fun syncModelsForProvider(providerType: AiProviderType) {
        if (_isSyncingModels.value) return
        viewModelScope.launch {
            _isSyncingModels.value = true
            _modelSyncStatusMessage.value = "Connecting to ${providerType.displayName}..."
            val endpoint = preferences.getProviderEndpoint(providerType)
            val apiKey = preferences.getProviderApiKey(providerType)
            val result = aiModelSyncManager.syncModels(providerType, endpoint, apiKey)
            when (result) {
                is ModelSyncResult.Success -> {
                    _syncedModelsMap.update { it + (providerType to result.models) }
                    _modelSyncStatusMessage.value = "✓ Synced ${result.models.size} models from ${result.providerName}"
                    val jsonArr = org.json.JSONArray()
                    for (m in result.models) {
                        jsonArr.put(org.json.JSONObject().apply {
                            put("id", m.id)
                            put("displayName", m.displayName)
                            put("description", m.description)
                            m.contextWindow?.let { put("contextWindow", it) }
                            m.supportsChat?.let { put("supportsChat", it) }
                            put("providerCode", m.providerCode)
                        })
                    }
                    preferences.saveSyncedModelsJson(providerType, jsonArr.toString())
                }
                is ModelSyncResult.Error -> {
                    _modelSyncStatusMessage.value = "Sync Error: ${result.message}"
                }
            }
            _isSyncingModels.value = false
        }
    }

    fun selectModelForProvider(providerType: AiProviderType, modelId: String) {
        val endpoint = preferences.getProviderEndpoint(providerType)
        preferences.saveProviderConfig(providerType, endpoint, null, modelId)
        _modelSyncStatusMessage.value = "Selected model: $modelId"
    }

    fun saveProviderConfig(
        providerType: AiProviderType,
        endpoint: String,
        apiKey: String?,
        modelId: String
    ) {
        preferences.saveProviderConfig(providerType, endpoint, apiKey, modelId)
        _uiState.update { it.copy(aiConnectionTestStatus = null) }
        _modelSyncStatusMessage.value = "Saved configuration for ${providerType.displayName}"
    }

    fun clearProviderApiKey(providerType: AiProviderType) {
        preferences.clearProviderApiKey(providerType)
        _uiState.update { it.copy(aiConnectionTestStatus = null) }
    }

    fun setActiveAiProviderType(providerType: AiProviderType) {
        preferences.setActiveAiProviderType(providerType)
        _uiState.update { it.copy(aiConnectionTestStatus = null) }
        _modelSyncStatusMessage.value = null
    }

    fun setDailyTokenBudget(budget: Long) {
        preferences.setDailyTokenBudget(budget)
    }

    fun setMonthlyTokenBudget(budget: Long) {
        preferences.setMonthlyTokenBudget(budget)
    }

    fun setBudgetWarningThreshold(percent: Int) {
        preferences.setBudgetWarningThreshold(percent)
    }

    fun setBlockWhenBudgetExceeded(blocked: Boolean) {
        preferences.setBlockWhenBudgetExceeded(blocked)
    }

    fun clearAllTokenUsage() {
        viewModelScope.launch {
            aiUsageRepository.clearAllUsage()
        }
    }


    /**
     * Exness inventory is gateway/session dependent; never fallback to sample instruments.
     * Replaces previous catalog atomically so a disconnected session cannot appear tradable.
     */
    fun refreshExnessCatalog() {
        symbolRepo.replaceExnessCatalog(emptyList())
        if (!preferences.settings.value.exnessConfig.isEnabled ||
            !preferences.settings.value.exnessConfig.isConfigured
        ) return
        viewModelScope.launch(Dispatchers.IO + safeHandler) {
            when (val result = exnessProvider.getSymbols()) {
                is MarketApiResult.Success -> symbolRepo.replaceExnessCatalog(result.data)
                is MarketApiResult.Error -> symbolRepo.replaceExnessCatalog(emptyList())
            }
        }
    }

    fun openProviderSymbolOnChart(provider: MarketProvider, symbol: String) {
        preferences.setSelectedMarketProvider(provider)
        _uiState.update { it.copy(selectedTab = TerminalTab.CHART, activeMarketProvider = provider) }
        loadMarketPairAndTimeframe(symbol, _uiState.value.activeTimeframe)
    }

    /** Open persisted watchlist with its stored provider, not symbol-name inference. */
    fun openWatchlistInstrumentOnChart(saved: com.example.storage.watchlist.WatchlistEntity) {
        val provider = MarketProvider.fromCode(saved.provider)
        preferences.setSelectedMarketProvider(provider)
        _uiState.update { it.copy(selectedTab = TerminalTab.CHART, activeMarketProvider = provider) }
        loadMarketPairAndTimeframe(saved.symbol, _uiState.value.activeTimeframe)
    }

    /** Provider-specific selection, so matching symbol codes don't silently switch feeds. */
    fun openMarketInstrumentOnChart(instrument: ExchangeSymbolInfo) {
        if (!instrument.status.equals("TRADING", true)) {
            _uiState.update {
                it.copy(
                    watchlistFeedbackMessage = "${instrument.symbol} belum terverifikasi di ${instrument.provider.displayLabel}. Refresh katalog dulu.",
                    isWatchlistFeedbackError = true
                )
            }
            return
        }
        if (instrument.provider == MarketProvider.EXNESS && symbolRepo.verifiedExnessSymbol(instrument.symbol) == null) {
            _uiState.update {
                it.copy(watchlistFeedbackMessage = "Exness tidak terhubung atau simbol sudah tidak tersedia.", isWatchlistFeedbackError = true)
            }
            return
        }
        preferences.setSelectedMarketProvider(instrument.provider)
        _uiState.update { it.copy(selectedTab = TerminalTab.CHART, activeMarketProvider = instrument.provider) }
        loadMarketPairAndTimeframe(instrument.symbol, _uiState.value.activeTimeframe)
    }

    /** Validates the selected provider rather than guessing from symbol naming. */
    fun addMarketInstrumentToWatchlist(instrument: ExchangeSymbolInfo) {
        if (watchlist.value.any { it.symbol.equals(instrument.symbol, ignoreCase = true) }) {
            val existing = watchlist.value.first { it.symbol.equals(instrument.symbol, ignoreCase = true) }
            _uiState.update {
                it.copy(
                    watchlistFeedbackMessage = "${instrument.symbol} sudah tersimpan (${existing.provider}). Simbol yang sama dari dua provider belum dapat disimpan bersamaan.",
                    isWatchlistFeedbackError = true
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingWatchlistData = true) }
            val verified = withContext(Dispatchers.IO) {
                when (instrument.provider) {
                    MarketProvider.EXNESS -> symbolRepo.verifiedExnessSymbol(instrument.symbol)?.let { MarketApiResult.Success(it) }
                        ?: MarketApiResult.Error("Exness symbol not verified by current gateway")
                    MarketProvider.BINANCE -> restClient.validateUsdtTradingSymbol(instrument.symbol)
                    MarketProvider.BIQUOTE -> biQuoteClient.validateSymbol(instrument.symbol, instrument.category)
                }
            }
            when (verified) {
                is MarketApiResult.Success -> {
                    val authoritative = verified.data
                    val outcome = withContext(Dispatchers.IO) {
                        watchlistRepo.addPair(authoritative.symbol, _uiState.value.liveTickers[authoritative.symbol], authoritative)
                    }
                    _uiState.update {
                        it.copy(
                            isLoadingWatchlistData = false,
                            watchlistFeedbackMessage = when (outcome) {
                                is com.example.storage.watchlist.AddPairOutcome.Added -> "${authoritative.symbol} (${authoritative.provider.displayLabel}) ditambahkan."
                                is com.example.storage.watchlist.AddPairOutcome.Duplicate -> "${authoritative.symbol} sudah ada di Watchlist."
                                is com.example.storage.watchlist.AddPairOutcome.Invalid -> outcome.message
                            },
                            isWatchlistFeedbackError = outcome !is com.example.storage.watchlist.AddPairOutcome.Added
                        )
                    }
                }
                is MarketApiResult.Error -> _uiState.update {
                    it.copy(
                        isLoadingWatchlistData = false,
                        watchlistFeedbackMessage = "${instrument.provider.displayLabel}: ${verified.message}",
                        isWatchlistFeedbackError = true
                    )
                }
            }
        }
    }

    fun refreshExchangeSymbols() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = symbolRepo.ensureSymbolsCached(forceRefresh = true)
            _uiState.update { it.copy(cachedSymbolCount = count) }
        }
        refreshExnessCatalog()
    }

    fun forceReconnectWebSocket() {
        wsManager.forceReconnect()
        val state = _uiState.value
        if (inferMarketProvider(state.activeSymbol) == MarketProvider.BIQUOTE) {
            val biQuoteWatchlist = watchlist.value
                .filter { inferMarketProvider(it.symbol) == MarketProvider.BIQUOTE }
                .map { it.symbol }
            biQuoteRealtime.subscribe(state.activeSymbol, state.activeTimeframe, biQuoteWatchlist)
        }
        if (state.candles.isEmpty() || state.restErrorMessage != null) {
            loadMarketPairAndTimeframe(state.activeSymbol, state.activeTimeframe)
        }
    }

    // --- Lifecycle Hooks ---
    fun onAppForeground() {
        wsManager.onAppForeground()
        val state = _uiState.value
        candleClock.sync(state.activeSymbol, state.activeTimeframe, state.candles.lastOrNull())
        candleClock.start(viewModelScope)
    }

    fun onAppBackground() {
        val cfg = preferences.settings.value
        val botRunning = _paperAccountState.value.botRunState == BotRunState.RUNNING
        if (!cfg.foregroundMonitoringEnabled && !botRunning) {
            wsManager.onAppBackground()
            candleClock.stop()
        }
    }

    fun selectMarketProvider(provider: MarketProvider) {
        preferences.setSelectedMarketProvider(provider)
        val currentSymbol = _uiState.value.activeSymbol
        val resolvedSymbol = when (provider) {
            MarketProvider.EXNESS -> {
                if (currentSymbol.endsWith("USDT")) "BTCUSD"
                else if (ExnessCatalog.findInstrument(currentSymbol) != null) currentSymbol
                else "EURUSD"
            }
            MarketProvider.BINANCE -> {
                if (currentSymbol in setOf("EURUSD", "GBPUSD", "USDJPY", "XAUUSD", "US30", "BTCUSD", "ETHUSD")) "BTCUSDT"
                else if (currentSymbol.endsWith("USDT")) currentSymbol
                else "${currentSymbol.removeSuffix("USD")}USDT"
            }
            MarketProvider.BIQUOTE -> currentSymbol
        }
        _uiState.update { it.copy(activeMarketProvider = provider) }
        if (provider == MarketProvider.EXNESS) refreshExnessCatalog()
        loadMarketPairAndTimeframe(resolvedSymbol, _uiState.value.activeTimeframe)
    }

    fun saveExnessConfig(config: ExnessConfig) {
        preferences.saveExnessConfig(config)
        exnessProvider.updateConfig(config)
        refreshExnessCatalog()
        if (_uiState.value.activeMarketProvider == MarketProvider.EXNESS) {
            loadMarketPairAndTimeframe(_uiState.value.activeSymbol, _uiState.value.activeTimeframe)
        }
    }

    override fun onCleared() {
        super.onCleared()
        candleClock.stop()
        wsManager.shutdown()
    }
}
