package io.github.neboyang.voicechanger

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.ArrayBlockingQueue

class RVCRealtime {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    val engine = RVCOnnxEngine()
    var f0UpKey: Int = 0
    var latencyMs: Int = 1000
    var onError: ((Throwable) -> Unit)? = null
    var audioManager: AudioManager? = null
    var realtimeMode: Boolean = false  // true=实时, false=VAD

    private var loopThread: Thread? = null

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

        loopThread = Thread({
            if (realtimeMode) realtimeLoop() else vadLoop()
        }, "rvc-loop")
        loopThread!!.start()
    }

    private fun realtimeLoop() {
        val sr = 48000
        val bs = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bs * 4)
        rec.startRecording()
        val buf = ShortArray(bs)
        val rawLatency = latencyMs.coerceIn(500, 5000)
        val chunkSize = sr * rawLatency / 1000
        val overlapSize = if (engine.overlapDivisor <= 0) 0 else chunkSize / engine.overlapDivisor
        val ringBuf = ArrayBlockingQueue<FloatArray>(12)

        // 持久 AudioTrack（不重复创建销毁）
        val playBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioManager?.isSpeakerphoneOn = true
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(playBuf * 96).build()
        track.play()

        // 录音线程
        val recThread = Thread({
            var prevOverlap = FloatArray(0)
            while (_isRunning.value) {
                val chunk = FloatArray(chunkSize)
                var idx = 0
                for (s in prevOverlap) chunk[idx++] = s
                while (idx < chunkSize && _isRunning.value) {
                    val r = rec.read(buf, 0, buf.size)
                    if (r <= 0) continue
                    for (i in 0 until r) { if (idx < chunkSize) chunk[idx++] = buf[i] / 32768f }
                }
                val tail = (chunk.size - overlapSize).coerceAtLeast(0)
                prevOverlap = chunk.copyOfRange(tail, chunk.size)
                if (_isRunning.value) ringBuf.put(chunk)
            }
        }, "rvc-record").apply { start() }

        // 处理线程（非阻塞写入）
        var prevTail = ShortArray(0)
        while (_isRunning.value) {
            val chunk = try { ringBuf.take() } catch (_: Exception) { break }
            val inp = FloatArray(chunk.size / 3) { (chunk[it * 3] + chunk[it * 3 + 1] + chunk[it * 3 + 2]) / 3f }
            try {
                val res = engine.infer(inp, f0UpKey)
                if (res != null && res.isNotEmpty()) {
                    val outLen = (res.size * 48 / 40).coerceAtMost(chunkSize)
                    val outShort = ShortArray(outLen)
                    val vol = engine.volume
                    for (i in 0 until outLen) {
                        val pos = (i.toDouble() * res.size) / outLen
                        val si = pos.toInt().coerceIn(0, res.size - 2)
                        val frac = (pos - si).toFloat()
                        val s = res[si] * (1f - frac) + res[si + 1] * frac
                        val s32 = ((s * vol) * 32768f).toInt()
                        outShort[i] = s32.coerceIn(-32768, 32767).toShort()
                    }
                    val fadeLen = minOf(prevTail.size, outShort.size, 512)
                    for (i in 0 until fadeLen) {
                        val a = prevTail[prevTail.size - fadeLen + i].toInt()
                        val b = outShort[i].toInt()
                        val f = (i.toFloat() / fadeLen)
                        outShort[i] = ((a * (1f - f) + b * f).toInt().coerceIn(-32768, 32767)).toShort()
                    }
                    prevTail = outShort.copyOfRange((outShort.size - 1024).coerceAtLeast(0), outShort.size)
                    // 非阻塞写入，不会卡住处理线程
                    var written = 0
                    while (written < outShort.size) {
                        val w = track.write(outShort, written, outShort.size - written, AudioTrack.WRITE_NON_BLOCKING)
                        if (w > 0) written += w
                        else Thread.sleep(5)
                    }
                }
            } catch (e: Exception) { onError?.invoke(e) }
        }
        track.stop(); track.release()
        audioManager?.isSpeakerphoneOn = false
        try { rec.stop(); rec.release() } catch (_: Exception) {}
        recThread.join(2000)
    }

    private fun vadLoop() {
        val sr = 48000
        val bs = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val buf = ShortArray(bs)
        fun startRec() = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bs * 4).apply { startRecording() }
        var rec = startRec()
        var prevTail = ShortArray(0)

        while (_isRunning.value) {
            val list = mutableListOf<ShortArray>()
            var silenceFrames = 0
            val vadTh = engine.vadSilenceFrames
            val vadEn = engine.vadEnergyThreshold
            while (_isRunning.value) {
                val r = rec.read(buf, 0, buf.size); if (r <= 0) continue
                list.add(buf.copyOf(r))
                var pos = 0
                while (pos + 160 <= r) {
                    var energy = 0f
                    for (i in 0 until 160) { val s = buf[pos + i].toInt(); energy += (s * s).toFloat() }
                    energy = kotlin.math.sqrt(energy / 160f)
                    if (energy < vadEn) { silenceFrames++; if (silenceFrames >= vadTh) { pos = -1; break } }
                    else silenceFrames = 0; pos += 160
                }
                if (pos == -1) break
            }
            if (!_isRunning.value) break
            try { rec.stop(); rec.release() } catch (_: Exception) {}
            try {
                val total = list.sumOf { it.size }; val fa = FloatArray(total); var o = 0
                for (a in list) for (s in a) fa[o++] = s / 32768f
                val inp = FloatArray(fa.size / 3) { (fa[it * 3] + fa[it * 3 + 1] + fa[it * 3 + 2]) / 3f }
                val res = engine.infer(inp, f0UpKey)
                if (res != null && res.isNotEmpty()) {
                    val outLen = (res.size * 48 / 40).coerceAtMost(total)
                    val outShort = ShortArray(outLen); val vol = engine.volume
                    for (i in 0 until outLen) {
                        val pos = (i.toDouble() * res.size) / outLen
                        val si = pos.toInt().coerceIn(0, res.size - 2)
                        val frac = (pos - si).toFloat()
                        val s = res[si] * (1f - frac) + res[si + 1] * frac
                        outShort[i] = ((s * vol) * 32768f).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    val fadeLen = minOf(prevTail.size, outShort.size, engine.crossfadeSamples)
                    for (i in 0 until fadeLen) {
                        val a = prevTail[prevTail.size - fadeLen + i].toInt()
                        val b = outShort[i].toInt()
                        val f = (i.toFloat() / fadeLen)
                        outShort[i] = ((a * (1f - f) + b * f).toInt().coerceIn(-32768, 32767)).toShort()
                    }
                    prevTail = outShort.copyOfRange((outShort.size - engine.crossfadeSamples).coerceAtLeast(0), outShort.size)
                    playAudio(outShort, sr)
                }
            } catch (e: Exception) { onError?.invoke(e) }
            if (_isRunning.value) rec = startRec()
        }
        try { rec.stop(); rec.release() } catch (_: Exception) {}
    }

    private fun playAudio(data: ShortArray, sr: Int) {
        audioManager?.isSpeakerphoneOn = true
        val playBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(playBuf * 4).build()
        if (track.state == AudioTrack.STATE_INITIALIZED) {
            track.play(); track.write(data, 0, data.size); track.stop(); track.release()
        }
        audioManager?.isSpeakerphoneOn = false
    }

    fun stop() {
        _isRunning.value = false
        loopThread?.join(5000); loopThread = null
    }
    fun release() { stop(); engine.unload() }
}
