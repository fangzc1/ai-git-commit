package com.github.fangzc.aicommit.util

import com.github.fangzc.aicommit.settings.PluginSettings
import com.intellij.openapi.project.Project
import com.intellij.util.net.IdeProxySelector
import com.intellij.util.net.ProxyConfiguration
import com.intellij.util.net.ProxyCredentialStore
import com.intellij.util.net.ProxySettings
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.ProxySelector
import java.net.http.HttpClient
import java.time.Duration

/**
 * 代理配置工具：根据插件设置构建 HttpClient
 */
object ProxyConfigUtil {

    /**
     * 根据插件配置构建带代理的 HttpClient
     */
    fun buildHttpClient(project: Project): HttpClient {
        val settings = PluginSettings.getInstance(project)
        val state = settings.state
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .version(HttpClient.Version.HTTP_1_1)

        when (state.proxyMode) {
            PluginSettings.ProxyMode.IDE -> configureIdeProxy(builder)
            PluginSettings.ProxyMode.CUSTOM -> configureCustomProxy(builder, settings)
            // 明确禁用代理，使用直连
            PluginSettings.ProxyMode.NONE -> builder.proxy(ProxySelector.of(null))
        }

        return builder.build()
    }

    /**
     * 使用 IDE 全局代理配置
     * 使用 IdeProxySelector + ProxySettings + ProxyCredentialStore
     * 替代已废弃的 HttpConfigurable 字段和方法
     */
    private fun configureIdeProxy(builder: HttpClient.Builder) {
        // 使用 IdeProxySelector 自动读取 IDE 代理设置，支持静态、自动检测和 PAC 代理
        builder.proxy(IdeProxySelector { ProxySettings.getInstance().getProxyConfiguration() })

        // 静态代理时，从 ProxyCredentialStore 获取认证凭据
        val config = ProxySettings.getInstance().getProxyConfiguration()
        if (config is ProxyConfiguration.StaticProxyConfiguration) {
            val credentials = ProxyCredentialStore.getInstance().getCredentials(config.host, config.port)
            if (credentials != null) {
                val login = credentials.userName ?: ""
                val password = credentials.getPasswordAsString() ?: ""
                if (login.isNotBlank()) {
                    builder.authenticator(createAuthenticator(login, password))
                }
            }
        }
    }

    /**
     * 使用插件自定义代理配置
     */
    private fun configureCustomProxy(builder: HttpClient.Builder, settings: PluginSettings) {
        val state = settings.state
        if (state.proxyHost.isNotBlank() && state.proxyPort > 0) {
            val address = InetSocketAddress(state.proxyHost, state.proxyPort)
            builder.proxy(ProxySelector.of(address))

            if (state.proxyAuthEnabled && state.proxyUser.isNotBlank()) {
                builder.authenticator(createAuthenticator(state.proxyUser, settings.proxyPassword))
            }
        }
    }

    private fun createAuthenticator(username: String, password: String): Authenticator {
        return object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(username, password.toCharArray())
            }
        }
    }
}
