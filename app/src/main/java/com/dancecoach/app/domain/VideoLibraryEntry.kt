/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain

import org.json.JSONArray
import org.json.JSONObject

/** 分析状态 */
enum class AnalysisStatus {
    NONE,       // 未分析
    QUEUED,     // 排队中
    ANALYZING,  // 分析中
    ANALYZED,   // 已分析（合格）
    WARNING,    // 已分析（有警告）
    FAILED      // 分析失败/不合格
}

/** 视频库中的一条记录（持久化到本地 JSON） */
data class VideoLibraryEntry(
    val id: String,              // 缓存ID = MD5(uri + fileSize + lastModified)
    val displayName: String,     // 显示名称（文件名去掉扩展名）
    val uri: String,             // 视频 URI（content:// 或 file://）
    val fileSize: Long,          // 文件大小（bytes，用于校验缓存有效性）
    val lastModified: Long,      // 文件最后修改时间（用于校验）
    val durationMs: Long,        // 视频时长
    val segmentCount: Int,       // 拆解出的段数
    val difficulty: String,      // 整体难度标签
    val thumbnailPath: String?,  // 缩略图路径（本地缓存）
    val lastPlayedAt: Long,      // 最后一次练习的时间戳
    val totalRounds: Int,        // 累计练习轮次
    val bestScore: Float,        // 历史最高平均分（0..100，-1表示未完成过）
    val cacheDir: String,        // 缓存目录路径（相对于 app cache dir）
    val scoreHistory: List<Float> = emptyList(),  // 历次得分列表
    val analyzed: Boolean = false, // 是否已完成骨架分析（向后兼容）
    // 动作预审相关
    val analysisStatus: AnalysisStatus = AnalysisStatus.NONE,
    val analysisProgress: Int = 0,           // 0-100
    val actionQuality: Float = -1f,          // 0-100, -1=未分析
    val detectionRate: Float = 0f,           // 检测率 0-1
    val avgConfidence: Float = 0f,           // 平均置信度 0-1
    val validFrameCount: Int = 0,            // 有效帧数
    val analysisMessage: String? = null,     // 分析提示信息
    val sourceId: String = ""                // 来源标记：从市场下载的视频记录其市场 itemId（用于去重/闭环）
)

/** 整个视频库 */
data class VideoLibrary(
    val entries: MutableList<VideoLibraryEntry>  // 按最后播放时间降序
) {
    fun toJson(): JSONObject {
        val root = JSONObject()
        val arr = JSONArray()
        for (e in entries) {
            val o = JSONObject()
            o.put("id", e.id)
            o.put("displayName", e.displayName)
            o.put("uri", e.uri)
            o.put("fileSize", e.fileSize)
            o.put("lastModified", e.lastModified)
            o.put("durationMs", e.durationMs)
            o.put("segmentCount", e.segmentCount)
            o.put("difficulty", e.difficulty)
            o.put("thumbnailPath", e.thumbnailPath ?: "")
            o.put("lastPlayedAt", e.lastPlayedAt)
            o.put("totalRounds", e.totalRounds)
            o.put("bestScore", e.bestScore)
            o.put("cacheDir", e.cacheDir)
            // scoreHistory
            val shArr = JSONArray()
            for (s in e.scoreHistory) shArr.put(s)
            o.put("scoreHistory", shArr)
            o.put("analyzed", e.analyzed)
            o.put("analysisStatus", e.analysisStatus.name)
            o.put("analysisProgress", e.analysisProgress)
            o.put("actionQuality", e.actionQuality)
            o.put("detectionRate", e.detectionRate)
            o.put("avgConfidence", e.avgConfidence)
            o.put("validFrameCount", e.validFrameCount)
            o.put("analysisMessage", e.analysisMessage ?: "")
            o.put("sourceId", e.sourceId)
            arr.put(o)
        }
        root.put("entries", arr)
        return root
    }

    companion object {
        fun fromJson(obj: JSONObject): VideoLibrary {
            val entries = mutableListOf<VideoLibraryEntry>()
            val arr = obj.getJSONArray("entries")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries.add(
                    VideoLibraryEntry(
                        id = o.getString("id"),
                        displayName = o.getString("displayName"),
                        uri = o.getString("uri"),
                        fileSize = o.getLong("fileSize"),
                        lastModified = o.getLong("lastModified"),
                        durationMs = o.getLong("durationMs"),
                        segmentCount = o.getInt("segmentCount"),
                        difficulty = o.getString("difficulty"),
                        thumbnailPath = o.optString("thumbnailPath", "").ifEmpty { null },
                        lastPlayedAt = o.getLong("lastPlayedAt"),
                        totalRounds = o.optInt("totalRounds", 0),
                        bestScore = o.optDouble("bestScore", -1.0).toFloat(),
                        cacheDir = o.getString("cacheDir"),
                        scoreHistory = run {
                            val sh = o.optJSONArray("scoreHistory")
                            if (sh != null) {
                                (0 until sh.length()).map { sh.getDouble(it).toFloat() }
                            } else emptyList()
                        },
                        analyzed = o.optBoolean("analyzed", false),
                        analysisStatus = run {
                            val name = o.optString("analysisStatus", "")
                            if (name.isNotEmpty()) AnalysisStatus.valueOf(name) else AnalysisStatus.NONE
                        },
                        analysisProgress = o.optInt("analysisProgress", 0),
                        actionQuality = o.optDouble("actionQuality", -1.0).toFloat(),
                        detectionRate = o.optDouble("detectionRate", 0.0).toFloat(),
                        avgConfidence = o.optDouble("avgConfidence", 0.0).toFloat(),
                        validFrameCount = o.optInt("validFrameCount", 0),
                        analysisMessage = o.optString("analysisMessage", "").ifEmpty { null },
                        sourceId = o.optString("sourceId", "")
                    )
                )
            }
            entries.sortByDescending { it.lastPlayedAt }
            return VideoLibrary(entries)
        }
    }
}
