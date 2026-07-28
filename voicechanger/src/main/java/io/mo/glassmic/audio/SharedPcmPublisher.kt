package io.mo.glassmic.audio

import android.os.ParcelFileDescriptor
import io.mo.glassmic.core.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class SharedPcmPublisher {
    private data class Consumer(
        val sampleRate: Int,
        val channels: Int,
        val fd: ParcelFileDescriptor,
        val out: FileOutputStream,
        val converter: Pcm16Converter
    )

    private val consumers = ConcurrentHashMap<String, Consumer>()
    @Volatile private var currentSource: AudioSourceProvider = SilenceSource
    @Volatile private var writerStarted = false

    private val scope = GlobalScope

    fun setSource(source: AudioSourceProvider) {
        currentSource.release()
        currentSource = source
    }

    fun attachConsumer(sampleRate: Int, channels: Int, writeFd: ParcelFileDescriptor) {
        val id = "consumer-${System.identityHashCode(writeFd)}"
        val fos = FileOutputStream(writeFd.fileDescriptor)
        val safeSr = sampleRate.coerceAtLeast(8000)
        val safeCh = channels.coerceAtLeast(1)
        val converter = Pcm16Converter(
            sourceSampleRate = 48000,
            sourceChannels = 1,
            targetSampleRate = safeSr,
            targetChannels = safeCh
        )
        consumers[id] = Consumer(safeSr, safeCh, writeFd, fos, converter)
        startWriter()
    }

    private fun detach(id: String) {
        consumers.remove(id)?.let {
            kotlin.runCatching { it.out.close() }
            kotlin.runCatching { it.fd.close() }
        }
    }

    private fun startWriter() {
        if (writerStarted) return
        synchronized(this) {
            if (writerStarted) return
            writerStarted = true
        }
        scope.launch(Dispatchers.IO) {
            val frame = ByteBuffer.allocate(8192)
            var nextSendAt = System.currentTimeMillis()
            while (isActive) {
                if (consumers.isEmpty()) {
                    delay(50)
                    nextSendAt = System.currentTimeMillis()
                    continue
                }
                frame.clear()
                val n = kotlin.runCatching {
                    currentSource.read(frame, 48000, 1)
                }.getOrDefault(0)
                if (n <= 0) {
                    delay(10)
                    continue
                }
                frame.flip()
                val data = ByteArray(frame.remaining())
                frame.get(data)
                for ((id, consumer) in consumers) {
                    try {
                        val converted = consumer.converter.convert(data)
                        if (converted.isNotEmpty()) {
                            consumer.out.write(converted)
                            consumer.out.flush()
                        }
                    } catch (e: Exception) {
                        detach(id)
                    }
                }
                val frameMs = (n.toLong() * 1000L + 96000 - 1) / 96000
                nextSendAt += frameMs
                val now = System.currentTimeMillis()
                val sleep = nextSendAt - now
                if (sleep > 0) delay(sleep)
                else if (sleep < -200) nextSendAt = now
            }
        }
    }
}
