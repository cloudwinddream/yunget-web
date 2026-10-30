package com.yunget.web.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiResult<T>(val ok: Boolean, val data: T? = null, val message: String = "")

fun <T> ok(data: T): ApiResult<T> = ApiResult(true, data)

@Serializable
data class ApiError(val ok: Boolean = false, val message: String = "")

fun fail(message: String): ApiError = ApiError(false, message)

@Serializable
data class PlatformInfo(
    val id: String,
    val name: String,
    val loginType: String, // cookie | password
    val loggedIn: Boolean,
    val nickname: String = ""
)

@Serializable
data class ParseRequest(val link: String, val pwd: String = "")

@Serializable
data class FavoriteRequest(val favorite: Boolean)

@Serializable
data class ParseResponse(
    val sessionId: String,
    val platform: String,
    val platformName: String,
    val title: String,
    val historyId: String = "",
    val favorite: Boolean = false
)

@Serializable
data class FileItem(
    val fid: String,
    val fname: String,
    val fsize: Long,
    val isdir: Boolean,
    val pdirFid: String = "",
    val fidToken: String = "",
    val modifyTime: String = "",
    // 相对下载目录的子路径（文件夹下载展开时由后端填充，普通文件下载为空）
    val relPath: String = ""
)

@Serializable
data class FileListRequest(val dirFid: String = "0")

@Serializable
data class DownloadSubmitRequest(
    val sessionId: String,
    val files: List<FileItem> = emptyList(),
    // 文件夹展开下载时传此 ID（由 /api/downloads/expand 返回）
    val expandId: String = ""
)

@Serializable
data class ExpandRequest(val sessionId: String, val files: List<FileItem>)

@Serializable
data class ExpandResponse(
    val expandId: String,
    val batchName: String,
    val fileCount: Int,
    val totalSize: Long,
    val truncated: Boolean = false
)

@Serializable
data class BatchActionRequest(val ids: List<Long>)

@Serializable
data class DirectDownloadRequest(
    val url: String,
    val fileName: String = "",
    val headers: Map<String, String> = emptyMap()
)

/** Gopeed 磁力引擎状态（设置页/任务页共用） */
@Serializable
data class GopeedStatusResponse(
    val installed: Boolean,
    val running: Boolean,
    val version: String = "",
    val os: String,
    val arch: String
)

/** Gopeed 任务的归一化视图（字段映射见 GopeedService.listTasks） */
@Serializable
data class GopeedTaskView(
    val id: String,
    val name: String,
    val total: Long,
    val downloaded: Long,
    val speed: Long,
    val status: String
)

@Serializable
data class GopeedCreateTaskRequest(
    val url: String,
    val name: String = ""
)

@Serializable
data class TaskInfo(
    val id: Long,
    val fileName: String,
    val status: String, // downloading | paused | completed | failed
    val downloaded: Long,
    val total: Long,
    val speed: Long,
    val etaMillis: Long = -1,
    val error: String = "",
    val createdAt: Long,
    // 文件夹下载批次（普通单文件下载为空）
    val batchId: String = "",
    val batchName: String = "",
    val relPath: String = ""
)

@Serializable
data class SettingsData(
    val maxConcurrentTasks: Int = 3,
    val speedLimitBps: Long = 0,
    val maxRetries: Int = 3,
    // 服务器上的下载目录；留空则用当前用户的下载目录（如 ~/Downloads）
    val downloadDir: String = "",
    // 分网盘连接数（键：quark/uc/xunlei/baidu/pan123/c139），每个网盘单独设置（照搬原版模型）；
    // 缺省=默认 32，迅雷固定 8（后端强制，不可改）；普通直链下载用 32
    val platformConnections: Map<String, Int> = emptyMap(),
    // 分网盘同时下载任务数覆盖（键同上）；缺省或 <=0 表示跟随全局同时任务数。
    // 限流是分网盘的（迅雷被限流不影响夸克），各网盘单独限流比全局一个闸门更合理。
    val platformConcurrentTasks: Map<String, Int> = emptyMap(),
    // 失败后自动重试（退避 1→2→4→8→10 分钟），适合被限流后隔一阵自动续传
    val autoRetry: Boolean = true,
    val autoRetryMax: Int = 10,
    // 夸克免转存下载（登录态）：优先拿分享凭证直取直链，不占本账号空间；失败自动回退转存
    val quarkNoSave: Boolean = true,
    // ffmpeg 可执行文件路径（B站 DASH 分轨合轨用）；留空则按 PATH → 常见安装路径顺序自动探测
    val ffmpegPath: String = "",
    // yt-dlp 可执行文件路径（YouTube 解析用）；留空则按 托管安装 → PATH 顺序自动探测
    val ytdlpPath: String = "",
    // yt-dlp 出站代理（YouTube 在部分网络必须走代理，如 http://127.0.0.1:7890）；留空 = 直连
    val ytdlpProxy: String = "",
    // Deno 可执行文件路径（yt-dlp 解析 YouTube 需要的 JavaScript 运行时）；留空则按 托管安装 → PATH → 常见路径顺序自动探测
    val denoPath: String = "",
    // 工具安装代理：ffmpeg / yt-dlp / Gopeed 三家「一键安装」下载官方源时共用
    // （GitHub 等官方源在部分网络直连不通、TLS 握手会被掐）；支持 http:// / https:// / socks5://；留空 = 直连
    val toolProxy: String = "",
    // 视频下载命名模板（仅作用于视频/YouTube 的标题生成型文件名，网盘保持原名）；
    // 变量：{title} 标题 {author} 作者 {id} 作品ID {platform} 平台 {quality} 清晰度 {date} 发布日期 {index} 序号（从 1 开始）
    val videoNameTemplate: String = "{title}"
)

@Serializable
data class DirListResponse(
    val path: String,
    val parent: String,
    val dirs: List<String>
)

@Serializable
data class CookieLoginRequest(val cookie: String)

@Serializable
data class QrStatusResponse(val status: String, val nickname: String? = null, val message: String? = null)

@Serializable
data class BatchDeleteRequest(val ids: List<Long>, val deleteFile: Boolean = false)

@Serializable
data class PasswordLoginRequest(val username: String, val password: String)

/** 个人盘文件操作（蓝奏云起步，后续平台共用） */
@Serializable
data class DriveDownloadRequest(val fid: String, val fname: String = "", val fidToken: String = "")

@Serializable
data class DriveFolderRequest(val parentId: String = "", val name: String)

@Serializable
data class DriveRenameRequest(val isDir: Boolean, val id: String, val name: String)

@Serializable
data class DriveItemRequest(
    val isDir: Boolean,
    val id: String,
    /** 文件名（光鸭建分享的标题用；其余平台忽略） */
    val fname: String = "",
    /** 父目录 id（115 删除接口需要 pid；其余平台忽略） */
    val pdirFid: String = ""
)

@Serializable
data class XunleiSmsRequest(val mobile: String)

@Serializable
data class XunleiSmsLoginRequest(
    val mobile: String,
    val code: String,
    val creditKey: String,
    val smsToken: String
)

@Serializable
data class XunleiLoginResponse(
    val needSms: Boolean,
    val message: String = "",
    val nickname: String = "",
    val creditKey: String = "",
    val smsToken: String = "",
    // 迅雷风控的验证页面（如有），可在浏览器打开完成验证
    val reviewUrl: String = ""
)

// ---------- 视频解析 / GitHub（v1.0.28） ----------

@Serializable
data class VideoParseRequest(val link: String)

@Serializable
data class VideoMediaItemDto(
    val index: Int,
    /** VIDEO | IMAGE | AUDIO */
    val kind: String,
    val fileName: String,
    val size: Long,
    val quality: String = "",
    val isHls: Boolean = false,
    /** true = 音视频分轨（B站 DASH），下载会生成视频轨 + 音轨两个任务，需自行合轨 */
    val needsMux: Boolean = false
)

@Serializable
data class VideoParseResponse(
    val sessionId: String,
    /** 平台枚举名（DOUYIN / BILIBILI / …） */
    val platform: String,
    val platformName: String,
    val title: String,
    val author: String = "",
    val cover: String = "",
    val text: String = "",
    /** 非致命降级提示（解析器给出，可能为空） */
    val notice: String = "",
    val medias: List<VideoMediaItemDto> = emptyList(),
    /** 所属合集信息（仅 B站合集）；不属于合集或补查失败时为 null */
    val listInfo: VideoListInfoDto? = null,
    /** 分P选集信息（仅 B站多P视频）；与 [listInfo] 独立，可同时存在 */
    val partsInfo: VideoListInfoDto? = null,
    /**
     * true = 链接指定了第 N 集（N>1）但该集取流失败、主下载区已回退为第 1 集，
     * 前端需醒目提示用户到分P选集区下载第 N 集（见 [requestedPart]）
     */
    val partMismatch: Boolean = false,
    /** 链接 `?p=` 指定的分P序号（已按范围回退）；无分P信息时为 0 */
    val requestedPart: Int = 0
)

@Serializable
data class VideoListEpisodeDto(
    /** 列表内全局序号（从 1 起，多小节已拉平） */
    val index: Int,
    val bvid: String,
    val title: String,
    /** 秒；0 = 未知 */
    val duration: Int = 0
)

@Serializable
data class VideoListInfoDto(
    /** 列表类型：season = B站合集 / parts = B站分P（episodes 的 bvid 为视频本身、index 为分P号） */
    val kind: String,
    val id: Long,
    val title: String,
    /** 批量下载落盘的子文件夹名（已清洗，下载目录下的单层目录名） */
    val folder: String = "",
    val total: Int,
    /** 当前视频在列表中的序号（1 起）；不在列表中为 0 */
    val currentIndex: Int = 0,
    val episodes: List<VideoListEpisodeDto> = emptyList()
)

@Serializable
data class VideoListDownloadRequest(
    val sessionId: String,
    /** 要下载的集序号（对应 VideoListEpisodeDto.index）；空 = 全部 */
    val indexes: List<Int> = emptyList(),
    /** 列表类型：season = B站合集（缺省，兼容旧前端） / parts = B站分P */
    val kind: String = "season"
)

@Serializable
data class VideoListEntryResultDto(
    val index: Int,
    val bvid: String,
    val title: String,
    val ok: Boolean,
    val message: String = "",
    val taskIds: List<Long> = emptyList()
)

@Serializable
data class VideoListDownloadResponse(
    /** 实际落盘的文件夹名（下载目录下的子目录） */
    val folder: String = "",
    val results: List<VideoListEntryResultDto> = emptyList()
)

@Serializable
data class VideoDownloadRequest(
    val sessionId: String,
    /** 要下载的媒体下标（对应 VideoMediaItemDto.index）；空 = 全部 */
    val indexes: List<Int> = emptyList()
)

@Serializable
data class BilibiliLoginRequest(val cookie: String)

@Serializable
data class BilibiliAccountResponse(
    val loggedIn: Boolean,
    val nickname: String = ""
)

@Serializable
data class FfmpegStatusResponse(
    val available: Boolean,
    /** ffmpeg 版本号（如 6.1.1）；不可用时为空 */
    val version: String = "",
    /** 实际命中的可执行文件路径；不可用时为空 */
    val path: String = "",
    /** 命中来源：manual（设置项路径）| app（应用托管安装）| path（系统 PATH）| system（常见安装路径）；不可用时为空 */
    val source: String = "",
    /** 手动路径无效原因（仅当设置了手动路径且该候选探测失败时非空）：路径不存在/目录下无可执行文件/文件无法运行/运行超时；未设置或手动路径有效时为 null */
    val manualHint: String? = null
)

@Serializable
data class GitHubParseRequest(val link: String)

@Serializable
data class GitHubItemDto(
    val index: Int,
    val name: String,
    /** 字节数；未知为 -1 */
    val size: Long,
    /** file | zip | release */
    val kind: String
)

@Serializable
data class GitHubParseResponse(
    val sessionId: String,
    val title: String,
    val items: List<GitHubItemDto> = emptyList()
)

@Serializable
data class GitHubDownloadRequest(
    val sessionId: String,
    /** 要下载的条目下标；空 = 全部 */
    val indexes: List<Int> = emptyList()
)

// ---------- YouTube 解析（yt-dlp 托管工具，v1.0.30） ----------

@Serializable
data class YtDlpInstallRequest(
    /** true = 强制更新：跳过「已检测到就返回现状」的早退，重下官方最新版到托管目录 */
    val force: Boolean = false
)

@Serializable
data class YtDlpStatusResponse(
    val available: Boolean,
    /** yt-dlp 版本（如 2025.10.14）；不可用时为空 */
    val version: String = "",
    /** 实际命中的可执行文件路径；不可用时为空 */
    val path: String = "",
    /** 命中来源：manual（设置项路径）| app（应用托管安装）| path（系统 PATH）| system（常见安装路径）；不可用时为空 */
    val source: String = "",
    /** 手动路径无效原因（仅当设置了手动路径且该候选探测失败时非空）：路径不存在/目录下无可执行文件/文件无法运行/运行超时；未设置或手动路径有效时为 null */
    val manualHint: String? = null
)

@Serializable
data class DenoStatusResponse(
    val available: Boolean,
    /** Deno 版本（如 2.9.8）；不可用时为空 */
    val version: String = "",
    /** 实际命中的可执行文件路径；不可用时为空 */
    val path: String = "",
    /** 命中来源：manual（设置项路径）| app（应用托管安装）| path（系统 PATH）| system（常见安装路径）；不可用时为空 */
    val source: String = "",
    /** 手动路径无效原因（仅当设置了手动路径且该候选探测失败时非空）：路径不存在/目录下无可执行文件/文件无法运行/运行超时；未设置或手动路径有效时为 null */
    val manualHint: String? = null
)

@Serializable
data class YouTubeAccountResponse(
    /** 是否已保存 Cookie（仅存不校验，YouTube 无轻量校验接口） */
    val saved: Boolean
)

@Serializable
data class YouTubeQualityDto(
    /** 视频高度（像素）；-1 = 仅音频伪档 */
    val height: Int,
    val label: String
)

@Serializable
data class YouTubeSubtitleDto(
    /** 语言键（原样保留，如 zh-Hans / en-orig）；下载时按此键回查字幕轨 */
    val lang: String,
    /** 展示名（yt-dlp 给出的轨道名，缺失时回退为语言键） */
    val name: String,
    /** true = 自动生成字幕（automatic_captions）；false = 官方/上传字幕 */
    val auto: Boolean
)

@Serializable
data class YouTubeEntryDto(
    /** 在播放列表中的下标（0 起） */
    val index: Int,
    val id: String,
    val title: String,
    /** 秒；未知为 -1 */
    val duration: Long = -1
)

@Serializable
data class YouTubeParseResponse(
    val sessionId: String,
    /** video | playlist */
    val kind: String,
    val title: String,
    val author: String = "",
    val cover: String = "",
    /** 秒；播放列表为 -1 */
    val duration: Long = -1,
    /** 可选清晰度档位（降序，末尾可能含「仅音频」伪档）；播放列表为常用档位表 */
    val qualities: List<YouTubeQualityDto> = emptyList(),
    val subtitles: List<YouTubeSubtitleDto> = emptyList(),
    /** 播放列表条目；单视频为空 */
    val entries: List<YouTubeEntryDto> = emptyList(),
    /** 格式概览文案（如「共 38 个格式 · 最高 2160p」） */
    val formatsSummary: String = "",
    /** 播放列表批量下载落盘的子文件夹名（已清洗）；单视频为空 */
    val folder: String = ""
)

@Serializable
data class YouTubeDownloadRequest(
    val sessionId: String,
    /** 目标清晰度：0 = 最高；-1 = 仅音频；正数 = 不超过该高度的最优 */
    val targetHeight: Int = 0,
    /** 要一并下载的字幕语言键（对应 YouTubeSubtitleDto.lang） */
    val subLangs: List<String> = emptyList(),
    /** 是否允许取自动生成字幕（语言键只在 automatic_captions 里时） */
    val includeAuto: Boolean = false
)

@Serializable
data class YouTubeDownloadResponse(
    val taskIds: List<Long> = emptyList(),
    /** 非致命提示（如分轨但无 ffmpeg、字幕缺轨等），可能为空 */
    val note: String = ""
)

@Serializable
data class YouTubePlaylistDownloadRequest(
    val sessionId: String,
    /** 选中的条目视频 ID（对应 YouTubeEntryDto.id） */
    val entryIds: List<String> = emptyList(),
    val targetHeight: Int = 0,
    val subLangs: List<String> = emptyList(),
    val includeAuto: Boolean = false
)

@Serializable
data class YouTubeEntryResultDto(
    val id: String,
    val title: String,
    val ok: Boolean,
    val message: String = "",
    val taskIds: List<Long> = emptyList()
)

@Serializable
data class YouTubePlaylistDownloadResponse(
    val results: List<YouTubeEntryResultDto> = emptyList()
)
