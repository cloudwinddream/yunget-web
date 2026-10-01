package dev.turbodl.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `If-Range` 行为测试：防止**中途换内容**时拼出新旧混杂的文件。
 *
 * ## 为什么这个测试重要
 *
 * 分片下载是"多个 HTTP 请求拼一个文件"。若下载途中源站换了内容
 * （网盘重新上传、CDN 回源到新版本），后续分片会取到**新版本**的字节，
 * 与已下的旧版本字节拼在一起 —— 得到一个混杂文件，
 * 而**长度校验仍会通过**（新旧大小常常一致），损坏静默落地。
 *
 * 本测试构造"下载中途换内容"的场景，验证：
 *  1. 请求确实带了 `If-Range`（否则保护形同虚设）
 *  2. 内容变更后服务器返回 200 整文件 → 引擎不会把新字节写进旧分片
 *  3. 最终产物要么是**一致的**某一版本，要么明确失败 —— 绝不允许混杂
 */
class IfRangeGuardTest {

    private lateinit var tmpDir: File
    private lateinit var server: HttpServer
    private var port = 0

    /** 版本 A / B：同长度、不同内容（模拟"换了同样大小的新文件"）。 */
    private val payloadA = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
    private val payloadB = ByteArray(4 * 1024 * 1024) { ((it * 7) % 241).toByte() }

    /** 当前生效的版本与它的 ETag。测试中途会切换。 */
    @Volatile
    private var current = payloadA
    @Volatile
    private var currentEtag = "\"v1\""

    private val ifRangeSeen = AtomicInteger(0)
    private val rangeRequests = AtomicInteger(0)
    private val servedWholeAfterChange = AtomicInteger(0)

    private fun subDir(name: String): File {
        require(!name.contains('/') && !name.contains('\\') && name != "..") { "非法子目录名：$name" }
        return File(tmpDir, name).apply { mkdirs() }
    }

    @BeforeTest
    fun setup() {
        tmpDir = java.nio.file.Files.createTempDirectory("turbodl-ifrange-").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/f.bin") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            val ifRange = ex.requestHeaders.getFirst("If-Range")
            if (!ifRange.isNullOrBlank()) ifRangeSeen.incrementAndGet()
            rangeRequests.incrementAndGet()

            val body = current
            val etag = currentEtag
            var s = 0
            var e = body.size - 1
            var honorRange = range != null
            if (range != null) {
                val m = Regex("bytes=(\\d+)-(\\d*)").find(range)
                if (m != null) {
                    s = m.groupValues[1].toInt()
                    e = m.groupValues[2].toIntOrNull() ?: (body.size - 1)
                }
                // 【核心语义】If-Range 不匹配 → 必须返回 200 整文件，而不是 206。
                // 这正是引擎用来发现"内容已变"的信号。
                if (!ifRange.isNullOrBlank() && ifRange != etag) {
                    honorRange = false
                    servedWholeAfterChange.incrementAndGet()
                }
            }
            val len = if (honorRange) (e - s + 1) else body.size
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            ex.responseHeaders.add("ETag", etag)
            if (honorRange && range != null) {
                ex.responseHeaders.add("Content-Range", "bytes $s-$e/${body.size}")
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { out -> out.write(body, s, len) }
            } else {
                ex.sendResponseHeaders(200, len.toLong())
                ex.responseBody.use { out -> out.write(body, 0, len) }
            }
        }
        server.executor = Executors.newFixedThreadPool(32)
        server.start()
        port = server.address.port
    }

    @AfterTest
    fun teardown() {
        server.stop(0)
        tmpDir.deleteRecursively()
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered().use { ins ->
            val b = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(b)
                if (n <= 0) break
                md.update(b, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun config(work: File, connections: Int = 8) = TurboConfig(
        maxConnectionsPerTask = connections,
        maxConcurrentTasks = 1,
        warmUpConnections = false,
        slowStart = false,
        workDir = work,
        segmentsPerConnection = 2,
    )

    // ---------------------------------------------------------------- 契约 1

    @Test
    fun `range requests carry If-Range when a strong validator exists`() = runBlocking {
        val work = subDir("w1")
        val out = File(tmpDir, "out1.bin")
        val client = TurboClient(config(work))
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:$port/f.bin", out))
            assertTrue(client.await(id).isSuccess, "下载应成功")
            assertTrue(
                ifRangeSeen.get() > 0,
                "分片请求必须带 If-Range（服务器提供了强 ETag）—— 否则中途换内容无法被发现",
            )
            assertEquals(sha256(payloadA), sha256(out), "未发生变更时应得到完整且正确的文件")
        } finally {
            client.shutdown()
        }
    }

    // ---------------------------------------------------------------- 契约 2

    @Test
    fun `content change mid download never yields a mixed file`() = runBlocking {
        val work = subDir("w2")
        val out = File(tmpDir, "out2.bin")
        val changed = AtomicBoolean(false)

        // 服务器侧：一旦有分片请求进来，立刻换成版本 B（模拟中途重新上传）
        val flip = Thread {
            while (!changed.get()) {
                if (rangeRequests.get() >= 1) {
                    current = payloadB
                    currentEtag = "\"v2\""
                    changed.set(true)
                    break
                }
                Thread.sleep(5)
            }
        }.apply { isDaemon = true; start() }

        val client = TurboClient(config(work))
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:$port/f.bin", out))
            val r = client.await(id)
            flip.interrupt()

            if (r.isSuccess) {
                // 成功的话，产物必须是**某一个完整版本**，绝不能是 A 与 B 的混合
                val got = sha256(out)
                val isA = got == sha256(payloadA)
                val isB = got == sha256(payloadB)
                assertTrue(
                    isA || isB,
                    "产物必须是单一版本（A 或 B）—— 新旧混杂说明 If-Range 未生效。" +
                        " 收到 If-Range=${ifRangeSeen.get()} 次，内容变更后返回整文件=" +
                        "${servedWholeAfterChange.get()} 次",
                )
            } else {
                // 失败也是可接受的（宁可失败也不产出错文件），但要确认确实观察到了变更
                assertTrue(
                    servedWholeAfterChange.get() > 0 || ifRangeSeen.get() > 0,
                    "失败时应能看出是内容变更导致（若完全没带 If-Range，就是保护缺失）",
                )
            }
        } finally {
            client.shutdown()
        }
    }
}
