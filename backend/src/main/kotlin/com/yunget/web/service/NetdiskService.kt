package com.yunget.web.service

import com.yunget.app.data.network.BaiduApi
import com.yunget.app.data.network.BaiduConstants
import com.yunget.app.data.network.C139Api
import com.yunget.app.data.network.C139Constants
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
import com.yunget.app.data.network.model.DownloadLink
import com.yunget.app.data.network.model.ShareFile
import com.yunget.app.data.network.model.ShareSession
import com.yunget.app.data.repository.BaiduResolveRepository
import com.yunget.app.data.repository.C139ResolveRepository
import com.yunget.app.data.repository.Pan123ResolveRepository
import com.yunget.app.data.repository.QuarkResolveRepository
import com.yunget.app.data.repository.ShareResolveRepository
import com.yunget.app.data.repository.UCResolveRepository
import com.yunget.app.data.repository.XunleiResolveRepository
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
    C139("c139", "139 云盘（和彩云）", "cookie");

    companion object {
        fun fromId(id: String): Platform? = values().find { it.id == id }
        fun fromSharePlatform(p: SharePlatform): Platform = when (p) {
            SharePlatform.QUARK -> QUARK
            SharePlatform.UC -> UC
            SharePlatform.XUNLEI -> XUNLEI
            SharePlatform.BAIDU -> BAIDU
            SharePlatform.PAN123 -> PAN123
            SharePlatform.C139 -> C139
        }
    }
}

private const val SESSION_TTL_MS = 30L * 60 * 1000

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
    private val downloadService: DownloadService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val quarkApi = QuarkApi()
    private val ucApi = UCApi()
    private val xunleiApi = XunleiApi()
    private val baiduApi = BaiduApi()
    private val pan123Api = Pan123Api()
    private val c139Api = C139Api()

    private val repos: Map<Platform, ShareResolveRepository> = mapOf(
        Platform.QUARK to QuarkResolveRepository(quarkApi),
        Platform.UC to UCResolveRepository(ucApi),
        Platform.BAIDU to BaiduResolveRepository(baiduApi),
        Platform.C139 to C139ResolveRepository(c139Api),
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
            Platform.QUARK, Platform.UC, Platform.BAIDU, Platform.C139 -> !acc?.cookie.isNullOrBlank()
            Platform.PAN123 -> !acc?.token.isNullOrBlank()
            Platform.XUNLEI -> !acc?.xunleiAccessToken.isNullOrBlank()
        }
        PlatformInfo(p.id, p.displayName, p.loginType, loggedIn, acc?.nickname ?: "")
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
                step.needSms -> XunleiLoginResponse(needSms = true, message = "需要短信验证")
                step.sessionId.isBlank() ->
                    XunleiLoginResponse(needSms = false, message = step.message.ifBlank { "登录失败，请检查账号密码" })
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

    // ---------- 解析 ----------

    private fun credentialOf(p: Platform): String {
        val acc = accounts.get(p.id) ?: throw IllegalStateException("请先登录${p.displayName}")
        return when (p) {
            Platform.QUARK, Platform.UC, Platform.BAIDU, Platform.C139 -> {
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

    suspend fun parse(link: String, pwd: String?): ParseResult {
        val parsed = ShareLinkParser.parse(link) ?: throw IllegalArgumentException("无法识别分享链接")
        val platform = Platform.fromSharePlatform(parsed.platform)
        val cred = if (platform == Platform.QUARK || platform == Platform.UC) {
            freshCookieAsync(platform)
        } else credentialOf(platform)
        val repo = repos[platform]!!
        val sess = repo.createSession(link, pwd?.takeIf { it.isNotBlank() }, cred).getOrThrow()
        val id = UUID.randomUUID().toString()
        sessions[id] = ResolveSession(id, platform, sess)
        return ParseResult(id, platform, sess.title)
    }

    suspend fun listFiles(sessionId: String, dirFid: String): List<ShareFile> {
        val s = sessions[sessionId] ?: throw IllegalStateException("解析会话已过期，请重新解析")
        val cred = if (s.platform == Platform.QUARK || s.platform == Platform.UC) {
            freshCookieAsync(s.platform)
        } else credentialOf(s.platform)
        return repos[s.platform]!!.listFiles(s.session, dirFid.ifBlank { "0" }, cred).getOrThrow()
    }

    /**
     * 批量取直链并提交下载。转存+取链是串行的（网盘接口有节奏要求），
     * 每个文件取到直链后立即入队，不阻塞等待全部完成。
     */
    suspend fun resolveAndEnqueue(sessionId: String, files: List<FileItem>): List<Long> {
        val s = sessions[sessionId] ?: throw IllegalStateException("解析会话已过期，请重新解析")
        val repo = repos[s.platform]!!
        val ids = mutableListOf<Long>()
        for (f in files) {
            if (f.isdir) continue
            val cred = if (s.platform == Platform.QUARK || s.platform == Platform.UC) {
                freshCookieAsync(s.platform)
            } else credentialOf(s.platform)
            val shareFile = ShareFile(
                fid = f.fid, fname = f.fname, fsize = f.fsize,
                isdir = false, pdirFid = f.pdirFid, fidToken = f.fidToken,
                modifyTime = f.modifyTime
            )
            val link = repo.getShareDownloadLink(s.session, shareFile, cred).getOrThrow()
            val headers = downloadHeaders(s.platform, link, cred)
            val id = downloadService.enqueue(
                url = link.downloadUrl,
                fileName = link.filename.ifBlank { f.fname },
                headers = headers,
                size = link.size,
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
            Platform.UC -> mapOf(
                "Cookie" to credential,
                "User-Agent" to UCConstants.USER_AGENT,
                "Referer" to UCConstants.DOWNLOAD_REFERER,
                "Origin" to UCConstants.WEB_ORIGIN
            )
            // 夸克：防盗链固定 Referer
            Platform.QUARK -> mapOf(
                "Cookie" to credential,
                "User-Agent" to QuarkConstants.API_USER_AGENT,
                "Referer" to QuarkConstants.DOWNLOAD_REFERER
            )
        }
    }

    data class ParseResult(val sessionId: String, val platform: Platform, val title: String)
}
