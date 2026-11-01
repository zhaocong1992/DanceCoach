/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局可调参数配置层（SharedPreferences 持久化）。
 *
 * 复用 ModePreference 的同一份 "dancecoach_prefs" 文件，所有键以 "cfg_" 前缀避免冲突。
 * 默认值与改造前代码中的硬编码常量完全一致，因此在不改动任何设置的情况下，
 * App 行为与之前保持不变（零回归）。
 *
 * 用法：在 Application/入口 Activity 调用一次 [init]，随后任意地方读写属性即可。
 */
object AppPrefs {

    private const val PREF_NAME = "dancecoach_prefs"

    private lateinit var appCtx: Context

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    private val prefs: SharedPreferences
        get() = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ═══ 摄像头 ═══
    /** 前置摄像头水平镜像（自拍镜像） */
    var mirror: Boolean
        get() = prefs.getBoolean(KEY_MIRROR, true)
        set(v) = prefs.edit().putBoolean(KEY_MIRROR, v).apply()

    /** 显示旋转角度（0/90/180/270） */
    var displayRotation: Int
        get() = prefs.getInt(KEY_DISPLAY_ROTATION, 0)
        set(v) = prefs.edit().putInt(KEY_DISPLAY_ROTATION, v).apply()

    // ═══ 姿态与分析 ═══
    /** 动作预审抽帧间隔（毫秒） */
    var sampleIntervalMs: Long
        get() = prefs.getLong(KEY_SAMPLE_INTERVAL_MS, 100L)
        set(v) = prefs.edit().putLong(KEY_SAMPLE_INTERVAL_MS, v).apply()

    // ═══ 教学与打分 ═══
    /** 分段达标阈值（0..1），分数 ≥ 此值才记完成 */
    var matchThreshold: Float
        get() = prefs.getFloat(KEY_MATCH_THRESHOLD, 0.7f)
        set(v) = prefs.edit().putFloat(KEY_MATCH_THRESHOLD, v).apply()

    /** 跟跳 lag（毫秒）：教师帧读取位置比用户当前动作慢的时长 */
    var lagMs: Long
        get() = prefs.getLong(KEY_LAG_MS, 1000L)
        set(v) = prefs.edit().putLong(KEY_LAG_MS, v).apply()

    /** 教师骨架实时识别总开关 */
    var enableTeacherDetect: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_TEACHER_DETECT, true)
        set(v) = prefs.edit().putBoolean(KEY_ENABLE_TEACHER_DETECT, v).apply()

    // ═══ 调试 ═══
    /** 调试帧落盘（FrameDumper.userEnabled 的开关） */
    var dumpFrames: Boolean
        get() = prefs.getBoolean(KEY_DUMP_FRAMES, false)
        set(v) = prefs.edit().putBoolean(KEY_DUMP_FRAMES, v).apply()

    // ═══ 屏幕方向 ═══
    /** 是否横屏（默认横屏）。首页顶栏开关切换，重启保持 */
    var isLandscape: Boolean
        get() = prefs.getBoolean(KEY_LANDSCAPE, true)
        set(v) = prefs.edit().putBoolean(KEY_LANDSCAPE, v).apply()

    // ═══ 键名 ═══
    private const val KEY_MIRROR = "cfg_mirror"
    private const val KEY_DISPLAY_ROTATION = "cfg_display_rotation"
    private const val KEY_SAMPLE_INTERVAL_MS = "cfg_sample_interval_ms"
    private const val KEY_MATCH_THRESHOLD = "cfg_match_threshold"
    private const val KEY_LAG_MS = "cfg_lag_ms"
    private const val KEY_ENABLE_TEACHER_DETECT = "cfg_enable_teacher_detect"
    private const val KEY_DUMP_FRAMES = "cfg_dump_frames"
    private const val KEY_LANDSCAPE = "cfg_landscape"
}
