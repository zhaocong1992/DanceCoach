/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain.market

import android.app.AlertDialog
import android.content.Context
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dancecoach.app.R

/**
 * 激励视频广告桥接层。
 *
 * 当前为【占位实现】：弹一个模拟广告对话框，用户点「完成观看」即视为广告看完，
 * 触发 onRewarded 回调发币。目的是先把「我的奖励 → 看广告得币」的完整体验跑通。
 *
 * 接入真实广告（推荐穿山甲 Pangle，国内填充/收益最佳；兜底优量汇，可上聚合）：
 *   1. 在 build.gradle 引入广告 SDK 依赖，并在 AndroidManifest 配置 AppID/权限。
 *   2. 在这里的 show() 内替换为：SDK 初始化 → 加载激励视频 → 展示 →
 *      onRewardedVideoComplete / onReward 回调里调用 onRewarded()，并在
 *      onAdClosed 里做刷新。务必「真实展示完」才发币，否则违反平台政策会被封号。
 *   3. AppID / 广告位ID 从运营侧获取后填入常量。
 */
object RewardedAdBridge {

    /** 真实接入时替换为平台分配的 AppID */
    const val APP_ID_PLACEHOLDER = "YOUR_APP_ID"
    /** 真实接入时替换为激励视频广告位ID */
    const val PLACEMENT_ID_PLACEHOLDER = "YOUR_PLACEMENT_ID"

    /**
     * 展示激励视频。广告正常播放完成（或此处模拟完成）后调用 [onRewarded]。
     * [onDismiss] 在广告关闭后调用，用于刷新 UI。
     */
    fun show(context: Context, onRewarded: () -> Unit, onDismiss: () -> Unit = {}) {
        // ===== 占位实现（模拟广告） =====
        var rewarded = false
        val dialog = AlertDialog.Builder(context)
            .setTitle("📺 激励视频广告（模拟）")
            .setMessage("这是占位广告。真实接入后会展示广告主素材。\n观看完成即可获得 10 舞币。")
            .setCancelable(false)
            .setPositiveButton("完成观看") { _, _ ->
                rewarded = true
                onRewarded()
            }
            .setNegativeButton("关闭") { _, _ -> /* 未看完不发币 */ }
            .setOnDismissListener { if (!rewarded) onDismiss() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(ContextCompat.getColor(context, R.color.accent))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                ?.let { (it as TextView).setTextColor(ContextCompat.getColor(context, R.color.textSecondary)) }
        }
        dialog.show()
        // ================================
    }
}
