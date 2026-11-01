/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain

import android.content.Context
import android.content.SharedPreferences

/**
 * 教学模式偏好持久化（SharedPreferences）。
 *
 * 模式：
 * - FOLLOW（跟着跳）：视频持续播放，用户实时跟跳
 * - SEGMENTED（分段跳）：老师先跳一段，暂停后用户跳
 *   【已禁用】2026-08-16 起暂时屏蔽此模式（保留全部相关代码未删除，便于日后恢复）。
 *   切换循环已改为仅 FOLLOW ↔ DEBUG 两态；getMode 读取到旧的 SEGMENTED 值时回退 DEBUG。
 * - DEBUG（调试模式）：等同跟着跳，但叠加教师/用户实时骨架 + 相似度浮层
 *
 * 默认 DEBUG（调试模式；用户要求）。
 * 切换方式：教学页左上角模式按钮（横屏），或竖屏左右滑动切换。
 */
object ModePreference {

    // NOTE: SEGMENTED（分段跳）模式已暂时禁用，但枚举值及各处执行代码（TeachingActivity 的
    // SEGMENTED 分支、updateFrameSegmented、TeachingController、Segmenter 等）均保留未删除，
    // 仅从切换循环与默认读取中移除，日后恢复只需放开 nextMode / getMode 即可。
    enum class Mode { FOLLOW, SEGMENTED, DEBUG }

    private const val PREF_NAME = "dancecoach_prefs"
    private const val KEY_MODE = "mode_preference"

    /** 固定 lag = 1000ms：教师视频帧读取位置比用户当前动作慢 1 秒，营造时差 */
    const val FIXED_LAG_MS: Long = 1000L

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 读取模式，默认 DEBUG（调试模式） */
    fun getMode(context: Context): Mode {
        val name = prefs(context).getString(KEY_MODE, Mode.DEBUG.name) ?: Mode.DEBUG.name
        return try {
            val m = Mode.valueOf(name)
            // 分段跳模式已禁用：读取到旧存储的 SEGMENTED 时回退到 DEBUG，避免进入不可用模式
            if (m == Mode.SEGMENTED) Mode.DEBUG else m
        } catch (e: IllegalArgumentException) {
            Mode.DEBUG
        }
    }

    /** 写入模式 */
    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    /** 切换模式，两态循环：DEBUG ↔ FOLLOW（SEGMENTED 已禁用，不再出现在循环里） */
    fun toggleMode(context: Context): Mode {
        val next = nextMode(getMode(context))
        setMode(context, next)
        return next
    }

    /** 给定当前模式，返回下一个模式（用于手势/按钮循环）。
     *  SEGMENTED 已禁用：循环仅 DEBUG ↔ FOLLOW。SEGMENTED 分支保留以兼容枚举穷尽性，
     *  永不经此进入。 */
    fun nextMode(current: Mode): Mode = when (current) {
        Mode.DEBUG -> Mode.FOLLOW
        Mode.FOLLOW -> Mode.DEBUG
        Mode.SEGMENTED -> Mode.DEBUG
    }
}
