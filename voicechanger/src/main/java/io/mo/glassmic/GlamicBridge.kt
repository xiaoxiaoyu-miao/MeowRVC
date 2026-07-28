package io.mo.glassmic

import android.os.ParcelFileDescriptor
import io.mo.glassmic.audio.RvcAudioSource
import io.mo.glassmic.audio.SharedPcmPublisher
import io.mo.glassmic.audio.SilenceSource

object GlamicBridge {
    private val publisher = SharedPcmPublisher()

    fun attachConsumer(sampleRate: Int, channels: Int, writeFd: ParcelFileDescriptor) {
        publisher.attachConsumer(sampleRate, channels, writeFd)
    }

    fun startRvcSource() {
        publisher.setSource(RvcAudioSource())
    }

    fun stopRvcSource() {
        publisher.setSource(SilenceSource)
    }
}
