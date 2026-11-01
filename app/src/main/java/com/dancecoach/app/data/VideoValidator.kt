/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.dancecoach.app.ml.PoseExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 舞蹈库入库前的视频素材校验：
 * - 仅支持竖屏（按显示方向判定，兼容带旋转元数据的视频）
 * - 单人（姿态检测能稳定检测到且画面中只有一个人）
 * - 人物身高占比合适（0.25 ~ 0.92 画面高）
 * - 视频时长 ≤ 30 秒
 * - 文件大小 ≤ 30 MB
 *
 * 校验失败的视频不会被写入舞蹈库，调用方应把 [Result.reason] 展示给用户。
 */
object VideoValidator {

    const val MAX_DURATION_MS = 30_000L
    const val MAX_SIZE_BYTES = 30L * 1024 * 1024
    const val MIN_BODY_RATIO = 0.25f
    const val MAX_BODY_RATIO = 0.92f
    const val MIN_VALID_RATIO = 0.6f
    private const val MIN_LM_SCORE = 0.3f
    private const val SAMPLE_FRAMES = 6

    /** 校验结果：passed=false 时 errors 为全部未通过原因（按检查顺序排列） */
    data class Result(val passed: Boolean, val errors: List<String>) {
        val reason: String get() = errors.joinToString("\n")

        companion object {
            fun ok() = Result(true, emptyList())
        }
    }

    /**
     * 校验视频是否符合入库标准。全部通过返回 ok()；任一不通过返回对应原因。
     * @param uri      视频 URI（content:// 或 file://）
     * @param fileSize 文件大小（bytes）；传 -1 时内部自动查询
     */
    suspend fun validate(context: Context, uri: Uri, fileSize: Long = -1L): Result {
        return withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
            } catch (e: Exception) {
                return@withContext Result(false, listOf("无法读取该视频文件"))
            }

            try {
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0

                val errors = mutableListOf<String>()

                // 1) 时长 ≤ 30s
                if (durationMs > MAX_DURATION_MS) {
                    errors.add("视频时长 ${durationMs / 1000} 秒，超过 30 秒上限")
                }

                // 2) 大小 ≤ 30MB
                val size = if (fileSize >= 0) fileSize else queryFileSize(context, uri)
                if (size > MAX_SIZE_BYTES) {
                    errors.add("文件大小 ${(size / 1024 / 1024)}MB，超过 30MB 上限")
                }

                // 3) 竖屏：按显示方向判定。带 90/270 旋转的视频，编码宽高互换才为显示方向。
                val displayPortrait = if (rotation % 180 != 0 && width > 0 && height > 0) {
                    width > height
                } else {
                    height > width
                }
                if (!displayPortrait) {
                    errors.add("仅支持竖屏视频，请使用竖屏拍摄")
                }

                // 4) 单人 + 身高占比（姿态检测抽样）
                if (durationMs > 0) {
                    val (validRatio, bodyRatio) =
                        samplePose(context, uri, durationMs, rotation, width, height)
                    if (validRatio < MIN_VALID_RATIO) {
                        errors.add("未稳定检测到单人舞者，请确保画面中只有一个人且全身可见")
                    } else {
                        if (bodyRatio < MIN_BODY_RATIO) {
                            errors.add("人物在画面中占比过小，请靠近拍摄")
                        } else if (bodyRatio > MAX_BODY_RATIO) {
                            errors.add("人物在画面中占比过大，请拉远拍摄")
                        }
                    }
                }

                if (errors.isEmpty()) Result.ok() else Result(false, errors)
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
    }

    /**
     * 均匀抽帧做姿态检测，返回 (有效帧占比, 平均身高占比)。
     * 有效帧 = 能检测到姿态且高分关键点 ≥ 6 个的帧。
     * 抽帧整体失败时返回 (1f, 0.5f)，放弃姿态判定，避免误伤可播放但抽帧异常的素材。
     */
    private suspend fun samplePose(
        context: Context,
        uri: Uri,
        durationMs: Long,
        rotation: Int,
        videoWidth: Int,
        videoHeight: Int
    ): Pair<Float, Float> {
        val extractor = PoseExtractor(context)
        val retriever = MediaMetadataRetriever()
        var valid = 0
        val ratios = mutableListOf<Float>()
        try {
            retriever.setDataSource(context, uri)
            for (i in 1..SAMPLE_FRAMES) {
                val t = durationMs * i / (SAMPLE_FRAMES + 1)
                val frame = try {
                    retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (e: Exception) {
                    null
                } ?: continue
                val upright = PoseExtractor.uprightBitmap(frame, rotation, videoWidth, videoHeight)
                val landmarks = try {
                    extractor.detectTeacherFrame(upright, 0)
                } catch (e: Exception) {
                    null
                }
                if (upright !== frame) upright.recycle()
                frame.recycle()

                if (landmarks == null) continue
                val pts = landmarks.filter { it.score >= MIN_LM_SCORE }
                if (pts.size < 6) continue
                val minY = pts.minOf { it.y }
                val maxY = pts.maxOf { it.y }
                // 关键点坐标在「最长边缩到 640」的送检图空间，等比换算回原帧高度再求占比
                val longEdge = maxOf(upright.width, upright.height).coerceAtLeast(1)
                val scale = if (longEdge > 640) 640f / longEdge else 1f
                val frameH = (upright.height * scale).coerceAtLeast(1f)
                ratios.add(((maxY - minY) / frameH).coerceIn(0f, 1.2f))
                valid++
            }
        } catch (e: Exception) {
            return 1f to 0.5f
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            try { extractor.close() } catch (_: Exception) {}
        }
        if (ratios.isEmpty()) return 0f to 0f
        return (valid.toFloat() / SAMPLE_FRAMES) to (ratios.average().toFloat())
    }

    private fun queryFileSize(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") {
            return try { File(uri.path ?: "").length() } catch (e: Exception) { 0L }
        }
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) c.getLong(idx) else 0L
                } else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
