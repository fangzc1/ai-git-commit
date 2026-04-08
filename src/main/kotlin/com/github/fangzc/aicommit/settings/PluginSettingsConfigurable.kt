package com.github.fangzc.aicommit.settings

import com.github.fangzc.aicommit.ai.AiClientFactory
import com.github.fangzc.aicommit.ai.AiProvider
import com.github.fangzc.aicommit.prompt.DefaultPrompts
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.*
import java.awt.Color
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * Settings 页面：Settings > Tools > AI Commit Message（应用级，所有项目共享）
 */
class PluginSettingsConfigurable : Configurable {

    private val settings = PluginSettings.getInstance()

    // UI 组件引用（每次 buildSettingsPanel 后重新赋值）
    private lateinit var providerCombo: Cell<com.intellij.openapi.ui.ComboBox<String>>
    private lateinit var apiUrlField: Cell<com.intellij.ui.components.JBTextField>
    private lateinit var apiKeyField: Cell<JBPasswordField>
    private lateinit var modelCombo: Cell<com.intellij.openapi.ui.ComboBox<String>>
    private lateinit var customPromptArea: Cell<JBTextArea>
    private lateinit var testButton: Cell<javax.swing.JButton>
    private lateinit var proxyPasswordField: Cell<JBPasswordField>
    private lateinit var overviewProviderLabel: JLabel
    private lateinit var overviewModelLabel: JLabel
    private lateinit var overviewPromptLabel: JLabel
    private lateinit var overviewProxyLabel: JLabel
    private lateinit var connectionStatusLabel: JLabel
    private lateinit var providerHintLabel: JLabel

    // 表单临时值（bind* 绑定字段会在用户输入时自动同步到此变量）
    private var selectedProvider = settings.stateData.provider
    private var apiUrl = settings.stateData.apiBaseUrl
    private var apiKey = settings.apiKey
    private var selectedModel = settings.stateData.model
    private var promptTemplate = settings.stateData.promptTemplate
    private var useCustomPrompt = settings.stateData.useCustomPrompt
    private var customPrompt = settings.stateData.customPrompt
    private var proxyMode = settings.stateData.proxyMode
    private var proxyHost = settings.stateData.proxyHost
    private var proxyPort = settings.stateData.proxyPort
    private var proxyAuthEnabled = settings.stateData.proxyAuthEnabled
    private var proxyUser = settings.stateData.proxyUser
    private var maxDiffLength = settings.stateData.maxDiffLength
    private var temperature = settings.stateData.temperature
    private var maxTokens = settings.stateData.maxTokens
    private var locale = settings.stateData.locale
    private var currentUiLocale = settings.stateData.uiLocale

    // 模型搜索状态：全量列表、防递归标志、防选中后重触发标志、后台自动获取标志
    private var allModels: List<String> = emptyList()
    private var isFiltering = false
    private var justSelected = false
    private var isAutoFetching = false

    // 各 Provider 的 API 地址和模型缓存，切换 Provider 时保存/恢复用户已填写的值
    private val providerApiUrlCache: MutableMap<AiProvider, String> =
        mutableMapOf(settings.stateData.provider to settings.stateData.apiBaseUrl)
    private val providerModelCache: MutableMap<AiProvider, String> =
        mutableMapOf(settings.stateData.provider to settings.stateData.model)
    // 各 Provider 的 API Key 缓存，切换时保存/恢复，用于判断 key 是否变更
    private val providerApiKeyCache: MutableMap<AiProvider, String> =
        mutableMapOf(settings.stateData.provider to settings.apiKey)

    // 测试连接成功后的结果缓存：记录测试时使用的 apiUrl、apiKey 与获取到的模型列表
    private data class ProviderTestCache(val apiUrl: String, val apiKey: String, val models: List<String>)
    private val providerTestResultCache: MutableMap<AiProvider, ProviderTestCache> = mutableMapOf()

    // 外层包装面板，语言切换时重建内层设置面板
    private var wrapper: JPanel? = null

    // 持有当前 DialogPanel 引用，用于提交 buttonsGroup.bind 等绑定值
    private var dialogPanel: DialogPanel? = null

    // 自定义提示词输入行，用于控制显/隐
    private var customPromptRow: Row? = null

    // 代理详情行引用，用于在非 CUSTOM 模式下隐藏这些字段，防止用户误清除
    private var proxyHostRow: Row? = null
    private var proxyPortRow: Row? = null
    private var proxyAuthCheckRow: Row? = null
    private var proxyUsernameRow: Row? = null
    private var proxyPasswordRow: Row? = null
    private var connectionStatus = ConnectionStatus.IDLE
    private var lastConnectionError: String? = null
    private var lastFetchedModelCount: Int? = null

    private enum class ConnectionStatus {
        IDLE, TESTING, SUCCESS, ERROR
    }

    /** 根据 currentUiLocale 返回对应语言的 UI 字符串 */
    private fun l(en: String, zh: String): String = if (currentUiLocale == "zh") zh else en

    /** 是否为自定义（用户需自填 API 地址）的供应商 */
    private fun isCustomProvider(provider: AiProvider): Boolean =
        provider == AiProvider.CUSTOM || provider == AiProvider.CUSTOM_ANTHROPIC

    override fun getDisplayName(): String = "AI Commit Message"

    override fun createComponent(): JComponent {
        val outerWrapper = JPanel(BorderLayout())
        wrapper = outerWrapper
        outerWrapper.add(buildSettingsPanel(), BorderLayout.CENTER)
        return outerWrapper
    }

    /**
     * 构建设置面板（语言切换时会被重新调用）
     * 所有组件从当前本地变量初始化，bind* 字段会在用户输入时自动更新本地变量
     */
    private fun buildSettingsPanel(): DialogPanel {
        return panel {
            group(l("Current Configuration", "当前配置摘要")) {
                row(l("Provider:", "提供商:")) {
                    overviewProviderLabel = label(selectedProvider.displayName).component
                }
                row(l("Model:", "模型:")) {
                    overviewModelLabel = label(currentModelValue()).component
                }
                row(l("Prompt Strategy:", "提示词策略:")) {
                    overviewPromptLabel = label(currentPromptSummary()).component
                }
                row(l("Proxy Mode:", "代理模式:")) {
                    overviewProxyLabel = label(proxyModeSummary()).component
                }
                row(l("Connection:", "连接状态:")) {
                    connectionStatusLabel = label(connectionStatusText()).component
                }
            }

            group(l("General", "通用")) {
                row(l("Settings Language:", "设置页语言:")) {
                    comboBox(listOf("English", "中文"))
                        .applyToComponent {
                            selectedItem = if (currentUiLocale == "zh") "中文" else "English"
                            addActionListener {
                                val newLocale = if (selectedItem == "中文") "zh" else "en"
                                if (newLocale != currentUiLocale) {
                                    currentUiLocale = newLocale
                                    rebuildPanel()
                                }
                            }
                        }
                }.comment(l("Only affects the settings UI language.", "仅影响设置页面语言。"))

                row(l("Commit Message Language:", "提交消息语言:")) {
                    textField()
                        .applyToComponent { text = this@PluginSettingsConfigurable.locale }
                        .onChanged { locale = it.text }
                }.comment(l("For example: en, zh, ja.", "例如：en、zh、ja。"))
            }

            group(l("Basic Access", "基础接入")) {
                row(l("Provider:", "提供商:")) {
                    val providers = AiProvider.entries.map { it.displayName }.toTypedArray()
                    providerCombo = comboBox(providers.toList())
                        .applyToComponent {
                            selectedItem = selectedProvider.displayName
                            addActionListener {
                                val newProvider = AiProvider.fromDisplayName(selectedItem as String)
                                if (newProvider == selectedProvider) return@addActionListener

                                // 将当前 Provider 的 URL、API Key 和模型保存到缓存
                                providerApiUrlCache[selectedProvider] = apiUrlField.component.text
                                providerApiKeyCache[selectedProvider] = String(apiKeyField.component.password)
                                providerModelCache[selectedProvider] =
                                    modelCombo.component.selectedItem as? String ?: ""

                                selectedProvider = newProvider

                                // 优先从缓存恢复 URL，缓存没有则使用 Provider 默认值
                                val restoredUrl = providerApiUrlCache[newProvider] ?: newProvider.defaultApiUrl
                                apiUrlField.component.text = restoredUrl
                                apiUrl = restoredUrl
                                // 非自定义供应商不允许修改 API 地址
                                apiUrlField.component.isEditable = isCustomProvider(newProvider)

                                // 恢复 API Key
                                val restoredKey = providerApiKeyCache[newProvider] ?: ""
                                apiKeyField.component.text = restoredKey

                                // 检查测试结果缓存：若 URL 和 Key 与上次测试一致则直接恢复模型列表
                                val testCache = providerTestResultCache[newProvider]
                                val restoredModel = providerModelCache[newProvider]
                                if (testCache != null
                                    && testCache.apiUrl == restoredUrl
                                    && testCache.apiKey == restoredKey
                                ) {
                                    populateModelCombo(testCache.models, restoredModel ?: testCache.models.firstOrNull() ?: "")
                                    updateConnectionStatus(
                                        ConnectionStatus.SUCCESS,
                                        null,
                                        testCache.models.size
                                    )
                                } else {
                                    updateConnectionStatus(ConnectionStatus.IDLE)
                                    updateModelList(newProvider, restoredModel)
                                }

                                updateProviderHint()
                                updateOverviewSummary()
                            }
                        }
                }

                row(l("API URL:", "API 地址:")) {
                    apiUrlField = textField()
                        .columns(COLUMNS_LARGE)
                        .applyToComponent {
                            text = apiUrl
                            // 非自定义供应商的 API 地址不允许修改
                            isEditable = isCustomProvider(selectedProvider)
                        }
                        .onChanged {
                            apiUrl = it.text
                            updateConnectionStatus(ConnectionStatus.IDLE)
                        }
                }
                syncComboWidths()

                row(l("API Key:", "API 密钥:")) {
                    apiKeyField = cell(JBPasswordField())
                        .columns(COLUMNS_LARGE)
                        .applyToComponent { text = apiKey }
                    testButton = button(l("Test Connection", "测试连接")) {
                        testConnection()
                    }
                }

                row(l("Model:", "模型:")) {
                    val models = selectedProvider.defaultModels.ifEmpty {
                        listOf(selectedModel.ifBlank { "gpt-4o-mini" })
                    }
                    allModels = models
                    modelCombo = comboBox(models)
                        .applyToComponent {
                            isEditable = true
                            selectedItem = selectedModel
                            addActionListener {
                                if (!isFiltering) justSelected = true
                                updateOverviewSummary()
                            }
                            attachModelSearchFilter()
                        }
                }
                syncComboWidths()
            }

            group(l("Prompt Strategy", "提示词策略")) {
                buttonsGroup {
                    for (name in DefaultPrompts.TEMPLATES.keys) {
                        val description = when (name) {
                            "Conventional Commits" -> l(
                                "(Default) <type>(<scope>): <subject> + body",
                                "(默认) <type>(<scope>): <subject> + body 改动说明"
                            )
                            "Simple" -> l(
                                "Single-line <type>(<scope>): <subject> only",
                                "仅生成一行 <type>(<scope>): <subject>"
                            )
                            "Detailed" -> l(
                                "<type>: <subject> + body + footer (with Breaking Change & Issue refs)",
                                "<type>: <subject> + body + footer（含 Breaking Change 和 Issue 关联）"
                            )
                            else -> ""
                        }
                        row {
                            radioButton(name, name)
                                .applyToComponent {
                                    addActionListener {
                                        useCustomPrompt = false
                                        promptTemplate = name
                                        customPromptRow?.visible(false)
                                        updateOverviewSummary()
                                        wrapper?.revalidate()
                                        wrapper?.repaint()
                                    }
                                }
                            label(description).applyToComponent {
                                foreground = UIUtil.getContextHelpForeground()
                            }
                        }
                    }
                    row {
                        radioButton("Custom", "Custom")
                            .applyToComponent {
                                addActionListener {
                                    useCustomPrompt = true
                                    customPromptRow?.visible(true)
                                    updateOverviewSummary()
                                    wrapper?.revalidate()
                                    wrapper?.repaint()
                                }
                            }
                        label(l("Write your own prompt template", "编写自定义提示词模板"))
                            .applyToComponent {
                                foreground = UIUtil.getContextHelpForeground()
                            }
                    }
                }.bind(
                    { if (useCustomPrompt) "Custom" else promptTemplate },
                    {
                        if (it == "Custom") {
                            useCustomPrompt = true
                        } else {
                            useCustomPrompt = false
                            promptTemplate = it
                        }
                    }
                )

                val promptRow = row {
                    customPromptArea = cell(JBTextArea(15, 60))
                        .align(Align.FILL)
                        .applyToComponent {
                            text = customPrompt
                            lineWrap = true
                            wrapStyleWord = true
                        }
                        .comment(
                            l(
                                "Use {diff} as placeholder for diff content, {branch} for current branch name, {locale} for language.",
                                "使用 {diff} 作为差异内容占位符，{branch} 为当前分支名，{locale} 为语言。"
                            )
                        )
                }
                promptRow.visible(useCustomPrompt)
                customPromptRow = promptRow
                promptRow.resizableRow()
            }

            collapsibleGroup(l("Network & Proxy", "网络与代理")) {
                buttonsGroup {
                    row {
                        radioButton(l("Use IDE Proxy", "使用 IDE 代理"), PluginSettings.ProxyMode.IDE)
                            .applyToComponent {
                                addActionListener {
                                    proxyMode = PluginSettings.ProxyMode.IDE
                                    setProxyFieldsVisible(false)
                                    updateOverviewSummary()
                                }
                            }
                    }
                    row {
                        radioButton(l("Custom Proxy", "自定义代理"), PluginSettings.ProxyMode.CUSTOM)
                            .applyToComponent {
                                addActionListener {
                                    proxyMode = PluginSettings.ProxyMode.CUSTOM
                                    setProxyFieldsVisible(true)
                                    updateOverviewSummary()
                                }
                            }
                    }
                    row {
                        radioButton(l("No Proxy", "不使用代理"), PluginSettings.ProxyMode.NONE)
                            .applyToComponent {
                                addActionListener {
                                    proxyMode = PluginSettings.ProxyMode.NONE
                                    setProxyFieldsVisible(false)
                                    updateOverviewSummary()
                                }
                            }
                    }
                }.bind(::proxyMode)

                indent {
                    proxyHostRow = row(l("Host:", "主机:")) {
                        textField()
                            .columns(COLUMNS_MEDIUM)
                            .applyToComponent { text = proxyHost }
                            .onChanged { proxyHost = it.text }
                    }
                    proxyPortRow = row(l("Port:", "端口:")) {
                        intTextField(1..65535)
                            .applyToComponent { text = proxyPort.toString() }
                            .onChanged { proxyPort = it.text.toIntOrNull()?.coerceIn(1..65535) ?: proxyPort }
                    }
                    proxyAuthCheckRow = row {
                        checkBox(l("Proxy Authentication", "代理身份验证"))
                            .applyToComponent {
                                isSelected = proxyAuthEnabled
                                addItemListener {
                                    proxyAuthEnabled = isSelected
                                    setProxyAuthFieldsVisible(proxyMode == PluginSettings.ProxyMode.CUSTOM && proxyAuthEnabled)
                                }
                            }
                    }
                    indent {
                        proxyUsernameRow = row(l("Username:", "用户名:")) {
                            textField()
                                .columns(COLUMNS_MEDIUM)
                                .applyToComponent { text = proxyUser }
                                .onChanged { proxyUser = it.text }
                        }
                        proxyPasswordRow = row(l("Password:", "密码:")) {
                            proxyPasswordField = cell(JBPasswordField())
                                .columns(COLUMNS_MEDIUM)
                                .applyToComponent { text = settings.proxyPassword }
                        }
                    }
                }
                setProxyFieldsVisible(proxyMode == PluginSettings.ProxyMode.CUSTOM)
            }

            collapsibleGroup(l("Advanced Settings", "高级设置")) {
                row(l("Max Diff Length:", "最大差异长度:")) {
                    intTextField(100..100000)
                        .applyToComponent { text = maxDiffLength.toString() }
                        .onChanged { maxDiffLength = it.text.toIntOrNull()?.coerceIn(100..100000) ?: maxDiffLength }
                }.comment(l("Maximum characters of diff to send to AI.", "发送给 AI 的 diff 最大字符数。"))
                row(l("Temperature:", "温度:")) {
                    textField()
                        .applyToComponent { text = temperature.toString() }
                        .onChanged { temperature = it.text.toDoubleOrNull() ?: temperature }
                }.comment(l("0.0 - 2.0. Lower values are more deterministic.", "范围 0.0 - 2.0，越低越稳定。"))
                row(l("Max Tokens:", "最大 Token 数:")) {
                    intTextField(100..8192)
                        .applyToComponent { text = maxTokens.toString() }
                        .onChanged { maxTokens = it.text.toIntOrNull()?.coerceIn(100..8192) ?: maxTokens }
                }
            }
        }.also { dialogPanel = it }
    }

    /**
     * 语言切换后重建设置面板，所有表单值从当前本地变量恢复
     */
    private fun rebuildPanel() {
        val w = wrapper ?: return
        w.removeAll()
        w.add(buildSettingsPanel(), BorderLayout.CENTER)
        w.revalidate()
        w.repaint()
    }

    /** 根据是否为 CUSTOM 模式显示或隐藏代理详情字段 */
    private fun setProxyFieldsVisible(visible: Boolean) {
        listOf(proxyHostRow, proxyPortRow, proxyAuthCheckRow).forEach { it?.visible(visible) }
        setProxyAuthFieldsVisible(visible && proxyAuthEnabled)
        updateOverviewSummary()
        wrapper?.revalidate()
        wrapper?.repaint()
    }

    private fun setProxyAuthFieldsVisible(visible: Boolean) {
        listOf(proxyUsernameRow, proxyPasswordRow).forEach { it?.visible(visible) }
        wrapper?.revalidate()
        wrapper?.repaint()
    }

    override fun isModified(): Boolean {
        val currentApiKey = String(apiKeyField.component.password)
        val currentModel = modelCombo.component.selectedItem as? String ?: ""
        val currentCustomPrompt = customPromptArea.component.text
        val currentProxyPassword = String(proxyPasswordField.component.password)

        return selectedProvider != settings.stateData.provider
                || apiUrlField.component.text != settings.stateData.apiBaseUrl
                || currentApiKey != settings.apiKey
                || currentModel != settings.stateData.model
                || promptTemplate != settings.stateData.promptTemplate
                || useCustomPrompt != settings.stateData.useCustomPrompt
                || currentCustomPrompt != settings.stateData.customPrompt
                || proxyMode != settings.stateData.proxyMode
                || proxyHost != settings.stateData.proxyHost
                || proxyPort != settings.stateData.proxyPort
                || proxyAuthEnabled != settings.stateData.proxyAuthEnabled
                || proxyUser != settings.stateData.proxyUser
                || currentProxyPassword != settings.proxyPassword
                || maxDiffLength != settings.stateData.maxDiffLength
                || temperature != settings.stateData.temperature
                || maxTokens != settings.stateData.maxTokens
                || locale != settings.stateData.locale
                || currentUiLocale != settings.stateData.uiLocale
                // 兜底：通过 DialogPanel 检测 buttonsGroup.bind 等绑定字段的变化
                || dialogPanel?.isModified() == true
    }

    override fun apply() {
        // 先提交 DialogPanel 内部的绑定值（buttonsGroup.bind 等）到本地变量
        dialogPanel?.apply()
        settings.stateData = PluginSettings.State(
            provider = selectedProvider,
            apiBaseUrl = apiUrlField.component.text,
            model = modelCombo.component.selectedItem as? String ?: "",
            promptTemplate = promptTemplate,
            useCustomPrompt = useCustomPrompt,
            customPrompt = customPromptArea.component.text,
            proxyMode = proxyMode,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyAuthEnabled = proxyAuthEnabled,
            proxyUser = proxyUser,
            maxDiffLength = maxDiffLength,
            temperature = temperature,
            maxTokens = maxTokens,
            locale = locale,
            uiLocale = currentUiLocale
        )
        settings.apiKey = String(apiKeyField.component.password)
        settings.proxyPassword = String(proxyPasswordField.component.password)
    }

    override fun reset() {
        selectedProvider = settings.stateData.provider
        apiUrl = settings.stateData.apiBaseUrl
        apiKey = settings.apiKey
        selectedModel = settings.stateData.model
        promptTemplate = settings.stateData.promptTemplate
        useCustomPrompt = settings.stateData.useCustomPrompt
        customPrompt = settings.stateData.customPrompt
        proxyMode = settings.stateData.proxyMode
        proxyHost = settings.stateData.proxyHost
        proxyPort = settings.stateData.proxyPort
        proxyAuthEnabled = settings.stateData.proxyAuthEnabled
        proxyUser = settings.stateData.proxyUser
        maxDiffLength = settings.stateData.maxDiffLength
        temperature = settings.stateData.temperature
        maxTokens = settings.stateData.maxTokens
        locale = settings.stateData.locale
        currentUiLocale = settings.stateData.uiLocale
        connectionStatus = ConnectionStatus.IDLE
        lastConnectionError = null
        lastFetchedModelCount = null
        // 重置 per-provider 缓存，仅保留已保存的 Provider 数据
        providerApiUrlCache.clear()
        providerApiUrlCache[settings.stateData.provider] = settings.stateData.apiBaseUrl
        providerModelCache.clear()
        providerModelCache[settings.stateData.provider] = settings.stateData.model
        providerApiKeyCache.clear()
        providerApiKeyCache[settings.stateData.provider] = settings.apiKey
        providerTestResultCache.clear()
        // 重建面板以将重置的值同步回所有 UI 组件
        if (wrapper != null) rebuildPanel()
    }

    private fun updateModelList(provider: AiProvider, preferredModel: String? = null) {
        val combo = modelCombo.component
        val models = provider.defaultModels.ifEmpty { listOf("") }
        allModels = models
        isFiltering = true
        try {
            combo.removeAllItems()
            models.forEach { combo.addItem(it) }
            // 优先使用缓存/传入的首选模型，否则使用模型列表第一个
            val targetModel = preferredModel?.takeIf { it.isNotBlank() }
            if (targetModel != null) {
                combo.selectedItem = targetModel
            } else if (models.isNotEmpty()) {
                combo.selectedItem = models.first()
            }
        } finally {
            isFiltering = false
        }
        selectedModel = combo.selectedItem as? String ?: selectedModel
        syncComboWidths()
        updateOverviewSummary()
    }

    private fun testConnection() {
        val currentKey = String(apiKeyField.component.password)
        val currentUrl = apiUrlField.component.text
        val currentModel = modelCombo.component.selectedItem as? String ?: ""

        if (currentKey.isBlank()) {
            updateConnectionStatus(
                ConnectionStatus.ERROR,
                l("Please enter an API key first.", "请先输入 API Key。")
            )
            return
        }

        testButton.component.isEnabled = false
        testButton.component.text = l("Testing...", "测试中...")
        updateConnectionStatus(ConnectionStatus.TESTING)

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var errorMsg: String? = null
            var fetchedModels: List<String> = emptyList()

            try {
                val client = AiClientFactory.create(
                    provider = selectedProvider,
                    apiKey = currentKey,
                    apiBaseUrl = currentUrl,
                    model = currentModel
                )
                // 直接通过获取模型列表来验证连接是否正常
                fetchedModels = client.fetchModels()
            } catch (e: Exception) {
                errorMsg = e.message
            }

            val successFinal = errorMsg == null
            val errorMsgFinal = errorMsg ?: l(
                "Please check your API key and settings.",
                "请检查 API Key 和相关配置。"
            )
            val fetchedModelsFinal = fetchedModels

            SwingUtilities.invokeLater {
                testButton.component.isEnabled = true
                testButton.component.text = l("Test Connection", "测试连接")

                if (successFinal) {
                    if (fetchedModelsFinal.isNotEmpty()) {
                        populateModelCombo(fetchedModelsFinal, currentModel)
                        // 缓存本次测试结果，供切换 Provider 后回来时免重测
                        providerTestResultCache[selectedProvider] =
                            ProviderTestCache(currentUrl, currentKey, fetchedModelsFinal)
                    }
                    updateConnectionStatus(
                        ConnectionStatus.SUCCESS,
                        if (fetchedModelsFinal.isNotEmpty()) {
                            l(
                                "Connection successful. Fetched ${fetchedModelsFinal.size} models.",
                                "连接成功，已获取 ${fetchedModelsFinal.size} 个模型。"
                            )
                        } else {
                            l("Connection successful.", "连接成功。")
                        },
                        fetchedModelsFinal.size
                    )
                } else {
                    updateConnectionStatus(
                        ConnectionStatus.ERROR,
                        l(
                            "Connection failed: $errorMsgFinal",
                            "连接失败：$errorMsgFinal"
                        )
                    )
                }
            }
        }
    }

    /**
     * 后台自动获取模型列表，完成后展开模型下拉框。
     * 供用户首次点击下拉框（无缓存）时自动触发，替代手动点击"测试连接"。
     */
    private fun autoFetchModelsAndShowPopup(apiKey: String, apiUrl: String) {
        if (isAutoFetching) return
        isAutoFetching = true
        // 在 EDT 上提前捕获 Swing 组件的值，避免在 IO 线程中访问 UI
        val currentModel = modelCombo.component.selectedItem as? String ?: selectedModel

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var fetchedModels: List<String> = emptyList()
            try {
                val client = AiClientFactory.create(
                    provider = selectedProvider,
                    apiKey = apiKey,
                    apiBaseUrl = apiUrl,
                    model = currentModel
                )
                fetchedModels = client.fetchModels()
            } catch (_: Exception) {
                // 静默失败，保持现有列表，不弹错误
            }

            val fetchedModelsFinal = fetchedModels
            SwingUtilities.invokeLater {
                isAutoFetching = false
                if (fetchedModelsFinal.isNotEmpty()) {
                    // 缓存本次结果，供后续切换 Provider 时恢复
                    providerTestResultCache[selectedProvider] =
                        ProviderTestCache(apiUrl, apiKey, fetchedModelsFinal)
                    val latestModel = modelCombo.component.selectedItem as? String ?: currentModel
                    populateModelCombo(fetchedModelsFinal, latestModel)
                    updateConnectionStatus(ConnectionStatus.SUCCESS, null, fetchedModelsFinal.size)
                }
                // 无论成功与否，获取完成后自动展开下拉框
                modelCombo.component.showPopup()
            }
        }
    }

    private fun populateModelCombo(models: List<String>, currentModel: String) {
        val combo = modelCombo.component
        allModels = models
        isFiltering = true
        try {
            combo.removeAllItems()
            models.forEach { combo.addItem(it) }
            // 优先保留当前已输入的模型，否则选第一个
            if (currentModel.isNotBlank() && models.contains(currentModel)) {
                combo.selectedItem = currentModel
            } else if (models.isNotEmpty()) {
                combo.selectedIndex = 0
            }
        } finally {
            isFiltering = false
        }
        selectedModel = combo.selectedItem as? String ?: currentModel
        updateOverviewSummary()
    }

    private fun updateOverviewSummary() {
        if (::overviewProviderLabel.isInitialized) {
            overviewProviderLabel.text = selectedProvider.displayName
        }
        if (::overviewModelLabel.isInitialized) {
            overviewModelLabel.text = currentModelValue()
        }
        if (::overviewPromptLabel.isInitialized) {
            overviewPromptLabel.text = currentPromptSummary()
        }
        if (::overviewProxyLabel.isInitialized) {
            overviewProxyLabel.text = proxyModeSummary()
        }
        if (::connectionStatusLabel.isInitialized) {
            connectionStatusLabel.text = connectionStatusText()
            connectionStatusLabel.foreground = when (connectionStatus) {
                ConnectionStatus.SUCCESS -> JBColor(Color(0x2E7D32), Color(0x6FBF73))
                ConnectionStatus.ERROR -> UIUtil.getErrorForeground()
                ConnectionStatus.TESTING -> UIUtil.getLabelForeground()
                ConnectionStatus.IDLE -> UIUtil.getContextHelpForeground()
            }
        }
    }

    private fun updateProviderHint() {
        if (::providerHintLabel.isInitialized) {
            providerHintLabel.text = providerHintText()
        }
    }

    private fun updateConnectionStatus(
        status: ConnectionStatus,
        message: String? = null,
        fetchedModelCount: Int? = null
    ) {
        connectionStatus = status
        lastConnectionError = if (status == ConnectionStatus.ERROR) message else null
        lastFetchedModelCount = if (status == ConnectionStatus.SUCCESS) fetchedModelCount else lastFetchedModelCount

        if (status == ConnectionStatus.IDLE) {
            lastConnectionError = null
        }

        if (status == ConnectionStatus.SUCCESS && fetchedModelCount != null) {
            lastFetchedModelCount = fetchedModelCount
        }
        updateOverviewSummary()
    }

    private fun providerHintText(): String = when (selectedProvider) {
        AiProvider.OPENAI -> l(
            "Recommended for the default setup. The endpoint and default models are prefilled.",
            "默认推荐配置，API 地址和模型建议会自动填充。"
        )
        AiProvider.ANTHROPIC -> l(
            "Useful when you prefer Claude models for longer change summaries.",
            "适合偏好 Claude 模型、需要更强长文本总结能力的场景。"
        )
        AiProvider.GEMINI -> l(
            "A good option when you want a fast model with a separate endpoint.",
            "适合希望使用独立端点和较快模型的场景。"
        )
        AiProvider.OPENROUTER -> l(
            "A model aggregator. The endpoint is prefilled. Fetch models via Test Connection.",
            "模型聚合平台，API 地址已预填，点击「测试连接」可自动获取模型列表。"
        )
        AiProvider.CUSTOM -> l(
            "For OpenAI-compatible services. You should provide your own endpoint and model.",
            "用于 OpenAI 兼容服务，需要自行填写 API 地址和模型。"
        )
        AiProvider.CUSTOM_ANTHROPIC -> l(
            "For Anthropic-compatible services (e.g. self-hosted Claude proxy). Provide your own endpoint and model.",
            "适用于 Anthropic 协议兼容服务（如自建 Claude 代理），需填写自定义 API 地址和模型。"
        )
    }

    private fun connectionStatusText(): String = when (connectionStatus) {
        ConnectionStatus.IDLE -> l("Not tested in this session.", "当前会话尚未测试。")
        ConnectionStatus.TESTING -> l("Testing connection...", "正在测试连接...")
        ConnectionStatus.SUCCESS -> when {
            lastFetchedModelCount != null && lastFetchedModelCount!! > 0 ->
                l(
                    "Connection verified. $lastFetchedModelCount models fetched.",
                    "连接已验证，获取到 $lastFetchedModelCount 个模型。"
                )
            else -> l("Connection verified.", "连接已验证。")
        }
        ConnectionStatus.ERROR -> lastConnectionError
            ?: l("Connection test failed.", "连接测试失败。")
    }

    private fun currentModelValue(): String {
        if (::modelCombo.isInitialized) {
            return modelCombo.component.selectedItem as? String ?: selectedModel.ifBlank { l("Not set", "未设置") }
        }
        return selectedModel.ifBlank { l("Not set", "未设置") }
    }

    private fun currentPromptSummary(): String =
        if (useCustomPrompt) l("Custom Prompt", "自定义提示词") else promptTemplate

    private fun proxyModeSummary(): String = when (proxyMode) {
        PluginSettings.ProxyMode.IDE -> l("IDE Proxy", "IDE 代理")
        PluginSettings.ProxyMode.CUSTOM -> l("Custom Proxy", "自定义代理")
        PluginSettings.ProxyMode.NONE -> l("No Proxy", "不使用代理")
    }

    /**
     * 为模型 ComboBox 附加 filter-as-you-type 搜索能力。
     * 仅在 popup **已展开**时执行过滤，popup 关闭时不干扰用户自由输入（支持自定义模型）。
     * popup 关闭时自动恢复全量列表，保证下次展开能看到所有模型。
     */
    private fun com.intellij.openapi.ui.ComboBox<String>.attachModelSearchFilter() {
        val textComponent = (editor?.editorComponent as? javax.swing.text.JTextComponent) ?: return
        var pendingUpdate = false

        // 仅在 popup 已展开时执行过滤，不干扰关闭状态下的自由输入
        textComponent.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = scheduleFilter()
            override fun removeUpdate(e: DocumentEvent) = scheduleFilter()
            override fun changedUpdate(e: DocumentEvent) {}

            private fun scheduleFilter() {
                if (isFiltering || pendingUpdate || !isPopupVisible) return
                pendingUpdate = true
                SwingUtilities.invokeLater {
                    pendingUpdate = false
                    if (isFiltering || justSelected || !isPopupVisible) {
                        justSelected = false
                        return@invokeLater
                    }
                    val keyword = textComponent.text
                    val filtered = if (keyword.isBlank()) allModels
                    else allModels.filter { it.contains(keyword, ignoreCase = true) }

                    // 更新下拉列表项（isFiltering 阻断 removeAllItems/addItem 触发的递归事件）
                    isFiltering = true
                    try {
                        removeAllItems()
                        filtered.forEach { addItem(it) }
                        textComponent.text = keyword
                        textComponent.caretPosition = keyword.length
                    } finally {
                        isFiltering = false
                    }

                    if (filtered.isEmpty()) hidePopup()
                }
            }
        })

        // popup 关闭时恢复全量列表，保证下次展开时显示所有模型
        addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
                if (isAutoFetching) {
                    // 正在后台获取中，阻止展开，等待获取完成后自动展开
                    SwingUtilities.invokeLater { hidePopup() }
                    return
                }
                val currentKey = String(apiKeyField.component.password)
                val currentUrl = apiUrlField.component.text
                // 检查是否有与当前 URL + Key 匹配的模型缓存
                val testCache = providerTestResultCache[selectedProvider]
                val hasCachedModels = testCache != null
                        && testCache.apiUrl == currentUrl
                        && testCache.apiKey == currentKey
                if (!hasCachedModels && currentKey.isNotBlank()) {
                    // 无缓存：阻止本次展开，后台自动获取，完成后再展开
                    SwingUtilities.invokeLater { hidePopup() }
                    autoFetchModelsAndShowPopup(currentKey, currentUrl)
                }
                // 有缓存或无 Key：默认正常展开，无需干预
            }
            override fun popupMenuCanceled(e: PopupMenuEvent) {}
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {
                if (isFiltering) return
                // 先保存当前 selectedItem（用户点击后已更新为目标模型）
                // 必须在 removeAllItems() 之前保存，否则 removeAllItems() 会将 selectedItem 置为 null
                val selectedValue = selectedItem as? String
                isFiltering = true
                try {
                    removeAllItems()
                    allModels.forEach { addItem(it) }
                    // 明确恢复 selectedItem，防止 removeAllItems() 导致选中项丢失
                    val valueToRestore = selectedValue ?: ""
                    selectedItem = valueToRestore
                    textComponent.text = valueToRestore
                    textComponent.caretPosition = valueToRestore.length
                } finally {
                    isFiltering = false
                }
            }
        })
    }

    private fun syncComboWidths() {
        if (!::apiUrlField.isInitialized) return

        val targetWidth = apiUrlField.component.preferredSize.width

        if (::providerCombo.isInitialized) {
            val size = Dimension(targetWidth, providerCombo.component.preferredSize.height)
            providerCombo.component.preferredSize = size
            providerCombo.component.minimumSize = size
            providerCombo.component.maximumSize = size
        }

        if (::modelCombo.isInitialized) {
            val size = Dimension(targetWidth, modelCombo.component.preferredSize.height)
            modelCombo.component.preferredSize = size
            modelCombo.component.minimumSize = size
            modelCombo.component.maximumSize = size
        }
    }
}
