package com.example.ai.vision

import com.example.storage.preferences.AiProviderMode
import com.example.storage.preferences.AiProviderType
import java.util.Locale

/**
 * Capability-aware registry for multimodal vision input in LUMAVUN Chart.
 *
 * Enforces strict capability validation:
 * - Does not infer vision capability solely from a successful model list response.
 * - Does not send image payloads to text-only endpoints.
 * - Respects the user's explicit Chart Vision setting (Auto vs Off).
 * - For Custom API, honors the explicit user-verified toggle [customApiSupportsVision].
 */
object VisionCapabilityRegistry {

    private val VERIFIED_GEMINI_VISION_PREFIXES = listOf(
        "gemini-1.5",
        "gemini-2.0",
        "gemini-2.5",
        "gemini-3.5",
        "gemini-pro-vision"
    )

    private val VERIFIED_OPENAI_VISION_MODELS = setOf(
        "gpt-4o",
        "gpt-4o-mini",
        "gpt-4-turbo",
        "gpt-4-turbo-2024-04-09",
        "chatgpt-4o-latest"
    )

    private val VERIFIED_OPENROUTER_VISION_PREFIXES = listOf(
        "google/gemini",
        "anthropic/claude-3",
        "openai/gpt-4o",
        "meta-llama/llama-3.2-11b-vision",
        "meta-llama/llama-3.2-90b-vision",
        "qwen/qwen-2-vl",
        "mistralai/pixtral"
    )

    private val KNOWN_TEXT_ONLY_PATTERNS = listOf(
        "deepseek/deepseek-chat",
        "deepseek/deepseek-r1",
        "deepseek-chat",
        "deepseek-r1",
        "meta-llama/llama-3.3-70b-instruct",
        "meta-llama/llama-3.1-8b-instruct",
        "meta-llama/llama-3.1-70b-instruct",
        "gpt-3.5-turbo",
        "o1-mini",
        "o3-mini"
    )

    /**
     * Checks if a specific provider and model identifier supports multimodal vision payloads.
     */
    fun supportsVision(
        providerType: AiProviderType,
        modelName: String,
        customApiSupportsVision: Boolean = false
    ): Boolean {
        val cleanModel = modelName.trim().lowercase(Locale.US)

        // Check explicit text-only blacklist first
        if (KNOWN_TEXT_ONLY_PATTERNS.any { cleanModel == it || cleanModel.startsWith("$it:") }) {
            return false
        }

        return when (providerType) {
            AiProviderType.GEMINI_BUILTIN,
            AiProviderType.GEMINI_PERSONAL -> {
                val modelId = cleanModel.removePrefix("models/")
                VERIFIED_GEMINI_VISION_PREFIXES.any { modelId.startsWith(it) } ||
                    modelId.contains("flash") ||
                    modelId.contains("pro")
            }

            AiProviderType.OPENAI -> {
                VERIFIED_OPENAI_VISION_MODELS.contains(cleanModel) ||
                    cleanModel.startsWith("gpt-4o") ||
                    cleanModel.contains("vision")
            }

            AiProviderType.OPENROUTER -> {
                VERIFIED_OPENROUTER_VISION_PREFIXES.any { cleanModel.startsWith(it) }
            }

            AiProviderType.CUSTOM_OPENAI -> {
                if (customApiSupportsVision) return true
                // If user hasn't explicitly toggled it on, check if model name is explicitly a verified vision model
                VERIFIED_OPENAI_VISION_MODELS.contains(cleanModel) || cleanModel.startsWith("gpt-4o")
            }
        }
    }

    /**
     * Checks if legacy [AiProviderMode] configuration supports vision.
     */
    fun supportsVisionForLegacyMode(
        mode: AiProviderMode,
        modelName: String,
        customApiSupportsVision: Boolean = false
    ): Boolean {
        return when (mode) {
            AiProviderMode.BUILT_IN -> supportsVision(AiProviderType.GEMINI_BUILTIN, modelName)
            AiProviderMode.GEMINI_API -> supportsVision(AiProviderType.GEMINI_PERSONAL, modelName)
            AiProviderMode.CUSTOM_API -> supportsVision(AiProviderType.CUSTOM_OPENAI, modelName, customApiSupportsVision)
        }
    }
}
