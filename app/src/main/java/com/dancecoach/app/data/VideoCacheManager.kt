/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.dancecoach.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 视频缓存管理器：
 * - 按 cacheId (= MD5(uri+size+mtime)) 缓存骨架序列 + 分段结果
 * - 管理视频库索引 (library.json)
 * - 提取视频缩略图
 */
object VideoCacheManager {

    private const val CACHE_ROOT = "video_cache"
    private const val LIBRARY_FILE = "library.json"
    private const val SEGMENTS_FILE = "segments.json"
    private const val THUMB_FILE = "thumbnail.jpg"

    /** 计算视频的缓存ID */
    fun computeCacheId(uri: Uri, fileSize: Long, lastModified: Long): String {
        return md5("$uri|$fileSize|$lastModified")
    }

    /** 加载缓存的分段结果（实时模式下只有分段，不含离线骨架序列） */
    fun loadCache(context: Context, cacheId: String): SegmentedDance? {
        val dir = getCacheDir(context, cacheId)
        val segFile = File(dir, SEGMENTS_FILE)
        if (!segFile.exists()) return null

        return try {
            segmentsFromJson(JSONObject(segFile.readText()))
        } catch (e: Exception) {
            null
        }
    }

    /** 保存分段结果到缓存 */
    fun saveCache(context: Context, cacheId: String, segmented: SegmentedDance) {
        val dir = getCacheDir(context, cacheId)
        dir.mkdirs()
        File(dir, SEGMENTS_FILE).writeText(segmentsToJson(segmented).toString())
    }

    /** 加载整个视频库索引 */
    fun loadLibrary(context: Context): VideoLibrary {
        val file = File(getCacheRoot(context), LIBRARY_FILE)
        if (!file.exists()) return VideoLibrary(mutableListOf())
        return try {
            VideoLibrary.fromJson(JSONObject(file.readText()))
        } catch (e: Exception) {
            VideoLibrary(mutableListOf())
        }
    }

    /** 保存视频库索引 */
    fun saveLibrary(context: Context, library: VideoLibrary) {
        val root = getCacheRoot(context)
        root.mkdirs()
        File(root, LIBRARY_FILE).writeText(library.toJson().toString())
    }

    /** 添加/更新一条视频库记录 */
    fun upsertEntry(context: Context, entry: VideoLibraryEntry) {
        val library = loadLibrary(context)
        val idx = library.entries.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            library.entries[idx] = entry
        } else {
            library.entries.add(entry)
        }
        library.entries.sortByDescending { it.lastPlayedAt }
        saveLibrary(context, library)
    }

    /** 删除一条视频库记录 + 清理缓存目录 */
    fun deleteEntry(context: Context, cacheId: String) {
        val library = loadLibrary(context)
        library.entries.removeAll { it.id == cacheId }
        saveLibrary(context, library)

        val dir = getCacheDir(context, cacheId)
        dir.deleteRecursively()
    }

    /** 获取视频缩略图路径（不存在则从视频提取并缓存） */
    fun getThumbnail(context: Context, uri: Uri, cacheId: String): String? {
        val dir = getCacheDir(context, cacheId)
        val thumbFile = File(dir, THUMB_FILE)
        if (thumbFile.exists()) return thumbFile.absolutePath

        dir.mkdirs()
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bmp = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            FileOutputStream(thumbFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            bmp.recycle()
            thumbFile.absolutePath
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * 仅提取视频元数据并创建库条目，不进行骨架分析（懒分析）。
     * 返回创建好的 VideoLibraryEntry。
     */
    fun addVideoMetadata(context: Context, uri: Uri): VideoLibraryEntry? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L

            // 文件信息
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            var fileSize = 0L
            var lastModified = 0L
            cursor?.use {
                val sizeIdx = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                val modIdx = it.getColumnIndex(android.provider.MediaStore.MediaColumns.DATE_MODIFIED)
                if (it.moveToFirst()) {
                    if (sizeIdx >= 0) fileSize = it.getLong(sizeIdx)
                    if (modIdx >= 0) lastModified = it.getLong(modIdx)
                }
            }

            val cacheId = computeCacheId(uri, fileSize, lastModified)
            val displayName = uri.lastPathSegment?.substringBeforeLast(".") ?: "未知视频"
            val thumbPath = getThumbnail(context, uri, cacheId)

            val entry = VideoLibraryEntry(
                id = cacheId,
                displayName = displayName,
                uri = uri.toString(),
                fileSize = fileSize,
                lastModified = lastModified,
                durationMs = durationMs,
                segmentCount = 0,
                difficulty = "未分析",
                thumbnailPath = thumbPath,
                lastPlayedAt = System.currentTimeMillis(),
                totalRounds = 0,
                bestScore = -1f,
                cacheDir = "$CACHE_ROOT/$cacheId",
                scoreHistory = emptyList(),
                analyzed = false
            )
            upsertEntry(context, entry)
            entry
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * 把「从市场下载到本机的视频文件」导入本地舞蹈库。
     * - 用 sourceId（市场 itemId）去重：已存在则只刷新最后播放时间，不重复建条目。
     * - 文件位于 App 自有目录，用 file:// URI 引用，永久可读、无需存储权限。
     * 返回导入（或已存在）的 VideoLibraryEntry。
     */
    fun importFromMarket(context: Context, file: File, title: String, sourceId: String): VideoLibraryEntry? {
        val lib = loadLibrary(context)
        val existing = lib.entries.find { it.sourceId == sourceId }
        if (existing != null) {
            val updated = existing.copy(lastPlayedAt = System.currentTimeMillis())
            upsertEntry(context, updated)
            return updated
        }
        val uri = Uri.fromFile(file)
        val base = addVideoMetadata(context, uri) ?: return null
        val entry = base.copy(
            displayName = title.ifBlank { base.displayName },
            sourceId = sourceId
        )
        upsertEntry(context, entry)
        return entry
    }

    // ═══ 内部工具 ═══

    private fun getCacheRoot(context: Context): File =
        File(context.cacheDir, CACHE_ROOT)

    private fun getCacheDir(context: Context, cacheId: String): File =
        File(context.cacheDir, "$CACHE_ROOT/$cacheId")

    /** SegmentedDance -> JSON */
    private fun segmentsToJson(segmented: SegmentedDance): JSONObject {
        val root = JSONObject()
        val arr = JSONArray()
        for (seg in segmented.segments) {
            val o = JSONObject()
            o.put("index", seg.index)
            o.put("startMs", seg.startMs)
            o.put("endMs", seg.endMs)
            o.put("difficulty", seg.difficulty)
            o.put("label", seg.label)
            o.put("startFrame", poseFrameToJson(seg.startFrame))
            o.put("endFrame", poseFrameToJson(seg.endFrame))
            arr.put(o)
        }
        root.put("segments", arr)
        return root
    }

    /** JSON -> SegmentedDance */
    private fun segmentsFromJson(obj: JSONObject): SegmentedDance {
        val arr = obj.getJSONArray("segments")
        val segments = mutableListOf<DanceSegment>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            segments.add(
                DanceSegment(
                    index = o.getInt("index"),
                    startMs = o.getLong("startMs"),
                    endMs = o.getLong("endMs"),
                    startFrame = poseFrameFromJson(o.getJSONObject("startFrame")),
                    endFrame = poseFrameFromJson(o.getJSONObject("endFrame")),
                    difficulty = o.getDouble("difficulty").toFloat(),
                    label = o.getString("label")
                )
            )
        }
        return SegmentedDance(segments)
    }

    private fun poseFrameToJson(frame: PoseFrame): JSONObject {
        val o = JSONObject()
        o.put("t", frame.t)
        val lmArr = JSONArray()
        for (lm in frame.landmarks) {
            val lo = JSONObject()
            lo.put("t", lm.type)
            lo.put("x", lm.x)
            lo.put("y", lm.y)
            lo.put("s", lm.score)
            lmArr.put(lo)
        }
        o.put("l", lmArr)
        return o
    }

    private fun poseFrameFromJson(o: JSONObject): PoseFrame {
        val t = o.getLong("t")
        val lm = mutableListOf<Landmark>()
        val la = o.getJSONArray("l")
        for (j in 0 until la.length()) {
            val lo = la.getJSONObject(j)
            lm.add(
                Landmark(
                    lo.getInt("t"),
                    lo.getDouble("x").toFloat(),
                    lo.getDouble("y").toFloat(),
                    lo.getDouble("s").toFloat()
                )
            )
        }
        return PoseFrame(t, lm)
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
