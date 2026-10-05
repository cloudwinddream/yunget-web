package com.yunget.web.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 解析历史一条：title 是分享主名称（文件夹/合集名），
 * 免得用户在一堆链接里反复点开看自己解析的是什么。
 */
@Serializable
data class HistoryEntry(
    val id: String,
    /** 有效链接（迅雷口令已展开成分享链接） */
    val link: String,
    val pwd: String = "",
    val platform: String,
    val platformName: String,
    val title: String,
    /** 最近一次解析时间（毫秒） */
    val createdAt: Long,
    val favorite: Boolean = false
)

/**
 * 解析历史 + 收藏的 JSON 文件持久化（history.json，与 accounts.json 同目录）。
 * 收藏是历史的子集（favorite=true），解析页收藏快捷区直接取收藏项。
 */
class HistoryStore(dataDir: File) {
    private val file = File(dataDir, "history.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = Any()
    private var entries: MutableList<HistoryEntry> = load()

    private fun load(): MutableList<HistoryEntry> = try {
        if (!file.exists()) mutableListOf()
        else json.decodeFromString<List<HistoryEntry>>(file.readText()).toMutableList()
    } catch (e: Exception) {
        mutableListOf()
    }

    private fun persist() {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "history.json.tmp")
            tmp.writeText(json.encodeToString(entries))
            tmp.renameTo(file)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 最近解析在前 */
    fun list(): List<HistoryEntry> = synchronized(lock) { entries.sortedByDescending { it.createdAt } }

    /**
     * 记录一次解析：同链接已存在则更新标题/时间（沿用收藏状态、保留旧 pwd 兜底），
     * 否则新建。非收藏最多保留最近 100 条。
     */
    fun record(link: String, pwd: String, platform: String, platformName: String, title: String): HistoryEntry =
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val idx = entries.indexOfFirst { it.link == link }
            val e = if (idx >= 0) {
                val old = entries.removeAt(idx)
                old.copy(
                    pwd = pwd.ifBlank { old.pwd },
                    platform = platform,
                    platformName = platformName,
                    title = title,
                    createdAt = now
                )
            } else {
                HistoryEntry(UUID.randomUUID().toString(), link, pwd, platform, platformName, title, now)
            }
            entries.add(0, e)
            var kept = 0
            entries.removeIf { !it.favorite && ++kept > MAX_HISTORY }
            persist()
            e
        }

    fun setFavorite(id: String, fav: Boolean): HistoryEntry? = synchronized(lock) {
        val i = entries.indexOfFirst { it.id == id }
        if (i < 0) return null
        val e = entries[i].copy(favorite = fav)
        entries[i] = e
        persist()
        e
    }

    fun delete(id: String) = synchronized(lock) {
        entries.removeIf { it.id == id }
        persist()
    }

    fun clearNonFavorites() = synchronized(lock) {
        entries.removeIf { !it.favorite }
        persist()
    }

    companion object {
        const val MAX_HISTORY = 100
    }
}
