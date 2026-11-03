package com.example.market.provider

import com.example.market.candle.Candle
import com.example.market.candle.MarketProvider
import com.example.market.candle.Ticker24h
import com.example.market.candle.Timeframe
import com.example.market.rest.BinanceRestClient
import com.example.market.rest.ExchangeSymbolInfo
import com.example.market.rest.MarketApiResult
import com.example.market.websocket.BinanceWebSocketManager
import com.example.market.websocket.KlineStreamEvent
import com.example.market.websocket.WsConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Binance Market Data Provider implementation.
 * Adapts BinanceRestClient and BinanceWebSocketManager into the unified MarketDataProvider interface.
 */
class BinanceMarketDataProvider(
    val restClient: BinanceRestClient = BinanceRestClient(),
    val wsManager: BinanceWebSocketManager = BinanceWebSocketManager()
) : MarketDataProvider {

    override val provider: MarketProvider = MarketProvider.BINANCE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionStatus = MutableStateFlow<ProviderConnectionStatus>(ProviderConnectionStatus.Connecting)
    override val connectionStatus: StateFlow<ProviderConnectionStatus> = _connectionStatus.asStateFlow()

    override val klineEvents: SharedFlow<KlineStreamEvent> = wsManager.klineEvents
    override val tickerEvents: SharedFlow<Ticker24h> = wsManager.tickerEvents

    init {
        scope.launch {
            wsManager.connectionStatus.collect { wsStatus ->
                _connectionStatus.value = when (wsStatus) {
                    WsConnectionStatus.LIVE -> ProviderConnectionStatus.Connected
                    WsConnectionStatus.RECONNECTING -> ProviderConnectionStatus.Connecting
                    WsConnectionStatus.DISCONNECTED -> ProviderConnectionStatus.Disconnected("Binance WebSocket Terputus")
                }
            }
        }
    }

    override suspend fun getSymbols(): MarketApiResult<List<ExchangeSymbolInfo>> {
        return restClient.fetchExchangeSymbols()
    }

    override suspend fun getHistoricalCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int
    ): MarketApiResult<List<Candle>> {
        return restClient.fetchHistoricalCandles(symbol, timeframe, limit)
    }

    override suspend fun getLatestQuote(symbol: String): MarketApiResult<Ticker24h> {
        return restClient.fetchSingleTicker(symbol)
    }

    override fun subscribe(
        symbol: String,
        timeframe: Timeframe,
        watchlistSymbols: List<String>
    ) {
        wsManager.subscribe(symbol, timeframe, watchlistSymbols)
    }

    override fun unsubscribe() {
        wsManager.shutdown()
        _connectionStatus.value = ProviderConnectionStatus.Disconnected("Unsubscribed")
    }

    override fun dispose() {
        wsManager.shutdown()
    }
}
