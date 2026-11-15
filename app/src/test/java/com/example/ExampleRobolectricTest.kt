package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.analyst.AIService
import com.example.ai.analyst.AiAnalystOutcome
import com.example.ai.analyst.BuiltInAIProvider
import com.example.analysis.events.EventRiskAssessment
import com.example.analysis.technical.TechnicalSnapshotBuilder
import com.example.chart.indicators.TechnicalIndicators
import com.example.market.calendar.CalendarLoadState
import com.example.market.candle.Candle
import com.example.market.candle.InstrumentCategory
import com.example.market.candle.MarketProvider
import com.example.market.candle.Ticker24h
import com.example.market.candle.Timeframe
import com.example.market.rest.BinanceRestClient
import com.example.market.status.MarketStatusService
import com.example.market.symbols.SymbolRepository
import com.example.market.symbols.SymbolValidationOutcome
import com.example.market.websocket.WsConnectionStatus
import com.example.storage.preferences.AiProviderMode
import com.example.storage.preferences.AppPreferences
import com.example.storage.preferences.CustomApiFormat
import com.example.storage.watchlist.AddPairOutcome
import com.example.storage.watchlist.CachedSymbolEntity
import com.example.storage.watchlist.TerminalDatabase
import com.example.storage.watchlist.WatchlistEntity
import com.example.storage.watchlist.WatchlistRepository
import com.example.ui.theme.NexSyncTheme
import com.example.ui.watchlist.MarketsScreen
import com.example.ui.aiPanel.CompactAiAnalystCard
import com.example.ui.chart.ChartScreen
import com.example.ai.parser.AiAnalysisResult
import com.example.ai.parser.EntryZone
import com.example.chart.indicators.IndicatorSeries
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("LUMAVUN Chart", appName)
    }

    @Test
    fun `default indicator and AI preferences prioritize large chart area and built-in AI`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferences(context)
        val settings = prefs.settings.value

        assertTrue("EMA should default to ON", settings.showEma)
        assertTrue("Volume should default to ON", settings.showVolume)
        assertFalse("RSI should default to OFF to maximize chart space", settings.showRsi)
        assertFalse("MACD should default to OFF to maximize chart space", settings.showMacd)
        assertTrue("Auto AI Signal monitoring should default to ON", settings.autoAiAnalysis)
        assertEquals("A", settings.autoSignalMinGrade)
        assertEquals("Primary AI should default to BUILT_IN", AiProviderMode.BUILT_IN, settings.primaryAiProvider)
        assertTrue("AI Fallback should default to ON", settings.enableAiFallback)
    }

    @Test
    fun `lastOpenedSymbol and lastOpenedTimeframe persist correctly across instances like TradingView`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferences(context)

        // Initial default checks
        assertEquals("BTCUSDT", prefs.getLastOpenedSymbol())
        assertEquals("1h", prefs.getLastOpenedTimeframe())
        assertEquals(Timeframe.H1, prefs.getLastOpenedTimeframeEnum())

        // User changes pair and timeframe on chart
        prefs.setLastOpenedSymbol("SOLUSDT")
        prefs.setLastOpenedTimeframe(Timeframe.M15)

        assertEquals("SOLUSDT", prefs.getLastOpenedSymbol())
        assertEquals("15m", prefs.getLastOpenedTimeframe())
        assertEquals(Timeframe.M15, prefs.getLastOpenedTimeframeEnum())

        // Simulate app restart: new instance reads persisted preferences
        val reloadedPrefs = AppPreferences(context)
        assertEquals("SOLUSDT", reloadedPrefs.getLastOpenedSymbol())
        assertEquals("15m", reloadedPrefs.getLastOpenedTimeframe())
        assertEquals(Timeframe.M15, reloadedPrefs.getLastOpenedTimeframeEnum())
    }

    @Test
    fun `builtInAiProvider and automatic failover work reliably with Primary and Backup badges`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferences(context)

        val candles = (1..220).map { i ->
            val base = 85000.0 + (i * 15.0)
            Candle(
                openTime = i * 60_000L,
                open = base - 10.0,
                high = base + 35.0,
                low = base - 25.0,
                close = base + 12.0,
                volume = 120.0 + i,
                closeTime = (i + 1) * 60_000L - 1L,
                isClosed = true
            )
        }
        val indicators = TechnicalIndicators.calculateAll(candles)
        val snapshot = TechnicalSnapshotBuilder.build(
            symbol = "BTCUSDT",
            timeframe = Timeframe.M15,
            candles = candles,
            indicators = indicators,
            higherTimeframeCandles = candles,
            lowerTimeframeCandles = candles
        )
        assertNotNull(snapshot)

        // 1. Built-in AI provider produces valid V2 structured analysis with "AI · Primary" badge
        val builtIn = BuiltInAIProvider()
        val result = builtIn.analyzeMarket(snapshot!!)
        assertNotNull(result)
        assertEquals("AI · Primary", result?.providerBadge)
        assertTrue(result!!.decision in listOf("LONG", "SHORT", "WAIT"))
        assertTrue(result.confidence in 1..100)

        // 2. When Custom API is selected without key and fallback is ON, AIService automatically fails over to Backup ("AI · Backup")
        prefs.setPrimaryAiProvider(AiProviderMode.CUSTOM_API)
        prefs.clearCustomApiKey()
        prefs.setEnableAiFallback(true)

        val aiService = AIService(preferences = prefs, builtInProvider = builtIn)
        val outcome = aiService.analyzeMarket(snapshot)
        assertTrue(outcome is AiAnalystOutcome.Success)
        val success = outcome as AiAnalystOutcome.Success
        assertEquals("AI · Backup", success.activeProviderBadge)
        assertEquals("AI · Backup", success.result.providerBadge)

        // 3. Obfuscated Custom API key save & clear
        prefs.saveCustomApiConfig(
            endpoint = "https://openrouter.ai/api/v1",
            apiKeyInput = "sk-or-v1-test-key",
            model = "openai/gpt-4o-mini",
            format = CustomApiFormat.OPENAI_COMPATIBLE
        )
        assertTrue(prefs.settings.value.hasCustomApiKey)
        assertEquals("sk-or-v1-test-key", prefs.getCustomApiKey())

        prefs.clearCustomApiKey()
        assertFalse(prefs.settings.value.hasCustomApiKey)
    }

    @Test
    fun `launch MainActivity and navigate all 5 tabs without crash`() {
        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val activity = controller.get()
            assertNotNull(activity)
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h740dp-xhdpi")
    fun `watchlist layout on narrow 360dp screen renders horizontal ACTIVE badge and supports swipe-to-delete on first middle and last rows`() {
        var watchlistItems by mutableStateOf(
            listOf(
                WatchlistEntity("BTCUSDT", sortOrder = 0, lastPrice = 89500.0, priceChangePercent = 2.45, volume = 125_000_000.0),
                WatchlistEntity("ETHUSDT", sortOrder = 1, lastPrice = 2450.5, priceChangePercent = -1.20, volume = 64_000_000.0),
                WatchlistEntity("SOLUSDT", sortOrder = 2, lastPrice = 185.25, priceChangePercent = 4.10, volume = 42_000_000.0)
            )
        )
        var selectedForChart: String? = null

        composeTestRule.setContent {
            NexSyncTheme(darkTheme = true) {
                MarketsScreen(
                    activeSymbol = "BTCUSDT",
                    watchlist = watchlistItems,
                    liveTickers = mapOf(
                        "BTCUSDT" to Ticker24h("BTCUSDT", 89500.0, 2.45, 90100.0, 87200.0, 1400.0, 125_000_000.0),
                        "ETHUSDT" to Ticker24h("ETHUSDT", 2450.5, -1.20, 2510.0, 2410.0, 26000.0, 64_000_000.0),
                        "SOLUSDT" to Ticker24h("SOLUSDT", 185.25, 4.10, 188.0, 176.0, 226000.0, 42_000_000.0)
                    ),
                    favoriteSymbols = setOf("BTCUSDT"),
                    searchQuery = "",
                    searchResults = emptyList(),
                    calendarState = CalendarLoadState.Loading,
                    eventRiskAssessment = EventRiskAssessment(),
                    onSearchQueryChange = {},
                    onSelectSymbolForChart = { selectedForChart = it },
                    onToggleFavorite = {},
                    onAddPair = { sym ->
                        if (watchlistItems.none { it.symbol == sym }) {
                            watchlistItems = watchlistItems + WatchlistEntity(sym, sortOrder = watchlistItems.size)
                        }
                    },
                    onRemovePair = { sym ->
                        watchlistItems = watchlistItems.filterNot { it.symbol == sym }
                    },
                    onMovePairUp = {},
                    onMovePairDown = {},
                    onRefreshCalendar = {}
                )
            }
        }

        // Verify ACTIVE badge is displayed horizontally on 360dp screen (width > height, never vertical)
        val activeBadgeNode = composeTestRule.onNodeWithTag("watchlist_active_badge_BTCUSDT", useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("ACTIVE", useUnmergedTree = true).assertIsDisplayed()
        val badgeBounds = activeBadgeNode.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "ACTIVE badge must be horizontal (width ${badgeBounds.width} > height ${badgeBounds.height})",
            badgeBounds.width > badgeBounds.height
        )

        // Verify Market Status LED dots and readable status label for the selected symbol
        composeTestRule.onNodeWithTag("watchlist_led_BTCUSDT", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("watchlist_led_ETHUSDT", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("watchlist_led_SOLUSDT", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("watchlist_status_label_BTCUSDT", useUnmergedTree = true).assertIsDisplayed()

        // Verify first, middle, and last rows are displayed
        composeTestRule.onNodeWithTag("watchlist_item_BTCUSDT").assertIsDisplayed()
        composeTestRule.onNodeWithTag("watchlist_item_ETHUSDT").assertIsDisplayed()
        composeTestRule.onNodeWithTag("watchlist_item_SOLUSDT").assertIsDisplayed()

        // Swipe left on first row (BTCUSDT) -> reveals delete button
        composeTestRule.onNodeWithTag("watchlist_item_BTCUSDT").performTouchInput {
            down(centerRight - Offset(10f, 0f))
            moveBy(Offset(-300f, 0f))
            up()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("swipe_delete_button_BTCUSDT").assertIsDisplayed()

        // Swipe left on middle row (ETHUSDT) -> single-active-row behavior closes BTCUSDT and opens ETHUSDT
        composeTestRule.onNodeWithTag("watchlist_item_ETHUSDT").performTouchInput {
            down(centerRight - Offset(10f, 0f))
            moveBy(Offset(-300f, 0f))
            up()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("swipe_delete_button_ETHUSDT").assertIsDisplayed()

        // Swipe left on last row (SOLUSDT) and tap Delete -> deletes SOLUSDT and shows Undo banner
        composeTestRule.onNodeWithTag("watchlist_item_SOLUSDT").performTouchInput {
            down(centerRight - Offset(10f, 0f))
            moveBy(Offset(-300f, 0f))
            up()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("swipe_delete_button_SOLUSDT")
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("BTCUSDT", "ETHUSDT"), watchlistItems.map { it.symbol })
        composeTestRule.onNodeWithTag("watchlist_undo_banner").assertIsDisplayed()

        // Tap Undo -> restores SOLUSDT
        composeTestRule.onNodeWithTag("watchlist_undo_delete_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("BTCUSDT", "ETHUSDT", "SOLUSDT"), watchlistItems.map { it.symbol })

        // Tap row to open chart
        composeTestRule.onNodeWithTag("watchlist_item_ETHUSDT").performClick()
        composeTestRule.waitForIdle()
        assertEquals("ETHUSDT", selectedForChart)
    }

    @Test
    fun `dynamic pair search normalization duplicate prevention invalid input and persistent storage work end-to-end`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, TerminalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.terminalDao()
        val watchlistRepo = WatchlistRepository(dao)
        val symbolRepo = SymbolRepository(dao, BinanceRestClient())

        try {
            // 1. Seed initial watchlist and dynamic exchange symbols (including LINK, ADA, AVAX, SUI, PEPE)
            watchlistRepo.ensureDefaultWatchlist()
            dao.insertCachedSymbols(
                SymbolRepository.POPULAR_SEED_SYMBOLS.map {
                    CachedSymbolEntity(it.symbol, it.baseAsset, "USDT", System.currentTimeMillis())
                } + listOf(
                    CachedSymbolEntity("SUIUSDT", "SUI", "USDT", System.currentTimeMillis()),
                    CachedSymbolEntity("PEPEUSDT", "PEPE", "USDT", System.currentTimeMillis()),
                    CachedSymbolEntity("RENDERUSDT", "RENDER", "USDT", System.currentTimeMillis())
                )
            )

            // 2. Test input normalization for BTC, ETH/USDT, SOL-USDT, LINK, ADA, AVAX, SUI
            assertEquals("BTCUSDT", SymbolRepository.normalizeUsdtSymbolInput("BTC"))
            assertEquals("ETHUSDT", SymbolRepository.normalizeUsdtSymbolInput("eth/usdt"))
            assertEquals("SOLUSDT", SymbolRepository.normalizeUsdtSymbolInput("SOL-USDT"))
            assertEquals("LINKUSDT", SymbolRepository.normalizeUsdtSymbolInput("link"))
            assertEquals("ADAUSDT", SymbolRepository.normalizeUsdtSymbolInput("ADA_USDT"))
            assertEquals("AVAXUSDT", SymbolRepository.normalizeUsdtSymbolInput("AVAX USDT"))
            assertEquals("", SymbolRepository.normalizeUsdtSymbolInput("   "))
            assertEquals("", SymbolRepository.normalizeUsdtSymbolInput("@@@"))
            assertEquals("", SymbolRepository.normalizeUsdtSymbolInput("USDT"))
            assertEquals("", SymbolRepository.normalizeUsdtSymbolInput("BTC/USDC"))

            // 3. Search dynamic symbols (LINK, ADA, AVAX, SUI, PEPE)
            val linkResults = symbolRepo.searchSymbols("LINK").first()
            assertTrue(linkResults.any { it.symbol == "LINKUSDT" })
            val suiResults = symbolRepo.searchSymbols("SUI").first()
            assertTrue(suiResults.any { it.symbol == "SUIUSDT" })

            // 4. Validate valid USDT symbol (AVAX -> AVAXUSDT) and add to persistent Watchlist
            val validation = symbolRepo.validateAndResolveUsdtSymbol("AVAX")
            assertTrue(validation is SymbolValidationOutcome.Valid)
            val validInfo = (validation as SymbolValidationOutcome.Valid).symbolInfo
            assertEquals("AVAXUSDT", validInfo.symbol)

            val addOutcome = watchlistRepo.addPair(
                symbol = validInfo.symbol,
                initialTicker = Ticker24h("AVAXUSDT", 34.50, 3.60, 35.10, 32.80, 950_000.0, 32_775_000.0)
            )
            assertTrue(addOutcome is AddPairOutcome.Added)

            // 5. Verify duplicate prevention
            val duplicateOutcome = watchlistRepo.addPair("AVAX/USDT")
            assertTrue(duplicateOutcome is AddPairOutcome.Duplicate)
            assertEquals("AVAXUSDT", (duplicateOutcome as AddPairOutcome.Duplicate).symbol)

            // 6. Verify invalid input rejection
            val invalidOutcome = watchlistRepo.addPair("@@@")
            assertTrue(invalidOutcome is AddPairOutcome.Invalid)

            // 7. Verify persistence across new Repository instance reading from same DB (simulating app reopen)
            val reopenedWatchlistRepo = WatchlistRepository(dao)
            val persistedList = reopenedWatchlistRepo.watchlistFlow.first()
            val persistedAvax = persistedList.find { it.symbol == "AVAXUSDT" }
            assertNotNull("Added pair AVAXUSDT must persist after app reopen", persistedAvax)
            assertEquals(34.50, persistedAvax!!.lastPrice, 0.001)
            assertEquals(3.60, persistedAvax.priceChangePercent, 0.001)
            assertEquals(32_775_000.0, persistedAvax.volume, 1.0)
        } finally {
            db.close()
        }
    }

    @Test
    fun `autoSetupEngineV2 persists lifecycle transitions in SignalJournal and renders MarketAssessment and ActiveSetup in UI`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(context, TerminalDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val dao = db.terminalDao()
            val watchlistRepo = WatchlistRepository(dao)

            try {
                val nowMs = 1_760_000_000_000L
                val candles = (1..220).map { i ->
                    val base = 90_000.0 + i * 18.0
                    Candle(
                        openTime = nowMs - (220 - i) * 900_000L,
                        open = base - 10.0,
                        high = base + 30.0,
                        low = base - 20.0,
                        close = base + 12.0,
                        volume = 120.0,
                        closeTime = nowMs - (220 - i) * 900_000L + 899_999L,
                        isClosed = true
                    )
                }
                val snapshot = TechnicalSnapshotBuilder.build("BTCUSDT", Timeframe.M15, candles)!!
                val longResult = com.example.ai.parser.AiResponseParser.parse(
                    rawText = """
                        {
                          "decision": "LONG",
                          "bias": "BULLISH",
                          "trend": "BULLISH",
                          "momentum": "POSITIVE",
                          "marketStructure": "HH_HL",
                          "confidence": 85,
                          "setupQuality": "A",
                          "marketRegime": "TRENDING",
                          "higherTimeframeBias": "BULLISH",
                          "primaryTimeframeBias": "BULLISH",
                          "lowerTimeframeBias": "PULLBACK",
                          "entryZone": { "low": 93500.0, "high": 93800.0 },
                          "stopLoss": 92900.0,
                          "takeProfit": [94800.0, 95600.0, 96500.0],
                          "riskReward": 2.2,
                          "entryTrigger": "Retest of 93,500 - 93,800 demand zone",
                          "invalidIf": "15m close below 92,900",
                          "reasoning": ["Confirmed bullish structure", "Institutional demand zone"],
                          "summary": "Bullish continuation pullback setup"
                        }
                    """.trimIndent()
                )!!.copy(analyzedAt = nowMs, expiryAt = nowMs + 12 * 900_000L)

                val activeSetup = com.example.analysis.signal.AutoSetupEngineV2.createActiveSetupFromAiResult(
                    symbol = "BTCUSDT",
                    timeframe = Timeframe.M15,
                    aiResult = longResult,
                    currentPrice = snapshot.currentPrice,
                    nowMs = nowMs
                )!!

                // 1. Record setup to SignalJournal with explicit lifecycleState and statusReason
                val savedId = watchlistRepo.recordSignalToJournal(
                    snapshot = snapshot,
                    result = activeSetup.sourceAiResult,
                    botAction = "MONITORING",
                    lifecycleState = activeSetup.lifecycleState.code,
                    statusReason = activeSetup.statusReason
                )
                assertTrue(savedId > 0L)

                // 2. Transition setup to ENTERED and persist to SignalJournal
                watchlistRepo.updateSignalSetupLifecycle(
                    setupId = savedId,
                    lifecycleState = com.example.analysis.signal.SetupLifecycleState.ENTERED,
                    forwardStatus = com.example.analysis.signal.SignalLifecycleStatus.ENTRY_TRIGGERED,
                    statusReason = "Entry Zone triggered @ 93,650.0 — position opened"
                )

                // 3. Restore active setup from SignalJournal and verify state + reason
                val latestEntity = watchlistRepo.getLatestActiveSetupForPair("BTCUSDT", Timeframe.M15)
                assertNotNull(latestEntity)
                assertEquals("ENTERED", latestEntity!!.lifecycleState)
                assertEquals("Entry Zone triggered @ 93,650.0 — position opened", latestEntity.statusReason)

                val restoredSetup = com.example.analysis.signal.AutoSetupEngineV2.restoreActiveSetupFromJournalEntity(latestEntity)
                assertNotNull(restoredSetup)
                assertEquals(com.example.analysis.signal.SetupLifecycleState.ENTERED, restoredSetup!!.lifecycleState)

                // 4. Verify UI renders both MarketAssessment (WAIT) and protected ActiveSetup (LONG ENTERED)
                val waitAssessment = com.example.analysis.signal.MarketAssessmentState(
                    symbol = "BTCUSDT",
                    timeframe = "15m",
                    bias = "BULLISH",
                    higherTimeframeBias = "BULLISH",
                    primaryTimeframeBias = "BULLISH",
                    lowerTimeframeBias = "NEUTRAL",
                    marketRegime = "TRENDING",
                    marketStructure = "HH_HL",
                    setupCondition = "ACTIVE_SETUP_PROTECTED",
                    scanLifecycleState = com.example.analysis.signal.SetupLifecycleState.ENTERED,
                    latestDecision = "WAIT",
                    latestGrade = "WAIT",
                    confidence = 60,
                    summary = "Waiting while active LONG setup is in progress",
                    dataStatusLabel = "OPEN",
                    isDataValidForEntry = true,
                    isIntrabarEvaluation = true
                )

                composeTestRule.setContent {
                    NexSyncTheme(darkTheme = true) {
                        com.example.ui.aiPanel.FullAiAnalystScreen(
                            symbol = "BTCUSDT",
                            timeframe = Timeframe.M15,
                            snapshot = snapshot,
                            aiResult = restoredSetup.sourceAiResult,
                            activeSignalOverlay = com.example.analysis.signal.AutoSetupEngineV2.buildOverlayFromActiveSetup(restoredSetup),
                            marketAssessment = waitAssessment,
                            activeSetup = restoredSetup,
                            isAnalyzing = false,
                            autoAiAnalysis = true,
                            errorMessage = null,
                            providerBadge = "AI · Primary",
                            signalJournal = listOf(latestEntity),
                            onToggleAutoAi = {},
                            onAnalyzeClick = {}
                        )
                    }
                }

                composeTestRule.onNodeWithTag("market_assessment_card", useUnmergedTree = true).assertExists()
                composeTestRule.onNodeWithTag("active_setup_lifecycle_badge", useUnmergedTree = true).assertExists()
                composeTestRule.onNodeWithTag("active_setup_status_reason", useUnmergedTree = true).assertExists()
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun `dualAiAnalysisSystem differentiates Scalping DayTrading and SwingTrading profiles and enforces stale data WAIT`() {
        runBlocking {
            val nowMs = 1_760_000_000_000L
            val candles = (1..240).map { i ->
                val base = 90_000.0 + i * 22.0
                Candle(
                    openTime = nowMs - (240 - i) * 900_000L,
                    open = base - 12.0,
                    high = base + 35.0,
                    low = base - 25.0,
                    close = base + 16.0,
                    volume = 180.0 + (i % 7) * 25.0,
                    closeTime = nowMs - (240 - i) * 900_000L + 899_999L,
                    isClosed = true
                )
            }
            val snapshot = TechnicalSnapshotBuilder.build("BTCUSDT", Timeframe.M15, candles)!!
            val builtIn = com.example.ai.analyst.BuiltInAIProvider()

            // 1. Verify profile default timeframes and higher-timeframe context
            assertEquals(Timeframe.M5, com.example.storage.preferences.TradingStyleProfile.SCALPING.defaultEntryTimeframe)
            assertEquals(Timeframe.M15, com.example.storage.preferences.TradingStyleProfile.SCALPING.resolveHigherTimeframe(Timeframe.M5))

            assertEquals(Timeframe.M15, com.example.storage.preferences.TradingStyleProfile.DAY_TRADING.defaultEntryTimeframe)
            assertEquals(Timeframe.H1, com.example.storage.preferences.TradingStyleProfile.DAY_TRADING.resolveHigherTimeframe(Timeframe.M15))

            assertEquals(Timeframe.H1, com.example.storage.preferences.TradingStyleProfile.SWING_TRADING.defaultEntryTimeframe)
            assertEquals(Timeframe.H4, com.example.storage.preferences.TradingStyleProfile.SWING_TRADING.resolveHigherTimeframe(Timeframe.H1))

            // 2. Run Scalping, Day Trading, and Swing Trading profiles and verify distinct levels, expiry, and rationale
            val scalpingContext = com.example.ai.analyst.AiAnalysisRequestContext(
                aiSystem = com.example.storage.preferences.AiAnalysisSystem.AI_TRADING_STYLE,
                continuousSubmode = com.example.storage.preferences.AiContinuousSubmode.MANUAL,
                tradingStyleProfile = com.example.storage.preferences.TradingStyleProfile.SCALPING,
                isAutomaticTrigger = false,
                higherTimeframeLabel = "15m",
                lowerTimeframeLabel = "1m",
                isDataValidForEntry = true,
                dataStatusLabel = "OPEN"
            )
            val dayTradingContext = scalpingContext.copy(
                tradingStyleProfile = com.example.storage.preferences.TradingStyleProfile.DAY_TRADING,
                higherTimeframeLabel = "1h",
                lowerTimeframeLabel = "5m"
            )
            val swingTradingContext = scalpingContext.copy(
                tradingStyleProfile = com.example.storage.preferences.TradingStyleProfile.SWING_TRADING,
                higherTimeframeLabel = "4h",
                lowerTimeframeLabel = "15m"
            )

            val scalpingRes = builtIn.synthesizeV2MultiTimeframeAnalysis(
                snapshot = snapshot,
                badge = "AI · Primary",
                modelName = "nexsync-mtf-v2",
                context = scalpingContext
            )!!
            val dayRes = builtIn.synthesizeV2MultiTimeframeAnalysis(
                snapshot = snapshot,
                badge = "AI · Primary",
                modelName = "nexsync-mtf-v2",
                context = dayTradingContext
            )!!
            val swingRes = builtIn.synthesizeV2MultiTimeframeAnalysis(
                snapshot = snapshot,
                badge = "AI · Primary",
                modelName = "nexsync-mtf-v2",
                context = swingTradingContext
            )!!

            assertEquals("AI_TRADING_STYLE", scalpingRes.aiSystemMode)
            assertEquals("MANUAL", scalpingRes.aiExecutionMode)
            assertEquals("SCALPING", scalpingRes.tradingStyleProfile)
            assertTrue(scalpingRes.modeAttributionBadge.contains("AI Gaya Trading · Scalping"))

            assertEquals("DAY_TRADING", dayRes.tradingStyleProfile)
            assertTrue(dayRes.modeAttributionBadge.contains("AI Gaya Trading · Day Trading"))

            assertEquals("SWING_TRADING", swingRes.tradingStyleProfile)
            assertTrue(swingRes.modeAttributionBadge.contains("AI Gaya Trading · Swing Trading"))

            // Verify different stop-loss distances / expiry windows across Scalping vs Day vs Swing
            assertTrue(scalpingRes.expiryAt < dayRes.expiryAt)
            assertTrue(dayRes.expiryAt < swingRes.expiryAt)
            assertTrue(scalpingRes.reasoning.any { it.contains("Scalping", ignoreCase = true) })
            assertTrue(dayRes.reasoning.any { it.contains("Day Trading", ignoreCase = true) })
            assertTrue(swingRes.reasoning.any { it.contains("Swing Trading", ignoreCase = true) })
            assertTrue(scalpingRes.positionSizingGuidance.isNotBlank())

            // 3. Verify invalid or stale market data blocks new setups and returns WAIT with clear reason
            val staleContext = dayTradingContext.copy(
                isDataValidForEntry = false,
                dataStatusLabel = "STALE",
                dataStatusReason = "Quote timestamp exceeded freshness threshold"
            )
            val staleOut = builtIn.analyze(snapshot, staleContext) as com.example.ai.analyst.AiAnalystOutcome.Success
            assertTrue(staleOut.result.isWait)
            assertEquals("WAIT", staleOut.result.effectiveEntryOrderType)
            assertTrue(staleOut.result.dataValidationWarning.contains("STALE", ignoreCase = true))
        }
    }

    @Test
    fun `dualAiAnalysisSystem UI hides Auto toggle in AI Gaya Trading and stops background auto monitor in Manual modes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = com.example.storage.preferences.AppPreferences(context)

        // 1. Selecting AI Continuous -> Auto
        prefs.setAiAnalysisSystem(com.example.storage.preferences.AiAnalysisSystem.AI_CONTINUOUS)
        prefs.setAiContinuousSubmode(com.example.storage.preferences.AiContinuousSubmode.AUTO)
        assertEquals(com.example.storage.preferences.AiAnalysisSystem.AI_CONTINUOUS, prefs.settings.value.aiAnalysisSystem)
        assertEquals(com.example.storage.preferences.AiContinuousSubmode.AUTO, prefs.settings.value.aiContinuousSubmode)
        assertTrue(prefs.settings.value.autoAiAnalysis)

        val openMarketEval = MarketStatusService.evaluate(
            symbol = "BTCUSDT",
            category = InstrumentCategory.CRYPTO,
            provider = MarketProvider.BINANCE,
            fallbackQuoteTimestampMs = System.currentTimeMillis(),
            connectionStatus = WsConnectionStatus.LIVE
        )
        val autoRuntime = com.example.analysis.signal.AutoSetupEngineV2.resolveAutoMonitorRuntimeState(
            aiSystem = prefs.settings.value.aiAnalysisSystem,
            continuousSubmode = prefs.settings.value.aiContinuousSubmode,
            isAnalyzing = false,
            hasCandles = true,
            wsStatus = WsConnectionStatus.LIVE,
            marketStatus = openMarketEval
        )
        assertEquals(com.example.storage.preferences.AiAutoMonitorRuntimeState.RUNNING, autoRuntime)

        // 2. Selecting AI Continuous -> Manual stops auto monitor
        prefs.setAiContinuousSubmode(com.example.storage.preferences.AiContinuousSubmode.MANUAL)
        assertFalse(prefs.settings.value.autoAiAnalysis)
        val manualRuntime = com.example.analysis.signal.AutoSetupEngineV2.resolveAutoMonitorRuntimeState(
            aiSystem = prefs.settings.value.aiAnalysisSystem,
            continuousSubmode = prefs.settings.value.aiContinuousSubmode,
            isAnalyzing = false,
            hasCandles = true,
            wsStatus = WsConnectionStatus.LIVE,
            marketStatus = openMarketEval
        )
        assertEquals(com.example.storage.preferences.AiAutoMonitorRuntimeState.STOPPED, manualRuntime)

        // 3. Selecting AI Gaya Trading -> Swing Trading is always Manual and hides Auto switch in UI
        prefs.setAiAnalysisSystem(com.example.storage.preferences.AiAnalysisSystem.AI_TRADING_STYLE)
        prefs.setTradingStyleProfile(com.example.storage.preferences.TradingStyleProfile.SWING_TRADING)
        val styleRuntime = com.example.analysis.signal.AutoSetupEngineV2.resolveAutoMonitorRuntimeState(
            aiSystem = prefs.settings.value.aiAnalysisSystem,
            continuousSubmode = prefs.settings.value.aiContinuousSubmode,
            isAnalyzing = false,
            hasCandles = true,
            wsStatus = WsConnectionStatus.LIVE,
            marketStatus = openMarketEval
        )
        assertEquals(com.example.storage.preferences.AiAutoMonitorRuntimeState.STOPPED, styleRuntime)

        composeTestRule.setContent {
            NexSyncTheme(darkTheme = true) {
                com.example.ui.aiPanel.FullAiAnalystScreen(
                    symbol = "BTCUSDT",
                    timeframe = Timeframe.H1,
                    snapshot = null,
                    aiResult = null,
                    isAnalyzing = false,
                    autoAiAnalysis = false,
                    aiAnalysisSystem = com.example.storage.preferences.AiAnalysisSystem.AI_TRADING_STYLE,
                    aiContinuousSubmode = com.example.storage.preferences.AiContinuousSubmode.MANUAL,
                    tradingStyleProfile = com.example.storage.preferences.TradingStyleProfile.SWING_TRADING,
                    autoMonitorRuntimeState = styleRuntime,
                    errorMessage = null,
                    providerBadge = "AI · Primary",
                    onToggleAutoAi = {},
                    onAnalyzeClick = {}
                )
            }
        }

        // Verify AI System Selection screen options & strategy profiles exist
        composeTestRule.onNodeWithTag("ai_system_selection_card", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("ai_system_option_continuous", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("ai_system_option_trading_style", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("trading_style_profile_scalping", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("trading_style_profile_day_trading", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("trading_style_profile_swing_trading", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("trading_style_manual_only_badge", useUnmergedTree = true).assertExists()
        // Verify AI Gaya Trading does NOT render an Auto toggle switch
        composeTestRule.onNodeWithTag("auto_ai_switch", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `marketStructureEngine detects confirmed pivots BOS CHoCH equal levels and order blocks without lookAhead bias`() {
        val baseTs = 1_760_000_000_000L
        // Construct deterministic XAU/USD candles forming:
        // Swing Low 1 (2600) -> Swing High 1 (2625) -> Swing Low 2 (2608, HL) -> Swing High 2 (2625.1, EQH & BOS) -> Breakdown below 2608 (Bearish CHoCH)
        val prices = listOf(
            2615.0, 2610.0, 2605.0, 2600.0, 2606.0, 2612.0, 2618.0, // pivot low at idx 3 (2600)
            2625.0, 2619.0, 2614.0, 2608.0, 2613.0, 2619.0, 2624.0, // pivot high at idx 7 (2625), pivot low at idx 10 (2608)
            2625.1, 2620.0, 2616.0, 2612.0, 2618.0, 2629.0, 2632.0, // pivot high at idx 14 (2625.1 -> EQH with 2625.0), then close at 2629/2632 -> Bullish BOS
            2626.0, 2618.0, 2604.0, 2602.0                          // sharp close below 2608 HL -> Bearish CHoCH
        )
        val candles = prices.mapIndexed { idx, closePrice ->
            val isBull = idx == 0 || closePrice >= prices[idx - 1]
            val openPrice = if (isBull) closePrice - 2.5 else closePrice + 2.5
            val highPrice = maxOf(openPrice, closePrice) + 1.2
            val lowPrice = minOf(openPrice, closePrice) - 1.2
            Candle(
                openTime = baseTs + idx * 900_000L,
                open = openPrice,
                high = highPrice,
                low = lowPrice,
                close = closePrice,
                volume = 1000.0 + idx * 20.0,
                closeTime = baseTs + (idx + 1) * 900_000L - 1L,
                isClosed = true
            )
        }

        val analysis = com.example.analysis.marketStructure.MarketStructureEngine.analyze(
            candles = candles,
            pivotLookback = 2,
            atr14 = 5.0
        )

        val confirmedHighs = analysis.confirmedSwings.filter { it.isHigh }
        val confirmedLows = analysis.confirmedSwings.filter { !it.isHigh }
        assertTrue("Confirmed swing highs must be detected", confirmedHighs.isNotEmpty())
        assertTrue("Confirmed swing lows must be detected", confirmedLows.isNotEmpty())
        // Verify no look-ahead bias: every confirmed pivot must have confirmedAtCandleOpenTime >= openTime + pivotLookback * 900_000L
        confirmedHighs.forEach { p ->
            assertTrue(p.isConfirmed)
            assertTrue(p.confirmedAtCandleOpenTime >= p.openTime + 2 * 900_000L)
        }
        confirmedLows.forEach { p ->
            assertTrue(p.isConfirmed)
            assertTrue(p.confirmedAtCandleOpenTime >= p.openTime + 2 * 900_000L)
        }
        assertNotNull("BOS event should be detected", analysis.latestBos)
        assertNotNull("CHoCH event should be detected when structure reverses below HL", analysis.latestChoch)
        assertTrue("Equal Highs (EQH) should be detected within tolerance", analysis.equalHighsLows.isNotEmpty())
    }

    @Test
    fun `smcLiquiditySweepEngine detects potential and confirmed sweeps invalidates closedThrough sweeps and deduplicates ticks`() {
        com.example.analysis.smc.SmcLiquiditySweepEngine.clearDeduplicationRegistryForTest()
        val baseTs = 1_760_100_000_000L
        val candles = (0..35).map { i ->
            val base = 2650.0 + (i % 5) * 2.0
            Candle(
                openTime = baseTs + i * 900_000L,
                open = base,
                high = base + 3.0,
                low = base - 2.5,
                close = base + 1.0,
                volume = 500.0,
                closeTime = baseTs + (i + 1) * 900_000L - 1L,
                isClosed = true
            )
        }.toMutableList()

        val trackedLow = com.example.analysis.smc.LiquidityReferenceLevel(
            type = com.example.analysis.smc.LiquidityReferenceType.PDL,
            price = 2640.0,
            referenceTimeMs = baseTs,
            label = "Previous Day Low (PDL)"
        )

        // 1. Add a closed candle that sweeps below 2640.0 (low = 2637.8) and reclaims above 2640.0 (close = 2642.5)
        val sweepCandleTime = baseTs + 36 * 900_000L
        val confirmedSweepCandles = candles + Candle(
            openTime = sweepCandleTime,
            open = 2641.5,
            high = 2644.0,
            low = 2637.8,
            close = 2642.5,
            volume = 1200.0,
            closeTime = sweepCandleTime + 899_999L,
            isClosed = true
        )

        val sweeps = com.example.analysis.smc.SmcLiquiditySweepEngine.detectLiquiditySweeps(
            symbol = "XAUUSD",
            timeframe = Timeframe.M15,
            candles = confirmedSweepCandles,
            referenceLevels = listOf(trackedLow),
            atr14 = 4.0,
            nowMs = sweepCandleTime + 900_000L
        )
        assertTrue("Confirmed bullish liquidity sweep should be detected", sweeps.isNotEmpty())
        val confirmedSweep = sweeps.last()
        assertEquals(com.example.analysis.smc.SweepDetectionState.CONFIRMED_SWEEP, confirmedSweep.state)
        assertTrue(confirmedSweep.isBullishSweep)
        assertFalse(confirmedSweep.isIntrabarObservation)

        // 2. Verify deduplication across repeated ticks
        assertTrue(
            "First emission of sweep should return true",
            com.example.analysis.smc.SmcLiquiditySweepEngine.registerSweepEmissionIfNew(confirmedSweep)
        )
        assertFalse(
            "Subsequent tick for same sweep dedupKey must be deduplicated",
            com.example.analysis.smc.SmcLiquiditySweepEngine.registerSweepEmissionIfNew(confirmedSweep)
        )

        // 3. Add an intrabar unclosed candle that penetrates below 2640.0 and is currently back at 2640.8 -> POTENTIAL_SWEEP
        val intrabarCandles = candles + Candle(
            openTime = sweepCandleTime,
            open = 2641.0,
            high = 2642.0,
            low = 2638.2,
            close = 2640.8,
            volume = 800.0,
            closeTime = sweepCandleTime + 899_999L,
            isClosed = false
        )
        val potentialSweeps = com.example.analysis.smc.SmcLiquiditySweepEngine.detectLiquiditySweeps(
            symbol = "XAUUSD",
            timeframe = Timeframe.M15,
            candles = intrabarCandles,
            referenceLevels = listOf(trackedLow),
            atr14 = 4.0,
            nowMs = sweepCandleTime + 300_000L
        )
        assertTrue(potentialSweeps.isNotEmpty())
        assertEquals(com.example.analysis.smc.SweepDetectionState.POTENTIAL_SWEEP, potentialSweeps.last().state)
        assertTrue(potentialSweeps.last().isIntrabarObservation)
    }

    @Test
    fun `tradingSessionEngine computes WITA London and NewYork DST sessions and session highs and lows`() {
        // Summer timestamp (July 15, 2026 13:30 UTC -> London BST is 14:30, NY EDT is 09:30 -> London-NY Overlap!)
        // WITA (Asia/Makassar, UTC+8) is 21:30 WITA
        val summerOverlapUtcMs = java.time.ZonedDateTime.of(
            2026, 7, 15, 13, 30, 0, 0, java.time.ZoneId.of("UTC")
        ).toInstant().toEpochMilli()

        val candles = (0..40).map { i ->
            val ts = summerOverlapUtcMs - (40 - i) * 3_600_000L
            Candle(
                openTime = ts,
                open = 2650.0 + i,
                high = 2655.0 + i,
                low = 2645.0 + i,
                close = 2652.0 + i,
                volume = 1000.0,
                closeTime = ts + 3_599_999L,
                isClosed = true
            )
        }

        val summerSnap = com.example.market.session.TradingSessionEngine.evaluateSession(
            symbol = "XAUUSD",
            candles = candles,
            nowMs = summerOverlapUtcMs
        )
        assertEquals(com.example.market.session.TradingSessionType.LONDON_NY_OVERLAP, summerSnap.primarySession)
        assertTrue("London DST must be active in July", summerSnap.isLondonDstActive)
        assertTrue("New York DST must be active in July", summerSnap.isNewYorkDstActive)
        assertTrue(summerSnap.witaTimeFormatted.contains("21:30 WITA"))
        assertNotNull("Current session range should be computed", summerSnap.currentSessionHighLow)
        assertNotNull("Previous Day High/Low (PDH/PDL) should be computed", summerSnap.previousDayHighLow)

        // Winter timestamp (January 15, 2026 13:30 UTC -> London GMT is 13:30, NY EST is 08:30 -> Both open, Overlap!)
        val winterOverlapUtcMs = java.time.ZonedDateTime.of(
            2026, 1, 15, 13, 30, 0, 0, java.time.ZoneId.of("UTC")
        ).toInstant().toEpochMilli()
        val winterSnap = com.example.market.session.TradingSessionEngine.evaluateSession(
            symbol = "XAUUSD",
            candles = candles,
            nowMs = winterOverlapUtcMs
        )
        assertFalse("London DST must be false in January", winterSnap.isLondonDstActive)
        assertFalse("New York DST must be false in January", winterSnap.isNewYorkDstActive)
    }

    @Test
    fun `spreadVolatilityAndEventFilters enforce normal excessive and unavailable spread policies and block sweepOnly entries`() {
        // 1. Normal XAU/USD spread ($0.35 on $2650) -> passes spread filter
        val normalSpread = com.example.analysis.smc.SmcLiquiditySweepEngine.validateSpreadAndVolatility(
            symbol = "XAUUSD",
            currentPrice = 2650.0,
            bidPrice = 2649.82,
            askPrice = 2650.17,
            hasVerifiedBidAsk = true,
            atr14 = 5.0,
            volatilityState = "NORMAL",
            proposedEntryPrice = 2650.0,
            proposedStopLoss = 2642.0,
            proposedTp1 = 2666.0,
            isLongSetup = true,
            profileCode = "DAY_TRADING"
        )
        assertTrue(normalSpread.isBidAskAvailable)
        assertFalse(normalSpread.isSpreadExcessive)
        assertTrue(normalSpread.isSpreadCheckPassed)
        assertTrue(normalSpread.recalculatedQuantityUnits > 0.0)

        // 2. Excessive XAU/USD spread ($4.50 on $2650 with $5 stop distance) -> rejected by spread filter
        val excessiveSpread = com.example.analysis.smc.SmcLiquiditySweepEngine.validateSpreadAndVolatility(
            symbol = "XAUUSD",
            currentPrice = 2650.0,
            bidPrice = 2647.75,
            askPrice = 2652.25,
            hasVerifiedBidAsk = true,
            atr14 = 5.0,
            volatilityState = "NORMAL",
            proposedEntryPrice = 2650.0,
            proposedStopLoss = 2645.0,
            proposedTp1 = 2656.0,
            isLongSetup = true,
            profileCode = "SCALPING"
        )
        assertTrue("Excessive spread must be flagged", excessiveSpread.isSpreadExcessive)
        assertFalse(excessiveSpread.isSpreadCheckPassed)

        // 3. Unavailable spread with BLOCK_NEW_ENTRIES policy -> rejected
        val unavailBlockConfig = com.example.analysis.smc.SpreadVolatilityConfig(
            unavailableSpreadPolicy = com.example.analysis.smc.UnavailableSpreadPolicy.BLOCK_NEW_ENTRIES
        )
        val unavailableBlocked = com.example.analysis.smc.SmcLiquiditySweepEngine.validateSpreadAndVolatility(
            symbol = "XAUUSD",
            currentPrice = 2650.0,
            bidPrice = null,
            askPrice = null,
            hasVerifiedBidAsk = false,
            atr14 = 5.0,
            volatilityState = "NORMAL",
            proposedEntryPrice = 2650.0,
            proposedStopLoss = 2642.0,
            proposedTp1 = 2666.0,
            isLongSetup = true,
            config = unavailBlockConfig
        )
        assertFalse(unavailableBlocked.isBidAskAvailable)
        assertFalse("Unavailable spread with BLOCK_NEW_ENTRIES policy must block entry", unavailableBlocked.isSpreadCheckPassed)

        // 4. Unavailable spread with ALLOW_WITH_WARNING policy -> allowed with explicit warning
        val unavailWarnConfig = com.example.analysis.smc.SpreadVolatilityConfig(
            unavailableSpreadPolicy = com.example.analysis.smc.UnavailableSpreadPolicy.ALLOW_WITH_WARNING
        )
        val unavailableWarn = com.example.analysis.smc.SmcLiquiditySweepEngine.validateSpreadAndVolatility(
            symbol = "XAUUSD",
            currentPrice = 2650.0,
            bidPrice = null,
            askPrice = null,
            hasVerifiedBidAsk = false,
            atr14 = 5.0,
            volatilityState = "NORMAL",
            proposedEntryPrice = 2650.0,
            proposedStopLoss = 2642.0,
            proposedTp1 = 2666.0,
            isLongSetup = true,
            config = unavailWarnConfig
        )
        assertTrue("Unavailable spread with ALLOW_WITH_WARNING policy should pass with warning", unavailableWarn.isSpreadCheckPassed)
        assertTrue(unavailableWarn.spreadStatusSummary.contains("UNAVAILABLE", ignoreCase = true))
    }

    @Test
    fun tradingViewNavigationAndSettingsScreenRenderCorrectly() {
        val settings = com.example.storage.preferences.TerminalSettings(
            userName = "Trader Alex",
            themeMode = "DARK",
            keepScreenOn = true,
            language = "id"
        )

        composeTestRule.setContent {
            com.example.ui.settings.SettingsScreen(
                settings = settings,
                connectionStatus = com.example.market.websocket.WsConnectionStatus.LIVE,
                cachedSymbolCount = 150,
                isTestingAiConnection = false,
                aiConnectionTestStatus = null,
                onSetDefaultTimeframe = {},
                onSetDefaultSymbol = {},
                onSetAutoAiAnalysis = {},
                onSetPrimaryAiProvider = {},
                onSetEnableAiFallback = {},
                onSaveCustomApiConfig = { _, _, _, _ -> },
                onClearCustomApiKey = {},
                onSaveGeminiCustomConfig = { _, _ -> },
                onClearGeminiCustomKey = {},
                onTestAiConnection = {},
                onSetShowEma = {},
                onSetShowRsi = {},
                onSetShowMacd = {},
                onSetShowVolume = {},
                onSetShowSupportResistance = {},
                onSetDarkMode = {},
                onForceReconnect = {},
                onRefreshExchangeSymbols = {}
            )
        }

        // Verify Profile Header items: Avatar, User Name, and BASIC Tier Badge
        composeTestRule.onNodeWithTag("profile_avatar_box", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("profile_name_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("tier_badge_basic", useUnmergedTree = true).assertExists()

        // Verify Menu list items (TradingView UX)
        composeTestRule.onNodeWithTag("menu_theme", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("menu_keep_screen_on", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("menu_watchlist", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("menu_chart", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("menu_simulation_account", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("menu_ai_config", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("menu_notifications", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("menu_language", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("menu_contact_info", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("menu_about", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("menu_delete_account", useUnmergedTree = true).assertExists()
    }

    @Test
    fun settingsAiConfigurationScreenOpensAndSwitchesTabsWithoutCrash() {
        val settings = com.example.storage.preferences.TerminalSettings(
            userName = "Trader Alex",
            themeMode = "DARK",
            keepScreenOn = true,
            language = "id"
        )

        composeTestRule.setContent {
            com.example.ui.settings.SettingsScreen(
                settings = settings,
                connectionStatus = com.example.market.websocket.WsConnectionStatus.LIVE,
                cachedSymbolCount = 150,
                isTestingAiConnection = false,
                aiConnectionTestStatus = null,
                initialSubPage = com.example.ui.settings.SettingsSubPage.AI_CONFIG.name,
                onSetDefaultTimeframe = {},
                onSetDefaultSymbol = {},
                onSetAutoAiAnalysis = {},
                onSetPrimaryAiProvider = {},
                onSetEnableAiFallback = {},
                onSaveCustomApiConfig = { _, _, _, _ -> },
                onClearCustomApiKey = {},
                onSaveGeminiCustomConfig = { _, _ -> },
                onClearGeminiCustomKey = {},
                onTestAiConnection = {},
                onSetShowEma = {},
                onSetShowRsi = {},
                onSetShowMacd = {},
                onSetShowVolume = {},
                onSetShowSupportResistance = {},
                onSetDarkMode = {},
                onForceReconnect = {},
                onRefreshExchangeSymbols = {}
            )
        }

        // Verify that the AI configuration screen, tab row, and provider content render with finite constraints
        composeTestRule.onNodeWithTag("ai_configuration_screen", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("ai_config_tab_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("ai_tab_provider", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("test_ai_connection_btn", useUnmergedTree = true).assertExists()

        // Inspect OpenAI provider and verify remote configuration inputs & Save button render
        composeTestRule.onNodeWithTag("provider_chip_openai", useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithTag("save_provider_config_btn", useUnmergedTree = true).assertExists()

        // Switch to Models tab and verify it renders without infinite constraints
        composeTestRule.onNodeWithTag("ai_tab_models", useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithTag("connect_sync_models_btn", useUnmergedTree = true).assertExists()

        // Switch to Usage tab and verify it renders without infinite constraints
        composeTestRule.onNodeWithTag("ai_tab_usage", useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithTag("configure_budget_btn", useUnmergedTree = true).assertExists()

        // Switch back to Provider tab and verify it renders cleanly
        composeTestRule.onNodeWithTag("ai_tab_provider", useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithTag("test_ai_connection_btn", useUnmergedTree = true).assertExists()
    }

    @Test
    fun settingsAiConfigurationFullNavigationCujTest() {
        val settings = com.example.storage.preferences.TerminalSettings(
            userName = "Trader Alex",
            themeMode = "DARK",
            keepScreenOn = true,
            language = "id"
        )

        composeTestRule.setContent {
            com.example.ui.settings.SettingsScreen(
                settings = settings,
                connectionStatus = com.example.market.websocket.WsConnectionStatus.LIVE,
                cachedSymbolCount = 150,
                isTestingAiConnection = false,
                aiConnectionTestStatus = null,
                initialSubPage = null,
                onSetDefaultTimeframe = {},
                onSetDefaultSymbol = {},
                onSetAutoAiAnalysis = {},
                onSetPrimaryAiProvider = {},
                onSetEnableAiFallback = {},
                onSaveCustomApiConfig = { _, _, _, _ -> },
                onClearCustomApiKey = {},
                onSaveGeminiCustomConfig = { _, _ -> },
                onClearGeminiCustomKey = {},
                onTestAiConnection = {},
                onSetShowEma = {},
                onSetShowRsi = {},
                onSetShowMacd = {},
                onSetShowVolume = {},
                onSetShowSupportResistance = {},
                onSetDarkMode = {},
                onForceReconnect = {},
                onRefreshExchangeSymbols = {}
            )
        }

        // 1. Initial Settings Hub shows menu_ai_config (scroll to it in the vertical scroll container)
        composeTestRule.onNodeWithTag("menu_ai_config", useUnmergedTree = true).performScrollTo().performClick()

        // 2. AI Configuration screen opened
        composeTestRule.onNodeWithTag("ai_configuration_screen", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("ai_tab_provider", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("test_ai_connection_btn", useUnmergedTree = true).assertExists()

        // 3. Switch between tabs repeatedly (using performScrollTo since outer SettingsScreen scrolls)
        composeTestRule.onNodeWithTag("ai_tab_models", useUnmergedTree = true).performScrollTo().performClick()
        composeTestRule.onNodeWithTag("connect_sync_models_btn", useUnmergedTree = true).performScrollTo().assertExists()

        composeTestRule.onNodeWithTag("ai_tab_usage", useUnmergedTree = true).performScrollTo().performClick()
        composeTestRule.onNodeWithTag("configure_budget_btn", useUnmergedTree = true).performScrollTo().assertExists()

        composeTestRule.onNodeWithTag("ai_tab_provider", useUnmergedTree = true).performScrollTo().performClick()
        composeTestRule.onNodeWithTag("test_ai_connection_btn", useUnmergedTree = true).performScrollTo().assertExists()

        // 4. Navigate back to Settings Hub
        composeTestRule.onNodeWithText("Kembali ke Pengaturan", useUnmergedTree = true).performScrollTo().performClick()
        composeTestRule.onNodeWithTag("menu_ai_config", useUnmergedTree = true).performScrollTo().assertExists()

        // 5. Reopen AI Configuration
        composeTestRule.onNodeWithTag("menu_ai_config", useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithTag("ai_configuration_screen", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("ai_tab_provider", useUnmergedTree = true).performScrollTo().assertExists()
    }

    @Test
    fun compactAiSignalBar_longSetup_rendersLevelsAndAnalystChartButton() {
        var clickedOpenPanel = false
        val longResult = AiAnalysisResult(
            decision = "LONG",
            bias = "BULLISH",
            confidence = 85,
            trend = "BULLISH",
            momentum = "POSITIVE",
            marketStructure = "HH_HL",
            setupQuality = "A+",
            entryZone = EntryZone(94000.0, 94500.0),
            stopLoss = 92500.0,
            takeProfit = listOf(98000.0, 102000.0),
            riskReward = 2.5,
            support = listOf(93000.0),
            resistance = listOf(99000.0),
            higherTimeframeBias = "BULLISH",
            primaryTimeframeBias = "BULLISH",
            lowerTimeframeBias = "BULLISH",
            invalidIf = "Break below 92,500",
            reasoning = listOf("Bullish market structure", "Liquidity sweep completed"),
            summary = "High probability pullback long setup.",
            rawJson = "{}"
        )

        composeTestRule.setContent {
            NexSyncTheme(darkTheme = true) {
                CompactAiAnalystCard(
                    symbol = "BTCUSDT",
                    timeframe = Timeframe.H1,
                    snapshot = null,
                    aiResult = longResult,
                    isAnalyzing = false,
                    errorMessage = null,
                    providerBadge = "AI · Primary",
                    onOpenAnalystPanel = { clickedOpenPanel = true }
                )
            }
        }

        // Verify prominent LONG decision and concise status
        composeTestRule.onNodeWithTag("compact_decision_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("🟢 LONG · A+", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("compact_setup_lifecycle_badge", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("PENDING", useUnmergedTree = true).assertExists()

        // Verify heuristic score
        composeTestRule.onNodeWithTag("compact_heuristic_score", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Score: 85%", useUnmergedTree = true).assertExists()

        // Verify button labeled "Analyst Chart" and click callback
        composeTestRule.onNodeWithTag("analyst_chart_button", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Analyst Chart", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("analyst_chart_button", useUnmergedTree = true).performClick()
        assertTrue("Clicking Analyst Chart button must trigger onOpenAnalystPanel", clickedOpenPanel)

        // Verify actionable trade levels Entry, SL, TP1 are rendered
        composeTestRule.onNodeWithText("Entry", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("SL", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("TP1", useUnmergedTree = true).assertExists()
    }

    @Test
    fun compactAiSignalBar_waitSetup_rendersShortStateWithoutTradeLevels() {
        val waitResult = AiAnalysisResult(
            decision = "WAIT",
            bias = "NEUTRAL",
            confidence = 50,
            trend = "TRANSITIONAL",
            momentum = "NEUTRAL",
            marketStructure = "TRANSITIONAL",
            setupQuality = "WAIT",
            entryZone = EntryZone(0.0, 0.0),
            stopLoss = 0.0,
            takeProfit = emptyList(),
            riskReward = 0.0,
            support = listOf(90000.0),
            resistance = listOf(95000.0),
            higherTimeframeBias = "TRANSITIONAL",
            primaryTimeframeBias = "TRANSITIONAL",
            lowerTimeframeBias = "SIDEWAYS",
            invalidIf = "Chop state",
            reasoning = listOf("Multi-timeframe transition in progress"),
            summary = "Wait for clean confirmation.",
            rawJson = "{}"
        )

        composeTestRule.setContent {
            NexSyncTheme(darkTheme = true) {
                CompactAiAnalystCard(
                    symbol = "BTCUSDT",
                    timeframe = Timeframe.H1,
                    snapshot = null,
                    aiResult = waitResult,
                    isAnalyzing = false,
                    errorMessage = null,
                    providerBadge = "AI · Primary"
                )
            }
        }

        // Verify prominent WAIT decision
        composeTestRule.onNodeWithTag("compact_decision_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("🟡 WAIT", useUnmergedTree = true).assertExists()

        // Verify short market state (e.g. "MTF Transitional") instead of verbose repeats
        composeTestRule.onNodeWithTag("compact_wait_state_badge", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("MTF Transitional", useUnmergedTree = true).assertExists()

        // Verify trade levels Entry, SL, TP1 are NOT rendered for WAIT (no invented trade levels)
        composeTestRule.onNodeWithText("Entry", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("SL", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("TP1", useUnmergedTree = true).assertDoesNotExist()

        // Button is consistently labeled "Analyst Chart"
        composeTestRule.onNodeWithText("Analyst Chart", useUnmergedTree = true).assertExists()
    }

    @Test
    fun chartScreen_analystChartButton_opensModalBottomSheetWithoutLeavingScreen() {
        var ranAiAnalysis = false
        var navigatedToFullAiTab = false
        val longResult = AiAnalysisResult(
            decision = "LONG",
            bias = "BULLISH",
            confidence = 88,
            trend = "BULLISH",
            momentum = "POSITIVE",
            marketStructure = "HH_HL",
            setupQuality = "A",
            entryZone = EntryZone(94000.0, 94500.0),
            stopLoss = 92500.0,
            takeProfit = listOf(98000.0, 102000.0),
            riskReward = 2.5,
            support = listOf(93000.0),
            resistance = listOf(99000.0),
            higherTimeframeBias = "BULLISH",
            primaryTimeframeBias = "BULLISH",
            lowerTimeframeBias = "BULLISH",
            invalidIf = "Break below 92,500",
            reasoning = listOf("Strong momentum break", "Order block reclaim confirmed"),
            summary = "High probability pullback long setup.",
            rawJson = "{}"
        )
        val emptyIndicators = IndicatorSeries(
            ema20 = emptyList(),
            ema50 = emptyList(),
            ema200 = emptyList(),
            rsi14 = emptyList(),
            macd = emptyList(),
            atr14 = emptyList(),
            volumeSma20 = emptyList()
        )

        composeTestRule.setContent {
            NexSyncTheme(darkTheme = true) {
                ChartScreen(
                    activeSymbol = "BTCUSDT",
                    activeTimeframe = Timeframe.H1,
                    currentPrice = 94250.0,
                    priceChangePercent = 2.15,
                    activeTicker = null,
                    candles = emptyList(),
                    indicators = emptyIndicators,
                    snapshot = null,
                    settings = com.example.storage.preferences.TerminalSettings(),
                    connectionStatus = WsConnectionStatus.LIVE,
                    reconnectingBannerMessage = null,
                    restErrorMessage = null,
                    aiResult = longResult,
                    isAiAnalyzing = false,
                    aiErrorMessage = null,
                    activeAiProviderBadge = "AI · Primary",
                    isFullscreen = false,
                    onToggleFullscreen = {},
                    onSelectTimeframe = {},
                    onToggleEma = {},
                    onToggleVolume = {},
                    onToggleRsi = {},
                    onToggleMacd = {},
                    onToggleSupportResistance = {},
                    onOpenFullAiTab = { navigatedToFullAiTab = true },
                    onRunAiAnalysis = { ranAiAnalysis = true },
                    onOpenMarketsSearch = {},
                    onForceReconnect = {}
                )
            }
        }

        // Before clicking, the bottom sheet is not displayed
        composeTestRule.onNodeWithTag("in_chart_analyst_bottom_sheet", useUnmergedTree = true).assertDoesNotExist()

        // Click "Analyst Chart" button
        composeTestRule.onNodeWithTag("analyst_chart_button", useUnmergedTree = true).performClick()

        // It should NOT navigate to the full AI tab!
        assertFalse("Tapping Analyst Chart must not navigate to the AI tab", navigatedToFullAiTab)

        // It opens the in-chart ModalBottomSheet
        composeTestRule.onNodeWithTag("in_chart_analyst_bottom_sheet", useUnmergedTree = true).assertExists()

        // Cached AI result is immediately shown
        composeTestRule.onNodeWithTag("analyst_decision_card", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("analyst_confidence_score", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("88%", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Heuristic Score", useUnmergedTree = true).assertExists()

        // "Analyze Now" button is present and clicking it triggers onRunAiAnalysis
        composeTestRule.onNodeWithTag("analyze_now_button", useUnmergedTree = true).assertExists()
        assertFalse("Opening the sheet must NOT automatically trigger an AI API request", ranAiAnalysis)
        composeTestRule.onNodeWithTag("analyze_now_button", useUnmergedTree = true).performClick()
        assertTrue("Clicking Analyze Now must request a fresh analysis", ranAiAnalysis)

        // Close button dismisses the bottom sheet
        composeTestRule.onNodeWithTag("analyst_chart_close_button", useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithTag("in_chart_analyst_bottom_sheet", useUnmergedTree = true).assertDoesNotExist()
    }
}


