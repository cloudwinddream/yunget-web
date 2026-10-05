/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunget.app.data.repository

import com.yunget.app.data.network.model.DownloadLink
import com.yunget.app.data.network.model.ShareFile
import com.yunget.app.data.network.model.ShareSession

/**
 * 分享解析仓库公共接口：夸克 / UC 共用同一套流程（token → 列表 → 转存 → 直链）。
 */
interface ShareResolveRepository {
    suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession>
    suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>>
    suspend fun ensureTempDir(cookie: String): Result<String>
    suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String>
    suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink>

    /**
     * 获取分享文件下载直链（平台差异在此收敛）：
     * - 夸克：转存到临时目录 → 用转存后新 fid 取直链；
     * - UC：直接用分享 fid + stoken + fid_token 取直链（无需转存）。
     */
    suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink>

    /**
     * 免转存取链（登录态）：把分享凭证（pwd_id / stoken / fids / share_fid_token）直接交
     * file/download 换直链，不转存、不占本账号空间（个别分享服务端仍要求先转存，
     * 此时本方法失败，由调用方回退 [getShareDownloadLink]）。默认不支持，夸克实现。
     */
    suspend fun getShareDownloadLinkWithoutSave(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> =
        Result.failure(UnsupportedOperationException("当前平台不支持免转存下载"))

    /**
     * 游客取链（未登录）：不带账号 Cookie 直接按分享参数取链。
     * 夸克仅放行约 50MB 以内的小文件（超出报 23018）；UC 实测大文件也放行。
     * 返回的 [DownloadLink.guestCookie] 是服务端下发的游客态 __pugs，下载时必须回带。
     */
    suspend fun getGuestShareDownloadLink(
        session: ShareSession,
        file: ShareFile
    ): Result<DownloadLink> =
        Result.failure(UnsupportedOperationException("当前平台不支持免登录下载"))

    /**
     * 下载完成后清理临时转存目录（夸克实现删除 tr_* 子目录；其它平台默认空实现）。
     * @param dirFid DownloadLink.cleanupDirFid 带回的临时目录 fid
     */
    suspend fun cleanupTempDir(dirFid: String, cookie: String) {}
}