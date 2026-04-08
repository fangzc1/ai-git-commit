package com.github.fangzc.aicommit.settings

import com.github.fangzc.aicommit.ai.AiProvider
import com.github.fangzc.aicommit.prompt.DefaultPrompts
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*

/**
 * 插件配置持久化服务（应用级别，所有项目共享）
 */
@Service(Service.Level.APP)
@State(
    name = "AiCommitSettings",
    storages = [Storage("aiCommitSettings.xml")]
)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    data class State(
        // AI Provider 配置
        var provider: AiProvider = AiProvider.OPENAI,
        var apiBaseUrl: String = AiProvider.OPENAI.defaultApiUrl,
        var model: String = "gpt-4o-mini",

        // 提示词配置
        var promptTemplate: String = DefaultPrompts.DEFAULT_TEMPLATE_NAME,
        var useCustomPrompt: Boolean = false,
        var customPrompt: String = "",

        // 代理配置
        var proxyMode: ProxyMode = ProxyMode.IDE,
        var proxyHost: String = "",
        var proxyPort: Int = 0,
        var proxyAuthEnabled: Boolean = false,
        var proxyUser: String = "",

        // 高级配置
        var maxDiffLength: Int = 10000,
        var temperature: Double = 0.7,
        var maxTokens: Int = 1024,
        var locale: String = "en",

        // 界面语言（"en" 或 "zh"）
        var uiLocale: String = "en"
    )

    enum class ProxyMode {
        IDE, CUSTOM, NONE
    }

    var stateData = State()

    override fun getState(): State = stateData

    override fun loadState(state: State) {
        stateData = state
    }

    // API Key 使用 PasswordSafe 安全存储
    var apiKey: String
        get() {
            val attributes = createCredentialAttributes("apiKey")
            return PasswordSafe.instance.getPassword(attributes) ?: ""
        }
        set(value) {
            val attributes = createCredentialAttributes("apiKey")
            PasswordSafe.instance.setPassword(attributes, value)
        }

    // 代理密码使用 PasswordSafe 安全存储
    var proxyPassword: String
        get() {
            val attributes = createCredentialAttributes("proxyPassword")
            return PasswordSafe.instance.getPassword(attributes) ?: ""
        }
        set(value) {
            val attributes = createCredentialAttributes("proxyPassword")
            PasswordSafe.instance.setPassword(attributes, value)
        }

    private fun createCredentialAttributes(key: String): CredentialAttributes {
        return CredentialAttributes(
            generateServiceName("AiCommitPlugin", key)
        )
    }

    companion object {
        fun getInstance(): PluginSettings =
            ApplicationManager.getApplication().getService(PluginSettings::class.java)
    }
}
