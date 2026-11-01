/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import com.dancecoach.app.R
import com.dancecoach.app.data.VideoCacheManager
import com.dancecoach.app.domain.AnalysisManager
import com.dancecoach.app.domain.market.MarketLibraryBridge
import com.dancecoach.app.domain.market.MarketRepoProvider
import com.dancecoach.app.domain.AnalysisStatus
import com.dancecoach.app.domain.VideoLibraryEntry
import com.dancecoach.app.ml.PoseExtractor
import kotlinx.coroutines.*

class ActionReviewActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var btnBack: ImageView
    private lateinit var btnRename: TextView
    private lateinit var btnReanalyze: TextView
    private lateinit var titleText: TextView
    private lateinit var videoView: VideoView
    private lateinit var skeletonOverlay: SkeletonOverlayView
    private lateinit var noPoseHint: TextView
    private lateinit var btnPlayPause: ImageView
    private lateinit var analyzingOverlay: LinearLayout
    private lateinit var analyzingText: TextView
    private lateinit var timeline: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var qualityScore: TextView
    private lateinit var qualityLabel: TextView
    private lateinit var detectionRate: TextView
    private lateinit var avgConfidence: TextView
    private lateinit var validFrames: TextView
    private lateinit var segmentCount: TextView
    private lateinit var difficulty: TextView
    private lateinit var analysisProgress: ProgressBar
    private lateinit var messageText: TextView
    private lateinit var btnConfirm: LinearLayout
    private lateinit var btnDelete: LinearLayout
    private lateinit var metricsPanel: View
    private lateinit var skeletonPane: View
    private lateinit var videoContainer: View
    private lateinit var timelineLayout: View

    private var entry: VideoLibraryEntry? = null
    private var poseExtractor: PoseExtractor? = null
    private var isPlaying = false
    private var detectionJob: Job? = null
    private var userSeeking = false
    private var isRemote = false
    private lateinit var btnShare: TextView
    private lateinit var btnSaveToLibrary: TextView

    private val progressObserver = Observer<AnalysisManager.Result> { result ->
        if (result.entryId != entry?.id) return@Observer
        updateMetrics(result)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationUtil.apply(this)
        setContentView(R.layout.activity_action_review)

        bindViews()
        btnBack.setOnClickListener { finish() }

        val remoteUrl = intent.getStringExtra("remoteUrl")
        val remoteTitle = intent.getStringExtra("remoteTitle")

        // 远程模式：来自舞蹈市场的视频，在应用内直接预览（不再跳外部播放器）
        if (remoteUrl != null) {
            setupRemoteMode(remoteUrl, remoteTitle)
            return
        }

        // 本地库模式：来自舞蹈库，可「分享到市场」形成闭环
        btnShare.visibility = View.VISIBLE

        AnalysisManager.init(this)

        val entryId = intent.getStringExtra("entryId") ?: run {
            finish()
            return
        }

        scope.launch {
            val lib = withContext(Dispatchers.IO) {
                VideoCacheManager.loadLibrary(this@ActionReviewActivity)
            }
            entry = lib.entries.find { it.id == entryId } ?: run {
                Toast.makeText(this@ActionReviewActivity, "视频不存在", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            val e = entry!!
            titleText.text = "动作预审 · ${e.displayName}"

            // 本地库视频 → 分享到市场（形成闭环）
            btnShare.setOnClickListener {
                startActivity(Intent(this@ActionReviewActivity, MarketUploadActivity::class.java).apply {
                    putExtra("entryId", e.id)
                })
            }

            // 初始化 PoseExtractor（独立实例，不与 AnalysisManager 冲突）
            val extractor = PoseExtractor(this@ActionReviewActivity)
            poseExtractor = extractor
            extractor.prepareLiveSource(this@ActionReviewActivity, Uri.parse(e.uri))

            // 设置视频
            try {
                videoView.setVideoURI(Uri.parse(e.uri))
                videoView.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    val duration = mp.duration
                    timeline.max = duration
                    timeLabel.text = "0:00 / ${formatDuration(duration.toLong())}"
                    // 自动开始播放
                    videoView.start()
                    isPlaying = true
                    updatePlayPauseIcon()
                    startDetectionLoop()
                }
                videoView.setOnCompletionListener {
                    // isLooping=true 时不会触发，但保留兜底
                    videoView.seekTo(0)
                    videoView.start()
                }
            } catch (_: Exception) {}

            // 显示指标
            displayMetrics(e)

            // 如果正在分析，显示进度
            if (e.analysisStatus == AnalysisStatus.ANALYZING ||
                e.analysisStatus == AnalysisStatus.QUEUED) {
                showAnalyzing(e.analysisProgress)
            }
        }

        btnRename.setOnClickListener { showRenameDialog() }
        btnReanalyze.setOnClickListener {
            entry?.let { AnalysisManager.enqueue(this, it.copy(analysisStatus = AnalysisStatus.QUEUED)) }
        }
        btnDelete.setOnClickListener { showDeleteConfirm() }
        btnConfirm.setOnClickListener {
            entry?.let { e ->
                val intent = Intent(this, TeachingActivity::class.java).apply {
                    putExtra("entryId", e.id)
                }
                startActivity(intent)
                finish()
            }
        }

        btnPlayPause.setOnClickListener { onPlayPauseClick() }

        // 时间轴：用户拖动时 seek，否则自动跟随
        timeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    videoView.seekTo(progress)
                    val dur = videoView.duration
                    if (dur > 0) {
                        timeLabel.text = "${formatDuration(progress.toLong())} / ${formatDuration(dur.toLong())}"
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { userSeeking = false }
        })

        AnalysisManager.progressLiveData.observe(this, progressObserver)
    }

    /** 远程（市场视频）预览模式：内联播放，隐藏本地库专属控件 */
    private fun setupRemoteMode(remoteUrl: String, remoteTitle: String?) {
        isRemote = true
        titleText.text = "市场预览 · ${remoteTitle ?: "视频"}"

        // 隐藏本地库专属操作
        btnRename.visibility = View.GONE
        btnReanalyze.visibility = View.GONE
        btnShare.visibility = View.GONE
        btnSaveToLibrary.visibility = View.VISIBLE

        // 隐藏指标面板与骨架窗格（远程视频无本地分析数据）
        metricsPanel.visibility = View.GONE
        skeletonPane.visibility = View.GONE

        // 视频区占满宽度：解除对 metricsPanel 的约束，改挂到父容器
        val vlp = videoContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        vlp.endToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        vlp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        videoContainer.layoutParams = vlp
        val tlp = timelineLayout.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        tlp.endToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        tlp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        timelineLayout.layoutParams = tlp

        btnSaveToLibrary.setOnClickListener {
            val fileId = intent.getStringExtra("remoteFileId") ?: run {
                Toast.makeText(this, "缺少文件信息，无法保存", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveRemoteToLibrary(fileId, remoteTitle)
        }

        try {
            videoView.setVideoURI(Uri.parse(remoteUrl))
            videoView.setOnPreparedListener { mp ->
                mp.isLooping = true
                val duration = mp.duration
                timeline.max = duration
                timeLabel.text = "0:00 / ${formatDuration(duration.toLong())}"
                videoView.start()
                isPlaying = true
                updatePlayPauseIcon()
            }
            videoView.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, "视频加载失败，请重试", Toast.LENGTH_SHORT).show()
                true
            }
        } catch (_: Exception) {}
    }

    /** 把当前预览的市场视频保存到本地舞蹈库 */
    private fun saveRemoteToLibrary(fileId: String, title: String?) {
        val repo = MarketRepoProvider.get(this)
        scope.launch {
            try {
                val imported = MarketLibraryBridge.downloadIntoLibrary(
                    this@ActionReviewActivity, repo, fileId, title ?: "市场视频", fileId
                )
                if (imported != null) {
                    Toast.makeText(
                        this@ActionReviewActivity,
                        "已保存到舞蹈库：${imported.displayName}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this@ActionReviewActivity, "保存失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ActionReviewActivity, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 播放/暂停：远程模式仅切换播放；本地模式叠加骨架检测循环 */
    private fun onPlayPauseClick() {
        if (isPlaying) {
            videoView.pause()
            isPlaying = false
            if (!isRemote) stopDetectionLoop()
        } else {
            videoView.start()
            isPlaying = true
            if (!isRemote) startDetectionLoop()
        }
        updatePlayPauseIcon()
    }

    /**
     * 实时检测循环：跟随视频播放位置取帧并检测动作骨架，更新叠加层。
     */
    private fun startDetectionLoop() {
        if (detectionJob?.isActive == true) return
        detectionJob = scope.launch(Dispatchers.Main) {
            while (isPlaying && isActive) {
                val pos = videoView.currentPosition
                val dur = videoView.duration
                // 更新时间轴
                if (!userSeeking && dur > 0) {
                    timeline.progress = pos
                    timeLabel.text = "${formatDuration(pos.toLong())} / ${formatDuration(dur.toLong())}"
                }
                // 异步检测当前帧的骨架
                val extractor = poseExtractor ?: break
                val landmarks = withContext(Dispatchers.IO) {
                    extractor.detectLiveFrameAt(pos.toLong())
                }
                if (landmarks != null && landmarks.isNotEmpty()) {
                    skeletonOverlay.updatePose(landmarks, false)
                    noPoseHint.visibility = View.GONE
                } else {
                    skeletonOverlay.updatePose(null, false)
                    noPoseHint.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun stopDetectionLoop() {
        detectionJob?.cancel()
        detectionJob = null
    }

    private fun updatePlayPauseIcon() {
        if (isPlaying) {
            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            btnPlayPause.visibility = View.GONE
        } else {
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            btnPlayPause.visibility = View.VISIBLE
        }
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        btnRename = findViewById(R.id.btnRename)
        btnReanalyze = findViewById(R.id.btnReanalyze)
        titleText = findViewById(R.id.titleText)
        videoView = findViewById(R.id.videoView)
        skeletonOverlay = findViewById(R.id.skeletonOverlay)
        // 预览页喂的是已物理转正的正立帧（PoseExtractor.detectLiveFrameAt 内部 uprightBitmap），
        // 必须关闭轴转置，否则骨架会被画横躺（与左侧原视频方向不符）。
        skeletonOverlay.transpose = false
        noPoseHint = findViewById(R.id.noPoseHint)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        analyzingOverlay = findViewById(R.id.analyzingOverlay)
        analyzingText = findViewById(R.id.analyzingText)
        timeline = findViewById(R.id.timeline)
        timeLabel = findViewById(R.id.timeLabel)
        qualityScore = findViewById(R.id.qualityScore)
        qualityLabel = findViewById(R.id.qualityLabel)
        detectionRate = findViewById(R.id.detectionRate)
        avgConfidence = findViewById(R.id.avgConfidence)
        validFrames = findViewById(R.id.validFrames)
        segmentCount = findViewById(R.id.segmentCount)
        difficulty = findViewById(R.id.difficulty)
        analysisProgress = findViewById(R.id.analysisProgress)
        messageText = findViewById(R.id.messageText)
        btnConfirm = findViewById(R.id.btnConfirm)
        btnDelete = findViewById(R.id.btnDelete)
        btnShare = findViewById(R.id.btnShare)
        btnSaveToLibrary = findViewById(R.id.btnSaveToLibrary)
        metricsPanel = findViewById(R.id.metricsPanel)
        skeletonPane = findViewById(R.id.skeletonPane)
        videoContainer = findViewById(R.id.videoContainer)
        timelineLayout = findViewById(R.id.timelineLayout)
    }

    private fun showRenameDialog() {
        val e = entry ?: return
        val input = android.widget.EditText(this).apply {
            setText(e.displayName)
            setSelection(text.length)
            setTextColor(android.graphics.Color.WHITE)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(input, pad, pad / 2, pad, 0)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                scope.launch {
                    val updated = e.copy(displayName = name)
                    withContext(Dispatchers.IO) {
                        VideoCacheManager.upsertEntry(this@ActionReviewActivity, updated)
                    }
                    entry = updated
                    titleText.text = "动作预审 · $name"
                    Toast.makeText(this@ActionReviewActivity, "已重命名", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteConfirm() {
        val e = entry ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("删除视频")
            .setMessage("确定从舞蹈库删除「${e.displayName}」吗？")
            .setPositiveButton("删除") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        VideoCacheManager.deleteEntry(this@ActionReviewActivity, e.id)
                    }
                    Toast.makeText(this@ActionReviewActivity, "已删除", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun displayMetrics(e: VideoLibraryEntry) {
        if (e.analysisStatus == AnalysisStatus.NONE || e.actionQuality < 0) {
            qualityScore.text = "--"
            qualityLabel.text = "未分析"
            detectionRate.text = "--"
            avgConfidence.text = "--"
            validFrames.text = "--"
            segmentCount.text = "--"
            difficulty.text = "--"
            return
        }

        qualityScore.text = e.actionQuality.toInt().toString()
        qualityLabel.text = when (e.analysisStatus) {
            AnalysisStatus.ANALYZED -> "合格"
            AnalysisStatus.WARNING -> "有警告"
            AnalysisStatus.FAILED -> "不合格"
            else -> ""
        }
        qualityLabel.setTextColor(when (e.analysisStatus) {
            AnalysisStatus.ANALYZED -> getColor(R.color.success)
            AnalysisStatus.WARNING -> getColor(R.color.warning)
            AnalysisStatus.FAILED -> getColor(R.color.failure)
            else -> getColor(R.color.textSecondary)
        })

        detectionRate.text = "${(e.detectionRate * 100).toInt()}%"
        avgConfidence.text = "%.2f".format(e.avgConfidence)
        validFrames.text = e.validFrameCount.toString()
        segmentCount.text = e.segmentCount.toString()
        difficulty.text = e.difficulty

        if (e.analysisMessage != null) {
            messageText.visibility = View.VISIBLE
            messageText.text = e.analysisMessage
        }
    }

    private fun updateMetrics(result: AnalysisManager.Result) {
        if (result.status == AnalysisStatus.ANALYZING || result.status == AnalysisStatus.QUEUED) {
            showAnalyzing(result.progress)
        } else {
            hideAnalyzing()
            // 刷新数据
            scope.launch {
                val lib = withContext(Dispatchers.IO) {
                    VideoCacheManager.loadLibrary(this@ActionReviewActivity)
                }
                entry = lib.entries.find { it.id == result.entryId }
                entry?.let { displayMetrics(it) }
            }
        }
    }

    private fun showAnalyzing(progress: Int) {
        analyzingOverlay.visibility = View.VISIBLE
        analyzingText.text = "分析中 $progress%"
        analysisProgress.visibility = View.VISIBLE
        analysisProgress.progress = progress
    }

    private fun hideAnalyzing() {
        analyzingOverlay.visibility = View.GONE
        analysisProgress.visibility = View.GONE
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDetectionLoop()
        scope.cancel()
        videoView.stopPlayback()
        poseExtractor?.close()
        poseExtractor = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
    }
}
