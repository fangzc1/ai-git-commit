pluginManagement {
    repositories {
        mavenLocal()

        maven {
            url = uri("https://maven.aliyun.com/repository/gradle-plugin")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "ai-commit-message"
