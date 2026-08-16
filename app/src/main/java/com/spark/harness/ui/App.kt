package com.spark.harness.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import com.spark.harness.data.ServerConfig
import com.spark.harness.data.ServerRepository
import java.util.UUID

sealed interface Screen {
    data object List : Screen
    data class Edit(val serverId: String?) : Screen
    data class Shell(val server: ServerConfig) : Screen
}

@Composable
fun HarnessApp(repository: ServerRepository) {
    var servers by remember { mutableStateOf(repository.load()) }
    var screen by remember {
        mutableStateOf<Screen>(
            servers.firstOrNull { it.isDefault }
                ?.let { Screen.Shell(it) }
                ?: Screen.List
        )
    }

    fun persistAndRefresh(list: List<ServerConfig>) {
        repository.save(list)
        servers = list
    }

    fun ensureSingleDefault(list: List<ServerConfig>): List<ServerConfig> {
        val hasDefault = list.any { it.isDefault }
        return if (hasDefault) {
            var seen = false
            list.map {
                if (it.isDefault) {
                    if (!seen) { seen = true; it } else it.copy(isDefault = false)
                } else it
            }
        } else if (list.isNotEmpty()) {
            list.mapIndexed { i, c -> if (i == 0) c.copy(isDefault = true) else c }
        } else list
    }

    when (val s = screen) {
        is Screen.List -> ServerListScreen(
            servers = servers,
            onAdd = { screen = Screen.Edit(null) },
            onEdit = { cfg -> screen = Screen.Edit(cfg.id) },
            onConnect = { cfg -> screen = Screen.Shell(cfg) },
            onDelete = { id -> persistAndRefresh(ensureSingleDefault(servers.filterNot { it.id == id })) },
            onSetDefault = { id ->
                persistAndRefresh(servers.map { it.copy(isDefault = it.id == id) })
            },
            onScanPick = { host, port ->
                val cfg = ServerConfig(
                    id = UUID.randomUUID().toString(),
                    name = host,
                    host = host,
                    port = port,
                    https = false,
                    isDefault = servers.isEmpty()
                )
                persistAndRefresh(servers + cfg)
                screen = Screen.Edit(cfg.id)
            },
            onAddLocal = {
                val existing = servers.firstOrNull { it.host == "127.0.0.1" && it.port == 3080 }
                if (existing != null) {
                    screen = Screen.Shell(existing)
                } else {
                    val cfg = ServerConfig(
                        id = UUID.randomUUID().toString(),
                        name = "本机 Harness",
                        host = "127.0.0.1",
                        port = 3080,
                        https = false,
                        isDefault = servers.isEmpty()
                    )
                    persistAndRefresh(ensureSingleDefault(servers + cfg))
                    screen = Screen.Shell(cfg)
                }
            }
        )

        is Screen.Edit -> ServerEditScreen(
            existing = servers.firstOrNull { it.id == s.serverId },
            onSave = { cfg ->
                val list = if (s.serverId == null) {
                    servers + cfg
                } else {
                    servers.map { if (it.id == s.serverId) cfg else it }
                }
                persistAndRefresh(ensureSingleDefault(list))
                screen = Screen.List
            },
            onCancel = { screen = Screen.List }
        )

        is Screen.Shell -> MainShell(
            server = s.server,
            onExit = { screen = Screen.List }
        )
    }
}
