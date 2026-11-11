package com.leo.connect.host

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.leo.connect.Channel
import com.leo.connect.Notif
import com.leo.connect.Prefs
import com.leo.connect.Proto
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * Điện thoại: giữ quyền chụp màn hình, chờ máy tính bảng kết nối qua Tailscale,
 * xác thực bằng mã PIN, gửi video màn hình và nhận lệnh điều khiển.
 */
class HostService : Service() {

    companion object {
        const val ACTION_START = "com.leo.connect.HOST_START"
        const val ACTION_STOP = "com.leo.connect.HOST_STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 1
        private const val TAG = "LeoConnect"

        @Volatile
        var running = false
            private set

        @Volatile
        var connectedFrom: String? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val videoThread = HandlerThread("leo-video").apply { start() }
    private val video = Handler(videoThread.looper)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var serverSocket: ServerSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var session: Session? = null
    private var failures = 0
    private var lockedUntil = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) session?.onDisplayChanged()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_START -> start(intent)
        }
        return START_NOT_STICKY
    }

    private fun stopIntent() = Intent(this, HostService::class.java).setAction(ACTION_STOP)

    private fun notification(title: String, text: String) =
        Notif.build(this, Notif.CHANNEL_HOST, title, text, "Tắt", stopIntent())

    private fun start(intent: Intent) {
        Notif.ensureChannels(this)
        val n = notification("Leo Connect đang sẵn sàng", "Máy tính bảng có thể kết nối qua Tailscale")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (running) return

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return
        }
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        if (mp == null) {
            stopSelf()
            return
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)

        val size = captureSize(Proto.QUALITIES[Proto.DEFAULT_QUALITY].maxSide)
        // Android 14+: mỗi lần cấp quyền chỉ tạo được MỘT VirtualDisplay, nên giữ nó suốt phiên
        // và chỉ đổi Surface/kích thước khi có máy kết nối hoặc khi xoay màn hình.
        virtualDisplay = mp.createVirtualDisplay(
            "LeoConnect", size.width, size.height, size.dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, video,
        )
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, video)
        running = true
        thread(name = "leo-server") { serve() }
    }

    private data class CaptureSize(val width: Int, val height: Int, val dpi: Int)

    private fun captureSize(maxSide: Int): CaptureSize {
        val metrics = DisplayMetrics()
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val scale = min(1f, maxSide.toFloat() / max(w, h))
        // Bộ nén video cần kích thước chia hết cho 16
        val sw = max(16, ((w * scale).toInt() / 16) * 16)
        val sh = max(16, ((h * scale).toInt() / 16) * 16)
        return CaptureSize(sw, sh, max(120, (metrics.densityDpi * scale).toInt()))
    }

    private fun serve() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(Proto.PORT))
            serverSocket = ss
            while (running) {
                val socket = ss.accept()
                thread(name = "leo-client") { handleClient(socket) }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "Máy chủ dừng: ${e.message}")
        }
    }

    private fun handleClient(socket: Socket) {
        val remote = socket.inetAddress
        // Chỉ nhận kết nối từ mạng Tailscale (đã mã hóa, chỉ thiết bị của bạn).
        if (!(Proto.isTailscale(remote) || remote.isLoopbackAddress) ||
            System.currentTimeMillis() < lockedUntil || session != null
        ) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            return
        }
        val channel = Channel(socket)
        var mySession: Session? = null
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 15_000
            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            channel.send(Proto.CHALLENGE, nonce)
            val auth = channel.receive()
            val key = Proto.keyFromPin(Prefs(this).pin)
            val ok = auth.type == Proto.AUTH &&
                MessageDigest.isEqual(auth.data, Proto.proof(key, nonce, "controller"))
            if (!ok) {
                synchronized(this) {
                    failures++
                    if (failures >= 5) {
                        lockedUntil = System.currentTimeMillis() + 60_000
                        failures = 0
                    }
                }
                channel.send(Proto.AUTH_FAIL)
                return
            }
            failures = 0
            val name = (Build.MANUFACTURER + " " + Build.MODEL).trim().toByteArray()
            channel.send(Proto.AUTH_OK, Proto.proof(key, nonce, "host") + name)
            socket.soTimeout = 0

            val s = Session(channel)
            synchronized(this) {
                if (session != null) return
                session = s
            }
            mySession = s
            connectedFrom = remote.hostAddress
            main.post { onSessionStart() }
            s.start()
            s.readLoop()
        } catch (e: Exception) {
            Log.i(TAG, "Kết nối kết thúc: ${e.message}")
        } finally {
            channel.close()
            if (mySession != null) {
                mySession.stop()
                synchronized(this) { if (session === mySession) session = null }
                connectedFrom = null
                main.post { onSessionEnd() }
            }
        }
    }

    private fun onSessionStart() {
        Notif.update(
            this, NOTIF_ID,
            notification("Máy tính bảng đang điều khiển điện thoại", "Từ ${connectedFrom ?: ""} – bấm Tắt để ngắt ngay"),
        )
        // Giữ màn hình sáng trong lúc bị điều khiển (màn hình tắt thì không chụp được).
        @Suppress("DEPRECATION")
        val wl = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "LeoConnect:session",
        )
        try {
            wl.acquire(4 * 60 * 60 * 1000L)
            wakeLock = wl
        } catch (_: Exception) {
        }
    }

    private fun onSessionEnd() {
        if (running) {
            Notif.update(this, NOTIF_ID, notification("Leo Connect đang sẵn sàng", "Máy tính bảng có thể kết nối qua Tailscale"))
        }
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    /** Một phiên điều khiển với một máy tính bảng. */
    private inner class Session(val channel: Channel) {
        @Volatile
        private var encoder: ScreenEncoder? = null
        private var lastSize: CaptureSize? = null

        @Volatile
        private var quality = Proto.QUALITIES[Proto.DEFAULT_QUALITY]

        @Volatile
        private var closed = false

        // Hàng đợi khung hình gửi đi. Khi mạng chậm, bỏ bớt khung để hình không bị trễ dồn.
        private val outbox = LinkedBlockingQueue<Frame>()

        @Volatile
        private var waitingForKey = false
        private val senderThread = thread(name = "leo-send") { sendLoop() }

        private fun sendLoop() {
            try {
                while (!closed) {
                    val f = outbox.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    channel.send(f.type, f.payload)
                }
            } catch (e: Exception) {
                closed = true
                channel.close()
            }
        }

        private fun enqueueFrame(flags: Int, pts: Long, data: ByteArray) {
            val isConfig = (flags and Proto.FLAG_CONFIG) != 0
            val isKey = (flags and Proto.FLAG_KEY) != 0
            val payload = Proto.encodeFrame(flags, pts, data, 0, data.size)
            if (isConfig) {
                outbox.add(Frame(Proto.VIDEO_FRAME, payload, droppable = false))
                return
            }
            if (isKey) {
                // Khung khóa: bỏ các khung cũ còn đang chờ, hình nhảy ngay tới hiện tại.
                outbox.removeIf { it.droppable }
                waitingForKey = false
                outbox.add(Frame(Proto.VIDEO_FRAME, payload, droppable = true))
                return
            }
            if (waitingForKey) return
            if (outbox.count { it.droppable } >= 3) {
                // Mạng không theo kịp: bỏ khung, xin khung khóa mới để bắt kịp.
                outbox.removeIf { it.droppable }
                waitingForKey = true
                encoder?.requestKeyFrame()
                return
            }
            outbox.add(Frame(Proto.VIDEO_FRAME, payload, droppable = true))
        }

        fun start() {
            video.post { restartEncoder() }
        }

        fun onDisplayChanged() {
            video.post {
                val size = captureSize(quality.maxSide)
                if (!closed && size != lastSize) restartEncoder()
            }
        }

        /** Chạy trên luồng video. */
        private fun restartEncoder() {
            if (closed) return
            val vd = virtualDisplay ?: return
            val q = quality
            val size = captureSize(q.maxSide)
            vd.surface = null
            encoder?.stop()
            encoder = null
            try {
                outbox.removeIf { it.droppable }
                waitingForKey = false
                outbox.add(Frame(Proto.VIDEO_CONFIG, Proto.encodeConfig(size.width, size.height), droppable = false))
                val enc = ScreenEncoder(size.width, size.height, q.bitrate, q.fps) { flags, pts, data ->
                    if (!closed) enqueueFrame(flags, pts, data)
                }
                enc.start()
                vd.resize(size.width, size.height, size.dpi)
                vd.surface = enc.inputSurface
                encoder = enc
                lastSize = size
            } catch (e: Exception) {
                Log.w(TAG, "Không khởi động được bộ nén: ${e.message}")
                channel.close()
            }
        }

        fun readLoop() {
            while (!closed) {
                val m = channel.receive()
                when (m.type) {
                    Proto.GESTURE -> {
                        val (duration, points) = Proto.decodeGesture(m.data)
                        ControlAccessibilityService.instance?.performGesture(duration, points)
                    }
                    Proto.TOUCH -> if (m.data.size >= 9) {
                        val bb = java.nio.ByteBuffer.wrap(m.data)
                        val action = bb.get().toInt()
                        ControlAccessibilityService.instance?.liveTouch(action, bb.float, bb.float)
                    }
                    Proto.KEY -> if (m.data.isNotEmpty()) {
                        ControlAccessibilityService.instance?.performKey(m.data[0].toInt())
                    }
                    Proto.TEXT -> if (m.data.isNotEmpty()) {
                        ControlAccessibilityService.instance?.typeText(String(m.data, Charsets.UTF_8))
                    }
                    Proto.REQUEST_KEYFRAME -> encoder?.requestKeyFrame()
                    Proto.QUALITY -> if (m.data.isNotEmpty()) {
                        val level = m.data[0].toInt().coerceIn(0, Proto.QUALITIES.size - 1)
                        val q = Proto.QUALITIES[level]
                        if (q !== quality) {
                            quality = q
                            video.post { restartEncoder() }
                        }
                    }
                    Proto.PING -> outbox.add(Frame(Proto.PONG, m.data, droppable = false))
                }
            }
        }

        fun stop() {
            closed = true
            senderThread.interrupt()
            video.post {
                virtualDisplay?.surface = null
                encoder?.stop()
                encoder = null
            }
        }
    }

    override fun onDestroy() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        session?.channel?.close()
        try {
            getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        video.post {
            virtualDisplay?.release()
            virtualDisplay = null
            projection?.stop()
            projection = null
            videoThread.quitSafely()
        }
        onSessionEnd()
        super.onDestroy()
    }
}

/** Một gói chờ gửi; droppable = khung video có thể bỏ khi mạng chậm. */
private class Frame(val type: Int, val payload: ByteArray, val droppable: Boolean)

/** Tiện ích kiểm tra từ giao diện. */
fun Context.isIgnoringBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
