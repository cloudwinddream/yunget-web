/*
 * TurboDL — 多线程下载引擎
 *
 * 本文件的安全策略设计参考上游 YunX (https://github.com/CYQawa/YunX) 的
 * `HlsRequestPolicy`（AGPL-3.0），并按本引擎的插件契约做了适配。
 */

package dev.turbodl.plugin.hls

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * HLS 请求的**来源（origin）与请求头**策略。
 *
 * ## 为什么需要它：跨域凭证泄漏
 *
 * HLS 的播放列表由一个域名提供，但分片、密钥、`#EXT-X-MAP` 初始化段常放在
 * **另一个域名**（CDN、对象存储）。而调用方传给引擎的请求头里可能带着
 * `Cookie` / `Authorization` / `Referer`（网盘直链往往需要它们才能取到播放列表）。
 *
 * 若把这些头**无差别**发给每个分片 URL，就等于把登录凭证交给了第三方域名 ——
 * 一旦分片域名被劫持、或本就是恶意清单（播放列表可控即可诱导），凭证直接泄漏。
 *
 * 因此：**只有与凭证来源同源（scheme + host + port 完全一致）的请求才携带敏感头**，
 * 跨域请求一律剥离。非敏感头（如 `User-Agent`）照常传递，不影响兼容性。
 *
 * ## 为什么强制 HTTPS
 *
 * `http://` 的播放列表可以被任意中间人改写（插入恶意分片地址或密钥地址）。
 * HLS 的行业实践已全面转向 HTTPS，故这里直接拒绝明文地址，
 * 而不是"允许但警告" —— 静默降级成明文才是真正的风险。
 */
internal object HlsRequestPolicy {

    /**
     * 需要按来源隔离的请求头（小写比较）。
     *
     * 这些头都能直接或间接换到账号权限：`Cookie`/`Authorization` 是凭证本身，
     * `Referer`/`Origin` 常被 CDN 用作防盗链判据（泄漏后他人可盗用直链），
     * `Proxy-Authorization` 则可能泄漏代理凭证。
     */
    private val SENSITIVE_HEADERS = setOf(
        "authorization",
        "cookie",
        "origin",
        "proxy-authorization",
        "referer",
    )

    /** 校验入口地址：必须是合法的 HTTPS URL，否则拒绝（返回 null）。 */
    fun initialUrl(url: String): HttpUrl? =
        url.toHttpUrlOrNull()?.takeIf { it.isHttps }

    /**
     * 依据 [base] 解析相对/绝对地址，并校验为 HTTPS。
     *
     * 播放列表中的分片地址可能是相对的（`seg1.ts`）、根相对的（`/a/b.ts`）
     * 或绝对 URL，三种都要按 RFC 3986 相对解析规则处理 —— 这正是
     * [HttpUrl.resolve] 的行为。
     */
    fun resolve(base: HttpUrl, candidate: String): HttpUrl? =
        base.resolve(candidate)?.takeIf { it.isHttps }

    /**
     * 计算出应发给 [target] 的请求头。
     *
     * 同源 → 原样传递；跨域 → 剥离 [SENSITIVE_HEADERS]。
     */
    fun headersFor(
        target: HttpUrl,
        credentialOrigin: HttpUrl,
        headers: Map<String, String>,
    ): Map<String, String> {
        if (sameOrigin(target, credentialOrigin)) return headers
        return headers.filterKeys { it.lowercase() !in SENSITIVE_HEADERS }
    }

    /** scheme + host + port 三者全等才算同源（端口缺省时按 scheme 归一化）。 */
    fun sameOrigin(left: HttpUrl, right: HttpUrl): Boolean =
        left.scheme == right.scheme &&
            left.host == right.host &&
            left.port == right.port

    /**
     * 从重定向响应的 `Location` 解析目标地址。
     *
     * `Location` 可能是相对路径，故需按**当前地址**解析（而非原始地址）。
     * 非 HTTPS 的目标一律拒绝。
     */
    fun redirectTarget(location: String?, current: HttpUrl): HttpUrl? {
        if (location.isNullOrBlank()) return null
        return current.resolve(location)?.takeIf { it.isHttps }
    }

    /**
     * 依据来源规则筛选出实际要发送的请求头。
     *
     * [originUrl] 为调用方提供的入口地址（凭证的"家"）；若它本身不可解析，
     * 则保守地**只发送非敏感头** —— 宁可与个别 CDN 不兼容，也不泄漏凭证。
     */
    fun effectiveHeaders(
        targetUrl: String,
        originUrl: String,
        headers: Map<String, String>,
    ): Map<String, String> {
        val target = initialUrl(targetUrl)
        val origin = initialUrl(originUrl)
        if (target == null || origin == null) {
            return headers.filterKeys { it.lowercase() !in SENSITIVE_HEADERS }
        }
        return headersFor(target, origin, headers)
    }

    /**
     * 构造一个已按来源规则筛选过请求头的 OkHttp 请求构建器。
     *
     * 之所以把构建逻辑放在本文件而非 [HlsBackend]：策略与用法集中在一处，
     * 调用方只需一行，也便于单独测试。
     */
    fun newRequestBuilder(
        targetUrl: String,
        originUrl: String,
        headers: Map<String, String>,
    ): okhttp3.Request.Builder {
        val effective = effectiveHeaders(targetUrl, originUrl, headers)
        return okhttp3.Request.Builder().url(targetUrl).apply {
            effective.forEach { (name, value) -> header(name, value) }
            // 与核心保持一致：未显式提供 UA 时补默认值（不覆盖调用方给的值）
            if (effective.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                header("User-Agent", "TurboDL/0.1")
            }
            get()
        }
    }
}
