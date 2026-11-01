/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dancecoach.app.R
import com.dancecoach.app.domain.AppPrefs

class HomeActivity : AppCompatActivity() {

    private lateinit var btnCast: LinearLayout
    private lateinit var castButtonText: TextView
    private lateinit var castButtonIcon: ImageView
    private var castDialog: DialogCastDevices? = null
    private var backPressedOnce = false

    private val castStateListener: (CastManager.CastState) -> Unit = { state ->
        updateCastButton(state)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化全局偏好配置（须在读取任何偏好前完成）
        AppPrefs.init(applicationContext)
        // 按用户偏好锁定屏幕方向（默认横屏；切换开关后此处会以新方向重建）
        OrientationUtil.apply(this)

        setContentView(R.layout.activity_home)

        // 初始化投屏管理器（仅首次生效）
        CastManager.init(this)

        btnCast = findViewById(R.id.btnCast)
        castButtonText = findViewById(R.id.castButtonText)
        castButtonIcon = findViewById(R.id.castButtonIcon)

        // 横竖屏切换：翻转偏好并请求系统旋转，触发 Activity 重建加载对应方向布局
        findViewById<TextView>(R.id.btnOrientation).setOnClickListener {
            AppPrefs.isLandscape = !AppPrefs.isLandscape
            OrientationUtil.apply(this)
        }

        // 4 张卡片点击导航
        findViewById<LinearLayout>(R.id.cardTeaching).setOnClickListener {
            startActivity(Intent(this, TeachingActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.cardLibrary).setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.cardMarket).setOnClickListener {
            startActivity(Intent(this, MarketActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.cardSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 投屏按钮
        btnCast.setOnClickListener { onCastButtonClicked() }

        CastManager.addStateListener(castStateListener)
    }

    private fun onCastButtonClicked() {
        if (CastManager.state == CastManager.CastState.CONNECTED) {
            // 已连接：确认断开
            AlertDialog.Builder(this)
                .setTitle("断开投屏")
                .setMessage("确定要断开当前投屏连接吗？")
                .setPositiveButton("断开") { _, _ -> CastManager.disconnect() }
                .setNegativeButton("取消", null)
                .show()
        } else {
            // 未连接：弹出设备选择对话框
            val dialog = DialogCastDevices(this).apply {
                onRouteSelected = { route -> CastManager.connect(route) }
            }
            castDialog = dialog
            dialog.show()
        }
    }

    private fun updateCastButton(state: CastManager.CastState) {
        val connected = state == CastManager.CastState.CONNECTED
        castButtonText.text = if (connected) "投屏中" else "投屏"
        castButtonText.setTextColor(
            resources.getColor(
                if (connected) R.color.castIconActive else R.color.castIconIdle,
                null
            )
        )
        castButtonIcon.setColorFilter(
            resources.getColor(
                if (connected) R.color.castIconActive else R.color.castIconIdle,
                null
            )
        )
        btnCast.setBackgroundResource(
            if (connected) R.drawable.cast_button_bg_active else R.drawable.cast_button_bg
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        CastManager.removeStateListener(castStateListener)
        castDialog?.dismiss()
        castDialog = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (backPressedOnce) {
            super.onBackPressed()
            return
        }
        backPressedOnce = true
        android.widget.Toast.makeText(this, "再按一次返回键退出", android.widget.Toast.LENGTH_SHORT).show()
        window.decorView.postDelayed({ backPressedOnce = false }, 2000)
    }
}
