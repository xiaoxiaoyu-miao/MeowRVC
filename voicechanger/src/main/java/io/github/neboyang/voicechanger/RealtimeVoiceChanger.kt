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
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * 实时（流式）变声：麦克风 → SoundTouch 变调 → 扬声器/耳机，边说边听。
 *
 * ```kotlin
 * val realtime = RealtimeVoiceChanger()
 * realtime.pitchSemiTones = 7f      // 运行中可随时调整，实时生效
 * realtime.start()                  // 需要 RECORD_AUDIO 权限
 * ...
 * realtime.stop()
 * ```
 *
 * 注意事项：
 * - **请佩戴耳机**，否则扬声器声音会被麦克风拾取形成啸叫回授
 * - 实时模式只支持**变调**（pitch）。tempo/rate 会改变输出时长，
 *   在实时链路中会导致缓冲区无限堆积或断流，因此固定为 1.0
 * - 采集源使用 VOICE_COMMUNICATION，多数设备会启用系统回声消除
 * - 端到端延迟 ≈ 设备音频缓冲 + SoundTouch 算法固有延迟（约 100ms）
 * - 与 [VoiceRecorder] 都占用麦克风，二者不要同时启动
 */
class RealtimeVoiceChanger(val config: AudioConfig = AudioConfig()) {

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    /** 输入音量，RMS 归一化到 0~1。 */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    /** 音频线程内发生错误时的回调（在音频线程调用），此后自动停止。 */
    @Volatile
    var onError: ((Throwable) -> Unit)? = null

    /** 音调偏移（半音），运行中修改实时生效。范围 [-24, 24]。 */
    @Volatile
    var pitchSemiTones: Float = 0f
        set(value) {
            require(value in -24f..24f) { "pitchSemiTones 超出范围 [-24, 24]: $value" }
            field = value
        }

    @Volatile private var stopRequested = false
    private val lifecycleLock = Any()
    private var worker: Thread? = null
    @Volatile private var activeRecord: AudioRecord? = null
    @Volatile private var activeTrack: AudioTrack? = null

    /**
     * 启动实时变声。
     *
     * @throws IllegalStateException 已在运行，或音频设备初始化失败（通常是未授予录音权限）
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        synchronized(lifecycleLock) {
            check(worker == null && !_isRunning.value) { "实时变声已在运行或正在停止" }

            val channelIn = if (config.channels == 1)
                AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val channelOut = if (config.channels == 1)
                AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

            val minIn = AudioRecord.getMinBufferSize(
                config.sampleRate, channelIn, AudioFormat.ENCODING_PCM_16BIT)
            val minOut = AudioTrack.getMinBufferSize(
                config.sampleRate, channelOut, AudioFormat.ENCODING_PCM_16BIT)
            check(minIn > 0 && minOut > 0) { "设备不支持该音频配置: $config" }

            var record: AudioRecord? = null
            var track: AudioTrack? = null
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    config.sampleRate, channelIn, AudioFormat.ENCODING_PCM_16BIT, minIn * 2)
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord 初始化失败，请确认已授予 RECORD_AUDIO 权限")
                }

                track = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(config.sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(channelOut)
                        .build(),
                    minOut * 2,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE)
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioTrack 初始化失败")
                }

                stopRequested = false
                activeRecord = record
                activeTrack = track
                _isRunning.value = true
                val readyRecord = checkNotNull(record)
                val readyTrack = checkNotNull(track)
                worker = Thread({ loop(readyRecord, readyTrack) }, "RealtimeVoiceChanger").also { it.start() }
            } catch (t: Throwable) {
                runCatching { record?.release() }
                runCatching { track?.release() }
                activeRecord = null
                activeTrack = null
                _isRunning.value = false
                throw t
            }
        }
    }

    /** 停止实时变声，等待音频线程退出（毫秒级）。可重复调用。 */
    fun stop() {
        stopRequested = true
        val runningWorker = synchronized(lifecycleLock) { worker }
        runCatching { activeRecord?.stop() }
        runCatching { activeTrack?.stop() }
        if (runningWorker != null && runningWorker !== Thread.currentThread()) {
            runningWorker.join(2000)
        }
    }

    private fun loop(record: AudioRecord, track: AudioTrack) {
        try {
            record.startRecording()
            track.play()

            // 20ms 一块，兼顾延迟与调度开销
            val chunk = ShortArray(config.sampleRate / 50 * config.channels)
            val out = ShortArray(chunk.size * 4)
            var appliedPitch = Float.NaN

            SoundTouch(config.sampleRate, config.channels).use { st ->
                while (!stopRequested) {
                    val pitch = pitchSemiTones
                    if (pitch != appliedPitch) {
                        st.setPitchSemiTones(pitch)
                        appliedPitch = pitch
                    }

                    val read = record.read(chunk, 0, chunk.size)
                    if (read < 0) throw IllegalStateException("AudioRecord.read 失败: $read")
                    if (read == 0) continue

                    _amplitude.value = rms(chunk, read)

                    st.putSamples(chunk, read / config.channels)
                    while (true) {
                        val frames = st.receiveSamples(out)
                        if (frames <= 0) break
                        writeFully(track, out, frames * config.channels)
                    }
                }
            }
        } catch (t: Throwable) {
            if (!stopRequested) runCatching { onError?.invoke(t) }
        } finally {
            runCatching { record.stop() }
            record.release()
            runCatching { track.stop() }
            track.release()
            _amplitude.value = 0f
            _isRunning.value = false
            synchronized(lifecycleLock) {
                if (activeRecord === record) activeRecord = null
                if (activeTrack === track) activeTrack = null
                if (worker === Thread.currentThread()) worker = null
            }
        }
    }

    private fun writeFully(track: AudioTrack, buffer: ShortArray, length: Int) {
        var offset = 0
        while (offset < length && !stopRequested) {
            val written = track.write(buffer, offset, length - offset)
            if (written < 0) throw IllegalStateException("AudioTrack.write 失败: $written")
            if (written == 0) continue
            offset += written
        }
    }

    private fun rms(buffer: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val s = buffer[i].toDouble()
            sum += s * s
        }
        return (sqrt(sum / length) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }
}
