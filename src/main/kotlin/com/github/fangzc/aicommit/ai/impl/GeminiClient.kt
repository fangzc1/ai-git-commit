package com.github.fangzc.aicommit.ai.impl

import com.github.fangzc.aicommit.ai.AbstractAiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Google Gemini API 客户端
 * 端点：POST /v1beta/models/{model}:streamGenerateContent?alt=sse&key={apiKey}
 * 认证：URL 参数 key
 */
class GeminiClient(
    apiKey: String,
    baseUrl: String,
    model: String,
    httpClient: HttpClient,
    temperature: Double = 0.7,
    maxTokens: Int = 1024
) : AbstractAiClient(apiKey, baseUrl, model, httpClient, temperature, maxTokens) {

    override fun getEndpointUrl(): String =
        "${baseUrl.trimEnd('/')}/v1beta/models/$model:streamGenerateContent?alt=sse&key=$apiKey"

    override fun buildHeaders(): Map<String, String> = mapOf(
        "Content-Type" to "application/json"
    )

    override fun buildRequestBody(prompt: String, stream: Boolean): String {
        val json = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", prompt)
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", temperature)
                put("maxOutputTokens", maxTokens)
            }
        }
        return json.toString()
    }

    override fun parseStreamChunk(data: String): String? {
        return try {
            val json = Json.parseToJsonElement(data).jsonObject
            val candidates = json["candidates"]?.jsonArray ?: return null
            val content = candidates.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject ?: return null
            val parts = content["parts"]?.jsonArray ?: return null
            parts.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }

    override fun parseFullResponse(responseBody: String): String {
        return parseStreamChunk(responseBody) ?: ""
    }

    override suspend fun testConnection(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val testUrl = "${baseUrl.trimEnd('/')}/v1beta/models/$model:generateContent?key=$apiKey"
                val requestBody = buildRequestBody("Say 'ok'", stream = false)
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(testUrl))
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .header("Content-Type", JSON_CONTENT_TYPE)
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
                response.statusCode() in 200..299
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Gemini 模型列表：GET /v1beta/models?key=...
     * 返回格式：{"models": [{"name": "models/gemini-pro", ...}]}
     * 注意：HTTP 错误或网络异常会直接抛出，由调用方负责处理
     */
    override suspend fun fetchModels(): List<String> {
        return withContext(Dispatchers.IO) {
            val url = "${baseUrl.trimEnd('/')}/v1beta/models?key=$apiKey"
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                val errorSnippet = response.body().take(300)
                throw RuntimeException("HTTP ${response.statusCode()}: $errorSnippet")
            }
            val json = Json.parseToJsonElement(response.body()).jsonObject
            val models = json["models"]?.jsonArray ?: emptyList()
            models.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                .map { it.removePrefix("models/") }
                .sorted()
        }
    }
}
