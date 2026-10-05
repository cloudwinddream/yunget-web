package com.yunget.web

import com.yunget.app.data.network.XunleiDeviceFingerprint
import com.yunget.web.model.ApiResult
import com.yunget.web.routes.apiRoutes
import com.yunget.web.service.AccountStore
import com.yunget.web.service.DownloadService
import com.yunget.web.service.HistoryStore
import com.yunget.web.service.NetdiskService
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("yunget-web")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull()
        ?: System.getenv("YUNGET_PORT")?.toIntOrNull()
        ?: 8080
    val dataDir = File(
        System.getenv("YUNGET_DATA_DIR") ?: "${System.getProperty("user.home")}/.yunget-web"
    ).apply { mkdirs() }

    log.info("数据目录: ${dataDir.absolutePath}")

    // 中文文件名依赖 JVM 的文件名编码；POSIX/C locale 下下载的中文文件名会异常
    val jnu = System.getProperty("sun.jnu.encoding", "")
    if (!jnu.equals("UTF-8", ignoreCase = true)) {
        log.warn(
            "当前文件名编码为 {}，下载的中文文件名可能异常；" +
                "建议使用 UTF-8 locale 启动，例如：LANG=C.UTF-8 LC_ALL=C.UTF-8 java -jar yunget-web.jar",
            jnu
        )
    }

    // 迅雷设备指纹（首次启动生成并持久化，此后复用）
    XunleiDeviceFingerprint.init(dataDir)

    val accounts = AccountStore(dataDir)
    val downloads = DownloadService(dataDir)
    val netdisk = NetdiskService(accounts, downloads, HistoryStore(dataDir))

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { downloads.shutdown() }
    })

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(netdisk, downloads)
    }.start(wait = true)
}

fun Application.module(netdisk: NetdiskService, downloads: DownloadService) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true })
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("请求异常: ${cause.message}", cause)
            // 不把堆栈/敏感信息吐给前端
            call.respond(ApiResult<Unit>(false, null, cause.message ?: "服务器内部错误"))
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(HttpStatusCode.NotFound, ApiResult<Unit>(false, null, "接口不存在"))
        }
    }
    install(CallLogging)

    routing {
        // 前端静态页
        staticResources("/", "static", index = "index.html")
        apiRoutes(netdisk, downloads)
    }
}
