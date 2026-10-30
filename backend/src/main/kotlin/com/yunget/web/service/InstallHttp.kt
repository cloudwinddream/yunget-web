package com.yunget.web.service

import com.yunget.app.data.network.HttpClients
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * 工具「一键安装」专用的 HTTP 客户端选取（v1.0.31）。
 *
 * ffmpeg / yt-dlp / Gopeed 三家安装都从官方源（gyan.dev / evermeet.cx /
 * johnvansickle.com / GitHub）下载，这些源在部分网络直连不通（TLS 握手被掐），
 * 因此安装流量单独支持一个「工具安装代理」（SettingsData.toolProxy），
 * 与业务流量互不影响：
 *
 * - toolProxy 为空 → 原样返回 [HttpClients] 的客户端（行为与之前一致）；
 * - 非空 → 基于原客户端 newBuilder().proxy(...) 换出带代理的实例
 *   （Dispatcher/连接池与原客户端共享，安装是低频操作，不另起一套）。
 *
 * 代理地址解析：`http://` / `https://` → [Proxy.Type.HTTP]；`socks5://` / `socks://`
 * → [Proxy.Type.SOCKS]；不带 scheme 时按 `http://` 补全（如直接填 127.0.0.1:7890）。
 * URL 带 userinfo（user:pass@）时：HTTP 代理经 proxyAuthenticator 以
 * Proxy-Authorization 基本认证应答 407 挑战；SOCKS 的账密认证走 JVM 全局
 * Authenticator、不在此处理（忽略账密，不崩）。解析失败抛友好文案。
 */
object InstallHttp {

    /** 三家安装「下载/元数据」失败文案的统一尾缀，指引用户去填工具安装代理 */
    const val PROXY_HINT = "（直连官方源失败时，可在设置页『工具安装代理』中填写代理后重试）"

    /** 安装包下载用客户端（大 Dispatcher 的下载客户端 + 可选代理） */
    fun downloadClient(toolProxy: String): OkHttpClient =
        withProxy(HttpClients.downloadClient(), toolProxy)

    /** 安装元数据请求用客户端（如查 GitHub Release 的 API 客户端 + 可选代理） */
    fun apiClient(toolProxy: String): OkHttpClient =
        withProxy(HttpClients.apiClient(), toolProxy)

    private fun withProxy(base: OkHttpClient, raw: String): OkHttpClient {
        val value = raw.trim()
        if (value.isEmpty()) return base
        val parsed = parse(value)
        val builder = base.newBuilder().proxy(parsed.proxy)
        if (parsed.proxy.type() == Proxy.Type.HTTP && parsed.user != null) {
            val credential = Credentials.basic(parsed.user, parsed.pass ?: "")
            builder.proxyAuthenticator { _, response ->
                // 已带认证头还 407 说明账密不对，别无限重试
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build()
            }
        }
        return builder.build()
    }

    private data class Parsed(val proxy: Proxy, val user: String?, val pass: String?)

    private fun parse(raw: String): Parsed {
        val withScheme = if (raw.contains("://")) raw else "http://$raw"
        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            throw IllegalStateException("工具安装代理格式不正确：『$raw』（示例：http://127.0.0.1:7890）")
        }
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host
        if (host.isNullOrBlank()) {
            throw IllegalStateException("工具安装代理格式不正确：『$raw』（示例：http://127.0.0.1:7890）")
        }
        val type = when (scheme) {
            "http", "https" -> Proxy.Type.HTTP
            "socks", "socks5" -> Proxy.Type.SOCKS
            else -> throw IllegalStateException(
                "工具安装代理仅支持 http:// / https:// / socks5://：『$raw』"
            )
        }
        val port = if (uri.port > 0) uri.port else when (scheme) {
            "https" -> 443
            "socks", "socks5" -> 1080
            else -> 80
        }
        val userInfo = uri.userInfo
        val user = userInfo?.substringBefore(':')?.takeIf { it.isNotEmpty() }
        val pass = userInfo?.substringAfter(':', "")?.takeIf { userInfo.contains(':') }
        return Parsed(Proxy(type, InetSocketAddress(host, port)), user, pass)
    }
}
