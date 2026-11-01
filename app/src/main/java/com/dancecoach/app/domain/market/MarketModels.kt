/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain.market

import java.util.concurrent.atomic.AtomicLong

/** 舞蹈市场里一条分享内容（视频 或 动作库文件） */
data class DanceItem(
    val id: String,
    val title: String,
    val authorName: String,
    val authorId: String = "",        // 上传者设备匿名 ID（"我的上传"按此聚合）
    val category: String,
    val coverColor: Int,        // Mock/无封面时用纯色块当封面；接 coverUrl 后优先用封面图
    val fileUrl: String,
    val fileId: String = "",    // 云存储 fileID（视频真实存放处）；云端无视频时为空
    val fileType: String,       // "video" 视频（闭环后只分享真实舞蹈视频，已取消 library 类型）
    var downloadCount: Int,
    var playCount: Int,
    val createdAt: Long,        // 毫秒时间戳
    val description: String = "",
    val difficulty: String = "其他",   // 难度标签：简单/中等/难（来自库视频）
    val coverUrl: String = "",           // 真实封面图地址（A-4 抽帧上云后填充；空则用 coverColor）
    var likeCount: Int = 0               // 点赞数（云端计数；Mock 下可本地 +1）
) {
    /** 热度分：播放×1 + 下载×3（下载是更强意图，权重更高） */
    val hotScore: Int get() = playCount * 1 + downloadCount * 3
}

/** 列表排序方式 */
enum class MarketSort { HOT, LATEST }

/** 市场固定分类枚举（与上传页下拉一致） */
val MARKET_CATEGORIES = listOf("全部", "民族舞", "街舞", "健身操", "古典舞", "少儿", "其他")

/** 上传请求 */
data class UploadRequest(
    val title: String,
    val category: String,
    val fileType: String,       // "video"（闭环后只分享真实舞蹈视频，已取消 library 类型）
    val description: String = "",
    val authorName: String = "我",
    val fileId: String = "",    // 直传模式：视频已 PUT 到云存储后的 fileID（回写 dances）
    val fileSize: Long = 0L,    // 视频字节大小
    val difficulty: String = "其他",  // 难度标签，来自库视频
    val coverUrl: String = ""   // 真实封面：上传时抽首帧压缩成 data URL 内嵌（空则用纯色块）
)

/** 云函数签发的云存储「直传」凭证：APP 用原生 HTTP PUT 把视频字节传到 uploadUrl */
data class UploadCredential(
    val uploadUrl: String,
    val fileId: String,      // 直传成功后的 fileID（用于 dances 记录与后续换链播放/下载）
    val objectKey: String
)

/**
 * 市场数据仓库接口：UI 只依赖它，不关心数据是 Mock 还是云端。
 * 方法均为 suspend，CloudMarketRepository 在 IO 线程做网络；Mock 直接返回。
 */
/**
 * 「我的上传」结算单条结果（syncMyUploads 返回）。
 * - found=false：云端已无此舞（或被他人删除/归属不符）→ 本机移出本地列表。
 * - found=true：云端该舞当前计数 + 本次可结算增量 + 剩余待结算。
 */
data class SyncResultItem(
    val id: String,
    val found: Boolean,
    val likeCount: Int = 0,
    val downloadCount: Int = 0,
    val deltaLikeCoins: Int = 0,   // 本次「被点赞」结算增量（已扣过封顶）
    val deltaDLCoins: Int = 0,     // 本次「被下载」结算增量（已扣过封顶）
    val pendingLike: Int = 0,      // 还可从「被点赞」领取（封顶后剩余）
    val pendingDL: Int = 0         // 还可从「被下载」领取（封顶后剩余）
) {
    val pendingTotal get() = pendingLike + pendingDL
    val deltaTotal get() = deltaLikeCoins + deltaDLCoins
}

interface MarketRepository {
    suspend fun list(sort: MarketSort, category: String = "全部", keyword: String = "", authorId: String = ""): List<DanceItem>
    suspend fun get(id: String): DanceItem?
    suspend fun upload(req: UploadRequest): DanceItem
    suspend fun reportPlay(id: String)
    suspend fun reportDownload(id: String)
    suspend fun reportLike(id: String)
    suspend fun syncMyUploads(ids: List<String>): List<SyncResultItem>  // 按本地上传 ID 列表结算被动收益
    suspend fun getFileUrl(fileId: String): String   // 云存储私有，用 fileID 换临时可下载/播放 URL
    suspend fun getUploadCredential(fileName: String): UploadCredential  // 拿直传预签名 URL
    suspend fun putVideo(uploadUrl: String, input: java.io.InputStream)  // 原生 HTTP PUT 视频流到云存储（流式，避免大视频 OOM）
}

/** 本地假数据实现，先把 UI 跑通；接云端后替换为 CloudRepository */
object MockMarketRepository : MarketRepository {
    private val items = mutableListOf<DanceItem>()
    private val seq = AtomicLong(1)

    private data class Seed(val title: String, val type: String, val dl: Int, val pl: Int, val cat: String, val diff: String)

    init {
        val now = System.currentTimeMillis()
        val palette = listOf(
            0xFF1A3A5C.toInt(), 0xFF3A1A5C.toInt(), 0xFF1A5C3A.toInt(),
            0xFF5C1A1A.toInt(), 0xFF5C4A1A.toInt(), 0xFF1A5C5C.toInt()
        )
        val seed = listOf(
            Seed("街舞入门教学", "video", 320, 1200, "街舞", "简单"),
            Seed("恰恰基础步", "video", 88, 410, "其他", "中等"),
            Seed("民族舞身韵组合", "video", 210, 760, "民族舞", "中等"),
            Seed("爵士舞发力技巧", "video", 45, 300, "其他", "难"),
            Seed("少儿拉丁铜牌", "video", 500, 1900, "少儿", "简单"),
            Seed("韩舞翻跳片段", "video", 12, 95, "其他", "中等"),
            Seed("古典舞手位练习", "video", 150, 530, "古典舞", "简单"),
            Seed("健身操全套", "video", 260, 880, "健身操", "简单")
        )
        seed.forEachIndexed { i, s ->
            val isVideo = s.type == "video"
            items.add(
                DanceItem(
                    id = "seed_$i",
                    title = s.title,
                    authorName = "舞者${i + 1}号",
                    authorId = "mock_device",
                    category = s.cat,
                    coverColor = palette[i % palette.size],
                    fileUrl = "mock://${if (isVideo) "video" else "library"}/$i",
                    fileType = if (isVideo) "video" else "library",
                    downloadCount = s.dl,
                    playCount = s.pl,
                    createdAt = now - (seed.size - i) * 3600_000L,
                    difficulty = s.diff
                )
            )
        }
    }

    override suspend fun list(sort: MarketSort, category: String, keyword: String, authorId: String): List<DanceItem> {
        var list: List<DanceItem> = items
        if (category.isNotBlank() && category != "全部") {
            list = list.filter { it.category == category }
        }
        if (authorId.isNotBlank()) {
            list = list.filter { it.authorId == authorId }
        }
        if (keyword.isNotBlank()) {
            val k = keyword.lowercase()
            list = list.filter {
                it.title.lowercase().contains(k) || it.authorName.lowercase().contains(k)
            }
        }
        return when (sort) {
            MarketSort.HOT -> list.sortedByDescending { it.hotScore }
            MarketSort.LATEST -> list.sortedByDescending { it.createdAt }
        }
    }

    override suspend fun get(id: String): DanceItem? = items.find { it.id == id }

    override suspend fun upload(req: UploadRequest): DanceItem {
        val item = DanceItem(
            id = "local_${seq.getAndIncrement()}",
            title = req.title.ifBlank { "未命名分享" },
            authorName = req.authorName,
            authorId = "mock_device",
            category = req.category.ifBlank { "其他" },
            coverColor = 0xFF00E5FF.toInt(),
            fileUrl = "mock://upload/${System.currentTimeMillis()}",
            fileType = req.fileType,
            downloadCount = 0,
            playCount = 0,
            createdAt = System.currentTimeMillis(),
            description = req.description,
            difficulty = req.difficulty.ifBlank { "其他" }
        )
        items.add(0, item)
        return item
    }

    override suspend fun reportPlay(id: String) {
        items.find { it.id == id }?.let { it.playCount++ }
    }

    override suspend fun reportDownload(id: String) {
        items.find { it.id == id }?.let { it.downloadCount++ }
    }

    override suspend fun reportLike(id: String) {
        items.find { it.id == id }?.let { it.likeCount++ }
    }

    // 模拟被动结算：维护每支舞已结算的「被赞/被下载」币，按封顶增量返回
    private val mockCredited = mutableMapOf<String, Pair<Int, Int>>()  // id -> (creditedLike, creditedDL)

    override suspend fun syncMyUploads(ids: List<String>): List<SyncResultItem> {
        val out = mutableListOf<SyncResultItem>()
        for (id in ids) {
            val item = items.find { it.id == id }
            if (item == null) {
                out.add(SyncResultItem(id, found = false))
                continue
            }
            val like = item.likeCount
            val dl = item.downloadCount
            val curLike = minOf(20, like)
            val curDL = minOf(20, dl)
            val (cL, cD) = mockCredited[id] ?: (0 to 0)
            var dLike = maxOf(0, curLike - cL)
            var dDL = maxOf(0, curDL - cD)
            // 单舞终生封顶 40
            val already = cL + cD
            val newTotal = already + dLike + dDL
            if (newTotal > 40) {
                var remain = 40 - already
                if (remain <= 0) { dLike = 0; dDL = 0 }
                else {
                    val takeLike = minOf(dLike, remain); remain -= takeLike
                    val takeDL = minOf(dDL, remain)
                    dLike = takeLike; dDL = takeDL
                }
            }
            mockCredited[id] = (cL + dLike) to (cD + dDL)
            out.add(
                SyncResultItem(
                    id = id, found = true,
                    likeCount = like, downloadCount = dl,
                    deltaLikeCoins = dLike, deltaDLCoins = dDL,
                    pendingLike = maxOf(0, curLike - (cL + dLike)),
                    pendingDL = maxOf(0, curDL - (cD + dDL))
                )
            )
        }
        return out
    }

    override suspend fun getFileUrl(fileId: String): String {
        return ""
    }

    override suspend fun getUploadCredential(fileName: String): UploadCredential =
        UploadCredential("", "", "")

    override suspend fun putVideo(uploadUrl: String, input: java.io.InputStream) {
        input.close()
    }
}
