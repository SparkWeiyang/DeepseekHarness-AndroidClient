package com.spark.harness.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spark.harness.data.ChatItem
import com.spark.harness.data.ModelGroup
import com.spark.harness.data.ModelInfo
import com.spark.harness.data.ModelSelection
import com.spark.harness.data.ServerConfig
import com.spark.harness.data.SkillEntry
import com.spark.harness.net.HarnessApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 一个会话的聊天状态机：加载历史 + 通过 mux 流折叠事件
 * （user/message、assistant/chunk、assistant/message、tool/call、tool/result、turn/start、turn/end 等），
 * 并维护标题、projections（统计条/权限/上下文压力）、队列、当前工具等 UI 状态。
 */
class ChatController(
    private val server: ServerConfig,
    private val sessionId: String
) {
    private val api = HarnessApiClient(server)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val items = mutableStateListOf<ChatItem>()
    var loading by mutableStateOf(true)
    var running by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** 会话标题（session/title 事件或 title projection）。 */
    var title by mutableStateOf<String?>(null)
    /** 当前正在执行的工具名（运行状态条）。 */
    var currentTool by mutableStateOf<String?>(null)
    /** 排队消息数（session/queue 帧）。 */
    var queueCount by mutableIntStateOf(0)
    /** 统计条文本（输入/输出/缓存命中/TTFT/速度）。 */
    var statsText by mutableStateOf<String?>(null)
    /** 上下文压力比例 0..1。 */
    var pressureFraction by mutableStateOf<Float?>(null)
    /** 当前权限预设（permissions projection）。 */
    var permPreset by mutableStateOf<String?>(null)
    /** 当前模型名（provider/model，session.models 缓存）。 */
    var currentModelName by mutableStateOf<String?>(null)

    private val projectionValues = HashMap<String, Any>()

    private var ws: WebSocket? = null
    private var maxSeq = -1L
    private var currentAssistant = -1
    private var currentReasoning = ""
    private val toolIndex = mutableMapOf<String, Int>()

    suspend fun start() {
        loadHistory()
        connectStream()
        scope.launch { refreshModels() }
    }

    private suspend fun loadHistory() {
        val payload = JSONObject()
            .put("sessionId", sessionId)
            .put("maxMessages", 50) // 限制初始窗口，避免大会话（数十万 chunk 事件）全量拉取
        when (val r = api.call("session.history", payload)) {
            is HarnessApiClient.RpcOutcome.Ok -> {
                items.clear()
                toolIndex.clear()
                maxSeq = -1L
                currentAssistant = -1
                currentReasoning = ""

                val v = r.value as? JSONObject
                // projections 尾页基线
                v?.optJSONObject("projections")?.optJSONObject("values")?.let { values ->
                    val keys = values.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        projectionValues[k] = values.get(k)
                    }
                }
                (projectionValues["title"] as? String)?.takeIf { it.isNotBlank() }?.let { title = it }
                recomputeProjectionState()

                val events = v?.optJSONArray("events") ?: JSONArray()
                for (i in 0 until events.length()) {
                    val event = events.optJSONObject(i)?.optJSONObject("event") ?: continue
                    applyEvent(event)
                }
            }
            is HarnessApiClient.RpcOutcome.Err -> error = r.message
        }
        loading = false
    }

    private fun connectStream() {
        ws = api.connectMux(
            onFrame = { method, payload -> onFrame(method, payload) },
            onState = { /* v1：断线由重进会话时的 history 重新拉取 */ }
        )
    }

    private fun onFrame(method: String, payload: JSONObject) {
        when (method) {
            "session/event" -> {
                if (payload.optString("sessionId") != sessionId) return
                val event = payload.optJSONObject("event") ?: return
                applyEvent(event)
            }
            "session/subscribed" -> {
                if (payload.optString("sessionId") != sessionId) return
                val lastSeq = payload.optLong("lastSeq", -1L)
                if (lastSeq > maxSeq) {
                    scope.launch { loadHistory() } // 订阅前有空洞，重拉
                }
            }
            "session/projection" -> {
                if (payload.optString("sessionId") != sessionId) return
                val key = payload.optString("key")
                val value = payload.opt("value")
                if (value != null) projectionValues[key] = value
                if (key == "title" && value is String && value.isNotBlank()) title = value
                recomputeProjectionState()
            }
            "session/queue" -> {
                if (payload.optString("sessionId") != sessionId) return
                val arr = payload.optJSONArray("items")
                var n = 0
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        if (arr.optJSONObject(i)?.optString("placement") == "queued") n++
                    }
                }
                queueCount = n
            }
            "host/session-status" -> {
                if (payload.optString("sessionId") != sessionId) return
                running = payload.optBoolean("running", false)
            }
            "stream/error" -> error = "stream error"
            // approval/question 帧：v1 忽略（默认权限预设 danger-full-access，极少触发）
        }
    }

    private fun applyEvent(event: JSONObject) {
        val seq = event.optLong("seq", -1L)
        if (seq >= 0 && seq <= maxSeq) return // 去重（mux 与 history 可能重叠）
        if (seq >= 0) maxSeq = seq

        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: JSONObject()
        when (type) {
            "user/message" -> {
                val kind = data.optJSONObject("source")?.optString("kind") ?: "user"
                val text = foldTextBlocks(data.optJSONArray("content"))
                if (text.isBlank()) return
                if (kind == "user") items.add(ChatItem.User(text))
                else items.add(ChatItem.Context(text))
            }
            "assistant/chunk" -> {
                val chunk = data.optJSONObject("chunk") ?: return
                when (chunk.optString("type")) {
                    "text-delta" -> appendAssistantText(chunk.optString("text"))
                    "reasoning-delta" -> {
                        currentReasoning += chunk.optString("text")
                        if (currentAssistant >= 0) {
                            val idx = currentAssistant
                            val ex = items.getOrNull(idx) as? ChatItem.Assistant
                            if (ex != null) items[idx] = ex.copy(reasoning = currentReasoning)
                        }
                    }
                }
            }
            "assistant/message" -> {
                val message = data.optJSONObject("message") ?: return
                val content = message.optJSONArray("content")
                val text = foldTextBlocks(content)
                val reasoning = foldReasoning(content) ?: currentReasoning
                val usage = formatUsage(data.optJSONObject("usage"))
                finalizeAssistant(text, reasoning, usage)
            }
            "tool/call" -> {
                val callId = data.optString("callId")
                val name = data.optString("name")
                val args = data.optString("arguments")
                items.add(ChatItem.Tool(callId, name, args, null, false))
                toolIndex[callId] = items.size - 1
                currentTool = name
            }
            "tool/result" -> {
                val message = data.optJSONObject("message") ?: return
                val block = message.optJSONArray("content")?.optJSONObject(0) ?: return
                val callId = block.optString("toolCallId")
                val isError = block.optBoolean("isError", false) || data.has("error")
                val resultText = foldTextBlocks(block.optJSONArray("content"))
                val idx = toolIndex[callId]
                if (idx != null && idx < items.size) {
                    val ex = items[idx] as? ChatItem.Tool
                    if (ex != null) items[idx] = ex.copy(result = resultText, isError = isError)
                } else {
                    items.add(ChatItem.Tool(callId, "tool", "", resultText, isError))
                }
                currentTool = null
            }
            "session/title" -> {
                data.optString("title").takeIf { it.isNotBlank() }?.let { title = it }
            }
            "turn/start" -> running = true
            "turn/end" -> {
                running = false
                currentTool = null
                currentAssistant = -1
                currentReasoning = ""
            }
        }
    }

    private fun appendAssistantText(text: String) {
        if (text.isEmpty()) return
        if (currentAssistant < 0) {
            items.add(ChatItem.Assistant("", currentReasoning, true))
            currentAssistant = items.size - 1
        }
        val idx = currentAssistant
        val ex = items.getOrNull(idx) as? ChatItem.Assistant ?: return
        items[idx] = ex.copy(text = ex.text + text)
    }

    private fun finalizeAssistant(text: String, reasoning: String?, usage: String?) {
        if (currentAssistant >= 0) {
            val idx = currentAssistant
            val ex = items.getOrNull(idx) as? ChatItem.Assistant
            val finalText = if (text.isNotBlank()) text else ex?.text ?: ""
            items[idx] = ChatItem.Assistant(finalText, reasoning, false, usage)
            currentAssistant = -1
            currentReasoning = ""
        } else {
            if (text.isNotBlank() || !reasoning.isNullOrBlank()) {
                items.add(ChatItem.Assistant(text, reasoning, false, usage))
            }
        }
    }

    // ── projections → 统计条 / 权限 ──────────────────────────────

    private fun recomputeProjectionState() {
        val tokenUsage = projectionValues["tokenUsage"] as? JSONObject
        val sessionStats = projectionValues["sessionStats"] as? JSONObject
        val pressure = projectionValues["contextPressure"] as? JSONObject
        val perms = projectionValues["permissions"] as? JSONObject

        permPreset = perms?.optString("currentValue")?.takeIf { it.isNotBlank() }

        val parts = mutableListOf<String>()
        if (tokenUsage != null) {
            val uncached = tokenUsage.optLong("uncachedInputTokens", 0)
            val cacheRead = tokenUsage.optLong("cacheReadTokens", 0)
            val cacheWrite = tokenUsage.optLong("cacheWriteTokens", 0)
            val output = tokenUsage.optLong("outputTokens", 0)
            val totalIn = uncached + cacheRead + cacheWrite
            parts.add("输入 ${fmtTok(totalIn)} · 输出 ${fmtTok(output)}")
            if (totalIn > 0) parts.add("缓存命中 ${(cacheRead * 100.0 / totalIn).toInt()}%")
        }
        if (sessionStats != null) {
            val ttftMs = sessionStats.optLong("ttftMs", 0)
            val ttftSteps = sessionStats.optInt("ttftSteps", 0)
            val decodeMs = sessionStats.optLong("decodeMs", 0)
            val decodeTokens = sessionStats.optLong("decodeTokens", 0)
            if (ttftSteps > 0) parts.add("首 token 平均 ${fmtMs(ttftMs / ttftSteps)}")
            if (decodeMs > 0) parts.add(String.format(Locale.US, "%.0f tok/s", decodeTokens * 1000.0 / decodeMs))
        }
        statsText = parts.joinToString(" · ").ifBlank { null }

        pressureFraction = if (pressure != null && pressure.optLong("contextWindow", 0) > 0) {
            (pressure.optLong("pressureTokens", 0).toDouble() / pressure.optLong("contextWindow")).toFloat().coerceIn(0f, 1f)
        } else null
    }

    private fun fmtTok(n: Long): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1000 -> String.format(Locale.US, "%.0fK", n / 1000.0)
        else -> n.toString()
    }

    private fun fmtMs(ms: Long): String = when {
        ms >= 60_000 -> String.format(Locale.US, "%.1fm", ms / 60_000.0)
        ms >= 1000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
        else -> "${ms}ms"
    }

    // ── 会话操作 ─────────────────────────────────────────────────

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            val content = JSONArray().put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", trimmed)
                }
            )
            val payload = JSONObject().apply {
                put("sessionId", sessionId)
                put("mode", "queue")
                put("content", content)
            }
            when (val r = api.call("session.prompt", payload)) {
                is HarnessApiClient.RpcOutcome.Ok -> Unit
                is HarnessApiClient.RpcOutcome.Err -> error = r.message
            }
        }
    }

    fun cancel() {
        scope.launch {
            api.call("session.cancel", JSONObject().put("sessionId", sessionId))
        }
    }

    // ── 模型目录与切换 ────────────────────────────────────────────

    /** 返回 (当前选择, provider 分组)；失败返回 null。 */
    suspend fun loadModels(): Pair<ModelSelection, List<ModelGroup>>? {
        when (val r = api.call("session.models", JSONObject().put("sessionId", sessionId))) {
            is HarnessApiClient.RpcOutcome.Ok -> {
                val v = r.value as? JSONObject ?: return null
                val cur = v.optJSONObject("current")
                val groupsArr = v.optJSONArray("groups") ?: JSONArray()
                val groups = ArrayList<ModelGroup>(groupsArr.length())
                for (i in 0 until groupsArr.length()) {
                    val g = groupsArr.optJSONObject(i) ?: continue
                    val modelsArr = g.optJSONArray("models") ?: JSONArray()
                    val models = ArrayList<ModelInfo>(modelsArr.length())
                    for (j in 0 until modelsArr.length()) {
                        val m = modelsArr.optJSONObject(j) ?: continue
                        models.add(
                            ModelInfo(
                                id = m.optString("id"),
                                name = m.optString("name").ifBlank { m.optString("id") },
                                description = m.optString("description").ifBlank { null }
                            )
                        )
                    }
                    groups.add(ModelGroup(g.optString("id"), g.optString("name"), models))
                }
                val sel = ModelSelection(
                    provider = cur?.optString("provider") ?: "",
                    model = cur?.optString("model") ?: "",
                    reasoningEffort = cur?.optString("reasoningEffort").takeIf { !it.isNullOrBlank() }
                )
                currentModelName = if (sel.model.isNotBlank()) "${sel.provider}/${sel.model}" else null
                return sel to groups
            }
            is HarnessApiClient.RpcOutcome.Err -> {
                error = r.message
                return null
            }
        }
    }

    private suspend fun refreshModels() {
        loadModels()
    }

    fun selectModel(provider: String, model: String, effort: String?) {
        scope.launch {
            val payload = JSONObject().apply {
                put("sessionId", sessionId)
                put("provider", provider)
                put("model", model)
                effort?.let { put("reasoningEffort", it) }
            }
            when (val r = api.call("session.selectModel", payload)) {
                is HarnessApiClient.RpcOutcome.Ok -> currentModelName = "$provider/$model"
                is HarnessApiClient.RpcOutcome.Err -> error = r.message
            }
        }
    }

    /** 用户可调用 skill（斜杠菜单）。 */
    suspend fun loadSkills(): List<SkillEntry> {
        when (val r = api.call("skill.list", JSONObject().put("sessionId", sessionId))) {
            is HarnessApiClient.RpcOutcome.Ok -> {
                val arr = (r.value as? JSONObject)?.optJSONArray("skills") ?: return emptyList()
                val out = ArrayList<SkillEntry>(arr.length())
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    out.add(
                        SkillEntry(
                            name = s.optString("name"),
                            description = s.optString("description"),
                            modelInvocable = s.optBoolean("modelInvocable", true)
                        )
                    )
                }
                return out
            }
            is HarnessApiClient.RpcOutcome.Err -> {
                error = r.message
                return emptyList()
            }
        }
    }

    /** 切换安全程度（全局 permission 预设）。 */
    fun setPermissionPreset(preset: String) {
        permPreset = preset
        scope.launch {
            val patch = JSONObject().put("defaultPreset", preset)
            val payload = JSONObject().apply {
                put("ns", "permission")
                put("patch", patch)
            }
            when (val r = api.call("settings.update", payload)) {
                is HarnessApiClient.RpcOutcome.Ok -> Unit
                is HarnessApiClient.RpcOutcome.Err -> error = r.message
            }
        }
    }

    fun close() {
        ws?.close(1000, "bye")
        scope.cancel()
    }

    // ── helpers ───────────────────────────────────────────────────

    private fun foldTextBlocks(content: JSONArray?): String {
        if (content == null) return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString()
    }

    private fun foldReasoning(content: JSONArray?): String? {
        if (content == null) return null
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "reasoning") sb.append(block.optString("text"))
        }
        return sb.toString().ifBlank { null }
    }

    private fun formatUsage(u: JSONObject?): String? {
        if (u == null) return null
        val input = u.optLong("inputTokens", -1)
        val output = u.optLong("outputTokens", -1)
        val cacheRead = u.optLong("cacheReadTokens", -1)
        val cacheWrite = u.optLong("cacheWriteTokens", -1)
        if (input < 0 && output < 0) return null
        val parts = mutableListOf<String>()
        if (input >= 0) parts.add("输入 ${fmtTok(input)}")
        if (cacheRead >= 0) parts.add("缓存读 ${fmtTok(cacheRead)}")
        if (cacheWrite > 0) parts.add("缓存写 ${fmtTok(cacheWrite)}")
        if (output >= 0) parts.add("输出 ${fmtTok(output)}")
        return parts.joinToString(" · ") + " tok"
    }
}
