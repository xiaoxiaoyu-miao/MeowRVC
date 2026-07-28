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
import java.util.concurrent.TimeUnit

class ReplicateCloudRvc(private val ctx: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private var tvStatus: MaterialTextView? = null
    private var selectedAudio: File? = null
    private var selectedPth: File? = null
    private var tvAudioInfo: MaterialTextView? = null
    private var tvModelInfo: MaterialTextView? = null
    private var etModelUrl: TextInputEditText? = null

    fun show(audioFile: File?) {
        selectedAudio = audioFile
        val scroll = android.widget.ScrollView(ctx)
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
        }

        // API Token
        container.addView(android.widget.TextView(ctx).apply { text = "Replicate API Token"; textSize = 16f })
        val etToken = TextInputEditText(ctx).apply { hint = "r8_... (https://replicate.com/account)" }
        container.addView(etToken)

        // Audio source
        container.addView(android.widget.TextView(ctx).apply { text = "音频来源"; textSize = 16f })
        tvAudioInfo = MaterialTextView(ctx).apply {
            text = if (audioFile?.exists() == true) "悬浮窗录音: ${audioFile.name}" else "暂无录音，请先使用悬浮窗录制"
            setTextSize(14f)
        }
        container.addView(tvAudioInfo)

        // PTH model upload
        container.addView(android.widget.TextView(ctx).apply { text = "上传自定义模型 (.pth)"; textSize = 16f })
        tvModelInfo = MaterialTextView(ctx).apply { text = "未选择"; setTextSize(14f) }
        container.addView(tvModelInfo)
        val btnPickPth = MaterialButton(ctx).apply {
            text = "选择 .pth 文件"
            setOnClickListener {
                // Will use ActivityResult contract - for now, open file picker
                Toast.makeText(ctx, "请将 .pth 文件放入 /sdcard/models/ 目录", Toast.LENGTH_LONG).show()
            }
        }
        container.addView(btnPickPth)

        // Or custom model URL
        container.addView(android.widget.TextView(ctx).apply { text = "或输入模型 ZIP 下载地址"; textSize = 14f })
        etModelUrl = TextInputEditText(ctx).apply { hint = "https://...model.zip" }
        container.addView(etModelUrl)

        // Pitch
        container.addView(android.widget.TextView(ctx).apply { text = "音高偏移"; textSize = 16f })
        val sliderPitch = Slider(ctx).apply { valueFrom = -24f; valueTo = 24f; value = 0f }
        container.addView(sliderPitch)

        // Status
        tvStatus = MaterialTextView(ctx).apply { text = "就绪" }
        container.addView(tvStatus)

        // Submit
        val btnSubmit = MaterialButton(ctx).apply {
            text = "开始转换"
            setOnClickListener {
                val token = etToken.text?.toString()?.trim() ?: ""
                if (token.isEmpty()) { Toast.makeText(ctx, "请输入 API Token", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                val audio = selectedAudio
                if (audio == null || !audio.exists()) { Toast.makeText(ctx, "请先录音生成音频文件", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                GlobalScope.launch(Dispatchers.IO) {
                    uploadAndRun(token, audio, selectedPth, etModelUrl?.text?.toString()?.trim() ?: "", sliderPitch.value.toInt())
                }
            }
        }
        container.addView(btnSubmit)

        scroll.addView(container)
        MaterialAlertDialogBuilder(ctx).setTitle("在线变声 (云端RVC)").setView(scroll).setNegativeButton("关闭", null).show()
    }

    private suspend fun uploadAndRun(token: String, audioFile: File, pthFile: File?, modelUrl: String, pitch: Int) {
        try {
            // Step 1: Upload audio
            withContext(Dispatchers.Main) { tvStatus?.text = "上传音频..." }
            val audioBytes = audioFile.readBytes()
            val b64 = java.util.Base64.getEncoder().encodeToString(audioBytes)
            val dataUri = "data:audio/wav;base64,$b64"

            // Step 2: Upload PTH model if selected
            var finalModelUrl = modelUrl
            if (pthFile != null && modelUrl.isEmpty()) {
                withContext(Dispatchers.Main) { tvStatus?.text = "上传模型..." }
                finalModelUrl = uploadPth(pthFile)
                if (finalModelUrl.isEmpty()) {
                    withContext(Dispatchers.Main) { tvStatus?.text = "模型上传失败" }
                    return
                }
            }

            // Step 3: Submit to Replicate
            withContext(Dispatchers.Main) { tvStatus?.text = "提交任务..." }
            val modelVer = "5598e8029cbd7e9268db84ce8c2a334eab6ebccbee67b78cf63c38e964379e15"
            val input = JSONObject().apply {
                put("song_input", dataUri)
                if (finalModelUrl.isNotEmpty()) put("rvc_model", finalModelUrl)
                put("pitch", pitch)
                put("index_rate", 0.3)
                put("protect_rate", 0.33)
            }
            val body = JSONObject().apply {
                put("version", modelVer)
                put("input", input)
            }

            val req = Request.Builder()
                .url("https://api.replicate.com/v1/predictions")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $token")
                .build()

            val resp = client.newCall(req).execute()
            val json = JSONObject(resp.body?.string() ?: "{}")
            val getUrl = json.optJSONObject("urls")?.optString("get", "") ?: ""

            if (getUrl.isEmpty()) {
                val detail = json.optString("detail", json.optString("error", "未知错误"))
                withContext(Dispatchers.Main) { tvStatus?.text = "提交失败: $detail" }
                return
            }

            // Step 4: Poll
            withContext(Dispatchers.Main) { tvStatus?.text = "处理中（约30-120秒）..." }
            var resultUrl = ""
            for (i in 0..60) {
                kotlinx.coroutines.delay(3000)
                val pollReq = Request.Builder().url(getUrl).addHeader("Authorization", "Bearer $token").build()
                val pollResp = client.newCall(pollReq).execute()
                val pollJson = JSONObject(pollResp.body?.string() ?: "{}")
                when (pollJson.optString("status", "")) {
                    "succeeded" -> { resultUrl = pollJson.optString("output", ""); break }
                    "failed" -> { withContext(Dispatchers.Main) { tvStatus?.text = "转换失败: ${pollJson.optString("error", "")}" }; return }
                    else -> { withContext(Dispatchers.Main) { tvStatus?.text = "处理中 ${i+1}/60..." } }
                }
            }

            // Step 5: Download result
            if (resultUrl.isNotEmpty()) {
                withContext(Dispatchers.Main) { tvStatus?.text = "下载结果中..." }
                val dlResp = client.newCall(Request.Builder().url(resultUrl).build()).execute()
                val outBytes = dlResp.body?.bytes() ?: return
                val outFile = File(ctx.cacheDir, "cloud_rvc_${System.currentTimeMillis()}.wav")
                outFile.writeBytes(outBytes)
                withContext(Dispatchers.Main) {
                    tvStatus?.text = "已保存: ${outFile.absolutePath}"
                    Toast.makeText(ctx, "云端变声完成，文件已保存", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { tvStatus?.text = "错误: ${e.message}" }
        }
    }

    private fun uploadPth(file: File): String {
        // Upload to temp.sh (free file hosting, no auth)
        try {
            val zipFile = File(ctx.cacheDir, "model_upload.zip")
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry(file.name))
                file.inputStream().copyTo(zos)
                zos.closeEntry()
            }

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "model.zip", zipFile.readBytes().toRequestBody("application/zip".toMediaType()))
                .build()
            val req = Request.Builder().url("https://temp.sh/upload").post(body).build()
            val resp = client.newCall(req).execute()
            val url = resp.body?.string()?.trim() ?: ""
            zipFile.delete()
            return url
        } catch (e: Exception) {
            return ""
        }
    }
}
