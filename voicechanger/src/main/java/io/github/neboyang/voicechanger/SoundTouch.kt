package io.github.neboyang.voicechanger

/**
 * SoundTouch 音频处理库的 Kotlin/JNI 封装。
 *
 * 采样格式固定为 16-bit PCM（native 层以 SOUNDTOUCH_INTEGER_SAMPLES 编译，
 * `short[]` 直接透传，无格式转换开销）。
 *
 * 实例**非线程安全**，请在单一线程内使用；用完必须调用 [close]（或配合
 * `use { }`）释放 native 内存。
 *
 * ```kotlin
 * SoundTouch(sampleRate = 44100, channels = 1).use { st ->
 *     st.setPitchSemiTones(6f)
 *     st.putSamples(pcm)
 *     val buf = ShortArray(4096)
 *     var n = st.receiveSamples(buf)
 *     ...
 *     st.flush()
 * }
 * ```
 */
class SoundTouch(sampleRate: Int, private val channels: Int) : AutoCloseable {

    private var handle: Long = 0L

    init {
        require(sampleRate in 8000..192000) { "非法采样率: $sampleRate" }
        require(channels == 1 || channels == 2) { "声道数只支持 1 或 2: $channels" }
        val created = nativeNew()
        try {
            nativeSetSampleRate(created, sampleRate)
            nativeSetChannels(created, channels)
            handle = created
        } catch (t: Throwable) {
            nativeDelete(created)
            throw t
        }
    }

    /** 变调（不变速）。单位半音，正值升调、负值降调，建议范围 [-24, +24]。 */
    fun setPitchSemiTones(semiTones: Float) {
        require(semiTones in -24f..24f) { "pitchSemiTones 超出范围 [-24, 24]: $semiTones" }
        nativeSetPitchSemiTones(checkedHandle(), semiTones)
    }

    /** 变速（不变调）。1.0 为原速，范围 (0.1, 10]。 */
    fun setTempo(tempo: Float) {
        require(tempo > 0.1f && tempo <= 10f) { "tempo 超出范围 (0.1, 10]: $tempo" }
        nativeSetTempo(checkedHandle(), tempo)
    }

    /** 变速率（同时改变速度与音调，类似磁带快放/慢放）。1.0 为原速。 */
    fun setRate(rate: Float) {
        require(rate > 0.1f && rate <= 10f) { "rate 超出范围 (0.1, 10]: $rate" }
        nativeSetRate(checkedHandle(), rate)
    }

    /** 一次性应用 [VoiceEffect] 的全部参数。 */
    fun applyEffect(effect: VoiceEffect) {
        setRate(effect.rate)
        setTempo(effect.tempo)
        setPitchSemiTones(effect.pitchSemiTones)
    }

    /**
     * 送入待处理的 PCM 采样。
     *
     * @param samples 交错排列的 16-bit PCM 数据
     * @param frames  帧数（每帧 = channels 个采样），默认取整个数组
     */
    fun putSamples(samples: ShortArray, frames: Int = samples.size / channels) {
        require(frames >= 0 && frames * channels <= samples.size) { "frames 越界: $frames" }
        nativePutSamples(checkedHandle(), samples, frames)
    }

    /**
     * 取出已处理的 PCM 采样，写入 [buffer]。
     *
     * @return 实际取出的帧数，0 表示暂无可取数据
     */
    fun receiveSamples(buffer: ShortArray): Int =
        nativeReceiveSamples(checkedHandle(), buffer, buffer.size / channels)

    /**
     * 冲出管线内部残留的尾部采样。所有输入送完后必须调用一次，
     * 再继续 [receiveSamples] 取空，否则结尾会被截断。
     */
    fun flush() = nativeFlush(checkedHandle())

    /** 管线内已处理、等待取出的帧数。 */
    fun availableFrames(): Int = nativeNumSamples(checkedHandle())

    override fun close() {
        if (handle != 0L) {
            nativeDelete(handle)
            handle = 0L
        }
    }

    private fun checkedHandle(): Long {
        check(handle != 0L) { "SoundTouch 实例已释放" }
        return handle
    }

    companion object {
        init {
            System.loadLibrary("soundtouch")
        }

        /** 底层 SoundTouch 库版本号，如 "2.4.1"。 */
        @JvmStatic
        val version: String
            get() = nativeGetVersion()

        @JvmStatic private external fun nativeNew(): Long
        @JvmStatic private external fun nativeDelete(handle: Long)
        @JvmStatic private external fun nativeSetSampleRate(handle: Long, sampleRate: Int)
        @JvmStatic private external fun nativeSetChannels(handle: Long, channels: Int)
        @JvmStatic private external fun nativeSetTempo(handle: Long, tempo: Float)
        @JvmStatic private external fun nativeSetRate(handle: Long, rate: Float)
        @JvmStatic private external fun nativeSetPitchSemiTones(handle: Long, semiTones: Float)
        @JvmStatic private external fun nativePutSamples(handle: Long, samples: ShortArray, numFrames: Int)
        @JvmStatic private external fun nativeReceiveSamples(handle: Long, out: ShortArray, maxFrames: Int): Int
        @JvmStatic private external fun nativeFlush(handle: Long)
        @JvmStatic private external fun nativeNumSamples(handle: Long): Int
        @JvmStatic private external fun nativeGetVersion(): String
    }
}
