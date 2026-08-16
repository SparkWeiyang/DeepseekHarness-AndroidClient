package com.spark.harness.data

import org.json.JSONObject

/** 一台 PC harness 服务器的连接配置。 */
data class ServerConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val https: Boolean = false,
    val isDefault: Boolean = false
) {
    val baseUrl: String
        get() {
            val scheme = if (https) "https" else "http"
            return "$scheme://$host:$port"
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("https", https)
        put("default", isDefault)
    }

    companion object {
        fun fromJson(o: JSONObject): ServerConfig = ServerConfig(
            id = o.optString("id"),
            name = o.optString("name"),
            host = o.optString("host"),
            port = o.optInt("port", 3080),
            https = o.optBoolean("https", false),
            isDefault = o.optBoolean("default", false)
        )
    }
}
