package dev.turbodl.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DoH 自动择优的**真实网络**验证（默认跳过）。
 *
 * ## 为什么默认跳过
 *
 * 它会真的去连公共 DoH 服务器。放进默认套件会让「没网/被墙/服务器限流」
 * 直接变成构建失败 —— 那是在测网络，不是测代码。
 * 但它必须存在：报文编解码、择优逻辑**只有真连一次**才能确认端到端可用。
 *
 * 启用方式：
 * ```
 * TURBODL_REALNET=1 ./gradlew :turbodl-core:test --tests "*RealDohProbeTest*"
 * ```
 */
class RealDohProbeTest {

    private val enabled = System.getenv("TURBODL_REALNET") == "1"

    @Test
    fun probesAllEndpointsAndReportsLatency() {
        if (!enabled) {
            println("[REALNET] 跳过真实 DoH 探测（设置 TURBODL_REALNET=1 启用）")
            return
        }
        val results = TurboHttpClients.probeDohLatency()
        println("=== DoH 端点延迟实测 ===")
        results.forEach { (url, ms) ->
            println("  ${if (ms < 0) "不可用    " else "%4d ms".format(ms)}  $url")
        }
        val usable = results.filter { it.second >= 0 }
        assertTrue(usable.isNotEmpty(), "至少应有一个端点可用（否则本机网络到公共 DoH 全不通）")
        // 结果顺序必须与候选列表一致（界面按此顺序展示）
        assertEquals(
            DnsMode.DEFAULT_DOH_ENDPOINTS.size, results.size,
            "返回条目数应与候选端点数一致",
        )
        val fastest = usable.minByOrNull { it.second }!!
        println("最快：${fastest.first} (${fastest.second}ms)")
    }
}
