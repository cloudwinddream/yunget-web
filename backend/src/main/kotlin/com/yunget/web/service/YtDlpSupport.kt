package com.yunget.web.service

import com.yunget.app.data.network.HttpClients
import com.yunget.web.model.YtDlpStatusResponse
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * yt-dlp 定位、探测、一键安装与进程执行（YouTube 解析用；模式仿 [FfmpegSupport]）。
 *
 * 定位顺序：① 设置项 ytdlpPath（source=manual）→ ② 应用托管安装
 * （{dataDir}/tools/yt-dlp/，source=app）→ ③ PATH 上的 yt-dlp（source=path）。
 * 每个候选实际跑一次 `yt-dlp --version`（3 秒超时）确认可执行并取版本号。
 * 探测结果按「当时生效的 ytdlpPath 设置值」缓存，设置一变自动重探。
 *
 * 一键安装（[install]）：任意来源已检测到就直接返回现状、不打扰；否则从
 * yt-dlp 官方 GitHub Release 下载单文件二进制（Win=yt-dlp.exe / mac=yt-dlp_macos /
 * Linux=yt-dlp_linux，aarch64 用 yt-dlp_linux_aarch64）落到托管目录。
 *
 * Cookie：账号页保存的是浏览器复制的 Cookie 头字符串，而 yt-dlp 的 --cookies
 * 只认 Netscape 格式文件。[cookieFileFor] 负责转换并落盘到
 * {dataDir}/tools/yt-dlp/cookies.txt（权限收紧到仅属主可读、解析后不删、复用）。
 */
class YtDlpSupport(private val dataDir: File) {

    private val managedDir: File get() = File(dataDir, "tools/yt-dlp")

    @Volatile
    private var cached: YtDlpStatusResponse? = null

    @Volatile
    private var cachedFor: String? = null

    fun status(customPath: String): YtDlpStatusResponse {
        val key = customPath.trim()
        cached?.let { if (cachedFor == key) return it }
        val found = locate(key)
        val result = if (found != null) {
            YtDlpStatusResponse(
                available = true,
                version = found.version,
                path = found.file.absolutePath,
                source = found.source
            )
        } else {
            YtDlpStatusResponse(available = false)
        }
        cachedFor = key
        cached = result
        return result
    }

    /** 当前可用的 yt-dlp 可执行文件；不可用返回 null */
    fun executable(customPath: String): File? {
        val st = status(customPath)
        return if (st.available) File(st.path) else null
    }

    fun invalidate() {
        cached = null
        cachedFor = null
    }

    private data class Found(val file: File, val version: String, val source: String)

    private fun locate(customPath: String): Found? {
        if (customPath.isNotBlank()) {
            val f = File(customPath)
            probeVersion(f)?.let { return Found(f, it, "manual") }
        }
        findManagedExe()?.let { f ->
            probeVersion(f)?.let { return Found(f, it, "app") }
        }
        probeVersion(null)?.let { version ->
            val resolved = whichYtDlp() ?: File(if (isWindows()) "yt-dlp.exe" else "yt-dlp")
            return Found(resolved, version, "path")
        }
        return null
    }

    private fun findManagedExe(): File? {
        val dir = managedDir
        if (!dir.isDirectory) return null
        val names = if (isWindows()) setOf("yt-dlp.exe") else setOf("yt-dlp", "yt-dlp.exe")
        return dir.walkTopDown().firstOrNull { it.isFile && it.name in names }
    }

    // ------------------------------------------------------------------
    // 一键安装
    // ------------------------------------------------------------------

    /**
     * 一键安装（同步阻塞，调用方需切到 IO 线程）：已检测到任意来源直接返回现状；
     * 否则清理托管目录残留后下载官方单文件二进制，跑 --version 验证后写入 version.txt。
     * 失败抛 IllegalStateException，文案注明步骤（下载/验证）。
     */
    fun install(customPath: String): YtDlpStatusResponse {
        status(customPath).let { if (it.available) return it }

        val pkg = packageFor() ?: throw IllegalStateException(
            "暂不支持在当前系统（${System.getProperty("os.name")} / ${System.getProperty("os.arch")}）自动安装 yt-dlp，请到 github.com/yt-dlp/yt-dlp 手动下载后在上方填写路径"
        )
        val dir = managedDir
        runCatching { dir.deleteRecursively() }
        dir.mkdirs()

        val exe = File(dir, pkg.fileName)
        try {
            download(pkg.url, exe)
        } catch (e: Exception) {
            throw IllegalStateException("下载 yt-dlp 失败（网络或地址问题）：${e.message}", e)
        }
        if (!isWindows()) runCatching { exe.setExecutable(true, false) }
        val version = probeVersion(exe)
            ?: throw IllegalStateException("yt-dlp 已下载到 ${exe.absolutePath}，但运行版本探测失败（二进制可能与本机架构不符）")
        runCatching { File(dir, "version.txt").writeText(version) }
        invalidate()
        println("[yt-dlp] 托管安装完成：${exe.absolutePath}（版本 $version，来源 ${pkg.url}）")
        return status(customPath)
    }

    private data class Pkg(val url: String, val fileName: String)

    private fun packageFor(): Pkg? {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val arm = arch.contains("aarch64") || arch.contains("arm64")
        val base = "https://github.com/yt-dlp/yt-dlp/releases/latest/download"
        return when {
            os.contains("win") -> Pkg("$base/yt-dlp.exe", "yt-dlp.exe")
            os.contains("mac") || os.contains("darwin") -> Pkg("$base/yt-dlp_macos", "yt-dlp")
            os.contains("linux") || os.contains("nux") ->
                if (arm) Pkg("$base/yt-dlp_linux_aarch64", "yt-dlp")
                else Pkg("$base/yt-dlp_linux", "yt-dlp")
            else -> null
        }
    }

    /** 流式下载落盘；HTTP 非 2xx 视为失败（与 FfmpegSupport.download 同实现） */
    private fun download(url: String, dest: File) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "YunGet-Web")
            .build()
        HttpClients.downloadClient().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("响应为空")
            body.byteStream().use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
            }
        }
        if (dest.length() <= 0L) throw IllegalStateException("下载内容为空")
    }

    // ------------------------------------------------------------------
    // 进程执行
    // ------------------------------------------------------------------

    /**
     * 跑一次 yt-dlp 并返回合并输出（stdout+stderr，UTF-8）。
     *
     * @param customPath 设置项 ytdlpPath（定位用）
     * @param proxy 设置项 ytdlpProxy，非空时追加 `--proxy <值>`
     * @param cookieHeader 账号页保存的 Cookie（头字符串或 Netscape 原文），
     *   非空时经 [cookieFileFor] 转换落盘并追加 `--cookies <文件>`
     * @param timeoutSec 超时（秒）；超时强杀并抛「解析超时」
     * 退出码非 0 抛 IllegalStateException 并带上输出尾部（yt-dlp 的 ERROR 行在里面）。
     * 同步阻塞，调用方需在 IO 线程/协程调用。
     */
    fun run(
        customPath: String,
        proxy: String,
        cookieHeader: String?,
        args: List<String>,
        timeoutSec: Long
    ): String {
        val exe = executable(customPath)
            ?: throw IllegalStateException("未检测到 yt-dlp，请到设置页一键安装")
        val cmd = mutableListOf(exe.absolutePath)
        if (proxy.isNotBlank()) {
            cmd += "--proxy"
            cmd += proxy.trim()
        }
        if (!cookieHeader.isNullOrBlank()) {
            cookieFileFor(cookieHeader)?.let { f ->
                cmd += "--cookies"
                cmd += f.absolutePath
            }
        }
        cmd += args
        val proc = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: Exception) {
            throw IllegalStateException("启动 yt-dlp 失败：${e.message}", e)
        }
        // 独立线程抽干输出，避免管道写满把子进程憋死
        val outFuture = CompletableFuture.supplyAsync {
            runCatching { proc.inputStream.readBytes() }.getOrDefault(ByteArray(0))
        }
        if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw IllegalStateException("解析超时（超过 ${timeoutSec} 秒），请稍后重试或检查代理设置")
        }
        val text = String(outFuture.get(5, TimeUnit.SECONDS), Charsets.UTF_8)
        if (proc.exitValue() != 0) {
            val tail = text.trim().lines().filter { it.isNotBlank() }.takeLast(3).joinToString("；")
            throw IllegalStateException(
                "yt-dlp 解析失败：${tail.ifBlank { "退出码 ${proc.exitValue()}" }}".take(600)
            )
        }
        return text
    }

    // ------------------------------------------------------------------
    // Cookie 转换（浏览器 Cookie 头字符串 → Netscape 格式文件）
    // ------------------------------------------------------------------

    /**
     * 把账号页保存的 Cookie 备成 yt-dlp 能吃的 --cookies 文件，返回文件或 null（转换不出有效条目时）。
     *
     * 存放位置：{dataDir}/tools/yt-dlp/cookies.txt（与托管二进制同目录），
     * 权限收紧为仅属主读写（POSIX 系统），解析后**不删除**、后续解析直接复用，
     * 内容随账号 Cookie 更新而覆写。
     *
     * 转换规则：
     * - 内容含 "# Netscape" → 认定为 Netscape 原文，原样落盘；
     * - 否则按 Cookie 头字符串解析："k=v; k2=v2" 逐对拆分（值可含 '='），
     *   每条写成 Netscape 行：域名固定 ".youtube.com"、includeSubdomains=TRUE、
     *   path=/、secure=TRUE、expiry=2030-01-01（1893456000）。
     *   说明：从 youtube.com 页面请求复制的 Cookie 头，本就是浏览器发给
     *   youtube.com 的那一份，原样按 .youtube.com 落盘即等价复现；
     *   风险：__Host- 前缀的 Cookie 按规范应为 host-only（无点域名），
     *   这里统一加点域名，yt-dlp 对此宽容、一般可正常发送；个别以 .google.com
     *   为域的会话 Cookie（如某些地区的 SID 变体）在 youtube.com 请求中本就会
     *   被浏览器合并发送，按 .youtube.com 落盘与复制来源一致。
     */
    private fun cookieFileFor(cookieHeader: String): File? = synchronized(this) {
        val raw = cookieHeader.trim()
        if (raw.isBlank()) return null
        val dir = managedDir.apply { mkdirs() }
        val file = File(dir, "cookies.txt")
        val content = if (raw.contains("# Netscape", ignoreCase = true)) {
            if (raw.endsWith("\n")) raw else raw + "\n"
        } else {
            val pairs = raw.split(';').mapNotNull { part ->
                val p = part.trim()
                val eq = p.indexOf('=')
                if (eq <= 0) null else p.substring(0, eq).trim() to p.substring(eq + 1).trim()
            }.filter { it.first.isNotBlank() }
            if (pairs.isEmpty()) return null
            buildString {
                appendLine("# Netscape HTTP Cookie File")
                appendLine("# 由 YunGet Web 从浏览器 Cookie 头字符串转换生成")
                for ((k, v) in pairs) {
                    append(".youtube.com\tTRUE\t/\tTRUE\t1893456000\t")
                    append(k).append('\t').append(v).append('\n')
                }
            }
        }
        try {
            if (!file.isFile || file.readText() != content) {
                file.writeText(content)
                tightenPermissions(file)
            }
        } catch (e: Exception) {
            println("[yt-dlp] 写 Cookie 文件失败：${e.message}")
            return null
        }
        file
    }

    private fun tightenPermissions(file: File) {
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                PosixFilePermissions.fromString("rw-------")
            )
        }
        // 非 POSIX（Windows）走 File API 兜底
        runCatching {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
    }

    // ------------------------------------------------------------------
    // 探测
    // ------------------------------------------------------------------

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    private fun whichYtDlp(): File? = runCatching {
        val tool = if (isWindows()) "where" else "which"
        val name = if (isWindows()) "yt-dlp.exe" else "yt-dlp"
        val proc = ProcessBuilder(tool, name).redirectErrorStream(true).start()
        if (!proc.waitFor(3, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return null
        }
        if (proc.exitValue() != 0) return null
        val line = proc.inputStream.bufferedReader().readLine()?.trim().orEmpty()
        if (line.isBlank()) null else File(line)
    }.getOrNull()

    /** 对候选跑 `yt-dlp --version`；输出首行即版本号（如 2025.10.14）。file=null 表示走 PATH 相对名 */
    private fun probeVersion(file: File?): String? = runCatching {
        val cmd = file?.absolutePath ?: if (isWindows()) "yt-dlp.exe" else "yt-dlp"
        if (file != null && !file.isFile) return null
        val proc = ProcessBuilder(cmd, "--version")
            .redirectErrorStream(true)
            .start()
        if (!proc.waitFor(3, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return null
        }
        if (proc.exitValue() != 0) return null
        proc.inputStream.bufferedReader().readLine()?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
