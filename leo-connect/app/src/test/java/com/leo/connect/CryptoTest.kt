package com.leo.connect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class CryptoTest {
    @Test fun haiMayRaCungKhoaPhien() {
        val a = Proto.newKeyPair()
        val b = Proto.newKeyPair()
        val s1 = Proto.sharedSecret(a.private, b.public.encoded)
        val s2 = Proto.sharedSecret(b.private, a.public.encoded)
        assertArrayEquals(s1, s2)
        val pin = Proto.keyFromPin("123456")
        val t = "bat-tay".toByteArray()
        val (c2h, h2c) = Proto.sessionKeys(s1, pin, t)
        assertEquals(32, c2h.size)
        assertFalse(c2h.contentEquals(h2c))
        // Sai PIN thì bằng chứng khác
        assertFalse(Proto.proof(pin, t, "host").contentEquals(Proto.proof(Proto.keyFromPin("654321"), t, "host")))
    }

    @Test fun kenhMaHoaGuiNhanDung() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
            val accepted = server.accept()
            val c = Channel(client)
            val h = Channel(accepted)
            val k1 = ByteArray(32) { 1 }
            val k2 = ByteArray(32) { 2 }
            c.send(Proto.AUTH, "chua ma hoa".toByteArray())
            assertEquals("chua ma hoa", String(h.receive().data))
            c.enableEncryption(send = k1, recv = k2)
            h.enableEncryption(send = k2, recv = k1)
            repeat(3) { i ->
                c.send(Proto.TEXT, "xin chào $i".toByteArray())
                val m = h.receive()
                assertEquals(Proto.TEXT, m.type)
                assertEquals("xin chào $i", String(m.data))
            }
            h.send(Proto.VIDEO_FRAME, ByteArray(100_000) { it.toByte() })
            assertEquals(100_000, c.receive().data.size)
            c.close(); h.close()
        }
    }

    @Test fun saiKhoaThiTuChoi() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
            val accepted = server.accept()
            val c = Channel(client)
            val h = Channel(accepted)
            c.enableEncryption(send = ByteArray(32) { 1 }, recv = ByteArray(32) { 2 })
            h.enableEncryption(send = ByteArray(32) { 2 }, recv = ByteArray(32) { 9 })
            c.send(Proto.TEXT, "bí mật".toByteArray())
            try {
                h.receive()
                fail("phải từ chối gói mã hóa bằng khóa khác")
            } catch (e: IOException) {
                // đúng
            }
            c.close(); h.close()
        }
    }
}
