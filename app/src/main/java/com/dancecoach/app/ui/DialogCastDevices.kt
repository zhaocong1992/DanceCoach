/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.dancecoach.app.R
import androidx.mediarouter.media.MediaRouter

/**
 * 投屏设备选择对话框。
 * 列出 MediaRouter 扫描到的远程显示设备，点击即连接。
 */
class DialogCastDevices(context: android.content.Context) : AlertDialog(context) {

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var cancelBtn: Button

    /** 选中某设备回调 */
    var onRouteSelected: ((MediaRouter.RouteInfo) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_cast_devices)

        listContainer = findViewById<LinearLayout>(R.id.castDeviceList)!!
        emptyHint = findViewById<TextView>(R.id.castEmptyHint)!!
        cancelBtn = findViewById<Button>(R.id.castCancelBtn)!!

        cancelBtn.setOnClickListener { dismiss() }
        refresh()
    }

    /** 重新扫描并刷新列表（每次显示时调用）。 */
    fun refresh() {
        if (!::listContainer.isInitialized) return
        listContainer.removeAllViews()

        val routes = CastManager.getAvailableRoutes()
        if (routes.isEmpty()) {
            emptyHint.visibility = View.VISIBLE
            return
        }
        emptyHint.visibility = View.GONE

        for (route in routes) {
            listContainer.addView(buildDeviceItem(route))
        }
    }

    private fun buildDeviceItem(route: MediaRouter.RouteInfo): View {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundResource(android.R.drawable.list_selector_background)
            isClickable = true
            isFocusable = true
        }

        val icon = TextView(context).apply {
            text = "📺"
            textSize = 22f
            setPadding(0, 0, 12, 0)
        }

        val name = TextView(context).apply {
            text = route.name
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            maxLines = 1
        }

        item.addView(icon)
        item.addView(name)

        val connected = CastManager.isConnectedTo(route)
        if (connected) {
            val tag = TextView(context).apply {
                text = "  已连接"
                setTextColor(ContextCompat.getColor(context, R.color.castIconActive))
                textSize = 12f
            }
            item.addView(tag)
        }

        item.setOnClickListener {
            onRouteSelected?.invoke(route)
            dismiss()
        }

        return item
    }

    override fun show() {
        super.show()
        refresh()
    }
}
