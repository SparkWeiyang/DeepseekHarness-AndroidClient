package com.spark.harness.data

/** 聊天流里的一条可渲染项。 */
sealed interface ChatItem {
    /** 用户输入气泡。 */
    data class User(val text: String) : ChatItem

    /** 注入的上下文（AGENTS.md、技能内容等系统提示），弱化显示。 */
    data class Context(val text: String) : ChatItem

    /** 助手回复；streaming=true 表示仍在流式输出。reasoning 为思考过程（可为空）。usage 为本次 step 的 token 统计。 */
    data class Assistant(
        val text: String,
        val reasoning: String?,
        val streaming: Boolean,
        val usage: String? = null
    ) : ChatItem

    /** 一次工具调用卡片；result 在 tool/result 到达后回填。 */
    data class Tool(
        val callId: String,
        val name: String,
        val args: String,
        val result: String?,
        val isError: Boolean
    ) : ChatItem
}

/** 会话列表项。 */
data class SessionSummary(
    val id: String,
    val updatedAt: Long,
    val running: Boolean,
    val blank: Boolean,
    val cwd: String?,
    val title: String?
)

/** 工作区。 */
data class WorkspaceView(
    val id: String,
    val path: String,
    val title: String,
    val sessionIds: List<String>
)

/** 模型目录：一个 provider 下的模型组。 */
data class ModelGroup(val id: String, val name: String, val models: List<ModelInfo>)

data class ModelInfo(val id: String, val name: String, val description: String?)

data class ModelSelection(val provider: String, val model: String, val reasoningEffort: String?)

/** 用户可调用的 skill（斜杠菜单）。 */
data class SkillEntry(val name: String, val description: String, val modelInvocable: Boolean)

/** 会话统计（由 projections 计算而来）。 */
data class SessionStats(
    val turns: Int,
    val steps: Int,
    val ttftAvgMs: Long,
    val decodeTokPerSec: Double,
    val cacheHitRate: Double,
    val uncachedInputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val outputTokens: Long,
    val contextWindow: Long,
    val pressureTokens: Long,
    val llmMs: Long,
    val toolMs: Long
) {
    val inputTokens: Long get() = uncachedInputTokens + cacheReadTokens + cacheWriteTokens
}
