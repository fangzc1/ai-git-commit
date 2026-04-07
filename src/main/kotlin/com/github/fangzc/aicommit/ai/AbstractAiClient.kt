package com.github.fangzc.aicommit.ai

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
 * AI 客户端基类：封装 HttpClient + SSE 流式解析通用逻辑
 */
abstract class AbstractAiClient(
    protected val apiKey: String,
    protected val baseUrl: String,
    protected val model: String,
    protected val httpClient: HttpClient,
    protected val temperature: Double = 0.7,
    protected val maxTokens: Int = 1024
) : AiClient {

    companion object {
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"

        /** 匹配末尾的版本号路径段，如 /v1、/v2、/v4 */
        private val VERSION_SUFFIX_REGEX = Regex(".*?/v\\d+$")
    }

    /**
     * 构建版本化 API URL：自动检测 base 末尾是否已含版本号
     * - 若已含版本号（如 /v4），直接追加 path（避免双重版本前缀，如 /v4/v1/...）
     * - 否则插入 /v1 前缀再追加 path
     */
    protected fun buildVersionedUrl(base: String, path: String): String {
        val normalizedBase = base.trimEnd('/')
        return if (normalizedBase.matches(VERSION_SUFFIX_REGEX)) {
            "$normalizedBase/$path"
        } else {
            "$normalizedBase/v1/$path"
        }
    }

    /** 构建请求体 JSON */
    abstract fun buildRequestBody(prompt: String, stream: Boolean): String

    /** 从 SSE data 行解析出 token 文本，返回 null 表示跳过该行 */
    abstract fun parseStreamChunk(data: String): String?

    /** 获取 API 端点 URL */
    abstract fun getEndpointUrl(): String

    /** 构建请求头 */
    abstract fun buildHeaders(): Map<String, String>

    /** 从非流式响应中解析完整文本 */
    abstract fun parseFullResponse(responseBody: String): String

    /** 判断 SSE 流是否结束 */
    open fun isStreamDone(data: String): Boolean = data.trim() == "[DONE]"

    /**
     * 获取模型列表端点所用的基础 URL（不含路径），子类可覆盖以规范化用户输入的 URL
     * 默认直接使用 baseUrl
     */
    open fun getModelsBaseUrl(): String = baseUrl.trimEnd('/')

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

                // 添加自定义请求头
                buildHeaders().forEach { (key, value) ->
                    requestBuilder.header(key, value)
                }

                val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())

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
     * 处理 SSE 事件流
     */
    protected suspend fun processSSEStream(
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
                    // 检查协程是否已取消
                    coroutineContext.ensureActive()

                    val currentLine = line ?: continue

                    // 跳过空行和注释
                    if (currentLine.isBlank() || currentLine.startsWith(":")) continue

                    // 处理 event 行（某些 Provider 如 Anthropic 使用 event 类型区分）
                    if (currentLine.startsWith("event:")) continue

                    // 处理 data 行
                    if (currentLine.startsWith("data:") || currentLine.startsWith("data: ")) {
                        val data = currentLine.removePrefix("data:").trim()

                        // 检查流是否结束
                        if (isStreamDone(data)) break

                        // 解析 token
                        val token = parseStreamChunk(data) ?: continue
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
                // 如果已有部分内容且是取消操作，仍然回调完成
                onComplete(accumulated.toString())
            } else {
                onError(e)
            }
        }
    }

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

            // 不捕获异常，让网络错误（如连接拒绝、超时）向上传播，以便调用方显示真实原因
            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                val errorSnippet = response.body().take(300)
                throw RuntimeException("HTTP ${response.statusCode()}: $errorSnippet")
            }
            true
        }
    }

    /**
     * 获取模型列表：OpenAI 兼容风格 GET /v1/models
     * Anthropic 同样使用此格式，Gemini 需要子类覆盖
     * 注意：HTTP 错误或网络异常会直接抛出，由调用方负责处理
     */
    override suspend fun fetchModels(): List<String> {
        return withContext(Dispatchers.IO) {
            val url = buildVersionedUrl(getModelsBaseUrl(), "models")
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()

            buildHeaders().forEach { (key, value) ->
                requestBuilder.header(key, value)
            }

            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                val errorSnippet = response.body().take(300)
                throw RuntimeException("HTTP ${response.statusCode()}: $errorSnippet")
            }
            val json = Json.parseToJsonElement(response.body()).jsonObject
            val data = json["data"]?.jsonArray ?: emptyList()
            data.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                .sorted()
        }
    }
}
