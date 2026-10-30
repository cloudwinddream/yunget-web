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
    val quarkNoSave: Boolean = true
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
    val medias: List<VideoMediaItemDto> = emptyList()
)

@Serializable
data class VideoDownloadRequest(
    val sessionId: String,
    /** 要下载的媒体下标（对应 VideoMediaItemDto.index）；空 = 全部 */
    val indexes: List<Int> = emptyList()
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
