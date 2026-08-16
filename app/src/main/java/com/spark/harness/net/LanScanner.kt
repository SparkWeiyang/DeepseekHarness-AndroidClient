package com.spark.harness.net

import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 局域网扫描：找到本机 IPv4 所属 /24 网段，并发探测指定端口。
 * 不依赖 WifiManager，因此无需额外的 WiFi 权限。
 */
object LanScanner {

    data class FoundHost(val ip: String, val port: Int)

    /** 返回本机主 IPv4（形如 192.168.1.23），无可用网络时返回 null。 */
    fun localIpv4(): String? = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .map { it.hostAddress }
            .firstOrNull { addr ->
                addr != null &&
                    addr.count { c -> c == '.' } == 3 &&
                    !addr.startsWith("127.")
            }
    }.getOrNull()

    /**
     * 扫描 [base].[1..254] 上开放的 [port]，边扫描边通过 [onFound] 回调（在工作线程触发，
     * 调用方需自行切回主线程更新 UI）。返回最终结果列表。
     */
    fun scan(
        base: String,
        port: Int,
        timeoutMs: Int = 350,
        onFound: (FoundHost) -> Unit = {}
    ): List<FoundHost> {
        val results = CopyOnWriteArrayList<FoundHost>()
        val pool = Executors.newFixedThreadPool(64)
        val futures = (1..254).map { i ->
            val ip = "$base.$i"
            pool.submit {
                if (isPortOpen(ip, port, timeoutMs)) {
                    val host = FoundHost(ip, port)
                    results.add(host)
                    onFound(host)
                }
            }
        }
        futures.forEach { it.get() }
        pool.shutdown()
        pool.awaitTermination(2, TimeUnit.SECONDS)
        return results.toList()
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            true
        }
    }.getOrDefault(false)
}
