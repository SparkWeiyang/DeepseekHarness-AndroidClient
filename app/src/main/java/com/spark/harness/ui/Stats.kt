package com.spark.harness.ui

import com.spark.harness.data.SessionStats
import org.json.JSONObject
import java.util.Locale

/** 从 session.list 项的 projections 计算统计卡数据。 */
fun parseSessionStats(projections: JSONObject?): SessionStats? {
    val values = projections?.optJSONObject("values") ?: return null
    val stats = values.optJSONObject("sessionStats") ?: return null
    val usage = values.optJSONObject("tokenUsage") ?: JSONObject()
    val pressure = values.optJSONObject("contextPressure") ?: JSONObject()

    val turns = stats.optInt("turns", 0)
    val steps = stats.optInt("steps", 0)
    val ttftMs = stats.optLong("ttftMs", 0)
    val ttftSteps = stats.optInt("ttftSteps", 0)
    val decodeMs = stats.optLong("decodeMs", 0)
    val decodeTokens = stats.optLong("decodeTokens", 0)
    val llmMs = stats.optLong("llmMs", 0)
    val toolMs = stats.optLong("toolMs", 0)

    val uncached = usage.optLong("uncachedInputTokens", 0)
    val cacheRead = usage.optLong("cacheReadTokens", 0)
    val cacheWrite = usage.optLong("cacheWriteTokens", 0)
    val output = usage.optLong("outputTokens", 0)
    val totalInput = uncached + cacheRead + cacheWrite
    val cacheHitRate = if (totalInput > 0) cacheRead.toDouble() / totalInput else 0.0
    val tokPerSec = if (decodeMs > 0) decodeTokens * 1000.0 / decodeMs else 0.0

    return SessionStats(
        turns = turns,
        steps = steps,
        ttftAvgMs = if (ttftSteps > 0) ttftMs / ttftSteps else 0,
        decodeTokPerSec = tokPerSec,
        cacheHitRate = cacheHitRate,
        uncachedInputTokens = uncached,
        cacheReadTokens = cacheRead,
        cacheWriteTokens = cacheWrite,
        outputTokens = output,
        contextWindow = pressure.optLong("contextWindow", 0),
        pressureTokens = pressure.optLong("pressureTokens", 0),
        llmMs = llmMs,
        toolMs = toolMs
    )
}

fun formatTokens(n: Long): String = when {
    n >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", n / 1_000_000_000.0)
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1000 -> String.format(Locale.US, "%.1fK", n / 1000.0)
    else -> n.toString()
}

fun formatMillis(ms: Long): String = when {
    ms >= 3_600_000 -> String.format(Locale.US, "%.1fh", ms / 3_600_000.0)
    ms >= 60_000 -> String.format(Locale.US, "%.1fm", ms / 60_000.0)
    ms >= 1000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
    else -> "${ms}ms"
}

/** 会话列表项的标题（projections.values.title）。 */
fun extractTitle(projections: JSONObject?): String? {
    val title = projections?.optJSONObject("values")?.opt("title") ?: return null
    return when (title) {
        is String -> title.ifBlank { null }
        is JSONObject -> title.optString("title").ifBlank { null }
        else -> null
    }
}
