/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.domain.market

import android.content.Context
import com.dancecoach.app.domain.LegalTexts

/**
 * 上传相关的法律声明与用户同意状态。
 *
 * 平台定位为「AI 舞蹈教学社区」，要求用户上传内容均为 AI 生成或已取得授权，
 * 且不含可识别自然人肖像。上传前必须勾选同意本协议，协议版本升级后需重新同意。
 */
object AgreementPrefs {
    private const val SP = "dancecoach_agreement"

    /** 协议版本：每次修改协议文案时 +1，已同意旧版本的用户需重新勾选 */
    const val AGREEMENT_VERSION = 2

    /** 上传页展示的简短声明（复用于发布前提示） */
    const val UPLOAD_DECLARATION =
        "本平台为 AI 舞蹈教学社区。为保障他人合法权益，请确认你分享的内容满足以下要求：\n" +
        "• 视频内容由 AI 生成，或你拥有完整著作权与肖像授权；\n" +
        "• 不含任何可识别的自然人肖像、人脸或个人信息；\n" +
        "• 不侵犯任何第三方的著作权、肖像权、隐私权等合法权益。\n" +
        "如违反规定，相关责任由上传者自行承担，平台有权下架内容。"

    /** 完整协议全文（Dialog 展示）：委托到完整的《用户协议》，与「关于」页保持一致 */
    val AGREEMENT_FULL get() = LegalTexts.USER_AGREEMENT

    fun getAgreedVersion(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
        return sp.getInt("agreed_version", 0)
    }

    fun setAgreedVersion(ctx: Context, v: Int) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit().putInt("agreed_version", v).apply()
    }

    /** 是否已同意当前版本协议 */
    fun isAgreed(ctx: Context): Boolean = getAgreedVersion(ctx) >= AGREEMENT_VERSION
}
