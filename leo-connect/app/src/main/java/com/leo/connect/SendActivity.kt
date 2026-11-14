package com.leo.connect

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView

/**
 * Gửi file sang máy kia. Mở theo 2 cách: từ nút Chia sẻ của app khác (chọn "Leo Connect"),
 * hoặc từ nút "Gửi file" trong app (tự mở trình chọn file). Giữ mở tới khi gửi xong để còn quyền đọc file.
 */
class SendActivity : Activity() {
    companion object {
        const val ACTION_PICK = "com.leo.connect.PICK_FILES"
        private const val REQ_PICK = 1
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var label: TextView
    private val refresher = object : Runnable {
        override fun run() {
            val p = Link.progress
            if (p.isNotEmpty()) label.text = p
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        label = TextView(this).apply {
            text = "Đang chuẩn bị gửi…"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        setContentView(label)
        if (!Link.connected) {
            Link.say(this, "Hai máy chưa kết nối. Kết nối Leo Connect trước rồi gửi lại.")
            finish()
            return
        }
        if (savedInstanceState != null) return
        when (intent?.action) {
            ACTION_PICK -> {
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                try {
                    startActivityForResult(pick, REQ_PICK)
                } catch (e: Exception) {
                    Link.say(this, "Máy này không có trình chọn file")
                    finish()
                }
            }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    uri != null -> send(listOf(uri))
                    !text.isNullOrEmpty() -> {
                        Link.sendText(text)
                        Link.say(this, "Đã chép sang bộ nhớ tạm của máy kia")
                        finish()
                    }
                    else -> finish()
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (uris.isNullOrEmpty()) finish() else send(uris)
            }
            else -> finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK) return
        val uris = ArrayList<Uri>()
        if (resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
            } else {
                data.data?.let { uris.add(it) }
            }
        }
        if (uris.isEmpty()) finish() else send(uris)
    }

    private fun send(uris: List<Uri>) {
        label.text = if (uris.size == 1) "Đang gửi file sang máy kia…" else "Đang gửi ${uris.size} file sang máy kia…"
        handler.post(refresher)
        Link.sendFiles(this, uris) { if (!isFinishing) finish() }
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresher)
        super.onDestroy()
    }
}
