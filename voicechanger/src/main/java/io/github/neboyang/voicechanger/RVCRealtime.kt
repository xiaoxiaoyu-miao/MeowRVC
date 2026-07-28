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
import java.util.concurrent.ArrayBlockingQueue

class RVCRealtime {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    val engine = RVCOnnxEngine()
    private var recThread: Thread? = null
    private var procThread: Thread? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var modelLoaded = false

    var f0UpKey: Int = 0
    var latencyMs: Int = 1000
    var onError: ((Throwable) -> Unit)? = null

    fun loadModel(modelDir: File): Boolean {
        modelLoaded = engine.load(modelDir)
        if (modelLoaded) engine.startServer()
        return modelLoaded
    }

    /** 反馈抑制：记录播放时间戳，录音时跳过自身外放 */
    @Volatile private var lastPlayMs = 0L

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (!modelLoaded) { onError?.invoke(java.lang.IllegalStateException("Model not loaded")); return }
        if (_isRunning.value) return

        val sr = 48000
        val recBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recBuf * 4)
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

        val rawLatencyMs = latencyMs.coerceIn(500, 10000)
        val accumSize = sr * rawLatencyMs / 1000
        val overlapSize = accumSize / 4
        val ringBuf = ArrayBlockingQueue<FloatArray>(6)

        // 录音线程（带重叠）
        recThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(4096)
            var prevOverlap = FloatArray(0)
            while (_isRunning.value) {
                val accum = FloatArray(accumSize)
                var idx = 0
                for (s in prevOverlap) accum[idx++] = s
                while (idx < accumSize && _isRunning.value) {
                    val read = record!!.read(buf, 0, buf.size)
                    if (read <= 0) continue
                    for (i in 0 until read) { if (idx < accumSize) accum[idx++] = buf[i] / 32768f }
                }
                val tail = (accum.size - overlapSize).coerceAtLeast(0)
                prevOverlap = accum.copyOfRange(tail, accum.size)
                if (_isRunning.value) ringBuf.put(accum)
            }
        }, "rvc-record")

        // 推理+播放线程
        procThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            var prevTail = ShortArray(0)
            while (_isRunning.value) {
                val accum = ringBuf.take()
                // 3点平均降采样 48→16kHz
                val inp = FloatArray(accum.size / 3) { (accum[it * 3] + accum[it * 3 + 1] + accum[it * 3 + 2]) / 3f }
                try {
                    val result = engine.infer(inp, f0UpKey)
                    if (result != null && result.isNotEmpty()) {
                        val outLen = (result.size * 48 / 40).coerceAtMost(accumSize)
                        val outShort = ShortArray(outLen)
                        val vol = engine.volume
                        for (i in 0 until outLen) {
                            val pos = (i.toDouble() * result.size) / outLen
                            val si = pos.toInt().coerceIn(0, result.size - 2)
                            val frac = (pos - si).toFloat()
                            val s = result[si] * (1f - frac) + result[si + 1] * frac
                            val s32 = ((s * vol) * 32768f).toInt()
                            outShort[i] = s32.coerceIn(-32768, 32767).toShort()
                        }
                        // 与上一块尾部淡入淡出
                        val fadeLen = minOf(prevTail.size, outShort.size, 768)
                        for (i in 0 until fadeLen) {
                            val a = prevTail[prevTail.size - fadeLen + i].toInt()
                            val b = outShort[i].toInt()
                            val f = (i.toFloat() / fadeLen)
                            outShort[i] = ((a * (1f - f) + b * f).toInt().coerceIn(-32768, 32767)).toShort()
                        }
                        prevTail = outShort.copyOfRange((outShort.size - 768).coerceAtLeast(0), outShort.size)

                        var written = 0
                        while (written < outShort.size && _isRunning.value) {
                            val w = track!!.write(outShort, written, outShort.size - written, AudioTrack.WRITE_NON_BLOCKING)
                            if (w > 0) written += w
                            else Thread.sleep(5)
                        }
                        lastPlayMs = System.currentTimeMillis()
                    }
                } catch (e: Exception) { onError?.invoke(e) }
            }
        }, "rvc-process")

        recThread!!.start()
        procThread!!.start()
    }

    fun stop() {
        _isRunning.value = false
        recThread?.join(3000); recThread = null
        procThread?.join(3000); procThread = null
        try { record?.stop() } catch (_: Exception) {}
        record?.release(); record = null
        try { track?.stop() } catch (_: Exception) {}
        track?.release(); track = null
    }

    fun release() { stop(); engine.unload() }
}
