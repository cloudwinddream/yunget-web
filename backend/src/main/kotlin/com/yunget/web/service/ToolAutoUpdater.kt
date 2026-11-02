package com.yunget.web.service

import com.yunget.web.model.ToolAutoUpdateResult
import com.yunget.web.model.ToolAutoUpdateStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 版本号归一化与比较（纯函数、无副作用，离线可单测）。
 *
 * 归一化：去首尾空白与开头的 v/V 前缀，只取开头的「数字＋点」核心段
 * （如 `v1.9.3` → [1,9,3]、`2025.10.14` → [2025,10,14]、`2.9.8 (stable)` → [2,9,8]、
 * `1.0-rc1` → [1,0]），逐段按**整数**比较（10 > 9，不走字典序），缺段按 0 补齐。
 * 两边都解析不出任何数字段时视为相等（compare 返回 0）——宁可不更新，也不拿
 * 垃圾输入乱触发更新。
 */
object ToolVersions {

    fun normalize(raw: String): List<Int> {
        var s = raw.trim()
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
        val core = s.takeWhile { it.isDigit() || it == '.' }
        if (core.none { it.isDigit() }) return emptyList()
        return core.split('.').map { seg -> seg.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
    }

    /** a > b 返回正数，a < b 返回负数，相等/不可比返回 0 */
    fun compare(a: String, b: String): Int {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0
        val n = maxOf(x.size, y.size)
        for (i in 0 until n) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** 远程版本是否严格新于本地版本 */
    fun isNewer(remote: String, local: String): Boolean = compare(remote, local) > 0
}

/**
 * 一个可自动更新工具的「渠道」：把「读本地状态 / 查官方最新版 / 执行更新」
 * 三个动作打包成可注入的函数。
 *
 * 生产组装见 [ToolAutoUpdater.defaultChannels]；离线 harness 可整体替换
 * 渠道表为替身（假远程版本＋假执行器），不起真实进程、不发真实网络请求地
 * 驱动一整轮检查逻辑。
 *
 * @param probe 读本地状态：是否已安装、是否为**应用托管版**（铁律判定落点）、本地版本、来源标识
 * @param fetchRemote 查官方最新版号（入参为当前 toolProxy 设置；失败抛异常）
 * @param performUpdate 执行更新（内部复用各工具现有 install(force=true) 路径），
 *   返回更新后复探到的版本与可选附注；失败抛异常
 */
class ToolChannel(
    val id: String,
    val probe: () -> Probe,
    val fetchRemote: (toolProxy: String) -> String,
    val performUpdate: () -> UpdateOutcome
) {
    data class Probe(
        val installed: Boolean,
        /** 是否为应用托管安装（source == "app"；Gopeed 内核只有托管一种来源，装了即托管） */
        val managed: Boolean,
        val version: String,
        /** 来源标识（manual / path / system / app …），仅用于拼跳过原因文案 */
        val source: String = ""
    )

    data class UpdateOutcome(
        /** 更新后复探到的本地版本（拿不到时为空，调用方以远程版本兜底展示） */
        val newVersion: String,
        /** 附注（如 Gopeed 引擎已自动重启），拼在结果 message 尾部 */
        val note: String = ""
    )
}

/**
 * 工具自动更新调度器：应用启动约 60 秒后首检、之后每 24 小时一轮，把
 * yt-dlp / Deno / Gopeed 三个**应用托管**工具保持在官方最新版。
 *
 * 设计口径（与设置页文案一致）：
 * - 每轮现读 [DownloadService.settings]：开关关掉的轮次直接跳过（改开关
 *   不用重启，下轮生效）；检查与下载都走设置里的 toolProxy。
 * - 铁律：只更新应用托管版（各 Support 状态里的 source == "app"；Gopeed
 *   内核只有托管来源）。命中手动路径 / PATH / 系统路径的一律不碰——那是
 *   用户自己的副本（如 Mac 上 brew 装的 yt-dlp），应用无权擅自替换；
 *   从未安装的不自动安装，只记 skipped。
 * - ffmpeg 不在覆盖范围内：它的三个静态构建源（gyan.dev / evermeet.cx /
 *   johnvansickle）都没有官方版本查询口、且极少更新，保留手动一键安装。
 * - 单轮内三家逐个隔离执行：一家失败（网络不通、GitHub 限流、下载失败）
 *   只记 failed、下轮再试，不影响其它家，更不影响主程序与下载任务；
 *   全程在后台 IO 协程跑，绝不阻塞启动与请求处理。
 * - 状态只存内存（[status]）：重启后首检前 results 为空、lastCheckAt 为 0。
 *
 * Gopeed 特别处理：其 install() 会先停引擎（运行中的 exe 无法被覆盖），
 * 若更新前引擎在运行，更新后自动调 start() 把它拉回来（任务元数据在
 * 引擎存储里，重启即恢复），避免磁力引擎被自动更新悄悄停掉无人知。
 */
class ToolAutoUpdater(
    private val downloads: DownloadService,
    youtube: YouTubeService,
    gopeed: GopeedService,
    /** 渠道表：生产用默认组装；测试/harness 可整体替换（构造后亦可赋值） */
    channels: List<ToolChannel> = defaultChannels(youtube, gopeed)
) {

    private val log = LoggerFactory.getLogger("tool-auto-update")

    /** 渠道表（var：harness 注入替身用；生产构造后不改） */
    var channels: List<ToolChannel> = channels

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    @Volatile
    private var lastCheckAt: Long = 0

    @Volatile
    private var lastResults: List<ToolAutoUpdateResult> = emptyList()

    /** 启动后台调度（Main 启动时调一次）：首检延迟约 60 秒，之后每 24 小时一轮 */
    fun start() {
        scope.launch {
            delay(FIRST_CHECK_DELAY_MS)
            while (isActive) {
                runCatching { runOnce() }
                    .onFailure { log.warn("工具自动更新本轮异常：{}", it.message) }
                delay(CHECK_INTERVAL_MS)
            }
        }
        log.info("工具自动更新调度已启动（首检 {} 秒后，周期 {} 小时）", FIRST_CHECK_DELAY_MS / 1000, CHECK_INTERVAL_MS / 3600_000)
    }

    fun stop() {
        scope.cancel()
    }

    /**
     * 跑一轮检查（同步阻塞，调用方在 IO 线程）：现读设置，开关闭则直接
     * 返回、不动已有状态；三家逐个隔离，结果整轮覆写内存态。
     * 已有一轮在跑时直接返回（防调度与手工触发重叠）。
     */
    fun runOnce() {
        if (!running.compareAndSet(false, true)) return
        try {
            val settings = downloads.settings
            if (!settings.autoUpdateTools) {
                log.info("工具自动更新已关闭，本轮跳过")
                return
            }
            val proxy = settings.toolProxy
            val results = channels.map { ch ->
                try {
                    checkOne(ch, proxy)
                } catch (e: Exception) {
                    // checkOne 内部已分段兜底，这里是最后防线：一家炸掉不连累其它家
                    ToolAutoUpdateResult(ch.id, action = "failed", message = "检查异常：${brief(e)}")
                }
            }
            lastResults = results
            lastCheckAt = System.currentTimeMillis()
            log.info(
                "工具自动更新本轮完成：{}",
                results.joinToString("；") { "${it.tool}=${it.action}" }
            )
        } finally {
            running.set(false)
        }
    }

    /** 状态快照（端点用）：enabled 现读设置，results/lastCheckAt 为内存态 */
    fun status(): ToolAutoUpdateStatus = ToolAutoUpdateStatus(
        enabled = downloads.settings.autoUpdateTools,
        lastCheckAt = lastCheckAt,
        results = lastResults
    )

    /** 单家检查：探测 → 铁律判定 → 查远程 → 比版 → （有新版才）执行更新并复探 */
    private fun checkOne(ch: ToolChannel, toolProxy: String): ToolAutoUpdateResult {
        val probe = try {
            ch.probe()
        } catch (e: Exception) {
            return ToolAutoUpdateResult(ch.id, action = "failed", message = "读取本地状态失败：${brief(e)}")
        }
        if (!probe.installed) {
            return ToolAutoUpdateResult(
                ch.id, localVersion = probe.version, action = "skipped",
                message = "未安装应用托管版，未纳入自动更新（不会自动安装）"
            )
        }
        if (!probe.managed) {
            return ToolAutoUpdateResult(
                ch.id, localVersion = probe.version, action = "skipped",
                message = skipReason(ch, probe.source)
            )
        }
        val remote = try {
            ch.fetchRemote(toolProxy).trim()
        } catch (e: Exception) {
            return ToolAutoUpdateResult(
                ch.id, localVersion = probe.version, action = "failed",
                message = "查询官方最新版失败（下轮重试）：${brief(e)}"
            )
        }
        if (remote.isBlank() || ToolVersions.normalize(remote).isEmpty()) {
            return ToolAutoUpdateResult(
                ch.id, localVersion = probe.version, action = "failed",
                message = "官方最新版号异常（『$remote』），本轮跳过更新"
            )
        }
        if (!ToolVersions.isNewer(remote, probe.version)) {
            return ToolAutoUpdateResult(
                ch.id, localVersion = probe.version, remoteVersion = remote,
                action = "up-to-date", message = "已是最新版"
            )
        }
        return try {
            val outcome = ch.performUpdate()
            val newVer = outcome.newVersion.ifBlank { remote }
            ToolAutoUpdateResult(
                ch.id, localVersion = probe.version, remoteVersion = remote,
                action = "updated",
                message = "已自动更新：${probe.version} → $newVer${outcome.note}"
            )
        } catch (e: Exception) {
            ToolAutoUpdateResult(
                ch.id, localVersion = probe.version, remoteVersion = remote,
                action = "failed", message = "更新失败（下轮重试）：${brief(e)}"
            )
        }
    }

    /** 非托管来源的跳过原因：点名来源种类，说明「那是你自己的副本、应用不擅自替换」 */
    private fun skipReason(ch: ToolChannel, source: String): String = when (source) {
        "manual" -> "当前为手动路径版本，未纳入自动更新"
        "path" -> "当前为系统 PATH 上的版本，未纳入自动更新"
        "system" -> "当前为系统安装版本，未纳入自动更新"
        else -> "当前来源（$source）非应用托管，未纳入自动更新"
    }

    /** 异常文案收敛：单行、限长，避免把长堆栈/多行输出塞进状态 */
    private fun brief(e: Exception): String =
        (e.message ?: e.javaClass.simpleName).replace(Regex("\\s+"), " ").trim().take(200)

    companion object {
        /** 首检延迟：避开启动高峰，也给用户留出先改设置（关开关/填代理）的窗口 */
        const val FIRST_CHECK_DELAY_MS = 60_000L

        /** 检查周期：24 小时 */
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

        /** 查 GitHub 官方最新 Release 的 tag_name（走工具安装代理；失败抛异常由调用方记状态） */
        fun githubLatestTag(repo: String, toolProxy: String): String {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "YunGet-Web")
                .build()
            try {
                InstallHttp.apiClient(toolProxy).newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("GitHub API HTTP ${resp.code}${InstallHttp.PROXY_HINT}")
                    }
                    val tag = runCatching { JSONObject(text).optString("tag_name") }.getOrDefault("")
                    if (tag.isBlank()) throw IllegalStateException("GitHub API 响应缺少 tag_name")
                    return tag
                }
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("${e.message}${InstallHttp.PROXY_HINT}", e)
            }
        }

        /** 生产渠道组装：三家各自的 probe / fetchRemote / performUpdate 落点 */
        fun defaultChannels(youtube: YouTubeService, gopeed: GopeedService): List<ToolChannel> = listOf(
            ToolChannel(
                id = "yt-dlp",
                probe = {
                    val st = youtube.ytdlpStatus()
                    ToolChannel.Probe(
                        installed = st.available,
                        managed = st.source == "app",
                        version = st.version,
                        source = st.source
                    )
                },
                fetchRemote = { proxy -> githubLatestTag("yt-dlp/yt-dlp", proxy) },
                performUpdate = {
                    // 复用现有「更新」路径：force 重下官方最新版到托管目录
                    val st = youtube.installYtdlp(force = true)
                    ToolChannel.UpdateOutcome(st.version)
                }
            ),
            ToolChannel(
                id = "deno",
                probe = {
                    val st = youtube.denoStatus()
                    ToolChannel.Probe(
                        installed = st.available,
                        managed = st.source == "app",
                        version = st.version,
                        source = st.source
                    )
                },
                fetchRemote = { proxy -> githubLatestTag("denoland/deno", proxy) },
                performUpdate = {
                    val st = youtube.installDeno(force = true)
                    ToolChannel.UpdateOutcome(st.version)
                }
            ),
            ToolChannel(
                id = "gopeed",
                probe = {
                    val st = gopeed.status()
                    // Gopeed 内核只有应用托管一种来源：装了即 managed
                    ToolChannel.Probe(
                        installed = st.installed,
                        managed = st.installed,
                        version = st.version,
                        source = if (st.installed) "app" else ""
                    )
                },
                fetchRemote = { proxy -> githubLatestTag("GopeedLab/gopeed", proxy) },
                performUpdate = {
                    // install() 内部会先停引擎（运行中的 exe 无法覆盖）；
                    // 更新前在运行的，装完自动拉回原状态，别把磁力引擎悄悄停掉
                    val wasRunning = gopeed.isRunning()
                    gopeed.install()
                    var note = ""
                    if (wasRunning) {
                        note = runCatching { gopeed.start() }
                            .fold(
                                onSuccess = { "（引擎已自动重启）" },
                                onFailure = { "（引擎自动重启失败，请到设置页手动启动：${it.message}）" }
                            )
                    }
                    ToolChannel.UpdateOutcome(gopeed.status().version, note)
                }
            )
        )
    }
}
