package com.leo.connect.controller

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.leo.connect.Proto
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Cửa sổ nổi hình điện thoại trên máy tính bảng:
 * - kéo thanh tiêu đề để di chuyển, kéo góc ◢ để đổi cỡ
 * - nút — thu nhỏ thành bong bóng, chạm bong bóng để mở lại
 * - chạm/vuốt/nhấn giữ trên màn hình được gửi sang điện thoại
 * - thanh dưới: Back, Home, Đa nhiệm, Thông báo, Khóa
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class PhoneWindow(private val context: Context, title: String, private val callbacks: Callbacks) {

    interface Callbacks {
        fun onSurface(surface: Surface?)
        fun onGesture(durationMs: Int, points: List<FloatArray>)
        fun onTouch(action: Int, x: Float, y: Float)
        fun onKey(key: Int)
        fun onQualityClick()
        fun onClose()
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private val tabletW get() = context.resources.displayMetrics.widthPixels
    private val tabletH get() = context.resources.displayMetrics.heightPixels

    // Tỉ lệ rộng/cao của màn hình điện thoại (cập nhật khi nhận VIDEO_CONFIG)
    private var aspect = 9f / 20f
    private var screenHeight = (min(tabletW, tabletH) * 0.75f).roundToInt()

    private val windowParams = overlayParams().apply {
        x = dp(48)
        y = dp(48)
    }
    private val bubbleParams = overlayParams()

    private val titleView = TextView(context)
    private val latencyView = TextView(context)
    private val qualityView = TextView(context)
    private val statusView = TextView(context)
    private val surfaceView = SurfaceView(context)
    private val screenBox = FrameLayout(context)
    private val root = LinearLayout(context)
    private val bubble = TextView(context)

    private var shown = false
    private var minimized = false

    init {
        buildWindow(title)
        buildBubble()
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun iconButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(4), dp(10), dp(4))
        setOnClickListener { onClick() }
    }

    private fun buildWindow(title: String) {
        root.orientation = LinearLayout.VERTICAL
        root.background = rounded(Color.parseColor("#1C1F24"), 28)
        root.setPadding(dp(8), dp(4), dp(8), dp(8))
        root.elevation = dp(12).toFloat()

        // Thanh tiêu đề: kéo để di chuyển
        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(4), dp(4))
        }
        titleView.apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
        }
        titleBar.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        latencyView.apply {
            setTextColor(Color.parseColor("#9AA5B1"))
            textSize = 12f
            setPadding(dp(6), 0, dp(6), 0)
        }
        titleBar.addView(latencyView)
        qualityView.apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = rounded(Color.parseColor("#33FFFFFF"), 10)
            setOnClickListener { callbacks.onQualityClick() }
        }
        titleBar.addView(qualityView)
        titleBar.addView(iconButton("—") { minimize() })
        titleBar.addView(iconButton("✕") { callbacks.onClose() })
        attachDrag(titleBar, root, windowParams)
        root.addView(titleBar)

        // Màn hình điện thoại
        screenBox.background = rounded(Color.BLACK, 18)
        screenBox.clipToOutline = true
        screenBox.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        statusView.apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.parseColor("#CC000000"))
            text = "Đang kết nối…"
        }
        screenBox.addView(statusView, FrameLayout.LayoutParams(-1, -1))
        root.addView(screenBox, screenLayoutParams())

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                callbacks.onSurface(holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                callbacks.onSurface(null)
            }
        })
        attachTouchForwarding()

        // Thanh điều hướng
        val nav = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        nav.addView(iconButton("◁") { callbacks.onKey(Proto.KEY_BACK) })
        nav.addView(iconButton("○") { callbacks.onKey(Proto.KEY_HOME) })
        nav.addView(iconButton("▢") { callbacks.onKey(Proto.KEY_RECENTS) })
        nav.addView(iconButton("🔔") { callbacks.onKey(Proto.KEY_NOTIFICATIONS) })
        nav.addView(iconButton("🔒") { callbacks.onKey(Proto.KEY_LOCK) })
        val resize = iconButton("◢") {}
        attachResize(resize)
        nav.addView(resize)
        root.addView(nav, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun buildBubble() {
        bubble.apply {
            text = "📱"
            textSize = 26f
            gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#0A84FF"), 32)
            elevation = dp(10).toFloat()
            layoutParams = FrameLayout.LayoutParams(dp(60), dp(60))
        }
        bubbleParams.width = dp(60)
        bubbleParams.height = dp(60)
        attachDrag(bubble, bubble, bubbleParams, onTap = { restore() })
    }

    private fun screenLayoutParams(): LinearLayout.LayoutParams {
        val h = screenHeight
        val w = (h * aspect).roundToInt()
        return LinearLayout.LayoutParams(w, h)
    }

    private fun applyScreenSize() {
        screenBox.layoutParams = screenLayoutParams()
        if (shown && !minimized) wm.updateViewLayout(root, windowParams)
    }

    /** Kéo [handle] để di chuyển cửa sổ [target]. Chạm nhẹ (không kéo) gọi [onTap]. */
    private fun attachDrag(handle: View, target: View, params: WindowManager.LayoutParams, onTap: (() -> Unit)? = null) {
        var startX = 0f
        var startY = 0f
        var originX = 0
        var originY = 0
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY
                    originX = params.x; originY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX
                    val dy = e.rawY - startY
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                    if (moved) {
                        params.x = (originX + dx).roundToInt().coerceIn(-dp(40), tabletW - dp(40))
                        params.y = (originY + dy).roundToInt().coerceIn(0, tabletH - dp(40))
                        if (target.isAttachedToWindow) wm.updateViewLayout(target, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap?.invoke()
            }
            onTap != null || moved || e.actionMasked == MotionEvent.ACTION_DOWN
        }
    }

    /** Kéo góc ◢ để phóng to/thu nhỏ (giữ đúng tỉ lệ màn hình điện thoại). */
    private fun attachResize(handle: View) {
        var startY = 0f
        var startX = 0f
        var originH = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY; originH = screenHeight
                }
                MotionEvent.ACTION_MOVE -> {
                    val byY = e.rawY - startY
                    val byX = (e.rawX - startX) / aspect
                    val delta = if (abs(byY) > abs(byX)) byY else byX
                    screenHeight = (originH + delta).roundToInt().coerceIn(dp(240), (tabletH * 0.95f).roundToInt())
                    // Không để cửa sổ rộng hơn màn hình máy tính bảng
                    screenHeight = min(screenHeight, ((tabletW * 0.95f) / aspect).roundToInt())
                    applyScreenSize()
                }
            }
            true
        }
    }

    // Chạm nhanh / vuốt nhanh (< 200 ms): gửi cả thao tác khi nhấc tay (giữ được lực vuốt).
    // Giữ ngón tay lâu hơn: chuyển sang chạm trực tiếp, điện thoại kéo/cuộn theo ngón tay ngay.
    private val points = ArrayList<FloatArray>()
    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var touching = false
    private var liveMode = false
    private var lastLiveSend = 0L
    private val goLive = Runnable { if (touching && !liveMode) startLive() }

    private fun startLive() {
        liveMode = true
        val first = points.first()
        callbacks.onTouch(Proto.TOUCH_DOWN, first[0], first[1])
        for (p in downsample(points.drop(1), 20)) callbacks.onTouch(Proto.TOUCH_MOVE, p[0], p[1])
        lastLiveSend = System.currentTimeMillis()
    }

    private fun attachTouchForwarding() {
        surfaceView.setOnTouchListener { v, e ->
            if (v.width == 0 || v.height == 0) return@setOnTouchListener true
            val nx = (e.x / v.width).coerceIn(0f, 1f)
            val ny = (e.y / v.height).coerceIn(0f, 1f)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    points.clear()
                    points.add(floatArrayOf(nx, ny))
                    lastX = e.x; lastY = e.y
                    downTime = e.eventTime
                    touching = true
                    liveMode = false
                    surfaceView.postDelayed(goLive, 200)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(e.x - lastX, e.y - lastY) > dp(2)) {
                        lastX = e.x; lastY = e.y
                        if (liveMode) {
                            val now = System.currentTimeMillis()
                            if (now - lastLiveSend >= 16) {
                                callbacks.onTouch(Proto.TOUCH_MOVE, nx, ny)
                                lastLiveSend = now
                            }
                        } else {
                            points.add(floatArrayOf(nx, ny))
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    surfaceView.removeCallbacks(goLive)
                    touching = false
                    if (liveMode) {
                        callbacks.onTouch(Proto.TOUCH_UP, nx, ny)
                        liveMode = false
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        points.add(floatArrayOf(nx, ny))
                        val duration = (e.eventTime - downTime).toInt().coerceIn(1, 60_000)
                        val first = points.first()
                        val maxMove = points.maxOf { hypot((it[0] - first[0]) * v.width, (it[1] - first[1]) * v.height) }
                        val gesture = if (maxMove < dp(8)) listOf(first) else downsample(points, 60)
                        callbacks.onGesture(duration, gesture)
                    }
                    points.clear()
                }
            }
            true
        }
    }

    private fun downsample(src: List<FloatArray>, maxPoints: Int): List<FloatArray> {
        if (src.size <= maxPoints) return ArrayList(src)
        val step = (src.size - 1).toFloat() / (maxPoints - 1)
        return (0 until maxPoints).map { src[min(src.size - 1, (it * step).roundToInt())] }
    }

    // ------------------------------------------------------------ API cho service

    fun show() {
        if (shown) return
        wm.addView(root, windowParams)
        shown = true
    }

    fun setTitle(title: String) {
        titleView.text = title
    }

    fun setLatency(text: String) {
        latencyView.text = text
    }

    fun setQualityLabel(text: String) {
        qualityView.text = text
    }

    /** null = ẩn lớp thông báo, hiện hình điện thoại. */
    fun setStatus(text: String?) {
        statusView.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) statusView.text = text
    }

    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        aspect = width.toFloat() / height
        // Màn hình xoay ngang: giới hạn theo chiều rộng máy tính bảng
        val maxH = min((tabletH * 0.85f).roundToInt(), ((tabletW * 0.9f) / aspect).roundToInt())
        screenHeight = max(dp(240), min(screenHeight, maxH))
        if (aspect > 1f) screenHeight = maxH
        applyScreenSize()
    }

    fun minimize() {
        if (!shown || minimized) return
        bubbleParams.x = tabletW - dp(76)
        bubbleParams.y = max(dp(80), windowParams.y)
        wm.removeView(root)
        wm.addView(bubble, bubbleParams)
        minimized = true
    }

    fun restore() {
        if (!minimized) return
        wm.removeView(bubble)
        wm.addView(root, windowParams)
        minimized = false
    }

    fun dismiss() {
        try {
            if (minimized) wm.removeView(bubble) else if (shown) wm.removeView(root)
        } catch (_: Exception) {
        }
        shown = false
        minimized = false
    }
}
