package com.yunget.web.routes

import com.yunget.app.data.network.model.ShareFile
import com.yunget.web.model.ApiResult
import com.yunget.web.model.BatchActionRequest
import com.yunget.web.model.BatchDeleteRequest
import com.yunget.web.model.CookieLoginRequest
import com.yunget.web.model.DirListResponse
import com.yunget.web.model.QrStatusResponse
import com.yunget.web.model.DirectDownloadRequest
import com.yunget.web.model.DownloadSubmitRequest
import com.yunget.web.model.ExpandRequest
import com.yunget.web.model.FileItem
import com.yunget.web.model.ParseRequest
import com.yunget.web.model.ParseResponse
import com.yunget.web.model.PasswordLoginRequest
import com.yunget.web.model.SettingsData
import com.yunget.web.model.XunleiSmsLoginRequest
import com.yunget.web.model.XunleiSmsRequest
import com.yunget.web.model.fail
import com.yunget.web.model.ok
import com.yunget.web.service.DownloadService
import com.yunget.web.service.NetdiskService
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

fun Route.apiRoutes(netdisk: NetdiskService, downloads: DownloadService) {
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
                        if (p == Platform.PAN123) {
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
                            title = r.title
                        )
                    )
                )
            } catch (e: Exception) {
                call.respond(fail(e.message ?: "解析失败"))
            }
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

        get("/health") {
            call.respond(ok(mapOf("status" to "ok")))
        }
    }
}

private fun ShareFile.toItem() = FileItem(
    fid = fid, fname = fname, fsize = fsize, isdir = isdir,
    pdirFid = pdirFid, fidToken = fidToken, modifyTime = modifyTime
)
