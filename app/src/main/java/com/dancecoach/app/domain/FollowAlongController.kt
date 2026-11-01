/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 跟着跳控制器（v4.0 新模式）。
 *
 * 状态：IDLE → COUNTDOWN → FOLLOWING → COMPLETED
 * - COUNTDOWN：3 秒准备倒计时，视频暂停
 * - FOLLOWING：老师视频连续播放，用户实时跟跳，每帧持续打分（固定 lag）
 * - COMPLETED：视频（或 2 分钟截断点）结束，展示轮次平均分，
 *   由 Activity 在数秒后接管进入手势选择
 *
 * 设计要点（v1.1 决策）：
 * - 不暂停、不做跟丢检测，直接持续打分
 * - lag 固定 500ms，不上 DTW
 */
class FollowAlongController(private val callback: Callback) {

    enum class State { IDLE, COUNTDOWN, FOLLOWING, COMPLETED }

    interface Callback {
        fun onStateChanged(state: State)
        /** 每帧实时相似度 */
        fun onScoreUpdate(score: Float, hint: String)
        /** 准备倒计时：剩余秒数 3/2/1，0 表示即将开始 */
        fun onCountdownTick(remainingSec: Int)
        /** 视频播放进度 0..100 */
        fun onFollowProgress(pct: Int)
        /** 表现曲线采样：视频时间(ms) + 当前分数（表现曲线已移除，默认空实现） */
        fun onPerformanceSample(timeMs: Long, score: Float) {}
        /** 轮次完成：平均分 */
        fun onRoundCompleted(averageScore: Float)
    }

    var state: State = State.IDLE
        private set

    var lagMs: Long = ModePreference.FIXED_LAG_MS

    // 总分累计
    private var sumScore = 0f
    private var scoreCount = 0

    private var totalDurationMs: Long = 0

    private val handler = Handler(Looper.getMainLooper())
    private var countdownEndMs = 0L

    fun start(totalDurationMs: Long) {
        if (totalDurationMs <= 0) return
        this.totalDurationMs = totalDurationMs
        sumScore = 0f
        scoreCount = 0
        enterCountdown()
    }

    /** FOLLOWING 状态下每 tick 调用。 */
    fun onTick(
        positionMs: Long,
        teacherLandmarks: List<Landmark>?,
        userLandmarks: List<Landmark>?
    ) {
        if (state != State.FOLLOWING) return

        val teacherTime = (positionMs - lagMs).coerceAtLeast(0L)
        val result = ComparisonEngine.compare(teacherLandmarks, userLandmarks, false, false)
        val score = result.score
        callback.onScoreUpdate(score, result.hint)

        callback.onPerformanceSample(positionMs, score)

        sumScore += score
        scoreCount++

        val pct = if (totalDurationMs > 0) {
            (positionMs.toFloat() / totalDurationMs * 100f).toInt().coerceIn(0, 100)
        } else 0
        callback.onFollowProgress(pct)
    }

    /** 视频播放到末尾（或被 2 分钟截断）时调用。 */
    fun onVideoEnded() {
        if (state != State.FOLLOWING) return
        val avg = if (scoreCount > 0) sumScore / scoreCount else 0f
        state = State.COMPLETED
        callback.onStateChanged(State.COMPLETED)
        callback.onRoundCompleted(avg)
    }

    fun pause() {
        handler.removeCallbacksAndMessages(null)
    }

    /** 外部退出/切视频前复位：清倒计时与总分累计，回到 IDLE，彻底停掉跟跳流程。 */
    fun reset() {
        handler.removeCallbacksAndMessages(null)
        sumScore = 0f
        scoreCount = 0
        state = State.IDLE
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun enterCountdown() {
        state = State.COUNTDOWN
        callback.onStateChanged(State.COUNTDOWN)
        countdownEndMs = SystemClock.elapsedRealtime() + 3000L
        tickCountdown()
    }

    private fun tickCountdown() {
        val remaining = ((countdownEndMs - SystemClock.elapsedRealtime()) / 1000).toInt().coerceAtLeast(0)
        callback.onCountdownTick(remaining)
        if (remaining <= 0) {
            enterFollowing()
        } else {
            handler.postDelayed({ tickCountdown() }, 500)
        }
    }

    private fun enterFollowing() {
        state = State.FOLLOWING
        callback.onStateChanged(State.FOLLOWING)
    }
}
