/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.ui

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dancecoach.app.R
import com.dancecoach.app.domain.GestureRecognizer
import com.dancecoach.app.domain.Landmark
import com.dancecoach.app.ui.TeachingController

/**
 * 投屏第二屏（电视）画面。
 *
 * 渲染：老师视频（SurfaceView，由 TeachingActivity 把 ExoPlayer 输出切到此处）、
 * 学生摄像头预览（TextureView，由 CameraManager 双写）、实时骨架、分数、提示、进度。
 */
class CastPresentation(context: Context, display: Display) : Presentation(context, display) {

    lateinit var teacherSurface: SurfaceView
    lateinit var studentTexture: TextureView
    lateinit var skeletonView: SkeletonOverlayView
    lateinit var scoreText: TextView
    lateinit var hintText: TextView
    lateinit var segmentProgress: ProgressBar
    lateinit var studentProgress: ProgressBar
    lateinit var teacherContainer: FrameLayout
    lateinit var studentContainer: FrameLayout
    private lateinit var segmentProgressContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cast_presentation)

        teacherSurface = findViewById(R.id.castTeacherSurface)
        studentTexture = findViewById(R.id.castStudentTexture)
        skeletonView = findViewById(R.id.castSkeleton)
        scoreText = findViewById(R.id.castScoreText)
        hintText = findViewById(R.id.castHintText)
        segmentProgress = findViewById(R.id.castSegmentProgress)
        studentProgress = findViewById(R.id.castStudentProgress)
        teacherContainer = findViewById(R.id.castTeacherContainer)
        studentContainer = findViewById(R.id.castStudentContainer)
        segmentProgressContainer = findViewById(R.id.castSegmentProgressContainer)
    }

    // ═══ 同步方法（由 TeachingActivity 调用） ═══

    fun updateSkeleton(pose: List<Landmark>?, mirror: Boolean) {
        skeletonView.updatePose(pose, mirror)
    }

    fun setActiveGesture(g: GestureRecognizer.Gesture) {
        skeletonView.setActiveGesture(g)
    }

    fun updateScore(text: String) {
        scoreText.text = text
    }

    fun updateHint(text: String) {
        hintText.text = text
    }

    fun updateTeacherProgress(pct: Int) {
        segmentProgress.progress = pct.coerceIn(0, 100)
    }

    fun updateStudentProgress(pct: Int) {
        // 进度条已移除：布局 castStudentProgress 默认 gone，这里只更新值不显示
        studentProgress.progress = pct.coerceIn(0, 100)
    }

    fun hideStudentProgress() {
        studentProgress.visibility = View.GONE
    }

    fun setActiveSide(isTeacher: Boolean) {
        val border = ContextCompat.getDrawable(context, R.drawable.border_active)
        if (isTeacher) {
            teacherContainer.background = border
            studentContainer.setBackgroundColor(Color.BLACK)
        } else {
            teacherContainer.setBackgroundColor(Color.BLACK)
            studentContainer.background = border
        }
    }

    fun updateSegmentBlocks(statuses: List<TeachingController.SegmentStatus>) {
        segmentProgressContainer.removeAllViews()
        val ctx = context
        for (status in statuses) {
            val block = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f
                ).apply {
                    marginStart = 2
                    marginEnd = 2
                }
                background = ContextCompat.getDrawable(
                    ctx,
                    when (status) {
                        TeachingController.SegmentStatus.COMPLETED -> R.drawable.seg_completed
                        TeachingController.SegmentStatus.CURRENT -> R.drawable.seg_current
                        TeachingController.SegmentStatus.PENDING -> R.drawable.seg_pending
                        TeachingController.SegmentStatus.FAILED -> R.drawable.seg_failed
                    }
                )
            }
            segmentProgressContainer.addView(block)
        }
    }

    override fun onDisplayRemoved() {
        super.onDisplayRemoved()
        // 显示被移除（如电视断开），由 CastManager 负责清理
    }
}
