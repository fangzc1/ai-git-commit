plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.github.fangzc"
version = "1.0.8"

kotlin {
    jvmToolchain(21)
}

repositories {
    maven { url = uri("https://mirrors.huaweicloud.com/repository/maven/") }
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")
        bundledPlugin("Git4Idea")
        pluginVerifier()
        instrumentationTools()
    }

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.github.fangzc.ai-commit-message"
        name = "AI Commit Helper"
        version = project.version.toString()
        description = """
            <h3>AI Commit Helper</h3>
            <p>Automatically generate Git commit messages using AI. Supports OpenAI, Anthropic, Gemini and custom providers with SSE streaming output.</p>
            <ul>
                <li>One-click generate commit message from selected changes</li>
                <li>Multiple AI providers: OpenAI, Anthropic, Gemini, Custom</li>
                <li>Streaming output for real-time generation</li>
                <li>Customizable prompt templates (Conventional Commits / Simple / Detailed / Custom)</li>
                <li>Proxy support (IDE / Custom / None)</li>
                <li>Configurable commit message language</li>
            </ul>
            <hr/>
            <h3>AI Commit Helper（中文说明）</h3>
            <p>使用 AI 自动生成 Git Commit Message。支持 OpenAI、Anthropic、Gemini 及自定义 Provider，采用 SSE 流式输出。</p>
            <ul>
                <li>一键基于已选中的代码变更生成 Commit Message</li>
                <li>多 AI Provider 支持：OpenAI、Anthropic、Gemini、自定义</li>
                <li>流式输出，实时查看生成过程</li>
                <li>可自定义 Prompt 模板（Conventional Commits / Simple / Detailed / Custom）</li>
                <li>代理支持（IDE 代理 / 自定义代理 / 无代理）</li>
                <li>可配置 Commit Message 输出语言</li>
            </ul>
        """.trimIndent()
        vendor {
            name = "Fangzc"
        }
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "8.12"
    }
}

// 不使用 .form 文件或属性注入，禁用字节码插桩以兼容非 JetBrains Runtime JDK
tasks.matching { it.name.contains("nstrumentCode") }.configureEach {
    enabled = false
}
