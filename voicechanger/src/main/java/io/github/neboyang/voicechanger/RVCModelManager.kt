package io.github.neboyang.voicechanger

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.URL

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

    private val _downloadStatus = MutableStateFlow("")
    val downloadStatus: StateFlow<String> = _downloadStatus

    fun getModelDir(modelId: String): File = File(modelsDir, modelId)
    fun isModelDownloaded(modelId: String): Boolean = File(modelsDir, modelId).exists()

    /** 下载基础模型到缓存目录（国内镜像）*/
    private suspend fun ensureBaseModels(): Boolean = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "base_models")
        cacheDir.mkdirs()
        val urls = mapOf(
            "hubert.onnx" to "https://hf-mirror.com/Sharl210/ultimate-rvc-mobile/resolve/main/hubert.onnx",
            "rmvpe.onnx" to "https://hf-mirror.com/Sharl210/ultimate-rvc-mobile/resolve/main/rmvpe.onnx"
        )
        for ((name, url) in urls) {
            val target = File(cacheDir, name)
            if (target.exists() && target.length() > 100_000_000) continue
            _downloadStatus.value = "下载 $name..."
            try {
                val conn = URL(url).openConnection()
                conn.connectTimeout = 30000
                conn.readTimeout = 120000
                val total = conn.contentLengthLong
                val input = conn.getInputStream()
                val output = FileOutputStream(target)
                val buf = ByteArray(8192)
                var read: Int; var downloaded = 0L
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    downloaded += read
                    if (total > 0) _downloadProgress.value = downloaded.toFloat() / total
                }
                output.close(); input.close()
            } catch (e: Exception) {
                _downloadStatus.value = "下载失败: ${e.message}"
                return@withContext false
            }
        }
        _downloadProgress.value = 1f
        _downloadStatus.value = "基础模型就绪"
        true
    }

    /** 导入用户 ONNX 模型：下载基础模型 + 复制用户文件 + 生成 config */
    suspend fun importModel(onnxPath: String): ModelInfo? = withContext(Dispatchers.IO) {
        val onnxFile = File(onnxPath)
        val modelName = onnxFile.nameWithoutExtension
        val dir = File(modelsDir, modelName)
        dir.mkdirs()

        // 1. 确保基础模型已下载
        val baseOk = ensureBaseModels()
        if (!baseOk) { return@withContext null }

        // 2. 复制基础模型到目标目录
        val cacheDir = File(context.cacheDir, "base_models")
        for (base in listOf("hubert.onnx", "rmvpe.onnx")) {
            val target = File(dir, base)
            if (!target.exists()) {
                File(cacheDir, base).copyTo(target, overwrite = true)
            }
        }

        // 3. 复制用户 ONNX
        val targetOnnx = File(dir, onnxFile.name)
        if (!targetOnnx.exists()) onnxFile.copyTo(targetOnnx, overwrite = true)

        // 4. 生成 config.json
        val cfg = org.json.JSONObject().apply {
            put("name", modelName)
            put("version", "v2")
            put("f0", true)
            put("feat_dim", 768)
            put("target_sr", 40000)
        }
        File(dir, "config.json").writeText(cfg.toString())

        val info = ModelInfo(id = modelName, name = modelName)
        scanLocalModels()
        return@withContext info
    }

    fun scanLocalModels() {
        if (!modelsDir.exists()) { _models.value = emptyList(); return }
        val list = modelsDir.listFiles()?.filter { it.isDirectory && File(it, "config.json").exists() }?.mapNotNull { dir ->
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

    fun deleteModel(modelId: String) {
        getModelDir(modelId).deleteRecursively()
        scanLocalModels()
    }
}
