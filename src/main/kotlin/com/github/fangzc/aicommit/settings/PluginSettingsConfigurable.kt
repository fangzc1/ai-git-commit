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

    // 各 Provider 的 API 地址和模型缓存，切换 Provider 时保存/恢复用户已填写的值
    private val providerApiUrlCache: MutableMap<AiProvider, String> =
        mutableMapOf(settings.stateData.provider to settings.stateData.apiBaseUrl)
    private val providerModelCache: MutableMap<AiProvider, String> =
        mutableMapOf(settings.stateData.provider to settings.stateData.model)

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

                                // 将当前 Provider 的 URL 和模型保存到缓存
                                providerApiUrlCache[selectedProvider] = apiUrlField.component.text
                                providerModelCache[selectedProvider] =
                                    modelCombo.component.selectedItem as? String ?: ""

                                selectedProvider = newProvider

                                // 优先从缓存恢复，缓存没有则使用 Provider 默认值
                                val restoredUrl = providerApiUrlCache[newProvider] ?: newProvider.defaultApiUrl
                                apiUrlField.component.text = restoredUrl
                                apiUrl = restoredUrl

                                val restoredModel = providerModelCache[newProvider]
                                updateConnectionStatus(ConnectionStatus.IDLE)
                                updateModelList(newProvider, restoredModel)
                                updateProviderHint()
                                updateOverviewSummary()
                            }
                        }
                }

                row {
                    providerHintLabel = label(
                        l(
                            "${providerHintText()} Changing provider updates the recommended endpoint and model list.",
                            "${providerHintText()} 切换提供商时会同步更新推荐的 API 地址和模型列表。"
                        )
                    ).component
                    providerHintLabel.foreground = UIUtil.getContextHelpForeground()
                }

                row(l("API URL:", "API 地址:")) {
                    apiUrlField = textField()
                        .columns(COLUMNS_LARGE)
                        .applyToComponent { text = apiUrl }
                        .onChanged {
                            apiUrl = it.text
                            updateConnectionStatus(ConnectionStatus.IDLE)
                        }
                }.comment(l("Use the provider default unless you are targeting a custom endpoint.", "除非使用自定义服务，否则建议保持默认地址。"))
                syncComboWidths()

                row(l("API Key:", "API 密钥:")) {
                    apiKeyField = cell(JBPasswordField())
                        .columns(COLUMNS_LARGE)
                        .applyToComponent { text = apiKey }
                    testButton = button(l("Test Connection", "测试连接")) {
                        testConnection()
                    }
                }.comment(l("The API key is stored securely via PasswordSafe.", "API 密钥会通过 PasswordSafe 安全存储。"))

                row(l("Model:", "模型:")) {
                    val models = selectedProvider.defaultModels.ifEmpty {
                        listOf(selectedModel.ifBlank { "gpt-4o-mini" })
                    }
                    modelCombo = comboBox(models)
                        .applyToComponent {
                            isEditable = true
                            selectedItem = selectedModel
                            addActionListener { updateOverviewSummary() }
                        }
                    label(l("You can keep the recommended model or enter one manually.", "可以使用推荐模型，也可以手动输入自定义模型。"))
                        .applyToComponent {
                            foreground = UIUtil.getContextHelpForeground()
                        }
                }
                syncComboWidths()
            }

            group(l("Prompt Strategy", "提示词策略")) {
                buttonsGroup {
                    for (name in DefaultPrompts.TEMPLATES.keys) {
                        val description = when (name) {
                            "Conventional Commits" -> l(
                                "(Default) <type>(<scope>): <subject> format",
                                "(默认) 生成 <type>(<scope>): <subject> 格式"
                            )
                            "Simple" -> l("One-line short summary", "一行简短描述")
                            "Detailed" -> l("Title line + detailed description", "标题行 + 详细说明")
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
                        label(l("Use the IDE global proxy settings.", "跟随 IDE 全局代理设置。")).applyToComponent {
                            foreground = UIUtil.getContextHelpForeground()
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
                        label(l("Provide a plugin-specific proxy configuration.", "为插件单独配置代理。")).applyToComponent {
                            foreground = UIUtil.getContextHelpForeground()
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
                        label(l("Connect directly without any proxy.", "直接连接，不走代理。")).applyToComponent {
                            foreground = UIUtil.getContextHelpForeground()
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
        // 重建面板以将重置的值同步回所有 UI 组件
        if (wrapper != null) rebuildPanel()
    }

    private fun updateModelList(provider: AiProvider, preferredModel: String? = null) {
        val combo = modelCombo.component
        combo.removeAllItems()
        val models = provider.defaultModels.ifEmpty { listOf("") }
        models.forEach { combo.addItem(it) }
        // 优先使用缓存/传入的首选模型，否则使用模型列表第一个
        val targetModel = preferredModel?.takeIf { it.isNotBlank() }
        if (targetModel != null) {
            combo.selectedItem = targetModel
        } else if (models.isNotEmpty()) {
            combo.selectedItem = models.first()
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

    private fun populateModelCombo(models: List<String>, currentModel: String) {
        val combo = modelCombo.component
        combo.removeAllItems()
        models.forEach { combo.addItem(it) }
        // 优先保留当前已输入的模型，否则选第一个
        if (currentModel.isNotBlank() && models.contains(currentModel)) {
            combo.selectedItem = currentModel
        } else if (models.isNotEmpty()) {
            combo.selectedIndex = 0
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
