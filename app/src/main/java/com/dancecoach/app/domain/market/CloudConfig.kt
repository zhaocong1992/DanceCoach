/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain.market

import android.content.Context
import com.dancecoach.app.BuildConfig
import com.dancecoach.app.R

/**
 * 云端接入配置。
 *
 * 取值优先级：
 *  1. `BuildConfig.CLOUD_ENDPOINT` / `CLOUD_APP_TOKEN`
 *     —— 由 app/build.gradle 从 local.properties（`cloud.endpoint` / `cloud.appToken`）
 *     或 Gradle 属性注入，**不进版本库**，推荐方式；
 *  2. `R.string.cloud_endpoint` / `cloud_app_token`
 *     —— 开源仓库里默认为空，作为兜底。
 *
 * 两者都为空 → 视为「未配置云端」，MarketRepoProvider 自动回退到 MockMarketRepository，
 * 此时教学、姿态评分、本地舞蹈库等功能完全可用，只有「舞蹈市场」走本地演示数据。
 */
object CloudConfig {

    private var endpointCache: String? = null
    private var tokenCache: String? = null

    /** 云函数 HTTP 触发网关地址，例：https://xxx.example.com/api */
    fun endpoint(context: Context): String {
        if (endpointCache == null) {
            val injected = runCatching { BuildConfig.CLOUD_ENDPOINT }.getOrDefault("").trim()
            endpointCache = if (injected.isNotBlank()) {
                injected
            } else {
                context.getString(R.string.cloud_endpoint).trim()
            }
        }
        return endpointCache!!
    }

    /** 与服务端 APP_SECRET 一致的共享密钥（可为空） */
    fun appToken(context: Context): String {
        if (tokenCache == null) {
            val injected = runCatching { BuildConfig.CLOUD_APP_TOKEN }.getOrDefault("").trim()
            tokenCache = if (injected.isNotBlank()) {
                injected
            } else {
                context.getString(R.string.cloud_app_token).trim()
            }
        }
        return tokenCache!!
    }

    /** 是否已配置云端（endpoint 非空才视为启用） */
    fun enabled(context: Context): Boolean = endpoint(context).isNotBlank()
}
