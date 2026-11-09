package com.yunget.web.service

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 任务「来源说明书」（v1.0.48 换链续下的地基）。
 *
 * 背景：网盘/视频直链都是带签名与时效的，大文件常下到一半链接就过期；
 * 任务记录里若只存当时的 URL，失败后点「继续」只能拿死链反复撞。
 * 入队时把「凭什么能再取一条新链」的来源信息编码进 TaskRecord.sourceRef，
 * 链接失效时按它重新取链、原地替换 URL/headers，分片（按 resumeKey 命名）
 * 与下载进度一律不动，即可接着下。
 *
 * 编码：竖线分段，每段 URLEncoder 编码（字段里出现 |、+、空格、中文都安全）。
 * 凭证（Cookie/令牌）一律不入 sourceRef——刷新时从 AccountStore 现取。
 *
 * 格式：
 * - share|<平台>|<shareUrl>|<pwd>|<fid>|<fidToken>
 * - drive|<平台>|<fid>|<fidToken>
 * - video|<VideoPlatform 名>|<页面URL>|<quality>|<kind>|<role>|<cid>（role: main/audio/mux-v/mux-a；cid 仅 B站分P 非 0）
 * - yt|<videoId>|<itag>|<role>|<lang>（role: track/mux-v/mux-a/sub；sub 时 itag 空、lang 为字幕语言）
 */
sealed interface SourceRef {
    fun encode(): String

    data class Share(val platform: String, val shareUrl: String, val pwd: String, val fid: String, val fidToken: String) : SourceRef {
        override fun encode(): String = join("share", platform, shareUrl, pwd, fid, fidToken)
    }

    data class Drive(val platform: String, val fid: String, val fidToken: String = "") : SourceRef {
        override fun encode(): String = join("drive", platform, fid, fidToken)
    }

    data class Video(
        val platform: String, val pageUrl: String, val quality: String,
        val kind: String, val role: String, val cid: Long = 0
    ) : SourceRef {
        override fun encode(): String =
            join("video", platform, pageUrl, quality, kind, role, cid.toString())
    }

    data class Yt(val videoId: String, val itag: String, val role: String, val lang: String = "") : SourceRef {
        override fun encode(): String = join("yt", videoId, itag, role, lang)
    }

    companion object {
        private fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())
        private fun dec(s: String): String =
            runCatching { URLDecoder.decode(s, Charsets.UTF_8.name()) }.getOrDefault(s)

        internal fun join(vararg parts: String): String = parts.joinToString("|") { enc(it) }

        /** 解码；格式不符/段数不对返回 null（旧任务 sourceRef 为空也走这里） */
        fun parse(raw: String): SourceRef? {
            if (raw.isBlank()) return null
            val p = raw.split('|').map { dec(it) }
            return when (p.firstOrNull()) {
                "share" -> if (p.size == 6) Share(p[1], p[2], p[3], p[4], p[5]) else null
                "drive" -> if (p.size == 4) Drive(p[1], p[2], p[3]) else null
                "video" -> if (p.size == 7) Video(p[1], p[2], p[3], p[4], p[5], p[6].toLongOrNull() ?: 0) else null
                "yt" -> if (p.size == 5) Yt(p[1], p[2], p[3], p[4]) else null
                else -> null
            }
        }

        /** 网盘平台 id 全集（legacy 反推时用它把 video/github 等非网盘键排除在外） */
        val NETDISK_PLATFORM_IDS = setOf(
            "quark", "uc", "xunlei", "baidu", "pan123", "c139",
            "lanzou", "ilanzou", "guangya", "pan115"
        )

        /**
         * 存量任务补救：v1.0.48 之前入队的网盘任务没有 sourceRef，但 resumeKey 统一是
         * `src-<平台>-<fid>-<size>`——平台 id 不含 `-`、size 为纯数字，按**首尾**切分可无损
         * 反推出 (平台, fid)。注意：分享与个人盘的 resumeKey 同形，无法区分来源，
         * 调用方一律按个人盘路径刷新（分享存量走「重新解析认领」的另一条路）；
         * 115 个人盘缺 fidToken（pickcode）时由调用方给出可读失败。
         */
        fun parseLegacyDriveResumeKey(resumeKey: String): Drive? {
            if (!resumeKey.startsWith("src-")) return null
            val rest = resumeKey.removePrefix("src-")
            val firstDash = rest.indexOf('-')
            if (firstDash <= 0) return null
            val platform = rest.substring(0, firstDash)
            if (platform !in NETDISK_PLATFORM_IDS) return null
            val tail = rest.substring(firstDash + 1)
            val lastDash = tail.lastIndexOf('-')
            if (lastDash <= 0) return null
            val fid = tail.substring(0, lastDash)
            if (fid.isBlank()) return null
            tail.substring(lastDash + 1).toLongOrNull() ?: return null
            return Drive(platform, fid, "")
        }
    }
}

/**
 * 链接失效类失败的文本判据（自动刷新用，集中放这一处便于将来改）。
 * 引擎已把 401/403/410 单独归为「授权/时效失败」（SegmentScheduler 的 AUTH_EXPIRED
 * 分支，文案形如「分片 X-Y 授权/时效失败（401/403/410）重试 N 次仍失败」），
 * 这里再兜住探测失败/令牌过期等其它来源的同类文案。误判成本低：刷新一次、
 * 取链失败即停，不会循环轰炸。
 */
fun isLinkExpiryFailure(reason: String): Boolean {
    if (reason.isBlank()) return false
    val r = reason.lowercase()
    return "401" in r || "403" in r || "410" in r ||
        "授权" in reason || "时效" in reason || "过期" in reason || "失效" in reason ||
        "令牌" in reason || "token" in r || "expired" in r ||
        "unauthorized" in r || "forbidden" in r
}
