package com.github.fangzc.aicommit.prompt

import com.github.fangzc.aicommit.settings.PluginSettings
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

/**
 * 提示词构建器：根据设置和 diff 内容组装完整的 prompt
 */
object PromptBuilder {

    fun build(diff: String, project: Project): String {
        val state = PluginSettings.getInstance(project).stateData
        val template = if (state.useCustomPrompt && state.customPrompt.isNotBlank()) {
            state.customPrompt
        } else {
            DefaultPrompts.TEMPLATES[state.promptTemplate] ?: DefaultPrompts.CONVENTIONAL
        }

        val branch = getCurrentBranch(project) ?: "unknown"
        val locale = state.locale.ifBlank { "en" }

        return template
            .replace("{diff}", diff)
            .replace("{branch}", branch)
            .replace("{locale}", locale)
    }

    private fun getCurrentBranch(project: Project): String? {
        val repos = GitRepositoryManager.getInstance(project).repositories
        return repos.firstOrNull()?.currentBranch?.name
    }
}
