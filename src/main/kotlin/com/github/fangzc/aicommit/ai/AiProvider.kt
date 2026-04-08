package com.github.fangzc.aicommit.ai

/**
 * 支持的 AI 服务提供商枚举
 */
enum class AiProvider(
    val displayName: String,
    val defaultApiUrl: String,
    val defaultModels: List<String>
) {
    OPENAI(
        displayName = "OpenAI",
        defaultApiUrl = "https://api.openai.com",
        defaultModels = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1-nano", "o4-mini")
    ),
    ANTHROPIC(
        displayName = "Anthropic",
        defaultApiUrl = "https://api.anthropic.com",
        defaultModels = listOf("claude-sonnet-4-20250514", "claude-haiku-4-20250414")
    ),
    GEMINI(
        displayName = "Gemini",
        defaultApiUrl = "https://generativelanguage.googleapis.com",
        defaultModels = listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.5-pro")
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        defaultApiUrl = "https://openrouter.ai/api",
        defaultModels = emptyList()
    ),
    CUSTOM(
        displayName = "Custom (OpenAI Compatible)",
        defaultApiUrl = "",
        defaultModels = emptyList()
    ),
    CUSTOM_ANTHROPIC(
        displayName = "Custom (Anthropic Compatible)",
        defaultApiUrl = "",
        defaultModels = emptyList()
    );

    companion object {
        fun fromDisplayName(name: String): AiProvider =
            entries.find { it.displayName == name } ?: OPENAI
    }
}
