package com.github.fangzc.aicommit.action

import com.github.fangzc.aicommit.ai.AiClientFactory
import com.github.fangzc.aicommit.diff.DiffCollector
import com.github.fangzc.aicommit.prompt.PromptBuilder
import com.github.fangzc.aicommit.settings.PluginSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler
import kotlinx.coroutines.*

/**
 * 主按钮 Action：在 commit message 编辑器工具栏中的 AI 生成按钮
 * 注册到 Vcs.MessageActionGroup，出现在 commit message 编辑器上方
 */
class GenerateCommitMessageAction : AnAction() {

    // 当前正在执行的生成任务，用于支持取消
    @Volatile
    private var currentJob: Job? = null

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // 只在 commit 上下文中显示按钮
        val workflowHandler = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER)
        e.presentation.isEnabledAndVisible = workflowHandler != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val document = e.getData(VcsDataKeys.COMMIT_MESSAGE_DOCUMENT) ?: return

        // 获取 commit workflow handler 以获取已选中的变更
        val workflowHandler = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER)
                as? AbstractCommitWorkflowHandler<*, *>
        if (workflowHandler == null) {
            showNotification(project, "Cannot access commit workflow.", NotificationType.ERROR)
            return
        }

        // 如果正在生成中，取消当前任务
        currentJob?.let {
            if (it.isActive) {
                it.cancel()
                showNotification(project, "Generation cancelled.", NotificationType.INFORMATION)
                currentJob = null
                return
            }
        }

        // 获取已选中的变更
        val includedChanges: List<Change> = workflowHandler.ui.getIncludedChanges()
        if (includedChanges.isEmpty()) {
            showNotification(
                project,
                "No files selected for commit. Please select files first.",
                NotificationType.WARNING
            )
            return
        }

        // 检查 API Key 是否已配置
        val settings = PluginSettings.getInstance()
        if (settings.apiKey.isBlank()) {
            showNotification(
                project,
                "API Key is not configured. Please go to Settings > Version Control > AI Commit Message.",
                NotificationType.ERROR
            )
            return
        }

        // 启动后台任务
        @Suppress("UnstableApiUsage")
        currentJob = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            withBackgroundProgress(project, "Generating commit message...") {
                try {
                    // 1. 收集 diff
                    val diff = withContext(Dispatchers.IO) {
                        DiffCollector.computeDiff(
                            changes = includedChanges,
                            project = project,
                            maxLength = settings.stateData.maxDiffLength
                        )
                    }

                    if (diff.isBlank()) {
                        showNotification(
                            project,
                            "Selected files have no changes.",
                            NotificationType.WARNING
                        )
                        return@withBackgroundProgress
                    }

                    // 2. 构建 prompt
                    val prompt = PromptBuilder.build(diff, project)

                    // 3. 调用 AI 流式生成
                    val client = AiClientFactory.createFromSettings()

                    // 清空当前 commit message
                    withContext(Dispatchers.Main) {
                        WriteCommandAction.runWriteCommandAction(project) {
                            document.setText("")
                        }
                    }

                    client.generateStreaming(
                        prompt = prompt,
                        onToken = { accumulatedText ->
                            // 每次收到 token，在 EDT 线程更新 commit message
                            com.intellij.openapi.application.ApplicationManager.getApplication()
                                .invokeLater {
                                    WriteCommandAction.runWriteCommandAction(project) {
                                        document.setText(accumulatedText)
                                    }
                                }
                        },
                        onComplete = { fullText ->
                            // 最终设置完整文本（确保最终一致性）
                            com.intellij.openapi.application.ApplicationManager.getApplication()
                                .invokeLater {
                                    WriteCommandAction.runWriteCommandAction(project) {
                                        document.setText(fullText.trim())
                                    }
                                }
                            currentJob = null
                        },
                        onError = { error ->
                            showNotification(
                                project,
                                "Failed to generate commit message: ${error.message}",
                                NotificationType.ERROR
                            )
                            currentJob = null
                        }
                    )
                } catch (e: CancellationException) {
                    showNotification(project, "Generation cancelled.", NotificationType.INFORMATION)
                } catch (e: Exception) {
                    showNotification(
                        project,
                        "Error: ${e.message}",
                        NotificationType.ERROR
                    )
                } finally {
                    currentJob = null
                }
            }
        }
    }

    private fun showNotification(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AiCommit.Notification")
            .createNotification(content, type)
            .notify(project)
    }
}
