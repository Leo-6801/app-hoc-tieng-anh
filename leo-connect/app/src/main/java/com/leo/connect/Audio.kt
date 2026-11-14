package com.leo.connect

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.annotation.TargetApi
import kotlin.concurrent.thread

/** Âm thanh truyền dạng PCM 16 bit, 48 kHz, 2 kênh; mỗi gói 20 ms. */
object AudioSpec {
    const val RATE = 48_000
    const val PACKET = RATE / 50 * 4
}

/** Điện thoại: thu tiếng đang phát của các app (nhạc, video, game) qua quyền chia sẻ màn hình. */
@TargetApi(29)
class AudioCapture(private val projection: MediaProjection, private val send: (ByteArray) -> Unit) {
    @Volatile
    private var running = false
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AudioSpec.RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val min = AudioRecord.getMinBufferSize(
                AudioSpec.RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val r = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, AudioSpec.PACKET * 4))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            r.startRecording()
            record = r
            running = true
            thread(name = "leo-audio") { loop(r) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun loop(r: AudioRecord) {
        val buf = ByteArray(AudioSpec.PACKET)
        try {
            while (running) {
                var filled = 0
                while (running && filled < buf.size) {
                    val n = r.read(buf, filled, buf.size - filled)
                    if (n <= 0) return
                    filled += n
                }
                if (!running) break
                // Không có tiếng thì không gửi, đỡ tốn đường truyền
                var silent = true
                for (b in buf) if (b.toInt() != 0) {
                    silent = false
                    break
                }
                if (!silent) send(buf.copyOf())
            }
        } catch (_: Exception) {
        } finally {
            running = false
        }
    }

    fun stop() {
        running = false
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
    }
}

/** Máy tính bảng: phát tiếng nhận từ điện thoại. Về dồn quá nhiều thì bỏ bớt để không trễ dần. */
class AudioPlayer {
    private var track: AudioTrack? = null

    fun write(data: ByteArray) {
        try {
            val t = track ?: create().also { track = it }
            t.write(data, 0, data.size, AudioTrack.WRITE_NON_BLOCKING)
        } catch (_: Exception) {
        }
    }

    private fun create(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            AudioSpec.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AudioSpec.RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, AudioSpec.PACKET * 6))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        return t
    }

    fun release() {
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        try {
            track?.release()
        } catch (_: Exception) {
        }
        track = null
    }
}
