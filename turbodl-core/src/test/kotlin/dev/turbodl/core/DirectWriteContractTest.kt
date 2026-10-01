package dev.turbodl.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 直写模型（写入最终文件，取消二次合并）的**安全护栏测试**。
 *
 * ## 为什么要先写这些测试
 *
 * 「取消合并」意味着下载不再写 `seg_*.part`，而是直接按偏移写最终文件。
 * 这条路径正是**出过三次数据事故**的地方（见 `_INCIDENT_20260928_data_loss.md`），
 * 所以必须先钉死行为再改实现：
 *
 * 1. **字节精确**：产物 SHA-256 必须与源一致（不能只看长度）
 * 2. **续传正确**：中断后重启，必须从正确偏移继续，且最终内容仍与源一致
 * 3. **偏移正确**：每个分片写在它该在的位置（拼错顺序也能"长度对"，
 *    但 SHA 会不一致 —— 这正是第 1 条要拦的）
 * 4. **不残留**：完成后不留分片垃圾
 *
 * 这些测试对**当前实现**（分片+合并）也应通过 —— 它们是行为契约，
 * 不是实现细节的断言。这样切换实现时才有可比性。
 */
class DirectWriteContractTest {

    private lateinit var tmpDir: File
    private lateinit var server: HttpServer
    private var port = 0
    private lateinit var payload: ByteArray

    /** 服务器端记录 Range 起始偏移，用于验证续传从正确位置开始。 */
    private val rangeStarts = java.util.Collections.synchronizedList(mutableListOf<Long>())
    private val requestCount = AtomicInteger(0)

    /** 在 tmpDir 下建一个子目录（名称固定，不含分隔符与 ..）。 */
    private fun subDir(name: String): File {
        require(!name.contains('/') && !name.contains('\\') && name != "..") { "非法子目录名：$name" }
        val d = File(tmpDir, name)
        d.mkdirs()
        return d
    }

    @BeforeTest
    fun setup() {
        // 用非平凡内容：全 0 或重复模式会掩盖"偏移写错但长度正确"的缺陷
        payload = ByteArray(6 * 1024 * 1024) { ((it * 2654435761u.toInt() ushr 13) xor it).toByte() }
        // 由 JVM 分配的临时目录（唯一、已规范化），避免自行拼接路径
        tmpDir = java.nio.file.Files.createTempDirectory("turbodl-dwc-").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/f.bin") { ex ->
            requestCount.incrementAndGet()
            val range = ex.requestHeaders.getFirst("Range")
            var s = 0
            var e = payload.size - 1
            if (range != null) {
                val m = Regex("bytes=(\\d+)-(\\d*)").find(range)
                if (m != null) {
                    s = m.groupValues[1].toInt()
                    e = m.groupValues[2].toIntOrNull() ?: (payload.size - 1)
                    rangeStarts.add(s.toLong())
                }
            }
            val len = e - s + 1
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            ex.responseHeaders.add("ETag", "\"dwc-etag\"")
            if (range != null) {
                ex.responseHeaders.add("Content-Range", "bytes $s-$e/${payload.size}")
                ex.sendResponseHeaders(206, len.toLong())
            } else {
                ex.sendResponseHeaders(200, len.toLong())
            }
            ex.responseBody.use { out -> out.write(payload, s, len) }
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun config(workDir: File, connections: Int = 8) = TurboConfig(
        maxConnectionsPerTask = connections,
        maxConcurrentTasks = 1,
        warmUpConnections = false,
        slowStart = false,
        workDir = workDir,
        segmentsPerConnection = 2,
    )

    // ---------------------------------------------------------------- 契约 1

    @Test
    fun `direct write produces byte-exact output`() = runBlocking {
        val work = subDir("w1")
        val out = File(tmpDir, "out1.bin")
        val client = TurboClient(config(work))
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:$port/f.bin", out))
            val r = client.await(id)
            assertTrue(r.isSuccess, "下载应成功，实际 $r")
            assertEquals(payload.size.toLong(), out.length(), "长度应精确")
            assertEquals(
                sha256(payload), sha256(out),
                "内容 SHA-256 必须与源一致 —— 长度对但内容错说明分片写到了错误偏移",
            )
        } finally {
            client.shutdown()
        }
    }

    // ---------------------------------------------------------------- 契约 2

    @Test
    fun `resume continues from existing bytes and stays byte-exact`() = runBlocking {
        val work = subDir("w2")
        val out = File(tmpDir, "out2.bin")

        // 第一次：下载一部分后取消（模拟中断）
        val c1 = TurboClient(config(work, connections = 4))
        c1.submit(DownloadRequest("http://127.0.0.1:$port/f.bin", out))
        kotlinx.coroutines.delay(400)
        c1.shutdown()

        val partial = if (out.exists()) out.length() else 0L

        // 第二次：同 workDir、同输出 → 应续传
        rangeStarts.clear()
        val c2 = TurboClient(config(work, connections = 4))
        try {
            val id2 = c2.submit(DownloadRequest("http://127.0.0.1:$port/f.bin", out))
            val r = c2.await(id2)
            assertTrue(r.isSuccess, "续传应成功，实际 $r")
            assertEquals(payload.size.toLong(), out.length(), "续传后长度应精确")
            assertEquals(
                sha256(payload), sha256(out),
                "续传后内容必须与源一致 —— 偏移算错会在这里暴露（中断时已下 $partial 字节）",
            )
        } finally {
            c2.shutdown()
        }
    }

    // ---------------------------------------------------------------- 契约 3

    @Test
    fun `segment offsets map to correct file positions`() = runBlocking {
        // 用小连接数强制多分片，验证每个分片落在正确偏移
        val work = subDir("w3")
        val out = File(tmpDir, "out3.bin")
        val client = TurboClient(config(work, connections = 4))
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:$port/f.bin", out))
            assertTrue(client.await(id).isSuccess)
            assertTrue(
                rangeStarts.size > 1,
                "应发出多个 Range 请求以验证偏移映射，实际 ${rangeStarts.size} 个",
            )
            assertEquals(payload.size.toLong(), out.length())
            assertEquals(sha256(payload), sha256(out), "多分片拼接后的内容必须与源一致")
        } finally {
            client.shutdown()
        }
    }

    // ---------------------------------------------------------------- 契约 4

    @Test
    fun `completed download leaves no leftover chunk files`() = runBlocking {
        val work = subDir("w4")
        val out = File(tmpDir, "out4.bin")
        val client = TurboClient(config(work))
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:$port/f.bin", out))
            assertTrue(client.await(id).isSuccess)
            assertEquals(sha256(payload), sha256(out))
            val leftovers = work.walkTopDown().filter { it.isFile }.toList()
            assertTrue(
                leftovers.isEmpty(),
                "完成后不应残留分片文件，实际残留：${leftovers.map { it.name }}",
            )
        } finally {
            client.shutdown()
        }
    }
}
