package com.yunget.web.service

import com.yunget.app.data.network.GitHubApi
import com.yunget.app.data.network.GitHubLinkParser
import com.yunget.app.data.network.GitHubLinkType
import com.yunget.app.data.network.HttpClients
import com.yunget.app.data.network.video.BilibiliParser
import com.yunget.app.data.network.video.DetectedVideoLink
import com.yunget.app.data.network.video.VideoHttp
import com.yunget.app.data.network.video.VideoMedia
import com.yunget.app.data.network.video.VideoLinkParser
import com.yunget.app.data.network.video.VideoParseException
import com.yunget.app.data.network.video.VideoParseResult
import com.yunget.app.data.network.video.VideoParsers
import com.yunget.app.data.network.video.VideoPlatform
import com.yunget.app.data.network.video.userMessage
import com.yunget.web.model.BilibiliAccountResponse
import com.yunget.web.model.FfmpegStatusResponse
import com.yunget.web.model.GitHubItemDto
import com.yunget.web.model.GitHubParseResponse
import com.yunget.web.model.VideoListDownloadResponse
import com.yunget.web.model.VideoListEntryResultDto
import com.yunget.web.model.VideoListEpisodeDto
import com.yunget.web.model.VideoListInfoDto
import com.yunget.web.model.VideoMediaItemDto
import com.yunget.web.model.VideoParseResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val SESSION_TTL_MS = 30L * 60 * 1000

/**
 * 视频解析 + GitHub 解析的会话层（协议实现整段移植自上游 YunGet 的
 * `data/network/video` 包与 GitHub 数据层，本类只做会话托管与下载入队）。
 *
 * 与网盘会话同理：解析产出的**真实媒体 URL 与请求头只存服务端**，前端只拿到
 * 会话 ID + 媒体下标，下载时按下标回查，避免直链（多带签名/时效）出现在页面里。
 *
 * - 视频：仅 B站接 Cookie 登录（存 AccountStore 的 "bilibili" 键），登录后解析
 *   BilibiliParser 才会走 DASH 高清分轨；其它平台 cookie 传 ""（需要登录的平台
 *   由解析器自己抛 UNSUPPORTED 并给出可操作提示）；
 * - B站 DASH 分轨：检测到 ffmpeg 时两轨下到暂存目录、齐了自动合轨为单个 mp4；
 *   无 ffmpeg 时维持双文件下载（前端 needsMux 明示）；
 * - GitHub：无 Token 匿名访问（限额 60 次/小时/IP），只处理公开仓库。
 */
class VideoService(
    private val downloadService: DownloadService,
    private val accounts: AccountStore,
    dataDir: File
) {

    private val ffmpeg = FfmpegSupport(dataDir)
    private val muxScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        /** AccountStore 中 B站登录态的键（与网盘 Platform 枚举无关，单独存放） */
        const val BILIBILI_KEY = "bilibili"
        /** 合轨暂存目录（下载目录下）；两轨先下到这里，合轨成功后清理 */
        const val MUX_STAGING_DIR = ".yunget-mux"
    }

    // ------------------------------------------------------------------
    // 视频
    // ------------------------------------------------------------------

    private data class VideoSession(
        val id: String,
        val result: VideoParseResult,
        /** 作品 ID（识别阶段的 DetectedVideoLink.id，如 B站 BV 号；命名模板 {id} 用） */
        val workId: String = "",
        /** 所属 B站合集（web 层补查得到）；非合集视频为 null */
        val season: BilibiliSeason.Info? = null,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val videoSessions = ConcurrentHashMap<String, VideoSession>()

    suspend fun parseVideo(link: String): VideoParseResponse {
        cleanupExpired()
        val detected = try {
            resolveDetected(link)
        } catch (e: VideoParseException) {
            throw IllegalStateException(e.userMessage())
        } ?: throw IllegalArgumentException("无法识别视频链接")
        val parser = VideoParsers.of(detected.platform)
            ?: throw IllegalArgumentException("暂不支持${detected.platform.displayName}")
        // 仅 B站注入登录 Cookie（解锁 DASH 高清）；其它平台保持匿名
        val cookie = if (detected.platform == VideoPlatform.BILIBILI) {
            accounts.get(BILIBILI_KEY)?.cookie?.takeIf { it.isNotBlank() } ?: ""
        } else ""
        val result = try {
            parser.parse(detected, cookie = cookie)
        } catch (e: VideoParseException) {
            throw IllegalStateException(e.userMessage())
        }
        if (result.medias.isEmpty()) throw IllegalStateException("没有解析到可下载的媒体")
        // B站合集补查（web 层）：上游解析器不建模合集，这里用 view 接口的 ugc_season
        // 字段补齐整单信息；任何失败都静默降级为普通单视频，绝不拖垮本次解析
        val season = if (detected.platform == VideoPlatform.BILIBILI) {
            val bvid = detected.id.takeIf { it.startsWith("BV", ignoreCase = true) }
                ?: result.url.substringAfterLast('/').takeIf { it.startsWith("BV", ignoreCase = true) }
            if (bvid != null) fetchSeasonInfo(bvid, cookie) else null
        } else null
        // 标题口径（用户真机实证）：合集成员的 data.title 是总标题，当前集在合集内的
        // episodes[].title 才是该集的真实标题——展示标题与下载文件名的 {title} 都以
        // 它为准（web 层覆盖，解析器不改）；找不到对应集条目（currentIndex=0）或集
        // 标题为空时回退总标题。原始总标题不另设字段保留：合集语境下前端另有合集
        // 区块展示合集名（listInfo.title），语义不丢。
        val currentEp = season?.episodes?.firstOrNull { it.index == season.currentIndex }
        val effectiveResult = if (currentEp != null && currentEp.title.isNotBlank()) {
            result.copy(title = currentEp.title)
        } else {
            result
        }
        val id = UUID.randomUUID().toString()
        videoSessions[id] = VideoSession(id, effectiveResult, workId = detected.id, season = season)
        return VideoParseResponse(
            sessionId = id,
            platform = effectiveResult.platform.name,
            platformName = effectiveResult.platform.displayName,
            title = effectiveResult.title,
            author = result.author,
            cover = result.cover,
            text = result.text,
            notice = result.notice,
            medias = effectiveResult.medias.mapIndexed { i, m ->
                VideoMediaItemDto(
                    index = i,
                    kind = m.kind.name,
                    // 展示名与下载落盘名同源：都走命名模板渲染（见 renderedName）
                    fileName = renderedName(effectiveResult, detected.id, i, m),
                    size = m.size,
                    quality = m.quality,
                    isHls = m.isHls,
                    needsMux = m.audioUrl.isNotBlank()
                )
            },
            listInfo = season?.let { s ->
                VideoListInfoDto(
                    kind = "season",
                    id = s.id,
                    title = s.title,
                    folder = s.folder,
                    total = s.episodes.size,
                    currentIndex = s.currentIndex,
                    episodes = s.episodes.map { e ->
                        VideoListEpisodeDto(e.index, e.bvid, e.title, e.duration)
                    }
                )
            }
        )
    }

    /**
     * 补查 B站合集信息：`x/web-interface/view` 的 `data.ugc_season`。请求头照
     * BilibiliParser.apiHeaders 同款（UA_CHROME + Referer bilibili.com + 已保存的
     * B站 Cookie 若有）。接口失败 / 不是合集 / 集数不足一律返回 null 并在日志留
     * 一行原因（静默降级，不影响单视频解析结果）。
     */
    private suspend fun fetchSeasonInfo(bvid: String, cookie: String): BilibiliSeason.Info? {
        val body = try {
            withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
                    .header("User-Agent", VideoHttp.UA_CHROME)
                    .header("Referer", "https://www.bilibili.com/")
                    .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
                    .build()
                HttpClients.apiClient().newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        println("[season] 合集补查 HTTP ${resp.code}（bvid=$bvid），按无合集处理")
                        null
                    } else {
                        resp.body?.string()
                    }
                }
            }
        } catch (e: Exception) {
            println("[season] 合集补查请求失败（bvid=$bvid）：${e.message}，按无合集处理")
            return null
        } ?: return null
        return BilibiliSeason.parse(body, bvid) { reason ->
            println("[season] 合集补查降级（bvid=$bvid）：$reason")
        }
    }

    /**
     * 按设置里的命名模板渲染一条媒体的最终文件名（基名走 [VideoNaming] 渲染 +
     * 解析器原名的扩展名另拼）。解析响应展示与下载入队共用此函数，保证两处一致；
     * 模板在两次调用之间被改动时，以下载时刻的设置为准（设置即时生效的既有语义）。
     */
    private fun renderedName(result: VideoParseResult, workId: String, index: Int, m: VideoMedia): String {
        val rawName = m.fileName.ifBlank { fileNameOf(m.url) }
        val vars = VideoNaming.Vars(
            title = result.title,
            author = result.author,
            id = workId,
            platform = result.platform.name,
            quality = m.quality,
            date = VideoNaming.formatDate(result.timestampSeconds),
            index = index + 1
        )
        val base = VideoNaming.renderBase(downloadService.settings.videoNameTemplate, vars, rawName)
        val ext = mediaExt(rawName, m.url)
        return if (ext.isNotBlank()) "$base.$ext" else base
    }

    /** 扩展名：优先解析器原名里的，其次媒体 URL 路径里的；形态不像扩展名（1–5 位字母数字）则放弃 */
    private fun mediaExt(rawName: String, url: String): String {
        fun sane(s: String): String? {
            val e = s.lowercase()
            return if (e.length in 1..5 && e.all { it.isLetterOrDigit() }) e else null
        }
        return sane(rawName.substringAfterLast('.', "")) ?: sane(url.substringBefore('?').substringAfterLast('.', "")) ?: ""
    }

    /**
     * 识别 + 短链展开：解析器都按 `link.id` 取数，短链（v.douyin.com / b23.tv /
     * xhslink 等）的 id 为空，必须先跟随重定向拿到终址再重新识别
     * （[VideoLinkParser] 的类注释里写明的调用方职责）。最多跟 5 跳。
     */
    private suspend fun resolveDetected(text: String): DetectedVideoLink? {
        var detected = VideoLinkParser.parse(text) ?: return null
        var hops = 0
        while (detected.isShortLink && hops < 5) {
            val next = VideoHttp.expandRedirect(detected.url) ?: break
            detected = VideoLinkParser.parse(next) ?: break
            hops++
        }
        return detected.takeIf { !it.isShortLink }
    }

    // ------------------------------------------------------------------
    // B站账号（Cookie 登录，仅用于解锁 DASH 高清；存 AccountStore 独立键）
    // ------------------------------------------------------------------

    fun bilibiliAccount(): BilibiliAccountResponse {
        val acc = accounts.get(BILIBILI_KEY)
        val loggedIn = !acc?.cookie.isNullOrBlank()
        return BilibiliAccountResponse(
            loggedIn = loggedIn,
            nickname = if (loggedIn) acc?.nickname ?: "" else ""
        )
    }

    /** 用 BilibiliParser.validateCookie 校验（nav 接口回昵称），通过才保存登录态 */
    suspend fun loginBilibili(cookie: String): BilibiliAccountResponse {
        val c = cookie.trim()
        if (c.isBlank()) throw IllegalArgumentException("请粘贴 Cookie")
        val nickname = BilibiliParser.validateCookie(c)
            ?: throw IllegalStateException(
                "B站 Cookie 校验失败：未登录或 Cookie 已过期（也可能是网络不通），请重新从浏览器复制完整的 Cookie"
            )
        accounts.save(BILIBILI_KEY, AccountData(cookie = c, nickname = nickname))
        return BilibiliAccountResponse(loggedIn = true, nickname = nickname)
    }

    fun logoutBilibili() {
        accounts.remove(BILIBILI_KEY)
    }

    // ------------------------------------------------------------------
    // ffmpeg（合轨支持：状态查询 / 一键安装，定位逻辑在 FfmpegSupport）
    // ------------------------------------------------------------------

    fun ffmpegStatus(): FfmpegStatusResponse =
        ffmpeg.status(downloadService.settings.ffmpegPath)

    /** 同步阻塞（下载安装包），路由层需包 Dispatchers.IO */
    fun installFfmpeg(): FfmpegStatusResponse =
        ffmpeg.install(downloadService.settings.ffmpegPath, downloadService.settings.toolProxy)

    /**
     * 按下标把媒体入队下载。返回新建的任务 ID 列表。
     *
     * - HLS 媒体（AcFun 的 m3u8）：URL 原样入队即可——下载引擎里 HLS 插件
     *   （turbo-plugin-hls）的 `HlsBackend.supports` 按「http(s) 且 path 以 .m3u8 结尾」
     *   认领，DownloadService 已装该插件，不需要任何特殊标记；
     * - DASH 分轨媒体（B站，audioUrl 非空）：
     *   - 检测到 ffmpeg：两轨改下到暂存目录 `.yunget-mux/<groupId>`（固定名 v.m4s/a.m4s），
     *     两边 onComplete 计数、齐了自动合轨成单个 mp4 落到下载目录根（见 [runMux]）；
     *   - 无 ffmpeg：维持双文件——视频轨用原名，音轨名为「原名（去扩展）.audio.m4s」，
     *     前端据 needsMux 明示需自行合轨。
     */
    suspend fun downloadVideo(sessionId: String, indexes: List<Int>): List<Long> {
        val s = videoSessions[sessionId]
            ?: throw IllegalStateException("解析会话已过期，请重新解析")
        val wanted = if (indexes.isEmpty()) s.result.medias.indices.toList() else indexes.distinct()
        val canMux = ffmpeg.executable(downloadService.settings.ffmpegPath) != null
        val ids = mutableListOf<Long>()
        for (i in wanted) {
            val m = s.result.medias.getOrNull(i) ?: continue
            ids += enqueueMedia(s.result, s.workId, i, m, relPath = "", canMux = canMux)
        }
        if (ids.isEmpty()) throw IllegalArgumentException("没有可下载的媒体")
        return ids
    }

    /**
     * 一条媒体的入队（单文件 / 分轨合轨 / 无 ffmpeg 时分轨双文件），返回新建的任务 ID。
     * [relPath] 非空时文件落到下载目录的该子目录下（列表批量下载的统一规则，见
     * [VideoNaming.folderName]）；单视频下载传 "" 落根目录，行为不变。最终文件名走
     * 命名模板渲染（与解析响应展示同源，见 renderedName），{index} = index + 1。
     */
    private suspend fun enqueueMedia(
        result: VideoParseResult,
        workId: String,
        index: Int,
        m: VideoMedia,
        relPath: String,
        canMux: Boolean
    ): List<Long> {
        val name = renderedName(result, workId, index, m)
        if (m.audioUrl.isNotBlank() && canMux) {
            return enqueueMuxPair(m, name, relPath)
        }
        val ids = mutableListOf<Long>()
        ids += downloadService.enqueue(
            url = m.url,
            fileName = name,
            headers = m.headers,
            size = m.size,
            platform = "video",
            resumeKey = "video-" + Integer.toHexString(m.url.hashCode()),
            relPath = relPath
        )
        if (m.audioUrl.isNotBlank()) {
            ids += downloadService.enqueue(
                url = m.audioUrl,
                fileName = audioFileName(name),
                headers = m.audioHeaders,
                size = -1,
                platform = "video",
                resumeKey = "video-" + Integer.toHexString(m.audioUrl.hashCode()) + "-audio",
                relPath = relPath
            )
        }
        return ids
    }

    /**
     * B站合集批量下载：逐集**顺序**以该集 bvid 重新走单视频解析（带已保存的 B站
     * Cookie），按定死规则选媒体——优先 DASH 分轨对（audioUrl 非空者），无分轨取
     * 第一条。说明：BilibiliParser.parse 恒只回一条媒体，且其 DASH 路径内部已取
     * 最高画质（dashQuality 按流 id 取 max），所以一般情形下「第一条」即最优。
     * {index} 命名变量 = 合集内序号；成品统一落到「合集名」文件夹（[BilibiliSeason.Info.folder]）。
     * 单集失败不中断其余，逐条结果聚合返回。
     */
    suspend fun downloadSeasonList(sessionId: String, indexes: List<Int>): VideoListDownloadResponse {
        val s = videoSessions[sessionId]
            ?: throw IllegalStateException("解析会话已过期，请重新解析")
        val season = s.season
            ?: throw IllegalArgumentException("该视频不属于合集，无法按合集批量下载")
        val wanted = if (indexes.isEmpty()) season.episodes
        else season.episodes.filter { it.index in indexes.toSet() }
        if (wanted.isEmpty()) throw IllegalArgumentException("请先勾选要下载的集数")
        val cookie = accounts.get(BILIBILI_KEY)?.cookie?.takeIf { it.isNotBlank() } ?: ""
        val parser = VideoParsers.of(VideoPlatform.BILIBILI)
            ?: throw IllegalStateException("暂不支持${VideoPlatform.BILIBILI.displayName}")
        val canMux = ffmpeg.executable(downloadService.settings.ffmpegPath) != null
        val results = mutableListOf<VideoListEntryResultDto>()
        for (ep in wanted) {
            try {
                val link = DetectedVideoLink(
                    platform = VideoPlatform.BILIBILI,
                    kind = "video",
                    id = ep.bvid,
                    url = "https://www.bilibili.com/video/${ep.bvid}"
                )
                val r = try {
                    parser.parse(link, cookie = cookie)
                } catch (e: VideoParseException) {
                    throw IllegalStateException(e.userMessage())
                }
                if (r.medias.isEmpty()) throw IllegalStateException("没有解析到可下载的媒体")
                val m = r.medias.firstOrNull { it.audioUrl.isNotBlank() } ?: r.medias.first()
                // 命名口径：逐集重解析得到的 r.title 是合集总标题，不得直接用作文件名
                // ——渲染前以该集在合集内的 episodes[].title 覆盖 {title} 来源（{index}
                // 仍为合集序号，见 enqueueMedia → renderedName）；集标题为空才回退总标题
                val named = if (ep.title.isNotBlank()) r.copy(title = ep.title) else r
                val ids = enqueueMedia(named, ep.bvid, ep.index - 1, m, relPath = season.folder, canMux = canMux)
                val note = when {
                    m.audioUrl.isBlank() -> ""
                    canMux -> "分轨下载，完成后自动合轨"
                    else -> "分轨双文件下载（未检测到 ffmpeg）"
                }
                results += VideoListEntryResultDto(
                    index = ep.index, bvid = ep.bvid, title = ep.title,
                    ok = ids.isNotEmpty(), message = note, taskIds = ids
                )
            } catch (e: Exception) {
                results += VideoListEntryResultDto(
                    index = ep.index, bvid = ep.bvid, title = ep.title,
                    ok = false, message = e.message ?: "解析失败"
                )
            }
        }
        return VideoListDownloadResponse(folder = season.folder, results = results)
    }

    /** 「片名.mp4」→「片名.audio.m4s」；无扩展名时直接追加 */
    private fun audioFileName(videoName: String): String {
        val dot = videoName.lastIndexOf('.')
        val base = if (dot > 0) videoName.substring(0, dot) else videoName
        return "$base.audio.m4s"
    }

    // ------------------------------------------------------------------
    // DASH 合轨协调（两轨都下完 → ffmpeg -c copy 合成单个 mp4）
    // ------------------------------------------------------------------

    /**
     * 一组合轨任务的内存状态。两轨各注册一个 onComplete 回调，[remaining] 倒数到 0
     * 时由后到者触发合轨。状态只在内存：应用重启即丢失，未完成的两轨会留在暂存
     * 目录 `.yunget-mux/` 里（设置页合轨卡有提示），不做持久化恢复。
     * 注意：onComplete 只在任务成功完成时由 DownloadService 调用；某轨永久失败时
     * 该组永远凑不齐，另一轨留在暂存目录——可接受的降级。
     */
    private data class MuxJob(
        val groupId: String,
        val finalName: String,
        /** 成品（含合轨失败保底的双文件）的目标子文件夹；"" = 下载目录根（单视频） */
        val targetRelPath: String = "",
        val videoTaskId: AtomicLong = AtomicLong(-1),
        val audioTaskId: AtomicLong = AtomicLong(-1),
        val remaining: AtomicInteger = AtomicInteger(2)
    )

    private val muxJobs = ConcurrentHashMap<String, MuxJob>()

    private suspend fun enqueueMuxPair(m: VideoMedia, displayName: String, targetRelPath: String = ""): List<Long> {
        val groupId = UUID.randomUUID().toString()
        val rel = "$MUX_STAGING_DIR/$groupId"
        val job = MuxJob(groupId = groupId, finalName = ensureMp4Name(displayName), targetRelPath = targetRelPath)
        muxJobs[groupId] = job
        val vId = downloadService.enqueue(
            url = m.url,
            fileName = "v.m4s",
            headers = m.headers,
            size = m.size,
            platform = "video",
            resumeKey = "video-mux-$groupId-v",
            relPath = rel,
            onComplete = { onMuxTrackComplete(groupId) }
        )
        job.videoTaskId.set(vId)
        val aId = downloadService.enqueue(
            url = m.audioUrl,
            fileName = "a.m4s",
            headers = m.audioHeaders,
            size = -1,
            platform = "video",
            resumeKey = "video-mux-$groupId-a",
            relPath = rel,
            onComplete = { onMuxTrackComplete(groupId) }
        )
        job.audioTaskId.set(aId)
        println("[mux] 分轨下载已入队：组 $groupId 视频轨任务 $vId、音轨任务 $aId，合轨目标 ${job.finalName}")
        return listOf(vId, aId)
    }

    private fun onMuxTrackComplete(groupId: String) {
        val job = muxJobs[groupId] ?: return
        if (job.remaining.decrementAndGet() != 0) return
        muxJobs.remove(groupId)
        muxScope.launch { runMux(job) }
    }

    /** 合轨主体（IO 线程）：成功删两轨；失败保底把两轨移到成品目标目录，绝不删源文件 */
    private fun runMux(job: MuxJob) {
        val base = downloadService.resolvedDownloadsDir()
        // 成品目录：单视频为下载根；列表批量下载为「列表名」子文件夹（暂存目录不变）
        val destDir = if (job.targetRelPath.isBlank()) base
        else File(base, job.targetRelPath).apply { mkdirs() }
        val stagingDir = File(base, "$MUX_STAGING_DIR/${job.groupId}")
        val vFile = trackFile(job.videoTaskId.get(), stagingDir, "v.m4s")
        val aFile = trackFile(job.audioTaskId.get(), stagingDir, "a.m4s")
        val ffmpegFile = ffmpeg.executable(downloadService.settings.ffmpegPath)
        if (ffmpegFile == null || !vFile.isFile || !aFile.isFile) {
            println(
                "[mux] 合轨前置条件不满足（组 ${job.groupId}：ffmpeg=${ffmpegFile != null} " +
                    "视频轨=${vFile.isFile} 音轨=${aFile.isFile}），改走双文件保底"
            )
            fallbackKeepTracks(job, destDir, vFile, aFile)
            cleanupStaging(base, stagingDir)
            return
        }
        val finalFile = uniqueFile(destDir, sanitizeFileName(job.finalName))
        val tmpOut = File(destDir, finalFile.nameWithoutExtension + ".muxing.mp4")
        val logFile = File(stagingDir, "ffmpeg.log")
        val exit = runCatching {
            val pb = ProcessBuilder(
                ffmpegFile.absolutePath,
                "-nostdin", "-hide_banner", "-loglevel", "error",
                "-i", vFile.absolutePath,
                "-i", aFile.absolutePath,
                "-c", "copy", "-movflags", "+faststart",
                "-y", tmpOut.absolutePath
            )
            pb.redirectErrorStream(true)
            pb.redirectOutput(logFile)
            pb.start().waitFor()
        }.getOrElse { e ->
            println("[mux] 合轨进程启动失败（组 ${job.groupId}）：${e.message}")
            -1
        }
        if (exit == 0 && tmpOut.isFile && tmpOut.length() > 0) {
            if (!tmpOut.renameTo(finalFile)) {
                runCatching { tmpOut.copyTo(finalFile, overwrite = true); tmpOut.delete() }
            }
            vFile.delete()
            aFile.delete()
            println("[mux] 合轨完成：${finalFile.absolutePath}（组 ${job.groupId}）")
        } else {
            tmpOut.delete()
            val tail = runCatching { logFile.readText().takeLast(500) }.getOrDefault("")
            println("[mux] 合轨失败（组 ${job.groupId}，退出码 $exit）$tail；改走双文件保底")
            fallbackKeepTracks(job, destDir, vFile, aFile)
        }
        cleanupStaging(base, stagingDir)
    }

    /** 轨道实际落盘路径：优先任务记录的 savePath（完成回调触发时已写入），缺失时按暂存目录推算 */
    private fun trackFile(taskId: Long, stagingDir: File, fallbackName: String): File {
        val sp = if (taskId > 0) {
            downloadService.get(taskId)?.savePath?.takeIf { it.isNotBlank() }
        } else null
        return if (sp != null) File(sp) else File(stagingDir, fallbackName)
    }

    /** 合轨失败保底：两轨移到成品目标目录（原名.video.m4s / 原名.audio.m4s），源文件只移不删 */
    private fun fallbackKeepTracks(job: MuxJob, destDir: File, vFile: File, aFile: File) {
        val stem = job.finalName.removeSuffix(".mp4").removeSuffix(".MP4")
        moveIfExists(vFile, uniqueFile(destDir, sanitizeFileName("$stem.video.m4s")))
        moveIfExists(aFile, uniqueFile(destDir, sanitizeFileName("$stem.audio.m4s")))
    }

    private fun moveIfExists(src: File, dest: File) {
        if (!src.isFile) return
        if (src.canonicalPath == dest.canonicalPath) return
        if (!src.renameTo(dest)) {
            runCatching { src.copyTo(dest, overwrite = true); src.delete() }
        }
    }

    /** 清暂存组目录（空才删得动）及可能变空的 .yunget-mux 父目录；残留文件时保留不动 */
    private fun cleanupStaging(base: File, stagingDir: File) {
        runCatching { stagingDir.delete() }
        runCatching { File(base, MUX_STAGING_DIR).delete() }
    }

    /** 合轨产物名确保以 .mp4 结尾（B站解析器本就给 .mp4，这里兜底其它来源） */
    private fun ensureMp4Name(name: String): String {
        val n = name.ifBlank { "bilibili_${System.currentTimeMillis()}.mp4" }
        if (n.lowercase().endsWith(".mp4")) return n
        val dot = n.lastIndexOf('.')
        return if (dot > 0) n.substring(0, dot) + ".mp4" else "$n.mp4"
    }

    /** 与 DownloadService 同款的文件名清洗（那份是 private，这里复制小工具自用） */
    private fun sanitizeFileName(name: String): String {
        var n = name.trim().ifBlank { "download" }
        n = n.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        if (n.length > 200) n = n.take(200)
        return n
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val baseName = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = if (ext.isNotEmpty() && ext != name) File(dir, "$baseName($i).$ext")
            else File(dir, "$name($i)")
            i++
        }
        return f
    }

    // ------------------------------------------------------------------
    // GitHub
    // ------------------------------------------------------------------

    /** 一个可下载条目（真实 URL 只存服务端） */
    private data class GhItem(
        val name: String,
        val size: Long,
        /** file | zip | release */
        val kind: String,
        val url: String
    )

    private data class GitHubSession(
        val id: String,
        val title: String,
        val items: List<GhItem>,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val ghSessions = ConcurrentHashMap<String, GitHubSession>()
    private val ghApi = GitHubApi()

    suspend fun parseGitHub(link: String): GitHubParseResponse {
        cleanupExpired()
        val type = GitHubLinkParser.parse(link)
            ?: throw IllegalArgumentException("无法识别 GitHub 链接")
        val (title, items) = when (type) {
            is GitHubLinkType.DirectFile ->
                type.fileName to listOf(GhItem(type.fileName, probeSize(type.url), "file", type.url))

            is GitHubLinkType.Repository -> repoItems(type)
            is GitHubLinkType.Account -> accountItems(type)
        }
        if (items.isEmpty()) throw IllegalStateException("没有找到可下载的内容")
        val id = UUID.randomUUID().toString()
        ghSessions[id] = GitHubSession(id, title, items)
        return GitHubParseResponse(
            sessionId = id,
            title = title,
            items = items.mapIndexed { i, it -> GitHubItemDto(i, it.name, it.size, it.kind) }
        )
    }

    /**
     * 仓库链接 → 可下载项：
     * - 无子路径（仓库主页）：默认分支源码 zip + 最新一个正式 Release 的全部资产；
     * - `releases` 子路径：第一页 Releases 的全部资产；`releases/tag/{tag}` 只取该版本；
     * - `tree/{分支}/{路径}` 子路径：该目录下的文件（raw 直链）；
     * - `blob/{分支}/{路径}` 子路径：该单个文件（raw 直链）。
     */
    private suspend fun repoItems(type: GitHubLinkType.Repository): Pair<String, List<GhItem>> {
        val owner = type.owner
        val repoName = type.repo
        val repo = ghApi.getRepo(owner, repoName)
            ?: throw IllegalStateException("仓库不存在，或 GitHub API 请求失败（匿名访问限额 60 次/小时）")
        val sub = type.subPath?.trim('/') ?: ""
        return when {
            sub == "releases" || sub.startsWith("releases/") -> {
                val tag = sub.removePrefix("releases").trim('/').removePrefix("tag/").trim('/')
                val releases = ghApi.getReleases(owner, repoName)
                    ?: throw IllegalStateException("获取 Releases 失败，请稍后重试")
                val chosen = releases.filter { !it.draft }
                    .filter { tag.isBlank() || it.tagName == tag }
                val items = chosen.flatMap { rel ->
                    rel.assets.map { GhItem(it.name, it.size, "release", it.downloadUrl) }
                }
                val title = if (tag.isBlank()) "${repo.fullName} · Releases" else "${repo.fullName} · $tag"
                title to items
            }

            sub.startsWith("tree/") || sub.startsWith("blob/") -> {
                val rest = sub.substringAfter('/')
                val branch = rest.substringBefore('/')
                val path = rest.substringAfter('/', "")
                if (sub.startsWith("blob/")) {
                    if (path.isBlank()) throw IllegalArgumentException("文件路径不完整")
                    val name = path.substringAfterLast('/')
                    name to listOf(
                        GhItem(name, -1, "file", "https://raw.githubusercontent.com/$owner/$repoName/$branch/$path")
                    )
                } else {
                    "${repo.fullName} · ${path.ifBlank { "/" }}" to
                        treeItems(owner, repoName, branch, path)
                }
            }

            else -> {
                val items = mutableListOf<GhItem>()
                items += GhItem(
                    name = "${repo.name}-${repo.defaultBranch}.zip",
                    size = -1,
                    kind = "zip",
                    url = "https://codeload.github.com/${repo.fullName}/zip/refs/heads/${repo.defaultBranch}"
                )
                ghApi.getReleases(owner, repoName)
                    ?.firstOrNull { !it.draft }
                    ?.assets
                    ?.forEach { items += GhItem(it.name, it.size, "release", it.downloadUrl) }
                repo.fullName to items
            }
        }
    }

    /** 逐层定位到目标目录后，列出其中文件（blob）作为可下载项 */
    private suspend fun treeItems(
        owner: String,
        repo: String,
        branch: String,
        path: String
    ): List<GhItem> {
        var sha = branch
        if (path.isNotBlank()) {
            for (seg in path.split('/')) {
                val entries = ghApi.getTree(owner, repo, sha)
                    ?: throw IllegalStateException("目录不存在，或 GitHub API 请求失败")
                val dir = entries.firstOrNull { it.path == seg && it.type == "tree" }
                    ?: throw IllegalStateException("路径不存在：$path")
                sha = dir.sha
            }
        }
        val entries = ghApi.getTree(owner, repo, sha)
            ?: throw IllegalStateException("目录不存在，或 GitHub API 请求失败")
        return entries.filter { it.type == "blob" }.map { e ->
            val fullPath = if (path.isBlank()) e.path else "$path/${e.path}"
            GhItem(
                name = e.path,
                size = e.size ?: -1,
                kind = "file",
                url = "https://raw.githubusercontent.com/$owner/$repo/$branch/$fullPath"
            )
        }
    }

    /** 账号/组织链接 → 其公开仓库（第一页，按更新排序）的默认分支源码 zip 列表 */
    private suspend fun accountItems(type: GitHubLinkType.Account): Pair<String, List<GhItem>> {
        val owner = type.owner
        val repos = if (ghApi.getUserType(owner) == "Organization") {
            ghApi.getOrgRepos(owner)
        } else {
            ghApi.getUserRepos(owner)
        } ?: throw IllegalStateException("用户/组织不存在，或 GitHub API 请求失败（匿名访问限额 60 次/小时）")
        val items = repos.map {
            GhItem(
                name = "${it.name}-${it.defaultBranch}.zip",
                size = -1,
                kind = "zip",
                url = "https://codeload.github.com/${it.fullName}/zip/refs/heads/${it.defaultBranch}"
            )
        }
        return owner to items
    }

    suspend fun downloadGitHub(sessionId: String, indexes: List<Int>): List<Long> {
        val s = ghSessions[sessionId]
            ?: throw IllegalStateException("解析会话已过期，请重新解析")
        val wanted = if (indexes.isEmpty()) s.items.indices.toList() else indexes.distinct()
        val ids = mutableListOf<Long>()
        for (i in wanted) {
            val item = s.items.getOrNull(i) ?: continue
            ids += downloadService.enqueue(
                url = item.url,
                fileName = item.name,
                headers = emptyMap(),
                size = item.size,
                platform = "github",
                resumeKey = "github-" + Integer.toHexString(item.url.hashCode())
            )
        }
        if (ids.isEmpty()) throw IllegalArgumentException("没有可下载的内容")
        return ids
    }

    /** 直链文件大小探测（HEAD 跟随重定向取 Content-Length）；失败返回 -1（引擎入队后会自行探测） */
    private suspend fun probeSize(url: String): Long = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "YunGet")
                .head()
                .build()
            HttpClients.apiClient().newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.header("Content-Length")?.toLongOrNull() ?: -1L
                } else -1L
            }
        }.getOrDefault(-1L)
    }

    // ------------------------------------------------------------------

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        videoSessions.entries.removeIf { now - it.value.createdAt > SESSION_TTL_MS }
        ghSessions.entries.removeIf { now - it.value.createdAt > SESSION_TTL_MS }
    }

    private fun fileNameOf(url: String): String =
        url.substringBefore('?').substringAfterLast('/').ifBlank { "download_${System.currentTimeMillis()}" }
}
