package io.github.neboyang.voicechanger

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream

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

    fun getModelDir(modelId: String): File = File(modelsDir, modelId)

    fun isModelDownloaded(modelId: String): Boolean = File(modelsDir, modelId).exists()

    /** 从 assets 复制基础模型 + 用户选择的 ONNX 到模型目录，生成 config.json */
    fun importModel(onnxPath: String): ModelInfo? {
        val onnxFile = File(onnxPath)
        val modelName = onnxFile.nameWithoutExtension
        val dir = File(modelsDir, modelName)
        dir.mkdirs()

        // 复制基础模型（assets 中提取）
        try {
            for (base in listOf("hubert.onnx", "rmvpe.onnx")) {
                val target = File(dir, base)
                if (!target.exists()) {
                    context.assets.open("models/$base").use { input ->
                        FileOutputStream(target).use { input.copyTo(it) }
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }

        // 复制用户选择的 ONNX
        val targetOnnx = File(dir, onnxFile.name)
        if (!targetOnnx.exists()) onnxFile.copyTo(targetOnnx, overwrite = true)

        // 生成 config.json
        val cfg = org.json.JSONObject().apply {
            put("name", modelName)
            put("version", "v2")
            put("f0", true)
            put("feat_dim", 768)
            put("target_sr", 40000)
        }
        File(dir, "config.json").writeText(cfg.toString())

        val info = ModelInfo(id = modelName, name = modelName)
        // 刷新列表
        scanLocalModels()
        return info
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
