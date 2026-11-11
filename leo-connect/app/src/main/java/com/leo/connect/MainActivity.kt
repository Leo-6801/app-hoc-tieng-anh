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
        content.addView(text("Leo Connect", 26f, textColor, bold = true))
        content.addView(text("Điều khiển điện thoại từ máy tính bảng qua Tailscale", 14f, mutedColor).apply {
            setPadding(0, dp(2), 0, dp(16))
        })

        val roles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        roles.addView(roleButton("📱 Điện thoại\n(được điều khiển)", "host"), LinearLayout.LayoutParams(0, -2, 1f))
        roles.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        roles.addView(roleButton("💻 Máy tính bảng\n(điều khiển)", "controller"), LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(roles)
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))

        when (prefs.role) {
            "host" -> renderHost()
            "controller" -> renderController()
            else -> content.addView(card().apply {
                addView(text("Chọn vai trò của máy này ở trên.\n\n• Cài Leo Connect và Tailscale trên CẢ HAI máy, đăng nhập Tailscale cùng một tài khoản.\n• Trên điện thoại chọn \"Điện thoại\", trên máy tính bảng chọn \"Máy tính bảng\".", 15f, textColor))
            })
        }
        setRenderedState()
        updateStatus()
    }

    private fun renderHost() {
        val ips = Proto.localTailscaleIps()
        content.addView(card().apply {
            addView(text("Thông tin để nhập trên máy tính bảng", 16f, textColor, bold = true))
            addView(row("Địa chỉ Tailscale", if (ips.isEmpty()) "Chưa thấy – hãy bật Tailscale" else ips.joinToString(", "),
                if (ips.isEmpty()) warnColor else textColor))
            addView(row("Mã ghép đôi (PIN)", prefs.pin, textColor, big = true))
            addView(smallButton("Đổi mã PIN") {
                prefs.regeneratePin()
                toast("Đã đổi mã. Nhập mã mới trên máy tính bảng.")
                render()
            })
        })

        content.addView(card().apply {
            addView(text("Chuẩn bị (làm một lần)", 16f, textColor, bold = true))
            addView(check("Quyền Trợ năng (để chạm/vuốt từ xa)", ControlAccessibilityService.isEnabled(this@MainActivity), "Mở cài đặt") {
                toast("Chọn \"Leo Connect\" trong danh sách và bật lên")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            addView(text("Nếu không bật được: vào Cài đặt → Ứng dụng → Leo Connect → ⋮ → \"Cho phép cài đặt hạn chế\" rồi thử lại.", 12f, mutedColor))
            addView(check("Không tối ưu pin (để luôn sẵn sàng)", isIgnoringBatteryOptimizations(), "Cho phép") {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            })
            addView(check("Tailscale đã kết nối", ips.isNotEmpty(), "Mở Tailscale") {
                val launch = packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
                if (launch != null) startActivity(launch) else toast("Chưa cài Tailscale")
            })
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
    }

    private fun renderController() {
        content.addView(card().apply {
            addView(text("Chuẩn bị (làm một lần)", 16f, textColor, bold = true))
            addView(check("Quyền hiển thị trên ứng dụng khác (cửa sổ nổi)", Settings.canDrawOverlays(this@MainActivity), "Cho phép") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            })
            addView(check("Tailscale đã kết nối", Proto.localTailscaleIps().isNotEmpty(), "Mở Tailscale") {
                val launch = packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
                if (launch != null) startActivity(launch) else toast("Chưa cài Tailscale")
            })
        })

        val nameInput = input("Tên hiển thị (vd: Điện thoại của Leo)", prefs.targetName, InputType.TYPE_CLASS_TEXT)
        val hostInput = input("Địa chỉ Tailscale của điện thoại (100.x.x.x)", prefs.targetHost,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val pinInput = input("Mã ghép đôi 6 số", prefs.targetPin,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)

        content.addView(card().apply {
            addView(text("Điện thoại cần điều khiển", 16f, textColor, bold = true))
            addView(nameInput)
            addView(hostInput)
            addView(pinInput)
            val status = text("", 15f, textColor, bold = true)
            statusText = status
            addView(status)
            if (!ControllerService.running) {
                addView(bigButton("Kết nối") {
                    val host = hostInput.text.toString().trim()
                    val pin = pinInput.text.toString().trim()
                    if (host.isEmpty() || pin.length != 6) {
                        toast("Nhập địa chỉ Tailscale và mã PIN 6 số")
                        return@bigButton
                    }
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        toast("Cần cho phép hiển thị trên ứng dụng khác trước")
                        return@bigButton
                    }
                    prefs.targetName = nameInput.text.toString().trim()
                    prefs.targetHost = host
                    prefs.targetPin = pin
                    startForegroundService(
                        Intent(this@MainActivity, ControllerService::class.java)
                            .setAction(ControllerService.ACTION_CONNECT)
                            .putExtra(ControllerService.EXTRA_HOST, host)
                            .putExtra(ControllerService.EXTRA_PIN, pin)
                            .putExtra(ControllerService.EXTRA_NAME, prefs.targetName)
                    )
                    moveTaskToBack(true)
                })
            } else {
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

        content.addView(card().apply {
            addView(text("Mẹo dùng cửa sổ điện thoại", 15f, textColor, bold = true))
            addView(text("• Kéo thanh tiêu đề để di chuyển, kéo ◢ để đổi cỡ.\n• Nút — thu nhỏ thành bong bóng 📱, chạm bong bóng để mở lại.\n• Chạm, vuốt, nhấn giữ trực tiếp trên màn hình điện thoại.\n• Hàng dưới: ◁ Quay lại · ○ Màn hình chính · ▢ Đa nhiệm · 🔔 Thông báo · 🔒 Khóa.", 13f, mutedColor))
        })
    }

    private fun updateStatus() {
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
                    ControllerService.connected -> { view.text = "🟢 Đã kết nối"; view.setTextColor(okColor) }
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
            toast("Nên bật quyền Trợ năng trước, nếu không máy tính bảng chỉ xem được, không điều khiển được")
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
