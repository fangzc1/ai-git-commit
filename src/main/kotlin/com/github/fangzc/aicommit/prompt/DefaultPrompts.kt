package com.github.fangzc.aicommit.prompt

/**
 * 严格遵循约定式提交结构化规范的模板
 */
object DefaultPrompts {

    /**
     * 格式：<type>(<scope>): <subject> + <body>
     * 侧重于解释“为什么”做这个改动
     */
    const val CONVENTIONAL = """
# Task
Generate a git commit message in 'Conventional Commits' format.

# Format
<type>(<scope>): <subject>
<BLANK LINE>
<body>

# Rules
1. Type: feat, fix, docs, style, refactor, perf, test, build, ci, chore, revert.
2. Scope: Package or module name (optional).
3. Subject: Imperative mood, no period, max 72 chars.
4. Body: Explain the motivation and what was changed. Use a blank line to separate from header.
5. Language: {locale}.

# Output
- Output ONLY the raw commit message.
- DO NOT use markdown code blocks or any labels like "Subject:".

# Diff
{diff}
"""

    /**
     * 格式：<type>(<scope>): <subject>
     * 极其精简，适合微小改动
     */
    const val SIMPLE = """
# Task
Generate a one-line git commit message in 'Conventional Commits' format.

# CRITICAL RULES
- ONLY output a single line of plain text.
- NO markdown code blocks (```). NO backticks (`).
- NO quotes, NO period at the end.
- Language: {locale}.

# Format
<type>(<scope>): <subject>

# Diff
{diff}
"""

    /**
     * 格式：<type>(<scope>): <subject> + <body> + <footer>
     * 最全模式，包含 Breaking Changes 和 Issue 关联
     */
    const val DETAILED = """
# Task
Generate a full-structured git commit message. 

# CRITICAL CONSTRAINT
- OUTPUT THE RAW TEXT ONLY.
- DO NOT USE MARKDOWN CODE BLOCKS (```).
- DO NOT USE QUOTES, SYMBOLS, OR ANY WRAPPERS.
- START IMMEDIATELY WITH THE TYPE.

# Format
<type>(<scope>): <subject>
<BLANK LINE>
<body>
<BLANK LINE>
<footer>

# Rules
1. Header: <type>(<scope>): <subject> (max 72 chars, imperative mood).
2. Body: Explain WHY and WHAT. Use bullet points for multiple changes.
3. Footer: If breaking, "BREAKING CHANGE: <desc>". If issue, "Closes #<num>".
4. Language: {locale}.

# Final Reminder
Your entire response will be used directly as a git commit message. If you include backticks (```), the commit will fail. Give me the plain text.

# Diff
{diff}
"""

    val TEMPLATES = mapOf(
        "Conventional Commits" to CONVENTIONAL, "Simple" to SIMPLE, "Detailed" to DETAILED
    )

    val DEFAULT_TEMPLATE_NAME = "Conventional Commits"
}
