/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.widget.Toast
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dancecoach.app.BuildConfig
import com.dancecoach.app.R
import com.dancecoach.app.domain.AppPrefs
import com.dancecoach.app.domain.LegalTexts
import com.dancecoach.app.domain.market.RewardManager
import com.dancecoach.app.domain.market.RewardReason
import com.dancecoach.app.domain.market.RewardedAdBridge
import android.graphics.Typeface
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

class SettingsActivity : AppCompatActivity(), CoroutineScope {
    private val job = Job()
    override val coroutineContext: CoroutineContext get() = Dispatchers.Main + job

    private lateinit var homePanel: android.widget.ScrollView
    private lateinit var detailPanel: LinearLayout
    private lateinit var detailContainer: LinearLayout
    private lateinit var detailTitle: TextView
    private var currentKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.init(applicationContext)
        OrientationUtil.apply(this)
        setContentView(R.layout.activity_settings)

        homePanel = findViewById(R.id.homePanel)
        detailPanel = findViewById(R.id.detailPanel)
        detailContainer = findViewById(R.id.detailContainer)
        detailTitle = findViewById(R.id.detailTitle)

        findViewById<TextView>(R.id.backBtn).setOnClickListener { showHome() }

        // 主页 6 个大块 → 钻取到对应小块
        findViewById<LinearLayout>(R.id.tileReward).setOnClickListener { showCategory("reward") }
        findViewById<LinearLayout>(R.id.tileCamera).setOnClickListener { showCategory("camera") }
        findViewById<LinearLayout>(R.id.tilePose).setOnClickListener { showCategory("pose") }
        findViewById<LinearLayout>(R.id.tileTeach).setOnClickListener { showCategory("teach") }
        findViewById<LinearLayout>(R.id.tileAdvanced).setOnClickListener { showCategory("advanced") }
        findViewById<LinearLayout>(R.id.tileAbout).setOnClickListener { showCategory("about") }

        showHome()
    }

    override fun onResume() {
        super.onResume()
        // v2：余额全为本机，进「我的」页无需联网拉取被动账本；回到主页即可
        showHome()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (detailPanel.visibility == View.VISIBLE) {
            showHome()
        } else {
            finish()
        }
    }

    private fun showHome() {
        currentKey = ""
        homePanel.visibility = View.VISIBLE
        detailPanel.visibility = View.GONE
    }

    private fun showCategory(key: String) {
        currentKey = key
        homePanel.visibility = View.GONE
        detailPanel.visibility = View.VISIBLE
        detailTitle.text = when (key) {
            "reward" -> "奖励中心"
            "camera" -> "拍摄"
            "pose" -> "姿态分析"
            "teach" -> "教学打分"
            "advanced" -> "高级"
            else -> "关于"
        }
        buildDetail(key)
    }

    // 重新渲染当前详情（签到/看广告后刷新数据，不切换显隐）
    private fun rebuildCurrentDetail() {
        if (currentKey.isNotEmpty()) buildDetail(currentKey)
    }

    private fun buildDetail(key: String) {
        val cPrimary = ContextCompat.getColor(this, R.color.textPrimary)
        val cSecondary = ContextCompat.getColor(this, R.color.textSecondary)
        val cAccent = ContextCompat.getColor(this, R.color.accent)

        detailContainer.removeAllViews()
        when (key) {
            "reward" -> addRewardSection(detailContainer, cPrimary, cSecondary, cAccent)
            "camera" -> {
                addSection(detailContainer, "拍摄")
                addSwitch(detailContainer, "前置镜像", "自拍镜像显示，关掉则与真实左右一致",
                    { AppPrefs.mirror }, { AppPrefs.mirror = it })
                addSlider(detailContainer, "显示旋转", "画面旋转角度",
                    0, 270, 90, AppPrefs.displayRotation, { "${it}°" }, { AppPrefs.displayRotation = it })
            }
            "pose" -> {
                addSection(detailContainer, "姿态与分析")
                addSlider(detailContainer, "分析抽帧间隔", "动作预审每秒取样次数（越小越密）",
                    33, 500, 1, AppPrefs.sampleIntervalMs.toInt(), { "${it} ms" }, { AppPrefs.sampleIntervalMs = it.toLong() })
            }
            "teach" -> {
                addSection(detailContainer, "教学与打分")
                addSlider(detailContainer, "达标阈值", "分段打分达到该比例才算通过",
                    30, 100, 1, (AppPrefs.matchThreshold * 100).toInt(), { "${it}%" }, { AppPrefs.matchThreshold = it / 100f })
                addSlider(detailContainer, "跟跳延迟", "教师动作比用户慢的时差",
                    0, 2000, 50, AppPrefs.lagMs.toInt(), { "${it} ms" }, { AppPrefs.lagMs = it.toLong() })
                addSwitch(detailContainer, "教师骨架识别", "教学/调试模式下实时识别教师骨架",
                    { AppPrefs.enableTeacherDetect }, { AppPrefs.enableTeacherDetect = it })
            }
            "advanced" -> {
                addSection(detailContainer, "调试")
                addSwitch(detailContainer, "调试帧落盘", "把送进姿态网络的帧落盘到缓存，用于排查识别抖动（Debug 目录）",
                    { AppPrefs.dumpFrames }, { AppPrefs.dumpFrames = it })
            }
            "about" -> addAboutCard(detailContainer, cPrimary, cSecondary, cAccent)
        }
    }

    private fun addRewardSection(parent: LinearLayout, cPrimary: Int, cSecondary: Int, cAccent: Int) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            p.bottomMargin = dp(8)
            layoutParams = p
            setBackgroundResource(R.drawable.settings_card_bg)
        }

        val info = RewardManager.getLevelInfo(this@SettingsActivity)
        val balTv = TextView(this).apply {
            text = "💎 ${RewardManager.getDisplayCoinsText(this@SettingsActivity)} 舞币   ·   Lv.${info.level} ${info.name}"
            textSize = 16f
            setTextColor(cAccent)
            setTypeface(null, Typeface.BOLD)
        }
        card.addView(balTv)

        val progTv = TextView(this).apply {
            text = if (info.nextThreshold == Int.MAX_VALUE) "已达最高等级 👑"
                   else "距下一级还需 ${RewardManager.formatCoin(info.nextThreshold - info.totalEarned)} 舞币"
            textSize = 12f
            setTextColor(cSecondary)
            setPadding(0, dp(4), 0, dp(8))
        }
        card.addView(progTv)

        val signed = RewardManager.isSignedInToday(this@SettingsActivity)
        val signBtn = TextView(this).apply {
            text = if (signed) "今日已签到 ✓" else "📅 每日签到 +10 舞币"
            textSize = 14f
            setTextColor(if (signed) cSecondary else cAccent)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.library_card_bg)
            gravity = Gravity.CENTER
            isEnabled = !signed
            alpha = if (signed) 0.6f else 1f
        }
        signBtn.setOnClickListener {
            val r = RewardManager.signIn(this@SettingsActivity) ?: return@setOnClickListener
            Toast.makeText(this@SettingsActivity, "签到成功 +${RewardManager.formatCoin(r.coinGain)} 舞币", Toast.LENGTH_SHORT).show()
            rebuildCurrentDetail()
        }
        card.addView(signBtn)

        // 看广告得舞币（占位广告，接真实 SDK 后仅在 RewardedAdBridge 内改动）
        val adBtn = TextView(this).apply {
            text = "📺 看广告 +10 舞币"
            textSize = 14f
            setTextColor(cAccent)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.library_card_bg)
            gravity = Gravity.CENTER
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            p.topMargin = dp(8)
            layoutParams = p
        }
        adBtn.setOnClickListener {
            RewardedAdBridge.show(
                this@SettingsActivity,
                onRewarded = {
                    RewardManager.award(this@SettingsActivity, RewardReason.AD_WATCH)
                    Toast.makeText(this@SettingsActivity, "观看完成 +10 舞币", Toast.LENGTH_SHORT).show()
                    rebuildCurrentDetail()
                }
            )
        }
        card.addView(adBtn)

        // 舞币结算机制说明入口
        val coinInfoBtn = TextView(this).apply {
            text = "💡 舞币结算机制说明 ›"
            textSize = 13f
            setTextColor(cSecondary)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.library_card_bg)
            gravity = Gravity.CENTER
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            p.topMargin = dp(8)
            layoutParams = p
        }
        coinInfoBtn.setOnClickListener { showCoinMechanismDialog() }
        card.addView(coinInfoBtn)

        val badgeTitle = TextView(this).apply {
            text = "徽章"
            textSize = 13f
            setTextColor(cPrimary)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        }
        card.addView(badgeTitle)

        val unlocked = RewardManager.unlockedBadges(this@SettingsActivity).map { it.id }.toSet()
        val badgeBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.LEFT
        }
        for (b in RewardManager.BADGES) {
            val got = b.id in unlocked
            val chip = TextView(this).apply {
                text = "${b.emoji} ${b.name}"
                textSize = 12f
                setTextColor(if (got) cPrimary else cSecondary)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                val p2 = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                p2.rightMargin = dp(8); p2.bottomMargin = dp(4)
                layoutParams = p2
                setBackgroundResource(R.drawable.library_card_bg)
                alpha = if (got) 1f else 0.4f
            }
            badgeBox.addView(chip)
        }
        card.addView(badgeBox)

        parent.addView(card)
    }

    private fun addSection(parent: LinearLayout, title: String) {
        val tv = TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.accent))
            setPadding(dp(4), dp(14), dp(4), dp(4))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        parent.addView(tv)
    }

    private fun addSwitch(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        getter: () -> Boolean,
        onCommit: (Boolean) -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            p.bottomMargin = dp(8)
            layoutParams = p
            setBackgroundResource(R.drawable.settings_card_bg)
        }
        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val tp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = tp
        }
        textBox.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.textPrimary))
        })
        if (subtitle.isNotEmpty()) {
            textBox.addView(TextView(this).apply {
                text = subtitle
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.textSecondary))
            })
        }
        val sw = Switch(this).apply { isChecked = getter() }
        sw.setOnCheckedChangeListener { _, c -> onCommit(c) }
        card.addView(textBox)
        card.addView(sw)
        parent.addView(card)
    }

    private fun addSlider(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        min: Int,
        max: Int,
        step: Int,
        initial: Int,
        format: (Int) -> String,
        onCommit: (Int) -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            p.bottomMargin = dp(8)
            layoutParams = p
            setBackgroundResource(R.drawable.settings_card_bg)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleTv = TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.textPrimary))
            val tp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = tp
        }
        val valueTv = TextView(this).apply {
            text = format(initial)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.accent))
        }
        top.addView(titleTv)
        top.addView(valueTv)

        val sub = TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.textSecondary))
            visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
        }

        val span = if (step > 0) (max - min) / step else 0
        val sb = SeekBar(this).apply {
            this.max = span
            progress = ((initial - min) / step).coerceIn(0, span)
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                valueTv.text = format(min + progress * step)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                val v = min + (s?.progress ?: 0) * step
                onCommit(v)
            }
        })

        card.addView(top)
        card.addView(sub)
        card.addView(sb)
        parent.addView(card)
    }

    private fun addAboutCard(
        parent: LinearLayout,
        cPrimary: Int,
        cSecondary: Int,
        cAccent: Int
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            p.bottomMargin = dp(16)
            layoutParams = p
            setBackgroundResource(R.drawable.settings_card_bg)
        }
        card.addView(TextView(this).apply {
            text = getString(R.string.settings_about_app)
            textSize = 16f
            setTextColor(cAccent)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = "版本 ${BuildConfig.VERSION_NAME}"
            textSize = 12f
            setTextColor(cSecondary)
            setPadding(0, dp(4), 0, dp(8))
        })
        // 版权署名：品牌名（不暴露个人真实姓名）
        card.addView(TextView(this).apply {
            text = LegalTexts.COPYRIGHT_LINE
            textSize = 12f
            setTextColor(cSecondary)
            setPadding(0, dp(4), 0, dp(4))
        })
        // 免责声明（补强版）
        card.addView(TextView(this).apply {
            text = LegalTexts.DISCLAIMER
            textSize = 11f
            setTextColor(cSecondary)
            setPadding(0, dp(6), 0, dp(6))
        })
        // 法律文档入口
        val linkAgreement = makeLink("📄 《用户协议》", cAccent)
        linkAgreement.setOnClickListener { showTextDialog("《用户协议》", LegalTexts.USER_AGREEMENT) }
        card.addView(linkAgreement)
        val linkPrivacy = makeLink("🔒 隐私政策", cAccent)
        linkPrivacy.setOnClickListener { showTextDialog("隐私政策", LegalTexts.PRIVACY_POLICY) }
        card.addView(linkPrivacy)
        val linkOss = makeLink("📚 第三方开源许可", cAccent)
        linkOss.setOnClickListener { showTextDialog("第三方开源许可", LegalTexts.OPEN_SOURCE_NOTICES) }
        card.addView(linkOss)
        parent.addView(card)
    }

    /** 关于页可点击的法律文档条目 */
    private fun makeLink(text: String, cAccent: Int): TextView {
        return TextView(this).apply {
            this.text = "$text ›"
            textSize = 13f
            setTextColor(cAccent)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.library_card_bg)
            gravity = Gravity.CENTER
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            p.topMargin = dp(8)
            layoutParams = p
        }
    }

    /** 通用法律文案弹窗（用户协议 / 隐私政策 / 开源许可） */
    private fun showTextDialog(title: String, body: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton("知道了") { d, _ -> d.dismiss() }
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 舞币结算机制说明全文（Dialog 展示） */
    private val COIN_MECHANISM = """
        舞币是平台内的虚拟积分（非真实货币，不可提现或交易），仅用于在平台内下载他人分享的舞蹈。

        【如何获得】
        · 上传分享舞蹈：+5 舞币（每日最多 3 条）
        · 每日签到：+10 / +15 / +22 / +32 / +50 舞币（随等级提升）
        · 观看广告：+10 舞币
        · 跟跳奖励：每完成一轮跟跳，相似度 ≥95 得 0.5 币；同一支舞每日最多领取 3 次，换不同的舞蹈练习可持续获得奖励
        · 被动收益：你分享的舞蹈被他人下载 +1、被点赞 +1，需在「我的上传」中点「结算收益」领取

        【被动收益封顶】
        · 单支舞因点赞最多累计 +20、因下载最多累计 +20
        · 单支舞终生最多 +40，超出不再发放（防刷）

        【如何消耗】
        · 下载他人内容：-2 舞币（余额不足时无法下载）

        【重要说明】
        · 本平台不强制登录，舞币仅保存在本机；清空应用数据或卸载重装后，舞币与「我的上传」列表将一并归零。
        · 被动收益不会自动发放，需你主动在「我的上传」点击结算。
    """.trimIndent()

    private fun showCoinMechanismDialog() {
        AlertDialog.Builder(this)
            .setTitle("舞币结算机制")
            .setMessage(COIN_MECHANISM)
            .setPositiveButton("知道了") { d, _ -> d.dismiss() }
            .show()
    }
}
