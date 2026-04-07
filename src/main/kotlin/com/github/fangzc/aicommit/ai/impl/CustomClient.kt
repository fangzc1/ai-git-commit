package com.github.fangzc.aicommit.ai.impl

import java.net.http.HttpClient

/**
 * 自定义 OpenAI 兼容端点客户端
 * 与 OpenAiClient 完全一致，仅允许用户自定义 API URL
 */
class CustomClient(
    apiKey: String,
    baseUrl: String,
    model: String,
    httpClient: HttpClient,
    temperature: Double = 0.7,
    maxTokens: Int = 1024
) : OpenAiClient(apiKey, baseUrl, model, httpClient, temperature, maxTokens) {

    override fun getEndpointUrl(): String {
        val url = baseUrl.trimEnd('/')
        // 如果用户已经提供了完整路径（包含 /chat/completions），直接使用
        return if (url.endsWith("/chat/completions")) {
            url
        } else {
            // buildVersionedUrl 会自动检测末尾是否含版本号（/v1、/v4 等），避免双重版本前缀
            buildVersionedUrl(url, "chat/completions")
        }
    }

    /**
     * 规范化 baseUrl 以获取模型列表端点的真实根地址
     * 用户可能输入完整路径（含 /chat/completions），需剥离；
     * 版本号部分（/v1、/v4 等）保留，由 buildVersionedUrl 统一处理
     */
    override fun getModelsBaseUrl(): String {
        val url = baseUrl.trimEnd('/')
        return when {
            url.endsWith("/v1/chat/completions") -> url.removeSuffix("/v1/chat/completions")
            url.endsWith("/chat/completions") -> url.removeSuffix("/chat/completions")
            else -> url  // 含版本号（/v1、/v4）或无版本号，均由 buildVersionedUrl 处理
        }
    }
}
