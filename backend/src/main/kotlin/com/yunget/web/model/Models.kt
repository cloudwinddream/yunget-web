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
    val maxConnections: Int = 16,
    val maxConcurrentTasks: Int = 3,
    val speedLimitBps: Long = 0,
    val maxRetries: Int = 3,
    // 服务器上的下载目录；留空则用当前用户的下载目录（如 ~/Downloads）
    val downloadDir: String = "",
    // 分网盘连接数覆盖（键：quark/uc/xunlei/baidu/pan123/c139）；缺省或 <=0 表示跟随全局连接数
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
