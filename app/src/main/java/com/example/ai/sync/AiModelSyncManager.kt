package com.example.ai.sync

import com.example.BuildConfig
import com.example.storage.preferences.AiProviderType
import com.example.storage.preferences.CustomApiFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Model metadata discovered from an AI provider's API.
 */
data class AiModelInfo(
    val id: String,
    val displayName: String,
    val description: String = "",
    val contextWindow: Int? = null,
    val supportsChat: Boolean? = true,
    val providerCode: String = ""
)

sealed class ModelSyncResult {
    data class Success(
        val models: List<AiModelInfo>,
        val providerName: String,
        val syncTimestamp: Long = System.currentTimeMillis()
    ) : ModelSyncResult()

    data class Error(
        val message: String,
        val isDiscoveryUnsupported: Boolean = false,
        val statusCode: Int? = null
    ) : ModelSyncResult()
}

class AiModelSyncManager(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    /**
     * Connects to the provider and dynamically synchronizes available models.
     */
    suspend fun syncModels(
        providerType: AiProviderType,
        endpoint: String,
        apiKey: String
    ): ModelSyncResult = withContext(Dispatchers.IO) {
        val trimmedEndpoint = endpoint.trim()
        val trimmedKey = apiKey.trim()

        // 1. Validate Endpoint HTTPS for remote endpoints
        val endpointValidation = validateEndpointSecurity(trimmedEndpoint)
        if (!endpointValidation.isValid) {
            return@withContext ModelSyncResult.Error(
                message = endpointValidation.errorMessage ?: "Invalid endpoint URL"
            )
        }

        // 2. Validate API key if required
        val effectiveApiKey = when (providerType) {
            AiProviderType.GEMINI_BUILTIN -> {
                val envKey = BuildConfig.GEMINI_API_KEY.trim()
                if (envKey.isEmpty() || envKey == "MY_GEMINI_API_KEY") {
                    return@withContext ModelSyncResult.Error(
                        message = "Gemini Built-in uses the server-injected capability. No API key is configured in this build. You can select standard models or supply your own key in Gemini Personal API.",
                        isDiscoveryUnsupported = true
                    )
                }
                envKey
            }
            AiProviderType.GEMINI_PERSONAL -> {
                if (trimmedKey.isEmpty()) {
                    return@withContext ModelSyncResult.Error(
                        message = "Gemini Personal API key is required to sync models. Enter your Google AI Studio API key in the Provider tab."
                    )
                }
                trimmedKey
            }
            AiProviderType.OPENAI, AiProviderType.OPENROUTER -> {
                if (trimmedKey.isEmpty()) {
                    return@withContext ModelSyncResult.Error(
                        message = "${providerType.displayName} API key is required to sync models. Enter your key in the Provider tab."
                    )
                }
                trimmedKey
            }
            AiProviderType.CUSTOM_OPENAI -> trimmedKey
        }

        // 3. Query provider-specific model listing API
        try {
            when (providerType) {
                AiProviderType.GEMINI_BUILTIN, AiProviderType.GEMINI_PERSONAL -> {
                    syncGeminiModels(trimmedEndpoint, effectiveApiKey, providerType.displayName)
                }
                AiProviderType.OPENAI -> {
                    syncOpenAiModels(trimmedEndpoint, effectiveApiKey, providerType.displayName)
                }
                AiProviderType.OPENROUTER -> {
                    syncOpenRouterModels(trimmedEndpoint, effectiveApiKey, providerType.displayName)
                }
                AiProviderType.CUSTOM_OPENAI -> {
                    syncCustomModels(trimmedEndpoint, effectiveApiKey, providerType.displayName)
                }
            }
        } catch (e: UnknownHostException) {
            ModelSyncResult.Error("Network error: Unable to resolve host. Check your internet connection or endpoint.")
        } catch (e: Exception) {
            ModelSyncResult.Error("Failed to sync models: ${e.message?.take(150) ?: "Unknown error"}")
        }
    }

    private fun syncGeminiModels(endpoint: String, apiKey: String, providerName: String): ModelSyncResult {
        val base = endpoint.trimEnd('/')
        val url = if (base.contains("generativelanguage.googleapis.com")) {
            "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
        } else {
            "$base/v1beta/models?key=$apiKey"
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val code = response.code
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val errorMsg = parseErrorMessage(body) ?: "HTTP $code: ${response.message}"
                return ModelSyncResult.Error(
                    message = "Gemini sync failed ($errorMsg)",
                    statusCode = code
                )
            }

            val root = JSONObject(body)
            val modelsArray = root.optJSONArray("models") ?: JSONArray()
            val parsedModels = mutableListOf<AiModelInfo>()

            for (i in 0 until modelsArray.length()) {
                val obj = modelsArray.optJSONObject(i) ?: continue
                val rawName = obj.optString("name", "")
                val cleanId = rawName.removePrefix("models/").trim()
                if (cleanId.isEmpty()) continue

                val supportedMethods = obj.optJSONArray("supportedGenerationMethods")
                var supportsGenerate = false
                if (supportedMethods != null) {
                    for (m in 0 until supportedMethods.length()) {
                        if (supportedMethods.optString(m).equals("generateContent", ignoreCase = true)) {
                            supportsGenerate = true
                            break
                        }
                    }
                } else {
                    supportsGenerate = true // If methods array omitted, assume capable
                }

                // Filter out embedding-only or image-only models to avoid invalid market analyses
                if (!supportsGenerate) continue
                if (cleanId.contains("embedding", ignoreCase = true) || cleanId.contains("aqa", ignoreCase = true)) {
                    continue
                }

                val displayName = obj.optString("displayName", cleanId).ifBlank { cleanId }
                val description = obj.optString("description", "")
                val inputTokenLimit = obj.optInt("inputTokenLimit", 0).takeIf { it > 0 }

                parsedModels.add(
                    AiModelInfo(
                        id = cleanId,
                        displayName = displayName,
                        description = description,
                        contextWindow = inputTokenLimit,
                        supportsChat = true,
                        providerCode = "gemini"
                    )
                )
            }

            // Sort priority models (flash, pro) to top
            parsedModels.sortWith(
                compareByDescending<AiModelInfo> { it.id.contains("flash", ignoreCase = true) }
                    .thenByDescending { it.id.contains("pro", ignoreCase = true) }
                    .thenBy { it.id }
            )

            if (parsedModels.isEmpty()) {
                return ModelSyncResult.Error(
                    message = "No text generation models returned from Gemini API.",
                    isDiscoveryUnsupported = false
                )
            }

            return ModelSyncResult.Success(
                models = parsedModels,
                providerName = providerName
            )
        }
    }

    private fun syncOpenAiModels(endpoint: String, apiKey: String, providerName: String): ModelSyncResult {
        val base = endpoint.trimEnd('/')
        val url = if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val code = response.code
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val errorMsg = parseErrorMessage(body) ?: "HTTP $code: ${response.message}"
                return ModelSyncResult.Error(
                    message = "OpenAI sync failed ($errorMsg)",
                    statusCode = code
                )
            }

            val root = JSONObject(body)
            val dataArray = root.optJSONArray("data") ?: JSONArray()
            val parsedModels = mutableListOf<AiModelInfo>()

            for (i in 0 until dataArray.length()) {
                val obj = dataArray.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").trim()
                if (id.isEmpty()) continue

                // Exclude embedding, audio, tts, whisper, dall-e models
                val isNonChat = id.contains("whisper", ignoreCase = true) ||
                    id.contains("dall-e", ignoreCase = true) ||
                    id.contains("tts", ignoreCase = true) ||
                    id.contains("embedding", ignoreCase = true) ||
                    id.contains("babbage", ignoreCase = true) ||
                    id.contains("davinci", ignoreCase = true)

                val isChatModel = id.startsWith("gpt-", ignoreCase = true) ||
                    id.startsWith("o1", ignoreCase = true) ||
                    id.startsWith("o3", ignoreCase = true) ||
                    id.startsWith("chatgpt", ignoreCase = true)

                if (isNonChat) continue

                val contextWin = when {
                    id.contains("gpt-4o", ignoreCase = true) -> 128000
                    id.contains("o1", ignoreCase = true) || id.contains("o3", ignoreCase = true) -> 200000
                    id.contains("gpt-4-turbo", ignoreCase = true) -> 128000
                    id.contains("gpt-4", ignoreCase = true) -> 8192
                    id.contains("gpt-3.5", ignoreCase = true) -> 16384
                    else -> null
                }

                parsedModels.add(
                    AiModelInfo(
                        id = id,
                        displayName = id,
                        description = if (isChatModel) "Chat & Instruction model" else "Language model",
                        contextWindow = contextWin,
                        supportsChat = isChatModel,
                        providerCode = "openai"
                    )
                )
            }

            // Prioritize modern models: gpt-4o, gpt-4o-mini, o1, o3
            parsedModels.sortWith(
                compareByDescending<AiModelInfo> { it.id == "gpt-4o-mini" }
                    .thenByDescending { it.id == "gpt-4o" }
                    .thenByDescending { it.id.startsWith("o1") || it.id.startsWith("o3") }
                    .thenByDescending { it.id.startsWith("gpt-4") }
                    .thenBy { it.id }
            )

            if (parsedModels.isEmpty()) {
                return ModelSyncResult.Error("No compatible language models returned from OpenAI endpoint.")
            }

            return ModelSyncResult.Success(
                models = parsedModels,
                providerName = providerName
            )
        }
    }

    private fun syncOpenRouterModels(endpoint: String, apiKey: String, providerName: String): ModelSyncResult {
        val base = endpoint.trimEnd('/')
        val url = if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("HTTP-Referer", "https://lumavun.chart")
            .header("X-Title", "LUMAVUN Chart")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val code = response.code
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val errorMsg = parseErrorMessage(body) ?: "HTTP $code: ${response.message}"
                return ModelSyncResult.Error(
                    message = "OpenRouter sync failed ($errorMsg)",
                    statusCode = code
                )
            }

            val root = JSONObject(body)
            val dataArray = root.optJSONArray("data") ?: JSONArray()
            val parsedModels = mutableListOf<AiModelInfo>()

            for (i in 0 until dataArray.length()) {
                val obj = dataArray.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").trim()
                if (id.isEmpty()) continue

                val name = obj.optString("name", id).ifBlank { id }
                val description = obj.optString("description", "")
                val contextLength = obj.optInt("context_length", 0).takeIf { it > 0 }

                parsedModels.add(
                    AiModelInfo(
                        id = id,
                        displayName = name,
                        description = description.take(120),
                        contextWindow = contextLength,
                        supportsChat = true,
                        providerCode = "openrouter"
                    )
                )
            }

            // Prioritize popular models (deepseek, anthropic/claude, openai/gpt, meta-llama)
            parsedModels.sortWith(
                compareByDescending<AiModelInfo> { it.id.contains("deepseek-chat", ignoreCase = true) }
                    .thenByDescending { it.id.contains("claude-3-5", ignoreCase = true) }
                    .thenByDescending { it.id.contains("gpt-4o", ignoreCase = true) }
                    .thenByDescending { it.id.contains("llama-3", ignoreCase = true) }
                    .thenBy { it.displayName }
            )

            if (parsedModels.isEmpty()) {
                return ModelSyncResult.Error("No models returned from OpenRouter.")
            }

            return ModelSyncResult.Success(
                models = parsedModels,
                providerName = providerName
            )
        }
    }

    private fun syncCustomModels(endpoint: String, apiKey: String, providerName: String): ModelSyncResult {
        val base = endpoint.trimEnd('/')
        val urlsToTry = mutableListOf<String>()
        if (base.endsWith("/v1")) {
            urlsToTry.add("$base/models")
            urlsToTry.add(base.removeSuffix("/v1") + "/models")
        } else {
            urlsToTry.add("$base/v1/models")
            urlsToTry.add("$base/models")
        }

        var lastError: String? = null
        var lastCode: Int? = null

        for (url in urlsToTry) {
            val reqBuilder = Request.Builder().url(url).get()
            if (apiKey.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $apiKey")
            }

            try {
                httpClient.newCall(reqBuilder.build()).execute().use { response ->
                    lastCode = response.code
                    val body = response.body?.string().orEmpty()

                    if (response.isSuccessful) {
                        val root = JSONObject(body)
                        val array = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
                        val parsed = mutableListOf<AiModelInfo>()

                        for (i in 0 until array.length()) {
                            val item = array.get(i)
                            val modelId = when (item) {
                                is JSONObject -> item.optString("id", item.optString("name", "")).trim()
                                is String -> item.trim()
                                else -> ""
                            }
                            if (modelId.isNotEmpty()) {
                                parsed.add(
                                    AiModelInfo(
                                        id = modelId,
                                        displayName = modelId,
                                        description = "Custom endpoint model",
                                        supportsChat = null, // Unknown capabilities
                                        providerCode = "custom"
                                    )
                                )
                            }
                        }

                        if (parsed.isNotEmpty()) {
                            return ModelSyncResult.Success(
                                models = parsed,
                                providerName = providerName
                            )
                        }
                    } else if (response.code == 404) {
                        lastError = "Endpoint does not support '/models' discovery (HTTP 404)."
                    } else {
                        val parsedErr = parseErrorMessage(body)
                        lastError = parsedErr ?: "HTTP ${response.code}: ${response.message}"
                    }
                }
            } catch (e: Exception) {
                lastError = e.message
            }
        }

        return ModelSyncResult.Error(
            message = lastError ?: "Custom endpoint model discovery failed. You can enter your model ID manually.",
            isDiscoveryUnsupported = true,
            statusCode = lastCode
        )
    }

    internal data class ValidationResult(val isValid: Boolean, val errorMessage: String? = null)

    internal fun validateEndpointSecurity(endpoint: String): ValidationResult {
        if (endpoint.isBlank()) {
            return ValidationResult(false, "Endpoint URL cannot be empty.")
        }
        val uri = try {
            URI.create(endpoint)
        } catch (_: Exception) {
            return ValidationResult(false, "Invalid URL syntax.")
        }

        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase().orEmpty()

        if (scheme == null || (scheme != "https" && scheme != "http")) {
            return ValidationResult(false, "Endpoint must start with https:// or http://")
        }

        // Allow http ONLY for local development / emulators
        val isLocalhost = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2"
        if (scheme == "http" && !isLocalhost) {
            return ValidationResult(false, "Insecure HTTP is only permitted for localhost/emulator. Remote endpoints must use HTTPS.")
        }

        return ValidationResult(true)
    }

    private fun parseErrorMessage(responseBody: String): String? {
        return try {
            val json = JSONObject(responseBody)
            if (json.has("error")) {
                val err = json.get("error")
                when (err) {
                    is JSONObject -> if (err.has("message")) err.getString("message") else null
                    is String -> err
                    else -> null
                }
            } else {
                if (json.has("message")) json.getString("message") else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
