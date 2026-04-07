@file:Suppress("DEPRECATION")

package com.github.fangzc.aicommit.util

import com.github.fangzc.aicommit.settings.PluginSettings
import com.intellij.openapi.project.Project
import com.intellij.util.net.HttpConfigurable
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
     */
    @Suppress("DEPRECATION")
    private fun configureIdeProxy(builder: HttpClient.Builder) {
        val httpConfigurable = HttpConfigurable.getInstance()
        if (httpConfigurable.USE_HTTP_PROXY && httpConfigurable.PROXY_HOST.isNotBlank()) {
            val address = InetSocketAddress(httpConfigurable.PROXY_HOST, httpConfigurable.PROXY_PORT)
            builder.proxy(ProxySelector.of(address))

            if (httpConfigurable.PROXY_AUTHENTICATION) {
                val login = httpConfigurable.proxyLogin ?: ""
                val password = httpConfigurable.plainProxyPassword ?: ""
                builder.authenticator(createAuthenticator(login, password))
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
