package com.leo.connect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Chuyển file và dùng chung bộ nhớ tạm giữa hai máy, đi trên kết nối đã mã hóa.
 * Dịch vụ đang kết nối (điện thoại hoặc máy tính bảng) gắn hàm gửi vào [attach].
 */
object Link {
    private class Incoming(val out: OutputStream, val uri: Uri?, val file: File?, val name: String, val size: Long) {
        var got = 0L
    }

    @Volatile
    private var sender: ((Int, ByteArray) -> Unit)? = null

    /** Dòng tiến độ chuyển file để hiện trên giao diện (rỗng = không có gì đang chuyển). */
    @Volatile
    var progress = ""
        private set

    val connected get() = sender != null

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val ids = AtomicInteger(1)
    private val incoming = HashMap<Int, Incoming>()

    @Volatile
    private var lastClip = ""
    private var lastClipStamp = 0L
    private const val CHUNK = 48 * 1024
    private const val MAX_CLIP = 100_000

    fun attach(send: (Int, ByteArray) -> Unit) {
        sender = send
    }

    fun detach(context: Context) {
        sender = null
        synchronized(incoming) {
            for (item in incoming.values) discard(context, item)
            incoming.clear()
        }
        progress = ""
    }

    fun say(context: Context, text: String) {
        main.post { Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show() }
    }

    // ------------------------------------------------------------ gửi file

    /** Gửi lần lượt các file sang máy kia (chạy nền). [onDone] được gọi ở luồng chính khi xong. */
    fun sendFiles(context: Context, uris: List<Uri>, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        io.execute {
            var sent = 0
            for (uri in uris) if (sendOne(app, uri)) sent++
            progress = ""
            if (sent > 0) say(app, if (sent == 1) "Đã gửi 1 file sang máy kia" else "Đã gửi $sent file sang máy kia")
            if (onDone != null) main.post(onDone)
        }
    }

    private fun sendOne(context: Context, uri: Uri): Boolean {
        val send = sender
        if (send == null) {
            say(context, "Chưa kết nối với máy kia nên chưa gửi được file")
            return false
        }
        val id = ids.getAndIncrement()
        var started = false
        try {
            var name = uri.lastPathSegment ?: "file"
            var size = -1L
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                        if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                    }
                }
            } catch (_: Exception) {
            }
            name = cleanName(name)
            val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("không mở được file")
            input.use { stream ->
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                send(Proto.FILE_BEGIN, ByteBuffer.allocate(12 + nameBytes.size).putInt(id).putLong(size).put(nameBytes).array())
                started = true
                val buf = ByteArray(CHUNK)
                var total = 0L
                var lastShown = 0L
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    send(Proto.FILE_DATA, ByteBuffer.allocate(4 + n).putInt(id).put(buf, 0, n).array())
                    total += n
                    val now = System.currentTimeMillis()
                    if (now - lastShown > 300) {
                        lastShown = now
                        progress = "Đang gửi $name" + if (size > 0) " · ${total * 100 / size}%" else " · ${total / 1024} KB"
                    }
                }
                send(Proto.FILE_END, ByteBuffer.allocate(5).putInt(id).put(1.toByte()).array())
            }
            return true
        } catch (e: SecurityException) {
            say(context, "Android không cho đọc file thả vào cửa sổ nổi. Hãy thả vào màn hình app Leo Connect, hoặc bấm Chia sẻ → Leo Connect.")
        } catch (e: Exception) {
            say(context, "Gửi file không thành công: ${e.message ?: e.javaClass.simpleName}")
        }
        if (started) {
            try {
                send(Proto.FILE_END, ByteBuffer.allocate(5).putInt(id).put(0.toByte()).array())
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun cleanName(raw: String): String {
        val name = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trim().trim('.')
        return if (name.isEmpty()) "file" else name.takeLast(120)
    }

    // ------------------------------------------------------------ nhận

    /** Xử lý gói chuyển file / bộ nhớ tạm. Trả về true nếu gói thuộc phần này. */
    fun onMessage(context: Context, type: Int, data: ByteArray): Boolean {
        val app = context.applicationContext
        when (type) {
            Proto.FILE_BEGIN -> if (data.size >= 12) {
                val bb = ByteBuffer.wrap(data)
                val id = bb.int
                val size = bb.long
                val name = cleanName(String(data, 12, data.size - 12, Charsets.UTF_8))
                try {
                    val item = create(app, name, size)
                    synchronized(incoming) { incoming[id] = item }
                    progress = "Đang nhận $name"
                } catch (e: Exception) {
                    say(app, "Không lưu được file $name: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            Proto.FILE_DATA -> if (data.size > 4) {
                val id = ByteBuffer.wrap(data).int
                val item = synchronized(incoming) { incoming[id] } ?: return true
                try {
                    item.out.write(data, 4, data.size - 4)
                    item.got += data.size - 4
                    progress = "Đang nhận ${item.name}" +
                        if (item.size > 0) " · ${item.got * 100 / item.size}%" else " · ${item.got / 1024} KB"
                } catch (e: Exception) {
                    synchronized(incoming) { incoming.remove(id) }
                    discard(app, item)
                    progress = ""
                    say(app, "Không lưu được file ${item.name}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            Proto.FILE_END -> if (data.size >= 5) {
                val bb = ByteBuffer.wrap(data)
                val id = bb.int
                val ok = bb.get().toInt() == 1
                val item = synchronized(incoming) { incoming.remove(id) } ?: return true
                progress = ""
                if (ok) finish(app, item) else discard(app, item)
            }
            Proto.CLIPBOARD -> {
                if (data.isEmpty() || data.size > MAX_CLIP * 4) return true
                val text = String(data, Charsets.UTF_8)
                lastClip = text
                main.post {
                    try {
                        app.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Leo Connect", text))
                    } catch (_: Exception) {
                    }
                }
            }
            else -> return false
        }
        return true
    }

    private fun create(context: Context, name: String, size: Long): Incoming {
        if (Build.VERSION.SDK_INT >= 29) {
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/LeoConnect")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("không tạo được file trong thư mục Tải xuống")
            val out = context.contentResolver.openOutputStream(uri)
                ?: throw java.io.IOException("không ghi được vào thư mục Tải xuống")
            return Incoming(out, uri, null, name, size)
        }
        val dir = File(context.getExternalFilesDir(null), "LeoConnect").apply { mkdirs() }
        val file = File(dir, name)
        return Incoming(file.outputStream(), null, file, name, size)
    }

    private fun discard(context: Context, item: Incoming) {
        try {
            item.out.close()
        } catch (_: Exception) {
        }
        try {
            if (item.uri != null) context.contentResolver.delete(item.uri, null, null)
            item.file?.delete()
        } catch (_: Exception) {
        }
    }

    private fun finish(context: Context, item: Incoming) {
        try {
            item.out.close()
            if (item.uri != null && Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.update(
                    item.uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
        } catch (e: Exception) {
            say(context, "Không lưu được file ${item.name}")
            return
        }
        val where = if (item.uri != null) "Tải xuống/LeoConnect" else item.file?.parent ?: ""
        say(context, "Đã nhận file ${item.name} (lưu ở $where)")
        notifyReceived(context, item)
    }

    private fun notifyReceived(context: Context, item: Incoming) {
        val uri = item.uri ?: return
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("files", "File đã nhận", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            val open = PendingIntent.getActivity(
                context, item.name.hashCode(), view,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = android.app.Notification.Builder(context, "files")
                .setSmallIcon(Icon.createWithResource(context, android.R.drawable.stat_sys_download_done))
                .setContentTitle("Đã nhận file từ máy kia")
                .setContentText(item.name)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(item.name.hashCode(), n)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ bộ nhớ tạm

    /**
     * Gửi nội dung vừa sao chép sang máy kia. Android chỉ cho đọc bộ nhớ tạm khi app đang được chọn
     * (cửa sổ đang nhận phím), nên gọi hàm này lúc người dùng chạm/gõ vào cửa sổ.
     */
    fun pushClipboard(context: Context) {
        if (sender == null) return
        try {
            val cm = context.getSystemService(ClipboardManager::class.java) ?: return
            // Xem mốc thời gian trước, chỉ đọc nội dung khi có lần sao chép mới
            val stamp = cm.primaryClipDescription?.timestamp ?: return
            if (stamp == lastClipStamp) return
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                ?: return
            lastClipStamp = stamp
            sendText(text)
        } catch (_: Exception) {
        }
    }

    /** Chép một đoạn chữ sang bộ nhớ tạm của máy kia. */
    fun sendText(text: String) {
        val send = sender ?: return
        if (text.isEmpty() || text.length > MAX_CLIP || text == lastClip) return
        lastClip = text
        io.execute {
            try {
                send(Proto.CLIPBOARD, text.toByteArray(Charsets.UTF_8))
            } catch (_: Exception) {
            }
        }
    }
}
