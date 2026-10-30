package com.yunget.app.data.repository

import com.yunget.app.data.network.ILanzouApi

/**
 * 蓝奏云优享版账号快照（网页版适配：字段与上游 ILanzouAccountEntity 对齐，
 * 实际持久化由 NetdiskService 经 lambda 落到 AccountStore，不移植 Room）。
 */
data class ILanzouAccountEntity(
    val appToken: String = "",
    val uuid: String = "",
    val account: String = "",
    val password: String = "",
    val userId: String = "",
    val nickname: String = ""
)

/**
 * 蓝奏云优享版账号仓库（网页版适配层，逻辑照上游 ILanzouAccountRepository）：
 * 设备标识 uuid 首次获取后持久化；账号密码登录换取 appToken；
 * 密码会同时保存，供 appToken 失效后自动重新登录。
 * 与上游的差异仅在存储：上游是 Room Dao，这里是宿主注入的 provider/saver。
 */
class ILanzouAccountRepository(
    private val accountProvider: () -> ILanzouAccountEntity?,
    private val accountSaver: (ILanzouAccountEntity) -> Unit,
    private val api: ILanzouApi
) {

    suspend fun getAccount(): ILanzouAccountEntity? = accountProvider()

    /** 取有效 appToken：缺失且保存了密码时自动重新登录（上游 ensureAppToken 同款语义）。 */
    suspend fun ensureAppToken(): String? {
        val acc = getAccount() ?: return null
        if (acc.appToken.isNotBlank()) return acc.appToken
        if (acc.password.isBlank()) return null
        val uuid = acc.uuid.takeIf { isValidUuid(it) } ?: return null
        val token = runCatching { api.login(acc.account, acc.password, uuid) }.getOrNull() ?: return null
        accountSaver(acc.copy(appToken = token))
        return token
    }

    /** 重新登录（appToken 失效时调用）；成功返回新 token。 */
    suspend fun relogin(): String? {
        val acc = getAccount() ?: return null
        if (acc.password.isBlank()) return null
        val uuid = acc.uuid.takeIf { isValidUuid(it) } ?: api.getUuid()
        val token = runCatching { api.login(acc.account, acc.password, uuid) }.getOrNull() ?: return null
        accountSaver(acc.copy(appToken = token, uuid = uuid))
        return token
    }

    /**
     * 取 userId（下载接口的 downloadId 需要 userId）：缺失时先请求账号信息并回存。
     */
    suspend fun ensureUserId(): String? {
        val acc = getAccount() ?: return null
        if (acc.userId.isNotBlank()) return acc.userId
        val info = api.fetchAccountInfo(acc.appToken, acc.uuid) ?: return null
        if (info.userId.isBlank()) return null
        accountSaver(
            acc.copy(
                userId = info.userId,
                nickname = acc.nickname.ifBlank { info.nickname }
            )
        )
        return info.userId
    }

    private fun isValidUuid(uuid: String): Boolean = Regex("^[A-Za-z0-9_-]{8,128}$").matches(uuid)
}
