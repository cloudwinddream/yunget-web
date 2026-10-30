package com.yunget.web.service

import com.yunget.web.model.DenoStatusResponse
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Deno 定位、探测与一键安装（yt-dlp 解析 YouTube 需要的 JavaScript 运行时；
 * 模式与 [YtDlpSupport] / [FfmpegSupport] 同构）。
 *
 * 背景：2026 版 yt-dlp 解析 YouTube 强制需要 JS 运行时，缺失时只返回故事板
 * （mhtml）伪格式、报「Requested format is not available」；且 yt-dlp 自身
 * 靠 PATH 找 deno 在部分机器上失效（真机实证：deno 明明装在
 * /opt/homebrew/bin，自动探测却报找不到），因此应用自己定位 deno 并在每次
 * 调用 yt-dlp 时显式传 `--js-runtimes deno:<路径>`（见 YtDlpSupport.run）。
 *
 * 定位顺序：① 设置项 denoPath（source=manual）→ ② 应用托管安装
 * （{dataDir}/tools/deno/，source=app）→ ③ PATH 上的 deno（source=path）→
 * ④ 常见安装路径（source=system）。每个候选实际跑一次 `deno --version`
 * （10 秒超时）确认可执行并取版本号（首行形如「deno 2.9.8 (stable, …)」）。
 * 结果按「当时生效的 denoPath 设置值」缓存，但仅限不含任何失败成分的
 * 成功结果；未命中、或手动路径候选失败（hint 非空）时不缓存、每次重新
 * 探测，避免一过性失败被钉死（与 YtDlpSupport v1.0.36 的口径一致）。
 *
 * 手动路径容错：设置值指向一个**目录**时，在该目录内按 deno / deno.exe
 * 找可执行文件作为候选，status 返回的 path 为最终文件路径。
 *
 * 一键安装（[install]）：任意来源已检测到就直接返回现状、不打扰；否则从
 * Deno 官方分发站 dl.deno.land 下载对应平台的 zip 包，解压出 deno 二进制
 * 落到托管目录。force=true 时跳过早退、强制重装（「更新」按钮）。
 */
class DenoSupport(private val dataDir: File) {

    private val managedDir: File get() = File(dataDir, "tools/deno")

    @Volatile
    private var cached: DenoStatusResponse? = null

    @Volatile
    private var cachedFor: String? = null

    fun status(customPath: String): DenoStatusResponse {
        val key = customPath.trim()
        cached?.let { if (cachedFor == key) return it }
        val (found, hint) = locate(key)
        val result = if (found != null) {
            DenoStatusResponse(
                available = true,
                version = found.version,
                path = found.file.absolutePath,
                source = found.source,
                manualHint = hint
            )
        } else {
            DenoStatusResponse(available = false, manualHint = hint)
        }
        // 只缓存「干净的成功」（与 YtDlpSupport 同口径）：整体未命中、或命中
        // 但带着手动路径失败原因（hint 非空）时都不缓存——失败可能只是一过
        // 性状态，钉死会让同一路径永远显示旧结论，直到改路径或重启。
        if (found != null && hint == null) {
            cachedFor = key
            cached = result
        }
        return result
    }

    /** 当前可用的 deno 可执行文件；不可用返回 null */
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
     * 命中，也把用户填错的路径原因透出去；定位顺序与胜负判定不因 hint 变化。
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
            val resolved = whichDeno() ?: File(if (isWindows()) "deno.exe" else "deno")
            return Found(resolved, version, "path") to hint
        }
        // ④ 常见安装路径：brew 装的 deno 落 /opt/homebrew/bin、官方安装脚本落
        //    ~/.deno/bin，而双击/服务方式启动的应用继承的 PATH 经常不含这些
        //    目录。Windows 无对应约定位置，跳过该层。
        if (!isWindows()) {
            for (p in commonPaths()) {
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
                ?: return null to "该目录下未找到 deno 可执行文件：$value"
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
     * 按 deno → deno.exe 依次找；都找不到返回 null（该层判无效、继续往下找）。
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
        val names = if (isWindows()) setOf("deno.exe") else setOf("deno", "deno.exe")
        return dir.walkTopDown().firstOrNull { it.isFile && it.name in names }
    }

    // ------------------------------------------------------------------
    // 一键安装
    // ------------------------------------------------------------------

    /**
     * 一键安装（同步阻塞，调用方需切到 IO 线程）：默认已检测到任意来源直接返回现状；
     * 否则清理托管目录残留后下载官方 zip 包，解压定位 deno、跑 --version 验证后
     * 写入 version.txt。force=true 时跳过早退、强制重装最新版（「更新」按钮）。
     * 失败抛 IllegalStateException，文案注明步骤（下载/解压/定位/验证）。
     *
     * @param toolProxy 工具安装代理（设置项 toolProxy，各家安装共用）
     */
    fun install(
        customPath: String,
        toolProxy: String = "",
        force: Boolean = false
    ): DenoStatusResponse {
        if (!force) status(customPath).let { if (it.available) return it }
        if (force) println("[deno] 强制更新：跳过现状早退，清托管目录重下最新版")

        val pkg = packageFor() ?: throw IllegalStateException(
            "暂不支持在当前系统（${System.getProperty("os.name")} / ${System.getProperty("os.arch")}）自动安装 Deno，请到 deno.com 手动下载后在上方填写路径"
        )
        val dir = managedDir
        runCatching { dir.deleteRecursively() }
        dir.mkdirs()

        val archive = File(dir, pkg.fileName)
        try {
            download(pkg.url, archive, toolProxy)
        } catch (e: Exception) {
            throw IllegalStateException("下载 Deno 失败（网络或地址问题）：${e.message}${InstallHttp.PROXY_HINT}", e)
        }
        try {
            unzip(archive, dir)
        } catch (e: Exception) {
            throw IllegalStateException("解压 Deno 安装包失败：${e.message}", e)
        } finally {
            runCatching { archive.delete() }
        }
        val exe = findManagedExe()
            ?: throw IllegalStateException("安装包已下载解压，但未在其中找到 deno 可执行文件")
        if (!isWindows()) runCatching { exe.setExecutable(true, false) }
        val version = probeVersion(exe)
            ?: throw IllegalStateException("Deno 已解压到 ${exe.absolutePath}，但运行版本探测失败（二进制可能与本机架构不符）")
        runCatching { File(dir, "version.txt").writeText(version) }
        invalidate()
        println("[deno] 托管安装完成：${exe.absolutePath}（版本 $version，来源 ${pkg.url}）")
        return status(customPath)
    }

    private data class Pkg(val url: String, val fileName: String)

    /** 按平台选 Deno 官方分发包（dl.deno.land 的 latest zip，内含 deno / deno.exe 单文件） */
    private fun packageFor(): Pkg? {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val arm = arch.contains("aarch64") || arch.contains("arm64")
        val target = when {
            os.contains("win") -> "x86_64-pc-windows-msvc"
            os.contains("mac") || os.contains("darwin") ->
                if (arm) "aarch64-apple-darwin" else "x86_64-apple-darwin"
            os.contains("linux") || os.contains("nux") ->
                if (arm) "aarch64-unknown-linux-gnu" else "x86_64-unknown-linux-gnu"
            else -> return null
        }
        return Pkg("https://dl.deno.land/release/latest/deno-$target.zip", "deno-pkg.zip")
    }

    /** 流式下载落盘；HTTP 非 2xx 视为失败（与 YtDlpSupport.download 同实现）。proxy 非空时经工具安装代理下载 */
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

    /** zip 解压（带 Zip-Slip 防护：条目必须落在本目录内；与 FfmpegSupport.unzip 同实现） */
    private fun unzip(archive: File, destDir: File) {
        val root = destDir.canonicalFile
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val target = File(root, entry.name).canonicalFile
                if (!target.path.startsWith(root.path + File.separator) && target != root) {
                    throw IllegalStateException("安装包内含越界路径：${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> zin.copyTo(out, DEFAULT_BUFFER_SIZE) }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }

    // ------------------------------------------------------------------
    // 探测
    // ------------------------------------------------------------------

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    private fun whichDeno(): File? = runCatching {
        val tool = if (isWindows()) "where" else "which"
        val name = if (isWindows()) "deno.exe" else "deno"
        val proc = ProcessBuilder(tool, name).redirectErrorStream(true).start()
        if (!proc.waitFor(3, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return null
        }
        if (proc.exitValue() != 0) return null
        val line = proc.inputStream.bufferedReader().readLine()?.trim().orEmpty()
        if (line.isBlank()) null else File(line)
    }.getOrNull()

    /** 探测失败分类（仅供手动路径的原因透出；定位胜负只看 version 是否为空） */
    private enum class ProbeFail { TIMEOUT, NOT_RUNNABLE, BAD_OUTPUT }

    private data class ProbeOutcome(val version: String?, val fail: ProbeFail?)

    /** 对候选跑 `deno --version`；首行形如「deno 2.9.8 (stable, release, …)」，取第二段为版本号。file=null 表示走 PATH 相对名 */
    private fun probeVersion(file: File?): String? = probe(file).version

    private fun probe(file: File?): ProbeOutcome {
        val cmd = file?.absolutePath ?: if (isWindows()) "deno.exe" else "deno"
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
        val version = first?.let { line ->
            val parts = line.split(Regex("\\s+"))
            // 期望形态：「deno 2.9.8 …」；宽容兜底：形如「2.9.8 …」的首段本身就是版本号时也认
            when {
                parts.size >= 2 && parts[0].equals("deno", ignoreCase = true) -> parts[1]
                parts.isNotEmpty() && parts[0].firstOrNull()?.isDigit() == true -> parts[0]
                else -> null
            }
        }
        return if (version != null) ProbeOutcome(version, null)
        else ProbeOutcome(null, ProbeFail.BAD_OUTPUT)
    }

    companion object {
        /** 版本探测超时（秒）；与 YtDlpSupport 同口径取 10 秒，给冷启动留足余量 */
        private const val PROBE_TIMEOUT_SEC = 10L

        /** 常见安装路径（POSIX）：brew / 发行版包 / 官方安装脚本默认位置 / 系统目录 */
        private fun commonPaths(): List<String> {
            val home = System.getProperty("user.home").orEmpty()
            return listOf(
                "/opt/homebrew/bin/deno",
                "/usr/local/bin/deno",
                File(home, ".deno/bin/deno").path,
                "/usr/bin/deno"
            )
        }

        /** 手动路径填成目录时，在其中找可执行文件的名字顺序 */
        private val MANUAL_EXE_NAMES = listOf("deno", "deno.exe")
    }
}
