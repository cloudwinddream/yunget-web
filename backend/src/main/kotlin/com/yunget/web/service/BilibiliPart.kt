package com.yunget.web.service

import com.yunget.app.data.download.DownloadPathPolicy
import com.yunget.app.data.network.video.VideoHttp
import com.yunget.app.data.network.video.VideoMedia
import com.yunget.app.data.network.video.VideoMediaKind
import org.json.JSONObject

/**
 * B站「分P」（多 P 视频）的 web 层建模 + 按分P取流（复刻上游 BilibiliParser 的
 * playurl 配方，见下方逐项对照）。
 *
 * 上游 BilibiliParser 只解析第一个分P（其类注释明示，parse 里 cid 写死
 * `pages[0].cid`），且是上游文件、既定红线不改——分P能力在这里补：
 * - [parse]：纯函数，把 view 接口响应里的 `data.pages[]` 拉成选集列表；
 * - [fetchMedia]：按指定分P的 cid 调 playurl 取媒体，配方与 BilibiliParser
 *   对应代码逐项对齐（端点/参数/请求头/响应解析/组装方式）。parser 的 playurl
 *   是无 WBI 签名的普通 GET（`VideoHttp.getJson` + 静态头），`dashQuality`
 *   仅依赖 (bvid, cid, cookie) 三个入参、无私有状态，故可完整复刻。
 *
 * 复刻对照（本文件 ↔ BilibiliParser.kt）：
 * - [apiHeaders] ↔ parser:46-50（UA_CHROME + Referer bilibili.com + Cookie 若有）；
 * - [mediaHeaders] ↔ parser:59-63（与 apiHeaders 同内容，parser 分开两份故这里照分）；
 * - [dashMedia] 的请求 URL ↔ parser:200（`qn=120&fnval=4048&fnver=0&fourk=1`）、
 *   媒体组装 ↔ parser:115-124（fileName/headers/quality 文案/audioUrl/audioHeaders）；
 * - [dashFromJson] ↔ parser:203-222（data.dash.video/audio 按 id 取 max）；
 * - [JSONObject.dashUrl] ↔ parser:224-227（baseUrl 优先、base_url 兜底、滤 "null" 串）；
 * - [durlMedia] 的请求 URL ↔ parser:136-139
 *   （`qn=80&fnval=1&fnver=0&fourk=1&platform=html5&high_quality=1`）、
 *   组装 ↔ parser:164-174（url/size/quality/fileName/headers）；
 * - [qualityLabel] ↔ parser:230-243；[httpsUrl] ↔ parser:236-240；
 * - [fileNameBase] ↔ parser:244-246（DownloadPathPolicy.sanitizeName 后截 80）；
 * - DASH 尝试条件 ↔ parser:109：仅 cookie 非空时才试 DASH，失败/未登录退 durl。
 */
object BilibiliPart {

    /** 一个分P（服务端会话内保存，cid 不下发前端） */
    data class Part(
        /** 分P序号（view 的 pages[].page，从 1 起） */
        val index: Int,
        val cid: Long,
        /** 分P标题（pages[].part） */
        val title: String,
        /** 秒；取不到为 0 */
        val duration: Int
    )

    data class Info(
        val bvid: String,
        /** 视频总标题（data.title；用作批量下载的文件夹名来源） */
        val title: String,
        /** 落盘文件夹名（[VideoNaming.folderName] 清洗后，单层目录名） */
        val folder: String,
        val parts: List<Part>,
        /** 链接指定的分P（已按范围回退：不在 1..parts 内时为 1） */
        val currentIndex: Int
    )

    private const val PLAY_URL = "https://api.bilibili.com/x/player/playurl"

    /**
     * 解析 view 接口原始响应里的 `data.pages[]`。不是多P（code 非 0 / 无 pages /
     * 有效分P < 2）返回 null，并通过 [log] 留一行降级原因。
     * [requestedPart] 为链接 `?p=` 指定的分P（取不到时调用方传 1），超出有效
     * 范围回退 1。
     */
    fun parse(body: String, currentBvid: String, requestedPart: Int, log: (String) -> Unit = {}): Info? {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            log("view 响应不是合法 JSON：${e.message}")
            return null
        }
        if (root.optInt("code") != 0) {
            log("view 接口 code=${root.optInt("code")} msg=${root.optString("message")}")
            return null
        }
        val data = root.optJSONObject("data")
        if (data == null) {
            log("view 响应缺 data 字段")
            return null
        }
        val pages = data.optJSONArray("pages")
        if (pages == null) {
            log("无 pages 字段，不是多P视频")
            return null
        }
        val parts = mutableListOf<Part>()
        for (i in 0 until pages.length()) {
            val pg = pages.optJSONObject(i) ?: continue
            val cid = pg.optLong("cid")
            if (cid <= 0L) continue // 无 cid 的分P取不了流，跳过（其余分P保留原 page 号）
            parts += Part(
                index = pg.optInt("page", i + 1),
                cid = cid,
                title = pg.optString("part"),
                duration = pg.optInt("duration")
            )
        }
        if (parts.size < 2) {
            log("有效分P数 ${parts.size} < 2，不按分P展示")
            return null
        }
        val title = data.optString("title")
        val current = if (parts.any { it.index == requestedPart }) requestedPart else 1
        return Info(
            bvid = data.optString("bvid").ifBlank { currentBvid },
            title = title,
            folder = VideoNaming.folderName(title),
            parts = parts,
            currentIndex = current
        )
    }

    // ------------------------------------------------------------------
    // 按分P取流（复刻 BilibiliParser 的 playurl 配方）
    // ------------------------------------------------------------------

    /** 页面/接口请求头（复刻 BilibiliParser.apiHeaders:46-50） */
    private fun apiHeaders(cookie: String): Map<String, String> = buildMap {
        put("User-Agent", VideoHttp.UA_CHROME)
        put("Referer", "https://www.bilibili.com/")
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    /** 下载直链的请求头（复刻 BilibiliParser.mediaHeaders:59-63） */
    private fun mediaHeaders(cookie: String): Map<String, String> = buildMap {
        put("User-Agent", VideoHttp.UA_CHROME)
        put("Referer", "https://www.bilibili.com/")
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    /**
     * 取指定分P的媒体：与 BilibiliParser.parse 的控制流一致——已登录（cookie
     * 非空）先试 DASH 高清分轨，拿不到退单文件 durl；未登录直接 durl。
     * 文件名基名以该分P标题为准（回退视频总标题），与 parser 用 data.title 的
     * 口径对应到分P粒度。取不到任何流时抛 [IllegalStateException]（友好文案）。
     */
    suspend fun fetchMedia(
        bvid: String,
        cid: Long,
        cookie: String,
        partTitle: String,
        videoTitle: String
    ): VideoMedia {
        val fileBase = fileNameBase(partTitle.ifBlank { videoTitle }, "bilibili_$bvid")
        if (cookie.isNotBlank()) {
            dashMedia(bvid, cid, cookie, fileBase)?.let { return it }
        }
        return durlMedia(bvid, cid, cookie, fileBase)
    }

    /** DASH 分轨媒体（复刻 BilibiliParser.parse:110-125 + dashQuality:197-222 的组装） */
    private suspend fun dashMedia(bvid: String, cid: Long, cookie: String, fileBase: String): VideoMedia? {
        val json = runCatching {
            VideoHttp.getJson(
                "$PLAY_URL?bvid=$bvid&cid=$cid&qn=120&fnval=4048&fnver=0&fourk=1",
                apiHeaders(cookie),
                "B站取 DASH 直链"
            )
        }.getOrNull() ?: return null
        val dash = dashFromJson(json) ?: return null
        return VideoMedia(
            url = dash.videoUrl,
            kind = VideoMediaKind.VIDEO,
            fileName = "$fileBase.mp4",
            headers = mediaHeaders(cookie),
            quality = "${qualityLabel(dash.quality)}（分轨，下载后合轨）",
            audioUrl = dash.audioUrl,
            audioHeaders = mediaHeaders(cookie)
        )
    }

    /** DASH 分轨直链（视频轨 + 音轨 + 清晰度编号） */
    data class DashStream(val videoUrl: String, val audioUrl: String, val quality: Int)

    /** 纯函数：从 playurl 响应取最优 DASH 双轨（复刻 BilibiliParser.dashQuality:203-222 的解析段） */
    fun dashFromJson(json: JSONObject): DashStream? {
        val dash = json.optJSONObject("data")?.optJSONObject("dash") ?: return null
        val videos = dash.optJSONArray("video") ?: return null
        val audios = dash.optJSONArray("audio") ?: return null
        val bestVideo = (0 until videos.length())
            .mapNotNull { videos.optJSONObject(it) }
            .maxByOrNull { it.optInt("id") } ?: return null
        val bestAudio = (0 until audios.length())
            .mapNotNull { audios.optJSONObject(it) }
            .maxByOrNull { it.optInt("id") } ?: return null
        val videoUrl = bestVideo.dashUrl() ?: return null
        val audioUrl = bestAudio.dashUrl() ?: return null
        return DashStream(videoUrl = videoUrl, audioUrl = audioUrl, quality = bestVideo.optInt("id"))
    }

    /** 直链字段名有两种（baseUrl / base_url），且可能被下发成 JSON null（复刻 BilibiliParser:224-227） */
    private fun JSONObject.dashUrl(): String? =
        (optString("baseUrl").takeIf { it.isNotBlank() } ?: optString("base_url"))
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { httpsUrl(it) }

    /** 单文件 durl 媒体（复刻 BilibiliParser.parse:134-175）；无直链抛友好异常 */
    private suspend fun durlMedia(bvid: String, cid: Long, cookie: String, fileBase: String): VideoMedia {
        val play = VideoHttp.getJson(
            "$PLAY_URL?bvid=$bvid&cid=$cid&qn=80&fnval=1&fnver=0&fourk=1&platform=html5&high_quality=1",
            apiHeaders(cookie),
            "B站取直链"
        )
        return durlFromJson(play, fileBase, cookie)
    }

    /** 纯函数：从 playurl 响应组装 durl 单文件媒体（复刻 BilibiliParser.parse:143-175 的解析段） */
    fun durlFromJson(play: JSONObject, fileBase: String, cookie: String): VideoMedia {
        val playData = play.optJSONObject("data")
        val durl = playData?.optJSONArray("durl")
        if (durl == null || durl.length() == 0) {
            throw IllegalStateException(
                "B站既未返回单文件直链、也未返回可合轨的分轨流" +
                    "（code=${play.optInt("code")} msg=${play.optString("message")}）：该视频可能是番剧/付费内容"
            )
        }
        val first = durl.optJSONObject(0)
            ?: throw IllegalStateException("B站 durl 为空")
        val url = first.optString("url")
        if (url.isBlank()) {
            throw IllegalStateException("B站直链为空（可能需要登录或已被风控）")
        }
        return VideoMedia(
            url = httpsUrl(url),
            kind = VideoMediaKind.VIDEO,
            fileName = "$fileBase.mp4",
            size = first.optLong("size", -1L),
            headers = mediaHeaders(cookie),
            quality = qualityLabel(playData.optInt("quality"))
        )
    }

    /** B站清晰度编号 → 展示文案（复刻 BilibiliParser.qualityLabel:230-243） */
    fun qualityLabel(qn: Int): String = when (qn) {
        6, 7 -> "240P"
        16 -> "360P"
        32 -> "480P"
        64 -> "720P"
        74 -> "720P60"
        80 -> "1080P"
        112 -> "1080P+"
        116 -> "1080P60"
        120 -> "4K"
        else -> if (qn > 0) "画质 $qn" else ""
    }

    /** 接口偶尔给 `//` 开头的协议相对地址（复刻 BilibiliParser.httpsUrl:236-240） */
    private fun httpsUrl(url: String): String = when {
        url.isBlank() -> ""
        url.startsWith("//") -> "https:$url"
        else -> url
    }

    /** 文件名：标题清洗后截断，标题为空时用兜底名（复刻 BilibiliParser.fileNameBase:244-246） */
    private fun fileNameBase(title: String, fallback: String): String {
        val cleaned = DownloadPathPolicy.sanitizeName(title).take(80)
        return cleaned.ifBlank { fallback }
    }
}
