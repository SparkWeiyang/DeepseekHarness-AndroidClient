package com.spark.harness.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spark.harness.data.ServerConfig
import com.spark.harness.net.HarnessApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val PERMISSION_OPTIONS = listOf(
    "read-only" to "只读",
    "workspace-write" to "工作区可写",
    "danger-full-access" to "完全访问"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(
    server: ServerConfig,
    onSwitchServer: () -> Unit
) {
    val api = remember(server.baseUrl) { HarnessApiClient(server) }
    val scope = rememberCoroutineScope()

    var themePref by remember { mutableStateOf<String?>(null) }
    var permPreset by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    fun reload() {
        loading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { api.call("settings.describe", JSONObject()) }
            if (r is HarnessApiClient.RpcOutcome.Ok) {
                val arr = (r.value as? JSONObject)?.optJSONArray("namespaces")
                arr?.let {
                    for (i in 0 until it.length()) {
                        val ns = it.optJSONObject(i) ?: continue
                        when (ns.optString("ns")) {
                            "ui-theme" -> themePref = ns.optJSONObject("value")?.optString("preference")
                            "permission" -> permPreset = ns.optJSONObject("value")?.optString("defaultPreset")
                        }
                    }
                }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun updateSetting(ns: String, patch: JSONObject) {
        scope.launch {
            withContext(Dispatchers.IO) {
                api.call("settings.update", JSONObject().apply {
                    put("ns", ns)
                    put("patch", patch)
                })
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                actions = {
                    IconButton(onClick = { reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 工作环境
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("工作环境", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("当前连接：${server.name}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        server.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "切换工作环境（局域网网页端 / 本地 Harness）›",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onSwitchServer() }
                    )
                }
            }

            // DeepSeek 凭证说明
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("DeepSeek 凭证", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "API Key 由 PC harness 统一管理（网页端 → 模型设置 → DeepSeek）。「详情」页的消耗查询直接使用 PC 端凭证，密钥不会下发到手机。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            // 安全程度
            permPreset?.let { current ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("安全程度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        PERMISSION_OPTIONS.forEach { (value, label) ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    permPreset = value
                                    updateSetting("permission", JSONObject().put("defaultPreset", value))
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = current == value, onClick = null)
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            // 主题
            themePref?.let { current ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("主题", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        listOf("dark" to "深色", "light" to "浅色").forEach { (value, label) ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    themePref = value
                                    updateSetting("ui-theme", JSONObject().put("preference", value))
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = current == value, onClick = null)
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}
