package com.leo.connect.controller

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
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
 * Cửa sổ nổi hình điện thoại trên máy tính bảng (không khung, không hàng nút):
 * - tay nắm ••• phía trên, nằm ngoài màn hình: kéo để di chuyển, nhấn giữ để mở menu (thu nhỏ / tắt)
 * - chụm/mở 2 ngón tay trên màn hình để thu phóng
 * - chạm/vuốt/nhấn giữ trên màn hình được gửi sang điện thoại
 * - vuốt từ mép trái/phải vào = Quay lại; vuốt từ đáy lên = Màn hình chính; vuốt lên rồi giữ = Đa nhiệm
 * - chạm vào cửa sổ là bàn phím rời gõ vào điện thoại; chạm ra ngoài thì trả bàn phím cho app khác
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class PhoneWindow(private val context: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        fun onSurface(surface: Surface?)
        fun onGesture(durationMs: Int, points: List<FloatArray>)
        fun onTouch(action: Int, x: Float, y: Float)
        fun onKey(key: Int)
        fun onReplace(deleteCount: Int, text: String)
        fun onRawKey(usage: Int, modifiers: Int)
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

    private val statusView = TextView(context)
    private val surfaceView = SurfaceView(context)
    private val screenBox = FrameLayout(context)
    private val root = LinearLayout(context)
    private val bubble = TextView(context)
    private val handle = TextView(context)
    private val menu = LinearLayout(context)

    private var shown = false
    private var minimized = false

    // Cửa sổ đang giữ bàn phím (bàn phím rời gõ vào điện thoại)
    private var keyboardMode = false
    private val keyInput = KeyInputView(context, object : KeyInputView.Listener {
        override fun onReplace(deleteCount: Int, text: String) = callbacks.onReplace(deleteCount, text)
        override fun onEnter() = callbacks.onKey(Proto.KEY_ENTER)
        override fun onNewline() = callbacks.onKey(Proto.KEY_NEWLINE)
        override fun onRawKey(usage: Int, modifiers: Int) = callbacks.onRawKey(usage, modifiers)
    })

    init {
        buildWindow()
        buildBubble()
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun menuItem(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnClickListener {
            hideMenu()
            onClick()
        }
    }

    private fun buildWindow() {
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL

        // Tay nắm ••• nằm phía trên, ngoài màn hình điện thoại nên không che nội dung
        handle.apply {
            text = "•••"
            textSize = 16f
            letterSpacing = 0.2f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(1), dp(22), dp(3))
            background = rounded(Color.parseColor("#B3181A1F"), 12)
        }
        attachHandle()
        root.addView(handle, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(4) })

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
        screenBox.addView(keyInput, FrameLayout.LayoutParams(1, 1))

        // Menu khi nhấn giữ •••: chỉ hiện tạm thời, tự ẩn
        menu.apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#F2181A1F"), 14)
            visibility = View.GONE
            elevation = dp(8).toFloat()
            addView(menuItem("—  Thu nhỏ thành bong bóng") { minimize() })
            addView(menuItem("⌨  Bàn phím ảo") { setKeyboard(true, showSoftKeyboard = true) })
            addView(menuItem("✕  Tắt cửa sổ") { callbacks.onClose() })
        }
        screenBox.addView(menu, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(6)
        })

        // Chạm ra ngoài cửa sổ (sang app khác): trả bàn phím cho app đó, ẩn menu
        root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                hideMenu()
                if (keyboardMode) setKeyboard(false)
            }
            false
        }
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
    }

    private val autoHideMenu = Runnable { hideMenu() }

    private fun showMenu() {
        menu.visibility = View.VISIBLE
        menu.removeCallbacks(autoHideMenu)
        menu.postDelayed(autoHideMenu, 5_000)
    }

    private fun hideMenu() {
        menu.removeCallbacks(autoHideMenu)
        menu.visibility = View.GONE
    }

    /** Tay nắm •••: kéo để di chuyển, nhấn giữ mở menu, chạm 2 lần phóng to tối đa / trở lại. */
    private fun attachHandle() {
        var startX = 0f
        var startY = 0f
        var originX = 0
        var originY = 0
        var moved = false
        var longPressed = false
        var lastTap = 0L
        val longPress = Runnable {
            if (!moved) {
                longPressed = true
                handle.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                showMenu()
            }
        }
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY
                    originX = windowParams.x; originY = windowParams.y
                    moved = false
                    longPressed = false
                    handle.postDelayed(longPress, 450)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX
                    val dy = e.rawY - startY
                    if (!moved && !longPressed && (abs(dx) > dp(6) || abs(dy) > dp(6))) {
                        moved = true
                        handle.removeCallbacks(longPress)
                        hideMenu()
                    }
                    if (moved) {
                        windowParams.x = (originX + dx).roundToInt().coerceIn(-dp(40), tabletW - dp(60))
                        windowParams.y = (originY + dy).roundToInt().coerceIn(0, tabletH - dp(40))
                        if (root.isAttachedToWindow) wm.updateViewLayout(root, windowParams)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handle.removeCallbacks(longPress)
                    if (e.actionMasked == MotionEvent.ACTION_UP && !moved && !longPressed) {
                        val now = System.currentTimeMillis()
                        if (now - lastTap < 350) {
                            toggleMaximize()
                            lastTap = 0L
                        } else {
                            lastTap = now
                        }
                    }
                }
            }
            true
        }
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

    /** Chiều cao màn hình lớn nhất vừa với máy tính bảng (chừa chỗ cho 2 thanh). */
    private fun maxScreenHeight(): Int =
        min((tabletH * 0.97f).roundToInt() - dp(40), ((tabletW * 0.97f) / aspect).roundToInt())

    private var heightBeforeMax = 0

    private fun toggleMaximize() {
        val maxH = maxScreenHeight()
        if (heightBeforeMax == 0 || screenHeight < maxH - dp(4)) {
            heightBeforeMax = screenHeight
            screenHeight = maxH
            windowParams.x = ((tabletW - screenHeight * aspect) / 2).roundToInt().coerceAtLeast(0)
            windowParams.y = 0
        } else {
            screenHeight = heightBeforeMax
            heightBeforeMax = 0
        }
        applyScreenSize()
    }

    // Chụm/mở 2 ngón tay trên màn hình điện thoại: thu phóng cửa sổ (không gửi sang điện thoại)
    private var pinching = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            screenHeight = (screenHeight * detector.scaleFactor).roundToInt().coerceIn(dp(240), maxScreenHeight())
            applyScreenSize()
            return true
        }
    })

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

    // Vuốt từ mép như dùng máy thật: 1 = mép trái/phải (Quay lại), 2 = đáy (Màn hình chính / Đa nhiệm)
    private var edge = 0
    private var edgeStartX = 0f
    private var edgeStartY = 0f
    private var navDone = false
    private val recentsHold = Runnable {
        if (edge == 2 && !navDone && touching) {
            navDone = true
            surfaceView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            callbacks.onKey(Proto.KEY_RECENTS)
        }
    }

    private fun attachTouchForwarding() {
        surfaceView.setOnTouchListener { v, e ->
            if (v.width == 0 || v.height == 0) return@setOnTouchListener true
            scaleDetector.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                // Ngón thứ 2 chạm vào: hủy thao tác đang gửi, chuyển sang thu phóng
                surfaceView.removeCallbacks(goLive)
                surfaceView.removeCallbacks(recentsHold)
                if (liveMode) {
                    val first = points.firstOrNull() ?: floatArrayOf(0.5f, 0.5f)
                    callbacks.onTouch(Proto.TOUCH_UP, first[0], first[1])
                    liveMode = false
                }
                points.clear()
                touching = false
                edge = 0
                pinching = true
                return@setOnTouchListener true
            }
            if (pinching) {
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) pinching = false
                return@setOnTouchListener true
            }
            val nx = (e.x / v.width).coerceIn(0f, 1f)
            val ny = (e.y / v.height).coerceIn(0f, 1f)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    hideMenu()
                    keyInput.reset() // chạm màn hình có thể làm đổi chỗ con trỏ trên điện thoại
                    points.clear()
                    points.add(floatArrayOf(nx, ny))
                    lastX = e.x; lastY = e.y
                    downTime = e.eventTime
                    touching = true
                    liveMode = false
                    navDone = false
                    edgeStartX = e.x; edgeStartY = e.y
                    if (directTouch) {
                        // Bluetooth: điện thoại coi đây là màn hình cảm ứng thật, nên gửi nguyên ngón tay
                        // ngay lập tức. Vuốt mép, vuốt lên rồi giữ... do chính điện thoại xử lý như cầm máy.
                        edge = 0
                        liveMode = true
                        callbacks.onTouch(Proto.TOUCH_DOWN, nx, ny)
                        lastLiveSend = System.currentTimeMillis()
                    } else {
                        edge = when {
                            e.y > v.height - dp(18) -> 2
                            e.x < dp(14) || e.x > v.width - dp(14) -> 1
                            else -> 0
                        }
                        // Bắt đầu từ mép: chờ xem có phải cử chỉ điều hướng không, chưa gửi chạm trực tiếp
                        if (edge == 0) surfaceView.postDelayed(goLive, 200)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(e.x - lastX, e.y - lastY) > dp(2)) {
                        lastX = e.x; lastY = e.y
                        if (edge == 2) {
                            // Đang vuốt lên từ đáy: dừng tay 0,35 giây thì mở Đa nhiệm
                            surfaceView.removeCallbacks(recentsHold)
                            if (!navDone && edgeStartY - e.y > dp(48)) surfaceView.postDelayed(recentsHold, 350)
                        }
                        if (liveMode) {
                            val now = System.currentTimeMillis()
                            if (now - lastLiveSend >= (if (directTouch) 11 else 16)) {
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
                    surfaceView.removeCallbacks(recentsHold)
                    touching = false
                    val up = e.actionMasked == MotionEvent.ACTION_UP
                    var handled = navDone
                    if (up && !handled && edge == 1 && abs(e.x - edgeStartX) > dp(40) &&
                        abs(e.x - edgeStartX) > abs(e.y - edgeStartY)
                    ) {
                        callbacks.onKey(Proto.KEY_BACK)
                        handled = true
                    }
                    if (up && !handled && edge == 2 && edgeStartY - e.y > dp(48)) {
                        callbacks.onKey(Proto.KEY_HOME)
                        handled = true
                    }
                    edge = 0
                    if (liveMode) {
                        callbacks.onTouch(Proto.TOUCH_UP, nx, ny)
                        liveMode = false
                    } else if (up && !handled) {
                        // Không phải cử chỉ điều hướng: gửi như chạm/vuốt bình thường
                        points.add(floatArrayOf(nx, ny))
                        val duration = (e.eventTime - downTime).toInt().coerceIn(1, 60_000)
                        val first = points.first()
                        val maxMove = points.maxOf { hypot((it[0] - first[0]) * v.width, (it[1] - first[1]) * v.height) }
                        val gesture = if (maxMove < dp(8)) listOf(first) else downsample(points, 60)
                        callbacks.onGesture(duration, gesture)
                    }
                    points.clear()
                    // Vừa chạm vào cửa sổ: nhận bàn phím rời (đổi sau khi nhấc tay để không ngắt thao tác chạm)
                    if (!keyboardMode) surfaceView.post { setKeyboard(true) }
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

    /**
     * Bật: cửa sổ nhận phím từ bàn phím rời và gõ vào điện thoại. Bàn phím ảo chỉ hiện khi
     * chọn trong menu ••• ([showSoftKeyboard]). Tắt: trả bàn phím cho app khác của máy tính bảng.
     */
    fun setKeyboard(on: Boolean, showSoftKeyboard: Boolean = false) {
        if (!shown || minimized) return
        val imm = context.getSystemService(InputMethodManager::class.java)
        if (on != keyboardMode) {
            keyboardMode = on
            windowParams.flags = if (on) {
                windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            if (!on) {
                keyInput.reset()
                imm.hideSoftInputFromWindow(keyInput.windowToken, 0)
            }
            if (root.isAttachedToWindow) wm.updateViewLayout(root, windowParams)
        }
        if (on) {
            keyInput.post {
                keyInput.requestFocus()
                if (showSoftKeyboard) {
                    imm.restartInput(keyInput)
                    imm.showSoftInput(keyInput, 0)
                }
            }
        }
    }

    // Đang điều khiển qua Bluetooth: gửi chạm trực tiếp, không tự bắt cử chỉ vuốt mép
    private var directTouch = false

    /** Bật khi điều khiển qua Bluetooth: chạm trực tiếp và bàn phím rời gửi phím thô sang điện thoại. */
    fun setRawKeys(on: Boolean) {
        directTouch = on
        if (keyInput.rawMode == on) return
        keyInput.rawMode = on
        keyInput.reset()
        context.getSystemService(InputMethodManager::class.java).restartInput(keyInput)
    }

    fun showMessage(text: String) {
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
    }

    fun minimize() {
        if (!shown || minimized) return
        hideMenu()
        if (keyboardMode) setKeyboard(false)
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
