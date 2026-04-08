plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.github.fangzc"
version = "1.0.1"

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
        name = "Commit Message(AI)"
        version = project.version.toString()
        description = """
            <p>Generate git commit messages using AI (OpenAI, Anthropic, Gemini or custom OpenAI-compatible endpoints).</p>
            <ul>
                <li>One-click AI commit message generation based on selected changes</li>
                <li>Streaming output for real-time feedback</li>
                <li>Built-in prompt templates (Conventional Commits, Simple, Detailed)</li>
                <li>Custom prompt support</li>
                <li>Proxy configuration</li>
            </ul>
        """.trimIndent()
        vendor {
            name = "fangzc"
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
