package com.yunget.web.service

import com.yunget.app.data.network.BaiduApi
import com.yunget.app.data.network.BaiduConstants
import com.yunget.app.data.network.C139Api
import com.yunget.app.data.network.GuangYaApi
import com.yunget.app.data.network.GuangYaConstants
import com.yunget.app.data.network.GuangYaDevice
import com.yunget.app.data.network.GuangYaTokens
import com.yunget.app.data.network.ILanzouApi
import com.yunget.app.data.network.ILanzouConstants
import com.yunget.app.data.network.LanzouApi
import com.yunget.app.data.network.LanzouConstants
import com.yunget.app.data.network.LanzouLog
import com.yunget.app.data.network.LanzouLoginParams
import com.yunget.app.data.network.C139Constants
import com.yunget.app.data.network.Pan115Api
import com.yunget.app.data.network.Pan115Constants
import com.yunget.app.data.network.Pan123Api
import com.yunget.app.data.network.Pan123Constants
import com.yunget.app.data.network.QuarkApi
import com.yunget.app.data.network.QuarkConstants
import com.yunget.app.data.network.ShareLinkParser
import com.yunget.app.data.network.SharePlatform
import com.yunget.app.data.network.UCApi
import com.yunget.app.data.network.UCConstants
import com.yunget.app.data.network.XunleiApi
import com.yunget.app.data.network.XunleiConstants
import com.yunget.app.data.network.XunleiDeviceFingerprint
import com.yunget.app.data.network.XunleiKouling
import com.yunget.app.data.network.model.DownloadLink
import com.yunget.app.data.network.model.ShareFile
import com.yunget.app.data.network.model.ShareInfo
import com.yunget.app.data.network.model.ShareSession
import com.yunget.app.data.repository.BaiduResolveRepository
import com.yunget.app.data.repository.C139ResolveRepository
import com.yunget.app.data.repository.GuangYaResolveRepository
import com.yunget.app.data.repository.ILanzouAccountEntity
import com.yunget.app.data.repository.ILanzouAccountRepository
import com.yunget.app.data.repository.ILanzouResolveRepository
import com.yunget.app.data.repository.LanzouResolveRepository
import com.yunget.app.data.repository.Pan115ResolveRepository
import com.yunget.app.data.repository.Pan123ResolveRepository
import com.yunget.app.data.repository.QuarkResolveRepository
import com.yunget.app.data.repository.ShareResolveRepository
import com.yunget.app.data.repository.UCResolveRepository
import com.yunget.app.data.repository.XunleiResolveRepository
import com.yunget.web.model.ExpandResponse
import com.yunget.web.model.FileItem
import com.yunget.web.model.PlatformInfo
import com.yunget.web.model.XunleiLoginResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class Platform(val id: String, val displayName: String, val loginType: String) {
    QUARK("quark", "夸克网盘", "cookie"),
    UC("uc", "UC 网盘", "cookie"),
    XUNLEI("xunlei", "迅雷网盘", "password"),
    BAIDU("baidu", "百度网盘", "cookie"),
    PAN123("pan123", "123 云盘", "password"),
    C139("c139", "139 云盘（和彩云）", "cookie"),
    LANZOU("lanzou", "蓝奏云", "password"),
    ILANZOU("ilanzou", "蓝奏云优享版", "password"),
    GUANGYA("guangya", "光鸭云盘", "password"),
    PAN115("pan115", "115 网盘", "cookie");

    companion object {
        fun fromId(id: String): Platform? = values().find { it.id == id }
        fun fromSharePlatform(p: SharePlatform): Platform = when (p) {
            SharePlatform.QUARK -> QUARK
            SharePlatform.UC -> UC
            SharePlatform.XUNLEI -> XUNLEI
            SharePlatform.BAIDU -> BAIDU
            SharePlatform.PAN123 -> PAN123
            SharePlatform.C139 -> C139
            SharePlatform.LANZOU -> LANZOU
            SharePlatform.ILANZOU -> ILANZOU
            SharePlatform.GUANGYA -> GUANGYA
            SharePlatform.PAN115 -> PAN115
        }
    }
}

private const val SESSION_TTL_MS = 30L * 60 * 1000
private const val MAX_FOLDER_FILES = 5000

data class ResolveSession(
    val id: String,
    val platform: Platform,
    val session: ShareSession,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 网盘业务层：直接复用 YunGet 的协议解析代码（data/network + data/repository）。
 * 网页版与 Android 版的差异只在登录态获取方式：
 * - 夸克/UC/百度/139：用户在浏览器开发者工具中复制 Cookie 粘贴进来（替代 App 内 WebView 登录）
 * - 迅雷：账号密码（可能需短信二次验证）
 * - 123：账号密码换 JWT
 */
class NetdiskService(
    private val accounts: AccountStore,
    private val downloadService: DownloadService,
    private val history: HistoryStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val quarkApi = QuarkApi()
    private val ucApi = UCApi()
    private val xunleiApi = XunleiApi()
    private val baiduApi = BaiduApi()
    private val pan123Api = Pan123Api()
    private val c139Api = C139Api()
    private val lanzouApi = LanzouApi()
    private val ilanzouApi = ILanzouApi()
    private val guangyaApi = GuangYaApi()
    private val pan115Api = Pan115Api()

    /** 优享账号适配（上游 Room 版账号仓库的网页替身：读写落到 AccountStore 的 ilanzou 条目） */
    private val ilanzouAccounts = ILanzouAccountRepository(
        accountProvider = {
            accounts.get("ilanzou")?.let { acc ->
                ILanzouAccountEntity(
                    appToken = acc.token,
                    uuid = acc.deviceId,
                    account = acc.username,
                    password = acc.password,
                    userId = acc.userId,
                    nickname = acc.nickname
                )
            }
        },
        accountSaver = { e ->
            accounts.update("ilanzou") {
                it.copy(
                    token = e.appToken, deviceId = e.uuid, username = e.account,
                    password = e.password, userId = e.userId,
                    nickname = e.nickname.ifBlank { it.nickname }
                )
            }
        },
        api = ilanzouApi
    )

    private val repos: Map<Platform, ShareResolveRepository> = mapOf(
        Platform.QUARK to QuarkResolveRepository(quarkApi),
        Platform.UC to UCResolveRepository(ucApi),
        Platform.BAIDU to BaiduResolveRepository(baiduApi),
        Platform.C139 to C139ResolveRepository(c139Api),
        Platform.LANZOU to LanzouResolveRepository(lanzouApi),
        Platform.ILANZOU to ILanzouResolveRepository(ilanzouApi, ilanzouAccounts),
        Platform.GUANGYA to GuangYaResolveRepository(guangyaApi) { guangyaEnsureAccessToken() },
        Platform.PAN115 to Pan115ResolveRepository(pan115Api),
        Platform.PAN123 to Pan123ResolveRepository(pan123Api) { accounts.get("pan123")?.token },
        Platform.XUNLEI to XunleiResolveRepository(
            api = xunleiApi,
            accountProvider = { accounts.get("xunlei")?.xunleiAccessToken?.takeIf { it.isNotBlank() } },
            deviceIdProvider = { XunleiDeviceFingerprint.deviceId() },
            captchaProvider = { "" },
            refreshProvider = {
                val acc = accounts.get("xunlei")
                if (acc == null || acc.xunleiRefreshToken.isBlank()) null
                else {
                    val nt = xunleiApi.refreshToken(acc.xunleiRefreshToken, XunleiDeviceFingerprint.deviceId())
                    if (nt != null) {
                        accounts.update("xunlei") {
                            it.copy(xunleiAccessToken = nt.first, xunleiRefreshToken = nt.second)
                        }
                    }
                    nt
                }
            }
        )
    )

    private val sessions = ConcurrentHashMap<String, ResolveSession>()

    init {
        LanzouLog.install { msg -> println("[lanzou] $msg") }
        // 光鸭业务 API 的 did 头：同步取登录设备的 deviceId 缓存（照上游 GuangYaAccountRepository 的注入方式）
        guangyaApi.deviceIdProvider = { guangyaCachedDeviceId }
        // 迅雷 401 自动刷新（pan 请求内部使用）
        xunleiApi.refreshTokenProvider = { deviceId ->
            val acc = accounts.get("xunlei")
            if (acc == null || acc.xunleiRefreshToken.isBlank()) null
            else {
                val nt = xunleiApi.refreshToken(acc.xunleiRefreshToken, deviceId)
                if (nt != null) {
                    accounts.update("xunlei") {
                        it.copy(xunleiAccessToken = nt.first, xunleiRefreshToken = nt.second)
                    }
                }
                nt
            }
        }
        // 过期会话清理
        scope.launch {
            while (true) {
                delay(5 * 60 * 1000)
                val now = System.currentTimeMillis()
                sessions.entries.removeIf { now - it.value.createdAt > SESSION_TTL_MS }
            }
        }
    }

    // ---------- 账号 ----------

    fun platforms(): List<PlatformInfo> = Platform.values().map { p ->
        val acc = accounts.get(p.id)
        val loggedIn = when (p) {
            Platform.QUARK, Platform.UC, Platform.BAIDU, Platform.C139, Platform.PAN115 -> !acc?.cookie.isNullOrBlank()
            Platform.PAN123 -> !acc?.token.isNullOrBlank()
            Platform.XUNLEI -> !acc?.xunleiAccessToken.isNullOrBlank()
            Platform.LANZOU -> !acc?.cookie.isNullOrBlank()
            Platform.ILANZOU -> !acc?.token.isNullOrBlank()
            Platform.GUANGYA -> !acc?.token.isNullOrBlank()
        }
        PlatformInfo(p.id, p.displayName, p.loginType, loggedIn, acc?.nickname ?: "")
    }

    /** 网盘容量（字节）：原版登录后有容量显示，网页版账号页同样展示。未登录/取失败抛异常供前端提示 */
    suspend fun quota(platformId: String): Map<String, Long> {
        val p = Platform.fromId(platformId) ?: throw IllegalArgumentException("未知平台")
        val acc = accounts.get(p.id)
        fun cookie(): String = acc?.cookie?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("未登录")
        val q = when (p) {
            Platform.QUARK -> quarkApi.getQuota(cookie())
            Platform.UC -> ucApi.getQuota(cookie())
            Platform.BAIDU -> baiduApi.getQuota(cookie())
            Platform.C139 -> c139Api.getQuota(cookie())
            Platform.PAN123 -> pan123Api.getQuota(
                acc?.token?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("未登录")
            )
            Platform.XUNLEI -> xunleiApi.getQuota(
                acc?.xunleiAccessToken?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("未登录"),
                XunleiDeviceFingerprint.deviceId(), ""
            )
            // 蓝奏云未提供容量接口（上游同样无此能力）
            Platform.LANZOU -> throw IllegalStateException("蓝奏云不提供容量查询")
            Platform.ILANZOU -> ilanzouApi.getQuota(
                ilanzouAccounts.ensureAppToken() ?: throw IllegalStateException("未登录"),
                acc?.deviceId?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("未登录")
            )
            Platform.GUANGYA -> guangyaApi.getQuota(
                guangyaEnsureAccessToken() ?: throw IllegalStateException("未登录"),
                guangyaDeviceOf(acc)
            )
            Platform.PAN115 -> pan115Api.getQuota(cookie())
        } ?: throw IllegalStateException("获取容量失败，请稍后重试")
        return mapOf("used" to q.used, "total" to q.total, "usedInTrash" to q.usedInTrash)
    }

    suspend fun saveCookie(platformId: String, cookie: String): Result<String> {
        val p = Platform.fromId(platformId) ?: return Result.failure(IllegalArgumentException("未知平台"))
        val nickname = when (p) {
            Platform.QUARK -> quarkApi.fetchNickname(cookie)
            Platform.UC -> ucApi.fetchNickname(cookie)
            Platform.BAIDU -> baiduApi.fetchNickname(cookie)
            Platform.C139 -> {
                // 139 要求 Cookie 中携带账号信息
                if (C139Constants.extractAccountFull(cookie).isNullOrBlank()) {
                    return Result.failure(IllegalStateException("Cookie 中缺少账号信息，请确认复制了完整的 Cookie"))
                }
                "139用户"
            }
            Platform.PAN115 -> {
                // 115 凭证就是整串 Cookie（UID/CID/SEID/KID），先校验 UID 在不在，再用 /user/info 验活取昵称
                if (!Pan115Constants.hasLoginCookie(cookie)) {
                    return Result.failure(IllegalStateException("Cookie 中缺少 UID 等登录字段，请确认复制了完整的 Cookie"))
                }
                pan115Api.fetchNickname(cookie)
            }
            else -> return Result.failure(IllegalArgumentException("该平台不支持 Cookie 登录"))
        } ?: return Result.failure(IllegalStateException("Cookie 无效或已过期（未能获取到账号昵称）"))
        accounts.save(p.id, AccountData(cookie = cookie, nickname = nickname))
        return Result.success(nickname)
    }

    suspend fun loginPan123(username: String, password: String): Result<String> {
        return try {
            val token = pan123Api.login(username.trim(), password)
            val nickname = pan123Api.fetchNickname(token) ?: username
            accounts.save("pan123", AccountData(token = token, nickname = nickname))
            Result.success(nickname)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun xunleiPasswordLogin(username: String, password: String): XunleiLoginResponse {
        return try {
            val deviceId = XunleiApi.newDeviceId()
            val step = xunleiApi.loginWithPassword(username.trim(), password, deviceId)
            when {
                step.needSms -> XunleiLoginResponse(
                    needSms = true,
                    message = "迅雷要求短信安全验证",
                    reviewUrl = step.reviewUrl
                )
                step.sessionId.isBlank() -> {
                    // 身份信息已失效[13]：密码已通过，但设备未过迅雷风控 → 引导改走短信验证
                    if (step.message.contains("身份信息已失效")) {
                        XunleiLoginResponse(
                            needSms = true,
                            message = "迅雷风控拦截：这台设备未通过身份验证。请改用下方短信验证码登录（用绑定的手机号），或先在官方迅雷 App 登录一次再回来。",
                            reviewUrl = step.reviewUrl
                        )
                    } else {
                        XunleiLoginResponse(
                            needSms = false,
                            message = step.message.ifBlank { "登录失败，请检查账号密码" },
                            reviewUrl = step.reviewUrl
                        )
                    }
                }
                else -> {
                    val captchaToken = xunleiApi.initCaptcha(deviceId, username.trim()) ?: ""
                    val tokens = xunleiApi.exchangeToken(step.sessionId, deviceId, captchaToken)
                        ?: return XunleiLoginResponse(needSms = false, message = "换取 Token 失败")
                    val nickname = step.nickname.ifBlank { username.trim() }
                    accounts.save(
                        "xunlei",
                        AccountData(
                            xunleiAccessToken = tokens.first,
                            xunleiRefreshToken = tokens.second,
                            nickname = nickname
                        )
                    )
                    XunleiLoginResponse(needSms = false, nickname = nickname)
                }
            }
        } catch (e: Exception) {
            XunleiLoginResponse(needSms = false, message = "登录异常：${e.message}")
        }
    }

    suspend fun xunleiSendSms(mobile: String): Result<XunleiLoginResponse> {
        return try {
            val step = xunleiApi.sendSms(mobile.trim(), XunleiApi.newDeviceId())
            if (step.smsCreditKey.isBlank()) {
                Result.failure(IllegalStateException(step.message.ifBlank { "短信发送失败" }))
            } else {
                Result.success(
                    XunleiLoginResponse(
                        needSms = true,
                        creditKey = step.smsCreditKey,
                        smsToken = step.smsToken
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun xunleiSmsLogin(mobile: String, code: String, creditKey: String, smsToken: String): Result<String> {
        return try {
            val deviceId = XunleiApi.newDeviceId()
            val step = xunleiApi.smsLogin(mobile.trim(), code.trim(), creditKey, smsToken, deviceId)
            if (step.sessionId.isBlank()) return Result.failure(IllegalStateException(step.message.ifBlank { "短信验证失败" }))
            val captchaToken = xunleiApi.initCaptcha(deviceId, mobile.trim()) ?: ""
            val tokens = xunleiApi.exchangeToken(step.sessionId, deviceId, captchaToken)
                ?: return Result.failure(IllegalStateException("换取 Token 失败"))
            val nickname = step.nickname.ifBlank { mobile.trim() }
            accounts.save(
                "xunlei",
                AccountData(
                    xunleiAccessToken = tokens.first,
                    xunleiRefreshToken = tokens.second,
                    nickname = nickname
                )
            )
            Result.success(nickname)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun logout(platformId: String) {
        accounts.remove(platformId)
    }


    suspend fun loginLanzou(username: String, password: String): Result<String> {
        return try {
            val name = username.trim()
            val cookie = lanzouApi.login(name, password)
            // 校验登录态确实可用（能取到 uid/vei 才算登录成功）
            lanzouApi.fetchLoginParams(cookie)
                ?: throw IllegalStateException("蓝奏云登录态无效，请重试")
            accounts.save("lanzou", AccountData(cookie = cookie, nickname = name))
            Result.success(name)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---------- 蓝奏个人盘 ----------

    private val lanzouParamsCache = ConcurrentHashMap<String, LanzouLoginParams>()

    /** 个人盘操作前置：登录 Cookie + uid/vei（按 Cookie 指纹缓存，避免每次多打一趟 mydisk） */
    private suspend fun lanzouParams(): Pair<String, LanzouLoginParams> {
        val cookie = credentialOf(Platform.LANZOU)
        val key = cookie.hashCode().toString()
        lanzouParamsCache[key]?.let { return cookie to it }
        val params = lanzouApi.fetchLoginParams(cookie)
            ?: throw IllegalStateException("蓝奏登录态无效，请重新登录")
        lanzouParamsCache[key] = params
        return cookie to params
    }

    suspend fun lanzouDriveList(dirFid: String): List<ShareFile> {
        val (cookie, params) = lanzouParams()
        val folderId = dirFid.ifBlank { LanzouConstants.ROOT_FOLDER_ID }
            .removePrefix(LanzouConstants.FOLDER_PREFIX)
            .ifBlank { LanzouConstants.ROOT_FOLDER_ID }
        return lanzouApi.listCloudFiles(cookie, params.uid, params.vei, folderId)
    }

    suspend fun lanzouDriveDownload(fid: String, fname: String): Long {
        val (cookie, params) = lanzouParams()
        val fileId = fid.removePrefix(LanzouConstants.FILE_PREFIX)
        val link = lanzouApi.getPersonalDownloadLink(cookie, params.uid, params.vei, fileId, fname)
        return downloadService.enqueue(
            url = link.downloadUrl,
            fileName = link.filename.ifBlank { fname },
            headers = downloadHeaders(Platform.LANZOU, link, cookie),
            size = link.size,
            platform = "lanzou",
            resumeKey = "src-lanzou-$fileId-${link.size}"
        )
    }

    suspend fun lanzouCreateFolder(parentId: String, name: String): String {
        val (cookie, params) = lanzouParams()
        val parent = parentId.removePrefix(LanzouConstants.FOLDER_PREFIX)
            .ifBlank { LanzouConstants.ROOT_FOLDER_ID }
        return lanzouApi.createFolder(cookie, params.uid, params.vei, parent, name)
    }

    suspend fun lanzouRename(isDir: Boolean, id: String, name: String) {
        val (cookie, params) = lanzouParams()
        if (isDir) {
            lanzouApi.renameFolder(cookie, params.uid, params.vei, id.removePrefix(LanzouConstants.FOLDER_PREFIX), name)
        } else {
            lanzouApi.renameFile(cookie, params.uid, params.vei, id.removePrefix(LanzouConstants.FILE_PREFIX), name)
        }
    }

    suspend fun lanzouDelete(isDir: Boolean, id: String) {
        val (cookie, params) = lanzouParams()
        if (isDir) {
            lanzouApi.deleteFolder(cookie, params.uid, params.vei, id.removePrefix(LanzouConstants.FOLDER_PREFIX))
        } else {
            lanzouApi.deleteFile(cookie, params.uid, params.vei, id.removePrefix(LanzouConstants.FILE_PREFIX))
        }
    }

    suspend fun lanzouShare(isDir: Boolean, id: String): ShareInfo {
        val (cookie, params) = lanzouParams()
        return if (isDir) {
            lanzouApi.createFolderShare(cookie, params.uid, params.vei, id.removePrefix(LanzouConstants.FOLDER_PREFIX))
        } else {
            lanzouApi.createFileShare(cookie, params.uid, params.vei, id.removePrefix(LanzouConstants.FILE_PREFIX))
        }
    }

    // ---------- 蓝奏云优享版：登录 / 令牌 / 个人盘 ----------

    /** 优享登录（照上游 ILanzouAccountRepository.login）：uuid 复用已存设备标识或新取，密码落库供 appToken 失效后静默重登 */
    suspend fun loginIlanzou(username: String, password: String): Result<String> {
        return try {
            val name = username.trim()
            val existing = accounts.get("ilanzou")
            val uuid = existing?.deviceId?.takeIf { Regex("^[A-Za-z0-9_-]{8,128}$").matches(it) }
                ?: ilanzouApi.getUuid()
            val token = ilanzouApi.login(name, password, uuid)
            val info = ilanzouApi.fetchAccountInfo(token, uuid)
            val nickname = info?.nickname.orEmpty().ifBlank { name }
            accounts.save(
                "ilanzou",
                AccountData(
                    token = token, username = name, password = password,
                    deviceId = uuid, userId = info?.userId ?: "", nickname = nickname
                )
            )
            Result.success(nickname)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 优享个人盘操作前置：有效 appToken + uuid（appToken 缺失时适配层会用保存的密码静默重登） */
    private suspend fun ilanzouTokenUuid(): Pair<String, String> {
        val token = ilanzouAccounts.ensureAppToken()
            ?: throw IllegalStateException("请先登录蓝奏云优享版")
        val uuid = accounts.get("ilanzou")?.deviceId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("请先登录蓝奏云优享版")
        return token to uuid
    }

    suspend fun ilanzouDriveList(dirFid: String): List<ShareFile> {
        val (token, uuid) = ilanzouTokenUuid()
        val folderId = dirFid.removePrefix(ILanzouConstants.FOLDER_PREFIX)
            .ifBlank { ILanzouConstants.ROOT_FOLDER_ID }
        return ilanzouApi.listFiles(token, uuid, folderId)
    }

    suspend fun ilanzouDriveDownload(fid: String, fname: String): Long {
        val (token, uuid) = ilanzouTokenUuid()
        val fileId = fid.removePrefix(ILanzouConstants.FILE_PREFIX)
        val userId = ilanzouAccounts.ensureUserId() ?: ""
        val link = ilanzouApi.getDownloadLink(token, uuid, fileId, userId)
            ?: throw IllegalStateException("蓝奏优享未返回下载地址")
        return downloadService.enqueue(
            url = link.downloadUrl,
            fileName = link.filename.ifBlank { fname },
            headers = downloadHeaders(Platform.ILANZOU, link, token),
            size = link.size,
            platform = "ilanzou",
            resumeKey = "src-ilanzou-$fileId-${link.size}"
        )
    }

    suspend fun ilanzouCreateFolder(parentId: String, name: String): String {
        val (token, uuid) = ilanzouTokenUuid()
        val parent = parentId.removePrefix(ILanzouConstants.FOLDER_PREFIX)
            .ifBlank { ILanzouConstants.ROOT_FOLDER_ID }
        return ilanzouApi.createDir(token, uuid, parent, name)
    }

    suspend fun ilanzouRename(isDir: Boolean, id: String, name: String) {
        val (token, uuid) = ilanzouTokenUuid()
        if (isDir) {
            ilanzouApi.renameFolder(token, uuid, id.removePrefix(ILanzouConstants.FOLDER_PREFIX), name)
        } else {
            ilanzouApi.renameFile(token, uuid, id.removePrefix(ILanzouConstants.FILE_PREFIX), name)
        }
    }

    suspend fun ilanzouDelete(isDir: Boolean, id: String) {
        val (token, uuid) = ilanzouTokenUuid()
        if (isDir) {
            ilanzouApi.deleteEntries(token, uuid, listOf(id.removePrefix(ILanzouConstants.FOLDER_PREFIX)), emptyList())
        } else {
            ilanzouApi.deleteEntries(token, uuid, emptyList(), listOf(id.removePrefix(ILanzouConstants.FILE_PREFIX)))
        }
    }

    /** 优享上游未提供「个人盘文件建分享」接口（只有分享转存入盘），故明确不支持 */
    suspend fun ilanzouShare(isDir: Boolean, id: String): ShareInfo =
        throw IllegalStateException("蓝奏云优享版暂不支持生成分享链接")

    // ---------- 光鸭云盘：登录 / 令牌续期 / 个人盘 ----------

    /** 最近一次读到的光鸭 deviceId 缓存：供 GuangYaApi.deviceIdProvider 同步取用（照上游账号仓库的做法） */
    @Volatile
    private var guangyaCachedDeviceId: String? = null

    /** 光鸭 access token 的本地到期时间（毫秒，0 = 未知；照上游：冷启动为 0，首次取用时会用 refresh 换一次） */
    @Volatile
    private var guangyaAccessExpiresAt: Long = 0L

    /** 设备标识：已存的复用，缺失时按上游规则生成一对（deviceId 32 位 hex + deviceSign） */
    private fun guangyaDeviceOf(acc: AccountData?): GuangYaDevice {
        val id = acc?.deviceId.orEmpty().ifBlank { GuangYaConstants.newDeviceId() }
        val sign = acc?.deviceSign.orEmpty().ifBlank { GuangYaConstants.newDeviceSign(id) }
        return GuangYaDevice(deviceId = id, deviceSign = sign)
    }

    private fun guangyaExpiryOf(tokens: GuangYaTokens): Long =
        if (tokens.expiresIn > 0) System.currentTimeMillis() + tokens.expiresIn * 1000L else 0L

    /** 光鸭登录（照上游 GuangYaAccountRepository.login）：设备标识首次生成后持久化，refresh 落库供续期 */
    suspend fun loginGuangya(username: String, password: String): Result<String> {
        return try {
            val name = username.trim()
            val existing = accounts.get("guangya")
            val device = guangyaDeviceOf(existing)
            guangyaCachedDeviceId = device.deviceId
            val tokens = guangyaApi.signIn(name, password, device)
            guangyaAccessExpiresAt = guangyaExpiryOf(tokens)
            val info = guangyaApi.fetchAccountInfo(tokens.accessToken, device)
            val nickname = info?.nickname.orEmpty().ifBlank { name }
            accounts.save(
                "guangya",
                AccountData(
                    token = tokens.accessToken,
                    refreshToken = tokens.refreshToken.ifBlank { existing?.refreshToken.orEmpty() },
                    deviceId = device.deviceId, deviceSign = device.deviceSign,
                    username = name, nickname = nickname
                )
            )
            Result.success(nickname)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 取有效 access token：临近或已过期时用 refresh_token 续期并落库（照上游 ensureAccessToken） */
    suspend fun guangyaEnsureAccessToken(): String? {
        val acc = accounts.get("guangya") ?: return null
        guangyaCachedDeviceId = acc.deviceId
        val now = System.currentTimeMillis()
        // 仍在有效期内（留 60s 缓冲）直接用
        if (acc.token.isNotBlank() && guangyaAccessExpiresAt > now + 60_000L) return acc.token
        // 无 refresh_token 时只能退回现有 access（冷启动未记录到期时间时它可能仍有效）
        if (acc.refreshToken.isBlank()) return acc.token.takeIf { it.isNotBlank() }
        val device = guangyaDeviceOf(acc)
        val refreshed = guangyaApi.refreshToken(acc.refreshToken, device)
            ?: return acc.token.takeIf { it.isNotBlank() }
        guangyaAccessExpiresAt = guangyaExpiryOf(refreshed)
        accounts.update("guangya") {
            it.copy(
                token = refreshed.accessToken,
                refreshToken = refreshed.refreshToken.ifBlank { acc.refreshToken },
                deviceId = device.deviceId, deviceSign = device.deviceSign
            )
        }
        return refreshed.accessToken
    }

    private suspend fun guangyaToken(): String =
        guangyaEnsureAccessToken() ?: throw IllegalStateException("请先登录光鸭云盘")

    suspend fun guangyaDriveList(dirFid: String): List<ShareFile> =
        guangyaApi.listCloudFiles(guangyaToken(), dirFid)

    suspend fun guangyaDriveDownload(fid: String, fname: String): Long {
        val token = guangyaToken()
        val file = ShareFile(fid = fid, fname = fname, fsize = 0L, isdir = false, pdirFid = "", fidToken = "")
        val link = guangyaApi.getDownloadLink(token, file)
            ?: throw IllegalStateException("光鸭未返回有效下载地址，请确认下载权限")
        return downloadService.enqueue(
            url = link.downloadUrl,
            fileName = link.filename.ifBlank { fname },
            headers = downloadHeaders(Platform.GUANGYA, link, token),
            size = link.size,
            platform = "guangya",
            resumeKey = "src-guangya-$fid-${link.size}"
        )
    }

    suspend fun guangyaCreateFolder(parentId: String, name: String): String =
        guangyaApi.createDir(guangyaToken(), parentId, name)

    suspend fun guangyaRename(isDir: Boolean, id: String, name: String) {
        // 光鸭重命名是文件/文件夹统一接口，不区分 isDir
        guangyaApi.renameFile(guangyaToken(), id, name)
    }

    suspend fun guangyaDelete(isDir: Boolean, id: String) {
        guangyaApi.deleteFiles(guangyaToken(), listOf(id))
    }

    suspend fun guangyaShare(isDir: Boolean, id: String, fname: String): ShareInfo =
        guangyaApi.createShare(guangyaToken(), listOf(id), fname.ifBlank { "分享" }, null, null)

    // ---------- 115 网盘：个人盘（Cookie 登录，无令牌续期） ----------

    suspend fun pan115DriveList(dirFid: String): List<ShareFile> {
        val cookie = credentialOf(Platform.PAN115)
        val cid = dirFid.ifBlank { Pan115Constants.ROOT_CID }
        val all = mutableListOf<ShareFile>()
        var offset = 0
        while (true) {
            val page = pan115Api.listFiles(cid, cookie, offset = offset, limit = Pan115Constants.PAGE_LIMIT)
            all += page.files
            offset += page.files.size
            val done = page.files.isEmpty() ||
                page.files.size < Pan115Constants.PAGE_LIMIT ||
                (page.total > 0 && all.size >= page.total)
            if (done) break
        }
        return all
    }

    suspend fun pan115DriveDownload(fid: String, fname: String, fidToken: String): Long {
        val cookie = credentialOf(Platform.PAN115)
        // 个人盘直链靠 pickcode（列表项 fidToken）走电脑端加密接口，缺它取不到链
        val file = ShareFile(fid = fid, fname = fname, fsize = 0L, isdir = false, pdirFid = "", fidToken = fidToken)
        val link = pan115Api.getDownloadLink(file, cookie)
            ?: throw IllegalStateException("115 未返回下载地址")
        return downloadService.enqueue(
            url = link.downloadUrl,
            fileName = link.filename.ifBlank { fname },
            headers = downloadHeaders(Platform.PAN115, link, cookie),
            size = link.size,
            platform = "pan115",
            resumeKey = "src-pan115-$fid-${link.size}"
        )
    }

    suspend fun pan115CreateFolder(parentId: String, name: String): String {
        val cookie = credentialOf(Platform.PAN115)
        return pan115Api.createDir(parentId.ifBlank { Pan115Constants.ROOT_CID }, name, cookie)
            ?: throw IllegalStateException("创建 115 文件夹失败")
    }

    suspend fun pan115Rename(isDir: Boolean, id: String, name: String) {
        // 115 重命名是文件/文件夹统一接口（fid 对文件是 file_id、对文件夹是 cate_id，均为列表 id 原值）
        pan115Api.rename(id, name, credentialOf(Platform.PAN115))
    }

    suspend fun pan115Delete(id: String, pdirFid: String) {
        val cookie = credentialOf(Platform.PAN115)
        pan115Api.delete(listOf(id), pdirFid.ifBlank { Pan115Constants.ROOT_CID }, cookie)
    }

    /** 115 建分享：网页版暂不暴露有效期选项，固定长期（share_duration=-1，与上游 UI 的默认首档一致） */
    suspend fun pan115Share(id: String): ShareInfo {
        val cookie = credentialOf(Platform.PAN115)
        return pan115Api.createShare(listOf(id), "-1", cookie)
    }

    // ---------- 解析 ----------

    private fun credentialOf(p: Platform): String {
        val acc = accounts.get(p.id) ?: throw IllegalStateException("请先登录${p.displayName}")
        return when (p) {
            Platform.QUARK, Platform.UC, Platform.BAIDU, Platform.C139, Platform.LANZOU, Platform.PAN115 -> {
                val c = acc.cookie
                if (c.isBlank()) throw IllegalStateException("请先登录${p.displayName}")
                // 夸克/UC 的 __puus 自动刷新由调用方经 freshCookieAsync 处理
                c
            }
            Platform.PAN123 -> {
                if (acc.token.isBlank()) throw IllegalStateException("请先登录${p.displayName}")
                ""
            }
            Platform.XUNLEI -> {
                if (acc.xunleiAccessToken.isBlank()) throw IllegalStateException("请先登录${p.displayName}")
                ""
            }
            // 优享/光鸭的仓库 createSession 收的 cookie 参数即 appToken / access token；
            // 这里给存储原值，静默重登/续期在 shareCred 与各 drive 方法的 ensure 逻辑里做
            Platform.ILANZOU, Platform.GUANGYA -> {
                if (acc.token.isBlank()) throw IllegalStateException("请先登录${p.displayName}")
                acc.token
            }
        }
    }

    suspend fun freshCookieAsync(p: Platform): String {
        val acc = accounts.get(p.id) ?: throw IllegalStateException("请先登录${p.displayName}")
        if (acc.cookie.isBlank()) throw IllegalStateException("请先登录${p.displayName}")
        val need = System.currentTimeMillis() - acc.updatedAt > QuarkConstants.PUUS_REFRESH_INTERVAL_MS
        if (!need) return acc.cookie
        val refreshed = when (p) {
            Platform.QUARK -> runCatching { quarkApi.refreshSession(acc.cookie) }.getOrNull()
            Platform.UC -> runCatching { ucApi.refreshSession(acc.cookie) }.getOrNull()
            else -> null
        }
        return if (!refreshed.isNullOrBlank()) {
            accounts.update(p.id) { it.copy(cookie = refreshed) }
            refreshed
        } else acc.cookie
    }

    /**
     * 夸克/UC 的凭证：已登录返回新鲜 Cookie；未登录返回空串走游客链路
     * （解析/列表免登录，下载小文件免登录，大文件再提示登录）。
     */
    suspend fun cookieOrEmptyAsync(p: Platform): String {
        val acc = accounts.get(p.id)
        return if (acc == null || acc.cookie.isBlank()) "" else freshCookieAsync(p)
    }

    /**
     * 分享解析/列表/取链用的凭证（parse/listFiles/enqueueFiles 共用）：
     * - 夸克/UC/蓝奏/115：分享支持匿名，未登录给空串走游客/匿名链路；
     * - 优享：分享匿名可用，登录后给有效 appToken（取链带 userId、转存可用；缺失时适配层静默重登）；
     * - 光鸭：分享匿名可用，登录后给有效 access token（受限分享可走转存下载；临期自动续期）；
     * - 其余平台（百度/139/123/迅雷）：必须登录，走 credentialOf。
     */
    private suspend fun shareCred(p: Platform): String = when (p) {
        Platform.QUARK, Platform.UC, Platform.LANZOU, Platform.PAN115 -> cookieOrEmptyAsync(p)
        Platform.ILANZOU -> ilanzouAccounts.ensureAppToken() ?: ""
        Platform.GUANGYA -> guangyaEnsureAccessToken() ?: ""
        else -> credentialOf(p)
    }

    suspend fun parse(link: String, pwd: String?): ParseResult {
        // 迅雷中文口令：不是链接、且长得像口令时，先换成分享链接（免登录，口令自带提取码）
        val effectiveLink = if (ShareLinkParser.parse(link) == null && XunleiKouling.looksLikeKouling(link)) {
            xunleiApi.parseKouling(XunleiKouling.normalize(link))
        } else link
        val parsed = ShareLinkParser.parse(effectiveLink) ?: throw IllegalArgumentException("无法识别分享链接")
        val platform = Platform.fromSharePlatform(parsed.platform)
        val cred = shareCred(platform)
        val repo = repos[platform]!!
        val sess = repo.createSession(effectiveLink, pwd?.takeIf { it.isNotBlank() }, cred).getOrThrow()
        val id = UUID.randomUUID().toString()
        sessions[id] = ResolveSession(id, platform, sess)
        // 记入解析历史（带分享主名称，历史页/收藏区一眼能认出是什么）
        val entry = history.record(
            effectiveLink, pwd?.takeIf { it.isNotBlank() } ?: "",
            platform.id, platform.displayName, sess.title
        )
        return ParseResult(id, platform, sess.title, entry.id, entry.favorite)
    }

    // ---------- 解析历史 / 收藏 ----------

    fun historyList(): List<HistoryEntry> = history.list()

    fun historySetFavorite(id: String, fav: Boolean): HistoryEntry =
        history.setFavorite(id, fav) ?: throw IllegalArgumentException("历史记录不存在")

    fun historyDelete(id: String) = history.delete(id)

    fun historyClear() = history.clearNonFavorites()

    suspend fun listFiles(sessionId: String, dirFid: String): List<ShareFile> {
        val s = sessions[sessionId] ?: throw IllegalStateException("解析会话已过期，请重新解析")
        val cred = shareCred(s.platform)
        return repos[s.platform]!!.listFiles(s.session, dirFid.ifBlank { "0" }, cred).getOrThrow()
    }

    /**
     * 批量取直链并提交下载。转存+取链是串行的（网盘接口有节奏要求），
     * 每个文件取到直链后立即入队，不阻塞等待全部完成。
     */
    suspend fun resolveAndEnqueue(sessionId: String, files: List<FileItem>): List<Long> =
        enqueueFiles(sessionId, files, batchId = "", batchName = "")

    // ---------- 文件夹下载：递归展开 ----------

    private data class Expansion(
        val id: String,
        val sessionId: String,
        val batchName: String,
        val files: List<FileItem>,
        val dirs: List<String>,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val expansions = ConcurrentHashMap<String, Expansion>()

    /**
     * 把用户勾选的文件/文件夹递归展开成完整文件清单（先预览数量与总大小，
     * 确认后用 expandId 正式提交，避免重复遍历）。
     * - 只选一个文件夹：批次名取文件夹名，文件落到「下载目录/文件夹名/…」
     * - 只选文件：批次名取分享标题，文件仍落在下载目录根下（兼容旧行为）
     * - 混合选择：批次名取分享标题，文件夹在其下按原名建目录
     */
    suspend fun expandSelection(sessionId: String, items: List<FileItem>): ExpandResponse {
        val s = sessions[sessionId] ?: throw IllegalStateException("解析会话已过期，请重新解析")
        require(items.isNotEmpty()) { "请选择要下载的文件" }
        val now = System.currentTimeMillis()
        expansions.entries.removeIf { now - it.value.createdAt > SESSION_TTL_MS }

        val singleFolder = items.size == 1 && items[0].isdir
        val hasFolder = items.any { it.isdir }
        val batchName = sanitizeSegment(
            if (singleFolder) items[0].fname else s.session.title.ifBlank { "批量下载" }
        )
        val rootPrefix = when {
            singleFolder -> batchName
            hasFolder -> batchName
            else -> ""
        }

        val outFiles = mutableListOf<FileItem>()
        val outDirs = mutableListOf<String>()
        var truncated = false

        suspend fun walk(dirFid: String, relDir: String, depth: Int) {
            if (depth > 32) { truncated = true; return }
            val children = listFiles(sessionId, dirFid)
            for (c in children) {
                if (c.isdir) {
                    val childRel = joinRel(relDir, sanitizeSegment(c.fname))
                    outDirs.add(childRel)
                    walk(c.fid, childRel, depth + 1)
                    if (outFiles.size > MAX_FOLDER_FILES) return
                } else {
                    if (outFiles.size >= MAX_FOLDER_FILES) { truncated = true; return }
                    outFiles.add(
                        FileItem(
                            fid = c.fid, fname = c.fname, fsize = c.fsize, isdir = false,
                            pdirFid = c.pdirFid, fidToken = c.fidToken,
                            modifyTime = c.modifyTime, relPath = relDir
                        )
                    )
                }
            }
        }

        for (item in items) {
            if (item.isdir) {
                val base = when {
                    singleFolder -> rootPrefix
                    else -> joinRel(rootPrefix, sanitizeSegment(item.fname))
                }
                if (base.isNotBlank()) outDirs.add(base)
                walk(item.fid, base, 0)
            } else {
                if (outFiles.size >= MAX_FOLDER_FILES) { truncated = true; break }
                outFiles.add(item.copy(relPath = rootPrefix))
            }
            if (outFiles.size > MAX_FOLDER_FILES) { truncated = true; break }
        }

        require(outFiles.isNotEmpty()) { "所选文件夹里没有可下载的文件" }
        val id = UUID.randomUUID().toString()
        expansions[id] = Expansion(id, sessionId, batchName, outFiles, outDirs.distinct())
        return ExpandResponse(
            expandId = id,
            batchName = batchName,
            fileCount = outFiles.size,
            totalSize = outFiles.sumOf { it.fsize },
            truncated = truncated
        )
    }

    /** 用展开结果正式提交下载（预建目录含空目录，再逐个取链入队）。 */
    suspend fun resolveAndEnqueueExpanded(expandId: String): List<Long> {
        val exp = expansions.remove(expandId)
            ?: throw IllegalStateException("展开结果已过期，请重新选择")
        val base = downloadService.resolvedDownloadsDir()
        exp.dirs.forEach { d -> runCatching { java.io.File(base, d).mkdirs() } }
        return enqueueFiles(exp.sessionId, exp.files, batchId = exp.id, batchName = exp.batchName)
    }

    private suspend fun enqueueFiles(
        sessionId: String,
        files: List<FileItem>,
        batchId: String,
        batchName: String
    ): List<Long> {
        val s = sessions[sessionId] ?: throw IllegalStateException("解析会话已过期，请重新解析")
        val repo = repos[s.platform]!!
        val ids = mutableListOf<Long>()
        for (f in files) {
            if (f.isdir) continue
            val cred = shareCred(s.platform)
            val shareFile = ShareFile(
                fid = f.fid, fname = f.fname, fsize = f.fsize,
                isdir = false, pdirFid = f.pdirFid, fidToken = f.fidToken,
                modifyTime = f.modifyTime
            )
            val link = when {
                // 未登录（夸克/UC）：游客取链，夸克约 50MB 内、UC 实测不限；超限会给出登录提示
                (s.platform == Platform.QUARK || s.platform == Platform.UC) && cred.isBlank() ->
                    repo.getGuestShareDownloadLink(s.session, shareFile).getOrThrow()
                // 夸克登录态：优先免转存直取链（不占本账号空间），个别分享失败后自动回退转存
                s.platform == Platform.QUARK && downloadService.settings.quarkNoSave ->
                    repo.getShareDownloadLinkWithoutSave(s.session, shareFile, cred)
                        .getOrElse { repo.getShareDownloadLink(s.session, shareFile, cred).getOrThrow() }
                else -> repo.getShareDownloadLink(s.session, shareFile, cred).getOrThrow()
            }
            val headers = downloadHeaders(s.platform, link, cred)
            val id = downloadService.enqueue(
                url = link.downloadUrl,
                fileName = link.filename.ifBlank { f.fname },
                headers = headers,
                size = link.size,
                platform = s.platform.id,
                resumeKey = "src-${s.platform.id}-${f.fid}-${link.size}",
                batchId = batchId,
                batchName = batchName,
                relPath = f.relPath,
                onComplete = {
                    // 夸克：下载完成后清理临时转存目录
                    val dirFid = link.cleanupDirFid
                    if (dirFid != null) {
                        val c = runCatching {
                            if (s.platform == Platform.QUARK || s.platform == Platform.UC) freshCookieAsync(s.platform)
                            else credentialOf(s.platform)
                        }.getOrNull()
                        if (!c.isNullOrBlank()) {
                            runCatching { repo.cleanupTempDir(dirFid, c) }
                        }
                    }
                }
            )
            ids.add(id)
        }
        return ids
    }

    private fun joinRel(a: String, b: String): String = if (a.isBlank()) b else "$a/$b"

    private fun sanitizeSegment(name: String): String {
        var n = name.trim().ifBlank { "download" }
        n = n.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        if (n.length > 100) n = n.take(100)
        return n
    }

    /** 各平台下载请求头（与 Android 版完全一致的配方） */
    private fun downloadHeaders(p: Platform, link: DownloadLink, credential: String): Map<String, String> {
        return when (p) {
            // 迅雷直链自带签名，必须用官方 app UA，浏览器 UA 会触发 CDN 降级
            Platform.XUNLEI -> mapOf("User-Agent" to XunleiConstants.APP_UA)
            Platform.BAIDU -> mapOf(
                "Cookie" to credential,
                "User-Agent" to BaiduConstants.UA_NETDISK
            )
            Platform.C139 -> mapOf("User-Agent" to C139Constants.PC_UA)
            // 123 直链为 CDN 签名地址，必须带 Referer
            Platform.PAN123 -> mapOf(
                "User-Agent" to Pan123Constants.WEB_UA,
                "Referer" to Pan123Constants.DOWNLOAD_REFERER
            )
            // UC：OSS 直链按 Referer 档位限速，补官方 Referer/Origin 满速
            Platform.UC -> if (link.guestCookie.isNotBlank()) mapOf(
                // 游客链路：只有服务端下发的 __pugs，UA/Sec-Ch-Ua 用 uc-cloud-drive 客户端同款头
                "Cookie" to link.guestCookie,
                "User-Agent" to UCConstants.GUEST_UA,
                "Sec-Ch-Ua" to UCConstants.GUEST_SEC_CH_UA,
                "Referer" to UCConstants.DOWNLOAD_REFERER,
                "Origin" to UCConstants.WEB_ORIGIN
            ) else mapOf(
                "Cookie" to credential,
                "User-Agent" to UCConstants.USER_AGENT,
                "Referer" to UCConstants.DOWNLOAD_REFERER,
                "Origin" to UCConstants.WEB_ORIGIN
            )
            // 蓝奏云：下载节点要求 Referer 为节点 origin，并注入 down_ip=1 Cookie（上游协议 §A4）
            Platform.LANZOU -> {
                val origin = runCatching { java.net.URI(link.downloadUrl) }.getOrNull()
                    ?.let { "${it.scheme}://${it.host}" } ?: "https://pan.lanzoui.com"
                mapOf(
                    "User-Agent" to LanzouConstants.WEB_UA,
                    "Referer" to "$origin/",
                    "Cookie" to LanzouConstants.DOWN_IP_COOKIE
                )
            }
            // 115：CDN 同时校验登录 Cookie 与取链响应下发的 900 秒 CDN Cookie，
            // 缺任一直接 403 no cookie value（上游文档 §4.3），因此两份 Cookie 拼接后一起发；
            // UA 必须是客户端串（与取链一致），浏览器 UA 会被判「网页端」拒发大文件直链
            Platform.PAN115 -> mapOf(
                "Cookie" to Pan115Constants.mergeCookies(credential, link.guestCookie),
                "User-Agent" to Pan115Constants.CLIENT_UA,
                "Referer" to Pan115Constants.DOWNLOAD_REFERER
            )
            // 光鸭直链为签名 CDN 地址，下载带 UA + Referer，且不携带 Authorization/Cookie（上游文档 §5.6/§7.1）
            Platform.GUANGYA -> mapOf(
                "User-Agent" to GuangYaConstants.WEB_UA,
                "Referer" to GuangYaConstants.DOWNLOAD_REFERER
            )
            // 蓝奏优享直链为 CDN 地址，下载带 UA + Referer + Origin，不带 Cookie（上游文档 §4.1）
            Platform.ILANZOU -> mapOf(
                "User-Agent" to ILanzouConstants.WEB_UA,
                "Referer" to ILanzouConstants.DOWNLOAD_REFERER,
                "Origin" to ILanzouConstants.WEB_SITE
            )
            // 夸克：防盗链固定 Referer
            Platform.QUARK -> if (link.guestCookie.isNotBlank()) mapOf(
                // 游客链路：缺 __pugs 则 CDN 直接 412
                "Cookie" to link.guestCookie,
                "User-Agent" to QuarkConstants.API_USER_AGENT,
                "Referer" to QuarkConstants.DOWNLOAD_REFERER
            ) else mapOf(
                "Cookie" to credential,
                "User-Agent" to QuarkConstants.API_USER_AGENT,
                "Referer" to QuarkConstants.DOWNLOAD_REFERER
            )
        }
    }

    data class ParseResult(
        val sessionId: String,
        val platform: Platform,
        val title: String,
        val historyId: String = "",
        val favorite: Boolean = false
    )
}
