package com.example.market.exness

import com.example.ai.parser.AiAnalysisResult
import com.example.ai.parser.EntryZone
import com.example.market.candle.InstrumentCategory
import com.example.market.candle.MarketProvider
import com.example.market.provider.ProviderConnectionStatus
import com.example.market.status.MarketStatusService
import com.example.market.symbols.SymbolRepository
import com.example.simulation.paper.BotRunState
import com.example.simulation.paper.PaperTradingAccountState
import com.example.simulation.paper.PaperTradingConfig
import com.example.simulation.paper.PaperTradingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExnessMarketDataProviderTest {

    @Test
    fun exnessCatalog_retrievesInstrumentAndFormatsPricesCorrectly() {
        val eurusd = ExnessCatalog.findInstrument("EURUSD")
        assertNotNull("EURUSD should exist in catalog", eurusd)
        assertEquals(5, eurusd!!.priceDigits)
        assertEquals(InstrumentCategory.FOREX, eurusd.category)
        assertEquals("1.08543", eurusd.formatPrice(1.0854321))

        // Namespaced symbol lookup (EXNESS:EURUSD)
        val namespaced = ExnessCatalog.findInstrument("EXNESS:EURUSD")
        assertNotNull(namespaced)
        assertEquals("EURUSD", namespaced!!.symbol)

        val usdjpy = ExnessCatalog.findInstrument("USDJPY")
        assertNotNull(usdjpy)
        assertEquals(3, usdjpy!!.priceDigits)
        assertEquals("152.345", usdjpy.formatPrice(152.3454))

        val xauusd = ExnessCatalog.findInstrument("XAUUSD")
        assertNotNull(xauusd)
        assertEquals(2, xauusd!!.priceDigits)
        assertEquals(InstrumentCategory.COMMODITIES, xauusd.category)
        assertEquals("2650.50", xauusd.formatPrice(2650.50))

        val xagusd = ExnessCatalog.findInstrument("XAGUSD")
        assertNotNull(xagusd)
        assertEquals(3, xagusd!!.priceDigits)
        assertEquals("31.245", xagusd.formatPrice(31.245))

        val btcusd = ExnessCatalog.findInstrument("BTCUSD")
        assertNotNull(btcusd)
        assertEquals(2, btcusd!!.priceDigits)
        assertEquals(InstrumentCategory.CRYPTO, btcusd.category)
        assertEquals("68500.25", btcusd.formatPrice(68500.25))

        val us30 = ExnessCatalog.findInstrument("US30")
        assertNotNull(us30)
        assertEquals(2, us30!!.priceDigits)
        assertEquals(InstrumentCategory.INDICES, us30.category)
        assertEquals("42500.10", us30.formatPrice(42500.10))
    }

    @Test
    fun exnessMarketDataProvider_initialConnectionStatusAndUnconfiguredState() {
        val defaultConfig = ExnessConfig()
        assertFalse("Default config should not be configured", defaultConfig.isConfigured)

        val provider = ExnessMarketDataProvider(defaultConfig)
        assertEquals(MarketProvider.EXNESS, provider.provider)
        assertTrue(
            "Initial status should be Unconfigured",
            provider.connectionStatus.value is ProviderConnectionStatus.Unconfigured
        )
        assertTrue(
            "Unconfigured status should be considered stale or offline",
            provider.connectionStatus.value.isStaleOrOffline
        )
    }

    @Test
    fun exnessMarketDataProvider_connectionStatusProperties() {
        val connected = ProviderConnectionStatus.Connected
        assertFalse(connected.isStaleOrOffline)
        assertEquals("CONNECTED", connected.displayLabel)

        val stale = ProviderConnectionStatus.Stale
        assertTrue(stale.isStaleOrOffline)
        assertEquals("STALE (>15s)", stale.displayLabel)

        val disconnected = ProviderConnectionStatus.Disconnected("Connection lost")
        assertTrue(disconnected.isStaleOrOffline)
        assertEquals("DISCONNECTED", disconnected.displayLabel)

        val error = ProviderConnectionStatus.Error("Gateway timeout")
        assertTrue(error.isStaleOrOffline)
        assertEquals("ERROR", error.displayLabel)

        val unconfigured = ProviderConnectionStatus.Unconfigured("Gateway Proxy URL not configured")
        assertTrue(unconfigured.isStaleOrOffline)
        assertEquals("UNCONFIGURED", unconfigured.displayLabel)
    }

    @Test
    fun marketStatusService_exnessStaleThresholdIs15Seconds() {
        val threshold = MarketStatusService.staleThresholdFor(
            provider = MarketProvider.EXNESS,
            category = InstrumentCategory.FOREX
        )
        assertEquals(15_000L, threshold)
    }

    @Test
    fun botSimulation_pausesWhenExnessDataIsStaleOrDisconnected() {
        val botConfig = PaperTradingConfig(
            startingBalanceUsd = 10_000.0,
            autoExecuteConfirmedSignals = true
        )
        val botRunning = PaperTradingAccountState(
            config = botConfig,
            balanceUsd = 10_000.0,
            equityUsd = 10_000.0,
            botRunState = BotRunState.RUNNING
        )

        val validSignal = AiAnalysisResult(
            decision = "LONG",
            bias = "BULLISH",
            trend = "UPTREND",
            marketStructure = "HH_HL",
            setupQuality = "A+",
            entryZone = EntryZone(1.08500, 1.08550),
            stopLoss = 1.08200,
            takeProfit = listOf(1.09000, 1.09500, 1.10000),
            riskReward = 2.0,
            confidence = 85,
            support = listOf(1.08200),
            resistance = listOf(1.09000),
            higherTimeframeBias = "BULLISH",
            primaryTimeframeBias = "BULLISH",
            lowerTimeframeBias = "BULLISH",
            momentum = "STRONG",
            invalidIf = "Below 1.08200",
            reasoning = listOf("Aligned"),
            summary = "Aligned",
            rawJson = "{}"
        )

        // When Exness market data is stale or disconnected
        val outcome = PaperTradingEngine.evaluateAutoEntry(
            state = botRunning,
            symbol = "EURUSD",
            timeframe = "15m",
            aiResult = validSignal,
            liveMarketPrice = 1.08520,
            signalId = 2001L,
            marketDataBlocked = true,
            marketDataReason = "Exness market data is disconnected or stale"
        )

        assertEquals("SKIPPED_MARKET_STATUS", outcome.botActionCode)
        assertTrue(
            outcome.statusDetail.contains("Exness market data is disconnected or stale")
        )
        assertTrue(
            outcome.updatedState.openPositions.isEmpty()
        )
    }

    @Test
    fun symbolRepository_referenceSeedsDoNotClaimExnessAvailability() {
        val seeds = SymbolRepository.ALL_SEED_SYMBOLS
        assertTrue("Reference examples must not advertise Exness availability", seeds.none {
            it.provider == MarketProvider.EXNESS
        })
        assertTrue("Binance examples remain available for discovery", seeds.any { it.provider == MarketProvider.BINANCE })
    }

    @Test
    fun exnessProvider_withoutGatewayDoesNotClaimLiveSymbols() = kotlinx.coroutines.runBlocking {
        val provider = ExnessMarketDataProvider(ExnessConfig())
        val result = provider.getSymbols()
        assertTrue("Unconfigured Exness must fail closed", result is com.example.market.rest.MarketApiResult.Error)
        provider.dispose()
    }

}
