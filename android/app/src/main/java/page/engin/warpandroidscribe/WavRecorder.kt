package com.engin.warpandroidscribe

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * 16 kHz mono PCM16 into a WAV file, on its own thread. Same format the
 * existing warpmobile IME uses, and the one every Whisper server accepts.
 */
class WavRecorder(private val file: File) {
    @Volatile
    var amplitude = 0
        private set

    @Volatile
    var frames = 0
        private set

    val durationMs: Long
        get() = frames * 1000L / SAMPLE_RATE

    @Volatile
    private var stopped = false

    private var writer: Thread? = null

    fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw IOException("AudioRecord buffer hesaplanamadı.")
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, SAMPLE_RATE),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("AudioRecord başlatılamadı.")
        }
        file.parentFile?.mkdirs()
        record.startRecording()
        writer = Thread { loop(record) }.apply {
            name = "warp-scribe-pcm"
            start()
        }
    }

    /** Stops recording and waits for the WAV header to be finalized. */
    fun stop() {
        stopped = true
        writer?.join(3000)
        writer = null
    }

    fun cancel() {
        stop()
        file.delete()
    }

    private fun loop(record: AudioRecord) {
        val buffer = ShortArray(2048)
        val bytes = ByteArray(buffer.size * 2)
        var written = 0
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                raf.write(header(0))
                while (!stopped) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) break
                    var peak = 0
                    for (index in 0 until read) {
                        val value = abs(buffer[index].toInt())
                        if (value > peak) peak = value
                    }
                    amplitude = peak
                    ByteBuffer.wrap(bytes, 0, read * 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .put(buffer, 0, read)
                    raf.write(bytes, 0, read * 2)
                    written += read
                    frames = written
                }
                val dataSize = written * 2
                raf.seek(4)
                raf.write(int32Le(36 + dataSize))
                raf.seek(40)
                raf.write(int32Le(dataSize))
            }
        } finally {
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
        }
    }

    private fun header(dataSize: Int): ByteArray {
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(SAMPLE_RATE)
        buffer.putInt(SAMPLE_RATE * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        return buffer.array()
    }

    private fun int32Le(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private companion object {
        const val SAMPLE_RATE = 16000
    }
}
