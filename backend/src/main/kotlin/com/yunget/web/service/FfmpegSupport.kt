package com.yunget.web.service

import com.yunget.app.data.network.HttpClients
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
 * 探测结果按「当时生效的 ffmpegPath 设置值」缓存：设置一变，下次查询自动重新探测。
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
        val found = locate(key)
        val result = if (found != null) {
            FfmpegStatusResponse(
                available = true,
                version = found.version,
                path = found.file.absolutePath,
                source = found.source
            )
        } else {
            FfmpegStatusResponse(available = false)
        }
        cachedFor = key
        cached = result
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

    private fun locate(customPath: String): Found? {
        // ① 设置项路径
        if (customPath.isNotBlank()) {
            val f = File(customPath)
            probeVersion(f)?.let { return Found(f, it, "manual") }
        }
        // ② 应用托管安装
        findManagedExe()?.let { f ->
            probeVersion(f)?.let { return Found(f, it, "app") }
        }
        // ③ PATH 上的 ffmpeg：相对名交给 ProcessBuilder 走 PATH 解析；
        //    探通后用 which 解析回绝对路径方便日志/展示（解析失败则退回相对名本身）
        probeVersion(null)?.let { version ->
            val resolved = whichFfmpeg() ?: File("ffmpeg")
            return Found(resolved, version, "path")
        }
        // ④ 常见安装路径
        for (p in COMMON_PATHS) {
            val f = File(p)
            if (!f.isFile) continue
            probeVersion(f)?.let { return Found(f, it, "system") }
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
    fun install(customPath: String): FfmpegStatusResponse {
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
            download(pkg.url, archive)
        } catch (e: Exception) {
            throw IllegalStateException("下载 ffmpeg 安装包失败（网络或地址问题）：${e.message}", e)
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

    /** 流式下载落盘（大文件不进内存）；HTTP 非 2xx 视为下载失败 */
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

    /**
     * 对候选跑 `ffmpeg -version` 取版本号。
     * @param file 候选文件；null 表示用 PATH 相对名 "ffmpeg"。
     * @return 版本号（如 "6.1.1"）；不可执行/超时/输出不符返回 null。
     */
    private fun probeVersion(file: File?): String? = runCatching {
        val cmd = file?.absolutePath ?: "ffmpeg"
        if (file != null && !file.isFile) return null
        val proc = ProcessBuilder(cmd, "-version")
            .redirectErrorStream(true)
            .start()
        if (!proc.waitFor(3, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return null
        }
        if (proc.exitValue() != 0) return null
        val firstLine = proc.inputStream.bufferedReader().readLine().orEmpty()
        val m = Regex("""ffmpeg version\s+(\S+)""").find(firstLine)
        m?.groupValues?.get(1)
    }.getOrNull()

    companion object {
        private val COMMON_PATHS = listOf(
            "/opt/homebrew/bin/ffmpeg",
            "/usr/local/bin/ffmpeg",
            "/usr/bin/ffmpeg"
        )
    }
}
