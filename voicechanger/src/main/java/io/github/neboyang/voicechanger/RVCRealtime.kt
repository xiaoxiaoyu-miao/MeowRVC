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
            .setBufferSizeInBytes(playBuf * 8).build()

        _isRunning.value = true
        record!!.startRecording()
        track!!.play()

        captureThread = Thread({
            val accumSize = sr * latencyMs / 1000
            val accum = FloatArray(accumSize)
            var idx = 0
            val shortBuf = ShortArray(4096)

            while (_isRunning.value) {
                val read = record!!.read(shortBuf, 0, shortBuf.size)
                if (read <= 0) continue
                for (i in 0 until read) { if (idx < accum.size) accum[idx++] = shortBuf[i] / 32768f }
                if (idx >= accum.size) {
                    idx = 0
                    try {
                        val inp = FloatArray(accum.size / 3) { accum[it * 3] }
                        val result = engine.infer(inp, f0UpKey)
                        if (result != null && result.isNotEmpty()) {
                            // 反馈抑制：输出后清空前段累积（打断声学反馈环路）
                            val feedbackGuard = accum.size / 2
                            for (i in 0 until feedbackGuard) accum[i] = 0f

                            val outLen = result.size * 48 / 40
                            val outShort = ShortArray(outLen)
                            val vol = engine.volume
                            for (i in 0 until outLen) {
                                val si = ((i.toLong() * result.size) / outLen).toInt().coerceIn(0, result.size - 1)
                                val s32 = ((result[si] * vol) * 32768f).toInt()
                                outShort[i] = s32.coerceIn(-32768, 32767).toShort()
                            }
                            track!!.write(outShort, 0, outShort.size)
                        }
                    } catch (e: Exception) { onError?.invoke(e) }
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
