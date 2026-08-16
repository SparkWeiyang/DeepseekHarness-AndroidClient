package com.spark.harness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spark.harness.data.ServerConfig
import com.spark.harness.data.SessionStats
import com.spark.harness.net.DeepSeekApi
import com.spark.harness.net.HarnessApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private data class Row(
    val session: JSONObject,
    val stats: SessionStats
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsTab(
    server: ServerConfig,
    onOpen: (sessionId: String) -> Unit
) {
    val api = remember(server.baseUrl) { HarnessApiClient(server) }
    val scope = rememberCoroutineScope()

    var rows by remember { mutableStateOf<List<Row>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // DeepSeek 消耗（PC 端凭证查询，经 dsh-lan 的 deepseek/balance 端点）
    var balance by remember { mutableStateOf<DeepSeekApi.Balance?>(null) }
    var balanceState by remember { mutableStateOf(0) } // 0=未查 1=查询中 2=已查
    var refreshTick by remember { mutableStateOf(0) }

    fun reload() {
        loading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { api.call("session.list", JSONObject()) }
            if (r is HarnessApiClient.RpcOutcome.Ok) {
                val arr = (r.value as? JSONObject)?.optJSONArray("items") ?: JSONArray()
                val list = ArrayList<Row>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optBoolean("blank", false)) continue
                    parseSessionStats(o.optJSONObject("projections"))?.let { s -> list.add(Row(o, s)) }
                }
                rows = list
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    // 拉取 DeepSeek 余额（经 PC harness 代查，密钥在 PC 端）
    LaunchedEffect(refreshTick, server.baseUrl) {
        balanceState = 1
        balance = DeepSeekApi.fetchBalance(api)
        balanceState = 2
    }

    val runningRows = rows.filter { it.session.optBoolean("running", false) }
    val displayRows = if (runningRows.isNotEmpty()) runningRows else rows.take(5)

    // 全站累计
    var totalInput = 0L
    var totalOutput = 0L
    var totalCacheRead = 0L
    var totalTurns = 0L
    var totalLlmMs = 0L
    rows.forEach { s ->
        totalInput += s.stats.inputTokens
        totalOutput += s.stats.outputTokens
        totalCacheRead += s.stats.cacheReadTokens
        totalTurns += s.stats.turns
        totalLlmMs += s.stats.llmMs
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("详情") },
                actions = {
                    IconButton(onClick = {
                        reload()
                        refreshTick++
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                item(key = "deepseek") { DeepSeekBalanceCard(balance, balanceState) }

                if (rows.isNotEmpty()) {
                    item(key = "totals") {
                        TotalsCard(totalInput, totalOutput, totalCacheRead, totalTurns, totalLlmMs, rows.size)
                    }
                }

                if (displayRows.isNotEmpty()) {
                    item(key = "header") {
                        Text(
                            if (runningRows.isNotEmpty()) "正在运行的项目 (${runningRows.size})" else "最近的项目",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(displayRows.size, key = { "s-" + displayRows[it].session.optString("sessionId") }) { i ->
                        SessionStatsCard(displayRows[i], onOpen)
                    }
                }

                if (runningRows.isNotEmpty()) {
                    item(key = "tasks-header") {
                        Text(
                            "进行中的任务",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(runningRows.size, key = { "task-" + runningRows[it].session.optString("sessionId") }) { i ->
                        RunningTaskCard(runningRows[i].session, onOpen)
                    }
                }
            }
        }
    }
}

// ── DeepSeek 消耗卡 ────────────────────────────────────────────

@Composable
private fun DeepSeekBalanceCard(
    balance: DeepSeekApi.Balance?,
    state: Int
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("DeepSeek 消耗", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            when {
                state == 1 -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("查询中…", style = MaterialTheme.typography.bodyMedium)
                }
                balance == null -> Text(
                    "查询失败：请确认 PC harness 已配置 DEEPSEEK_API_KEY（网页端 → 模型设置），或重启 PC 端加载 dsh-lan 插件。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                else -> {
                    val b = balance
                    Row(Modifier.fillMaxWidth()) {
                        Metric("剩余余额", "${b.total} ${b.currency}", Modifier.weight(1f))
                        Metric("赠金", "${b.granted}", Modifier.weight(1f))
                        Metric("充值", "${b.toppedUp}", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "已消耗 ≈ ${String.format(Locale.US, "%.2f", b.consumed)} ${b.currency}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!b.available) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "账户余额不可用（is_available=false）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

// ── 全站累计卡 ─────────────────────────────────────────────────

@Composable
private fun TotalsCard(
    input: Long,
    output: Long,
    cacheRead: Long,
    turns: Long,
    llmMs: Long,
    sessionCount: Int
) {
    val hitRate = if (input > 0) cacheRead.toDouble() / input else 0.0
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("全站累计 (${sessionCount} 个项目)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Metric("输入", formatTokens(input) + " tok", Modifier.weight(1f))
                Metric("输出", formatTokens(output) + " tok", Modifier.weight(1f))
                Metric("缓存命中", String.format(Locale.US, "%.0f%%", hitRate * 100), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "总轮次 $turns · LLM ${formatMillis(llmMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── 单会话统计卡 ───────────────────────────────────────────────

@Composable
private fun SessionStatsCard(row: Row, onOpen: (String) -> Unit) {
    val s = row.session
    val stats = row.stats
    val title = extractTitle(s.optJSONObject("projections"))
        ?: s.optString("cwd")
        ?: s.optString("sessionId").take(8)
    val running = s.optBoolean("running", false)

    Card(Modifier.fillMaxWidth().clickable { onOpen(s.optString("sessionId")) }) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (running) "⚡ " else "") + title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "打开 ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Metric("缓存命中", String.format(Locale.US, "%.0f%%", stats.cacheHitRate * 100), Modifier.weight(1f))
                Metric("首 token", formatMillis(stats.ttftAvgMs), Modifier.weight(1f))
                Metric("速度", String.format(Locale.US, "%.0f tok/s", stats.decodeTokPerSec), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "输入 ${formatTokens(stats.inputTokens)} tok · 输出 ${formatTokens(stats.outputTokens)} tok · ${stats.turns} 轮",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (stats.contextWindow > 0) {
                Spacer(Modifier.height(8.dp))
                val frac = (stats.pressureTokens.toDouble() / stats.contextWindow).coerceIn(0.0, 1.0)
                Text(
                    "上下文 ${formatTokens(stats.pressureTokens)} / ${formatTokens(stats.contextWindow)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Bar(frac.toFloat(), if (frac > 0.8) Color(0xFFE53935) else Color(0xFF4CAF50))
            }
        }
    }
}

// ── 进行中任务卡 ───────────────────────────────────────────────

@Composable
private fun RunningTaskCard(session: JSONObject, onOpen: (String) -> Unit) {
    val values = session.optJSONObject("projections")?.optJSONObject("values")
    val goal = values?.optJSONObject("goal")?.optJSONObject("goal")
    val objective = goal?.optString("objective") ?: ""
    val todos = values?.optJSONArray("todos")
    Card(
        Modifier.fillMaxWidth().clickable { onOpen(session.optString("sessionId")) },
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "⚡ ${extractTitle(session.optJSONObject("projections")) ?: session.optString("sessionId").take(8)}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            if (objective.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(objective, style = MaterialTheme.typography.bodyMedium)
            }
            todos?.let { arr ->
                for (i in 0 until minOf(arr.length(), 6)) {
                    val t = arr.optJSONObject(i) ?: continue
                    val status = t.optString("status")
                    val mark = when (status) {
                        "completed" -> "✓"
                        "in_progress" -> "▶"
                        else -> "○"
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$mark ${t.optString("content")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status == "in_progress") MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Bar(frac: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(frac.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
    }
}
