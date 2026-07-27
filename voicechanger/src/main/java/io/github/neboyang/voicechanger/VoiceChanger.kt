package io.github.neboyang.voicechanger

import android.Manifest
import android.content.Context
import android.os.Environment
import androidx.annotation.RequiresPermission
import java.io.File

/**
 * 门面类：一站式「录音 → 变声 → 播放」。
 *
 * ```kotlin
 * val changer = VoiceChanger(context)
 * changer.startRecording()
 * ...
 * val recording = changer.stopRecording()          // suspend
 * val out = changer.changeVoice(VoiceEffect.UNCLE) // suspend，输出 .m4a
 * changer.play(out)
 * ```
 *
 * - 录音中间文件存放在应用 cache 目录，变声结果默认输出到
 *   `getExternalFilesDir(Music)`（应用专属目录，**无需任何存储权限**，
 *   兼容 Android 10+ 分区存储）
 * - 需要更细的控制（自定义输出路径/格式/进度）请直接使用
 *   [VoiceRecorder]、[VoiceProcessor]、[VoicePlayer]
 */
class VoiceChanger(
    context: Context,
    val config: AudioConfig = AudioConfig(),
) {
    /** 底层录音器，可直接订阅其 state / amplitude。 */
    val recorder = VoiceRecorder(config)

    /** 底层播放器。 */
    val player = VoicePlayer()

    private val cacheDir = File(context.cacheDir, "voicechanger").apply { mkdirs() }
    private val outputDir =
        (context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir)
            .let { File(it, "voicechanger").apply { mkdirs() } }

    /** 最近一次录音的 PCM 文件。 */
    var lastRecording: File? = null
        private set

    /** 开始录音。 */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording() {
        recorder.start(File(cacheDir, "rec_${System.currentTimeMillis()}.pcm"))
    }

    fun pauseRecording() = recorder.pause()

    fun resumeRecording() = recorder.resume()

    /** 停止录音并返回结果。 */
    suspend fun stopRecording(): RecordingResult =
        recorder.stop().also { lastRecording = it.file }

    /**
     * 对最近一次录音应用变声效果。
     *
     * @param effect   变声参数，见 [VoiceEffect] 预设或自定义
     * @param fileName 输出文件名，`.wav` 结尾输出 WAV，否则输出 AAC(M4A)
     * @return 变声后的音频文件
     * @throws IllegalStateException 尚未录音
     */
    suspend fun changeVoice(
        effect: VoiceEffect,
        fileName: String = "voice_${System.currentTimeMillis()}.m4a",
        onProgress: ((Float) -> Unit)? = null,
    ): File {
        require(fileName.isNotBlank() && File(fileName).name == fileName) {
            "fileName 必须是不包含路径的文件名: $fileName"
        }
        val input = checkNotNull(lastRecording) { "还没有录音，请先 startRecording/stopRecording" }
        return VoiceProcessor.process(input, File(outputDir, fileName), effect, config, onProgress)
    }

    /** 播放文件（通常是 [changeVoice] 的返回值）。 */
    fun play(file: File, onCompletion: (() -> Unit)? = null) {
        player.onCompletion = onCompletion
        player.play(file)
    }

    fun stopPlaying() = player.stop()

    /** 释放资源并清理录音缓存。在页面/进程退出时调用。 */
    fun release() {
        val activeRecording = recorder.activeOutputFile
        recorder.cancel()
        player.release()
        cacheDir.listFiles()?.filterNot { it == activeRecording }?.forEach { it.delete() }
        lastRecording = null
    }
}
