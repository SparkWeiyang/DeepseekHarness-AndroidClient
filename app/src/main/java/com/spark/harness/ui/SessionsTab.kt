package com.spark.harness.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spark.harness.data.ServerConfig
import com.spark.harness.data.SessionSummary
import com.spark.harness.data.WorkspaceView
import com.spark.harness.net.HarnessApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsTab(
    server: ServerConfig,
    onOpen: (sessionId: String) -> Unit
) {
    val api = remember(server.baseUrl) { HarnessApiClient(server) }
    val scope = rememberCoroutineScope()

    var workspaces by remember { mutableStateOf<List<WorkspaceView>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    var selectedWorkspace by remember { mutableStateOf<String?>(null) } // null = 全部
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    var fabMenu by remember { mutableStateOf(false) }
    var newWorkspaceDialog by remember { mutableStateOf(false) }

    fun reload() {
        loading = true
        scope.launch {
            val ws = withContext(Dispatchers.IO) { api.call("workspace.list", JSONObject()) }
            if (ws is HarnessApiClient.RpcOutcome.Ok) {
                workspaces = parseWorkspaces((ws.value as? JSONObject)?.optJSONArray("items"))
            } else if (ws is HarnessApiClient.RpcOutcome.Err) error = ws.message

            val ss = withContext(Dispatchers.IO) { api.call("session.list", JSONObject()) }
            if (ss is HarnessApiClient.RpcOutcome.Ok) {
                sessions = parseSessions((ss.value as? JSONObject)?.optJSONArray("items"))
                if (error == null) error = null
            } else if (ss is HarnessApiClient.RpcOutcome.Err) error = ss.message
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    // 搜索（服务端 session.search，300ms 防抖）
    LaunchedEffect(query) {
        searchJob?.cancel()
        if (query.isBlank()) {
            searchResults = null
            return@LaunchedEffect
        }
        searchJob = scope.launch {
            delay(300)
            val r = withContext(Dispatchers.IO) {
                api.call("session.search", JSONObject().put("query", query))
            }
            if (r is HarnessApiClient.RpcOutcome.Ok) {
                val arr = (r.value as? JSONObject)?.optJSONArray("items") ?: JSONArray()
                val out = ArrayList<Pair<String, String>>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(o.optString("sessionId") to o.optString("snippet"))
                }
                searchResults = out
            }
        }
    }

    fun createSession() {
        scope.launch {
            val payload = JSONObject()
            selectedWorkspace?.let { payload.put("workspaceId", it) }
            val r = withContext(Dispatchers.IO) { api.call("session.create", payload) }
            if (r is HarnessApiClient.RpcOutcome.Ok) {
                val id = (r.value as? JSONObject)?.optString("sessionId")
                if (!id.isNullOrBlank()) onOpen(id)
            } else if (r is HarnessApiClient.RpcOutcome.Err) error = r.message
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会话") },
                actions = {
                    IconButton(onClick = { reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { fabMenu = true }) {
                    Icon(Icons.Default.Add, contentDescription = "新建")
                }
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (selectedWorkspace == null) "新建会话" else "当前工作区新建会话") },
                        onClick = { fabMenu = false; createSession() }
                    )
                    DropdownMenuItem(
                        text = { Text("新增工作区") },
                        onClick = { fabMenu = false; newWorkspaceDialog = true }
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("搜索会话…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedWorkspace == null,
                        onClick = { selectedWorkspace = null },
                        label = { Text("全部") }
                    )
                }
                items(workspaces, key = { it.id }) { ws ->
                    FilterChip(
                        selected = selectedWorkspace == ws.id,
                        onClick = { selectedWorkspace = ws.id },
                        label = { Text(ws.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null && sessions.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Text(
                            if (server.host == "127.0.0.1") "无法连接本地 Harness（VM）" else "连接失败",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            error!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        if (server.host == "127.0.0.1") {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "本地 Harness = Linux VM 隔离实例（端口 3090）。请确认：\n" +
                                    "1. VM 内已运行 dsh web --port 3090\n" +
                                    "2. VM 端口已转发到主机 127.0.0.1:3090\n" +
                                    "3. PC 端已运行 scripts\\dsh-adb-tunnel.ps1\n" +
                                    "详见 vm/README.md",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                searchResults != null -> SearchResults(searchResults!!, onOpen)
                else -> SessionGroupedList(
                    sessions = sessions,
                    workspaces = workspaces,
                    selectedWorkspace = selectedWorkspace,
                    onOpen = onOpen
                )
            }
        }
    }

    if (newWorkspaceDialog) {
        NewWorkspaceDialog(
            onDismiss = { newWorkspaceDialog = false },
            onCreate = { path ->
                newWorkspaceDialog = false
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        api.call("workspace.create", JSONObject().put("path", path))
                    }
                    if (r is HarnessApiClient.RpcOutcome.Err) error = r.message
                    reload()
                }
            }
        )
    }
}

@Composable
private fun SearchResults(results: List<Pair<String, String>>, onOpen: (String) -> Unit) {
    if (results.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("无匹配会话", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        items(results, key = { it.first }) { (id, snippet) ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(id) }) {
                Column(Modifier.padding(12.dp)) {
                    Text(id.take(8), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(snippet, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SessionGroupedList(
    sessions: List<SessionSummary>,
    workspaces: List<WorkspaceView>,
    selectedWorkspace: String?,
    onOpen: (String) -> Unit
) {
    val sessionById = sessions.associateBy { it.id }
    val wsBySession = HashMap<String, String>()
    workspaces.forEach { ws -> ws.sessionIds.forEach { wsBySession[it] = ws.id } }

    val visibleWs = if (selectedWorkspace == null) workspaces else workspaces.filter { it.id == selectedWorkspace }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        visibleWs.forEach { ws ->
            val ids = ws.sessionIds.mapNotNull { sessionById[it] }
            if (ids.isNotEmpty()) {
                item(key = "ws-" + ws.id) {
                    Text(
                        ws.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                items(ids, key = { it.id }) { s -> SessionRow(s, onOpen) }
            }
        }
        if (selectedWorkspace == null) {
            val ungrouped = sessions.filter { !wsBySession.containsKey(it.id) }
            if (ungrouped.isNotEmpty()) {
                item(key = "ungrouped") {
                    Text(
                        "未分组",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                items(ungrouped, key = { it.id }) { s -> SessionRow(s, onOpen) }
            }
        }
        if (sessions.isEmpty()) {
            item { Text("还没有会话，点右下角 + 新建", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun SessionRow(session: SessionSummary, onOpen: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(session.id) }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    session.title ?: session.cwd ?: "会话 ${session.id.take(8)}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    formatTime(session.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (session.running) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.width(14.dp).height(14.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun NewWorkspaceDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var path by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增工作区") },
        text = {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text("目录路径（须已存在）") },
                placeholder = { Text("例如 C:\\Users\\spark\\Desktop\\MyProject") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(path.trim()) }, enabled = path.isNotBlank()) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun parseWorkspaces(items: JSONArray?): List<WorkspaceView> {
    if (items == null) return emptyList()
    val out = ArrayList<WorkspaceView>(items.length())
    for (i in 0 until items.length()) {
        val o = items.optJSONObject(i) ?: continue
        val ids = o.optJSONArray("sessionIds")
        val sessionIds = ArrayList<String>(ids?.length() ?: 0)
        ids?.let { arr -> for (j in 0 until arr.length()) sessionIds.add(arr.optString(j)) }
        out.add(
            WorkspaceView(
                id = o.optString("workspaceId"),
                path = o.optString("path"),
                title = o.optString("title").ifBlank { o.optString("path").substringAfterLast('\\').ifBlank { o.optString("workspaceId") } },
                sessionIds = sessionIds
            )
        )
    }
    return out
}

private fun parseSessions(items: JSONArray?): List<SessionSummary> {
    if (items == null) return emptyList()
    val out = ArrayList<SessionSummary>(items.length())
    for (i in 0 until items.length()) {
        val o = items.optJSONObject(i) ?: continue
        if (o.optBoolean("blank", false)) continue // 隐藏空会话
        out.add(
            SessionSummary(
                id = o.optString("sessionId"),
                updatedAt = o.optLong("updatedAt", 0L),
                running = o.optBoolean("running", false),
                blank = false,
                cwd = o.optString("cwd").ifBlank { null },
                title = extractTitle(o.optJSONObject("projections"))
            )
        )
    }
    return out.sortedByDescending { it.updatedAt }
}

private fun formatTime(epochMs: Long): String {
    if (epochMs <= 0) return ""
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
}
