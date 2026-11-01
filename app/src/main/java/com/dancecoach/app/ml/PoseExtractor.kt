/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.dancecoach.app.domain.Landmark
import com.dancecoach.app.domain.PoseUtils
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseDetectorOptionsBase
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 包裹 ML Kit 姿态检测。
 *  - detect(): 对单张 Bitmap 实时检测（摄像头/教师视频帧共用）
 *  - prepareLiveSource / detectLiveFrameAt / releaseLiveSource: 实时教学时，
 *    用独立 MediaMetadataRetriever 按播放时间从视频取帧并检测，作为“教师骨架”。
 *    不再在导入时离线预处理整段视频（避免长耗时，且教师骨架随播放实时产生）。
 */
class PoseExtractor(context: Context) {

    /** 摄像头流专用检测器：STREAM_MODE 依赖"连续帧来自同一视频流"做时序跟踪与平滑 */
    private val detector: PoseDetector

    /**
     * 教师视频帧专用检测器：SINGLE_IMAGE_MODE。
     * 教师帧由 MediaMetadataRetriever 按播放时间独立抽取，帧间不连续，
     * 若与摄像头帧共用同一个 STREAM_MODE 检测器，两条无关的画面交错会
     * 污染时序跟踪模型，导致两侧精度同时下降——这是之前跟跳/调试界面
     * 骨架识别明显差于手势选择界面的主因。
     */
    private val teacherDetector: PoseDetector

    /** 实时教师帧检测用的视频源（与 ExoPlayer 各自独立解码，互不干扰） */
    private var liveRetriever: MediaMetadataRetriever? = null
    private var liveRotation = 0
    private var liveVideoWidth = 0
    private var liveVideoHeight = 0

    /** 送网最长边目标分辨率 */
    private val DETECT_LONG_EDGE = 640

    /**
     * 教师视频 retriever 专用单线程调度器。
     * MediaMetadataRetriever 非线程安全，在 IO 线程 getFrameAtTime 的同时主线程 release
     * 会导致 native 死锁或崩溃；所有 prepare/release/extract 操作串行化到此线程。
     */
    private val liveExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DC-teacher-retriever")
    }
    private val liveDispatcher = liveExecutor.asCoroutineDispatcher()

    init {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptionsBase.STREAM_MODE)
            .build()
        detector = PoseDetection.getClient(options)

        val teacherOptions = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptionsBase.SINGLE_IMAGE_MODE)
            .build()
        teacherDetector = PoseDetection.getClient(teacherOptions)
    }

    /** 对摄像头帧做姿态检测（STREAM_MODE，享受时序平滑）。 */
    suspend fun detect(bitmap: Bitmap, rotationDegrees: Int = 0): List<Landmark>? =
        detectOn(detector, bitmap, rotationDegrees)

    /** 对教师视频帧做姿态检测（SINGLE_IMAGE_MODE，独立检测器，不污染摄像头流）。 */
    suspend fun detectTeacherFrame(bitmap: Bitmap, rotationDegrees: Int = 0): List<Landmark>? =
        detectOn(teacherDetector, bitmap, rotationDegrees)

    private suspend fun detectOn(
        target: PoseDetector,
        bitmap: Bitmap,
        rotationDegrees: Int
    ): List<Landmark>? {
        val resized = resizeLongEdge(bitmap, DETECT_LONG_EDGE)
        val result = suspendCancellableCoroutine<List<Landmark>?> { cont ->
            val image = InputImage.fromBitmap(resized, rotationDegrees)
            target.process(image)
                .addOnSuccessListener { pose: Pose ->
                    val landmarks = PoseUtils.poseToLandmarks(pose)
                    cont.resume(if (landmarks.isNotEmpty()) landmarks else null)
                }
                .addOnFailureListener {
                    cont.resume(null)
                }
        }
        if (resized !== bitmap) resized.recycle()
        return result
    }

    /**
     * 保持原始比例，将最长边缩放到 [targetLong]。
     * 若原图最长边已 ≤ targetLong 则原样返回（不放大）。
     */
    private fun resizeLongEdge(src: Bitmap, targetLong: Int): Bitmap {
        val long = maxOf(src.width, src.height)
        if (long <= targetLong) return src
        val scale = targetLong.toFloat() / long
        val newW = (src.width * scale).toInt()
        val newH = (src.height * scale).toInt()
        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }

    // ═══ 实时教师帧检测 ═══

    /** 准备一段视频作为"实时教师源"。会打开一个独立的解码器。串行到专用线程执行。 */
    fun prepareLiveSource(context: Context, uri: Uri) {
        releaseLiveSource()
        liveExecutor.execute {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                liveRotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                liveVideoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                liveVideoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                liveRetriever = retriever
            } catch (_: Exception) {
                try { retriever.release() } catch (_: Exception) { }
            }
        }
    }

    /** 释放实时教师源解码器（切视频/暂停/退出时调用）。 */
    fun releaseLiveSource() {
        liveExecutor.execute {
            try { liveRetriever?.release() } catch (_: Exception) { }
            liveRetriever = null
        }
    }

    /**
     * 从正在播放的视频里取 [timeMs] 处的帧并实时检测，返回教师骨架。
     * ML Kit 返回的坐标处于"送入 bitmap 自身坐标空间"，rotation 只影响内部
     * 检测、不改变输出坐标系——因此把帧物理旋转到正立方向后送检（rotation 传 0），
     * 骨架与 overlay 的坐标映射（x→屏幕x, y→屏幕y）天然对齐。
     * 未 prepare 或取帧失败返回 null。
     */
    suspend fun detectLiveFrameAt(timeMs: Long): List<Landmark>? {
        return withContext(liveDispatcher) {
            val retriever = liveRetriever ?: return@withContext null
            val bmp = try {
                retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (_: Exception) {
                null
            } ?: return@withContext null
            val upright = uprightBitmap(bmp, liveRotation, liveVideoWidth, liveVideoHeight)
            val landmarks = detectTeacherFrame(upright, 0)
            android.util.Log.d("DC_T", "detectLiveFrameAt t=$timeMs src=${bmp.width}x${bmp.height} vRot=$liveRotation upright=${upright.width}x${upright.height} ret=${landmarks?.size}")
            if (upright !== bmp) upright.recycle()
            bmp.recycle()
            landmarks
        }
    }

    fun close() {
        releaseLiveSource()
        detector.close()
        teacherDetector.close()
        liveExecutor.shutdown()
    }

    companion object {
        /**
         * 把 getFrameAtTime 取出的帧旋转到正立（显示）方向，返回可能的新 bitmap。
         *
         * 背景：ML Kit 对 InputImage.fromBitmap(bmp, rotation) 返回的坐标处于
         * bmp 自身坐标空间（rotation 只供内部检测用），所以只有送检 bitmap 物理
         * 正立，下游骨架显示才是正立的。
         *
         * 部分设备的 getFrameAtTime 已应用视频旋转元数据（返回帧已正立），部分
         * 设备返回编码方向帧（人横躺）。通过对比"视频显示方向"与"帧实际宽高
         * 方向"判断帧是否已正立，避免二次旋转：
         * 旋转 90/270 时显示宽高互换，显示竖屏 iff 编码宽 > 编码高。
         */
        @JvmStatic
        fun uprightBitmap(
            frame: Bitmap,
            videoRotation: Int,
            videoWidth: Int,
            videoHeight: Int
        ): Bitmap {
            val alreadyUpright = if (videoRotation % 180 != 0 && videoWidth > 0 && videoHeight > 0) {
                val displayPortrait = videoWidth > videoHeight
                val framePortrait = frame.height > frame.width
                displayPortrait == framePortrait
            } else {
                // 无旋转或 180°（宽高不变，无法用宽高判断，保守视为已正立）
                true
            }
            if (alreadyUpright) return frame
            val m = android.graphics.Matrix()
            // 编码方向帧顺时针转 rotation 度即为显示正立方向
            m.postRotate(videoRotation.toFloat())
            return Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, m, true)
        }
    }
}
