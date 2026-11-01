/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.ViewGroup
import java.io.ByteArrayOutputStream
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dancecoach.app.R
import com.dancecoach.app.data.VideoCacheManager
import com.dancecoach.app.domain.VideoLibraryEntry
import com.dancecoach.app.domain.market.*
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

class MarketUploadActivity : AppCompatActivity(), CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    private lateinit var job: Job
    private lateinit var repo: MarketRepository
    private var uploading = false
    // 闭环约束：上传必须来自本地舞蹈库中的某条视频
    private var entry: VideoLibraryEntry? = null
    private lateinit var spinnerCategory: Spinner
    private lateinit var btnSubmit: TextView
    private lateinit var agreeCb: CheckBox
    private var uploadLoading: ProgressBar? = null
    private var uploadLoadingText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        job = SupervisorJob()
        OrientationUtil.apply(this)
        setContentView(R.layout.activity_market_upload)
        repo = MarketRepoProvider.get(this)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        // 分类下拉（固定枚举，与市场和首页一致）
        spinnerCategory = findViewById(R.id.spinnerCategory)
        val catAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            MARKET_CATEGORIES.filter { it != "全部" }
        )
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCategory.adapter = catAdapter

        val editTitle = findViewById<EditText>(R.id.editTitle)
        val editDesc = findViewById<EditText>(R.id.editDesc)
        btnSubmit = findViewById(R.id.btnSubmit)
        uploadLoading = findViewById(R.id.uploadLoading)
        uploadLoadingText = findViewById(R.id.uploadLoadingText)
        val btnPickVideo = findViewById<TextView>(R.id.btnPickVideo)
        val tvFile = findViewById<TextView>(R.id.tvFile)

        // 版权与肖像权协议：未同意则禁止发布
        findViewById<TextView>(R.id.tvDeclaration).text = AgreementPrefs.UPLOAD_DECLARATION
        agreeCb = findViewById(R.id.agreeCb)
        agreeCb.isChecked = AgreementPrefs.isAgreed(this)
        agreeCb.setOnCheckedChangeListener { _, checked ->
            if (checked) AgreementPrefs.setAgreedVersion(this, AgreementPrefs.AGREEMENT_VERSION)
            refreshSubmitState()
        }
        findViewById<TextView>(R.id.btnAgreementLink).setOnClickListener { showAgreementDialog() }

        // 闭环后只分享真实视频，隐藏「类型」选择
        findViewById<ViewGroup>(R.id.typeGroup).visibility = ViewGroup.GONE

        val entryId = intent.getStringExtra("entryId")
        if (entryId != null) {
            // 来自舞蹈库：加载条目并预填
            this.launch {
                val lib = withContext(Dispatchers.IO) {
                    VideoCacheManager.loadLibrary(this@MarketUploadActivity)
                }
                val e = lib.entries.find { it.id == entryId }
                if (e == null) {
                    Toast.makeText(this@MarketUploadActivity, "视频不存在", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }
                entry = e
                editTitle.setText(e.displayName)
                tvFile.text = "已选：${e.displayName}（来自舞蹈库）"
                btnPickVideo.visibility = ViewGroup.GONE
                refreshSubmitState()
            }
        } else {
            // 无来源：引导用户去舞蹈库选择（不允许从任意文件上传）
            tvFile.text = "请先从本地舞蹈库选择要分享的视频（在舞蹈库预览页点「分享到市场」）"
            btnPickVideo.text = "← 去舞蹈库选择视频"
            btnPickVideo.setOnClickListener {
                startActivity(Intent(this, LibraryActivity::class.java))
            }
        }

        btnSubmit.setOnClickListener { submit(editTitle, editDesc, tvFile) }
        refreshSubmitState()
    }

    /** 发布按钮可用性：必须已选视频 且 已勾选协议 */
    private fun refreshSubmitState() {
        val can = entry != null && agreeCb.isChecked
        btnSubmit.isEnabled = can
        btnSubmit.alpha = if (can) 1f else 0.5f
    }

    /** 完整协议对话框：点「同意」即勾选并持久化同意版本 */
    private fun showAgreementDialog() {
        AlertDialog.Builder(this)
            .setTitle("《AI幻舞 用户协议》")
            .setMessage(AgreementPrefs.AGREEMENT_FULL)
            .setPositiveButton("同意并勾选") { d, _ ->
                agreeCb.isChecked = true
                d.dismiss()
            }
            .setNegativeButton("关闭") { d, _ -> d.dismiss() }
            .show()
    }

    private fun submit(
        editTitle: EditText,
        editDesc: EditText,
        tvFile: TextView
    ) {
        if (!agreeCb.isChecked) {
            Toast.makeText(this, "请先阅读并同意《AI幻舞 用户协议》", Toast.LENGTH_SHORT).show()
            return
        }
        val entry = entry ?: run {
            Toast.makeText(this, "请先从舞蹈库选择视频", Toast.LENGTH_SHORT).show()
            return
        }
        val title = editTitle.text.toString().trim()
        if (title.isBlank()) {
            Toast.makeText(this, "请填写标题", Toast.LENGTH_SHORT).show()
            return
        }
        if (uploading) return
        // 每日上传上限（本机按天计，最多 3 条）
        if (RewardPrefs.getDailyUploadCount(this) >= 3) {
            Toast.makeText(this, "今日上传已达上限（3 条），明天再来吧", Toast.LENGTH_LONG).show()
            return
        }
        uploading = true
        btnSubmit.isEnabled = false
        btnSubmit.text = "上传中…"
        // 上传期间显示转圈，避免误以为死机
        uploadLoading?.visibility = android.view.View.VISIBLE
        uploadLoadingText?.visibility = android.view.View.VISIBLE

        val desc = editDesc.text.toString().trim()
        val fileType = "video"
        val category = spinnerCategory.selectedItem?.toString() ?: "其他"
        val difficulty = entry.difficulty ?: "其他"
        val uri = Uri.parse(entry.uri)

        launch {
            try {
                // 封面：优先复用本地库已生成的缩略图（与本地库卡片同一张），否则抽首帧
                val coverUrl = extractCoverDataUrl(entry.thumbnailPath, uri) ?: ""
                // 流式读取并直传到云存储（不走内存全量，避免大视频 OOM）
                val input = contentResolver.openInputStream(uri)
                    ?: throw Exception("无法读取视频")
                input.use { stream ->
                    val cred = repo.getUploadCredential("${entry.displayName}.mp4")
                    repo.putVideo(cred.uploadUrl, stream)
                    val req = UploadRequest(
                        title = title,
                        category = category,
                        fileType = fileType,
                        description = desc,
                        fileId = cred.fileId,
                        fileSize = entry.fileSize,
                        difficulty = difficulty,
                        coverUrl = coverUrl
                    )
                    val uploaded = repo.upload(req)
                    // 写入本地「我的上传」列表 + 离线缓存（清数据即清空，与 v2 自洽）
                    RewardPrefs.addMyUploadId(this@MarketUploadActivity, uploaded.id)
                    RewardPrefs.putMyUploadMeta(
                        this@MarketUploadActivity,
                        RewardPrefs.MyUploadMeta(
                            id = uploaded.id,
                            title = uploaded.title,
                            coverUrl = uploaded.coverUrl,
                            likeCount = 0,
                            downloadCount = 0,
                            pendingLike = 0,
                            pendingDL = 0
                        )
                    )
                }
                val r = RewardManager.award(this@MarketUploadActivity, RewardReason.UPLOAD)
                RewardPrefs.incDailyUploadCount(this@MarketUploadActivity)
                uploadLoading?.visibility = android.view.View.GONE
                uploadLoadingText?.visibility = android.view.View.GONE
                Toast.makeText(this@MarketUploadActivity, "上传成功，+${RewardManager.formatCoin(r.coinGain)} 舞币", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                uploadLoading?.visibility = android.view.View.GONE
                uploadLoadingText?.visibility = android.view.View.GONE
                Toast.makeText(this@MarketUploadActivity, "上传失败：${e.message}", Toast.LENGTH_LONG).show()
                uploading = false
                refreshSubmitState()
                btnSubmit.text = "发布分享"
            }
        }
    }

    /**
     * 生成上传封面：优先复用本地库已生成的缩略图文件（entry.thumbnailPath），
     * 与本地库卡片封面完全一致、且免去重复抽帧；该文件不可用时再回退从视频抽首帧。
     * 统一缩放到宽 240、JPEG 质量 55，编码成 data URL 内嵌；失败/过大返回 null。
     */
    private suspend fun extractCoverDataUrl(thumbnailPath: String?, uri: Uri?): String? = withContext(Dispatchers.IO) {
        // 1) 复用本地缩略图（与本地库卡片同一张）
        if (!thumbnailPath.isNullOrBlank()) {
            val file = java.io.File(thumbnailPath)
            if (file.exists()) {
                try {
                    val src = BitmapFactory.decodeFile(thumbnailPath) ?: return@withContext null
                    val cover = scaleToWidth(src, 240)
                    if (cover != src) src.recycle()
                    val out = ByteArrayOutputStream()
                    cover.compress(Bitmap.CompressFormat.JPEG, 55, out)
                    cover.recycle()
                    val bytes = out.toByteArray()
                    if (bytes.size > 60 * 1024) return@withContext null
                    return@withContext "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                } catch (e: Exception) { /* 回退抽帧 */ }
            }
        }
        // 2) 回退：从视频抽首帧
        if (uri != null) {
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(this@MarketUploadActivity, uri)
                val frame = retriever.getFrameAtTime(300_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0)
                    ?: return@withContext null
                val cover = scaleToWidth(frame, 240)
                if (cover != frame) frame.recycle()
                val out = ByteArrayOutputStream()
                cover.compress(Bitmap.CompressFormat.JPEG, 55, out)
                cover.recycle()
                val bytes = out.toByteArray()
                if (bytes.size > 60 * 1024) return@withContext null
                return@withContext "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            } catch (e: Exception) {
                null
            } finally {
                try { retriever?.release() } catch (e: Exception) {}
            }
        }
        null
    }

    /** 等比缩放到指定宽度（src 已不大于目标宽则原样返回，避免无谓放大与重复回收） */
    private fun scaleToWidth(src: Bitmap, targetW: Int): Bitmap {
        if (src.width <= targetW) return src
        val scale = targetW.toFloat() / src.width.coerceAtLeast(1)
        val targetH = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }
}
