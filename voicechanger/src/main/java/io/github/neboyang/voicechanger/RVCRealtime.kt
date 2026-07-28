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
    var f0UpKey: Int = 0
    var latencyMs: Int = 1000
    var onError: ((Throwable) -> Unit)? = null

    private var recordThread: Thread? = null
    private var playThread: Thread? = null

    fun loadModel(modelDir: File): Boolean {
        val ok = engine.load(modelDir)
        if (ok) engine.startServer()
        return ok
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (!engine.isLoaded()) { onError?.invoke(java.lang.IllegalStateException("Model not loaded")); return }
        if (_isRunning.value) return
        _isRunning.value = true

        recordThread = Thread({
            val sr = 48000
            val bs = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bs * 2)
            rec.startRecording()

            val list = mutableListOf<ShortArray>()
            val buf = ShortArray(bs)
            var silenceFrames = 0
            val vadThreshold = 300 // 1秒停顿

            while (_isRunning.value) {
                val r = rec.read(buf, 0, buf.size)
                if (r <= 0) continue
                list.add(buf.copyOf(r))
                var energy = 0f; val n = minOf(r, 160)
                for (i in 0 until n) { val s = buf[i].toInt(); energy += (s * s).toFloat() }
                energy = kotlin.math.sqrt(energy / n)
                if (energy < 500f) silenceFrames++ else silenceFrames = 0
                if (silenceFrames >= vadThreshold) { _isRunning.value = false; break }
            }

            rec.stop(); rec.release()

            // 处理录音
            val total = list.sumOf { it.size }; val fa = FloatArray(total); var o = 0
            for (a in list) for (s in a) fa[o++] = s / 32768f
            val inp = FloatArray(fa.size / 3) { fa[it * 3] }
            try {
                val res = engine.infer(inp, f0UpKey)
                if (res != null && res.isNotEmpty()) {
                    // 外放
                    val outLen = (res.size * 48 / 40).coerceAtMost(total)
                    val outShort = ShortArray(outLen)
                    for (i in 0 until outLen) {
                        val pos = (i.toDouble() * res.size) / outLen
                        val si = pos.toInt().coerceIn(0, res.size - 2)
                        val frac = (pos - si).toFloat()
                        val s = res[si] * (1f - frac) + res[si + 1] * frac
                        val s32 = ((s * engine.volume) * 32768f).toInt()
                        outShort[i] = s32.coerceIn(-32768, 32767).toShort()
                    }
                    playThread = Thread({
                        val playBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        val track = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setBufferSizeInBytes(playBuf * 4).build()
                        track.play()
                        track.write(outShort, 0, outShort.size)
                        track.stop(); track.release()
                    }, "rvc-play").apply { start(); join() }
                }
            } catch (e: Exception) { onError?.invoke(e) }
        }, "rvc-record")
        recordThread!!.start()
    }

    fun stop() {
        _isRunning.value = false
        recordThread?.join(3000); recordThread = null
        playThread?.join(3000); playThread = null
    }

    fun release() { stop(); engine.unload() }
}
