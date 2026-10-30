package com.yunget.web.routes

import com.yunget.app.data.network.model.ShareFile
import com.yunget.web.model.ApiResult
import com.yunget.web.model.BatchActionRequest
import com.yunget.web.model.BatchDeleteRequest
import com.yunget.web.model.DriveDownloadRequest
import com.yunget.web.model.DriveFolderRequest
import com.yunget.web.model.DriveItemRequest
import com.yunget.web.model.DriveRenameRequest
import com.yunget.web.model.CookieLoginRequest
import com.yunget.web.model.GitHubDownloadRequest
import com.yunget.web.model.GitHubParseRequest
import com.yunget.web.model.GopeedCreateTaskRequest
import com.yunget.web.model.DirListResponse
import com.yunget.web.model.QrStatusResponse
import com.yunget.web.model.DirectDownloadRequest
import com.yunget.web.model.DownloadSubmitRequest
import com.yunget.web.model.ExpandRequest
import com.yunget.web.model.FavoriteRequest
import com.yunget.web.model.FileItem
import com.yunget.web.model.ParseRequest
import com.yunget.web.model.ParseResponse
import com.yunget.web.model.PasswordLoginRequest
import com.yunget.web.model.SettingsData
import com.yunget.web.model.BilibiliLoginRequest
import com.yunget.web.model.VideoDownloadRequest
import com.yunget.web.model.VideoListDownloadRequest
import com.yunget.web.model.VideoParseRequest
import com.yunget.web.model.YouTubeDownloadRequest
import com.yunget.web.model.YouTubePlaylistDownloadRequest
import com.yunget.web.model.YtDlpInstallRequest
import com.yunget.web.model.XunleiSmsLoginRequest
import com.yunget.web.model.XunleiSmsRequest
import com.yunget.web.model.fail
import com.yunget.web.model.ok
import com.yunget.web.service.DownloadService
import com.yunget.web.service.GopeedService
import com.yunget.web.service.NetdiskService
import com.yunget.web.service.VideoService
import com.yunget.web.service.YouTubeService
import com.yunget.web.service.Platform
import com.yunget.web.service.QrLoginService
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Route.apiRoutes(netdisk: NetdiskService, downloads: DownloadService, gopeed: GopeedService, video: VideoService, youtube: YouTubeService) {
    route("/api") {

        // ---------- 平台与账号 ----------
        get("/platforms") {
            call.respond(ok(netdisk.platforms()))
        }

        get("/accounts/{platform}/quota") {
            val platform = call.parameters["platform"] ?: return@get call.respond(fail("缺少平台参数"))
            try {
                call.respond(ok(netdisk.quota(platform)))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "获取容量失败"))
            }
        }

        post("/accounts/{platform}") {
            val platform = call.parameters["platform"] ?: return@post call.respond(fail("缺少平台参数"))
            val p = Platform.fromId(platform) ?: return@post call.respond(fail("未知平台"))
            try {
                when (p.loginType) {
                    "cookie" -> {
                        val req = call.receive<CookieLoginRequest>()
                        if (req.cookie.isBlank()) {
                            call.respond(fail("Cookie 不能为空"))
                        } else {
                            val r = netdisk.saveCookie(platform, req.cookie.trim())
                            r.fold(
                                onSuccess = { call.respond(ok(mapOf("nickname" to it))) },
                                onFailure = { call.respond(fail(it.message ?: "登录失败")) }
                            )
                        }
                    }
                    "password" -> {
                        val req = call.receive<PasswordLoginRequest>()
                        if (p == Platform.ILANZOU) {
                            val r = netdisk.loginIlanzou(req.username, req.password)
                            r.fold(
                                onSuccess = { call.respond(ok(mapOf("nickname" to it))) },
                                onFailure = { call.respond(fail(it.message ?: "登录失败")) }
                            )
                        } else if (p == Platform.GUANGYA) {
                            val r = netdisk.loginGuangya(req.username, req.password)
                            r.fold(
                                onSuccess = { call.respond(ok(mapOf("nickname" to it))) },
                                onFailure = { call.respond(fail(it.message ?: "登录失败")) }
                            )
                        } else if (p == Platform.LANZOU) {
                            val r = netdisk.loginLanzou(req.username, req.password)
                            r.fold(
                                onSuccess = { call.respond(ok(mapOf("nickname" to it))) },
                                onFailure = { call.respond(fail(it.message ?: "登录失败")) }
                            )
                        } else if (p == Platform.PAN123) {
                            val r = netdisk.loginPan123(req.username, req.password)
                            r.fold(
                                onSuccess = { call.respond(ok(mapOf("nickname" to it))) },
                                onFailure = { call.respond(fail(it.message ?: "登录失败")) }
                            )
                        } else {
                            // 迅雷：密码登录可能需要短信二次验证
                            val resp = netdisk.xunleiPasswordLogin(req.username, req.password)
                            call.respond(ok(resp))
                        }
                    }
                }
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "登录失败"))
            }
        }

        post("/accounts/xunlei/sms") {
            try {
                val req = call.receive<XunleiSmsRequest>()
                val r = netdisk.xunleiSendSms(req.mobile)
                r.fold(
                    onSuccess = { call.respond(ok(it)) },
                    onFailure = { call.respond(fail(it.message ?: "短信发送失败")) }
                )
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "短信发送失败"))
            }
        }

        post("/accounts/xunlei/sms-login") {
            try {
                val req = call.receive<XunleiSmsLoginRequest>()
                val r = netdisk.xunleiSmsLogin(req.mobile, req.code, req.creditKey, req.smsToken)
                r.fold(
                    onSuccess = { call.respond(ok(mapOf("nickname" to it))) },
                    onFailure = { call.respond(fail(it.message ?: "验证失败")) }
                )
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "验证失败"))
            }
        }

        delete("/accounts/{platform}") {
            val platform = call.parameters["platform"] ?: return@delete call.respond(fail("缺少平台参数"))
            netdisk.logout(platform)
            call.respond(ok(mapOf("done" to true)))
        }

        // ---------- 扫码登录（夸克 / UC）----------
        post("/qrlogin/{platform}") {
            val platform = call.parameters["platform"] ?: return@post call.respond(fail("缺少平台参数"))
            if (!QrLoginService.supported(platform)) {
                return@post call.respond(fail("该平台暂不支持扫码登录，请用 Cookie 方式"))
            }
            try {
                val (sessionId, qrUrl) = QrLoginService.start(platform)
                call.respond(ok(mapOf("sessionId" to sessionId, "qrUrl" to qrUrl)))
            } catch (e: Exception) {
                call.respond(fail("获取二维码失败：${e.message}"))
            }
        }
        get("/qrlogin/{platform}/status") {
            val platform = call.parameters["platform"] ?: return@get call.respond(fail("缺少平台参数"))
            val sessionId = call.request.queryParameters["sessionId"]
                ?: return@get call.respond(fail("缺少 sessionId"))
            val r = QrLoginService.poll(sessionId)
            when (r.status) {
                "success" -> {
                    val nr = netdisk.saveCookie(platform, r.cookie ?: "")
                    nr.fold(
                        onSuccess = { call.respond(ok(QrStatusResponse("success", nickname = it))) },
                        onFailure = {
                            call.respond(
                                ok(
                                    QrStatusResponse(
                                        "failed",
                                        message = "扫码成功，但 Cookie 验证失败：${it.message}"
                                    )
                                )
                            )
                        }
                    )
                }
                else -> call.respond(ok(QrStatusResponse(r.status, message = r.message)))
            }
        }
        delete("/qrlogin/session") {
            val sessionId = call.request.queryParameters["sessionId"]
            if (sessionId != null) QrLoginService.cancel(sessionId)
            call.respond(ok(mapOf("done" to true)))
        }

        // ---------- 分享解析 ----------
        post("/parse") {
            try {
                val req = call.receive<ParseRequest>()
                if (req.link.isBlank()) {
                    call.respond(fail("分享链接不能为空"))
                    return@post
                }
                val r = netdisk.parse(req.link.trim(), req.pwd)
                call.respond(
                    ok(
                        ParseResponse(
                            sessionId = r.sessionId,
                            platform = r.platform.id,
                            platformName = r.platform.displayName,
                            title = r.title,
                            historyId = r.historyId,
                            favorite = r.favorite
                        )
                    )
                )
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "解析失败"))
            }
        }

        // ---------- 解析历史 / 收藏 ----------
        get("/history") {
            call.respond(ok(netdisk.historyList()))
        }

        post("/history/{id}/favorite") {
            val id = call.parameters["id"] ?: return@post call.respond(fail("缺少记录"))
            try {
                val req = call.receive<FavoriteRequest>()
                call.respond(ok(netdisk.historySetFavorite(id, req.favorite)))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "操作失败"))
            }
        }

        post("/history/clear") {
            netdisk.historyClear()
            call.respond(ok(mapOf("cleared" to true)))
        }

        delete("/history/{id}") {
            val id = call.parameters["id"] ?: return@delete call.respond(fail("缺少记录"))
            netdisk.historyDelete(id)
            call.respond(ok(mapOf("deleted" to true)))
        }

        get("/sessions/{id}/files") {
            try {
                val id = call.parameters["id"] ?: return@get call.respond(fail("缺少会话"))
                val dirFid = call.request.queryParameters["dirFid"] ?: "0"
                val files = netdisk.listFiles(id, dirFid)
                call.respond(
                    ok(files.map {
                        FileItem(
                            fid = it.fid, fname = it.fname, fsize = it.fsize,
                            isdir = it.isdir, pdirFid = it.pdirFid,
                            fidToken = it.fidToken, modifyTime = it.modifyTime
                        )
                    })
                )
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "获取文件列表失败"))
            }
        }


        // ---------- 个人盘文件（蓝奏云 / 蓝奏云优享版 / 光鸭云盘 / 115 网盘 / 夸克 / UC） ----------

        get("/drive/{platform}/list") {
            val platform = call.parameters["platform"] ?: return@get call.respond(fail("缺少平台参数"))
            try {
                val dirFid = call.request.queryParameters["dirFid"] ?: ""
                val files = when (platform) {
                    "lanzou" -> netdisk.lanzouDriveList(dirFid)
                    "ilanzou" -> netdisk.ilanzouDriveList(dirFid)
                    "guangya" -> netdisk.guangyaDriveList(dirFid)
                    "pan115" -> netdisk.pan115DriveList(dirFid)
                    "quark" -> netdisk.quarkDriveList(dirFid)
                    "uc" -> netdisk.ucDriveList(dirFid)
                    else -> return@get call.respond(fail("该平台暂不支持网盘文件管理"))
                }
                call.respond(ok(files.map { it.toItem() }))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "获取文件列表失败"))
            }
        }

        post("/drive/{platform}/download") {
            val platform = call.parameters["platform"] ?: return@post call.respond(fail("缺少平台参数"))
            try {
                val req = call.receive<DriveDownloadRequest>()
                val id = when (platform) {
                    "lanzou" -> netdisk.lanzouDriveDownload(req.fid, req.fname)
                    "ilanzou" -> netdisk.ilanzouDriveDownload(req.fid, req.fname)
                    "guangya" -> netdisk.guangyaDriveDownload(req.fid, req.fname)
                    "pan115" -> netdisk.pan115DriveDownload(req.fid, req.fname, req.fidToken)
                    "quark" -> netdisk.quarkDriveDownload(req.fid, req.fname)
                    "uc" -> netdisk.ucDriveDownload(req.fid, req.fname)
                    else -> return@post call.respond(fail("该平台暂不支持网盘文件管理"))
                }
                call.respond(ok(mapOf("taskId" to id)))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "下载失败"))
            }
        }

        post("/drive/{platform}/folder") {
            val platform = call.parameters["platform"] ?: return@post call.respond(fail("缺少平台参数"))
            try {
                val req = call.receive<DriveFolderRequest>()
                if (req.name.isBlank()) return@post call.respond(fail("文件夹名不能为空"))
                val id = when (platform) {
                    "lanzou" -> netdisk.lanzouCreateFolder(req.parentId, req.name.trim())
                    "ilanzou" -> netdisk.ilanzouCreateFolder(req.parentId, req.name.trim())
                    "guangya" -> netdisk.guangyaCreateFolder(req.parentId, req.name.trim())
                    "pan115" -> netdisk.pan115CreateFolder(req.parentId, req.name.trim())
                    "quark" -> netdisk.quarkCreateFolder(req.parentId, req.name.trim())
                    "uc" -> netdisk.ucCreateFolder(req.parentId, req.name.trim())
                    else -> return@post call.respond(fail("该平台暂不支持网盘文件管理"))
                }
                call.respond(ok(mapOf("folderId" to id)))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "新建文件夹失败"))
            }
        }

        post("/drive/{platform}/rename") {
            val platform = call.parameters["platform"] ?: return@post call.respond(fail("缺少平台参数"))
            try {
                val req = call.receive<DriveRenameRequest>()
                if (req.name.isBlank()) return@post call.respond(fail("名称不能为空"))
                when (platform) {
                    "lanzou" -> netdisk.lanzouRename(req.isDir, req.id, req.name.trim())
                    "ilanzou" -> netdisk.ilanzouRename(req.isDir, req.id, req.name.trim())
                    "guangya" -> netdisk.guangyaRename(req.isDir, req.id, req.name.trim())
                    "pan115" -> netdisk.pan115Rename(req.isDir, req.id, req.name.trim())
                    "quark" -> netdisk.quarkRename(req.isDir, req.id, req.name.trim())
                    "uc" -> netdisk.ucRename(req.isDir, req.id, req.name.trim())
                    else -> return@post call.respond(fail("该平台暂不支持网盘文件管理"))
                }
                call.respond(ok(mapOf("renamed" to true)))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "重命名失败"))
            }
        }

        post("/drive/{platform}/delete") {
            val platform = call.parameters["platform"] ?: return@post call.respond(fail("缺少平台参数"))
            try {
                val req = call.receive<DriveItemRequest>()
                when (platform) {
                    "lanzou" -> netdisk.lanzouDelete(req.isDir, req.id)
                    "ilanzou" -> netdisk.ilanzouDelete(req.isDir, req.id)
                    "guangya" -> netdisk.guangyaDelete(req.isDir, req.id)
                    "pan115" -> netdisk.pan115Delete(req.id, req.pdirFid)
                    "quark" -> netdisk.quarkDelete(req.isDir, req.id)
                    "uc" -> netdisk.ucDelete(req.isDir, req.id)
                    else -> return@post call.respond(fail("该平台暂不支持网盘文件管理"))
                }
                call.respond(ok(mapOf("deleted" to true)))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "删除失败"))
            }
        }

        post("/drive/{platform}/share") {
            val platform = call.parameters["platform"] ?: return@post call.respond(fail("缺少平台参数"))
            try {
                val req = call.receive<DriveItemRequest>()
                val info = when (platform) {
                    "lanzou" -> netdisk.lanzouShare(req.isDir, req.id)
                    "ilanzou" -> netdisk.ilanzouShare(req.isDir, req.id)
                    "guangya" -> netdisk.guangyaShare(req.isDir, req.id, req.fname)
                    "pan115" -> netdisk.pan115Share(req.id)
                    "quark" -> netdisk.quarkShare(req.id, req.fname)
                    "uc" -> netdisk.ucShare(req.id, req.fname)
                    else -> return@post call.respond(fail("该平台暂不支持网盘文件管理"))
                }
                call.respond(
                    ok(
                        mapOf(
                            "shareUrl" to info.shareUrl,
                            "passcode" to info.passcode,
                            "warning" to (info.warning ?: "")
                        )
                    )
                )
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "生成分享失败"))
            }
        }


        // ---------- 下载 ----------
        post("/downloads/expand") {
            try {
                val req = call.receive<ExpandRequest>()
                val r = netdisk.expandSelection(req.sessionId, req.files)
                call.respond(ok(r))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "展开文件夹失败"))
            }
        }

        post("/downloads") {
            try {
                val req = call.receive<DownloadSubmitRequest>()
                val ids = if (req.expandId.isNotBlank()) {
                    netdisk.resolveAndEnqueueExpanded(req.expandId)
                } else {
                    if (req.files.isEmpty()) {
                        call.respond(fail("请选择要下载的文件"))
                        return@post
                    }
                    netdisk.resolveAndEnqueue(req.sessionId, req.files)
                }
                call.respond(ok(mapOf("taskIds" to ids)))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "提交下载失败"))
            }
        }

        post("/downloads/direct") {
            try {
                val req = call.receive<DirectDownloadRequest>()
                if (req.url.isBlank()) {
                    call.respond(fail("下载链接不能为空"))
                    return@post
                }
                val headers = req.headers.toMutableMap()
                if (!headers.containsKey("User-Agent")) {
                    headers["User-Agent"] =
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                }
                val id = downloads.enqueue(req.url.trim(), req.fileName.trim(), headers, 0)
                call.respond(ok(mapOf("taskIds" to listOf(id))))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "提交下载失败"))
            }
        }

        get("/tasks") {
            call.respond(ok(downloads.list()))
        }

        post("/tasks/{id}/pause") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(fail("任务不存在"))
            downloads.pause(id)
            call.respond(ok(mapOf("done" to true)))
        }

        post("/tasks/{id}/resume") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(fail("任务不存在"))
            downloads.resume(id)
            call.respond(ok(mapOf("done" to true)))
        }

        delete("/tasks/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(fail("任务不存在"))
            val deleteFile = call.request.queryParameters["deleteFile"] == "true"
            downloads.delete(id, deleteFile)
            call.respond(ok(mapOf("done" to true)))
        }

        post("/tasks/batch-delete") {
            val req = call.receive<BatchDeleteRequest>()
            var n = 0
            req.ids.forEach { id ->
                runCatching { downloads.delete(id, req.deleteFile); n++ }
            }
            call.respond(ok(mapOf("deleted" to n)))
        }

        post("/tasks/batch-pause") {
            val req = call.receive<BatchActionRequest>()
            req.ids.forEach { id -> runCatching { downloads.pause(id) } }
            call.respond(ok(mapOf("done" to true)))
        }

        post("/tasks/batch-resume") {
            val req = call.receive<BatchActionRequest>()
            req.ids.forEach { id -> runCatching { downloads.resume(id) } }
            call.respond(ok(mapOf("done" to true)))
        }

        // ---------- 服务器目录浏览（供设置页选择下载目录） ----------
        get("/fs/dirs") {
            val raw = call.request.queryParameters["path"]?.takeIf { it.isNotBlank() }
                ?: System.getProperty("user.home") ?: "/"
            val dir = runCatching { java.io.File(raw).canonicalFile }.getOrNull()
            if (dir == null || !dir.isDirectory) {
                call.respond(fail("目录不存在"))
                return@get
            }
            val dirs = dir.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
            call.respond(
                ok(
                    DirListResponse(
                        path = dir.absolutePath,
                        parent = dir.parentFile?.absolutePath ?: "",
                        dirs = dirs
                    )
                )
            )
        }

        // ---------- 设置 ----------
        get("/settings") {
            call.respond(ok(downloads.settingsForDisplay()))
        }

        put("/settings") {
            try {
                val s = call.receive<SettingsData>()
                downloads.updateSettings(s)
                call.respond(ok(mapOf("done" to true)))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "保存设置失败"))
            }
        }

        post("/settings/reset") {
            try {
                downloads.resetSettings()
                call.respond(ok(downloads.settingsForDisplay()))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "恢复默认设置失败"))
            }
        }

        // ---------- Gopeed 磁力引擎 ----------
        // GopeedService 全部是阻塞调用（进程托管 + 同步 HTTP），统一在 IO 调度器上执行
        get("/gopeed/status") {
            try {
                call.respond(ok(withContext(Dispatchers.IO) { gopeed.status() }))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "获取 Gopeed 状态失败"))
            }
        }

        post("/gopeed/install") {
            try {
                // 同步下载官方内核包（约 20MB），耗时取决于网速
                val st = withContext(Dispatchers.IO) { gopeed.install() }
                call.respond(ok(st))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "安装 Gopeed 内核失败"))
            }
        }

        post("/gopeed/start") {
            try {
                withContext(Dispatchers.IO) { gopeed.start() }
                call.respond(ok(withContext(Dispatchers.IO) { gopeed.status() }))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "启动 Gopeed 失败"))
            }
        }

        post("/gopeed/stop") {
            try {
                withContext(Dispatchers.IO) { gopeed.stop() }
                call.respond(ok(withContext(Dispatchers.IO) { gopeed.status() }))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "停止 Gopeed 失败"))
            }
        }

        post("/gopeed/uninstall") {
            try {
                val st = withContext(Dispatchers.IO) { gopeed.uninstall() }
                call.respond(ok(st))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "卸载 Gopeed 内核失败"))
            }
        }

        // Tracker 来源同步：抓取已启用官方源 + 自定义源并聚合落盘（逐源结果在响应里；
        // 全部失败时旧聚合保留不清空、anySuccess=false，不算接口错误）
        post("/gopeed/trackers/sync") {
            try {
                val r = withContext(Dispatchers.IO) { gopeed.syncTrackers() }
                call.respond(ok(r))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "Tracker 同步失败"))
            }
        }

        get("/gopeed/tasks") {
            try {
                call.respond(ok(withContext(Dispatchers.IO) { gopeed.listTasks() }))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "获取磁力任务失败"))
            }
        }

        post("/gopeed/tasks") {
            try {
                val req = call.receive<GopeedCreateTaskRequest>()
                if (req.url.isBlank()) {
                    call.respond(fail("链接不能为空"))
                    return@post
                }
                val id = withContext(Dispatchers.IO) { gopeed.createTask(req.url.trim(), req.name.trim()) }
                call.respond(ok(mapOf("id" to id)))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "创建磁力任务失败"))
            }
        }

        post("/gopeed/tasks/{id}/pause") {
            val id = call.parameters["id"] ?: return@post call.respond(fail("缺少任务 ID"))
            try {
                withContext(Dispatchers.IO) { gopeed.pauseTask(id) }
                call.respond(ok(mapOf("done" to true)))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "暂停失败"))
            }
        }

        post("/gopeed/tasks/{id}/continue") {
            val id = call.parameters["id"] ?: return@post call.respond(fail("缺少任务 ID"))
            try {
                withContext(Dispatchers.IO) { gopeed.continueTask(id) }
                call.respond(ok(mapOf("done" to true)))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "继续失败"))
            }
        }

        delete("/gopeed/tasks/{id}") {
            val id = call.parameters["id"] ?: return@delete call.respond(fail("缺少任务 ID"))
            try {
                withContext(Dispatchers.IO) { gopeed.deleteTask(id) }
                call.respond(ok(mapOf("done" to true)))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "删除失败"))
            }
        }


        // ---------- 视频解析 / GitHub 解析（v1.0.28） ----------
        // 媒体与文件真实 URL 只存服务端会话，前端按下标回查下载（同网盘会话模式）

        post("/video/parse") {
            try {
                val req = call.receive<VideoParseRequest>()
                if (req.link.isBlank()) return@post call.respond(fail("请粘贴视频链接"))
                call.respond(ok(video.parseVideo(req.link.trim())))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "视频解析失败"))
            }
        }

        post("/video/download") {
            try {
                val req = call.receive<VideoDownloadRequest>()
                val ids = video.downloadVideo(req.sessionId, req.indexes)
                call.respond(ok(mapOf("taskIds" to ids)))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "提交下载失败"))
            }
        }

        // B站列表批量下载（kind 分发）：season = 合集逐集解析入队、落「合集名」文件夹；
        // parts = 分P逐个按 cid 取流入队、落「视频总标题」文件夹；逐条结果聚合返回
        post("/video/list/download") {
            try {
                val req = call.receive<VideoListDownloadRequest>()
                val resp = if (req.kind == "parts") {
                    video.downloadPartsList(req.sessionId, req.indexes)
                } else {
                    video.downloadSeasonList(req.sessionId, req.indexes)
                }
                call.respond(ok(resp))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "列表下载提交失败"))
            }
        }

        // ---------- B站账号（Cookie 登录，解锁 DASH 高清；存 AccountStore 独立键） ----------

        get("/video/accounts/bilibili") {
            call.respond(ok(video.bilibiliAccount()))
        }

        post("/video/accounts/bilibili") {
            try {
                val req = call.receive<BilibiliLoginRequest>()
                if (req.cookie.isBlank()) return@post call.respond(fail("请粘贴 Cookie"))
                call.respond(ok(video.loginBilibili(req.cookie)))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "B站登录失败"))
            }
        }

        delete("/video/accounts/bilibili") {
            video.logoutBilibili()
            call.respond(ok(video.bilibiliAccount()))
        }

        // ---------- ffmpeg（B站分轨合轨：状态 / 一键安装） ----------

        get("/video/ffmpeg") {
            try {
                call.respond(ok(withContext(Dispatchers.IO) { video.ffmpegStatus() }))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "获取 ffmpeg 状态失败"))
            }
        }

        post("/video/ffmpeg/install") {
            try {
                // 同步阻塞（下载+解压），照 Gopeed 路由的做法包 Dispatchers.IO
                val st = withContext(Dispatchers.IO) { video.installFfmpeg() }
                call.respond(ok(st))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "ffmpeg 安装失败"))
            }
        }

        post("/github/parse") {
            try {
                val req = call.receive<GitHubParseRequest>()
                if (req.link.isBlank()) return@post call.respond(fail("请粘贴 GitHub 链接"))
                call.respond(ok(video.parseGitHub(req.link.trim())))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "GitHub 解析失败"))
            }
        }

        post("/github/download") {
            try {
                val req = call.receive<GitHubDownloadRequest>()
                val ids = video.downloadGitHub(req.sessionId, req.indexes)
                call.respond(ok(mapOf("taskIds" to ids)))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "提交下载失败"))
            }
        }

        // ---------- YouTube 解析（yt-dlp 托管工具；真实直链只存服务端会话） ----------

        post("/youtube/parse") {
            try {
                val req = call.receive<VideoParseRequest>()
                if (req.link.isBlank()) return@post call.respond(fail("请粘贴 YouTube 链接"))
                call.respond(ok(withContext(Dispatchers.IO) { youtube.parse(req.link.trim()) }))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "YouTube 解析失败"))
            }
        }

        post("/youtube/download") {
            try {
                val req = call.receive<YouTubeDownloadRequest>()
                call.respond(ok(youtube.download(req)))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "提交下载失败"))
            }
        }

        post("/youtube/playlist/download") {
            try {
                val req = call.receive<YouTubePlaylistDownloadRequest>()
                call.respond(ok(youtube.downloadPlaylist(req)))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "提交下载失败"))
            }
        }

        // ---------- YouTube 账号（Cookie 仅存不校验，解析失败时请更新） ----------

        get("/youtube/account") {
            call.respond(ok(youtube.youtubeAccount()))
        }

        post("/youtube/account") {
            try {
                val req = call.receive<CookieLoginRequest>()
                youtube.saveYoutubeAccount(req.cookie)
                call.respond(ok(youtube.youtubeAccount()))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "保存 Cookie 失败"))
            }
        }

        delete("/youtube/account") {
            youtube.clearYoutubeAccount()
            call.respond(ok(youtube.youtubeAccount()))
        }

        // ---------- yt-dlp 工具（状态 / 一键安装，仿 ffmpeg） ----------

        get("/youtube/ytdlp") {
            try {
                call.respond(ok(withContext(Dispatchers.IO) { youtube.ytdlpStatus() }))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "获取 yt-dlp 状态失败"))
            }
        }

        post("/youtube/ytdlp/install") {
            try {
                // 请求体可带 {"force":true}（「更新」按钮：强制重下最新版）；缺省/无体按普通安装
                val req = runCatching { call.receive<YtDlpInstallRequest>() }
                    .getOrDefault(YtDlpInstallRequest())
                // 同步阻塞（下载二进制），包 Dispatchers.IO
                val st = withContext(Dispatchers.IO) { youtube.installYtdlp(req.force) }
                call.respond(ok(st))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "yt-dlp 安装失败"))
            }
        }

        // ---------- Deno 工具（yt-dlp 的 JS 运行时；状态 / 一键安装，仿 yt-dlp） ----------

        get("/youtube/deno") {
            try {
                call.respond(ok(withContext(Dispatchers.IO) { youtube.denoStatus() }))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "获取 Deno 状态失败"))
            }
        }

        post("/youtube/deno/install") {
            try {
                // 请求体与 yt-dlp 安装同构：{"force":true} = 强制重装（「更新」按钮）
                val req = runCatching { call.receive<YtDlpInstallRequest>() }
                    .getOrDefault(YtDlpInstallRequest())
                // 同步阻塞（下载二进制），包 Dispatchers.IO
                val st = withContext(Dispatchers.IO) { youtube.installDeno(req.force) }
                call.respond(ok(st))
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "Deno 安装失败"))
            }
        }

        get("/health") {
            call.respond(ok(mapOf("status" to "ok")))
        }
    }
}

private fun ShareFile.toItem() = FileItem(
    fid = fid, fname = fname, fsize = fsize, isdir = isdir,
    pdirFid = pdirFid, fidToken = fidToken, modifyTime = modifyTime
)
