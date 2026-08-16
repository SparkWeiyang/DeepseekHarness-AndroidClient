package com.spark.harness.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** 用 SharedPreferences + org.json 持久化服务器列表（零额外依赖）。 */
class ServerRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<ServerConfig> {
        val raw = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { ServerConfig.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun save(servers: List<ServerConfig>) {
        val arr = JSONArray()
        servers.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "harness_servers"
        private const val KEY_SERVERS = "server_list"
    }
}
