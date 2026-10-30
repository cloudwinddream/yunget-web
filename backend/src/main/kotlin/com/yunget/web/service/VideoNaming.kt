package com.yunget.web.service

import java.text.SimpleDateFormat
import java.util.Date

/**
 * 视频下载命名模板渲染（用户拍板：可配置 + 内置默认 "{title}"）。
 *
 * 作用范围：仅视频平台（含 YouTube）这类「靠标题生成」的文件名；网盘文件保持
 * 盘内原名、GitHub / 磁力命名不动。设置项 `SettingsData.videoNameTemplate`。
 *
 * 变量：{title} 标题、{author} 作者、{id} 作品 ID、{platform} 平台（枚举名，
 * 如 BILIBILI / DOUYIN / YOUTUBE）、{quality} 清晰度标签、{date} 发布日期
 * （yyyy-MM-dd）、{index} 序号（从 1 开始）。变量缺失渲染为空串；未知的
 * {xxx} 占位符原样保留（让写错的变量在文件名里可见、便于用户自查）。
 *
 * 渲染后处理（收拢规则，刻意保持简单可预期）：
 * 1. 连续空白压成单个空格；
 * 2. 同一分隔符（- _ ·）连写压成一个（"--" → "-"）；
 * 3. 两种不同分隔符相邻（如 " - _ "）收拢为先出现者（" - "）；
 * 4. 去掉首尾的分隔符与空白及结尾的点；
 * 5. 走与 DownloadService 同款清洗：非法字符 / \ : * ? " < > | 换 "_"，超 200 字符截断。
 *
 * 回退链：渲染结果为空 → 用 "{title}" 再渲染 → 仍为空 → 用 fallbackName 的去
 * 扩展名部分（即解析器原名）→ 再为空则 "download"。返回值是不带扩展名的基名，
 * 扩展名由调用方按媒体另拼。
 */
object VideoNaming {

    data class Vars(
        val title: String = "",
        val author: String = "",
        val id: String = "",
        val platform: String = "",
        val quality: String = "",
        val date: String = "",
        /** 序号（从 1 开始）；0 或负数表示无序号，渲染为空 */
        val index: Int = 0
    )

    /** 渲染模板为不带扩展名的文件名基名（已清洗）；[fallbackName] 为解析器原始文件名（可带扩展名） */
    fun renderBase(template: String, vars: Vars, fallbackName: String): String {
        val tpl = template.trim().ifBlank { "{title}" }
        var out = substitute(tpl, vars)
        if (out.isBlank()) out = substitute("{title}", vars)
        if (out.isBlank()) out = fallbackName.substringBeforeLast('.', fallbackName)
        if (out.isBlank()) out = "download"
        return sanitize(collapse(out))
    }

    private fun substitute(tpl: String, v: Vars): String =
        tpl.replace("{title}", v.title)
            .replace("{author}", v.author)
            .replace("{id}", v.id)
            .replace("{platform}", v.platform)
            .replace("{quality}", v.quality)
            .replace("{date}", v.date)
            .replace("{index}", if (v.index > 0) v.index.toString() else "")

    /** 分隔符收拢（规则见类注释） */
    private fun collapse(s: String): String {
        var r = s.replace(Regex("\\s+"), " ")
        r = r.replace(Regex("([-_·])\\1+"), "$1")
        r = r.replace(Regex("\\s*([-_·])\\s*([-_·])\\s*"), " $1 ")
        r = r.replace(Regex("\\s+"), " ").trim()
        // 去首尾分隔符/点（避免 ".mp4" 前出现 "标题..mp4" 或名字以 "- " 开头）
        r = r.trim { it == ' ' || it == '-' || it == '_' || it == '·' || it == '.' }
        // 收拢后再压一次名字内部的 ".."（如标题自带 "v1..2" 的笔误形态）
        r = r.replace(Regex("\\.{2,}"), ".")
        return r
    }

    /** 与 DownloadService.sanitizeFileName 同规则（那份是 private，这里保持一致实现） */
    fun sanitize(name: String): String {
        var n = name.trim().ifBlank { "download" }
        n = n.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        if (n.length > 200) n = n.take(200)
        return n
    }

    /** 时间戳（秒）→ yyyy-MM-dd；<=0 返回空串 */
    fun formatDate(timestampSeconds: Long): String {
        if (timestampSeconds <= 0) return ""
        return try {
            SimpleDateFormat("yyyy-MM-dd").format(Date(timestampSeconds * 1000))
        } catch (e: Exception) {
            ""
        }
    }

    /** yt-dlp 的 upload_date（yyyyMMdd）→ yyyy-MM-dd；形态不对返回空串 */
    fun formatYtDate(uploadDate: String): String {
        val d = uploadDate.trim()
        if (d.length != 8 || !d.all { it.isDigit() }) return ""
        return "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)}"
    }
}
