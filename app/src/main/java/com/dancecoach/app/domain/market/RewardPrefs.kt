/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain.market

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 本机激励体系存储（SharedPreferences）。
 *
 * 轻量「奖励机制」版：舞币、累计获得、上传/下载/收藏/签到次数、最后签到日期、已解锁徽章
 * 全部存本机，不依赖登录、不依赖后端（设计稿 C 组的完整积分/充值需登录+合规，本期不做）。
 * 代价：币不跨设备、卸载即丢失、易作弊——MVP 体验玩法可接受。
 *
 * v2：本机即唯一账本。被动收入（别人下载/点赞你的舞蹈）在「我的上传」刷新结算时
 * 由云端 syncMyUploads 返回增量并发到本机舞币，不再有独立「服务端被动余额」概念。
 *
 * 「我的上传」本地列表：清应用数据即清空，与「不登录、归零」自洽。
 */
object RewardPrefs {

    private const val NAME = "reward_prefs"
    private const val K_COINS = "coins"
    private const val K_TOTAL = "total_earned"
    private const val K_UPLOAD = "upload_count"
    private const val K_DOWNLOAD = "download_count"
    private const val K_FAV = "favorite_count"
    private const val K_SIGNIN = "signin_count"
    private const val K_LAST = "last_signin_date"
    private const val K_BADGES = "badges"
    private const val K_UP_TODAY_DATE = "upload_today_date"
    private const val K_UP_TODAY_COUNT = "upload_today_count"
    // 金币小数化：内部按「分」存储（1 币 = 10 分），此标志标记是否已完成旧版「币」→「分」的一次性迁移
    private const val K_COIN_SCALE = "coin_scale_v2"
    // 跟跳奖励防刷：同一支舞每日限结算 N 次（跨天归零），不同舞蹈不受限，鼓励多用
    private const val K_TAP_DANCE_DATE = "tap_dance_reward_date"
    private const val PREFIX_TAP_DANCE = "tap_dance_reward_"

    // 「我的上传」本地列表 + 离线缓存
    private const val K_MYUP_IDS = "myupload_ids"
    private const val PREFIX_MYUP = "myupd_"
    private const val K_SEED_DONE = "myupload_seed_done"

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * 一次性迁移：v2 之前币存的是「币」（整数），跟跳奖励引入小数后内部改为「分」
     * （1 币 = 10 分）。首次读取余额/累计时把旧值 ×10，并打标记防止重复迁移。
     */
    private fun ensureCoinScaleMigrated(c: Context) {
        val sp = sp(c)
        if (sp.getBoolean(K_COIN_SCALE, false)) return
        sp.edit()
            .putInt(K_COINS, sp.getInt(K_COINS, 0) * 10)
            .putInt(K_TOTAL, sp.getInt(K_TOTAL, 0) * 10)
            .putBoolean(K_COIN_SCALE, true)
            .apply()
    }

    fun getCoins(c: Context): Int {
        ensureCoinScaleMigrated(c)
        return sp(c).getInt(K_COINS, 0)
    }
    fun addCoins(c: Context, delta: Int) =
        sp(c).edit().putInt(K_COINS, getCoins(c) + delta).apply()
    fun setCoins(c: Context, v: Int) {
        ensureCoinScaleMigrated(c)
        sp(c).edit().putInt(K_COINS, v.coerceAtLeast(0)).apply()
    }

    fun getTotalEarned(c: Context): Int {
        ensureCoinScaleMigrated(c)
        return sp(c).getInt(K_TOTAL, 0)
    }
    fun addTotalEarned(c: Context, delta: Int) =
        sp(c).edit().putInt(K_TOTAL, getTotalEarned(c) + delta).apply()

    fun getUploadCount(c: Context) = sp(c).getInt(K_UPLOAD, 0)
    fun incUploadCount(c: Context) =
        sp(c).edit().putInt(K_UPLOAD, getUploadCount(c) + 1).apply()

    /** 今日已上传条数（跨天自动归零） */
    fun getDailyUploadCount(c: Context): Int {
        val sp = sp(c)
        if (sp.getString(K_UP_TODAY_DATE, "") != todayStr()) return 0
        return sp.getInt(K_UP_TODAY_COUNT, 0)
    }

    /** 成功后记一笔今日上传（跨天重计） */
    fun incDailyUploadCount(c: Context) {
        val sp = sp(c)
        val n = if (sp.getString(K_UP_TODAY_DATE, "") == todayStr())
            sp.getInt(K_UP_TODAY_COUNT, 0) else 0
        sp.edit().putString(K_UP_TODAY_DATE, todayStr())
            .putInt(K_UP_TODAY_COUNT, n + 1).apply()
    }

    private fun todayStr(): String {
        val d = java.util.Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            d.get(java.util.Calendar.YEAR),
            d.get(java.util.Calendar.MONTH) + 1,
            d.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    fun getDownloadCount(c: Context) = sp(c).getInt(K_DOWNLOAD, 0)
    fun incDownloadCount(c: Context) =
        sp(c).edit().putInt(K_DOWNLOAD, getDownloadCount(c) + 1).apply()

    fun getFavoriteCount(c: Context) = sp(c).getInt(K_FAV, 0)
    fun incFavoriteCount(c: Context) =
        sp(c).edit().putInt(K_FAV, getFavoriteCount(c) + 1).apply()

    fun getSigninCount(c: Context) = sp(c).getInt(K_SIGNIN, 0)
    fun incSigninCount(c: Context) =
        sp(c).edit().putInt(K_SIGNIN, getSigninCount(c) + 1).apply()

    fun getLastSignin(c: Context) = sp(c).getString(K_LAST, "") ?: ""
    fun setLastSignin(c: Context, v: String) = sp(c).edit().putString(K_LAST, v).apply()

    // ════════════════════════════════════════════════════════════════════
    // 跟跳奖励防刷（跨天自动归零）：同一支舞每日限 TAP_PER_DANCE_LIMIT 次；
    // 不同舞蹈互不影响，鼓励用户多练习不同内容。
    // ════════════════════════════════════════════════════════════════════

    /** 该支舞今日已结算的跟跳奖励次数（跨天归零） */
    fun getDanceRewardCountToday(c: Context, danceId: String): Int {
        val sp = sp(c)
        if (danceId.isBlank()) return 0
        if (sp.getString(K_TAP_DANCE_DATE, "") != todayStr()) return 0
        return sp.getInt(PREFIX_TAP_DANCE + danceId, 0)
    }

    /** 该支舞结算成功后记一次（跨天重计） */
    fun incDanceRewardCountToday(c: Context, danceId: String) {
        if (danceId.isBlank()) return
        val sp = sp(c)
        val n = if (sp.getString(K_TAP_DANCE_DATE, "") == todayStr())
            sp.getInt(PREFIX_TAP_DANCE + danceId, 0) else 0
        sp.edit().putString(K_TAP_DANCE_DATE, todayStr())
            .putInt(PREFIX_TAP_DANCE + danceId, n + 1).apply()
    }

    fun getBadges(c: Context): Set<String> =
        LinkedHashSet(sp(c).getStringSet(K_BADGES, emptySet()) ?: emptySet())
    fun setBadges(c: Context, set: Set<String>) =
        sp(c).edit().putStringSet(K_BADGES, LinkedHashSet(set)).apply()

    // ════════════════════════════════════════════════════════════════════
    // 「我的上传」本地列表 + 离线缓存 metadata
    // ════════════════════════════════════════════════════════════════════

    /** 单条上传在本机的缓存元信息（用于「我的上传」离线展示 + 待结算角标） */
    data class MyUploadMeta(
        val id: String,
        val title: String,
        val coverUrl: String,
        val likeCount: Int,
        val downloadCount: Int,
        val pendingLike: Int,   // 还可从「被点赞」领取的舞币（封顶后剩余）
        val pendingDL: Int      // 还可从「被下载」领取的舞币（封顶后剩余）
    ) {
        fun pendingTotal() = pendingLike + pendingDL
    }

    fun getMyUploadIds(c: Context): Set<String> =
        LinkedHashSet(sp(c).getStringSet(K_MYUP_IDS, emptySet()) ?: emptySet())

    fun addMyUploadId(c: Context, id: String) {
        val s = getMyUploadIds(c).toMutableSet()
        s.add(id)
        sp(c).edit().putStringSet(K_MYUP_IDS, s).apply()
    }

    fun removeMyUploadId(c: Context, id: String) {
        val s = getMyUploadIds(c).toMutableSet()
        s.remove(id)
        sp(c).edit().remove(PREFIX_MYUP + id).putStringSet(K_MYUP_IDS, s).apply()
    }

    fun putMyUploadMeta(c: Context, meta: MyUploadMeta) {
        val j = JSONObject().apply {
            put("id", meta.id)
            put("title", meta.title)
            put("coverUrl", meta.coverUrl)
            put("likeCount", meta.likeCount)
            put("downloadCount", meta.downloadCount)
            put("pendingLike", meta.pendingLike)
            put("pendingDL", meta.pendingDL)
        }
        sp(c).edit().putString(PREFIX_MYUP + meta.id, j.toString()).apply()
    }

    fun getMyUploadMeta(c: Context, id: String): MyUploadMeta? {
        val s = sp(c).getString(PREFIX_MYUP + id, null) ?: return null
        return try {
            val j = JSONObject(s)
            MyUploadMeta(
                id = j.optString("id", id),
                title = j.optString("title", "未命名舞蹈"),
                coverUrl = j.optString("coverUrl", ""),
                likeCount = j.optInt("likeCount", 0),
                downloadCount = j.optInt("downloadCount", 0),
                pendingLike = j.optInt("pendingLike", 0),
                pendingDL = j.optInt("pendingDL", 0)
            )
        } catch (e: Exception) { null }
    }

    fun getAllMyUploadMetas(c: Context): Map<String, MyUploadMeta> {
        val out = LinkedHashMap<String, MyUploadMeta>()
        for (id in getMyUploadIds(c)) {
            getMyUploadMeta(c, id)?.let { out[id] = it }
        }
        return out
    }

    /** 全部上传的「待结算」合计（市场页角标用） */
    fun totalPending(c: Context): Int =
        getAllMyUploadMetas(c).values.sumOf { it.pendingTotal() }

    fun isSeedDone(c: Context) = sp(c).getBoolean(K_SEED_DONE, false)
    fun setSeedDone(c: Context) = sp(c).edit().putBoolean(K_SEED_DONE, true).apply()
}
