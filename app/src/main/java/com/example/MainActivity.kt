package com.example

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.analysis.events.EventProtectionMode
import com.example.analysis.events.MacroCalendarEvent
import com.example.analysis.events.UnavailableEventPolicy
import com.example.analysis.signal.ChartSignalOverlay
import com.example.market.candle.MarketProvider
import com.example.market.candle.inferInstrumentCategory
import com.example.market.candle.inferMarketProvider
import com.example.market.websocket.WsConnectionStatus
import com.example.ui.TerminalTab
import com.example.ui.TerminalViewModel
import com.example.ui.aiPanel.FullAiAnalystScreen
import com.example.ui.chart.ChartScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BullishGreen
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.NeutralAmber
import com.example.ui.theme.NexSyncTheme
import com.example.ui.watchlist.EconomicCalendarScreen
import com.example.ui.watchlist.MarketsScreen

class MainActivity : ComponentActivity() {
    private val terminalViewModel: TerminalViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. Official Android SplashScreen API configuration (Android 12+ and backward compatible)
        installSplashScreen()
        CrashLogger.install(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialCrashLog = CrashLogger.readCrashLog(this)
        setContent {
            var pendingCrashLog by remember { mutableStateOf(initialCrashLog) }
            val settings by terminalViewModel.settings.collectAsStateWithLifecycle()
            // 2. Cinematic full-screen portrait opening display (only on initial cold start)
            var showCinematicSplash by rememberSaveable { mutableStateOf(savedInstanceState == null) }

            NexSyncTheme(darkTheme = settings.darkMode) {
                Box(modifier = Modifier.fillMaxSize()) {
                    NexSyncTerminalApp(viewModel = terminalViewModel)

                    // Cinematic portrait splash screen with smooth, subtle fade-out transition
                    AnimatedVisibility(
                        visible = showCinematicSplash,
                        enter = fadeIn(animationSpec = tween(200)),
                        exit = fadeOut(animationSpec = tween(500))
                    ) {
                        LumavunCinematicSplashScreen(
                            onDismiss = { showCinematicSplash = false }
                        )
                    }
                }
                pendingCrashLog?.let { logText ->
                    CrashLogDialog(
                        crashLog = logText,
                        onCopy = {
                            CrashLogger.copyToClipboard(this@MainActivity, logText)
                        },
                        onDismiss = {
                            CrashLogger.clearCrashLog(this@MainActivity)
                            pendingCrashLog = null
                        }
                    )
                }
            }
        }
    }
}

/**
 * Cinematic full-screen portrait splash screen showing the LUMAVUN celestial artwork
 * with crescent moon, shining golden star, purple mist, wordmark, and tagline: "Imagine Beyond the Mist."
 */
@Composable
fun LumavunCinematicSplashScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Keep startup responsive: brief 1.8s branding display while background engines initialize,
    // or immediate dismissal upon tapping anywhere on screen.
    LaunchedEffect(Unit) {
        delay(1800L)
        onDismiss()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF030921))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() }
            .testTag("lumavun_splash_screen"),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_lumavun_splash),
            contentDescription = "LUMAVUN - Imagine Beyond the Mist.",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
fun CrashLogDialog(
    crashLog: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("crash_log_dialog"),
        title = {
            Text(
                text = "Log Crash Terakhir",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = crashLog,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("crash_log_text")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onCopy,
                modifier = Modifier.testTag("crash_log_copy_button")
            ) {
                Text("Salin")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("crash_log_dismiss_button")
            ) {
                Text("Tutup")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexSyncTerminalApp(viewModel: TerminalViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val biQuoteConnectionStatus by viewModel.biQuoteConnectionStatus.collectAsStateWithLifecycle()
    val reconnectingBanner by viewModel.reconnectingBanner.collectAsStateWithLifecycle()
    val watchlist by viewModel.watchlist.collectAsStateWithLifecycle()
    val signalJournal by viewModel.signalJournal.collectAsStateWithLifecycle()
    val paperAccountState by viewModel.paperAccountState.collectAsStateWithLifecycle()
    val calendarState by viewModel.calendarState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val selectedWatchlistCategory by viewModel.selectedWatchlistCategory.collectAsStateWithLifecycle()

    val aiUsageDashboard by viewModel.aiUsageDashboardData.collectAsStateWithLifecycle()
    val recentAiUsageRecords by viewModel.recentAiUsageRecords.collectAsStateWithLifecycle()
    val syncedModelsMap by viewModel.syncedModelsMap.collectAsStateWithLifecycle()
    val isSyncingModels by viewModel.isSyncingModels.collectAsStateWithLifecycle()
    val modelSyncStatusMessage by viewModel.modelSyncStatusMessage.collectAsStateWithLifecycle()
    val trackedSetups by viewModel.trackedSetups.collectAsStateWithLifecycle()
    val tradePlanOverlay by viewModel.tradePlanOverlay.collectAsStateWithLifecycle()

    var pendingSettingsSubPage by rememberSaveable { mutableStateOf<String?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(settings.notificationsEnabled, settings.foregroundMonitoringEnabled) {
        if ((settings.notificationsEnabled || settings.foregroundMonitoringEnabled) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted && context is Activity) {
                runCatching {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onAppForeground()
                Lifecycle.Event.ON_STOP -> viewModel.onAppBackground()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Keep Screen On support (TradingView UX)
    DisposableEffect(settings.keepScreenOn) {
        val activity = context as? Activity
        if (settings.keepScreenOn) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Automatically support landscape orientation in Fullscreen Chart Mode
    DisposableEffect(uiState.isChartFullscreen) {
        val activity = context as? Activity
        if (uiState.isChartFullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose {}
    }

    var showNotificationsSheet by rememberSaveable { mutableStateOf(false) }
    var showProfileQuickSettingsSheet by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showNotificationsSheet || showProfileQuickSettingsSheet || uiState.isChartFullscreen || uiState.selectedTab != TerminalTab.CHART) {
        if (showNotificationsSheet) {
            showNotificationsSheet = false
        } else if (showProfileQuickSettingsSheet) {
            showProfileQuickSettingsSheet = false
        } else if (uiState.isChartFullscreen) {
            viewModel.setChartFullscreen(false)
        } else {
            viewModel.selectTab(TerminalTab.CHART)
        }
    }

    val hideChromeForFullscreen = uiState.selectedTab == TerminalTab.CHART && uiState.isChartFullscreen

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (!hideChromeForFullscreen) {
                GlobalHeaderBar(
                    currentTab = uiState.selectedTab,
                    onBackClick = if (uiState.selectedTab != TerminalTab.CHART) {
                        { viewModel.selectTab(TerminalTab.CHART) }
                    } else null,
                    onGearClick = {
                        showProfileQuickSettingsSheet = true
                    },
                    onNotificationClick = {
                        showNotificationsSheet = true
                    }
                )
            }
        },
        bottomBar = {
            if (!hideChromeForFullscreen) {
                TerminalBottomNavigationBar(
                    selectedTab = uiState.selectedTab,
                    onSelectTab = { tab ->
                        if (tab != TerminalTab.PROFILE) {
                            pendingSettingsSubPage = null
                        }
                        viewModel.selectTab(tab)
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            when (uiState.selectedTab) {
                    TerminalTab.WATCHLIST -> {
                        MarketsScreen(
                            activeSymbol = uiState.activeSymbol,
                            watchlist = watchlist,
                            liveTickers = uiState.liveTickers,
                            favoriteSymbols = settings.favoriteSymbols,
                            searchQuery = searchQuery,
                            searchResults = searchResults,
                            selectedCategory = selectedWatchlistCategory,
                            onSelectCategory = viewModel::setWatchlistCategory,
                            calendarState = calendarState,
                            eventRiskAssessment = uiState.eventRiskAssessment,
                            isLoadingWatchlistData = uiState.isLoadingWatchlistData,
                            feedbackMessage = uiState.watchlistFeedbackMessage,
                            isFeedbackError = uiState.isWatchlistFeedbackError,
                            onDismissFeedback = viewModel::dismissWatchlistFeedback,
                            onRefreshWatchlist = viewModel::refreshWatchlistData,
                            onSearchQueryChange = viewModel::setSearchQuery,
                            onSelectSymbolForChart = { sym -> viewModel.selectSymbol(sym, switchToChartTab = true) },
                            onToggleFavorite = viewModel::toggleFavoriteSymbol,
                            onAddPair = viewModel::addPairToWatchlist,
                            onRemovePair = viewModel::removePairFromWatchlist,
                            onMovePairUp = viewModel::moveWatchlistPairUp,
                            onMovePairDown = viewModel::moveWatchlistPairDown,
                            onRefreshCalendar = { viewModel.refreshEconomicCalendar(initialLoad = false) },
                            onSelectMarketInstrumentForChart = viewModel::openMarketInstrumentOnChart,
                            onSelectProviderSymbolForChart = viewModel::openProviderSymbolOnChart,
                            onAddMarketInstrument = viewModel::addMarketInstrumentToWatchlist,
                            onSelectWatchlistInstrumentForChart = viewModel::openWatchlistInstrumentOnChart,
                            onRefreshMarketSymbols = viewModel::refreshExchangeSymbols,
                            binanceConnectionStatus = connectionStatus,
                            biQuoteConnectionStatus = biQuoteConnectionStatus,
                            exnessConnectionStatus = uiState.exnessConnectionStatus
                        )
                    }

                    TerminalTab.CHART -> {
                        val activeTicker = uiState.liveTickers[uiState.activeSymbol]
                        val activeProvider = activeTicker?.provider ?: inferMarketProvider(
                            uiState.activeSymbol,
                            activeTicker?.category ?: inferInstrumentCategory(uiState.activeSymbol)
                        )
                        val activeSymbolConnectionStatus = when (activeProvider) {
                            MarketProvider.BIQUOTE -> biQuoteConnectionStatus
                            MarketProvider.BINANCE -> connectionStatus
                            MarketProvider.EXNESS -> when (uiState.exnessConnectionStatus) {
                                is com.example.market.provider.ProviderConnectionStatus.Connected -> com.example.market.websocket.WsConnectionStatus.LIVE
                                is com.example.market.provider.ProviderConnectionStatus.Connecting -> com.example.market.websocket.WsConnectionStatus.RECONNECTING
                                is com.example.market.provider.ProviderConnectionStatus.Stale -> com.example.market.websocket.WsConnectionStatus.RECONNECTING
                                is com.example.market.provider.ProviderConnectionStatus.Disconnected,
                                is com.example.market.provider.ProviderConnectionStatus.Unconfigured,
                                is com.example.market.provider.ProviderConnectionStatus.Error -> com.example.market.websocket.WsConnectionStatus.DISCONNECTED
                            }
                        }
                        ChartScreen(
                            activeSymbol = uiState.activeSymbol,
                            activeTimeframe = uiState.activeTimeframe,
                            currentPrice = uiState.currentPrice,
                            priceChangePercent = uiState.priceChangePercent,
                            activeTicker = activeTicker,
                            candles = uiState.candles,
                            indicators = uiState.indicators,
                            snapshot = uiState.snapshot,
                            settings = settings,
                            connectionStatus = activeSymbolConnectionStatus,
                            reconnectingBannerMessage = reconnectingBanner,
                            restErrorMessage = uiState.restErrorMessage,
                            aiResult = uiState.aiResult,
                            isAiAnalyzing = uiState.isAiAnalyzing,
                            aiErrorMessage = uiState.aiErrorMessage,
                            activeAiProviderBadge = uiState.activeAiProviderBadge,
                            isFullscreen = uiState.isChartFullscreen,
                            onToggleFullscreen = viewModel::toggleChartFullscreen,
                            onSelectTimeframe = viewModel::selectTimeframe,
                            onToggleEma = viewModel::toggleEma,
                            onToggleVolume = viewModel::toggleVolume,
                            onToggleRsi = viewModel::toggleRsi,
                            onToggleMacd = viewModel::toggleMacd,
                            onToggleSupportResistance = viewModel::toggleSupportResistance,
                            onToggleSmc = viewModel::toggleSmc,
                            onToggleIct = viewModel::toggleIct,
                            onToggleAmd = viewModel::toggleAmd,
                            onOpenFullAiTab = {
                                if (uiState.aiResult == null && !uiState.isAiAnalyzing && viewModel.isAutoAiMonitoringActive(settings)) {
                                    viewModel.runAiAnalysis(isAutomaticTrigger = true)
                                }
                                viewModel.selectTab(TerminalTab.AI)
                            },
                            onRunAiAnalysis = { viewModel.runAiAnalysis(isAutomaticTrigger = false) },
                            onOpenMarketsSearch = { viewModel.selectTab(TerminalTab.WATCHLIST) },
                            onForceReconnect = viewModel::forceReconnectWebSocket,
                            countdownFlow = viewModel.candleCountdown,
                            activeSignalOverlay = uiState.activeSignalOverlay,
                            historicalSignalOverlays = uiState.historicalSignalOverlays,
                            selectedJournalOverlay = uiState.selectedJournalOverlay,
                            onClearSelectedJournalOverlay = viewModel::clearSelectedJournalSignal,
                            latestBaselineSignal = uiState.latestBaselineSignal,
                            onToggleSignalOverlay = viewModel::toggleSignalOverlay,
                            onToggleSignalHistory = viewModel::toggleSignalHistory,
                            onToggleSma = viewModel::toggleSma,
                            onToggleBollingerBands = viewModel::toggleBollingerBands,
                            onToggleAtr = viewModel::toggleAtr,
                            onSetPerTimeframeIndicators = viewModel::setPerTimeframeIndicators,
                            onUpdateIndicatorParams = viewModel::updateIndicatorParams,
                            botActionSummary = uiState.botActionSummary,
                            marketAssessment = uiState.marketAssessment,
                            activeSetup = uiState.activeSetup,
                            onSelectAiStrategy = viewModel::selectAiStrategy,
                            trackedSetups = trackedSetups,
                            onStartTracking = viewModel::startTrackingTradePlan,
                            onStopTracking = viewModel::stopTrackingSetup,
                            tradePlanOverlay = tradePlanOverlay,
                            onToggleTradePlanOverlay = viewModel::toggleTradePlanOverlay,
                            onSelectTrackedSetup = viewModel::selectOverlaySetup
                        )
                    }

                    TerminalTab.AI -> {
                        FullAiAnalystScreen(
                            symbol = uiState.activeSymbol,
                            timeframe = uiState.activeTimeframe,
                            snapshot = uiState.snapshot,
                            aiResult = uiState.aiResult,
                            activeSignalOverlay = uiState.activeSignalOverlay,
                            marketAssessment = uiState.marketAssessment,
                            activeSetup = uiState.activeSetup,
                            isAnalyzing = uiState.isAiAnalyzing,
                            autoAiAnalysis = settings.autoAiAnalysis,
                            aiAnalysisSystem = uiState.aiAnalysisSystem,
                            aiContinuousSubmode = uiState.aiContinuousSubmode,
                            tradingStyleProfile = uiState.tradingStyleProfile,
                            autoMonitorRuntimeState = uiState.autoMonitorRuntimeState,
                            activeModeAttributionLabel = uiState.activeModeAttributionLabel,
                            modeSwitchStatusBanner = uiState.modeSwitchStatusBanner,
                            errorMessage = uiState.aiErrorMessage,
                            providerBadge = uiState.activeAiProviderBadge,
                            signalJournal = signalJournal,
                            isCurrentSignalSaved = uiState.isCurrentSignalSaved,
                            paperAccountState = paperAccountState,
                            botActionSummary = uiState.botActionSummary,
                            eventRiskAssessment = uiState.eventRiskAssessment,
                            upcomingEvents = uiState.upcomingMacroEvents,
                            eventProtectionMode = EventProtectionMode.fromCode(settings.eventProtectionMode),
                            unavailableEventPolicy = UnavailableEventPolicy.fromCode(settings.unavailableEventPolicy),
                            eventPreBufferMinutes = settings.eventPreBufferMinutes,
                            eventPostBufferMinutes = settings.eventPostBufferMinutes,
                            manualEventLockActive = settings.manualEventLockActive,
                            onToggleAutoAi = viewModel::setAutoAiAnalysis,
                            onSelectAiAnalysisSystem = viewModel::setAiAnalysisSystem,
                            onSelectAiContinuousSubmode = viewModel::setAiContinuousSubmode,
                            onSelectTradingStyleProfile = { profile -> viewModel.setTradingStyleProfile(profile, applyDefaultTimeframe = true) },
                            onSelectTimeframe = viewModel::selectTimeframe,
                            onAnalyzeClick = { viewModel.runAiAnalysis(isAutomaticTrigger = false) },
                            onSaveSignalToJournal = viewModel::saveCurrentSignalToJournal,
                            onSetBotRunState = viewModel::setBotRunState,
                            onApplyCustomStartingBalance = viewModel::applyCustomStartingBalance,
                            onUpdatePaperConfig = viewModel::updatePaperTradingConfig,
                            onOpenVirtualTradeFromCurrentSignal = viewModel::openVirtualTradeFromCurrentSignal,
                            onCloseVirtualTradeManually = viewModel::closeVirtualTradeManually,
                            onResetPaperAccount = viewModel::resetPaperTradingAccount,
                            onSetEventProtectionMode = viewModel::setEventProtectionMode,
                            onSetUnavailableEventPolicy = viewModel::setUnavailableEventPolicy,
                            onSetEventBufferMinutes = viewModel::setEventBufferMinutes,
                            onToggleManualEventLock = viewModel::setManualEventLockActive,
                            onSelectJournalSignal = viewModel::selectJournalSignalAndNavigateToChart,
                            onOpenAiConfig = {
                                pendingSettingsSubPage = com.example.ui.settings.SettingsSubPage.AI_CONFIG.name
                                viewModel.selectTab(TerminalTab.PROFILE)
                            }
                        )
                    }

                    TerminalTab.CALENDAR -> {
                        EconomicCalendarScreen(
                            activeSymbol = uiState.activeSymbol,
                            calendarState = calendarState,
                            eventRiskAssessment = uiState.eventRiskAssessment,
                            onRefreshCalendar = { viewModel.refreshEconomicCalendar(initialLoad = false) },
                            calendarTimezoneOption = settings.calendarTimezone,
                            watchedCurrencies = settings.watchedCurrencies
                        )
                    }

                    TerminalTab.PROFILE -> {
                        SettingsScreen(
                            settings = settings,
                            connectionStatus = connectionStatus,
                            cachedSymbolCount = uiState.cachedSymbolCount,
                            isTestingAiConnection = uiState.isTestingAiConnection,
                            aiConnectionTestStatus = uiState.aiConnectionTestStatus,
                            aiUsageDashboard = aiUsageDashboard,
                            recentAiUsageRecords = recentAiUsageRecords,
                            syncedModelsMap = syncedModelsMap,
                            isSyncingModels = isSyncingModels,
                            modelSyncStatusMessage = modelSyncStatusMessage,
                            onSyncModels = viewModel::syncModelsForProvider,
                            onSelectModel = viewModel::selectModelForProvider,
                            onSaveProviderConfig = viewModel::saveProviderConfig,
                            onClearProviderApiKey = viewModel::clearProviderApiKey,
                            onSetActiveAiProviderType = viewModel::setActiveAiProviderType,
                            onSetDailyTokenBudget = viewModel::setDailyTokenBudget,
                            onSetMonthlyTokenBudget = viewModel::setMonthlyTokenBudget,
                            onSetBudgetWarningThreshold = viewModel::setBudgetWarningThreshold,
                            onSetBlockWhenBudgetExceeded = viewModel::setBlockWhenBudgetExceeded,
                            onClearAllTokenUsage = viewModel::clearAllTokenUsage,
                            initialSubPage = pendingSettingsSubPage,
                            onSetDefaultTimeframe = viewModel::setDefaultTimeframe,
                            onSetDefaultSymbol = viewModel::setDefaultSymbol,
                            onSetAutoAiAnalysis = viewModel::setAutoAiAnalysis,
                            onSetAutoSignalMinGrade = viewModel::setAutoSignalMinGrade,
                            onSetNotificationsEnabled = viewModel::setNotificationsEnabled,
                            onSetNotifyNewSignals = viewModel::setNotifyNewSignals,
                            onSetNotifyBotEntries = viewModel::setNotifyBotEntries,
                            onSetNotifyTpSlHits = viewModel::setNotifyTpSlHits,
                            onSetNotifySignalInvalidation = viewModel::setNotifySignalInvalidation,
                            onSetNotifyRiskEvents = viewModel::setNotifyRiskEvents,
                            onSetForegroundMonitoringEnabled = viewModel::setForegroundMonitoringEnabled,
                            onSetPrimaryAiProvider = viewModel::setPrimaryAiProvider,
                            onSetEnableAiFallback = viewModel::setEnableAiFallback,
                            onSetChartVisionMode = viewModel::setChartVisionMode,
                            onSetCustomApiSupportsVision = viewModel::setCustomApiSupportsVision,
                            onSelectAiStrategy = viewModel::selectAiStrategy,
                            onSaveCustomApiConfig = viewModel::saveCustomApiConfig,
                            onClearCustomApiKey = viewModel::clearCustomApiKey,
                            onSaveGeminiCustomConfig = viewModel::saveGeminiCustomConfig,
                            onClearGeminiCustomKey = viewModel::clearGeminiCustomKey,
                            onTestAiConnection = viewModel::testAiProviderConnection,
                            onSetShowEma = viewModel::setShowEma,
                            onSetShowRsi = viewModel::setShowRsi,
                            onSetShowMacd = viewModel::setShowMacd,
                            onSetShowVolume = viewModel::setShowVolume,
                            onSetShowSupportResistance = viewModel::setShowSupportResistance,
                            onSetShowSma = viewModel::setShowSma,
                            onSetShowBollingerBands = viewModel::setShowBollingerBands,
                            onSetShowAtr = viewModel::setShowAtr,
                            onSetPerTimeframeIndicators = viewModel::setPerTimeframeIndicators,
                            simulationStartingBalanceUsdt = paperAccountState.config.startingBalanceUsd,
                            simulationEquityUsdt = paperAccountState.equityUsd,
                            simulationRiskPerTradePercent = paperAccountState.config.riskPerTradePercent,
                            simulationMaxDailyLossPercent = paperAccountState.config.dailyLossLimitPct,
                            onApplySimulationStartingBalance = { amount, _ ->
                                viewModel.applyCustomStartingBalance(amount)
                            },
                            onUpdateSimulationRiskConfig = { riskPct, maxDailyLossPct ->
                                viewModel.updatePaperTradingConfig(
                                    paperAccountState.config.copy(
                                        riskPerTradePercent = riskPct,
                                        dailyLossLimitPct = maxDailyLossPct
                                    )
                                )
                            },
                            onSetDarkMode = viewModel::setDarkMode,
                            onForceReconnect = viewModel::forceReconnectWebSocket,
                            onRefreshExchangeSymbols = viewModel::refreshExchangeSymbols,
                            onSetCalendarTimezone = viewModel::setCalendarTimezone,
                            onToggleWatchedCurrency = viewModel::toggleWatchedCurrency,
                            onSetUserName = viewModel::setUserName,
                            onSetThemeMode = viewModel::setThemeMode,
                            onSetKeepScreenOn = viewModel::setKeepScreenOn,
                            onSetLanguage = viewModel::setLanguage,
                            onSelectMarketProvider = viewModel::selectMarketProvider,
                            onSaveExnessConfig = viewModel::saveExnessConfig,
                            exnessConnectionStatus = uiState.exnessConnectionStatus
                        )
                    }
                }

                if (showNotificationsSheet) {
                    NotificationsBottomSheet(
                        notificationsEnabled = settings.notificationsEnabled,
                        onToggleNotifications = viewModel::setNotificationsEnabled,
                        activeSignalOverlay = uiState.activeSignalOverlay,
                        historicalSignals = uiState.historicalSignalOverlays,
                        upcomingEvents = uiState.upcomingMacroEvents,
                        onDismiss = { showNotificationsSheet = false }
                    )
                }

                if (showProfileQuickSettingsSheet) {
                    ProfileQuickSettingsBottomSheet(
                        keepScreenOn = settings.keepScreenOn,
                        onToggleKeepScreenOn = viewModel::setKeepScreenOn,
                        notificationsEnabled = settings.notificationsEnabled,
                        onToggleNotifications = viewModel::setNotificationsEnabled,
                        themeMode = settings.themeMode,
                        onSetThemeMode = viewModel::setThemeMode,
                        language = settings.language,
                        onSetLanguage = viewModel::setLanguage,
                        onDismiss = { showProfileQuickSettingsSheet = false }
                    )
                }
            }
        }
    }

/**
 * Global Header Bar matching Requirement BAGIAN 2:
 * 1. Hapus teks "NexSyncAI Lite", badge "AI · Primary", dan badge "LIVE" dari header.
 * 2. Tengah header: tampilkan logo aplikasi saja (ikon APK bulat atau logo kecil, ukuran ~24-28dp) dengan warna aksen cyan.
 * 3. Kiri header: kosongkan atau beri tombol back jika di halaman settings/subpage.
 * 4. Kanan header:
 *    - Di halaman Profil: tampilkan ikon gear (Settings) yang membuka pengaturan inline atau bottom sheet settings.
 *    - Di halaman lain (Daftar Pantau, Chart, AI, Kalender): tampilkan ikon lonceng (Notifications) yang membuka daftar notifikasi.
 */
@Composable
fun GlobalHeaderBar(
    currentTab: TerminalTab = TerminalTab.CHART,
    onBackClick: (() -> Unit)? = null,
    onGearClick: () -> Unit = {},
    onNotificationClick: () -> Unit = {},
    // Legacy parameter overloads for backward compatibility
    connectionStatus: WsConnectionStatus = WsConnectionStatus.LIVE,
    activeProviderBadge: String = "",
    onReconnectClick: () -> Unit = {},
    onSettingsClick: () -> Unit = onGearClick,
    isSettingsOpen: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF000000))
            .statusBarsPadding()
            .testTag("global_header_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Kiri header: kosongkan atau beri tombol back jika di halaman settings/subpage
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (onBackClick != null) {
                    val backTag = if (currentTab == TerminalTab.PROFILE) "settings_back_button" else "header_back_button"
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag(backTag)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Kembali ke Terminal",
                            tint = CyanAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Tengah header: tampilkan logo aplikasi saja (ikon APK bulat atau logo kecil, ukuran ~24dp)
            Surface(
                color = CyanAccent.copy(alpha = 0.14f),
                shape = CircleShape,
                border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f)),
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .testTag("header_app_logo")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = "LUMAVUN Logo",
                        tint = CyanAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Kanan header:
            // - Di halaman Profil: tampilkan ikon gear (Settings)
            // - Di halaman lain (Daftar Pantau, Chart, AI, Kalender): tampilkan ikon lonceng (Notifications)
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (currentTab == TerminalTab.PROFILE || isSettingsOpen) {
                    IconButton(
                        onClick = {
                            onGearClick()
                            onSettingsClick()
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("global_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Pengaturan Profil",
                            tint = CyanAccent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = onNotificationClick,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("global_notification_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = "Notifikasi",
                            tint = Color(0xFFE2E8F0),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = Color(0xFF1E2430), thickness = 1.dp)
    }
}

@Composable
private fun TerminalTopHeader(
    connectionStatus: WsConnectionStatus = WsConnectionStatus.LIVE,
    activeProviderBadge: String = "",
    onReconnectClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    isProfileSelected: Boolean = false
) {
    GlobalHeaderBar(
        currentTab = if (isProfileSelected) TerminalTab.PROFILE else TerminalTab.CHART,
        onGearClick = onProfileClick,
        isSettingsOpen = isProfileSelected
    )
}

/**
 * 5 Tab Bottom Navigation Bar:
 * 1. Daftar Pantau (Bookmark / BookmarkBorder)
 * 2. Chart (ShowChart / TrendingUp)
 * 3. AI (SmartToy / AutoAwesome)
 * 4. Kalender (CalendarMonth / CalendarToday)
 * 5. Profil (Person / PersonOutline)
 */
private val tradingViewBottomNavTabs = listOf(
    TerminalTab.WATCHLIST,
    TerminalTab.CHART,
    TerminalTab.AI,
    TerminalTab.CALENDAR,
    TerminalTab.PROFILE
)

@Composable
private fun TerminalBottomNavigationBar(
    selectedTab: TerminalTab,
    onSelectTab: (TerminalTab) -> Unit
) {
    val barBackground = Color(0xFF000000)
    val dividerColor = Color(0xFF1E2430)
    val inactiveColor = Color(0xFF6B7280)
    val activeColor = Color(0xFF00BCD4)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(barBackground)
            .navigationBarsPadding()
            .testTag("terminal_bottom_navigation_bar")
    ) {
        HorizontalDivider(color = dividerColor, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tradingViewBottomNavTabs.forEach { tab ->
                val isSelected = selectedTab == tab
                val iconVector = when (tab) {
                    TerminalTab.WATCHLIST -> if (isSelected) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder
                    TerminalTab.CHART -> if (isSelected) Icons.Filled.ShowChart else Icons.Outlined.ShowChart
                    TerminalTab.AI -> if (isSelected) Icons.Filled.SmartToy else Icons.Outlined.SmartToy
                    TerminalTab.CALENDAR -> if (isSelected) Icons.Filled.CalendarMonth else Icons.Outlined.CalendarToday
                    TerminalTab.PROFILE -> if (isSelected) Icons.Filled.Person else Icons.Outlined.PersonOutline
                }
                val itemColor = if (isSelected) activeColor else inactiveColor

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSelectTab(tab) }
                        .padding(vertical = 4.dp)
                        .testTag("nav_tab_${tab.name.lowercase()}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = tab.title,
                        tint = itemColor,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = tab.title,
                        color = itemColor,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsBottomSheet(
    notificationsEnabled: Boolean,
    onToggleNotifications: (Boolean) -> Unit,
    activeSignalOverlay: ChartSignalOverlay?,
    historicalSignals: List<ChartSignalOverlay>,
    upcomingEvents: List<MacroCalendarEvent>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0F1420),
        scrimColor = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.testTag("notifications_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
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
                        imageVector = Icons.Default.Notifications,
                        contentDescription = "Notifikasi",
                        tint = CyanAccent,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "Notifikasi & Alert Pasar",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    color = Color(0xFF1E2430),
                    shape = CircleShape,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .testTag("close_notifications_sheet_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "✕",
                            color = Color(0xFF9CA3AF),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF1E2430), thickness = 1.dp)

            // Master notification toggle
            Surface(
                color = Color(0xFF161B28),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFF222B3D)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "Notifikasi Sistem",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (notificationsEnabled) "Notifikasi sinyal & TP/SL aktif" else "Semua notifikasi dinonaktifkan",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (notificationsEnabled) CyanAccent else Color(0xFF9CA3AF)
                        )
                    }
                    Switch(
                        checked = notificationsEnabled,
                        onCheckedChange = onToggleNotifications,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF001F26),
                            checkedTrackColor = CyanAccent,
                            uncheckedThumbColor = Color(0xFF6B7280),
                            uncheckedTrackColor = Color(0xFF1E2430)
                        ),
                        modifier = Modifier.testTag("notifications_sheet_master_toggle")
                    )
                }
            }

            // Active Signal section
            if (activeSignalOverlay != null) {
                Text(
                    text = "SINYAL AKTIF",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF9CA3AF),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                val isLong = activeSignalOverlay.isLong
                val accentColor = if (isLong) BullishGreen else BearishRed

                Surface(
                    color = Color(0xFF161B28),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${activeSignalOverlay.symbol} • ${activeSignalOverlay.decision}",
                                style = MaterialTheme.typography.titleSmall,
                                color = accentColor,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                color = accentColor.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = activeSignalOverlay.status.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accentColor,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Entry: $${activeSignalOverlay.entry} | TP1: $${activeSignalOverlay.tp1} | SL: $${activeSignalOverlay.stopLoss}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                }
            }

            // Recent alerts
            if (historicalSignals.isNotEmpty()) {
                Text(
                    text = "ALERT TERBARU (${historicalSignals.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF9CA3AF),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                historicalSignals.take(4).forEach { hist ->
                    val isLong = hist.isLong
                    Surface(
                        color = Color(0xFF161B28),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF1E283D)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "${hist.symbol} ${hist.decision}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isLong) BullishGreen else BearishRed,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Status: ${hist.status.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF9CA3AF)
                                )
                            }
                            Text(
                                text = "$${hist.entry}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // High impact events
            if (upcomingEvents.isNotEmpty()) {
                val highImpact = upcomingEvents.filter { it.impact.name.contains("HIGH", ignoreCase = true) }.take(2)
                if (highImpact.isNotEmpty()) {
                    Text(
                        text = "PERINGATAN EVENT RISIKO TINGGI",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeutralAmber,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    highImpact.forEach { ev ->
                        Surface(
                            color = Color(0xFF161B28),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, NeutralAmber.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = ev.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "${ev.category} • Dampak Tinggi",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NeutralAmber
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (activeSignalOverlay == null && historicalSignals.isEmpty()) {
                Surface(
                    color = Color(0xFF161B28),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E283D)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null,
                            tint = Color(0xFF6B7280),
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "Belum Ada Notifikasi Baru",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Semua parameter pasar stabil. Pemantauan AI dan bot virtual berjalan otomatis di latar belakang.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF9CA3AF),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileQuickSettingsBottomSheet(
    keepScreenOn: Boolean,
    onToggleKeepScreenOn: (Boolean) -> Unit,
    notificationsEnabled: Boolean,
    onToggleNotifications: (Boolean) -> Unit,
    themeMode: String,
    onSetThemeMode: (String) -> Unit,
    language: String,
    onSetLanguage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0F1420),
        scrimColor = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.testTag("profile_quick_settings_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
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
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Pengaturan Cepat",
                        tint = CyanAccent,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "Pengaturan Cepat",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    color = Color(0xFF1E2430),
                    shape = CircleShape,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .testTag("close_quick_settings_sheet_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "✕",
                            color = Color(0xFF9CA3AF),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF1E2430), thickness = 1.dp)

            // Layar Tetap Menyala
            Surface(
                color = Color(0xFF161B28),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFF222B3D)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "Layar Tetap Menyala",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Cegah layar smartphone mati saat chart aktif",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9CA3AF)
                        )
                    }
                    Switch(
                        checked = keepScreenOn,
                        onCheckedChange = onToggleKeepScreenOn,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF001F26),
                            checkedTrackColor = CyanAccent,
                            uncheckedThumbColor = Color(0xFF6B7280),
                            uncheckedTrackColor = Color(0xFF1E2430)
                        ),
                        modifier = Modifier.testTag("quick_keep_screen_on_switch")
                    )
                }
            }

            // Notifikasi Push
            Surface(
                color = Color(0xFF161B28),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFF222B3D)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "Notifikasi Push",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Peringatan real-time sinyal baru & TP/SL",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9CA3AF)
                        )
                    }
                    Switch(
                        checked = notificationsEnabled,
                        onCheckedChange = onToggleNotifications,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF001F26),
                            checkedTrackColor = CyanAccent,
                            uncheckedThumbColor = Color(0xFF6B7280),
                            uncheckedTrackColor = Color(0xFF1E2430)
                        ),
                        modifier = Modifier.testTag("quick_notifications_switch")
                    )
                }
            }

            // Tema
            Surface(
                color = Color(0xFF161B28),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFF222B3D)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Tema Tampilan",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("DARK" to "Gelap", "LIGHT" to "Terang", "AUTO" to "Auto").forEach { (mode, label) ->
                            val isSelected = themeMode.equals(mode, ignoreCase = true)
                            Surface(
                                color = if (isSelected) CyanAccent else Color(0xFF1E2430),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onSetThemeMode(mode) }
                                    .testTag("quick_theme_$mode")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isSelected) Color(0xFF001F26) else Color.White,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bahasa
            Surface(
                color = Color(0xFF161B28),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFF222B3D)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Bahasa Antarmuka",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("id" to "Indonesia", "en" to "English").forEach { (code, label) ->
                            val isSelected = language.equals(code, ignoreCase = true)
                            Surface(
                                color = if (isSelected) CyanAccent else Color(0xFF1E2430),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onSetLanguage(code) }
                                    .testTag("quick_lang_$code")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isSelected) Color(0xFF001F26) else Color.White,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
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
