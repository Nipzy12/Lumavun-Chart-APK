package com.example.storage.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.example.ai.strategy.AiStrategy
import com.example.market.candle.MarketProvider
import com.example.market.candle.Timeframe
import com.example.market.exness.ExnessConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class AiProviderMode(val label: String) {
    BUILT_IN("Built-in AI"),
    CUSTOM_API("Custom API"),
    GEMINI_API("Gemini API")
}

enum class CustomApiFormat(val label: String) {
    OPENAI_COMPATIBLE("OpenAI Compatible"),
    GEMINI_REST("Gemini REST")
}

/**
 * Multi-provider AI selector:
 * Supports Gemini Built-in, Gemini Personal API, OpenAI, OpenRouter, and Custom Provider.
 */
enum class AiProviderType(
    val code: String,
    val displayName: String,
    val defaultEndpoint: String,
    val defaultModel: String,
    val requiresApiKey: Boolean,
    val format: CustomApiFormat
) {
    GEMINI_BUILTIN(
        code = "GEMINI_BUILTIN",
        displayName = "Gemini Built-in",
        defaultEndpoint = "https://generativelanguage.googleapis.com",
        defaultModel = "gemini-2.5-flash",
        requiresApiKey = false,
        format = CustomApiFormat.GEMINI_REST
    ),
    GEMINI_PERSONAL(
        code = "GEMINI_PERSONAL",
        displayName = "Gemini Personal API",
        defaultEndpoint = "https://generativelanguage.googleapis.com",
        defaultModel = "gemini-2.5-flash",
        requiresApiKey = true,
        format = CustomApiFormat.GEMINI_REST
    ),
    OPENAI(
        code = "OPENAI",
        displayName = "OpenAI",
        defaultEndpoint = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        requiresApiKey = true,
        format = CustomApiFormat.OPENAI_COMPATIBLE
    ),
    OPENROUTER(
        code = "OPENROUTER",
        displayName = "OpenRouter",
        defaultEndpoint = "https://openrouter.ai/api/v1",
        defaultModel = "deepseek/deepseek-chat",
        requiresApiKey = true,
        format = CustomApiFormat.OPENAI_COMPATIBLE
    ),
    CUSTOM_OPENAI(
        code = "CUSTOM_OPENAI",
        displayName = "Custom Provider",
        defaultEndpoint = "https://api.openai.com/v1",
        defaultModel = "custom-model",
        requiresApiKey = false,
        format = CustomApiFormat.OPENAI_COMPATIBLE
    );

    val label: String get() = displayName

    companion object {
        fun fromCode(code: String?): AiProviderType {
            return entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) }
                ?: GEMINI_BUILTIN
        }
    }
}


/**
 * Top-level AI Analysis System selector:
 * 1. AI_CONTINUOUS — supports AUTO and MANUAL submodes.
 * 2. AI_TRADING_STYLE — always manual; analyzes using SCALPING, DAY_TRADING, or SWING_TRADING profiles.
 */
enum class AiAnalysisSystem(
    val code: String,
    val displayTitle: String,
    val shortBadge: String,
    val description: String
) {
    AI_CONTINUOUS(
        code = "AI_CONTINUOUS",
        displayTitle = "AI Continuous",
        shortBadge = "Continuous",
        description = "Continuously monitor market conditions and identify trading setups (Auto), or analyze on demand (Manual)."
    ),
    AI_TRADING_STYLE(
        code = "AI_TRADING_STYLE",
        displayTitle = "AI Gaya Trading",
        shortBadge = "Gaya Trading",
        description = "Manual strategy-driven analysis using Scalping, Day Trading, or Swing Trading rules and timeframe horizons."
    );

    val label: String get() = displayTitle

    companion object {
        fun fromCode(code: String?): AiAnalysisSystem {
            return entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) }
                ?: AI_CONTINUOUS
        }
    }
}

/**
 * Submodes for Option A — AI Continuous.
 */
enum class AiContinuousSubmode(
    val code: String,
    val displayLabel: String,
    val description: String
) {
    AUTO(
        code = "AUTO",
        displayLabel = "Auto",
        description = "Continuously monitors valid live market data & intrabar ticks using Setup Engine V2."
    ),
    MANUAL(
        code = "MANUAL",
        displayLabel = "Manual",
        description = "Analyzes market conditions only when you explicitly request an analysis."
    );

    val label: String get() = displayLabel

    companion object {
        fun fromCode(code: String?): AiContinuousSubmode {
            return entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) }
                ?: AUTO
        }
    }
}

/**
 * Chart Vision modes (Phase 6):
 * AUTO: offscreen chart images generated only when user requests analysis (Analyze Now) and provider supports vision.
 * OFF: chart images disabled entirely, strictly using Technical Only analysis.
 */
enum class ChartVisionMode(val code: String, val displayLabel: String) {
    AUTO("AUTO", "Auto when supported"),
    OFF("OFF", "Off");

    companion object {
        fun fromCode(code: String?): ChartVisionMode {
            return entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) }
                ?: AUTO
        }
    }
}

/**
 * Strategy profiles for Option B — AI Gaya Trading (Always Manual).
 */
enum class TradingStyleProfile(
    val code: String,
    val displayLabel: String,
    val entryTimeframeRangeLabel: String,
    val higherTimeframeRangeLabel: String,
    val defaultEntryTimeframe: Timeframe,
    val recommendedEntryTimeframes: List<Timeframe>,
    val minimumRiskReward: Double,
    val defaultExpiryMinutes: Int,
    val focusSummary: String
) {
    SCALPING(
        code = "SCALPING",
        displayLabel = "Scalping",
        entryTimeframeRangeLabel = "1m–5m",
        higherTimeframeRangeLabel = "5m–15m",
        defaultEntryTimeframe = Timeframe.M5,
        recommendedEntryTimeframes = listOf(Timeframe.M1, Timeframe.M5),
        minimumRiskReward = 1.45,
        defaultExpiryMinutes = 30,
        focusSummary = "Short-term momentum, spread & fee compensation, volatility filter, and rapid invalidation."
    ),
    DAY_TRADING(
        code = "DAY_TRADING",
        displayLabel = "Day Trading",
        entryTimeframeRangeLabel = "5m–15m",
        higherTimeframeRangeLabel = "15m–1H",
        defaultEntryTimeframe = Timeframe.M15,
        recommendedEntryTimeframes = listOf(Timeframe.M5, Timeframe.M15, Timeframe.M30),
        minimumRiskReward = 1.55,
        defaultExpiryMinutes = 360,
        focusSummary = "Intraday structure (HH/HL or LH/LL), trend continuation, breakouts, retests, and session horizon."
    ),
    SWING_TRADING(
        code = "SWING_TRADING",
        displayLabel = "Swing Trading",
        entryTimeframeRangeLabel = "1H–4H",
        higherTimeframeRangeLabel = "4H–1D",
        defaultEntryTimeframe = Timeframe.H1,
        recommendedEntryTimeframes = listOf(Timeframe.H1, Timeframe.H4),
        minimumRiskReward = 1.80,
        defaultExpiryMinutes = 2880,
        focusSummary = "Broader market structure, macro trend alignment, major S/R zones, and structural SL sizing."
    );

    val label: String get() = displayLabel
    val higherTimeframeContextLabel: String get() = higherTimeframeRangeLabel

    /**
     * Resolves the appropriate Higher-Timeframe context for a given entry [timeframe] under this profile.
     */
    fun resolveHigherTimeframe(entryTimeframe: Timeframe): Timeframe {
        return when (this) {
            SCALPING -> when (entryTimeframe) {
                Timeframe.M1 -> Timeframe.M5
                Timeframe.M5 -> Timeframe.M15
                else -> entryTimeframe.higherTimeframe() ?: Timeframe.M15
            }
            DAY_TRADING -> when (entryTimeframe) {
                Timeframe.M1 -> Timeframe.M15
                Timeframe.M5 -> Timeframe.M15
                Timeframe.M15, Timeframe.M30 -> Timeframe.H1
                else -> entryTimeframe.higherTimeframe() ?: Timeframe.H1
            }
            SWING_TRADING -> when (entryTimeframe) {
                Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.M30, Timeframe.H1 -> Timeframe.H4
                Timeframe.H4, Timeframe.D1 -> Timeframe.D1
            }
        }
    }

    /**
     * Resolves the Lower-Timeframe trigger context for a given entry [timeframe] under this profile.
     */
    fun resolveLowerTimeframe(entryTimeframe: Timeframe): Timeframe? {
        return when (this) {
            SCALPING -> when (entryTimeframe) {
                Timeframe.M1 -> null
                Timeframe.M5 -> Timeframe.M1
                else -> entryTimeframe.lowerTimeframe()
            }
            DAY_TRADING -> when (entryTimeframe) {
                Timeframe.M5 -> Timeframe.M1
                Timeframe.M15, Timeframe.M30 -> Timeframe.M5
                else -> entryTimeframe.lowerTimeframe()
            }
            SWING_TRADING -> when (entryTimeframe) {
                Timeframe.H1 -> Timeframe.M15
                Timeframe.H4 -> Timeframe.H1
                Timeframe.D1 -> Timeframe.H4
                else -> entryTimeframe.lowerTimeframe()
            }
        }
    }

    companion object {
        fun fromCode(code: String?): TradingStyleProfile {
            return entries.firstOrNull {
                it.code.equals(code?.trim(), ignoreCase = true) ||
                    it.displayLabel.equals(code?.trim(), ignoreCase = true)
            } ?: DAY_TRADING
        }
    }
}

/**
 * Explicit runtime status for AI Continuous Auto monitoring.
 * Prevents claiming background/automatic monitoring is active when stopped, starting, stale, or disconnected.
 */
enum class AiAutoMonitorRuntimeState(val code: String, val displayLabel: String) {
    STOPPED("STOPPED", "STOPPED"),
    STARTING("STARTING", "STARTING"),
    RUNNING("RUNNING", "RUNNING"),
    ACTIVE("ACTIVE", "RUNNING"),
    ANALYZING("ANALYZING", "ANALYZING"),
    STALE("STALE", "DATA STALE"),
    DISCONNECTED("DISCONNECTED", "DISCONNECTED");

    val badgeLabel: String get() = displayLabel
}

data class TerminalSettings(
    val defaultSymbol: String = "BTCUSDT",
    val defaultTimeframe: Timeframe = Timeframe.M15,
    val autoAiAnalysis: Boolean = true,
    val aiAnalysisSystem: AiAnalysisSystem = AiAnalysisSystem.AI_CONTINUOUS,
    val aiContinuousSubmode: AiContinuousSubmode = AiContinuousSubmode.AUTO,
    val tradingStyleProfile: TradingStyleProfile = TradingStyleProfile.DAY_TRADING,
    val autoSignalMinGrade: String = "A", // "A+", "A", "B"
    val showEma: Boolean = true,
    val showSma: Boolean = false,
    val showBollingerBands: Boolean = false,
    val showVolume: Boolean = true,
    val showRsi: Boolean = false,
    val showMacd: Boolean = false,
    val showAtr: Boolean = true,
    val showSupportResistance: Boolean = false,
    val showSmc: Boolean = false,
    val showIct: Boolean = false,
    val showAmd: Boolean = false,
    val showSignalOverlay: Boolean = true,
    val showSignalHistory: Boolean = false,
    val showTradePlanOverlay: Boolean = true,
    val perTimeframeIndicators: Boolean = false,
    val ema1Period: Int = 20,
    val ema2Period: Int = 50,
    val ema3Period: Int = 200,
    val sma1Period: Int = 20,
    val sma2Period: Int = 50,
    val bbPeriod: Int = 20,
    val bbStdDev: Double = 2.0,
    val rsiPeriod: Int = 14,
    val macdFast: Int = 12,
    val macdSlow: Int = 26,
    val macdSignal: Int = 9,
    val atrPeriod: Int = 14,
    val darkMode: Boolean = true,
    // Multi-provider AI settings (Requirements #25-#33)
    val primaryAiProvider: AiProviderMode = AiProviderMode.BUILT_IN,
    val activeAiProviderType: AiProviderType = AiProviderType.GEMINI_BUILTIN,
    val enableAiFallback: Boolean = true,
    val customApiEndpoint: String = "https://api.openai.com/v1",
    val customApiModel: String = "gpt-4o-mini",
    val customApiFormat: CustomApiFormat = CustomApiFormat.OPENAI_COMPATIBLE,
    val hasCustomApiKey: Boolean = false,
    val geminiCustomModel: String = "gemini-3.5-flash",
    val hasGeminiCustomKey: Boolean = false,
    val dailyTokenBudget: Long = 0L,
    val monthlyTokenBudget: Long = 0L,
    val budgetWarningThresholdPercent: Int = 80,
    val blockWhenBudgetExceeded: Boolean = false,
    // Requirement #6 & #7: Notifications, Foreground Monitoring & Event Risk Protection
    val notificationsEnabled: Boolean = true,
    val notifyNewSignals: Boolean = true,
    val notifyBotEntries: Boolean = true,
    val notifyTpSlHits: Boolean = true,
    val notifySignalInvalidation: Boolean = true,
    val notifyRiskEvents: Boolean = true,
    val foregroundMonitoringEnabled: Boolean = false,
    val eventProtectionMode: String = "BLOCK_HIGH_IMPACT",
    val unavailableEventPolicy: String = "ALLOW_WITH_WARNING",
    val eventPreBufferMinutes: Int = 30,
    val eventPostBufferMinutes: Int = 30,
    val manualEventLockActive: Boolean = false,
    val favoriteSymbols: Set<String> = setOf("BTCUSDT", "ETHUSDT", "SOLUSDT"),
    val calendarTimezone: CalendarTimezoneOption = CalendarTimezoneOption.DEVICE,
    val watchedCurrencies: Set<String> = setOf("USD", "EUR", "GBP", "JPY", "XAU"),
    val lastOpenedSymbol: String = "BTCUSDT",
    val lastOpenedTimeframe: String = "1h",
    val userName: String = "Trader",
    val themeMode: String = "DARK", // "DARK", "LIGHT", "AUTO"
    val keepScreenOn: Boolean = false,
    val language: String = "id", // "id", "en"
    val chartVisionMode: ChartVisionMode = ChartVisionMode.AUTO,
    val customApiSupportsVision: Boolean = false,
    val selectedAiStrategy: AiStrategy = AiStrategy.AI_HYBRID,
    val selectedMarketProvider: MarketProvider = MarketProvider.BINANCE,
    val exnessConfig: ExnessConfig = ExnessConfig()
)

data class PersistedBotState(
    val runState: String = "STOPPED", // "RUNNING", "PAUSED", "STOPPED"
    val startingBalanceUsd: Double = 10_000.0,
    val currentBalanceUsd: Double = 10_000.0,
    val peakEquityUsd: Double = 10_000.0,
    val maxDrawdownPct: Double = 0.0,
    val riskPerTradePct: Double = 1.0,
    val leverage: Double = 5.0,
    val feeBps: Double = 4.0,
    val slippageBps: Double = 2.0,
    val maxOpenPositions: Int = 3,
    val dailyLossLimitPct: Double = 5.0,
    val cooldownMinutes: Int = 15,
    val minSetupGrade: String = "A",
    val moveSlToBreakEvenOnTp1: Boolean = true,
    val tp1AllocationPct: Double = 30.0,
    val tp2AllocationPct: Double = 30.0,
    val tp3AllocationPct: Double = 40.0,
    val lastEntryTimestamp: Long = 0L
)

class AppPreferences(context: Context) {
    companion object {
        private const val PREFS_NAME = "nexsync_lite_prefs_v3"
        private const val KEY_DEFAULT_SYMBOL = "default_symbol"
        private const val KEY_DEFAULT_TIMEFRAME = "default_timeframe"
        private const val KEY_LAST_OPENED_SYMBOL = "last_opened_symbol"
        private const val KEY_LAST_OPENED_TIMEFRAME = "last_opened_timeframe"
        private const val KEY_AUTO_AI = "auto_ai_analysis"
        private const val KEY_AI_ANALYSIS_SYSTEM = "ai_analysis_system"
        private const val KEY_AI_CONTINUOUS_SUBMODE = "ai_continuous_submode"
        private const val KEY_TRADING_STYLE_PROFILE = "trading_style_profile"
        private const val KEY_AUTO_SIGNAL_MIN_GRADE = "auto_signal_min_grade"
        private const val KEY_SHOW_EMA = "show_ema"
        private const val KEY_SHOW_SMA = "show_sma"
        private const val KEY_SHOW_BB = "show_bb"
        private const val KEY_SHOW_VOLUME = "show_volume"
        private const val KEY_SHOW_RSI = "show_rsi"
        private const val KEY_SHOW_MACD = "show_macd"
        private const val KEY_SHOW_ATR = "show_atr"
        private const val KEY_SHOW_SR = "show_sr"
        private const val KEY_SHOW_SMC = "show_smc"
        private const val KEY_SHOW_ICT = "show_ict"
        private const val KEY_SHOW_AMD = "show_amd"
        private const val KEY_SHOW_SIGNAL_OVERLAY = "show_signal_overlay"
        private const val KEY_SHOW_SIGNAL_HISTORY = "show_signal_history"
        private const val KEY_SHOW_TRADE_PLAN_OVERLAY = "show_trade_plan_overlay"
        private const val KEY_PER_TF_INDICATORS = "per_tf_indicators"
        private const val KEY_EMA1_PERIOD = "ema1_period"
        private const val KEY_EMA2_PERIOD = "ema2_period"
        private const val KEY_EMA3_PERIOD = "ema3_period"
        private const val KEY_SMA1_PERIOD = "sma1_period"
        private const val KEY_SMA2_PERIOD = "sma2_period"
        private const val KEY_BB_PERIOD = "bb_period"
        private const val KEY_BB_STDDEV = "bb_stddev"
        private const val KEY_RSI_PERIOD = "rsi_period"
        private const val KEY_MACD_FAST = "macd_fast"
        private const val KEY_MACD_SLOW = "macd_slow"
        private const val KEY_MACD_SIGNAL = "macd_signal"
        private const val KEY_ATR_PERIOD = "atr_period"
        private const val KEY_DARK_MODE = "dark_mode"

        private const val KEY_PRIMARY_AI_PROVIDER = "primary_ai_provider"
        private const val KEY_ENABLE_AI_FALLBACK = "enable_ai_fallback"
        private const val KEY_CUSTOM_API_ENDPOINT = "custom_api_endpoint"
        private const val KEY_CUSTOM_API_KEY_OBF = "custom_api_key_obf"
        private const val KEY_CUSTOM_API_MODEL = "custom_api_model"
        private const val KEY_CUSTOM_API_FORMAT = "custom_api_format"
        private const val KEY_GEMINI_CUSTOM_KEY_OBF = "gemini_custom_key_obf"
        private const val KEY_GEMINI_CUSTOM_MODEL = "gemini_custom_model"

        private const val KEY_ACTIVE_AI_PROVIDER_TYPE = "active_ai_provider_type"
        private const val KEY_DAILY_TOKEN_BUDGET = "daily_token_budget"
        private const val KEY_MONTHLY_TOKEN_BUDGET = "monthly_token_budget"
        private const val KEY_BUDGET_WARNING_THRESHOLD = "budget_warning_threshold"
        private const val KEY_BLOCK_WHEN_BUDGET_EXCEEDED = "block_when_budget_exceeded"
        private const val KEY_SYNCED_MODELS_JSON_PREFIX = "synced_models_json_"
        private const val KEY_PROVIDER_ENDPOINT_PREFIX = "provider_endpoint_"
        private const val KEY_PROVIDER_KEY_PREFIX = "provider_key_"
        private const val KEY_PROVIDER_MODEL_PREFIX = "provider_model_"


        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        private const val KEY_NOTIFY_NEW_SIGNALS = "notify_new_signals"
        private const val KEY_NOTIFY_BOT_ENTRIES = "notify_bot_entries"
        private const val KEY_NOTIFY_TPSL_HITS = "notify_tpsl_hits"
        private const val KEY_NOTIFY_INVALIDATION = "notify_invalidation"
        private const val KEY_NOTIFY_RISK_EVENTS = "notify_risk_events"
        private const val KEY_FOREGROUND_MONITOR = "foreground_monitoring_enabled"
        private const val KEY_EVENT_PROTECTION_MODE = "event_protection_mode"
        private const val KEY_UNAVAILABLE_EVENT_POLICY = "unavailable_event_policy"
        private const val KEY_EVENT_PRE_BUFFER_MINS = "event_pre_buffer_mins"
        private const val KEY_EVENT_POST_BUFFER_MINS = "event_post_buffer_mins"
        private const val KEY_MANUAL_EVENT_LOCK = "manual_event_lock"
        private const val KEY_FAVORITE_SYMBOLS = "favorite_symbols"
        private const val KEY_CALENDAR_CACHE_JSON = "calendar_cache_json"
        private const val KEY_CALENDAR_CACHE_TS = "calendar_cache_ts"
        private const val KEY_CALENDAR_TIMEZONE = "calendar_timezone"
        private const val KEY_WATCHED_CURRENCIES = "watched_currencies"
        private const val KEY_USER_NAME = "userName"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_CHART_VISION_MODE = "chart_vision_mode"
        private const val KEY_CUSTOM_API_SUPPORTS_VISION = "custom_api_supports_vision"
        private const val KEY_SELECTED_AI_STRATEGY = "selected_ai_strategy"
        private const val KEY_SELECTED_MARKET_PROVIDER = "selected_market_provider"
        private const val KEY_EXNESS_GATEWAY_URL = "exness_gateway_url"
        private const val KEY_EXNESS_SESSION_TOKEN_OBF = "exness_session_token_obf"
        private const val KEY_EXNESS_ACCOUNT_TIER = "exness_account_tier"
        private const val KEY_EXNESS_SERVER_ENV = "exness_server_env"
        private const val KEY_EXNESS_IS_ENABLED = "exness_is_enabled"

        // Bot Trading (Simulasi) Persistent Keys
        private const val KEY_BOT_RUN_STATE = "bot_run_state"
        private const val KEY_BOT_STARTING_BALANCE = "bot_starting_balance"
        private const val KEY_BOT_CURRENT_BALANCE = "bot_current_balance"
        private const val KEY_BOT_PEAK_EQUITY = "bot_peak_equity"
        private const val KEY_BOT_MAX_DD = "bot_max_dd"
        private const val KEY_BOT_RISK_PCT = "bot_risk_pct"
        private const val KEY_BOT_LEVERAGE = "bot_leverage"
        private const val KEY_BOT_FEE_BPS = "bot_fee_bps"
        private const val KEY_BOT_SLIPPAGE_BPS = "bot_slippage_bps"
        private const val KEY_BOT_MAX_OPEN_POS = "bot_max_open_pos"
        private const val KEY_BOT_DAILY_LOSS_PCT = "bot_daily_loss_pct"
        private const val KEY_BOT_COOLDOWN_MINS = "bot_cooldown_mins"
        private const val KEY_BOT_MIN_GRADE = "bot_min_grade"
        private const val KEY_BOT_BE_ON_TP1 = "bot_be_on_tp1"
        private const val KEY_BOT_TP1_PCT = "bot_tp1_pct"
        private const val KEY_BOT_TP2_PCT = "bot_tp2_pct"
        private const val KEY_BOT_TP3_PCT = "bot_tp3_pct"
        private const val KEY_BOT_LAST_ENTRY_TS = "bot_last_entry_ts"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<TerminalSettings> = _settings.asStateFlow()

    private fun readSettings(): TerminalSettings = runCatching {
        val sym = prefs.getString(KEY_DEFAULT_SYMBOL, "BTCUSDT") ?: "BTCUSDT"
        val tfStr = prefs.getString(KEY_DEFAULT_TIMEFRAME, Timeframe.M15.binanceInterval) ?: "15m"
        val lastOpenedSym = prefs.getString(KEY_LAST_OPENED_SYMBOL, null)?.trim()?.takeIf { it.isNotEmpty() } ?: sym
        val lastOpenedTf = prefs.getString(KEY_LAST_OPENED_TIMEFRAME, null)?.trim()?.takeIf { it.isNotEmpty() } ?: "1h"
        val providerName = prefs.getString(KEY_PRIMARY_AI_PROVIDER, AiProviderMode.BUILT_IN.name)
            ?: AiProviderMode.BUILT_IN.name
        val formatName = prefs.getString(KEY_CUSTOM_API_FORMAT, CustomApiFormat.OPENAI_COMPATIBLE.name)
            ?: CustomApiFormat.OPENAI_COMPATIBLE.name

        val provider = AiProviderMode.entries.firstOrNull { it.name == providerName }
            ?: AiProviderMode.BUILT_IN
        val format = CustomApiFormat.entries.firstOrNull { it.name == formatName }
            ?: CustomApiFormat.OPENAI_COMPATIBLE

        val favSet = runCatching {
            prefs.getStringSet(KEY_FAVORITE_SYMBOLS, setOf("BTCUSDT", "ETHUSDT", "SOLUSDT"))
        }.getOrNull() ?: setOf("BTCUSDT", "ETHUSDT", "SOLUSDT")

        val storedAutoAi = prefs.getBoolean(KEY_AUTO_AI, true)
        val aiSystem = AiAnalysisSystem.fromCode(
            prefs.getString(KEY_AI_ANALYSIS_SYSTEM, AiAnalysisSystem.AI_CONTINUOUS.code)
        )
        val continuousSubmode = if (prefs.contains(KEY_AI_CONTINUOUS_SUBMODE)) {
            AiContinuousSubmode.fromCode(prefs.getString(KEY_AI_CONTINUOUS_SUBMODE, AiContinuousSubmode.AUTO.code))
        } else {
            if (storedAutoAi) AiContinuousSubmode.AUTO else AiContinuousSubmode.MANUAL
        }
        val styleProfile = TradingStyleProfile.fromCode(
            prefs.getString(KEY_TRADING_STYLE_PROFILE, TradingStyleProfile.DAY_TRADING.code)
        )
        val effectiveAutoAi = aiSystem == AiAnalysisSystem.AI_CONTINUOUS &&
            continuousSubmode == AiContinuousSubmode.AUTO

        val activeProviderTypeCode = prefs.getString(KEY_ACTIVE_AI_PROVIDER_TYPE, null)
        val activeProviderType = if (!activeProviderTypeCode.isNullOrBlank()) {
            AiProviderType.fromCode(activeProviderTypeCode)
        } else {
            when (provider) {
                AiProviderMode.BUILT_IN -> AiProviderType.GEMINI_BUILTIN
                AiProviderMode.GEMINI_API -> AiProviderType.GEMINI_PERSONAL
                AiProviderMode.CUSTOM_API -> AiProviderType.CUSTOM_OPENAI
            }
        }
        val dailyBudget = prefs.getLong(KEY_DAILY_TOKEN_BUDGET, 0L)
        val monthlyBudget = prefs.getLong(KEY_MONTHLY_TOKEN_BUDGET, 0L)
        val warningThreshold = prefs.getInt(KEY_BUDGET_WARNING_THRESHOLD, 80).coerceIn(1, 100)
        val blockWhenBudgetExceeded = prefs.getBoolean(KEY_BLOCK_WHEN_BUDGET_EXCEEDED, false)

        TerminalSettings(
            defaultSymbol = sym.uppercase(Locale.US),
            defaultTimeframe = Timeframe.fromInterval(tfStr),
            autoAiAnalysis = effectiveAutoAi,
            aiAnalysisSystem = aiSystem,
            aiContinuousSubmode = continuousSubmode,
            tradingStyleProfile = styleProfile,
            autoSignalMinGrade = prefs.getString(KEY_AUTO_SIGNAL_MIN_GRADE, "A") ?: "A",
            showEma = prefs.getBoolean(KEY_SHOW_EMA, true),
            showSma = prefs.getBoolean(KEY_SHOW_SMA, false),
            showBollingerBands = prefs.getBoolean(KEY_SHOW_BB, false),
            showVolume = prefs.getBoolean(KEY_SHOW_VOLUME, true),
            showRsi = prefs.getBoolean(KEY_SHOW_RSI, false),
            showMacd = prefs.getBoolean(KEY_SHOW_MACD, false),
            showAtr = prefs.getBoolean(KEY_SHOW_ATR, true),
            showSupportResistance = prefs.getBoolean(KEY_SHOW_SR, false),
            showSmc = prefs.getBoolean(KEY_SHOW_SMC, false),
            showIct = prefs.getBoolean(KEY_SHOW_ICT, false),
            showAmd = prefs.getBoolean(KEY_SHOW_AMD, false),
            showSignalOverlay = prefs.getBoolean(KEY_SHOW_SIGNAL_OVERLAY, true),
            showSignalHistory = prefs.getBoolean(KEY_SHOW_SIGNAL_HISTORY, false),
            showTradePlanOverlay = prefs.getBoolean(KEY_SHOW_TRADE_PLAN_OVERLAY, true),
            perTimeframeIndicators = prefs.getBoolean(KEY_PER_TF_INDICATORS, false),
            ema1Period = prefs.getInt(KEY_EMA1_PERIOD, 20).coerceIn(2, 500),
            ema2Period = prefs.getInt(KEY_EMA2_PERIOD, 50).coerceIn(2, 500),
            ema3Period = prefs.getInt(KEY_EMA3_PERIOD, 200).coerceIn(2, 500),
            sma1Period = prefs.getInt(KEY_SMA1_PERIOD, 20).coerceIn(2, 500),
            sma2Period = prefs.getInt(KEY_SMA2_PERIOD, 50).coerceIn(2, 500),
            bbPeriod = prefs.getInt(KEY_BB_PERIOD, 20).coerceIn(2, 200),
            bbStdDev = (runCatching { prefs.getString(KEY_BB_STDDEV, "2.0") }.getOrNull()?.toDoubleOrNull() ?: 2.0).coerceIn(0.5, 5.0),
            rsiPeriod = prefs.getInt(KEY_RSI_PERIOD, 14).coerceIn(2, 100),
            macdFast = prefs.getInt(KEY_MACD_FAST, 12).coerceIn(2, 100),
            macdSlow = prefs.getInt(KEY_MACD_SLOW, 26).coerceIn(5, 200),
            macdSignal = prefs.getInt(KEY_MACD_SIGNAL, 9).coerceIn(2, 100),
            atrPeriod = prefs.getInt(KEY_ATR_PERIOD, 14).coerceIn(2, 100),
            darkMode = prefs.getBoolean(KEY_DARK_MODE, true),
            primaryAiProvider = provider,
            activeAiProviderType = activeProviderType,
            enableAiFallback = prefs.getBoolean(KEY_ENABLE_AI_FALLBACK, true),
            customApiEndpoint = prefs.getString(KEY_CUSTOM_API_ENDPOINT, "https://api.openai.com/v1")
                ?: "https://api.openai.com/v1",
            customApiModel = prefs.getString(KEY_CUSTOM_API_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini",
            customApiFormat = format,
            hasCustomApiKey = getCustomApiKey().isNotBlank(),
            geminiCustomModel = prefs.getString(KEY_GEMINI_CUSTOM_MODEL, "gemini-3.5-flash")
                ?: "gemini-3.5-flash",
            hasGeminiCustomKey = getGeminiCustomApiKey().isNotBlank(),
            dailyTokenBudget = dailyBudget,
            monthlyTokenBudget = monthlyBudget,
            budgetWarningThresholdPercent = warningThreshold,
            blockWhenBudgetExceeded = blockWhenBudgetExceeded,

            notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true),
            notifyNewSignals = prefs.getBoolean(KEY_NOTIFY_NEW_SIGNALS, true),
            notifyBotEntries = prefs.getBoolean(KEY_NOTIFY_BOT_ENTRIES, true),
            notifyTpSlHits = prefs.getBoolean(KEY_NOTIFY_TPSL_HITS, true),
            notifySignalInvalidation = prefs.getBoolean(KEY_NOTIFY_INVALIDATION, true),
            notifyRiskEvents = prefs.getBoolean(KEY_NOTIFY_RISK_EVENTS, true),
            foregroundMonitoringEnabled = prefs.getBoolean(KEY_FOREGROUND_MONITOR, false),
            eventProtectionMode = prefs.getString(KEY_EVENT_PROTECTION_MODE, "BLOCK_HIGH_IMPACT")
                ?: "BLOCK_HIGH_IMPACT",
            unavailableEventPolicy = prefs.getString(KEY_UNAVAILABLE_EVENT_POLICY, "ALLOW_WITH_WARNING")
                ?: "ALLOW_WITH_WARNING",
            eventPreBufferMinutes = prefs.getInt(KEY_EVENT_PRE_BUFFER_MINS, 30).coerceIn(5, 180),
            eventPostBufferMinutes = prefs.getInt(KEY_EVENT_POST_BUFFER_MINS, 30).coerceIn(5, 180),
            manualEventLockActive = prefs.getBoolean(KEY_MANUAL_EVENT_LOCK, false),
            favoriteSymbols = favSet.map { it.uppercase(Locale.US) }.toSet(),
            calendarTimezone = CalendarTimezoneOption.fromCode(
                prefs.getString(KEY_CALENDAR_TIMEZONE, CalendarTimezoneOption.DEVICE.code)
            ),
            watchedCurrencies = runCatching {
                prefs.getStringSet(KEY_WATCHED_CURRENCIES, setOf("USD", "EUR", "GBP", "JPY", "XAU"))
            }.getOrNull() ?: setOf("USD", "EUR", "GBP", "JPY", "XAU"),
            lastOpenedSymbol = lastOpenedSym.uppercase(Locale.US),
            lastOpenedTimeframe = lastOpenedTf,
            userName = prefs.getString(KEY_USER_NAME, "Trader")?.trim()?.ifEmpty { "Trader" } ?: "Trader",
            themeMode = prefs.getString(KEY_THEME_MODE, "DARK") ?: "DARK",
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false),
            language = prefs.getString(KEY_APP_LANGUAGE, "id") ?: "id",
            chartVisionMode = ChartVisionMode.fromCode(prefs.getString(KEY_CHART_VISION_MODE, ChartVisionMode.AUTO.code)),
            customApiSupportsVision = prefs.getBoolean(KEY_CUSTOM_API_SUPPORTS_VISION, false),
            selectedAiStrategy = AiStrategy.fromId(prefs.getString(KEY_SELECTED_AI_STRATEGY, AiStrategy.AI_HYBRID.id)),
            selectedMarketProvider = MarketProvider.fromCode(
                prefs.getString(KEY_SELECTED_MARKET_PROVIDER, MarketProvider.BINANCE.code)
            ),
            exnessConfig = ExnessConfig(
                gatewayUrl = prefs.getString(KEY_EXNESS_GATEWAY_URL, "") ?: "",
                sessionToken = decodeSecret(prefs.getString(KEY_EXNESS_SESSION_TOKEN_OBF, "") ?: ""),
                accountTier = prefs.getString(KEY_EXNESS_ACCOUNT_TIER, "STANDARD") ?: "STANDARD",
                serverEnvironment = prefs.getString(KEY_EXNESS_SERVER_ENV, "DEMO") ?: "DEMO",
                isEnabled = prefs.getBoolean(KEY_EXNESS_IS_ENABLED, false)
            )
        )
    }.getOrElse { TerminalSettings() }

    fun getCachedCalendarPayload(): Pair<String, Long> = runCatching {
        val raw = prefs.getString(KEY_CALENDAR_CACHE_JSON, "") ?: ""
        val ts = prefs.getLong(KEY_CALENDAR_CACHE_TS, 0L)
        raw to ts
    }.getOrElse { "" to 0L }

    fun saveCachedCalendarPayload(rawJson: String, timestampMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_CALENDAR_CACHE_JSON, rawJson)
            .putLong(KEY_CALENDAR_CACHE_TS, timestampMs)
            .apply()
    }

    fun readPersistedBotState(): PersistedBotState = runCatching {
        val startBal = prefs.getString(KEY_BOT_STARTING_BALANCE, "10000.0")?.toDoubleOrNull() ?: 10_000.0
        val currBal = prefs.getString(KEY_BOT_CURRENT_BALANCE, null)?.toDoubleOrNull() ?: startBal
        val peakEq = prefs.getString(KEY_BOT_PEAK_EQUITY, null)?.toDoubleOrNull() ?: maxOf(startBal, currBal)
        val maxDd = prefs.getString(KEY_BOT_MAX_DD, "0.0")?.toDoubleOrNull() ?: 0.0
        val riskPct = prefs.getString(KEY_BOT_RISK_PCT, "1.0")?.toDoubleOrNull() ?: 1.0
        val lev = prefs.getString(KEY_BOT_LEVERAGE, "5.0")?.toDoubleOrNull() ?: 5.0
        val fee = prefs.getString(KEY_BOT_FEE_BPS, "4.0")?.toDoubleOrNull() ?: 4.0
        val slip = prefs.getString(KEY_BOT_SLIPPAGE_BPS, "2.0")?.toDoubleOrNull() ?: 2.0
        val dailyLoss = prefs.getString(KEY_BOT_DAILY_LOSS_PCT, "5.0")?.toDoubleOrNull() ?: 5.0
        val tp1Pct = prefs.getString(KEY_BOT_TP1_PCT, "30.0")?.toDoubleOrNull() ?: 30.0
        val tp2Pct = prefs.getString(KEY_BOT_TP2_PCT, "30.0")?.toDoubleOrNull() ?: 30.0
        val tp3Pct = prefs.getString(KEY_BOT_TP3_PCT, "40.0")?.toDoubleOrNull() ?: 40.0

        PersistedBotState(
            runState = prefs.getString(KEY_BOT_RUN_STATE, "STOPPED") ?: "STOPPED",
            startingBalanceUsd = startBal,
            currentBalanceUsd = currBal,
            peakEquityUsd = peakEq,
            maxDrawdownPct = maxDd,
            riskPerTradePct = riskPct,
            leverage = lev,
            feeBps = fee,
            slippageBps = slip,
            maxOpenPositions = prefs.getInt(KEY_BOT_MAX_OPEN_POS, 3),
            dailyLossLimitPct = dailyLoss,
            cooldownMinutes = prefs.getInt(KEY_BOT_COOLDOWN_MINS, 15),
            minSetupGrade = prefs.getString(KEY_BOT_MIN_GRADE, "A") ?: "A",
            moveSlToBreakEvenOnTp1 = prefs.getBoolean(KEY_BOT_BE_ON_TP1, true),
            tp1AllocationPct = tp1Pct,
            tp2AllocationPct = tp2Pct,
            tp3AllocationPct = tp3Pct,
            lastEntryTimestamp = prefs.getLong(KEY_BOT_LAST_ENTRY_TS, 0L)
        )
    }.getOrElse { PersistedBotState() }

    fun savePersistedBotState(state: PersistedBotState) {
        prefs.edit()
            .putString(KEY_BOT_RUN_STATE, state.runState)
            .putString(KEY_BOT_STARTING_BALANCE, state.startingBalanceUsd.toString())
            .putString(KEY_BOT_CURRENT_BALANCE, state.currentBalanceUsd.toString())
            .putString(KEY_BOT_PEAK_EQUITY, state.peakEquityUsd.toString())
            .putString(KEY_BOT_MAX_DD, state.maxDrawdownPct.toString())
            .putString(KEY_BOT_RISK_PCT, state.riskPerTradePct.toString())
            .putString(KEY_BOT_LEVERAGE, state.leverage.toString())
            .putString(KEY_BOT_FEE_BPS, state.feeBps.toString())
            .putString(KEY_BOT_SLIPPAGE_BPS, state.slippageBps.toString())
            .putInt(KEY_BOT_MAX_OPEN_POS, state.maxOpenPositions)
            .putString(KEY_BOT_DAILY_LOSS_PCT, state.dailyLossLimitPct.toString())
            .putInt(KEY_BOT_COOLDOWN_MINS, state.cooldownMinutes)
            .putString(KEY_BOT_MIN_GRADE, state.minSetupGrade)
            .putBoolean(KEY_BOT_BE_ON_TP1, state.moveSlToBreakEvenOnTp1)
            .putString(KEY_BOT_TP1_PCT, state.tp1AllocationPct.toString())
            .putString(KEY_BOT_TP2_PCT, state.tp2AllocationPct.toString())
            .putString(KEY_BOT_TP3_PCT, state.tp3AllocationPct.toString())
            .putLong(KEY_BOT_LAST_ENTRY_TS, state.lastEntryTimestamp)
            .apply()
    }

    fun setDefaultSymbol(symbol: String) {
        val clean = symbol.trim().uppercase(Locale.US)
        if (clean.isEmpty()) return
        prefs.edit().putString(KEY_DEFAULT_SYMBOL, clean).apply()
        _settings.value = readSettings()
    }

    fun setDefaultTimeframe(timeframe: Timeframe) {
        prefs.edit().putString(KEY_DEFAULT_TIMEFRAME, timeframe.binanceInterval).apply()
        _settings.value = readSettings()
    }

    fun getLastOpenedSymbol(): String {
        return prefs.getString(KEY_LAST_OPENED_SYMBOL, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: prefs.getString(KEY_DEFAULT_SYMBOL, "BTCUSDT")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "BTCUSDT"
    }

    fun setLastOpenedSymbol(symbol: String) {
        val clean = symbol.trim().uppercase(Locale.US)
        if (clean.isEmpty()) return
        prefs.edit().putString(KEY_LAST_OPENED_SYMBOL, clean).apply()
        _settings.value = readSettings()
    }

    fun getLastOpenedTimeframe(): String {
        return prefs.getString(KEY_LAST_OPENED_TIMEFRAME, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: prefs.getString(KEY_DEFAULT_TIMEFRAME, "1h")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "1h"
    }

    fun setLastOpenedTimeframe(timeframe: String) {
        val clean = timeframe.trim().lowercase(Locale.US)
        if (clean.isEmpty()) return
        prefs.edit().putString(KEY_LAST_OPENED_TIMEFRAME, clean).apply()
        _settings.value = readSettings()
    }

    fun setLastOpenedTimeframe(timeframe: Timeframe) {
        setLastOpenedTimeframe(timeframe.binanceInterval)
    }

    fun getLastOpenedTimeframeEnum(): Timeframe {
        return Timeframe.fromInterval(getLastOpenedTimeframe())
    }

    fun setCalendarTimezone(option: CalendarTimezoneOption) {
        prefs.edit().putString(KEY_CALENDAR_TIMEZONE, option.code).apply()
        _settings.value = readSettings()
    }

    fun setWatchedCurrencies(currencies: Set<String>) {
        prefs.edit().putStringSet(KEY_WATCHED_CURRENCIES, currencies).apply()
        _settings.value = readSettings()
    }

    fun toggleWatchedCurrency(currency: String) {
        val current = _settings.value.watchedCurrencies.toMutableSet()
        val c = currency.uppercase(Locale.US)
        if (current.contains(c)) {
            if (current.size > 1) {
                current.remove(c)
            }
        } else {
            current.add(c)
        }
        setWatchedCurrencies(current)
    }

    fun setUserName(name: String) {
        val clean = name.trim().ifEmpty { "Trader" }
        prefs.edit().putString(KEY_USER_NAME, clean).apply()
        _settings.value = readSettings()
    }

    fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME_MODE, mode).apply()
        val isDark = when (mode.uppercase(Locale.US)) {
            "LIGHT" -> false
            else -> true
        }
        prefs.edit().putBoolean(KEY_DARK_MODE, isDark).apply()
        _settings.value = readSettings()
    }

    fun setKeepScreenOn(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
        _settings.value = readSettings()
    }

    fun setLanguage(lang: String) {
        prefs.edit().putString(KEY_APP_LANGUAGE, lang).apply()
        _settings.value = readSettings()
    }

    fun setAutoAiAnalysis(enabled: Boolean) {
        val submode = if (enabled) AiContinuousSubmode.AUTO else AiContinuousSubmode.MANUAL
        prefs.edit()
            .putString(KEY_AI_ANALYSIS_SYSTEM, AiAnalysisSystem.AI_CONTINUOUS.code)
            .putString(KEY_AI_CONTINUOUS_SUBMODE, submode.code)
            .putBoolean(KEY_AUTO_AI, enabled)
            .apply()
        _settings.value = readSettings()
    }

    fun setAiAnalysisSystem(system: AiAnalysisSystem) {
        val currentSubmode = _settings.value.aiContinuousSubmode
        val effectiveAuto = system == AiAnalysisSystem.AI_CONTINUOUS &&
            currentSubmode == AiContinuousSubmode.AUTO
        prefs.edit()
            .putString(KEY_AI_ANALYSIS_SYSTEM, system.code)
            .putBoolean(KEY_AUTO_AI, effectiveAuto)
            .apply()
        _settings.value = readSettings()
    }

    fun setAiContinuousSubmode(submode: AiContinuousSubmode) {
        val isAuto = submode == AiContinuousSubmode.AUTO
        prefs.edit()
            .putString(KEY_AI_ANALYSIS_SYSTEM, AiAnalysisSystem.AI_CONTINUOUS.code)
            .putString(KEY_AI_CONTINUOUS_SUBMODE, submode.code)
            .putBoolean(KEY_AUTO_AI, isAuto)
            .apply()
        _settings.value = readSettings()
    }

    fun setTradingStyleProfile(profile: TradingStyleProfile) {
        prefs.edit()
            .putString(KEY_AI_ANALYSIS_SYSTEM, AiAnalysisSystem.AI_TRADING_STYLE.code)
            .putString(KEY_TRADING_STYLE_PROFILE, profile.code)
            .putBoolean(KEY_AUTO_AI, false)
            .apply()
        _settings.value = readSettings()
    }

    fun setAutoSignalMinGrade(grade: String) {
        prefs.edit().putString(KEY_AUTO_SIGNAL_MIN_GRADE, grade.trim().uppercase(Locale.US)).apply()
        _settings.value = readSettings()
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()
        _settings.value = readSettings()
    }

    fun setNotifyNewSignals(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_NEW_SIGNALS, enabled).apply()
        _settings.value = readSettings()
    }

    fun setNotifyBotEntries(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_BOT_ENTRIES, enabled).apply()
        _settings.value = readSettings()
    }

    fun setNotifyTpSlHits(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_TPSL_HITS, enabled).apply()
        _settings.value = readSettings()
    }

    fun setNotifySignalInvalidation(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_INVALIDATION, enabled).apply()
        _settings.value = readSettings()
    }

    fun setNotifyRiskEvents(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_RISK_EVENTS, enabled).apply()
        _settings.value = readSettings()
    }

    fun setForegroundMonitoringEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FOREGROUND_MONITOR, enabled).apply()
        _settings.value = readSettings()
    }

    fun setEventProtectionMode(mode: String) {
        prefs.edit().putString(KEY_EVENT_PROTECTION_MODE, mode).apply()
        _settings.value = readSettings()
    }

    fun setUnavailableEventPolicy(policy: String) {
        prefs.edit().putString(KEY_UNAVAILABLE_EVENT_POLICY, policy).apply()
        _settings.value = readSettings()
    }

    fun setEventBufferMinutes(preMinutes: Int, postMinutes: Int) {
        prefs.edit()
            .putInt(KEY_EVENT_PRE_BUFFER_MINS, preMinutes.coerceIn(5, 180))
            .putInt(KEY_EVENT_POST_BUFFER_MINS, postMinutes.coerceIn(5, 180))
            .apply()
        _settings.value = readSettings()
    }

    fun setManualEventLockActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_MANUAL_EVENT_LOCK, active).apply()
        _settings.value = readSettings()
    }

    fun toggleFavoriteSymbol(symbol: String) {
        val clean = symbol.trim().uppercase(Locale.US)
        if (clean.isEmpty()) return
        val current = _settings.value.favoriteSymbols.toMutableSet()
        if (current.contains(clean)) {
            current.remove(clean)
        } else {
            current.add(clean)
        }
        prefs.edit().putStringSet(KEY_FAVORITE_SYMBOLS, current).apply()
        _settings.value = readSettings()
    }

    fun setShowEma(visible: Boolean, timeframe: Timeframe? = null) {
        val editor = prefs.edit().putBoolean(KEY_SHOW_EMA, visible)
        if (timeframe != null && _settings.value.perTimeframeIndicators) {
            editor.putBoolean("${KEY_SHOW_EMA}_${timeframe.binanceInterval}", visible)
        }
        editor.apply()
        _settings.value = readSettings()
    }

    fun setShowSma(visible: Boolean, timeframe: Timeframe? = null) {
        val editor = prefs.edit().putBoolean(KEY_SHOW_SMA, visible)
        if (timeframe != null && _settings.value.perTimeframeIndicators) {
            editor.putBoolean("${KEY_SHOW_SMA}_${timeframe.binanceInterval}", visible)
        }
        editor.apply()
        _settings.value = readSettings()
    }

    fun setShowBollingerBands(visible: Boolean, timeframe: Timeframe? = null) {
        val editor = prefs.edit().putBoolean(KEY_SHOW_BB, visible)
        if (timeframe != null && _settings.value.perTimeframeIndicators) {
            editor.putBoolean("${KEY_SHOW_BB}_${timeframe.binanceInterval}", visible)
        }
        editor.apply()
        _settings.value = readSettings()
    }

    fun setShowRsi(visible: Boolean, timeframe: Timeframe? = null) {
        val editor = prefs.edit().putBoolean(KEY_SHOW_RSI, visible)
        if (timeframe != null && _settings.value.perTimeframeIndicators) {
            editor.putBoolean("${KEY_SHOW_RSI}_${timeframe.binanceInterval}", visible)
        }
        editor.apply()
        _settings.value = readSettings()
    }

    fun setShowMacd(visible: Boolean, timeframe: Timeframe? = null) {
        val editor = prefs.edit().putBoolean(KEY_SHOW_MACD, visible)
        if (timeframe != null && _settings.value.perTimeframeIndicators) {
            editor.putBoolean("${KEY_SHOW_MACD}_${timeframe.binanceInterval}", visible)
        }
        editor.apply()
        _settings.value = readSettings()
    }

    fun setShowAtr(visible: Boolean, timeframe: Timeframe? = null) {
        val editor = prefs.edit().putBoolean(KEY_SHOW_ATR, visible)
        if (timeframe != null && _settings.value.perTimeframeIndicators) {
            editor.putBoolean("${KEY_SHOW_ATR}_${timeframe.binanceInterval}", visible)
        }
        editor.apply()
        _settings.value = readSettings()
    }

    fun setShowVolume(visible: Boolean, timeframe: Timeframe? = null) {
        val editor = prefs.edit().putBoolean(KEY_SHOW_VOLUME, visible)
        if (timeframe != null && _settings.value.perTimeframeIndicators) {
            editor.putBoolean("${KEY_SHOW_VOLUME}_${timeframe.binanceInterval}", visible)
        }
        editor.apply()
        _settings.value = readSettings()
    }

    fun setShowSupportResistance(visible: Boolean, timeframe: Timeframe? = null) {
        val editor = prefs.edit().putBoolean(KEY_SHOW_SR, visible)
        if (timeframe != null && _settings.value.perTimeframeIndicators) {
            editor.putBoolean("${KEY_SHOW_SR}_${timeframe.binanceInterval}", visible)
        }
        editor.apply()
        _settings.value = readSettings()
    }

    fun setShowSmc(visible: Boolean, timeframe: Timeframe? = null) {
        val editor = prefs.edit().putBoolean(KEY_SHOW_SMC, visible)
        if (timeframe != null && _settings.value.perTimeframeIndicators) {
            editor.putBoolean("${KEY_SHOW_SMC}_${timeframe.binanceInterval}", visible)
        }
        editor.apply()
        _settings.value = readSettings()
    }

    fun setShowIct(visible: Boolean, timeframe: Timeframe? = null) {
        val editor = prefs.edit().putBoolean(KEY_SHOW_ICT, visible)
        if (timeframe != null && _settings.value.perTimeframeIndicators) {
            editor.putBoolean("${KEY_SHOW_ICT}_${timeframe.binanceInterval}", visible)
        }
        editor.apply()
        _settings.value = readSettings()
    }

    fun setShowAmd(visible: Boolean, timeframe: Timeframe? = null) {
        val editor = prefs.edit().putBoolean(KEY_SHOW_AMD, visible)
        if (timeframe != null && _settings.value.perTimeframeIndicators) {
            editor.putBoolean("${KEY_SHOW_AMD}_${timeframe.binanceInterval}", visible)
        }
        editor.apply()
        _settings.value = readSettings()
    }

    fun setPerTimeframeIndicators(enabled: Boolean, activeTimeframe: Timeframe? = null) {
        val cur = _settings.value
        val editor = prefs.edit().putBoolean(KEY_PER_TF_INDICATORS, enabled)
        if (enabled && activeTimeframe != null) {
            val tfKey = activeTimeframe.binanceInterval
            editor.putBoolean("${KEY_SHOW_EMA}_$tfKey", cur.showEma)
            editor.putBoolean("${KEY_SHOW_SMA}_$tfKey", cur.showSma)
            editor.putBoolean("${KEY_SHOW_BB}_$tfKey", cur.showBollingerBands)
            editor.putBoolean("${KEY_SHOW_VOLUME}_$tfKey", cur.showVolume)
            editor.putBoolean("${KEY_SHOW_RSI}_$tfKey", cur.showRsi)
            editor.putBoolean("${KEY_SHOW_MACD}_$tfKey", cur.showMacd)
            editor.putBoolean("${KEY_SHOW_ATR}_$tfKey", cur.showAtr)
            editor.putBoolean("${KEY_SHOW_SR}_$tfKey", cur.showSupportResistance)
            editor.putBoolean("${KEY_SHOW_SMC}_$tfKey", cur.showSmc)
            editor.putBoolean("${KEY_SHOW_ICT}_$tfKey", cur.showIct)
            editor.putBoolean("${KEY_SHOW_AMD}_$tfKey", cur.showAmd)
        }
        editor.apply()
        _settings.value = readSettings()
    }

    fun applyTimeframeIndicatorProfile(timeframe: Timeframe) {
        if (!_settings.value.perTimeframeIndicators) return
        val tfKey = timeframe.binanceInterval
        if (!prefs.contains("${KEY_SHOW_EMA}_$tfKey")) return
        prefs.edit()
            .putBoolean(KEY_SHOW_EMA, prefs.getBoolean("${KEY_SHOW_EMA}_$tfKey", _settings.value.showEma))
            .putBoolean(KEY_SHOW_SMA, prefs.getBoolean("${KEY_SHOW_SMA}_$tfKey", _settings.value.showSma))
            .putBoolean(KEY_SHOW_BB, prefs.getBoolean("${KEY_SHOW_BB}_$tfKey", _settings.value.showBollingerBands))
            .putBoolean(KEY_SHOW_VOLUME, prefs.getBoolean("${KEY_SHOW_VOLUME}_$tfKey", _settings.value.showVolume))
            .putBoolean(KEY_SHOW_RSI, prefs.getBoolean("${KEY_SHOW_RSI}_$tfKey", _settings.value.showRsi))
            .putBoolean(KEY_SHOW_MACD, prefs.getBoolean("${KEY_SHOW_MACD}_$tfKey", _settings.value.showMacd))
            .putBoolean(KEY_SHOW_ATR, prefs.getBoolean("${KEY_SHOW_ATR}_$tfKey", _settings.value.showAtr))
            .putBoolean(KEY_SHOW_SR, prefs.getBoolean("${KEY_SHOW_SR}_$tfKey", _settings.value.showSupportResistance))
            .putBoolean(KEY_SHOW_SMC, prefs.getBoolean("${KEY_SHOW_SMC}_$tfKey", _settings.value.showSmc))
            .putBoolean(KEY_SHOW_ICT, prefs.getBoolean("${KEY_SHOW_ICT}_$tfKey", _settings.value.showIct))
            .putBoolean(KEY_SHOW_AMD, prefs.getBoolean("${KEY_SHOW_AMD}_$tfKey", _settings.value.showAmd))
            .apply()
        _settings.value = readSettings()
    }

    fun updateIndicatorParams(
        ema1Period: Int = _settings.value.ema1Period,
        ema2Period: Int = _settings.value.ema2Period,
        ema3Period: Int = _settings.value.ema3Period,
        sma1Period: Int = _settings.value.sma1Period,
        sma2Period: Int = _settings.value.sma2Period,
        bbPeriod: Int = _settings.value.bbPeriod,
        bbStdDev: Double = _settings.value.bbStdDev,
        rsiPeriod: Int = _settings.value.rsiPeriod,
        macdFast: Int = _settings.value.macdFast,
        macdSlow: Int = _settings.value.macdSlow,
        macdSignal: Int = _settings.value.macdSignal,
        atrPeriod: Int = _settings.value.atrPeriod
    ) {
        val safeFast = macdFast.coerceIn(2, 100)
        val safeSlow = macdSlow.coerceIn(safeFast + 1, 200)
        prefs.edit()
            .putInt(KEY_EMA1_PERIOD, ema1Period.coerceIn(2, 500))
            .putInt(KEY_EMA2_PERIOD, ema2Period.coerceIn(2, 500))
            .putInt(KEY_EMA3_PERIOD, ema3Period.coerceIn(2, 500))
            .putInt(KEY_SMA1_PERIOD, sma1Period.coerceIn(2, 500))
            .putInt(KEY_SMA2_PERIOD, sma2Period.coerceIn(2, 500))
            .putInt(KEY_BB_PERIOD, bbPeriod.coerceIn(2, 200))
            .putString(KEY_BB_STDDEV, bbStdDev.coerceIn(0.5, 5.0).toString())
            .putInt(KEY_RSI_PERIOD, rsiPeriod.coerceIn(2, 100))
            .putInt(KEY_MACD_FAST, safeFast)
            .putInt(KEY_MACD_SLOW, safeSlow)
            .putInt(KEY_MACD_SIGNAL, macdSignal.coerceIn(2, 100))
            .putInt(KEY_ATR_PERIOD, atrPeriod.coerceIn(2, 100))
            .apply()
        _settings.value = readSettings()
    }

    fun setShowSignalOverlay(visible: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_SIGNAL_OVERLAY, visible).apply()
        _settings.value = readSettings()
    }

    fun setShowSignalHistory(visible: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_SIGNAL_HISTORY, visible).apply()
        _settings.value = readSettings()
    }

    fun setShowTradePlanOverlay(visible: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_TRADE_PLAN_OVERLAY, visible).apply()
        _settings.value = readSettings()
    }

    fun toggleTradePlanOverlay() {
        val current = _settings.value.showTradePlanOverlay
        setShowTradePlanOverlay(!current)
    }

    fun setDarkMode(dark: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_MODE, dark).apply()
        _settings.value = readSettings()
    }

    fun setPrimaryAiProvider(mode: AiProviderMode) {
        prefs.edit().putString(KEY_PRIMARY_AI_PROVIDER, mode.name).apply()
        _settings.value = readSettings()
    }

    fun setEnableAiFallback(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLE_AI_FALLBACK, enabled).apply()
        _settings.value = readSettings()
    }

    fun saveCustomApiConfig(
        endpoint: String,
        apiKeyInput: String?,
        model: String,
        format: CustomApiFormat
    ) {
        val editor = prefs.edit()
            .putString(KEY_CUSTOM_API_ENDPOINT, endpoint.trim().ifEmpty { "https://api.openai.com/v1" })
            .putString(KEY_CUSTOM_API_MODEL, model.trim().ifEmpty { "gpt-4o-mini" })
            .putString(KEY_CUSTOM_API_FORMAT, format.name)

        if (apiKeyInput != null && apiKeyInput.isNotBlank() && !apiKeyInput.startsWith("••••")) {
            editor.putString(KEY_CUSTOM_API_KEY_OBF, encodeSecret(apiKeyInput.trim()))
        }
        editor.apply()
        _settings.value = readSettings()
    }

    fun clearCustomApiKey() {
        prefs.edit().remove(KEY_CUSTOM_API_KEY_OBF).apply()
        _settings.value = readSettings()
    }

    fun saveGeminiCustomConfig(apiKeyInput: String?, model: String) {
        val editor = prefs.edit()
            .putString(KEY_GEMINI_CUSTOM_MODEL, model.trim().ifEmpty { "gemini-3.5-flash" })
        if (apiKeyInput != null && apiKeyInput.isNotBlank() && !apiKeyInput.startsWith("••••")) {
            editor.putString(KEY_GEMINI_CUSTOM_KEY_OBF, encodeSecret(apiKeyInput.trim()))
        }
        editor.apply()
        _settings.value = readSettings()
    }

    fun clearGeminiCustomKey() {
        prefs.edit().remove(KEY_GEMINI_CUSTOM_KEY_OBF).apply()
        _settings.value = readSettings()
    }

    fun setActiveAiProviderType(type: AiProviderType) {
        prefs.edit().putString(KEY_ACTIVE_AI_PROVIDER_TYPE, type.code).apply()
        when (type) {
            AiProviderType.GEMINI_BUILTIN -> setPrimaryAiProvider(AiProviderMode.BUILT_IN)
            AiProviderType.GEMINI_PERSONAL -> setPrimaryAiProvider(AiProviderMode.GEMINI_API)
            AiProviderType.OPENAI, AiProviderType.OPENROUTER, AiProviderType.CUSTOM_OPENAI -> setPrimaryAiProvider(AiProviderMode.CUSTOM_API)
        }
    }

    fun setDailyTokenBudget(budget: Long) {
        prefs.edit().putLong(KEY_DAILY_TOKEN_BUDGET, budget.coerceAtLeast(0L)).apply()
        _settings.value = readSettings()
    }

    fun setMonthlyTokenBudget(budget: Long) {
        prefs.edit().putLong(KEY_MONTHLY_TOKEN_BUDGET, budget.coerceAtLeast(0L)).apply()
        _settings.value = readSettings()
    }

    fun setBudgetWarningThreshold(percent: Int) {
        prefs.edit().putInt(KEY_BUDGET_WARNING_THRESHOLD, percent.coerceIn(1, 100)).apply()
        _settings.value = readSettings()
    }

    fun setBlockWhenBudgetExceeded(blocked: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_WHEN_BUDGET_EXCEEDED, blocked).apply()
        _settings.value = readSettings()
    }

    fun setChartVisionMode(mode: ChartVisionMode) {
        prefs.edit().putString(KEY_CHART_VISION_MODE, mode.code).apply()
        _settings.value = readSettings()
    }

    fun setCustomApiSupportsVision(supports: Boolean) {
        prefs.edit().putBoolean(KEY_CUSTOM_API_SUPPORTS_VISION, supports).apply()
        _settings.value = readSettings()
    }

    fun setSelectedAiStrategy(strategy: AiStrategy) {
        prefs.edit().putString(KEY_SELECTED_AI_STRATEGY, strategy.id).apply()
        _settings.value = readSettings()
    }

    fun getProviderEndpoint(type: AiProviderType): String {
        return prefs.getString(KEY_PROVIDER_ENDPOINT_PREFIX + type.code, null)
            ?: when (type) {
                AiProviderType.GEMINI_BUILTIN, AiProviderType.GEMINI_PERSONAL -> type.defaultEndpoint
                AiProviderType.OPENAI -> "https://api.openai.com/v1"
                AiProviderType.OPENROUTER -> "https://openrouter.ai/api/v1"
                AiProviderType.CUSTOM_OPENAI -> _settings.value.customApiEndpoint
            }
    }

    fun getProviderModel(type: AiProviderType): String {
        return prefs.getString(KEY_PROVIDER_MODEL_PREFIX + type.code, null)
            ?: when (type) {
                AiProviderType.GEMINI_BUILTIN -> "gemini-2.5-flash"
                AiProviderType.GEMINI_PERSONAL -> _settings.value.geminiCustomModel.ifBlank { "gemini-2.5-flash" }
                AiProviderType.OPENAI -> "gpt-4o-mini"
                AiProviderType.OPENROUTER -> "deepseek/deepseek-chat"
                AiProviderType.CUSTOM_OPENAI -> _settings.value.customApiModel.ifBlank { "custom-model" }
            }
    }

    fun getProviderApiKey(type: AiProviderType): String {
        val specific = prefs.getString(KEY_PROVIDER_KEY_PREFIX + type.code, null)
        if (!specific.isNullOrBlank()) {
            return decodeSecret(specific)
        }
        return when (type) {
            AiProviderType.GEMINI_PERSONAL -> getGeminiCustomApiKey()
            AiProviderType.OPENAI, AiProviderType.OPENROUTER, AiProviderType.CUSTOM_OPENAI -> getCustomApiKey()
            AiProviderType.GEMINI_BUILTIN -> ""
        }
    }

    fun hasProviderApiKey(type: AiProviderType): Boolean {
        return getProviderApiKey(type).isNotBlank()
    }

    fun saveProviderConfig(
        type: AiProviderType,
        endpoint: String,
        apiKeyInput: String?,
        model: String
    ) {
        val editor = prefs.edit()
            .putString(KEY_PROVIDER_ENDPOINT_PREFIX + type.code, endpoint.trim().ifEmpty { type.defaultEndpoint })
            .putString(KEY_PROVIDER_MODEL_PREFIX + type.code, model.trim().ifEmpty { type.defaultModel })

        if (apiKeyInput != null && apiKeyInput.isNotBlank() && !apiKeyInput.startsWith("••••")) {
            editor.putString(KEY_PROVIDER_KEY_PREFIX + type.code, encodeSecret(apiKeyInput.trim()))
        }
        editor.apply()

        // Sync legacy fields for backward compatibility
        when (type) {
            AiProviderType.GEMINI_PERSONAL -> {
                saveGeminiCustomConfig(apiKeyInput, model)
            }
            AiProviderType.OPENAI, AiProviderType.OPENROUTER, AiProviderType.CUSTOM_OPENAI -> {
                saveCustomApiConfig(endpoint, apiKeyInput, model, type.format)
            }
            else -> {}
        }
        _settings.value = readSettings()
    }

    fun clearProviderApiKey(type: AiProviderType) {
        prefs.edit().remove(KEY_PROVIDER_KEY_PREFIX + type.code).apply()
        when (type) {
            AiProviderType.GEMINI_PERSONAL -> clearGeminiCustomKey()
            AiProviderType.OPENAI, AiProviderType.OPENROUTER, AiProviderType.CUSTOM_OPENAI -> clearCustomApiKey()
            else -> {}
        }
        _settings.value = readSettings()
    }

    fun saveSyncedModelsJson(type: AiProviderType, json: String) {
        prefs.edit().putString(KEY_SYNCED_MODELS_JSON_PREFIX + type.code, json).apply()
    }

    fun getSyncedModelsJson(type: AiProviderType): String {
        return prefs.getString(KEY_SYNCED_MODELS_JSON_PREFIX + type.code, "") ?: ""
    }


    internal fun getCustomApiKey(): String {
        val obf = prefs.getString(KEY_CUSTOM_API_KEY_OBF, "") ?: ""
        return decodeSecret(obf)
    }

    internal fun getGeminiCustomApiKey(): String {
        val obf = prefs.getString(KEY_GEMINI_CUSTOM_KEY_OBF, "") ?: ""
        return decodeSecret(obf)
    }

    private fun encodeSecret(plain: String): String {
        if (plain.isEmpty()) return ""
        val bytes = plain.toByteArray(Charsets.UTF_8)
        val xored = ByteArray(bytes.size) { i -> (bytes[i].toInt() xor 0x5A).toByte() }
        return Base64.encodeToString(xored, Base64.NO_WRAP)
    }

    private fun decodeSecret(encoded: String): String {
        if (encoded.isEmpty()) return ""
        return try {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            val unxored = ByteArray(raw.size) { i -> (raw[i].toInt() xor 0x5A).toByte() }
            String(unxored, Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    fun setSelectedMarketProvider(provider: MarketProvider) {
        prefs.edit().putString(KEY_SELECTED_MARKET_PROVIDER, provider.code).apply()
        _settings.value = readSettings()
    }

    fun saveExnessConfig(config: ExnessConfig) {
        prefs.edit()
            .putString(KEY_EXNESS_GATEWAY_URL, config.gatewayUrl)
            .putString(KEY_EXNESS_SESSION_TOKEN_OBF, encodeSecret(config.sessionToken))
            .putString(KEY_EXNESS_ACCOUNT_TIER, config.accountTier)
            .putString(KEY_EXNESS_SERVER_ENV, config.serverEnvironment)
            .putBoolean(KEY_EXNESS_IS_ENABLED, config.isEnabled)
            .apply()
        _settings.value = readSettings()
    }
}
