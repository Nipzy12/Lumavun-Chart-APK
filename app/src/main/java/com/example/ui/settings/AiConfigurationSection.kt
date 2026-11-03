package com.example.ui.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.sync.AiModelInfo
import com.example.ai.usage.AiUsageDashboardData
import com.example.ai.usage.TokenUsageSource
import com.example.storage.preferences.AiProviderMode
import com.example.storage.preferences.AiProviderType
import com.example.storage.preferences.CustomApiFormat
import com.example.storage.preferences.TerminalSettings
import com.example.storage.watchlist.AiTokenUsageEntity
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BullishGreen
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.NeutralAmber
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class AiConfigSubTab(val title: String) {
    PROVIDER("Provider"),
    MODELS("Models"),
    USAGE("Usage")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiConfigurationSection(
    settings: TerminalSettings,
    isTestingAiConnection: Boolean,
    aiConnectionTestStatus: String?,
    aiUsageDashboard: AiUsageDashboardData,
    recentAiUsageRecords: List<AiTokenUsageEntity>,
    syncedModelsMap: Map<AiProviderType, List<AiModelInfo>>,
    isSyncingModels: Boolean,
    modelSyncStatusMessage: String?,
    onSetPrimaryAiProvider: (AiProviderMode) -> Unit,
    onSetEnableAiFallback: (Boolean) -> Unit,
    onSaveCustomApiConfig: (String, String?, String, CustomApiFormat) -> Unit,
    onClearCustomApiKey: () -> Unit,
    onSaveGeminiCustomConfig: (String?, String) -> Unit,
    onClearGeminiCustomKey: () -> Unit,
    onTestAiConnection: () -> Unit,
    onSyncModels: (AiProviderType) -> Unit,
    onSelectModel: (AiProviderType, String) -> Unit,
    onSaveProviderConfig: (AiProviderType, String, String?, String) -> Unit,
    onClearProviderApiKey: (AiProviderType) -> Unit,
    onSetActiveAiProviderType: (AiProviderType) -> Unit,
    onSetDailyTokenBudget: (Long) -> Unit,
    onSetMonthlyTokenBudget: (Long) -> Unit,
    onSetBudgetWarningThreshold: (Int) -> Unit,
    onSetBlockWhenBudgetExceeded: (Boolean) -> Unit,
    onClearAllTokenUsage: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableStateOf(AiConfigSubTab.PROVIDER) }

    // Active inspection provider inside the configuration UI
    var inspectingProvider by rememberSaveable { mutableStateOf(settings.activeAiProviderType) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("ai_configuration_screen")
    ) {
        // Tab Bar: Provider | Models | Usage
        TabRow(
            selectedTabIndex = selectedTab.ordinal,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = CyanAccent,
            indicator = { tabPositions ->
                if (selectedTab.ordinal < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                        color = CyanAccent,
                        height = 3.dp
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("ai_config_tab_row")
        ) {
            AiConfigSubTab.entries.forEach { tab ->
                val isSelected = selectedTab == tab
                Tab(
                    selected = isSelected,
                    onClick = { selectedTab = tab },
                    text = {
                        Text(
                            text = tab.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.testTag("ai_tab_${tab.name.lowercase()}")
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        when (selectedTab) {
            AiConfigSubTab.PROVIDER -> {
                AiProviderTabContent(
                    settings = settings,
                    inspectingProvider = inspectingProvider,
                    onInspectProvider = { inspectingProvider = it },
                    isTestingConnection = isTestingAiConnection,
                    connectionStatus = aiConnectionTestStatus,
                    onTestConnection = onTestAiConnection,
                    onSetActiveProvider = onSetActiveAiProviderType,
                    onSaveProviderConfig = onSaveProviderConfig,
                    onClearApiKey = onClearProviderApiKey,
                    onSetEnableFallback = onSetEnableAiFallback
                )
            }
            AiConfigSubTab.MODELS -> {
                AiModelsTabContent(
                    settings = settings,
                    inspectingProvider = inspectingProvider,
                    onInspectProvider = { inspectingProvider = it },
                    syncedModels = syncedModelsMap[inspectingProvider].orEmpty(),
                    isSyncing = isSyncingModels,
                    syncStatusMessage = modelSyncStatusMessage,
                    onSyncModels = onSyncModels,
                    onSelectModel = onSelectModel
                )
            }
            AiConfigSubTab.USAGE -> {
                AiUsageDashboardContent(
                    dashboardData = aiUsageDashboard,
                    recentRecords = recentAiUsageRecords,
                    onSetDailyBudget = onSetDailyTokenBudget,
                    onSetMonthlyBudget = onSetMonthlyTokenBudget,
                    onSetWarningThreshold = onSetBudgetWarningThreshold,
                    onSetBlockWhenExceeded = onSetBlockWhenBudgetExceeded,
                    onClearAllUsage = onClearAllTokenUsage
                )
            }
        }
    }
}

/**
 * Tab 1: AI Provider Configuration Content
 */
@Composable
private fun AiProviderTabContent(
    settings: TerminalSettings,
    inspectingProvider: AiProviderType,
    onInspectProvider: (AiProviderType) -> Unit,
    isTestingConnection: Boolean,
    connectionStatus: String?,
    onTestConnection: () -> Unit,
    onSetActiveProvider: (AiProviderType) -> Unit,
    onSaveProviderConfig: (AiProviderType, String, String?, String) -> Unit,
    onClearApiKey: (AiProviderType) -> Unit,
    onSetEnableFallback: (Boolean) -> Unit
) {
    var endpointInput by remember(inspectingProvider) {
        mutableStateOf(
            when (inspectingProvider) {
                AiProviderType.GEMINI_BUILTIN -> inspectingProvider.defaultEndpoint
                AiProviderType.GEMINI_PERSONAL -> inspectingProvider.defaultEndpoint
                AiProviderType.OPENAI -> "https://api.openai.com/v1"
                AiProviderType.OPENROUTER -> "https://openrouter.ai/api/v1"
                AiProviderType.CUSTOM_OPENAI -> settings.customApiEndpoint
            }
        )
    }

    var apiKeyInput by remember(inspectingProvider) { mutableStateOf("") }
    var modelInput by remember(inspectingProvider) {
        mutableStateOf(
            when (inspectingProvider) {
                AiProviderType.GEMINI_BUILTIN -> "gemini-2.5-flash"
                AiProviderType.GEMINI_PERSONAL -> settings.geminiCustomModel.ifBlank { "gemini-2.5-flash" }
                AiProviderType.OPENAI -> "gpt-4o-mini"
                AiProviderType.OPENROUTER -> "deepseek/deepseek-chat"
                AiProviderType.CUSTOM_OPENAI -> settings.customApiModel.ifBlank { "custom-model" }
            }
        )
    }

    val isActiveProvider = settings.activeAiProviderType == inspectingProvider

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // Provider selector chips (horizontal scroll)
        Text(
            text = "SELECT PROVIDER",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AiProviderType.entries.forEach { pType ->
                val isInspecting = inspectingProvider == pType
                val isCurrentlyActive = settings.activeAiProviderType == pType

                Surface(
                    color = if (isInspecting) CyanAccent else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    border = if (isCurrentlyActive && !isInspecting) BorderStroke(1.5.dp, CyanAccent) else null,
                    modifier = Modifier
                        .clickable { onInspectProvider(pType) }
                        .testTag("provider_chip_${pType.code.lowercase()}")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = pType.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isInspecting) FontWeight.Bold else FontWeight.Medium,
                            color = if (isInspecting) Color(0xFF001F26) else MaterialTheme.colorScheme.onSurface
                        )
                        if (isCurrentlyActive) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(if (isInspecting) Color(0xFF001F26) else CyanAccent, CircleShape)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Active card for selected provider
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Header with status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = inspectingProvider.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isActiveProvider) "● Active Primary Provider" else "Not currently active",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isActiveProvider) BullishGreen else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (!isActiveProvider) {
                        Button(
                            onClick = { onSetActiveProvider(inspectingProvider) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanAccent,
                                contentColor = Color(0xFF001F26)
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.testTag("set_active_provider_btn")
                        ) {
                            Text(
                                text = "Set Active",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(12.dp))

                // Provider specific notes
                when (inspectingProvider) {
                    AiProviderType.GEMINI_BUILTIN -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Gemini Built-in uses pre-configured server capability. No personal API key is required. Automatic fallback to local quantitative synthesis ensures uninterrupted market analysis.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                    else -> {
                        // Remote API Endpoint
                        Text(
                            text = "API Endpoint (HTTPS Required for Remote)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = endpointInput,
                            onValueChange = { endpointInput = it },
                            placeholder = { Text(inspectingProvider.defaultEndpoint) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanAccent,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("provider_endpoint_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // API Key Input
                        val hasSavedKey = when (inspectingProvider) {
                            AiProviderType.GEMINI_PERSONAL -> settings.hasGeminiCustomKey
                            else -> settings.hasCustomApiKey
                        }

                        Text(
                            text = if (hasSavedKey) "API Key (•••• Saved in encrypted storage)" else "API Key",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            placeholder = { Text(if (hasSavedKey) "••••••••••••••••" else "Enter API key") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanAccent,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("provider_api_key_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Model ID Input
                        Text(
                            text = "Model Identifier",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = modelInput,
                            onValueChange = { modelInput = it },
                            placeholder = { Text(inspectingProvider.defaultModel) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanAccent,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("provider_model_input")
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Save and Clear action buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    onSaveProviderConfig(
                                        inspectingProvider,
                                        endpointInput,
                                        apiKeyInput.takeIf { it.isNotBlank() },
                                        modelInput
                                    )
                                    apiKeyInput = ""
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyanAccent,
                                    contentColor = Color(0xFF001F26)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).testTag("save_provider_config_btn")
                            ) {
                                Text(
                                    text = "Save Config",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (hasSavedKey) {
                                OutlinedButton(
                                    onClick = {
                                        apiKeyInput = ""
                                        onClearApiKey(inspectingProvider)
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("clear_api_key_btn")
                                ) {
                                    Text(
                                        text = "Clear Key",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = BearishRed
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Connection Test Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onTestConnection,
                        enabled = !isTestingConnection,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanAccent.copy(alpha = 0.15f),
                            contentColor = CyanAccent
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("test_ai_connection_btn")
                    ) {
                        if (isTestingConnection) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = CyanAccent
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = if (isTestingConnection) "Testing..." else "[ Test Connection ]",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (!connectionStatus.isNullOrBlank()) {
                        val isOk = connectionStatus.contains("Connected", ignoreCase = true)
                        Text(
                            text = connectionStatus,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isOk) BullishGreen else BearishRed,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("ai_connection_status_text")
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Automatic Fallback Card
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "Enable AI Fallback",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Automatically failover to secondary provider or built-in quantitative synthesis if primary provider times out or encounters network errors.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = settings.enableAiFallback,
                    onCheckedChange = onSetEnableFallback,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CyanAccent,
                        checkedTrackColor = CyanAccent.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier.testTag("enable_fallback_switch")
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

/**
 * Tab 2: Dynamic Model Synchronization Content
 */
@Composable
private fun AiModelsTabContent(
    settings: TerminalSettings,
    inspectingProvider: AiProviderType,
    onInspectProvider: (AiProviderType) -> Unit,
    syncedModels: List<AiModelInfo>,
    isSyncing: Boolean,
    syncStatusMessage: String?,
    onSyncModels: (AiProviderType) -> Unit,
    onSelectModel: (AiProviderType, String) -> Unit
) {
    var manualModelInput by rememberSaveable { mutableStateOf("") }
    val currentSelectedModel = when (inspectingProvider) {
        AiProviderType.GEMINI_BUILTIN -> "gemini-2.5-flash"
        AiProviderType.GEMINI_PERSONAL -> settings.geminiCustomModel.ifBlank { "gemini-2.5-flash" }
        AiProviderType.OPENAI -> "gpt-4o-mini"
        AiProviderType.OPENROUTER -> "deepseek/deepseek-chat"
        AiProviderType.CUSTOM_OPENAI -> settings.customApiModel.ifBlank { "custom-model" }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // Provider selector tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AiProviderType.entries.forEach { pType ->
                val isSelected = inspectingProvider == pType
                Surface(
                    color = if (isSelected) CyanAccent else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable { onInspectProvider(pType) }
                ) {
                    Text(
                        text = pType.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color(0xFF001F26) else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Connect & Sync Action Card
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Dynamic Model Discovery",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Query ${inspectingProvider.displayName}'s documented model listing API to discover available models.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = { onSyncModels(inspectingProvider) },
                        enabled = !isSyncing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanAccent,
                            contentColor = Color(0xFF001F26)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("connect_sync_models_btn")
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF001F26)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = if (isSyncing) "Syncing..." else "Connect & Sync",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (!syncStatusMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    val isError = syncStatusMessage.contains("Error", ignoreCase = true) || syncStatusMessage.contains("failed", ignoreCase = true)
                    Surface(
                        color = if (isError) BearishRed.copy(alpha = 0.15f) else BullishGreen.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = syncStatusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isError) BearishRed else BullishGreen,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(10.dp).testTag("model_sync_status_message")
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Selected Model Card
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SELECTED MODEL FOR ANALYSIS",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = currentSelectedModel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    color = BullishGreen.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "ACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = BullishGreen,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Dynamic Synced Model List
        Text(
            text = "AVAILABLE MODELS (${syncedModels.size})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))

        if (syncedModels.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudSync,
                        contentDescription = "No synced models",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No models synchronized yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Press 'Connect & Sync' above to fetch models from ${inspectingProvider.displayName}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                syncedModels.forEach { model ->
                    val isSelected = currentSelectedModel.equals(model.id, ignoreCase = true)
                    Surface(
                        color = if (isSelected) CyanAccent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) CyanAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectModel(inspectingProvider, model.id) }
                            .testTag("model_item_${model.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = model.id,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) CyanAccent else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (model.supportsChat == true) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = BullishGreen.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "Chat",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 9.sp,
                                                color = BullishGreen,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    } else if (model.supportsChat == null) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = NeutralAmber.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "Capabilities: Unknown",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 9.sp,
                                                color = NeutralAmber,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }

                                if (model.displayName.isNotBlank() && model.displayName != model.id) {
                                    Text(
                                        text = model.displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (model.contextWindow != null && model.contextWindow > 0) {
                                    Text(
                                        text = "Context window: ${NumberFormat.getNumberInstance().format(model.contextWindow)} tokens",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }

                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = CyanAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Manual Model Entry Section
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Manual Model ID Entry",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Specify any custom model identifier if your endpoint does not support discovery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualModelInput,
                        onValueChange = { manualModelInput = it },
                        placeholder = { Text("e.g. gpt-4o, claude-3-7-sonnet") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.weight(1f).testTag("manual_model_id_input")
                    )

                    Button(
                        onClick = {
                            if (manualModelInput.isNotBlank()) {
                                onSelectModel(inspectingProvider, manualModelInput.trim())
                                manualModelInput = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = CyanAccent
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("apply_manual_model_btn")
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

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Notice: A model being listed does not necessarily mean it supports chat or text generation. Always verify model compatibility.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(20.dp))
    }
}

/**
 * Tab 3: Token Usage Dashboard Content
 */
@Composable
private fun AiUsageDashboardContent(
    dashboardData: AiUsageDashboardData,
    recentRecords: List<AiTokenUsageEntity>,
    onSetDailyBudget: (Long) -> Unit,
    onSetMonthlyBudget: (Long) -> Unit,
    onSetWarningThreshold: (Int) -> Unit,
    onSetBlockWhenExceeded: (Boolean) -> Unit,
    onClearAllUsage: () -> Unit
) {
    var showBudgetConfigDialog by rememberSaveable { mutableStateOf(false) }
    var showClearConfirmationDialog by rememberSaveable { mutableStateOf(false) }

    val numberFormat = remember { NumberFormat.getNumberInstance() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // Budget & Warnings Card
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "TOKEN USAGE BUDGETS",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanAccent,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Locally Enforced Limits",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    OutlinedButton(
                        onClick = { showBudgetConfigDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("configure_budget_btn")
                    ) {
                        Text(
                            text = "Configure",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyanAccent
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Daily Budget Progress
                val dailyBudget = dashboardData.dailyBudget
                val dailyUsed = dashboardData.todayTokens
                val dailyProgress = if (dailyBudget > 0) (dailyUsed.toFloat() / dailyBudget).coerceIn(0f, 1f) else 0f
                val dailyColor = when {
                    dailyBudget > 0 && dailyUsed >= dailyBudget -> BearishRed
                    dailyBudget > 0 && dailyUsed >= (dailyBudget * dashboardData.warningThresholdPercent / 100) -> NeutralAmber
                    else -> BullishGreen
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Daily Budget",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (dailyBudget > 0) {
                            "${numberFormat.format(dailyUsed)} / ${numberFormat.format(dailyBudget)} (${(dailyProgress * 100).toInt()}%)"
                        } else {
                            "${numberFormat.format(dailyUsed)} tokens (Unconstrained)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = dailyColor
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { if (dailyBudget > 0) dailyProgress else 0f },
                    color = dailyColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Monthly Budget Progress
                val monthlyBudget = dashboardData.monthlyBudget
                val monthlyUsed = dashboardData.monthTokens
                val monthlyProgress = if (monthlyBudget > 0) (monthlyUsed.toFloat() / monthlyBudget).coerceIn(0f, 1f) else 0f
                val monthlyColor = when {
                    monthlyBudget > 0 && monthlyUsed >= monthlyBudget -> BearishRed
                    monthlyBudget > 0 && monthlyUsed >= (monthlyBudget * dashboardData.warningThresholdPercent / 100) -> NeutralAmber
                    else -> CyanAccent
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Monthly Budget",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (monthlyBudget > 0) {
                            "${numberFormat.format(monthlyUsed)} / ${numberFormat.format(monthlyBudget)} (${(monthlyProgress * 100).toInt()}%)"
                        } else {
                            "${numberFormat.format(monthlyUsed)} tokens (Unconstrained)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = monthlyColor
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { if (monthlyBudget > 0) monthlyProgress else 0f },
                    color = monthlyColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                )

                // Warning / Limit Alert banner
                if (dashboardData.isDailyBudgetExceeded || dashboardData.isMonthlyBudgetExceeded) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = BearishRed.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Budget Exceeded",
                                tint = BearishRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (dashboardData.blockWhenBudgetExceeded) {
                                    "Budget limit reached! Cloud requests are blocked."
                                } else {
                                    "Budget limit reached. You can still analyze or adjust your budget."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = BearishRed,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else if (dashboardData.isDailyBudgetWarning || dashboardData.isMonthlyBudgetWarning) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = NeutralAmber.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Budget Warning",
                                tint = NeutralAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Approaching configured budget threshold (${dashboardData.warningThresholdPercent}%).",
                                style = MaterialTheme.typography.bodySmall,
                                color = NeutralAmber,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Locally enforced budgets, not official provider billing limits.",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Aggregate Metrics 6-Box Grid
        Text(
            text = "USAGE OVERVIEW",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            UsageMetricCard(
                label = "Today",
                value = numberFormat.format(dashboardData.todayTokens),
                modifier = Modifier.weight(1f)
            )
            UsageMetricCard(
                label = "This Month",
                value = numberFormat.format(dashboardData.monthTokens),
                modifier = Modifier.weight(1f)
            )
            UsageMetricCard(
                label = "All-Time",
                value = numberFormat.format(dashboardData.allTimeTokens),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            UsageMetricCard(
                label = "Input Tokens",
                value = numberFormat.format(dashboardData.totalInputTokens),
                modifier = Modifier.weight(1f)
            )
            UsageMetricCard(
                label = "Output Tokens",
                value = numberFormat.format(dashboardData.totalOutputTokens),
                modifier = Modifier.weight(1f)
            )
            UsageMetricCard(
                label = "Total Requests",
                value = "${dashboardData.totalRequests} (${dashboardData.successRequests}✓)",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Usage Per Provider
        if (dashboardData.perProvider.isNotEmpty()) {
            Text(
                text = "USAGE PER PROVIDER",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dashboardData.perProvider.forEach { p ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = p.providerDisplayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${p.requestCount} requests (${p.successCount} success)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "${numberFormat.format(p.totalTokens)} tokens",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = CyanAccent
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Usage Per Model
        if (dashboardData.perModel.isNotEmpty()) {
            Text(
                text = "USAGE PER MODEL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dashboardData.perModel.forEach { m ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = m.modelId,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${m.requestCount} requests",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "${numberFormat.format(m.totalTokens)} tokens",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = CyanAccent
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Recent Activity Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "RECENT REQUEST ACTIVITY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )

            if (recentRecords.isNotEmpty()) {
                Text(
                    text = "Clear All",
                    style = MaterialTheme.typography.labelSmall,
                    color = BearishRed,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { showClearConfirmationDialog = true }
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))

        if (recentRecords.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No token usage recorded yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recentRecords.take(40).forEach { record ->
                    RecentUsageItemCard(record = record)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    // Budget Configuration Dialog
    if (showBudgetConfigDialog) {
        var dailyInput by remember { mutableStateOf(if (dashboardData.dailyBudget > 0) dashboardData.dailyBudget.toString() else "") }
        var monthlyInput by remember { mutableStateOf(if (dashboardData.monthlyBudget > 0) dashboardData.monthlyBudget.toString() else "") }
        var thresholdInput by remember { mutableStateOf(dashboardData.warningThresholdPercent.toString()) }
        var blockExceeded by remember { mutableStateOf(dashboardData.blockWhenBudgetExceeded) }

        AlertDialog(
            onDismissRequest = { showBudgetConfigDialog = false },
            title = {
                Text(
                    text = "Configure Token Budgets",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Set local usage limits. Leave 0 or blank for unconstrained.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = dailyInput,
                        onValueChange = { dailyInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Daily Budget (Tokens)") },
                        placeholder = { Text("e.g. 50000") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = monthlyInput,
                        onValueChange = { monthlyInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Monthly Budget (Tokens)") },
                        placeholder = { Text("e.g. 1000000") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = thresholdInput,
                        onValueChange = { thresholdInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Warning Threshold (%)") },
                        placeholder = { Text("80") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Block requests when budget exceeded",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = blockExceeded,
                            onCheckedChange = { blockExceeded = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyanAccent,
                                checkedTrackColor = CyanAccent.copy(alpha = 0.4f)
                            )
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val dVal = dailyInput.toLongOrNull() ?: 0L
                        val mVal = monthlyInput.toLongOrNull() ?: 0L
                        val tVal = thresholdInput.toIntOrNull() ?: 80
                        onSetDailyBudget(dVal)
                        onSetMonthlyBudget(mVal)
                        onSetWarningThreshold(tVal)
                        onSetBlockWhenExceeded(blockExceeded)
                        showBudgetConfigDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanAccent,
                        contentColor = Color(0xFF001F26)
                    )
                ) {
                    Text("Save Budgets", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showBudgetConfigDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clear Confirmation Dialog
    if (showClearConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmationDialog = false },
            title = { Text("Clear All Usage Records?") },
            text = { Text("This will permanently remove all stored token usage history. Your budget settings will be preserved.") },
            confirmButton = {
                Button(
                    onClick = {
                        onClearAllUsage()
                        showClearConfirmationDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BearishRed)
                ) {
                    Text("Clear Records", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearConfirmationDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun UsageMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RecentUsageItemCard(record: AiTokenUsageEntity) {
    val dateFormat = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
        modifier = Modifier.fillMaxWidth().testTag("usage_record_${record.id}")
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = record.providerDisplayName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "· ${record.modelId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent
                    )
                }

                Surface(
                    color = if (record.isSuccess) BullishGreen.copy(alpha = 0.15f) else BearishRed.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = if (record.isSuccess) "Success" else "Failed",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = if (record.isSuccess) BullishGreen else BearishRed,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Token breakdown or unavailable
                val tokenSummary = when {
                    record.usageSource == TokenUsageSource.LOCAL.name -> "Local Quantitative Synthesis (0 Cloud Tokens)"
                    record.totalTokens != null && record.totalTokens > 0 -> {
                        val inT = record.inputTokens ?: 0
                        val outT = record.outputTokens ?: 0
                        "Tokens: ${record.totalTokens} (In: $inT · Out: $outT)"
                    }
                    else -> "Tokens: Unavailable"
                }

                Text(
                    text = tokenSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (record.usageSource == TokenUsageSource.LOCAL.name) NeutralAmber else MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = when (record.usageSource) {
                            TokenUsageSource.ACTUAL.name -> "Actual"
                            TokenUsageSource.ESTIMATED.name -> "Estimated"
                            TokenUsageSource.LOCAL.name -> "Local"
                            else -> "Unavailable"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = dateFormat.format(Date(record.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )

            if (!record.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Error: ${record.errorMessage}",
                    style = MaterialTheme.typography.labelSmall,
                    color = BearishRed,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
