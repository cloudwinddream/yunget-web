package com.yunget.web.service

import com.yunget.web.model.SettingsData
import com.yunget.web.model.TaskInfo
import com.yunget.web.model.TrashItemResponse
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
    // 来源文件稳定键（platform+fid+size）：引擎分片目录按它命名，同一任务换链续下靠它命中旧分片；
    // 注意删任务会连该键的分片目录与本任务的 .part 一并清掉，删除后重新下载无法找回已下字节；空=按任务 id
    val resumeKey: String = "",
    // 已自动重试次数（失败后退避续传；有明显进度推进会清零）
    val autoRetry: Int = 0,
    // 文件夹下载批次（普通单文件下载为空）
    val batchId: String = "",
    val batchName: String = "",
    val relPath: String = "",
    // 排队优先级（-1 低 / 0 普通 / 1 高）：只影响同网盘排队放行顺序，不抢占运行中任务；
    // 旧 tasks.json 无此字段时靠默认值 0 自动兼容
    val priority: Int = 0,
    // 来源说明书（SourceRef 编码）：链接失效后凭它重新取链原地换上续下；空=不可刷新
    // （旧 tasks.json 无此字段靠默认值兼容；存量网盘任务可由 resumeKey 反推个人盘来源）
    val sourceRef: String = "",
    // 链接分组：同一次解析产出的多条任务（如 DASH 双轨、YouTube 视频+字幕）共享一个组 id，
    // 刷新时整组从同一次重解析结果一起换链；空=独立任务
    val linkGroup: String = "",
    // 已自动刷新链接次数（与 autoRetry 分开记账；手动刷新链接会清零）
    val autoRefreshCount: Int = 0
)

/** 重新取到的新链接（换链续下用）：url/headers 必须整套替换；size>0 时与任务记录 total 核对防续错文件 */
data class FreshLink(
    val url: String,
    val headers: Map<String, String>,
    val size: Long = -1,
    /** 取链方回填的来源说明书（存量任务首次刷新时补写来源）；null=沿用记录里已有的 */
    val sourceRef: String? = null
)

/**
 * 换链取链方（接缝）：由 Main 装配、按 sourceRef 分发到各来源服务
 * （NetdiskService / VideoService / YouTubeService）。入参为触发任务（sourceRef 已由
 * DownloadService 补齐为有效值）与同 linkGroup 的可刷新成员（含触发任务自身）；
 * 返回 taskId → 新链接（须覆盖全部成员，缺一视为失败）；无法取链抛异常（文案透传给用户）。
 * 测试时可注入替身，不必真跑网络。
 */
typealias LinkRefreshProvider = suspend (TaskRecord, List<TaskRecord>) -> Map<Long, FreshLink>?

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
    // 链接失效后的自动刷新：待执行的刷新 Job（与 retryJobs 分开，互不覆盖）
    private val autoRefreshJobs = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()

    /** 换链取链方（Main 装配；为空时刷新链接功能不可用，refreshAndResume 给出可读报错） */
    @Volatile
    var refreshProvider: LinkRefreshProvider? = null

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
        // 回收站到期清理：启动先清一轮，之后每 24 小时一轮（清除删除超过 7 天的条目）
        scope.launch {
            runCatching { purgeExpiredTrash() }
            while (true) {
                delay(TRASH_SWEEP_INTERVAL_MS)
                runCatching { purgeExpiredTrash() }
            }
        }
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
        /** 回收站目录名（位于下载目录内） */
        const val TRASH_DIR_NAME = ".yunget-trash"
        /** 回收站保留天数：超期条目由到期清理自动彻底删除 */
        const val TRASH_RETENTION_DAYS = 7
        const val TRASH_RETENTION_MS = TRASH_RETENTION_DAYS * 86_400_000L
        /** 到期清理轮询间隔（启动一轮＋每 24 小时一轮） */
        const val TRASH_SWEEP_INTERVAL_MS = 24L * 3600 * 1000

        /** 可换链续下的任务状态（手动刷新与存量认领共用；completed/下载中不在内） */
        val REFRESHABLE_STATES = setOf("failed", "paused", "retry_wait", "queued")
        /** 自动刷新链接的次数上限（与 autoRetry 分开记账，手动刷新清零） */
        const val MAX_AUTO_REFRESH = 3
        /** 自动刷新最小间隔：5 分钟；第 n 次在此基础上再叠加 (n-1) 个 5 分钟 */
        const val AUTO_REFRESH_BASE_MS = 5L * 60 * 1000

        /**
         * 自动刷新是否该接管这次失败（纯函数，供单测）：任务可刷新、失败像链接失效、
         * 且自动刷新次数未封顶，三者同时成立才接管；接管后不再并行走原死链重试。
         */
        fun autoRefreshEligible(refreshable: Boolean, autoRefreshCount: Int, reason: String): Boolean =
            refreshable && autoRefreshCount < MAX_AUTO_REFRESH && isLinkExpiryFailure(reason)
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
        sourceRef: String = "",
        linkGroup: String = "",
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
            batchId = batchId, batchName = batchName, relPath = relPath,
            sourceRef = sourceRef, linkGroup = linkGroup
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
        cancelAutoRefresh(id)
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
        cancelAutoRefresh(id)
        // 手动继续：重试计数清零，给自动重试重新留额度
        tasks[id] = t.copy(autoRetry = 0)
        startInternal(id)
    }

    /** 注册/替换任务的完成回调（换链续下时新链接若带临时转存清理目录，需按新链接重登记） */
    fun registerOnComplete(id: Long, cb: suspend () -> Unit) {
        callbacks[id] = cb
    }

    // ---------- 换链续下（刷新链接并继续） ----------

    /** 任务是否具备刷新链接的来源：有 sourceRef，或存量网盘任务可由 resumeKey 反推个人盘来源 */
    fun canRefresh(t: TaskRecord): Boolean =
        SourceRef.parse(t.sourceRef) != null ||
            SourceRef.parseLegacyDriveResumeKey(t.resumeKey) != null

    /**
     * 刷新链接并继续：按来源重新取一条新链，原地替换任务的 url/headers 后重新启动。
     * resumeKey / 分片目录 / .part / 已下进度一律不动，引擎靠 stableKey 命中旧分片续下。
     * 同 linkGroup 的成员（DASH 双轨等）由取链方从同一次重解析结果一起换链。
     * 失败一律抛异常（文案直接透传给用户），任务记录保持原样。
     * @param manual true=用户手动点击（成功后自动刷新计数清零）；false=自动刷新调度触发
     */
    suspend fun refreshAndResume(id: Long, manual: Boolean = true) {
        val t = tasks[id] ?: throw IllegalArgumentException("任务不存在")
        if (t.status !in REFRESHABLE_STATES) {
            throw IllegalStateException("任务当前状态不能刷新链接（仅失败/已暂停/等待重试/排队中的任务可以）")
        }
        // 有效来源：优先记录里的 sourceRef；存量任务按 resumeKey 反推个人盘来源
        var backfilled: String? = null
        val parsed = SourceRef.parse(t.sourceRef) ?: run {
            val legacy = SourceRef.parseLegacyDriveResumeKey(t.resumeKey)
                ?: throw IllegalStateException(
                    "该任务没有记录来源信息，无法刷新链接（多为旧版本创建的非网盘任务），请重新解析后下载"
                )
            if (legacy.platform == "pan115") {
                throw IllegalStateException(
                    "115 旧任务缺少取链凭证（pickcode），无法自动刷新链接，请到「网盘文件」页重新下载该文件"
                )
            }
            backfilled = legacy.encode()
            legacy
        }
        val provider = refreshProvider
            ?: throw IllegalStateException("刷新链接服务未就绪，请稍后重试")
        // 同组成员（双轨/字幕）一起换链；无组则只有自己
        val members = if (t.linkGroup.isNotBlank()) {
            tasks.values
                .filter { it.linkGroup == t.linkGroup && it.status in REFRESHABLE_STATES }
                .sortedBy { it.id }
                .ifEmpty { listOf(t) }
        } else listOf(t)
        val trigger = t.copy(sourceRef = backfilled ?: t.sourceRef)
        val fresh = provider(trigger, members)
            ?: throw IllegalStateException("未能获取新链接，请稍后重试")
        if (!fresh.containsKey(id)) throw IllegalStateException("未能获取新链接，请稍后重试")
        applyFreshLinks(fresh, triggerId = id, manual = manual, backfillSourceRef = backfilled)
        members.forEach { startInternal(it.id) }
        System.out.println("[refresh] task=$id 已换新链接续下（manual=$manual，同组 ${members.size} 个任务）")
    }

    /**
     * 把取链方给的新链接原地落到任务记录上（refreshAndResume 与分享存量认领共用）。
     * 大小守卫：任一成员的新链大小与记录 total 不一致 → 整体拒绝、一个都不动
     * （双轨尤其要整体成败一致，不能一条换了另一条没换）。
     */
    private fun applyFreshLinks(
        fresh: Map<Long, FreshLink>,
        triggerId: Long,
        manual: Boolean,
        backfillSourceRef: String? = null
    ) {
        for ((mid, fl) in fresh) {
            val m = tasks[mid] ?: continue
            if (fl.size > 0 && m.total > 0 && fl.size != m.total) {
                throw IllegalStateException("来源文件已发生变化（大小与原记录不一致），无法安全续下：${m.fileName}")
            }
        }
        for ((mid, fl) in fresh) {
            val m = tasks[mid] ?: continue
            tasks[mid] = m.copy(
                url = fl.url,
                headers = fl.headers,
                sourceRef = fl.sourceRef
                    ?: if (mid == triggerId && backfillSourceRef != null) backfillSourceRef else m.sourceRef,
                error = "",
                autoRetry = 0,
                autoRefreshCount = if (mid == triggerId && manual) 0 else m.autoRefreshCount
            )
            headersCache[mid] = fl.headers
            cancelRetry(mid)
            cancelAutoRefresh(mid)
        }
        persistTasks()
    }

    /**
     * 分享存量认领：用户重新解析同一分享时，入队前按 resumeKey 找一个未完成的老任务，
     * 命中则把本次解析的新链原地换给它续下（不新建任务）。只认领「无 sourceRef 的存量任务」
     * 与「来源同为分享的新任务」——个人盘来源的任务 resumeKey 同形，不得被分享认领劫持。
     * 同批重复解析同一文件只认领一次：已认领的 id 由调用方经 [excludeIds] 排除。
     */
    fun findClaimCandidate(resumeKey: String, excludeIds: Set<Long> = emptySet()): TaskRecord? {
        if (resumeKey.isBlank()) return null
        return tasks.values
            .filter {
                it.resumeKey == resumeKey && it.id !in excludeIds &&
                    it.status in REFRESHABLE_STATES &&
                    (it.sourceRef.isBlank() || it.sourceRef.startsWith("share|"))
            }
            .minWithOrNull(compareBy({ it.createdAt }, { it.id }))
    }

    /**
     * 认领落链：把新链原地换给被认领的老任务并启动（大小守卫不过抛异常，调用方回退正常入队）。
     */
    fun applyClaimedLink(id: Long, fresh: FreshLink) {
        val t = tasks[id] ?: throw IllegalArgumentException("任务不存在")
        if (t.status !in REFRESHABLE_STATES) throw IllegalStateException("任务状态已变化，无法认领续下")
        applyFreshLinks(mapOf(id to fresh), triggerId = id, manual = true)
        startInternal(id)
    }

    // ---------- 自动刷新（链接失效类失败时） ----------

    private fun scheduleAutoRefresh(id: Long) {
        val t = tasks[id] ?: return
        cancelAutoRefresh(id)
        autoRefreshJobs[id] = scope.launch {
            // 间隔 ≥5 分钟：第 n 次再叠加 (n-1) 个 5 分钟；另按任务 id 抖动 ≤30 秒，
            // 多个任务同时失效时把重取链请求天然错峰（取链接口有节奏要求）
            val wait = AUTO_REFRESH_BASE_MS * t.autoRefreshCount.coerceAtLeast(1) +
                (id % 31) * 1000L
            delay(wait)
            autoRefreshJobs.remove(id)
            // 等待期间用户可能暂停/删除/手动处理：状态变了就作罢
            if (tasks[id]?.status != "retry_wait") return@launch
            try {
                refreshAndResume(id, manual = false)
            } catch (e: Exception) {
                // 取链失败（如游客凭证过期需登录、分享已删）：停止自动尝试，原因明示给用户
                tasks[id]?.let {
                    if (it.status == "retry_wait" || it.status == "queued") {
                        tasks[id] = it.copy(
                            status = "failed",
                            error = "自动刷新链接失败：${e.message ?: "未知原因"}（可手动点「刷新链接并继续」重试）"
                        )
                        persistTasks()
                    }
                }
            }
        }
    }

    private fun cancelAutoRefresh(id: Long) {
        autoRefreshJobs.remove(id)?.cancel()
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
        cancelAutoRefresh(id)
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
                    // 不再直删：断言通过才移入回收站（7 天内可恢复），断言不过只删记录不动文件
                    if (moveFileToTrash(id, f)) cleanupEmptyParents(f)
                }
            }
            tasks.remove(id)
            persistTasks()
        }
    }

    // ---------- 回收站（下载目录/.yunget-trash，保留 7 天） ----------

    @Serializable
    private data class TrashEntry(
        val id: String,
        val trashName: String,
        val originalPath: String,
        val fileName: String,
        val size: Long,
        val deletedAt: Long
    )

    private val trashLock = Any()

    private fun trashDir(): File = File(resolvedDownloadsDir(), TRASH_DIR_NAME)
    private fun trashIndexFile(dir: File): File = File(dir, "index.json")

    private fun loadTrashIndex(dir: File): MutableList<TrashEntry> {
        val f = trashIndexFile(dir)
        if (!f.exists()) return mutableListOf()
        return try {
            json.decodeFromString<List<TrashEntry>>(f.readText()).toMutableList()
        } catch (e: Exception) {
            System.out.println("[trash] 清单读取失败，按空处理：${e.message}")
            mutableListOf()
        }
    }

    private fun saveTrashIndex(dir: File, entries: List<TrashEntry>) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "index.json.tmp")
            tmp.writeText(json.encodeToString(entries))
            tmp.renameTo(trashIndexFile(dir))
        } catch (e: Exception) { e.printStackTrace() }
    }

    /**
     * 删文件前的断言：目标 canonical 路径必须位于当前下载目录内、且是普通文件。
     * 任一不满足都拒绝碰文件（只删任务记录）：防 savePath 异常时误删下载目录外的内容，
     * 也是文件夹批次整批误删的最后一道闸。
     */
    private fun assertFileInDownloads(f: File): Boolean = try {
        val root = resolvedDownloadsDir().canonicalFile
        val c = f.canonicalFile
        c.isFile && c.path.startsWith(root.path + File.separator)
    } catch (e: Exception) { false }

    /** 断言通过 → 移入回收站并登记清单，返回 true；断言不过/移动失败 → 打日志、返回 false（调用方只删记录）。 */
    private fun moveFileToTrash(taskId: Long, f: File): Boolean {
        if (!assertFileInDownloads(f)) {
            System.out.println("[trash] 拒绝处理下载目录外或非普通文件（task=$taskId）：${f.path}，仅删除任务记录")
            return false
        }
        return try {
            val dir = trashDir().apply { mkdirs() }
            val entryId = java.util.UUID.randomUUID().toString()
            val trashName = "${entryId}_${f.name}"
            val dest = File(dir, trashName)
            if (!f.renameTo(dest)) {
                f.copyTo(dest, overwrite = true)
                f.delete()
            }
            val entry = TrashEntry(
                id = entryId, trashName = trashName,
                originalPath = f.canonicalPath, fileName = f.name,
                size = dest.length(), deletedAt = System.currentTimeMillis()
            )
            synchronized(trashLock) {
                val entries = loadTrashIndex(dir)
                entries.add(entry)
                saveTrashIndex(dir, entries)
            }
            true
        } catch (e: Exception) {
            System.out.println("[trash] 移入回收站失败（task=$taskId）：${f.path}：${e.message}，仅删除任务记录")
            false
        }
    }

    /** 回收站条目列表（按删除时间倒序，含剩余天数） */
    fun listTrash(): List<TrashItemResponse> {
        val dir = trashDir()
        val now = System.currentTimeMillis()
        val entries = synchronized(trashLock) { loadTrashIndex(dir) }
        return entries.sortedByDescending { it.deletedAt }.map { e ->
            TrashItemResponse(
                id = e.id, fileName = e.fileName, originalPath = e.originalPath,
                size = e.size, deletedAt = e.deletedAt,
                remainingDays = (TRASH_RETENTION_DAYS - ((now - e.deletedAt) / 86_400_000L).toInt()).coerceAtLeast(0)
            )
        }
    }

    /** 恢复：移回原路径；原路径已被占用时按 uniqueFile 规则改名。返回最终落点绝对路径。 */
    fun restoreTrash(entryId: String): String {
        val dir = trashDir()
        val entry = synchronized(trashLock) { loadTrashIndex(dir).firstOrNull { it.id == entryId } }
            ?: throw IllegalArgumentException("回收站条目不存在")
        val src = File(dir, entry.trashName)
        if (!src.exists()) {
            synchronized(trashLock) {
                val entries = loadTrashIndex(dir)
                entries.removeAll { it.id == entryId }
                saveTrashIndex(dir, entries)
            }
            throw IllegalArgumentException("回收站中的文件已不存在，已移除该条目")
        }
        var dest = File(entry.originalPath)
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest = uniqueFile(dest.parentFile ?: resolvedDownloadsDir(), dest.name)
        if (!src.renameTo(dest)) {
            src.copyTo(dest, overwrite = true)
            src.delete()
        }
        synchronized(trashLock) {
            val entries = loadTrashIndex(dir)
            entries.removeAll { it.id == entryId }
            saveTrashIndex(dir, entries)
        }
        return dest.absolutePath
    }

    /** 彻底删除单个条目（文件＋清单记录） */
    fun purgeTrashItem(entryId: String): Boolean {
        val dir = trashDir()
        var removed = false
        synchronized(trashLock) {
            val entries = loadTrashIndex(dir)
            val e = entries.firstOrNull { it.id == entryId }
            if (e != null) {
                File(dir, e.trashName).delete()
                entries.removeAll { it.id == entryId }
                saveTrashIndex(dir, entries)
                removed = true
            }
        }
        return removed
    }

    /** 清空回收站，返回清掉的条目数 */
    fun clearTrash(): Int {
        val dir = trashDir()
        var n = 0
        synchronized(trashLock) {
            val entries = loadTrashIndex(dir)
            entries.forEach { File(dir, it.trashName).delete(); n++ }
            if (entries.isNotEmpty()) saveTrashIndex(dir, emptyList())
        }
        return n
    }

    /** 到期清理：deletedAt 超过 7 天的条目连文件带清单一并清掉，顺手清回收站内的空子目录。返回清掉的条目数 */
    fun purgeExpiredTrash(): Int {
        val dir = trashDir()
        if (!dir.exists()) return 0
        val now = System.currentTimeMillis()
        var n = 0
        synchronized(trashLock) {
            val entries = loadTrashIndex(dir)
            val expired = entries.filter { now - it.deletedAt > TRASH_RETENTION_MS }
            if (expired.isNotEmpty()) {
                expired.forEach { File(dir, it.trashName).delete(); n++ }
                saveTrashIndex(dir, entries - expired.toSet())
            }
        }
        dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
            if (sub.listFiles().isNullOrEmpty()) sub.delete()
        }
        if (n > 0) System.out.println("[trash] 到期清理：清除 $n 个超过 7 天的条目")
        return n
    }

    fun list(): List<TaskInfo> = tasks.values.sortedByDescending { it.id }.map { t ->
        TaskInfo(
            id = t.id, fileName = t.fileName, status = t.status,
            downloaded = t.downloaded, total = t.total,
            speed = speeds[t.id] ?: 0L, etaMillis = etas[t.id] ?: -1L,
            error = t.error, createdAt = t.createdAt,
            batchId = t.batchId, batchName = t.batchName, relPath = t.relPath,
            priority = t.priority,
            canRefresh = canRefresh(t)
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
                    // 自动刷新计数同口径清零：否则链接每几小时过期一次的大文件会被 3 次上限卡死
                    var newRefresh = t.autoRefreshCount
                    val base = retryBaseline[id]
                    if (base != null && p.downloadedBytes >= base + 32L * 1024 * 1024) {
                        if (newRetry > 0) newRetry = 0
                        if (newRefresh > 0) newRefresh = 0
                        if (newRetry != t.autoRetry || newRefresh != t.autoRefreshCount) retryBaseline.remove(id)
                    }
                    if (p.downloadedBytes != t.downloaded ||
                        (p.totalBytes > 0 && p.totalBytes != t.total) || st != t.status ||
                        newRetry != t.autoRetry || newRefresh != t.autoRefreshCount
                    ) {
                        tasks[id] = t.copy(
                            downloaded = p.downloadedBytes,
                            total = if (p.totalBytes > 0) p.totalBytes else t.total,
                            status = st,
                            autoRetry = newRetry,
                            autoRefreshCount = newRefresh
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
                if (autoRefreshEligible(canRefresh(t), t.autoRefreshCount, ev.reason)) {
                    // 链接失效类失败且任务可刷新：换新链才是正解，原死链退避重试毫无意义，
                    // 故自动刷新接管这次失败（与 autoRetry 分开记账，预算/间隔见常量）
                    val next = t.autoRefreshCount + 1
                    retryBaseline[id] = t.downloaded
                    tasks[id] = t.copy(status = "retry_wait", error = ev.reason, autoRefreshCount = next)
                    persistTasks()
                    scheduleAutoRefresh(id)
                    return@launch
                }
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
