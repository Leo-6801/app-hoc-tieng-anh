package com.leo.connect

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** Tự cập nhật: xem bản mới nhất trên GitHub, tải APK và đưa cho Android cài. */
object Updater {
    class Info(val number: Int, val version: String, val url: String)

    private const val RELEASES = "https://api.github.com/repos/Leo-6801/app-hoc-tieng-anh/releases?per_page=20"
    private const val TAG_PREFIX = "leo-connect-v"

    /** Bản mới nhất đã tìm thấy (null = chưa kiểm tra hoặc không có). */
    @Volatile
    var latest: Info? = null
        private set

    /** Dòng trạng thái để hiện trên giao diện. */
    @Volatile
    var status = ""

    @Volatile
    var busy = false
        private set

    fun currentNumber(context: Context): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    } catch (_: Exception) {
        0
    }

    fun hasUpdate(context: Context): Boolean = (latest?.number ?: 0) > currentNumber(context)

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "LeoConnect")
        return c
    }

    /** Hỏi GitHub bản mới nhất (gọi ở luồng nền). Lỗi mạng thì ném ngoại lệ. */
    fun check(): Info? {
        val c = open(RELEASES)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) throw java.io.IOException("GitHub trả về mã ${c.responseCode}")
            val list = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            var best: Info? = null
            for (i in 0 until list.length()) {
                val release = list.getJSONObject(i)
                val tag = release.optString("tag_name")
                if (!tag.startsWith(TAG_PREFIX)) continue
                val version = tag.removePrefix(TAG_PREFIX)
                val number = version.substringAfterLast('.').toIntOrNull() ?: continue
                val assets = release.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    if (!asset.optString("name").endsWith(".apk")) continue
                    if (best == null || number > best.number) {
                        best = Info(number, version, asset.getString("browser_download_url"))
                    }
                }
            }
            latest = best
            return best
        } finally {
            c.disconnect()
        }
    }

    /** Tải APK thẳng vào phiên cài đặt của Android rồi yêu cầu cài (gọi ở luồng nền). */
    fun downloadAndInstall(context: Context, info: Info) {
        if (busy) return
        busy = true
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        var sessionId = -1
        try {
            status = "Đang tải bản ${info.version}…"
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            // Từ lần cập nhật thứ hai trở đi, Android có thể cài luôn không hỏi lại
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            val c = open(info.url)
            try {
                if (c.responseCode != 200) throw java.io.IOException("tải về lỗi mã ${c.responseCode}")
                val length = c.contentLengthLong
                session.openWrite("leo-connect.apk", 0, length).use { out ->
                    c.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        var shown = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            val now = System.currentTimeMillis()
                            if (now - shown > 300) {
                                shown = now
                                status = "Đang tải bản ${info.version}… " +
                                    if (length > 0) "${total * 100 / length}%" else "${total / 1024} KB"
                            }
                        }
                        session.fsync(out)
                    }
                }
            } finally {
                c.disconnect()
            }
            status = "Đã tải xong. Nếu Android hỏi, bấm Cập nhật / Cài đặt. App sẽ tự đóng, mở lại là xong."
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(app, sessionId, Intent(app, UpdateReceiver::class.java), flags)
            session.commit(pending.intentSender)
            session.close()
        } catch (e: Exception) {
            status = "Cập nhật không thành công: ${e.message ?: e.javaClass.simpleName}"
            try {
                if (sessionId != -1) installer.abandonSession(sessionId)
            } catch (_: Exception) {
            }
        } finally {
            busy = false
        }
    }
}

/** Nhận kết quả cài đặt; khi Android cần người dùng xác nhận thì mở màn hình xác nhận. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Updater.status = "Không mở được màn hình cài đặt: ${e.message}"
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.status = "Đã cập nhật xong."
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Updater.status = "Cài bản mới không thành công. $message"
            }
        }
    }
}
