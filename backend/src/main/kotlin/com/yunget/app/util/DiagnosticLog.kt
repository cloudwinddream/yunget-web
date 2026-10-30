package com.yunget.app.util

import org.slf4j.LoggerFactory

/**
* 上游 Android 版的诊断日志（写私有目录文件、按模块轮转、依赖
* Context/SettingsRepository）。网页版没有对应的诊断模式开关与导出通道，
* 这里只保留移植代码实际用到的接口面（模块常量 + [warn]），转发到服务端日志，
* 语义等价于「诊断行进日志」，不落上游的文件轮转机制。
*/
object DiagnosticLog {

const val DB = "db"
const val CRYPTO = "crypto"
const val DOWNLOAD = "download"
const val WEBVIEW = "webview"
const val NETWORK = "network"
const val OPERATION = "operation"

private val log = LoggerFactory.getLogger("diagnostic")

/** 记录一条 WARN 级诊断事件（上游签名：模块、事件名、详情） */
fun warn(module: String, event: String, message: String) {
log.warn("[{}] {}: {}", module, event, message)
}
}
