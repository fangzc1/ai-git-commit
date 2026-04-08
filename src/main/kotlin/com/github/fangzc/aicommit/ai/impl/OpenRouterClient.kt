package com.github.fangzc.aicommit.ai.impl

import java.net.http.HttpClient

/**
 * OpenRouter API 客户端
 * 基于 OpenAI 兼容协议（POST /v1/chat/completions）
 * 默认端点：https://openrouter.ai/api
 * 额外请求头：HTTP-Referer、X-Title（OpenRouter 推荐携带，用于展示来源）
 */
class OpenRouterClient(
    apiKey: String,
    baseUrl: String,
    model: String,
    httpClient: HttpClient,
    temperature: Double = 0.7,
    maxTokens: Int = 1024
) : OpenAiClient(apiKey, baseUrl, model, httpClient, temperature, maxTokens) {

    override fun buildHeaders(): Map<String, String> =
        super.buildHeaders() + mapOf(
            "HTTP-Referer" to "jetbrains-ai-commit-plugin",
            "X-Title" to "AI Commit Plugin"
        )
}
