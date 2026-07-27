package io.github.neboyang.voicechanger

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File
import java.io.IOException

/**
 * 简单的音频文件播放器（MediaPlayer 封装），支持 WAV / M4A 等系统支持的格式。
 * 请在主线程使用；页面销毁时调用 [release]。
 */
class VoicePlayer {

    private var player: MediaPlayer? = null

    /** 正在播放的文件，未播放时为 null。 */
    var currentFile: File? = null
        private set

    /** 播放完成回调（自然播完才触发，主动 stop 不触发）。 */
    var onCompletion: (() -> Unit)? = null

    val isPlaying: Boolean
        get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    /**
     * 播放文件。若正在播放会先停止上一个。
     * @throws IOException 文件无法打开或格式不支持
     */
    @Throws(IOException::class)
    fun play(file: File) {
        stop()
        require(file.isFile && file.length() > 0) { "音频文件不存在或为空: $file" }
        val created = MediaPlayer()
        try {
            created.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stop()
                    onCompletion?.invoke()
                }
                setOnErrorListener { _, _, _ ->
                    stop()
                    true
                }
                prepare()
                start()
            }
            player = created
            currentFile = file
        } catch (t: Throwable) {
            runCatching { created.release() }
            throw t
        }
    }

    /** 停止播放并释放当前 MediaPlayer。可重复调用。 */
    fun stop() {
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        player = null
        currentFile = null
    }

    /** 当前播放进度（毫秒）。 */
    fun currentPosition(): Int =
        runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    /** 释放资源，等价于 [stop]。 */
    fun release() = stop()
}
