package com.yunget.web.service

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
 * （{dataDir}/tools/yt-dlp/，source=app）→ ③ PATH 上的 yt-dlp（source=path）→
 * ④ 常见安装路径（source=system，与 [FfmpegSupport] 同名同义；Windows 无对应
 * 约定俗成位置，跳过该层）。应用进程继承的 PATH 常缺 Homebrew 的
 * /opt/homebrew/bin，第 ④ 层就是为 brew/manual 安装兜的底。
 * 每个候选实际跑一次 `yt-dlp --version`（10 秒超时——官方单文件为 PyInstaller
 * 打包、冷启动解压慢，3 秒会误杀真机可用的二进制）确认可执行并取版本号。
 * 结果按「当时生效的 ytdlpPath 设置值」缓存，但仅限不含任何失败成分的
 * 成功结果；未命中、或手动路径候选失败（hint 非空）时不缓存、每次重新
 * 探测，避免冷启动超时等一过性失败被钉死。
 *
 * 手动路径容错：设置值指向一个**目录**时（用户常把下载目录整个填进来），
 * 在该目录内按 yt-dlp / yt-dlp_macos / yt-dlp.exe 找可执行文件作为候选，
 * status 返回的 path 为最终文件路径（见 [resolveManualCandidate]）。
 *
 * 一键安装（[install]）：任意来源已检测到就直接返回现状、不打扰；否则从
 * yt-dlp 官方 GitHub Release 下载单文件二进制（Win=yt-dlp.exe / mac=yt-dlp_macos /
 * Linux=yt-dlp_linux，aarch64 用 yt-dlp_linux_aarch64）落到托管目录。
 *
 * Cookie：账号页保存的是浏览器复制的 Cookie 头字符串，而 yt-dlp 的 --cookies
 * 只认 Netscape 格式文件。[cookieFileFor] 负责转换并落盘到
 * {dataDir}/tools/yt-dlp/cookies.txt（权限收紧到仅属主可读、解析后不删、复用）。
 */
class YtDlpSupport(
    private val dataDir: File,
    /**
     * Deno 运行时可执行文件的惰性解析（构造注入，与全工程 provider 惯例一致）：
     * 每次 run 调用时现取——解析成功返回 deno 的绝对路径，run 会显式追加
     * `--js-runtimes deno:<路径>`（yt-dlp 自身靠 PATH 找 deno 在部分机器上
     * 失效，真机实证）；解析不到返回 null，run 不带该参数、行为与旧版一致。
     * 默认 null（无 Deno 集成时不传参）。
     */
    private val denoPathProvider: () -> String? = { null }
) {

    private val managedDir: File get() = File(dataDir, "tools/yt-dlp")

    /** 最近一次 run 捕获的 yt-dlp WARNING 行（已去首尾空白）；run 每次覆写，诊断用 */
    @Volatile
    private var lastRunWarnings: List<String> = emptyList()

    /** 最近一次 run 实际携带的 Cookie 条数；null = 未携带（未保存/转换不出有效条目）。run 每次覆写 */
    @Volatile
    private var lastRunCookieCount: Int? = null

    /** 最近一次 run 捕获的 WARNING 行（成功路径不消费，仅解析全档失败时由调用方挑相关行透出） */
    fun lastWarnings(): List<String> = lastRunWarnings

    /** 最近一次 run 实际携带的 Cookie 条数（null = 未携带），供解析失败指引区分「没带上」与「带了仍被拦」 */
    fun lastCookieCount(): Int? = lastRunCookieCount

    @Volatile
    private var cached: YtDlpStatusResponse? = null

    @Volatile
    private var cachedFor: String? = null

    fun status(customPath: String): YtDlpStatusResponse {
        val key = customPath.trim()
        cached?.let { if (cachedFor == key) return it }
        val (found, hint) = locate(key)
        val result = if (found != null) {
            YtDlpStatusResponse(
                available = true,
                version = found.version,
                path = found.file.absolutePath,
                source = found.source,
                manualHint = hint
            )
        } else {
            YtDlpStatusResponse(available = false, manualHint = hint)
        }
        // 只缓存「干净的成功」：整体未命中、或命中但结果里带着手动路径的
        // 失败原因（hint 非空）时都不缓存。失败可能只是一过性状态（冷启动
        // 超时、首次运行被系统扫描拦截等），钉死会让同一路径永远显示旧结论
        // （未检测到、或错的来源+过时原因），直到改路径或重启。未缓存时每次
        // status 重新探测——二进制不存在时各层都是毫秒级落空，代价可忽略。
        if (found != null && hint == null) {
            cachedFor = key
            cached = result
        }
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

    /**
     * 定位主流程，返回 (命中项, 手动路径无效原因)。
     * hint 仅在「手动路径已设置但该候选探测失败」时非空——即使最终靠其它层
     * 命中，也把用户填错的路径原因透出去，省得对着「未检测到」无线索；
     * 定位顺序与胜负判定不因 hint 有任何变化。
     */
    private fun locate(customPath: String): Pair<Found?, String?> {
        var hint: String? = null
        if (customPath.isNotBlank()) {
            val (found, h) = probeManual(customPath)
            if (found != null) return found to null
            hint = h
        }
        findManagedExe()?.let { f ->
            probeVersion(f)?.let { return Found(f, it, "app") to hint }
        }
        probeVersion(null)?.let { version ->
            val resolved = whichYtDlp() ?: File(if (isWindows()) "yt-dlp.exe" else "yt-dlp")
            return Found(resolved, version, "path") to hint
        }
        // ④ 常见安装路径（与 FfmpegSupport 的 system 层同名同义）：
        //    brew 装的 yt-dlp 落 /opt/homebrew/bin，而双击/服务方式启动的应用
        //    继承的 PATH 经常不含该目录。Windows 无对应约定位置，跳过该层。
        if (!isWindows()) {
            for (p in COMMON_PATHS) {
                val f = File(p)
                if (!f.isFile) continue
                probeVersion(f)?.let { return Found(f, it, "system") to hint }
            }
        }
        return null to hint
    }

    /**
     * 手动路径候选探测 + 失败原因分类，返回 (命中项, 原因)。
     * 原因按形态区分：路径不存在 / 是目录但目录内未找到二进制 /
     * 文件存在但运行失败（权限被拒、退出码非 0、输出不符）/ 运行超时。
     */
    private fun probeManual(raw: String): Pair<Found?, String?> {
        val value = raw.trim()
        val f = File(value)
        if (!f.exists()) return null to "路径不存在：$value"
        if (f.isDirectory) {
            val cand = resolveManualCandidate(value)
                ?: return null to "该目录下未找到 yt-dlp 可执行文件：$value"
            val o = probe(cand)
            return if (o.version != null) Found(cand, o.version, "manual") to null
            else null to manualRunHint(o.fail, cand)
        }
        val o = probe(f)
        return if (o.version != null) Found(f, o.version, "manual") to null
        else null to manualRunHint(o.fail, f)
    }

    /** 探测已执行但失败时的文案：超时单独一类，其余（启动被拒/非 0 退出/输出不符）归「无法运行」 */
    private fun manualRunHint(fail: ProbeFail?, file: File): String = when (fail) {
        ProbeFail.TIMEOUT -> "运行超时：${file.absolutePath}"
        else -> "文件无法运行（可能缺少执行权限或被系统拦截）：${file.absolutePath}"
    }

    /**
     * 手动路径候选解析：设置值指向文件 → 就是它；指向**目录** → 在目录内
     * 按 yt-dlp → yt-dlp_macos → yt-dlp.exe 依次找（用户从 GitHub 下载的
     * Mac 二进制名叫 yt-dlp_macos，常把所在目录整个填进路径框）；
     * 都找不到返回 null（该层判无效、继续往下找，不误报）。
     */
    private fun resolveManualCandidate(raw: String): File? {
        val f = File(raw.trim())
        if (f.isFile) return f
        if (f.isDirectory) {
            for (name in MANUAL_EXE_NAMES) {
                val child = File(f, name)
                if (child.isFile) return child
            }
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
     * 一键安装（同步阻塞，调用方需切到 IO 线程）：默认已检测到任意来源直接返回现状；
     * 否则清理托管目录残留后下载官方单文件二进制，跑 --version 验证后写入 version.txt。
     * force=true 时跳过早退、强制重下最新版（「更新」按钮）。
     * 失败抛 IllegalStateException，文案注明步骤（下载/验证）。
     */
    /**
     * @param toolProxy 工具安装代理（设置项 toolProxy，三家安装共用，优先）
     * @param fallbackProxy 兜底代理（设置项 ytdlpProxy——用户可能早先在 yt-dlp 卡里填过）；
     *   toolProxy 为空时才用它，避免用户填过一处却对安装不生效。
     * @param force true = 强制更新：跳过「已检测到就返回现状」的早退，清托管目录
     *   重下官方最新版（即使命中的是手动路径/PATH 也照下，装到托管目录；
     *   定位顺序不变，装完状态可能仍显示手动/PATH 来源）。注意托管目录里的
     *   cookies.txt 会随目录清理被删，但源 Cookie 在账号存储里，下次解析自动重建。
     */
    fun install(
        customPath: String,
        toolProxy: String = "",
        fallbackProxy: String = "",
        force: Boolean = false
    ): YtDlpStatusResponse {
        if (!force) status(customPath).let { if (it.available) return it }
        if (force) println("[yt-dlp] 强制更新：跳过现状早退，清托管目录重下最新版")

        val pkg = packageFor() ?: throw IllegalStateException(
            "暂不支持在当前系统（${System.getProperty("os.name")} / ${System.getProperty("os.arch")}）自动安装 yt-dlp，请到 github.com/yt-dlp/yt-dlp 手动下载后在上方填写路径"
        )
        val dir = managedDir
        runCatching { dir.deleteRecursively() }
        dir.mkdirs()

        val exe = File(dir, pkg.fileName)
        val effectiveProxy = toolProxy.trim().ifBlank { fallbackProxy.trim() }
        try {
            download(pkg.url, exe, effectiveProxy)
        } catch (e: Exception) {
            throw IllegalStateException("下载 yt-dlp 失败（网络或地址问题）：${e.message}${InstallHttp.PROXY_HINT}", e)
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

    /** 流式下载落盘；HTTP 非 2xx 视为失败（与 FfmpegSupport.download 同实现）。proxy 非空时经工具安装代理下载 */
    private fun download(url: String, dest: File, proxy: String = "") {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "YunGet-Web")
            .build()
        InstallHttp.downloadClient(proxy).newCall(request).execute().use { resp ->
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
        // JS 运行时显式传参：2026 版 yt-dlp 解析 YouTube 强制需要 Deno，且它
        // 自身靠 PATH 探测 deno 在部分机器上失效（应用进程 PATH 不全）；应用
        // 已定位到 deno 时每次调用都带上 --js-runtimes，探测不到则不带、照旧跑。
        runCatching { denoPathProvider() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { denoPath ->
            cmd += "--js-runtimes"
            cmd += "deno:$denoPath"
        }
        if (proxy.isNotBlank()) {
            cmd += "--proxy"
            cmd += proxy.trim()
        }
        lastRunCookieCount = null
        if (!cookieHeader.isNullOrBlank()) {
            cookieFileFor(cookieHeader)?.let { (f, count) ->
                cmd += "--cookies"
                cmd += f.absolutePath
                lastRunCookieCount = count
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
        val rawText = String(outFuture.get(5, TimeUnit.SECONDS), Charsets.UTF_8)
        // 警告行单独捕获、并从返回文本中剥离：调用方为拿到警告已不再传
        // --no-warnings，WARNING 行混在合并输出里若原样返回会污染 -J 的 JSON
        // 提取；捕获的警告经 lastWarnings() 供解析全档失败时挑相关行透出。
        val rawLines = rawText.lines()
        lastRunWarnings = rawLines.map { it.trim() }.filter { it.startsWith("WARNING:") }
        val text = rawLines.filterNot { it.trimStart().startsWith("WARNING:") }
            .joinToString("\n")
        if (proc.exitValue() != 0) {
            // 失败文案取输出尾部：yt-dlp 失败时 stdout 会打一个 JSON 字面量 "null"
            // （-J 无结果时的占位），混在合并输出里会变成「…；null」的怪尾巴，先滤掉；
            // 有 ERROR 行时优先只取 ERROR 行（真正的失败原因在那里）。
            val lines = text.trim().lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && it != "null" }
            val errLines = lines.filter { it.startsWith("ERROR") }
            val tail = (errLines.ifEmpty { lines }).takeLast(3).joinToString("；")
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
    /**
     * 生成 Cookie 文件并回带**有效条目数**（供解析失败指引注明本次到底带没带上
     * Cookie、带了几条）：Netscape 原文按非注释条目行数计（「#HttpOnly_」前缀
     * 行是导出工具写的真实条目、计入；「#」注释与空行不计）；头字符串按转换
     * 出的 k=v 对数计。转换不出有效条目返回 null。
     */
    private fun cookieFileFor(cookieHeader: String): Pair<File, Int>? = synchronized(this) {
        val raw = cookieHeader.trim()
        if (raw.isBlank()) return null
        val dir = managedDir.apply { mkdirs() }
        val file = File(dir, "cookies.txt")
        var entryCount = 0
        val content = if (raw.contains("# Netscape", ignoreCase = true)) {
            entryCount = raw.lines().count { line ->
                val t = line.trim()
                t.isNotBlank() && (!t.startsWith("#") || t.startsWith("#HttpOnly_"))
            }
            if (entryCount <= 0) return null
            if (raw.endsWith("\n")) raw else raw + "\n"
        } else {
            val pairs = raw.split(';').mapNotNull { part ->
                val p = part.trim()
                val eq = p.indexOf('=')
                if (eq <= 0) null else p.substring(0, eq).trim() to p.substring(eq + 1).trim()
            }.filter { it.first.isNotBlank() }
            if (pairs.isEmpty()) return null
            entryCount = pairs.size
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
        file to entryCount
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

    /** 探测失败分类（仅供手动路径的原因透出；定位胜负只看 version 是否为空，语义与旧版一致） */
    private enum class ProbeFail { TIMEOUT, NOT_RUNNABLE, BAD_OUTPUT }

    private data class ProbeOutcome(val version: String?, val fail: ProbeFail?)

    /** 对候选跑 `yt-dlp --version`；输出首行即版本号（如 2025.10.14）。file=null 表示走 PATH 相对名 */
    private fun probeVersion(file: File?): String? = probe(file).version

    private fun probe(file: File?): ProbeOutcome {
        val cmd = file?.absolutePath ?: if (isWindows()) "yt-dlp.exe" else "yt-dlp"
        if (file != null && !file.isFile) return ProbeOutcome(null, ProbeFail.NOT_RUNNABLE)
        val proc = try {
            ProcessBuilder(cmd, "--version")
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            // 无执行权限/不可执行格式等都在进程启动期抛 IOException
            return ProbeOutcome(null, ProbeFail.NOT_RUNNABLE)
        }
        if (!proc.waitFor(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return ProbeOutcome(null, ProbeFail.TIMEOUT)
        }
        if (proc.exitValue() != 0) return ProbeOutcome(null, ProbeFail.NOT_RUNNABLE)
        val first = runCatching { proc.inputStream.bufferedReader().readLine() }.getOrNull()
            ?.trim()?.takeIf { it.isNotBlank() }
        return if (first != null) ProbeOutcome(first, null)
        else ProbeOutcome(null, ProbeFail.BAD_OUTPUT)
    }

    companion object {
        /**
         * 版本探测超时（秒）。yt-dlp 官方单文件是 PyInstaller 打包，冷启动要先
         * 解压到临时目录，首跑常超 3 秒（用户真机 yt-dlp_macos 即如此），故放宽
         * 到 10 秒；ffmpeg 是原生二进制启动快，维持其自家的 3 秒、两边独立。
         */
        private const val PROBE_TIMEOUT_SEC = 10L

        /** 常见安装路径（POSIX；与 FfmpegSupport 的同名清单同构、仅工具名不同；~/.local/bin 为用户级手动安装的常见落点） */
        private val COMMON_PATHS: List<String>
            get() = listOf(
                "/opt/homebrew/bin/yt-dlp",
                "/usr/local/bin/yt-dlp",
                File(System.getProperty("user.home").orEmpty(), ".local/bin/yt-dlp").path,
                "/usr/bin/yt-dlp"
            )

        /** 手动路径填成目录时，在其中找可执行文件的名字顺序 */
        private val MANUAL_EXE_NAMES = listOf("yt-dlp", "yt-dlp_macos", "yt-dlp.exe")
    }
}
