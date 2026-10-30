package com.yunget.web.service

import com.yunget.app.data.network.HttpClients
import com.yunget.web.model.GopeedStatusResponse
import com.yunget.web.model.GopeedTaskView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File
import java.net.Proxy
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Gopeed 磁力/BT 引擎托管（官方二进制子进程版，移植自桌面版 YunX-Desktop 的 GopeedEngine +
 * GopeedKernelProvisioner，REST 契约与上游一致）。
 *
 * 与桌面版的差异：
 * - os/arch 按本机映射（windows/darwin/linux × amd64/arm64），不再写死 windows；
 * - 内核落在 `{dataDir}/gopeed/kernel/`，存储（bolt）在 `{dataDir}/gopeed/storage/`（卸载保留）；
 * - 状态用 @Volatile 字段维护（请求驱动，无 StateFlow）；
 * - 不下发桌面版的运行期全局配置（maxRunning/代理/BT 做种等不在本期范围），
 *   任务落盘目录一律走每个任务的 `opts.path` = 当前下载目录。
 *
 * 进程约定与桌面版一致：只绑 127.0.0.1 随机空闲端口、不设 API token；
 * 启动参数只传 gopeed 服务端真实存在的 `-A/-P/-d`（传不存在的参数会让它打印 usage 后退出）；
 * stdout/stderr 重定向到 `{dataDir}/gopeed/gopeed.log`。
 *
 * 线程约定：全部方法**阻塞**（进程操作 + OkHttp 同步调用），路由层须在 IO 调度器上调用。
 */
class GopeedService(
    private val dataDir: File,
    private val downloads: DownloadService
) {

    /** 引擎状态：未安装内核 / 已安装未运行 / 运行中 */
    enum class State { NOT_INSTALLED, INSTALLED, RUNNING }

    private val log = LoggerFactory.getLogger("gopeed")

    @Volatile
    private var state: State = State.NOT_INSTALLED

    /** 最近一次失败原因（原文，供排查） */
    @Volatile
    var lastError: String? = null
        private set

    /** 引擎 HTTP API 端口；未运行时为 0 */
    @Volatile
    var port: Int = 0
        private set

    @Volatile
    private var process: Process? = null

    private var shutdownHook: Thread? = null

    /** 进程生命周期串行化（start/stop/install/uninstall 互斥；synchronized 可重入） */
    private val lifecycleLock = Any()

    /** 专用于 localhost 的客户端：**必须绕开系统代理**，否则本地请求会被代理走 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    companion object {
        private const val RELEASES_LATEST_URL =
            "https://api.github.com/repos/GopeedLab/gopeed/releases/latest"
        private const val UA = "YunGet-Web"

        /** 启动后等待 HTTP API 就绪的上限（毫秒）；冷启动建库一般 1-3 秒 */
        private const val READY_TIMEOUT_MS = 20_000L

        /** 停止时等待进程自行退出的上限（毫秒），超时强杀 */
        private const val STOP_TIMEOUT_MS = 3_000L

        /** 本机 OS（Gopeed 官方资产名用词）：windows / darwin / linux */
        fun osKey(): String {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                os.contains("win") -> "windows"
                os.contains("mac") || os.contains("darwin") -> "darwin"
                else -> "linux"
            }
        }

        /** 本机架构（Gopeed 官方资产名用词）：aarch64/arm64 → arm64，其余 → amd64 */
        fun archKey(): String {
            val arch = System.getProperty("os.arch").orEmpty().lowercase()
            return if (arch.contains("aarch64") || arch.contains("arm64")) "arm64" else "amd64"
        }

        /** 内核可执行文件名：Windows 带 .exe，其余为裸名 */
        fun exeName(): String = if (osKey() == "windows") "gopeed.exe" else "gopeed"
    }

    // ---------------------------------------------------------------------------------------------
    // 目录与状态
    // ---------------------------------------------------------------------------------------------

    /** 引擎数据根目录 `{dataDir}/gopeed` */
    fun engineDir(): File = File(dataDir, "gopeed")

    /** 内核目录（下载的 zip 与解出的可执行文件都在这里） */
    private fun kernelDir(): File = File(engineDir(), "kernel")

    /** 内核可执行文件 */
    fun exeFile(): File = File(kernelDir(), exeName())

    /** 引擎存储目录（bolt 数据库）；卸载内核时保留，避免误删未完成任务的元数据 */
    private fun storageDir(): File = File(engineDir(), "storage")

    /** 子进程日志（stdout/stderr 重定向到这里） */
    private fun logFile(): File = File(engineDir(), "gopeed.log")

    private fun pidFile(): File = File(engineDir(), "engine.pid")

    /** 安装时记录的内核版本（未运行时 status 也能报出版本） */
    private fun versionFile(): File = File(engineDir(), "kernel-version.txt")

    fun isInstalled(): Boolean = exeFile().isFile

    fun isRunning(): Boolean = state == State.RUNNING && process?.isAlive == true

    /** 把内存状态与磁盘/进程事实对齐（exe 被外部删除、进程意外退出时纠偏） */
    private fun syncState() {
        state = when {
            isRunning() -> State.RUNNING
            isInstalled() -> State.INSTALLED
            else -> State.NOT_INSTALLED
        }
        if (state != State.RUNNING && process?.isAlive != true) {
            process = null
            port = 0
        }
    }

    /** 状态快照：{installed, running, version, os, arch} */
    fun status(): GopeedStatusResponse {
        syncState()
        return GopeedStatusResponse(
            installed = isInstalled(),
            running = isRunning(),
            version = currentVersion(),
            os = osKey(),
            arch = archKey()
        )
    }

    private fun currentVersion(): String {
        if (isRunning()) version()?.let { return it }
        return runCatching { versionFile().takeIf { it.isFile }?.readText()?.trim() }
            .getOrNull().orEmpty()
    }

    // ---------------------------------------------------------------------------------------------
    // 内核供应（下载官方 Release → 校验 → 解包）
    // ---------------------------------------------------------------------------------------------

    private data class KernelPlan(
        val version: String,
        val assetName: String,
        val url: String,
        val size: Long,
        /** 服务端给出的 sha256 摘要（形如 `sha256:xxxx`）；空串表示不校验 */
        val digest: String
    )

    /** 查询官方最新 Release，拼出本机可用的内核包计划 */
    private fun resolvePlan(): KernelPlan {
        val req = Request.Builder()
            .url(RELEASES_LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", UA)
            .build()
        val json = HttpClients.apiClient().newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("获取 Gopeed 官方 Release 失败（HTTP ${resp.code}）")
            }
            runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("Gopeed 官方 Release 响应格式异常")
            }
        }
        val tag = json.optString("tag_name")
        if (tag.isBlank()) throw IllegalStateException("Gopeed 官方 Release 缺少版本号")
        val wanted = "gopeed-web-$tag-${osKey()}-${archKey()}.zip"
        val asset = json.optJSONArray("assets")?.let { arr ->
            (0 until arr.length())
                .mapNotNull { arr.optJSONObject(it) }
                .firstOrNull { it.optString("name").equals(wanted, ignoreCase = true) }
        } ?: throw IllegalStateException("Gopeed 官方 Release（$tag）里没有本机可用的内核包 $wanted")
        val direct = asset.optString("browser_download_url")
        if (direct.isBlank()) throw IllegalStateException("内核包缺少下载地址")
        return KernelPlan(
            version = tag,
            assetName = wanted,
            url = direct,
            size = asset.optLong("size"),
            digest = asset.optString("digest")
        )
    }

    /** 流式下载内核包到 kernel 目录（每次重下，避免半截文件续传语义纠缠） */
    private fun downloadKernel(plan: KernelPlan): File {
        val dir = kernelDir().also { it.mkdirs() }
        val target = File(dir, plan.assetName)
        if (target.exists()) runCatching { target.delete() }
        val req = Request.Builder().url(plan.url).header("User-Agent", UA).build()
        HttpClients.downloadClient().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("内核下载失败（HTTP ${resp.code}）")
            val body = resp.body ?: throw IllegalStateException("内核下载响应为空")
            var downloaded = 0L
            target.outputStream().buffered().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        downloaded += read
                    }
                }
            }
            val total = body.contentLength().takeIf { it > 0 } ?: plan.size
            if (total > 0 && downloaded != total) {
                runCatching { target.delete() }
                throw IllegalStateException("内核下载不完整（$downloaded/$total 字节）")
            }
        }
        return target
    }

    /**
     * 校验 sha256：`digest` 形如 `sha256:xxxx`。
     * 长度不是 64（算法非 sha256 / 服务端未给）时跳过，不做无谓拦截。
     */
    private fun verifyDigest(file: File, digest: String) {
        val expected = digest.substringAfter(':', digest).trim().lowercase()
        if (expected.length != 64) return
        val actual = sha256(file)
        if (actual != expected) {
            runCatching { file.delete() }
            throw IllegalStateException("内核包校验失败（sha256 不匹配），已丢弃损坏的包")
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 从官方 zip 导入内核：在包内按名找可执行文件（任意层级），落到 kernel 目录。
     * 导入前先停引擎（运行中的 exe 在 Windows 上无法被覆盖）。
     */
    private fun installFromArchive(archive: File, version: String): File {
        if (isRunning()) stop()
        val target = exeFile()
        target.parentFile?.mkdirs()
        var found = false
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val entryName = entry.name.replace('\\', '/')
                if (!entry.isDirectory &&
                    entryName.substringAfterLast('/').equals(exeName(), ignoreCase = true)
                ) {
                    target.outputStream().buffered().use { out -> zip.copyTo(out) }
                    found = true
                    break
                }
                entry = zip.nextEntry
            }
        }
        if (!found) {
            throw IllegalStateException("内核包里没有找到 ${exeName()}（请确认下载的是 Gopeed 官方包）")
        }
        if (osKey() != "windows") {
            runCatching { target.setExecutable(true, false) }
        }
        runCatching { versionFile().writeText(version) }
        lastError = null
        state = State.INSTALLED
        log.info("Gopeed 内核已安装：{}（{} 字节，版本 {}）", target.absolutePath, target.length(), version)
        return target
    }

    /** 安装最新版内核（解析 → 下载 → 校验 → 解包），同步执行 */
    fun install(): GopeedStatusResponse {
        synchronized(lifecycleLock) {
            val plan = resolvePlan()
            log.info("Gopeed 内核安装计划：{}（{} 字节）", plan.assetName, plan.size)
            val archive = downloadKernel(plan)
            try {
                if (plan.digest.isNotBlank()) verifyDigest(archive, plan.digest)
                installFromArchive(archive, plan.version)
            } finally {
                // 导入后删除临时包（保留会白占 ~20MB）
                runCatching { archive.delete() }
            }
        }
        return status()
    }

    /** 删除内核（先停引擎）；存储目录保留，避免误删用户未完成任务的元数据 */
    fun uninstall(): GopeedStatusResponse {
        synchronized(lifecycleLock) {
            if (isRunning()) stop()
            runCatching { exeFile().delete() }
            runCatching { versionFile().delete() }
            state = State.NOT_INSTALLED
            log.info("Gopeed 内核已删除")
        }
        return status()
    }

    // ---------------------------------------------------------------------------------------------
    // 进程生命周期
    // ---------------------------------------------------------------------------------------------

    /**
     * 启动引擎（幂等：已在运行直接返回端口）。默认下载目录取当前设置，
     * 实际落盘由每个任务的 `opts.path` 决定，这里只负责建好目录。
     * @return HTTP API 端口
     */
    fun start(): Int {
        synchronized(lifecycleLock) {
            if (isRunning()) return port
            val exe = exeFile()
            if (!exe.isFile) throw IllegalStateException("还没有安装 Gopeed 内核，请先在设置页安装")
            lastError = null
            // 上一次应用被强杀可能留下孤儿进程：它仍占着 bolt 存储锁，必须先清掉
            killStaleProcess()
            val downloadDir = downloads.resolvedDownloadsDir()
            val localPort = pickFreePort()
            val args = listOf(
                exe.absolutePath,
                "-A", "127.0.0.1",
                "-P", localPort.toString(),
                "-d", storageDir().absolutePath
            )
            try {
                val logF = logFile().also { it.parentFile?.mkdirs() }
                val p = ProcessBuilder(args)
                    .directory(engineDir())
                    // stdout/stderr 落地到文件：既不阻塞子进程，也留一份可排查的运行日志
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logF))
                    .redirectError(ProcessBuilder.Redirect.appendTo(logF))
                    .start()
                process = p
                port = localPort
                registerShutdownHook()
                if (!awaitReady()) {
                    // 进程还活着 ⇒ 真的没在限时内响应；已经退出 ⇒ 多半是启动参数/内核不匹配，
                    // 此时把日志路径一并给出，避免统一报成「启动超时」误导排查方向。
                    val exitCode = runCatching { p.exitValue() }.getOrNull()
                    runCatching { p.destroyForcibly() }
                    process = null
                    port = 0
                    val detail = if (exitCode != null) {
                        "Gopeed 进程已退出（退出码 $exitCode），未能提供 HTTP 服务；" +
                            "详见子进程日志 ${logF.absolutePath}"
                    } else {
                        "Gopeed 启动超时（${READY_TIMEOUT_MS / 1000}s 内未响应 /api/v1/info），" +
                            "详见 ${logF.absolutePath}"
                    }
                    lastError = detail
                    throw IllegalStateException(detail)
                }
                runCatching { pidFile().writeText(p.pid().toString()) }
                state = State.RUNNING
                log.info(
                    "Gopeed 已启动：pid={} port={} 版本={} 下载目录={}",
                    p.pid(), port, version() ?: "未知", downloadDir.absolutePath
                )
                return port
            } catch (e: Exception) {
                lastError = e.message
                log.error("Gopeed 启动失败：{}", e.message)
                throw e
            }
        }
    }

    /** 停止引擎（幂等）：先正常结束，超时强杀 */
    fun stop() {
        synchronized(lifecycleLock) {
            val p = process
            process = null
            runCatching { pidFile().takeIf { it.isFile }?.delete() }
            port = 0
            if (p != null) {
                runCatching {
                    p.destroy()
                    if (!p.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        log.warn("Gopeed 未在 {}ms 内退出，强制结束", STOP_TIMEOUT_MS)
                        p.destroyForcibly()
                        p.waitFor(2, TimeUnit.SECONDS)
                    }
                }.onFailure { log.warn("停止 Gopeed 异常：{}", it.message) }
            }
            state = if (isInstalled()) State.INSTALLED else State.NOT_INSTALLED
            log.info("Gopeed 已停止")
        }
    }

    /** 重启：先停再起 */
    fun restart(): Int {
        stop()
        return start()
    }

    /**
     * 清掉上一次应用被强杀后残留的引擎进程（按 pid 文件精确匹配，不误伤用户自己装的 Gopeed）。
     * 匹配条件：pid 存活 且 该进程可执行文件路径就是本应用的内核。
     */
    private fun killStaleProcess() {
        val pid = pidFile().takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: return
        pidFile().delete()
        if (process?.pid() == pid) return
        val handle = ProcessHandle.of(pid).orElse(null) ?: return
        if (!handle.isAlive) return
        val cmd = runCatching { handle.info().command().orElse("") }.getOrDefault("")
        if (!cmd.equals(exeFile().absolutePath, ignoreCase = true)) {
            log.warn("pid {} 存活但不是本应用的内核（{}），跳过清理", pid, cmd)
            return
        }
        log.warn("清理残留 Gopeed 进程 pid={}", pid)
        runCatching { handle.destroyForcibly() }
    }

    private fun registerShutdownHook() {
        if (shutdownHook != null) return
        val hook = Thread { runCatching { stop() } }
        shutdownHook = hook
        runCatching { Runtime.getRuntime().addShutdownHook(hook) }
    }

    /** 取一个本机空闲端口（先探测再释放；与引擎实际绑定之间存在极小竞态） */
    private fun pickFreePort(): Int = ServerSocket(0).use { it.localPort }

    /** 轮询 `/api/v1/info` 直到引擎就绪 */
    private fun awaitReady(): Boolean {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            process?.let { if (!it.isAlive) return false }
            if (runCatching { infoJson() }.isSuccess) return true
            Thread.sleep(200L)
        }
        return false
    }

    // ---------------------------------------------------------------------------------------------
    // 业务前置与 REST 客户端
    // ---------------------------------------------------------------------------------------------

    /** 业务调用前置：未安装报友好错误；已安装未运行则自动启动 */
    fun ensureRunning() {
        if (!isInstalled()) throw IllegalStateException("请先在设置页安装 Gopeed 内核")
        if (!isRunning()) start()
    }

    /** 引擎核心版本（`GET /api/v1/info` 的 `data.version`） */
    fun version(): String? =
        runCatching { infoJson().optString("version").takeIf { it.isNotBlank() } }.getOrNull()

    private fun infoJson(): JSONObject = get("/api/v1/info")

    /**
     * 新建下载任务（磁力/BT 或 http 直链，Gopeed 本就都支持）。
     * 落盘目录固定为当前下载目录（`opts.path`)；[name] 给定时作为落盘名下发。
     * @return 引擎任务 ID
     */
    fun createTask(url: String, name: String = ""): String {
        ensureRunning()
        val saveDir = downloads.resolvedDownloadsDir().absolutePath
        val req = JSONObject().apply { put("url", url) }
        val opts = JSONObject().apply {
            put("path", saveDir)
            if (name.isNotBlank()) put("name", name)
        }
        val body = JSONObject().apply {
            put("req", req)
            put("opts", opts)
        }
        // 注意：新建任务的 `data` 是**任务 ID 字符串**，不是对象（与查询类接口不同）
        val id = requestEnvelope("POST", "/api/v1/tasks", body.toString()).optString("data").trim()
        if (id.isBlank()) throw IllegalStateException("Gopeed 未返回任务 ID")
        log.info("Gopeed 建任务：id={} url={}", id, url.take(120))
        return id
    }

    /**
     * 全部任务的归一化列表（供前端磁力任务卡渲染）。
     * 字段映射（与桌面版 taskStatus 的实测结论一致）：
     * - 名称：`meta.res.name`（磁力要等元数据解析后才有），缺失时按 req.url 兜底；
     * - 总大小：`meta.res.size`（progress 里**没有** total);
     * - 已下载/速度：`progress.downloaded` / `progress.speed`；
     * - 状态：任务对象顶层 `status`（ready/running/wait/pause/error/done）。
     */
    fun listTasks(): List<GopeedTaskView> {
        ensureRunning()
        val tasks = getList("/api/v1/tasks")
        return (0 until tasks.length()).mapNotNull { i ->
            val t = tasks.optJSONObject(i) ?: return@mapNotNull null
            val meta = t.optJSONObject("meta")
            val res = meta?.optJSONObject("res")
            val progress = t.optJSONObject("progress")
            val name = res?.optString("name")?.takeIf { it.isNotBlank() }
                ?: t.optString("name").takeIf { it.isNotBlank() }
                ?: fallbackName(meta?.optJSONObject("req")?.optString("url").orEmpty())
            GopeedTaskView(
                id = t.optString("id"),
                name = name,
                total = res?.optLong("size") ?: 0L,
                downloaded = progress?.optLong("downloaded") ?: 0L,
                speed = progress?.optLong("speed") ?: 0L,
                status = t.optString("status")
            )
        }
    }

    private fun fallbackName(url: String): String = when {
        url.startsWith("magnet:") -> "磁力任务"
        url.isBlank() -> "未知任务"
        else -> url.substringAfterLast('/').substringBefore('?').ifBlank { "下载任务" }
    }

    fun pauseTask(id: String) {
        ensureRunning()
        requestEnvelope("PUT", "/api/v1/tasks/$id/pause", null)
    }

    fun continueTask(id: String) {
        ensureRunning()
        requestEnvelope("PUT", "/api/v1/tasks/$id/continue", null)
    }

    fun deleteTask(id: String) {
        ensureRunning()
        requestEnvelope("DELETE", "/api/v1/tasks/$id", null)
    }

    /** `GET` 列表类接口：`data` 是 JSON 数组（与返回对象的 [get] 区分） */
    private fun getList(path: String): JSONArray =
        requestEnvelope("GET", path, null).optJSONArray("data") ?: JSONArray()

    /** `GET`：返回信封里的 `data` 对象（查询类接口的 `data` 都是对象） */
    private fun get(path: String): JSONObject =
        requestEnvelope("GET", path, null).optJSONObject("data") ?: JSONObject()

    /**
     * 发一次请求并解析统一信封 `{code,msg,data}`，返回整个信封。
     * `code != 0` 或 HTTP 非 2xx 一律抛 [IllegalStateException]（消息取服务端 `msg`，便于直接展示）。
     */
    private fun requestEnvelope(method: String, path: String, body: String?): JSONObject {
        val currentPort = port
        if (currentPort <= 0) throw IllegalStateException("Gopeed 引擎未运行")
        val url = "http://127.0.0.1:$currentPort$path"
        val builder = Request.Builder().url(url)
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> {
                val payload = (body ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType())
                if (method == "POST") builder.post(payload) else builder.put(payload)
            }
        }
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (text.isBlank()) {
                if (!resp.isSuccessful) throw IllegalStateException("Gopeed 返回 HTTP ${resp.code}")
                return JSONObject()
            }
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("Gopeed 响应不是合法 JSON（HTTP ${resp.code}）：${text.take(200)}")
            }
            val code = json.optInt("code")
            if (code != 0) {
                val msg = json.optString("msg").takeIf { it.isNotBlank() } ?: "Gopeed 调用失败（code=$code）"
                throw IllegalStateException(msg)
            }
            return json
        }
    }
}
