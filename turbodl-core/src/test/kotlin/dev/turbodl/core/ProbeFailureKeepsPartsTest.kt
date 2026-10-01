package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
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
 * 【⛔ 真实事故回归】**探测失败（无法取证）不得被当成"内容已变"而删掉旧分片。**
 *
 * ## 事故经过（2026-09-28，用户真机）
 *
 * 用户一个夸克分享任务暂停时留下 32 个分片 / 14.75MB 与校验器 `len=1447815647|weak`。
 * 约 24 小时后恢复下载 —— 夸克直链早已过期（`__puus` 约 3 小时），于是：
 *
 * 1. 探测拿到 403 → `ProbeResult(totalSize=null, etag=null, lastModified=null)`
 * 2. `probe.validator` 退化为 `"weak"`（只有 weak 标记，连 `len=` 都没有）
 * 3. 旧逻辑 `changed = prev.isNotEmpty() && prev != now` → `"len=…|weak" != "weak"` → true
 * 4. `discard = changed` → **删光全部分片**，用户 14.75MB 白下
 *
 * ## 错在哪
 *
 * `changed` 把两个不同的量混为一谈：
 *  - 「服务器确实换了文件」= 确证 → 该删；
 *  - 「探测没拿到信息」= 无法取证 → 绝不能删。
 *
 * 这是项目记忆里那条铁律的第三次复发：
 * > "无法取证" ≠ "已确证改变"：判定不要写成二值。
 *
 * ## 本测试守什么
 *
 * 1. 探测失败（服务器回 403）+ 有旧分片 → **分片必须全部保留**；
 * 2. 此时 `.validator` 不得被残缺值覆盖；
 * 3. 对照组：服务器正常且确实换了文件 → 仍应能正常重下（安全性不退化）。
 *
 * 文件路径均由固定常量推导，并经 [safeChild] 校验落在工作目录内。
 */
class ProbeFailureKeepsPartsTest {

    private lateinit var workDir: File

    @AfterTest
    fun teardown() {
        if (::workDir.isInitialized) workDir.deleteRecursively()
    }

    /**
     * 在 [parent] 下安全地取子路径：规范化后必须仍在 [parent] 内，否则抛错。
     * 本测试的所有路径都由常量推导，这里只是显式加固。
     */
    private fun safeChild(parent: File, name: String): File {
        val base = parent.canonicalFile
        val child = File(base, name).canonicalFile
        require(child.path == base.path || child.path.startsWith(base.path + File.separator)) {
            "路径逃出工作目录：$name"
        }
        return child
    }

    private class SwitchableServer(
        private val payload: ByteArray,
        private val fail403: Boolean,
        private val etag: String? = null,
    ) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        val requests = AtomicInteger(0)

        init {
            server.createContext("/f.bin") { ex: HttpExchange ->
                requests.incrementAndGet()
                if (fail403) {
                    val body = "expired link".toByteArray()
                    ex.sendResponseHeaders(403, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                    return@createContext
                }
                val size = payload.size
                etag?.let { ex.responseHeaders.add("ETag", it) }
                ex.responseHeaders.add("Content-Type", "application/octet-stream")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                val m = ex.requestHeaders.getFirst("Range")?.let {
                    Regex("bytes=(\\d+)-(\\d*)").find(it)
                }
                if (m == null) {
                    ex.sendResponseHeaders(200, size.toLong())
                    ex.responseBody.use { it.write(payload) }
                    return@createContext
                }
                val s = m.groupValues[1].toInt()
                val e = m.groupValues[2].toIntOrNull() ?: (size - 1)
                if (s >= size) { ex.sendResponseHeaders(416, -1); ex.close(); return@createContext }
                val end = minOf(e, size - 1)
                val len = end - s + 1
                ex.responseHeaders.add("Content-Range", "bytes $s-$end/$size")
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { it.write(payload, s, len) }
            }
            server.executor = Executors.newFixedThreadPool(8)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    private companion object {
        const val SIZE = 2 * 1024 * 1024
        const val BLOCK = 128 * 1024
        const val KEY = "probe-fail-task"
        const val PART_COUNT = 3
        const val VALIDATOR_FILE = ".validator"

        /** 分片文件名：与引擎一致 `seg_<start>_<end>.part`，数值由常量推导。 */
        fun partFileName(index: Int): String {
            val start = index.toLong() * BLOCK
            val end = start + BLOCK - 1
            return "seg_" + start.toString() + "_" + end.toString() + ".part"
        }
    }

    private fun newWorkDir(): File =
        File(System.getProperty("java.io.tmpdir"), "turbodl-probefail-" + System.nanoTime())

    /** 铺好旧分片与旧校验器；返回分片目录。 */
    private fun stage(payload: ByteArray, validator: String): File {
        workDir = newWorkDir()
        workDir.mkdirs()
        val chunkDir = safeChild(workDir, "key_$KEY").apply { mkdirs() }
        for (i in 0 until PART_COUNT) {
            val from = i * BLOCK
            val to = from + BLOCK
            safeChild(chunkDir, partFileName(i)).writeBytes(payload.copyOfRange(from, to))
        }
        safeChild(chunkDir, VALIDATOR_FILE).writeText(validator)
        return chunkDir
    }

    private fun countParts(chunkDir: File): Int =
        chunkDir.listFiles()?.count {
            it.isFile && it.name.startsWith("seg_") && it.name.endsWith(".part")
        } ?: 0

    private fun cfg(w: File) = TurboConfig(
        maxConnectionsPerTask = 4,
        maxConcurrentTasks = 1,
        warmUpConnections = false,
        slowStart = false,
        workDir = w,
    )

    @Test
    fun `probe failure must keep old parts and not overwrite validator`() = runBlocking {
        val payload = ByteArray(SIZE) { ((it * 31 + 7) % 256).toByte() }
        val oldValidator = "len=$SIZE|weak"
        val chunkDir = stage(payload, oldValidator)
        val before = countParts(chunkDir)
        assertEquals(PART_COUNT, before, "前置条件：应有 $PART_COUNT 个旧分片")

        // 服务器全程 403：模拟夸克直链过期
        val srv = SwitchableServer(payload, fail403 = true)
        val client = TurboClient(cfg(workDir))
        val out = File.createTempFile("probefail-out", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(
                DownloadRequest(
                    "http://127.0.0.1:${srv.port}/f.bin", out,
                    stableKey = KEY, knownSize = SIZE.toLong(),
                )
            )
            val res = client.await(id)
            // 任务失败是**预期**的（链接确实坏了）；关键是数据不能被删。
            println("[PROBE-FAIL] 任务结果 success=${res.isSuccess}（预期 false：链接已失效）")

            val after = countParts(chunkDir)
            println("[PROBE-FAIL] 旧分片：处理前=$before 处理后=$after")

            assertEquals(
                before, after,
                "⛔ 探测失败（无法取证）时绝不能删旧分片 —— " +
                    "这正是 2026-09-28 删掉用户 14.75MB 的那个缺陷",
            )

            val marker = safeChild(chunkDir, VALIDATOR_FILE)
            val validatorAfter = if (marker.isFile) marker.readText().trim() else ""
            assertEquals(
                oldValidator, validatorAfter,
                "⛔ 探测失败时不得用残缺令牌覆盖旧令牌 —— " +
                    "否则连「文件多大」这个已知事实都丢了，下次探测再失败又会误判 changed",
            )
        } finally {
            srv.stop()
            runCatching { client.shutdown() }
        }
    }

    @Test
    fun `control - genuine validator change still works`() = runBlocking {
        val payload = ByteArray(SIZE) { ((it * 13 + 5) % 256).toByte() }
        // 旧分片记的是旧 ETag；服务器现在返回不同的 ETag → 确证换了文件 → 应丢弃并重下
        val chunkDir = stage(payload, "len=$SIZE|etag=\"old-version\"|lm=Wed, 21 Oct 2026 07:28:00 GMT")
        assertEquals(PART_COUNT, countParts(chunkDir))

        val srv = SwitchableServer(payload, fail403 = false, etag = "\"new-version\"")
        val client = TurboClient(cfg(workDir))
        val out = File.createTempFile("probechange-out", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(
                DownloadRequest(
                    "http://127.0.0.1:${srv.port}/f.bin", out,
                    stableKey = KEY, knownSize = SIZE.toLong(),
                )
            )
            val res = client.await(id)
            println("[PROBE-CHANGE] success=${res.isSuccess}")

            // 对照组的价值：证明修复没有把安全性一起放宽 ——
            // 确证换了文件时，旧分片必须被丢弃，否则会合并出新旧混杂的损坏文件。
            assertTrue(res.isSuccess, "换了文件应能正常重下成功")
            assertTrue(
                out.readBytes().contentEquals(payload),
                "重下后内容必须逐字节一致",
            )
        } finally {
            srv.stop()
            runCatching { client.shutdown() }
        }
    }
}
