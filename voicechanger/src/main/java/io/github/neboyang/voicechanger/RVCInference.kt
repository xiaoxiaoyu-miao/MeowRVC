package io.github.neboyang.voicechanger

import java.io.File

/**
 * JNI bridge to the native RVC inference engine.
 *
 * Usage:
 *   val rvc = RVCInference()
 *   rvc.init(modelDir, config)
 *   val output = rvc.infer(inputAudio, f0UpKey = 0)
 *   rvc.release()
 */
class RVCInference {
    data class Config(
        val version: String = "v2",
        val ifF0: Boolean = true,
        val featDim: Int = 768,
        val targetSr: Int = 40000,
        val interChannels: Int = 1024,
        val speakerCount: Int = 1,
        val ginChannels: Int = 256,
    )

    /**
     * Initialize the RVC engine with model files in [modelDir]
     * and given architecture [config].
     * Expected files: hubert.mnn, rmvpe.mnn, text_encoder.mnn, flow.mnn, generator.mnn
     */
    fun init(modelDir: File, config: Config = Config()): Boolean {
        return nativeInit(
            modelDir.absolutePath,
            config.version,
            config.ifF0,
            config.featDim,
            config.targetSr,
            config.interChannels,
            config.speakerCount,
            config.ginChannels,
        )
    }

    /**
     * Run RVC inference on [inputAudio] (float array, 48000 Hz).
     * Returns converted audio as float array at model's target sample rate.
     */
    fun infer(
        inputAudio: FloatArray,
        f0UpKey: Int = 0,
        indexRate: Int = 0,
        protect: Float = 0.33f,
    ): FloatArray? {
        return nativeInfer(inputAudio, inputAudio.size, f0UpKey, indexRate, protect)
    }

    /** Reset internal pitch cache for real-time streaming. */
    fun reset() = nativeReset()

    /** Release native resources. */
    fun release() = nativeRelease()

    // --- JNI declarations ---

    private external fun nativeInit(
        modelDir: String,
        version: String,
        ifF0: Boolean,
        featDim: Int,
        targetSr: Int,
        interChannels: Int,
        speakerCount: Int,
        ginChannels: Int,
    ): Boolean

    private external fun nativeInfer(
        audio: FloatArray,
        audioLen: Int,
        f0UpKey: Int,
        indexRate: Int,
        protect: Float,
    ): FloatArray?

    private external fun nativeReset()
    private external fun nativeRelease()

    companion object {
        init {
            System.loadLibrary("voicechanger")
        }
    }
}
