package com.example.trading

/**
 * Requirement #16: Architecture boundary prepared for future trading execution.
 *
 * In v1 ("NexSyncAI Lite"), NO real order execution, leverage, futures, or withdrawals
 * are enabled. Future versions can implement this interface (e.g., via signed exchange
 * connectors) and inject it into the ViewModel without modifying the market data,
 * chart, or AI analysis modules.
 */
interface OrderExecutionPort {
    val isExecutionEnabled: Boolean get() = false
}

object ReadOnlyAnalysisModePort : OrderExecutionPort {
    override val isExecutionEnabled: Boolean = false
}
