package com.leo.connect.controller

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Surface
import com.leo.connect.Channel
import com.leo.connect.Notif
import com.leo.connect.Proto
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Máy tính bảng: kết nối tới điện thoại qua Tailscale, hiển thị màn hình điện thoại
 * trong cửa sổ nổi và gửi thao tác chạm/phím. Tự kết nối lại khi rớt mạng.
 */
class ControllerService : Service(), PhoneWindow.Callbacks {

    companion object {
        const val ACTION_CONNECT = "com.leo.connect.CONNECT"
        const val ACTION_DISCONNECT = "com.leo.connect.DISCONNECT"
        const val EXTRA_HOST = "host"
        const val EXTRA_PIN = "pin"
        const val EXTRA_NAME = "name"
        private const val NOTIF_ID = 2

        @Volatile
        var running = false
            private set

        @Volatile
        var connected = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()
    private var window: PhoneWindow? = null

    @Volatile
    private var active = false

    @Volatile
    private var channel: Channel? = null
    private var netThread: Thread? = null

    // Trạng thái video (truy cập trong khóa decoderLock)
    private val decoderLock = Any()
    private var videoWidth = 0
    private var videoHeight = 0
    private var codecConfig: ByteArray? = null
    private var surface: Surface? = null
    private var decoder: VideoDecoder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> stopSelf()
            ACTION_CONNECT -> connect(intent)
        }
        return START_NOT_STICKY
    }

    private fun connect(intent: Intent) {
        Notif.ensureChannels(this)
        val stop = Intent(this, ControllerService::class.java).setAction(ACTION_DISCONNECT)
        val n = Notif.build(this, Notif.CHANNEL_CONTROLLER, "Leo Connect", "Đang điều khiển điện thoại", "Ngắt kết nối", stop)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (running) {
            window?.restore()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val host = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty()
        val pin = intent.getStringExtra(EXTRA_PIN)?.trim().orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() } ?: host
        if (host.isEmpty() || pin.isEmpty()) {
            stopSelf()
            return
        }
        running = true
        active = true
        window = PhoneWindow(this, name, this).also { it.show() }
        val key = Proto.keyFromPin(pin)
        netThread = thread(name = "leo-net") { netLoop(host, key) }
    }

    private fun ui(block: PhoneWindow.() -> Unit) {
        main.post { window?.block() }
    }

    private fun netLoop(host: String, key: ByteArray) {
        while (active) {
            ui { setStatus("Đang kết nối tới $host…") }
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(host, Proto.PORT), 8_000)
                socket.tcpNoDelay = true
                socket.soTimeout = 15_000
                val ch = Channel(socket)
                val challenge = ch.receive()
                if (challenge.type != Proto.CHALLENGE) throw IOException("Điện thoại trả lời không đúng giao thức")
                ch.send(Proto.AUTH, Proto.proof(key, challenge.data, "controller"))
                val reply = ch.receive()
                if (reply.type == Proto.AUTH_FAIL) {
                    ui { setStatus("Sai mã ghép đôi (PIN).\nKiểm tra lại mã trên điện thoại rồi kết nối lại.") }
                    active = false
                    ch.close()
                    break
                }
                val expected = Proto.proof(key, challenge.data, "host")
                if (reply.type != Proto.AUTH_OK || reply.data.size < 32 ||
                    !MessageDigest.isEqual(reply.data.copyOfRange(0, 32), expected)
                ) throw IOException("Không xác thực được điện thoại")
                val phoneName = String(reply.data, 32, reply.data.size - 32)
                socket.soTimeout = 0
                channel = ch
                connected = true
                ui {
                    if (phoneName.isNotBlank()) setTitle(phoneName)
                    setStatus("Đang chờ hình ảnh…")
                }
                while (active) {
                    val m = ch.receive()
                    when (m.type) {
                        Proto.VIDEO_CONFIG -> onVideoConfig(m.data)
                        Proto.VIDEO_FRAME -> onVideoFrame(m.data)
                    }
                }
            } catch (e: Exception) {
                if (active) {
                    val reason = when {
                        e is java.net.SocketTimeoutException -> "Không phản hồi"
                        e is java.net.ConnectException -> "Điện thoại chưa bật chế độ sẵn sàng hoặc Tailscale chưa kết nối"
                        else -> e.message ?: "Lỗi mạng"
                    }
                    ui { setStatus("Mất kết nối: $reason\nThử lại sau 3 giây…") }
                }
            } finally {
                connected = false
                channel = null
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                synchronized(decoderLock) { releaseDecoderLocked() }
            }
            if (!active) break
            try {
                Thread.sleep(3_000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun onVideoConfig(data: ByteArray) {
        val (w, h) = Proto.decodeConfig(data)
        synchronized(decoderLock) {
            videoWidth = w
            videoHeight = h
            codecConfig = null
            releaseDecoderLocked()
            createDecoderLocked()
        }
        ui {
            setVideoSize(w, h)
            setStatus(null)
        }
    }

    private fun onVideoFrame(data: ByteArray) {
        if (data.size < 9) return
        val bb = ByteBuffer.wrap(data)
        val flags = bb.get().toInt()
        val pts = bb.long
        val payload = data.copyOfRange(9, data.size)
        synchronized(decoderLock) {
            if ((flags and Proto.FLAG_CONFIG) != 0) codecConfig = payload
            decoder?.feed(payload, flags, pts)
        }
    }

    private fun createDecoderLocked() {
        val s = surface ?: return
        if (decoder != null || videoWidth <= 0 || videoHeight <= 0) return
        decoder = try {
            VideoDecoder(s, videoWidth, videoHeight, codecConfig)
        } catch (e: Exception) {
            null
        }
        send(Proto.REQUEST_KEYFRAME)
    }

    private fun releaseDecoderLocked() {
        decoder?.release()
        decoder = null
    }

    private fun send(type: Int, payload: ByteArray = ByteArray(0)) {
        val ch = channel ?: return
        sender.execute {
            try {
                ch.send(type, payload)
            } catch (_: Exception) {
                ch.close()
            }
        }
    }

    // ------------------------------------------------------------ PhoneWindow.Callbacks

    override fun onSurface(surface: Surface?) {
        synchronized(decoderLock) {
            if (surface === this.surface && decoder != null) return
            releaseDecoderLocked()
            this.surface = surface
            createDecoderLocked()
        }
    }

    override fun onGesture(durationMs: Int, points: List<FloatArray>) {
        send(Proto.GESTURE, Proto.encodeGesture(durationMs, points))
    }

    override fun onKey(key: Int) {
        send(Proto.KEY, byteArrayOf(key.toByte()))
    }

    override fun onClose() {
        stopSelf()
    }

    override fun onDestroy() {
        active = false
        running = false
        connected = false
        channel?.close()
        netThread?.interrupt()
        synchronized(decoderLock) {
            releaseDecoderLocked()
            surface = null
        }
        window?.dismiss()
        window = null
        sender.shutdownNow()
        super.onDestroy()
    }
}
