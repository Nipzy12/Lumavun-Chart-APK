package com.example.storage.watchlist

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.ai.plan.TradeDirection
import com.example.ai.plan.TradeOrderType
import com.example.ai.tracker.SetupTransitionRecord
import com.example.ai.tracker.TrackedPendingSetup
import com.example.ai.tracker.TrackedSetupStatus
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "watchlist_items")
data class WatchlistEntity(
    @PrimaryKey val symbol: String,
    val sortOrder: Int,
    val lastPrice: Double = 0.0,
    val priceChangePercent: Double = 0.0,
    val highPrice: Double = 0.0,
    val lowPrice: Double = 0.0,
    val volume: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis(),
    val category: String = "CRYPTO",
    val provider: String = "BINANCE",
    val displayName: String = ""
)

@Entity(tableName = "cached_symbols")
data class CachedSymbolEntity(
    @PrimaryKey val symbol: String,
    val baseAsset: String,
    val quoteAsset: String,
    val updatedAt: Long,
    val category: String = "CRYPTO",
    val provider: String = "BINANCE",
    val displayName: String = ""
)

@Entity(tableName = "cached_candles", primaryKeys = ["symbol", "timeframe", "openTime"])
data class CachedCandleEntity(
    val symbol: String,
    val timeframe: String,
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long,
    val isClosed: Boolean
)

@Entity(tableName = "cached_ai_analysis", primaryKeys = ["symbol", "timeframe"])
data class CachedAiAnalysisEntity(
    val symbol: String,
    val timeframe: String,
    val rawJson: String,
    val analyzedAt: Long
)

/**
 * Requirement #6, #7, #8, #15: Immutable AI Signal Journal Entity.
 * Associated with the exact confirmed candle that generated the signal (`candleOpenTimeAtSignal`, `candleCloseTimeAtSignal`).
 * Only the lifecycle tracking columns (`status`, `highestTpReached`, `realizedR`, `resolvedAt`)
 * are updated as subsequent candles arrive; original signal fields are NEVER overwritten.
 */
@Entity(tableName = "signal_journal")
data class SignalJournalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val candleOpenTimeAtSignal: Long,
    val candleCloseTimeAtSignal: Long = 0L,
    val symbol: String,
    val primaryTimeframe: String,
    val higherTimeframe: String,
    val lowerTimeframe: String,
    val priceAtSignal: Double,
    val decision: String,               // "LONG", "SHORT", "WAIT"
    val confidence: Int,
    val setupQuality: String,           // "A+", "A", "B", "WAIT"
    val entryPrice: Double = 0.0,       // Midpoint / representative entry price
    val entryMin: Double,
    val entryMax: Double,
    val stopLoss: Double,
    val tp1: Double,
    val tp2: Double,
    val tp3: Double,
    val riskReward: Double,
    val marketStructure: String = "SIDEWAYS",
    val trend: String = "SIDEWAYS",
    val momentum: String = "NEUTRAL",
    val marketConditions: String,       // Immutable MTF condition summary at creation
    val aiProviderUsed: String,         // "AI · Primary" or "AI · Backup"
    val aiModelUsed: String,            // e.g. "gemini-3.5-flash", "gpt-4o-mini", "nexsync-mtf-v2"
    val originalSnapshotJson: String,   // Complete immutable analysis + market snapshot JSON
    // Requirement #1, #3, #8, #9: Setup metadata & lifecycle tracking fields
    val marketRegime: String = "RANGING",
    val mtfConfirmation: String = "",
    val entryTrigger: String = "",
    val invalidation: String = "",
    val expiryAt: Long = 0L,
    val setupSignature: String = "",
    val botAction: String = "NONE",     // Separate from AI Signal: e.g., "NONE", "PENDING_TRIGGER", "ENTERED", "SKIPPED_RISK", "SKIPPED_EVENT"
    val status: String = "ACTIVE",      // "WAIT", "ACTIVE", "ENTRY_TRIGGERED", "TP1_HIT", "TP2_HIT", "TP3_HIT", "SL_HIT", "INVALIDATED", "EXPIRED", "CLOSED", "CANCELLED", "AMBIGUOUS"
    val highestTpReached: Int = 0,      // 0, 1, 2, or 3
    val realizedR: Double = 0.0,
    val resolvedAt: Long? = null,
    val exitModelVersion: Int = 2,      // 1 = legacy hindsight exit model, 2 = realistic 1/3 partial TP + breakeven + fee/slippage model
    val source: String = "AI",          // "AI" or "BASELINE"
    val lifecycleState: String = "ACTIVE", // "SCANNING", "CANDIDATE", "ACTIVE", "PARTIALLY_TRIGGERED", "ENTERED", "INVALIDATED", "EXPIRED", "CANCELLED", "COMPLETED"
    val statusReason: String = "",      // Explicit reason for current lifecycle state or transition
    val aiSystemMode: String = "AI_CONTINUOUS", // "AI_CONTINUOUS" or "AI_TRADING_STYLE"
    val aiExecutionMode: String = "AUTO",       // "AUTO" or "MANUAL"
    val tradingStyleProfile: String = "",       // "", "SCALPING", "DAY_TRADING", "SWING_TRADING"
    val entryOrderType: String = "LIMIT",       // "MARKET", "LIMIT", "WAIT"
    val selectedTimeframesSummary: String = "",
    val setupDecisionState: String = "WAITING_FOR_CONFIRMATION",
    val tradingSessionLabel: String = "",
    val liquiditySweepState: String = "NONE",
    val liquiditySweepSummary: String = "",
    val smcStructureSummary: String = "",
    val spreadStatusSummary: String = "",
    val eventRiskSummary: String = ""
)

/**
 * Requirement #2 & #3: Persistent Bot Trading (Simulasi) Position & Transaction Record.
 * Persists open and closed simulated positions across app restarts.
 */
@Entity(tableName = "bot_positions")
data class BotPositionEntity(
    @PrimaryKey val id: String,
    val signalId: Long = 0L,
    val setupSignature: String = "",
    val symbol: String,
    val timeframe: String,
    val side: String,                   // "LONG" or "SHORT"
    val setupGrade: String,             // "A+", "A", "B"
    val marketRegime: String = "TRENDING",
    val entryPrice: Double,
    val currentPrice: Double,
    val stopLoss: Double,
    val initialStopLoss: Double,
    val tp1: Double,
    val tp2: Double,
    val tp3: Double,
    val initialNotionalUsd: Double,
    val remainingNotionalUsd: Double,
    val marginUsedUsd: Double,
    val leverage: Double,
    val riskPercent: Double,
    val tp1AllocationPct: Double = 30.0,
    val tp2AllocationPct: Double = 30.0,
    val tp3AllocationPct: Double = 40.0,
    val moveSlToBreakEvenOnTp1: Boolean = true,
    val tp1Hit: Boolean = false,
    val tp2Hit: Boolean = false,
    val tp3Hit: Boolean = false,
    val slMovedToBreakEven: Boolean = false,
    val realizedPnlUsd: Double = 0.0,
    val unrealizedPnlUsd: Double = 0.0,
    val feesPaidUsd: Double = 0.0,
    val openedAt: Long,
    val closedAt: Long? = null,
    val status: String = "OPEN",        // "OPEN", "CLOSED_TP3", "CLOSED_SL", "CLOSED_BE", "CLOSED_MANUAL"
    val closeReason: String = "",
    val lastProcessedPriceTick: Double = 0.0,
    val lastProcessedTimestamp: Long = 0L
)

@Entity(tableName = "ai_token_usage")
data class AiTokenUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val requestId: String,
    val timestamp: Long,                  // UTC epoch ms
    val providerId: String,               // "gemini_builtin", "gemini_personal", "openai", "openrouter", "custom_openai", "local"
    val providerDisplayName: String,      // "Gemini Built-in", "OpenAI", etc.
    val modelId: String,                  // "gemini-2.5-flash", "gpt-4o-mini", etc.
    val inputTokens: Int?,                // null if unavailable
    val outputTokens: Int?,               // null if unavailable
    val totalTokens: Int?,                // null if unavailable
    val isSuccess: Boolean,
    val usageSource: String,              // "ACTUAL", "ESTIMATED", "UNAVAILABLE", "LOCAL"
    val errorMessage: String? = null
)

/**
 * LUMAVUN Chart — AI Insight V3.2: Persistent Tracked Pending Setup Entity.
 */
@Entity(tableName = "tracked_pending_setups")
data class TrackedPendingSetupEntity(
    @PrimaryKey val setupId: String,
    val strategyId: String,
    val strategyName: String,
    val symbol: String,
    val timeframe: String,
    val direction: String,
    val orderType: String,
    val entryZoneMin: Double,
    val entryZoneMax: Double,
    val stopLoss: Double,
    val tp1: Double,
    val tp2: Double? = null,
    val tp3: Double? = null,
    val riskRewardTp1: Double? = null,
    val createdAt: Long,
    val expiresAt: Long,
    val currentStatus: String,
    val lastVerifiedPrice: Double,
    val lastVerifiedTimestamp: Long,
    val invalidationCondition: String = "",
    val transitionHistoryJson: String = "[]"
) {
    fun toDomain(): TrackedPendingSetup {
        return TrackedPendingSetup(
            setupId = setupId,
            strategyId = strategyId,
            strategyName = strategyName,
            symbol = symbol,
            timeframe = timeframe,
            direction = TradeDirection.fromString(direction),
            orderType = TradeOrderType.fromString(orderType),
            entryZoneMin = entryZoneMin,
            entryZoneMax = entryZoneMax,
            stopLoss = stopLoss,
            tp1 = tp1,
            tp2 = tp2,
            tp3 = tp3,
            riskRewardTp1 = riskRewardTp1,
            createdAt = createdAt,
            expiresAt = expiresAt,
            currentStatus = TrackedSetupStatus.fromString(currentStatus),
            lastVerifiedPrice = lastVerifiedPrice,
            lastVerifiedTimestamp = lastVerifiedTimestamp,
            invalidationCondition = invalidationCondition,
            transitionHistory = SetupTransitionRecord.listFromJson(transitionHistoryJson)
        )
    }

    companion object {
        fun fromDomain(domain: TrackedPendingSetup): TrackedPendingSetupEntity {
            return TrackedPendingSetupEntity(
                setupId = domain.setupId,
                strategyId = domain.strategyId,
                strategyName = domain.strategyName,
                symbol = domain.symbol,
                timeframe = domain.timeframe,
                direction = domain.direction.code,
                orderType = domain.orderType.code,
                entryZoneMin = domain.entryZoneMin,
                entryZoneMax = domain.entryZoneMax,
                stopLoss = domain.stopLoss,
                tp1 = domain.tp1,
                tp2 = domain.tp2,
                tp3 = domain.tp3,
                riskRewardTp1 = domain.riskRewardTp1,
                createdAt = domain.createdAt,
                expiresAt = domain.expiresAt,
                currentStatus = domain.currentStatus.code,
                lastVerifiedPrice = domain.lastVerifiedPrice,
                lastVerifiedTimestamp = domain.lastVerifiedTimestamp,
                invalidationCondition = domain.invalidationCondition,
                transitionHistoryJson = SetupTransitionRecord.listToJson(domain.transitionHistory)
            )
        }
    }
}

@Dao
interface TerminalDao {
    // --- AI Token Usage Tracking ---
    @Query("SELECT * FROM ai_token_usage ORDER BY timestamp DESC")
    fun observeAllTokenUsage(): Flow<List<AiTokenUsageEntity>>

    @Query("SELECT * FROM ai_token_usage ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentTokenUsage(limit: Int = 100): Flow<List<AiTokenUsageEntity>>

    @Query("SELECT * FROM ai_token_usage WHERE timestamp >= :sinceTimestamp ORDER BY timestamp DESC")
    fun observeTokenUsageSince(sinceTimestamp: Long): Flow<List<AiTokenUsageEntity>>

    @Query("SELECT * FROM ai_token_usage WHERE timestamp >= :sinceTimestamp")
    suspend fun getTokenUsageSince(sinceTimestamp: Long): List<AiTokenUsageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTokenUsage(record: AiTokenUsageEntity)

    @Query("DELETE FROM ai_token_usage WHERE timestamp < :beforeTimestamp")
    suspend fun pruneTokenUsageOlderThan(beforeTimestamp: Long)

    @Query("DELETE FROM ai_token_usage")
    suspend fun clearAllTokenUsage()

    // --- Watchlist ---
    @Query("SELECT * FROM watchlist_items ORDER BY sortOrder ASC")
    fun observeWatchlist(): Flow<List<WatchlistEntity>>

    @Query("SELECT * FROM watchlist_items ORDER BY sortOrder ASC")
    suspend fun getWatchlistOnce(): List<WatchlistEntity>

    @Query("SELECT MAX(sortOrder) FROM watchlist_items")
    suspend fun getMaxSortOrder(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatchlistItem(item: WatchlistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatchlistItems(items: List<WatchlistEntity>)

    @Query("DELETE FROM watchlist_items WHERE symbol = :symbol")
    suspend fun deleteWatchlistSymbol(symbol: String)

    @Query(
        """
        UPDATE watchlist_items 
        SET lastPrice = :lastPrice, 
            priceChangePercent = :changePct, 
            highPrice = :high, 
            lowPrice = :low, 
            volume = :volume, 
            updatedAt = :updatedAt 
        WHERE symbol = :symbol
        """
    )
    suspend fun updateWatchlistTicker(
        symbol: String,
        lastPrice: Double,
        changePct: Double,
        high: Double,
        low: Double,
        volume: Double,
        updatedAt: Long
    )

    // --- Exchange Symbols Cache ---
    @Query(
        """
        SELECT * FROM cached_symbols 
        WHERE (symbol LIKE :pattern OR baseAsset LIKE :pattern OR displayName LIKE :pattern)
        ORDER BY 
            CASE 
                WHEN baseAsset = :exactQuery OR symbol = :exactQuery THEN 0
                WHEN baseAsset LIKE :prefixPattern OR symbol LIKE :prefixPattern THEN 1
                ELSE 2
            END,
            LENGTH(symbol) ASC,
            symbol ASC 
        LIMIT 150
        """
    )
    fun searchCachedSymbols(
        pattern: String,
        exactQuery: String = "",
        prefixPattern: String = "%"
    ): Flow<List<CachedSymbolEntity>>

    @Query("SELECT * FROM cached_symbols WHERE symbol = :symbol LIMIT 1")
    suspend fun getCachedSymbolByCode(symbol: String): CachedSymbolEntity?

    @Query("DELETE FROM cached_symbols WHERE provider = 'BINANCE' AND quoteAsset != 'USDT'")
    suspend fun deleteNonUsdtCachedSymbols()

    @Query("SELECT COUNT(*) FROM cached_symbols")
    suspend fun getCachedSymbolCount(): Int

    @Query("SELECT MAX(updatedAt) FROM cached_symbols")
    suspend fun getLastSymbolCacheTime(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCachedSymbols(symbols: List<CachedSymbolEntity>)

    // --- Historical Candles Offline Cache ---
    @Query("SELECT * FROM cached_candles WHERE symbol = :symbol AND timeframe = :timeframe ORDER BY openTime ASC")
    suspend fun getCachedCandles(symbol: String, timeframe: String): List<CachedCandleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCachedCandles(candles: List<CachedCandleEntity>)

    @Query("DELETE FROM cached_candles WHERE symbol = :symbol AND timeframe = :timeframe")
    suspend fun clearCachedCandlesForPair(symbol: String, timeframe: String)

    // --- Cached AI Analysis ---
    @Query("SELECT * FROM cached_ai_analysis WHERE symbol = :symbol AND timeframe = :timeframe LIMIT 1")
    suspend fun getCachedAiAnalysis(symbol: String, timeframe: String): CachedAiAnalysisEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCachedAiAnalysis(entity: CachedAiAnalysisEntity)

    // --- AI Signal Journal & Forward-Test Tracking (Requirements #4, #6, #15) ---
    @Query("SELECT * FROM signal_journal ORDER BY timestamp DESC")
    fun observeSignalJournal(): Flow<List<SignalJournalEntity>>

    @Query("SELECT * FROM signal_journal ORDER BY timestamp DESC")
    suspend fun getAllSignalsOnce(): List<SignalJournalEntity>

    @Query(
        """
        SELECT * FROM signal_journal 
        WHERE symbol = :symbol 
          AND primaryTimeframe = :timeframe 
          AND status IN ('ACTIVE', 'PARTIALLY_TRIGGERED', 'ENTERED', 'ENTRY_TRIGGERED', 'TP1_HIT', 'TP2_HIT')
        ORDER BY timestamp ASC
        """
    )
    suspend fun getTrackableSignalsForPair(symbol: String, timeframe: String): List<SignalJournalEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSignalJournal(signal: SignalJournalEntity): Long

    /**
     * Updates ONLY the forward-test tracking outcome columns.
     * Never modifies historical signal parameters, entry, SL, TP, or original snapshot.
     */
    @Query(
        """
        UPDATE signal_journal 
        SET status = :status,
            highestTpReached = :highestTpReached,
            realizedR = :realizedR,
            resolvedAt = :resolvedAt
        WHERE id = :id
        """
    )
    suspend fun updateSignalForwardOutcome(
        id: Long,
        status: String,
        highestTpReached: Int,
        realizedR: Double,
        resolvedAt: Long?
    )

    @Query(
        """
        UPDATE signal_journal 
        SET status = :status,
            lifecycleState = :lifecycleState,
            statusReason = :statusReason,
            highestTpReached = :highestTpReached,
            realizedR = :realizedR,
            resolvedAt = :resolvedAt
        WHERE id = :id
        """
    )
    suspend fun updateSignalLifecycleAndOutcome(
        id: Long,
        status: String,
        lifecycleState: String,
        statusReason: String,
        highestTpReached: Int,
        realizedR: Double,
        resolvedAt: Long?
    )

    @Query("UPDATE signal_journal SET botAction = :botAction WHERE id = :id")
    suspend fun updateSignalBotAction(id: Long, botAction: String)

    @Query(
        """
        UPDATE signal_journal 
        SET botAction = :botAction,
            lifecycleState = :lifecycleState,
            status = :status,
            statusReason = :statusReason
        WHERE id = :id
        """
    )
    suspend fun updateSignalBotActionAndLifecycle(
        id: Long,
        botAction: String,
        lifecycleState: String,
        status: String,
        statusReason: String
    )

    // --- Bot Trading (Simulasi) Persistent Positions & History ---
    @Query("SELECT * FROM bot_positions ORDER BY openedAt DESC")
    fun observeBotPositions(): Flow<List<BotPositionEntity>>

    @Query("SELECT * FROM bot_positions ORDER BY openedAt DESC")
    suspend fun getAllBotPositionsOnce(): List<BotPositionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBotPosition(position: BotPositionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBotPositions(positions: List<BotPositionEntity>)

    @Query("DELETE FROM bot_positions")
    suspend fun clearAllBotPositions()

    // --- Tracked Pending Setups (V3.2 Smart Pending Setup Tracker) ---
    @Query("SELECT * FROM tracked_pending_setups ORDER BY createdAt DESC")
    fun observeAllTrackedSetups(): Flow<List<TrackedPendingSetupEntity>>

    @Query("SELECT * FROM tracked_pending_setups WHERE currentStatus NOT IN ('TP3_REACHED', 'STOP_LOSS_REACHED', 'INVALIDATED', 'EXPIRED', 'CANCELLED', 'AMBIGUOUS') ORDER BY createdAt DESC")
    fun observeActiveTrackedSetups(): Flow<List<TrackedPendingSetupEntity>>

    @Query("SELECT * FROM tracked_pending_setups WHERE symbol = :symbol ORDER BY createdAt DESC")
    fun observeTrackedSetupsForSymbol(symbol: String): Flow<List<TrackedPendingSetupEntity>>

    @Query("SELECT * FROM tracked_pending_setups WHERE setupId = :setupId LIMIT 1")
    suspend fun getTrackedSetupById(setupId: String): TrackedPendingSetupEntity?

    @Query("SELECT * FROM tracked_pending_setups WHERE currentStatus NOT IN ('TP3_REACHED', 'STOP_LOSS_REACHED', 'INVALIDATED', 'EXPIRED', 'CANCELLED', 'AMBIGUOUS')")
    suspend fun getAllActiveTrackedSetupsOnce(): List<TrackedPendingSetupEntity>

    @Query("SELECT * FROM tracked_pending_setups WHERE symbol = :symbol AND currentStatus NOT IN ('TP3_REACHED', 'STOP_LOSS_REACHED', 'INVALIDATED', 'EXPIRED', 'CANCELLED', 'AMBIGUOUS')")
    suspend fun getActiveTrackedSetupsForSymbol(symbol: String): List<TrackedPendingSetupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrackedSetup(setup: TrackedPendingSetupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrackedSetups(setups: List<TrackedPendingSetupEntity>)

    @Query("DELETE FROM tracked_pending_setups WHERE setupId = :setupId")
    suspend fun deleteTrackedSetup(setupId: String)

    @Query("DELETE FROM tracked_pending_setups WHERE currentStatus IN ('TP3_REACHED', 'STOP_LOSS_REACHED', 'INVALIDATED', 'EXPIRED', 'CANCELLED', 'AMBIGUOUS') AND createdAt < :beforeTimestamp")
    suspend fun pruneTerminalSetupsOlderThan(beforeTimestamp: Long)
}

@Database(
    entities = [
        WatchlistEntity::class,
        CachedSymbolEntity::class,
        CachedCandleEntity::class,
        CachedAiAnalysisEntity::class,
        SignalJournalEntity::class,
        BotPositionEntity::class,
        AiTokenUsageEntity::class,
        TrackedPendingSetupEntity::class
    ],
    version = 12,
    exportSchema = false
)
abstract class TerminalDatabase : RoomDatabase() {
    abstract fun terminalDao(): TerminalDao

    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE signal_journal ADD COLUMN exitModelVersion INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE signal_journal ADD COLUMN source TEXT NOT NULL DEFAULT 'AI'"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watchlist_items ADD COLUMN category TEXT NOT NULL DEFAULT 'CRYPTO'")
                db.execSQL("ALTER TABLE watchlist_items ADD COLUMN provider TEXT NOT NULL DEFAULT 'BINANCE'")
                db.execSQL("ALTER TABLE watchlist_items ADD COLUMN displayName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cached_symbols ADD COLUMN category TEXT NOT NULL DEFAULT 'CRYPTO'")
                db.execSQL("ALTER TABLE cached_symbols ADD COLUMN provider TEXT NOT NULL DEFAULT 'BINANCE'")
                db.execSQL("ALTER TABLE cached_symbols ADD COLUMN displayName TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE signal_journal ADD COLUMN lifecycleState TEXT NOT NULL DEFAULT 'ACTIVE'")
                db.execSQL("ALTER TABLE signal_journal ADD COLUMN statusReason TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE signal_journal ADD COLUMN aiSystemMode TEXT NOT NULL DEFAULT 'AI_CONTINUOUS'")
                db.execSQL("ALTER TABLE signal_journal ADD COLUMN aiExecutionMode TEXT NOT NULL DEFAULT 'AUTO'")
                db.execSQL("ALTER TABLE signal_journal ADD COLUMN tradingStyleProfile TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE signal_journal ADD COLUMN entryOrderType TEXT NOT NULL DEFAULT 'LIMIT'")
                db.execSQL("ALTER TABLE signal_journal ADD COLUMN selectedTimeframesSummary TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE signal_journal ADD COLUMN setupDecisionState TEXT NOT NULL DEFAULT 'WAITING_FOR_CONFIRMATION'")
                db.execSQL("ALTER TABLE signal_journal ADD COLUMN tradingSessionLabel TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE signal_journal ADD COLUMN liquiditySweepState TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE signal_journal ADD COLUMN liquiditySweepSummary TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE signal_journal ADD COLUMN smcStructureSummary TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE signal_journal ADD COLUMN spreadStatusSummary TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE signal_journal ADD COLUMN eventRiskSummary TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ai_token_usage (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        requestId TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        providerId TEXT NOT NULL,
                        providerDisplayName TEXT NOT NULL,
                        modelId TEXT NOT NULL,
                        inputTokens INTEGER,
                        outputTokens INTEGER,
                        totalTokens INTEGER,
                        isSuccess INTEGER NOT NULL,
                        usageSource TEXT NOT NULL,
                        errorMessage TEXT
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tracked_pending_setups (
                        setupId TEXT PRIMARY KEY NOT NULL,
                        strategyId TEXT NOT NULL,
                        strategyName TEXT NOT NULL,
                        symbol TEXT NOT NULL,
                        timeframe TEXT NOT NULL,
                        direction TEXT NOT NULL,
                        orderType TEXT NOT NULL,
                        entryZoneMin REAL NOT NULL,
                        entryZoneMax REAL NOT NULL,
                        stopLoss REAL NOT NULL,
                        tp1 REAL NOT NULL,
                        tp2 REAL,
                        tp3 REAL,
                        riskRewardTp1 REAL,
                        createdAt INTEGER NOT NULL,
                        expiresAt INTEGER NOT NULL,
                        currentStatus TEXT NOT NULL,
                        lastVerifiedPrice REAL NOT NULL,
                        lastVerifiedTimestamp INTEGER NOT NULL,
                        invalidationCondition TEXT NOT NULL,
                        transitionHistoryJson TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var INSTANCE: TerminalDatabase? = null

        fun getInstance(context: Context): TerminalDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TerminalDatabase::class.java,
                    "nexsync_lite_terminal.db"
                )
                    .addMigrations(
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12
                    )
                    .fallbackToDestructiveMigrationFrom(1, 2, 3)
                    .fallbackToDestructiveMigration()
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

