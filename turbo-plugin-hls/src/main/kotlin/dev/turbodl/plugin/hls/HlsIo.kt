/*
 * TurboDL — 多线程下载引擎
 *
 * 本文件的安全设计参考上游 YunX (https://github.com/CYQawa/YunX) 的有界读取（AGPL-3.0），
 * 并按本引擎的插件契约做了适配。
 */

package dev.turbodl.plugin.hls

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * HLS 资源的有界读取与大小校验。
 *
 * ## 为什么必须有上限
 *
 * 播放列表、密钥、分片都是**远端可控**的内容，且下载链路可能经过不受信任的中间节点。
 * 若不设上限：
 *  - 一个被篡改的清单可以声明超大 `Content-Length`，或干脆持续吐字节，
 *    最终把整个响应读进内存 → **OOM 崩溃**
 *  - `EXT-X-BYTERANGE` 声明的长度若被放大，会让我们为一个小分片分配巨大缓冲
 *
 * 因此所有"整块读入内存"的路径都必须带上限；分片则改为**流式落盘**（见 HlsBackend）。
 */
internal object HlsIo {

    /** 清单/密钥文本的读取上限（8MB）：正常清单只有几十 KB，密钥 16 字节。 */
    const val MAX_TEXT_BYTES: Long = 8L * 1024 * 1024

    /**
     * 读取 [input] 直到结束，累计超过 [limit] 字节即抛错。
     *
     * 抛错而非截断：截断会产出一个**看似成功但内容残缺**的清单，
     * 后续按它下载会得到损坏的媒体文件 —— 静默的错误比显式失败危险得多。
     */
    fun readBounded(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) {
                throw IllegalStateException("response exceeds byte limit")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * 分片落盘时的单分片上限校验。
     *
     * 分片是流式写入的，不存在 OOM 风险，但仍需上限来防止
     * 「一个异常响应把磁盘写满」。正常分片为 1~10MB，[MAX_SEGMENT_BYTES] 留足余量。
     */
    const val MAX_SEGMENT_BYTES: Long = 512L * 1024 * 1024

    /** 累计已写字节，超过上限即抛错。返回新的累计值。 */
    fun checkedTotal(current: Long, added: Long, limit: Long = MAX_SEGMENT_BYTES): Long {
        val next = current + added
        if (next > limit) {
            throw IllegalStateException("segment exceeds byte limit")
        }
        return next
    }
}
