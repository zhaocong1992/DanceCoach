/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain

/** 一个原子动作段 */
data class DanceSegment(
    val index: Int,            // 第几段（0-based）
    val startMs: Long,         // 起始时间（毫秒）
    val endMs: Long,           // 结束时间（毫秒）
    val startFrame: PoseFrame, // 起始帧骨架
    val endFrame: PoseFrame,   // 末尾帧骨架（用户需要匹配的目标姿态）
    val difficulty: Float,     // 难度 0..1
    val label: String          // "简单" / "中等" / "较难"
)

/** 拆解后的分段舞蹈 */
data class SegmentedDance(
    val segments: List<DanceSegment>     // 拆解结果
) {
    companion object {
        /**
         * 按时间等分生成段落（实时模式下替代离线动作拆解）。
         * 每段长约 [segmentMs]，首尾完整覆盖整段视频。
         * 难度统一给“中等”，真实难度在实时跟跳时由打分曲线体现。
         */
        fun timeBased(durationMs: Long, segmentMs: Long = 5000L): SegmentedDance {
            if (durationMs <= 0) return SegmentedDance(emptyList())
            val segs = mutableListOf<DanceSegment>()
            var idx = 0
            var start = 0L
            while (start < durationMs) {
                val end = (start + segmentMs).coerceAtMost(durationMs)
                segs.add(
                    DanceSegment(
                        index = idx,
                        startMs = start,
                        endMs = end,
                        startFrame = PoseFrame(start, emptyList()),
                        endFrame = PoseFrame(end, emptyList()),
                        difficulty = 0.2f,
                        label = "中等"
                    )
                )
                start = end
                idx++
            }
            return SegmentedDance(segs)
        }
    }
}

/** 单段成绩记录 */
data class SegmentScore(
    val segmentIndex: Int,    // 段序号
    val score: Float,         // 得分 0..100
    val retries: Int,         // 重试次数
    val skipped: Boolean      // 是否跳过
)
