# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

JetBrains IntelliJ 插件，在 Git Commit 对话框的工具栏中提供 AI 生成 commit message 的闪电按钮。基于 IntelliJ Platform Plugin SDK 2.x 构建，支持 OpenAI/Anthropic/Gemini/Custom 四种 AI Provider，使用 SSE 流式输出。

## 构建与开发命令

```bash
# 构建插件（产物在 build/distributions/ 下生成 .zip，build/libs/ 下生成 .jar）
./gradlew build

# 启动包含插件的调试 IDE 实例
./gradlew runIde

# 验证插件兼容性
./gradlew verifyPluginConfiguration

# 清理构建产物
./gradlew clean
```

**环境要求**：JDK 21 + Gradle 8.12 + Kotlin 2.1.0，目标平台 IntelliJ IDEA 2024.3+ (sinceBuild 243)

## 架构

### 核心流程

用户点击闪电按钮 → `GenerateCommitMessageAction` 获取已选中的 Change 列表 → `DiffCollector` 计算 unified diff → `PromptBuilder` 组装 prompt → `AiClientFactory` 创建对应 Provider 客户端 → `AbstractAiClient` 通过 SSE 流式调用 → 实时写入 Commit Message Document

### 包结构与职责

- **`action/`** — `GenerateCommitMessageAction`：注册到 `Vcs.MessageActionGroup` 的 AnAction，管理协程生命周期和取消逻辑
- **`ai/`** — AI 客户端抽象层
  - `AiClient` 接口：定义 `generateStreaming`、`testConnection`、`fetchModels`
  - `AbstractAiClient`：基于 JDK HttpClient 的 SSE 流式解析基类，处理 URL 版本化、流解析、错误处理
  - `AiClientFactory`：根据 `PluginSettings` 中配置的 Provider 类型创建具体客户端实例（含 HttpClient + 代理配置）
  - `AiProvider` 枚举：定义各 Provider 的默认 API 地址和模型列表
  - `impl/` — 四个具体实现：`OpenAiClient`、`AnthropicClient`、`GeminiClient`、`CustomClient`
- **`diff/`** — `DiffCollector`：遍历 `Change` 对象生成 unified diff 文本，支持长度截断
- **`prompt/`** — `PromptBuilder`（组装 diff + locale + 模板）和 `DefaultPrompts`（内置三套模板）
- **`settings/`** — `PluginSettings`（项目级 `PersistentStateComponent`，API Key 通过 `PasswordSafe` 加密存储）和 `PluginSettingsConfigurable`（Settings UI）
- **`util/`** — `ProxyConfigUtil`：根据代理模式（IDE/Custom/None）配置 HttpClient 代理

### 关键设计决策

- 字节码插桩已禁用（`build.gradle.kts` 中过滤 `instrumentCode` 任务），因为插件不使用 .form 文件或属性注入
- `sinceBuild = "243"` 且 `untilBuild = null`，不限制上限版本
- 插件依赖 `Git4Idea` 和 `com.intellij.modules.vcs`
- 使用 `kotlinx-serialization-json` 进行 JSON 序列化（非 Gson/Moshi）
- Settings 注册在 `parentId="tools"` 下，即 `Settings → Tools → AI Commit Helper`

### 其他

- 功能更新后，请更新自动更新插件说明
- 功能更新后，请更新自动更新reademe文档
