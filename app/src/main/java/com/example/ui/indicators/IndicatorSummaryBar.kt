package com.example.ui.indicators

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.analysis.marketStructure.StructureState
import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.chart.indicators.ChartIndicatorParams
import com.example.market.candle.formatCryptoPrice
import com.example.storage.preferences.TerminalSettings
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BearishRedSoft
import com.example.ui.theme.BullishGreen
import com.example.ui.theme.BullishGreenSoft
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.Ema200Color
import com.example.ui.theme.Ema20Color
import com.example.ui.theme.Ema50Color
import com.example.ui.theme.NeutralAmber
import com.example.ui.theme.NeutralAmberSoft
import java.util.Locale

enum class IndicatorCategoryFilter(val label: String) {
    ALL("All"),
    OVERLAY("Main Chart"),
    OSCILLATOR("Oscillators")
}

/**
 * Horizontally Scrollable TradingView-inspired Indicator & Signal Overlay Bar:
 * [ fx Indicators ] [ EMA ] [ SMA ] [ BB ] [ VOL ] [ RSI ] [ MACD ] [ ATR ] [ S/R ] [ SIG ] [ HIST ]
 */
@Composable
fun IndicatorToggleRow(
    showEma: Boolean,
    showSma: Boolean = false,
    showBollingerBands: Boolean = false,
    showVolume: Boolean,
    showRsi: Boolean,
    showMacd: Boolean,
    showAtr: Boolean = true,
    showSupportResistance: Boolean,
    onToggleEma: () -> Unit,
    onToggleSma: () -> Unit = {},
    onToggleBollingerBands: () -> Unit = {},
    onToggleVolume: () -> Unit,
    onToggleRsi: () -> Unit,
    onToggleMacd: () -> Unit,
    onToggleAtr: () -> Unit = {},
    onToggleSupportResistance: () -> Unit,
    showSignalOverlay: Boolean = true,
    showSignalHistory: Boolean = false,
    onToggleSignalOverlay: () -> Unit = {},
    onToggleSignalHistory: () -> Unit = {},
    onOpenIndicatorsModal: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val activeCount = listOf(
        showEma,
        showSma,
        showBollingerBands,
        showVolume,
        showRsi,
        showMacd,
        showAtr,
        showSupportResistance
    ).count { it }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // TradingView-inspired "fx Indicators" modal launcher button
        Surface(
            color = CyanAccent.copy(alpha = 0.20f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, CyanAccent, RoundedCornerShape(6.dp))
                .clickable(onClick = onOpenIndicatorsModal)
                .testTag("open_indicators_panel_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Indicators & Parameters",
                    tint = CyanAccent,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "fx Indicators ($activeCount)",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyanAccent,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/**
 * TradingView-inspired Indicators Search, Enable/Disable, and Parameter Configuration Modal.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TradingViewIndicatorsDialog(
    settings: TerminalSettings,
    activeTimeframeLabel: String,
    onDismiss: () -> Unit,
    onToggleEma: () -> Unit,
    onToggleSma: () -> Unit,
    onToggleBollingerBands: () -> Unit,
    onToggleVolume: () -> Unit,
    onToggleRsi: () -> Unit,
    onToggleMacd: () -> Unit,
    onToggleAtr: () -> Unit,
    onToggleSupportResistance: () -> Unit,
    onToggleSmc: () -> Unit = {},
    showSmcProV5: Boolean = false,
    onToggleSmcProV5: () -> Unit = {},
    smcProQuality: String = "STRICT",
    onChangeSmcProQuality: (String) -> Unit = {},
    smcProHtfBias: Boolean = true,
    onChangeSmcProHtfBias: (Boolean) -> Unit = {},
    onToggleIct: () -> Unit = {},
    onToggleAmd: () -> Unit = {},
    onToggleSignalOverlay: () -> Unit,
    onToggleSignalHistory: () -> Unit,
    onToggleTradePlanOverlay: () -> Unit = {},
    onSetPerTimeframeIndicators: (Boolean) -> Unit,
    onUpdateIndicatorParams: (ChartIndicatorParams) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf(IndicatorCategoryFilter.ALL) }

    var ema1Input by remember(settings.ema1Period) { mutableStateOf(settings.ema1Period.toString()) }
    var ema2Input by remember(settings.ema2Period) { mutableStateOf(settings.ema2Period.toString()) }
    var ema3Input by remember(settings.ema3Period) { mutableStateOf(settings.ema3Period.toString()) }
    var sma1Input by remember(settings.sma1Period) { mutableStateOf(settings.sma1Period.toString()) }
    var sma2Input by remember(settings.sma2Period) { mutableStateOf(settings.sma2Period.toString()) }
    var bbPeriodInput by remember(settings.bbPeriod) { mutableStateOf(settings.bbPeriod.toString()) }
    var bbStdInput by remember(settings.bbStdDev) { mutableStateOf(settings.bbStdDev.toString()) }
    var rsiPeriodInput by remember(settings.rsiPeriod) { mutableStateOf(settings.rsiPeriod.toString()) }
    var macdFastInput by remember(settings.macdFast) { mutableStateOf(settings.macdFast.toString()) }
    var macdSlowInput by remember(settings.macdSlow) { mutableStateOf(settings.macdSlow.toString()) }
    var macdSigInput by remember(settings.macdSignal) { mutableStateOf(settings.macdSignal.toString()) }
    var atrPeriodInput by remember(settings.atrPeriod) { mutableStateOf(settings.atrPeriod.toString()) }

    fun saveCurrentParams() {
        val params = ChartIndicatorParams(
            ema1Period = ema1Input.toIntOrNull()?.coerceIn(2, 500) ?: settings.ema1Period,
            ema2Period = ema2Input.toIntOrNull()?.coerceIn(2, 500) ?: settings.ema2Period,
            ema3Period = ema3Input.toIntOrNull()?.coerceIn(2, 500) ?: settings.ema3Period,
            sma1Period = sma1Input.toIntOrNull()?.coerceIn(2, 500) ?: settings.sma1Period,
            sma2Period = sma2Input.toIntOrNull()?.coerceIn(2, 500) ?: settings.sma2Period,
            bbPeriod = bbPeriodInput.toIntOrNull()?.coerceIn(2, 200) ?: settings.bbPeriod,
            bbStdDev = bbStdInput.toDoubleOrNull()?.coerceIn(0.5, 5.0) ?: settings.bbStdDev,
            rsiPeriod = rsiPeriodInput.toIntOrNull()?.coerceIn(2, 100) ?: settings.rsiPeriod,
            macdFast = macdFastInput.toIntOrNull()?.coerceIn(2, 100) ?: settings.macdFast,
            macdSlow = macdSlowInput.toIntOrNull()?.coerceIn(5, 200) ?: settings.macdSlow,
            macdSignal = macdSigInput.toIntOrNull()?.coerceIn(2, 100) ?: settings.macdSignal,
            atrPeriod = atrPeriodInput.toIntOrNull()?.coerceIn(2, 100) ?: settings.atrPeriod
        )
        onUpdateIndicatorParams(params)
    }

    val q = searchQuery.trim().lowercase(Locale.US)
    fun matches(title: String, keywords: String, isOverlay: Boolean): Boolean {
        val catOk = when (categoryFilter) {
            IndicatorCategoryFilter.ALL -> true
            IndicatorCategoryFilter.OVERLAY -> isOverlay
            IndicatorCategoryFilter.OSCILLATOR -> !isOverlay
        }
        if (!catOk) return false
        if (q.isEmpty()) return true
        return title.lowercase(Locale.US).contains(q) || keywords.lowercase(Locale.US).contains(q)
    }

    Dialog(
        onDismissRequest = {
            saveCurrentParams()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 640.dp)
                .border(1.dp, CyanAccent.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .testTag("tradingview_indicators_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "INDICATORS & STRATEGIES",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Configure overlays, oscillators & periods ($activeTimeframeLabel)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = {
                            saveCurrentParams()
                            onDismiss()
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("close_indicators_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Indicators",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = "Search indicators (EMA, SMA, Bollinger, RSI, MACD, ATR, Volume)...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search Indicator",
                            tint = CyanAccent
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear"
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("indicator_search_input")
                )

                // Category Tabs & Per-Timeframe Persistence Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IndicatorCategoryFilter.entries.forEach { cat ->
                            val isSel = categoryFilter == cat
                            Surface(
                                color = if (isSel) CyanAccent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        if (isSel) CyanAccent else Color.Transparent,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { categoryFilter = cat }
                                    .testTag("indicator_cat_${cat.name.lowercase()}")
                            ) {
                                Text(
                                    text = cat.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSel) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Per-TF ($activeTimeframeLabel)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Switch(
                            checked = settings.perTimeframeIndicators,
                            onCheckedChange = onSetPerTimeframeIndicators,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = CyanAccent
                            ),
                            modifier = Modifier.testTag("per_tf_indicators_switch")
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                // Scrollable Indicator Items
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (matches("EMA (Exponential Moving Average)", "ema moving average overlay trend", isOverlay = true)) {
                        IndicatorConfigCard(
                            title = "EMA (Exponential Moving Average)",
                            subtitle = "Main Chart Overlay · 3 Lines (${settings.ema1Period} / ${settings.ema2Period} / ${settings.ema3Period})",
                            enabled = settings.showEma,
                            onToggle = onToggleEma,
                            tag = "modal_toggle_ema",
                            onApply = { saveCurrentParams() },
                            onCancel = {
                                ema1Input = settings.ema1Period.toString()
                                ema2Input = settings.ema2Period.toString()
                                ema3Input = settings.ema3Period.toString()
                            }
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CompactPeriodPresetChip("20/50/200") {
                                    ema1Input = "20"; ema2Input = "50"; ema3Input = "200"
                                    saveCurrentParams()
                                }
                                CompactPeriodPresetChip("9/21/55") {
                                    ema1Input = "9"; ema2Input = "21"; ema3Input = "55"
                                    saveCurrentParams()
                                }
                                CompactPeriodPresetChip("12/26/100") {
                                    ema1Input = "12"; ema2Input = "26"; ema3Input = "100"
                                    saveCurrentParams()
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = ema1Input,
                                    onValueChange = { ema1Input = it.filter(Char::isDigit) },
                                    label = { Text("EMA 1") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = ema2Input,
                                    onValueChange = { ema2Input = it.filter(Char::isDigit) },
                                    label = { Text("EMA 2") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = ema3Input,
                                    onValueChange = { ema3Input = it.filter(Char::isDigit) },
                                    label = { Text("EMA 3") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    if (matches("SMA (Simple Moving Average)", "sma simple moving average overlay", isOverlay = true)) {
                        IndicatorConfigCard(
                            title = "SMA (Simple Moving Average)",
                            subtitle = "Main Chart Overlay · 2 Lines (${settings.sma1Period} / ${settings.sma2Period})",
                            enabled = settings.showSma,
                            onToggle = onToggleSma,
                            tag = "modal_toggle_sma",
                            onApply = { saveCurrentParams() },
                            onCancel = {
                                sma1Input = settings.sma1Period.toString()
                                sma2Input = settings.sma2Period.toString()
                            }
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CompactPeriodPresetChip("20 / 50") {
                                    sma1Input = "20"; sma2Input = "50"
                                    saveCurrentParams()
                                }
                                CompactPeriodPresetChip("10 / 30") {
                                    sma1Input = "10"; sma2Input = "30"
                                    saveCurrentParams()
                                }
                                CompactPeriodPresetChip("50 / 100") {
                                    sma1Input = "50"; sma2Input = "100"
                                    saveCurrentParams()
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = sma1Input,
                                    onValueChange = { sma1Input = it.filter(Char::isDigit) },
                                    label = { Text("SMA 1") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = sma2Input,
                                    onValueChange = { sma2Input = it.filter(Char::isDigit) },
                                    label = { Text("SMA 2") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    if (matches("Bollinger Bands (BB)", "bb bollinger bands volatility channel overlay", isOverlay = true)) {
                        IndicatorConfigCard(
                            title = "Bollinger Bands (BB)",
                            subtitle = "Main Chart Overlay · Upper, Basis & Lower (${settings.bbPeriod}, ${settings.bbStdDev}σ)",
                            enabled = settings.showBollingerBands,
                            onToggle = onToggleBollingerBands,
                            tag = "modal_toggle_bb",
                            onApply = { saveCurrentParams() },
                            onCancel = {
                                bbPeriodInput = settings.bbPeriod.toString()
                                bbStdInput = settings.bbStdDev.toString()
                            }
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = bbPeriodInput,
                                    onValueChange = { bbPeriodInput = it.filter(Char::isDigit) },
                                    label = { Text("Length") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = bbStdInput,
                                    onValueChange = { bbStdInput = it },
                                    label = { Text("StdDev (σ)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    if (matches("RSI (Relative Strength Index)", "rsi relative strength oscillator momentum", isOverlay = false)) {
                        IndicatorConfigCard(
                            title = "RSI (Relative Strength Index)",
                            subtitle = "Separate Oscillator Sub-Panel · Period ${settings.rsiPeriod}",
                            enabled = settings.showRsi,
                            onToggle = onToggleRsi,
                            tag = "modal_toggle_rsi",
                            onApply = { saveCurrentParams() },
                            onCancel = {
                                rsiPeriodInput = settings.rsiPeriod.toString()
                            }
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CompactPeriodPresetChip("RSI 7") { rsiPeriodInput = "7"; saveCurrentParams() }
                                CompactPeriodPresetChip("RSI 14") { rsiPeriodInput = "14"; saveCurrentParams() }
                                CompactPeriodPresetChip("RSI 21") { rsiPeriodInput = "21"; saveCurrentParams() }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = rsiPeriodInput,
                                onValueChange = { rsiPeriodInput = it.filter(Char::isDigit) },
                                label = { Text("RSI Length") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (matches("MACD", "macd histogram signal oscillator momentum", isOverlay = false)) {
                        IndicatorConfigCard(
                            title = "MACD (Moving Average Convergence Divergence)",
                            subtitle = "Separate Oscillator Sub-Panel · (${settings.macdFast}, ${settings.macdSlow}, ${settings.macdSignal})",
                            enabled = settings.showMacd,
                            onToggle = onToggleMacd,
                            tag = "modal_toggle_macd",
                            onApply = { saveCurrentParams() },
                            onCancel = {
                                macdFastInput = settings.macdFast.toString()
                                macdSlowInput = settings.macdSlow.toString()
                                macdSigInput = settings.macdSignal.toString()
                            }
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = macdFastInput,
                                    onValueChange = { macdFastInput = it.filter(Char::isDigit) },
                                    label = { Text("Fast") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = macdSlowInput,
                                    onValueChange = { macdSlowInput = it.filter(Char::isDigit) },
                                    label = { Text("Slow") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = macdSigInput,
                                    onValueChange = { macdSigInput = it.filter(Char::isDigit) },
                                    label = { Text("Signal") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    if (matches("ATR (Average True Range)", "atr average true range volatility", isOverlay = false)) {
                        IndicatorConfigCard(
                            title = "ATR (Average True Range)",
                            subtitle = "Volatility Readout & Stop Distance Basis · Period ${settings.atrPeriod}",
                            enabled = settings.showAtr,
                            onToggle = onToggleAtr,
                            tag = "modal_toggle_atr",
                            onApply = { saveCurrentParams() },
                            onCancel = {
                                atrPeriodInput = settings.atrPeriod.toString()
                            }
                        ) {
                            OutlinedTextField(
                                value = atrPeriodInput,
                                onValueChange = { atrPeriodInput = it.filter(Char::isDigit) },
                                label = { Text("ATR Length") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (matches("Volume + SMA 20", "vol volume histogram bars", isOverlay = false)) {
                        IndicatorConfigCard(
                            title = "Volume Histogram",
                            subtitle = "Dedicated Volume Pane with 20-bar Volume SMA",
                            enabled = settings.showVolume,
                            onToggle = onToggleVolume,
                            tag = "modal_toggle_volume"
                        )
                    }

                    if (matches("Support & Resistance Levels", "sr support resistance levels key zones", isOverlay = true)) {
                        IndicatorConfigCard(
                            title = "Support & Resistance (S/R)",
                            subtitle = "Main Chart Overlay · Clustered Swing Pivot Levels",
                            enabled = settings.showSupportResistance,
                            onToggle = onToggleSupportResistance,
                            tag = "modal_toggle_sr"
                        )
                    }

                    if (matches("SMC (Smart Money Concepts)", "smc smart money concepts market structure swings bos choch", isOverlay = true)) {
                        IndicatorConfigCard(
                            title = "SMC (Smart Money Concepts)",
                            subtitle = "Main Chart Overlay · Swing High/Low (HH/LH/HL/LL), BOS & CHoCH",
                            enabled = settings.showSmc,
                            onToggle = onToggleSmc,
                            tag = "modal_toggle_smc"
                        )
                    }

                    if (matches("SMC Entry Models PRO v5", "smc pro v5 sweep choch mss order block fvg sniper pending entry tp sl follow candle", isOverlay = true)) {
                        IndicatorConfigCard(
                            title = "SMC Entry Models PRO v5",
                            subtitle = "M1 Sweep + CHoCH, M2 OB, M3 FVG · closed-candle only",
                            enabled = showSmcProV5,
                            onToggle = onToggleSmcProV5,
                            tag = "modal_toggle_smc_pro_v5"
                        ) {
                            Text(
                                "Quality filter (heuristic, not win probability)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                CompactPeriodPresetChip("Strict 85" + if (smcProQuality == "STRICT") " ✓" else "") {
                                    onChangeSmcProQuality("STRICT")
                                }
                                CompactPeriodPresetChip("Normal 75" + if (smcProQuality == "NORMAL") " ✓" else "") {
                                    onChangeSmcProQuality("NORMAL")
                                }
                                CompactPeriodPresetChip("Loose 65" + if (smcProQuality == "LOOSE") " ✓" else "") {
                                    onChangeSmcProQuality("LOOSE")
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Closed HTF bias filter",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(checked = smcProHtfBias, onCheckedChange = onChangeSmcProHtfBias)
                            }
                            Text(
                                "Pending retests are analytical only. SL takes priority when SL and TP are touched in the same candle.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (matches("ICT (Inner Circle Trader)", "ict inner circle trader order blocks liquidity sweep ob", isOverlay = true)) {
                        IndicatorConfigCard(
                            title = "ICT (Inner Circle Trader)",
                            subtitle = "Main Chart Overlay · Bullish/Bearish Order Blocks & Liquidity Sweeps",
                            enabled = settings.showIct,
                            onToggle = onToggleIct,
                            tag = "modal_toggle_ict"
                        )
                    }

                    if (matches("AMD (Accumulation-Manipulation-Distribution)", "amd asian range session manipulation distribution judas swing", isOverlay = true)) {
                        IndicatorConfigCard(
                            title = "AMD (Accumulation-Manipulation-Distribution)",
                            subtitle = "Main Chart Overlay · Asia Range, London Manipulation & NY Distribution",
                            enabled = settings.showAmd,
                            onToggle = onToggleAmd,
                            tag = "modal_toggle_amd"
                        )
                    }

                    if (matches("AI Signal Levels & History", "signal entry sl tp overlay history", isOverlay = true)) {
                        IndicatorConfigCard(
                            title = "AI Signal Overlay (Entry / SL / TP1-3)",
                            subtitle = "Main Chart Overlay · Active Trade Setup Lines",
                            enabled = settings.showSignalOverlay,
                            onToggle = onToggleSignalOverlay,
                            tag = "modal_toggle_signal_overlay",
                            onApply = { saveCurrentParams() },
                            onCancel = {}
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Include Historical Journal Signals on Chart",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Switch(
                                    checked = settings.showSignalHistory,
                                    onCheckedChange = { onToggleSignalHistory() }
                                )
                            }
                        }
                    }

                    if (matches("AI Trade Plan Overlay", "trade plan pending setup entry sl tp risk reward invalidation", isOverlay = true)) {
                        IndicatorConfigCard(
                            title = "AI Trade Plan Overlay (V3.3)",
                            subtitle = "Main Chart Overlay · Entry Band, SL, TP1-3 & Pending Setup Lifecycle",
                            enabled = settings.showTradePlanOverlay,
                            onToggle = onToggleTradePlanOverlay,
                            tag = "modal_toggle_trade_plan_overlay"
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            ema1Input = "20"; ema2Input = "50"; ema3Input = "200"
                            sma1Input = "20"; sma2Input = "50"
                            bbPeriodInput = "20"; bbStdInput = "2.0"
                            rsiPeriodInput = "14"
                            macdFastInput = "12"; macdSlowInput = "26"; macdSigInput = "9"
                            atrPeriodInput = "14"
                            onUpdateIndicatorParams(ChartIndicatorParams())
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Reset Defaults",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = {
                            saveCurrentParams()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanAccent,
                            contentColor = Color(0xFF001F26)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("apply_indicators_button")
                    ) {
                        Text(
                            text = "Apply & Close",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IndicatorConfigCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    tag: String,
    onApply: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    parameterContent: (@Composable () -> Unit)? = null
) {
    var isExpanded by remember { mutableStateOf(false) }
    val hasConfigurableParams = parameterContent != null

    val cardBackground = when {
        enabled -> CyanAccent.copy(alpha = 0.08f)
        isExpanded -> CyanAccent.copy(alpha = 0.04f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }
    val cardBorder = when {
        enabled -> CyanAccent.copy(alpha = 0.55f)
        isExpanded -> CyanAccent.copy(alpha = 0.40f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(RoundedCornerShape(8.dp))
            .background(cardBackground)
            .border(1.dp, cardBorder, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = hasConfigurableParams) {
                    isExpanded = !isExpanded
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Status badge: "ACTIVE ✓" or "+ ADD"
                Surface(
                    color = if (enabled) BullishGreenSoft else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .border(
                            1.dp,
                            if (enabled) BullishGreen else CyanAccent.copy(alpha = 0.7f),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable {
                            if (enabled && isExpanded) {
                                isExpanded = false
                            }
                            onToggle()
                        }
                        .testTag(tag)
                ) {
                    Text(
                        text = if (enabled) "ACTIVE ✓" else "+ ADD",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (enabled) BullishGreen else CyanAccent,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }

                // Settings gear button (only shown when indicator has customizable parameters)
                if (hasConfigurableParams) {
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("${tag}_settings")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = if (isExpanded) "Collapse $title settings" else "Expand $title settings",
                            tint = if (isExpanded) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Parameter editor (HANYA tampil saat user expand baris atau klik gear)
        if (isExpanded && parameterContent != null) {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            Spacer(modifier = Modifier.height(10.dp))

            parameterContent()

            // Row kontrol: Remove (jika aktif), Cancel, Apply
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (enabled) {
                    OutlinedButton(
                        onClick = {
                            onToggle()
                            isExpanded = false
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = BearishRed
                        ),
                        border = BorderStroke(1.dp, BearishRed.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("${tag}_remove_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = BearishRed
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Remove",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        onCancel?.invoke()
                        isExpanded = false
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("${tag}_cancel_button")
                ) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Button(
                    onClick = {
                        if (!enabled) {
                            onToggle()
                        }
                        onApply?.invoke()
                        isExpanded = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanAccent,
                        contentColor = Color(0xFF001F26)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1.2f)
                        .testTag("${tag}_apply_button")
                ) {
                    Text(
                        text = "Apply",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactPeriodPresetChip(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        color = CyanAccent.copy(alpha = 0.14f),
        shape = RoundedCornerShape(5.dp),
        modifier = Modifier
            .border(1.dp, CyanAccent.copy(alpha = 0.45f), RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = CyanAccent,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun IndicatorToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String
) {
    val bgColor = if (selected) {
        CyanAccent.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val borderColor = if (selected) {
        CyanAccent.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    }
    val textColor = if (selected) {
        CyanAccent
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .testTag(tag)
    ) {
        Text(
            text = "[ $label ]",
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarketStructureAndLevelsCard(
    snapshot: MarketTechnicalSnapshot?,
    modifier: Modifier = Modifier
) {
    if (snapshot == null) return

    val structure = snapshot.marketStructure
    val (badgeColor, badgeBg) = when (structure.state) {
        StructureState.BULLISH -> BullishGreen to BullishGreenSoft
        StructureState.BEARISH -> BearishRed to BearishRedSoft
        StructureState.SIDEWAYS, StructureState.TRANSITIONAL -> NeutralAmber to NeutralAmberSoft
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag("market_structure_sr_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "LOCAL TECHNICAL SNAPSHOT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${structure.swingSummary} (${structure.biasLabel})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Surface(
                color = badgeBg,
                shape = RoundedCornerShape(5.dp)
            ) {
                Text(
                    text = structure.state.displayLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = badgeColor,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IndicatorMetricItem(
                label = "EMA 20",
                value = snapshot.ema20?.let { formatCryptoPrice(it) } ?: "--",
                valueColor = Ema20Color
            )
            IndicatorMetricItem(
                label = "EMA 50",
                value = snapshot.ema50?.let { formatCryptoPrice(it) } ?: "--",
                valueColor = Ema50Color
            )
            IndicatorMetricItem(
                label = "EMA 200",
                value = snapshot.ema200?.let { formatCryptoPrice(it) } ?: "--",
                valueColor = Ema200Color
            )
            IndicatorMetricItem(
                label = "RSI 14",
                value = snapshot.rsi14?.let { String.format(Locale.US, "%.1f", it) } ?: "--",
                valueColor = when {
                    (snapshot.rsi14 ?: 50.0) >= 70.0 -> BearishRed
                    (snapshot.rsi14 ?: 50.0) <= 30.0 -> BullishGreen
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            IndicatorMetricItem(
                label = "ATR 14",
                value = snapshot.atr14?.let { formatCryptoPrice(it) } ?: "--",
                valueColor = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun IndicatorMetricItem(
    label: String,
    value: String,
    valueColor: Color
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false
        )
    }
}
