/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain.market

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.dancecoach.app.data.VideoCacheManager
import com.dancecoach.app.data.VideoValidator
import com.dancecoach.app.domain.VideoLibraryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 市场 ↔ 舞蹈库 桥梁：把市场视频下载并导入到本地舞蹈库（闭环的关键一环）。
 * 流式下载，避免大视频一次性读进内存导致 OOM。
 */
object MarketLibraryBridge {

    /**
     * 把市场视频下载到 App 自有 Movies 目录，并登记进本地舞蹈库。
     * @param fileId   云端 fileID（用于换临时下载 URL）
     * @param title    显示标题（取市场条目标题）
     * @param sourceId 市场 itemId，作为去重键，同一市场视频只入库一次
     * @return 导入后的 VideoLibraryEntry（失败返回 null）
     */
    suspend fun downloadIntoLibrary(
        context: Context,
        repo: MarketRepository,
        fileId: String,
        title: String,
        sourceId: String
    ): VideoLibraryEntry? = withContext(Dispatchers.IO) {
        val url = repo.getFileUrl(fileId)
        if (url.isBlank()) return@withContext null

        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val file = File(dir, "$sourceId.mp4")

        // 流式下载到本地文件
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        try {
            conn.inputStream.use { inp ->
                file.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (inp.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        // 入库前校验：竖屏 / 单人 / 身高占比 / ≤30s / ≤30MB。
        // 不通过则删除已下载文件并抛错，由调用方提示（同时不扣舞币）。
        val check = VideoValidator.validate(context, Uri.fromFile(file), file.length())
        if (!check.passed) {
            file.delete()
            throw IllegalStateException("该视频不符合入库标准：\n${check.reason}")
        }

        VideoCacheManager.importFromMarket(context, file, title, sourceId)
    }
}
