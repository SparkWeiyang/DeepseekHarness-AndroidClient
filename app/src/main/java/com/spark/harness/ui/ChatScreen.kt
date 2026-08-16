package com.spark.harness.ui

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spark.harness.data.ChatItem
import com.spark.harness.data.ModelGroup
import com.spark.harness.data.ModelSelection
import com.spark.harness.data.ServerConfig
import com.spark.harness.data.SkillEntry
import kotlinx.coroutines.launch

private val BUILTIN_COMMANDS = listOf(
    "/goal" to "创建/管理长期目标",
    "/compact" to "压缩会话上下文",
    "/feedback" to "发送反馈"
)

private fun permLabel(preset: String?): String = when (preset) {
    "read-only" -> "只读"
    "workspace-write" -> "工作区可写"
    "danger-full-access" -> "完全访问"
    else -> "安全程度"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    server: ServerConfig,
    sessionId: String,
    onBack: () -> Unit
) {
    val controller = remember(server.baseUrl, sessionId) { ChatController(server, sessionId) }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    var menuOpen by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showPermDialog by remember { mutableStateOf(false) }
    var showCmdDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { controller.start() }
    DisposableEffect(Unit) { onDispose { controller.close() } }

    // 自动滚动到底部
    LaunchedEffect(controller.items.size, controller.currentTool) {
        if (controller.items.isNotEmpty()) {
            listState.scrollToItem(controller.items.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            controller.title ?: sessionId.take(8),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        controller.error?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (controller.running) {
                        IconButton(onClick = { controller.cancel() }) {
                            Icon(Icons.Default.Close, contentDescription = "停止")
                        }
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("切换模型") },
                            onClick = { menuOpen = false; showModelDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("安全程度") },
                            onClick = { menuOpen = false; showPermDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("命令") },
                            onClick = { menuOpen = false; showCmdDialog = true }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (controller.loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (controller.items.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("发送一条消息开始", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        itemsIndexed(controller.items) { _, item ->
                            ChatItemView(item, controller.running)
                        }
                    }
                }
            }
            Composer(
                controller = controller,
                value = input,
                onValueChange = { input = it },
                onSend = {
                    val text = input
                    input = ""
                    controller.send(text)
                },
                onOpenCommands = { showCmdDialog = true },
                onOpenModels = { showModelDialog = true },
                onOpenPerms = { showPermDialog = true }
            )
        }
    }

    if (showModelDialog) {
        ModelDialog(controller, onDismiss = { showModelDialog = false })
    }
    if (showPermDialog) {
        PermissionDialog(controller, onDismiss = { showPermDialog = false })
    }
    if (showCmdDialog) {
        CommandDialog(
            controller = controller,
            onDismiss = { showCmdDialog = false },
            onPick = { cmd -> input = (input + " " + cmd).trimStart() }
        )
    }
}

// ── 对话框 ─────────────────────────────────────────────────────

@Composable
private fun ModelDialog(controller: ChatController, onDismiss: () -> Unit) {
    var data by remember { mutableStateOf<Pair<ModelSelection, List<ModelGroup>>?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        data = controller.loadModels()
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换模型") },
        text = {
            when {
                loading -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> {
                    val d = data
                    if (d == null || d.second.isEmpty()) {
                        Text("无可用模型目录")
                    } else {
                        val (current, groups) = d
                        LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            groups.forEach { g ->
                                item(key = g.id) {
                                    Text(
                                        g.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                                    )
                                }
                                items(g.models, key = { g.id + "/" + it.id }) { m ->
                                    val selected = current.provider == g.id && current.model == m.id
                                    Row(
                                        Modifier.fillMaxWidth().clickable {
                                            controller.selectModel(g.id, m.id, null)
                                            onDismiss()
                                        },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(selected = selected, onClick = null)
                                        Column {
                                            Text(m.name, style = MaterialTheme.typography.bodyMedium)
                                            m.description?.let {
                                                Text(
                                                    it,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun PermissionDialog(controller: ChatController, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("安全程度") },
        text = {
            Column {
                listOf(
                    "read-only" to "只读（禁止写入）",
                    "workspace-write" to "工作区可写",
                    "danger-full-access" to "完全访问（危险）"
                ).forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            controller.setPermissionPreset(value)
                            onDismiss()
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = controller.permPreset == value, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun CommandDialog(
    controller: ChatController,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    var skills by remember { mutableStateOf<List<SkillEntry>?>(null) }
    LaunchedEffect(Unit) { skills = controller.loadSkills() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("命令") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Text(
                        "内置命令",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                items(BUILTIN_COMMANDS, key = { it.first }) { (name, desc) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(name); onDismiss() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(desc, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val list = skills
                if (list != null && list.isNotEmpty()) {
                    item {
                        Text(
                            "技能",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                        )
                    }
                    items(list, key = { it.name }) { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick("/" + s.name); onDismiss() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("/" + s.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                            Text(s.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

// ── 消息渲染 ────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatItemView(item: ChatItem, running: Boolean) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val copy = { text: String ->
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", text))) }
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }
    when (item) {
        is ChatItem.User -> {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
                    modifier = Modifier.widthIn(max = 560.dp)
                        .combinedClickable(onClick = {}, onLongClick = { copy(item.text) })
                ) {
                    Text(item.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
        is ChatItem.Context -> {
            var expanded by remember(item.text) { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                Column(Modifier.widthIn(max = 720.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { expanded = !expanded },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "上下文注入",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            item.text.lines().firstOrNull()?.take(24) ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (expanded) {
                        Text(
                            item.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 22.dp, top = 2.dp)
                                .heightIn(max = 160.dp)
                        )
                    }
                }
            }
        }
        is ChatItem.Assistant -> {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Column(Modifier.widthIn(max = 760.dp)) {
                    if (!item.reasoning.isNullOrBlank()) {
                        ReasoningBlock(item.reasoning, item.streaming)
                    }
                    Box(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = {}, onLongClick = { copy(item.text) })
                    ) {
                        MarkdownText(item.text)
                    }
                    item.usage?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (item.streaming) {
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.width(14.dp).height(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("生成中…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        is ChatItem.Tool -> {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                ToolCard(item, running)
            }
        }
    }
}

@Composable
private fun ReasoningBlock(reasoning: String, streaming: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val summary = if (streaming) {
        reasoning.lines().lastOrNull { it.isNotBlank() } ?: ""
    } else {
        reasoning.lines().firstOrNull { it.isNotBlank() } ?: ""
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "思考",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                if (!expanded) {
                    Text(
                        summary.take(40),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Text(reasoning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun ToolCard(item: ChatItem.Tool, running: Boolean) {
    var expanded by remember(item.callId) { mutableStateOf(false) }
    val executing = item.result == null && running
    val done = item.result != null
    Card(
        modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    executing -> {
                        CircularProgressIndicator(Modifier.width(14.dp).height(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("执行中", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    done && item.isError -> Text("✗", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    done -> Text("✓", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    else -> Text("○", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "⚙ ${item.name}",
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { expanded = !expanded }
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                if (item.args.isNotBlank()) {
                    Text("参数", style = MaterialTheme.typography.labelSmall)
                    Text(
                        item.args,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (item.result != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (item.isError) "错误" else "结果",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        item.result,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 24,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

// ── 输入区（对标网页端 composer bar） ───────────────────────────

@Composable
private fun Composer(
    controller: ChatController,
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onOpenCommands: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenPerms: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            // 统计条（粘在输入区）
            controller.statsText?.let { stats ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stats,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    controller.pressureFraction?.let { frac ->
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .width(60.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(frac)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (frac > 0.8f) Color(0xFFE53935) else Color(0xFF4CAF50))
                            )
                        }
                    }
                }
            }

            // 运行状态 / 排队
            if (controller.running) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    CircularProgressIndicator(Modifier.width(12.dp).height(12.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        controller.currentTool?.let { "⚙ 正在执行: $it" } ?: "正在生成…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (controller.queueCount > 0) {
                Text(
                    "${controller.queueCount} 条排队消息",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // 输入行
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onOpenCommands) {
                    Icon(Icons.Default.Add, contentDescription = "命令")
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("发消息给 Harness…") },
                    maxLines = 6
                )
                Spacer(Modifier.width(4.dp))
                if (controller.running) {
                    IconButton(onClick = { controller.cancel() }) {
                        Icon(Icons.Default.Close, contentDescription = "停止")
                    }
                } else {
                    IconButton(onClick = onSend, enabled = value.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                    }
                }
            }

            // 模型 / 权限 chips
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onOpenModels,
                    label = { Text(controller.currentModelName ?: "模型", maxLines = 1) }
                )
                AssistChip(
                    onClick = onOpenPerms,
                    label = { Text(permLabel(controller.permPreset), maxLines = 1) }
                )
            }
        }
    }
}
