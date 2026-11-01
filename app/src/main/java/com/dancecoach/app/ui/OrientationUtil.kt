/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import com.dancecoach.app.domain.AppPrefs

/**
 * 横竖屏方向工具：所有 Activity 在 onCreate 开头调用 [apply]，
 * 按用户偏好（首页开关切换）锁定横屏或竖屏。
 * 切换时方向变化触发 Activity 重建，系统自动加载对应 orientation 的布局。
 */
object OrientationUtil {
    fun apply(activity: Activity) {
        activity.requestedOrientation =
            if (AppPrefs.isLandscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}
