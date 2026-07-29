package io.github.neboyang.voicechanger

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
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
    var audioManager: AudioManager? = null

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
            val sr = 48000
            val bs = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val buf = ShortArray(bs)

            fun startRec(): AudioRecord {
                val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bs * 4)
                r.startRecording(); return r
            }

            var rec = startRec()

            while (_isRunning.value) {
                // === 录音阶段（直到 1 秒静音）===
                val list = mutableListOf<ShortArray>()
                var silenceFrames = 0
                val vadThreshold = 300

                while (_isRunning.value) {
                    val r = rec.read(buf, 0, buf.size)
                    if (r <= 0) continue
                    list.add(buf.copyOf(r))
                    var pos = 0
                    while (pos + 160 <= r) {
                        var energy = 0f
                        for (i in 0 until 160) { val s = buf[pos + i].toInt(); energy += (s * s).toFloat() }
                        energy = kotlin.math.sqrt(energy / 160f)
                        if (energy < 500f) { silenceFrames++; if (silenceFrames >= vadThreshold) { pos = -1; break } }
                        else silenceFrames = 0
                        pos += 160
                    }
                    if (pos == -1) break
                }
                if (!_isRunning.value) break

                // 停止录音（释放麦克风），播完再恢复
                rec.stop(); rec.release()

                // === 处理 + 外放 ===
                try {
                    val total = list.sumOf { it.size }; val fa = FloatArray(total); var o = 0
                    for (a in list) for (s in a) fa[o++] = s / 32768f
                    val inp = FloatArray(fa.size / 3) { fa[it * 3] }
                    val res = engine.infer(inp, f0UpKey)

                    if (res != null && res.isNotEmpty()) {
                        val outLen = (res.size * 48 / 40).coerceAtMost(total)
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

                        // 扬声器外放（此时无录音，系统走扬声器）
                        audioManager?.isSpeakerphoneOn = true
                        val playBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        val track = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setBufferSizeInBytes(playBuf * 4).build()
                        if (track.state == AudioTrack.STATE_INITIALIZED) {
                            track.play()
                            track.write(outShort, 0, outShort.size)
                            track.stop(); track.release()
                        }
                        audioManager?.isSpeakerphoneOn = false
                    }
                } catch (e: Exception) { onError?.invoke(e) }

                // 重新开始录音
                if (_isRunning.value) rec = startRec()
            }

            rec.stop(); rec.release()
        }, "rvc-loop")
        loopThread!!.start()
    }

    fun stop() {
        _isRunning.value = false
        loopThread?.join(5000); loopThread = null
    }

    fun release() { stop(); engine.unload() }
}
