package com.yunget.web.service

import com.yunget.web.model.SettingsData
import com.yunget.web.model.TaskInfo
import dev.turbodl.core.DnsMode
import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.ProxyMode
import dev.turbodl.core.TaskState
import dev.turbodl.core.TurboConfig
import dev.turbodl.core.TurboEvent
import dev.turbodl.plugin.bootstrap.TurboBootstrap
import dev.turbodl.plugin.hls.HlsPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class TaskRecord(
    val id: Long,
    val fileName: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val total: Long = 0,
    val downloaded: Long = 0,
    val status: String = "downloading",
    val error: String = "",
    val savePath: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    // 来源网盘 id（quark/uc/xunlei/baidu/pan123/c139），用于分网盘连接数；空=全局设置
    val platform: String = "",
    // 来源文件稳定键（platform+fid+size）：分片目录按它命名，删任务重下同文件也能续传；空=按任务 id
    val resumeKey: String = "",
    // 已自动重试次数（失败后退避续传；有明显进度推进会清零）
    val autoRetry: Int = 0,
    // 文件夹下载批次（普通单文件下载为空）
    val batchId: String = "",
    val batchName: String = "",
    val relPath: String = "",
    // 排队优先级（-1 低 / 0 普通 / 1 高）：只影响同网盘排队放行顺序，不抢占运行中任务；
    // 旧 tasks.json 无此字段时靠默认值 0 自动兼容
    val priority: Int = 0
)

/**
 * 下载服务：TurboDL 引擎的 Web 封装。
 * - 任务落盘到设置中的下载目录（默认当前用户的下载目录，如 ~/Downloads）
 * - 任务元数据持久化到 tasks.json，重启后可恢复（下载中→已暂停）
 * - 断点续传靠 stableKey="web-<id>" 复用分片目录
 */
class DownloadService(dataDir: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // 默认下载目录：当前用户的下载目录（如 ~/Downloads）；取不到用户主目录时退回 dataDir/downloads
    private val defaultDownloadsDir: File = (
        System.getProperty("user.home")?.takeIf { it.isNotBlank() }
            ?.let { File(it, "Downloads") }
            ?: File(dataDir, "downloads")
        ).apply { mkdirs() }
    private val tmpDir = File(dataDir, "tmp").apply { mkdirs() }
    private val chunkDir = File(dataDir, "chunks").apply { mkdirs() }
    private val tasksFile = File(dataDir, "tasks.json")
    private val settingsFile = File(dataDir, "settings.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private val idGen = AtomicLong(1)
    private val tasks = ConcurrentHashMap<Long, TaskRecord>()
    private val turboIds = ConcurrentHashMap<Long, Long>()       // taskId -> turbo taskId
    private val turboToTask = ConcurrentHashMap<Long, Long>()    // reverse
    private val speeds = ConcurrentHashMap<Long, Long>()
    private val etas = ConcurrentHashMap<Long, Long>()
    private val callbacks = ConcurrentHashMap<Long, suspend () -> Unit>()
    private val headersCache = ConcurrentHashMap<Long, Map<String, String>>()
    // 失败自动重试：待执行的重试 Job + 失败时的进度基线（进度推进足够多则重置重试计数）
    private val retryJobs = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()
    private val retryBaseline = ConcurrentHashMap<Long, Long>()

    // 正在启动中的任务（startInternal 已登记、协程尚未结束）：防双开守卫 + 优先级闸门的「等待者」判定依据。
    // 必须声明在 init 之前：init 的重启接管会同步调用 startInternal 并 add 本集合，
    // Kotlin 按类体文本顺序初始化属性，声明晚于 init 时此处仍为 null 会 NPE（冒烟实证）。
    private val starting = ConcurrentHashMap.newKeySet<Long>()

    var settings: SettingsData = loadSettings()
        private set

    private val bootstrap: TurboBootstrap = TurboBootstrap.create(
        config = buildConfig(settings),
        extraPlugins = listOf(HlsPlugin())
    )
    private val client get() = bootstrap.client

    init {
        loadTasks()
        scope.launch { client.events.collect { onEvent(it) } }
        // 重启恢复：上次关机时正等着自动重试的任务，重新排队
        tasks.values.filter { it.status == "retry_wait" }.forEach { scheduleRetry(it.id) }
        // 重启恢复：上次关机时还在排队（等网盘槽位）的任务，重新进入闸门等待，
        // 否则它们没有任何等待协程、永远不会被提升（只能暂停再继续唤醒）。
        // 两段式：先把全员同步登记进 starting 再逐个启动，避免某个先被调度的协程在
        // 高优先级任务登记之前就抢走首个名额（冒烟实证过 2 号抢跑 3 号）
        val queuedAtBoot = tasks.values.filter { it.status == "queued" }
        queuedAtBoot.forEach { starting.add(it.id) }
        queuedAtBoot.forEach { startInternal(it.id, alreadyRegistered = true) }
    }

    // ---------- 配置 ----------

    private fun buildConfig(s: SettingsData) = TurboConfig(
        // 引擎单任务硬上限 256（原版安卓引擎为 512）；各网盘的实际值走 connectionsOverride
        maxConnectionsPerTask = 256,
        // 引擎全局闸门只做总天花板：各网盘限流之和（引擎要求 1..64）。
        // 真正的分网盘限流由 DownloadService 的 platformRunning 闸门在提交前执行。
        maxConcurrentTasks = totalConcurrentLimit(s).coerceIn(1, 64),
        globalSpeedLimitBytesPerSec = s.speedLimitBps.coerceAtLeast(0),
        maxRetries = s.maxRetries.coerceIn(0, 50),
        // 网盘直链必探测：先跟随 302 拿到最终 CDN 地址再分片，否则每个分片重走重定向、
        // 容易被节点拒绝，表现为"下载中但 0 速度"。顺带拿到 ETag 校验器，断点续传更稳。
        skipProbeWhenSizeKnown = false,
        dynamicSegmentation = true,
        segmentsPerConnection = 4,
        forceHttp1 = true,
        // 关闭背压降并发（与上游原版一致）：网盘 CDN 频繁 502/503，开启后线程只降难升，
        // 是“下到后面速度暴跌”的主因；暂时错误靠分片重试处理，不动并发。
        // 注：v1.0.11 曾误开为 4 导致速度明显变慢，v1.0.14 恢复原值。
        backpressureConsecutiveFailures = 0,
        maxConnectionsPerHost = 0,
        workDir = chunkDir,
        proxy = ProxyMode.System,
        dns = DnsMode.System,
        warmUpConnections = true,
        slowStart = true,
        trustAllCerts = false,
        // 网盘直链场景：CDN 常不给 ETag/Last-Modified（弱校验器）。弱校验下让分片指纹 MISMATCH
        // 整批丢分片太脆（实测续传从 0 重下），故信任弱校验器续传；文件变没变由引擎按
        // len/etag 字段的正向证据判定（见 BuiltinHttpBackend 续传校验）。
        trustWeakValidator = true
    )

    fun updateSettings(s: SettingsData) {
        val dir = s.downloadDir.trim()
        if (dir.isNotBlank()) {
            val f = File(dir)
            val ok = try {
                f.mkdirs()
                f.isDirectory && f.canWrite()
            } catch (e: Exception) {
                false
            }
            if (!ok) throw IllegalArgumentException("下载目录无法创建或不可写：$dir")
        }
        settings = s.copy(downloadDir = dir)
        try {
            settingsFile.writeText(json.encodeToString(settings))
        } catch (e: Exception) { e.printStackTrace() }
        client.updateConfig(buildConfig(s))
    }

    // ---------- 分网盘并发 ----------

    /** 某网盘的分片连接数（照搬原版模型）：每个网盘单独设置，默认 32，迅雷默认 8；
     *  注意：原版把迅雷写死 8 不可改，但实测部分用户的迅雷链路吃得消更多连接，
     *  锁死会导致这部分用户降速，故本项目改为"默认 8、可调"。 */
    fun connectionsFor(platform: String): Int {
        val v = settings.platformConnections[platform]?.takeIf { it in 1..512 }
        return v ?: if (platform == "xunlei") XUNLEI_CONNECTIONS else DEFAULT_CONNECTIONS
    }

    /** 某网盘的同时任务数：单独设置 >0 则用它，否则跟随全局（设置改动对等待中的任务即时生效） */
    fun concurrentLimit(platform: String): Int =
        settings.platformConcurrentTasks[platform]?.takeIf { it > 0 }
            ?: settings.maxConcurrentTasks.coerceIn(1, 64)

    private fun totalConcurrentLimit(s: SettingsData): Int =
        PLATFORM_IDS.sumOf { id ->
            s.platformConcurrentTasks[id]?.takeIf { it > 0 } ?: s.maxConcurrentTasks.coerceIn(1, 64)
        }

    // 分网盘并发闸门：platform -> 正在占用槽位的任务数；等待者轮询（设置变更即时生效）。
    // 槽位从 startInternal 持有到任务真正结束（完成/失败/暂停/删除），而不是提交即释放，
    // 否则引擎内部排队的任务会绕过网盘限流。
    private val platformGateMutex = Mutex()
    private val platformRunning = mutableMapOf<String, Int>()
    // 持有槽位的任务（taskId -> platform）：release 按任务幂等，暂停/删除与启动竞态时不漏放
    private val platformHeld = ConcurrentHashMap<Long, String>()
    // 在途守卫集合 starting 声明在本类前部（init 之前，原因见彼处注释）。

    private suspend fun acquirePlatformSlot(taskId: Long, platform: String): Boolean {
        // 等槽位期间被暂停/删除则直接退出（由调用方释放逻辑兜底，不占槽位）
        while (true) {
            val cur = tasks[taskId]
            if (cur == null || cur.status == "paused") return false
            val ok = platformGateMutex.withLock {
                val n = platformRunning.getOrDefault(platform, 0)
                // 放行条件：名额未满，且同网盘等待者中没有比自己更「资深」的
                // （优先级更高，或同级且创建更早）——排队由自由抢答改为按档排序
                if (n < concurrentLimit(platform).coerceAtLeast(1) && !hasSeniorWaiter(cur)) {
                    platformRunning[platform] = n + 1
                    platformHeld[taskId] = platform
                    true
                } else false
            }
            if (ok) return true
            delay(400)
        }
    }

    /**
     * 是否存在比 [self] 更应先放行的同网盘等待者。等待者口径：同 platform 且在 [starting] 中——
     * 该集合恰好标记「有活的提交前协程」的任务（含重启时被 init 接管的排队任务）；
     * 不再叠加 status 判断：新入队任务在协程跑起来前状态还是默认的 downloading，
     * 只认 status==queued 会让先建的任务在启动竞态里被后建的插队。
     * 排序键：priority 高者先；同级 createdAt 早者先（同毫秒再比 id，保证确定性）。
     */
    private fun hasSeniorWaiter(self: TaskRecord): Boolean =
        tasks.values.any { u ->
            u.id != self.id && u.platform == self.platform && starting.contains(u.id) &&
                (u.priority > self.priority ||
                    (u.priority == self.priority &&
                        (u.createdAt < self.createdAt || (u.createdAt == self.createdAt && u.id < self.id))))
        }

    private suspend fun releasePlatformSlot(taskId: Long) {
        val p = platformHeld.remove(taskId) ?: return
        platformGateMutex.withLock {
            platformRunning[p] = (platformRunning.getOrDefault(p, 0) - 1).coerceAtLeast(0)
        }
    }

    companion object {
        /** 各网盘默认连接数（原版默认值） */
        const val DEFAULT_CONNECTIONS = 32
        /** 迅雷默认连接数（原版为固定值；本项目改为默认 8、可调，避免锁死导致部分用户降速） */
        const val XUNLEI_CONNECTIONS = 8
        /** 参与并发统计的网盘 id（含 "" = 普通直链下载） */
        val PLATFORM_IDS = listOf("quark", "uc", "xunlei", "baidu", "pan123", "c139", "lanzou", "ilanzou", "guangya", "pan115", "video", "github", "")
    }

    /** 恢复默认设置：回到 SettingsData() 初始值（各网盘连接数 32、迅雷固定 8 等）；
     *  唯独下载目录保留——那是位置不是调参，重置会让人找不到文件 */
    fun resetSettings(): SettingsData {
        val dir = settings.downloadDir
        settings = SettingsData().copy(downloadDir = dir)
        try {
            settingsFile.writeText(json.encodeToString(settings))
        } catch (e: Exception) { e.printStackTrace() }
        client.updateConfig(buildConfig(settings))
        return settings
    }

    /** 实际生效的下载目录（设置留空时用默认目录） */
    fun resolvedDownloadsDir(): File {
        val custom = settings.downloadDir.trim()
        return if (custom.isBlank()) defaultDownloadsDir else File(custom).apply { mkdirs() }
    }

    /** 给前端展示的设置（downloadDir 填为当前生效目录，方便查看与修改） */
    fun settingsForDisplay(): SettingsData =
        settings.copy(downloadDir = resolvedDownloadsDir().absolutePath)

    private fun loadSettings(): SettingsData = try {
        if (settingsFile.exists()) json.decodeFromString(settingsFile.readText()) else SettingsData()
    } catch (e: Exception) { SettingsData() }

    // ---------- 任务 ----------

    suspend fun enqueue(
        url: String,
        fileName: String,
        headers: Map<String, String>,
        size: Long,
        platform: String = "",
        resumeKey: String = "",
        batchId: String = "",
        batchName: String = "",
        relPath: String = "",
        onComplete: (suspend () -> Unit)? = null
    ): Long {
        val safeName = sanitizeFileName(
            fileName.ifBlank {
                url.substringAfterLast('/').substringBefore('?')
                    .ifBlank { "download_${System.currentTimeMillis()}" }
            }
        )
        val id = idGen.getAndIncrement()
        tasks[id] = TaskRecord(
            id = id, fileName = safeName, url = url, headers = headers, total = size,
            platform = platform, resumeKey = resumeKey,
            batchId = batchId, batchName = batchName, relPath = relPath
        )
        if (headers.isNotEmpty()) headersCache[id] = headers
        if (onComplete != null) callbacks[id] = onComplete
        persistTasks()
        startInternal(id)
        return id
    }

    private fun startInternal(id: Long, alreadyRegistered: Boolean = false) {
        if (turboIds.containsKey(id)) {
            if (alreadyRegistered) starting.remove(id) // 预登记后未能启动：撤掉登记，勿留僵尸等待者
            return
        }
        if (tasks[id] == null) {
            if (alreadyRegistered) starting.remove(id)
            return
        }
        // 在途守卫（同步占位）：已有一个协程在等槽位/提交时，后续触发直接作罢
        if (!alreadyRegistered && !starting.add(id)) return
        scope.launch {
            try {
                // 先标排队：分网盘并发闸门按网盘各自限流，拿到槽位才真正提交。
                // 用最新记录回写，避免把排队期间刚改的 priority 一并覆盖掉
                val fresh = tasks[id] ?: return@launch
                tasks[id] = fresh.copy(status = "queued", error = "")
                persistTasks()
                if (!acquirePlatformSlot(id, fresh.platform)) return@launch
                // 等槽位期间可能被暂停/删除：以最新状态为准，避免漏放槽位
                val cur = tasks[id]
                if (cur == null || cur.status == "paused") {
                    releasePlatformSlot(id)
                    return@launch
                }
                tasks[id] = cur.copy(status = "downloading", error = "")
                val out = File(tmpDir, "task_${id}.part")
                val headers = headersCache[id] ?: cur.headers
                // 分网盘连接数（照搬原版）：每个网盘单独设置，默认 32，迅雷固定 8
                val request = DownloadRequest(
                    url = cur.url,
                    destination = out,
                    headers = headers,
                    knownSize = if (cur.total > 0) cur.total else -1,
                    connectionsOverride = connectionsFor(cur.platform).coerceIn(1, 256),
                    stableKey = cur.resumeKey.ifBlank { "web-$id" }
                )
                persistTasks()
                val turboId = client.submit(request)
                turboIds[id] = turboId
                turboToTask[turboId] = id
            } finally {
                // 提交成功后由 turboIds 守卫接棒；其余退出路径（被暂停/删除/异常）
                // 释放占位，允许之后 resume 重新拉起
                starting.remove(id)
            }
        }
    }

    fun pause(id: Long) {
        cancelRetry(id)
        val turboId = turboIds.remove(id)
        if (turboId != null) turboToTask.remove(turboId)
        speeds.remove(id); etas.remove(id)
        scope.launch {
            if (turboId != null) runCatching { client.pause(turboId) }
            tasks[id]?.let { tasks[id] = it.copy(status = "paused", autoRetry = 0) }
            // 已提交到引擎才占着网盘槽位；仍在排队等的由 startInternal 的检查自行退出
            if (turboId != null) releasePlatformSlot(id)
            persistTasks()
        }
    }

    fun resume(id: Long) {
        val t = tasks[id] ?: return
        if (t.status == "completed") return
        // 正在下载/合并的不要重入；在排队且已有等待协程时也不要重复拉起
        // （startInternal 的在途守卫也会挡，这里提前返回避免无谓地清零重试计数）
        if (t.status == "downloading" || t.status == "merging") return
        if (t.status == "queued" && starting.contains(id)) return
        cancelRetry(id)
        // 手动继续：重试计数清零，给自动重试重新留额度
        tasks[id] = t.copy(autoRetry = 0)
        startInternal(id)
    }

    /** 设置排队优先级（-1 低 / 0 普通 / 1 高）。排队中改档下一轮闸门轮询即生效；
     *  运行中改档只影响其下次排队，不抢占。任务不存在或档位越界抛 IllegalArgumentException。 */
    fun setPriority(id: Long, priority: Int) {
        if (priority !in -1..1) throw IllegalArgumentException("优先级只能是 -1（低）、0（普通）或 1（高）")
        val t = tasks[id] ?: throw IllegalArgumentException("任务不存在")
        if (t.priority != priority) {
            tasks[id] = t.copy(priority = priority)
            persistTasks()
        }
    }

    /** 自动重试退避：第 n 次失败后等 1/2/4/8/10…分钟再续（限流窗口通常按分钟计） */
    private fun retryDelayMs(attempt: Int): Long =
        minOf(60_000L * (1L shl minOf((attempt - 1).coerceAtLeast(0), 4)), 600_000L)

    private fun scheduleRetry(id: Long) {
        if (!settings.autoRetry) return
        val t = tasks[id] ?: return
        cancelRetry(id)
        retryJobs[id] = scope.launch {
            kotlinx.coroutines.delay(retryDelayMs(t.autoRetry))
            retryJobs.remove(id)
            // 等待期间用户可能暂停/删除/手动继续：状态变了就作罢
            if (tasks[id]?.status == "retry_wait") startInternal(id)
        }
    }

    private fun cancelRetry(id: Long) {
        retryJobs.remove(id)?.cancel()
        retryBaseline.remove(id)
    }

    fun delete(id: Long, deleteFile: Boolean) {
        cancelRetry(id)
        val turboId = turboIds.remove(id)
        if (turboId != null) turboToTask.remove(turboId)
        speeds.remove(id); etas.remove(id)
        callbacks.remove(id); retryBaseline.remove(id)
        scope.launch {
            if (turboId != null) runCatching { client.cancel(turboId, deleteOutput = true) }
            releasePlatformSlot(id)
            File(tmpDir, "task_${id}.part").delete()
            if (deleteFile) {
                tasks[id]?.savePath?.takeIf { it.isNotBlank() }?.let {
                    val f = File(it)
                    if (f.delete()) cleanupEmptyParents(f)
                }
            }
            tasks.remove(id)
            persistTasks()
        }
    }

    fun list(): List<TaskInfo> = tasks.values.sortedByDescending { it.id }.map { t ->
        TaskInfo(
            id = t.id, fileName = t.fileName, status = t.status,
            downloaded = t.downloaded, total = t.total,
            speed = speeds[t.id] ?: 0L, etaMillis = etas[t.id] ?: -1L,
            error = t.error, createdAt = t.createdAt,
            batchId = t.batchId, batchName = t.batchName, relPath = t.relPath,
            priority = t.priority
        )
    }

    fun get(id: Long): TaskRecord? = tasks[id]

    fun shutdown() {
        runCatching { scope.let { } }
        runCatching { bootstrap.shutdown() }
    }

    // ---------- 事件 ----------

    private fun onEvent(ev: TurboEvent) {
        val id = turboToTask[ev.taskId] ?: return
        when (ev) {
            is TurboEvent.Progress -> {
                val p = ev.progress
                speeds[id] = p.speedBytesPerSec
                etas[id] = p.etaMillis
                val t = tasks[id]
                if (t != null) {
                    // 状态同步：尤其 MERGING（100% 后合并分片），否则界面一直显示"下载中"像卡死
                    val st = when (p.state) {
                        TaskState.MERGING -> "merging"
                        TaskState.DOWNLOADING, TaskState.PROBING, TaskState.QUEUED -> "downloading"
                        else -> t.status
                    }
                    // 自动重试计数：自失败点后又推进 ≥32MB，说明这次重试是有效的，清零重新计数
                    var newRetry = t.autoRetry
                    val base = retryBaseline[id]
                    if (newRetry > 0 && base != null && p.downloadedBytes >= base + 32L * 1024 * 1024) {
                        newRetry = 0
                        retryBaseline.remove(id)
                    }
                    if (p.downloadedBytes != t.downloaded ||
                        (p.totalBytes > 0 && p.totalBytes != t.total) || st != t.status || newRetry != t.autoRetry
                    ) {
                        tasks[id] = t.copy(
                            downloaded = p.downloadedBytes,
                            total = if (p.totalBytes > 0) p.totalBytes else t.total,
                            status = st,
                            autoRetry = newRetry
                        )
                        // 进度落盘节流：不做高频写
                        if (System.currentTimeMillis() - lastPersistTs > 5000) persistTasks()
                    }
                }
            }
            is TurboEvent.Completed -> scope.launch { onCompleted(id, ev.file, ev.totalBytes) }
            is TurboEvent.Failed -> scope.launch {
                turboIds.remove(id)?.let { turboToTask.remove(it) }
                speeds.remove(id); etas.remove(id)
                releasePlatformSlot(id)
                File(tmpDir, "task_${id}.part").delete()
                val t = tasks[id] ?: return@launch
                val maxAuto = if (settings.autoRetry) settings.autoRetryMax.coerceIn(0, 50) else 0
                if (t.autoRetry < maxAuto) {
                    // 自动重试：退避后续传（被限流时等一阵再试往往就好了）；手动暂停可随时打断等待
                    val next = t.autoRetry + 1
                    retryBaseline[id] = t.downloaded
                    tasks[id] = t.copy(status = "retry_wait", error = ev.reason, autoRetry = next)
                    persistTasks()
                    scheduleRetry(id)
                } else {
                    tasks[id] = t.copy(status = "failed", error = ev.reason)
                    persistTasks()
                }
            }
            is TurboEvent.StateChanged -> {
                if (ev.state == TaskState.PAUSED || ev.state == TaskState.CANCELED) {
                    speeds.remove(id); etas.remove(id)
                }
            }
            is TurboEvent.Metadata -> {
                // 引擎的续传判定（是否复用旧分片/为何丢弃），打到服务日志便于排查"为什么从头下"
                if (ev.resumeNote.isNotBlank()) {
                    System.out.println("[download] task=$id resume: ${ev.resumeNote}")
                }
            }
            else -> {}
        }
    }

    /** 删文件后顺带清掉变空的父目录（只清下载目录内、且确实空了的目录，不越出下载目录） */
    private fun cleanupEmptyParents(file: File) {
        runCatching {
            val root = resolvedDownloadsDir().canonicalFile
            var dir = file.parentFile?.canonicalFile
            while (dir != null && dir != root && dir.path.startsWith(root.path + File.separator)) {
                if (!dir.delete()) break
                dir = dir.parentFile?.canonicalFile
            }
        }
    }

    private suspend fun onCompleted(id: Long, file: File, totalBytes: Long) {
        val task = tasks[id] ?: return
        try {
            val base = resolvedDownloadsDir()
            val destDir = if (task.relPath.isBlank()) base
            else File(base, task.relPath).apply { mkdirs() }
                    .takeIf { it.canonicalPath.startsWith(base.canonicalPath) } ?: base
            val dest = uniqueFile(destDir, task.fileName)
            // 同线程内移动/复制
            if (!file.renameTo(dest)) {
                file.copyTo(dest, overwrite = true)
                file.delete()
            } else if (file.exists()) {
                file.delete()
            }
            val actual = dest.length()
            val finalTotal = maxOf(if (totalBytes > 0) totalBytes else 0L, actual, task.total)
            tasks[id] = task.copy(
                status = "completed", savePath = dest.absolutePath,
                downloaded = actual, total = finalTotal, error = ""
            )
            turboIds.remove(id)?.let { turboToTask.remove(it) }
            speeds.remove(id); etas.remove(id)
            releasePlatformSlot(id)
            persistTasks()
            // 清理回调（如夸克临时转存目录）
            callbacks.remove(id)?.let { cb -> runCatching { cb() } }
        } catch (e: Exception) {
            tasks[id]?.let { tasks[id] = it.copy(status = "failed", error = "保存失败：${e.message}") }
            persistTasks()
        } finally {
            // 成功路径已在 try 内清理过一遍（均为幂等操作，这里重复调用无副作用）；
            // 失败路径必须在这里兜底释放：否则网盘名额永久泄漏堵死整条队列，
            // 且 turboIds 残留会让该任务之后永远无法再被启动
            turboIds.remove(id)?.let { turboToTask.remove(it) }
            speeds.remove(id); etas.remove(id)
            releasePlatformSlot(id)
        }
    }

    // ---------- 持久化 ----------

    @Volatile
    private var lastPersistTs = 0L

    private fun persistTasks() {
        lastPersistTs = System.currentTimeMillis()
        try {
            val tmp = File(tasksFile.parentFile, "tasks.json.tmp")
            tmp.writeText(json.encodeToString(tasks.values.toList()))
            tmp.renameTo(tasksFile)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun loadTasks() {
        try {
            if (!tasksFile.exists()) return
            val list: List<TaskRecord> = json.decodeFromString(tasksFile.readText())
            var maxId = 0L
            for (t in list) {
                // 上次未完成的任务标记为已暂停（进程重启/崩溃恢复；含合并中，分片还在，可继续）
                val fixed = if (t.status == "downloading" || t.status == "merging") t.copy(status = "paused") else t
                tasks[t.id] = fixed
                if (t.headers.isNotEmpty()) headersCache[t.id] = t.headers
                if (t.id > maxId) maxId = t.id
            }
            idGen.set(maxId + 1)
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ---------- 工具 ----------

    private fun sanitizeFileName(name: String): String {
        var n = name.trim().ifBlank { "download" }
        n = n.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        if (n.length > 200) n = n.take(200)
        return n
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = if (ext.isNotEmpty() && ext != name) File(dir, "$base($i).$ext")
            else File(dir, "$name($i)")
            i++
        }
        return f
    }
}
