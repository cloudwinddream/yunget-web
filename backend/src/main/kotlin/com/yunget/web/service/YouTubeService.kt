package com.yunget.web.service

import com.yunget.web.model.YouTubeAccountResponse
import com.yunget.web.model.YouTubeDownloadRequest
import com.yunget.web.model.YouTubeDownloadResponse
import com.yunget.web.model.YouTubeEntryDto
import com.yunget.web.model.YouTubeEntryResultDto
import com.yunget.web.model.YouTubeParseResponse
import com.yunget.web.model.YouTubePlaylistDownloadRequest
import com.yunget.web.model.YouTubePlaylistDownloadResponse
import com.yunget.web.model.YouTubeQualityDto
import com.yunget.web.model.YouTubeSubtitleDto
import com.yunget.web.model.DenoStatusResponse
import com.yunget.web.model.YtDlpStatusResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val YT_SESSION_TTL_MS = 30L * 60 * 1000
private const val PARSE_TIMEOUT_SEC = 90L

// ---------------------------------------------------------------------
// yt-dlp -J 输出的子集建模（ignoreUnknownKeys 容错，只取用得到的字段）
// ---------------------------------------------------------------------

@Serializable
private data class YtFormatJson(
    val format_id: String = "",
    val ext: String = "",
    val url: String = "",
    val vcodec: String? = null,
    val acodec: String? = null,
    val height: Int? = null,
    val abr: Double? = null,
    val tbr: Double? = null,
    val filesize: Long? = null,
    val filesize_approx: Long? = null,
    val protocol: String = "",
    val http_headers: Map<String, String> = emptyMap()
)

@Serializable
private data class YtSubJson(
    val ext: String = "",
    val url: String = "",
    val name: String = ""
)

@Serializable
private data class YtEntryJson(
    val id: String = "",
    val title: String = "",
    val duration: Double? = null
)

@Serializable
private data class YtJson(
    val id: String = "",
    val title: String = "",
    val uploader: String = "",
    val upload_date: String = "",
    val duration: Double? = null,
    val thumbnail: String = "",
    val webpage_url: String = "",
    val formats: List<YtFormatJson> = emptyList(),
    val subtitles: Map<String, List<YtSubJson>> = emptyMap(),
    val automatic_captions: Map<String, List<YtSubJson>> = emptyMap(),
    val entries: List<YtEntryJson>? = null
)

/**
 * YouTube 解析（yt-dlp 托管工具路线）：yt-dlp 只做解析器（-J 拿元数据与直链），
 * 下载仍走自家 DownloadService；DASH 分轨复用 v1.0.29 的 ffmpeg 合轨模式。
 *
 * - 单视频：`-J --no-playlist`（不加 --no-warnings，WARNING 由 YtDlpSupport
 *   捕获剥离、全档失败时透出最相关一条），后端按目标高度挑格式（见 [pick]）；
 *   解析带 player_client 兜底链（默认 → android_vr → tv → web_safari），见 [runParseJson]；
 * - 播放列表（URL 含 list= 或 /playlist）：`-J --flat-playlist` 只列条目，
 *   下载时逐条再取完整 -J（顺序执行、逐条失败不中断）；
 * - 字幕：从 -J 的 subtitles / automatic_captions 里取 vtt 直链当普通文件下载，
 *   与成品同名（「成品名.<lang>.vtt」）；
 * - 登录：账号页可存 YouTube Cookie（AccountStore 键 "youtube"，仅存不校验），
 *   YtDlpSupport 会转成 Netscape 格式文件以 --cookies 传入；
 * - 命名：成品名走 [VideoNaming] 模板（变量齐全：title/author/id/date/quality/index）。
 */
class YouTubeService(
    private val downloadService: DownloadService,
    private val accounts: AccountStore,
    dataDir: File
) {

    companion object {
        /** AccountStore 中 YouTube 登录态的键 */
        const val YOUTUBE_KEY = "youtube"
        /** 合轨暂存目录（下载目录下），与 VideoService 共用同一目录名 */
        const val MUX_STAGING_DIR = ".yunget-mux"
        /** 播放列表没有逐条格式信息，给常用档位表（0=最高，-1=仅音频） */
        private val PLAYLIST_TIERS = listOf(
            YouTubeQualityDto(0, "最高画质"),
            YouTubeQualityDto(2160, "2160p"),
            YouTubeQualityDto(1440, "1440p"),
            YouTubeQualityDto(1080, "1080p"),
            YouTubeQualityDto(720, "720p"),
            YouTubeQualityDto(480, "480p"),
            YouTubeQualityDto(360, "360p"),
            YouTubeQualityDto(-1, "仅音频")
        )

        /** 播放器客户端兜底链的一档：label 仅打日志，args 为空 = yt-dlp 默认客户端 */
        private data class ClientAttempt(val label: String, val args: List<String>)

        /**
         * 兜底链尝试序：默认 → android_vr → tv → web_safari。
         * 不同 player_client 在 YouTube 的格式门控下可用格式不同，一个拿不到时换下一个。
         */
        private val PLAYER_CLIENT_ATTEMPTS = listOf(
            ClientAttempt("default", emptyList()),
            ClientAttempt("android_vr", listOf("--extractor-args", "youtube:player_client=android_vr")),
            ClientAttempt("tv", listOf("--extractor-args", "youtube:player_client=tv")),
            ClientAttempt("web_safari", listOf("--extractor-args", "youtube:player_client=web_safari"))
        )

        /** 兜底链全部失败时追加在错误主文案后的自救指引 */
        private const val RETRY_GUIDE =
            "（可尝试：到账号页保存 YouTube Cookie 后重试；在设置页更新 yt-dlp 到最新版；或检查工具代理是否可用）"

        /** 解析失败且 Deno 未解析到时追加的指引（无 JS 运行时是「无可用格式」的一大主因） */
        private const val DENO_MISSING_GUIDE =
            "（未检测到 Deno 运行时也会导致无可用格式——到设置页一键安装 Deno 后重试）"
    }

    private val deno = DenoSupport(dataDir)

    // yt-dlp 调用时显式传 Deno：provider 每次调用现解析（走 DenoSupport 的
    // 缓存 status），保证设置页刚装好/刚改路径立刻生效，不必重启。
    private val ytdlp = YtDlpSupport(dataDir) {
        deno.executable(settings.denoPath)?.absolutePath
    }
    private val ffmpeg = FfmpegSupport(dataDir)
    private val json = Json { ignoreUnknownKeys = true }
    private val muxScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val settings get() = downloadService.settings

    // ------------------------------------------------------------------
    // 内部模型与会话
    // ------------------------------------------------------------------

    private data class YtFormat(
        val id: String,
        val ext: String,
        val url: String,
        val vcodec: String?,
        val acodec: String?,
        val height: Int?,
        val abr: Double?,
        val tbr: Double?,
        val size: Long?,
        val headers: Map<String, String>
    )

    private data class YtSub(val url: String, val ext: String, val name: String)

    private data class YtVideoInfo(
        val id: String,
        val title: String,
        val uploader: String,
        val uploadDate: String,
        val durationSec: Long,
        val cover: String,
        val formats: List<YtFormat>,
        val subtitles: Map<String, List<YtSub>>,
        val autoCaptions: Map<String, List<YtSub>>
    )

    private data class YtEntry(val index: Int, val id: String, val title: String, val durationSec: Long)

    private data class YtSession(
        val id: String,
        /** video | playlist */
        val kind: String,
        val video: YtVideoInfo?,
        val playlistTitle: String = "",
        val entries: List<YtEntry> = emptyList(),
        val createdAt: Long = System.currentTimeMillis()
    )

    private val sessions = ConcurrentHashMap<String, YtSession>()

    // ------------------------------------------------------------------
    // 账号（Cookie 仅存不校验：YouTube 无轻量校验接口）
    // ------------------------------------------------------------------

    fun youtubeAccount(): YouTubeAccountResponse =
        YouTubeAccountResponse(saved = !accounts.get(YOUTUBE_KEY)?.cookie.isNullOrBlank())

    fun saveYoutubeAccount(cookie: String) {
        val c = cookie.trim()
        if (c.isBlank()) throw IllegalArgumentException("请粘贴 Cookie")
        accounts.save(YOUTUBE_KEY, AccountData(cookie = c))
    }

    fun clearYoutubeAccount() {
        accounts.remove(YOUTUBE_KEY)
    }

    // ------------------------------------------------------------------
    // yt-dlp 工具状态 / 安装（定位与安装在 YtDlpSupport）
    // ------------------------------------------------------------------

    fun ytdlpStatus(): YtDlpStatusResponse = ytdlp.status(settings.ytdlpPath)

    /** 同步阻塞（下载二进制），路由层需包 Dispatchers.IO。安装下载走工具安装代理，ytdlpProxy 兜底；force=true 为「更新」（强制重下最新版） */
    fun installYtdlp(force: Boolean = false): YtDlpStatusResponse =
        ytdlp.install(settings.ytdlpPath, settings.toolProxy, settings.ytdlpProxy, force)

    // ------------------------------------------------------------------
    // Deno 工具状态 / 安装（yt-dlp 的 JS 运行时，定位与安装在 DenoSupport）
    // ------------------------------------------------------------------

    fun denoStatus(): DenoStatusResponse = deno.status(settings.denoPath)

    /** 同步阻塞（下载二进制），路由层需包 Dispatchers.IO。安装下载走工具安装代理；force=true 为「更新」（强制重装） */
    fun installDeno(force: Boolean = false): DenoStatusResponse =
        deno.install(settings.denoPath, settings.toolProxy, force)

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    suspend fun parse(link: String): YouTubeParseResponse {
        cleanupExpired()
        if (!ytdlp.status(settings.ytdlpPath).available) {
            throw IllegalStateException("未检测到 yt-dlp，请到设置页一键安装")
        }
        val isPlaylist = link.contains("list=") || link.contains("/playlist")
        val j = runParseJson(link, flatPlaylist = isPlaylist)
        val sessionId = UUID.randomUUID().toString()
        return if (isPlaylist) {
            val entries = j.entries.orEmpty().mapIndexed { i, e ->
                YtEntry(
                    index = i,
                    id = e.id,
                    title = e.title.ifBlank { e.id },
                    durationSec = e.duration?.toLong() ?: -1
                )
            }.filter { it.id.isNotBlank() }
            if (entries.isEmpty()) throw IllegalStateException("播放列表为空、不可见，或需要登录/代理才能访问")
            val title = j.title.ifBlank { "YouTube 播放列表" }
            sessions[sessionId] = YtSession(sessionId, "playlist", null, title, entries)
            YouTubeParseResponse(
                sessionId = sessionId,
                kind = "playlist",
                title = title,
                qualities = PLAYLIST_TIERS,
                entries = entries.map { YouTubeEntryDto(it.index, it.id, it.title, it.durationSec) },
                formatsSummary = "共 ${entries.size} 个视频 · 下载时逐条按所选档位取最优格式",
                // 列表批量下载的统一规则：成品落到「列表名」文件夹（与下载时同源计算）
                folder = VideoNaming.folderName(title)
            )
        } else {
            val info = toVideoInfo(j)
            if (info.formats.none { it.url.isNotBlank() }) {
                throw IllegalStateException("没有解析到可下载的格式（视频可能需要登录 Cookie，或需要设置代理）")
            }
            sessions[sessionId] = YtSession(sessionId, "video", info)
            YouTubeParseResponse(
                sessionId = sessionId,
                kind = "video",
                title = info.title,
                author = info.uploader,
                cover = info.cover,
                duration = info.durationSec,
                qualities = qualitiesOf(info),
                subtitles = subtitlesDto(info),
                formatsSummary = formatsSummary(info)
            )
        }
    }

    /**
     * 跑 yt-dlp -J 取 JSON，带播放器客户端兜底链（子进程阻塞，包 IO；
     * Cookie/代理由 YtDlpSupport 按设置拼参数）。
     *
     * 背景：YouTube 近年按 player_client 门控格式——默认客户端（含未登录、
     * 旧版 yt-dlp）常报「Requested format is not available」或只剩故事板
     * （mhtml）格式。兜底链依次尝试 [默认 → android_vr → tv → web_safari]：
     * 进程非 0 退出、输出解析失败、或（仅单视频模式）剔除 mhtml 后无可用
     * 格式，都算本次失败、换下一个客户端；首个成功即停并打日志。
     * flat 播放列表模式只在进程/解析失败时重试（列表没有 formats 可查）。
     * 全部失败时以最后一次的错误为主文案并追加自救指引。
     * 单视频与播放列表逐条下载都经此函数，兜底链天然全覆盖。
     */
    private suspend fun runParseJson(url: String, flatPlaylist: Boolean): YtJson {
        val cookie = accounts.get(YOUTUBE_KEY)?.cookie?.takeIf { it.isNotBlank() }
        var lastError: Exception? = null
        for ((i, attempt) in PLAYER_CLIENT_ATTEMPTS.withIndex()) {
            // 不再传 --no-warnings：WARNING 行是定位环境问题（如缺 JS 运行时）
            // 的关键线索，由 YtDlpSupport.run 捕获并从返回文本中剥离（不污染
            // JSON 提取），全档失败时挑最相关的一条透出到报错里。
            val args = mutableListOf("-J")
            if (flatPlaylist) args += "--flat-playlist" else args += "--no-playlist"
            args += attempt.args
            args += url
            val n = i + 1
            val total = PLAYER_CLIENT_ATTEMPTS.size
            try {
                val text = withContext(Dispatchers.IO) {
                    ytdlp.run(settings.ytdlpPath, settings.ytdlpProxy, cookie, args, PARSE_TIMEOUT_SEC)
                }
                val j = try {
                    json.decodeFromString(YtJson.serializer(), text)
                } catch (e: Exception) {
                    throw IllegalStateException("yt-dlp 输出解析失败（可能版本过旧或被平台拦截）：${e.message}", e)
                }
                if (!flatPlaylist && j.formats.none { it.url.isNotBlank() && it.protocol != "mhtml" }) {
                    println("[youtube] 解析尝试 $n/$total（client=${attempt.label}）：进程成功但无可用格式（仅故事板或空），换下一客户端")
                    lastError = IllegalStateException("没有解析到可用格式（默认客户端只返回了故事板等不可用格式）")
                    continue
                }
                println("[youtube] 解析尝试 $n/$total（client=${attempt.label}）：成功")
                return j
            } catch (e: Exception) {
                println("[youtube] 解析尝试 $n/$total（client=${attempt.label}）：失败：${e.message}")
                lastError = e
            }
        }
        val base = lastError?.message ?: "yt-dlp 解析失败"
        throw IllegalStateException(buildString {
            append(base)
            // 警告透出：附上最近一次运行里最相关的 WARNING 行（原文自带
            // 「WARNING:」前缀），让缺运行时/需 PO Token 这类环境问题在报错里自证
            relevantWarning(ytdlp.lastWarnings())?.let { append("（yt-dlp 提示：").append(it).append("）") }
            // Cookie 携带情况：区分「根本没带上」与「带了仍被机器人验证拦下」，
            // 两者的下一步完全不同（前者去保存，后者多半是出口 IP 被风控）
            val cookieCount = ytdlp.lastCookieCount()
            if (cookieCount != null) {
                append("（本次解析已携带登录 Cookie（$cookieCount 条），仍被 YouTube 拦截——大概率是当前出口 IP 被风控（机器人验证），建议更换代理节点，或在设置页为 yt-dlp 指定其它出站代理后重试）")
            }
            append(RETRY_GUIDE)
            if (!deno.status(settings.denoPath).available) append(DENO_MISSING_GUIDE)
        })
    }

    /**
     * 从 yt-dlp 的 WARNING 行中挑最相关的一条透出：优先含 JavaScript runtime /
     * deno / PO Token / impersonate 关键词的行（按出现顺序取第一条）；一条都
     * 不含时退取第一条警告；没有警告返回 null（成功路径不消费此函数）。
     * 单条截断 300 字防爆。
     */
    private fun relevantWarning(warnings: List<String>): String? {
        if (warnings.isEmpty()) return null
        val keys = listOf("javascript runtime", "deno", "po token", "impersonate")
        val hit = warnings.firstOrNull { line -> keys.any { line.contains(it, ignoreCase = true) } }
        return (hit ?: warnings.first()).take(300)
    }

    private fun toVideoInfo(j: YtJson): YtVideoInfo {
        val formats = j.formats.mapNotNull { f ->
            if (f.url.isBlank() || f.protocol == "mhtml") null
            else YtFormat(
                id = f.format_id,
                ext = f.ext,
                url = f.url,
                vcodec = f.vcodec,
                acodec = f.acodec,
                height = f.height,
                abr = f.abr,
                tbr = f.tbr,
                size = f.filesize ?: f.filesize_approx,
                headers = f.http_headers
            )
        }
        fun subMap(m: Map<String, List<YtSubJson>>): Map<String, List<YtSub>> {
            // 入库即归一化语言键：兜底客户端返回的自动字幕键可能拼入轨道 ID
            // （如 zh-CN-1oijpNB9Fqw），不洗净会污染 DTO 语言与字幕文件名。
            // 多个原始键归一到同一干净键时合并轨道，「原始键恰等于干净键」的
            // 条目（官方/原轨）整体排前，保证 pickVtt 的队首优先语义不变。
            val merged = LinkedHashMap<String, MutableList<YtSub>>()
            val ordered = m.entries.sortedBy { (raw, _) -> if (normalizeLangKey(raw) == raw) 0 else 1 }
            for ((raw, tracksJson) in ordered) {
                val tracks = tracksJson.filter { it.url.isNotBlank() }
                    .map { YtSub(it.url, it.ext, it.name) }
                if (tracks.isEmpty()) continue
                merged.getOrPut(normalizeLangKey(raw)) { mutableListOf() }.addAll(tracks)
            }
            return merged
        }
        return YtVideoInfo(
            id = j.id,
            title = j.title,
            uploader = j.uploader,
            uploadDate = j.upload_date,
            durationSec = j.duration?.toLong() ?: -1,
            cover = j.thumbnail,
            formats = formats,
            subtitles = subMap(j.subtitles),
            autoCaptions = subMap(j.automatic_captions)
        )
    }

    private fun hasVideo(f: YtFormat): Boolean = !f.vcodec.isNullOrBlank() && f.vcodec != "none"
    private fun hasAudio(f: YtFormat): Boolean = !f.acodec.isNullOrBlank() && f.acodec != "none"

    private fun qualitiesOf(info: YtVideoInfo): List<YouTubeQualityDto> {
        val heights = info.formats.filter { hasVideo(it) }
            .mapNotNull { it.height }.filter { it > 0 }
            .distinct().sortedDescending()
        val list = heights.map { YouTubeQualityDto(it, "${it}p") }.toMutableList()
        if (info.formats.any { hasAudio(it) && !hasVideo(it) }) {
            list += YouTubeQualityDto(-1, "仅音频")
        }
        return list
    }

    private fun formatsSummary(info: YtVideoInfo): String {
        val maxH = info.formats.filter { hasVideo(it) }.mapNotNull { it.height }.maxOrNull()
        return if (maxH != null) "共 ${info.formats.size} 个格式 · 最高 ${maxH}p"
        else "共 ${info.formats.size} 个格式"
    }

    private fun subtitlesDto(info: YtVideoInfo): List<YouTubeSubtitleDto> {
        val out = mutableListOf<YouTubeSubtitleDto>()
        for ((lang, tracks) in info.subtitles) {
            out += YouTubeSubtitleDto(lang, tracks.firstOrNull()?.name?.ifBlank { lang } ?: lang, auto = false)
        }
        for ((lang, tracks) in info.autoCaptions) {
            // 同语言已有官方字幕时只列官方那条（下载解析时官方优先），避免前端出现重复项
            if (lang in info.subtitles) continue
            out += YouTubeSubtitleDto(lang, tracks.firstOrNull()?.name?.ifBlank { lang } ?: lang, auto = true)
        }
        return out
    }

    // ------------------------------------------------------------------
    // 格式挑选（规则定死，见交付报告）
    // ------------------------------------------------------------------

    private data class Pick(
        val video: YtFormat?,
        val audio: YtFormat?,
        /** 选中的视频高度；-1 = 仅音频，0 = 未知 */
        val height: Int,
        /** true = 分轨（video 与 audio 两个独立格式） */
        val split: Boolean
    )

    /** 同高内排序：ext=mp4 且 vcodec 为 avc1 者优先，其次 tbr 大者，再 filesize 大者 */
    private val videoRank = compareByDescending<YtFormat> {
        if (it.ext == "mp4" && it.vcodec?.startsWith("avc1") == true) 1 else 0
    }.thenByDescending { it.tbr ?: 0.0 }.thenByDescending { it.size ?: 0L }

    /** 音频排序：abr 大者优先，同 abr 时 ext=m4a 者优先 */
    private fun bestAudio(candidates: List<YtFormat>): YtFormat? =
        candidates.sortedWith(
            compareByDescending<YtFormat> { it.abr ?: 0.0 }
                .thenByDescending { if (it.ext == "m4a") 1 else 0 }
                .thenByDescending { it.tbr ?: 0.0 }
        ).firstOrNull()

    private fun pick(info: YtVideoInfo, targetHeight: Int): Pick {
        val audioOnly = info.formats.filter { hasAudio(it) && !hasVideo(it) && it.url.isNotBlank() }
        if (targetHeight == -1) {
            val a = bestAudio(audioOnly)
                ?: throw IllegalStateException("该视频没有独立的音频格式，无法只下音频")
            return Pick(video = null, audio = a, height = -1, split = false)
        }
        val videoFmts = info.formats.filter { hasVideo(it) && it.url.isNotBlank() }
        if (videoFmts.isEmpty()) throw IllegalStateException("该视频没有可用的视频格式")
        val heights = videoFmts.map { it.height ?: 0 }.distinct()
        val allowed = if (targetHeight > 0) heights.filter { it in 1..targetHeight } else heights
        // 目标高度以下无格式时回退到全体最小高度（离目标最近的一档向上一档）
        val h = if (allowed.isNotEmpty()) allowed.max() else heights.min()
        val same = videoFmts.filter { (it.height ?: 0) == h }
        val progressive = same.filter { hasAudio(it) }
        if (progressive.isNotEmpty()) {
            // 一体格式优先（免合轨）
            return Pick(video = progressive.sortedWith(videoRank).first(), audio = null, height = h, split = false)
        }
        val v = same.sortedWith(videoRank).first()
        val a = bestAudio(audioOnly)
        return if (a != null) Pick(video = v, audio = a, height = h, split = true)
        else Pick(video = v, audio = null, height = h, split = false)
    }

    /** 合轨容器：avc1 视频 + mp4a 音频 → mp4（带 faststart）；其余组合（vp9/av1/opus 等）→ mkv 保 -c copy 必成 */
    private fun muxContainer(pick: Pick): Pair<String, Boolean> {
        val vc = pick.video?.vcodec ?: ""
        val ac = pick.audio?.acodec ?: ""
        val mp4Ok = vc.startsWith("avc1") && (ac.isBlank() || ac.startsWith("mp4a"))
        return if (mp4Ok) "mp4" to true else "mkv" to false
    }

    // ------------------------------------------------------------------
    // 下载（单视频 / 播放列表）
    // ------------------------------------------------------------------

    private data class EnqueueOutcome(val taskIds: List<Long>, val note: String)

    suspend fun download(req: YouTubeDownloadRequest): YouTubeDownloadResponse {
        val s = sessions[req.sessionId]
            ?: throw IllegalStateException("解析会话已过期，请重新解析")
        val info = s.video ?: throw IllegalArgumentException("该会话是播放列表，请在列表卡里勾选下载")
        val out = enqueueInfo(info, req.targetHeight, req.subLangs, req.includeAuto, index1 = 0)
        if (out.taskIds.isEmpty()) throw IllegalArgumentException("没有可下载的内容")
        return YouTubeDownloadResponse(out.taskIds, out.note)
    }

    suspend fun downloadPlaylist(req: YouTubePlaylistDownloadRequest): YouTubePlaylistDownloadResponse {
        val s = sessions[req.sessionId]
            ?: throw IllegalStateException("解析会话已过期，请重新解析")
        if (s.kind != "playlist") throw IllegalArgumentException("该会话不是播放列表")
        val wantedIds = req.entryIds.toSet()
        val wanted = s.entries.filter { it.id in wantedIds }
        if (wanted.isEmpty()) throw IllegalArgumentException("请先勾选要下载的条目")
        // 列表批量下载的统一规则：全部条目落到「播放列表名」文件夹
        val folder = VideoNaming.folderName(s.playlistTitle)
        val results = mutableListOf<YouTubeEntryResultDto>()
        // 顺序执行：逐条取完整格式再入队，避免并发轰击 YouTube；单条失败不中断其余
        for (entry in wanted) {
            try {
                val j = runParseJson("https://www.youtube.com/watch?v=${entry.id}", flatPlaylist = false)
                val info = toVideoInfo(j)
                val out = enqueueInfo(info, req.targetHeight, req.subLangs, req.includeAuto, index1 = entry.index + 1, relPath = folder)
                results += YouTubeEntryResultDto(
                    id = entry.id, title = entry.title,
                    ok = out.taskIds.isNotEmpty(),
                    message = out.note, taskIds = out.taskIds
                )
            } catch (e: Exception) {
                results += YouTubeEntryResultDto(
                    id = entry.id, title = entry.title,
                    ok = false, message = e.message ?: "解析失败"
                )
            }
        }
        return YouTubePlaylistDownloadResponse(results)
    }

    /**
     * 把一条视频按挑选结果入队（视频/音频/字幕），命名走模板；返回任务与提示。
     * [relPath] 非空时全部产物（合轨成品 / 分轨双文件 / 字幕）落到下载目录的该子
     * 文件夹（列表批量下载的统一规则）；单视频下载传 "" 落根目录，行为不变。
     */
    private suspend fun enqueueInfo(
        info: YtVideoInfo,
        targetHeight: Int,
        subLangs: List<String>,
        includeAuto: Boolean,
        index1: Int,
        relPath: String = ""
    ): EnqueueOutcome {
        val pick = pick(info, targetHeight)
        val vars = VideoNaming.Vars(
            title = info.title,
            author = info.uploader,
            id = info.id,
            platform = "YOUTUBE",
            quality = if (pick.height > 0) "${pick.height}p" else if (pick.height == -1) "audio" else "",
            date = VideoNaming.formatYtDate(info.uploadDate),
            index = index1
        )
        val base = VideoNaming.renderBase(settings.videoNameTemplate, vars, info.title)
        val ids = mutableListOf<Long>()
        val notes = mutableListOf<String>()
        val v = pick.video
        val a = pick.audio
        when {
            v != null && a != null -> {
                val canMux = ffmpeg.executable(settings.ffmpegPath) != null
                if (canMux) {
                    val (outExt, faststart) = muxContainer(pick)
                    val finalName = "$base.$outExt"
                    ids += enqueueMuxPair(v, a, finalName, faststart, relPath)
                    notes += "分轨下载，完成后自动合轨为 $finalName"
                } else {
                    ids += enqueueTrack(v, "$base.${v.ext.ifBlank { "mp4" }}", relPath)
                    ids += enqueueTrack(a, "$base.audio.${a.ext.ifBlank { "m4a" }}", relPath)
                    notes += "未检测到 ffmpeg：音视频分轨以两个文件下载，需自行合轨（设置页可一键安装 ffmpeg）"
                }
            }
            v != null -> ids += enqueueTrack(v, "$base.${v.ext.ifBlank { "mp4" }}", relPath)
            a != null -> ids += enqueueTrack(a, "$base.${a.ext.ifBlank { "m4a" }}", relPath)
        }
        // 字幕：与成品同基名，「基名.<lang>.vtt」；取不到轨的语言跳过并记进提示
        for (lang in subLangs.distinct()) {
            val sub = resolveSubtitle(info, lang, includeAuto)
            if (sub == null) {
                notes += "字幕「$lang」无可用轨道，已跳过"
                continue
            }
            val subName = "$base.${sanitizeLangKey(lang)}.vtt"
            ids += downloadService.enqueue(
                url = sub.url,
                fileName = subName,
                headers = emptyMap(),
                size = -1,
                platform = "video",
                resumeKey = "youtube-sub-" + Integer.toHexString(sub.url.hashCode()),
                relPath = relPath
            )
        }
        return EnqueueOutcome(ids, notes.joinToString("；"))
    }

    /** 字幕轨解析：官方字幕优先；语言只在自动字幕里且 includeAuto 时才取自动轨；轨内优先 vtt */
    private fun resolveSubtitle(info: YtVideoInfo, lang: String, includeAuto: Boolean): YtSub? {
        info.subtitles[lang]?.let { tracks -> return pickVtt(tracks) }
        if (includeAuto) info.autoCaptions[lang]?.let { tracks -> return pickVtt(tracks) }
        return null
    }

    private fun pickVtt(tracks: List<YtSub>): YtSub? =
        tracks.firstOrNull { it.ext == "vtt" } ?: tracks.firstOrNull()

    /**
     * 语言键归一化（入库时用）：按 `-` 分段保留 BCP-47 形状的前缀——主段恒保留，
     * 其后子段仅当「纯字母且 ≤5 字符」或「纯数字且 ≤4 字符」才保留，遇到第一个
     * 不合规子段即截断（其后全丢），以剥掉兜底客户端拼进键里的轨道 ID
     * （zh-CN-1oijpNB9Fqw → zh-CN；zh-Hant-HK、es-419、sl-rozaj-biske 不受影响）。
     * 结果为空（原键为空等极端情况）时退回 sanitizeLangKey(原键)，保证不丢轨。
     */
    private fun normalizeLangKey(raw: String): String {
        val parts = raw.split('-')
        val kept = mutableListOf(parts.first())
        for (seg in parts.drop(1)) {
            val ok = (seg.isNotEmpty() && seg.all { it.isLetter() } && seg.length <= 5) ||
                (seg.isNotEmpty() && seg.all { it.isDigit() } && seg.length <= 4)
            if (!ok) break
            kept += seg
        }
        return kept.joinToString("-").ifBlank { sanitizeLangKey(raw) }
    }

    /** 字幕语言键进文件名前的清洗（en-orig、zh-Hans 这类键只保留安全字符） */
    private fun sanitizeLangKey(lang: String): String =
        lang.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "sub" }

    private suspend fun enqueueTrack(f: YtFormat, fileName: String, relPath: String = ""): Long =
        downloadService.enqueue(
            url = f.url,
            fileName = fileName,
            headers = f.headers,
            size = f.size ?: -1,
            platform = "video",
            resumeKey = "youtube-" + Integer.toHexString(f.url.hashCode()),
            relPath = relPath
        )

    // ------------------------------------------------------------------
    // DASH 合轨协调（同 VideoService 模式的自包含实现：两轨下完 → ffmpeg -c copy）
    // ------------------------------------------------------------------

    private data class YtMuxJob(
        val groupId: String,
        val finalName: String,
        val videoName: String,
        val audioName: String,
        val faststart: Boolean,
        /** 成品（含合轨失败保底的双文件）的目标子文件夹；"" = 下载目录根（单视频） */
        val targetRelPath: String = "",
        val videoTaskId: AtomicLong = AtomicLong(-1),
        val audioTaskId: AtomicLong = AtomicLong(-1),
        val remaining: AtomicInteger = AtomicInteger(2)
    )

    private val muxJobs = ConcurrentHashMap<String, YtMuxJob>()

    private suspend fun enqueueMuxPair(
        v: YtFormat, a: YtFormat, finalName: String, faststart: Boolean, targetRelPath: String = ""
    ): List<Long> {
        val groupId = UUID.randomUUID().toString()
        val rel = "$MUX_STAGING_DIR/$groupId"
        val vName = "v.${v.ext.ifBlank { "mp4" }}"
        val aName = "a.${a.ext.ifBlank { "m4a" }}"
        val job = YtMuxJob(groupId, finalName, vName, aName, faststart, targetRelPath)
        muxJobs[groupId] = job
        val vId = downloadService.enqueue(
            url = v.url, fileName = vName, headers = v.headers, size = v.size ?: -1,
            platform = "video", resumeKey = "youtube-mux-$groupId-v", relPath = rel,
            onComplete = { onMuxTrackComplete(groupId) }
        )
        job.videoTaskId.set(vId)
        val aId = downloadService.enqueue(
            url = a.url, fileName = aName, headers = a.headers, size = a.size ?: -1,
            platform = "video", resumeKey = "youtube-mux-$groupId-a", relPath = rel,
            onComplete = { onMuxTrackComplete(groupId) }
        )
        job.audioTaskId.set(aId)
        println("[yt-mux] 分轨下载已入队：组 $groupId 视频轨任务 $vId、音轨任务 $aId，合轨目标 $finalName")
        return listOf(vId, aId)
    }

    private fun onMuxTrackComplete(groupId: String) {
        val job = muxJobs[groupId] ?: return
        if (job.remaining.decrementAndGet() != 0) return
        muxJobs.remove(groupId)
        muxScope.launch { runMux(job) }
    }

    private fun runMux(job: YtMuxJob) {
        val base = downloadService.resolvedDownloadsDir()
        // 成品目录：单视频为下载根；播放列表批量下载为「列表名」子文件夹（暂存目录不变）
        val destDir = if (job.targetRelPath.isBlank()) base
        else File(base, job.targetRelPath).apply { mkdirs() }
        val stagingDir = File(base, "$MUX_STAGING_DIR/${job.groupId}")
        val vFile = trackFile(job.videoTaskId.get(), stagingDir, job.videoName)
        val aFile = trackFile(job.audioTaskId.get(), stagingDir, job.audioName)
        val ffmpegFile = ffmpeg.executable(settings.ffmpegPath)
        if (ffmpegFile == null || !vFile.isFile || !aFile.isFile) {
            println(
                "[yt-mux] 合轨前置条件不满足（组 ${job.groupId}：ffmpeg=${ffmpegFile != null} " +
                    "视频轨=${vFile.isFile} 音轨=${aFile.isFile}），改走双文件保底"
            )
            fallbackKeepTracks(job, destDir, vFile, aFile)
            cleanupStaging(base, stagingDir)
            return
        }
        val finalFile = uniqueFile(destDir, VideoNaming.sanitize(job.finalName))
        val tmpOut = File(destDir, finalFile.nameWithoutExtension + ".muxing." + finalFile.extension)
        val logFile = File(stagingDir, "ffmpeg.log")
        val cmd = mutableListOf(
            ffmpegFile.absolutePath,
            "-nostdin", "-hide_banner", "-loglevel", "error",
            "-i", vFile.absolutePath,
            "-i", aFile.absolutePath,
            "-c", "copy"
        )
        if (job.faststart) {
            cmd += "-movflags"
            cmd += "+faststart"
        }
        cmd += "-y"
        cmd += tmpOut.absolutePath
        val exit = runCatching {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            pb.redirectOutput(logFile)
            pb.start().waitFor()
        }.getOrElse { e ->
            println("[yt-mux] 合轨进程启动失败（组 ${job.groupId}）：${e.message}")
            -1
        }
        if (exit == 0 && tmpOut.isFile && tmpOut.length() > 0) {
            if (!tmpOut.renameTo(finalFile)) {
                runCatching { tmpOut.copyTo(finalFile, overwrite = true); tmpOut.delete() }
            }
            vFile.delete()
            aFile.delete()
            println("[yt-mux] 合轨完成：${finalFile.absolutePath}（组 ${job.groupId}）")
        } else {
            tmpOut.delete()
            val tail = runCatching { logFile.readText().takeLast(500) }.getOrDefault("")
            println("[yt-mux] 合轨失败（组 ${job.groupId}，退出码 $exit）$tail；改走双文件保底")
            fallbackKeepTracks(job, destDir, vFile, aFile)
        }
        cleanupStaging(base, stagingDir)
    }

    /** 轨道实际落盘路径：优先任务记录的 savePath，缺失时按暂存目录推算（同 VideoService） */
    private fun trackFile(taskId: Long, stagingDir: File, fallbackName: String): File {
        val sp = if (taskId > 0) {
            downloadService.get(taskId)?.savePath?.takeIf { it.isNotBlank() }
        } else null
        return if (sp != null) File(sp) else File(stagingDir, fallbackName)
    }

    /** 合轨失败保底：两轨移到成品目标目录（原名.video.<ext> / 原名.audio.<ext>），只移不删 */
    private fun fallbackKeepTracks(job: YtMuxJob, destDir: File, vFile: File, aFile: File) {
        val stem = job.finalName.substringBeforeLast('.', job.finalName)
        val vExt = job.videoName.substringAfterLast('.', "mp4")
        val aExt = job.audioName.substringAfterLast('.', "m4a")
        moveIfExists(vFile, uniqueFile(destDir, VideoNaming.sanitize("$stem.video.$vExt")))
        moveIfExists(aFile, uniqueFile(destDir, VideoNaming.sanitize("$stem.audio.$aExt")))
    }

    private fun moveIfExists(src: File, dest: File) {
        if (!src.isFile) return
        if (src.canonicalPath == dest.canonicalPath) return
        if (!src.renameTo(dest)) {
            runCatching { src.copyTo(dest, overwrite = true); src.delete() }
        }
    }

    private fun cleanupStaging(base: File, stagingDir: File) {
        runCatching { stagingDir.delete() }
        runCatching { File(base, MUX_STAGING_DIR).delete() }
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

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { now - it.value.createdAt > YT_SESSION_TTL_MS }
    }
}
