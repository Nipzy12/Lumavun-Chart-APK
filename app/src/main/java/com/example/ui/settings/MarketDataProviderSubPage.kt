package com.example.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.market.candle.InstrumentCategory
import com.example.market.candle.MarketProvider
import com.example.market.exness.ExnessCatalog
import com.example.market.exness.ExnessConfig
import com.example.market.provider.ProviderConnectionStatus
import com.example.storage.preferences.TerminalSettings
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BullishGreen
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.NeutralAmber

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarketDataProviderSubPage(
    settings: TerminalSettings,
    exnessStatus: ProviderConnectionStatus,
    onSelectProvider: (MarketProvider) -> Unit,
    onSaveExnessConfig: (ExnessConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeProvider = settings.selectedMarketProvider
    var gatewayUrlInput by remember(settings.exnessConfig.gatewayUrl) {
        mutableStateOf(settings.exnessConfig.gatewayUrl)
    }
    var sessionTokenInput by remember(settings.exnessConfig.sessionToken) {
        mutableStateOf(settings.exnessConfig.sessionToken)
    }
    var selectedTier by remember(settings.exnessConfig.accountTier) {
        mutableStateOf(settings.exnessConfig.accountTier)
    }
    var selectedEnv by remember(settings.exnessConfig.serverEnvironment) {
        mutableStateOf(settings.exnessConfig.serverEnvironment)
    }
    var saveSuccessFeedback by remember { mutableStateOf(false) }
    var selectedCategoryFilter by remember { mutableStateOf(InstrumentCategory.ALL) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Market Data Provider Selection Card
        Surface(
            color = Color(0xFF101522),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF1E283D)),
            modifier = Modifier.fillMaxWidth().testTag("market_provider_selector_card")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "PILIH PROVIDER DATA PASAR",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Chart, AI Analysis, indikator teknikal, dan Bot simulasi akan bersumber dari provider aktif.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                )

                // Provider Option: Binance
                ProviderOptionRow(
                    title = "Binance",
                    subtitle = "Crypto Spot & Perpetual Futures (Data publik gratis)",
                    badge = "CRYPTO",
                    badgeColor = CyanAccent,
                    isSelected = activeProvider == MarketProvider.BINANCE,
                    onClick = { onSelectProvider(MarketProvider.BINANCE) },
                    testTag = "provider_option_binance"
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Provider Option: Exness
                ProviderOptionRow(
                    title = "Exness",
                    subtitle = "Forex Majors/Crosses, Gold/Metals, Indices & CFD",
                    badge = "MULTI-ASSET CFD",
                    badgeColor = Color(0xFFFFD700),
                    isSelected = activeProvider == MarketProvider.EXNESS,
                    onClick = { onSelectProvider(MarketProvider.EXNESS) },
                    testTag = "provider_option_exness"
                )
            }
        }

        // 2. Active Provider Status & Diagnostics Banner
        Surface(
            color = Color(0xFF121929),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(
                1.dp,
                if (activeProvider == MarketProvider.EXNESS && exnessStatus.isStaleOrOffline) {
                    NeutralAmber.copy(alpha = 0.5f)
                } else Color(0xFF1E283D)
            ),
            modifier = Modifier.fillMaxWidth().testTag("provider_status_card")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "STATUS KONEKSI DATA PASAR",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )

                    val statusLabel = if (activeProvider == MarketProvider.EXNESS) {
                        exnessStatus.displayLabel
                    } else {
                        "CONNECTED (Binance Stream)"
                    }
                    val statusColor = if (activeProvider == MarketProvider.EXNESS) {
                        when (exnessStatus) {
                            is ProviderConnectionStatus.Connected -> BullishGreen
                            is ProviderConnectionStatus.Connecting -> CyanAccent
                            is ProviderConnectionStatus.Stale -> NeutralAmber
                            is ProviderConnectionStatus.Disconnected,
                            is ProviderConnectionStatus.Unconfigured,
                            is ProviderConnectionStatus.Error -> BearishRed
                        }
                    } else BullishGreen

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(statusColor.copy(alpha = 0.15f))
                            .border(0.8.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (activeProvider == MarketProvider.EXNESS) {
                    val message = when (exnessStatus) {
                        is ProviderConnectionStatus.Connected -> "Koneksi gateway proxy Exness aktif. Menerima harga Bid/Ask real-time."
                        is ProviderConnectionStatus.Connecting -> "Menghubungkan ke gateway proxy Exness..."
                        is ProviderConnectionStatus.Stale -> "Peringatan: Data Exness tidak menerima update >15 detik (Stale). Bot simulasi dijeda sementara."
                        is ProviderConnectionStatus.Unconfigured -> exnessStatus.message
                        is ProviderConnectionStatus.Disconnected -> "Koneksi gateway terputus: ${exnessStatus.reason}. Memulai reconnect otomatis..."
                        is ProviderConnectionStatus.Error -> "Error gateway Exness: ${exnessStatus.message}"
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // 3. Exness Security & Architecture Notice
        Surface(
            color = Color(0xFF0F172A),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth().testTag("exness_security_notice_card")
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Keamanan",
                    tint = CyanAccent,
                    modifier = Modifier.size(22.dp)
                )
                Column {
                    Text(
                        text = "Arsitektur Gateway Proxy & Zero-Credential",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Sesuai standar keamanan LUMAVUN Chart V3.4A, aplikasi ini strictly market-data & simulasi saja. Kredensial akun live atau private key trading dilarang berada di dalam APK. Data pasar Exness dialirkan melalui institutional gateway proxy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // 4. Exness Gateway Proxy Configuration Form
        Surface(
            color = Color(0xFF101522),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF1E283D)),
            modifier = Modifier.fillMaxWidth().testTag("exness_config_form_card")
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "KONFIGURASI GATEWAY PROXY EXNESS",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFD700),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                // Gateway URL Field
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Backend Gateway Proxy URL",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    OutlinedTextField(
                        value = gatewayUrlInput,
                        onValueChange = {
                            gatewayUrlInput = it
                            saveSuccessFeedback = false
                        },
                        placeholder = { Text("https://gateway.example.com/exness", color = Color(0xFF64748B)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = Color(0xFF2A364F),
                            focusedContainerColor = Color(0xFF090D16),
                            unfocusedContainerColor = Color(0xFF090D16),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("input_exness_gateway_url")
                    )
                }

                // Session Token Field
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Session Token / JWT (Opsional)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    OutlinedTextField(
                        value = sessionTokenInput,
                        onValueChange = {
                            sessionTokenInput = it
                            saveSuccessFeedback = false
                        },
                        placeholder = { Text("Bearer token / session auth header", color = Color(0xFF64748B)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = Color(0xFF2A364F),
                            focusedContainerColor = Color(0xFF090D16),
                            unfocusedContainerColor = Color(0xFF090D16),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("input_exness_session_token")
                    )
                }

                // Account Tier & Environment Selectors
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Account Tier
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Account Tier",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val tiers = listOf("STANDARD", "RAW_SPREAD", "PRO", "ZERO")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            tiers.forEach { tier ->
                                val isSelected = selectedTier == tier
                                Surface(
                                    color = if (isSelected) Color(0xFFFFD700).copy(alpha = 0.2f) else Color(0xFF0B101D),
                                    shape = RoundedCornerShape(4.dp),
                                    border = BorderStroke(
                                        0.8.dp,
                                        if (isSelected) Color(0xFFFFD700) else Color(0xFF1E283D)
                                    ),
                                    modifier = Modifier
                                        .clickable {
                                            selectedTier = tier
                                            saveSuccessFeedback = false
                                        }
                                        .testTag("tier_$tier")
                                ) {
                                    Text(
                                        text = tier.replace("_", " "),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = if (isSelected) Color(0xFFFFD700) else Color(0xFF94A3B8),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Environment
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Environment",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("DEMO", "REAL").forEach { env ->
                                val isSelected = selectedEnv == env
                                Surface(
                                    color = if (isSelected) CyanAccent.copy(alpha = 0.2f) else Color(0xFF0B101D),
                                    shape = RoundedCornerShape(4.dp),
                                    border = BorderStroke(
                                        0.8.dp,
                                        if (isSelected) CyanAccent else Color(0xFF1E283D)
                                    ),
                                    modifier = Modifier
                                        .clickable {
                                            selectedEnv = env
                                            saveSuccessFeedback = false
                                        }
                                        .testTag("env_$env")
                                ) {
                                    Text(
                                        text = env,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = if (isSelected) CyanAccent else Color(0xFF94A3B8),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Save Button
                Button(
                    onClick = {
                        val newConfig = ExnessConfig(
                            gatewayUrl = gatewayUrlInput.trim(),
                            sessionToken = sessionTokenInput.trim(),
                            accountTier = selectedTier,
                            serverEnvironment = selectedEnv,
                            isEnabled = gatewayUrlInput.isNotBlank(),
                            lastUpdateTimestamp = System.currentTimeMillis()
                        )
                        onSaveExnessConfig(newConfig)
                        saveSuccessFeedback = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color(0xFF070B14)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().testTag("btn_save_exness_config")
                ) {
                    Text(
                        text = if (saveSuccessFeedback) "✓ Konfigurasi Tersimpan" else "Simpan Konfigurasi & Hubungkan",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 5. Supported Exness Instrument Catalog Table
        Surface(
            color = Color(0xFF101522),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF1E283D)),
            modifier = Modifier.fillMaxWidth().testTag("exness_catalog_card")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "KATALOG INSTRUMEN EXNESS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Instrumen resmi didukung untuk charting & simulasi bot dengan presisi digit desimal native.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                // Category Filter chips
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    val filterCategories = listOf(
                        InstrumentCategory.ALL,
                        InstrumentCategory.FOREX,
                        InstrumentCategory.COMMODITIES,
                        InstrumentCategory.CRYPTO,
                        InstrumentCategory.INDICES
                    )
                    filterCategories.forEach { cat ->
                        val isSelected = selectedCategoryFilter == cat
                        Surface(
                            color = if (isSelected) CyanAccent.copy(alpha = 0.2f) else Color(0xFF0A0F1D),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(0.8.dp, if (isSelected) CyanAccent else Color(0xFF1E283D)),
                            modifier = Modifier.clickable { selectedCategoryFilter = cat }
                        ) {
                            Text(
                                text = cat.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) CyanAccent else Color(0xFF94A3B8),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Instrument Rows
                val instruments = ExnessCatalog.INSTRUMENTS.filter {
                    selectedCategoryFilter == InstrumentCategory.ALL || it.category == selectedCategoryFilter
                }

                instruments.take(12).forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = item.symbol,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Color(0xFF1E293B))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "${item.priceDigits} digits",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }
                            Text(
                                text = item.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF64748B)
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Spread ~${item.typicalSpreadPips} pips",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFFD700)
                            )
                            Text(
                                text = "Contract ${item.contractSize.toInt()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF64748B)
                            )
                        }
                    }
                    HorizontalDivider(color = Color(0xFF162032))
                }
            }
        }
    }
}

@Composable
private fun ProviderOptionRow(
    title: String,
    subtitle: String,
    badge: String,
    badgeColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        color = if (isSelected) Color(0xFF121E33) else Color(0xFF090D16),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            1.2.dp,
            if (isSelected) CyanAccent else Color(0xFF1E283D)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) CyanAccent else Color.Transparent)
                        .border(1.5.dp, if (isSelected) CyanAccent else Color(0xFF475569), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF070B14))
                        )
                    }
                }

                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(badgeColor.copy(alpha = 0.2f))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = badgeColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
