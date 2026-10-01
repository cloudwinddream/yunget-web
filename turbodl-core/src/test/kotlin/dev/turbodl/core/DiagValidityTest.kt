package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 【诊断工具自身的有效性测试】—— 防止"测量把工具自己的缺陷当成服务端特性"。
 *
 * ## 被这个测试钉住的真实事故
 *
 * 早期 `TurboDiagnostics` 永远跑满固定窗口、并用「字节数 ÷ 窗口时长」算速率。
 * 对一个**能在窗口内下完**的文件（实测 22.9MB 的 APK），字节数就等于文件大小，
 * 于是 **8/16/64/128 四个档位全部报出同一个 1.51 MB/s**，看起来像"服务端聚合限速"。
 * 我据此给出了两个**错误结论**（先"对并发有惩罚"，后"按 IP 聚合限速"）。
 *
 * 现在：
 *  - 下完即停，按**实际用时**算速率，并置 `completed = true`；
 *  - `interpret()` 只要看到有任何一档 `completed`，就**拒绝给结论**，提示改用更大文件。
 *
 * opt-in（`TURBODL_BENCH=1`）：它是测量台，不进默认套件。
 */
class DiagValidityTest {

    /**
     * 可限速的 Range 服务器。
     *
     * @param perResponseBytesPerSec 每条响应各自的速率（模拟"每连接限速"）
     * @param globalBytesPerSec 全服务器**合计**速率（模拟"按 IP/聚合限速"）
     */
    private class SlowServer(
        private val payload: ByteArray,
        private val perResponseBytesPerSec: Long,
        private val globalBytesPerSec: Long = Long.MAX_VALUE / 4,
    ) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        private val count = AtomicInteger(0)
        private val globalNextFreeNanos = java.util.concurrent.atomic.AtomicLong(0)
        val requests: Int get() = count.get()

        /** 合计限速：按 16KB 粒度把全服务器的发送时间轴排开。 */
        private fun paceGlobal(n: Int) {
            if (globalBytesPerSec > Long.MAX_VALUE / 8) return
            val gap = (n * 1_000_000_000.0 / globalBytesPerSec).toLong().coerceAtLeast(1)
            while (true) {
                val now = System.nanoTime()
                val next = globalNextFreeNanos.get()
                val base = if (next == 0L) now else next
                val slot = maxOf(base, now)
                if (globalNextFreeNanos.compareAndSet(next, slot + gap)) {
                    val waitMs = (slot - now) / 1_000_000
                    if (waitMs > 0) Thread.sleep(waitMs)
                    return
                }
            }
        }

        init {
            server.createContext("/f.bin") { ex: HttpExchange ->
                val size = payload.size
                val m = ex.requestHeaders.getFirst("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                val s = m?.groupValues?.get(1)?.toInt() ?: 0
                val e = m?.groupValues?.get(2)?.toIntOrNull() ?: (size - 1)
                if (s >= size) { ex.sendResponseHeaders(416, -1); ex.close(); return@createContext }
                val end = minOf(e, size - 1)
                val len = end - s + 1
                if (m == null) {
                    ex.sendResponseHeaders(200, size.toLong())
                    ex.responseBody.use { it.write(payload) }
                    return@createContext
                }
                count.incrementAndGet()
                ex.responseHeaders.add("Content-Range", "bytes $s-$end/$size")
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { out ->
                    var off = s
                    val chunk = 16 * 1024
                    while (off <= end) {
                        val n = minOf(chunk, end - off + 1)
                        paceGlobal(n)
                        out.write(payload, off, n)
                        off += n
                        if (perResponseBytesPerSec in 1..(Long.MAX_VALUE / 8)) {
                            val sleepMs = (n * 1000L) / perResponseBytesPerSec
                            if (sleepMs > 0) Thread.sleep(sleepMs)
                        }
                    }
                }
            }
            server.executor = Executors.newFixedThreadPool(64)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    private fun enable() = System.getenv("TURBODL_BENCH") == "1"

    @Test
    fun `small file finishing inside the window must be reported as invalid`() = runBlocking {
        if (!enable()) { println("[DIAG] 跳过（TURBODL_BENCH=1 启用）"); return@runBlocking }
        // 4MB 文件、不限速 → 一定会在 8 秒窗口内下完
        val payload = ByteArray(4 * 1024 * 1024) { ((it * 31 + 7) % 256).toByte() }
        val srv = SlowServer(payload, Long.MAX_VALUE / 2)
        val workDir = File(System.getProperty("java.io.tmpdir"), "diag-validity-1").apply { mkdirs() }
        try {
            val results = TurboDiagnostics.sweepConnections(
                url = "http://127.0.0.1:${srv.port}/f.bin",
                knownSize = payload.size.toLong(),
                tiers = listOf(8, 16),
                windowMs = 8_000,
                gapMs = 0,
                workDir = workDir,
            )
            println("[DIAG] 小文件：$results")
            assertTrue(results.all { it.completed }, "4MB 文件应在窗口内下完，每档都必须标记 completed")
            assertTrue(
                results.all { it.elapsedMs < 8_000 },
                "下完即停：用时必须小于窗口（否则又变成 文件大小÷固定窗口）"
            )
            val verdict = TurboDiagnostics.interpret(results)
            println("[DIAG] 判读=$verdict")
            assertTrue(
                verdict.contains("数据无效"),
                "窗口内下完时**必须拒绝给结论**，否则会伪装成「服务端聚合限速」。实际=$verdict"
            )
        } finally {
            srv.stop(); workDir.deleteRecursively()
        }
    }

    @Test
    fun `aggregate limited source is flat and not completed, so it is judged`() = runBlocking {
        if (!enable()) { println("[DIAG] 跳过（TURBODL_BENCH=1 启用）"); return@runBlocking }
        // 16MB，**全服务器合计** 400KB/s：无论几连接都下不完、吞吐都该是 ~0.4MB/s。
        // 这正是"按 IP/聚合限速"的形态，也是这个诊断工具要识别的真实场景。
        val payload = ByteArray(16 * 1024 * 1024) { ((it * 17 + 3) % 256).toByte() }
        val srv = SlowServer(payload, perResponseBytesPerSec = 0, globalBytesPerSec = 400 * 1024)
        val workDir = File(System.getProperty("java.io.tmpdir"), "diag-validity-2").apply { mkdirs() }
        try {
            val results = TurboDiagnostics.sweepConnections(
                url = "http://127.0.0.1:${srv.port}/f.bin",
                knownSize = payload.size.toLong(),
                tiers = listOf(8, 16),
                windowMs = 4_000,
                gapMs = 200,
                workDir = workDir,
            )
            println("[DIAG] 聚合限速：$results")
            assertTrue(results.none { it.completed }, "合计 400KB/s 时 16MB 不可能在 4 秒内下完")
            assertTrue(results.all { it.comparable }, "未下完且无错误的档位必须可比")
            val verdict = TurboDiagnostics.interpret(results)
            println("[DIAG] 判读=$verdict")
            assertTrue(!verdict.contains("数据无效"), "可比数据不应被判为无效。实际=$verdict")
            assertTrue(
                verdict.contains("加连接无收益"),
                "两档吞吐都 ~0.4MB/s（平坦）⇒ 必须判为「加连接无收益」而不是别的形状。实际=$verdict"
            )
        } finally {
            srv.stop(); workDir.deleteRecursively()
        }
    }

    @Test
    fun `per response limited source gains from more connections`() = runBlocking {
        if (!enable()) { println("[DIAG] 跳过（TURBODL_BENCH=1 启用）"); return@runBlocking }
        // 64MB @ 每响应 400KB/s：8 连接≈3.2MB/s、16 连接≈6.4MB/s，两者 4 秒内都下不完 → 可比。
        val payload = ByteArray(64 * 1024 * 1024) { ((it * 53 + 11) % 256).toByte() }
        val srv = SlowServer(payload, perResponseBytesPerSec = 400 * 1024)
        val workDir = File(System.getProperty("java.io.tmpdir"), "diag-validity-3").apply { mkdirs() }
        try {
            val results = TurboDiagnostics.sweepConnections(
                url = "http://127.0.0.1:${srv.port}/f.bin",
                knownSize = payload.size.toLong(),
                tiers = listOf(8, 16),
                windowMs = 4_000,
                gapMs = 200,
                workDir = workDir,
            )
            println("[DIAG] 每响应限速：$results")
            assertTrue(results.none { it.completed }, "64MB @400KB/s/响应 不该在 4 秒内下完")
            println(
                "[DIAG] 8 连接=%.2f MB/s  16 连接=%.2f MB/s".format(
                    results[0].mbPerSec, results[1].mbPerSec
                )
            )
            assertTrue(
                results[1].mbPerSec > results[0].mbPerSec * 1.3,
                "每响应限速：连接数翻倍应显著提速（否则形状判读的前提就站不住）"
            )
        } finally {
            srv.stop(); workDir.deleteRecursively()
        }
    }

    /**
     * 【纯单元】并发扫描的判读**不得再输出"限速按文件计 ⇒ 建议拆文件并行"**。
     *
     * 事故：该结论曾被送到用户面前，实测被证伪 —— 真正起作用的是**总连接数**，
     * 而总连接数在**单任务内**就能加满，拆文件并无额外收益。
     *
     * 根因是设计缺陷：`sweepConcurrentTasks` 固定"每任务连接数"，
     * 于是任务数增长时总连接数同步增长；在此设计下「按文件限速」与「每连接限速」
     * 预测完全相同，**单靠本扫描无法区分**。修法：判读必须同时对照总连接数增长。
     */
    @Test
    fun `concurrent verdict must not claim per-file throttling`() {
        // 场景：1×16=16 连接 → 3×16=48 连接，吞吐恰好跟着连接数涨（实测夸克就是这样）。
        val results = listOf(
            ConnectionTierResult(
                connections = 1, bytes = 2_267_451, elapsedMs = 15_200, peakConnections = 16,
                connectionsPerTask = 16, windowMs = 15_000,
            ),
            ConnectionTierResult(
                connections = 3, bytes = 7_289_175, elapsedMs = 15_100, peakConnections = 16,
                connectionsPerTask = 16, windowMs = 15_000,
            ),
        )
        val text = TurboDiagnostics.interpretConcurrent(results)
        println("[DIAG-UNIT] $text")

        assertTrue(
            !text.contains("按**文件**计") && !text.contains("拆成多个任务并行"),
            "判读不得再把「吞吐随任务数上升」断言为按文件限速/建议拆文件（已被实测证伪）",
        )
        assertTrue(
            text.contains("连接数"),
            "判读必须显式指出真正起作用的是总连接数",
        )
    }

    /** 【纯单元】连接数不变、只有任务数变时，判读要能识别出「涨的是连接」还是「真是聚合上限」。 */
    @Test
    fun `concurrent verdict distinguishes connection growth from aggregate cap`() {
        // (a) 吞吐与连接数同步增长 → 指出提速来自连接
        val grew = TurboDiagnostics.interpretConcurrent(
            listOf(
                ConnectionTierResult(1, 2_267_451, 15_200, 16, connectionsPerTask = 16, windowMs = 15_000),
                ConnectionTierResult(3, 7_289_175, 15_100, 16, connectionsPerTask = 16, windowMs = 15_000),
            )
        )
        // (b) 连接数翻了 3 倍但吞吐不动 → 聚合总量上限
        val flat = TurboDiagnostics.interpretConcurrent(
            listOf(
                ConnectionTierResult(1, 2_267_451, 15_200, 16, connectionsPerTask = 16, windowMs = 15_000),
                ConnectionTierResult(3, 2_300_000, 15_100, 16, connectionsPerTask = 16, windowMs = 15_000),
            )
        )
        println("[DIAG-UNIT] grew: $grew")
        println("[DIAG-UNIT] flat: $flat")
        assertTrue(grew.contains("更多连接"), "吞吐随连接数涨时应指出提速来自连接")
        assertTrue(flat.contains("IP/账户"), "连接数涨而吞吐不动时应判为聚合总量上限")
    }

    /** 【纯单元】有档位在窗口内下完时，判读必须拒绝给结论。 */
    @Test
    fun `concurrent verdict refuses when a tier completed early`() {
        val text = TurboDiagnostics.interpretConcurrent(
            listOf(
                ConnectionTierResult(1, 4_194_304, 400, 16, connectionsPerTask = 16,
                    completed = true, windowMs = 15_000),
                ConnectionTierResult(3, 12_582_912, 1_200, 16, connectionsPerTask = 16,
                    completed = true, windowMs = 15_000),
            )
        )
        println("[DIAG-UNIT] $text")
        assertTrue(text.contains("数据无效"), "有档位提前下完就必须拒绝下结论")
        assertTrue(!text.contains("更多连接"), "无效数据不得给出任何形状结论")
    }
}
