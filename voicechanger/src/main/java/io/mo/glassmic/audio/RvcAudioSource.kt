package io.mo.glassmic.audio

import io.mo.glassmic.core.model.SourceType
import java.nio.ByteBuffer

class RvcAudioSource : AudioSourceProvider {
    override val type = SourceType.FILE

    override suspend fun read(out: ByteBuffer, sampleRate: Int, channels: Int): Int {
        // TODO: record mic → RVC → write PCM
        // For now, return silence
        val n = out.remaining()
        out.put(ByteArray(n))
        return n
    }

    override fun release() {}
}
