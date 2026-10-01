package com.yunget.web.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 夸克 / UC 扫码登录。
 *
 * 流程（逆向自官方 Web 扫码登录）：
 * 1. GET uop.quark.cn/cas/ajax/getTokenForQrcodeLogin → qrToken
 * 2. 用 qrToken 拼出二维码 URL（su.quark.cn/4_eMHBJ），用户用夸克/UC App 扫码确认
 * 3. 轮询 uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken → service_ticket
 * 4. GET pan.quark.cn/account/info?st=<ticket>&lw=scan（UC 为 drive.uc.cn）
 *    从 Set-Cookie 提取 __pus / __puus 等登录 Cookie
 *
 * 注意：扫码接口为非官方公开 API，若官方改版可能失效，届时仍可用手动填入 Cookie 登录。
 */
object QrLoginService {

    private const val TOKEN_URL = "https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin"
    private const val POLL_URL = "https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken"
    private const val QR_BASE = "https://su.quark.cn/4_eMHBJ"
    private const val CLIENT_ID = "532"
    private const val SESSION_TTL_MS = 5 * 60 * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private data class QrSession(
        val sessionId: String,
        val platform: String,
        val qrToken: String,
        val createdAt: Long,
    )

    private val sessions = ConcurrentHashMap<String, QrSession>()

    /** 扫码会话有效平台 */
    fun supported(platform: String): Boolean = platform == "quark" || platform == "uc"

    private fun exchangeUrl(platform: String): String =
        if (platform == "uc") "https://drive.uc.cn/account/info"
        else "https://pan.quark.cn/account/info"

    private fun referer(platform: String): String =
        if (platform == "uc") "https://drive.uc.cn/" else "https://pan.quark.cn/"

    private fun get(url: String, referer: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Referer", referer)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IllegalStateException("空响应")
        }
    }

    /** 第 1 步：获取扫码 token，返回 (sessionId, qrUrl) */
    suspend fun start(platform: String): Pair<String, String> = withContext(Dispatchers.IO) {
        if (!supported(platform)) throw IllegalArgumentException("该平台暂不支持扫码登录")
        val requestId = UUID.randomUUID().toString()
        val url = "$TOKEN_URL?client_id=$CLIENT_ID&v=1.2&request_id=$requestId"
        val body = get(url, referer(platform))
        val root = json.parseToJsonElement(body).jsonObject
        val status = root["status"]?.jsonPrimitive?.intOrNull
        if (status != 2000000) {
            throw IllegalStateException("获取二维码失败：${root["message"]?.jsonPrimitive?.content ?: "status=$status"}")
        }
        val token = root["data"]?.jsonObject
            ?.get("members")?.jsonObject
            ?.get("token")?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("响应中缺少 token")
        val qrUrl = buildQrUrl(token)
        val sessionId = UUID.randomUUID().toString()
        sessions[sessionId] = QrSession(sessionId, platform, token, System.currentTimeMillis())
        sessionId to qrUrl
    }

    private fun buildQrUrl(token: String): String {
        val biz = URLEncoder.encode("S:custom|OPT:SAREA@0|OPT:IMMERSIVE@1|OPT:BACK_BTN_STYLE@0", "UTF-8")
        val t = URLEncoder.encode(token, "UTF-8")
        return "$QR_BASE?token=$t&client_id=$CLIENT_ID&ssb=weblogin&uc_param_str=&uc_biz_str=$biz"
    }

    data class PollResult(val status: String, val cookie: String? = null, val message: String? = null)

    /**
     * 第 2 步：轮询扫码状态。
     * @return status: waiting（等待扫码）/ success（成功，cookie 非空）/
     *         expired（二维码过期）/ failed（失败，message 说明原因）
     */
    suspend fun poll(sessionId: String): PollResult = withContext(Dispatchers.IO) {
        val s = sessions[sessionId]
            ?: return@withContext PollResult("expired", message = "二维码已过期，请重新获取")
        if (System.currentTimeMillis() - s.createdAt > SESSION_TTL_MS) {
            sessions.remove(sessionId)
            return@withContext PollResult("expired", message = "二维码已过期，请重新获取")
        }
        val requestId = UUID.randomUUID().toString()
        val url = "$POLL_URL?client_id=$CLIENT_ID&v=1.2&token=${URLEncoder.encode(s.qrToken, "UTF-8")}&request_id=$requestId"
        val body = runCatching { get(url, referer(s.platform)) }.getOrElse {
            return@withContext PollResult("waiting")
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@withContext PollResult("waiting")
        val status = root["status"]?.jsonPrimitive?.intOrNull
        val message = root["message"]?.jsonPrimitive?.content ?: ""

        val ticket = root["data"]?.jsonObject
            ?.get("members")?.jsonObject
            ?.get("service_ticket")?.jsonPrimitive?.content
        if (status == 2000000 && message == "ok" && !ticket.isNullOrBlank()) {
            sessions.remove(sessionId)
            return@withContext try {
                val cookie = exchangeForCookie(s.platform, ticket)
                PollResult("success", cookie = cookie)
            } catch (e: Exception) {
                PollResult("failed", message = "兑换登录态失败：${e.message}")
            }
        }
        if (status in listOf(50004002, 50004003, 50004004) ||
            message.contains("expir", true) || message.contains("fail", true) ||
            message.contains("invalid", true)
        ) {
            sessions.remove(sessionId)
            return@withContext PollResult("expired", message = "二维码已失效，请重新获取")
        }
        // 50004001 等均为等待中
        PollResult("waiting")
    }

    /** 第 3 步：用 service_ticket 换取登录 Cookie */
    private fun exchangeForCookie(platform: String, serviceTicket: String): String {
        val url = "${exchangeUrl(platform)}?st=${URLEncoder.encode(serviceTicket, "UTF-8")}&lw=scan"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Referer", referer(platform))
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val pairs = resp.headers("Set-Cookie").mapNotNull { sc ->
                val pair = sc.substringBefore(';').trim()
                pair.takeIf { it.contains('=') && !it.startsWith("=") }
            }
            if (pairs.isEmpty()) throw IllegalStateException("未收到登录 Cookie")
            return pairs.joinToString("; ")
        }
    }

    fun cancel(sessionId: String) {
        sessions.remove(sessionId)
    }
}
