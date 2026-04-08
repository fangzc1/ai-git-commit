package com.github.fangzc.aicommit.ai

import com.github.fangzc.aicommit.ai.impl.AnthropicClient
import com.github.fangzc.aicommit.ai.impl.CustomAnthropicClient
import com.github.fangzc.aicommit.ai.impl.CustomClient
import com.github.fangzc.aicommit.ai.impl.GeminiClient
import com.github.fangzc.aicommit.ai.impl.OpenAiClient
import com.github.fangzc.aicommit.ai.impl.OpenRouterClient
import com.github.fangzc.aicommit.settings.PluginSettings
import com.github.fangzc.aicommit.util.ProxyConfigUtil

/**
 * AI 客户端工厂：根据配置创建对应的 AiClient 实例
 */
object AiClientFactory {

    /**
     * 根据全局配置创建 AiClient
     */
    fun createFromSettings(): AiClient {
        val settings = PluginSettings.getInstance()
        val state = settings.stateData
        return create(
            provider = state.provider,
            apiKey = settings.apiKey,
            apiBaseUrl = state.apiBaseUrl,
            model = state.model,
            temperature = state.temperature,
            maxTokens = state.maxTokens
        )
    }

    /**
     * 根据指定参数创建 AiClient（用于 Test Connection）
     */
    fun create(
        provider: AiProvider,
        apiKey: String,
        apiBaseUrl: String,
        model: String,
        temperature: Double = 0.7,
        maxTokens: Int = 1024
    ): AiClient {
        val httpClient = ProxyConfigUtil.buildHttpClient()

        return when (provider) {
            AiProvider.OPENAI -> OpenAiClient(
                apiKey = apiKey,
                baseUrl = apiBaseUrl.ifBlank { AiProvider.OPENAI.defaultApiUrl },
                model = model,
                httpClient = httpClient,
                temperature = temperature,
                maxTokens = maxTokens
            )
            AiProvider.ANTHROPIC -> AnthropicClient(
                apiKey = apiKey,
                baseUrl = apiBaseUrl.ifBlank { AiProvider.ANTHROPIC.defaultApiUrl },
                model = model,
                httpClient = httpClient,
                temperature = temperature,
                maxTokens = maxTokens
            )
            AiProvider.GEMINI -> GeminiClient(
                apiKey = apiKey,
                baseUrl = apiBaseUrl.ifBlank { AiProvider.GEMINI.defaultApiUrl },
                model = model,
                httpClient = httpClient,
                temperature = temperature,
                maxTokens = maxTokens
            )
            AiProvider.OPENROUTER -> OpenRouterClient(
                apiKey = apiKey,
                baseUrl = apiBaseUrl.ifBlank { AiProvider.OPENROUTER.defaultApiUrl },
                model = model,
                httpClient = httpClient,
                temperature = temperature,
                maxTokens = maxTokens
            )
            AiProvider.CUSTOM -> CustomClient(
                apiKey = apiKey,
                baseUrl = apiBaseUrl,
                model = model,
                httpClient = httpClient,
                temperature = temperature,
                maxTokens = maxTokens
            )
            AiProvider.CUSTOM_ANTHROPIC -> CustomAnthropicClient(
                apiKey = apiKey,
                baseUrl = apiBaseUrl,
                model = model,
                httpClient = httpClient,
                temperature = temperature,
                maxTokens = maxTokens
            )
        }
    }
}
