package com.spark.harness.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spark.harness.net.LanScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 局域网扫描对话框：并发探测当前 /24 网段下端口 3080 开放的主机。
 * 点击某台主机即通过 [onPick] 回传 (host, port)。
 */
@Composable
fun LanScanDialog(
    onDismiss: () -> Unit,
    onPick: (host: String, port: Int) -> Unit
) {
    val scanPort = 3080
    val found = remember { mutableStateListOf<LanScanner.FoundHost>() }
    var scanning by remember { mutableStateOf(false) }
    var base by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var runId by remember { mutableIntStateOf(0) }

    fun triggerScan() {
        val localIp = LanScanner.localIpv4()
        if (localIp == null) {
            error = "无法获取本机 IP，请确认已连接 WiFi"
            scanning = false
            return
        }
        base = localIp.substringBeforeLast('.')
        error = null
        found.clear()
        scanning = true
        runId += 1
    }

    // 首次进入自动扫描
    LaunchedEffect(Unit) { triggerScan() }

    // 每次 runId 变化执行一次扫描；结果一次性写回，避免跨线程增量更新导致的重复项
    LaunchedEffect(runId) {
        val myRun = runId
        val b = base ?: return@LaunchedEffect
        if (myRun == 0) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            LanScanner.scan(b, scanPort)
        }
        // 若期间触发了新的扫描（runId 已变），丢弃本次过期结果
        if (myRun == runId) {
            found.clear()
            found.addAll(result)
            scanning = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!scanning) onDismiss() },
        title = { Text("扫描局域网") },
        text = {
            Column {
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(
                        text = if (base != null) "正在扫描 $base.1 - $base.254 的 $scanPort 端口…" else "准备扫描…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (scanning) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text("扫描中…")
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("发现 ${found.size} 台主机")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (!scanning && found.isEmpty()) {
                        Text(
                            "未发现开放 $scanPort 端口的主机。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (found.isNotEmpty()) {
                        LazyColumn(Modifier.height(220.dp)) {
                            items(found, key = { it.ip }) { host ->
                                Button(
                                    onClick = { onPick(host.ip, host.port) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("${host.ip}:${host.port}", Modifier.padding(vertical = 4.dp))
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (scanning) {
                TextButton(onClick = {}, enabled = false) { Text("扫描中…") }
            } else {
                TextButton(onClick = { triggerScan() }) { Text("重新扫描") }
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) { Text("取消") }
        }
    )
}
