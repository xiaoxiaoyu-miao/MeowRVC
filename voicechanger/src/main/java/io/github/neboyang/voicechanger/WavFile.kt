package io.github.neboyang.voicechanger

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** WAV 数据段及音频参数。 */
data class WavInfo(
    val config: AudioConfig,
    val dataOffset: Long,
    val dataLength: Long,
)

/** RIFF/PCM WAV 的读写工具。 */
object WavFile {

    /** 本库生成的标准 PCM WAV 头长度。输入 WAV 不要求固定为该长度。 */
    const val HEADER_SIZE = 44

    /** 生成 44 字节 WAV 头。[dataLength] 为 PCM 数据段的字节数。 */
    fun header(dataLength: Int, config: AudioConfig): ByteArray {
        require(dataLength >= 0 && dataLength % config.bytesPerFrame == 0) {
            "PCM 数据长度必须按帧对齐: $dataLength"
        }
        val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataLength)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(config.channels.toShort())
        buf.putInt(config.sampleRate)
        buf.putInt(config.bytesPerSecond)
        buf.putShort(config.bytesPerFrame.toShort())
        buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataLength)
        return buf.array()
    }

    /** 将裸 PCM 文件包装为 WAV 文件。 */
    fun pcmToWav(pcm: File, wav: File, config: AudioConfig) {
        val dataLength = pcm.length()
        require(dataLength <= Int.MAX_VALUE - 36) { "PCM 文件过大: $dataLength 字节" }
        require(dataLength % config.bytesPerFrame == 0L) { "PCM 文件末尾不是完整音频帧" }
        wav.parentFile?.mkdirs()
        wav.outputStream().buffered().use { out ->
            out.write(header(dataLength.toInt(), config))
            pcm.inputStream().buffered().use { it.copyTo(out) }
        }
    }

    /** 判断文件是否具有 RIFF/WAVE 魔数。更严格的格式校验请使用 [parse]。 */
    fun isWav(file: File): Boolean {
        if (file.length() < 12) return false
        val head = ByteArray(12)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < head.size) {
                val read = input.read(head, offset, head.size - offset)
                if (read < 0) return false
                offset += read
            }
        }
        return String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(head, 8, 4, Charsets.US_ASCII) == "WAVE"
    }

    /**
     * 解析 PCM WAV 的 chunk，支持 `LIST`、`JUNK`、扩展 `fmt ` 等非固定 44 字节头。
     * 当前处理链只接受 16-bit PCM、单/双声道。
     */
    fun parse(file: File): WavInfo {
        require(isWav(file)) { "不是有效的 RIFF/WAVE 文件: $file" }
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(12)
            var config: AudioConfig? = null
            var dataOffset = -1L
            var dataLength = -1L

            while (raf.filePointer + 8 <= raf.length()) {
                val idBytes = ByteArray(4)
                raf.readFully(idBytes)
                val chunkId = String(idBytes, Charsets.US_ASCII)
                val chunkSize = readUInt32Le(raf)
                val chunkStart = raf.filePointer
                val chunkEnd = chunkStart + chunkSize
                require(chunkEnd >= chunkStart && chunkEnd <= raf.length()) {
                    "WAV chunk 越界: $chunkId, size=$chunkSize"
                }

                when (chunkId) {
                    "fmt " -> {
                        require(chunkSize >= 16) { "WAV fmt chunk 过短: $chunkSize" }
                        val audioFormat = readUInt16Le(raf)
                        val channels = readUInt16Le(raf)
                        val sampleRate = readUInt32Le(raf)
                        val byteRate = readUInt32Le(raf)
                        val blockAlign = readUInt16Le(raf)
                        val bitsPerSample = readUInt16Le(raf)
                        require(audioFormat == 1) { "仅支持 PCM WAV，format=$audioFormat" }
                        require(bitsPerSample == 16) { "仅支持 16-bit WAV，实际为 $bitsPerSample-bit" }
                        require(sampleRate <= Int.MAX_VALUE) { "WAV 采样率越界: $sampleRate" }
                        val parsed = AudioConfig(sampleRate.toInt(), channels)
                        require(blockAlign == parsed.bytesPerFrame) { "WAV blockAlign 不一致: $blockAlign" }
                        require(byteRate == parsed.bytesPerSecond.toLong()) { "WAV byteRate 不一致: $byteRate" }
                        config = parsed
                    }
                    "data" -> {
                        dataOffset = chunkStart
                        dataLength = chunkSize
                    }
                }

                if (config != null && dataOffset >= 0) break
                raf.seek(chunkEnd + (chunkSize and 1L))
            }

            val parsedConfig = requireNotNull(config) { "WAV 缺少 fmt chunk" }
            require(dataOffset >= 0) { "WAV 缺少 data chunk" }
            require(dataLength % parsedConfig.bytesPerFrame == 0L) { "WAV data 长度不是完整音频帧" }
            return WavInfo(parsedConfig, dataOffset, dataLength)
        }
    }

    /**
     * Read WAV file as float array (normalized to [-1, 1]).
     * Returns (samples, sampleRate).
     */
    fun read(file: File): Pair<FloatArray, Int> {
        val info = parse(file)
        val config = info.config
        val frameCount = info.dataLength / config.bytesPerFrame
        val sampleCount = frameCount * config.channels
        val data = ShortArray(sampleCount.toInt())

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(info.dataOffset)
            val bytes = ByteArray(info.dataLength.toInt())
            raf.readFully(bytes)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.asShortBuffer().get(data)
        }

        // Convert to mono float [-1, 1]
        val mono = FloatArray(frameCount.toInt())
        if (config.channels == 1) {
            for (i in mono.indices) mono[i] = data[i] / 32768f
        } else {
            for (i in mono.indices) {
                mono[i] = (data[i * 2] + data[i * 2 + 1]) / 65536f
            }
        }
        return Pair(mono, config.sampleRate)
    }

    /**
     * Write float array as 16-bit mono WAV.
     */
    fun write(file: File, samples: FloatArray, sampleRate: Int) {
        val shortData = ShortArray(samples.size)
        for (i in samples.indices) {
            val s = (samples[i] * 32768f).toInt()
            shortData[i] = s.coerceIn(-32768, 32767).toShort()
        }

        val pcmSize = shortData.size * 2
        val headerBytes = header(pcmSize, AudioConfig(sampleRate, 1))

        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { out ->
            out.write(headerBytes)
            val buf = ByteBuffer.allocate(pcmSize).order(ByteOrder.LITTLE_ENDIAN)
            buf.asShortBuffer().put(shortData)
            out.write(buf.array())
        }
    }

    private fun readUInt16Le(raf: RandomAccessFile): Int = try {
        java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xffff
    } catch (e: EOFException) {
        throw IllegalArgumentException("WAV 文件意外结束", e)
    }

    private fun readUInt32Le(raf: RandomAccessFile): Long = try {
        Integer.reverseBytes(raf.readInt()).toLong() and 0xffff_ffffL
    } catch (e: EOFException) {
        throw IllegalArgumentException("WAV 文件意外结束", e)
    }
}
