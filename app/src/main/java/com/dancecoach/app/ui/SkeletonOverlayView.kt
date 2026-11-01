/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.dancecoach.app.domain.GestureRecognizer
import com.dancecoach.app.domain.Landmark
import com.dancecoach.app.domain.PoseUtils
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.min

/**
 * 实时骨架叠加视图：在手势选择面板中显示用户当前的骨架。
 * 使用 canonical 坐标（躯干中心为原点，躯干长度为1），y 向下为正。
 */
class SkeletonOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var canonical: Map<Int, Pair<Float, Float>>? = null

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val highlightBonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFF9800.toInt()
    }

    private val highlightJointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFF9800.toInt()
    }

    // 调试模式：把最不到位的骨骼标红
    private val worstBonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 7f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFF1744.toInt()
    }

    private var activeGesture: GestureRecognizer.Gesture = GestureRecognizer.Gesture.NONE
    private var worstBoneName: String? = null
    // 显示用左右镜像（仅翻转水平轴，不影响竖直朝向，避免把人画倒）
    private var flipHorizontal: Boolean = false

    /**
     * 是否对 (nx, ny) 做轴转置后再绘制。
     *  - true（默认）：把 nx(水平) 画到屏幕 Y、ny(竖直) 画到屏幕 X。
     *    这是摄像头路径的补偿逻辑——摄像头送网的是横屏原始帧（站立的人被拍成
     *    横躺），转置后刚好把人画正。
     *  - false：nx→屏幕 X、ny→屏幕 Y（直接对齐）。用于“送来已是正立帧”的来源，
     *    例如动作预审页（PoseExtractor.detectLiveFrameAt 已用 uprightBitmap 物理转正）。
     *    若不关掉转置，正立帧会被画成横躺。
     */
    var transpose: Boolean = true

    /** 需要高亮的骨骼对（根据手势） */
    private val highlightBones: Set<Pair<Int, Int>>
        get() = when (activeGesture) {
            GestureRecognizer.Gesture.PREV -> setOf(
                PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
                PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST
            )
            GestureRecognizer.Gesture.NEXT -> setOf(
                PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
                PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST
            )
            GestureRecognizer.Gesture.REPEAT -> setOf(
                PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
                PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
                PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
                PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST
            )
            GestureRecognizer.Gesture.EXIT -> setOf(
                PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
                PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
                PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
                PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST
            )
            GestureRecognizer.Gesture.SWITCH_MODE -> setOf(
                PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
                PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
                PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
                PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST
            )
            else -> emptySet()
        }

    fun updatePose(pose: List<Landmark>?, mirror: Boolean) {
        flipHorizontal = mirror
        canonical = if (pose != null) PoseUtils.canonical(pose, false) else null
        invalidate()
    }

    fun setActiveGesture(g: GestureRecognizer.Gesture) {
        activeGesture = g
        invalidate()
    }

    /** 调试模式：设置当前最不到位的骨骼（中文名），null 表示不高亮 */
    fun setWorstBone(nameZh: String?) {
        worstBoneName = nameZh
        invalidate()
    }

    fun clear() {
        canonical = null
        activeGesture = GestureRecognizer.Gesture.NONE
        worstBoneName = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lm = canonical ?: return

        val cx = width / 2f
        val cy = height / 2f
        val scale = min(width, height) / 5f

        val highlight = highlightBones

        // 调试模式：最不到位的骨骼（标红）
        val worstPair = worstBoneName?.let { name ->
            PoseUtils.BONES.firstOrNull { it.nameZh == name }?.let { b -> b.a to b.b }
        }

        // 画骨骼线
        val flip = if (flipHorizontal) -1f else 1f
        for (bone in PoseUtils.BONES) {
            val a = lm[bone.a] ?: continue
            val b = lm[bone.b] ?: continue
            // 转置开关：true 时 nx→Y、ny→X（摄像头横屏帧补偿）；false 时 nx→X、ny→Y（正立帧）
            val aX = if (transpose) a.second else a.first
            val aY = if (transpose) a.first else a.second
            val bX = if (transpose) b.second else b.first
            val bY = if (transpose) b.first else b.second
            val ax = cx + flip * aX * scale
            val ay = cy + aY * scale
            val bx = cx + flip * bX * scale
            val by = cy + bY * scale

            val pair = bone.a to bone.b
            val pairRev = bone.b to bone.a
            if (worstPair != null && (pair == worstPair || pairRev == worstPair)) {
                canvas.drawLine(ax, ay, bx, by, worstBonePaint)
            } else if (highlight.contains(pair) || highlight.contains(pairRev)) {
                canvas.drawLine(ax, ay, bx, by, highlightBonePaint)
            } else {
                bonePaint.color = 0xAAFFFFFF.toInt()
                canvas.drawLine(ax, ay, bx, by, bonePaint)
            }
        }

        // 画关节点
        for ((type, point) in lm) {
            val pX = if (transpose) point.second else point.first
            val pY = if (transpose) point.first else point.second
            val px = cx + flip * pX * scale
            val py = cy + pY * scale

            val isHighlighted = when (activeGesture) {
                GestureRecognizer.Gesture.PREV -> type == PoseLandmark.LEFT_WRIST || type == PoseLandmark.LEFT_ELBOW
                GestureRecognizer.Gesture.NEXT -> type == PoseLandmark.RIGHT_WRIST || type == PoseLandmark.RIGHT_ELBOW
                GestureRecognizer.Gesture.REPEAT -> type == PoseLandmark.LEFT_WRIST || type == PoseLandmark.RIGHT_WRIST ||
                    type == PoseLandmark.LEFT_ELBOW || type == PoseLandmark.RIGHT_ELBOW
                GestureRecognizer.Gesture.EXIT -> type == PoseLandmark.LEFT_WRIST || type == PoseLandmark.RIGHT_WRIST
                GestureRecognizer.Gesture.SWITCH_MODE -> type == PoseLandmark.LEFT_WRIST || type == PoseLandmark.RIGHT_WRIST ||
                    type == PoseLandmark.LEFT_ELBOW || type == PoseLandmark.RIGHT_ELBOW
                else -> false
            }

            if (isHighlighted) {
                canvas.drawCircle(px, py, 6f, highlightJointPaint)
            } else {
                jointPaint.color = 0xCCFFFFFF.toInt()
                canvas.drawCircle(px, py, 4f, jointPaint)
            }
        }
    }
}
