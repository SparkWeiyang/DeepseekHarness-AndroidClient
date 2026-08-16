package com.spark.harness.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.spark.harness.data.ServerConfig

private data class TabDef(val label: String, val icon: ImageVector)

private val TABS = listOf(
    TabDef("会话", Icons.AutoMirrored.Filled.List),
    TabDef("详情", Icons.Default.Info),
    TabDef("设置", Icons.Default.Settings)
)

/** 连接一台服务器后的主界面：会话 / 详情 / 设置 三 Tab；打开会话时全屏聊天。 */
@Composable
fun MainShell(
    server: ServerConfig,
    onExit: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var chatSession by remember { mutableStateOf<String?>(null) }

    val session = chatSession
    if (session != null) {
        ChatScreen(server, session, onBack = { chatSession = null })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> SessionsTab(server, onOpen = { chatSession = it })
                1 -> DetailsTab(server, onOpen = { chatSession = it })
                2 -> SettingsTab(server, onSwitchServer = onExit)
            }
        }
    }
}
