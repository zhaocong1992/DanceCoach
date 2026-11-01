/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain

import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.hypot

object PoseUtils {

    /** 需要比对的骨骼（成对关键点 + 中文名） */
    data class Bone(val a: Int, val b: Int, val nameZh: String)

    val BONES: List<Bone> = listOf(
        Bone(PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER, "肩膀"),
        Bone(PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, "左大臂"),
        Bone(PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST, "左小臂"),
        Bone(PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, "右大臂"),
        Bone(PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST, "右小臂"),
        Bone(PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP, "髋部"),
        Bone(PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP, "左躯干"),
        Bone(PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP, "右躯干"),
        Bone(PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, "左大腿"),
        Bone(PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE, "左小腿"),
        Bone(PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, "右大腿"),
        Bone(PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE, "右小腿")
    )

    /**
     * ML Kit 的 Pose -> 我们的 Landmark 列表。
     * 过滤掉 inFrameLikelihood 过低的废点：ML Kit 对 33 个关键点都会返回坐标，
     * 即使没检测到（s≈0）也会给一个飞散位置；这些点会污染 canonical/compare 与显示。
     *
     * 但阈值不能太高——摄像头在低光/运动下整体置信度偏低，阈值 0.3 会把几乎所有
     * 关节一起滤空，导致骨架完全不显示（之前版本的回归）。这里用较宽松的阈值(0.2)，
     * 并且当过滤后剩余关节 < 4 时整体兜底返回（含低分点），确保骨架始终可见。
     */
    fun poseToLandmarks(pose: Pose, minScore: Float = 0.05f): List<Landmark> {
        val out = mutableListOf<Landmark>()
        for (lm in pose.allPoseLandmarks) {
            val t = lm.landmarkType
            // canonical() 依赖肩/髋/鼻来定位中心与归一化；这些核心点即使 likelihood 偏低
            // 也必须保留，否则 canonical 返回 null -> 骨架完全不显示、相似度恒为 0。
            // 只有非核心点才按阈值丢弃，去掉 ML Kit 对未检测到关节给出的飞散坐标。
            val isCore = t == PoseLandmark.LEFT_SHOULDER || t == PoseLandmark.RIGHT_SHOULDER ||
                    t == PoseLandmark.LEFT_HIP || t == PoseLandmark.RIGHT_HIP ||
                    t == PoseLandmark.NOSE
            if (!isCore && lm.inFrameLikelihood < minScore) continue
            out.add(Landmark(t, lm.position.x, lm.position.y, lm.inFrameLikelihood))
        }
        return out
    }

    /**
     * 归一化到“标准骨架空间”：
     *  - 原点 = 双肩+双髋中心
     *  - 缩放 = 肩到髋的距离（躯干长度）
     * 这样可消除身高、站位、离摄像头远近的差异。
     * mirror=true 时水平翻转（跟练镜像场景用）。
     * 返回 type -> (nx, ny)，ny 向上为负。
     */
    fun canonical(pose: List<Landmark>, mirror: Boolean): Map<Int, Pair<Float, Float>>? {
        val ls = pose.firstOrNull { it.type == PoseLandmark.LEFT_SHOULDER } ?: return null
        val rs = pose.firstOrNull { it.type == PoseLandmark.RIGHT_SHOULDER } ?: return null
        val lh = pose.firstOrNull { it.type == PoseLandmark.LEFT_HIP } ?: return null
        val rh = pose.firstOrNull { it.type == PoseLandmark.RIGHT_HIP } ?: return null

        val cx = (ls.x + rs.x + lh.x + rh.x) / 4f
        val cy = (ls.y + rs.y + lh.y + rh.y) / 4f
        val scale = maxOf(1e-3f, hypot((ls.x - lh.x).toDouble(), (ls.y - lh.y).toDouble()).toFloat())

        val map = LinkedHashMap<Int, Pair<Float, Float>>()
        for (lm in pose) {
            var nx = (lm.x - cx) / scale
            val ny = (lm.y - cy) / scale
            if (mirror) nx = -nx
            map[lm.type] = Pair(nx, ny)
        }
        return map
    }
}
