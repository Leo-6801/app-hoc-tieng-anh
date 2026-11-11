package com.leo.connect.host

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import com.leo.connect.Proto
import kotlin.concurrent.thread

/**
 * Nén màn hình thành video H.264. Màn hình được vẽ vào [inputSurface]
 * (qua VirtualDisplay), mỗi khung hình nén xong được đưa cho [onFrame].
 */
class ScreenEncoder(
    val width: Int,
    val height: Int,
    private val bitrate: Int,
    private val onFrame: (flags: Int, ptsUs: Long, data: ByteArray) -> Unit,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    lateinit var inputSurface: Surface
        private set

    @Volatile
    private var running = false
    private var drainThread: Thread? = null

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Màn hình đứng yên vẫn gửi lại khung cũ, để máy tính bảng luôn có hình.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000L)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        running = true
        drainThread = thread(name = "leo-encoder") { drain() }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                codec.dequeueOutputBuffer(info, 20_000)
            } catch (e: Exception) {
                break
            }
            if (index < 0) continue
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes, 0, info.size)
                    var flags = 0
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) flags = flags or Proto.FLAG_CONFIG
                    if ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) flags = flags or Proto.FLAG_KEY
                    onFrame(flags, info.presentationTimeUs, bytes)
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: Exception) {
                Log.w("LeoConnect", "Lỗi gửi khung hình: ${e.message}")
                running = false
            }
        }
    }

    fun requestKeyFrame() {
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {
        }
    }

    fun stop() {
        running = false
        try {
            drainThread?.join(500)
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
        try {
            inputSurface.release()
        } catch (_: Exception) {
        }
    }
}
