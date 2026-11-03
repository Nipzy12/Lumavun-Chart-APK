package com.example.ai.tracker

import com.example.ai.plan.StructuredTradePlan
import com.example.market.candle.Candle
import com.example.storage.watchlist.TerminalDao
import com.example.storage.watchlist.TrackedPendingSetupEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * LUMAVUN Chart — AI Insight V3.2: Persistent Repository for Tracked Pending Setups.
 *
 * Provides reactive Room flows, asynchronous persistence, duplicate prevention,
 * and deterministic tick/candle evaluations off the UI thread.
 */
class TrackedSetupRepository(
    private val dao: TerminalDao
) {
    /**
     * All tracked setups sorted by creation timestamp descending.
     */
    val allTrackedSetupsFlow: Flow<List<TrackedPendingSetup>> = dao.observeAllTrackedSetups()
        .map { list -> list.map { it.toDomain() } }
        .flowOn(Dispatchers.IO)

    /**
     * Active non-terminal tracked setups sorted by creation timestamp descending.
     */
    val activeTrackedSetupsFlow: Flow<List<TrackedPendingSetup>> = dao.observeActiveTrackedSetups()
        .map { list -> list.map { it.toDomain() } }
        .flowOn(Dispatchers.IO)

    /**
     * Observes tracked setups for a specific symbol.
     */
    fun observeTrackedSetupsForSymbol(symbol: String): Flow<List<TrackedPendingSetup>> {
        val clean = symbol.trim().uppercase(Locale.US)
        return dao.observeTrackedSetupsForSymbol(clean)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(Dispatchers.IO)
    }

    /**
     * Starts tracking a validated pending or actionable setup.
     * Prevents duplicate setup creation if an active non-terminal setup already tracks
     * the identical symbol, timeframe, strategy, and direction.
     */
    suspend fun startTracking(
        plan: StructuredTradePlan,
        currentPrice: Double,
        nowMs: Long = System.currentTimeMillis()
    ): Result<TrackedPendingSetup> = withContext(Dispatchers.IO) {
        if (plan.isWaitOrInvalid) {
            return@withContext Result.failure(
                IllegalArgumentException("Cannot track WAIT or INVALID trade setups. Capital preservation prioritized.")
            )
        }

        val cleanSymbol = plan.symbol.trim().uppercase(Locale.US)
        val cleanTf = plan.timeframe.trim()

        // 1. Duplicate check: Check existing active setups for this symbol and timeframe
        val existingActive = dao.getActiveTrackedSetupsForSymbol(cleanSymbol)
        val duplicate = existingActive.firstOrNull { existing ->
            existing.timeframe.equals(cleanTf, ignoreCase = true) &&
                existing.strategyId.equals(plan.strategyId, ignoreCase = true) &&
                existing.direction.equals(plan.direction.code, ignoreCase = true) &&
                !TrackedSetupStatus.fromString(existing.currentStatus).isTerminal
        }

        if (duplicate != null) {
            return@withContext Result.failure(
                IllegalStateException("Setup already actively tracked for $cleanSymbol ($cleanTf) under ${plan.strategyName}.")
            )
        }

        // 2. Instantiate domain model
        val setup = TrackedPendingSetup.fromTradePlan(plan, currentPrice, nowMs)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Trade plan missing mandatory entry, stop loss, or take profit levels.")
            )

        // 3. Persist entity
        val entity = TrackedPendingSetupEntity.fromDomain(setup)
        dao.upsertTrackedSetup(entity)

        // 4. Bounded storage: Prune terminal setups older than 14 days
        val twoWeeksAgo = nowMs - (14 * 24 * 60 * 60 * 1000L)
        dao.pruneTerminalSetupsOlderThan(twoWeeksAgo)

        Result.success(setup)
    }

    /**
     * Stops/cancels an active setup manually upon user request.
     */
    suspend fun stopTracking(
        setupId: String,
        reason: String = "Tracking stopped by user",
        nowMs: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        val entity = dao.getTrackedSetupById(setupId) ?: return@withContext false
        val currentDomain = entity.toDomain()
        if (currentDomain.isTerminal) return@withContext true

        val cancelled = PendingSetupEvaluationEngine.cancelSetup(currentDomain, reason, nowMs)
        dao.upsertTrackedSetup(TrackedPendingSetupEntity.fromDomain(cancelled))
        true
    }

    /**
     * Evaluates live price ticks for active setups matching [symbol].
     */
    suspend fun evaluatePriceTick(
        symbol: String,
        currentPrice: Double,
        nowMs: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        if (!currentPrice.isFinite() || currentPrice <= 0.0) return@withContext
        val cleanSymbol = symbol.trim().uppercase(Locale.US)

        val activeEntities = dao.getActiveTrackedSetupsForSymbol(cleanSymbol)
        if (activeEntities.isEmpty()) return@withContext

        val updatedEntities = mutableListOf<TrackedPendingSetupEntity>()
        for (entity in activeEntities) {
            val domain = entity.toDomain()
            val evaluated = PendingSetupEvaluationEngine.evaluateLiveTick(domain, currentPrice, nowMs)
            if (evaluated.currentStatus != domain.currentStatus ||
                evaluated.lastVerifiedPrice != domain.lastVerifiedPrice ||
                evaluated.transitionHistory.size != domain.transitionHistory.size
            ) {
                updatedEntities.add(TrackedPendingSetupEntity.fromDomain(evaluated))
            }
        }

        if (updatedEntities.isNotEmpty()) {
            dao.upsertTrackedSetups(updatedEntities)
        }
    }

    /**
     * Evaluates closed or forming candle updates for active setups matching [symbol] and [timeframe].
     */
    suspend fun evaluateCandle(
        symbol: String,
        timeframe: String,
        candle: Candle,
        nowMs: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        val cleanSymbol = symbol.trim().uppercase(Locale.US)
        val cleanTf = timeframe.trim()

        val activeEntities = dao.getActiveTrackedSetupsForSymbol(cleanSymbol)
            .filter { it.timeframe.equals(cleanTf, ignoreCase = true) }
        if (activeEntities.isEmpty()) return@withContext

        val updatedEntities = mutableListOf<TrackedPendingSetupEntity>()
        for (entity in activeEntities) {
            val domain = entity.toDomain()
            val evaluated = PendingSetupEvaluationEngine.evaluateCandle(domain, candle, nowMs)
            if (evaluated.currentStatus != domain.currentStatus ||
                evaluated.lastVerifiedPrice != domain.lastVerifiedPrice ||
                evaluated.transitionHistory.size != domain.transitionHistory.size
            ) {
                updatedEntities.add(TrackedPendingSetupEntity.fromDomain(evaluated))
            }
        }

        if (updatedEntities.isNotEmpty()) {
            dao.upsertTrackedSetups(updatedEntities)
        }
    }

    /**
     * Deletes a tracked setup entirely from persistence.
     */
    suspend fun deleteSetup(setupId: String) = withContext(Dispatchers.IO) {
        dao.deleteTrackedSetup(setupId)
    }

    /**
     * Retrieves all active setups once (e.g. for inspection or unit tests).
     */
    suspend fun getAllActiveTrackedSetupsOnce(): List<TrackedPendingSetup> = withContext(Dispatchers.IO) {
        dao.getAllActiveTrackedSetupsOnce().map { it.toDomain() }
    }
}
