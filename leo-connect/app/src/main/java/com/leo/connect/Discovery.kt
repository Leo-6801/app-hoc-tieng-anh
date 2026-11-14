package com.leo.connect

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Tự tìm điện thoại trong cùng mạng Wi-Fi bằng gói UDP phát rộng (broadcast).
 * Máy tính bảng hỏi "LEOCONNECT?", điện thoại đang sẵn sàng trả lời "LEOCONNECT!" + tên máy.
 * Gói tìm kiếm không chứa mã PIN hay dữ liệu nhạy cảm.
 */
object Discovery {
    private val QUERY = "LEOCONNECT?1".toByteArray()
    private val REPLY = "LEOCONNECT!1".toByteArray()

    class Found(val name: String, val ip: String)

    /** Điện thoại: trả lời các gói tìm kiếm cho tới khi [socket] bị đóng. Chạy trên luồng riêng. */
    fun respond(socket: DatagramSocket, name: String) {
        val buf = ByteArray(64)
        val answer = REPLY + name.toByteArray(Charsets.UTF_8).take(80).toByteArray()
        while (!socket.isClosed) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                if (!Proto.isLocalNetwork(packet.address)) continue
                if (packet.length != QUERY.size || !buf.copyOf(packet.length).contentEquals(QUERY)) continue
                socket.send(DatagramPacket(answer, answer.size, packet.address, packet.port))
            } catch (e: Exception) {
                if (socket.isClosed) return
            }
        }
    }

    fun openResponderSocket(): DatagramSocket = DatagramSocket(null).apply {
        reuseAddress = true
        broadcast = true
        bind(InetSocketAddress(Proto.DISCOVERY_PORT))
    }

    /** Máy tính bảng: tìm trong khoảng [timeoutMs] rồi trả về danh sách điện thoại thấy được. */
    fun find(timeoutMs: Int = 1500): List<Found> {
        val found = LinkedHashMap<String, Found>()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 250
                val targets = LinkedHashSet<InetAddress>()
                targets.add(InetAddress.getByName("255.255.255.255"))
                try {
                    for (ni in NetworkInterface.getNetworkInterfaces()) {
                        if (!ni.isUp || ni.isLoopback) continue
                        for (ia in ni.interfaceAddresses) ia.broadcast?.let { targets.add(it) }
                    }
                } catch (_: Exception) {
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                var lastSend = 0L
                val buf = ByteArray(256)
                while (System.currentTimeMillis() < deadline) {
                    val now = System.currentTimeMillis()
                    if (now - lastSend >= 400) {
                        lastSend = now
                        for (t in targets) {
                            try {
                                socket.send(DatagramPacket(QUERY, QUERY.size, t, Proto.DISCOVERY_PORT))
                            } catch (_: Exception) {
                            }
                        }
                    }
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        if (packet.length < REPLY.size || !Proto.isLocalNetwork(packet.address)) continue
                        if (!buf.copyOf(REPLY.size).contentEquals(REPLY)) continue
                        val ip = packet.address.hostAddress ?: continue
                        val name = String(buf, REPLY.size, packet.length - REPLY.size, Charsets.UTF_8)
                        found[ip] = Found(name.ifBlank { ip }, ip)
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        return found.values.toList()
    }
}
