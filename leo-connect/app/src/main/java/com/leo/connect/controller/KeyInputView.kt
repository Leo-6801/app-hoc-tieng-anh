package com.leo.connect.controller

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Ô nhận phím vô hình. Nhận chữ từ bộ gõ của máy tính bảng (bàn phím rời hoặc bàn phím ảo),
 * kể cả chữ đang gõ dở kiểu Telex/VNI, và báo ra ngoài dưới dạng "chèn chữ" / "xóa lùi" / "Enter",
 * để điện thoại hiện chữ ngay trong lúc gõ.
 */
class KeyInputView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onText(text: String)
        fun onBackspace()
        fun onEnter()
    }

    // Chữ đang gõ dở (đã gửi sang điện thoại); khi bộ gõ đổi chữ này thì gửi phần chênh lệch.
    private var composing = ""

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    fun reset() {
        composing = ""
    }

    /** Thay chữ đang gõ dở [composing] bằng [next]: xóa phần khác nhau rồi gửi phần mới. */
    private fun replaceComposing(next: String) {
        var common = 0
        while (common < composing.length && common < next.length && composing[common] == next[common]) common++
        repeat(composing.length - common) { listener.onBackspace() }
        if (next.length > common) listener.onText(next.substring(common))
        composing = next
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                replaceComposing(text?.toString() ?: "")
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                replaceComposing(text?.toString() ?: "")
                composing = ""
                return true
            }

            override fun finishComposingText(): Boolean {
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                composing = ""
                repeat(beforeLength.coerceAtMost(200)) { listener.onBackspace() }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) handleKey(event)
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                composing = ""
                listener.onEnter()
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return if (handleKey(event)) true else super.onKeyDown(keyCode, event)
    }

    /** Phím vật lý không qua bộ gõ (Backspace, Enter, ký tự thường). */
    private fun handleKey(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                composing = ""
                listener.onBackspace()
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                composing = ""
                listener.onEnter()
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                listener.onText("\t")
                return true
            }
            KeyEvent.KEYCODE_SPACE -> {
                composing = ""
                listener.onText(" ")
                return true
            }
        }
        val ch = event.unicodeChar
        if (ch != 0 && !event.isCtrlPressed) {
            composing = ""
            listener.onText(String(Character.toChars(ch)))
            return true
        }
        return false
    }
}
