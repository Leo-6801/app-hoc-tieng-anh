package com.leo.connect.controller

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Ô nhận phím vô hình trong cửa sổ điện thoại. Luôn gõ tiếng Việt kiểu Telex.
 *
 * Phím có thể tới theo 2 đường, cả hai đều đi qua bộ gõ Telex của app:
 * - Phím thẳng từ bàn phím rời (onKeyDown / commitText từng ký tự): app tự giữ "từ đang gõ".
 * - Bộ gõ của máy tính bảng gom chữ đang gõ dở (setComposingText "tieesng"): app chạy Telex
 *   trên cả chuỗi đó.
 * Mỗi lần từ đang gõ đổi, gửi MỘT lệnh "xóa n ký tự rồi chèn chữ mới" sang điện thoại.
 */
class KeyInputView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        /** Xóa [deleteCount] ký tự trước con trỏ rồi chèn [text]. */
        fun onReplace(deleteCount: Int, text: String)
        fun onEnter()
        fun onNewline()

        /** Phím thô (mã HID + phím bổ trợ) khi đang điều khiển qua Bluetooth. */
        fun onRawKey(usage: Int, modifiers: Int)
    }

    /**
     * Chế độ phím thô: chuyển nguyên phím sang điện thoại qua Bluetooth, không gõ Telex ở máy tính bảng
     * (bộ gõ của điện thoại tự xử lý tiếng Việt như với bàn phím thật).
     */
    var rawMode = false

    // Từ đang gõ theo đường phím thẳng: đúng như đang hiện trên điện thoại
    private var word = ""

    // Chữ đang gõ dở theo đường bộ gõ: [composingShown] là phần đã gửi sang điện thoại
    private var composingShown = ""

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    /** Gọi khi con trỏ trên điện thoại có thể đã đổi chỗ (chạm vào màn hình...). */
    fun reset() {
        word = ""
        composingShown = ""
    }

    /** Đổi [old] (đang hiện trên điện thoại) thành [next] bằng một lệnh. */
    private fun change(old: String, next: String) {
        var common = 0
        while (common < old.length && common < next.length && old[common] == next[common]) common++
        val delete = old.length - common
        val insert = next.substring(common)
        if (delete > 0 || insert.isNotEmpty()) listener.onReplace(delete, insert)
    }

    private fun typeChar(ch: Char) {
        if (ch.isLetter()) {
            val next = Telex.process(word, ch)
            change(word, next)
            word = next
        } else {
            listener.onReplace(0, ch.toString())
            word = ""
        }
    }

    private fun backspace() {
        if (composingShown.isNotEmpty()) composingShown = composingShown.dropLast(1)
        word = word.dropLast(1)
        listener.onReplace(1, "")
    }

    private fun enter(newline: Boolean) {
        reset()
        if (newline) listener.onNewline() else listener.onEnter()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (rawMode) {
            outAttrs.inputType = InputType.TYPE_NULL
            return null
        }
        // Tắt gợi ý/tự sửa để bộ gõ chuyển phím thẳng, không tự gom và sửa chữ
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val shown = Telex.type(text?.toString() ?: "")
                change(composingShown, shown)
                composingShown = shown
                word = ""
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val t = text?.toString() ?: ""
                if (composingShown.isEmpty() && t.length == 1) {
                    // Bộ gõ chuyển nguyên từng phím (thường gặp với bàn phím rời)
                    if (t[0] == '\n') enter(newline = false) else typeChar(t[0])
                    return true
                }
                change(composingShown, Telex.type(t))
                composingShown = ""
                word = ""
                return true
            }

            override fun finishComposingText(): Boolean {
                composingShown = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val n = beforeLength.coerceIn(0, 200)
                if (n > 0) {
                    composingShown = composingShown.dropLast(n)
                    word = word.dropLast(n)
                    listener.onReplace(n, "")
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) handleKey(event)
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                enter(newline = false)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return if (handleKey(event)) true else super.onKeyDown(keyCode, event)
    }

    /** Phím vật lý không qua bộ gõ. */
    private fun handleKey(event: KeyEvent): Boolean {
        if (rawMode) {
            val usage = BluetoothHid.usageOf(event.keyCode)
            if (usage == 0) return false
            listener.onRawKey(usage, BluetoothHid.modifiersOf(event))
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                backspace()
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                enter(newline = event.isShiftPressed)
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                reset()
                listener.onReplace(0, "\t")
                return true
            }
        }
        val ch = event.unicodeChar
        if (ch != 0 && !event.isCtrlPressed && !event.isAltPressed && ch < 0x10000) {
            composingShown = ""
            typeChar(ch.toChar())
            return true
        }
        return false
    }
}
