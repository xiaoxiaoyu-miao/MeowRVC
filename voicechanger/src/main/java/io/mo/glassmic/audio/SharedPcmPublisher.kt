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
        val out: FileOutputStream
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
        consumers[id] = Consumer(sampleRate, channels, writeFd, fos)
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
            val frame = ByteBuffer.allocate(4096)
            while (isActive) {
                if (consumers.isEmpty()) {
                    delay(50)
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
                        consumer.out.write(data)
                        consumer.out.flush()
                    } catch (e: Exception) {
                        detach(id)
                    }
                }
                delay((n * 1000L) / (48000 * 2)) // pace at real-time rate
            }
        }
    }
}
