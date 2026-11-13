package com.leo.connect.controller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.view.KeyEvent
import java.util.concurrent.Executors

/**
 * THỬ NGHIỆM: máy tính bảng đóng vai thiết bị nhập Bluetooth (HID) của điện thoại:
 * một màn hình cảm ứng (tọa độ tuyệt đối), một bàn phím và các phím điều hướng.
 * Điện thoại chỉ cần ghép đôi Bluetooth, không cần quyền Trợ năng.
 */
@SuppressLint("MissingPermission")
class BluetoothHid(
    private val context: Context,
    private val targetAddress: String,
    private val onStatus: (String) -> Unit,
) {
    companion object {
        private const val ID_TOUCH = 1
        private const val ID_KEYBOARD = 2
        private const val ID_CONSUMER = 3

        const val CONSUMER_HOME = 0x223
        const val CONSUMER_BACK = 0x224
        const val CONSUMER_RECENTS = 0x1A2

        const val KEY_ENTER = 0x28
        const val KEY_BACKSPACE = 0x2A
        const val MOD_SHIFT = 0x02

        private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        private val DESCRIPTOR = bytes(
            // Màn hình cảm ứng 1 điểm chạm, tọa độ tuyệt đối 0..32767
            0x05, 0x0D, 0x09, 0x04, 0xA1, 0x01, 0x85, ID_TOUCH,
            0x09, 0x22, 0xA1, 0x00,
            0x09, 0x42, 0x09, 0x32, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x02, 0x81, 0x02,
            0x75, 0x06, 0x95, 0x01, 0x81, 0x03,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x16, 0x00, 0x00, 0x26, 0xFF, 0x7F,
            0x75, 0x10, 0x95, 0x02, 0x81, 0x02,
            0xC0, 0xC0,
            // Bàn phím: 8 phím bổ trợ + 6 phím
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, ID_KEYBOARD,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0,
            // Phím điều hướng (Quay lại, Màn hình chính, Đa nhiệm)
            0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, ID_CONSUMER,
            0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
            0xC0,
        )

        /** Mã phím Android → mã phím HID (0 nếu không hỗ trợ). */
        fun usageOf(keyCode: Int): Int = when (keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 0x04 + (keyCode - KeyEvent.KEYCODE_A)
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 0x1E + (keyCode - KeyEvent.KEYCODE_1)
            KeyEvent.KEYCODE_0 -> 0x27
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> KEY_ENTER
            KeyEvent.KEYCODE_ESCAPE -> 0x29
            KeyEvent.KEYCODE_DEL -> KEY_BACKSPACE
            KeyEvent.KEYCODE_TAB -> 0x2B
            KeyEvent.KEYCODE_SPACE -> 0x2C
            KeyEvent.KEYCODE_MINUS -> 0x2D
            KeyEvent.KEYCODE_EQUALS -> 0x2E
            KeyEvent.KEYCODE_LEFT_BRACKET -> 0x2F
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x30
            KeyEvent.KEYCODE_BACKSLASH -> 0x31
            KeyEvent.KEYCODE_SEMICOLON -> 0x33
            KeyEvent.KEYCODE_APOSTROPHE -> 0x34
            KeyEvent.KEYCODE_GRAVE -> 0x35
            KeyEvent.KEYCODE_COMMA -> 0x36
            KeyEvent.KEYCODE_PERIOD -> 0x37
            KeyEvent.KEYCODE_SLASH -> 0x38
            KeyEvent.KEYCODE_FORWARD_DEL -> 0x4C
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0x4F
            KeyEvent.KEYCODE_DPAD_LEFT -> 0x50
            KeyEvent.KEYCODE_DPAD_DOWN -> 0x51
            KeyEvent.KEYCODE_DPAD_UP -> 0x52
            else -> 0
        }

        fun modifiersOf(event: KeyEvent): Int {
            var m = 0
            if (event.isCtrlPressed) m = m or 0x01
            if (event.isShiftPressed) m = m or 0x02
            if (event.isAltPressed) m = m or 0x04
            if (event.isMetaPressed) m = m or 0x08
            return m
        }
    }

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val worker = Executors.newSingleThreadExecutor()
    private var hid: BluetoothHidDevice? = null

    @Volatile
    private var host: BluetoothDevice? = null

    @Volatile
    var connected = false
        private set

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (!registered) return
            onStatus("Bluetooth: đang chờ điện thoại kết nối…")
            // Chủ động nối tới điện thoại đã chọn (nếu đã ghép đôi)
            try {
                val target = adapter?.bondedDevices?.firstOrNull { it.address == targetAddress }
                if (target != null) hid?.connect(target)
            } catch (_: Exception) {
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED && device != null) {
                host = device
                connected = true
                onStatus("Bluetooth: đã kết nối, đang điều khiển qua Bluetooth")
            } else if (state == BluetoothProfile.STATE_DISCONNECTED && device == host) {
                host = null
                connected = false
                onStatus("Bluetooth: mất kết nối, quay về điều khiển qua Wi-Fi")
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            val d = device ?: return
            try {
                hid?.reportError(d, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
            } catch (_: Exception) {
            }
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            val d = device ?: return
            try {
                hid?.reportError(d, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            } catch (_: Exception) {
            }
        }
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            val device = proxy as? BluetoothHidDevice ?: return
            hid = device
            try {
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Leo Connect", "Điều khiển điện thoại", "Leo", BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR,
                )
                if (!device.registerApp(sdp, null, null, worker, callback)) {
                    onStatus("Bluetooth: máy tính bảng không cho đăng ký làm thiết bị nhập")
                }
            } catch (e: Exception) {
                onStatus("Bluetooth: lỗi đăng ký (${e.message})")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            connected = false
        }
    }

    fun start() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            onStatus("Bluetooth: hãy bật Bluetooth trên máy tính bảng")
            return
        }
        try {
            if (!a.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE)) {
                onStatus("Bluetooth: máy tính bảng này không hỗ trợ đóng vai thiết bị nhập")
            }
        } catch (e: Exception) {
            onStatus("Bluetooth: thiếu quyền hoặc không hỗ trợ (${e.message})")
        }
    }

    fun stop() {
        connected = false
        try {
            host?.let { hid?.disconnect(it) }
            hid?.unregisterApp()
            hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        } catch (_: Exception) {
        }
        hid = null
        worker.shutdownNow()
    }

    private fun send(id: Int, data: ByteArray) {
        val h = host ?: return
        try {
            hid?.sendReport(h, id, data)
        } catch (_: Exception) {
        }
    }

    private fun touchReport(down: Boolean, x: Float, y: Float) {
        val px = (x.coerceIn(0f, 1f) * 32767).toInt()
        val py = (y.coerceIn(0f, 1f) * 32767).toInt()
        send(ID_TOUCH, bytes(if (down) 0x03 else 0x00, px and 0xFF, px shr 8, py and 0xFF, py shr 8))
    }

    private fun run(block: () -> Unit) {
        try {
            worker.execute(block)
        } catch (_: Exception) {
        }
    }

    /** Chạm trực tiếp theo ngón tay. Tọa độ chuẩn hóa 0..1 theo màn hình điện thoại (chiều dọc). */
    fun touch(down: Boolean, x: Float, y: Float) = run { touchReport(down, x, y) }

    /** Phát lại một cú chạm/vuốt đã ghi: đi qua các điểm trong [durationMs]. */
    fun gesture(durationMs: Int, points: List<FloatArray>) = run {
        if (points.isEmpty()) return@run
        val step = (durationMs.toLong() / points.size).coerceIn(8L, 50L)
        try {
            for (p in points) {
                touchReport(true, p[0], p[1])
                Thread.sleep(step)
            }
            if (points.size == 1) Thread.sleep(durationMs.toLong().coerceIn(30L, 1500L))
        } catch (_: InterruptedException) {
        }
        val last = points.last()
        touchReport(false, last[0], last[1])
    }

    /** Nhấn rồi nhả một phím. */
    fun key(usage: Int, modifiers: Int = 0) = run {
        if (usage == 0) return@run
        send(ID_KEYBOARD, bytes(modifiers, 0, usage, 0, 0, 0, 0, 0))
        send(ID_KEYBOARD, ByteArray(8))
    }

    fun consumer(usage: Int) = run {
        send(ID_CONSUMER, bytes(usage and 0xFF, usage shr 8))
        send(ID_CONSUMER, ByteArray(2))
    }
}
