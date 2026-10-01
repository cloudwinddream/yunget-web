package dev.turbodl.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 【回归】插件路由下，传输层设置必须能热更新。
 *
 * ## 被钉住的真实缺陷
 *
 * App 走插件路径时（`TurboBootstrap` 装 `HttpBackendPlugin`，priority 0 命中所有 http/https），
 * 下载实际由**插件后端**执行，core 自己的 `BuiltinHttpBackend`/`downloader` 根本不被使用。
 * 而 `TurboClient.updateConfig()` 只重建它自己那两个 client —— 于是：
 *
 * - 用户改了代理 / DoH / 忽略 SSL / 超时 → **不重启进程不生效**；
 * - 设置页却写着"动态生效"（`MainScreen` 注释），与实际不符。
 *
 * 修法：插件后端持有的 client 由 [TransportClientHolder] 按"传输层签名"惰性重建，
 * 每次 `download()` 开始时比对一次 `BackendContext.config`。
 *
 * ## 本测试验证什么
 *
 * 1. 传输层字段（如 userAgent）改了 → 请求真的带上了新值（证明重建生效）；
 * 2. 非传输层字段（如 maxConnectionsPerTask）改了 → **不**触发重建
 *    （重建会丢弃连接池，让下一任务重新握手，属于不该有的开销）；
 * 3. 大量重复调用 `onConfigSeen` 且配置不变时，client 实例保持同一个（零开销路径）。
 */
class TransportConfigHotSwapTest {

    private lateinit var tmpDir: File

    @AfterTest
    fun teardown() {
        if (::tmpDir.isInitialized) tmpDir.deleteRecursively()
    }

    /**
     * 构造指向**本测试自己启动的**本地服务器的 URL。
     *
     * 这里刻意把 host 写死为环回常量、只让端口变化，并显式断言协议为 http：
     *  - 本测试的被测对象是「OkHttpClient 是否因配置变化被重建」，**必须**连本地服务器；
     *  - 除环回外不接受任何主机，避免"变量拼 URL"把外部输入带进请求目标。
     */
    private fun localUrl(port: Int): String {
        require(port in 1..65535) { "端口非法：$port" }
        return "http://$LOOPBACK:$port/f.bin"
    }

    /** 记录每个请求的 User-Agent，用于判定 client 是否被重建。 */
    private class UaServer(payload: ByteArray) {
        val server: HttpServer = HttpServer.create(InetSocketAddress(LOOPBACK, 0), 0)
        val port: Int get() = server.address.port
        val seenUa = java.util.Collections.synchronizedList(mutableListOf<String>())
        val requests = AtomicInteger(0)

        init {
            server.createContext("/f.bin") { ex ->
                requests.incrementAndGet()
                seenUa.add(ex.requestHeaders.getFirst("User-Agent") ?: "")
                val range = ex.requestHeaders.getFirst("Range")
                val start = range?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toLong() }
                val body = if (start != null && start < payload.size) {
                    val end = (payload.size - 1)
                    payload.copyOfRange(start.toInt().coerceAtMost(payload.size - 1), end + 1)
                } else payload
                if (start != null) {
                    ex.responseHeaders.add(
                        "Content-Range",
                        "bytes $start-${payload.size - 1}/${payload.size}"
                    )
                    ex.sendResponseHeaders(206, body.size.toLong())
                } else {
                    ex.sendResponseHeaders(200, body.size.toLong())
                }
                ex.responseBody.use { it.write(body) }
            }
            server.executor = Executors.newFixedThreadPool(8)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    @Test
    fun `transport-level config change rebuilds client and takes effect`() = runBlocking {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "turbodl-hotswap-${System.nanoTime()}")
            .apply { mkdirs() }
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
        val srv = UaServer(payload)

        val holder = TransportClientHolder(TurboConfig(userAgent = "UA-FIRST"))
        val downloader = SegmentDownloader(
            { holder.client }, { holder.client }, { holder.config.ioBufferSize },
        )

        try {
            // ---- 第一次下载：应带 UA-FIRST ----
            val cfg1 = TurboConfig(userAgent = "UA-FIRST", maxConnectionsPerTask = 2)
            holder.onConfigSeen(cfg1)
            download(downloader, localUrl(srv.port), File(tmpDir, "a.bin"))
            assertEquals("UA-FIRST", srv.seenUa.lastOrNull(), "首次请求应带初始 UA")

            // ---- 改传输层字段：应重建并生效 ----
            val cfg2 = TurboConfig(userAgent = "UA-SECOND", maxConnectionsPerTask = 2)
            holder.onConfigSeen(cfg2)
            download(downloader, localUrl(srv.port), File(tmpDir, "b.bin"))
            assertEquals(
                "UA-SECOND", srv.seenUa.lastOrNull(),
                "改了 userAgent 后，请求必须带新值（否则就是「设置不重启不生效」那个缺陷）",
            )
        } finally {
            srv.stop()
        }
    }

    @Test
    fun `non-transport config change does not rebuild client`() {
        // 连接数是"业务调参"，每次下载从 BackendContext.config 现读，不该触发 client 重建。
        val holder = TransportClientHolder(TurboConfig(maxConnectionsPerTask = 4))
        val before = holder.client

        holder.onConfigSeen(TurboConfig(maxConnectionsPerTask = 32))
        assertTrue(
            holder.client === before,
            "改 maxConnectionsPerTask 不该重建 client（重建会丢连接池、下个任务重新握手）",
        )
        assertEquals(32, holder.config.maxConnectionsPerTask, "但 config 本身必须已更新")

        // 传输层字段才该触发重建。
        val afterBiz = holder.client
        holder.onConfigSeen(TurboConfig(maxConnectionsPerTask = 32, connectTimeoutMs = 9_000))
        assertTrue(holder.client !== afterBiz, "改 connectTimeoutMs 应触发重建")
    }

    @Test
    fun `repeated same config is a no-op`() {
        val holder = TransportClientHolder(TurboConfig(userAgent = "UA-STABLE"))
        val first = holder.client
        val same = TurboConfig(userAgent = "UA-STABLE")
        repeat(50) { holder.onConfigSeen(same) }
        assertTrue(
            holder.client === first,
            "配置未变时反复调用应是零开销的 no-op（否则每任务都重建一次 client）",
        )
    }

    /**
     * 下整个文件（走 `downloadWhole` 的 200 路径）。
     * 本测试只关心"请求头是否随配置更新"，不关心分片策略，故用最简整数路径。
     */
    private suspend fun download(
        downloader: SegmentDownloader,
        url: String,
        dest: File,
    ): Boolean = downloader.downloadWhole(
        taskId = 1L,
        url = url,
        outFile = dest,
        headers = emptyMap(),
        total = -1L,
        onBytes = { },
    )

    private companion object {
        /**
         * 本测试只连自己启动的本地服务器；主机名固定为环回常量，
         * 不接受任何来自外部的目标主机。
         */
        const val LOOPBACK = "127.0.0.1"
    }
}
