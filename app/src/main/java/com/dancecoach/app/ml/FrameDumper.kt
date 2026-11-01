/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.ml

import android.content.Context
import android.graphics.Bitmap
import com.dancecoach.app.domain.Landmark
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 调试专用：把“真正送进姿态网络的帧”连同尺寸/旋转元数据落盘，用于离线量化抖动来源。
 * 不影响正常逻辑，仅当 enabled 开关打开时写文件。
 */
object FrameDumper {

    @Volatile var userEnabled = false

    private const val USER_LIMIT = 60
    private var userIdx = 0

    fun teacherDir(context: Context): File =
        File(context.cacheDir, "debug_frames").also { it.mkdirs() }

    /** 用户：落盘 ML Kit 实际看到的旋转后帧 + 尺寸 + 检测到的 landmarks */
    fun dumpUser(
        context: Context,
        bmp: Bitmap,
        rotationDegrees: Int,
        landmarks: List<Landmark>?
    ) {
        if (!userEnabled || userIdx >= USER_LIMIT) return
        val dir = teacherDir(context)
        val idx = userIdx++
        val img = File(dir, "user_%03d.jpg".format(idx))
        try {
            FileOutputStream(img).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (_: Exception) {}
        val meta = JSONObject().apply {
            put("idx", idx)
            put("rot", rotationDegrees)
            put("w", bmp.width); put("h", bmp.height)
            val arr = JSONArray()
            landmarks?.forEach {
                arr.put(JSONObject().apply {
                    put("type", it.type); put("x", it.x); put("y", it.y); put("s", it.score)
                })
            }
            put("lm", arr)
        }
        appendJsonl(File(dir, "user_frames.jsonl"), meta)
    }

    private fun appendJsonl(file: File, obj: JSONObject) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(raf.length())
                raf.write((obj.toString() + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (_: Exception) {}
    }
}
