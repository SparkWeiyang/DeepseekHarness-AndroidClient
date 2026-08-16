package com.spark.harness.net

import android.util.Log
import com.spark.harness.data.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * DSH Web 的 RPC 传输层：
 * - unary 调用 = HTTP POST /api/<method>（JSON 信封，rpcId 关联）
 * - 下行流 = WebSocket /api/events.mux（只下行，ServerRequest 帧）
 */
class HarnessApiClient(private val server: ServerConfig) {

    private val base = server.baseUrl.trimEnd('/')
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    sealed interface RpcOutcome {
        data class Ok(val value: Any?) : RpcOutcome
        data class Err(val code: String, val message: String) : RpcOutcome
    }

    /** 发起一次 unary RPC，返回成功值或业务/传输错误。 */
    suspend fun call(method: String, payload: JSONObject? = null): RpcOutcome {
        val body = JSONObject().apply {
            put("type", "client-request")
            put("rpcId", UUID.randomUUID().toString())
            put("method", method)
            put("payload", payload ?: JSONObject())
        }
        val req = Request.Builder()
            .url("$base/api/$method")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { resp -> parse(resp) }
            } catch (e: Exception) {
                RpcOutcome.Err("internal", e.message ?: "network error")
            }
        }
    }

    private fun parse(resp: Response): RpcOutcome {
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            return RpcOutcome.Err("http-${resp.code}", "HTTP ${resp.code}")
        }
        return try {
            val root = JSONObject(text)
            val result = root.optJSONObject("result")
                ?: return RpcOutcome.Err("internal", "missing result in response")
            if (result.optBoolean("ok", false)) {
                RpcOutcome.Ok(result.opt("value"))
            } else {
                val error = result.optJSONObject("error")
                RpcOutcome.Err(
                    error?.optString("code") ?: "internal",
                    error?.optString("message") ?: "unknown error"
                )
            }
        } catch (e: Exception) {
            RpcOutcome.Err("internal", "bad response: ${e.message}")
        }
    }

    enum class MuxState { OPEN, FAILED, CLOSED }

    /** 连接 mux 下行流；每个 ServerRequest 帧回调 onFrame(method, payload)。 */
    fun connectMux(
        onFrame: (method: String, payload: JSONObject) -> Unit,
        onState: (MuxState) -> Unit = {}
    ): WebSocket {
        val wsUrl = base.replaceFirst("http", "ws") + "/api/events.mux"
        val req = Request.Builder().url(wsUrl).build()
        return client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onState(MuxState.OPEN)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    val method = root.optString("method")
                    val payload = root.optJSONObject("payload") ?: JSONObject()
                    onFrame(method, payload)
                } catch (e: Exception) {
                    Log.w("Harness", "bad mux frame: $e")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("Harness", "mux failure: ${t.message}")
                onState(MuxState.FAILED)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onState(MuxState.CLOSED)
            }
        })
    }
}
