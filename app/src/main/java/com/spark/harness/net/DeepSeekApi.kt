package com.spark.harness.net

import org.json.JSONObject

/**
 * DeepSeek 官方账户余额。
 * 通过 PC harness 的 `deepseek/balance` 端点查询（dsh-lan 插件提供），
 * PC 端用自己的 DEEPSEEK_API_KEY 凭证调官方接口，密钥永不出 PC。
 */
object DeepSeekApi {

    data class Balance(
        val currency: String,
        val total: Double,
        val granted: Double,
        val toppedUp: Double,
        val available: Boolean
    ) {
        /** 已消耗 ≈ 赠金 + 充值 − 剩余（估算）。 */
        val consumed: Double get() = (granted + toppedUp - total).coerceAtLeast(0.0)
    }

    suspend fun fetchBalance(api: HarnessApiClient): Balance? {
        // Typert 端点信封：payload 必须是 { args: {...} }（与 legacy 方法的 payload 直传不同）
        val payload = JSONObject().put("args", JSONObject())
        return when (val r = api.call("deepseek/balance", payload)) {
            is HarnessApiClient.RpcOutcome.Ok -> parse(r.value as? JSONObject)
            is HarnessApiClient.RpcOutcome.Err -> null
        }
    }

    private fun parse(root: JSONObject?): Balance? {
        if (root == null) return null
        val info = root.optJSONArray("balance_infos")?.optJSONObject(0) ?: return null
        return Balance(
            currency = info.optString("currency", ""),
            total = info.optString("total_balance").toDoubleOrNull() ?: 0.0,
            granted = info.optString("granted_balance").toDoubleOrNull() ?: 0.0,
            toppedUp = info.optString("topped_up_balance").toDoubleOrNull() ?: 0.0,
            available = root.optBoolean("is_available", false)
        )
    }
}
