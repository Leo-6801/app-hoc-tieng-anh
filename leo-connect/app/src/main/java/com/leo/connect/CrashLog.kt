package com.leo.connect

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ghi lại lỗi gần nhất vào bộ nhớ của app để hiện trên màn hình chính (giúp tìm nguyên nhân). */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last_error.txt")

    fun record(context: Context, error: Throwable, where: String = "") {
        try {
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            val time = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.US).format(Date())
            val header = "Leo Connect ${BuildConfigInfo.version(context)} – Android ${Build.VERSION.RELEASE} – $time" +
                (if (where.isNotEmpty()) " – $where" else "")
            file(context).writeText(header + "\n" + sw.toString().take(6000))
        } catch (_: Exception) {
        }
    }

    fun read(context: Context): String? = try {
        file(context).takeIf { it.exists() }?.readText()
    } catch (_: Exception) {
        null
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (_: Exception) {
        }
    }
}

object BuildConfigInfo {
    fun version(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }
}

/** Bắt mọi lỗi làm văng app và ghi lại trước khi app đóng. */
class LeoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            CrashLog.record(this, error, "luồng ${thread.name}")
            previous?.uncaughtException(thread, error)
        }
    }
}
