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
