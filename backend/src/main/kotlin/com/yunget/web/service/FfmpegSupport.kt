package com.yunget.web.service

import com.yunget.web.model.FfmpegStatusResponse
import okhttp3.Request
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * ffmpeg 定位、探测与辅助安装（B站 DASH 分轨合轨用）。
 *
 * 定位顺序：① 设置项 ffmpegPath（source=manual）→ ② 应用托管安装
 * （{dataDir}/tools/ffmpeg/，source=app）→ ③ PATH 上的 ffmpeg（source=path）→
 * ④ 常见安装路径（source=system）。每个候选都实际跑一次 `ffmpeg -version`
 * （3 秒超时）确认可执行并取版本号，第一个通过的胜出。
 *
 * 结果按「当时生效的 ffmpegPath 设置值」缓存，但仅限不含任何失败成分的
 * 成功结果；未命中、或手动路径候选失败（hint 非空）时不缓存、每次重新
 * 探测，避免一过性失败被钉死（与 YtDlpSupport 一致）。
 *
 * 手动路径容错：设置值指向一个**目录**时（用户常把下载目录整个填进来），
 * 在该目录内按 ffmpeg / ffmpeg.exe 找可执行文件作为候选，status 返回的
 * path 为最终文件路径（见 resolveManualCandidate）。
 *
 * 辅助安装（[install]）：任意来源已检测到就直接返回现状、不打扰；否则按平台下载
 * 官方静态构建（Windows=gyan.dev / macOS=evermeet.cx / Linux=johnvansickle.com）
 * 到托管目录解压定位，全程给分步失败文案。
 */
class FfmpegSupport(private val dataDir: File) {

    private val managedDir: File get() = File(dataDir, "tools/ffmpeg")

    @Volatile
    private var cached: FfmpegStatusResponse? = null

    @Volatile
    private var cachedFor: String? = null

    fun status(customPath: String): FfmpegStatusResponse {
        val key = customPath.trim()
        cached?.let { if (cachedFor == key) return it }
        val (found, hint) = locate(key)
        val result = if (found != null) {
            FfmpegStatusResponse(
                available = true,
                version = found.version,
                path = found.file.absolutePath,
                source = found.source,
                manualHint = hint
            )
        } else {
            FfmpegStatusResponse(available = false, manualHint = hint)
        }
        // 只缓存「干净的成功」：整体未命中、或命中但结果里带着手动路径的
        // 失败原因（hint 非空）时都不缓存。失败可能只是一过性状态（冷启动
        // 超时、首次运行被系统扫描等），钉死会让同一路径永远显示旧结论，
        // 直到改路径或重启。未缓存时每次 status 重新探测——与 YtDlpSupport 一致。
        if (found != null && hint == null) {
            cachedFor = key
            cached = result
        }
        return result
    }

    /** 当前可用的 ffmpeg 可执行文件；不可用返回 null */
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
        // ① 设置项路径（文件或其所在目录，见 probeManual/resolveManualCandidate）
        if (customPath.isNotBlank()) {
            val (found, h) = probeManual(customPath)
            if (found != null) return found to null
            hint = h
        }
        // ② 应用托管安装
        findManagedExe()?.let { f ->
            probeVersion(f)?.let { return Found(f, it, "app") to hint }
        }
        // ③ PATH 上的 ffmpeg：相对名交给 ProcessBuilder 走 PATH 解析；
        //    探通后用 which 解析回绝对路径方便日志/展示（解析失败则退回相对名本身）
        probeVersion(null)?.let { version ->
            val resolved = whichFfmpeg() ?: File("ffmpeg")
            return Found(resolved, version, "path") to hint
        }
        // ④ 常见安装路径
        for (p in COMMON_PATHS) {
            val f = File(p)
            if (!f.isFile) continue
            probeVersion(f)?.let { return Found(f, it, "system") to hint }
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
                ?: return null to "该目录下未找到 ffmpeg 可执行文件：$value"
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
     * 找 ffmpeg / ffmpeg.exe（Windows 优先 .exe）；都找不到返回 null
     * （该层判无效、继续往下找，不误报）。
     */
    private fun resolveManualCandidate(raw: String): File? {
        val f = File(raw.trim())
        if (f.isFile) return f
        if (f.isDirectory) {
            val names = if (isWindows()) listOf("ffmpeg.exe", "ffmpeg") else listOf("ffmpeg", "ffmpeg.exe")
            for (name in names) {
                val child = File(f, name)
                if (child.isFile) return child
            }
        }
        return null
    }

    /** 托管目录内递归找 ffmpeg 可执行文件（解压结构因发行方而异：根目录或 bin/ 子目录） */
    private fun findManagedExe(): File? {
        val dir = managedDir
        if (!dir.isDirectory) return null
        val names = if (isWindows()) setOf("ffmpeg.exe") else setOf("ffmpeg", "ffmpeg.exe")
        return dir.walkTopDown()
            .firstOrNull { it.isFile && it.name in names }
    }

    // ------------------------------------------------------------------
    // 辅助安装
    // ------------------------------------------------------------------

    /**
     * 一键安装（同步阻塞，调用方需切到 IO 线程）：
     * 已检测到任意来源的 ffmpeg → 直接返回当前状态，不重复下载；
     * 否则清理托管目录残留后下载官方静态构建并安装，装完返回刷新后的状态。
     * 任一步失败抛 IllegalStateException，文案注明是哪一步（下载/解压/定位/探测）。
     */
    fun install(customPath: String, toolProxy: String = ""): FfmpegStatusResponse {
        status(customPath).let { if (it.available) return it }

        val pkg = packageFor() ?: throw IllegalStateException(
            "暂不支持在当前系统（${System.getProperty("os.name")} / ${System.getProperty("os.arch")}）自动安装 ffmpeg，请手动安装后在上方填写路径"
        )
        val dir = managedDir
        // 重装语义：托管目录已有（残留）文件先删净再装，避免半截文件干扰定位
        runCatching { dir.deleteRecursively() }
        dir.mkdirs()

        val archive = File(dir, pkg.fileName)
        try {
            download(pkg.url, archive, toolProxy)
        } catch (e: Exception) {
            throw IllegalStateException("下载 ffmpeg 安装包失败（网络或地址问题）：${e.message}${InstallHttp.PROXY_HINT}", e)
        }
        try {
            if (pkg.isZip) unzip(archive, dir) else untarXz(archive, dir)
        } catch (e: Exception) {
            throw IllegalStateException("解压 ffmpeg 安装包失败：${e.message}", e)
        } finally {
            runCatching { archive.delete() }
        }
        val exe = findManagedExe()
            ?: throw IllegalStateException("安装包已下载解压，但未在其中找到 ffmpeg 可执行文件")
        if (!isWindows()) runCatching { exe.setExecutable(true, false) }
        val version = probeVersion(exe)
            ?: throw IllegalStateException("ffmpeg 已解压到 ${exe.absolutePath}，但运行版本探测失败（二进制可能与本机架构不符）")
        runCatching { File(dir, "version.txt").writeText(version) }
        invalidate()
        println("[ffmpeg] 托管安装完成：${exe.absolutePath}（版本 $version，来源 ${pkg.url}）")
        return status(customPath)
    }

    private data class Pkg(val url: String, val fileName: String, val isZip: Boolean)

    /**
     * 按平台选官方静态构建包：
     * - Windows：gyan.dev 的 release-essentials（zip，内含 bin/ffmpeg.exe）；
     * - macOS：evermeet.cx 官方发布入口 getrelease/zip——该站当前版本提供 universal
     *   二进制（Intel / Apple Silicon 通用），这是唯一不区分架构的官方地址；
     *   若个别架构跑不起来，用户可改走 brew 手动安装或填写自定义路径兜底；
     * - Linux：johnvansickle.com 的 amd64/arm64 静态包（tar.xz）。
     */
    private fun packageFor(): Pkg? {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val arm = arch.contains("aarch64") || arch.contains("arm64")
        return when {
            os.contains("win") -> Pkg(
                "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip",
                "ffmpeg-pkg.zip", isZip = true
            )
            os.contains("mac") || os.contains("darwin") -> Pkg(
                "https://evermeet.cx/ffmpeg/getrelease/zip",
                "ffmpeg-pkg.zip", isZip = true
            )
            os.contains("linux") || os.contains("nux") -> Pkg(
                if (arm) "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-arm64-static.tar.xz"
                else "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz",
                "ffmpeg-pkg.tar.xz", isZip = false
            )
            else -> null
        }
    }

    /** 流式下载落盘（大文件不进内存）；HTTP 非 2xx 视为下载失败。toolProxy 非空时经工具安装代理下载 */
    private fun download(url: String, dest: File, toolProxy: String = "") {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "YunGet-Web")
            .build()
        InstallHttp.downloadClient(toolProxy).newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("响应为空")
            body.byteStream().use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
            }
        }
        if (dest.length() <= 0L) throw IllegalStateException("下载内容为空")
    }

    /** zip 解压（带 Zip-Slip 防护：条目必须落在本目录内） */
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

    /**
     * tar.xz 解压：org.tukaani:xz 纯 Java 解 XZ + 极简 tar 解析
     * （普通文件/目录、GNU longname 'L'、pax header 'x' 的 path= 覆盖；链接条目跳过）。
     * 带与 zip 相同的越界防护。tar 的 size 字段按八进制解析（静态包单文件 < 8GB，不涉及 base-256 编码）。
     */
    private fun untarXz(archive: File, destDir: File) {
        val root = destDir.canonicalFile
        val input = XZInputStream(BufferedInputStream(FileInputStream(archive)))
        input.use { stream ->
            val header = ByteArray(512)
            var longName: String? = null
            while (true) {
                if (!readFully(stream, header)) break
                if (header.all { it == 0.toByte() }) break // 结束零块
                var name = longName ?: tarString(header, 0, 100)
                longName = null
                val prefix = tarString(header, 345, 155)
                if (prefix.isNotBlank() && name.isNotBlank()) name = "$prefix/$name"
                val size = tarOctal(header, 124, 12)
                val type = header[156].toInt().toChar()
                when (type) {
                    'L' -> { // GNU longname：内容即下一条目的真实名
                        val buf = ByteArray(size.toInt())
                        readFully(stream, buf)
                        longName = buf.decodeToString().trimEnd('\u0000')
                        skipFully(stream, padded(size) - size)
                    }
                    'x', 'g' -> { // pax 扩展头：只取 path= 覆盖名，其余键忽略
                        val buf = ByteArray(size.toInt())
                        readFully(stream, buf)
                        paxPath(buf.decodeToString())?.let { longName = it }
                        skipFully(stream, padded(size) - size)
                    }
                    '5' -> { // 目录
                        val target = File(root, name).canonicalFile
                        if (target.path.startsWith(root.path + File.separator)) target.mkdirs()
                        skipFully(stream, padded(size) - size)
                    }
                    '0', '\u0000' -> { // 普通文件
                        val target = File(root, name).canonicalFile
                        if (!target.path.startsWith(root.path + File.separator)) {
                            throw IllegalStateException("安装包内含越界路径：$name")
                        }
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            var remaining = size
                            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (remaining > 0) {
                                val n = stream.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                if (n < 0) throw IllegalStateException("安装包内容不完整")
                                out.write(buf, 0, n)
                                remaining -= n
                            }
                        }
                        skipFully(stream, padded(size) - size)
                    }
                    else -> skipFully(stream, padded(size)) // 链接等其它条目：跳过
                }
            }
        }
    }

    private fun padded(size: Long): Long = ((size + 511) / 512) * 512

    private fun tarString(header: ByteArray, offset: Int, len: Int): String {
        var end = offset
        val limit = offset + len
        while (end < limit && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun tarOctal(header: ByteArray, offset: Int, len: Int): Long {
        val s = tarString(header, offset, len).trim()
        return if (s.isBlank()) 0L else s.toLongOrNull(8) ?: 0L
    }

    /** pax 记录形如 "<len> path=<value>\n"，取 path 的值 */
    private fun paxPath(content: String): String? {
        for (line in content.lineSequence()) {
            val idx = line.indexOf(" path=")
            if (idx >= 0) return line.substring(idx + 6).trim()
        }
        return null
    }

    private fun readFully(stream: java.io.InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = stream.read(buf, off, buf.size - off)
            if (n < 0) return off > 0
            off += n
        }
        return true
    }

    private fun skipFully(stream: java.io.InputStream, bytes: Long) {
        var remaining = bytes
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            val n = stream.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw IllegalStateException("安装包内容不完整")
            remaining -= n
        }
    }

    // ------------------------------------------------------------------
    // 探测
    // ------------------------------------------------------------------

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    /** `which ffmpeg` 取绝对路径；失败返回 null */
    private fun whichFfmpeg(): File? = runCatching {
        val proc = ProcessBuilder("which", "ffmpeg").redirectErrorStream(true).start()
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

    /**
     * 对候选跑 `ffmpeg -version` 取版本号。
     * @param file 候选文件；null 表示用 PATH 相对名 "ffmpeg"。
     * @return 版本号（如 "6.1.1"）；不可执行/超时/输出不符返回 null。
     */
    private fun probeVersion(file: File?): String? = probe(file).version

    private fun probe(file: File?): ProbeOutcome {
        val cmd = file?.absolutePath ?: "ffmpeg"
        if (file != null && !file.isFile) return ProbeOutcome(null, ProbeFail.NOT_RUNNABLE)
        val proc = try {
            ProcessBuilder(cmd, "-version")
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            // 无执行权限/不可执行格式等都在进程启动期抛 IOException
            return ProbeOutcome(null, ProbeFail.NOT_RUNNABLE)
        }
        if (!proc.waitFor(3, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return ProbeOutcome(null, ProbeFail.TIMEOUT)
        }
        if (proc.exitValue() != 0) return ProbeOutcome(null, ProbeFail.NOT_RUNNABLE)
        val firstLine = runCatching { proc.inputStream.bufferedReader().readLine() }.getOrNull().orEmpty()
        val m = Regex("""ffmpeg version\s+(\S+)""").find(firstLine)
        val version = m?.groupValues?.get(1)
        return if (version != null) ProbeOutcome(version, null)
        else ProbeOutcome(null, ProbeFail.BAD_OUTPUT)
    }

    companion object {
        private val COMMON_PATHS = listOf(
            "/opt/homebrew/bin/ffmpeg",
            "/usr/local/bin/ffmpeg",
            "/usr/bin/ffmpeg"
        )
    }
}
