/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain.market

import android.content.Context
import android.content.SharedPreferences

/**
 * 市场互动的「本机状态」存储（SharedPreferences）。
 * - 已点赞的舞蹈 id 集合：用于去重（防止同一设备重复 +1）与界面高亮。
 * - 已收藏的舞蹈 id 集合：纯本地"我的收藏"，不跨设备。
 *
 * 说明：点赞计数本身是云端跨设备统计（likeCount），本机只记"我是否点过"，
 * 不存计数副本。收藏完全是本机行为。
 */
object MarketPrefs {

    private const val NAME = "market_prefs"
    private const val KEY_LIKED = "liked_ids"
    private const val KEY_FAV = "favorited_ids"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isLiked(context: Context, id: String): Boolean =
        sp(context).getStringSet(KEY_LIKED, emptySet())?.contains(id) ?: false

    fun setLiked(context: Context, id: String, liked: Boolean) {
        val set = LinkedHashSet(sp(context).getStringSet(KEY_LIKED, emptySet()) ?: emptySet())
        if (liked) set.add(id) else set.remove(id)
        sp(context).edit().putStringSet(KEY_LIKED, set).apply()
    }

    fun isFavorited(context: Context, id: String): Boolean =
        sp(context).getStringSet(KEY_FAV, emptySet())?.contains(id) ?: false

    fun setFavorited(context: Context, id: String, fav: Boolean) {
        val set = LinkedHashSet(sp(context).getStringSet(KEY_FAV, emptySet()) ?: emptySet())
        if (fav) set.add(id) else set.remove(id)
        sp(context).edit().putStringSet(KEY_FAV, set).apply()
    }

    /** "我的收藏"页读取全部已收藏 id */
    fun getFavoritedIds(context: Context): Set<String> =
        sp(context).getStringSet(KEY_FAV, emptySet()) ?: emptySet()
}
