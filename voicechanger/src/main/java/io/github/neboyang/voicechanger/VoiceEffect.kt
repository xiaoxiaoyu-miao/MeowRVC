package io.github.neboyang.voicechanger

/**
 * 变声效果参数。三个参数相互独立，可自由组合出新音色，
 * 调参思路见项目文档 `docs/voice-tuning.md`。
 *
 * @param pitchSemiTones 音调偏移（半音）。正值升调（女声/萝莉方向），
 *   负值降调（大叔方向）。±12 为一个八度，建议范围 [-24, +24]。
 *   换算：音调倍率 k 对应 `12 * log2(k)` 个半音。
 * @param tempo 节拍倍率（变速不变调）。1.0 原速，>1 加快，<1 放慢。
 * @param rate 速率倍率（变速且变调，类似磁带快放）。1.0 原速。
 */
data class VoiceEffect(
    val pitchSemiTones: Float = 0f,
    val tempo: Float = 1f,
    val rate: Float = 1f,
) {
    init {
        require(pitchSemiTones in -24f..24f) { "pitchSemiTones 超出范围 [-24, 24]: $pitchSemiTones" }
        require(tempo > 0.1f && tempo <= 10f) { "tempo 超出范围 (0.1, 10]: $tempo" }
        require(rate > 0.1f && rate <= 10f) { "rate 超出范围 (0.1, 10]: $rate" }
    }

    companion object {
        /** 原声，不做处理。 */
        @JvmField val NONE = VoiceEffect()

        /** 小猫：升 4 半音 + 速率 1.2 倍，尖细急促。 */
        @JvmField val KITTY = VoiceEffect(pitchSemiTones = 4f, tempo = 1.02f, rate = 1.2f)

        /** 女声（原 ROSE 预设，音调 2.1 倍 ≈ +12.8 半音），偏夸张的娃娃音。 */
        @JvmField val ROSE = VoiceEffect(pitchSemiTones = 12.8f)

        /** 男声变女声：+7 半音，自然一些的男转女推荐值。 */
        @JvmField val WOMAN = VoiceEffect(pitchSemiTones = 7f)

        /** 大叔：降 4 半音（原 UNCLE 预设，音调 0.8 倍 ≈ -3.9 半音）。 */
        @JvmField val UNCLE = VoiceEffect(pitchSemiTones = -3.9f)

        /** 女声变男声：-7 半音。 */
        @JvmField val MAN = VoiceEffect(pitchSemiTones = -7f)

        /** 汤姆猫：升 10 半音，节奏微调。 */
        @JvmField val TOM = VoiceEffect(pitchSemiTones = 10f, tempo = 1.005f, rate = 0.993f)

        /** 带中文名称的便捷映射；跨语言 UI 推荐使用 [VoicePreset]。 */
        @JvmField val PRESETS: Map<String, VoiceEffect> = linkedMapOf(
            "原声" to NONE,
            "小猫" to KITTY,
            "娃娃音" to ROSE,
            "女声" to WOMAN,
            "大叔" to UNCLE,
            "男声" to MAN,
            "汤姆猫" to TOM,
        )
    }
}

/**
 * 语言无关的内置预设标识。UI 应根据枚举值自行本地化名称，而不是依赖中文 [VoiceEffect.PRESETS] 键。
 */
enum class VoicePreset(val effect: VoiceEffect) {
    NONE(VoiceEffect.NONE),
    KITTY(VoiceEffect.KITTY),
    ROSE(VoiceEffect.ROSE),
    WOMAN(VoiceEffect.WOMAN),
    UNCLE(VoiceEffect.UNCLE),
    MAN(VoiceEffect.MAN),
    TOM(VoiceEffect.TOM),
}
