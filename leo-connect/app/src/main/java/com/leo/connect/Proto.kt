package com.leo.connect

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Giao thức Leo Connect (qua TCP trong mạng Wi-Fi nội bộ).
 * Mỗi gói: [loại: 1 byte][độ dài: 4 byte][dữ liệu]. Sau bước bắt tay, dữ liệu được mã hóa AES-GCM.
 */
object Proto {
    const val PORT = 47800
    const val DISCOVERY_PORT = 47801
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
    const val TEXT = 25 // chữ gõ từ bàn phím (UTF-8)
    const val CAPS = 26 // khả năng giải mã của máy tính bảng: [bit 0 = H.265]
    const val REPLACE = 27 // thay chữ trước con trỏ: [số ký tự xóa: int][chữ chèn UTF-8]

    fun encodeReplace(deleteCount: Int, text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(4 + bytes.size).putInt(deleteCount).put(bytes).array()
    }

    const val CODEC_AVC = 0
    const val CODEC_HEVC = 1
    const val CAPS_HEVC = 1

    fun mime(codec: Int): String =
        if (codec == CODEC_HEVC) android.media.MediaFormat.MIMETYPE_VIDEO_HEVC else android.media.MediaFormat.MIMETYPE_VIDEO_AVC

    /** Máy có bộ mã hóa (encoder=true) hoặc giải mã phần cứng/phần mềm cho [mime] không. */
    fun hasCodec(mime: String, encoder: Boolean): Boolean = try {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder == encoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    } catch (e: Exception) {
        false
    }

    // Đo độ trễ
    const val PING = 30
    const val PONG = 31
    const val P2P_INFO = 33 // điện thoại gửi tên + mật khẩu nhóm Wi-Fi Direct: "tên\nmật khẩu"
    const val UDP_HELLO = 34 // máy tính bảng báo cổng UDP nhận hình: [cổng: int]
    const val NACK = 36 // máy tính bảng xin gửi lại gói hình bị mất: [mã khung: int][số gói: short][thứ tự gói: short...]; 0 gói = cả khung
    const val UDP_OFF = 35 // UDP không tới được: quay về gửi hình qua TCP
    const val CONGESTION = 32 // máy tính bảng báo hình đang trễ: điện thoại hạ bitrate ngay

    const val TOUCH_DOWN = 0
    const val TOUCH_MOVE = 1
    const val TOUCH_UP = 2

    /** Mức chất lượng: cạnh dài tối đa (px), bitrate, khung hình/giây. */
    class Quality(val label: String, val maxSide: Int, val bitrate: Int, val fps: Int)

    val QUALITIES = listOf(
        Quality("Mượt", 1024, 2_000_000, 30),
        // Dùng khi điều khiển qua Bluetooth: Bluetooth chiếm bớt sóng Wi-Fi nên cần luồng hình nhẹ hơn
        Quality("Cân bằng", 1600, 4_000_000, 30),
        // Wi-Fi nội bộ: độ phân giải gốc của điện thoại, 60 khung hình/giây
        Quality("Nét", 1920, 8_000_000, 60),
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
    const val KEY_BACKSPACE = 6
    const val KEY_ENTER = 7
    const val KEY_NEWLINE = 8 // Shift+Enter: xuống dòng

    /** Khóa bí mật suy ra từ mã PIN ghép đôi. */
    fun keyFromPin(pin: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("leo-connect/v2/$pin".toByteArray())

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    /**
     * Bằng chứng biết mã PIN, gắn với toàn bộ nội dung bắt tay (nonce + 2 khóa công khai) và vai trò,
     * nên không dùng lại được và kẻ đứng giữa không thay khóa được nếu không biết PIN.
     */
    fun proof(pinKey: ByteArray, transcript: ByteArray, role: String): ByteArray =
        hmac(pinKey, transcript, role.toByteArray())

    // --- Trao đổi khóa ECDH: người nghe lén trong Wi-Fi không tính được khóa phiên

    fun newKeyPair(): java.security.KeyPair {
        val gen = java.security.KeyPairGenerator.getInstance("EC")
        gen.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        return gen.generateKeyPair()
    }

    fun sharedSecret(privateKey: java.security.PrivateKey, peerPublic: ByteArray): ByteArray {
        val pub = java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(peerPublic))
        val ka = javax.crypto.KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }

    /** Khóa phiên cho từng chiều: (máy tính bảng → điện thoại, điện thoại → máy tính bảng). */
    fun sessionKeys(shared: ByteArray, pinKey: ByteArray, transcript: ByteArray): Pair<ByteArray, ByteArray> {
        val master = hmac(shared, pinKey, transcript)
        return hmac(master, "c2h".toByteArray()) to hmac(master, "h2c".toByteArray())
    }

    // --- Gửi hình qua UDP: mỗi khung hình chia thành nhiều gói nhỏ, mất gói thì bỏ khung đó,
    // không bắt cả luồng phải chờ như TCP. Mỗi gói mã hóa riêng bằng AES-GCM.
    const val UDP_CHUNK = 1200

    private fun udpNonce(counter: Long) = javax.crypto.spec.GCMParameterSpec(
        128, ByteBuffer.allocate(12).put(byteArrayOf(0x55, 0x44, 0x50, 0)).putLong(counter).array(),
    )

    /** Gói UDP = [bộ đếm: 8 byte][dữ liệu đã mã hóa]. */
    fun sealUdp(cipher: javax.crypto.Cipher, key: SecretKeySpec, counter: Long, plain: ByteArray): ByteArray {
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, udpNonce(counter))
        val body = cipher.doFinal(plain)
        return ByteBuffer.allocate(8 + body.size).putLong(counter).put(body).array()
    }

    /** Trả về null nếu gói sai khóa hoặc bị sửa. */
    fun openUdp(cipher: javax.crypto.Cipher, key: SecretKeySpec, packet: ByteArray, length: Int): ByteArray? {
        if (length < 8 + 16) return null
        return try {
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, udpNonce(ByteBuffer.wrap(packet, 0, 8).long))
            cipher.doFinal(packet, 8, length - 8)
        } catch (e: java.security.GeneralSecurityException) {
            null
        }
    }

    /** Địa chỉ trong mạng nội bộ (Wi-Fi nhà, điểm phát sóng): 10.x, 172.16–31.x, 192.168.x, 169.254.x. */
    fun isLocalNetwork(addr: InetAddress): Boolean =
        addr is Inet4Address && (addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isLoopbackAddress)

    /** Các địa chỉ IPv4 của máy này trong mạng nội bộ. */
    fun localIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    } catch (e: Exception) {
        emptyList()
    }

    fun encodeConfig(width: Int, height: Int, codec: Int): ByteArray =
        ByteBuffer.allocate(12).putInt(width).putInt(height).putInt(codec).array()

    /** (rộng, cao, chuẩn nén). Bản cũ không gửi chuẩn nén thì coi là H.264. */
    fun decodeConfig(data: ByteArray): Triple<Int, Int, Int> {
        val bb = ByteBuffer.wrap(data)
        val w = bb.int
        val h = bb.int
        val codec = if (data.size >= 12) bb.int else CODEC_AVC
        return Triple(w, h, codec)
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

    // Mã hóa AES-256-GCM, mỗi chiều một khóa và một bộ đếm gói (dùng làm nonce)
    private var sendKey: SecretKeySpec? = null
    private var recvKey: SecretKeySpec? = null
    private var sendCounter = 0L
    private var recvCounter = 0L
    private val sendCipher by lazy { javax.crypto.Cipher.getInstance("AES/GCM/NoPadding") }
    private val recvCipher by lazy { javax.crypto.Cipher.getInstance("AES/GCM/NoPadding") }

    /** Bật mã hóa cho mọi gói từ đây về sau. Gọi sau bước bắt tay, trên luồng đang đọc. */
    fun enableEncryption(send: ByteArray, recv: ByteArray) {
        synchronized(writeLock) { sendKey = SecretKeySpec(send, "AES") }
        recvKey = SecretKeySpec(recv, "AES")
    }

    private fun nonce(counter: Long): javax.crypto.spec.GCMParameterSpec =
        javax.crypto.spec.GCMParameterSpec(128, ByteBuffer.allocate(12).putInt(0).putLong(counter).array())

    fun send(type: Int, payload: ByteArray = ByteArray(0)) {
        synchronized(writeLock) {
            val key = sendKey
            val body = if (key == null) payload else {
                sendCipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, nonce(sendCounter++))
                sendCipher.updateAAD(byteArrayOf(type.toByte()))
                sendCipher.doFinal(payload)
            }
            output.writeByte(type)
            output.writeInt(body.size)
            output.write(body)
            output.flush()
        }
    }

    fun receive(): Message {
        val type = input.readUnsignedByte()
        val length = input.readInt()
        if (length < 0 || length > Proto.MAX_PAYLOAD) throw IOException("Độ dài gói không hợp lệ: $length")
        val data = ByteArray(length)
        input.readFully(data)
        val key = recvKey ?: return Message(type, data)
        try {
            recvCipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, nonce(recvCounter++))
            recvCipher.updateAAD(byteArrayOf(type.toByte()))
            return Message(type, recvCipher.doFinal(data))
        } catch (e: java.security.GeneralSecurityException) {
            throw IOException("Gói dữ liệu không hợp lệ (sai khóa hoặc bị sửa)")
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}
