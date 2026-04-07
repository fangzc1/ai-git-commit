package com.github.fangzc.aicommit.ai.impl

import com.github.fangzc.aicommit.ai.AbstractAiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.coroutines.coroutineContext

/**
 * OpenAI API 客户端
 * 主端点：POST /v1/chat/completions
 * 降级端点：POST /v1/responses（用于 gpt-5.4 等新模型，不支持 chat/completions）
 */
open class OpenAiClient(
    apiKey: String,
    baseUrl: String,
    model: String,
    httpClient: HttpClient,
    temperature: Double = 0.7,
    maxTokens: Int = 1024
) : AbstractAiClient(apiKey, baseUrl, model, httpClient, temperature, maxTokens) {

    override fun getEndpointUrl(): String = buildVersionedUrl(baseUrl, "chat/completions")

    override fun buildHeaders(): Map<String, String> = mapOf(
        "Authorization" to "Bearer $apiKey",
        "Content-Type" to "application/json"
    )

    override fun buildRequestBody(prompt: String, stream: Boolean): String {
        val json = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
            put("stream", stream)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
        }
        return json.toString()
    }

    override fun parseStreamChunk(data: String): String? {
        return try {
            val json = Json.parseToJsonElement(data).jsonObject
            val choices = json["choices"]?.jsonArray ?: return null
            val delta = choices.firstOrNull()?.jsonObject?.get("delta")?.jsonObject ?: return null
            delta["content"]?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }

    override fun parseFullResponse(responseBody: String): String {
        val json = Json.parseToJsonElement(responseBody).jsonObject
        val choices = json["choices"]?.jsonArray ?: return ""
        val message = choices.firstOrNull()?.jsonObject?.get("message")?.jsonObject ?: return ""
        return message["content"]?.jsonPrimitive?.contentOrNull ?: ""
    }

    /**
     * 重写流式生成：先尝试 /v1/chat/completions，
     * 若收到"不支持该模型"错误则自动降级到 /v1/responses（新模型如 gpt-5.4 系列）
     */
    override suspend fun generateStreaming(
        prompt: String,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                val requestBody = buildRequestBody(prompt, stream = true)
                val requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(getEndpointUrl()))
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .header("Content-Type", JSON_CONTENT_TYPE)

                buildHeaders().forEach { (key, value) ->
                    requestBuilder.header(key, value)
                }

                val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())

                if (response.statusCode() == 400) {
                    val errorBody = response.body().readBytes().toString(Charsets.UTF_8)
                    // 检测到"模型不支持 chat/completions"时，降级到 Responses API
                    if (isUnsupportedApiError(errorBody)) {
                        generateWithResponsesApi(prompt, onToken, onComplete, onError)
                        return@withContext
                    }
                    onError(RuntimeException("API request failed (400): $errorBody"))
                    return@withContext
                }

                if (response.statusCode() !in 200..299) {
                    val errorBody = response.body().readBytes().toString(Charsets.UTF_8)
                    onError(RuntimeException("API request failed (${response.statusCode()}): $errorBody"))
                    return@withContext
                }

                val reader = BufferedReader(InputStreamReader(response.body(), Charsets.UTF_8))
                processSSEStream(reader, onToken, onComplete, onError)
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    /**
     * 重写测试连接：先尝试 /v1/chat/completions，
     * 若模型不支持则自动降级到 /v1/responses（兼容 gpt-5.4 等新模型）
     */
    override suspend fun testConnection(): Boolean {
        return withContext(Dispatchers.IO) {
            val requestBody = buildRequestBody("Say 'ok'", stream = false)
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(getEndpointUrl()))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .header("Content-Type", JSON_CONTENT_TYPE)

            buildHeaders().forEach { (key, value) ->
                requestBuilder.header(key, value)
            }

            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 400 && isUnsupportedApiError(response.body())) {
                // 降级：使用 /v1/responses 端点测试连接
                val responsesUrl = getEndpointUrl().removeSuffix("chat/completions") + "responses"
                val responsesBody = buildJsonObject {
                    put("model", model)
                    put("input", "Say 'ok'")
                    put("stream", false)
                }.toString()
                val responsesRequest = HttpRequest.newBuilder()
                    .uri(URI.create(responsesUrl))
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(responsesBody))
                    .header("Content-Type", JSON_CONTENT_TYPE)

                buildHeaders().forEach { (key, value) ->
                    responsesRequest.header(key, value)
                }

                val responsesResponse = httpClient.send(responsesRequest.build(), HttpResponse.BodyHandlers.ofString())
                if (responsesResponse.statusCode() !in 200..299) {
                    val errorSnippet = responsesResponse.body().take(300)
                    throw RuntimeException("HTTP ${responsesResponse.statusCode()}: $errorSnippet")
                }
                return@withContext true
            }

            if (response.statusCode() !in 200..299) {
                val errorSnippet = response.body().take(300)
                throw RuntimeException("HTTP ${response.statusCode()}: $errorSnippet")
            }
            true
        }
    }

    /**
     * 判断错误是否为"模型不支持 /chat/completions 端点"
     */
    private fun isUnsupportedApiError(errorBody: String): Boolean =
        errorBody.contains("unsupported_api_for_model") ||
                errorBody.contains("not accessible via the /chat/completions endpoint")

    /**
     * 使用 OpenAI Responses API（/v1/responses）进行流式生成
     * 适用于 gpt-5.4 等新模型
     */
    private suspend fun generateWithResponsesApi(
        prompt: String,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        // 从 chat/completions 端点推导同级的 responses 端点，避免 baseUrl 已含 /v1 时出现双重前缀
        val responsesUrl = getEndpointUrl().removeSuffix("chat/completions") + "responses"
        val requestBody = buildJsonObject {
            put("model", model)
            put("input", prompt)
            put("stream", true)
        }.toString()

        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(responsesUrl))
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .header("Content-Type", JSON_CONTENT_TYPE)

        buildHeaders().forEach { (key, value) ->
            requestBuilder.header(key, value)
        }

        val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())

        if (response.statusCode() !in 200..299) {
            val errorBody = response.body().readBytes().toString(Charsets.UTF_8)
            onError(RuntimeException("API request failed (${response.statusCode()}): $errorBody"))
            return
        }

        val reader = BufferedReader(InputStreamReader(response.body(), Charsets.UTF_8))
        processResponsesApiStream(reader, onToken, onComplete, onError)
    }

    /**
     * 解析 Responses API 的 SSE 流
     * 事件格式：event: response.output_text.delta / data: {"type":"...","delta":"..."}
     */
    private suspend fun processResponsesApiStream(
        reader: BufferedReader,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val accumulated = StringBuilder()
        try {
            reader.use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    coroutineContext.ensureActive()
                    val currentLine = line ?: continue

                    if (currentLine.isBlank() || currentLine.startsWith("event:") || currentLine.startsWith(":")) continue

                    if (currentLine.startsWith("data:")) {
                        val data = currentLine.removePrefix("data:").trim()
                        if (data == "[DONE]") break

                        val token = parseResponsesChunk(data) ?: continue
                        if (token.isNotEmpty()) {
                            accumulated.append(token)
                            onToken(accumulated.toString())
                        }
                    }
                }
            }
            onComplete(accumulated.toString())
        } catch (e: Exception) {
            if (accumulated.isNotEmpty()) {
                onComplete(accumulated.toString())
            } else {
                onError(e)
            }
        }
    }

    /**
     * 解析 Responses API 单个 SSE 数据块
     * 仅处理 type=response.output_text.delta 的增量文本
     */
    private fun parseResponsesChunk(data: String): String? {
        return try {
            val json = Json.parseToJsonElement(data).jsonObject
            when (json["type"]?.jsonPrimitive?.contentOrNull) {
                "response.output_text.delta" -> json["delta"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
