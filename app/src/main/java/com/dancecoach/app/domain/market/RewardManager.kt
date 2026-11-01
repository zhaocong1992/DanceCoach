/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain.market

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

/** 触发发币的行为类型 */
enum class RewardReason { UPLOAD, AD_WATCH }

/** 一次发币/签到后的结果，供 UI 弹提示 */
data class AwardResult(
    val coinGain: Int,        // 本次总获得（含徽章奖励）
    val levelUp: Boolean,     // 是否升级
    val newBadges: List<BadgeDef>
)

/** 徽章定义（静态） */
data class BadgeDef(
    val id: String,
    val name: String,
    val emoji: String,
    val desc: String
)

/** 当前等级信息（按累计「获得」计算，不随花费掉级） */
data class LevelInfo(
    val level: Int,
    val name: String,
    val totalEarned: Int,
    val nextThreshold: Int,   // 下一级阈值；Int.MAX_VALUE 表示已满级
    val progressInLevel: Int,  // 本级已积累
    val span: Int             // 本级跨度（用于进度条百分比）
)

/**
 * 激励引擎（v2）：
 *  - 全部舞币都是本机余额（RewardPrefs.getCoins），主动收入与被动结算增量都落到这里。
 *  - 金币内部按「分」存储（1 币 = 10 分），支持 0.5 等小数展示；对外文案统一用
 *    formatCoin() 格式化。
 *  - 被动收入（别人下载/点赞你的舞蹈）在「我的上传」刷新时由云端 syncMyUploads 返回增量（币）并加币。
 *  - 下载他人内容花费 2 舞币，纯本机扣减。
 * 零登录；被动部分需部署最新云函数（含 syncMyUploads）。
 */
object RewardManager {

    // 行为发币规则（主动，单位：分）
    private val COIN_RULES = mapOf(
        RewardReason.UPLOAD to 50,
        RewardReason.AD_WATCH to 100
    )
    // 签到按等级递增（分）：Lv1..Lv5 = 10/15/22/32/50 币
    private val SIGNIN_TABLE = intArrayOf(100, 150, 220, 320, 500)
    private const val BADGE_BONUS = 100   // 每解锁一个徽章额外奖励（10 币）
    private const val DOWNLOAD_COST = 20  // 下载他人内容花费（2 币）

    // 等级阈值（按累计获得 totalEarned，单位：分）
    private val LEVELS = listOf(
        Tier(1, "新手", 0),
        Tier(2, "舞动新苗", 300),
        Tier(3, "节奏达人", 1200),
        Tier(4, "舞林高手", 3000),
        Tier(5, "舞王", 6000)
    )

    // ═══ 跟跳奖励（鼓励使用） ═══
    // 每完成一轮跟跳，相似度 ≥95 得 0.5 币（单一档位）；未达标不发币
    const val TAP_SCORE_THRESHOLD = 95f
    const val TAP_REWARD_CENTS = 5   // 0.5 币 = 5 分
    // 防刷：同一支舞每日最多领取 3 次；换其他舞蹈可继续领取（鼓励多练习不同内容）
    const val TAP_PER_DANCE_LIMIT = 3

    // 徽章定义
    val BADGES = listOf(
        BadgeDef("upload_1", "初登舞台", "🎬", "首次上传分享"),
        BadgeDef("download_10", "下载狂人", "⬇️", "累计下载/导入 10 次"),
        BadgeDef("favorite_10", "收藏家", "⭐", "累计收藏 10 次"),
        BadgeDef("signin_7", "坚持不懈", "📅", "累计签到 7 天"),
        BadgeDef("coin_500", "小富翁", "💰", "累计获得 500 舞币")
    )

    /** 行为发币（主动）：返回本次总获得（含徽章奖励）与解锁/升级信息 */
    fun award(context: Context, reason: RewardReason): AwardResult {
        val gain = COIN_RULES[reason] ?: 0
        if (reason == RewardReason.UPLOAD) RewardPrefs.incUploadCount(context)
        val before = levelOf(RewardPrefs.getTotalEarned(context))
        RewardPrefs.addTotalEarned(context, gain)
        RewardPrefs.addCoins(context, gain)
        val after = levelOf(RewardPrefs.getTotalEarned(context))
        val newBadges = checkBadges(context)
        var total = gain
        if (newBadges.isNotEmpty()) {
            RewardPrefs.addTotalEarned(context, newBadges.size * BADGE_BONUS)
            RewardPrefs.addCoins(context, newBadges.size * BADGE_BONUS)
            total += newBadges.size * BADGE_BONUS
        }
        return AwardResult(total, after > before, newBadges)
    }

    /** 每日签到：按当前等级给不同数量的舞币；今天已签返回 null */
    fun signIn(context: Context): AwardResult? {
        val today = todayStr()
        if (RewardPrefs.getLastSignin(context) == today) return null
        val lv = getLevelInfo(context).level
        val gain = SIGNIN_TABLE.getOrElse(lv - 1) { SIGNIN_TABLE.last() }
        RewardPrefs.setLastSignin(context, today)
        RewardPrefs.incSigninCount(context)
        val before = levelOf(RewardPrefs.getTotalEarned(context))
        RewardPrefs.addTotalEarned(context, gain)
        RewardPrefs.addCoins(context, gain)
        val after = levelOf(RewardPrefs.getTotalEarned(context))
        val newBadges = checkBadges(context)
        var total = gain
        if (newBadges.isNotEmpty()) {
            RewardPrefs.addTotalEarned(context, newBadges.size * BADGE_BONUS)
            RewardPrefs.addCoins(context, newBadges.size * BADGE_BONUS)
            total += newBadges.size * BADGE_BONUS
        }
        return AwardResult(total, after > before, newBadges)
    }

    fun isSignedInToday(context: Context): Boolean =
        RewardPrefs.getLastSignin(context) == todayStr()

    /** 收藏舞蹈时调用：累计收藏次数，解锁"收藏家"徽章时给徽章奖励（+10 币/个） */
    fun onFavorite(context: Context) {
        RewardPrefs.incFavoriteCount(context)
        val newBadges = checkBadges(context)
        if (newBadges.isNotEmpty()) {
            val bonus = newBadges.size * BADGE_BONUS
            RewardPrefs.addTotalEarned(context, bonus)
            RewardPrefs.addCoins(context, bonus)
        }
    }

    /** 下载他人内容花费的舞币数 */
    fun downloadCost(): Int = DOWNLOAD_COST

    /** 当前余额是否够下载（余额不足应拦截） */
    fun canDownload(context: Context): Boolean =
        RewardPrefs.getCoins(context) >= DOWNLOAD_COST

    /** 下载成功后扣费（调用前务必已确认 canDownload，且导入成功） */
    fun applyDownloadCost(context: Context) {
        RewardPrefs.setCoins(context, RewardPrefs.getCoins(context) - DOWNLOAD_COST)
    }

    /** 把 syncMyUploads 返回的被动结算增量（单位：币）落到本机余额。云端按结算点只给增量，不重复发 */
    fun creditPassiveDelta(context: Context, deltaCoins: Int) {
        if (deltaCoins <= 0) return
        val cents = deltaCoins * 10   // 币 → 分
        RewardPrefs.addTotalEarned(context, cents)
        RewardPrefs.addCoins(context, cents)
    }

    /** 检查并解锁满足条件的徽章，返回本次新解锁的 */
    private fun checkBadges(context: Context): List<BadgeDef> {
        val unlocked = RewardPrefs.getBadges(context).toMutableSet()
        val newly = mutableListOf<BadgeDef>()
        for (b in BADGES) {
            if (b.id in unlocked) continue
            if (earned(context, b.id)) {
                unlocked.add(b.id)
                newly.add(b)
            }
        }
        if (newly.isNotEmpty()) RewardPrefs.setBadges(context, unlocked)
        return newly
    }

    private fun earned(context: Context, id: String): Boolean = when (id) {
        "upload_1" -> RewardPrefs.getUploadCount(context) >= 1
        "download_10" -> RewardPrefs.getDownloadCount(context) >= 10
        "favorite_10" -> RewardPrefs.getFavoriteCount(context) >= 10
        "signin_7" -> RewardPrefs.getSigninCount(context) >= 7
        "coin_500" -> RewardPrefs.getTotalEarned(context) >= 5000
        else -> false
    }

    // ═══ 跟跳奖励 ═══

    /** 按相似度(0-100)返回本轮跟跳奖励（分）；相似度 ≥95 得 0.5 币，否则 0 */
    fun tapRewardForScore(score: Float): Int =
        if (score >= TAP_SCORE_THRESHOLD) TAP_REWARD_CENTS else 0

    /**
     * 结算一次跟跳奖励：按相似度发币并计入累计与徽章。
     * 防刷限制：同一支舞（[danceId]）每日最多结算 [TAP_PER_DANCE_LIMIT] 次；不同舞蹈互不影响。
     * @return 本次获得的分（0 表示未达门槛或受防刷限制未发币）
     */
    fun awardTap(context: Context, score: Float, danceId: String): Int {
        val gain = tapRewardForScore(score)
        if (gain <= 0) return 0
        if (danceId.isNotBlank() &&
            RewardPrefs.getDanceRewardCountToday(context, danceId) >= TAP_PER_DANCE_LIMIT) return 0
        RewardPrefs.addTotalEarned(context, gain)
        RewardPrefs.addCoins(context, gain)
        checkBadges(context)
        if (danceId.isNotBlank()) RewardPrefs.incDanceRewardCountToday(context, danceId)
        return gain
    }

    /**
     * 查询本轮跟跳为何未发币（供结算弹窗展示）：未达门槛返回 null（走鼓励文案）；
     * 已达标但被防刷拦截时返回具体原因文本。
     */
    fun tapAwardBlockedReason(context: Context, danceId: String, score: Float): String? {
        if (tapRewardForScore(score) <= 0) return null
        if (danceId.isNotBlank() &&
            RewardPrefs.getDanceRewardCountToday(context, danceId) >= TAP_PER_DANCE_LIMIT) {
            return "这支舞蹈今天的奖励已领满 ${TAP_PER_DANCE_LIMIT} 次，换一支舞或明天再来吧"
        }
        return null
    }

    // ═══ 展示 ═══

    /** 把「分」格式化为带小数的币文本（去尾零）：50→"5"、2→"0.2"、15→"1.5" */
    fun formatCoin(cents: Int): String {
        val whole = cents / 10
        val frac = cents % 10
        return if (frac == 0) "$whole" else "$whole.$frac"
    }

    /** 展示用总余额（币文本，支持小数） */
    fun getDisplayCoinsText(context: Context): String =
        formatCoin(RewardPrefs.getCoins(context))

    fun getLevelInfo(context: Context): LevelInfo {
        val te = RewardPrefs.getTotalEarned(context)
        val idx = levelIndex(te)
        val cur = LEVELS[idx]
        val next = if (idx + 1 < LEVELS.size) LEVELS[idx + 1] else null
        val base = cur.threshold
        val top = next?.threshold ?: (cur.threshold + 1)
        return LevelInfo(
            level = cur.level,
            name = cur.name,
            totalEarned = te,
            nextThreshold = next?.threshold ?: Int.MAX_VALUE,
            progressInLevel = te - base,
            span = (top - base).coerceAtLeast(1)
        )
    }

    fun unlockedBadges(context: Context): List<BadgeDef> {
        val set = RewardPrefs.getBadges(context)
        return BADGES.filter { it.id in set }
    }

    private fun levelIndex(te: Int): Int {
        var idx = 0
        for (i in LEVELS.indices) if (te >= LEVELS[i].threshold) idx = i
        return idx
    }
    private fun levelOf(te: Int): Int = LEVELS[levelIndex(te)].level

    private fun todayStr(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private data class Tier(val level: Int, val name: String, val threshold: Int)
}
