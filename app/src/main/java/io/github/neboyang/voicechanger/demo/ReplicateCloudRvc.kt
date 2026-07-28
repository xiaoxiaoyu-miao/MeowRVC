package io.github.neboyang.voicechanger.demo

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReplicateCloudRvc(private val ctx: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private var tvStatus: MaterialTextView? = null
    private var selectedAudio: File? = null
    private var etToken: TextInputEditText? = null
    private var etModelUrl: TextInputEditText? = null
    private var tvAudioInfo: MaterialTextView? = null
    private var tvModelInfo: MaterialTextView? = null
    private var modelCachePath: String? = null
    private var onPickPth: ((Uri) -> Unit)? = null
    private var settings: SettingsManager? = null

    fun show(audioFile: File?, pickerLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>) {
        selectedAudio = audioFile
        settings = SettingsManager(ctx)

        val scroll = android.widget.ScrollView(ctx)
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
        }

        // API Token
        container.addView(android.widget.TextView(ctx).apply { text = "Replicate API Token"; textSize = 16f })
        etToken = TextInputEditText(ctx).apply {
            hint = "r8_..."
            setText(settings?.getString("replicate_token", ""))
        }
        container.addView(etToken)

        // Audio source
        container.addView(android.widget.TextView(ctx).apply { text = "音频来源"; textSize = 16f })
        tvAudioInfo = MaterialTextView(ctx).apply {
            text = if (audioFile?.exists() == true) "悬浮窗录音: ${audioFile.name}" else "暂无录音，请先使用悬浮窗录制"
            setTextSize(14f)
        }
        container.addView(tvAudioInfo)

        // Model
        container.addView(android.widget.TextView(ctx).apply { text = "音色模型 (.pth)"; textSize = 16f })
        val cachedModel = settings?.getString("cached_model_name", "")
        tvModelInfo = MaterialTextView(ctx).apply {
            text = if (cachedModel?.isNotEmpty() == true) "已缓存: $cachedModel" else "未选择"
            setTextSize(14f)
        }
        container.addView(tvModelInfo)
        val btnPickPth = MaterialButton(ctx).apply {
            text = "选择 .pth 文件"
            setOnClickListener {
                pickerLauncher.launch(arrayOf("*/*"))
                onPickPth = { uri -> handleModelUri(uri) }
            }
        }
        container.addView(btnPickPth)

        // Clear cached model
        val btnClearModel = MaterialButton(ctx).apply {
            text = "清除缓存模型"
            setOnClickListener {
                modelCachePath = null
                settings?.save("cached_model_path", "")
                settings?.save("cached_model_name", "")
                tvModelInfo?.text = "未选择"
                File(ctx.cacheDir, "cloud_models").deleteRecursively()
                Toast.makeText(ctx, "已清除", Toast.LENGTH_SHORT).show()
            }
        }
        container.addView(btnClearModel)

        // Or URL
        container.addView(android.widget.TextView(ctx).apply { text = "或输入模型 ZIP 下载地址"; textSize = 14f })
        etModelUrl = TextInputEditText(ctx).apply { hint = "https://...model.zip" }
        container.addView(etModelUrl)

        // Pitch
        container.addView(android.widget.TextView(ctx).apply { text = "音高偏移"; textSize = 16f })
        val sliderPitch = Slider(ctx).apply { valueFrom = -24f; valueTo = 24f; value = 0f }
        container.addView(sliderPitch)

        tvStatus = MaterialTextView(ctx).apply { text = "就绪" }
        container.addView(tvStatus)

        val btnSubmit = MaterialButton(ctx).apply {
            text = "开始转换"
            setOnClickListener {
                val token = etToken?.text?.toString()?.trim() ?: ""
                if (token.isEmpty()) { Toast.makeText(ctx, "请输入 API Token", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                settings?.save("replicate_token", token)
                val audio = selectedAudio
                if (audio == null || !audio.exists()) { Toast.makeText(ctx, "请先录音", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                GlobalScope.launch(Dispatchers.IO) {
                    runCvc(token, audio, modelCachePath, etModelUrl?.text?.toString()?.trim() ?: "", sliderPitch.value.toInt())
                }
            }
        }
        container.addView(btnSubmit)

        scroll.addView(container)
        MaterialAlertDialogBuilder(ctx).setTitle("在线变声 (云端RVC)").setView(scroll).setNegativeButton("关闭", null).show()
    }

    fun handlePickedUri(uri: Uri) {
        onPickPth?.invoke(uri)
        onPickPth = null
    }

    private suspend fun runCvc(token: String, audioFile: File, cachedZip: String?, modelUrl: String, pitch: Int) {
        try {
            withContext(Dispatchers.Main) { tvStatus?.text = "上传音频..." }
            val audioB64 = java.util.Base64.getEncoder().encodeToString(audioFile.readBytes())

            var finalModelUrl = modelUrl
            if (finalModelUrl.isEmpty() && cachedZip != null) {
                val cachedFile = File(cachedZip)
                if (cachedFile.exists()) {
                    withContext(Dispatchers.Main) { tvStatus?.text = "上传缓存模型..." }
                    val uploadUrl = uploadToTempSh(cachedFile)
                    if (uploadUrl.isNotEmpty()) finalModelUrl = uploadUrl
                }
            }

            withContext(Dispatchers.Main) { tvStatus?.text = "提交 Replicate 任务..." }
            val input = JSONObject().apply {
                put("song_input", "data:audio/wav;base64,$audioB64")
                if (finalModelUrl.isNotEmpty()) put("rvc_model", finalModelUrl)
                put("pitch", pitch)
                put("index_rate", 0.3)
                put("protect_rate", 0.33)
            }
            val body = JSONObject().apply {
                put("version", "5598e8029cbd7e9268db84ce8c2a334eab6ebccbee67b78cf63c38e964379e15")
                put("input", input)
            }
            val req = Request.Builder()
                .url("https://api.replicate.com/v1/predictions")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $token").build()
            val resp = client.newCall(req).execute()
            val json = JSONObject(resp.body?.string() ?: "{}")
            val getUrl = json.optJSONObject("urls")?.optString("get", "") ?: ""
            if (getUrl.isEmpty()) {
                withContext(Dispatchers.Main) { tvStatus?.text = "提交失败: ${json.optString("detail", json.optString("error", "未知"))}" }
                return
            }

            withContext(Dispatchers.Main) { tvStatus?.text = "处理中..." }
            var resultUrl = ""
            for (i in 0..60) {
                kotlinx.coroutines.delay(3000)
                val pollReq = Request.Builder().url(getUrl).addHeader("Authorization", "Bearer $token").build()
                val pollResp = client.newCall(pollReq).execute()
                val pollJson = JSONObject(pollResp.body?.string() ?: "{}")
                when (pollJson.optString("status", "")) {
                    "succeeded" -> { resultUrl = pollJson.optString("output", ""); break }
                    "failed" -> { withContext(Dispatchers.Main) { tvStatus?.text = "失败" }; return }
                    else -> { withContext(Dispatchers.Main) { tvStatus?.text = "处理中 ${i+1}/60" } }
                }
            }

            if (resultUrl.isNotEmpty()) {
                withContext(Dispatchers.Main) { tvStatus?.text = "下载结果..." }
                val dlResp = client.newCall(Request.Builder().url(resultUrl).build()).execute()
                val outFile = File(ctx.cacheDir, "cloud_rvc_${System.currentTimeMillis()}.wav")
                outFile.writeBytes(dlResp.body?.bytes() ?: return)
                withContext(Dispatchers.Main) {
                    tvStatus?.text = "已保存"
                    Toast.makeText(ctx, "云端变声完成", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { tvStatus?.text = "错误: ${e.message}" }
        }
    }

    private fun uploadToTempSh(file: File): String {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.readBytes().toRequestBody("application/zip".toMediaType()))
                .build()
            val req = Request.Builder().url("https://temp.sh/upload").post(body).build()
            client.newCall(req).execute().body?.string()?.trim() ?: ""
        } catch (_: Exception) { "" }
    }

    private fun handleModelUri(uri: Uri) {
        val cachedDir = File(ctx.cacheDir, "cloud_models")
        cachedDir.mkdirs()
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return
            val fileName = "model_${System.currentTimeMillis()}.pth"
            val localFile = File(cachedDir, fileName)
            input.use { it.copyTo(FileOutputStream(localFile)) }
            val zipFile = File(cachedDir, "${fileName}.zip")
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                zos.putNextEntry(ZipEntry(fileName))
                localFile.inputStream().copyTo(zos)
                zos.closeEntry()
            }
            modelCachePath = zipFile.absolutePath
            settings?.save("cached_model_path", zipFile.absolutePath)
            settings?.save("cached_model_name", fileName.removeSuffix(".pth"))
            tvModelInfo?.text = "已缓存: ${fileName.removeSuffix(".pth")}"
            localFile.delete()
            Toast.makeText(ctx, "模型已缓存", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
