package io.github.neboyang.voicechanger

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipFile

/**
 * Downloads and manages RVC model files.
 * Models are stored in context.filesDir/rvc_models/<modelId>/
 */
class RVCModelManager(private val context: Context) {

    data class ModelInfo(
        val id: String,
        val name: String,
        val version: String = "v2",
        val ifF0: Boolean = true,
        val featDim: Int = 768,
        val targetSr: Int = 40000,
        val interChannels: Int = 1024,
        val speakerCount: Int = 1,
        val ginChannels: Int = 256,
    )

    val modelsDir: File get() = File("/sdcard/models")

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress

    fun getModelDir(modelId: String): File = File(modelsDir, modelId)

    fun isModelDownloaded(modelId: String): Boolean {
        val dir = getModelDir(modelId)
        return dir.exists() && File(dir, "config.json").exists()
    }

    fun scanLocalModels() {
        if (!modelsDir.exists()) {
            _models.value = emptyList()
            return
        }
        val list = modelsDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val configFile = File(dir, "config.json")
            if (!configFile.exists()) return@mapNotNull null
            try {
                val config = org.json.JSONObject(configFile.readText())
                ModelInfo(
                    id = dir.name,
                    name = config.optString("name", dir.name),
                    version = config.optString("version", "v2"),
                    ifF0 = config.optBoolean("f0", true),
                    featDim = config.optInt("feat_dim", 768),
                    targetSr = config.optInt("target_sr", 40000),
                    interChannels = config.optInt("inter_channels", 1024),
                    speakerCount = config.optInt("speaker_count", 1),
                    ginChannels = config.optInt("gin_channels", 256),
                )
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()
        _models.value = list
    }

    /**
     * Download an RVC model pack from a URL.
     * The ZIP should contain: hubert.mnn, rmvpe.mnn, text_encoder.mnn, flow.mnn,
     * generator.mnn, config.json
     */
    suspend fun downloadModel(modelId: String, url: String) = withContext(Dispatchers.IO) {
        val dir = getModelDir(modelId)
        dir.mkdirs()

        val zipFile = File(dir, "model.zip")
        val urlConnection = URL(url).openConnection()
        val totalBytes = urlConnection.contentLengthLong
        val inputStream = urlConnection.getInputStream()
        val outputStream = FileOutputStream(zipFile)
        val buffer = ByteArray(8192)
        var bytesRead: Int
        var totalRead = 0L

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
            totalRead += bytesRead
            if (totalBytes > 0) {
                _downloadProgress.value = totalRead.toFloat() / totalBytes
            }
        }
        outputStream.close()
        inputStream.close()

        // Extract ZIP
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val targetFile = File(dir, entry.name)
                if (entry.isDirectory) {
                    targetFile.mkdirs()
                } else {
                    targetFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
        zipFile.delete()
        _downloadProgress.value = 1f
        scanLocalModels()
    }

    fun deleteModel(modelId: String) {
        getModelDir(modelId).deleteRecursively()
        scanLocalModels()
    }

    companion object {
        // Default model source URLs (community models)
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/lj1995/VoiceConversionWebUI/resolve/main/"
    }
}
