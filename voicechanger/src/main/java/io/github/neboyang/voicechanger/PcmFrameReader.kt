package io.github.neboyang.voicechanger

import java.io.IOException
import java.io.InputStream

/** 将可能发生短读取的字节流整理为完整 PCM 帧块。 */
internal class PcmFrameReader(
    private val input: InputStream,
    bufferSize: Int,
    private val bytesPerFrame: Int,
) {
    init {
        require(bytesPerFrame > 0)
        require(bufferSize >= bytesPerFrame)
    }

    val buffer = ByteArray(bufferSize - bufferSize % bytesPerFrame)
    var totalBytesRead: Long = 0
        private set

    private val remainder = ByteArray(bytesPerFrame - 1)
    private var remainderSize = 0

    /** 返回完整帧字节数，EOF 返回 -1；EOF 存在残帧时抛出 [IOException]。 */
    fun read(): Int {
        if (remainderSize > 0) remainder.copyInto(buffer, endIndex = remainderSize)

        while (true) {
            val read = input.read(buffer, remainderSize, buffer.size - remainderSize)
            if (read < 0) {
                if (remainderSize != 0) {
                    throw IOException("PCM 输入末尾不是完整音频帧，残留 $remainderSize 字节")
                }
                return -1
            }
            if (read == 0) continue
            totalBytesRead += read

            val total = remainderSize + read
            val usable = total - total % bytesPerFrame
            val nextRemainderSize = total - usable
            if (nextRemainderSize > 0) {
                buffer.copyInto(remainder, startIndex = usable, endIndex = total)
            }
            remainderSize = nextRemainderSize
            if (usable > 0) return usable

            remainder.copyInto(buffer, endIndex = remainderSize)
        }
    }
}
