package com.yunget.web.service

import org.json.JSONObject

/**
 * B站「合集」（ugc_season）的 web 层建模与 view 接口响应解析（纯函数，便于离线验证）。
 *
 * 上游 BilibiliParser 只解析单个视频、不建模合集（既定红线：上游文件不改），合集信息
 * 由 VideoService 在 web 层补查 `x/web-interface/view` 的 `data.ugc_season` 字段得到，
 * 本对象只负责把响应 JSON 拉平成集数列表，不做任何网络请求。
 *
 * 结构：ugc_season { id, title, sections: [ { title, episodes: [ { bvid, title,
 * arc: { duration, ... } } ] } ] }。多小节按 sections 顺序拉平、全局编号。
 */
object BilibiliSeason {

    data class Episode(
        /** 全局序号（从 1 起） */
        val index: Int,
        val bvid: String,
        val title: String,
        /** 秒；取不到为 0 */
        val duration: Int
    )

    data class Info(
        val id: Long,
        val title: String,
        /** 落盘文件夹名（[VideoNaming.folderName] 清洗后，单层目录名） */
        val folder: String,
        val episodes: List<Episode>,
        /** 当前 bvid 在合集中的序号（1 起）；不在列表中为 0 */
        val currentIndex: Int
    )

    /**
     * 解析 view 接口原始响应。不是合集（code 非 0 / 无 ugc_season / 字段缺失 /
     * 集数 < 2）返回 null，并通过 [log] 留一行降级原因，调用方静默按普通单视频处理。
     */
    fun parse(body: String, currentBvid: String, log: (String) -> Unit = {}): Info? {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            log("view 响应不是合法 JSON：${e.message}")
            return null
        }
        if (root.optInt("code") != 0) {
            log("view 接口 code=${root.optInt("code")} msg=${root.optString("message")}")
            return null
        }
        val season = root.optJSONObject("data")?.optJSONObject("ugc_season")
        if (season == null) {
            log("无 ugc_season 字段，不是合集视频")
            return null
        }
        val sections = season.optJSONArray("sections")
        if (sections == null) {
            log("ugc_season 缺 sections 字段")
            return null
        }
        val episodes = mutableListOf<Episode>()
        var next = 1
        for (si in 0 until sections.length()) {
            val eps = sections.optJSONObject(si)?.optJSONArray("episodes") ?: continue
            for (ei in 0 until eps.length()) {
                val ep = eps.optJSONObject(ei) ?: continue
                val bvid = ep.optString("bvid")
                    .ifBlank { ep.optJSONObject("arc")?.optString("bvid").orEmpty() }
                if (bvid.isBlank()) continue
                episodes += Episode(
                    index = next++,
                    bvid = bvid,
                    title = ep.optString("title"),
                    duration = ep.optJSONObject("arc")?.optInt("duration") ?: 0
                )
            }
        }
        // 只有一集也算不上「列表」，与单视频无异，不单独展示合集区块
        if (episodes.size < 2) {
            log("合集有效集数 ${episodes.size} < 2，不按合集展示")
            return null
        }
        val title = season.optString("title")
        return Info(
            id = season.optLong("id"),
            title = title,
            folder = VideoNaming.folderName(title),
            episodes = episodes,
            currentIndex = episodes.firstOrNull {
                it.bvid.equals(currentBvid, ignoreCase = true)
            }?.index ?: 0
        )
    }
}
