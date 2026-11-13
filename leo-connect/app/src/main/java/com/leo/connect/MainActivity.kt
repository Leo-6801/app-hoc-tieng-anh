package com.leo.connect

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.leo.connect.controller.ControllerService
import com.leo.connect.host.ControlAccessibilityService
import com.leo.connect.host.HostService
import com.leo.connect.host.isIgnoringBatteryOptimizations
import kotlin.math.roundToInt

@SuppressLint("SetTextI18n", "BatteryLife")
class MainActivity : Activity() {

    companion object {
        private const val REQ_PROJECTION = 10
        private const val REQ_NOTIFICATIONS = 11
        private const val REQ_BLUETOOTH = 12
        private const val REQ_NEARBY = 13
    }

    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout
    private var statusText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 2_000)
        }
    }

    private val night get() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val cardColor get() = if (night) Color.parseColor("#1C1F24") else Color.WHITE
    private val textColor get() = if (night) Color.parseColor("#E8EBEF") else Color.parseColor("#1B1F24")
    private val mutedColor get() = if (night) Color.parseColor("#9AA5B1") else Color.parseColor("#5F6B7A")
    private val accent = Color.parseColor("#0A84FF")
    private val okColor = Color.parseColor("#12805C")
    private val warnColor = Color.parseColor("#C62828")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        Notif.ensureChannels(this)
        // Thả file vào màn hình app để gửi sang máy kia
        content.setOnDragListener { _, e ->
            when (e.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> true
                android.view.DragEvent.ACTION_DROP -> {
                    val uris = ArrayList<Uri>()
                    val clip = e.clipData
                    if (clip != null) {
                        for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
                    }
                    if (uris.isNotEmpty()) {
                        val perms = requestDragAndDropPermissions(e)
                        if (perms != null) dropPermissions.add(perms)
                        Link.sendFiles(this, uris) {
                            if (perms != null) {
                                perms.release()
                                dropPermissions.remove(perms)
                            }
                        }
                    }
                    uris.isNotEmpty()
                }
                else -> true
            }
        }
        if (!updateChecked) {
            updateChecked = true
            kotlin.concurrent.thread(name = "leo-update-check") {
                try {
                    Updater.check()
                } catch (_: Exception) {
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    // ------------------------------------------------------------ giao diện

    private fun render() {
        content.removeAllViews()
        statusText = null
        btStatusText = null
        p2pStatusText = null
        fileText = null
        content.addView(text("Leo Connect", 26f, textColor, bold = true))
        content.addView(text("Điều khiển điện thoại từ máy tính bảng trong cùng Wi-Fi · phiên bản ${BuildConfigInfo.version(this)}", 14f, mutedColor).apply {
            setPadding(0, dp(2), 0, dp(4))
        })
        val updateLine = text("", 13f, mutedColor)
        updateText = updateLine
        content.addView(updateLine)
        content.addView(smallButton(if (Updater.hasUpdate(this)) "⬇ Cập nhật lên bản ${Updater.latest?.version}" else "Kiểm tra cập nhật") {
            runUpdate()
        }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))

        val roles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        roles.addView(roleButton("📱 Điện thoại\n(được điều khiển)", "host"), LinearLayout.LayoutParams(0, -2, 1f))
        roles.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        roles.addView(roleButton("💻 Máy tính bảng\n(điều khiển)", "controller"), LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(roles)
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))

        CrashLog.read(this)?.let { error ->
            content.addView(card().apply {
                addView(text("⚠️ Lần trước Leo Connect gặp lỗi", 16f, warnColor, bold = true))
                addView(text("Chụp màn hình phần này gửi cho người phát triển để sửa.", 13f, mutedColor))
                addView(text(error.take(1500), 11f, textColor).apply { setTextIsSelectable(true) })
                addView(smallButton("Sao chép") {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Leo Connect lỗi", error))
                    toast("Đã sao chép")
                })
                addView(smallButton("Xóa thông báo lỗi") {
                    CrashLog.clear(this@MainActivity)
                    render()
                })
            })
        }

        when (prefs.role) {
            "host" -> renderHost()
            "controller" -> renderController()
            else -> content.addView(card().apply {
                addView(text("Chọn vai trò của máy này ở trên.\n\n• Cài Leo Connect trên CẢ HAI máy và cho hai máy bắt cùng một mạng Wi-Fi (không cần Tailscale).\n• Trên điện thoại chọn \"Điện thoại\", trên máy tính bảng chọn \"Máy tính bảng\".", 15f, textColor))
            })
        }
        setRenderedState()
        updateStatus()
    }

    private var fileText: TextView? = null

    /** Thẻ gửi file + giải thích bộ nhớ tạm, dùng cho cả hai máy. */
    private fun shareCard(other: String) = card().apply {
        addView(text("Gửi file và bộ nhớ tạm", 16f, textColor, bold = true))
        val line = text("", 13f, okColor)
        fileText = line
        addView(line)
        addView(smallButton("📎 Chọn file gửi sang $other") {
            startActivity(Intent(this@MainActivity, SendActivity::class.java).setAction(SendActivity.ACTION_PICK))
        })
        addView(text("Cách khác: trong app bất kỳ bấm Chia sẻ → Leo Connect, hoặc kéo file thả vào màn hình này. File nhận được nằm ở Tải xuống/LeoConnect. Chỉ gửi được khi hai máy đang kết nối.", 12f, mutedColor))
        addView(text("Bộ nhớ tạm: sao chép chữ trên máy tính bảng rồi chạm vào cửa sổ điện thoại là dán được bên điện thoại. Chiều ngược lại (sao chép trên điện thoại) Android chỉ cho đọc khi app Leo Connect đang mở trên điện thoại, nên có thể không tự sang.", 12f, mutedColor))
    }

    private fun renderHost() {
        val ips = Proto.localIps()
        content.addView(card().apply {
            addView(text("Thông tin để nhập trên máy tính bảng", 16f, textColor, bold = true))
            addView(row("Mã ghép đôi (PIN)", prefs.pin, textColor, big = true))
            addView(row("Địa chỉ Wi-Fi (chỉ cần khi máy tính bảng không tự tìm thấy)",
                if (ips.isEmpty()) "Chưa kết nối Wi-Fi" else ips.joinToString(", "),
                if (ips.isEmpty()) warnColor else textColor))
            addView(smallButton("Đổi mã PIN") {
                prefs.regeneratePin()
                toast("Đã đổi mã. Nhập mã mới trên máy tính bảng.")
                render()
            })
        })

        content.addView(card().apply {
            addView(text("Chuẩn bị (làm một lần)", 16f, textColor, bold = true))
            addView(check("Quyền Trợ năng (không cần nếu điều khiển qua Bluetooth)", ControlAccessibilityService.isEnabled(this@MainActivity), "Mở cài đặt") {
                toast("Chọn \"Leo Connect\" trong danh sách và bật lên")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            addView(text("Nếu không bật được: vào Cài đặt → Ứng dụng → Leo Connect → ⋮ → \"Cho phép cài đặt hạn chế\" rồi thử lại.", 12f, mutedColor))
            addView(check("Không tối ưu pin (để luôn sẵn sàng)", isIgnoringBatteryOptimizations(), "Cho phép") {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            })
            addView(check("Đã kết nối Wi-Fi (cùng mạng với máy tính bảng)", ips.isNotEmpty(), "Mở Wi-Fi") {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            })
            addView(nearbyCheck())
            addView(bleCheck())
            addView(check("Quyền Ghi âm (để truyền tiếng nhạc, video sang máy tính bảng; không thu micro)",
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED, "Cho phép") {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_NOTIFICATIONS)
            })
            addView(text("Cấp hai quyền này TRƯỚC khi bấm \"Bật chế độ sẵn sàng\". Nếu đang bật thì tắt rồi bật lại.", 12f, mutedColor))
            val p2pLine = text("", 13f, mutedColor)
            p2pStatusText = p2pLine
            addView(p2pLine)
        })

        content.addView(card().apply {
            val status = text("", 15f, textColor, bold = true)
            statusText = status
            addView(status)
            if (!HostService.running) {
                addView(bigButton("Bật chế độ sẵn sàng") { requestProjection() })
                addView(text("Android sẽ hỏi quyền ghi/chia sẻ màn hình: chọn \"Toàn bộ màn hình\" rồi \"Bắt đầu\".", 12f, mutedColor))
            } else {
                addView(bigButton("Tắt chế độ sẵn sàng", danger = true) {
                    startService(Intent(this@MainActivity, HostService::class.java).setAction(HostService.ACTION_STOP))
                    handler.postDelayed({ render() }, 500)
                })
            }
        })
        content.addView(shareCard("máy tính bảng"))
    }

    // Máy tính bảng: tự tìm điện thoại trong mạng Wi-Fi
    private var deviceList: LinearLayout? = null
    private var hostField: EditText? = null
    private var searching = false

    private fun searchDevices() {
        val list = deviceList ?: return
        if (searching) return
        searching = true
        list.removeAllViews()
        list.addView(text("Đang tìm điện thoại trong mạng Wi-Fi…", 14f, mutedColor))
        Thread {
            // Tìm song song qua Wi-Fi và qua Bluetooth
            var nearby: List<Discovery.Found> = emptyList()
            val bleThread = Thread { nearby = BleBeacon.scan(this, 2000) }
            if (hasBlePermission()) bleThread.start()
            val viaWifi = Discovery.find(2000)
            try {
                bleThread.join(3000)
            } catch (_: InterruptedException) {
            }
            val savedDirect = prefs.peerP2pSsid.isNotEmpty()
            val found = ArrayList(viaWifi)
            var seenNearbyWithoutWifi = false
            for (d in nearby) {
                when {
                    d.ip.isNotEmpty() && found.none { it.ip == d.ip } ->
                        found.add(Discovery.Found(d.name + " (thấy qua Bluetooth)", d.ip))
                    d.ip.isEmpty() && viaWifi.isEmpty() && savedDirect ->
                        // Không chung Wi-Fi nhưng đã ghép từ trước: vào thẳng đường Wi-Fi Direct
                        found.add(Discovery.Found(d.name + " (ở gần, nối thẳng)", "192.168.49.1"))
                    d.ip.isEmpty() && viaWifi.isEmpty() -> seenNearbyWithoutWifi = true
                }
            }
            runOnUiThread {
                searching = false
                val current = deviceList ?: return@runOnUiThread
                current.removeAllViews()
                if (found.isEmpty() && seenNearbyWithoutWifi) {
                    current.addView(text("Thấy điện thoại ở gần qua Bluetooth, nhưng hai máy chưa cùng mạng Wi-Fi. Lần ghép đầu tiên cần cùng Wi-Fi (hoặc máy tính bảng bắt điểm phát sóng của điện thoại); từ lần sau hai máy tự nối thẳng.", 13f, warnColor))
                } else if (found.isEmpty()) {
                    current.addView(text("Không thấy điện thoại nào. Kiểm tra: điện thoại đã bấm \"Bật chế độ sẵn sàng\", hai máy cùng một Wi-Fi, và Wi-Fi không bật chế độ cách ly thiết bị (mạng khách). Có thể nhập địa chỉ Wi-Fi của điện thoại vào ô bên dưới.", 13f, warnColor))
                } else {
                    for (d in found) {
                        current.addView(Button(this).apply {
                            text = "📱 ${d.name}  ·  ${d.ip}"
                            isAllCaps = false
                            textSize = 15f
                            setTextColor(textColor)
                            background = GradientDrawable().apply {
                                setColor(if (night) Color.parseColor("#2A2F37") else Color.parseColor("#EEF1F5"))
                                cornerRadius = dp(12).toFloat()
                            }
                            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
                            setOnClickListener {
                                hostField?.setText(d.ip)
                                prefs.targetName = d.name
                                toast("Đã chọn ${d.name}. Nhập mã PIN rồi bấm Kết nối.")
                            }
                        })
                    }
                    // Địa chỉ đang điền không còn đúng (đổi Wi-Fi, chuyển sang điểm phát sóng...): tự điền
                    // địa chỉ mới tìm thấy
                    val typed = hostField?.text?.toString()?.trim().orEmpty()
                    if (found.none { it.ip == typed }) {
                        hostField?.setText(found[0].ip)
                        prefs.targetName = found[0].name
                    }
                }
            }
        }.start()
    }

    private fun renderController() {
        content.addView(card().apply {
            addView(text("Chuẩn bị (làm một lần)", 16f, textColor, bold = true))
            addView(check("Quyền hiển thị trên ứng dụng khác (cửa sổ nổi)", Settings.canDrawOverlays(this@MainActivity), "Cho phép") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            })
            addView(check("Đã kết nối Wi-Fi (cùng mạng với điện thoại)", Proto.localIps().isNotEmpty(), "Mở Wi-Fi") {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            })
            addView(nearbyCheck())
            addView(bleCheck())
        })

        val hostInput = input("Địa chỉ Wi-Fi của điện thoại (tự điền khi chọn ở trên)", prefs.targetHost,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val pinInput = input("Mã ghép đôi 6 số", prefs.targetPin,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        hostField = hostInput

        content.addView(card().apply {
            addView(text("Điện thoại cần điều khiển", 16f, textColor, bold = true))
            if (!ControllerService.running) {
                val list = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                deviceList = list
                addView(list)
                addView(smallButton("Tìm lại") { searchDevices() })
                addView(hostInput)
                addView(pinInput)
            }
            val status = text("", 15f, textColor, bold = true)
            statusText = status
            addView(status)
            if (!ControllerService.running) {
                addView(bigButton("Kết nối") {
                    val host = hostInput.text.toString().trim()
                    val pin = pinInput.text.toString().trim()
                    if (host.isEmpty() || pin.length != 6) {
                        toast("Chọn điện thoại (hoặc nhập địa chỉ) và nhập mã PIN 6 số")
                        return@bigButton
                    }
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        toast("Cần cho phép hiển thị trên ứng dụng khác trước")
                        return@bigButton
                    }
                    prefs.targetHost = host
                    prefs.targetPin = pin
                    startForegroundService(
                        Intent(this@MainActivity, ControllerService::class.java)
                            .setAction(ControllerService.ACTION_CONNECT)
                            .putExtra(ControllerService.EXTRA_HOST, host)
                            .putExtra(ControllerService.EXTRA_PIN, pin)
                    )
                    moveTaskToBack(true)
                })
            } else {
                deviceList = null
                addView(bigButton("Mở lại cửa sổ điện thoại") {
                    startForegroundService(
                        Intent(this@MainActivity, ControllerService::class.java).setAction(ControllerService.ACTION_CONNECT)
                    )
                    moveTaskToBack(true)
                })
                addView(bigButton("Ngắt kết nối", danger = true) {
                    startService(Intent(this@MainActivity, ControllerService::class.java).setAction(ControllerService.ACTION_DISCONNECT))
                    handler.postDelayed({ render() }, 500)
                })
            }
        })
        if (!ControllerService.running) searchDevices()
        content.addView(shareCard("điện thoại"))
        renderBluetoothCard()

        content.addView(card().apply {
            addView(text("Cách dùng cửa sổ điện thoại", 15f, textColor, bold = true))
            addView(text("• Kéo tay nắm ••• phía trên để di chuyển cửa sổ. Nhấn giữ ••• để mở menu: thu nhỏ thành bong bóng, bàn phím ảo, tắt cửa sổ.\n• Chụm/mở 2 ngón tay trên màn hình để thu phóng.\n• Chạm, vuốt, nhấn giữ trực tiếp như cầm điện thoại.\n• Vuốt từ mép trái hoặc phải vào = Quay lại. Vuốt từ đáy lên = Màn hình chính. Vuốt từ đáy lên rồi giữ = Đa nhiệm.\n• Chạm vào cửa sổ rồi gõ bằng bàn phím rời (tiếng Việt Telex). Enter = bấm nút Gửi, Shift+Enter = xuống dòng. Chạm ra ngoài cửa sổ để gõ ở app khác.", 13f, mutedColor))
        })
    }

    private var btStatusText: TextView? = null
    private var p2pStatusText: TextView? = null

    private val blePermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        ) else emptyArray()

    private fun hasBlePermission(): Boolean =
        blePermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun bleCheck(): View =
        check("Quyền Bluetooth (để hai máy thấy nhau khi ở gần)", hasBlePermission(), "Cho phép") {
            requestPermissions(blePermissions, REQ_NEARBY)
        }

    private val nearbyPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION

    private fun hasNearbyPermission(): Boolean =
        checkSelfPermission(nearbyPermission) == PackageManager.PERMISSION_GRANTED

    /** Dòng kiểm tra quyền cho Wi-Fi Direct, dùng ở cả hai vai trò. */
    private fun nearbyCheck(): View =
        check("Quyền \"Thiết bị ở gần\" (để hai máy nối thẳng Wi-Fi 5 GHz, không qua router)", hasNearbyPermission(), "Cho phép") {
            requestPermissions(arrayOf(nearbyPermission), REQ_NEARBY)
        }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Thử nghiệm: máy tính bảng làm màn hình cảm ứng + bàn phím Bluetooth của điện thoại. */
    @SuppressLint("MissingPermission")
    private fun renderBluetoothCard() {
        content.addView(card().apply {
            addView(text("Điều khiển qua Bluetooth (thử nghiệm)", 16f, textColor, bold = true))
            addView(text("Máy tính bảng đóng vai màn hình cảm ứng và bàn phím Bluetooth của điện thoại, nên điện thoại KHÔNG cần bật Trợ năng. Hình ảnh vẫn truyền qua Wi-Fi. Hai máy phải ở gần nhau.", 13f, mutedColor))
            if (Build.VERSION.SDK_INT < 28) {
                addView(text("Máy tính bảng này dùng Android quá cũ, không hỗ trợ.", 13f, warnColor))
                return@apply
            }
            if (!prefs.btEnabled) {
                addView(bigButton("Bật điều khiển qua Bluetooth") {
                    if (!hasBluetoothPermission()) {
                        requestPermissions(
                            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE),
                            REQ_BLUETOOTH,
                        )
                    } else {
                        prefs.btEnabled = true
                        render()
                    }
                })
                return@apply
            }
            if (!hasBluetoothPermission()) {
                addView(smallButton("Cấp quyền Bluetooth") {
                    requestPermissions(
                        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE),
                        REQ_BLUETOOTH,
                    )
                })
                return@apply
            }
            val btLine = text("", 15f, textColor, bold = true)
            btStatusText = btLine
            addView(btLine)
            addView(text("Cách ghép lần đầu:\n1. Bấm Kết nối ở trên để mở cửa sổ điện thoại (bước này bật chế độ thiết bị nhập).\n2. Bấm nút bên dưới để điện thoại tìm thấy máy tính bảng.\n3. Trên ĐIỆN THOẠI: Cài đặt → Bluetooth → ghép đôi với máy tính bảng này. Nếu hai máy đã ghép đôi từ trước, hãy hủy ghép rồi ghép lại.\n4. Khi thấy báo \"đang điều khiển qua Bluetooth\" là được. Có thể tắt Trợ năng trên điện thoại để kiểm tra.", 13f, textColor))
            addView(smallButton("Cho điện thoại tìm thấy máy tính bảng (5 phút)") {
                try {
                    startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                        .putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
                } catch (e: Exception) {
                    toast("Không mở được: ${e.message}")
                }
            })
            val bonded = try {
                getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.bondedDevices?.toList().orEmpty()
            } catch (e: Exception) {
                emptyList()
            }
            if (bonded.isNotEmpty()) {
                addView(text("Điện thoại đã ghép đôi (chọn để máy tính bảng tự nối lại lần sau):", 13f, mutedColor))
                for (d in bonded) {
                    val selected = d.address == prefs.btAddress
                    addView(smallButton((if (selected) "✅ " else "○ ") + (d.name ?: d.address)) {
                        prefs.btAddress = if (selected) "" else d.address
                        render()
                    })
                }
            }
            addView(text("Khi điều khiển qua Bluetooth, vuốt trên cửa sổ như đang cầm điện thoại: quay lại, về màn hình chính, mở đa nhiệm đều theo đúng kiểu điều hướng của điện thoại (cử chỉ hoặc 3 nút). Lưu ý: chỉ chạm 1 ngón (không thu phóng 2 ngón trong app của điện thoại). Gõ tiếng Việt do bàn phím của điện thoại xử lý: bật Telex cho bàn phím thật trong cài đặt bàn phím của điện thoại. Thay đổi ở đây có hiệu lực từ lần Kết nối sau.", 12f, mutedColor))
            addView(smallButton("Tắt điều khiển qua Bluetooth") {
                prefs.btEnabled = false
                render()
            })
        })
    }

    private var updateText: TextView? = null
    private var updateChecked = false
    private var shownUpdate = false
    private val dropPermissions = ArrayList<android.view.DragAndDropPermissions>()

    /** Kiểm tra bản mới; có thì tải và đưa cho Android cài. */
    private fun runUpdate() {
        if (Updater.busy) return
        Updater.status = "Đang kiểm tra bản mới…"
        updateStatus()
        val current = Updater.currentNumber(this)
        kotlin.concurrent.thread(name = "leo-update") {
            try {
                val info = Updater.check()
                if (info == null || info.number <= current) {
                    Updater.status = "Đang dùng bản mới nhất."
                } else {
                    Updater.downloadAndInstall(this, info)
                }
            } catch (e: Exception) {
                Updater.status = "Không kiểm tra được bản mới (cần Internet): ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun updateStatus() {
        updateText?.let { line ->
            val s = Updater.status
            line.text = when {
                s.isNotEmpty() -> s
                Updater.hasUpdate(this) -> "Có bản mới ${Updater.latest?.version}."
                else -> ""
            }
            line.visibility = if (line.text.isEmpty()) View.GONE else View.VISIBLE
        }
        if (Updater.hasUpdate(this) && !shownUpdate) {
            shownUpdate = true
            render()
            return
        }
        fileText?.let { line ->
            val p = Link.progress
            line.text = p
            line.visibility = if (p.isEmpty()) View.GONE else View.VISIBLE
        }
        p2pStatusText?.text = HostService.p2pStatus
        btStatusText?.let { line ->
            val s = ControllerService.btStatus
            line.text = when {
                s.isNotEmpty() -> "Trạng thái: $s"
                ControllerService.running -> "Trạng thái: Bluetooth chưa bật cho phiên này. Ngắt kết nối rồi Kết nối lại."
                else -> "Trạng thái: chưa kết nối. Bấm Kết nối ở trên trước."
            }
        }
        val view = statusText ?: return
        when (prefs.role) {
            "host" -> {
                val from = HostService.connectedFrom
                when {
                    from != null -> { view.text = "🟢 Đang bị điều khiển từ $from"; view.setTextColor(okColor) }
                    HostService.running -> { view.text = "🟢 Đang sẵn sàng – chờ máy tính bảng kết nối"; view.setTextColor(okColor) }
                    else -> { view.text = "⚪ Chưa bật chế độ sẵn sàng"; view.setTextColor(mutedColor) }
                }
                if (HostService.running != (findButtonState() == "running")) render()
            }
            "controller" -> {
                when {
                    ControllerService.connected -> {
                        val stats = ControllerService.netStats
                        view.text = if (stats.isEmpty()) "🟢 Đã kết nối" else "🟢 Đã kết nối\n$stats"
                        view.setTextColor(okColor)
                    }
                    ControllerService.running -> { view.text = "🟡 Đang kết nối…"; view.setTextColor(mutedColor) }
                    else -> { view.text = "⚪ Chưa kết nối"; view.setTextColor(mutedColor) }
                }
                if (ControllerService.running != (findButtonState() == "running")) render()
            }
        }
    }

    // Ghi nhớ trạng thái lúc vẽ giao diện để biết khi nào cần vẽ lại nút
    private var renderedState = ""
    private fun findButtonState() = renderedState

    private fun requestProjection() {
        if (!ControlAccessibilityService.isEnabled(this)) {
            toast("Trợ năng đang tắt: máy tính bảng chỉ điều khiển được qua Bluetooth")
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                startForegroundService(
                    Intent(this, HostService::class.java)
                        .setAction(HostService.ACTION_START)
                        .putExtra(HostService.EXTRA_CODE, resultCode)
                        .putExtra(HostService.EXTRA_DATA, data)
                )
                handler.postDelayed({ render() }, 600)
            } else {
                toast("Chưa cấp quyền chia sẻ màn hình")
            }
        }
    }

    // ------------------------------------------------------------ các thành phần giao diện

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(cardColor)
            cornerRadius = dp(16).toFloat()
        }
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    }

    private fun row(label: String, value: String, color: Int, big: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(text(label, 13f, mutedColor))
        addView(text(value, if (big) 28f else 17f, color, bold = true).apply {
            setTextIsSelectable(true)
            if (big) letterSpacing = 0.15f
        })
    }

    private fun check(label: String, ok: Boolean, action: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(text((if (ok) "✅ " else "⚠️ ") + label, 14f, if (ok) textColor else warnColor),
            LinearLayout.LayoutParams(0, -2, 1f))
        if (!ok) addView(smallButton(action, onClick))
    }

    private fun roleButton(label: String, role: String): Button {
        val selected = prefs.role == role
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(if (selected) Color.WHITE else textColor)
            background = GradientDrawable().apply {
                setColor(if (selected) accent else cardColor)
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(8), dp(14), dp(8), dp(14))
            setOnClickListener {
                prefs.role = role
                render()
            }
        }
    }

    private fun bigButton(label: String, danger: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(if (danger) warnColor else accent)
            cornerRadius = dp(12).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(accent)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { onClick() }
    }

    private fun input(hint: String, value: String, type: Int) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = type
        setTextColor(textColor)
        setHintTextColor(mutedColor)
        textSize = 16f
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLUETOOTH && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) prefs.btEnabled = true
        render()
    }

    private fun setRenderedState() {
        renderedState = when (prefs.role) {
            "host" -> if (HostService.running) "running" else "idle"
            "controller" -> if (ControllerService.running) "running" else "idle"
            else -> ""
        }
    }
}
