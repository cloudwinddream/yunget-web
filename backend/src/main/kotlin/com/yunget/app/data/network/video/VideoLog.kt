package com.yunget.app.data.network.video

import org.slf4j.LoggerFactory

/**
* 替代 `android.util.Log` 的最小日志面。
*
* 上游 KuaishouParser / XhsParser 用 `android.util.Log.w(tag, msg)` 记非致命诊断，
* JVM 无此类；调用点已逐个改为 [VideoLog.w]，转发到服务端日志，信息不丢。
*/
object VideoLog {

private val log = LoggerFactory.getLogger("video-parser")

fun w(tag: String, msg: String) {
log.warn("[{}] {}", tag, msg)
}
}
