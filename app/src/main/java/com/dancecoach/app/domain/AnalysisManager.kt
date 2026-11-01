/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.dancecoach.app.data.VideoCacheManager
import com.dancecoach.app.ml.PoseExtractor
import com.google.mlkit.vision.pose.PoseLandmark
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 动作预审队列管理器（单例）。
 *
 * 流程：
 * 1. 用 MediaMetadataRetriever 按采样间隔（默认 100ms，可在设置页调整）取一帧
 * 2. 对每帧运行 PoseExtractor.detectTeacherFrame()（SINGLE_IMAGE_MODE）
 * 3. 用 Segmenter 对骨架序列分段
 * 4. 计算 4 项质量指标 → 更新 VideoLibraryEntry
 */
object AnalysisManager {

    private const val TAG = "AnalysisManager"

    /** 分析任务 */
    data class Task(
        val entryId: String,
        val uri: String,
        val durationMs: Long
    )

    /** 单次分析结果 */
    data class Result(
        val entryId: String,
        val status: AnalysisStatus,
        val progress: Int,
        val actionQuality: Float,
        val detectionRate: Float,
        val avgConfidence: Float,
        val validFrameCount: Int,
        val segmentCount: Int,
        val difficulty: String,
        val message: String?
    )

    /** 进度回调（entryId -> Result），用 LiveData 供 Activity 观察 */
    val progressLiveData = MutableLiveData<Result>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = ConcurrentLinkedQueue<Task>()
    private var currentJob: Job? = null
    private var poseExtractor: PoseExtractor? = null
    @Volatile private var cancelled = false

    /** 初始化 PoseExtractor（由 Application 或 Activity 调用） */
    fun init(context: Context) {
        if (poseExtractor == null) {
            poseExtractor = PoseExtractor(context.applicationContext)
        }
    }

    /** 入队一个视频进行分析 */
    fun enqueue(context: Context, entry: VideoLibraryEntry) {
        // 标记为排队中
        val updated = entry.copy(
            analysisStatus = AnalysisStatus.QUEUED,
            analysisProgress = 0,
            analysisMessage = "排队中..."
        )
        VideoCacheManager.upsertEntry(context, updated)

        pending.add(Task(entry.id, entry.uri, entry.durationMs))

        progressLiveData.postValue(Result(
            entryId = entry.id,
            status = AnalysisStatus.QUEUED,
            progress = 0,
            actionQuality = -1f,
            detectionRate = 0f,
            avgConfidence = 0f,
            validFrameCount = 0,
            segmentCount = 0,
            difficulty = "未分析",
            message = "排队中..."
        ))

        startNext(context)
    }

    /** 取消指定视频的分析 */
    fun cancel(entryId: String) {
        pending.removeAll { it.entryId == entryId }
        if (currentJob?.isActive == true && currentEntryId == entryId) {
            cancelled = true
        }
    }

    private var currentEntryId: String? = null

    private fun startNext(context: Context) {
        if (currentJob?.isActive == true) return
        val task = pending.poll() ?: return
        currentEntryId = task.entryId

        currentJob = scope.launch {
            analyseVideo(context, task)
            // 处理队列中下一个
            withContext(Dispatchers.Main) {
                currentEntryId = null
                startNext(context)
            }
        }
    }

    private suspend fun analyseVideo(context: Context, task: Task) {
        val extractor = poseExtractor ?: run {
            Log.e(TAG, "PoseExtractor not initialized")
            return
        }
        cancelled = false

        // 标记分析中
        updateEntry(context, task.entryId) {
            it.copy(analysisStatus = AnalysisStatus.ANALYZING, analysisProgress = 0, analysisMessage = "正在分析...")
        }
        progressLiveData.postValue(Result(
            entryId = task.entryId, status = AnalysisStatus.ANALYZING,
            progress = 0, actionQuality = -1f, detectionRate = 0f,
            avgConfidence = 0f, validFrameCount = 0, segmentCount = 0,
            difficulty = "未分析", message = "正在分析..."
        ))

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(task.uri))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open video", e)
            markFailed(context, task.entryId, "无法打开视频")
            return
        }

        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        val videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: 0
        val videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: 0

        // 采样帧
        val totalFrames = if (task.durationMs > 0) (task.durationMs / AppPrefs.sampleIntervalMs).toInt() else 0
        if (totalFrames == 0) {
            retriever.release()
            markFailed(context, task.entryId, "视频时长无效")
            return
        }

        val frames = mutableListOf<PoseFrame>()
        var detectedCount = 0
        var confidenceSum = 0f
        var validTorsoCount = 0

        for (i in 0 until totalFrames) {
            if (cancelled) {
                retriever.release()
                markCancelled(context, task.entryId)
                return
            }

            val timeMs = i * AppPrefs.sampleIntervalMs
            val bmp: Bitmap? = try {
                retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) { null }

            if (bmp == null) {
                frames.add(PoseFrame(timeMs, emptyList()))
                continue
            }

            val landmarks = try {
                // 帧物理旋转到正立后送检（rotation 传 0），与预审页实时检测同一路径
                val upright = PoseExtractor.uprightBitmap(bmp, rotation, videoWidth, videoHeight)
                val lm = extractor.detectTeacherFrame(upright, 0)
                if (upright !== bmp) upright.recycle()
                lm
            } catch (_: Exception) { null }
            bmp.recycle()

            if (landmarks != null && landmarks.isNotEmpty()) {
                detectedCount++
                val frameConfidence = landmarks.map { it.score }.average().toFloat()
                confidenceSum += frameConfidence

                // 检查是否完整躯干（双肩+双髋）
                val hasTorso = landmarks.any { it.type == PoseLandmark.LEFT_SHOULDER } &&
                        landmarks.any { it.type == PoseLandmark.RIGHT_SHOULDER } &&
                        landmarks.any { it.type == PoseLandmark.LEFT_HIP } &&
                        landmarks.any { it.type == PoseLandmark.RIGHT_HIP }
                if (hasTorso) validTorsoCount++

                frames.add(PoseFrame(timeMs, landmarks))
            } else {
                frames.add(PoseFrame(timeMs, emptyList()))
            }

            // 更新进度
            val progress = ((i + 1) * 100 / totalFrames).coerceIn(0, 95)
            updateEntry(context, task.entryId) {
                it.copy(analysisProgress = progress, analysisMessage = "分析中 $progress%")
            }
            progressLiveData.postValue(Result(
                entryId = task.entryId, status = AnalysisStatus.ANALYZING,
                progress = progress, actionQuality = -1f, detectionRate = 0f,
                avgConfidence = 0f, validFrameCount = 0, segmentCount = 0,
                difficulty = "未分析", message = "分析中 $progress%"
            ))
        }

        retriever.release()

        // 分段
        val seq = PoseSequence(
            name = task.entryId,
            durationMs = task.durationMs,
            fps = (1000f / AppPrefs.sampleIntervalMs).toInt(),
            sourceVideo = true,
            frames = frames
        )
        val segmented = Segmenter.segment(seq)

        // 计算指标
        val detectionRate = detectedCount.toFloat() / totalFrames
        val avgConfidence = if (detectedCount > 0) confidenceSum / detectedCount else 0f
        val validFrames = validTorsoCount
        val segCount = segmented.segments.size

        // 质量评分
        val quality = computeQuality(detectionRate, avgConfidence, validFrames, segCount)
        val status = determineStatus(detectionRate, avgConfidence, validFrames, segCount)
        val difficulty = determineDifficulty(segmented)
        val message = buildMessage(status, detectionRate, avgConfidence, validFrames, segCount)

        // 保存分段缓存
        VideoCacheManager.saveCache(context, task.entryId, segmented)

        // 更新条目
        updateEntry(context, task.entryId) {
            it.copy(
                analysisStatus = status,
                analysisProgress = 100,
                actionQuality = quality,
                detectionRate = detectionRate,
                avgConfidence = avgConfidence,
                validFrameCount = validFrames,
                segmentCount = segCount,
                difficulty = difficulty,
                analyzed = true,
                analysisMessage = message
            )
        }
        progressLiveData.postValue(Result(
            entryId = task.entryId, status = status,
            progress = 100, actionQuality = quality,
            detectionRate = detectionRate, avgConfidence = avgConfidence,
            validFrameCount = validFrames, segmentCount = segCount,
            difficulty = difficulty, message = message
        ))
    }

    private fun computeQuality(detectionRate: Float, avgConfidence: Float, validFrames: Int, segCount: Int): Float {
        // 4 项指标加权平均 → 0-100
        val drScore = (detectionRate / 0.7f).coerceIn(0f, 1f)
        val confScore = (avgConfidence / 0.5f).coerceIn(0f, 1f)
        val frameScore = (validFrames.toFloat() / 15f).coerceIn(0f, 1f)
        val segScore = (segCount.toFloat() / 3f).coerceIn(0f, 1f)
        return (drScore * 30 + confScore * 25 + frameScore * 20 + segScore * 25).coerceIn(0f, 100f)
    }

    private fun determineStatus(detectionRate: Float, avgConfidence: Float, validFrames: Int, segCount: Int): AnalysisStatus {
        if (detectionRate < 0.3f) return AnalysisStatus.FAILED
        val allPass = detectionRate >= 0.7f && avgConfidence >= 0.5f && validFrames >= 15 && segCount >= 3
        return if (allPass) AnalysisStatus.ANALYZED else AnalysisStatus.WARNING
    }

    private fun determineDifficulty(segmented: SegmentedDance): String {
        if (segmented.segments.isEmpty()) return "未知"
        val avgDiff = segmented.segments.map { it.difficulty }.average().toFloat()
        return when {
            avgDiff < 0.1f -> "简单"
            avgDiff < 0.3f -> "中等"
            else -> "较难"
        }
    }

    private fun buildMessage(status: AnalysisStatus, dr: Float, conf: Float, vf: Int, sc: Int): String? {
        return when (status) {
            AnalysisStatus.ANALYZED -> "动作质量合格"
            AnalysisStatus.WARNING -> {
                val issues = mutableListOf<String>()
                if (dr < 0.7f) issues.add("检测率偏低(${(dr*100).toInt()}%)")
                if (conf < 0.5f) issues.add("置信度偏低(${"%.2f".format(conf)})")
                if (vf < 15) issues.add("有效帧不足($vf)")
                if (sc < 3) issues.add("分段不足($sc)")
                issues.joinToString("，")
            }
            AnalysisStatus.FAILED -> "检测率过低(${(dr*100).toInt()}%)，建议重新导入"
            else -> null
        }
    }

    private fun markFailed(context: Context, entryId: String, msg: String) {
        updateEntry(context, entryId) {
            it.copy(analysisStatus = AnalysisStatus.FAILED, analysisProgress = 100, analysisMessage = msg)
        }
        progressLiveData.postValue(Result(
            entryId = entryId, status = AnalysisStatus.FAILED,
            progress = 100, actionQuality = -1f, detectionRate = 0f,
            avgConfidence = 0f, validFrameCount = 0, segmentCount = 0,
            difficulty = "未知", message = msg
        ))
    }

    private fun markCancelled(context: Context, entryId: String) {
        updateEntry(context, entryId) {
            it.copy(analysisStatus = AnalysisStatus.NONE, analysisProgress = 0, analysisMessage = null)
        }
    }

    private fun updateEntry(context: Context, entryId: String, block: (VideoLibraryEntry) -> VideoLibraryEntry) {
        val lib = VideoCacheManager.loadLibrary(context)
        val idx = lib.entries.indexOfFirst { it.id == entryId }
        if (idx >= 0) {
            lib.entries[idx] = block(lib.entries[idx])
            VideoCacheManager.saveLibrary(context, lib)
        }
    }
}
