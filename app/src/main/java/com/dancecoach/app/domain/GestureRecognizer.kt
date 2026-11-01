/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain

import android.util.Log
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.abs

/**
 * 手势识别器：从用户骨架检测 4 种导航手势。
 *
 * 统一正立坐标系：canonical first=横轴(左右)、second=纵轴(向下为正，上为负)。
 * 手势：
 * - EXIT: 双手抱头（退出教学，回到首页）
 * - PREV: 举起左手（上一个视频）
 * - NEXT: 举起右手（下一个视频）
 * - REPEAT: 双臂横在胸前（重练当前视频）—— 双臂水平外展至肩膀高度，不交叉
 */
object GestureRecognizer {

    private const val TAG = "GestureRecognizer"

    enum class Gesture {
        NONE, EXIT, PREV, NEXT, REPEAT, SWITCH_MODE
    }

    /**
     * 检测当前帧的手势。
     * @param pose 用户骨架 landmark 列表（统一为正立坐标系）
     * @param mirror 是否镜像（与摄像头预览一致）
     */
    fun detect(pose: List<Landmark>, mirror: Boolean): Gesture {
        val c = PoseUtils.canonical(pose, mirror) ?: return Gesture.NONE

        val lw = c[PoseLandmark.LEFT_WRIST] ?: return Gesture.NONE
        val rw = c[PoseLandmark.RIGHT_WRIST] ?: return Gesture.NONE
        val ls = c[PoseLandmark.LEFT_SHOULDER] ?: return Gesture.NONE
        val rs = c[PoseLandmark.RIGHT_SHOULDER] ?: return Gesture.NONE
        val nose = c[PoseLandmark.NOSE] ?: return Gesture.NONE

        // 统一正立坐标系：canonical first=横轴(左右)，second=纵轴(向下为正，上为负)
        val lwV = lw.second; val lwH = lw.first
        val rwV = rw.second; val rwH = rw.first
        val lsV = ls.second; val lsH = ls.first
        val rsV = rs.second; val rsH = rs.first
        val nH = nose.first

        // 调试日志
        Log.d(TAG, "lwV=${"%.2f".format(lwV)} lwH=${"%.2f".format(lwH)} " +
                "rwV=${"%.2f".format(rwV)} rwH=${"%.2f".format(rwH)} " +
                "lsV=${"%.2f".format(lsV)} lsH=${"%.2f".format(lsH)} " +
                "rsV=${"%.2f".format(rsV)} rsH=${"%.2f".format(rsH)} nH=${"%.2f".format(nH)}")

        // 1. EXIT — 双手抱头：双腕都明显高于肩（手举到头的高度），且横向贴近头部两侧。
        //    与 PREV/NEXT（只举一只手、另一手下垂）和 REPEAT（双臂平举、手在肩高）区分明显。
        val lwAboveHead = lwV < lsV - 0.25f
        val rwAboveHead = rwV < rsV - 0.25f
        val lwNearHead = abs(lwH - nH) < 0.45f
        val rwNearHead = abs(rwH - nH) < 0.45f
        if (lwAboveHead && rwAboveHead && lwNearHead && rwNearHead) {
            Log.d(TAG, "→ EXIT (hands on head)")
            return Gesture.EXIT
        }

        // 2. REPEAT — 双臂横在胸前：双臂水平外展至肩膀高度，不交叉（T-pose at chest）
        //    手腕在肩膀高度 ±0.3，手臂向外伸展（手腕比肩膀更远离身体中线），左右不交叉
        val bothAtShoulder = abs(lwV - lsV) < 0.3f && abs(rwV - rsV) < 0.3f
        val lwOnLeftSide = (lwH > 0) == (lsH > 0)
        val rwOnRightSide = (rwH > 0) == (rsH > 0)
        val armsExtended = abs(lwH) > abs(lsH) + 0.1f && abs(rwH) > abs(rsH) + 0.1f
        if (bothAtShoulder && armsExtended && lwOnLeftSide && rwOnRightSide) {
            Log.d(TAG, "→ REPEAT (arms horizontal at chest)")
            return Gesture.REPEAT
        }

        // 3. PREV — 举起左手，右手未举起
        val leftRaised = lwV < lsV - 0.2f
        val rightNotRaised = rwV > rsV - 0.05f
        if (leftRaised && rightNotRaised) {
            Log.d(TAG, "→ PREV (left raised)")
            return Gesture.PREV
        }

        // 4. NEXT — 举起右手，左手未举起
        val rightRaised = rwV < rsV - 0.2f
        val leftNotRaised = lwV > lsV - 0.05f
        if (rightRaised && leftNotRaised) {
            Log.d(TAG, "→ NEXT (right raised)")
            return Gesture.NEXT
        }

        return Gesture.NONE
    }
}
