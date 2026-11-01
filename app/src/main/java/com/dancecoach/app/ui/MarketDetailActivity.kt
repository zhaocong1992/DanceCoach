/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dancecoach.app.R
import com.dancecoach.app.domain.market.*
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

class MarketDetailActivity : AppCompatActivity(), CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    private lateinit var job: Job
    private lateinit var repo: MarketRepository
    private var item: DanceItem? = null
    private var detailVideo: VideoView? = null
    private var detailPlayPause: ImageView? = null
    private var coverText: TextView? = null
    private var coverImage: ImageView? = null
    private var detailLoading: ProgressBar? = null
    private var isPlaying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        job = SupervisorJob()
        OrientationUtil.apply(this)
        setContentView(R.layout.activity_market_detail)
        repo = MarketRepoProvider.get(this)
        val id = intent.getStringExtra("id") ?: ""

        detailVideo = findViewById(R.id.detailVideo)
        detailPlayPause = findViewById(R.id.detailPlayPause)
        coverText = findViewById(R.id.coverText)
        detailLoading = findViewById(R.id.detailLoading)
        // 详情请求未返回前显示转圈，避免误以为死机
        detailLoading?.visibility = android.view.View.VISIBLE

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        // 进入即内联播放：与本地舞蹈库一致，不再单独点「播放预览」跳窗
        detailPlayPause?.setOnClickListener { togglePlayInline() }

        launch {
            // 取详情：云端失败或已不存在时明确提示并关闭，不再回退本地示例（避免假内容误导）
            item = try {
                repo.get(id)
            } catch (e: Exception) {
                Toast.makeText(this@MarketDetailActivity, "加载失败：${e.message}", Toast.LENGTH_LONG).show()
                null
            }
            val entry = item
            if (entry == null) {
                detailLoading?.visibility = android.view.View.GONE
                Toast.makeText(this@MarketDetailActivity, "该舞蹈不存在或已删除", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            detailLoading?.visibility = android.view.View.GONE
            render(entry)
            // 点赞 / 收藏本机状态初始化与点击
            setupInteraction(entry)
            // 封面占位：用已生成的真实封面（data URL）；无封面则显示标题首字 + 纯色块
            showCover(entry)
            // 进入即内联自动播放
            startInlinePlay(entry)
            findViewById<TextView>(R.id.btnDownload).setOnClickListener { onDownload(entry) }
        }
    }

    override fun onDestroy() {
        job.cancel()
        detailVideo?.stopPlayback()
        super.onDestroy()
    }

    private fun render(entry: DanceItem) {
        findViewById<TextView>(R.id.titleText).text = entry.title
        findViewById<TextView>(R.id.authorText).text = "作者：${entry.authorName}"
        findViewById<TextView>(R.id.typeText).text =
            "类型：${if (entry.fileType == "video") "教学视频" else "动作库"}"
        findViewById<TextView>(R.id.playText).text = "播放：${entry.playCount}"
        findViewById<TextView>(R.id.downloadText).text = "下载：${entry.downloadCount}"
        findViewById<TextView>(R.id.descText).text =
            "描述：${if (entry.description.isBlank()) "（暂无）" else entry.description}"
    }

    /** 点赞（云端计数 + 本机去重）/ 收藏（纯本机）状态与点击 */
    private fun setupInteraction(entry: DanceItem) {
        val btnLike = findViewById<TextView>(R.id.btnLikeDetail)
        val btnFav = findViewById<TextView>(R.id.btnFavDetail)
        val liked = MarketPrefs.isLiked(this, entry.id)
        val faved = MarketPrefs.isFavorited(this, entry.id)
        btnLike.text = "❤ ${entry.likeCount}"
        btnLike.setTextColor(if (liked) Color.RED else ContextCompat.getColor(this, R.color.textSecondary))
        btnFav.text = if (faved) "★ 已收藏" else "☆ 收藏"
        btnFav.setTextColor(if (faved) ContextCompat.getColor(this, R.color.accent) else ContextCompat.getColor(this, R.color.textSecondary))

        btnLike.setOnClickListener {
            if (MarketPrefs.isLiked(this, entry.id)) {
                Toast.makeText(this, "你已经赞过啦", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            MarketPrefs.setLiked(this, entry.id, true)
            btnLike.setTextColor(Color.RED)
            btnLike.text = "❤ ${entry.likeCount + 1}"
            launch { try { repo.reportLike(entry.id) } catch (e: Exception) { /* 静默 */ } }
        }
        btnFav.setOnClickListener {
            val now = !MarketPrefs.isFavorited(this, entry.id)
            MarketPrefs.setFavorited(this, entry.id, now)
            btnFav.text = if (now) "★ 已收藏" else "☆ 收藏"
            btnFav.setTextColor(if (now) ContextCompat.getColor(this, R.color.accent) else ContextCompat.getColor(this, R.color.textSecondary))
            // 新收藏时累计收藏次数（"收藏家"徽章）
            if (now) RewardManager.onFavorite(this)
        }
    }

    private fun showCover(entry: DanceItem) {
        val cv = detailVideo
        val ct = coverText
        if (cv == null || ct == null) return
        // 真实封面优先（data URL）；否则首字 + 纯色块兜底
        if (entry.coverUrl.startsWith("data:image")) {
            try {
                val b64 = entry.coverUrl.substringAfter(",")
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val iv = ImageView(this)
                iv.setImageBitmap(bmp)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                val coverFrame = findViewById<FrameLayout>(R.id.cover)
                coverFrame.removeView(ct)
                coverFrame.addView(iv, 0)
                coverImage = iv
            } catch (e: Exception) {
                ct.setBackgroundColor(entry.coverColor)
                ct.text = entry.title.first().toString()
            }
        } else {
            ct.setBackgroundColor(entry.coverColor)
            ct.text = entry.title.first().toString()
        }
    }

    /**
     * 进入详情页即内联播放：复用页面内 VideoView，自动 start（与本地库一致）。
     * 先播放上报，再换临时 URL 播放；视频加载失败回退到封面占位。
     */
    private fun startInlinePlay(entry: DanceItem) {
        if (entry.fileId.isBlank()) return
        launch {
            // 播放上报（不影响播放，失败静默）
            try {
                repo.reportPlay(entry.id)
                entry.playCount++
                findViewById<TextView>(R.id.playText).text = "播放：${entry.playCount}"
            } catch (e: Exception) {
                MockMarketRepository.reportPlay(entry.id)
            }
            // 换临时 URL 并内联播放
            val url = try {
                repo.getFileUrl(entry.fileId)
            } catch (e: Exception) {
                Toast.makeText(this@MarketDetailActivity, "播放加载失败：${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            val cv = detailVideo ?: return@launch
            val ct = coverText
            val pp = detailPlayPause
            cv.visibility = android.view.View.VISIBLE
            cv.setVideoURI(Uri.parse(url))
            cv.setOnPreparedListener { mp ->
                mp.isLooping = true
                cv.start()
                isPlaying = true
                pp?.visibility = android.view.View.VISIBLE
                pp?.setImageResource(android.R.drawable.ic_media_pause)
                // 视频开始播放后隐藏封面占位，避免视频未覆盖区域透出静止封面
                ct?.visibility = android.view.View.GONE
                coverImage?.visibility = android.view.View.GONE
            }
            cv.setOnErrorListener { _, _, _ ->
                Toast.makeText(this@MarketDetailActivity, "视频加载失败，请重试", Toast.LENGTH_SHORT).show()
                cv.visibility = android.view.View.GONE
                ct?.visibility = android.view.View.VISIBLE
                coverImage?.visibility = android.view.View.VISIBLE
                true
            }
        }
    }

    private fun togglePlayInline() {
        val cv = detailVideo ?: return
        val pp = detailPlayPause ?: return
        if (isPlaying) {
            cv.pause()
            isPlaying = false
            pp.setImageResource(android.R.drawable.ic_media_play)
        } else {
            cv.start()
            isPlaying = true
            pp.setImageResource(android.R.drawable.ic_media_pause)
        }
    }

    /**
     * 下载：把市场视频保存到「本地舞蹈库」（而非躺平在 Movies 目录）。
     * 经 MarketLibraryBridge 流式下载并登记进库，提示用户已入库。
     */
    private fun onDownload(entry: DanceItem) {
        if (entry.fileId.isBlank()) {
            Toast.makeText(this, "该条目暂未上传视频文件", Toast.LENGTH_SHORT).show()
            return
        }
        // P3/P6：余额不足先拦截并推荐看广告；余额够则先入库、成功才扣费，失败回滚不扣
        if (!RewardManager.canDownload(this)) {
            showInsufficientDialog(entry)
            return
        }
        launch {
            // 1) 先入库（耗时/可能失败），此时不扣费；下载期间显示转圈
            detailLoading?.visibility = android.view.View.VISIBLE
            var imported: com.dancecoach.app.domain.VideoLibraryEntry? = null
            try {
                imported = MarketLibraryBridge.downloadIntoLibrary(
                    this@MarketDetailActivity, repo, entry.fileId, entry.title, entry.id
                )
            } catch (e: Exception) {
                detailLoading?.visibility = android.view.View.GONE
                if (e is IllegalStateException) {
                    // 入库校验未通过：弹窗说明原因（此时不扣舞币）
                    showDownloadRejectedDialog(e.message ?: "该视频不符合入库标准")
                } else {
                    Toast.makeText(this@MarketDetailActivity, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            detailLoading?.visibility = android.view.View.GONE
            if (imported == null) {
                Toast.makeText(this@MarketDetailActivity, "保存失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // 2) 入库成功 → 上报 + 扣费（仅本机）
            try {
                repo.reportDownload(entry.id)
                entry.downloadCount++
                findViewById<TextView>(R.id.downloadText).text = "下载：${entry.downloadCount}"
            } catch (e: Exception) {
                MockMarketRepository.reportDownload(entry.id)
            }
            RewardManager.applyDownloadCost(this@MarketDetailActivity)
            RewardPrefs.incDownloadCount(this@MarketDetailActivity)
            val cost = RewardManager.downloadCost()
            Toast.makeText(
                this@MarketDetailActivity,
                "已保存到舞蹈库（消耗 ${RewardManager.formatCoin(cost)} 舞币）",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 入库校验未通过：弹窗展示具体原因 */
    private fun showDownloadRejectedDialog(msg: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("无法保存该视频")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show()
    }

    /** 舞币不足：拦截下载，弹窗推荐看广告赚币（占位实现，真实接入后仅 RewardedAdBridge 内改动） */
    private fun showInsufficientDialog(entry: DanceItem) {
        val cost = RewardManager.downloadCost()
        android.app.AlertDialog.Builder(this)
            .setTitle("舞币不足")
            .setMessage("下载需 ${RewardManager.formatCoin(cost)} 舞币，当前余额不足。看广告可立即 +10 舞币后再下载。")
            .setPositiveButton("看广告 +10") { _, _ ->
                RewardedAdBridge.show(
                    this,
                    onRewarded = {
                        RewardManager.award(this, RewardReason.AD_WATCH)
                        Toast.makeText(this, "已获得 10 舞币，可再次点击下载", Toast.LENGTH_SHORT).show()
                    }
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
