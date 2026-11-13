package com.leo.connect.host

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
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
    private val fps: Int,
    private val mime: String,
    private val onFrame: (flags: Int, ptsUs: Long, data: ByteArray) -> Unit,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(mime)
    lateinit var inputSurface: Surface
        private set

    @Volatile
    private var running = false
    private var drainThread: Thread? = null

    private fun format(lowLatency: Boolean) =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // Khung khóa rất nặng, mỗi lần gửi là hình khựng một nhịp: chỉ gửi khi máy tính bảng xin
            // (lúc mới kết nối hoặc mất gói), không gửi định kỳ mỗi 2 giây như trước.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 30)
            // Màn hình đứng yên vẫn gửi lại khung cũ, để máy tính bảng luôn có hình.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000L)
            if (lowLatency) {
                if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                    // Baseline: không có khung B nên không phải chờ sắp xếp lại khung → bớt trễ.
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                }
                setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = thời gian thực
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
        }

    fun start() {
        try {
            // Làm mới hình dần dần (intra-refresh): tránh các khung khóa lớn gây giật độ trễ
            val withRefresh = format(lowLatency = true).apply {
                setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, fps)
            }
            codec.configure(withRefresh, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            try {
                codec.reset()
                codec.configure(format(lowLatency = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e2: Exception) {
            // Một số chip không nhận các tùy chọn trên → dùng cấu hình cơ bản
            Log.w("LeoConnect", "Bộ nén không hỗ trợ chế độ trễ thấp: ${e2.message}")
            codec.reset()
            codec.configure(format(lowLatency = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        }
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

    /** Đổi bitrate ngay khi đang chạy (tự hạ khi mạng yếu, tăng lại khi mạng tốt). */
    fun setBitrate(bps: Int) {
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
        } catch (_: Exception) {
        }
    }

    private var lastKeyRequest = 0L

    /** Xin khung khóa, tối đa 1 lần mỗi 0,4 giây (khung khóa nặng, xin liên tục sẽ làm nghẽn thêm). */
    fun requestKeyFrame() {
        val now = System.currentTimeMillis()
        if (now - lastKeyRequest < 400) return
        lastKeyRequest = now
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
