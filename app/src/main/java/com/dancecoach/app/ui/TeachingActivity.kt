/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.widget.*
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dancecoach.app.R
import com.dancecoach.app.camera.CameraManager
import com.dancecoach.app.data.VideoCacheManager
import com.dancecoach.app.domain.*
import com.dancecoach.app.domain.market.*
import com.dancecoach.app.ml.FrameDumper
import com.dancecoach.app.ml.PoseExtractor
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import kotlinx.coroutines.*

class TeachingActivity : AppCompatActivity(), TeachingController.Callback, FollowAlongController.Callback {

    // UI
    private lateinit var previewView: TextureView
    private lateinit var playerView: com.google.android.exoplayer2.ui.PlayerView
    private lateinit var scoreText: TextView
    private lateinit var hintText: TextView
    private lateinit var btnPickVideo: Button
    private lateinit var btnRotateLeft: Button
    private lateinit var btnRotateRight: Button
    private lateinit var btnFlip: Button
    private lateinit var btnMute: Button
    private lateinit var segmentProgress: ProgressBar
    private lateinit var studentProgress: ProgressBar
    private lateinit var teacherContainer: FrameLayout
    private lateinit var studentContainer: FrameLayout
    private lateinit var segmentProgressContainer: LinearLayout
    private lateinit var libraryListContainer: LinearLayout
    private lateinit var cameraControlsBar: LinearLayout
    private lateinit var gestureSelectPanel: LinearLayout
    private lateinit var gesturePrev: TextView
    private lateinit var gestureRepeat: TextView
    private lateinit var gestureNext: TextView
    private lateinit var gestureExit: TextView
    private lateinit var gesturePrevIcon: ImageView
    private lateinit var gestureRepeatIcon: ImageView
    private lateinit var gestureNextIcon: ImageView
    private lateinit var gestureExitIcon: ImageView
    private lateinit var gestureConfirmProgress: ProgressBar
    private lateinit var skeletonOverlay: SkeletonOverlayView
    private lateinit var gestureTimeoutText: TextView
    private lateinit var btnModeStatus: Button
    private lateinit var followCountdown: TextView

    // 调试模式：双角骨架浮层
    private lateinit var teacherSkeletonPanel: FrameLayout
    private lateinit var teacherSkeleton: SkeletonOverlayView
    private lateinit var teacherSkeletonScore: TextView
    private lateinit var userSkeletonPanel: FrameLayout
    private lateinit var userSkeleton: SkeletonOverlayView
    private lateinit var userSkeletonScore: TextView

    // 核心组件
    private lateinit var extractor: PoseExtractor
    private var cameraManager: CameraManager? = null
    private var exoPlayer: ExoPlayer? = null
    private lateinit var teachingController: TeachingController
    private lateinit var followAlongController: FollowAlongController

    // 当前教学模式（DEBUG=调试 / FOLLOW=跟着跳 / SEGMENTED=分段跳），默认 DEBUG
    private var currentMode: ModePreference.Mode = ModePreference.Mode.DEBUG
    private val MAX_FOLLOW_MS = 120_000L
    // 当前是否为调试模式（=跟着跳 + 双角骨架浮层）
    private var isDebugMode: Boolean = false
    // 竖屏 Debug 切换：左右滑手势检测器
    private var debugGesture: GestureDetector? = null

    // 协程
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 状态
    private val latestUser = java.util.concurrent.atomic.AtomicReference<List<Landmark>?>(null)
    private var currentSegmentedDance: SegmentedDance? = null
    private var currentVideoUri: Uri? = null
    private var currentLibraryEntry: VideoLibraryEntry? = null
    private var running = false
    private var isMuted = false
    private var loadingDialog: AlertDialog? = null
    private var loadingTitleView: TextView? = null
    private var loadingProgressView: ProgressBar? = null
    private var loadingMsgView: TextView? = null

    // 学生跟跳帧收集
    private val studentFrames = mutableListOf<Pair<Long, List<Landmark>>>()

    // 实时教师骨架（播放时按时间从视频取帧检测得到，与用户摄像头同源）
    private val latestTeacher = java.util.concurrent.atomic.AtomicReference<List<Landmark>?>(null)
    private var teacherDetecting = false
    private var lastTeacherDetectMs = 0L
    private val teacherStudentFrames = mutableListOf<Pair<Long, List<Landmark>>>()
    private val TEACHER_DETECT_INTERVAL_MS = 80L  // 与摄像头侧 80ms 节流一致

    // 手势检测
    private var currentGesture: GestureRecognizer.Gesture = GestureRecognizer.Gesture.NONE
    private var gestureHoldStartMs = 0L
    private val GESTURE_HOLD_MS = 1500L

    // 投屏：presentation 变化时同步画面输出
    private val castPresentationListener: (CastPresentation?) -> Unit = { applyCastState() }
    private var castVideoActive = false

    // 舞蹈库列表
    private var libraryEntries: List<VideoLibraryEntry> = emptyList()

    // tick
    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (running) {
                updateFrame()
                handler.postDelayed(this, 50)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationUtil.apply(this)
        setContentView(R.layout.activity_teaching)

        bindViews()
        initListeners()
        initExoPlayer()
        initTeachingController()
        initFollowAlongController()

        // 投屏
        CastManager.init(this)
        CastManager.addPresentationListener(castPresentationListener)
        applyCastState()

        extractor = PoseExtractor(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }

        refreshLibraryList()

        // 优先加载指定视频（从舞蹈库跳转）；否则默认自动加载舞蹈库第一个，避免黑屏不知如何开始
        val targetEntry = intent.getStringExtra("entryId")
            ?.let { entryId -> libraryEntries.find { it.id == entryId } }
            ?: libraryEntries.firstOrNull()
        targetEntry?.let { loadVideoFromLibrary(it) }

        // 舞蹈库为空：自动从市场下载一个示例舞蹈
        if (libraryEntries.isEmpty()) fetchExampleDanceIfNeeded()
    }

    private var fetchingExample = false

    /** 舞蹈库为空时自动从市场下载一个示例舞蹈并开始运行（失败保持空态） */
    private fun fetchExampleDanceIfNeeded() {
        if (fetchingExample) return
        fetchingExample = true
        // 下载可能耗时，显示转圈提示，避免用户误以为死机
        showLoadingDialog("正在下载示例舞蹈…", "首次使用自动下载，请稍候")
        scope.launch {
            try {
                val repo = MarketRepoProvider.get(this@TeachingActivity)
                val list = repo.list(MarketSort.LATEST)
                // 优先取教学视频，否则第一个条目
                val example = list.firstOrNull { it.fileType == "video" } ?: list.firstOrNull()
                if (example != null && !example.fileId.isBlank()) {
                    MarketLibraryBridge.downloadIntoLibrary(
                        this@TeachingActivity, repo, example.fileId, example.title, example.id
                    )
                    refreshLibraryList()
                    libraryEntries.firstOrNull()?.let { loadVideoFromLibrary(it) }
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@TeachingActivity,
                    "示例舞蹈下载失败，请检查网络后手动添加",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                fetchingExample = false
                dismissLoadingDialog()
            }
        }
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        playerView = findViewById(R.id.playerView)
        scoreText = findViewById(R.id.scoreText)
        hintText = findViewById(R.id.hintText)
        btnPickVideo = findViewById(R.id.btnPickVideo)
        btnRotateLeft = findViewById(R.id.btnRotateLeft)
        btnRotateRight = findViewById(R.id.btnRotateRight)
        btnFlip = findViewById(R.id.btnFlip)
        btnMute = findViewById(R.id.btnMute)
        segmentProgress = findViewById(R.id.segmentProgress)
        studentProgress = findViewById(R.id.studentProgress)
        teacherContainer = findViewById(R.id.teacherVideoContainer)
        studentContainer = findViewById(R.id.studentContainer)
        segmentProgressContainer = findViewById(R.id.segmentProgressContainer)
        libraryListContainer = findViewById(R.id.libraryListContainer)
        cameraControlsBar = findViewById(R.id.cameraControlsBar)
        gestureSelectPanel = findViewById(R.id.gestureSelectPanel)
        gesturePrev = findViewById(R.id.gesturePrev)
        gestureRepeat = findViewById(R.id.gestureRepeat)
        gestureNext = findViewById(R.id.gestureNext)
        gestureExit = findViewById(R.id.gestureExit)
        gesturePrevIcon = findViewById(R.id.gesturePrevIcon)
        gestureRepeatIcon = findViewById(R.id.gestureRepeatIcon)
        gestureNextIcon = findViewById(R.id.gestureNextIcon)
        gestureExitIcon = findViewById(R.id.gestureExitIcon)
        gestureConfirmProgress = findViewById(R.id.gestureConfirmProgress)
        skeletonOverlay = findViewById(R.id.skeletonOverlay)
        gestureTimeoutText = findViewById(R.id.gestureTimeoutText)
        btnModeStatus = findViewById(R.id.btnModeStatus)
        followCountdown = findViewById(R.id.followCountdown)
        teacherSkeletonPanel = findViewById(R.id.teacherSkeletonPanel)
        teacherSkeleton = findViewById(R.id.teacherSkeleton)
        teacherSkeletonScore = findViewById(R.id.teacherSkeletonScore)
        userSkeletonPanel = findViewById(R.id.userSkeletonPanel)
        userSkeleton = findViewById(R.id.userSkeleton)
        userSkeletonScore = findViewById(R.id.userSkeletonScore)
    }

    private fun initListeners() {
        btnPickVideo.setOnClickListener { pickVideos() }

        btnRotateLeft.setOnClickListener {
            cameraManager?.let { cm ->
                cm.displayRotation = (cm.displayRotation + 270) % 360
                AppPrefs.displayRotation = cm.displayRotation
            }
        }
        btnRotateRight.setOnClickListener {
            cameraManager?.let { cm ->
                cm.displayRotation = (cm.displayRotation + 90) % 360
                AppPrefs.displayRotation = cm.displayRotation
            }
        }
        btnFlip.setOnClickListener {
            cameraManager?.let { cm ->
                cm.mirror = !cm.mirror
                AppPrefs.mirror = cm.mirror
                btnFlip.text = if (cm.mirror) "镜像✓" else "镜像"
            }
        }
        btnMute.setOnClickListener {
            isMuted = !isMuted
            exoPlayer?.volume = if (isMuted) 0f else 1f
            btnMute.text = if (isMuted) "🔇" else "🔊"
        }
        btnModeStatus.setOnClickListener { switchModeAndRestart() }

        // 竖屏 Debug 切换：在教师画面上左滑或右滑均可切换调试/跟跳模式
        debugGesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                val dy = e2.y - (e1?.y ?: e2.y)
                val horizontal = kotlin.math.abs(dx) > 120 &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f
                if (!horizontal) return false
                // 仅竖屏生效，且手势选择面板显示期间不响应，避免与选手势冲突
                val inGestureSelect =
                    teachingController.state == TeachingController.State.GESTURE_SELECT
                if (AppPrefs.isLandscape || inGestureSelect) return false
                switchModeAndRestart()
                val now = ModePreference.getMode(this@TeachingActivity)
                Toast.makeText(
                    this@TeachingActivity,
                    if (now == ModePreference.Mode.DEBUG) "显示动作：开" else "显示动作：关",
                    Toast.LENGTH_SHORT
                ).show()
                return true
            }
        })
        window.decorView.setOnTouchListener { _, ev -> debugGesture?.onTouchEvent(ev) ?: false }
    }

    private fun initExoPlayer() {
        exoPlayer = com.google.android.exoplayer2.SimpleExoPlayer.Builder(this).build()
        playerView.player = exoPlayer
        exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF
    }

    private fun initTeachingController() {
        teachingController = TeachingController(this).apply {
            matchThreshold = AppPrefs.matchThreshold
        }
    }

    private fun initFollowAlongController() {
        followAlongController = FollowAlongController(this).apply {
            lagMs = AppPrefs.lagMs
        }
    }

    /** 切换教学模式（手动点击按钮或举重姿势手势触发），并重启当前视频。 */
    private fun switchModeAndRestart() {
        currentMode = ModePreference.toggleMode(this)
        updateModeStatusButton()
        val entry = currentLibraryEntry
        if (entry != null) {
            teachingController.pause()
            followAlongController.pause()
            running = false
            handler.removeCallbacks(tickRunnable)
            loadVideoFromLibrary(entry)
        }
    }

    private fun updateModeStatusButton() {
        btnModeStatus.text = when (currentMode) {
            ModePreference.Mode.FOLLOW -> "跟着跳"
            ModePreference.Mode.SEGMENTED -> "分段跳"
            ModePreference.Mode.DEBUG -> "显示动作"
        }
        val bg = when (currentMode) {
            ModePreference.Mode.FOLLOW -> R.drawable.mode_status_bg_follow
            ModePreference.Mode.SEGMENTED -> R.drawable.mode_status_bg_segmented
            ModePreference.Mode.DEBUG -> R.drawable.mode_status_bg_debug
        }
        btnModeStatus.setBackgroundResource(bg)
    }

    private fun startCamera() {
        cameraManager = CameraManager(this, this, extractor) { landmarks, _, _, _ ->
            android.util.Log.d("DC", "onPose landmarks=${landmarks?.size}")
            latestUser.set(landmarks)
        }
        // 从设置页读取摄像头偏好（镜像 / 显示旋转），保证重启后保持用户选择
        cameraManager?.mirror = AppPrefs.mirror
        cameraManager?.displayRotation = AppPrefs.displayRotation
        // 竖屏 flag：视频流自动旋转正立；镜像横竖屏一致（前置默认自拍镜像），镜像按钮均可用
        val portrait = !AppPrefs.isLandscape
        cameraManager?.portraitMode = portrait
        // 调试帧落盘开关（设置页「调试帧落盘」→ FrameDumper.userEnabled）
        FrameDumper.userEnabled = AppPrefs.dumpFrames
        btnFlip.text = if (cameraManager?.mirror == true) "镜像✓" else "镜像"
        // 用户骨架统一来自物理转正帧（正立坐标系，与教师骨架一致），骨架 overlay 关闭轴转置
        skeletonOverlay.transpose = false
        userSkeleton.transpose = false
        cameraManager?.start(previewView)
    }

    // ═══ 投屏画面同步 ═══

    /** 根据当前投屏状态，把视频输出切到电视 Surface 或还原到手机，并双写摄像头帧。 */
    private fun applyCastState() {
        val pres = CastManager.getPresentation()
        val shouldCast = CastManager.state == CastManager.CastState.CONNECTED && pres != null
        if (shouldCast && !castVideoActive) {
            cameraManager?.secondaryTarget = pres?.studentTexture
            exoPlayer?.setVideoSurfaceView(pres?.teacherSurface)
            castVideoActive = true
        } else if (!shouldCast && castVideoActive) {
            cameraManager?.secondaryTarget = null
            exoPlayer?.setVideoSurfaceView(null)
            playerView.player = exoPlayer
            castVideoActive = false
        }
    }

    /** 若投屏 Presentation 存在，对其执行同步操作。 */
    private fun castSync(block: (CastPresentation) -> Unit) {
        CastManager.getPresentation()?.let { block(it) }
    }

    // ═══ 批量选择视频 ═══

    private fun pickVideos() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uris = mutableListOf<Uri>()
            val clipData = data?.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    uris.add(clipData.getItemAt(i).uri)
                }
            } else if (data?.data != null) {
                uris.add(data.data!!)
            }

            if (uris.isEmpty()) return

            // 批量添加到舞蹈库（仅提取元数据，不分析）
            scope.launch {
                showLoadingDialog("正在添加视频…", "共 ${uris.size} 个")
                for ((i, uri) in uris.withIndex()) {
                    val exists = isUriAlreadyInLibrary(uri)
                    if (!exists) {
                        withContext(Dispatchers.IO) {
                            VideoCacheManager.addVideoMetadata(this@TeachingActivity, uri)
                        }
                    }
                    runOnUiThread {
                        updateLoadingDialog("正在添加视频…", "${i + 1} / ${uris.size}", (i + 1).toFloat() / uris.size)
                    }
                }
                dismissLoadingDialog()
                refreshLibraryList()
                Toast.makeText(this@TeachingActivity, "已添加 ${uris.size} 个视频到舞蹈库", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isUriAlreadyInLibrary(uri: Uri): Boolean {
        val uriStr = uri.toString()
        return libraryEntries.any { it.uri == uriStr }
    }

    // ═══ 视频加载（懒分析） ═══

    private fun loadVideoFromLibrary(entry: VideoLibraryEntry) {
        val uri = Uri.parse(entry.uri)
        currentVideoUri = uri
        currentLibraryEntry = entry

        // 切视频时清空旧的师生骨架，避免显示上一视频的残留骨架
        latestTeacher.set(null)
        latestUser.set(null)

        val cacheId = entry.id

        // 实时模式：不再离线预处理整段视频。优先用已缓存的分段，否则按时间即时切分并缓存。
        var segmented = VideoCacheManager.loadCache(this, cacheId)
        if (segmented == null || segmented.segments.isEmpty()) {
            val dur = entry.durationMs.coerceAtLeast(1000L)
            segmented = SegmentedDance.timeBased(dur)
            val segCopy = segmented
            scope.launch(Dispatchers.IO) {
                VideoCacheManager.saveCache(this@TeachingActivity, cacheId, segCopy)
            }
        }

        currentSegmentedDance = segmented
        setupExoPlayer(uri)

        // 更新库条目（标记已分段、段数），保证列表显示一致
        val updated = entry.copy(
            segmentCount = segmented.segments.size,
            difficulty = "中等",
            lastPlayedAt = System.currentTimeMillis(),
            analyzed = true
        )
        VideoCacheManager.upsertEntry(this, updated)
        currentLibraryEntry = updated

        startTeaching()
        refreshLibraryList()
    }

    // ═══ 加载对话框 ═══

    private fun showLoadingDialog(title: String, message: String?) {
        dismissLoadingDialog()
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
        }
        loadingTitleView = TextView(this).apply {
            text = title
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(0, 0, 0, 16)
        }
        loadingProgressView = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = true
        }
        loadingMsgView = TextView(this).apply {
            text = message ?: ""
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 13f
            setPadding(0, 8, 0, 0)
            visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        dialogView.addView(loadingTitleView)
        dialogView.addView(loadingProgressView)
        dialogView.addView(loadingMsgView)

        loadingDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        loadingDialog?.show()
    }

    private fun updateLoadingDialog(title: String, message: String?, progress: Float) {
        loadingTitleView?.text = title
        if (progress > 0) {
            loadingProgressView?.isIndeterminate = false
            loadingProgressView?.progress = (progress * 100).toInt()
        } else {
            loadingProgressView?.isIndeterminate = true
        }
        if (message != null) {
            loadingMsgView?.text = message
            loadingMsgView?.visibility = View.VISIBLE
        }
    }

    private fun dismissLoadingDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
        loadingTitleView = null
        loadingProgressView = null
        loadingMsgView = null
    }

    private fun setupExoPlayer(uri: Uri) {
        exoPlayer?.let { player ->
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.volume = if (isMuted) 0f else 1f
        }
    }

    private fun startTeaching() {
        val segmented = currentSegmentedDance ?: return
        running = true
        handler.removeCallbacks(tickRunnable)
        // 切视频/重开时先把旧的状态机复位，否则若之前处在 GESTURE_SELECT，
        // updateFrame 会走分段流程，导致跟跳/调试模式的双角骨架不更新。
        teachingController.resetToIdle()
        currentMode = ModePreference.getMode(this)
        updateModeStatusButton()
        isDebugMode = (currentMode == ModePreference.Mode.DEBUG)
        android.util.Log.d("DC", "startTeaching mode=$currentMode isDebug=$isDebugMode")
        // 实时教师源：用独立解码器按播放时间取帧检测（与 ExoPlayer 互不干扰）
        if (AppPrefs.enableTeacherDetect) {
            currentVideoUri?.let { extractor.prepareLiveSource(this, it) }
        }
        if (currentMode == ModePreference.Mode.FOLLOW || isDebugMode) {
            val dur = (exoPlayer?.duration ?: 0L).coerceAtMost(MAX_FOLLOW_MS).coerceAtLeast(1L)
            followAlongController.start(dur)
        } else {
            teachingController.start(segmented.segments)
        }
        if (!isDebugMode) setDebugSkeletonVisible(false)
        handler.post(tickRunnable)
    }

    /** 调试模式：显示/隐藏用户与教师双角骨架浮层（两者均按正立坐标绘制，与统一流水线一致） */
    private fun setDebugSkeletonVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        teacherSkeletonPanel.visibility = v
        userSkeletonPanel.visibility = v
        if (!visible) {
            userSkeleton.clear()
            teacherSkeleton.clear()
        }
    }

    // ═══ 舞蹈库列表 ═══

    private fun refreshLibraryList() {
        val library = VideoCacheManager.loadLibrary(this)
        libraryEntries = library.entries
        libraryListContainer.removeAllViews()

        if (libraryEntries.isEmpty()) {
            val empty = TextView(this).apply {
                text = "暂无舞蹈\n点「+ 添加视频」"
                setTextColor(0xFF888888.toInt())
                textSize = 9f
                gravity = android.view.Gravity.CENTER
                setPadding(4, 8, 4, 8)
            }
            libraryListContainer.addView(empty)
            return
        }

        for (entry in libraryEntries.take(50)) {
            val itemView = createLibraryItem(entry)
            libraryListContainer.addView(itemView)
        }
    }

    private fun createLibraryItem(entry: VideoLibraryEntry): View {
        val isLand = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val density = resources.displayMetrics.density
        // 竖屏：封面铺满卡片的 FrameLayout；横屏：竖排（缩略图 + 名称 + 信息）
        val container: android.view.ViewGroup = if (isLand) {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                val pad4 = (4 * density).toInt()
                val pad6 = (6 * density).toInt()
                setPadding(pad4, pad6, pad4, pad6)
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
        } else {
            FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    marginStart = 6
                    marginEnd = 6
                }
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
        }

        if (entry.thumbnailPath != null) {
            val imgFile = java.io.File(entry.thumbnailPath)
            if (imgFile.exists()) {
                val bmp = BitmapFactory.decodeFile(imgFile.absolutePath)
                if (bmp != null) {
                    val imgView = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(bmp)
                    }
                    if (isLand) {
                        // 横屏：封面占满左栏宽、纵向放大（100×70 dp，需乘 density 转 px）
                        imgView.layoutParams = LinearLayout.LayoutParams(
                            (100 * density).toInt(),
                            (70 * density).toInt()
                        ).apply {
                            bottomMargin = 2
                        }
                    } else {
                        // 竖屏：封面固定尺寸居中（80×64），四周留白，不撑满横条
                        imgView.layoutParams = FrameLayout.LayoutParams(
                            (80 * density).toInt(),
                            (64 * density).toInt(),
                            android.view.Gravity.CENTER
                        )
                    }
                    container.addView(imgView)
                }
            }
        }

        // 名称：横屏居中；竖屏叠加在封面底部（半透明条）
        val nameView = TextView(this).apply {
            text = entry.displayName
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 9f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
        }
        if (isLand) {
            container.addView(nameView)
        } else {
            nameView.setPadding(2, 2, 2, 2)
            nameView.setBackgroundColor(0x66000000.toInt())
            nameView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM
            )
            container.addView(nameView)
        }

        // 信息：分数 / 状态（分段跳已屏蔽，不再显示段数；仅横屏显示）
        if (isLand) {
            val infoStr = when {
                !entry.analyzed -> "未分析"
                entry.bestScore >= 0 -> "${entry.bestScore.toInt()}分"
                else -> "未练习"
            }
            val infoView = TextView(this).apply {
                text = infoStr
                setTextColor(if (entry.analyzed) 0xFFAAAAAA.toInt() else 0xFF888888.toInt())
                textSize = 8f
                gravity = android.view.Gravity.CENTER
            }
            container.addView(infoView)
        }

        // 高亮当前
        if (currentLibraryEntry?.id == entry.id) {
            container.setBackgroundColor(0x4420E3FF.toInt())
        }

        container.setOnClickListener {
            loadVideoFromLibrary(entry)
        }

        container.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("删除视频")
                .setMessage("从舞蹈库中删除「${entry.displayName}」？缓存也会清除。")
                .setPositiveButton("删除") { _, _ ->
                    VideoCacheManager.deleteEntry(this, entry.id)
                    refreshLibraryList()
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }

        return container
    }

    // ═══ 段进度方块 ═══

    private fun updateSegmentBlocks(statuses: List<TeachingController.SegmentStatus>) {
        segmentProgressContainer.removeAllViews()
        for (status in statuses) {
            val block = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = 2
                    marginEnd = 2
                }
                background = ContextCompat.getDrawable(
                    this@TeachingActivity,
                    when (status) {
                        TeachingController.SegmentStatus.COMPLETED -> R.drawable.seg_completed
                        TeachingController.SegmentStatus.CURRENT -> R.drawable.seg_current
                        TeachingController.SegmentStatus.PENDING -> R.drawable.seg_pending
                        TeachingController.SegmentStatus.FAILED -> R.drawable.seg_failed
                    }
                )
            }
            segmentProgressContainer.addView(block)
        }
        castSync { it.updateSegmentBlocks(statuses) }
    }

    // ═══ tick 主循环 ═══

    private fun updateFrame() {
        android.util.Log.d("DC", "updateFrame mode=$currentMode fstate=${followAlongController.state} running=$running")
        // 手势选择是两种模式共用的 UI，始终走分段跳的检测分支
        if (teachingController.state == TeachingController.State.GESTURE_SELECT) {
            updateFrameSegmented()
            return
        }
        if (currentMode == ModePreference.Mode.FOLLOW || currentMode == ModePreference.Mode.DEBUG) {
            updateFrameFollow()
        } else {
            updateFrameSegmented()
        }
    }

    private fun updateFrameSegmented() {
        when (teachingController.state) {
            TeachingController.State.IDLE -> {
                if (currentSegmentedDance != null && loadingDialog == null) {
                    detectAndHandleGesture { /* IDLE 状态退出无操作 */ }
                }
            }

            TeachingController.State.TEACHER_DEMO -> {
                val seg = teachingController.currentSegment ?: return
                val pos = exoPlayer?.currentPosition ?: 0
                if (pos >= seg.endMs) {
                    exoPlayer?.pause()
                    teachingController.onTeacherDemoEnded()
                } else {
                    val pct = ((pos - seg.startMs).toFloat() / (seg.endMs - seg.startMs) * 100).toInt().coerceIn(0, 100)
                    segmentProgress.progress = pct
                    castSync { it.updateTeacherProgress(pct) }
                }
            }

            TeachingController.State.STUDENT_DANCE -> {
                val seg = teachingController.currentSegment ?: return
                val user = latestUser.get()
                val elapsed = SystemClock.elapsedRealtime() - teachingController.studentDanceStartMs
                val duration = seg.endMs - seg.startMs

                if (user != null) {
                    studentFrames.add(Pair(elapsed, user))
                }

                // 教师按当前练习进度实时从视频取帧检测（与跟跳模式同源）
                val targetTime = seg.startMs + elapsed.coerceAtMost(duration)
                if (AppPrefs.enableTeacherDetect) maybeDetectTeacher(targetTime)
                val teacher = if (AppPrefs.enableTeacherDetect) latestTeacher.get() else null
                teacherStudentFrames.add(Pair(elapsed, teacher ?: emptyList()))

                val result = ComparisonEngine.compare(teacher, user, false, false)
                teachingController.onStudentDanceTick(result.score, result.hint)

                val pct = (elapsed.toFloat() / duration * 100).toInt().coerceIn(0, 100)
                studentProgress.progress = pct
                castSync { it.updateStudentProgress(pct) }
            }

            TeachingController.State.GESTURE_SELECT -> {
                detectAndHandleGesture { onNavigateExit() }
            }

            TeachingController.State.MATCHED, TeachingController.State.REPLAY,
            TeachingController.State.SKIPPED, TeachingController.State.COMPLETED -> { }
        }
    }

    private fun updateFrameFollow() {
        when (followAlongController.state) {
            FollowAlongController.State.IDLE -> {
                if (currentSegmentedDance != null && loadingDialog == null) {
                    detectAndHandleGesture { /* IDLE 状态退出无操作 */ }
                }
            }
            FollowAlongController.State.COUNTDOWN -> {
                // 倒计时显示由回调更新
            }
            FollowAlongController.State.FOLLOWING -> {
                val pos = exoPlayer?.currentPosition ?: 0L
                // 教师比用户慢 lagMs：读取位置 = 当前播放时间 - lag，再从视频实时取帧检测
                val teacherTime = (pos - followAlongController.lagMs).coerceAtLeast(0L)
                if (AppPrefs.enableTeacherDetect) maybeDetectTeacher(teacherTime)
                val teacher = if (AppPrefs.enableTeacherDetect) latestTeacher.get() else null
                val user = latestUser.get()
                android.util.Log.d("DC", "tick pos=$pos tt=$teacherTime teacher=${teacher?.size} user=${user?.size}")
                followAlongController.onTick(pos, teacher, user)

                // 调试模式：刷新双角骨架浮层（教师按 lag 延迟取帧，与其参考动作一致；用户实时）
                // 教师来自视频帧（PoseExtractor 已物理转正 → 正立空间），overlay 须关闭轴转置；
                // 用户来自摄像头原始帧（未转正），沿用默认 transpose=true 的轴转置补偿，恢复原样。
                if (isDebugMode) {
                    teacherSkeleton.transpose = false
                    // 教师是普通录像播放（PlayerView 不镜像），骨架不应左右翻转，否则与画面左右相反
                    teacherSkeleton.updatePose(teacher, false)
                    // 用户骨架镜像与摄像头预览一致（默认自拍镜像）
                    userSkeleton.updatePose(user, cameraManager?.mirror ?: true)
                    val result = ComparisonEngine.compare(teacher, user, false, false)
                    val scoreStr = "${result.score.toInt()}%"
                    teacherSkeletonScore.text = scoreStr
                    userSkeletonScore.text = scoreStr
                    userSkeleton.setWorstBone(result.worstBone)
                    val ctNull = teacher?.let { PoseUtils.canonical(it, false) == null } ?: true
                    val cuNull = user?.let { PoseUtils.canonical(it, cameraManager?.mirror ?: true) == null } ?: true
                    android.util.Log.d("DC", "DBG score=${result.score.toInt()} tNull=$ctNull uNull=$cuNull panel=${teacherSkeletonPanel.visibility} skW=${teacherSkeleton.width} skH=${teacherSkeleton.height} tPts=${teacher?.size} uPts=${user?.size}")
                }

                val limit = (exoPlayer?.duration ?: 0L).coerceAtMost(MAX_FOLLOW_MS)
                if (pos >= limit) {
                    exoPlayer?.pause()
                    followAlongController.onVideoEnded()
                }
            }
            FollowAlongController.State.COMPLETED -> {
                // 等待 Activity 接管进入手势选择
            }
        }
    }

    /**
     * 节流地从正在播放的视频实时检测“教师骨架”。
     * 每 [TEACHER_DETECT_INTERVAL_MS] 最多取一帧，避免与 ExoPlayer 争抢解码资源。
     * 检测结果存入 [latestTeacher]，tick 主循环直接读取用于显示与打分。
     */
    private fun maybeDetectTeacher(timeMs: Long) {
        if (teacherDetecting) return
        val now = System.currentTimeMillis()
        if (now - lastTeacherDetectMs < TEACHER_DETECT_INTERVAL_MS) return
        lastTeacherDetectMs = now
        teacherDetecting = true
        val ext = extractor
        scope.launch(Dispatchers.IO) {
            val lm = try {
                ext.detectLiveFrameAt(timeMs)
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                teacherDetecting = false
                // 检测失败(某帧没检出人/取帧失败)时保留上一帧的好骨架，避免教师骨架“时有时无”地闪空
                if (lm != null) latestTeacher.set(lm)
            }
        }
    }

    // ═══ 手势检测（GESTURE_SELECT / IDLE 共用） ═══

    /**
     * 统一手势检测循环：识别 → UI 高亮 → 保持 [GESTURE_HOLD_MS] 确认 → 分发。
     * @param exitAction EXIT 手势确认后的动作（GESTURE_SELECT 中退出教学；IDLE 中无操作）
     */
    private fun detectAndHandleGesture(exitAction: () -> Unit) {
        val user = latestUser.get()
        // 镜像横竖屏一致：手势识别与骨架显示均按用户镜像设置（默认自拍镜像）
        val mirror = cameraManager?.mirror ?: true

        // 更新骨架视图
        skeletonOverlay.updatePose(user, mirror)

        val gesture = if (user != null) {
            GestureRecognizer.detect(user, mirror)
        } else {
            GestureRecognizer.Gesture.NONE
        }

        if (gesture != currentGesture) {
            currentGesture = gesture
            gestureHoldStartMs = SystemClock.elapsedRealtime()
        }

        // 更新 UI 高亮
        updateGestureUI(gesture)
        skeletonOverlay.setActiveGesture(gesture)
        castSync { it.updateSkeleton(user, mirror); it.setActiveGesture(gesture) }

        // 检查手势确认
        val heldMs = SystemClock.elapsedRealtime() - gestureHoldStartMs
        val pct = (heldMs.toFloat() / GESTURE_HOLD_MS * 100).toInt().coerceIn(0, 100)
        gestureConfirmProgress.progress = if (gesture != GestureRecognizer.Gesture.NONE) pct else 0

        if (gesture != GestureRecognizer.Gesture.NONE && heldMs >= GESTURE_HOLD_MS) {
            when (gesture) {
                GestureRecognizer.Gesture.REPEAT -> startTeaching()
                GestureRecognizer.Gesture.PREV -> onNavigatePrev()
                GestureRecognizer.Gesture.NEXT -> onNavigateNext()
                GestureRecognizer.Gesture.EXIT -> exitAction()
                GestureRecognizer.Gesture.NONE -> {}
            }
            currentGesture = GestureRecognizer.Gesture.NONE
            gestureHoldStartMs = 0L
        }
    }

    private fun updateGestureUI(gesture: GestureRecognizer.Gesture) {
        val activeColor = 0xFFFF9800.toInt()
        val inactiveColor = 0xFF888888.toInt()

        val isPrev = gesture == GestureRecognizer.Gesture.PREV
        val isRepeat = gesture == GestureRecognizer.Gesture.REPEAT
        val isNext = gesture == GestureRecognizer.Gesture.NEXT
        val isExit = gesture == GestureRecognizer.Gesture.EXIT

        gesturePrev.setTextColor(if (isPrev) activeColor else inactiveColor)
        gestureRepeat.setTextColor(if (isRepeat) activeColor else inactiveColor)
        gestureNext.setTextColor(if (isNext) activeColor else inactiveColor)
        gestureExit.setTextColor(if (isExit) activeColor else inactiveColor)

        gesturePrevIcon.setColorFilter(if (isPrev) activeColor else inactiveColor)
        gestureRepeatIcon.setColorFilter(if (isRepeat) activeColor else inactiveColor)
        gestureNextIcon.setColorFilter(if (isNext) activeColor else inactiveColor)
        gestureExitIcon.setColorFilter(if (isExit) activeColor else inactiveColor)
    }

    // ═══ 全序列比对 ═══

    /**
     * 逐段练结束时，用练习过程中实时采集的“教师骨架”与“学生骨架”按时间对齐后整体打分。
     * 直接配对 teacherStudentFrames / studentFrames（二者都按练习 elapsed 时间记录，天然对齐）。
     */
    private fun compareFullSequence(): Float {
        if (teacherStudentFrames.isEmpty() || studentFrames.isEmpty()) return 0f

        var sum = 0f
        var count = 0
        for ((elapsed, teacher) in teacherStudentFrames) {
            if (teacher.isEmpty()) continue
            var best: List<Landmark>? = null
            var bestDt = Long.MAX_VALUE
            for ((e, student) in studentFrames) {
                val dt = kotlin.math.abs(e - elapsed)
                if (dt < bestDt) {
                    bestDt = dt
                    best = student
                }
            }
            if (best != null) {
                val result = ComparisonEngine.compare(teacher, best, false, false)
                sum += result.score
                count++
            }
        }

        return if (count > 0) sum / count else 0f
    }

    // ═══ TeachingController.Callback ═══

    override fun onStateChanged(state: TeachingController.State) {
        when (state) {
            TeachingController.State.IDLE -> {
                scoreText.text = "相似度 --%"
                hintText.text = ""
                studentProgress.visibility = View.GONE
                castSync { it.updateScore("相似度 --%"); it.updateHint(""); it.hideStudentProgress() }
                if (currentSegmentedDance != null) {
                    gestureSelectPanel.visibility = View.VISIBLE
                    cameraControlsBar.visibility = View.GONE
                    gestureConfirmProgress.progress = 0
                    updateGestureUI(GestureRecognizer.Gesture.NONE)
                } else {
                    gestureSelectPanel.visibility = View.GONE
                    cameraControlsBar.visibility = View.VISIBLE
                }
                skeletonOverlay.clear()
                gestureTimeoutText.visibility = View.GONE
                currentGesture = GestureRecognizer.Gesture.NONE
                gestureHoldStartMs = 0L
            }
            TeachingController.State.TEACHER_DEMO -> {
                scoreText.text = "相似度 --%"
                hintText.text = ""
                studentProgress.visibility = View.GONE
                segmentProgress.progress = 0
                gestureSelectPanel.visibility = View.GONE
                cameraControlsBar.visibility = View.VISIBLE
                castSync { it.updateTeacherProgress(0); it.hideStudentProgress() }
            }
            TeachingController.State.STUDENT_DANCE -> {
                scoreText.text = "相似度 --%"
                hintText.text = ""
                // 进度条已移除：学生跳舞进度不再显示（布局 studentProgress 默认 gone）
                studentFrames.clear()
                teacherStudentFrames.clear()
                gestureSelectPanel.visibility = View.GONE
                cameraControlsBar.visibility = View.VISIBLE
            }
            TeachingController.State.MATCHED, TeachingController.State.REPLAY,
            TeachingController.State.SKIPPED -> {
                studentProgress.visibility = View.GONE
                castSync { it.hideStudentProgress() }
            }
            TeachingController.State.COMPLETED -> {
                studentProgress.visibility = View.GONE
                castSync { it.hideStudentProgress() }
            }
            TeachingController.State.GESTURE_SELECT -> {
                gestureSelectPanel.visibility = View.VISIBLE
                cameraControlsBar.visibility = View.GONE
                studentProgress.visibility = View.GONE
                currentGesture = GestureRecognizer.Gesture.NONE
                gestureHoldStartMs = 0L
                gestureConfirmProgress.progress = 0
                updateGestureUI(GestureRecognizer.Gesture.NONE)
                skeletonOverlay.clear()
                gestureTimeoutText.visibility = View.GONE
                castSync { it.hideStudentProgress() }
            }
        }
    }

    override fun onSegmentStatusChanged(statuses: List<TeachingController.SegmentStatus>) {
        updateSegmentBlocks(statuses)
    }

    override fun onScoreUpdate(score: Float, hint: String) {
        scoreText.text = "相似度 ${score.toInt()}%"
        hintText.text = hint
        android.util.Log.d("DC", "SCORE=${score.toInt()} t=${latestTeacher.get()?.size} u=${latestUser.get()?.size} hint=$hint")
        castSync { it.updateScore("相似度 ${score.toInt()}%"); it.updateHint(hint) }
    }

    override fun onActiveSideChanged(isTeacher: Boolean) {
        val border = ContextCompat.getDrawable(this, R.drawable.border_active)
        if (isTeacher) {
            teacherContainer.background = border
            studentContainer.setBackgroundColor(android.graphics.Color.BLACK)
        } else {
            teacherContainer.setBackgroundColor(android.graphics.Color.BLACK)
            studentContainer.background = border
        }
        castSync { it.setActiveSide(isTeacher) }
    }

    override fun onPlaySegment(segment: DanceSegment) {
        exoPlayer?.seekTo(segment.startMs)
        exoPlayer?.play()
    }

    override fun onPauseVideo() {
        exoPlayer?.pause()
    }

    override fun onStudentDanceTimeUp() {
        val score = compareFullSequence()
        scoreText.text = "最终 ${score.toInt()}%"
        teachingController.submitStudentScore(score)
    }

    override fun onMatched(score: Float) {
        scoreText.text = "✓ ${score.toInt()}%"
    }

    override fun onSkipped() {
        scoreText.text = "已跳过"
    }

    override fun onRoundCompleted(averageScore: Float, scores: List<SegmentScore>) {
        // 分段流程（分段跳已屏蔽，保留兼容）：
        val danceId = currentLibraryEntry?.id ?: ""
        val reward = RewardManager.awardTap(this, averageScore, danceId)
        val blocked = RewardManager.tapAwardBlockedReason(this, danceId, averageScore)
        showRoundResultPopup(averageScore, recordScore(averageScore), reward, blocked, scores)
    }

    /** 保存本轮分数到舞蹈库，返回是否刷新了历史纪录 */
    private fun recordScore(averageScore: Float): Boolean {
        var isNewRecord = false
        currentLibraryEntry?.let { entry ->
            isNewRecord = entry.bestScore < 0 || averageScore > entry.bestScore
            val newHistory = entry.scoreHistory + averageScore
            val updated = entry.copy(
                lastPlayedAt = System.currentTimeMillis(),
                totalRounds = entry.totalRounds + 1,
                bestScore = if (isNewRecord) averageScore else entry.bestScore,
                scoreHistory = newHistory
            )
            VideoCacheManager.upsertEntry(this, updated)
            currentLibraryEntry = updated
            refreshLibraryList()
        }
        return isNewRecord
    }

    /** 统一结算弹窗：scores 非空时显示各段得分（分段流程），否则为整轮跟跳 */
    private fun showRoundResultPopup(
        averageScore: Float,
        isRecord: Boolean,
        reward: Int,
        blocked: String?,
        scores: List<SegmentScore>?
    ) {
        val entry = currentLibraryEntry
        val sb = StringBuilder()
        if (isRecord) sb.append("🎉 新纪录！\n\n")
        sb.append("本轮完成\n总分：${averageScore.toInt()}%\n")
        if (scores != null) {
            sb.append("各段得分：\n")
            for (s in scores) {
                val status = if (s.skipped) "跳过" else "${s.score.toInt()}%"
                sb.append("  第${s.segmentIndex + 1}段：$status\n")
            }
        }
        if (reward > 0) {
            sb.append("\n💰 跟跳奖励 +${RewardManager.formatCoin(reward)} 舞币\n")
        } else if (blocked != null) {
            sb.append("\n🔒 $blocked\n")
        } else {
            sb.append("\n💡 相似度 ≥${RewardManager.TAP_SCORE_THRESHOLD.toInt()} 可获得 " +
                "${RewardManager.formatCoin(RewardManager.TAP_REWARD_CENTS)} 舞币奖励，再接再厉！\n")
        }
        val bestStr = if (entry != null && entry.bestScore >= 0) {
            "\n历史最高：${entry.bestScore.toInt()}%"
        } else ""
        val msg = sb.toString() + bestStr
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isRecord) "🎉 新纪录！" else "本轮完成")
            .setMessage(msg)
            .setCancelable(false)
            .create()
        dialog.show()
        // 3 秒后自动消失
        handler.postDelayed({ dialog.dismiss() }, 3000)
    }

    // ═══ FollowAlongController.Callback（跟着跳） ═══

    override fun onStateChanged(state: FollowAlongController.State) {
        android.util.Log.d("DC", "onStateChanged $state")
        when (state) {
            FollowAlongController.State.COUNTDOWN -> {
                scoreText.text = "相似度 --%"
                hintText.text = ""
                studentProgress.visibility = View.GONE
                followCountdown.visibility = View.VISIBLE
                gestureSelectPanel.visibility = View.GONE
                cameraControlsBar.visibility = if (isDebugMode) View.GONE else View.VISIBLE
                if (isDebugMode) setDebugSkeletonVisible(true)
                castSync { it.updateScore("相似度 --%"); it.updateHint(""); it.hideStudentProgress() }
            }
            FollowAlongController.State.FOLLOWING -> {
                exoPlayer?.seekTo(0)
                exoPlayer?.play()
                followCountdown.visibility = View.GONE
                // 进度条已移除：跟跳进度不再显示（布局 studentProgress 默认 gone）
                scoreText.text = "相似度 --%"
                hintText.text = ""
                gestureSelectPanel.visibility = View.GONE
                cameraControlsBar.visibility = if (isDebugMode) View.GONE else View.VISIBLE
                if (isDebugMode) setDebugSkeletonVisible(true)
            }
            FollowAlongController.State.COMPLETED -> {
                studentProgress.visibility = View.GONE
                followCountdown.visibility = View.GONE
                setDebugSkeletonVisible(false)
                castSync { it.hideStudentProgress() }
            }
            FollowAlongController.State.IDLE -> { }
        }
    }

    override fun onCountdownTick(remainingSec: Int) {
        followCountdown.text = if (remainingSec > 0) "$remainingSec" else "开始！"
    }

    override fun onFollowProgress(pct: Int) {
        studentProgress.progress = pct
        castSync { it.updateStudentProgress(pct) }
    }

    override fun onRoundCompleted(averageScore: Float) {
        // 跟跳奖励：按本轮相似度结算（鼓励使用；同一支舞每日限 3 次，换舞可继续领）
        val danceId = currentLibraryEntry?.id ?: ""
        val reward = RewardManager.awardTap(this, averageScore, danceId)
        val blocked = RewardManager.tapAwardBlockedReason(this, danceId, averageScore)
        showRoundResultPopup(averageScore, recordScore(averageScore), reward, blocked, null)
        handler.postDelayed({
            teachingController.forceGestureSelect()
        }, 3000)
    }

    override fun onNavigatePrev() {
        // 上一个视频
        val currentIdx = libraryEntries.indexOfFirst { it.id == currentLibraryEntry?.id }
        if (libraryEntries.size <= 1) {
            // 只有一个视频，重练
            currentLibraryEntry?.let { loadVideoFromLibrary(it) }
        } else {
            val prevIdx = if (currentIdx <= 0) libraryEntries.size - 1 else currentIdx - 1
            loadVideoFromLibrary(libraryEntries[prevIdx])
        }
    }

    override fun onNavigateNext() {
        // 下一个视频
        val currentIdx = libraryEntries.indexOfFirst { it.id == currentLibraryEntry?.id }
        if (libraryEntries.size <= 1) {
            currentLibraryEntry?.let { loadVideoFromLibrary(it) }
        } else {
            val nextIdx = if (currentIdx < 0 || currentIdx >= libraryEntries.size - 1) 0 else currentIdx + 1
            loadVideoFromLibrary(libraryEntries[nextIdx])
        }
    }

    override fun onNavigateExit() {
        // 退出教学模式：复位两个状态机，离开教学页回到首页（onDestroy 会释放资源）。
        teachingController.resetToIdle()
        followAlongController.reset()
        currentGesture = GestureRecognizer.Gesture.NONE
        gestureHoldStartMs = 0L
        exoPlayer?.pause()
        Toast.makeText(this, "已退出教学", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ═══ 生命周期 ═══

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "需要摄像头权限才能使用", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        running = false
        handler.removeCallbacks(tickRunnable)
        teachingController.pause()
        followAlongController.pause()
        exoPlayer?.pause()
        cameraManager?.stop()
        cameraManager = null
        extractor.releaseLiveSource()
        setDebugSkeletonVisible(false)
        dismissLoadingDialog()
    }

    override fun onResume() {
        super.onResume()
        // onPause 会释放实时教师解码器，恢复前台时重新 prepare（currentVideoUri 为空表示尚未选视频，跳过）
        if (AppPrefs.enableTeacherDetect) {
            currentVideoUri?.let { extractor.prepareLiveSource(this, it) }
        }
        if (teachingController.state != TeachingController.State.IDLE) {
            running = true
            teachingController.resume()
            handler.post(tickRunnable)
        } else if (currentSegmentedDance != null) {
            // IDLE 状态但有视频：启动 tick 进行手势检测（双臂横在胸前重新进入）
            running = true
            handler.post(tickRunnable)
        }
        if (currentMode == ModePreference.Mode.FOLLOW &&
            followAlongController.state == FollowAlongController.State.FOLLOWING
        ) {
            exoPlayer?.play()
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (cameraManager == null) {
                startCamera()
            }
        }
        applyCastState()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        handler.removeCallbacks(tickRunnable)
        teachingController.destroy()
        followAlongController.destroy()
        exoPlayer?.release()
        cameraManager?.stop()
        extractor.close()
        scope.cancel()
        dismissLoadingDialog()
        CastManager.removePresentationListener(castPresentationListener)
    }

    companion object {
        private const val REQ_CAMERA = 1001
        private const val REQ_PICK = 1002

        /**
         * 总开关：教师视频骨架实时识别。
         * 排查用户侧精度期间置为 false——不做教师取帧/检测/打分，
         * 摄像头检测管线与手势选择界面完全一致（单一 STREAM_MODE 流），
         * 调试模式下用于在前面的可视化窗口显示舞蹈库教师对应的骨架，与用户骨架对照。
         * 是否开启由设置页 AppPrefs.enableTeacherDetect 控制。
         */
    }
}
