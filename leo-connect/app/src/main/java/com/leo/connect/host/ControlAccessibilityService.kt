package com.leo.connect.host

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.leo.connect.CrashLog
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

    // ------------------------------------------------------------ gõ chữ vào ô nhập đang chọn
    // Các thao tác gõ liên tiếp được gom lại, áp một lần lên nội dung ô nhập (tránh mất chữ khi gõ nhanh).

    private sealed class Edit {
        class Insert(val text: String) : Edit()
        object Backspace : Edit()
    }

    private val pendingEdits = ArrayList<Edit>()
    private val flushEdits = Runnable { safe("gõ chữ") { applyEdits() } }

    /** Lỗi khi gõ chữ không được làm văng app: ghi lại để xem trên màn hình chính. */
    private inline fun safe(where: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            pendingEdits.clear()
            CrashLog.record(this, e, where)
        }
    }

    fun typeText(text: String) {
        main.post {
            pendingEdits.add(Edit.Insert(text))
            main.removeCallbacks(flushEdits)
            main.postDelayed(flushEdits, 30)
        }
    }

    private fun queueBackspace() {
        pendingEdits.add(Edit.Backspace)
        main.removeCallbacks(flushEdits)
        main.postDelayed(flushEdits, 30)
    }

    // Nội dung ô nhập mà app vừa ghi. App đích (Zalo, Messenger...) có thể chưa kịp cập nhật,
    // nên nếu đọc ra nội dung cũ thì dùng bản app tự nhớ thay vì nội dung cũ.
    private var lastNode: android.view.accessibility.AccessibilityNodeInfo? = null
    private var lastWritten: String? = null
    private var lastBefore: String? = null
    private var lastCursor = 0
    private var lastWriteAt = 0L

    private fun pressEnter() {
        applyEdits()
        val node = findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null || !node.isEditable) return
        val enterAction = if (Build.VERSION.SDK_INT >= 30) {
            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
        } else {
            null
        }
        // Chỉ dùng lệnh "Gửi" của ô nhập khi ô đó thật sự có; nếu không thì xuống dòng
        if (enterAction != null && node.actionList.any { it.id == enterAction.id } &&
            node.performAction(enterAction.id)
        ) {
            lastWritten = null
            return
        }
        pendingEdits.add(Edit.Insert("\n"))
        applyEdits()
    }

    /** Chạy trên luồng chính. */
    private fun applyEdits() {
        main.removeCallbacks(flushEdits)
        if (pendingEdits.isEmpty()) return
        val edits = ArrayList(pendingEdits)
        pendingEdits.clear()
        val node = findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (!node.isEditable) return
        var current = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
        var start = node.textSelectionStart
        var end = node.textSelectionEnd

        val remembered = lastWritten
        val recent = System.currentTimeMillis() - lastWriteAt < 3_000
        if (remembered != null && recent && node == lastNode && current != remembered && current == lastBefore) {
            // App chưa cập nhật: dùng nội dung vừa ghi
            current = remembered
            start = lastCursor
            end = lastCursor
        } else if (remembered != null && recent && node == lastNode && current == remembered) {
            // Nội dung đúng như vừa ghi nhưng con trỏ có thể chưa cập nhật
            if (start < 0 || start == end && start != lastCursor && (start == 0 || start == current.length)) {
                start = lastCursor
                end = lastCursor
            }
        }

        if (start < 0 || end < 0) {
            start = current.length
            end = current.length
        }
        start = start.coerceIn(0, current.length)
        end = end.coerceIn(0, current.length)
        if (start > end) start = end.also { end = start }

        val sb = StringBuilder(current)
        var cursor = start
        if (end > start && edits.isNotEmpty()) {
            // Có đoạn đang được bôi đen: thao tác đầu tiên thay thế đoạn đó
            sb.delete(start, end)
            if (edits.first() is Edit.Backspace) edits.removeAt(0)
        }
        for (e in edits) {
            when (e) {
                is Edit.Insert -> {
                    sb.insert(cursor, e.text)
                    cursor += e.text.length
                }
                Edit.Backspace -> if (cursor > 0) {
                    // Xóa nguyên một ký tự (kể cả emoji 2 đơn vị)
                    val remove = if (cursor >= 2 && Character.isSurrogatePair(sb[cursor - 2], sb[cursor - 1])) 2 else 1
                    sb.delete(cursor - remove, cursor)
                    cursor -= remove
                }
            }
        }
        val result = sb.toString()
        val args = Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, result)
        }
        node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val sel = Bundle().apply {
            putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        lastNode = node
        lastBefore = current
        lastWritten = result
        lastCursor = cursor
        lastWriteAt = System.currentTimeMillis()
    }

    fun performKey(key: Int) {
        if (key == Proto.KEY_BACKSPACE) {
            main.post { safe("xóa lùi") { queueBackspace() } }
            return
        }
        if (key == Proto.KEY_ENTER) {
            main.post { safe("Enter") { pressEnter() } }
            return
        }
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
