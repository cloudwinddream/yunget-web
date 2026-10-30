package com.yunget.web.service

import com.yunget.app.data.network.GitHubApi
import com.yunget.app.data.network.GitHubLinkParser
import com.yunget.app.data.network.GitHubLinkType
import com.yunget.app.data.network.HttpClients
import com.yunget.app.data.network.video.DetectedVideoLink
import com.yunget.app.data.network.video.VideoHttp
import com.yunget.app.data.network.video.VideoLinkParser
import com.yunget.app.data.network.video.VideoParseException
import com.yunget.app.data.network.video.VideoParseResult
import com.yunget.app.data.network.video.VideoParsers
import com.yunget.app.data.network.video.userMessage
import com.yunget.web.model.GitHubItemDto
import com.yunget.web.model.GitHubParseResponse
import com.yunget.web.model.VideoMediaItemDto
import com.yunget.web.model.VideoParseResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val SESSION_TTL_MS = 30L * 60 * 1000

/**
 * 视频解析 + GitHub 解析的会话层（协议实现整段移植自上游 YunGet 的
 * `data/network/video` 包与 GitHub 数据层，本类只做会话托管与下载入队）。
 *
 * 与网盘会话同理：解析产出的**真实媒体 URL 与请求头只存服务端**，前端只拿到
 * 会话 ID + 媒体下标，下载时按下标回查，避免直链（多带签名/时效）出现在页面里。
 *
 * - 视频：本期不接视频平台账号，cookie 一律传 ""（需要登录的平台由解析器自己
 *   抛 UNSUPPORTED 并给出可操作提示）；
 * - GitHub：无 Token 匿名访问（限额 60 次/小时/IP），只处理公开仓库。
 */
class VideoService(
    private val downloadService: DownloadService
) {

    // ------------------------------------------------------------------
    // 视频
    // ------------------------------------------------------------------

    private data class VideoSession(
        val id: String,
        val result: VideoParseResult,
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
        val result = try {
            parser.parse(detected, cookie = "")
        } catch (e: VideoParseException) {
            throw IllegalStateException(e.userMessage())
        }
        if (result.medias.isEmpty()) throw IllegalStateException("没有解析到可下载的媒体")
        val id = UUID.randomUUID().toString()
        videoSessions[id] = VideoSession(id, result)
        return VideoParseResponse(
            sessionId = id,
            platform = result.platform.name,
            platformName = result.platform.displayName,
            title = result.title,
            author = result.author,
            cover = result.cover,
            text = result.text,
            notice = result.notice,
            medias = result.medias.mapIndexed { i, m ->
                VideoMediaItemDto(
                    index = i,
                    kind = m.kind.name,
                    fileName = m.fileName.ifBlank { fileNameOf(m.url) },
                    size = m.size,
                    quality = m.quality,
                    isHls = m.isHls,
                    needsMux = m.audioUrl.isNotBlank()
                )
            }
        )
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

    /**
     * 按下标把媒体入队下载。返回新建的任务 ID 列表。
     *
     * - HLS 媒体（AcFun 的 m3u8）：URL 原样入队即可——下载引擎里 HLS 插件
     *   （turbo-plugin-hls）的 `HlsBackend.supports` 按「http(s) 且 path 以 .m3u8 结尾」
     *   认领，DownloadService 已装该插件，不需要任何特殊标记；
     * - DASH 分轨媒体（B站，audioUrl 非空）：本期不合轨，生成两个任务——
     *   视频轨用原名，音轨名为「原名（去扩展）.audio.m4s」，前端据 needsMux 明示。
     */
    suspend fun downloadVideo(sessionId: String, indexes: List<Int>): List<Long> {
        val s = videoSessions[sessionId]
            ?: throw IllegalStateException("解析会话已过期，请重新解析")
        val wanted = if (indexes.isEmpty()) s.result.medias.indices.toList() else indexes.distinct()
        val ids = mutableListOf<Long>()
        for (i in wanted) {
            val m = s.result.medias.getOrNull(i) ?: continue
            val name = m.fileName.ifBlank { fileNameOf(m.url) }
            ids += downloadService.enqueue(
                url = m.url,
                fileName = name,
                headers = m.headers,
                size = m.size,
                platform = "video",
                resumeKey = "video-" + Integer.toHexString(m.url.hashCode())
            )
            if (m.audioUrl.isNotBlank()) {
                ids += downloadService.enqueue(
                    url = m.audioUrl,
                    fileName = audioFileName(name),
                    headers = m.audioHeaders,
                    size = -1,
                    platform = "video",
                    resumeKey = "video-" + Integer.toHexString(m.audioUrl.hashCode()) + "-audio"
                )
            }
        }
        if (ids.isEmpty()) throw IllegalArgumentException("没有可下载的媒体")
        return ids
    }

    /** 「片名.mp4」→「片名.audio.m4s」；无扩展名时直接追加 */
    private fun audioFileName(videoName: String): String {
        val dot = videoName.lastIndexOf('.')
        val base = if (dot > 0) videoName.substring(0, dot) else videoName
        return "$base.audio.m4s"
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
