package io.mo.glassmic.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.neboyang.voicechanger.RVCOnnxEngine
import io.mo.glassmic.core.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RvcAudioSource(
    private val engine: RVCOnnxEngine? = null,
    private val f0UpKey: Int = 0
) : AudioSourceProvider {
    override val type = SourceType.FILE

    private val pipe = Pair<ByteBuffer, Any>(ByteBuffer.allocateDirect(65536), Object())
    private val writeBuf = ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN)
    private val writeLock = Any()
    private var record: AudioRecord? = null
    private var running = false

    private val readBuf = object {
        val buffer = ShortArray(4096)
        var writePos = 0
        var readPos = 0
        val data = FloatArray(4096)
    }

    fun start() {
        if (running) return
        running = true
        val sr = 48000
        val bufSize = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096) * 4
        record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        record?.startRecording()

        GlobalScope.launch(Dispatchers.IO) {
            val tmpShort = ShortArray(4096)
            val accumFrameCount = 50
            val hop = 160
            val downsampleRate = 3
            val accumSize = accumFrameCount * hop * downsampleRate // 24000 @48kHz → 8000 @16kHz
            val accum = FloatArray(accumSize)
            var idx = 0

            while (isActive && running) {
                val rec = record ?: break
                val read = rec.read(tmpShort, 0, tmpShort.size)
                if (read <= 0) continue

                for (i in 0 until read) {
                    if (idx < accum.size) accum[idx++] = tmpShort[i] / 32768f
                }

                if (idx >= accum.size) {
                    idx = 0
                    val inp = FloatArray(accum.size / downsampleRate) { accum[it * downsampleRate] }
                    try {
                        val result = engine?.infer(inp, f0UpKey)
                        if (result != null && result.isNotEmpty()) {
                            val outLen = result.size * 48 / 40
                            synchronized(writeLock) {
                                writeBuf.clear()
                                for (i in 0 until outLen.coerceAtMost(writeBuf.capacity() / 2)) {
                                    val si = ((i.toLong() * result.size) / outLen).toInt().coerceIn(0, result.size - 1)
                                    val s32 = (result[si] * 32768f).toInt()
                                    writeBuf.putShort(s32.coerceIn(-32768, 32767).toShort())
                                }
                                writeBuf.flip()
                                (writeLock as java.lang.Object).notifyAll()
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun stop() {
        running = false
        try { record?.stop() } catch (_: Exception) {}
        record?.release()
        record = null
    }

    override suspend fun read(out: ByteBuffer, sampleRate: Int, channels: Int): Int {
        synchronized(writeLock) {
            if (writeBuf.remaining() == 0) {
                (writeLock as java.lang.Object).wait(20)
            }
            if (writeBuf.remaining() == 0) return 0
            val n = minOf(writeBuf.remaining(), out.remaining())
            val tmp = ByteArray(n)
            writeBuf.get(tmp)
            out.put(tmp)
            return n
        }
    }

    override fun release() { stop() }
}
