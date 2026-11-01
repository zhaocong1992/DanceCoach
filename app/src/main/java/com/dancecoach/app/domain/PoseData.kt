/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain

/** 单个关键点：type 对应 PoseLandmark 常量，x/y 为图像像素坐标，score 为置信度 */
data class Landmark(
    val type: Int,
    val x: Float,
    val y: Float,
    val score: Float
)

/** 某一时刻的骨架 */
data class PoseFrame(
    val t: Long, // 毫秒时间戳
    val landmarks: List<Landmark>
)

/**
 * 整段舞蹈的骨架序列。
 * sourceVideo=true 表示由真实视频离线预处理得到；false 为程序生成示例。
 * bitmapW/bitmapH 是 ML Kit 输入帧的像素尺寸，landmark 坐标以此为参考。
 */
data class PoseSequence(
    val name: String,
    val durationMs: Long,
    val fps: Int,
    val sourceVideo: Boolean,
    val frames: List<PoseFrame>,
    val bitmapW: Int = 0,
    val bitmapH: Int = 0
)
