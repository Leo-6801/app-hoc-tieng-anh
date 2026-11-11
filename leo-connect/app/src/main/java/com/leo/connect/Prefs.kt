package com.leo.connect

import android.content.Context
import java.security.SecureRandom
import java.util.Locale

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("leo_connect", Context.MODE_PRIVATE)

    /** "host" = điện thoại được điều khiển, "controller" = máy tính bảng điều khiển. */
    var role: String
        get() = p.getString("role", "") ?: ""
        set(value) = p.edit().putString("role", value).apply()

    /** Mã ghép đôi 6 số của điện thoại (tự tạo lần đầu). */
    val pin: String
        get() {
            val existing = p.getString("pin", null)
            if (existing != null) return existing
            return regeneratePin()
        }

    fun regeneratePin(): String {
        val value = String.format(Locale.US, "%06d", SecureRandom().nextInt(1_000_000))
        p.edit().putString("pin", value).apply()
        return value
    }

    // Máy tính bảng: thông tin điện thoại đã lưu
    var targetHost: String
        get() = p.getString("target_host", "") ?: ""
        set(value) = p.edit().putString("target_host", value).apply()

    var targetPin: String
        get() = p.getString("target_pin", "") ?: ""
        set(value) = p.edit().putString("target_pin", value).apply()

    var targetName: String
        get() = p.getString("target_name", "") ?: ""
        set(value) = p.edit().putString("target_name", value).apply()

    /** Mức chất lượng hình (chỉ số trong Proto.QUALITIES). */
    var quality: Int
        get() = p.getInt("quality", Proto.DEFAULT_QUALITY).coerceIn(0, Proto.QUALITIES.size - 1)
        set(value) = p.edit().putInt("quality", value).apply()
}
