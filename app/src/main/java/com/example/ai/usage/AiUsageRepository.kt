package com.example.ai.usage

import com.example.storage.preferences.AppPreferences
import com.example.storage.watchlist.AiTokenUsageEntity
import com.example.storage.watchlist.TerminalDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

enum class TokenUsageSource(val label: String) {
    ACTUAL("Actual"),
    ESTIMATED("Estimated"),
    UNAVAILABLE("Unavailable"),
    LOCAL("Local Synthesis (No Cloud Tokens)")
}

data class TokenUsageSnapshot(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val source: TokenUsageSource = TokenUsageSource.UNAVAILABLE,
    val isSuccess: Boolean = true,
    val errorMessage: String? = null
)

data class ProviderUsageSummary(
    val providerId: String,
    val providerDisplayName: String,
    val totalTokens: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val requestCount: Int,
    val successCount: Int,
    val failedCount: Int
)

data class ModelUsageSummary(
    val modelId: String,
    val providerDisplayName: String,
    val totalTokens: Long,
    val requestCount: Int
)

data class AiUsageDashboardData(
    val todayTokens: Long = 0L,
    val monthTokens: Long = 0L,
    val allTimeTokens: Long = 0L,
    val totalInputTokens: Long = 0L,
    val totalOutputTokens: Long = 0L,
    val totalRequests: Int = 0,
    val successRequests: Int = 0,
    val failedRequests: Int = 0,
    val perProvider: List<ProviderUsageSummary> = emptyList(),
    val perModel: List<ModelUsageSummary> = emptyList(),
    val dailyBudget: Long = 0L,
    val monthlyBudget: Long = 0L,
    val warningThresholdPercent: Int = 80,
    val blockWhenBudgetExceeded: Boolean = false,
    val isDailyBudgetExceeded: Boolean = false,
    val isDailyBudgetWarning: Boolean = false,
    val isMonthlyBudgetExceeded: Boolean = false,
    val isMonthlyBudgetWarning: Boolean = false
)

class AiUsageRepository(
    private val dao: TerminalDao,
    private val preferences: AppPreferences
) {

    val recentUsageFlow: Flow<List<AiTokenUsageEntity>> = dao.observeRecentTokenUsage(100)

    val dashboardDataFlow: Flow<AiUsageDashboardData> = combine(
        dao.observeAllTokenUsage(),
        preferences.settings
    ) { records, settings ->
        val nowUtc = System.currentTimeMillis()
        val startOfTodayUtc = getStartOfTodayUtc()
        val startOfMonthUtc = getStartOfMonthUtc()

        var todayTokens = 0L
        var monthTokens = 0L
        var allTimeTokens = 0L
        var totalInputTokens = 0L
        var totalOutputTokens = 0L
        var successCount = 0
        var failedCount = 0

        val providerMap = mutableMapOf<String, ProviderAccumulator>()
        val modelMap = mutableMapOf<String, ModelAccumulator>()

        for (rec in records) {
            if (rec.isSuccess) successCount++ else failedCount++

            // Only count cloud tokens (exclude local synthesis or unavailable)
            val isCloud = rec.usageSource != TokenUsageSource.LOCAL.name
            val recTotal = if (isCloud) (rec.totalTokens ?: 0).toLong() else 0L
            val recIn = if (isCloud) (rec.inputTokens ?: 0).toLong() else 0L
            val recOut = if (isCloud) (rec.outputTokens ?: 0).toLong() else 0L

            allTimeTokens += recTotal
            totalInputTokens += recIn
            totalOutputTokens += recOut

            if (rec.timestamp >= startOfTodayUtc) {
                todayTokens += recTotal
            }
            if (rec.timestamp >= startOfMonthUtc) {
                monthTokens += recTotal
            }

            // Group by provider
            val pKey = rec.providerId
            val pAcc = providerMap.getOrPut(pKey) {
                ProviderAccumulator(rec.providerId, rec.providerDisplayName)
            }
            pAcc.totalTokens += recTotal
            pAcc.inputTokens += recIn
            pAcc.outputTokens += recOut
            pAcc.requestCount++
            if (rec.isSuccess) pAcc.successCount++ else pAcc.failedCount++

            // Group by model
            val mKey = rec.modelId
            val mAcc = modelMap.getOrPut(mKey) {
                ModelAccumulator(rec.modelId, rec.providerDisplayName)
            }
            mAcc.totalTokens += recTotal
            mAcc.requestCount++
        }

        val dailyBudget = settings.dailyTokenBudget
        val monthlyBudget = settings.monthlyTokenBudget
        val threshold = settings.budgetWarningThresholdPercent.coerceIn(1, 100)

        val dailyExceeded = dailyBudget > 0 && todayTokens >= dailyBudget
        val dailyWarning = dailyBudget > 0 && !dailyExceeded && todayTokens >= (dailyBudget * threshold / 100)

        val monthlyExceeded = monthlyBudget > 0 && monthTokens >= monthlyBudget
        val monthlyWarning = monthlyBudget > 0 && !monthlyExceeded && monthTokens >= (monthlyBudget * threshold / 100)

        AiUsageDashboardData(
            todayTokens = todayTokens,
            monthTokens = monthTokens,
            allTimeTokens = allTimeTokens,
            totalInputTokens = totalInputTokens,
            totalOutputTokens = totalOutputTokens,
            totalRequests = records.size,
            successRequests = successCount,
            failedRequests = failedCount,
            perProvider = providerMap.values.map { it.toSummary() }.sortedByDescending { it.totalTokens },
            perModel = modelMap.values.map { it.toSummary() }.sortedByDescending { it.totalTokens },
            dailyBudget = dailyBudget,
            monthlyBudget = monthlyBudget,
            warningThresholdPercent = threshold,
            blockWhenBudgetExceeded = settings.blockWhenBudgetExceeded,
            isDailyBudgetExceeded = dailyExceeded,
            isDailyBudgetWarning = dailyWarning,
            isMonthlyBudgetExceeded = monthlyExceeded,
            isMonthlyBudgetWarning = monthlyWarning
        )
    }.flowOn(Dispatchers.Default)

    /**
     * Records a token usage entry. Never stores raw API keys or prompt texts.
     */
    suspend fun recordUsage(
        providerId: String,
        providerDisplayName: String,
        modelId: String,
        snapshot: TokenUsageSnapshot,
        requestId: String = UUID.randomUUID().toString()
    ) = withContext(Dispatchers.IO) {
        val entity = AiTokenUsageEntity(
            requestId = requestId,
            timestamp = System.currentTimeMillis(),
            providerId = providerId,
            providerDisplayName = providerDisplayName,
            modelId = modelId,
            inputTokens = snapshot.inputTokens,
            outputTokens = snapshot.outputTokens,
            totalTokens = snapshot.totalTokens,
            isSuccess = snapshot.isSuccess,
            usageSource = snapshot.source.name,
            errorMessage = snapshot.errorMessage?.take(200)
        )
        dao.insertTokenUsage(entity)

        // Prune older than 90 days to prevent unlimited database growth
        val ninetyDaysAgo = System.currentTimeMillis() - (90L * 24 * 3600 * 1000L)
        dao.pruneTokenUsageOlderThan(ninetyDaysAgo)
    }

    /**
     * Checks whether an upcoming cloud request is allowed under locally configured token budgets.
     */
    suspend fun checkCloudRequestAllowed(): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val settings = preferences.settings.value
        if (!settings.blockWhenBudgetExceeded) return@withContext true to null

        val startOfToday = getStartOfTodayUtc()
        val startOfMonth = getStartOfMonthUtc()

        val monthRecords = dao.getTokenUsageSince(startOfMonth)
        var todayTokens = 0L
        var monthTokens = 0L

        for (rec in monthRecords) {
            if (rec.usageSource != TokenUsageSource.LOCAL.name) {
                val total = (rec.totalTokens ?: 0).toLong()
                monthTokens += total
                if (rec.timestamp >= startOfToday) {
                    todayTokens += total
                }
            }
        }

        if (settings.dailyTokenBudget > 0 && todayTokens >= settings.dailyTokenBudget) {
            return@withContext false to "Daily local token budget reached ($todayTokens / ${settings.dailyTokenBudget} tokens). Cloud analysis is blocked by your local AI Configuration."
        }

        if (settings.monthlyTokenBudget > 0 && monthTokens >= settings.monthlyTokenBudget) {
            return@withContext false to "Monthly local token budget reached ($monthTokens / ${settings.monthlyTokenBudget} tokens). Cloud analysis is blocked by your local AI Configuration."
        }

        true to null
    }

    suspend fun clearAllUsage() = withContext(Dispatchers.IO) {
        dao.clearAllTokenUsage()
    }

    companion object {
        fun getStartOfTodayUtc(): Long {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        fun getStartOfMonthUtc(): Long {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }

    private class ProviderAccumulator(
        val providerId: String,
        val providerDisplayName: String,
        var totalTokens: Long = 0L,
        var inputTokens: Long = 0L,
        var outputTokens: Long = 0L,
        var requestCount: Int = 0,
        var successCount: Int = 0,
        var failedCount: Int = 0
    ) {
        fun toSummary() = ProviderUsageSummary(
            providerId = providerId,
            providerDisplayName = providerDisplayName,
            totalTokens = totalTokens,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            requestCount = requestCount,
            successCount = successCount,
            failedCount = failedCount
        )
    }

    private class ModelAccumulator(
        val modelId: String,
        val providerDisplayName: String,
        var totalTokens: Long = 0L,
        var requestCount: Int = 0
    ) {
        fun toSummary() = ModelUsageSummary(
            modelId = modelId,
            providerDisplayName = providerDisplayName,
            totalTokens = totalTokens,
            requestCount = requestCount
        )
    }
}
