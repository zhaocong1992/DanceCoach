/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.util.Base64
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dancecoach.app.R
import com.dancecoach.app.domain.market.*
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

class MarketActivity : AppCompatActivity(), CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    private lateinit var job: Job
    private lateinit var listView: ListView
    private lateinit var repo: MarketRepository
    private var sort = MarketSort.HOT
    private var categoryFilter = "全部"
    private var keywordFilter = ""
    private lateinit var adapter: DanceAdapter
    private var loadingBar: ProgressBar? = null
    private var emptyText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        job = SupervisorJob()
        OrientationUtil.apply(this)
        setContentView(R.layout.activity_market)
        repo = MarketRepoProvider.get(this)
        listView = findViewById(R.id.listView)
        loadingBar = findViewById(R.id.loadingBar)
        emptyText = findViewById(R.id.emptyText)
        adapter = DanceAdapter()
        listView.adapter = adapter

        findViewById<TextView>(R.id.tabHot).setOnClickListener { switchSort(MarketSort.HOT) }
        findViewById<TextView>(R.id.tabLatest).setOnClickListener { switchSort(MarketSort.LATEST) }
        findViewById<TextView>(R.id.fabUpload).setOnClickListener {
            startActivity(Intent(this, MarketUploadActivity::class.java))
        }
        findViewById<TextView>(R.id.btnMyUpload).setOnClickListener {
            startActivity(Intent(this, MarketMyListActivity::class.java).putExtra("mode", "mine"))
        }
        findViewById<TextView>(R.id.btnMyFav).setOnClickListener {
            startActivity(Intent(this, MarketMyListActivity::class.java).putExtra("mode", "favorites"))
        }

        // 搜索框：输入防抖后刷新
        val searchBox = findViewById<EditText>(R.id.searchBox)
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                keywordFilter = s?.toString()?.trim() ?: ""
                searchRunnable?.let { handler.removeCallbacks(it) }
                searchRunnable = Runnable { loadList() }
                handler.postDelayed(searchRunnable!!, 300)
            }
        })

        // 构建分类条（chip 动态生成）
        buildCategoryBar()

        listView.setOnItemClickListener { _, _, pos, _ ->
            val item = adapter.getItem(pos)
            val intent = Intent(this, MarketDetailActivity::class.java)
            intent.putExtra("id", item.id)
            startActivity(intent)
        }
        switchSort(MarketSort.HOT)
    }

    /** 动态生成分类 chips */
    private fun buildCategoryBar() {
        val bar = findViewById<LinearLayout>(R.id.categoryBar)
        bar.removeAllViews()
        MARKET_CATEGORIES.forEach { cat ->
            val chip = TextView(this).apply {
                text = cat
                textSize = 13f
                setPadding(28, 12, 28, 12)
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 12 }
                if (cat == categoryFilter) {
                    setBackgroundResource(R.drawable.market_tab_bg)
                    setTextColor(ContextCompat.getColor(this@MarketActivity, R.color.bg))
                } else {
                    setBackgroundColor(ContextCompat.getColor(this@MarketActivity, R.color.cardBg))
                    setTextColor(ContextCompat.getColor(this@MarketActivity, R.color.textSecondary))
                }
                setOnClickListener {
                    categoryFilter = cat
                    buildCategoryBar()
                    loadList()
                }
            }
            bar.addView(chip)
        }
    }

    override fun onResume() {
        super.onResume()
        updateMyUploadBadge()
        loadList()
    }

    /** 市场页「我的上传」入口角标：有可结算收益时提示「待结算 N」 */
    private fun updateMyUploadBadge() {
        val pending = RewardPrefs.totalPending(this)
        val btn = findViewById<TextView>(R.id.btnMyUpload)
        btn.text = if (pending > 0) "我的上传 · 待结算 $pending" else "我的上传"
    }

    override fun onDestroy() {
        job.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** 拉取列表：云端优先（已带分类/关键词参数）；云端失败时不再回退本地假数据，
     *  只显示"加载失败"空态，避免出现"点开又没有"的误导性示例。
     *  未配置云端（Mock 演示模式）时才直接展示本地示例数据。 */
    private fun loadList() {
        loadingBar?.visibility = View.VISIBLE
        emptyText?.visibility = View.GONE
        launch {
            var items: List<DanceItem> = emptyList()
            var failed = false
            try {
                items = applyFilter(repo.list(sort, categoryFilter, keywordFilter))
            } catch (e: Exception) {
                failed = true
                android.util.Log.w("DC_MARKET", "list 失败：${e.message}")
                if (CloudConfig.enabled(this@MarketActivity)) {
                    // 云端已配置：给明确失败反馈，不回退示例数据
                    Toast.makeText(
                        this@MarketActivity,
                        "加载失败，请检查网络后重试：${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    // 未配置云端：本地示例模式
                    items = applyFilter(MockMarketRepository.list(sort, categoryFilter, keywordFilter, ""))
                }
            }
            adapter.items = items
            adapter.notifyDataSetChanged()
            loadingBar?.visibility = View.GONE
            when {
                failed -> {
                    emptyText?.text = "加载失败，请检查网络后重试"
                    emptyText?.visibility = View.VISIBLE
                }
                items.isEmpty() -> {
                    emptyText?.text = "暂无舞蹈，去上传一支吧"
                    emptyText?.visibility = View.VISIBLE
                }
                else -> emptyText?.visibility = View.GONE
            }
        }
    }

    /** 前端兜底过滤（与云端过滤逻辑一致），防止云函数未部署新参数时筛选失效 */
    private fun applyFilter(items: List<DanceItem>): List<DanceItem> {
        var list = items
        if (categoryFilter.isNotBlank() && categoryFilter != "全部") {
            list = list.filter { it.category == categoryFilter }
        }
        if (keywordFilter.isNotBlank()) {
            val k = keywordFilter.lowercase()
            list = list.filter {
                it.title.lowercase().contains(k) || it.authorName.lowercase().contains(k)
            }
        }
        return list
    }

    private fun switchSort(s: MarketSort) {
        sort = s
        val hot = findViewById<TextView>(R.id.tabHot)
        val latest = findViewById<TextView>(R.id.tabLatest)
        if (s == MarketSort.HOT) {
            hot.setBackgroundResource(R.drawable.market_tab_bg)
            hot.setTextColor(ContextCompat.getColor(this, R.color.bg))
            latest.setBackgroundColor(0)
            latest.setTextColor(ContextCompat.getColor(this, R.color.textSecondary))
        } else {
            latest.setBackgroundResource(R.drawable.market_tab_bg)
            latest.setTextColor(ContextCompat.getColor(this, R.color.bg))
            hot.setBackgroundColor(0)
            hot.setTextColor(ContextCompat.getColor(this, R.color.textSecondary))
        }
        loadList()
    }

    inner class DanceAdapter : BaseAdapter() {
        var items: List<DanceItem> = emptyList()
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(this@MarketActivity)
                .inflate(R.layout.item_market_dance, parent, false)
            val item = items[p]
            v.findViewById<TextView>(R.id.titleText).text = item.title
            v.findViewById<TextView>(R.id.authorText).text = "作者：${item.authorName}"
            v.findViewById<TextView>(R.id.playText).text = "播放 ${item.playCount}"
            v.findViewById<TextView>(R.id.downloadText).text = "下载 ${item.downloadCount}"
            v.findViewById<TextView>(R.id.typeText).text =
                if (item.fileType == "video") "视频" else "动作库"
            v.findViewById<TextView>(R.id.difficultyText).text = "难度 ${item.difficulty}"
            // 封面：有内嵌 data URL 则解码显示真实首帧，否则回退纯色块+首字
            val coverBox = v.findViewById<FrameLayout>(R.id.coverBox)
            val coverText = v.findViewById<TextView>(R.id.coverText)
            val coverImage = v.findViewById<ImageView>(R.id.coverImage)
            var shown = false
            if (item.coverUrl.startsWith("data:image")) {
                try {
                    val b64 = item.coverUrl.substringAfter(",")
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        coverImage.setImageBitmap(bmp)
                        coverImage.visibility = View.VISIBLE
                        coverText.visibility = View.GONE
                        coverBox.setBackgroundColor(0xFF000000.toInt())
                        shown = true
                    }
                } catch (e: Exception) { /* 回退纯色块 */ }
            }
            if (!shown) {
                coverImage.visibility = View.GONE
                coverText.visibility = View.VISIBLE
                coverBox.setBackgroundColor(item.coverColor)
                coverText.text = item.title.first().toString()
            }

            // 点赞 / 收藏（本机 SP 记录状态，点赞计数云端统计）
            val btnLike = v.findViewById<TextView>(R.id.btnLike)
            val btnFav = v.findViewById<TextView>(R.id.btnFavorite)
            val liked = MarketPrefs.isLiked(this@MarketActivity, item.id)
            val faved = MarketPrefs.isFavorited(this@MarketActivity, item.id)
            btnLike.text = "❤ ${item.likeCount}"
            btnLike.setTextColor(if (liked) Color.RED else ContextCompat.getColor(this@MarketActivity, R.color.textSecondary))
            btnFav.text = if (faved) "★ 已收藏" else "☆ 收藏"
            btnFav.setTextColor(if (faved) ContextCompat.getColor(this@MarketActivity, R.color.accent) else ContextCompat.getColor(this@MarketActivity, R.color.textSecondary))

            btnLike.setOnClickListener {
                if (MarketPrefs.isLiked(this@MarketActivity, item.id)) {
                    Toast.makeText(this@MarketActivity, "你已经赞过啦", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                MarketPrefs.setLiked(this@MarketActivity, item.id, true)
                btnLike.setTextColor(Color.RED)
                btnLike.text = "❤ ${item.likeCount + 1}"
                launch {
                    try { repo.reportLike(item.id) } catch (e: Exception) { /* 云端失败静默 */ }
                }
            }
            btnFav.setOnClickListener {
                val now = !MarketPrefs.isFavorited(this@MarketActivity, item.id)
                MarketPrefs.setFavorited(this@MarketActivity, item.id, now)
                btnFav.text = if (now) "★ 已收藏" else "☆ 收藏"
                btnFav.setTextColor(if (now) ContextCompat.getColor(this@MarketActivity, R.color.accent) else ContextCompat.getColor(this@MarketActivity, R.color.textSecondary))
                // 新收藏时累计收藏次数（"收藏家"徽章）
                if (now) RewardManager.onFavorite(this@MarketActivity)
            }
            return v
        }
    }
}
