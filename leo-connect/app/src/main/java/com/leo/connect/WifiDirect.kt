package com.leo.connect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Wi-Fi Direct: hai máy tự nối thẳng với nhau ở băng tần 5 GHz, không qua router.
 * Điện thoại mở một nhóm (tên + mật khẩu ngẫu nhiên, lưu lại để dùng lần sau); máy tính bảng nhận
 * tên và mật khẩu qua kết nối đã mã hóa rồi tự vào nhóm, không cần bấm xác nhận.
 */
@SuppressLint("MissingPermission")
class WifiDirectHost(
    private val context: Context,
    val ssid: String,
    val passphrase: String,
    private val onStatus: (String) -> Unit,
) {
    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var ready = false
        private set

    fun start() {
        val m = manager
        if (Build.VERSION.SDK_INT < 29 || m == null) {
            onStatus("Wi-Fi Direct: máy này không hỗ trợ")
            return
        }
        try {
            val ch = m.initialize(context, Looper.getMainLooper(), null)
            if (ch == null) {
                onStatus("Wi-Fi Direct: không khởi động được")
                return
            }
            channel = ch
            onStatus("Wi-Fi Direct: đang mở đường nối thẳng 5 GHz…")
            // Xóa nhóm cũ (nếu còn) rồi tạo nhóm mới
            val next = Runnable { create(m, ch) }
            m.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    main.postDelayed(next, 700)
                }

                override fun onFailure(reason: Int) {
                    main.post(next)
                }
            })
        } catch (e: Exception) {
            onStatus("Wi-Fi Direct: thiếu quyền \"Thiết bị ở gần\" (${e.javaClass.simpleName})")
        }
    }

    private fun create(m: WifiP2pManager, ch: WifiP2pManager.Channel) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val config = WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(passphrase)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                .build()
            m.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    ready = true
                    onStatus("Wi-Fi Direct: đường nối thẳng 5 GHz đã sẵn sàng")
                }

                override fun onFailure(reason: Int) {
                    ready = false
                    onStatus("Wi-Fi Direct: điện thoại không mở được đường 5 GHz (mã $reason), dùng Wi-Fi thường")
                }
            })
        } catch (e: Exception) {
            onStatus("Wi-Fi Direct: thiếu quyền \"Thiết bị ở gần\" (${e.javaClass.simpleName})")
        }
    }

    fun stop() {
        ready = false
        try {
            channel?.let { manager?.removeGroup(it, null) }
        } catch (_: Exception) {
        }
    }
}

@SuppressLint("MissingPermission")
class WifiDirectClient(
    private val context: Context,
    private val onConnected: (ownerAddress: String) -> Unit,
    private val onLost: () -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private var joinedSsid = ""
    private var connected = false
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) check()
        }
    }

    private fun ensureChannel(): WifiP2pManager.Channel? {
        val m = manager ?: return null
        channel?.let { return it }
        val ch = m.initialize(context, Looper.getMainLooper(), null) ?: return null
        channel = ch
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        registered = true
        return ch
    }

    private fun check() {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            m.requestConnectionInfo(ch) { info ->
                val owner = info?.groupOwnerAddress?.hostAddress
                if (info != null && info.groupFormed && !info.isGroupOwner && owner != null) {
                    if (!connected) {
                        connected = true
                        onConnected(owner)
                    }
                } else if (connected) {
                    connected = false
                    joinedSsid = ""
                    onLost()
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Vào nhóm Wi-Fi Direct của điện thoại. Gọi lại nhiều lần với cùng tên thì bỏ qua. */
    fun join(ssid: String, passphrase: String) {
        if (Build.VERSION.SDK_INT < 29 || manager == null) return
        if (ssid.isEmpty() || (ssid == joinedSsid)) return
        try {
            val ch = ensureChannel() ?: return
            joinedSsid = ssid
            val config = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(passphrase).build()
            manager.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    check()
                }

                override fun onFailure(reason: Int) {
                    joinedSsid = ""
                    onStatus("Wi-Fi Direct: máy tính bảng chưa vào được đường nối thẳng (mã $reason), dùng Wi-Fi thường")
                }
            })
        } catch (e: Exception) {
            joinedSsid = ""
            onStatus("Wi-Fi Direct: thiếu quyền \"Thiết bị ở gần\" trên máy tính bảng")
        }
    }

    fun stop() {
        try {
            if (registered) context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        registered = false
        try {
            channel?.let { manager?.removeGroup(it, null) }
        } catch (_: Exception) {
        }
    }
}
