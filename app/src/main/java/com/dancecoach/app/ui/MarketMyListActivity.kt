/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dancecoach.app.R
import com.dancecoach.app.domain.market.*
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

/**
 * 市场「我的」列表页：
 * - mode = "mine"：我的上传。v2 改为【打开只渲染本地缓存、绝不联网】；只有点「结算收益」
 *   才调 syncMyUploads 结算被动币 + 刷新待结算。首次为空（老用户升级）静默 seed 一次。
 * - mode = "favorites"：我的收藏（读本机 SP 收藏集合，再 list 全部按 id 过滤）。
 * 复用 item_market_dance 卡片布局，点击进详情，点赞/收藏交互与首页一致。
 */
class MarketMyListActivity : AppCompatActivity(), CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    private lateinit var job: Job
    private lateinit var repo: MarketRepository
    private lateinit var mode: String
    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private lateinit var btnRefresh: TextView
    private var loadingBar: ProgressBar? = null
    private val items = mutableListOf<DanceItem>()
    private val pendingMap = mutableMapOf<String, Int>()   // 仅 mine：每舞待结算合计
    private lateinit var adapter: MyAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        job = SupervisorJob()
        OrientationUtil.apply(this)
        setContentView(R.layout.activity_market_my_list)
        repo = MarketRepoProvider.get(this)
        mode = intent.getStringExtra("mode") ?: "mine"

        listView = findViewById(R.id.listView)
        emptyText = findViewById(R.id.emptyText)
        btnRefresh = findViewById(R.id.btnRefresh)
        loadingBar = findViewById(R.id.loadingBar)
        adapter = MyAdapter()
        listView.adapter = adapter

        findViewById<TextView>(R.id.titleText).text =
            if (mode == "mine") "我的上传" else "我的收藏"
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        // 「结算收益」按钮仅 mine 有意义；favorites 隐藏
        if (mode == "mine") {
            btnRefresh.setOnClickListener { refreshSync() }
        } else {
            btnRefresh.visibility = View.GONE
        }

        listView.setOnItemClickListener { _, _, pos, _ ->
            val item = items[pos]
            startActivity(Intent(this, MarketDetailActivity::class.java).putExtra("id", item.id))
        }

        // 打开即渲染本地缓存（mine 不联网）；首次为空静默 seed
        renderCache()
        if (mode == "mine") maybeSeed()
    }

    override fun onResume() {
        super.onResume()
        renderCache()
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    /** 渲染本地缓存（mine：RewardPrefs；favorites：联网拉取） */
    private fun renderCache() {
        items.clear()
        pendingMap.clear()
        if (mode == "mine") {
            val metas = RewardPrefs.getAllMyUploadMetas(this)
            metas.values.forEach { m ->
                items.add(
                    DanceItem(
                        id = m.id,
                        title = m.title,
                        authorName = "我",
                        authorId = DeviceIdProvider.get(this),
                        category = "",
                        coverColor = colorFromId(m.id),
                        fileUrl = "",
                        fileType = "video",
                        downloadCount = m.downloadCount,
                        playCount = 0,
                        createdAt = 0,
                        coverUrl = m.coverUrl,
                        likeCount = m.likeCount
                    )
                )
                pendingMap[m.id] = m.pendingTotal()
            }
        } else {
            val favIds = MarketPrefs.getFavoritedIds(this)
            if (favIds.isNotEmpty()) {
                loadingBar?.visibility = View.VISIBLE
                launch {
                    try {
                        val all = repo.list(MarketSort.LATEST)
                        items.clear()
                        items.addAll(all.filter { favIds.contains(it.id) })
                        adapter.notifyDataSetChanged()
                    } catch (e: Exception) { /* 静默 */ }
                    loadingBar?.visibility = View.GONE
                    updateEmpty()
                }
            }
        }
        adapter.notifyDataSetChanged()
        updateEmpty()
    }

    private fun updateEmpty() {
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    /** P1 存量迁移：老用户升级后本地上传列表为空，首次静默 seed 一次（仅此一次） */
    private fun maybeSeed() {
        if (RewardPrefs.getAllMyUploadMetas(this).isNotEmpty()) return
        if (RewardPrefs.isSeedDone(this)) return
        launch {
            try {
                val me = DeviceIdProvider.get(this@MarketMyListActivity)
                val list = repo.list(MarketSort.LATEST, "", "", me).filter { it.authorId == me }
                for (it in list) {
                    RewardPrefs.addMyUploadId(this@MarketMyListActivity, it.id)
                    RewardPrefs.putMyUploadMeta(
                        this@MarketMyListActivity,
                        RewardPrefs.MyUploadMeta(
                            id = it.id,
                            title = it.title,
                            coverUrl = it.coverUrl,
                            likeCount = it.likeCount,
                            downloadCount = it.downloadCount,
                            pendingLike = kotlin.math.min(20, it.likeCount),
                            pendingDL = kotlin.math.min(20, it.downloadCount)
                        )
                    )
                }
            } catch (e: Exception) { /* 联网失败静默，下次再试 */ }
            RewardPrefs.setSeedDone(this@MarketMyListActivity)
            renderCache()
        }
    }

    /** 「结算收益」：仅 mine。按本地上传 ID 列表向云端结算被动币，更新计数与待结算 */
    private fun refreshSync() {
        val ids = RewardPrefs.getMyUploadIds(this@MarketMyListActivity).toList()
        if (ids.isEmpty()) {
            Toast.makeText(this@MarketMyListActivity, "暂无上传记录", Toast.LENGTH_SHORT).show()
            return
        }
        btnRefresh.isEnabled = false
        val oldText = btnRefresh.text
        btnRefresh.text = "结算中…"
        loadingBar?.visibility = View.VISIBLE
        launch {
            try {
                val results = repo.syncMyUploads(ids)
                var gained = 0
                val toRemove = mutableListOf<String>()
                for (r in results) {
                    if (!r.found) { toRemove.add(r.id); continue }
                    gained += r.deltaTotal
                    val meta = RewardPrefs.getMyUploadMeta(this@MarketMyListActivity, r.id)
                    RewardPrefs.putMyUploadMeta(
                        this@MarketMyListActivity,
                        RewardPrefs.MyUploadMeta(
                            id = r.id,
                            title = meta?.title ?: "",
                            coverUrl = meta?.coverUrl ?: "",
                            likeCount = r.likeCount,
                            downloadCount = r.downloadCount,
                            pendingLike = r.pendingLike,
                            pendingDL = r.pendingDL
                        )
                    )
                }
                for (id in toRemove) RewardPrefs.removeMyUploadId(this@MarketMyListActivity, id)
                if (gained > 0) RewardManager.creditPassiveDelta(this@MarketMyListActivity, gained)
                Toast.makeText(
                    this@MarketMyListActivity,
                    if (gained > 0) "结算完成，+${gained} 舞币" else "暂无新收益",
                    Toast.LENGTH_SHORT
                ).show()
                renderCache()
            } catch (e: Exception) {
                Toast.makeText(this@MarketMyListActivity, "结算失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                btnRefresh.isEnabled = true
                btnRefresh.text = oldText
                loadingBar?.visibility = View.GONE
            }
        }
    }

    private inner class MyAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(p: Int): Any = items[p]
        override fun getItemId(p: Int): Long = p.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_market_dance, parent, false)
            val item = items[pos]

            val coverBox = v.findViewById<FrameLayout>(R.id.coverBox)
            val coverImage = v.findViewById<ImageView>(R.id.coverImage)
            val coverText = v.findViewById<TextView>(R.id.coverText)
            val titleText = v.findViewById<TextView>(R.id.titleText)
            val authorText = v.findViewById<TextView>(R.id.authorText)
            val playText = v.findViewById<TextView>(R.id.playText)
            val downloadText = v.findViewById<TextView>(R.id.downloadText)
            val typeText = v.findViewById<TextView>(R.id.typeText)
            val difficultyText = v.findViewById<TextView>(R.id.difficultyText)
            val btnLike = v.findViewById<TextView>(R.id.btnLike)
            val btnFav = v.findViewById<TextView>(R.id.btnFavorite)

            titleText.text = item.title
            authorText.text = "作者：${item.authorName}"
            playText.text = "播放 ${item.playCount}"
            // mine 模式下展示「待结算」角标
            val pending = if (mode == "mine") (pendingMap[item.id] ?: 0) else 0
            downloadText.text = "下载 ${item.downloadCount}" +
                if (pending > 0) "  ·  待结算 $pending" else ""
            typeText.text = if (item.fileType == "video") "教学视频" else "动作库"
            difficultyText.text = "难度 ${item.difficulty}"

            // 封面：真实 data URL 优先，否则首字 + 纯色块
            val shown = item.coverUrl.startsWith("data:image")
            coverImage.visibility = if (shown) View.VISIBLE else View.GONE
            coverText.visibility = if (shown) View.GONE else View.VISIBLE
            if (shown) {
                try {
                    val bytes = android.util.Base64.decode(
                        item.coverUrl.substringAfter(","), android.util.Base64.DEFAULT
                    )
                    coverImage.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                } catch (e: Exception) {
                    coverImage.visibility = View.GONE
                    coverText.visibility = View.VISIBLE
                    coverBox.setBackgroundColor(item.coverColor)
                    coverText.text = item.title.first().toString()
                }
            } else {
                coverBox.setBackgroundColor(item.coverColor)
                coverText.text = item.title.first().toString()
            }

            // 点赞（云端计数 + 本机去重）
            val liked = MarketPrefs.isLiked(v.context, item.id)
            btnLike.text = "❤ ${item.likeCount}"
            btnLike.setTextColor(if (liked) Color.RED else ContextCompat.getColor(v.context, R.color.textSecondary))
            btnLike.setOnClickListener {
                if (MarketPrefs.isLiked(v.context, item.id)) {
                    Toast.makeText(v.context, "你已经赞过啦", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                MarketPrefs.setLiked(v.context, item.id, true)
                btnLike.setTextColor(Color.RED)
                btnLike.text = "❤ ${item.likeCount + 1}"
                launch { try { repo.reportLike(item.id) } catch (e: Exception) {} }
            }

            // 收藏（纯本机）
            val faved = MarketPrefs.isFavorited(v.context, item.id)
            btnFav.text = if (faved) "★ 已收藏" else "☆ 收藏"
            btnFav.setTextColor(
                if (faved) ContextCompat.getColor(v.context, R.color.accent)
                else ContextCompat.getColor(v.context, R.color.textSecondary)
            )
            btnFav.setOnClickListener {
                val now = !MarketPrefs.isFavorited(v.context, item.id)
                MarketPrefs.setFavorited(v.context, item.id, now)
                btnFav.text = if (now) "★ 已收藏" else "☆ 收藏"
                btnFav.setTextColor(
                    if (now) ContextCompat.getColor(v.context, R.color.accent)
                    else ContextCompat.getColor(v.context, R.color.textSecondary)
                )
                // 新收藏时累计收藏次数（"收藏家"徽章）
                if (now) RewardManager.onFavorite(v.context)
            }

            return v
        }
    }

    private fun colorFromId(id: String): Int {
        val palette = intArrayOf(
            0xFF1A3A5C.toInt(), 0xFF3A1A5C.toInt(), 0xFF1A5C3A.toInt(),
            0xFF5C1A1A.toInt(), 0xFF5C4A1A.toInt(), 0xFF1A5C5C.toInt()
        )
        val h = if (id.isEmpty()) 0 else id.hashCode()
        return palette[Math.abs(h) % palette.size]
    }
}
