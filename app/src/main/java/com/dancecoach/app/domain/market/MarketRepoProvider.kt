/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain.market

import android.content.Context

/**
 * 仓库工厂：云端已配置（cloud_endpoint 非空）就用 CloudMarketRepository，否则回退 Mock。
 * UI 层只需要拿到一个 MarketRepository，不用关心背后是谁。
 */
object MarketRepoProvider {

    fun get(context: Context): MarketRepository {
        return if (CloudConfig.enabled(context.applicationContext)) {
            CloudMarketRepository(context.applicationContext)
        } else {
            MockMarketRepository
        }
    }
}
