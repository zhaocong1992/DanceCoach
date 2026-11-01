/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.dancecoach.app.domain.DanceSegment
import com.dancecoach.app.domain.GestureRecognizer
import com.dancecoach.app.domain.SegmentScore

/**
 * 教学状态机（v3.5 — 老师示范 + 学生跟跳 + 全时序比对 + 手势导航）
 *
 * 状态：IDLE → TEACHER_DEMO → STUDENT_DANCE → MATCHED/REPLAY → COMPLETED → GESTURE_SELECT → 导航
 *
 * TEACHER_DEMO：老师视频播放一遍该段，绿色框在老师侧
 * STUDENT_DANCE：学生跟跳一遍，绿色框移到学生侧，收集用户骨架序列
 * COMPLETED → GESTURE_SELECT：等待手势导航（上一个/下一个/重练/退出）
 */
class TeachingController(private val callback: Callback) {

    enum class State {
        IDLE, TEACHER_DEMO, STUDENT_DANCE, MATCHED, REPLAY, SKIPPED, COMPLETED, GESTURE_SELECT
    }

    enum class SegmentStatus {
        PENDING, CURRENT, FAILED, COMPLETED
    }

    interface Callback {
        fun onStateChanged(state: State)
        fun onSegmentStatusChanged(statuses: List<SegmentStatus>)
        fun onScoreUpdate(score: Float, hint: String)
        fun onActiveSideChanged(isTeacher: Boolean)
        fun onPlaySegment(segment: DanceSegment)
        fun onPauseVideo()
        fun onStudentDanceTimeUp()
        fun onMatched(score: Float)
        fun onReplay(retryCount: Int) {}
        fun onSkipped()
        fun onRoundCompleted(averageScore: Float, scores: List<SegmentScore>)
        fun onRoundStarted(round: Int) {}
        fun onGestureSelectStarted() {}
        fun onNavigatePrev()
        fun onNavigateNext()
        fun onNavigateExit()
    }

    var state: State = State.IDLE
        private set

    var matchThreshold: Float = 0.7f

    private var segments: List<DanceSegment> = emptyList()
    private var currentIndex = 0
    var round = 1
        private set
    private var retryCount = 0

    // GESTURE_SELECT：无超时，等待用户手动选择
    var gestureSelectStartMs = 0L
        private set

    // 段状态
    private var segmentStatuses: MutableList<SegmentStatus> = mutableListOf()

    // STUDENT_DANCE 状态
    var studentDanceStartMs = 0L
        private set

    // 评分
    private val roundScores = mutableListOf<SegmentScore>()

    private val handler = Handler(Looper.getMainLooper())

    // ═══ 公开方法 ═══

    fun start(segs: List<DanceSegment>) {
        if (segs.isEmpty()) return
        segments = segs
        currentIndex = 0
        round = 1
        retryCount = 0
        roundScores.clear()
        segmentStatuses = MutableList(segs.size) { SegmentStatus.PENDING }
        callback.onRoundStarted(round)
        enterTeacherDemo()
    }

    /** TEACHER_DEMO 状态下视频播放到达段末尾时调用 */
    fun onTeacherDemoEnded() {
        if (state != State.TEACHER_DEMO) return
        callback.onPauseVideo()
        enterStudentDance()
    }

    /** STUDENT_DANCE 状态下每 tick 调用，仅更新 UI 显示 */
    fun onStudentDanceTick(score: Float, hint: String) {
        if (state != State.STUDENT_DANCE) return
        callback.onScoreUpdate(score, hint)
    }

    /** MainActivity 完成全序列比对后调用，提交最终得分 */
    fun submitStudentScore(score: Float) {
        if (state != State.STUDENT_DANCE) return
        if (score >= matchThreshold) {
            segmentStatuses[currentIndex] = SegmentStatus.COMPLETED
            callback.onSegmentStatusChanged(segmentStatuses.toList())
            val discount = maxOf(0.5f, 1f - 0.05f * retryCount)
            val segScore = score * discount
            roundScores.add(SegmentScore(currentIndex, segScore, retryCount, false))
            callback.onMatched(segScore)
            state = State.MATCHED
            callback.onStateChanged(State.MATCHED)
            handler.postDelayed({ nextSegment() }, 1000)
        } else {
            enterReplay()
        }
    }

    /** GESTURE_SELECT 状态下，手势确认后调用 */
    fun selectGesture(gesture: GestureRecognizer.Gesture) {
        if (state != State.GESTURE_SELECT) return
        handler.removeCallbacksAndMessages(null)
        when (gesture) {
            GestureRecognizer.Gesture.PREV -> {
                callback.onNavigatePrev()
                state = State.IDLE
                callback.onStateChanged(State.IDLE)
            }
            GestureRecognizer.Gesture.NEXT -> {
                callback.onNavigateNext()
                state = State.IDLE
                callback.onStateChanged(State.IDLE)
            }
            GestureRecognizer.Gesture.REPEAT -> {
                round++
                currentIndex = 0
                retryCount = 0
                roundScores.clear()
                segmentStatuses = MutableList(segments.size) { SegmentStatus.PENDING }
                callback.onRoundStarted(round)
                enterTeacherDemo()
            }
            GestureRecognizer.Gesture.EXIT -> {
                callback.onNavigateExit()
                state = State.IDLE
                callback.onStateChanged(State.IDLE)
            }
            else -> {}
        }
    }

    /** 外部切视频/重新开始前，把状态机复位到 IDLE，避免旧状态（如 GESTURE_SELECT）干扰新模式。 */
    fun resetToIdle() {
        handler.removeCallbacksAndMessages(null)
        state = State.IDLE
    }

    /** 外部（如跟着跳完成后）强制进入手势选择状态。 */
    fun forceGestureSelect() {
        handler.removeCallbacksAndMessages(null)
        state = State.GESTURE_SELECT
        callback.onStateChanged(State.GESTURE_SELECT)
        callback.onGestureSelectStarted()
        gestureSelectStartMs = SystemClock.elapsedRealtime()
    }

    fun pause() {
        handler.removeCallbacksAndMessages(null)
    }

    fun resume() {
        when (state) {
            State.STUDENT_DANCE -> {
                val seg = currentSegment ?: return
                val elapsed = SystemClock.elapsedRealtime() - studentDanceStartMs
                val remaining = (seg.endMs - seg.startMs) - elapsed
                if (remaining > 0) {
                    handler.postDelayed({
                        if (state != State.STUDENT_DANCE) return@postDelayed
                        callback.onStudentDanceTimeUp()
                    }, remaining)
                } else {
                    callback.onStudentDanceTimeUp()
                }
            }
            State.TEACHER_DEMO -> { /* MainActivity 恢复 exoPlayer 即可 */ }
            else -> { }
        }
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
    }

    val currentSegment: DanceSegment?
        get() = segments.getOrNull(currentIndex)

    // ═══ 状态流转 ═══

    private fun enterTeacherDemo() {
        state = State.TEACHER_DEMO
        callback.onStateChanged(State.TEACHER_DEMO)
        callback.onActiveSideChanged(true)
        segmentStatuses[currentIndex] = SegmentStatus.CURRENT
        callback.onSegmentStatusChanged(segmentStatuses.toList())
        val seg = currentSegment ?: return
        callback.onPlaySegment(seg)
    }

    private fun enterStudentDance() {
        state = State.STUDENT_DANCE
        callback.onStateChanged(State.STUDENT_DANCE)
        callback.onActiveSideChanged(false)
        callback.onPauseVideo()
        studentDanceStartMs = SystemClock.elapsedRealtime()
        val seg = currentSegment ?: return
        val duration = seg.endMs - seg.startMs
        handler.postDelayed({
            if (state != State.STUDENT_DANCE) return@postDelayed
            callback.onStudentDanceTimeUp()
        }, duration)
    }

    private fun enterReplay() {
        segmentStatuses[currentIndex] = SegmentStatus.FAILED
        callback.onSegmentStatusChanged(segmentStatuses.toList())
        retryCount++
        callback.onReplay(retryCount)
        state = State.REPLAY
        callback.onStateChanged(State.REPLAY)
        handler.postDelayed({ enterTeacherDemo() }, 1000)
    }

    private fun nextSegment() {
        if (currentIndex < segments.size - 1) {
            currentIndex++
            retryCount = 0
            enterTeacherDemo()
        } else {
            val avg = computeAverageScore()
            callback.onRoundCompleted(avg, roundScores.toList())
            state = State.COMPLETED
            callback.onStateChanged(State.COMPLETED)
            // 3 秒后进入手势选择（评分弹窗显示时间）
            handler.postDelayed({
                if (state != State.COMPLETED) return@postDelayed
                state = State.GESTURE_SELECT
                callback.onStateChanged(State.GESTURE_SELECT)
                callback.onGestureSelectStarted()
                gestureSelectStartMs = SystemClock.elapsedRealtime()
                // 无超时，等待用户手动手势选择
            }, 3000)
        }
    }

    private fun computeAverageScore(): Float {
        val valid = roundScores.filter { !it.skipped }
        if (valid.isEmpty()) return 0f
        return valid.map { it.score }.average().toFloat()
    }
}
