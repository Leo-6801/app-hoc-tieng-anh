package com.leo.connect

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Giao thức Leo Connect (qua TCP, đi trong mạng Tailscale đã mã hóa).
 * Mỗi gói: [loại: 1 byte][độ dài: 4 byte][dữ liệu].
 */
object Proto {
    const val PORT = 47800
    const val MAX_PAYLOAD = 8 * 1024 * 1024

    // Bắt tay xác thực
    const val CHALLENGE = 1
    const val AUTH = 2
    const val AUTH_OK = 3
    const val AUTH_FAIL = 4

    // Video: điện thoại -> máy tính bảng
    const val VIDEO_CONFIG = 10
    const val VIDEO_FRAME = 11

    // Điều khiển: máy tính bảng -> điện thoại
    const val GESTURE = 20
    const val KEY = 21
    const val REQUEST_KEYFRAME = 22
    const val TOUCH = 23 // chạm trực tiếp theo ngón tay: [hành động][x][y]
    const val QUALITY = 24 // [mức chất lượng]

    // Đo độ trễ
    const val PING = 30
    const val PONG = 31

    const val TOUCH_DOWN = 0
    const val TOUCH_MOVE = 1
    const val TOUCH_UP = 2

    /** Mức chất lượng: cạnh dài tối đa (px), bitrate, khung hình/giây. */
    class Quality(val label: String, val maxSide: Int, val bitrate: Int, val fps: Int)

    val QUALITIES = listOf(
        Quality("Mượt", 1024, 2_000_000, 30),
        Quality("Cân bằng", 1280, 3_500_000, 30),
        Quality("Nét", 1600, 6_000_000, 30),
    )
    const val DEFAULT_QUALITY = 1

    fun encodeTouch(action: Int, x: Float, y: Float): ByteArray =
        ByteBuffer.allocate(9).put(action.toByte()).putFloat(x).putFloat(y).array()

    const val FLAG_CONFIG = 1
    const val FLAG_KEY = 2

    const val KEY_BACK = 1
    const val KEY_HOME = 2
    const val KEY_RECENTS = 3
    const val KEY_NOTIFICATIONS = 4
    const val KEY_LOCK = 5

    /** Khóa bí mật suy ra từ mã PIN ghép đôi. */
    fun keyFromPin(pin: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("leo-connect/v1/$pin".toByteArray())

    /** Bằng chứng biết mã PIN, gắn với nonce và vai trò để không dùng lại được. */
    fun proof(key: ByteArray, nonce: ByteArray, role: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(nonce)
        mac.update(role.toByteArray())
        return mac.doFinal()
    }

    /** Địa chỉ thuộc mạng Tailscale: 100.64.0.0/10 hoặc fd7a:115c:a1e0::/48. */
    fun isTailscale(addr: InetAddress): Boolean {
        val b = addr.address
        return when (addr) {
            is Inet4Address -> (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
            is Inet6Address -> b.size == 16 &&
                (b[0].toInt() and 0xFF) == 0xFD && (b[1].toInt() and 0xFF) == 0x7A &&
                (b[2].toInt() and 0xFF) == 0x11 && (b[3].toInt() and 0xFF) == 0x5C &&
                (b[4].toInt() and 0xFF) == 0xA1 && (b[5].toInt() and 0xFF) == 0xE0
            else -> false
        }
    }

    /** Các địa chỉ Tailscale (IPv4) của máy này. */
    fun localTailscaleIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && isTailscale(it) }
            .mapNotNull { it.hostAddress }
    } catch (e: Exception) {
        emptyList()
    }

    fun encodeConfig(width: Int, height: Int): ByteArray =
        ByteBuffer.allocate(8).putInt(width).putInt(height).array()

    fun decodeConfig(data: ByteArray): Pair<Int, Int> {
        val bb = ByteBuffer.wrap(data)
        return bb.int to bb.int
    }

    fun encodeFrame(flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int): ByteArray {
        val bb = ByteBuffer.allocate(9 + length)
        bb.put(flags.toByte())
        bb.putLong(ptsUs)
        bb.put(data, offset, length)
        return bb.array()
    }

    /** Mã hóa thao tác chạm: thời lượng (ms) + danh sách điểm (x, y) đã chuẩn hóa 0..1. */
    fun encodeGesture(durationMs: Int, points: List<FloatArray>): ByteArray {
        val bb = ByteBuffer.allocate(8 + points.size * 8)
        bb.putInt(durationMs)
        bb.putInt(points.size)
        for (p in points) {
            bb.putFloat(p[0])
            bb.putFloat(p[1])
        }
        return bb.array()
    }

    fun decodeGesture(data: ByteArray): Pair<Int, List<FloatArray>> {
        val bb = ByteBuffer.wrap(data)
        val duration = bb.int
        val count = bb.int
        if (count < 1 || count > 500 || data.size < 8 + count * 8) throw IOException("Gói chạm không hợp lệ")
        val points = ArrayList<FloatArray>(count)
        repeat(count) { points.add(floatArrayOf(bb.float, bb.float)) }
        return duration to points
    }
}

class Message(val type: Int, val data: ByteArray)

/** Một kết nối đã đóng khung gói. send() an toàn khi gọi từ nhiều luồng. */
class Channel(private val socket: Socket) : Closeable {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
    private val writeLock = Any()

    fun send(type: Int, payload: ByteArray = ByteArray(0)) {
        synchronized(writeLock) {
            output.writeByte(type)
            output.writeInt(payload.size)
            output.write(payload)
            output.flush()
        }
    }

    fun receive(): Message {
        val type = input.readUnsignedByte()
        val length = input.readInt()
        if (length < 0 || length > Proto.MAX_PAYLOAD) throw IOException("Độ dài gói không hợp lệ: $length")
        val data = ByteArray(length)
        input.readFully(data)
        return Message(type, data)
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}
