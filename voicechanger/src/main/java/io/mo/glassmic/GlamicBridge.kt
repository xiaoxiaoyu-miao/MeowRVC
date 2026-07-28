package io.mo.glassmic

import android.os.ParcelFileDescriptor
import io.github.neboyang.voicechanger.RVCOnnxEngine
import io.mo.glassmic.audio.RvcAudioSource
import io.mo.glassmic.audio.SharedPcmPublisher
import io.mo.glassmic.audio.SilenceSource

object GlamicBridge {
    private val publisher = SharedPcmPublisher()
    private var source: RvcAudioSource? = null

    fun attachConsumer(sampleRate: Int, channels: Int, writeFd: ParcelFileDescriptor) {
        publisher.attachConsumer(sampleRate, channels, writeFd)
    }

    fun start(engine: RVCOnnxEngine?, f0UpKey: Int = 0) {
        source?.stop()
        source = RvcAudioSource(engine, f0UpKey).also {
            it.start()
            publisher.setSource(it)
        }
    }

    fun stop() {
        source?.stop()
        source = null
        publisher.setSource(SilenceSource)
    }
}
