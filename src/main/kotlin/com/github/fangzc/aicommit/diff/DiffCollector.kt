package com.github.fangzc.aicommit.diff

import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import java.io.StringWriter

/**
 * Diff 收集器：从 Change 列表生成 unified diff 文本
 */
object DiffCollector {

    /**
     * 根据已选中的变更列表，计算 unified diff
     * @param changes 已选中的变更列表
     * @param project 当前项目
     * @param maxLength 最大 diff 字符数限制
     * @return unified diff 文本
     */
    fun computeDiff(changes: List<Change>, project: Project, maxLength: Int = 10000): String {
        if (changes.isEmpty()) return ""

        val basePath = project.basePath ?: return ""

        try {
            // 使用 IdeaTextPatchBuilder 生成 patch
            val patches = IdeaTextPatchBuilder.buildPatch(
                project,
                changes,
                java.nio.file.Path.of(basePath),
                false,  // reversePatch
                true    // includeBaseText
            )

            // 使用 UnifiedDiffWriter 输出 unified diff 字符串
            val writer = StringWriter()
            UnifiedDiffWriter.write(project, patches, writer, "\n", null)
            val fullDiff = writer.toString()

            // 如果 diff 超过限制，截断并追加提示
            return if (fullDiff.length > maxLength) {
                val truncated = fullDiff.substring(0, maxLength)
                "$truncated\n\n... [diff truncated, ${fullDiff.length - maxLength} characters omitted]"
            } else {
                fullDiff
            }
        } catch (e: Exception) {
            // 降级处理：手动构建简单 diff 信息
            return buildSimpleDiffInfo(changes)
        }
    }

    /**
     * 降级方案：当 IdeaTextPatchBuilder 失败时，构建简单的变更信息
     */
    private fun buildSimpleDiffInfo(changes: List<Change>): String {
        val sb = StringBuilder()
        for (change in changes) {
            val type = when {
                change.type == Change.Type.NEW -> "Added"
                change.type == Change.Type.DELETED -> "Deleted"
                change.type == Change.Type.MOVED -> "Moved"
                else -> "Modified"
            }
            val path = (change.afterRevision ?: change.beforeRevision)?.file?.path ?: "unknown"
            sb.appendLine("$type: $path")

            // 尝试获取文件内容差异
            try {
                val beforeContent = getRevisionContent(change.beforeRevision)
                val afterContent = getRevisionContent(change.afterRevision)
                if (beforeContent != null || afterContent != null) {
                    if (beforeContent == null && afterContent != null) {
                        sb.appendLine("+++ New file content (first 200 lines):")
                        afterContent.lines().take(200).forEach { sb.appendLine("+ $it") }
                    } else if (beforeContent != null && afterContent == null) {
                        sb.appendLine("--- Deleted file")
                    }
                }
            } catch (_: Exception) {
                // 忽略内容获取失败
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun getRevisionContent(revision: ContentRevision?): String? {
        return try {
            revision?.content
        } catch (_: Exception) {
            null
        }
    }
}
