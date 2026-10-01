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
data class ParseResponse(
    val sessionId: String,
    val platform: String,
    val platformName: String,
    val title: String
)

@Serializable
data class FileItem(
    val fid: String,
    val fname: String,
    val fsize: Long,
    val isdir: Boolean,
    val pdirFid: String = "",
    val fidToken: String = "",
    val modifyTime: String = ""
)

@Serializable
data class FileListRequest(val dirFid: String = "0")

@Serializable
data class DownloadSubmitRequest(val sessionId: String, val files: List<FileItem>)

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
    val createdAt: Long
)

@Serializable
data class SettingsData(
    val maxConnections: Int = 16,
    val maxConcurrentTasks: Int = 3,
    val speedLimitBps: Long = 0,
    val maxRetries: Int = 3
)

@Serializable
data class CookieLoginRequest(val cookie: String)

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
    val smsToken: String = ""
)
