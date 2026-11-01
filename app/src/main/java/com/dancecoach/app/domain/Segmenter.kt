/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 动作拆解算法：运动能量谷检测（自适应阈值）
 *
 * 舞蹈动作的天然边界是"运动停顿点"——身体从动→静→动的转折处。
 * 通过计算每帧的运动能量（所有关键点的位移总量），能量曲线的局部最小值就是动作边界。
 */
object Segmenter {

    private const val MIN_SEGMENT_MS = 2000L
    private const val MAX_SEGMENT_MS = 8000L
    private const val SMOOTH_WINDOW = 2 // 单侧窗口，实际窗口 = 2*2+1 = 5帧
    private const val PERCENTILE = 30
    private const val THRESHOLD_MIN = 0.10f
    private const val THRESHOLD_MAX = 0.35f

    fun segment(seq: PoseSequence): SegmentedDance {
        val frames = seq.frames
        if (frames.size < 2) {
            // 视频太短，整段作为一个原子动作
            val seg = DanceSegment(
                0, 0, seq.durationMs,
                frames.firstOrNull() ?: PoseFrame(0, emptyList()),
                frames.lastOrNull() ?: PoseFrame(seq.durationMs, emptyList()),
                0f, "简单"
            )
            return SegmentedDance(listOf(seg))
        }

        // 第1步：计算每帧运动能量（用归一化坐标）
        val energies = computeEnergies(frames)

        // 第2步：平滑（5帧窗口均值）
        val smoothed = smooth(energies)

        // 第3步：归一化
        val maxE = smoothed.maxOrNull() ?: 1f
        val norm: List<Float> = if (maxE > 1e-6f) smoothed.map { it / maxE } else smoothed.toList()

        // 第4步：自适应阈值
        val threshold = computeAdaptiveThreshold(norm)

        // 第5步：找候选分割点
        val candidates = findCandidates(norm, threshold, frames)

        // 第6步：约束过滤
        val splitPoints = applyConstraints(candidates, frames, seq.durationMs)

        // 第7步：生成段 + 计算难度
        val segments = buildSegments(splitPoints, frames, norm, seq)

        return SegmentedDance(segments)
    }

    /** 计算每帧运动能量：归一化坐标的帧间位移平方和 */
    private fun computeEnergies(frames: List<PoseFrame>): FloatArray {
        val n = frames.size
        val energies = FloatArray(n)
        val canonicals = frames.map { PoseUtils.canonical(it.landmarks, false) }

        for (i in 1 until n) {
            val prev = canonicals[i - 1] ?: continue
            val curr = canonicals[i] ?: continue

            var sum = 0f
            for ((type, p) in curr) {
                val q = prev[type] ?: continue
                val dx = p.first - q.first
                val dy = p.second - q.second
                sum += dx * dx + dy * dy
            }
            energies[i] = sum
        }
        energies[0] = energies.getOrElse(1) { 0f }
        return energies
    }

    /** 5帧窗口均值平滑 */
    private fun smooth(energies: FloatArray): FloatArray {
        val n = energies.size
        val out = FloatArray(n)
        for (i in 0 until n) {
            var sum = 0f
            var count = 0
            for (j in max(0, i - SMOOTH_WINDOW)..min(n - 1, i + SMOOTH_WINDOW)) {
                sum += energies[j]
                count++
            }
            out[i] = sum / count
        }
        return out
    }

    /** 取30分位数作为阈值，保底 [0.10, 0.35] */
    private fun computeAdaptiveThreshold(norm: List<Float>): Float {
        val sorted = norm.sorted()
        if (sorted.isEmpty()) return THRESHOLD_MIN
        val idx = (sorted.size * PERCENTILE / 100).coerceIn(0, sorted.size - 1)
        val threshold = sorted[idx]
        return threshold.coerceIn(THRESHOLD_MIN, THRESHOLD_MAX)
    }

    /** 找候选分割点：低于阈值且在±3帧窗口内是局部最小值 */
    private fun findCandidates(
        norm: List<Float>,
        threshold: Float,
        frames: List<PoseFrame>
    ): MutableList<Int> {
        val candidates = mutableListOf<Int>()
        val window = 3

        for (i in 1 until norm.size - 1) {
            if (norm[i] >= threshold) continue

            var isMin = true
            for (j in max(0, i - window)..min(norm.size - 1, i + window)) {
                if (j != i && norm[j] < norm[i]) {
                    isMin = false
                    break
                }
            }
            if (isMin) candidates.add(i)
        }
        return candidates
    }

    /** 约束过滤：最小段长合并、最大段长强制分割 */
    private fun applyConstraints(
        candidates: MutableList<Int>,
        frames: List<PoseFrame>,
        durationMs: Long
    ): List<Int> {
        if (frames.isEmpty()) return listOf(0)

        val lastFrame = frames.size - 1

        // 首尾固定为分割点
        val points = mutableListOf(0)
        points.addAll(candidates)
        if (points.last() != lastFrame) points.add(lastFrame)
        points.sort()

        // Pass 1: 合并距离过近的分割点（保留能量更低的）
        val minFrameGap = if (frames.size > 1) {
            val frameIntervalMs = frames[1].t - frames[0].t
            (MIN_SEGMENT_MS / frameIntervalMs).toInt().coerceAtLeast(1)
        } else 1

        val merged = mutableListOf<Int>()
        for (p in points) {
            if (merged.isEmpty() || p - merged.last() >= minFrameGap) {
                merged.add(p)
            }
            // 否则跳过（丢弃太近的分割点，相当于合并）
        }
        if (merged.last() != lastFrame) merged.add(lastFrame)

        // Pass 2: 确保每段 >= MIN_SEGMENT_MS，不满足则与下一段合并
        val result = mutableListOf(merged[0])
        var i = 0
        while (i < merged.size - 1) {
            val start = merged[i]
            var end = merged[i + 1]
            var segMs = frames[end].t - frames[start].t

            // 段太短，向后合并直到满足最小段长或到达末尾
            while (segMs < MIN_SEGMENT_MS && i < merged.size - 2) {
                i++
                end = merged[i + 1]
                segMs = frames[end].t - frames[start].t
            }
            result.add(end)
            i++
        }

        // Pass 3: 最大段长强制分割
        val finalPoints = mutableListOf<Int>()
        for (j in 0 until result.size - 1) {
            val start = result[j]
            val end = result[j + 1]
            finalPoints.add(start)
            val segMs = frames[end].t - frames[start].t
            if (segMs > MAX_SEGMENT_MS) {
                // 在段内找到能量最低点强制分割
                val splitIdx = findBestSplitInRange(start, end, frames)
                if (splitIdx > start && splitIdx < end) {
                    finalPoints.add(splitIdx)
                    // 如果分割后仍然超过 MAX，再分一次
                    if (frames[end].t - frames[splitIdx].t > MAX_SEGMENT_MS) {
                        val split2 = findBestSplitInRange(splitIdx, end, frames)
                        if (split2 > splitIdx && split2 < end) {
                            finalPoints.add(split2)
                        }
                    }
                }
            }
        }
        finalPoints.add(lastFrame)
        return finalPoints.distinct().sorted()
    }

    /** 在 [start, end] 范围内找到运动能量最低的帧作为分割点 */
    private fun findBestSplitInRange(start: Int, end: Int, frames: List<PoseFrame>): Int {
        if (end - start < 2) return start

        val canonicals = frames.map { PoseUtils.canonical(it.landmarks, false) }
        var minIdx = start + 1
        var minEnergy = Float.MAX_VALUE

        for (i in (start + 1) until end) {
            val prev = canonicals[i - 1] ?: continue
            val curr = canonicals[i] ?: continue
            var sum = 0f
            for ((type, p) in curr) {
                val q = prev[type] ?: continue
                sum += hypot(p.first - q.first, p.second - q.second)
            }
            if (sum < minEnergy) {
                minEnergy = sum
                minIdx = i
            }
        }
        return minIdx
    }

    /** 生成分段 + 计算难度标签 */
    private fun buildSegments(
        splitPoints: List<Int>,
        frames: List<PoseFrame>,
        norm: List<Float>,
        seq: PoseSequence
    ): List<DanceSegment> {
        val segments = mutableListOf<DanceSegment>()
        for (i in 0 until splitPoints.size - 1) {
            val startIdx = splitPoints[i]
            val endIdx = splitPoints[i + 1]
            val startMs = frames[startIdx].t
            val endMs = frames[endIdx].t

            // 计算段内平均能量作为难度
            var avgE = 0f
            var count = 0
            for (j in startIdx..endIdx) {
                if (j < norm.size) {
                    avgE += norm[j]
                    count++
                }
            }
            if (count > 0) avgE /= count
            val difficulty = avgE.coerceIn(0f, 1f)
            val label = when {
                difficulty < 0.1f -> "简单"
                difficulty < 0.3f -> "中等"
                else -> "较难"
            }

            segments.add(
                DanceSegment(
                    index = i,
                    startMs = startMs,
                    endMs = endMs,
                    startFrame = frames[startIdx],
                    endFrame = frames[endIdx],
                    difficulty = difficulty,
                    label = label
                )
            )
        }
        return segments
    }
}
