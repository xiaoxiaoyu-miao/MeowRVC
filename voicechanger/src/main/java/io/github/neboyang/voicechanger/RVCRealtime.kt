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
    enum class RunMode { IDLE, REALTIME, VAD, RECORD_TO_FILE }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _runMode = MutableStateFlow(RunMode.IDLE)
    val runMode: StateFlow<RunMode> = _runMode

    val engine = RVCOnnxEngine()
    var f0UpKey: Int = 0
    var latencyMs: Int = 2000
    var onError: ((Throwable) -> Unit)? = null
    var onRecordSaved: ((File) -> Unit)? = null
    var audioManager: AudioManager? = null
    var realtimeMode: Boolean = false

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
        _runMode.value = if (realtimeMode) RunMode.REALTIME else RunMode.VAD

        // 通话模式：确保 VOICE_COMMUNICATION 录音源 + 免提扬声器路由真正生效
        audioManager?.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        audioManager?.isSpeakerphoneOn = true

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
        val ringBuf = ArrayBlockingQueue<FloatArray>(12)

        // AudioTrack 缓冲区增大到 8 倍最小缓冲
        val playBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioManager?.isSpeakerphoneOn = true
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(playBuf * 96)  // 从 4 倍改为 8 倍
            .build()
        track.play()

        // 录音线程（chunkSize/overlap 每次迭代按最新 latency/overlap 计算，拖动条即时生效）
        val recThread = Thread({
            var prevOverlap = FloatArray(0)
            while (_isRunning.value) {
                val rawLatency = latencyMs.coerceIn(80, 5000)
                val chunkSize = sr * rawLatency / 1000
                val overlapSize = if (engine.overlapDivisor <= 0) 0 else chunkSize / engine.overlapDivisor
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

        // 处理线程（带预填充和智能丢弃）
        var prevTail = ShortArray(0)
        var preFillCount = 0  // 用于预填充

        while (_isRunning.value) {
            // 智能丢弃：如果队列积压超过 2 个块，只取最新一个
            var chunk: FloatArray? = null
            while (_isRunning.value) {
                val polled = ringBuf.poll()
                if (polled == null) {
                    // 队列为空，等待
                    chunk = try { ringBuf.take() } catch (_: Exception) { break }
                    break
                } else {
                    chunk = polled
                    // 如果队列中还有更多，继续取，直到只剩一个
                    if (ringBuf.size <= 1) break
                }
            }
            if (chunk == null || !_isRunning.value) break

            val inp = FloatArray(chunk.size / 3) { (chunk[it * 3] + chunk[it * 3 + 1] + chunk[it * 3 + 2]) / 3f }
            try {
                val res = engine.infer(inp, f0UpKey)
                if (res != null && res.isNotEmpty()) {
                    val outLen = (res.size.toDouble() * 48000 / engine.targetSr).toInt()
                    val outShort = ShortArray(outLen)

                    for (i in 0 until outLen) {
                        val pos = (i.toDouble() * res.size) / outLen
                        val si = pos.toInt().coerceIn(0, res.size - 2)
                        val frac = (pos - si).toFloat()
                        val s = res[si] * (1f - frac) + res[si + 1] * frac
                        val s32 = (s * 32768f).toInt()
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

                    // 写入 AudioTrack
                    var written = 0
                    while (written < outShort.size) {
                        val w = track.write(outShort, written, outShort.size - written, AudioTrack.WRITE_NON_BLOCKING)
                        if (w < 0) {
                            throw java.io.IOException("AudioTrack write error: $w")
                        }
                        if (w > 0) {
                            written += w
                        } else {
                            Thread.sleep(5)
                        }
                    }

                    // 预填充：前 2 个块快速处理，让播放器有足够数据
                    preFillCount++
                    if (preFillCount < 2) {
                        // 不额外操作，继续循环
                    }
                } else {
                    // 推理失败，写入静音防止断流
                    val silent = ShortArray(chunk.size)
                    track.write(silent, 0, silent.size, AudioTrack.WRITE_NON_BLOCKING)
                }
            } catch (e: Exception) {
                onError?.invoke(e)
                break
            }
        }

        track.stop(); track.release()
        audioManager?.isSpeakerphoneOn = false
        try { rec.stop(); rec.release() } catch (_: Exception) {}
        recThread.join(2000)
    }

    private fun vadLoop() {
        // 保持不变
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
            // 停止时若已收集到音频，也要处理完这一段再退出
            if (list.isEmpty() && !_isRunning.value) break
            try { rec.stop(); rec.release() } catch (_: Exception) {}
            try {
                val total = list.sumOf { it.size }; val fa = FloatArray(total); var o = 0
                for (a in list) for (s in a) fa[o++] = s / 32768f
                val inp = FloatArray(fa.size / 3) { (fa[it * 3] + fa[it * 3 + 1] + fa[it * 3 + 2]) / 3f }
                val res = engine.infer(inp, f0UpKey)
                if (res != null && res.isNotEmpty()) {
                    val outLen = (res.size * 48 / 40).coerceAtMost(total)
                    val outShort = ShortArray(outLen)
                    for (i in 0 until outLen) {
                        val pos = (i.toDouble() * res.size) / outLen
                        val si = pos.toInt().coerceIn(0, res.size - 2)
                        val frac = (pos - si).toFloat()
                        val s = res[si] * (1f - frac) + res[si + 1] * frac
                        outShort[i] = (s * 32768f).toInt().coerceIn(-32768, 32767).toShort()
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

    /**
     * 录音 → 变声 → 保存到文件（不外放）。
     * 录音期间将每段 VAD 语音变声后的结果累积，stop() 时写入 [outFile]。
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecordToFile(outFile: File) {
        if (!engine.isLoaded()) { onError?.invoke(java.lang.IllegalStateException("Model not loaded")); return }
        if (_isRunning.value) return
        _isRunning.value = true
        _runMode.value = RunMode.RECORD_TO_FILE
        loopThread = Thread({ recordToFileLoop(outFile) }, "rvc-record-file")
        loopThread!!.start()
    }

    private fun recordToFileLoop(outFile: File) {
        val sr = 48000
        val bs = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val buf = ShortArray(bs)
        fun startRec() = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bs * 4).apply { startRecording() }
        var rec = startRec()
        var prevTail = ShortArray(0)
        val converted = java.io.ByteArrayOutputStream()

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
            // 停止时若已收集到音频，也要处理完这一段再退出
            if (list.isEmpty() && !_isRunning.value) break
            try { rec.stop(); rec.release() } catch (_: Exception) {}
            try {
                val total = list.sumOf { it.size }; val fa = FloatArray(total); var o = 0
                for (a in list) for (s in a) fa[o++] = s / 32768f
                val inp = FloatArray(fa.size / 3) { (fa[it * 3] + fa[it * 3 + 1] + fa[it * 3 + 2]) / 3f }
                val res = engine.infer(inp, f0UpKey)
                if (res != null && res.isNotEmpty()) {
                    val outLen = (res.size * 48 / 40).coerceAtMost(total)
                    val outShort = ShortArray(outLen)
                    for (i in 0 until outLen) {
                        val pos = (i.toDouble() * res.size) / outLen
                        val si = pos.toInt().coerceIn(0, res.size - 2)
                        val frac = (pos - si).toFloat()
                        val s = res[si] * (1f - frac) + res[si + 1] * frac
                        outShort[i] = (s * 32768f).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    val fadeLen = minOf(prevTail.size, outShort.size, engine.crossfadeSamples)
                    for (i in 0 until fadeLen) {
                        val a = prevTail[prevTail.size - fadeLen + i].toInt()
                        val b = outShort[i].toInt()
                        val f = (i.toFloat() / fadeLen)
                        outShort[i] = ((a * (1f - f) + b * f).toInt().coerceIn(-32768, 32767)).toShort()
                    }
                    prevTail = outShort.copyOfRange((outShort.size - engine.crossfadeSamples).coerceAtLeast(0), outShort.size)
                    for (s in outShort) { converted.write(s.toInt() and 0xFF); converted.write((s.toInt() shr 8) and 0xFF) }
                }
            } catch (e: Exception) { onError?.invoke(e) }
            if (_isRunning.value) rec = startRec()
        }
        try { rec.stop(); rec.release() } catch (_: Exception) {}

        if (converted.size() > 0) {
            val bytes = converted.toByteArray()
            val samples = ShortArray(bytes.size / 2)
            for (i in samples.indices) samples[i] = ((bytes[i * 2].toInt() and 0xFF) or ((bytes[i * 2 + 1].toInt() and 0xFF) shl 8)).toShort()
            val floats = FloatArray(samples.size) { samples[it] / 32768f }
            outFile.parentFile?.mkdirs()
            WavFile.write(outFile, floats, sr)
            onRecordSaved?.invoke(outFile)
        }
    }

    private fun playAudio(data: ShortArray, sr: Int) {
        audioManager?.isSpeakerphoneOn = true
        val playBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(playBuf * 48).build()
        if (track.state == AudioTrack.STATE_INITIALIZED) {
            track.play()
            track.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)
            val playMs = (data.size * 1000L / sr)
            try { Thread.sleep(playMs) } catch (_: InterruptedException) {}
            track.stop(); track.release()
        }
        audioManager?.isSpeakerphoneOn = false
    }

    fun stop() {
        _isRunning.value = false
        _runMode.value = RunMode.IDLE
        loopThread?.join(5000); loopThread = null
        audioManager?.isSpeakerphoneOn = false
        audioManager?.mode = android.media.AudioManager.MODE_NORMAL
    }
    fun release() { stop(); engine.unload() }
}