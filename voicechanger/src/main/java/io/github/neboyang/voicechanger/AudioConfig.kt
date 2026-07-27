package io.github.neboyang.voicechanger

/**
 * PCM 音频参数。整条链路（录音 → 变声 → 编码）共用同一份配置。
 *
 * 采样格式固定为 16-bit（PCM_16BIT）。
 */
data class AudioConfig(
    /** 采样率（Hz）。默认 44100，人声场景 16000 也足够且更省资源。 */
    val sampleRate: Int = 44100,
    /** 声道数，1（单声道）或 2（立体声）。录音变声场景建议单声道。 */
    val channels: Int = 1,
) {
    init {
        require(sampleRate in 8000..192000) { "非法采样率: $sampleRate" }
        require(channels == 1 || channels == 2) { "声道数只支持 1 或 2: $channels" }
    }

    /** 每帧字节数（16-bit = 2 字节 × 声道数）。 */
    val bytesPerFrame: Int get() = channels * 2

    /** 每秒字节数。 */
    val bytesPerSecond: Int get() = sampleRate * bytesPerFrame
}
