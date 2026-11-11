package com.leo.connect.controller

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.leo.connect.Proto
import kotlin.concurrent.thread

/** Giải mã video H.264 từ điện thoại và vẽ thẳng lên [surface]. */
class VideoDecoder(surface: Surface, width: Int, height: Int, config: ByteArray?) {
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

    @Volatile
    private var running = true
    private var waitingForKeyFrame = true
    private val drainThread: Thread

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        codec.configure(format, surface, null, 0)
        codec.start()
        drainThread = thread(name = "leo-decoder") { drain() }
        if (config != null) queue(config, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, 0)
    }

    /** [flags] theo Proto.FLAG_*. Bỏ qua các khung trước khung khóa đầu tiên để tránh hình lỗi. */
    fun feed(data: ByteArray, flags: Int, ptsUs: Long) {
        if ((flags and Proto.FLAG_CONFIG) != 0) {
            queue(data, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, ptsUs)
            return
        }
        if (waitingForKeyFrame) {
            if ((flags and Proto.FLAG_KEY) == 0) return
            waitingForKeyFrame = false
        }
        queue(data, 0, ptsUs)
    }

    private fun queue(data: ByteArray, codecFlags: Int, ptsUs: Long) {
        if (!running) return
        try {
            val index = codec.dequeueInputBuffer(50_000)
            if (index < 0) return
            val buffer = codec.getInputBuffer(index) ?: return
            buffer.clear()
            if (data.size > buffer.capacity()) {
                codec.queueInputBuffer(index, 0, 0, ptsUs, 0)
                return
            }
            buffer.put(data)
            codec.queueInputBuffer(index, 0, data.size, ptsUs, codecFlags)
        } catch (_: Exception) {
        }
    }

    private fun drain() {
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
        try {
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
