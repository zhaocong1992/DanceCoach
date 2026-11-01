/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain.market

import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * 匿名身份：用设备固定 ID 当「匿名 userId」。
 *
 * 背景：用户决定首期先用匿名登录跑通，不强制注册。
 * 微信云开发的「网页匿名登录」需要前端 SDK 与签名，原生 APP 没有官方 SDK，
 * 所以用设备级稳定 ID（ANDROID_ID）等价替代——同一台设备上传/计数归因一致，
 * 换设备或卸载重装会生成新 ID（MVP 接受，后续可接入真实登录把数据合并）。
 */
object DeviceIdProvider {

    private var cached: String? = null

    fun get(context: Context): String {
        if (cached == null) {
            val fromSettings = try {
                Settings.Secure.getString(
                    context.applicationContext.contentResolver,
                    Settings.Secure.ANDROID_ID
                )
            } catch (e: Exception) {
                null
            }
            cached = if (fromSettings.isNullOrBlank()) {
                "anon_${UUID.randomUUID()}"
            } else {
                "dev_$fromSettings"
            }
        }
        return cached!!
    }
}
