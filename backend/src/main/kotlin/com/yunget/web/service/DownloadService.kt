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
import kotlinx.coroutines.launch
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
    val createdAt: Long = System.currentTimeMillis()
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
    }

    // ---------- 配置 ----------

    private fun buildConfig(s: SettingsData) = TurboConfig(
        maxConnectionsPerTask = s.maxConnections.coerceIn(1, 128),
        maxConcurrentTasks = s.maxConcurrentTasks.coerceIn(1, 64),
        globalSpeedLimitBytesPerSec = s.speedLimitBps.coerceAtLeast(0),
        maxRetries = s.maxRetries.coerceIn(0, 50),
        dynamicSegmentation = true,
        segmentsPerConnection = 4,
        forceHttp1 = true,
        backpressureConsecutiveFailures = 0,
        maxConnectionsPerHost = 0,
        workDir = chunkDir,
        proxy = ProxyMode.System,
        dns = DnsMode.System,
        warmUpConnections = true,
        slowStart = true,
        trustAllCerts = false,
        trustWeakValidator = false
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
        onComplete: (suspend () -> Unit)? = null
    ): Long {
        val safeName = sanitizeFileName(
            fileName.ifBlank {
                url.substringAfterLast('/').substringBefore('?')
                    .ifBlank { "download_${System.currentTimeMillis()}" }
            }
        )
        val id = idGen.getAndIncrement()
        tasks[id] = TaskRecord(id = id, fileName = safeName, url = url, headers = headers, total = size)
        if (headers.isNotEmpty()) headersCache[id] = headers
        if (onComplete != null) callbacks[id] = onComplete
        persistTasks()
        startInternal(id)
        return id
    }

    private fun startInternal(id: Long) {
        if (turboIds.containsKey(id)) return
        val task = tasks[id] ?: return
        scope.launch {
            tasks[id] = task.copy(status = "downloading", error = "")
            val out = File(tmpDir, "task_${id}.part")
            val headers = headersCache[id] ?: task.headers
            val request = DownloadRequest(
                url = task.url,
                destination = out,
                headers = headers,
                knownSize = if (task.total > 0) task.total else -1,
                connectionsOverride = settings.maxConnections.coerceIn(1, 128),
                stableKey = "web-$id"
            )
            persistTasks()
            val turboId = client.submit(request)
            turboIds[id] = turboId
            turboToTask[turboId] = id
        }
    }

    fun pause(id: Long) {
        val turboId = turboIds.remove(id)
        if (turboId != null) turboToTask.remove(turboId)
        speeds.remove(id); etas.remove(id)
        scope.launch {
            if (turboId != null) runCatching { client.pause(turboId) }
            tasks[id]?.let { tasks[id] = it.copy(status = "paused") }
            persistTasks()
        }
    }

    fun resume(id: Long) {
        val t = tasks[id] ?: return
        if (t.status == "completed") return
        startInternal(id)
    }

    fun delete(id: Long, deleteFile: Boolean) {
        val turboId = turboIds.remove(id)
        if (turboId != null) turboToTask.remove(turboId)
        speeds.remove(id); etas.remove(id)
        callbacks.remove(id)
        scope.launch {
            if (turboId != null) runCatching { client.cancel(turboId, deleteOutput = true) }
            File(tmpDir, "task_${id}.part").delete()
            if (deleteFile) {
                tasks[id]?.savePath?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
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
            error = t.error, createdAt = t.createdAt
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
                if (t != null && (p.downloadedBytes != t.downloaded ||
                            (p.totalBytes > 0 && p.totalBytes != t.total))
                ) {
                    tasks[id] = t.copy(
                        downloaded = p.downloadedBytes,
                        total = if (p.totalBytes > 0) p.totalBytes else t.total
                    )
                    // 进度落盘节流：不做高频写
                    if (System.currentTimeMillis() - lastPersistTs > 5000) persistTasks()
                }
            }
            is TurboEvent.Completed -> scope.launch { onCompleted(id, ev.file, ev.totalBytes) }
            is TurboEvent.Failed -> scope.launch {
                turboIds.remove(id)?.let { turboToTask.remove(it) }
                speeds.remove(id); etas.remove(id)
                File(tmpDir, "task_${id}.part").delete()
                tasks[id]?.let { tasks[id] = it.copy(status = "failed", error = ev.reason) }
                persistTasks()
            }
            is TurboEvent.StateChanged -> {
                if (ev.state == TaskState.PAUSED || ev.state == TaskState.CANCELED) {
                    speeds.remove(id); etas.remove(id)
                }
            }
            else -> {}
        }
    }

    private suspend fun onCompleted(id: Long, file: File, totalBytes: Long) {
        val task = tasks[id] ?: return
        try {
            val dest = uniqueFile(resolvedDownloadsDir(), task.fileName)
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
            persistTasks()
            // 清理回调（如夸克临时转存目录）
            callbacks.remove(id)?.let { cb -> runCatching { cb() } }
        } catch (e: Exception) {
            tasks[id]?.let { tasks[id] = it.copy(status = "failed", error = "保存失败：${e.message}") }
            persistTasks()
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
                // 上次未完成的任务标记为已暂停（进程重启/崩溃恢复）
                val fixed = if (t.status == "downloading") t.copy(status = "paused") else t
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
