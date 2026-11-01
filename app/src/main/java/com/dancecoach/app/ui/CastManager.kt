/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter

/**
 * 投屏管理器（单例）。
 *
 * 封装 AndroidX MediaRouter，用于发现并连接支持 Presentation 第二屏的显示设备
 * （如支持 Miracast / Wi-Fi Display 的智能电视）。
 *
 * 不依赖 Google Cast SDK，使用系统原生 MediaRouter + Presentation API。
 *
 * 状态通过两个监听器向外通知：
 * - 状态监听器 [addStateListener]：IDLE / CONNECTED，供首页按钮更新
 * - presentation 监听器 [addPresentationListener]：Presentation 创建/销毁，供 TeachingActivity 同步画面
 */
object CastManager {

    private const val TAG = "CastManager"

    enum class CastState { IDLE, CONNECTED }

    @Volatile
    var state: CastState = CastState.IDLE
        private set

    private var appContext: Context? = null
    private var mediaRouter: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var selectedRoute: MediaRouter.RouteInfo? = null
    private var presentation: CastPresentation? = null
    private var displayManager: DisplayManager? = null

    private val stateListeners = mutableListOf<(CastState) -> Unit>()
    private val presentationListeners = mutableListOf<(CastPresentation?) -> Unit>()

    private val routeCallback = object : MediaRouter.Callback() {
        override fun onRouteChanged(router: MediaRouter?, route: MediaRouter.RouteInfo?) {
            super.onRouteChanged(router, route)
            if (route == selectedRoute) {
                // 已选路由的连接状态可能变化，presentation display 可能刚刚就绪
                route?.presentationDisplay?.let { display ->
                    if (presentation == null) {
                        createPresentation(display)
                    }
                }
            }
        }

        override fun onRouteRemoved(router: MediaRouter?, route: MediaRouter.RouteInfo?) {
            super.onRouteRemoved(router, route)
            if (route == selectedRoute) {
                Log.d(TAG, "selected route removed, disconnecting")
                disconnect()
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            val disp = displayManager?.getDisplay(displayId) ?: return
            if (selectedRoute?.presentationDisplay == disp && presentation == null) {
                createPresentation(disp)
            }
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (presentation?.display?.displayId == displayId) {
                dismissPresentation()
            }
        }

        override fun onDisplayChanged(displayId: Int) {}
    }

    /** 初始化（可重复调用，仅首次生效）。建议在 Application 或首个 Activity 中调用。 */
    fun init(context: Context) {
        if (mediaRouter != null) return
        appContext = context.applicationContext
        mediaRouter = MediaRouter.getInstance(context)
        selector = MediaRouteSelector.Builder()
            .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO)
            .build()
        displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        mediaRouter?.addCallback(
            selector!!,
            routeCallback,
            MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
        )
        displayManager?.registerDisplayListener(displayListener, null)
    }

    fun addStateListener(listener: (CastState) -> Unit) {
        stateListeners.add(listener)
        listener(state) // 立即回灌当前状态
    }

    fun removeStateListener(listener: (CastState) -> Unit) {
        stateListeners.remove(listener)
    }

    fun addPresentationListener(listener: (CastPresentation?) -> Unit) {
        presentationListeners.add(listener)
        listener(presentation) // 立即回灌当前 presentation
    }

    fun removePresentationListener(listener: (CastPresentation?) -> Unit) {
        presentationListeners.remove(listener)
    }

    /** 返回当前可连接的路由（排除默认本地路由，仅保留支持本 selector 的远程显示设备）。 */
    fun getAvailableRoutes(): List<MediaRouter.RouteInfo> {
        val mr = mediaRouter ?: return emptyList()
        val sel = selector ?: return emptyList()
        return mr.routes.filter { route ->
            route.isEnabled && !route.isDefault && route.matchesSelector(sel)
        }
    }

    fun isConnectedTo(route: MediaRouter.RouteInfo): Boolean {
        return selectedRoute == route && state == CastState.CONNECTED
    }

    /** 选择并连接一个路由。系统会发起无线显示连接，连接成功后 presentation display 就绪，
     * 由 [displayListener] 或 [routeCallback] 触发 [createPresentation]。 */
    fun connect(route: MediaRouter.RouteInfo) {
        Log.d(TAG, "connect -> ${route.name}")
        selectedRoute = route
        state = CastState.CONNECTED
        notifyState()
        mediaRouter?.selectRoute(route)
        // 部分设备 selectRoute 后 presentationDisplay 立即可用，立即尝试创建
        route.presentationDisplay?.let { display ->
            if (presentation == null) createPresentation(display)
        }
    }

    fun disconnect() {
        Log.d(TAG, "disconnect")
        selectedRoute?.let { route ->
            try {
                mediaRouter?.unselect(MediaRouter.UNSELECT_REASON_DISCONNECTED)
            } catch (e: Exception) {
                Log.w(TAG, "unselect failed: ${e.message}")
            }
        }
        selectedRoute = null
        dismissPresentation()
        state = CastState.IDLE
        notifyState()
    }

    fun getPresentation(): CastPresentation? = presentation

    private fun createPresentation(display: android.view.Display) {
        if (presentation != null) return
        val ctx = appContext ?: return
        try {
            val pres = CastPresentation(ctx, display)
            pres.show()
            presentation = pres
            Log.d(TAG, "Presentation shown on display ${display.displayId}")
            presentationListeners.forEach { it(presentation) }
        } catch (e: Exception) {
            Log.e(TAG, "createPresentation failed: ${e.message}")
        }
    }

    private fun dismissPresentation() {
        presentation?.dismiss()
        presentation = null
        presentationListeners.forEach { it(null) }
    }

    private fun notifyState() {
        stateListeners.forEach { it(state) }
    }
}
