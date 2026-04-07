package com.github.fangzc.aicommit.ai.impl

import com.github.fangzc.aicommit.ai.AbstractAiClient
import kotlinx.serialization.json.*
import java.net.http.HttpClient

/**
 * Anthropic API 客户端
 * 端点：POST /v1/messages
 * 认证：x-api-key header
 * SSE 事件类型：content_block_delta -> delta.text
 */
class AnthropicClient(
    apiKey: String,
    baseUrl: String,
    model: String,
    httpClient: HttpClient,
    temperature: Double = 0.7,
    maxTokens: Int = 1024
) : AbstractAiClient(apiKey, baseUrl, model, httpClient, temperature, maxTokens) {

    override fun getEndpointUrl(): String = buildVersionedUrl(baseUrl, "messages")

    override fun buildHeaders(): Map<String, String> = mapOf(
        "x-api-key" to apiKey,
        "anthropic-version" to "2023-06-01",
        "Content-Type" to "application/json"
    )

    override fun buildRequestBody(prompt: String, stream: Boolean): String {
        val json = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put("stream", stream)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
        }
        return json.toString()
    }

    override fun parseStreamChunk(data: String): String? {
        return try {
            val json = Json.parseToJsonElement(data).jsonObject
            val type = json["type"]?.jsonPrimitive?.contentOrNull ?: return null

            when (type) {
                "content_block_delta" -> {
                    val delta = json["delta"]?.jsonObject ?: return null
                    delta["text"]?.jsonPrimitive?.contentOrNull
                }
                "message_stop" -> null
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun isStreamDone(data: String): Boolean {
        return try {
            val json = Json.parseToJsonElement(data).jsonObject
            json["type"]?.jsonPrimitive?.contentOrNull == "message_stop"
        } catch (_: Exception) {
            false
        }
    }

    override fun parseFullResponse(responseBody: String): String {
        val json = Json.parseToJsonElement(responseBody).jsonObject
        val content = json["content"]?.jsonArray ?: return ""
        return content.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
    }
}
