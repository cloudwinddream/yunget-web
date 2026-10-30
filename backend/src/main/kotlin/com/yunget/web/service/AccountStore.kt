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
    val updatedAt: Long = 0L,
    // ---- v1.0.26 新增（优享 / 光鸭）：均带默认值，旧 accounts.json 可直接读 ----
    /** 登录密码（优享 appToken 失效时静默重登用；仅本机存储） */
    val password: String = "",
    /** 登录账号名（优享静默重登用；昵称可能与账号名不同，故单列） */
    val username: String = "",
    /** 通用 refresh token（光鸭 access token 续期用） */
    val refreshToken: String = "",
    /** 设备标识（优享 uuid / 光鸭 deviceId，首次生成后持久化，换设备标识会被风控盯上） */
    val deviceId: String = "",
    /** 光鸭设备签名（wdi10.<deviceId><随机尾>，与 deviceId 成对生成、成对持久化） */
    val deviceSign: String = "",
    /** 平台侧用户 ID（优享取链的 downloadId 需要 userId） */
    val userId: String = ""
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
