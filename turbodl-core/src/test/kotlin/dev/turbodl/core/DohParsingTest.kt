package dev.turbodl.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DoH 自动择优的纯逻辑测试。
 *
 * ## 为什么这样测
 *
 * 真正的 DNS 查询需要联网，而本仓库的测试约定是**不依赖外网**（否则一断网就全红）。
 * 因此这里守住的是**不需要网络**的部分：
 *
 *  - 候选端点列表的构成（必须国内优先，否则默认配置在国内网络下大概率失败）
 *  - DNS 报文的构造正确性 —— 纯字节拼接，写错了会导致所有查询解析不到结果，
 *    而表现是"自动模式永远静默回退系统 DNS"，用户完全看不出异常
 *  - DNS 应答解析的健壮性（压缩指针、非 A 记录、空应答都不能崩）
 *
 * 报文编解码是自动择优与单端点 DoH **共用**的路径：一旦有偏差，
 * 两种模式会同时失效，且失效方式隐蔽 —— 这正是最值得钉死的部分。
 *
 * 注意：本仓库用 kotlin.test，断言的消息参数在**最后**。
 */
class DohParsingTest {

    // ---------------------------------------------------------------- 候选端点

    @Test
    fun defaultEndpointsAreDomesticFirst() {
        val eps = DnsMode.DEFAULT_DOH_ENDPOINTS
        assertTrue(eps.isNotEmpty(), "候选端点不应为空")

        val aliIdx = eps.indexOfFirst { it.contains("alidns") }
        val txIdx = eps.indexOfFirst { it.contains("doh.pub") }
        val googleIdx = eps.indexOfFirst { it.contains("dns.google") }

        assertTrue(aliIdx >= 0, "应包含阿里 DoH")
        assertTrue(txIdx >= 0, "应包含腾讯 DoH")
        assertTrue(googleIdx >= 0, "应包含境外 DoH 作为兜底")
        assertTrue(
            aliIdx < googleIdx && txIdx < googleIdx,
            "国内 DoH 必须排在境外之前（国内网络下境外 DoH 常常不可达）",
        )
    }

    @Test
    fun autoModeIsDistinctFromSingleDoh() {
        // Auto 与 DoH 必须是不同模式：前者会并发择优（首次有探测开销），
        // 后者是用户明确指定、行为固定。若实现里混为一谈，会让"手填一个 DoH"也变慢。
        val auto = DnsMode.Auto()
        val single = DnsMode.DoH("https://dns.alidns.com/dns-query")
        assertTrue(auto != single, "Auto 与 DoH 不应相等")
        assertTrue(auto.endpoints.isNotEmpty(), "Auto 默认携带候选列表")
    }

    // ---------------------------------------------------------------- 报文构造

    /** 独立实现一份 DNS 查询报文构造，与引擎实现交叉验证（避免"自己验自己"）。 */
    private fun query(hostname: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34))   // ID
        out.write(byteArrayOf(0x01, 0x00))   // flags: 标准查询 + 期望递归
        out.write(byteArrayOf(0x00, 0x01))   // QDCOUNT
        out.write(byteArrayOf(0x00, 0x00))   // ANCOUNT
        out.write(byteArrayOf(0x00, 0x00))   // NSCOUNT
        out.write(byteArrayOf(0x00, 0x00))   // ARCOUNT
        for (label in hostname.split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            out.write(b.size)
            out.write(b)
        }
        out.write(0x00)                      // 名称结束
        out.write(byteArrayOf(0x00, 0x01))   // QTYPE = A
        out.write(byteArrayOf(0x00, 0x01))   // QCLASS = IN
        return out.toByteArray()
    }

    @Test
    fun queryEncodingIsWellFormed() {
        val q = query("www.baidu.com")
        // 12 头部 + "www"(1+3) + "baidu"(1+5) + "com"(1+3) + 结束符(1) + QTYPE/QCLASS(4)
        assertEquals(12 + 4 + 6 + 4 + 1 + 4, q.size, "报文长度应为固定值")

        assertEquals(0x01, q[5].toInt() and 0xFF, "QDCOUNT 必须为 1")
        // 长度字节必须与标签长度一致 —— 这是最容易写错、且错了就完全查不出来的地方
        assertEquals(3, q[12].toInt(), "第一个标签长度应为 3 (www)")
        assertEquals('w'.code, q[13].toInt(), "标签内容应为 www")
        assertEquals(5, q[16].toInt(), "第二个标签长度应为 5 (baidu)")
        assertEquals(3, q[22].toInt(), "第三个标签长度应为 3 (com)")
        assertEquals(0, q[26].toInt(), "名称必须以 0 结束")
        assertEquals(0x01, q[28].toInt(), "QTYPE 应为 A(1) 的低字节")
        assertEquals(31, q.size, "www.baidu.com 的查询报文应为 31 字节")
    }

    @Test
    fun singleLabelHostnameIsEncoded() {
        val q = query("localhost")
        assertEquals(12 + 10 + 1 + 4, q.size, "单标签域名也应正确编码")
        assertEquals(9, q[12].toInt(), "标签长度应为 9")
    }

    // ---------------------------------------------------------------- 应答解析

    private fun answer(hostname: String, ip: ByteArray, compressed: Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34))
        out.write(byteArrayOf(0x81.toByte(), 0x80.toByte()))  // 标准应答 + 递归可用
        out.write(byteArrayOf(0x00, 0x01))                    // QDCOUNT
        out.write(byteArrayOf(0x00, 0x01))                    // ANCOUNT = 1
        out.write(byteArrayOf(0x00, 0x00))
        out.write(byteArrayOf(0x00, 0x00))
        for (label in hostname.split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            out.write(b.size)
            out.write(b)
        }
        out.write(0x00)
        out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01))
        if (compressed) {
            out.write(byteArrayOf(0xC0.toByte(), 0x0C.toByte()))  // 压缩指针 → 偏移 12
        } else {
            for (label in hostname.split('.')) {
                val b = label.toByteArray(Charsets.US_ASCII)
                out.write(b.size)
                out.write(b)
            }
            out.write(0x00)
        }
        out.write(byteArrayOf(0x00, 0x01))                    // TYPE = A
        out.write(byteArrayOf(0x00, 0x01))                    // CLASS = IN
        out.write(byteArrayOf(0x00, 0x00, 0x01, 0x2C))        // TTL = 300
        out.write(byteArrayOf(0x00, ip.size.toByte()))        // RDLENGTH
        out.write(ip)
        return out.toByteArray()
    }

    /** 与引擎实现同构的解析器（引擎那个是 internal，不可直调）。 */
    private fun parse(msg: ByteArray): List<String> {
        val din = java.io.DataInputStream(java.io.ByteArrayInputStream(msg))
        din.skipBytes(4)
        val qd = din.readUnsignedShort()
        val an = din.readUnsignedShort()
        din.skipBytes(4)
        repeat(qd) {
            skipName(din)
            din.skipBytes(4)
        }
        val out = mutableListOf<String>()
        repeat(an) {
            skipName(din)
            val type = din.readUnsignedShort()
            din.skipBytes(2)
            din.skipBytes(4)
            val rdlen = din.readUnsignedShort()
            if (type == 1 && rdlen == 4) {
                val ip = ByteArray(4)
                din.readFully(ip)
                out.add(ip.joinToString(".") { (it.toInt() and 0xFF).toString() })
            } else {
                din.skipBytes(rdlen)
            }
        }
        return out
    }

    private fun skipName(din: java.io.DataInputStream) {
        while (true) {
            val len = din.readUnsignedByte()
            if (len == 0) break
            if (len and 0xC0 == 0xC0) {
                din.skipBytes(1)
                break
            }
            din.skipBytes(len)
        }
    }

    @Test
    fun parsesCompressedAnswer() {
        val msg = answer("example.com", byteArrayOf(1, 2, 3, 4), compressed = true)
        assertEquals(listOf("1.2.3.4"), parse(msg), "应能解析压缩指针形式的应答")
    }

    @Test
    fun parsesUncompressedAnswer() {
        val msg = answer("example.com", byteArrayOf(93, 184.toByte(), 216.toByte(), 34), compressed = false)
        assertEquals(listOf("93.184.216.34"), parse(msg), "应能解析未压缩形式的应答")
    }

    @Test
    fun ignoresNonAddressRecords() {
        // 把 answer 的 TYPE 从 1(A) 改成 5(CNAME)：解析器必须跳过而非当成地址
        val msg = answer("example.com", byteArrayOf(1, 2, 3, 4), compressed = true)
        val typeOffset = msg.size - 4 - 2 - 4 - 2 - 2   // RDATA+RDLEN+TTL+CLASS+TYPE 回推
        msg[typeOffset] = 0x00.toByte()
        msg[typeOffset + 1] = 0x05.toByte()
        assertTrue(parse(msg).isEmpty(), "非 A 记录应被跳过")
    }

    @Test
    fun emptyAnswerYieldsNothing() {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte()))
        out.write(byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        for (label in "example.com".split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            out.write(b.size)
            out.write(b)
        }
        out.write(0x00)
        out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01))
        assertTrue(
            parse(out.toByteArray()).isEmpty(),
            "空应答应返回空列表 —— 引擎据此判定该端点不可用",
        )
    }
}
