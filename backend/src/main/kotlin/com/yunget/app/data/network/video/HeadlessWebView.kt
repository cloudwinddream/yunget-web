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
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package com.yunget.app.data.network.video

/**
* 上游 Android 版用隐藏 WebView 跑页面 JS 取数（实现见上游
* `HeadlessWebView.kt`，依赖 Activity/WebView，JVM 上不存在此运行环境）。
*
* 桩的语义严格对齐上游契约：上游在「拿不到 Activity / 超时 / 页面报错」时返回 null，
* JVM 上永远没有 WebView 可用，所以 [loadAndExtract] 恒返回 null —— 调用方
* （[DouyinParser.fetchViaWebView]）会据此走它自己的 HTTP 回退路线，行为与上游
* 「WebView 不可用」分支完全一致，不静默吞结果、也不伪造数据。
*
* 受影响面（实测结论见 DouyinParser 类注释）：抖音的主路线就是 WebView 路线，
* 回退的纯 HTTP + SSR 路线自 2026-10 起被 byted_acrawler 挑战挡住、基本拿不到数据，
* 因此网页版抖音解析大概率失败，失败信息会明确来自回退路线（页面无 _ROUTER_DATA 等）。
*
* 注：上游还有 `install(Application)` 一员，签名依赖 android.app.Application 且仅由
* Android 的 Application 类调用，JVM 无对应物、移植范围内无调用方，故不提供。
*/
object HeadlessWebView {

/**
* 与上游同签名。JVM 无 WebView，恒返回 null（= 上游「不可用」语义）。
*/
@Suppress("UNUSED_PARAMETER")
suspend fun loadAndExtract(
url: String,
userAgent: String,
readyCheckJs: String,
extractJs: String,
timeoutMs: Long = 25_000L,
pollIntervalMs: Long = 350L,
extraDocumentStartJs: List<String> = emptyList()
): String? = null
}
