/*
 * TurboDL — 多线程下载引擎
 */

package dev.turbodl.plugin.hls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HlsRequestPolicy] 的安全契约测试。
 *
 * ## 为什么必须测
 *
 * HLS 的播放列表由一个域名提供，但**分片、密钥、初始化段常放在另一个域名**
 * （CDN / 对象存储）。若把调用方传入的请求头无差别发给每个地址，
 * 就等于把网盘登录凭证（Cookie / Authorization / Referer）交给第三方域名。
 *
 * 这个缺陷**不会报错、不会崩溃**，下载照常成功 —— 只是凭证悄悄泄漏了。
 * 因此必须有测试把「跨域必须剥离敏感头」钉死。
 *
 * 同时覆盖：强制 HTTPS（明文清单可被中间人改写）、重定向逐跳校验、
 * 相对地址解析（播放列表里三种写法都要支持）。
 *
 * 注意：本模块用 kotlin.test，断言的消息参数在**最后**。
 */
class HlsRequestPolicyTest {

    private val originHeaders = mapOf(
        "Cookie" to "session=secret",
        "Authorization" to "Bearer token123",
        "Referer" to "https://pan.example.com/",
        "User-Agent" to "TurboDL-test",
        "Accept" to "*/*",
    )

    private fun effective(target: String, origin: String) =
        HlsRequestPolicy.effectiveHeaders(target, origin, originHeaders)

    // ---------------------------------------------------------------- 同源：原样传递

    @Test
    fun sameOriginKeepsAllHeaders() {
        val out = effective(
            "https://cdn.example.com/seg1.ts",
            "https://cdn.example.com/master.m3u8",
        )
        assertEquals(originHeaders, out, "同源必须原样传递全部请求头")
    }

    @Test
    fun sameOriginIgnoresDefaultPortDifference() {
        val out = effective(
            "https://cdn.example.com:443/seg1.ts",
            "https://cdn.example.com/master.m3u8",
        )
        assertEquals(originHeaders, out, "显式 443 与省略应视为同源")
    }

    // ---------------------------------------------------------------- 跨域：剥离敏感头（核心）

    @Test
    fun crossOriginStripsSensitiveHeaders() {
        val out = effective(
            "https://other-cdn.net/seg1.ts",
            "https://pan.example.com/master.m3u8",
        )
        assertTrue("Cookie" !in out, "Cookie 不得跨域发送")
        assertTrue("Authorization" !in out, "Authorization 不得跨域发送")
        assertTrue("Referer" !in out, "Referer 不得跨域发送（可被 CDN 当作盗链判据）")
        // 非敏感头应保留，避免影响兼容性
        assertEquals("TurboDL-test", out["User-Agent"], "User-Agent 应保留")
        assertEquals("*/*", out["Accept"], "Accept 应保留")
    }

    @Test
    fun differentPortIsCrossOrigin() {
        val out = effective(
            "https://cdn.example.com:8443/seg1.ts",
            "https://cdn.example.com/master.m3u8",
        )
        assertTrue("Cookie" !in out, "端口不同即跨域，必须剥离 Cookie")
    }

    @Test
    fun differentHostIsCrossOrigin() {
        val out = effective(
            "https://cdn.example.com/seg1.ts",
            "https://pan.example.com/master.m3u8",
        )
        assertTrue("Cookie" !in out, "域名不同即跨域")
    }

    @Test
    fun sensitiveHeaderMatchingIsCaseInsensitive() {
        // 调用方可能用任意大小写；匹配必须大小写无关，否则保护被绕过
        val headers = mapOf(
            "cookie" to "a=1",
            "AUTHORIZATION" to "Bearer x",
            "ReFeReR" to "https://pan.example.com/",
            "User-Agent" to "UA",
        )
        val out = HlsRequestPolicy.effectiveHeaders(
            "https://other.net/seg.ts",
            "https://pan.example.com/m.m3u8",
            headers,
        )
        assertEquals(1, out.size, "只应保留 User-Agent")
        assertEquals("UA", out["User-Agent"])
    }

    @Test
    fun unparsableOriginStripsSensitiveHeaders() {
        val out = effective("https://cdn.example.com/seg1.ts", "not-a-url")
        assertTrue("Cookie" !in out, "来源不可解析时必须保守剥离敏感头")
        assertTrue("Authorization" !in out, "来源不可解析时必须保守剥离敏感头")
        assertEquals("TurboDL-test", out["User-Agent"], "User-Agent 应保留")
    }

    // ---------------------------------------------------------------- 强制 HTTPS

    @Test
    fun plainHttpEntryIsRejected() {
        assertNull(
            HlsRequestPolicy.initialUrl("http://example.com/a.m3u8"),
            "明文 http 入口必须拒绝（可被中间人改写清单）",
        )
        assertNotNull(HlsRequestPolicy.initialUrl("https://example.com/a.m3u8"))
    }

    @Test
    fun resolvingToHttpIsRejected() {
        val base = HlsRequestPolicy.initialUrl("https://cdn.example.com/a/b.m3u8")!!
        assertNull(
            HlsRequestPolicy.resolve(base, "http://evil.net/seg.ts"),
            "清单里的分片地址若为 http，必须拒绝（否则可被降级攻击）",
        )
        assertNotNull(HlsRequestPolicy.resolve(base, "https://cdn.example.com/seg.ts"))
    }

    // ---------------------------------------------------------------- 相对地址解析

    @Test
    fun resolvesRelativeSegmentPaths() {
        val base = HlsRequestPolicy.initialUrl("https://cdn.example.com/hls/v1/index.m3u8")!!
        assertEquals(
            "https://cdn.example.com/hls/v1/seg1.ts",
            HlsRequestPolicy.resolve(base, "seg1.ts")?.toString() ?: "",
            "相对路径应基于清单所在目录解析",
        )
        assertEquals(
            "https://cdn.example.com/other/seg2.ts",
            HlsRequestPolicy.resolve(base, "/other/seg2.ts")?.toString() ?: "",
            "根相对路径应基于 host 解析",
        )
        assertEquals(
            "https://other.net/seg3.ts",
            HlsRequestPolicy.resolve(base, "https://other.net/seg3.ts")?.toString() ?: "",
            "绝对 URL 应原样使用",
        )
    }

    // ---------------------------------------------------------------- 重定向

    @Test
    fun redirectTargetResolvesRelativeLocation() {
        val current = HlsRequestPolicy.initialUrl("https://cdn.example.com/a/b.m3u8")!!
        assertEquals(
            "https://cdn.example.com/a/moved.m3u8",
            HlsRequestPolicy.redirectTarget("moved.m3u8", current)?.toString() ?: "",
            "Location 为相对路径时须按当前地址解析",
        )
    }

    @Test
    fun redirectToHttpIsRejected() {
        val current = HlsRequestPolicy.initialUrl("https://cdn.example.com/a.m3u8")!!
        assertNull(
            HlsRequestPolicy.redirectTarget("http://evil.net/a.m3u8", current),
            "重定向到明文 http 必须拒绝（经典的 HTTPS 降级攻击）",
        )
    }

    @Test
    fun missingOrBlankLocationIsRejected() {
        val current = HlsRequestPolicy.initialUrl("https://cdn.example.com/a.m3u8")!!
        assertNull(HlsRequestPolicy.redirectTarget(null, current), "缺少 Location 应拒绝")
        assertNull(HlsRequestPolicy.redirectTarget("  ", current), "空白 Location 应拒绝")
    }

    @Test
    fun redirectAcrossOriginThenHeadersAreFiltered() {
        // 重定向到另一域名后，后续请求不得再带凭证（requestBuilder 每跳重算的依据）
        val target = HlsRequestPolicy.initialUrl("https://other-cdn.net/a.m3u8")!!
        val origin = HlsRequestPolicy.initialUrl("https://pan.example.com/a.m3u8")!!
        val filtered = HlsRequestPolicy.headersFor(target, origin, originHeaders)
        assertTrue("Cookie" !in filtered, "重定向跨域后不得携带 Cookie")
    }

    // ---------------------------------------------------------------- 请求构建

    @Test
    fun newRequestBuilderAppliesPolicyAndDefaultUserAgent() {
        val req = HlsRequestPolicy.newRequestBuilder(
            targetUrl = "https://cdn.example.com/seg.ts",
            originUrl = "https://pan.example.com/m.m3u8",
            headers = mapOf("Cookie" to "s=1"),
        ).build()
        assertNull(req.header("Cookie"), "跨域请求不应带 Cookie")
        assertEquals("TurboDL/0.1", req.header("User-Agent"), "未提供 UA 时应补默认值")
    }

    @Test
    fun newRequestBuilderKeepsCallerUserAgent() {
        val req = HlsRequestPolicy.newRequestBuilder(
            targetUrl = "https://cdn.example.com/seg.ts",
            originUrl = "https://cdn.example.com/m.m3u8",
            headers = mapOf("User-Agent" to "custom-ua"),
        ).build()
        assertEquals("custom-ua", req.header("User-Agent"), "调用方给的 UA 不应被默认值覆盖")
    }

    @Test
    fun newRequestBuilderIsAlwaysGet() {
        val req = HlsRequestPolicy.newRequestBuilder(
            targetUrl = "https://cdn.example.com/seg.ts",
            originUrl = "https://cdn.example.com/m.m3u8",
            headers = emptyMap(),
        ).build()
        assertEquals("GET", req.method, "HLS 资源一律用 GET")
    }
}
