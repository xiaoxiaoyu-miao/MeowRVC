package io.github.neboyang.voicechanger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * 离线变声处理管线：PCM/WAV 输入 → SoundTouch 变声 → WAV 或 AAC(M4A) 输出。
 *
 * 全程流式处理，内存占用与音频长度无关；支持协程取消。
 * 需要处理任意流（网络、管道等）时用 [processStream]；
 * 需要更底层的逐块控制时直接用 [SoundTouch]。
 */
object VoiceProcessor {

    /** 单次送入 SoundTouch 的帧数。 */
    private const val CHUNK_FRAMES = 4096

    /**
     * 对 [input] 文件应用 [effect]，结果写入 [output] 文件。
     *
     * @param input  裸 PCM（16-bit LE）或 PCM WAV 文件（自动解析 chunk 与音频参数）
     * @param output 输出文件：`.wav` 输出 WAV，`.m4a`/`.mp4` 输出 AAC(MPEG-4)
     * @param config 裸 PCM 的音频参数；WAV 输入时使用文件内参数
     * @param onProgress 进度回调（0~1），在 IO 线程回调
     * @return [output]
     */
    suspend fun process(
        input: File,
        output: File,
        effect: VoiceEffect,
        config: AudioConfig = AudioConfig(),
        onProgress: ((Float) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        require(input.exists() && input.length() > 0) { "输入文件不存在或为空: $input" }
        val outputIsWav = output.extension.equals("wav", true)
        require(outputIsWav || output.extension.equals("m4a", true) || output.extension.equals("mp4", true)) {
            "不支持的输出扩展名 .${output.extension}，仅支持 .wav、.m4a、.mp4"
        }
        output.parentFile?.mkdirs()

        // 第一阶段：SoundTouch 变声，输出到临时 PCM 文件
        val processedPcm = File.createTempFile("st_", ".pcm", output.parentFile)
        try {
            val wavInfo = if (WavFile.isWav(input)) WavFile.parse(input) else null
            val effectiveConfig = wavInfo?.config ?: config
            val dataOffset = wavInfo?.dataOffset ?: 0L
            val totalBytes = (wavInfo?.dataLength ?: input.length()).coerceAtLeast(1)
            // 进度权重：WAV 封装很快（变声占 95%），AAC 编码较慢（变声占 60%）
            val stageWeight = if (outputIsWav) 0.95f else 0.6f

            input.inputStream().buffered().use { ins ->
                skipFully(ins, dataOffset)
                val pcmInput = if (wavInfo != null) LimitedInputStream(ins, wavInfo.dataLength) else ins
                processedPcm.outputStream().buffered().use { outs ->
                    pump(pcmInput, outs, effect, effectiveConfig) { bytesRead ->
                        onProgress?.invoke(bytesRead.toFloat() / totalBytes * stageWeight)
                    }
                }
            }

            // 第二阶段：封装容器
            coroutineContext.ensureActive()
            if (outputIsWav) {
                WavFile.pcmToWav(processedPcm, output, effectiveConfig)
                onProgress?.invoke(1f)
            } else {
                val context = coroutineContext
                AacEncoder.encode(processedPcm, output, effectiveConfig, onProgress = { p ->
                    onProgress?.invoke(0.6f + p * 0.4f)
                }, ensureActive = { context.ensureActive() })
            }
            output
        } finally {
            processedPcm.delete()
        }
    }

    /**
     * 流式变声：从 [input] 读入 16-bit LE PCM，变声后的裸 PCM 写入 [output]。
     * 适合来源/去向不是文件的场景（网络流、管道、Socket 等）。
     *
     * 输入必须是**裸 PCM**（不含 WAV 头）；调用方负责关闭两个流。
     *
     * @return 写出的字节数
     */
    suspend fun processStream(
        input: InputStream,
        output: OutputStream,
        effect: VoiceEffect,
        config: AudioConfig = AudioConfig(),
    ): Long = withContext(Dispatchers.IO) {
        pump(input, output, effect, config, onBytesRead = null)
    }

    /**
     * 核心泵：input(PCM) → SoundTouch → output(PCM)，含尾部 flush。
     * 支持协程取消；返回写出的字节数。
     */
    private suspend fun pump(
        input: InputStream,
        output: OutputStream,
        effect: VoiceEffect,
        config: AudioConfig,
        onBytesRead: ((Long) -> Unit)?,
    ): Long {
        var readBytes = 0L
        var writtenBytes = 0L

        SoundTouch(config.sampleRate, config.channels).use { st ->
            st.applyEffect(effect)

            val frameReader = PcmFrameReader(
                input, CHUNK_FRAMES * config.bytesPerFrame, config.bytesPerFrame)
            val inBytes = frameReader.buffer
            val inShorts = ShortArray(CHUNK_FRAMES * config.channels)
            val outShorts = ShortArray(CHUNK_FRAMES * 2 * config.channels)
            val outBytes = ByteBuffer.allocate(outShorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)

            fun drain() {
                while (true) {
                    val frames = st.receiveSamples(outShorts)
                    if (frames <= 0) break
                    val samples = frames * config.channels
                    outBytes.clear()
                    outBytes.asShortBuffer().put(outShorts, 0, samples)
                    output.write(outBytes.array(), 0, samples * 2)
                    writtenBytes += samples * 2L
                }
            }

            while (true) {
                coroutineContext.ensureActive()
                val usable = frameReader.read()
                if (usable < 0) break
                readBytes = frameReader.totalBytesRead
                onBytesRead?.invoke(readBytes)
                ByteBuffer.wrap(inBytes, 0, usable)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .get(inShorts, 0, usable / 2)
                st.putSamples(inShorts, usable / config.bytesPerFrame)
                drain()
            }

            // 冲出管线尾部残留（1.x 缺这一步，结尾会被截断）
            st.flush()
            drain()
        }
        output.flush()
        return writtenBytes
    }

    private fun skipFully(input: InputStream, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (input.read() >= 0) {
                remaining--
            } else {
                throw IOException("输入文件在数据段之前意外结束")
            }
        }
    }

    private class LimitedInputStream(input: InputStream, private var remaining: Long) :
        FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = super.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val allowed = minOf(length.toLong(), remaining).toInt()
            val read = super.read(buffer, offset, allowed)
            if (read > 0) remaining -= read
            return read
        }
    }
}
