package io.github.neboyang.voicechanger

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * 裸 PCM → AAC(M4A) 编码器，基于公开 API MediaCodec + MediaMuxer。
 *
 * 替代 1.x 版本使用的 android.media.AmrInputStream——那是一个 @hide API，
 * 普通工程无法编译，且已在 Android 9 (API 28) 中被移除。
 */
internal object AacEncoder {

    private const val TIMEOUT_US = 10_000L
    private const val PCM_CHUNK_BYTES = 16 * 1024

    fun encode(
        pcmFile: File,
        outFile: File,
        config: AudioConfig,
        bitRate: Int = 96_000,
        onProgress: ((Float) -> Unit)? = null,
        ensureActive: () -> Unit = {},
    ) {
        require(pcmFile.length() % config.bytesPerFrame == 0L) { "PCM 文件末尾不是完整音频帧" }
        outFile.parentFile?.mkdirs()
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val format = MediaFormat.createAudioFormat(mime, config.sampleRate, config.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_CHUNK_BYTES)
        }

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var codecStarted = false
        var trackIndex = -1
        var muxerStarted = false
        var completed = false
        val bufferInfo = MediaCodec.BufferInfo()
        val totalBytes = pcmFile.length().coerceAtLeast(1)
        var readBytes = 0L
        var totalFrames = 0L
        var inputDone = false

        try {
            val activeCodec = MediaCodec.createEncoderByType(mime).also { codec = it }
            activeCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val activeMuxer = MediaMuxer(
                outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            activeCodec.start()
            codecStarted = true

            pcmFile.inputStream().buffered().use { input ->
                val chunk = ByteArray(PCM_CHUNK_BYTES)
                while (true) {
                    ensureActive()
                    if (!inputDone) {
                        val inIndex = activeCodec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inBuf = checkNotNull(activeCodec.getInputBuffer(inIndex))
                            inBuf.clear()
                            val capacity = minOf(chunk.size, inBuf.remaining())
                            val maxRead = capacity - capacity % config.bytesPerFrame
                            check(maxRead > 0) { "AAC 编码器输入缓冲区小于一个 PCM 帧" }
                            val n = readChunk(input, chunk, maxRead)
                            val ptsUs = totalFrames * 1_000_000L / config.sampleRate
                            if (n < 0) {
                                activeCodec.queueInputBuffer(
                                    inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                check(n % config.bytesPerFrame == 0) { "PCM 文件末尾不是完整音频帧" }
                                inBuf.put(chunk, 0, n)
                                activeCodec.queueInputBuffer(inIndex, 0, n, ptsUs, 0)
                                totalFrames += n / config.bytesPerFrame
                                readBytes += n
                                onProgress?.invoke((readBytes.toFloat() / totalBytes).coerceAtMost(1f))
                            }
                        }
                    }

                    val outIndex = activeCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!muxerStarted) { "输出格式变化了多次" }
                        trackIndex = activeMuxer.addTrack(activeCodec.outputFormat)
                        activeMuxer.start()
                        muxerStarted = true
                    } else if (outIndex >= 0) {
                        val outBuf = checkNotNull(activeCodec.getOutputBuffer(outIndex))
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0 // CSD 已包含在 outputFormat 中
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            activeMuxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                        }
                        activeCodec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            completed = true
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (!completed) outFile.delete()
        }
    }

    /** 文件流读取到缓冲区满或 EOF，避免短读取破坏 PCM 帧边界。 */
    private fun readChunk(input: java.io.InputStream, buffer: ByteArray, length: Int): Int {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) return if (offset == 0) -1 else offset
            if (read == 0) continue
            offset += read
        }
        return offset
    }
}
