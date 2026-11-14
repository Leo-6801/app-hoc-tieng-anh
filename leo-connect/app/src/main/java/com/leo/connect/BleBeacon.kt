package com.leo.connect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import java.net.InetAddress

/**
 * Tìm nhau bằng Bluetooth năng lượng thấp: điện thoại phát một tín hiệu nhỏ "Leo Connect ở đây"
 * kèm địa chỉ Wi-Fi hiện tại; máy tính bảng quét thấy là biết điện thoại ở gần và ở địa chỉ nào,
 * kể cả khi mạng Wi-Fi chặn cách tìm thông thường.
 * Tín hiệu KHÔNG chứa mã PIN hay mật khẩu: việc xác thực vẫn làm qua kết nối đã mã hóa.
 */
@SuppressLint("MissingPermission")
object BleBeacon {
    private const val COMPANY_ID = 0xFFFF // mã dành cho thử nghiệm, không thuộc hãng nào
    private val MAGIC = byteArrayOf(0x4C, 0x43, 0x01) // "LC" + phiên bản

    class Advertiser(private val context: Context) {
        private var callback: AdvertiseCallback? = null
        private var advertisedIp = ""

        /** Phát (hoặc phát lại khi địa chỉ đổi). Trả về false nếu máy không phát được. */
        fun start(ip: String): Boolean {
            if (callback != null && ip == advertisedIp) return true
            stop()
            return try {
                val advertiser = context.getSystemService(BluetoothManager::class.java)?.adapter
                    ?.takeIf { it.isEnabled }?.bluetoothLeAdvertiser ?: return false
                val address = if (ip.isEmpty()) ByteArray(4) else InetAddress.getByName(ip).address
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(false)
                    .build()
                val data = AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addManufacturerData(COMPANY_ID, MAGIC + address)
                    .build()
                val response = AdvertiseData.Builder().setIncludeDeviceName(true).build()
                val cb = object : AdvertiseCallback() {}
                advertiser.startAdvertising(settings, data, response, cb)
                callback = cb
                advertisedIp = ip
                true
            } catch (e: Exception) {
                false
            }
        }

        fun stop() {
            val cb = callback ?: return
            callback = null
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser
                    ?.stopAdvertising(cb)
            } catch (_: Exception) {
            }
        }
    }

    /** Máy tính bảng: quét trong [timeoutMs]. Địa chỉ rỗng nghĩa là thấy điện thoại nhưng nó chưa có Wi-Fi. */
    fun scan(context: Context, timeoutMs: Long = 2000): List<Discovery.Found> {
        val found = LinkedHashMap<String, Discovery.Found>()
        try {
            val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return emptyList()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    val record = result?.scanRecord ?: return
                    val data = record.getManufacturerSpecificData(COMPANY_ID) ?: return
                    if (data.size < MAGIC.size + 4 || !data.copyOf(MAGIC.size).contentEquals(MAGIC)) return
                    val raw = data.copyOfRange(MAGIC.size, MAGIC.size + 4)
                    val ip = if (raw.all { it.toInt() == 0 }) "" else InetAddress.getByAddress(raw).hostAddress ?: ""
                    val name = record.deviceName ?: "Điện thoại"
                    synchronized(found) { found[result.device.address] = Discovery.Found(name, ip) }
                }
            }
            val filter = ScanFilter.Builder()
                .setManufacturerData(COMPANY_ID, MAGIC, byteArrayOf(-1, -1, -1))
                .build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(listOf(filter), settings, callback)
            try {
                Thread.sleep(timeoutMs)
            } finally {
                scanner.stopScan(callback)
            }
        } catch (_: Exception) {
        }
        return synchronized(found) { found.values.toList() }
    }
}
