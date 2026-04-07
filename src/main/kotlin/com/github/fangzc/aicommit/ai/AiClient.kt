package com.github.fangzc.aicommit.ai

/**
 * AI 客户端统一接口
 */
interface AiClient {

    /**
     * 流式生成 commit message
     * @param prompt 完整的 prompt 文本
     * @param onToken 每次累积文本的回调（传入的是到目前为止的完整累积文本）
     * @param onComplete 生成完成回调（传入完整文本）
     * @param onError 错误回调
     */
    suspend fun generateStreaming(
        prompt: String,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit
    )

    /**
     * 测试连接是否成功
     */
    suspend fun testConnection(): Boolean

    /**
     * 获取该 Provider 支持的模型列表
     * 默认返回空列表（由各子类按需覆盖）
     */
    suspend fun fetchModels(): List<String> = emptyList()
}
