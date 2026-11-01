/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain

import kotlin.math.hypot
import kotlin.math.max

object ComparisonEngine {

    data class Result(
        val score: Float,          // 0..100
        val worstBone: String?,    // 当前最不达标部位
        val hint: String           // 给用户的提示语
    )

    /**
     * 比较老师骨架与用户骨架。
     * 采用“骨骼方向向量”的余弦相似度（对旋转/缩放稳健），再去均值得到 0..100 分。
     *
     * 注意：自拍视频常常被手机以“镜像”方式存储，而实时摄像头送进网络的是未镜像的原始帧，
     * 二者左右约定可能相反。方向向量相似度对整体旋转不敏感，但对左右镜像极度敏感——
     * 只要左右相反，所有肢体方向向量全部反向，分数直接跌到接近 0（即便人其实跳得一样）。
     * 因此这里同时用“正常”与“水平镜像”两种约定各算一次，取分数更高者，
     * 保证“跟着自己录的视频跳”也能获得合理高分。
     */
    fun compare(
        teacher: List<Landmark>?,
        user: List<Landmark>?,
        mirrorTeacher: Boolean,
        mirrorUser: Boolean
    ): Result {
        if (teacher == null || user == null) {
            return Result(0f, null, "等待中…")
        }
        val normal = compareOne(teacher, user, mirrorTeacher, mirrorUser)
        val mirrored = compareOne(teacher, user, !mirrorTeacher, mirrorUser)
        return if (mirrored.score > normal.score) mirrored else normal
    }

    private fun compareOne(
        teacher: List<Landmark>,
        user: List<Landmark>,
        mirrorTeacher: Boolean,
        mirrorUser: Boolean
    ): Result {
        val ct = PoseUtils.canonical(teacher, mirrorTeacher)
            ?: return Result(0f, null, "老师骨架不完整")
        val cu = PoseUtils.canonical(user, mirrorUser)
            ?: return Result(0f, null, "没看清你的动作")

        var sum = 0f
        var n = 0
        var worst: String? = null
        var worstScore = Float.MAX_VALUE

        for (b in PoseUtils.BONES) {
            val ta = ct[b.a] ?: continue
            val tb = ct[b.b] ?: continue
            val ua = cu[b.a] ?: continue
            val ub = cu[b.b] ?: continue

            val vTx = tb.first - ta.first
            val vTy = tb.second - ta.second
            val vUx = ub.first - ua.first
            val vUy = ub.second - ua.second

            val lt = hypot(vTx, vTy)
            val lu = hypot(vUx, vUy)
            if (lt < 0.05f || lu < 0.05f) continue // 退化的骨骼，跳过

            val dot = (vTx * vUx + vTy * vUy) / (lt * lu)
            val s = max(0f, dot) // 0..1
            sum += s
            n++
            if (s < worstScore) {
                worstScore = s
                worst = b.nameZh
            }
        }

        val score = if (n == 0) 0f else (sum / n) * 100f
        val hint = if (worst == null) "很好，继续！" else "注意：${worst}再到位一点"
        return Result(score, worst, hint)
    }
}
