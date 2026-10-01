package com.yunget.web.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 各网盘登录凭证的 JSON 文件持久化（~/.yunget-web 或数据目录下 accounts.json）。
 *
 * 注意：Cookie / Token 以明文存放在服务器本地文件，仅供本机自用场景。
 * 不要把数据目录暴露给不可信用户。
 */
@Serializable
data class AccountData(
    val cookie: String = "",
    val token: String = "",
    val xunleiAccessToken: String = "",
    val xunleiRefreshToken: String = "",
    val nickname: String = "",
    val updatedAt: Long = 0L
)

class AccountStore(dataDir: File) {
    private val file = File(dataDir, "accounts.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = Any()
    private var accounts: MutableMap<String, AccountData> = load()

    private fun load(): MutableMap<String, AccountData> {
        return try {
            if (!file.exists()) return mutableMapOf()
            val map: Map<String, AccountData> = json.decodeFromString(file.readText())
            map.toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun persist() {
        try {
            file.parentFile?.mkdirs()
            // 写临时文件后原子替换，避免崩溃写坏
            val tmp = File(file.parentFile, "accounts.json.tmp")
            tmp.writeText(json.encodeToString(accounts))
            tmp.renameTo(file)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun get(platformId: String): AccountData? = synchronized(lock) { accounts[platformId] }

    fun save(platformId: String, data: AccountData) = synchronized(lock) {
        accounts[platformId] = data.copy(updatedAt = System.currentTimeMillis())
        persist()
    }

    fun update(platformId: String, mutate: (AccountData) -> AccountData) = synchronized(lock) {
        val cur = accounts[platformId] ?: AccountData()
        accounts[platformId] = mutate(cur).copy(updatedAt = System.currentTimeMillis())
        persist()
    }

    fun remove(platformId: String) = synchronized(lock) {
        accounts.remove(platformId)
        persist()
    }
}
