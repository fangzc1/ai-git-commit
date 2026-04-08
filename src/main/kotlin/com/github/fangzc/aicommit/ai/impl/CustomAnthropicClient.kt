package com.github.fangzc.aicommit.ai.impl

import java.net.http.HttpClient

/**
 * 自定义 Anthropic 兼容端点客户端
 * 使用 Anthropic 协议（x-api-key + /v1/messages + SSE content_block_delta）
 * 适用于自建 Claude 代理或第三方 Anthropic 兼容服务
 */
class CustomAnthropicClient(
    apiKey: String,
    baseUrl: String,
    model: String,
    httpClient: HttpClient,
    temperature: Double = 0.7,
    maxTokens: Int = 1024
) : AnthropicClient(apiKey, baseUrl, model, httpClient, temperature, maxTokens) {

    override fun getEndpointUrl(): String {
        val url = baseUrl.trimEnd('/')
        // 若用户已提供完整路径（含 /messages），直接使用
        return if (url.endsWith("/messages")) {
            url
        } else {
            // buildVersionedUrl 自动检测末尾是否含版本号（/v1 等），避免双重前缀
            buildVersionedUrl(url, "messages")
        }
    }

    /**
     * 规范化 baseUrl 以获取模型列表端点的根地址
     * 剥离用户可能输入的 /v1/messages 或 /messages 后缀
     */
    override fun getModelsBaseUrl(): String {
        val url = baseUrl.trimEnd('/')
        return when {
            url.endsWith("/v1/messages") -> url.removeSuffix("/v1/messages")
            url.endsWith("/messages") -> url.removeSuffix("/messages")
            else -> url
        }
    }
}
