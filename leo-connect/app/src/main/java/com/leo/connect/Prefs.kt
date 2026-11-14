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

    private fun randomText(length: Int): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        return (1..length).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    /** Điện thoại: tên và mật khẩu nhóm Wi-Fi Direct (tạo một lần, giữ nguyên để máy tính bảng nhớ). */
    val p2pSsid: String
        get() = p.getString("p2p_ssid", null) ?: ("DIRECT-LC-" + randomText(6)).also {
            p.edit().putString("p2p_ssid", it).apply()
        }

    val p2pPassphrase: String
        get() = p.getString("p2p_pass", null) ?: randomText(20).also {
            p.edit().putString("p2p_pass", it).apply()
        }

    /** Máy tính bảng: nhóm Wi-Fi Direct của điện thoại đã ghép (nhận qua kết nối đã mã hóa). */
    var peerP2pSsid: String
        get() = p.getString("peer_p2p_ssid", "") ?: ""
        set(value) = p.edit().putString("peer_p2p_ssid", value).apply()

    var peerP2pPassphrase: String
        get() = p.getString("peer_p2p_pass", "") ?: ""
        set(value) = p.edit().putString("peer_p2p_pass", value).apply()

    /** Thử nghiệm: điều khiển qua Bluetooth thay cho Trợ năng. */
    var btEnabled: Boolean
        get() = p.getBoolean("bt_enabled", false)
        set(value) = p.edit().putBoolean("bt_enabled", value).apply()

    /** Địa chỉ Bluetooth của điện thoại đã chọn (có thể trống). */
    var btAddress: String
        get() = p.getString("bt_address", "") ?: ""
        set(value) = p.edit().putString("bt_address", value).apply()

    /** Bộ gõ Telex của app cho bàn phím rời (VI) hoặc gõ thường (EN). */
    var telex: Boolean
        get() = p.getBoolean("telex", true)
        set(value) = p.edit().putBoolean("telex", value).apply()

    /** Máy tính bảng: phát tiếng của điện thoại. */
    var audio: Boolean
        get() = p.getBoolean("audio", true)
        set(value) = p.edit().putBoolean("audio", value).apply()

    /** Mức chất lượng hình (chỉ số trong Proto.QUALITIES). */
    var quality: Int
        get() = p.getInt("quality", Proto.DEFAULT_QUALITY).coerceIn(0, Proto.QUALITIES.size - 1)
        set(value) = p.edit().putInt("quality", value).apply()
}
