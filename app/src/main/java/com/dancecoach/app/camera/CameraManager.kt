/*
 * AI幻舞 (AI Huanwu) — Android dance coaching app
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 AI幻舞
 */

package com.dancecoach.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.dancecoach.app.domain.Landmark
import com.dancecoach.app.ml.FrameDumper
import com.dancecoach.app.ml.PoseExtractor
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 用 CameraX 打开（优先前置）摄像头，按 ~12fps 抽帧。
 *
 * 不使用 CameraX Preview use case，而是只用 ImageAnalysis：
 * 每帧 ImageProxy 转 Bitmap 后，在像素级别做旋转 + 水平翻转（前置摄像头），
 * 然后通过 TextureView.lockCanvas() 直接绘制到屏幕。
 * 这样镜像在像素层面完成，不受 View 渲染管线的 scaleX/scaleY/setTransform 干扰。
 *
 * 同时将原始 Bitmap（未旋转）+ rotationDegrees 传给 PoseExtractor 做姿态检测。
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val extractor: PoseExtractor,
    private val onPose: (List<Landmark>?, bitmapW: Int, bitmapH: Int, debugBitmap: Bitmap?) -> Unit
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastFrameTime = 0L
    /** 只在首帧打印一次 plane 布局，避免刷屏 */
    private var strideLogged = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var previewView: TextureView? = null

    /** 额外的渲染目标（如投屏第二屏的 TextureView），每帧同时绘制到此视图。 */
    var secondaryTarget: TextureView? = null

    // 用户可调的显示旋转角度（0/90/180/270）和水平镜像开关
    var displayRotation: Int = 0
    var mirror: Boolean = true

    /**
     * 竖屏模式 flag：
     * - 预览/检测的旋转基准用摄像头 rotationDegrees（自动正立），横屏用用户 displayRotation
     * - 镜像行为与横屏一致（跟随 [mirror] 设置，默认前置自拍镜像）
     */
    var portraitMode: Boolean = false

    fun start(previewView: TextureView) {
        this.previewView = previewView

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
            bind()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bind() {
        val provider = cameraProvider ?: return

        // 只用 ImageAnalysis，不用 Preview。
        // 预览渲染改为手动 Canvas 绘制，以支持像素级水平镜像。
        // 分辨率用 640x480 保证显示清晰度；ML Kit 也能处理这个分辨率。
        val analyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(Size(640, 480))
            .setTargetRotation(Surface.ROTATION_0)
            .build()

        analyzer.setAnalyzer(analysisExecutor) { imageProxy ->
            val now = System.currentTimeMillis()
            if (now - lastFrameTime < 80) {
                imageProxy.close()
                return@setAnalyzer
            }
            lastFrameTime = now
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val bmW = imageProxy.width
            val bmH = imageProxy.height
            // 首帧打印真实的 plane 布局（行跨度/像素跨度），用于在任何设备上直接确认
            // 数据是否为紧凑排列——这是历史上多起"换设备就花屏"问题的根因所在。
            if (!strideLogged && imageProxy.planes.size >= 3) {
                strideLogged = true
                val p0 = imageProxy.planes[0]
                val p1 = imageProxy.planes[1]
                val p2 = imageProxy.planes[2]
                android.util.Log.d(
                    "DC_CAM",
                    "fmt=${imageProxy.format} size=${imageProxy.width}x${imageProxy.height} " +
                        "Y(row=${p0.rowStride},px=${p0.pixelStride}) " +
                        "U(row=${p1.rowStride},px=${p1.pixelStride}) " +
                        "V(row=${p2.rowStride},px=${p2.pixelStride})"
                )
            }
            val rawBitmap = imageProxyToBitmap(imageProxy)
            imageProxy.close()
            if (rawBitmap != null) {
                // 1. 创建显示用 bitmap：
                //    旋转：竖屏模式 = 按摄像头方向自动旋转正立；横屏模式 = 按用户设置的旋转角度。
                //    镜像：横竖屏一致，均按用户镜像设置（前置摄像头默认自拍镜像=照镜子效果）。
                val effectiveRotation = if (portraitMode) rotationDegrees else displayRotation
                val effectiveMirror = mirror
                val displayBm = createDisplayBitmap(rawBitmap, effectiveRotation, effectiveMirror)

                // 2. 在主线程绘制到 TextureView（手机 + 额外的投屏目标）
                mainHandler.post {
                    renderToTextureView(displayBm)
                    displayBm.recycle()
                }

                // 3. 后台线程做姿态检测。统一坐标系：把帧物理旋转到“与预览显示一致的正立方向”后检测
                //    （rotation 传 0），用户骨架始终与用户看到的预览对齐，与教师骨架同一正立坐标系。
                //    横屏：预览按用户设置的 displayRotation 旋转 → 检测用同一角度；
                //    竖屏：预览按摄像头 rotationDegrees 自动正立 → 检测用同一角度。
                scope.launch {
                    val uprightRotation = if (portraitMode) rotationDegrees else displayRotation
                    val upright = createDisplayBitmap(rawBitmap, uprightRotation, false)
                    val landmarks = extractor.detect(upright, 0)
                    android.util.Log.d(
                        "DC_CAM",
                        "uprightRot=$uprightRotation raw=${rawBitmap.width}x${rawBitmap.height} " +
                        "upright=${upright.width}x${upright.height} portrait=$portraitMode lm=${landmarks?.size}"
                    )
                    if (upright !== rawBitmap) upright.recycle()
                    // 调试：复制一张"网络实际看到的"帧（按 rotationDegrees 旋转、不镜像）
                    // 用于界面核对朝向，或落盘量化抖动来源
                    val needDbg = FrameDumper.userEnabled
                    val dbg = if (needDbg) createDisplayBitmap(rawBitmap, rotationDegrees, false) else null
                    if (FrameDumper.userEnabled && dbg != null) {
                        FrameDumper.dumpUser(context, dbg, rotationDegrees, landmarks)
                    }
                    rawBitmap.recycle()
                    withContext(Dispatchers.Main) { onPose(landmarks, bmW, bmH, dbg) }
                }
            }
        }

        fun tryBind(lens: Int): Boolean {
            return try {
                val selector = CameraSelector.Builder().requireLensFacing(lens).build()
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, analyzer)
                true
            } catch (e: Exception) {
                false
            }
        }

        if (!tryBind(CameraSelector.LENS_FACING_FRONT)) {
            tryBind(CameraSelector.LENS_FACING_BACK)
        }
    }

    /**
     * 将原始 bitmap 旋转到正立方向，并可选水平翻转（前置摄像头自拍镜像）。
     * postRotate 先旋转使画面正立，postScale(-1,1) 再水平翻转。
     * Bitmap.createBitmap 会自动计算旋转后的新边界，无需手动处理坐标偏移。
     */
    private fun createDisplayBitmap(src: Bitmap, rotationDegrees: Int, flip: Boolean): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        if (flip) {
            matrix.postScale(-1f, 1f)
        }
        val result = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        // createBitmap 在矩阵为单位矩阵时可能返回源 bitmap 本身，
        // 这会导致 displayBm 和 rawBitmap 是同一对象，后台 recycle 后主线程绘制崩溃。
        if (result === src) {
            return src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)
        }
        return result
    }

    /**
     * 将 bitmap 以 center-crop 方式绘制到主 TextureView 与额外的投屏目标。
     * 用 lockCanvas/unlockCanvasAndPost 做 CPU 软件渲染。
     */
    private fun renderToTextureView(bitmap: Bitmap) {
        previewView?.let { drawToView(it, bitmap) }
        secondaryTarget?.let { drawToView(it, bitmap) }
    }

    private fun drawToView(view: TextureView, bitmap: Bitmap) {
        if (view.width <= 0 || view.height <= 0) return
        val canvas = view.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.BLACK)
            val viewW = view.width.toFloat()
            val viewH = view.height.toFloat()
            val bmW = bitmap.width.toFloat()
            val bmH = bitmap.height.toFloat()
            // center-crop：取较大缩放比铺满
            val scale = maxOf(viewW / bmW, viewH / bmH)
            val drawW = bmW * scale
            val drawH = bmH * scale
            val dx = (viewW - drawW) / 2f
            val dy = (viewH - drawH) / 2f
            canvas.drawBitmap(bitmap, null, RectF(dx, dy, dx + drawW, dy + drawH), null)
        } finally {
            view.unlockCanvasAndPost(canvas)
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val nv21 = yuv420888ToNv21(image)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
            val jpeg = out.toByteArray()
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * YUV_420_888 -> NV21 的通用转换。
     *
     * 不能把 plane.buffer 整块当"紧凑数据"搬运，必须按每个 plane 的
     * rowStride（行跨度）与 pixelStride（像素跨度）逐行逐像素取样：
     *  - rowStride 常因内存对齐大于图像宽度（例如 1080 宽实际行跨度 1088），
     *    行尾填充字节若被当成下一行的开头像素，画面就会逐行错位成斜向条纹；
     *  - 色度 plane 常以 pixelStride=2 交错存放，直接把 U、V 两块内存首尾拼接
     *    也得不到 NV21 要求的 VU 交错顺序，会额外产生彩色噪条。
     * 老设备 rowStride 恰好等于宽度，旧写法歪打正着；有对齐填充的设备必花屏。
     */
    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val nv21 = ByteArray(width * height * 3 / 2)
        var pos = 0

        for (row in 0 until height) {
            var src = row * yRowStride
            for (col in 0 until width) {
                nv21[pos++] = yBuf.byteAt(src)
                src += yPixelStride
            }
        }

        // 色度：NV21 要求 V 在前、U 在后交错排列
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        for (row in 0 until chromaHeight) {
            var vSrc = row * vRowStride
            var uSrc = row * uRowStride
            for (col in 0 until chromaWidth) {
                nv21[pos++] = vBuf.byteAt(vSrc)
                nv21[pos++] = uBuf.byteAt(uSrc)
                vSrc += vPixelStride
                uSrc += uPixelStride
            }
        }
        return nv21
    }

    /**
     * 按下标读取一个字节。部分设备的色度 plane 末尾会少一个字节（remaining 比
     * 理论值小 1），越界时补 0，避免因为最后一个像素抛异常导致整帧丢失。
     */
    private fun ByteBuffer.byteAt(index: Int): Byte =
        if (index >= 0 && index < this.limit()) this.get(index) else 0

    fun stop() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        scope.cancel()
    }
}
