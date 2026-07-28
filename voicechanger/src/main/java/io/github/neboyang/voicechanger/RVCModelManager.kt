package io.github.neboyang.voicechanger

import android.app.DownloadManager
import android.app.DownloadManager.Query
import android.content.Context
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class RVCModelManager(private val context: Context) {

    data class ModelInfo(
        val id: String, val name: String, val version: String = "v2",
        val ifF0: Boolean = true, val featDim: Int = 768, val targetSr: Int = 40000,
        val interChannels: Int = 1024, val speakerCount: Int = 1, val ginChannels: Int = 256,
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

    /** 使用 DownloadManager 下载基础模型（通知栏可见） */
    private suspend fun ensureBaseModels(modelDir: File): Boolean = withContext(Dispatchers.IO) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val urlGroups = listOf(
            "hubert.onnx" to listOf(
                "https://hf-mirror.com/spaces/14-26AA/sovits_aishell3/resolve/37fd30fd9498eb3ebd87a110f90fc447af6d8f45/hubert.onnx",
                "https://huggingface.co/spaces/14-26AA/sovits_aishell3/resolve/37fd30fd9498eb3ebd87a110f90fc447af6d8f45/hubert.onnx",
            ),
            "rmvpe.onnx" to listOf(
                "https://hf-mirror.com/lj1995/VoiceConversionWebUI/resolve/main/rmvpe.onnx",
                "https://huggingface.co/lj1995/VoiceConversionWebUI/resolve/main/rmvpe.onnx",
            )
        )
        for ((name, urls) in urlGroups) {
            val target = File(modelDir, name)
            if (target.exists() && target.length() > 100_000_000) continue
            _downloadStatus.value = "下载 $name..."
            val dest = File(context.getExternalFilesDir(null), "base_models/$name")
            dest.parentFile?.mkdirs()
            var success = false
            for (url in urls) {
                if (success) break
                try {
                    val req = DownloadManager.Request(Uri.parse(url))
                        .setTitle("MeowRVC - $name")
                        .setDescription("RVC 基础模型")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationUri(Uri.fromFile(dest))
                        .setAllowedOverMetered(true).setAllowedOverRoaming(true)
                    val id = dm.enqueue(req)
                    var prevPct = 0
                    while (true) {
                        var done = false; var failed = false
                        dm.query(Query().setFilterById(id)).use { c ->
                            if (c.moveToFirst()) {
                                val s = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS))
                                val dl = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                                val total = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                                val pct = if (total > 0) (dl * 100 / total).toInt() else 0
                                if (pct > prevPct) { prevPct = pct; _downloadProgress.value = dl.toFloat() / total.coerceAtLeast(1); _downloadStatus.value = "$name $pct%" }
                                if (s == DownloadManager.STATUS_SUCCESSFUL) { dest.copyTo(target, overwrite = true); dest.delete(); success = true; done = true }
                                if (s == DownloadManager.STATUS_FAILED) { failed = true }
                            }
                        }
                        if (done || failed) break
                        delay(1000)
                    }
                } catch (_: Exception) {}
            }
            if (!success) { _downloadStatus.value = "下载失败"; return@withContext false }
        }
        _downloadProgress.value = 1f; _downloadStatus.value = "就绪"
        true
    }

    /** 从文件夹导入：复制 ONNX → /sdcard/models/<name>/ + 下载基础模型 */
    suspend fun importFromFolder(folderPath: String): ModelInfo? = withContext(Dispatchers.IO) {
        val dir = File(folderPath)
        if (!dir.isDirectory) return@withContext null
        val onnxFiles = dir.listFiles { f -> f.extension == "onnx" && f.name !in setOf("hubert.onnx", "rmvpe.onnx") }
        if (onnxFiles.isNullOrEmpty()) return@withContext null

        // 取第一个 ONNX 文件名作为模型名
        val modelName = onnxFiles.first().nameWithoutExtension
        val targetDir = File(modelsDir, modelName)
        targetDir.mkdirs()

        // 复制 ONNX 文件到模型目录
        for (f in onnxFiles) {
            val dest = File(targetDir, f.name)
            if (!dest.exists()) f.copyTo(dest)
        }

        // 下载基础模型
        if (!ensureBaseModels(targetDir)) return@withContext null

        // config.json
        val cfgFile = File(targetDir, "config.json")
        if (!cfgFile.exists()) {
            cfgFile.writeText(org.json.JSONObject().apply {
                put("name", modelName); put("version", "v2"); put("f0", true)
                put("feat_dim", 768); put("target_sr", 40000)
            }.toString())
        }

        scanLocalModels()
        return@withContext ModelInfo(id = modelName, name = modelName)
    }

    suspend fun importModel(onnxPath: String): ModelInfo? = importFromFolder(File(onnxPath).parent)

    fun scanLocalModels() {
        if (!modelsDir.exists()) { _models.value = emptyList(); return }
        _models.value = modelsDir.listFiles()?.filter { it.isDirectory && File(it, "config.json").exists() }?.mapNotNull { dir ->
            try {
                val cfg = org.json.JSONObject(File(dir, "config.json").readText())
                ModelInfo(id = dir.name, name = cfg.optString("name", dir.name))
            } catch (_: Exception) { null }
        } ?: emptyList()
    }

    fun deleteModel(modelId: String) { getModelDir(modelId).deleteRecursively(); scanLocalModels() }
}
