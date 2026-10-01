package dev.turbodl.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * 分片合并与「下载中预先合并」。
 *
 * 本文件有两块能力：
 *  1. [PartMerger.merge] —— 传统的收尾合并（并行版）
 *  2. [OverlapMerger]    —— 下载过程中把完成的分片提前写进临时文件，摊薄收尾开销
 */
internal object PartMerger {

    /** 并行度上限：再高也只是抢同一个磁盘队列。 */
    private const val MAX_THREADS = 8

    /** 单线程处理的字节数下限：低于此值不值得并行。 */
    private const val MIN_BYTES_PER_THREAD = 8L * 1024 * 1024

    /**
     * 合并 [parts] 到 [target]。
     *
     * @param onProgress 可选进度回调：(已合并字节, 总字节)。
     * @param parallel 是否并行（默认并行；小文件/单核场景可关掉）
     * @param offsets 每个分片在目标文件中的起始偏移（与 [parts] 一一对应）。
     *   为 null 时按**累加**推导（等价于顺序拼接）。
     *
     * 【为什么允许显式偏移】收尾托管会让在飞分片提前收工，其文件长度可能超过让出点，
     * 与接手的分片**重叠**。显式偏移让重叠区间落到同一位置 → 写入幂等、结果仍正确；
     * 若按累加拼接，重叠会被算两次（长度校验失败）。
     */
    fun merge(
        parts: List<File>,
        target: File,
        onProgress: ((merged: Long, total: Long) -> Unit)? = null,
        parallel: Boolean = true,
        offsets: List<Long>? = null,
    ): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val hasOffsets = offsets != null && offsets.size == parts.size

        if (parts.size <= 1) {
            val ok = mergeSequential(parts, target, onProgress, offsets)
            if (ok) {
                val n = if (hasOffsets) offsets!!.maxOf { it } + parts[0].length() else parts[0].length()
                onProgress?.invoke(n, n)
            }
            return@runCatching ok
        }

        val spans = ArrayList<Pair<File, Long>>(parts.size)
        if (hasOffsets) {
            for (i in parts.indices) spans.add(parts[i] to offsets!![i])
        } else {
            var off = 0L
            for (p in parts) {
                spans.add(p to off)
                off += p.length()
            }
        }
        val totalBytes = if (hasOffsets) {
            spans.maxOf { (f, o) -> o + f.length() }
        } else {
            parts.sumOf { it.length() }
        }

        val threads = if (!parallel) 1 else {
            val byBytes = (totalBytes / MIN_BYTES_PER_THREAD).toInt().coerceAtLeast(1)
            minOf(MAX_THREADS, byBytes, parts.size)
        }

        val merged = if (threads <= 1) {
            mergeSequential(parts, target, onProgress, offsets)
            totalBytes
        } else {
            mergeParallel(target, spans, totalBytes, threads, onProgress)
        }
        merged >= totalBytes
    }.getOrDefault(false)

    /** 顺序合并（单线程回退路径）。 */
    private fun mergeSequential(
        parts: List<File>,
        target: File,
        onProgress: ((Long, Long) -> Unit)?,
        offsets: List<Long>? = null,
    ): Boolean = runCatching {
        val hasOffsets = offsets != null && offsets.size == parts.size
        val totalBytes = if (hasOffsets) {
            parts.indices.maxOf { offsets!![it] + parts[it].length() }
        } else {
            parts.sumOf { it.length() }
        }
        var merged = 0L
        RandomAccessFile(target, "rw").use { raf ->
            if (hasOffsets) raf.setLength(totalBytes)
            for (i in parts.indices) {
                val part = parts[i]
                if (hasOffsets) raf.seek(offsets!![i])
                FileInputStream(part).use { fis ->
                    fis.channel.use { inCh ->
                        val outCh = raf.channel
                        var pos = 0L
                        val size = inCh.size()
                        while (pos < size) {
                            val n = inCh.transferTo(pos, size - pos, outCh)
                            if (n <= 0) break
                            pos += n
                            merged += n
                            onProgress?.invoke(merged, totalBytes)
                        }
                    }
                }
            }
        }
        true
    }.getOrDefault(false)

    /** 并行合并：预分配目标文件，多线程各领一批分片按偏移直写。 */
    private fun mergeParallel(
        target: File,
        spans: List<Pair<File, Long>>,
        totalBytes: Long,
        threads: Int,
        onProgress: ((Long, Long) -> Unit)?,
    ): Long = runBlocking {
        RandomAccessFile(target, "rw").use { it.setLength(totalBytes) }

        val progress = java.util.concurrent.atomic.AtomicLong(0)
        val lastReport = java.util.concurrent.atomic.AtomicLong(0)
        val chunks = spans.chunked((spans.size + threads - 1) / threads)

        chunks.map { group ->
            async(Dispatchers.IO) {
                var written = 0L
                RandomAccessFile(target, "rw").use { raf ->
                    for ((part, offset) in group) {
                        raf.seek(offset)
                        FileInputStream(part).use { fis ->
                            fis.channel.use { inCh ->
                                val outCh = raf.channel
                                var pos = 0L
                                val size = inCh.size()
                                while (pos < size) {
                                    val n = inCh.transferTo(pos, size - pos, outCh)
                                    if (n <= 0) break
                                    pos += n
                                    written += n
                                    progress.addAndGet(n)
                                }
                            }
                        }
                        onProgress?.let { cb ->
                            val now = System.currentTimeMillis()
                            val last = lastReport.get()
                            if (now - last >= 200 && lastReport.compareAndSet(last, now)) {
                                cb(progress.get(), totalBytes)
                            }
                        }
                    }
                }
                written
            }
        }.awaitAll().sum()
    }
}

/**
 * 重叠合并器：**在下载过程中**把完成的分片写进临时文件，把合并 I/O
 * 摊到网络等待里，而不是全部堆在收尾。
 *
 * ## 为什么（2026-09-29 实测）
 *
 * 512MB / 64 连接 / 每连接 4MB/s：
 *
 * | 段 | 耗时 |
 * |---|---|
 * | 下载（网络瓶颈，磁盘空闲） | 2.54s |
 * | 合并（收尾，磁盘饱和） | 1.58s |
 *
 * 合并贵是因为它发生在**下载刚结束**：页缓存堆着 512MB 脏页待回写，
 * 紧接着又读 512MB + 写 512MB。单测佐证：冷合并 254 MB/s，刷盘后 1087 MB/s。
 *
 * 而下载阶段网络是瓶颈（约 200 MB/s），磁盘能跑 800+ MB/s —— 此时磁盘是闲的。
 * 把合并提前到这里，开销就能藏进网络等待。
 *
 * ## 为什么不是"直接写最终路径"（aria2-next 的做法）
 *
 * 那需要重写续传判定（分片文件是当前的进度凭证），而这条路径出过三次数据事故。
 * 本类**不改动任何数据布局与续传格式**：分片文件照旧，只是写入时机提前，
 * 且中间产物落在 `*.merging` 临时文件上，最终路径只在收尾时 rename 占位
 * （同文件系统 rename ≈ 0ms），保留"未完成不在目标位置留半成品"的既有保证。
 *
 * ## 正确性：按绝对偏移写，无需等待顺序
 *
 * 每个分片在文件中的位置就是它文件名里的 `start` —— 精确、无歧义。
 * 因此**任何完成的分片都可以立刻写**，不必等前面的分片先写完
 * （首版要求"严格连续才写"，结果慢分片会卡住整条链：实测只写进 94MB/512MB）。
 *
 * 收尾托管造成的区间**重叠**也因此天然安全：重叠部分落到同一偏移、
 * 内容相同 → 写入幂等。
 *
 * [writtenBytes] 只用于统计"已写入多少"（进度展示），不参与写入决策。
 */
internal class OverlapMerger(private val dest: File) {

    /** 中间产物（与目标同目录 → 收尾可原子 rename）。 */
    val tempFile: File = File(dest.parentFile, dest.name + ".merging")

    /** 已写入临时文件的分片名。 */
    private val written = HashSet<String>()

    /** 已写入的区间 `[start, start+len)`，用于收尾做**完整覆盖**判定。 */
    private val spans = ArrayList<Pair<Long, Long>>()

    /** 已写入的字节总数（含重叠部分重复计数，仅用于进度展示）。 */
    @Volatile
    private var writtenBytes = 0L

    @Volatile
    private var failed = false

    // ---------- 诊断计数（用于定位"只有一部分分片被写入"这类问题）----------
    @Volatile
    private var enqueued = 0
    @Volatile
    private var dropped = 0

    /** 目标总大小（用于一次性预分配；<=0 表示未知，退化为按需扩展）。 */
    @Volatile
    var expectedSize: Long = -1L

    /**
     * 专用写入线程。
     *
     * 【为什么必须独立线程】首版把写入直接放在分片完成回调里，结果下载段
     * 从 200 MB/s 掉到 55 MB/s（总耗时 5.2s → 12.6s）。原因是回调运行在调度器的
     * ioDispatcher（并行度 workers+4）上，写盘会和 64 个正在阻塞读 socket 的 worker
     * **抢同一批线程**。改成"投递队列 + 单一后台线程"后，worker 只入队（不碰磁盘）。
     *
     * 【为什么保持一个常开句柄 + 一次预分配】
     * 第二版每写一个分片都 `RandomAccessFile(tempFile,"rw")` + `setLength`，
     * 结果写线程只跟上 51 MB/s（实测 enqueued=251 / dequeued=62）。
     * 两个原因：
     *  1. 分片是**乱序完成**的，写靠后的分片时 `setLength(offset)` 会把中间
     *     空洞全部落盘（Windows 上代价极高）；
     *  2. 每个分片开关一次文件句柄。
     * 故改为：开始时按总大小**预分配一次**，全程复用同一句柄，写入只 seek 不动长度。
     */
    private val queue = java.util.concurrent.LinkedBlockingQueue<Triple<Long, File, Long?>>()
    private var handle: RandomAccessFile? = null

    private val writer = Thread {
        try {
            while (true) {
                val item = queue.take()
                val (start, file, size) = item
                if (start < 0) break            // 结束哨兵
                val ok = try {
                    writeAt(file, start).also { success ->
                        if (success) {
                            writtenBytes += file.length()
                            synchronized(written) {
                                written.add(file.name)
                                spans.add(start to (start + file.length()))
                            }
                        }
                    }
                } catch (e: Throwable) {
                    false
                }
                if (!ok) failed = true
            }
        } catch (_: InterruptedException) {
            // 正常退出路径（shutdown 会中断）
        } finally {
            runCatching { handle?.close() }
            handle = null
        }
    }.apply { isDaemon = true; name = "turbodl-overlap-merge" }

    init {
        writer.start()
    }

    fun mergedBytes(): Long = writtenBytes

    fun hasFailed(): Boolean = failed

    fun writtenNames(): Set<String> = synchronized(written) { HashSet(written) }

    /** 清理可能残留的临时文件（如上次中断留下）。 */
    fun reset() {
        runCatching { tempFile.delete() }
        synchronized(written) { written.clear(); spans.clear() }
        writtenBytes = 0L
        failed = false
        enqueued = 0
        dropped = 0
        runCatching { handle?.close() }
        handle = null
    }

    /**
     * 分片完成时调用（可并发）。**只入队，不碰磁盘** —— 真正的写入在
     * [writer] 线程上串行进行。
     *
     * 任何完成的分片都会立刻入队（**不需要等前面的分片先完成**）：
     * 它在文件中的位置由 `start` 唯一确定，乱序写入安全；
     * 收尾托管造成的重叠也因"同一偏移写同样内容"而幂等。
     */
    suspend fun onSegmentDone(start: Long, file: File) {
        enqueued++
        if (failed || file.length() <= 0) return
        // 队列有界：极端情况下（写盘远慢于下载）宁可丢弃后续预合并，
        // 也不能让内存无界增长 —— 收尾仍有常规合并兜底。
        if (queue.size > MAX_QUEUE) { dropped++; return }
        queue.offer(Triple(start, file, expectedSize))
    }

    /**
     * 收尾：把还没写入的分片补齐，确认**整段区间真的被覆盖**，再搬到最终路径。
     *
     * 【为什么不能只比长度】若中间某个分片没写成功，而末尾分片写成功了，
     * 文件长度仍等于 total —— 长度校验会放行一个**中间有空洞**的文件。
     * 故这里按已写入的区间做覆盖判定（区间数 = 分片数，代价可忽略）。
     *
     * 【为什么需要 [allParts] 兜底】写线程是异步的，下载结束时它可能还差最后一两个
     * 分片没落盘（实测差 2MB = 1 个分片，于是覆盖判定失败、预合并整条路径白做）。
     * 故收尾时把**尚未写入的分片**同步补写一遍：数量极少（通常 0~2 个），
     * 代价可忽略，但能保证预合并真正生效。
     *
     * @param allParts 全部未完成分片（start → 文件），用于补齐漏写的部分
     * @return 是否成功（必须完整覆盖 [0, expectedTotal)）
     */
    fun publish(expectedTotal: Long, allParts: Map<Long, File> = emptyMap()): Boolean = runCatching {
        if (failed) return@runCatching false
        // 等队列排空（最多 [DRAIN_TIMEOUT_MS]）：避免刚下完就 rename 导致内容不全
        val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
        while (queue.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        if (queue.isNotEmpty()) return@runCatching false

        // 同步补齐：把"尚未写入"的分片补上（通常只有 0~2 个）
        if (!coversFully(expectedTotal)) {
            val done = synchronized(written) { written.toHashSet() }
            for ((start, file) in allParts) {
                if (file.name in done || file.length() <= 0) continue
                if (!writeAt(file, start)) return@runCatching false
                synchronized(written) {
                    written.add(file.name)
                    spans.add(start to (start + file.length()))
                }
            }
        }

        if (!tempFile.isFile) return@runCatching false
        if (!coversFully(expectedTotal)) return@runCatching false
        // 关闭句柄后再校验/截断（Windows 下打开中的文件无法 rename）
        runCatching { handle?.close() }
        handle = null
        // 【必须截断】预分配是按"各分片长度之和"算的，而收尾托管可能让某个分片
        // 的文件长于它最终占用的区间（重叠部分），使文件比 total 长一点
        // （实测多 2MB）。截断到精确长度即可 —— 覆盖判定已确认前 total 字节有效。
        if (tempFile.length() != expectedTotal) {
            RandomAccessFile(tempFile, "rw").use { it.setLength(expectedTotal) }
        }
        if (tempFile.length() != expectedTotal) return@runCatching false
        if (dest.exists()) dest.delete()
        if (!tempFile.renameTo(dest)) {
            tempFile.copyTo(dest, overwrite = true)
            tempFile.delete()
        }
        true
    }.getOrDefault(false)

    /** 已写入区间是否完整覆盖 [0, total)。 */
    private fun coversFully(total: Long): Boolean {
        val list = synchronized(written) { spans.toList() }.sortedBy { it.first }
        var pos = 0L
        for ((s, e) in list) {          // e 为开区间末端
            if (s > pos) return false    // 出现空洞
            if (e > pos) pos = e
            if (pos >= total) return true
        }
        return pos >= total
    }

    /** 诊断：入队/出队/丢弃 计数与覆盖位置。 */
    fun debugStats(): String {
        val list = synchronized(written) { spans.toList() }.sortedBy { it.first }
        var pos = 0L
        for ((s, e) in list) {
            if (s > pos) break
            if (e > pos) pos = e
        }
        return "enqueued=$enqueued dropped=$dropped " +
            "spans=${list.size} coveredUpTo=$pos writtenBytes=$writtenBytes " +
            "queueLen=${queue.size} failed=$failed"
    }

    /** 停止写线程（任务结束/取消时调用，避免线程泄漏）。 */
    fun shutdown() {
        runCatching { queue.offer(Triple(-1L, dest, null)) }
        runCatching { writer.interrupt() }
    }

    /**
     * 写入一个分片（仅在 [writer] 线程上调用，无需额外同步）。
     *
     * 全程复用 [handle]，且**只在开始时预分配一次**总大小 ——
     * 写入过程只 seek，不改文件长度（乱序写靠后的分片时不会去落盘中间空洞）。
     */
    private fun writeAt(part: File, offset: Long): Boolean = runCatching {
        val raf = handle ?: RandomAccessFile(tempFile, "rw").also {
            it.setLength(expectedSize)   // 一次性预分配
            handle = it
        }
        raf.seek(offset)
        FileInputStream(part).use { fis ->
            fis.channel.use { inCh ->
                val outCh = raf.channel
                var pos = 0L
                val size = inCh.size()
                while (pos < size) {
                    val n = inCh.transferTo(pos, size - pos, outCh)
                    if (n <= 0) break
                    pos += n
                }
            }
        }
        true
    }.getOrDefault(false)

    private companion object {
        /** 预合并队列上限：超过则放弃后续预合并（收尾走常规合并兜底）。 */
        const val MAX_QUEUE = 512

        /** publish 等待队列排空的上限（毫秒）。 */
        const val DRAIN_TIMEOUT_MS = 30_000L
    }
}
