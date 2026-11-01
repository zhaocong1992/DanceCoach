/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain.market

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 云端市场仓库：通过 HTTP 调用 CloudBase 云函数 danceApi，实现 MarketRepository。
 * 所有网络请求在 Dispatchers.IO 上执行（接口方法为 suspend）。
 *
 * 通信契约见 cloud/danceApi/index.js：
 *   POST endpoint，body = { action, payload, deviceId, token }
 *   返回 { code, data, message }，code!=0 视为失败并抛 CloudApiException。
 */
class CloudMarketRepository(private val context: Context) : MarketRepository {

    private val io = Dispatchers.IO

    /** 调一次云函数，返回解析后的响应对象（code!=0 时抛异常） */
    private suspend fun call(action: String, payload: JSONObject): JSONObject = withContext(io) {
        val endpoint = CloudConfig.endpoint(context.applicationContext)
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val body = JSONObject().apply {
            put("action", action)
            put("payload", payload)
            put("deviceId", DeviceIdProvider.get(context.applicationContext))
            put("token", CloudConfig.appToken(context.applicationContext))
        }
        try {
            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val code = root.optInt("code", -1)
            if (code != 0) {
                throw CloudApiException(code, root.optString("message", "云端返回错误"))
            }
            root
        } catch (e: CloudApiException) {
            Log.e(TAG, "danceApi 调用失败 action=$action code=${e.code} msg=${e.message}")
            throw e
        } catch (e: Exception) {
            // 网络层错误（无网/超时/DNS/HTTP 非 200 等）统一转成可捕获异常
            Log.e(TAG, "danceApi 网络错误 action=$action", e)
            throw CloudApiException(-1, e.message ?: e.toString())
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun list(sort: MarketSort, category: String, keyword: String, authorId: String): List<DanceItem> = withContext(io) {
        val payload = JSONObject()
            .put("sort", if (sort == MarketSort.HOT) "hot" else "latest")
            .put("limit", 50)
            .put("category", category)
            .put("keyword", keyword)
            .put("authorId", authorId)
        val root = call("list", payload)
        val arr: JSONArray = root.optJSONArray("data") ?: JSONArray()
        val out = ArrayList<DanceItem>(arr.length())
        for (i in 0 until arr.length()) {
            out.add(parseItem(arr.optJSONObject(i)))
        }
        out
    }

    override suspend fun get(id: String): DanceItem? = withContext(io) {
        val payload = JSONObject().put("id", id)
        val root = call("get", payload)
        val data = root.optJSONObject("data") ?: return@withContext null
        parseItem(data)
    }

    override suspend fun upload(req: UploadRequest): DanceItem = withContext(io) {
        val payload = JSONObject().apply {
            put("title", req.title)
            put("category", req.category)
            put("fileType", req.fileType)
            put("difficulty", req.difficulty)
            put("description", req.description)
            put("authorName", req.authorName)
            // 真实封面：首帧压缩的 data URL（内嵌进文档），空则回退纯色块
            if (req.coverUrl.isNotBlank()) {
                put("coverUrl", req.coverUrl)
            }
            // 直传模式：视频已 PUT 到云存储，这里只回传 fileID 与大小（不再传视频字节）
            if (req.fileId.isNotBlank()) {
                put("fileId", req.fileId)
                put("fileSize", req.fileSize)
            }
        }
        val root = call("upload", payload)
        val data = root.optJSONObject("data") ?: throw CloudApiException(-1, "上传无返回")
        parseItem(data)
    }

    /** 向云函数申请云存储「直传」预签名 PUT URL（APP 随后原生 PUT 视频字节） */
    override suspend fun getUploadCredential(fileName: String): UploadCredential = withContext(io) {
        val payload = JSONObject().put("fileName", fileName)
        val root = call("getUploadCredential", payload)
        val data = root.optJSONObject("data") ?: throw CloudApiException(-1, "获取上传凭证失败")
        UploadCredential(
            uploadUrl = data.optString("uploadUrl", ""),
            fileId = data.optString("fileId", ""),
            objectKey = data.optString("objectKey", "")
        )
    }

    /** 原生 HTTP PUT 把视频流直接传到云存储预签名 URL（流式，绕过 API 网关体限制与内存峰值） */
    override suspend fun putVideo(uploadUrl: String, input: java.io.InputStream): Unit = withContext(io) {
        val url = URL(uploadUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/octet-stream")
        }
        try {
            conn.outputStream.use { os ->
                input.use { inp ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (inp.read(buf).also { n = it } != -1) {
                        os.write(buf, 0, n)
                    }
                }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText()?.take(300) ?: ""
                throw CloudApiException(-1, "直传失败 HTTP $code: $err")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 云存储私有，用 fileID 换取临时可访问 URL（用于下载/播放） */
    override suspend fun getFileUrl(fileId: String): String = withContext(io) {
        val payload = JSONObject().put("fileId", fileId)
        val root = call("getFileUrl", payload)
        val data = root.optJSONObject("data") ?: throw CloudApiException(-1, "换链无返回")
        val url = data.optString("url", "")
        if (url.isBlank()) throw CloudApiException(-1, "换链为空")
        url
    }

    override suspend fun reportPlay(id: String) {
        withContext(io) { call("reportPlay", JSONObject().put("id", id)) }
    }

    override suspend fun reportDownload(id: String) {
        withContext(io) { call("reportDownload", JSONObject().put("id", id)) }
    }

    override suspend fun reportLike(id: String) {
        withContext(io) { call("reportLike", JSONObject().put("id", id)) }
    }

    /** 按本地上传 ID 列表向云端结算被动收益（每舞封顶 + 归属校验在服务端） */
    override suspend fun syncMyUploads(ids: List<String>): List<SyncResultItem> = withContext(io) {
        val payload = JSONObject()
        val arr = org.json.JSONArray()
        for (id in ids) arr.put(id)
        payload.put("ids", arr)
        val root = call("syncMyUploads", payload)
        val arrResp: org.json.JSONArray = root.optJSONArray("data") ?: org.json.JSONArray()
        val out = ArrayList<SyncResultItem>(arrResp.length())
        for (i in 0 until arrResp.length()) {
            val j = arrResp.optJSONObject(i) ?: continue
            out.add(
                SyncResultItem(
                    id = j.optString("id", ""),
                    found = j.optBoolean("found", false),
                    likeCount = j.optInt("likeCount", 0),
                    downloadCount = j.optInt("downloadCount", 0),
                    deltaLikeCoins = j.optInt("deltaLikeCoins", 0),
                    deltaDLCoins = j.optInt("deltaDLCoins", 0),
                    pendingLike = j.optInt("pendingLike", 0),
                    pendingDL = j.optInt("pendingDL", 0)
                )
            )
        }
        out
    }

    /** 把云端返回的 JSON 文档转成 APP 的 DanceItem（云端无封面图，用颜色块代替） */
    private fun parseItem(j: JSONObject?): DanceItem {
        if (j == null) throw CloudApiException(-1, "条目为空")
        val id = j.optString("id", j.optString("_id", ""))
        return DanceItem(
            id = id,
            title = j.optString("title", "未命名"),
            authorName = j.optString("authorName", "匿名舞者"),
            authorId = j.optString("authorId", ""),
            category = j.optString("category", "其他"),
            coverColor = colorFromId(id),
            fileUrl = j.optString("fileUrl", ""),
            fileId = j.optString("fileId", ""),
            fileType = j.optString("fileType", "video"),
            downloadCount = j.optInt("downloadCount", 0),
            playCount = j.optInt("playCount", 0),
            likeCount = j.optInt("likeCount", 0),
            createdAt = j.optLong("createdAt", 0L),
            description = j.optString("description", ""),
            difficulty = j.optString("difficulty", "其他"),
            coverUrl = j.optString("coverUrl", "")
        )
    }

    companion object {
        const val TAG = "DC_MARKET"

        // 与 Mock 同款调色板，按 id 哈希取色，保证同一内容颜色稳定
        private val PALETTE = intArrayOf(
            0xFF1A3A5C.toInt(), 0xFF3A1A5C.toInt(), 0xFF1A5C3A.toInt(),
            0xFF5C1A1A.toInt(), 0xFF5C4A1A.toInt(), 0xFF1A5C5C.toInt()
        )

        private fun colorFromId(id: String): Int {
            val h = if (id.isEmpty()) 0 else id.hashCode()
            return PALETTE[Math.abs(h) % PALETTE.size]
        }
    }
}

/** 云端调用异常：code 为云函数返回的 code（-1 表示网络/传输层错误） */
class CloudApiException(val code: Int, message: String) : Exception(message)
