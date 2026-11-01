/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import com.dancecoach.app.R
import com.dancecoach.app.data.VideoCacheManager
import com.dancecoach.app.data.VideoValidator
import com.dancecoach.app.domain.AnalysisManager
import com.dancecoach.app.domain.AnalysisStatus
import com.dancecoach.app.domain.VideoLibraryEntry
import kotlinx.coroutines.*

class LibraryActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var btnBack: ImageView
    private lateinit var btnImport: LinearLayout
    private lateinit var statsText: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var videoGrid: LinearLayout
    private lateinit var emptyState: LinearLayout
    private lateinit var deleteOverlay: FrameLayout
    private lateinit var overlayText: TextView

    private val entries = mutableListOf<VideoLibraryEntry>()
    private val cardViews = mutableMapOf<String, View>()

    private val progressObserver = Observer<AnalysisManager.Result> { result ->
        updateCardProgress(result)
        if (result.status == AnalysisStatus.ANALYZED ||
            result.status == AnalysisStatus.WARNING ||
            result.status == AnalysisStatus.FAILED) {
            // 分析完成，刷新数据
            refreshLibrary()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationUtil.apply(this)
        setContentView(R.layout.activity_library)

        AnalysisManager.init(this)

        btnBack = findViewById(R.id.btnBack)
        btnImport = findViewById(R.id.btnImport)
        statsText = findViewById(R.id.statsText)
        scrollView = findViewById(R.id.scrollView)
        videoGrid = findViewById(R.id.videoGrid)
        emptyState = findViewById(R.id.emptyState)
        deleteOverlay = findViewById(R.id.deleteOverlay)
        overlayText = findViewById(R.id.overlayText)

        btnBack.setOnClickListener { finish() }
        btnImport.setOnClickListener { openVideoPicker() }

        AnalysisManager.progressLiveData.observe(this, progressObserver)
    }

    override fun onResume() {
        super.onResume()
        refreshLibrary()
    }

    private fun refreshLibrary() {
        scope.launch {
            val lib = withContext(Dispatchers.IO) {
                VideoCacheManager.loadLibrary(this@LibraryActivity)
            }
            entries.clear()
            entries.addAll(lib.entries)
            updateUI()
        }
    }

    private fun updateUI() {
        if (entries.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            scrollView.visibility = View.GONE
            statsText.visibility = View.GONE
            return
        }

        emptyState.visibility = View.GONE
        scrollView.visibility = View.VISIBLE
        statsText.visibility = View.VISIBLE

        // 统计
        val analyzed = entries.count {
            it.analysisStatus == AnalysisStatus.ANALYZED ||
            it.analysisStatus == AnalysisStatus.WARNING
        }
        val avgScore = entries.filter { it.bestScore >= 0 }
            .map { it.bestScore }
            .takeIf { it.isNotEmpty() }
            ?.average()?.toInt() ?: 0
        statsText.text = "${entries.size}个视频 · ${analyzed}已分析" +
            if (avgScore > 0) " · 均分${avgScore}" else ""

        // 等布局完成后按 scrollView 实际宽度构建网格（避免导航栏/挖孔导致的误差）
        scrollView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                scrollView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                buildGrid()
            }
        })
    }

    /** 用 scrollView 实际可用宽度构建网格，每行 4 列，超出换行 */
    private fun buildGrid() {
        if (entries.isEmpty()) return

        videoGrid.removeAllViews()
        cardViews.clear()

        val density = resources.displayMetrics.density
        val columns = if (resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE) 4 else 2
        val cardMarginEnd = (10 * density).toInt()
        val cardMarginBottom = (10 * density).toInt()
        val hPadding = videoGrid.paddingLeft + videoGrid.paddingRight
        val containerWidth = scrollView.width.coerceAtLeast(1)
        // 可用宽度 = 容器宽 - 两侧padding - 3个卡片间距 - 滚动条余量
        val available = containerWidth - hPadding - cardMarginEnd * (columns - 1) - (4 * density).toInt()
        val cardWidth = (available / columns).coerceAtLeast((100 * density).toInt())

        var currentRow = newRow()
        var count = 0

        entries.forEachIndexed { index, entry ->
            val card = createCard(entry, cardWidth, cardMarginEnd, cardMarginBottom)
            cardViews[entry.id] = card
            currentRow.addView(card)
            count++

            // 每满 4 个换行
            if (count == columns && index != entries.lastIndex) {
                videoGrid.addView(currentRow)
                currentRow = newRow()
                count = 0
            }
        }

        videoGrid.addView(currentRow)
    }

    private fun newRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun createCard(
        entry: VideoLibraryEntry,
        cardWidth: Int,
        cardMarginEnd: Int,
        cardMarginBottom: Int
    ): View {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.item_library_video, videoGrid, false)

        val params = LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.setMargins(0, 0, cardMarginEnd, cardMarginBottom)
        view.layoutParams = params

        // 缩略图比例：横屏 16:9；竖屏 9:16（与竖屏素材一致）
        val thumb = view.findViewById<ImageView>(R.id.thumb)
        val thumbLayout = thumb.layoutParams as FrameLayout.LayoutParams
        val thumbRatio = if (resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE) 9f / 16f else 16f / 9f
        thumbLayout.height = (cardWidth * thumbRatio).toInt()
        thumb.layoutParams = thumbLayout

        entry.thumbnailPath?.let { path ->
            try {
                val bmp = BitmapFactory.decodeFile(path)
                if (bmp != null) thumb.setImageBitmap(bmp)
            } catch (_: Exception) {}
        }

        // 名称
        view.findViewById<TextView>(R.id.name).text = entry.displayName

        // 时长
        view.findViewById<TextView>(R.id.duration).text = formatDuration(entry.durationMs)

        // 状态
        updateCardDisplay(view, entry)

        // 点击 → 直接进入动作预审页（开始练习/重新分析/重命名等操作都在预审页内）
        view.setOnClickListener {
            startActivity(Intent(this, ActionReviewActivity::class.java).apply {
                putExtra("entryId", entry.id)
            })
        }

        // 删除按钮 → 弹确认框（单独点击，不会触发卡片预览）
        view.findViewById<TextView>(R.id.btnDelete).setOnClickListener {
            confirmDelete(entry)
        }

        return view
    }

    /** 删除确认：先取消后台分析，再彻底清除库记录 + 缓存目录（分段/缩略图/配置） */
    private fun confirmDelete(entry: VideoLibraryEntry) {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("删除舞蹈")
            .setMessage("确定删除「${entry.displayName}」吗？\n将同时清除该舞蹈的缓存数据、分段记录与所有配置，且无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                deleteEntryWithProgress(entry)
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(getColor(R.color.failure))
        }
        dialog.show()
    }

    /** 弹出等待遮罩禁止操作，后台删除完成后整体刷新库 */
    private fun deleteEntryWithProgress(entry: VideoLibraryEntry) {
        // 1. 立即显示等待遮罩，拦截所有点击
        deleteOverlay.visibility = View.VISIBLE

        scope.launch {
            // 2. 取消该视频可能正在排队/进行的分析
            AnalysisManager.cancel(entry.id)
            // 3. 彻底删除：库记录 + 缓存目录（分段/缩略图/配置）
            withContext(Dispatchers.IO) {
                VideoCacheManager.deleteEntry(this@LibraryActivity, entry.id)
            }
            // 4. 删除完成，整体重新加载并重建列表（与遮罩同协程，确保先更新再收起遮罩）
            val lib = withContext(Dispatchers.IO) {
                VideoCacheManager.loadLibrary(this@LibraryActivity)
            }
            entries.clear()
            entries.addAll(lib.entries)
            updateUI()
            // 5. 收起遮罩
            deleteOverlay.visibility = View.GONE
            Toast.makeText(this@LibraryActivity, "已删除", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCardDisplay(view: View, entry: VideoLibraryEntry) {
        val badge = view.findViewById<TextView>(R.id.statusBadge)
        val progress = view.findViewById<ProgressBar>(R.id.analysisProgress)
        val scoreText = view.findViewById<TextView>(R.id.scoreText)
        val statusText = view.findViewById<TextView>(R.id.statusText)

        when (entry.analysisStatus) {
            AnalysisStatus.NONE -> {
                badge.visibility = View.VISIBLE
                badge.text = "待审"
                badge.setTextColor(getColor(R.color.textSecondary))
                badge.setBackgroundResource(R.drawable.status_badge_bg)
                progress.visibility = View.GONE
                scoreText.text = ""
                statusText.text = "未分析"
            }
            AnalysisStatus.QUEUED -> {
                badge.visibility = View.VISIBLE
                badge.text = "排队"
                badge.setTextColor(getColor(R.color.accent))
                progress.visibility = View.VISIBLE
                progress.progress = 0
                scoreText.text = ""
                statusText.text = "排队中"
            }
            AnalysisStatus.ANALYZING -> {
                badge.visibility = View.GONE
                progress.visibility = View.VISIBLE
                progress.progress = entry.analysisProgress
                scoreText.text = "${entry.analysisProgress}%"
                statusText.text = "分析中"
            }
            AnalysisStatus.ANALYZED -> {
                badge.visibility = View.VISIBLE
                badge.text = "合格"
                badge.setTextColor(getColor(R.color.success))
                progress.visibility = View.GONE
                scoreText.text = if (entry.bestScore >= 0) "${entry.bestScore.toInt()}分" else ""
                statusText.text = if (entry.actionQuality >= 0) "质量${entry.actionQuality.toInt()}" else "已分析"
            }
            AnalysisStatus.WARNING -> {
                badge.visibility = View.VISIBLE
                badge.text = "警告"
                badge.setTextColor(getColor(R.color.warning))
                progress.visibility = View.GONE
                scoreText.text = if (entry.bestScore >= 0) "${entry.bestScore.toInt()}分" else ""
                statusText.text = entry.analysisMessage ?: "有警告"
            }
            AnalysisStatus.FAILED -> {
                badge.visibility = View.VISIBLE
                badge.text = "不合格"
                badge.setTextColor(getColor(R.color.failure))
                progress.visibility = View.GONE
                scoreText.text = ""
                statusText.text = entry.analysisMessage ?: "不合格"
            }
        }
    }

    private fun updateCardProgress(result: AnalysisManager.Result) {
        val view = cardViews[result.entryId] ?: return
        val entry = entries.find { it.id == result.entryId } ?: return

        // 更新本地数据
        val idx = entries.indexOf(entry)
        entries[idx] = entry.copy(
            analysisStatus = result.status,
            analysisProgress = result.progress,
            actionQuality = result.actionQuality,
            detectionRate = result.detectionRate,
            avgConfidence = result.avgConfidence,
            validFrameCount = result.validFrameCount,
            segmentCount = result.segmentCount,
            difficulty = result.difficulty,
            analysisMessage = result.message
        )
        updateCardDisplay(view, entries[idx])
    }

    private fun openVideoPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(Intent.createChooser(intent, "选择舞蹈视频"), REQ_PICK_VIDEOS)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_VIDEOS || resultCode != RESULT_OK) return

        val uris = mutableListOf<Uri>()
        val clipData = data?.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                uris.add(clipData.getItemAt(i).uri)
            }
        } else {
            data?.data?.let { uris.add(it) }
        }

        if (uris.isEmpty()) return

        // 持久化 URI 访问权限
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
        }

        // 逐个校验入库标准（竖屏/单人/身高占比/≤30s/≤30MB），全部通过才写入舞蹈库
        scope.launch {
            overlayText.text = "校验视频素材中…"
            deleteOverlay.visibility = View.VISIBLE

            var imported = 0
            val failed = mutableListOf<Pair<String, String>>()
            for (uri in uris) {
                val name = queryDisplayName(uri)
                val result = withContext(Dispatchers.IO) {
                    VideoValidator.validate(this@LibraryActivity, uri)
                }
                if (!result.passed) {
                    failed.add(name to result.reason)
                    continue
                }
                val entry = withContext(Dispatchers.IO) {
                    VideoCacheManager.addVideoMetadata(this@LibraryActivity, uri)
                }
                if (entry != null) {
                    imported++
                    // 自动加入分析队列
                    AnalysisManager.enqueue(this@LibraryActivity, entry)
                } else {
                    failed.add(name to "导入失败，请重试")
                }
            }
            deleteOverlay.visibility = View.GONE

            if (failed.isEmpty()) {
                Toast.makeText(this@LibraryActivity,
                    "已导入 $imported 个视频，开始自动分析",
                    Toast.LENGTH_SHORT).show()
                refreshLibrary()
            } else {
                refreshLibrary()
                showImportResultDialog(imported, failed)
            }
        }
    }

    /** 弹窗汇总导入结果：通过的条数 + 每个未通过视频及具体原因 */
    private fun showImportResultDialog(imported: Int, failed: List<Pair<String, String>>) {
        val sb = StringBuilder()
        if (imported > 0) sb.append("✅ 已导入 $imported 个视频\n\n")
        sb.append("以下视频未通过校验，未导入：\n")
        for ((name, reason) in failed) {
            sb.append("· $name\n$reason\n\n")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("导入结果")
            .setMessage(sb.toString().trim())
            .setPositiveButton("知道了", null)
            .show()
    }

    /** 取视频显示名（用于校验失败提示）；查询失败回退到 URI 尾段 */
    private fun queryDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(
                uri,
                arrayOf(MediaStore.Video.Media.DISPLAY_NAME, OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            } ?: uri.lastPathSegment?.substringBeforeLast(".") ?: "未知视频"
        } catch (e: Exception) {
            uri.lastPathSegment?.substringBeforeLast(".") ?: "未知视频"
        }
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
    }

    companion object {
        private const val REQ_PICK_VIDEOS = 3001
    }
}
