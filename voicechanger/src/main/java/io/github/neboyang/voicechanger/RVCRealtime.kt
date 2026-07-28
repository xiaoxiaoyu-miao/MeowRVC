package io.github.neboyang.voicechanger

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class RVCRealtime {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    val engine = RVCOnnxEngine()
    private var captureThread: Thread? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var modelLoaded = false

    var f0UpKey: Int = 0
    var latencyMs: Int = 1000
    var onError: ((Throwable) -> Unit)? = null

    fun loadModel(modelDir: File): Boolean {
        modelLoaded = engine.load(modelDir)
        if (modelLoaded) {
            engine.startServer()  // Start LSPosed socket server
        }
        return modelLoaded
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (!modelLoaded) { onError?.invoke(java.lang.IllegalStateException("Model not loaded")); return }
        if (_isRunning.value) return

        val sr = 48000
        val recBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

        record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recBuf * 4)
        // 开启声学回声消除
        try { android.media.audiofx.AcousticEchoCanceler.create(record!!.audioSessionId)?.enabled = true } catch (_: Exception) {}

        val playBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sr)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(playBuf * 16).build()

        _isRunning.value = true
        record!!.startRecording()
        track!!.play()

        // 双缓冲：读缓冲和写缓冲交替，避免推理时丢数据
        val bufCount = 3
        val bufSize = sr * latencyMs / 1000
        val bufs = Array(bufCount) { FloatArray(bufSize) }
        var fillIdx = 0
        var procIdx = -1
        var readIdx = 0
        val lock = Object()

        captureThread = Thread({
            val shortBuf = ShortArray(4096)
            val outBufs = Array(bufCount) { ShortArray(0) }

            while (_isRunning.value) {
                // 填充满当前缓冲
                var filled = false
                while (fillIdx < bufs.size && !filled) {
                    val read = record!!.read(shortBuf, 0, shortBuf.size)
                    if (read <= 0) continue
                    for (i in 0 until read) {
                        if (readIdx < bufs[fillIdx].size) {
                            bufs[fillIdx][readIdx++] = shortBuf[i] / 32768f
                        }
                    }
                    if (readIdx >= bufs[fillIdx].size) {
                        readIdx = 0
                        procIdx = fillIdx
                        fillIdx++
                        filled = true
                    }
                }

                // 处理已满的缓冲
                if (procIdx >= 0 && procIdx < bufs.size) {
                    val inp = FloatArray(bufs[procIdx].size / 3) { bufs[procIdx][it * 3] }
                    try {
                        val result = engine.infer(inp, f0UpKey)
                        if (result != null && result.isNotEmpty()) {
                            val outLen = (result.size * 48 / 40).coerceAtMost(bufs[procIdx].size)
                            val outShort = ShortArray(outLen)
                            val vol = engine.volume
                            for (i in 0 until outLen) {
                                val si = ((i.toLong() * result.size) / outLen).toInt().coerceIn(0, result.size - 1)
                                val s32 = ((result[si] * vol) * 32768f).toInt()
                                outShort[i] = s32.coerceIn(-32768, 32767).toShort()
                            }
                            outBufs[procIdx] = outShort
                        }
                    } catch (e: Exception) { onError?.invoke(e) }
                    if (fillIdx >= bufs.size) fillIdx = 0
                    procIdx = -1
                }

                // 播放上一轮已处理好的缓冲
                for (i in 0 until bufs.size) {
                    if (outBufs[i].isNotEmpty()) {
                        track!!.write(outBufs[i], 0, outBufs[i].size)
                        outBufs[i] = ShortArray(0)
                    }
                }
            }
        }, "rvc-realtime")
        captureThread!!.start()
    }

    fun stop() {
        _isRunning.value = false
        captureThread?.join(2000); captureThread = null
        try { record?.stop() } catch (_: Exception) {}
        record?.release(); record = null
        try { track?.stop() } catch (_: Exception) {}
        track?.release(); track = null
    }

    fun release() { stop(); engine.unload() }
}
