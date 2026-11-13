package com.example.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.sync.AiModelInfo
import com.example.ai.usage.AiUsageDashboardData
import com.example.market.calendar.CurrencyPairBiasEngine
import com.example.market.candle.Timeframe
import com.example.market.candle.formatSymbolPair
import com.example.market.symbols.SymbolRepository
import com.example.market.websocket.WsConnectionStatus
import com.example.storage.preferences.AiProviderMode
import com.example.storage.preferences.AiProviderType
import com.example.storage.preferences.CalendarTimezoneOption
import com.example.storage.preferences.CustomApiFormat
import com.example.storage.preferences.TerminalSettings
import com.example.storage.watchlist.AiTokenUsageEntity
import com.example.ui.settings.AiConfigurationSection
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BullishGreen
import com.example.ui.theme.CyanAccent
import com.example.ui.components.premiumPanel
import com.example.ui.theme.NeutralAmber
import java.util.Locale

/**
 * Sub-pages for Settings Navigation ala TradingView
 */
enum class SettingsSubPage(val title: String) {
    WATCHLIST("Daftar Pantau"),
    CHART("Chart & Indikator"),
    SIMULATION("Akun Simulasi"),
    AI_CONFIG("Konfigurasi AI"),
    NOTIFICATIONS("Notifikasi Push"),
    MARKET_DATA_PROVIDER("Data Pasar & Exness")
}

/**
 * Settings & Profile Screen redesign adhering to TradingView mobile UX specifications:
 * 1. Profile Header: Circular Avatar, Editable Name ("Trader"), Tier badge "BASIC" (dashed border).
 * 2. Menu List: Tema, Layar Tetap Menyala, Daftar Pantau, Chart, Notifikasi, Bahasa, Info Kontak, Tentang, Hapus Akun.
 * 3. Sub-pages for Chart & Indicators, Simulation Account & Risk, AI Provider & Backup, Watchlist, Notifications.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: TerminalSettings,
    connectionStatus: WsConnectionStatus,
    cachedSymbolCount: Int,
    isTestingAiConnection: Boolean,
    aiConnectionTestStatus: String?,
    onSetDefaultTimeframe: (Timeframe) -> Unit,
    onSetDefaultSymbol: (String) -> Unit,
    onSetAutoAiAnalysis: (Boolean) -> Unit,
    onSetAutoSignalMinGrade: (String) -> Unit = {},
    onSetNotificationsEnabled: (Boolean) -> Unit = {},
    onSetNotifyNewSignals: (Boolean) -> Unit = {},
    onSetNotifyBotEntries: (Boolean) -> Unit = {},
    onSetNotifyTpSlHits: (Boolean) -> Unit = {},
    onSetNotifySignalInvalidation: (Boolean) -> Unit = {},
    onSetNotifyRiskEvents: (Boolean) -> Unit = {},
    onSetForegroundMonitoringEnabled: (Boolean) -> Unit = {},
    onSetPrimaryAiProvider: (AiProviderMode) -> Unit,
    onSetEnableAiFallback: (Boolean) -> Unit,
    onSaveCustomApiConfig: (String, String?, String, CustomApiFormat) -> Unit,
    onClearCustomApiKey: () -> Unit,
    onSaveGeminiCustomConfig: (String?, String) -> Unit,
    onClearGeminiCustomKey: () -> Unit,
    onTestAiConnection: () -> Unit,
    aiUsageDashboard: AiUsageDashboardData = AiUsageDashboardData(),
    recentAiUsageRecords: List<AiTokenUsageEntity> = emptyList(),
    syncedModelsMap: Map<AiProviderType, List<AiModelInfo>> = emptyMap(),
    isSyncingModels: Boolean = false,
    modelSyncStatusMessage: String? = null,
    onSyncModels: (AiProviderType) -> Unit = {},
    onSelectModel: (AiProviderType, String) -> Unit = { _, _ -> },
    onSaveProviderConfig: (AiProviderType, String, String?, String) -> Unit = { _, _, _, _ -> },
    onClearProviderApiKey: (AiProviderType) -> Unit = {},
    onSetActiveAiProviderType: (AiProviderType) -> Unit = {},
    onSetDailyTokenBudget: (Long) -> Unit = {},
    onSetMonthlyTokenBudget: (Long) -> Unit = {},
    onSetBudgetWarningThreshold: (Int) -> Unit = {},
    onSetBlockWhenBudgetExceeded: (Boolean) -> Unit = {},
    onClearAllTokenUsage: () -> Unit = {},
    initialSubPage: String? = null,
    onSetShowEma: (Boolean) -> Unit,
    onSetShowRsi: (Boolean) -> Unit,
    onSetShowMacd: (Boolean) -> Unit,
    onSetShowVolume: (Boolean) -> Unit,
    onSetShowSupportResistance: (Boolean) -> Unit,
    onSetShowSma: (Boolean) -> Unit = {},
    onSetShowBollingerBands: (Boolean) -> Unit = {},
    onSetShowAtr: (Boolean) -> Unit = {},
    onSetPerTimeframeIndicators: (Boolean) -> Unit = {},
    simulationStartingBalanceUsdt: Double = 10_000.0,
    simulationEquityUsdt: Double = 10_000.0,
    simulationRiskPerTradePercent: Double = 1.0,
    simulationMaxDailyLossPercent: Double = 3.0,
    onApplySimulationStartingBalance: (Double, Boolean) -> Unit = { _, _ -> },
    onUpdateSimulationRiskConfig: (Double, Double) -> Unit = { _, _ -> },
    onSetDarkMode: (Boolean) -> Unit,
    onForceReconnect: () -> Unit,
    onRefreshExchangeSymbols: () -> Unit,
    onSetCalendarTimezone: (CalendarTimezoneOption) -> Unit = {},
    onToggleWatchedCurrency: (String) -> Unit = {},
    onSetUserName: (String) -> Unit = {},
    onSetThemeMode: (String) -> Unit = {},
    onSetKeepScreenOn: (Boolean) -> Unit = {},
    onSetLanguage: (String) -> Unit = {},
    onSetChartVisionMode: (com.example.storage.preferences.ChartVisionMode) -> Unit = {},
    onSetCustomApiSupportsVision: (Boolean) -> Unit = {},
    onSelectAiStrategy: (com.example.ai.strategy.AiStrategy) -> Unit = {},
    onSelectMarketProvider: (com.example.market.candle.MarketProvider) -> Unit = {},
    onSaveExnessConfig: (com.example.market.exness.ExnessConfig) -> Unit = {},
    exnessConnectionStatus: com.example.market.provider.ProviderConnectionStatus = com.example.market.provider.ProviderConnectionStatus.Unconfigured("Gateway belum dikonfigurasi"),
    modifier: Modifier = Modifier
) {
    var activeSubPage by rememberSaveable { mutableStateOf<String?>(initialSubPage) }
    LaunchedEffect(initialSubPage) {
        if (initialSubPage != null) {
            activeSubPage = initialSubPage
        }
    }

    var showEditNameDialog by rememberSaveable { mutableStateOf(false) }
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showContactDialog by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteAccountDialog by rememberSaveable { mutableStateOf(false) }
    var showLoginComingSoonDialog by rememberSaveable { mutableStateOf(false) }

    // Sub-page back handler
    BackHandler(enabled = activeSubPage != null) {
        activeSubPage = null
    }

    var startingBalanceInput by remember(simulationStartingBalanceUsdt) {
        mutableStateOf(String.format(Locale.US, "%.0f", simulationStartingBalanceUsdt))
    }
    var riskPercentInput by remember(simulationRiskPerTradePercent) {
        mutableStateOf(String.format(Locale.US, "%.1f", simulationRiskPerTradePercent))
    }
    var maxDailyLossInput by remember(simulationMaxDailyLossPercent) {
        mutableStateOf(String.format(Locale.US, "%.1f", simulationMaxDailyLossPercent))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
            .testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (activeSubPage != null) {
            // ================= SUB-PAGE VIEW =================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { activeSubPage = null }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Kembali ke Pengaturan",
                    tint = CyanAccent,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Kembali ke Pengaturan",
                    style = MaterialTheme.typography.labelLarge,
                    color = CyanAccent,
                    fontWeight = FontWeight.SemiBold
                )
            }

            when (activeSubPage) {
                SettingsSubPage.WATCHLIST.name -> {
                    SettingsSectionCard(title = "DAFTAR PANTAU (WATCHLIST)") {
                        Text(
                            text = "Simbol Default (${formatSymbolPair(settings.defaultSymbol)})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SymbolRepository.DEFAULT_PAIRS.forEach { sym ->
                                val selected = settings.defaultSymbol.equals(sym, ignoreCase = true)
                                Surface(
                                    color = if (selected) CyanAccent else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .clickable { onSetDefaultSymbol(sym) }
                                        .testTag("settings_default_sym_$sym")
                                ) {
                                    Text(
                                        text = formatSymbolPair(sym),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) Color(0xFF001F26) else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "Cached Exchange Symbols: $cachedSymbolCount pairs",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Sinkronisasi daftar pair Binance exchangeInfo untuk pencarian cepat",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = onRefreshExchangeSymbols,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = CyanAccent
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("refresh_symbols_button")
                            ) {
                                Text(
                                    text = "Sync Symbols",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                SettingsSubPage.CHART.name -> {
                    SettingsSectionCard(title = "TIMEFRAME DEFAULT") {
                        Text(
                            text = "Timeframe Default Chart",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Timeframe.entries.forEach { tf ->
                                val selected = settings.defaultTimeframe == tf
                                Surface(
                                    color = if (selected) CyanAccent else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .clickable { onSetDefaultTimeframe(tf) }
                                        .testTag("settings_default_tf_${tf.binanceInterval}")
                                ) {
                                    Text(
                                        text = tf.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) Color(0xFF001F26) else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }

                    SettingsSectionCard(title = "INDIKATOR TEKNIKAL") {
                        SettingsToggleRow(
                            title = "Simpan Indikator per Timeframe",
                            subtitle = "Simpan preferensi on/off indikator secara terpisah untuk tiap timeframe",
                            checked = settings.perTimeframeIndicators,
                            onCheckedChange = onSetPerTimeframeIndicators,
                            tag = "settings_per_tf_indicators_switch"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SettingsToggleRow(
                            title = "EMA (${settings.ema1Period} / ${settings.ema2Period} / ${settings.ema3Period})",
                            subtitle = "Overlay Exponential Moving Averages pada candlestick chart",
                            checked = settings.showEma,
                            onCheckedChange = onSetShowEma,
                            tag = "settings_ema_switch"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SettingsToggleRow(
                            title = "SMA (${settings.sma1Period} / ${settings.sma2Period})",
                            subtitle = "Overlay Simple Moving Averages pada candlestick chart",
                            checked = settings.showSma,
                            onCheckedChange = onSetShowSma,
                            tag = "settings_sma_switch"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SettingsToggleRow(
                            title = "Bollinger Bands (${settings.bbPeriod}, ${settings.bbStdDev})",
                            subtitle = "Overlay pita volatilitas Upper, Basis, dan Lower",
                            checked = settings.showBollingerBands,
                            onCheckedChange = onSetShowBollingerBands,
                            tag = "settings_bb_switch"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SettingsToggleRow(
                            title = "RSI (${settings.rsiPeriod})",
                            subtitle = "Tampilkan Relative Strength Index sub-chart",
                            checked = settings.showRsi,
                            onCheckedChange = onSetShowRsi,
                            tag = "settings_rsi_switch"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SettingsToggleRow(
                            title = "MACD (${settings.macdFast}, ${settings.macdSlow}, ${settings.macdSignal})",
                            subtitle = "Tampilkan MACD line, signal line, dan histogram",
                            checked = settings.showMacd,
                            onCheckedChange = onSetShowMacd,
                            tag = "settings_macd_switch"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SettingsToggleRow(
                            title = "ATR (${settings.atrPeriod})",
                            subtitle = "Tampilkan nilai Average True Range pada legend chart",
                            checked = settings.showAtr,
                            onCheckedChange = onSetShowAtr,
                            tag = "settings_atr_switch"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SettingsToggleRow(
                            title = "Volume Histogram",
                            subtitle = "Tampilkan batang volume halus di chart pane utama",
                            checked = settings.showVolume,
                            onCheckedChange = onSetShowVolume,
                            tag = "settings_volume_switch"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SettingsToggleRow(
                            title = "Support & Resistance Lines",
                            subtitle = "Plot klaster garis level harga Support & Resistance lokal",
                            checked = settings.showSupportResistance,
                            onCheckedChange = onSetShowSupportResistance,
                            tag = "settings_sr_switch"
                        )
                    }

                    SettingsSectionCard(title = "PENGATURAN KALENDER EKONOMI") {
                        Text(
                            text = "Zona Waktu Kalender",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Pilih zona waktu untuk menampilkan jadwal event ekonomi (aktif: ${settings.calendarTimezone.displayLabel})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CalendarTimezoneOption.entries.forEach { tzOpt ->
                                val isSelected = settings.calendarTimezone == tzOpt
                                Surface(
                                    color = if (isSelected) CyanAccent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .border(
                                            1.dp,
                                            if (isSelected) CyanAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .clickable { onSetCalendarTimezone(tzOpt) }
                                        .testTag("settings_tz_${tzOpt.code.lowercase()}")
                                ) {
                                    Text(
                                        text = tzOpt.shortLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Watched Currencies (Bias Pair)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Pilih mata uang untuk menampilkan badge bias pergerakan pair saat rilis High Impact",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CurrencyPairBiasEngine.ALL_WATCHABLE_CURRENCIES.forEach { ccy ->
                                val isWatched = settings.watchedCurrencies.contains(ccy)
                                Surface(
                                    color = if (isWatched) BullishGreen.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .border(
                                            1.dp,
                                            if (isWatched) BullishGreen else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .clickable { onToggleWatchedCurrency(ccy) }
                                        .testTag("settings_watched_ccy_${ccy.lowercase()}")
                                ) {
                                    Text(
                                        text = if (isWatched) "✓ $ccy" else "+ $ccy",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isWatched) BullishGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (isWatched) FontWeight.Bold else FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                SettingsSubPage.SIMULATION.name -> {
                    SettingsSectionCard(title = "AKUN SIMULASI VIRTUAL & RISIKO") {
                        Text(
                            text = "LUMAVUN Chart · Mode Read-Only & Virtual Paper Trading (Tanpa Kredensial Binance)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Saldo Awal Simulasi",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "$${String.format(Locale.US, "%,.2f", simulationStartingBalanceUsdt)} USDT",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CyanAccent,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Ekuitas Virtual Saat Ini",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "$${String.format(Locale.US, "%,.2f", simulationEquityUsdt)} USDT",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (simulationEquityUsdt >= simulationStartingBalanceUsdt) BullishGreen else BearishRed,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = startingBalanceInput,
                                onValueChange = { startingBalanceInput = it },
                                label = { Text("Saldo Awal (USDT)") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("profile_starting_balance_input")
                            )
                            Button(
                                onClick = {
                                    val parsed = startingBalanceInput.toDoubleOrNull()
                                    if (parsed != null && parsed >= 10.0) {
                                        onApplySimulationStartingBalance(parsed, true)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyanAccent,
                                    contentColor = Color(0xFF001F26)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("profile_apply_balance_button")
                            ) {
                                Text(
                                    text = "Set Saldo",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = riskPercentInput,
                                onValueChange = { riskPercentInput = it },
                                label = { Text("Risiko/Trade (%)") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("profile_risk_percent_input")
                            )
                            OutlinedTextField(
                                value = maxDailyLossInput,
                                onValueChange = { maxDailyLossInput = it },
                                label = { Text("Max Daily Loss (%)") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("profile_max_daily_loss_input")
                            )
                            Button(
                                onClick = {
                                    val riskPct = riskPercentInput.toDoubleOrNull()?.coerceIn(0.1, 10.0) ?: simulationRiskPerTradePercent
                                    val maxLossPct = maxDailyLossInput.toDoubleOrNull()?.coerceIn(0.5, 25.0) ?: simulationMaxDailyLossPercent
                                    onUpdateSimulationRiskConfig(riskPct, maxLossPct)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = CyanAccent
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("profile_save_risk_button")
                            ) {
                                Text(
                                    text = "Simpan Risiko",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                SettingsSubPage.AI_CONFIG.name -> {
                    AiConfigurationSection(
                        settings = settings,
                        isTestingAiConnection = isTestingAiConnection,
                        aiConnectionTestStatus = aiConnectionTestStatus,
                        aiUsageDashboard = aiUsageDashboard,
                        recentAiUsageRecords = recentAiUsageRecords,
                        syncedModelsMap = syncedModelsMap,
                        isSyncingModels = isSyncingModels,
                        modelSyncStatusMessage = modelSyncStatusMessage,
                        onSetPrimaryAiProvider = onSetPrimaryAiProvider,
                        onSetEnableAiFallback = onSetEnableAiFallback,
                        onSaveCustomApiConfig = onSaveCustomApiConfig,
                        onClearCustomApiKey = onClearCustomApiKey,
                        onSaveGeminiCustomConfig = onSaveGeminiCustomConfig,
                        onClearGeminiCustomKey = onClearGeminiCustomKey,
                        onTestAiConnection = onTestAiConnection,
                        onSyncModels = onSyncModels,
                        onSelectModel = onSelectModel,
                        onSaveProviderConfig = onSaveProviderConfig,
                        onClearProviderApiKey = onClearProviderApiKey,
                        onSetActiveAiProviderType = onSetActiveAiProviderType,
                        onSetDailyTokenBudget = onSetDailyTokenBudget,
                        onSetMonthlyTokenBudget = onSetMonthlyTokenBudget,
                        onSetBudgetWarningThreshold = onSetBudgetWarningThreshold,
                        onSetBlockWhenBudgetExceeded = onSetBlockWhenBudgetExceeded,
                        onClearAllTokenUsage = onClearAllTokenUsage,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    SettingsSectionCard(title = "AUTO AI SIGNAL & SETUP QUALITY") {
                        SettingsToggleRow(
                            title = "Auto AI Signal Monitoring",
                            subtitle = "Monitoring struktur pasar secara kontinu, EMA, RSI, MACD, dan MTF alignment",
                            checked = settings.autoAiAnalysis,
                            onCheckedChange = onSetAutoAiAnalysis,
                            tag = "settings_auto_ai_switch"
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Minimum Setup Quality Grade for Auto Signal",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("A+", "A", "B").forEach { grade ->
                                val selected = settings.autoSignalMinGrade.equals(grade, ignoreCase = true)
                                Surface(
                                    color = if (selected) CyanAccent else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .clickable { onSetAutoSignalMinGrade(grade) }
                                        .testTag("settings_auto_grade_$grade")
                                ) {
                                    Text(
                                        text = "Grade $grade",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) Color(0xFF001F26) else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    SettingsSectionCard(title = "HYBRID CHART VISION (V2.2)") {
                        Text(
                            text = "Chart Vision Mode",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "When set to Auto, offscreen historical (~200c) and recent (~75c) chart images are generated on-demand when tapping 'Analyze Now'. Images are never captured continuously in the background.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            com.example.storage.preferences.ChartVisionMode.entries.forEach { mode ->
                                val selected = settings.chartVisionMode == mode
                                Surface(
                                    color = if (selected) CyanAccent else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .clickable { onSetChartVisionMode(mode) }
                                        .testTag("settings_chart_vision_mode_${mode.name.lowercase(Locale.US)}")
                                ) {
                                    Text(
                                        text = mode.displayLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) Color(0xFF001F26) else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        SettingsToggleRow(
                            title = "Custom API Supports Vision",
                            subtitle = "Enable multimodal image payloads for Custom OpenAI-compatible endpoints that support vision",
                            checked = settings.customApiSupportsVision,
                            onCheckedChange = onSetCustomApiSupportsVision,
                            tag = "settings_custom_api_vision_switch"
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    SettingsSectionCard(title = "AI STRATEGY HUB (V3.0A)") {
                        Text(
                            text = "Active AI Strategy",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Select the default institutional strategy applied during AI market analysis. Can also be switched directly on the chart.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            com.example.ai.strategy.AiStrategy.entries.forEach { strategy ->
                                val selected = settings.selectedAiStrategy == strategy
                                Surface(
                                    color = if (selected) CyanAccent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.dp, if (selected) CyanAccent else Color.Transparent),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectAiStrategy(strategy) }
                                        .testTag("settings_strategy_${strategy.id.lowercase(Locale.US)}")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = strategy.displayName,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = if (selected) CyanAccent else MaterialTheme.colorScheme.onSurface,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                                )
                                                if (selected) {
                                                    Surface(
                                                        color = CyanAccent.copy(alpha = 0.2f),
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            text = "ACTIVE",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = CyanAccent,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Text(
                                                text = strategy.shortDescription,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 11.sp
                                            )
                                        }
                                        if (selected) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .background(CyanAccent, CircleShape)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                SettingsSubPage.NOTIFICATIONS.name -> {
                    SettingsSectionCard(title = "NOTIFIKASI & MONITOR BACKGROUND") {
                        SettingsToggleRow(
                            title = "Enable Terminal Notifications",
                            subtitle = "Master switch notifikasi signal, eksekusi bot, TP/SL, dan event risk",
                            checked = settings.notificationsEnabled,
                            onCheckedChange = onSetNotificationsEnabled,
                            tag = "settings_notifications_master_switch"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SettingsToggleRow(
                            title = "Confirmed AI Signals",
                            subtitle = "Notifikasi ketika sinyal LONG atau SHORT valid terkonfirmasi",
                            checked = settings.notifyNewSignals,
                            onCheckedChange = onSetNotifyNewSignals,
                            tag = "settings_notify_signals_switch"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SettingsToggleRow(
                            title = "Triggered Bot Entries",
                            subtitle = "Notifikasi ketika Bot Trading virtual membuka posisi baru",
                            checked = settings.notifyBotEntries,
                            onCheckedChange = onSetNotifyBotEntries,
                            tag = "settings_notify_bot_entries_switch"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SettingsToggleRow(
                            title = "TP1 / TP2 / TP3 & Stop Loss Hits",
                            subtitle = "Notifikasi saat partial take-profit atau stop-loss tersentuh",
                            checked = settings.notifyTpSlHits,
                            onCheckedChange = onSetNotifyTpSlHits,
                            tag = "settings_notify_tpsl_switch"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SettingsToggleRow(
                            title = "Signal Invalidation & Expiry",
                            subtitle = "Notifikasi ketika setup aktif ter-invalisir sebelum trigger",
                            checked = settings.notifySignalInvalidation,
                            onCheckedChange = onSetNotifySignalInvalidation,
                            tag = "settings_notify_invalidation_switch"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SettingsToggleRow(
                            title = "Event Risk & Daily Loss Limit",
                            subtitle = "Notifikasi saat ada peringatan event ekonomi High Impact",
                            checked = settings.notifyRiskEvents,
                            onCheckedChange = onSetNotifyRiskEvents,
                            tag = "settings_notify_risk_switch"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SettingsToggleRow(
                            title = "Continuous Background Monitoring",
                            subtitle = "Jalankan Android Foreground Service pemantau saat Auto AI / Bot aktif",
                            checked = settings.foregroundMonitoringEnabled,
                            onCheckedChange = onSetForegroundMonitoringEnabled,
                            tag = "settings_foreground_monitor_switch"
                        )
                    }
                }

                SettingsSubPage.MARKET_DATA_PROVIDER.name -> {
                    MarketDataProviderSubPage(
                        settings = settings,
                        exnessStatus = exnessConnectionStatus,
                        onSelectProvider = onSelectMarketProvider,
                        onSaveExnessConfig = onSaveExnessConfig
                    )
                }
            }
        } else {
            // ================= TRADINGVIEW PROFILE & SETTINGS HUB =================

            // 0. Login atau Signup (Coming Soon)
            Surface(
                color = Color(0xFF101522),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF1E283D)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showLoginComingSoonDialog = true }
                    .testTag("profile_login_signup_banner")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(CyanAccent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Masuk atau Daftar",
                                tint = CyanAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Masuk atau Daftar",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFE2E8F0),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Sinkronisasi cloud & watchlist multi-device",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        color = NeutralAmber.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, NeutralAmber.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "Coming Soon",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeutralAmber,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // 1. Header Profil (atas)
            Surface(
                color = Color(0xFF101522),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1E2430), RoundedCornerShape(14.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Avatar bulat (placeholder stylish, bisa diklik untuk edit nama)
                        Box(
                            modifier = Modifier
                                .size(62.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E293B))
                                .border(2.dp, CyanAccent.copy(alpha = 0.5f), CircleShape)
                                .clickable { showEditNameDialog = true }
                                .testTag("profile_avatar_box"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Avatar Pengguna",
                                tint = CyanAccent,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        // Nama user editable & Badge Tier BASIC
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { showEditNameDialog = true }
                                    .testTag("profile_name_row")
                            ) {
                                Text(
                                    text = settings.userName.ifBlank { "Trader" },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit Nama",
                                    tint = CyanAccent,
                                    modifier = Modifier.size(15.dp)
                                )
                            }

                            // Badge tier "BASIC" (style dashed border, abu-abu)
                            Box(
                                modifier = Modifier
                                    .dashedBorder(
                                        width = 1.dp,
                                        color = Color(0xFF6B7280),
                                        cornerRadius = 4.dp
                                    )
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .testTag("tier_badge_basic")
                            ) {
                                Text(
                                    text = "BASIC",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF9CA3AF),
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFF1E2430), thickness = 1.dp)

                    // Connection Status & Reconnect Button (Pindahan dari Header Utama)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val statusDotColor = when (connectionStatus) {
                            WsConnectionStatus.LIVE -> BullishGreen
                            WsConnectionStatus.RECONNECTING -> NeutralAmber
                            WsConnectionStatus.DISCONNECTED -> BearishRed
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(statusDotColor)
                            )
                            Text(
                                text = "Koneksi Pasar: ${connectionStatus.badgeText}",
                                style = MaterialTheme.typography.labelMedium,
                                color = statusDotColor,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Surface(
                            color = CyanAccent.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .clickable { onForceReconnect() }
                                .testTag("profile_reconnect_button")
                        ) {
                            Text(
                                text = "Reconnect",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanAccent,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // 2. Menu List (bawah header, bergaya TradingView)
            Text(
                text = "PENGATURAN",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF6B7280),
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 🌙 Tema -> value di kanan (Gelap/Terang/Auto(mengikuti sistem hp)), klik buka dialog
                val themeLabel = when (settings.themeMode.uppercase(Locale.US)) {
                    "LIGHT" -> "Terang"
                    "AUTO" -> "Auto (mengikuti sistem hp)"
                    else -> "Gelap"
                }
                TradingViewMenuItem(
                    icon = Icons.Default.DarkMode,
                    iconTint = NeutralAmber,
                    title = "Tema",
                    valueText = themeLabel,
                    testTag = "menu_theme",
                    onClick = { showThemeDialog = true }
                )

                // 📊 Layar Tetap Menyala -> toggle switch di kanan
                TradingViewMenuToggleItem(
                    icon = Icons.Default.Smartphone,
                    iconTint = CyanAccent,
                    title = "Layar Tetap Menyala",
                    subtitle = "Cegah layar mati saat chart aktif",
                    checked = settings.keepScreenOn,
                    onCheckedChange = onSetKeepScreenOn,
                    testTag = "menu_keep_screen_on"
                )

                // 📑 Daftar Pantau -> klik buka halaman pengaturan watchlist
                TradingViewMenuItem(
                    icon = Icons.Filled.Bookmark,
                    iconTint = CyanAccent,
                    title = "Daftar Pantau",
                    valueText = formatSymbolPair(settings.defaultSymbol),
                    testTag = "menu_watchlist",
                    onClick = { activeSubPage = SettingsSubPage.WATCHLIST.name }
                )

                // 📈 Chart -> klik buka pengaturan chart
                TradingViewMenuItem(
                    icon = Icons.Filled.ShowChart,
                    iconTint = BullishGreen,
                    title = "Chart",
                    valueText = settings.defaultTimeframe.label,
                    testTag = "menu_chart",
                    onClick = { activeSubPage = SettingsSubPage.CHART.name }
                )

                // 💰 Akun Simulasi -> saldo & risiko virtual
                TradingViewMenuItem(
                    icon = Icons.Default.AccountBalanceWallet,
                    iconTint = CyanAccent,
                    title = "Akun Simulasi",
                    valueText = "$${String.format(Locale.US, "%,.0f", simulationEquityUsdt)}",
                    testTag = "menu_simulation_account",
                    onClick = { activeSubPage = SettingsSubPage.SIMULATION.name }
                )

                // 🤖 AI Configuration -> Providers, Models & Token Usage Dashboard
                TradingViewMenuItem(
                    icon = Icons.Filled.SmartToy,
                    iconTint = Color(0xFFA78BFA),
                    title = "AI Configuration",
                    valueText = settings.activeAiProviderType.displayName,
                    testTag = "menu_ai_config",
                    onClick = { activeSubPage = SettingsSubPage.AI_CONFIG.name }
                )

                // 🌐 Data Pasar & Exness -> Market Data Provider & Exness Configuration
                TradingViewMenuItem(
                    icon = Icons.Filled.ShowChart,
                    iconTint = Color(0xFFFFD700),
                    title = "Data Pasar & Exness",
                    valueText = settings.selectedMarketProvider.displayLabel,
                    testTag = "menu_market_data_provider",
                    onClick = { activeSubPage = SettingsSubPage.MARKET_DATA_PROVIDER.name }
                )

                // 🔔 Notifikasi Push -> klik buka pengaturan notifikasi
                TradingViewMenuItem(
                    icon = Icons.Default.Notifications,
                    iconTint = NeutralAmber,
                    title = "Notifikasi Push",
                    valueText = if (settings.notificationsEnabled) "Aktif" else "Mati",
                    testTag = "menu_notifications",
                    onClick = { activeSubPage = SettingsSubPage.NOTIFICATIONS.name }
                )

                // 🌐 Bahasa -> value di kanan (Indonesia/English), klik buka dialog
                val langLabel = if (settings.language.lowercase() == "en") "English" else "Indonesia"
                TradingViewMenuItem(
                    icon = Icons.Default.Language,
                    iconTint = Color(0xFF60A5FA),
                    title = "Bahasa",
                    valueText = langLabel,
                    testTag = "menu_language",
                    onClick = { showLanguageDialog = true }
                )

                // 📧 Informasi Kontak -> klik buka dialog info
                TradingViewMenuItem(
                    icon = Icons.Default.Email,
                    iconTint = Color(0xFF34D399),
                    title = "Informasi Kontak",
                    testTag = "menu_contact_info",
                    onClick = { showContactDialog = true }
                )

                // ℹ️ Tentang -> klik buka dialog versi app + kredit
                TradingViewMenuItem(
                    icon = Icons.Default.Info,
                    iconTint = Color(0xFF9CA3AF),
                    title = "Tentang",
                    valueText = "v3.4.0",
                    testTag = "menu_about",
                    onClick = { showAboutDialog = true }
                )

                // 🗑️ Hapus Akun -> teks merah, klik buka konfirmasi
                TradingViewMenuItem(
                    icon = Icons.Default.DeleteForever,
                    iconTint = BearishRed,
                    title = "Hapus Akun",
                    textColor = BearishRed,
                    testTag = "menu_delete_account",
                    onClick = { showDeleteAccountDialog = true }
                )
            }
        }
    }

    // ================= DIALOGS =================

    // 1. Edit User Name Dialog
    if (showEditNameDialog) {
        var tempName by remember { mutableStateOf(settings.userName) }
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            title = {
                Text(
                    text = "Ubah Nama Trader",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Nama ini akan ditampilkan pada profil terminal Anda:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = tempName,
                        onValueChange = { tempName = it },
                        label = { Text("Nama Pengguna") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_user_name_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = tempName.trim().ifEmpty { "Trader" }
                        onSetUserName(clean)
                        showEditNameDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanAccent,
                        contentColor = Color(0xFF001F26)
                    ),
                    modifier = Modifier.testTag("save_user_name_dialog_button")
                ) {
                    Text("Simpan", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showEditNameDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    // 2. Tema Dialog (Gelap / Terang / Auto)
    if (showThemeDialog) {
        val currentMode = settings.themeMode.uppercase(Locale.US)
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = {
                Text(
                    text = "Pilih Tema Tampilan",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ThemeOptionRow(
                        label = "Gelap (TradingView Dark Pejal)",
                        selected = currentMode == "DARK",
                        onSelect = {
                            onSetThemeMode("DARK")
                            onSetDarkMode(true)
                            showThemeDialog = false
                        }
                    )
                    ThemeOptionRow(
                        label = "Terang (Light Theme)",
                        selected = currentMode == "LIGHT",
                        onSelect = {
                            onSetThemeMode("LIGHT")
                            onSetDarkMode(false)
                            showThemeDialog = false
                        }
                    )
                    ThemeOptionRow(
                        label = "Auto (Ikuti Tema Sistem)",
                        selected = currentMode == "AUTO",
                        onSelect = {
                            onSetThemeMode("AUTO")
                            showThemeDialog = false
                        }
                    )
                }
            },
            confirmButton = {
                OutlinedButton(onClick = { showThemeDialog = false }) {
                    Text("Tutup")
                }
            }
        )
    }

    // 3. Bahasa Dialog
    if (showLanguageDialog) {
        val currentLang = settings.language.lowercase(Locale.US)
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = {
                Text(
                    text = "Pilih Bahasa / Language",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LanguageOptionRow(
                        label = "Bahasa Indonesia (ID)",
                        selected = currentLang != "en",
                        onSelect = {
                            onSetLanguage("id")
                            showLanguageDialog = false
                        }
                    )
                    LanguageOptionRow(
                        label = "English (US)",
                        selected = currentLang == "en",
                        onSelect = {
                            onSetLanguage("en")
                            showLanguageDialog = false
                        }
                    )
                }
            },
            confirmButton = {
                OutlinedButton(onClick = { showLanguageDialog = false }) {
                    Text("Tutup")
                }
            }
        )
    }

    // 4. Informasi Kontak Dialog
    if (showContactDialog) {
        AlertDialog(
            onDismissRequest = { showContactDialog = false },
            title = {
                Text(
                    text = "Informasi Kontak",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "LUMAVUN Support & Komunitas",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = CyanAccent
                    )
                    Text(
                        text = "• Email Dukungan: support@lumavun.com\n• Tim Riset Algoritma: team@lumavun.com\n• Komunitas Trader: LUMAVUN Telegram & Discord\n• Data Pasar Publik: Binance Public WebSocket API\n• Operasional: 24/7 Realtime Monitoring",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showContactDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color(0xFF001F26))
                ) {
                    Text("Tutup")
                }
            }
        )
    }

    // 5. Tentang Dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = {
                Text(
                    text = "Tentang LUMAVUN Chart",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "LUMAVUN Chart · v3.4.0 (TradingView Edition)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent
                    )
                    Text(
                        text = "Imagine Beyond the Mist. Aplikasi terminal pasar kripto profesional bertenaga AI Insight terintegrasi dengan chart candlestick interaktif TradingView UX, deteksi multi-timeframe SMC/ICT/AMD, dan eksekusi virtual Paper Trading.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Engine Pasar: Binance Public Klines, 24h Ticker, BiQuote WebSocket\nEngine AI: LUMAVUN AI Insight (Built-in & Gemini API)\nLisensi: Open Source Community Build",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF9CA3AF)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showAboutDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color(0xFF001F26))
                ) {
                    Text("OK")
                }
            }
        )
    }

    // 6. Hapus Akun Dialog (Fitur Belum Tersedia)
    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountDialog = false },
            title = {
                Text(
                    text = "Hapus Akun",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BearishRed
                )
            },
            text = {
                Text(
                    text = "Fitur belum tersedia.\n\nAkun Anda berjalan sepenuhnya dalam mode read-only lokal & simulasi virtual privat di perangkat ini. Tidak ada data pribadi atau kredensial yang disimpan di server cloud pihak ketiga.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = { showDeleteAccountDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text("Mengerti")
                }
            }
        )
    }
}

// ================= HELPER COMPOSABLES =================

@Composable
private fun TradingViewMenuItem(
    icon: ImageVector,
    iconTint: Color = CyanAccent,
    title: String,
    subtitle: String? = null,
    valueText: String? = null,
    textColor: Color = Color(0xFFE2E8F0),
    testTag: String? = null,
    onClick: () -> Unit
) {
    Surface(
        color = Color(0xFF101522),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .border(1.dp, Color(0xFF1E2430), RoundedCornerShape(10.dp))
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(iconTint.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = iconTint,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        fontWeight = FontWeight.Medium
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!valueText.isNullOrBlank()) {
                    Text(
                        text = valueText,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF9CA3AF)
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Buka",
                    tint = Color(0xFF6B7280),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun TradingViewMenuToggleItem(
    icon: ImageVector,
    iconTint: Color = CyanAccent,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null
) {
    Surface(
        color = Color(0xFF101522),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF1E2430), RoundedCornerShape(10.dp))
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(iconTint.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = iconTint,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFE2E8F0),
                        fontWeight = FontWeight.Medium
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = CyanAccent
                )
            )
        }
    }
}

@Composable
private fun ThemeOptionRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onSelect() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = CyanAccent)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) CyanAccent else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun LanguageOptionRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onSelect() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = CyanAccent)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) CyanAccent else MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Modifier for creating a dashed border
 */
fun Modifier.dashedBorder(
    width: Dp,
    color: Color,
    cornerRadius: Dp = 4.dp,
    dashLength: Float = 10f,
    gapLength: Float = 8f
): Modifier = this.drawBehind {
    val stroke = Stroke(
        width = width.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLength, gapLength), 0f)
    )
    drawRoundRect(
        color = color,
        size = size,
        cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx()),
        style = stroke
    )
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .premiumPanel(12)
            .padding(14.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = CyanAccent,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
        Spacer(modifier = Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = BullishGreen
            ),
            modifier = Modifier.testTag(tag)
        )
    }
}
