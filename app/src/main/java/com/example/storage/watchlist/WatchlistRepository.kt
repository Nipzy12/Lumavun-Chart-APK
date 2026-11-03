package com.example.storage.watchlist

import com.example.ai.parser.AiAnalysisResult
import com.example.ai.prompt.AiPromptEngine
import com.example.analysis.journal.SignalForwardTester
import com.example.analysis.signal.ActiveTradeSetup
import com.example.analysis.signal.SetupLifecycleState
import com.example.analysis.supportResistance.SupportResistanceEngine.roundPriceCleanly
import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.market.candle.Candle
import com.example.market.candle.InstrumentCategory
import com.example.market.candle.MarketProvider
import com.example.market.candle.Ticker24h
import com.example.market.candle.Timeframe
import com.example.market.candle.inferInstrumentCategory
import com.example.market.candle.inferMarketProvider
import com.example.market.rest.ExchangeSymbolInfo
import com.example.market.symbols.SymbolRepository
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.util.Locale

sealed class AddPairOutcome {
    data class Added(val symbol: String) : AddPairOutcome()
    data class Duplicate(val symbol: String) : AddPairOutcome()
    data class Invalid(val message: String) : AddPairOutcome()
}

class WatchlistRepository(
    private val dao: TerminalDao
) {
    val watchlistFlow: Flow<List<WatchlistEntity>> = dao.observeWatchlist()
    val signalJournalFlow: Flow<List<SignalJournalEntity>> = dao.observeSignalJournal()
    val botPositionsFlow: Flow<List<BotPositionEntity>> = dao.observeBotPositions()

    suspend fun ensureDefaultWatchlist() {
        val current = dao.getWatchlistOnce()
        if (current.isEmpty()) {
            val defaults = SymbolRepository.DEFAULT_PAIRS.mapIndexed { index, sym ->
                WatchlistEntity(
                    symbol = sym,
                    sortOrder = index,
                    category = InstrumentCategory.CRYPTO.code,
                    provider = MarketProvider.BINANCE.code
                )
            }
            dao.upsertWatchlistItems(defaults)
        }
    }

    suspend fun addPair(
        symbol: String,
        initialTicker: Ticker24h? = null,
        symbolInfo: ExchangeSymbolInfo? = null
    ): AddPairOutcome {
        val clean = if (symbolInfo != null) {
            symbolInfo.symbol.trim().uppercase(Locale.US)
        } else {
            val usdt = SymbolRepository.normalizeUsdtSymbolInput(symbol)
            usdt.ifEmpty { SymbolRepository.normalizeMultiMarketSymbolInput(symbol) }
        }
        if (clean.isEmpty()) {
            return AddPairOutcome.Invalid("Symbol '$symbol' tidak valid.")
        }
        val existing = dao.getWatchlistOnce()
        if (existing.any { it.symbol.equals(clean, ignoreCase = true) }) {
            return AddPairOutcome.Duplicate(clean)
        }
        val category = symbolInfo?.category ?: initialTicker?.category ?: inferInstrumentCategory(clean)
        val provider = symbolInfo?.provider ?: initialTicker?.provider ?: inferMarketProvider(clean, category)
        val displayName = symbolInfo?.displayName?.ifBlank { initialTicker?.displayName.orEmpty() }
            ?: initialTicker?.displayName.orEmpty()
        val nextOrder = (dao.getMaxSortOrder() ?: -1) + 1
        dao.upsertWatchlistItem(
            WatchlistEntity(
                symbol = clean,
                sortOrder = nextOrder,
                lastPrice = initialTicker?.lastPrice ?: 0.0,
                priceChangePercent = initialTicker?.priceChangePercent ?: 0.0,
                highPrice = initialTicker?.highPrice ?: 0.0,
                lowPrice = initialTicker?.lowPrice ?: 0.0,
                volume = initialTicker?.quoteVolume ?: 0.0,
                updatedAt = initialTicker?.updatedAt ?: System.currentTimeMillis(),
                category = category.code,
                provider = provider.code,
                displayName = displayName
            )
        )
        return AddPairOutcome.Added(clean)
    }

    suspend fun removePair(symbol: String) {
        val clean = symbol.trim().uppercase(Locale.US)
        dao.deleteWatchlistSymbol(clean)
        normalizeSortOrders()
    }

    suspend fun movePair(symbol: String, directionDelta: Int) {
        val list = dao.getWatchlistOnce().toMutableList()
        val index = list.indexOfFirst { it.symbol.equals(symbol, ignoreCase = true) }
        if (index == -1) return
        val targetIndex = (index + directionDelta).coerceIn(0, list.lastIndex)
        if (targetIndex == index) return

        val item = list.removeAt(index)
        list.add(targetIndex, item)
        val reordered = list.mapIndexed { idx, entity ->
            entity.copy(sortOrder = idx)
        }
        dao.upsertWatchlistItems(reordered)
    }

    suspend fun updateTicker(ticker: Ticker24h) {
        val usdtVolume = if (ticker.quoteVolume > 0.0) ticker.quoteVolume else ticker.volume
        dao.updateWatchlistTicker(
            symbol = ticker.symbol,
            lastPrice = ticker.lastPrice,
            changePct = ticker.priceChangePercent,
            high = ticker.highPrice,
            low = ticker.lowPrice,
            volume = usdtVolume,
            updatedAt = ticker.updatedAt
        )
    }

    suspend fun updateTickersBatch(tickers: List<Ticker24h>) {
        for (t in tickers) {
            updateTicker(t)
        }
    }

    suspend fun cacheCandles(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        provider: MarketProvider = MarketProvider.BINANCE
    ) {
        if (candles.isEmpty()) return
        val clean = symbol.uppercase(Locale.US)
        val cacheKey = if (provider == MarketProvider.EXNESS && !clean.startsWith("EXNESS:")) "EXNESS:$clean" else clean
        val entities = candles.takeLast(350).map { c ->
            CachedCandleEntity(
                symbol = cacheKey,
                timeframe = timeframe.binanceInterval,
                openTime = c.openTime,
                open = c.open,
                high = c.high,
                low = c.low,
                close = c.close,
                volume = c.volume,
                closeTime = c.closeTime,
                isClosed = c.isClosed
            )
        }
        dao.clearCachedCandlesForPair(cacheKey, timeframe.binanceInterval)
        dao.insertCachedCandles(entities)
    }

    suspend fun getOfflineCachedCandles(
        symbol: String,
        timeframe: Timeframe,
        provider: MarketProvider = MarketProvider.BINANCE
    ): List<Candle> {
        val clean = symbol.uppercase(Locale.US)
        val cacheKey = if (provider == MarketProvider.EXNESS && !clean.startsWith("EXNESS:")) "EXNESS:$clean" else clean
        return dao.getCachedCandles(cacheKey, timeframe.binanceInterval).map { e ->
            Candle(
                openTime = e.openTime,
                open = e.open,
                high = e.high,
                low = e.low,
                close = e.close,
                volume = e.volume,
                closeTime = e.closeTime,
                isClosed = e.isClosed
            )
        }
    }

    suspend fun saveAiAnalysisCache(symbol: String, timeframe: Timeframe, rawJson: String) {
        dao.saveCachedAiAnalysis(
            CachedAiAnalysisEntity(
                symbol = symbol.uppercase(Locale.US),
                timeframe = timeframe.binanceInterval,
                rawJson = rawJson,
                analyzedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun getCachedAiAnalysis(symbol: String, timeframe: Timeframe): CachedAiAnalysisEntity? {
        return dao.getCachedAiAnalysis(symbol.uppercase(Locale.US), timeframe.binanceInterval)
    }

    /**
     * Requirement #1, #3, #6 & #15: Saves an immutable AI Signal Journal entry.
     * Prevents duplicate signals for the same setup (`setupSignature` within active window).
     */
    suspend fun recordSignalToJournal(
        snapshot: MarketTechnicalSnapshot,
        result: AiAnalysisResult,
        botAction: String = "NONE",
        source: String = "AI",
        lifecycleState: String = "",
        statusReason: String = ""
    ): Long {
        val cleanSymbol = snapshot.symbol.uppercase(Locale.US)
        val tfLabel = snapshot.timeframe.label
        val cleanSource = source.trim().uppercase(Locale.US).ifEmpty { "AI" }

        // Requirement #1: Prevent duplicate signals for the same setup within the same source ("AI" or "BASELINE")
        if (!result.isWait && result.setupSignature.isNotBlank()) {
            val activeForPair = dao.getTrackableSignalsForPair(cleanSymbol, tfLabel)
            val duplicate = activeForPair.firstOrNull { existing ->
                existing.source.equals(cleanSource, ignoreCase = true) && (
                    existing.setupSignature == result.setupSignature ||
                        (existing.decision.equals(result.decision, ignoreCase = true) &&
                            kotlin.math.abs(existing.stopLoss - result.stopLoss) <= existing.stopLoss * 0.0025)
                    )
            }
            if (duplicate != null) {
                return duplicate.id
            }
        }

        val htfLabel = snapshot.higherContext?.timeframe?.label
            ?: snapshot.timeframe.higherTimeframe()?.label
            ?: "--"
        val ltfLabel = snapshot.lowerContext?.timeframe?.label
            ?: snapshot.timeframe.lowerTimeframe()?.label
            ?: "--"

        val marketConditions = buildString {
            append("${htfLabel}:${result.higherTimeframeBias}")
            append(" | ${snapshot.timeframe.label}:${result.primaryTimeframeBias} (${result.marketStructure})")
            append(" | ${ltfLabel}:${result.lowerTimeframeBias}")
            append(" | Regime:${result.marketRegime}")
        }

        val immutableSnapshotJson = JSONObject().apply {
            put("recordedAt", result.analyzedAt)
            put("candleOpenTime", snapshot.latestCandle.openTime)
            put("candleCloseTime", snapshot.latestCandle.closeTime)
            put("marketPayload", JSONObject(AiPromptEngine.buildStructuredMarketPayload(snapshot)))
            put("aiOutput", JSONObject(result.rawJson))
        }.toString()

        val initialStatus = if (result.isWait) "WAIT" else "ACTIVE"
        val resolvedLifecycle = lifecycleState.ifBlank {
            if (result.isWait) SetupLifecycleState.SCANNING.code else SetupLifecycleState.ACTIVE.code
        }
        val resolvedReason = statusReason.ifBlank {
            if (result.isWait) {
                result.summary.ifBlank { "WAIT logged — no trade setup" }
            } else {
                "Active ${result.decision} setup (Grade ${result.setupQualityGrade}) waiting for price to reach ${roundPriceCleanly(result.entryZone.min)} - ${roundPriceCleanly(result.entryZone.max)}"
            }
        }
        val entryMid = roundPriceCleanly((result.entryZone.min + result.entryZone.max) / 2.0)

        val smcSummaryText = listOfNotNull(
            result.latestBosSummary.takeIf { it.isNotBlank() && !it.startsWith("None", ignoreCase = true) },
            result.latestChochSummary.takeIf { it.isNotBlank() && !it.startsWith("None", ignoreCase = true) },
            result.equalHighsLowsSummary.takeIf { it.isNotBlank() && !it.startsWith("None", ignoreCase = true) },
            result.orderBlockCandidatesSummary.takeIf { it.isNotBlank() && !it.startsWith("None", ignoreCase = true) }
        ).joinToString(" | ").ifBlank {
            snapshot.marketStructure.latestChoch?.description
                ?: snapshot.marketStructure.latestBos?.description
                ?: snapshot.marketStructure.state.code
        }

        val entity = SignalJournalEntity(
            timestamp = result.analyzedAt,
            candleOpenTimeAtSignal = snapshot.latestCandle.openTime,
            candleCloseTimeAtSignal = snapshot.latestCandle.closeTime,
            symbol = cleanSymbol,
            primaryTimeframe = tfLabel,
            higherTimeframe = htfLabel,
            lowerTimeframe = ltfLabel,
            priceAtSignal = snapshot.currentPrice,
            decision = result.decision,
            confidence = result.confidence,
            setupQuality = result.setupQualityGrade,
            entryPrice = if (result.isWait) 0.0 else entryMid,
            entryMin = result.entryZone.min,
            entryMax = result.entryZone.max,
            stopLoss = result.stopLoss,
            tp1 = result.takeProfit.getOrElse(0) { 0.0 },
            tp2 = result.takeProfit.getOrElse(1) { 0.0 },
            tp3 = result.takeProfit.getOrElse(2) { 0.0 },
            riskReward = result.riskReward,
            marketStructure = result.marketStructure,
            trend = result.trend,
            momentum = result.momentum,
            marketConditions = marketConditions,
            aiProviderUsed = result.providerBadge,
            aiModelUsed = result.modelUsed,
            originalSnapshotJson = immutableSnapshotJson,
            marketRegime = result.marketRegime,
            mtfConfirmation = result.mtfConfirmation.ifBlank { snapshot.mtfConfirmation.summary },
            entryTrigger = result.effectiveEntryTrigger,
            invalidation = result.invalidIf,
            expiryAt = result.expiryAt,
            setupSignature = result.setupSignature,
            botAction = botAction,
            status = initialStatus,
            highestTpReached = 0,
            realizedR = 0.0,
            resolvedAt = if (result.isWait) result.analyzedAt else null,
            exitModelVersion = 2,
            source = cleanSource,
            lifecycleState = resolvedLifecycle,
            statusReason = resolvedReason,
            aiSystemMode = result.aiSystemMode,
            aiExecutionMode = result.aiExecutionMode,
            tradingStyleProfile = result.tradingStyleProfile,
            entryOrderType = result.effectiveEntryOrderType,
            selectedTimeframesSummary = result.selectedTimeframesSummary.ifBlank {
                "Primary: $tfLabel · Higher: $htfLabel · Lower: $ltfLabel"
            },
            setupDecisionState = result.setupDecisionState,
            tradingSessionLabel = result.tradingSessionBadge.ifBlank {
                snapshot.smcAnalysis?.sessionSnapshot?.primarySession?.displayLabel.orEmpty()
            },
            liquiditySweepState = result.liquiditySweepState,
            liquiditySweepSummary = result.liquiditySweepSummary.ifBlank {
                snapshot.smcAnalysis?.latestActiveSweep?.summaryBadge.orEmpty()
            },
            smcStructureSummary = smcSummaryText,
            spreadStatusSummary = result.spreadValidationSummary.ifBlank {
                snapshot.smcAnalysis?.spreadVolatilityValidation?.spreadStatusSummary.orEmpty()
            },
            eventRiskSummary = result.eventRiskSummary
        )

        return dao.insertSignalJournal(entity)
    }

    suspend fun updateSignalBotAction(signalId: Long, botAction: String) {
        if (signalId <= 0L) return
        dao.updateSignalBotAction(signalId, botAction)
    }

    suspend fun updateSignalSetupLifecycle(
        setupId: Long,
        lifecycleState: SetupLifecycleState,
        forwardStatus: com.example.analysis.signal.SignalLifecycleStatus,
        statusReason: String,
        highestTpReached: Int = 0,
        realizedR: Double = 0.0
    ) {
        if (setupId <= 0L) return
        val resolvedAt = if (lifecycleState.isTerminal || forwardStatus.isTerminal) System.currentTimeMillis() else null
        dao.updateSignalLifecycleAndOutcome(
            id = setupId,
            status = forwardStatus.code,
            lifecycleState = lifecycleState.code,
            statusReason = statusReason,
            highestTpReached = highestTpReached,
            realizedR = realizedR,
            resolvedAt = resolvedAt
        )
    }

    suspend fun updateActiveSetupLifecycleInJournal(setup: ActiveTradeSetup) {
        if (setup.setupId <= 0L) return
        val resolvedAt = if (setup.lifecycleState.isTerminal) setup.updatedAt else null
        dao.updateSignalLifecycleAndOutcome(
            id = setup.setupId,
            status = setup.forwardStatus.code,
            lifecycleState = setup.lifecycleState.code,
            statusReason = setup.statusReason,
            highestTpReached = setup.highestTpReached,
            realizedR = setup.realizedR,
            resolvedAt = resolvedAt
        )
        if (setup.botAction.isNotBlank()) {
            dao.updateSignalBotAction(setup.setupId, setup.botAction)
        }
    }

    suspend fun getLatestActiveSetupForPair(symbol: String, timeframe: Timeframe): SignalJournalEntity? {
        return getLatestActiveAiJournalSignal(symbol, timeframe)
    }

    suspend fun getLatestActiveAiJournalSignal(symbol: String, timeframe: Timeframe): SignalJournalEntity? {
        val clean = symbol.uppercase(Locale.US)
        return dao.getTrackableSignalsForPair(clean, timeframe.label)
            .filter { it.source.ifBlank { "AI" }.equals("AI", ignoreCase = true) }
            .maxByOrNull { it.timestamp }
    }

    /**
     * Requirement #4 & #8: Evaluates all active signals for (symbol, timeframe) against subsequent candles
     * without look-ahead bias and updates ONLY their forward-test status columns.
     */
    suspend fun evaluateActiveSignalsForPair(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>
    ): List<Pair<SignalJournalEntity, String>> {
        if (candles.isEmpty()) return emptyList()
        val clean = symbol.uppercase(Locale.US)
        val activeSignals = dao.getTrackableSignalsForPair(clean, timeframe.label)
        if (activeSignals.isEmpty()) return emptyList()

        val transitions = ArrayList<Pair<SignalJournalEntity, String>>()
        for (sig in activeSignals) {
            val update = SignalForwardTester.evaluateSignalAgainstCandles(sig, candles)
            if (update != null) {
                val mappedLifecycle = SetupLifecycleState.fromCode(update.status).code
                val reason = buildReasonForForwardStatus(sig, update.status, update.highestTpReached)
                dao.updateSignalLifecycleAndOutcome(
                    id = sig.id,
                    status = update.status,
                    lifecycleState = mappedLifecycle,
                    statusReason = reason,
                    highestTpReached = update.highestTpReached,
                    realizedR = update.realizedR,
                    resolvedAt = update.resolvedAt
                )
                if (update.status != sig.status) {
                    transitions.add(sig to update.status)
                }
            }
        }
        return transitions
    }

    /**
     * Requirement #1 & #3: Evaluates active signals against live price ticks continuously.
     */
    suspend fun evaluateActiveSignalsOnLiveTick(
        symbol: String,
        timeframe: Timeframe,
        livePrice: Double,
        nowMs: Long = System.currentTimeMillis()
    ): List<Pair<SignalJournalEntity, String>> {
        if (livePrice <= 0.0) return emptyList()
        val clean = symbol.uppercase(Locale.US)
        val activeSignals = dao.getTrackableSignalsForPair(clean, timeframe.label)
        if (activeSignals.isEmpty()) return emptyList()

        val transitions = ArrayList<Pair<SignalJournalEntity, String>>()
        for (sig in activeSignals) {
            val update = SignalForwardTester.evaluateSignalAgainstLivePrice(sig, livePrice, nowMs)
            if (update != null) {
                val mappedLifecycle = SetupLifecycleState.fromCode(update.status).code
                val reason = buildReasonForForwardStatus(sig, update.status, update.highestTpReached, livePrice)
                dao.updateSignalLifecycleAndOutcome(
                    id = sig.id,
                    status = update.status,
                    lifecycleState = mappedLifecycle,
                    statusReason = reason,
                    highestTpReached = update.highestTpReached,
                    realizedR = update.realizedR,
                    resolvedAt = update.resolvedAt
                )
                if (update.status != sig.status) {
                    transitions.add(sig to update.status)
                }
            }
        }
        return transitions
    }

    private fun buildReasonForForwardStatus(
        sig: SignalJournalEntity,
        status: String,
        highestTpReached: Int,
        livePrice: Double = 0.0
    ): String {
        val priceNote = if (livePrice > 0.0) " @ ${roundPriceCleanly(livePrice)}" else ""
        return when (status.uppercase(Locale.US)) {
            "ENTRY_TRIGGERED", "ENTERED" -> "Entry Zone (${roundPriceCleanly(sig.entryMin)} - ${roundPriceCleanly(sig.entryMax)}) triggered$priceNote"
            "TP1_HIT" -> "TP1 (${roundPriceCleanly(sig.tp1)}) reached$priceNote · Stop moved to Break-Even"
            "TP2_HIT" -> "TP2 (${roundPriceCleanly(sig.tp2)}) reached$priceNote · Trailing toward TP3"
            "TP3_HIT" -> "Completed: Final target TP3 (${roundPriceCleanly(sig.tp3)}) reached$priceNote"
            "SL_HIT" -> "Completed: Stop Loss (${roundPriceCleanly(sig.stopLoss)}) hit after entry$priceNote"
            "CLOSED" -> if (highestTpReached >= 1) {
                "Completed: Remaining position closed at Break-Even after TP$highestTpReached$priceNote"
            } else {
                "Completed: Closed by time-stop or manual exit$priceNote"
            }
            "INVALIDATED" -> "Invalidated: Stop Loss or TP1 reached before entry trigger$priceNote"
            "EXPIRED" -> "Expired: Price did not reach Entry Zone before setup expiry"
            "CANCELLED" -> sig.statusReason.ifBlank { "Cancelled due to opposing structure or risk rule" }
            "AMBIGUOUS" -> "Ambiguous intrabar ordering (both entry/SL/TP touched inside same bar)"
            else -> sig.statusReason
        }
    }

    // --- Bot Trading (Simulasi) Persistence ---
    suspend fun getAllBotPositionsOnce(): List<BotPositionEntity> {
        return dao.getAllBotPositionsOnce()
    }

    suspend fun upsertBotPosition(entity: BotPositionEntity) {
        dao.upsertBotPosition(entity)
    }

    suspend fun upsertBotPositions(entities: List<BotPositionEntity>) {
        if (entities.isEmpty()) return
        dao.upsertBotPositions(entities)
    }

    suspend fun clearAllBotPositions() {
        dao.clearAllBotPositions()
    }

    private suspend fun normalizeSortOrders() {
        val list = dao.getWatchlistOnce()
        val updated = list.mapIndexed { idx, item -> item.copy(sortOrder = idx) }
        if (updated.isNotEmpty()) {
            dao.upsertWatchlistItems(updated)
        }
    }
}
