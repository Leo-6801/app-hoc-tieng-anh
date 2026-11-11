package com.leo.connect.controller

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Ô nhận phím vô hình trong cửa sổ điện thoại.
 * - Bàn phím rời: tự gõ Telex (khi bật VI) rồi gửi chữ đã có dấu sang điện thoại.
 * - Bàn phím ảo / bộ gõ có xử lý tiếng Việt: lấy chữ đang gõ dở từ bộ gõ.
 * Điện thoại nhận "chèn chữ" / "xóa lùi" / "Enter"; khi chữ đổi (thêm dấu) thì gửi phần chênh lệch.
 */
class KeyInputView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onText(text: String)
        fun onBackspace()
        fun onEnter()
        fun onNewline()
    }

    /** Bật bộ gõ Telex cho bàn phím rời. */
    var telex = true

    // Chữ đang gõ dở do bộ gõ của máy tính bảng quản lý
    private var composing = ""

    // Từ hiện tại do bộ gõ Telex của app quản lý (đã gửi sang điện thoại)
    private var word = ""

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    /** Gọi khi con trỏ trên điện thoại có thể đã đổi chỗ (chạm vào màn hình...). */
    fun reset() {
        composing = ""
        word = ""
    }

    /** Thay [old] (đã gửi) bằng [next]: xóa phần khác nhau rồi gửi phần mới. */
    private fun replace(old: String, next: String) {
        var common = 0
        while (common < old.length && common < next.length && old[common] == next[common]) common++
        repeat(old.length - common) { listener.onBackspace() }
        if (next.length > common) listener.onText(next.substring(common))
    }

    private fun typeChar(ch: Char) {
        if (telex && ch.isLetter() && ch.code < 128) {
            val next = Telex.process(word, ch)
            replace(word, next)
            word = next
        } else {
            listener.onText(ch.toString())
            word = if (ch.isLetter()) word + ch else ""
        }
    }

    private fun backspace() {
        composing = ""
        listener.onBackspace()
        word = word.dropLast(1)
    }

    private fun enter() {
        reset()
        listener.onEnter()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val next = text?.toString() ?: ""
                replace(composing, next)
                composing = next
                word = ""
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val t = text?.toString() ?: ""
                if (composing.isEmpty() && t.length == 1) {
                    // Bộ gõ chỉ chuyển nguyên phím (thường gặp với bàn phím rời)
                    when (t[0]) {
                        '\n' -> enter()
                        else -> typeChar(t[0])
                    }
                    return true
                }
                replace(composing, t)
                composing = ""
                word = ""
                return true
            }

            override fun finishComposingText(): Boolean {
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtMost(200)) { backspace() }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) handleKey(event)
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                enter()
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return if (handleKey(event)) true else super.onKeyDown(keyCode, event)
    }

    /** Phím vật lý không qua bộ gõ. */
    private fun handleKey(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                backspace()
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.isShiftPressed) {
                    reset()
                    listener.onNewline()
                } else {
                    enter()
                }
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                reset()
                listener.onText("\t")
                return true
            }
        }
        val ch = event.unicodeChar
        if (ch != 0 && !event.isCtrlPressed && !event.isAltPressed && ch < 0x10000) {
            composing = ""
            typeChar(ch.toChar())
            return true
        }
        return false
    }
}
