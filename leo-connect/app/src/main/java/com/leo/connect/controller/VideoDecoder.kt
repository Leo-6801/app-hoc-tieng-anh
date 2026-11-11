package com.leo.connect.controller

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.leo.connect.Proto
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Giải mã video H.264 từ điện thoại và vẽ thẳng lên [surface].
 * Khung hình được xếp hàng và giải mã ở luồng riêng, nên luồng nhận mạng không bao giờ bị chặn.
 * Nếu giải mã không kịp (hàng đợi dài), bỏ các khung cũ và gọi [onNeedKeyFrame] để xin khung khóa mới.
 */
class VideoDecoder(
    surface: Surface,
    width: Int,
    height: Int,
    mime: String,
    config: ByteArray?,
    private val onNeedKeyFrame: () -> Unit,
) {
    private class Item(val data: ByteArray, val codecFlags: Int, val ptsUs: Long, val key: Boolean)

    private val codec = MediaCodec.createDecoderByType(mime)
    private val queue = LinkedBlockingDeque<Item>()

    @Volatile
    private var running = true

    @Volatile
    private var waitingForKeyFrame = true
    private val feedThread: Thread
    private val drainThread: Thread

    init {
        val format = MediaFormat.createVideoFormat(mime, width, height)
        if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        codec.configure(format, surface, null, 0)
        codec.start()
        if (config != null) queue.add(Item(config, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, 0, false))
        feedThread = thread(name = "leo-dec-in") { feedLoop() }
        drainThread = thread(name = "leo-dec-out") { drainLoop() }
    }

    /** Gọi từ luồng mạng; không chặn. [flags] theo Proto.FLAG_*. */
    fun feed(data: ByteArray, flags: Int, ptsUs: Long) {
        if (!running) return
        if ((flags and Proto.FLAG_CONFIG) != 0) {
            queue.add(Item(data, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, ptsUs, false))
            return
        }
        val key = (flags and Proto.FLAG_KEY) != 0
        if (key) {
            // Khung khóa: bỏ các khung cũ chưa giải mã, nhảy ngay tới hình hiện tại
            queue.removeIf { it.codecFlags == 0 }
            waitingForKeyFrame = false
            queue.add(Item(data, 0, ptsUs, true))
            return
        }
        if (waitingForKeyFrame) return
        if (queue.size >= 3) {
            queue.removeIf { it.codecFlags == 0 }
            waitingForKeyFrame = true
            onNeedKeyFrame()
            return
        }
        queue.add(Item(data, 0, ptsUs, false))
    }

    private fun feedLoop() {
        while (running) {
            val item = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue
            try {
                var index = -1
                while (running && index < 0) index = codec.dequeueInputBuffer(10_000)
                if (!running) break
                val buffer = codec.getInputBuffer(index) ?: continue
                buffer.clear()
                if (item.data.size > buffer.capacity()) {
                    codec.queueInputBuffer(index, 0, 0, item.ptsUs, 0)
                    continue
                }
                buffer.put(item.data)
                codec.queueInputBuffer(index, 0, item.data.size, item.ptsUs, item.codecFlags)
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                val index = codec.dequeueOutputBuffer(info, 20_000)
                if (index >= 0) codec.releaseOutputBuffer(index, true)
            } catch (_: Exception) {
                break
            }
        }
    }

    fun release() {
        running = false
        feedThread.interrupt()
        try {
            feedThread.join(300)
            drainThread.join(300)
        } catch (_: Exception) {
        }
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        try {
            codec.release()
        } catch (_: Exception) {
        }
    }
}
