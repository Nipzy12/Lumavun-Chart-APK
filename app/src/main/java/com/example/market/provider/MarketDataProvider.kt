package com.example.market.provider

import com.example.market.candle.Candle
import com.example.market.candle.MarketProvider
import com.example.market.candle.Ticker24h
import com.example.market.candle.Timeframe
import com.example.market.rest.ExchangeSymbolInfo
import com.example.market.rest.MarketApiResult
import com.example.market.websocket.KlineStreamEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

sealed class ProviderConnectionStatus {
    object Connected : ProviderConnectionStatus()
    object Connecting : ProviderConnectionStatus()
    object Stale : ProviderConnectionStatus()
    data class Disconnected(val reason: String = "") : ProviderConnectionStatus()
    data class Unconfigured(val message: String) : ProviderConnectionStatus()
    data class Error(val message: String) : ProviderConnectionStatus()

    val isLive: Boolean get() = this is Connected
    val isStaleOrOffline: Boolean get() = this is Stale || this is Disconnected || this is Unconfigured || this is Error
    val displayLabel: String
        get() = when (this) {
            is Connected -> "CONNECTED"
            is Connecting -> "CONNECTING"
            is Stale -> "STALE (>15s)"
            is Disconnected -> "DISCONNECTED"
            is Unconfigured -> "UNCONFIGURED"
            is Error -> "ERROR"
        }
}

/**
 * Reusable market data provider interface supporting multiple market backends
 * (Binance Crypto Spot/Futures, Exness Forex/Metals/CFD, etc.).
 * Strictly market-data and simulation only.
 */
interface MarketDataProvider {
    val provider: MarketProvider
    val connectionStatus: StateFlow<ProviderConnectionStatus>
    val klineEvents: SharedFlow<KlineStreamEvent>
    val tickerEvents: SharedFlow<Ticker24h>

    suspend fun getSymbols(): MarketApiResult<List<ExchangeSymbolInfo>>
    suspend fun getHistoricalCandles(symbol: String, timeframe: Timeframe, limit: Int = 200): MarketApiResult<List<Candle>>
    suspend fun getLatestQuote(symbol: String): MarketApiResult<Ticker24h>

    fun subscribe(symbol: String, timeframe: Timeframe, watchlistSymbols: List<String> = emptyList())
    fun unsubscribe()
    fun dispose()
}
