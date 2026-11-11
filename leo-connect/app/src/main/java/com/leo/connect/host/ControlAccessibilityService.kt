package com.leo.connect.host

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.leo.connect.Proto

/**
 * Thực hiện thao tác do máy tính bảng gửi tới: chạm, vuốt, nhấn giữ, Back/Home/Đa nhiệm.
 * Không đọc nội dung màn hình (canRetrieveWindowContent = false).
 */
class ControlAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ControlAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val component = ComponentName(context, ControlAccessibilityService::class.java)
            val names = setOf(component.flattenToString(), component.flattenToShortString())
            return enabled.split(':').any { entry -> names.any { it.equals(entry, ignoreCase = true) } }
        }
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** [points]: toạ độ chuẩn hoá 0..1 theo màn hình điện thoại. */
    fun performGesture(durationMs: Int, points: List<FloatArray>) {
        if (points.isEmpty()) return
        main.post {
            val metrics = DisplayMetrics()
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            val w = metrics.widthPixels.toFloat()
            val h = metrics.heightPixels.toFloat()
            val path = Path()
            points.forEachIndexed { i, p ->
                val x = (p[0] * w).coerceIn(0f, w - 1f)
                val y = (p[1] * h).coerceIn(0f, h - 1f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val duration = durationMs.toLong().coerceIn(1L, GestureDescription.getMaxGestureDuration())
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            try {
                dispatchGesture(gesture, null, null)
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------ chạm trực tiếp (kéo, cuộn, giữ)
    // Ngón tay "ảo" được giữ trên màn hình bằng các đoạn vuốt nối tiếp nhau (continueStroke).
    // Mỗi đoạn mới phải bắt đầu đúng chỗ đoạn trước kết thúc.

    private var liveStroke: GestureDescription.StrokeDescription? = null
    private var liveEnd: FloatArray? = null
    private val livePending = ArrayList<FloatArray>()
    private var liveUp = false
    private var liveInFlight = false
    private var liveGeneration = 0
    private var holdSign = 1f

    private fun toScreen(nx: Float, ny: Float): FloatArray {
        val metrics = DisplayMetrics()
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        return floatArrayOf((nx * w).coerceIn(1f, w - 2f), (ny * h).coerceIn(1f, h - 2f))
    }

    fun liveTouch(action: Int, nx: Float, ny: Float) {
        main.post {
            val p = toScreen(nx, ny)
            when (action) {
                Proto.TOUCH_DOWN -> {
                    liveGeneration++
                    liveStroke = null
                    livePending.clear()
                    liveUp = false
                    liveInFlight = false
                    liveEnd = p
                    dispatchLiveSegment()
                }
                Proto.TOUCH_MOVE -> {
                    if (liveEnd == null) return@post
                    livePending.add(p)
                    if (!liveInFlight) dispatchLiveSegment()
                }
                Proto.TOUCH_UP -> {
                    if (liveEnd == null) return@post
                    livePending.add(p)
                    liveUp = true
                    if (!liveInFlight) dispatchLiveSegment()
                }
            }
        }
    }

    /** Chạy trên luồng chính. */
    private fun dispatchLiveSegment() {
        val start = liveEnd ?: return
        val points = ArrayList(livePending)
        livePending.clear()
        val willContinue = !liveUp
        val path = Path()
        path.moveTo(start[0], start[1])
        val end: FloatArray
        val duration: Long
        if (points.isEmpty()) {
            // Ngón tay đứng yên: giữ bằng một đoạn rất ngắn (đổi chiều để không bị trôi)
            holdSign = -holdSign
            end = floatArrayOf(start[0] + 0.5f * holdSign, start[1])
            path.lineTo(end[0], end[1])
            duration = 60
        } else {
            for (pt in points) path.lineTo(pt[0], pt[1])
            end = points.last()
            duration = (points.size * 16L).coerceIn(16L, 120L)
        }
        val previous = liveStroke
        val stroke = try {
            if (previous == null) {
                GestureDescription.StrokeDescription(path, 0, duration, willContinue)
            } else {
                previous.continueStroke(path, 0, duration, willContinue)
            }
        } catch (e: Exception) {
            resetLive()
            return
        }
        liveStroke = if (willContinue) stroke else null
        liveEnd = if (willContinue) end else null
        liveInFlight = true
        val generation = liveGeneration
        val ok = try {
            dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (generation != liveGeneration) return
                        liveInFlight = false
                        if (liveStroke != null) dispatchLiveSegment()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (generation != liveGeneration) return
                        resetLive()
                    }
                },
                main,
            )
        } catch (e: Exception) {
            false
        }
        if (!ok) resetLive()
    }

    private fun resetLive() {
        liveStroke = null
        liveEnd = null
        livePending.clear()
        liveUp = false
        liveInFlight = false
    }

    fun performKey(key: Int) {
        val action = when (key) {
            Proto.KEY_BACK -> GLOBAL_ACTION_BACK
            Proto.KEY_HOME -> GLOBAL_ACTION_HOME
            Proto.KEY_RECENTS -> GLOBAL_ACTION_RECENTS
            Proto.KEY_NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            Proto.KEY_LOCK -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return
            else -> return
        }
        main.post { performGlobalAction(action) }
    }
}
