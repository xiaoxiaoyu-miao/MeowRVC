package io.github.neboyang.voicechanger.demo

import android.app.Dialog
import android.content.Context
import android.widget.Toast
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class ReplicateCloudRvc(private val ctx: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var dialog: Dialog? = null
    private var tvStatus: MaterialTextView? = null

    fun show(audioFile: File? = null) {
        val scroll = android.widget.ScrollView(ctx)
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
        }

        container.addView(android.widget.TextView(ctx).apply { text = "Replicate API Token" })
        val etToken = TextInputEditText(ctx).apply { hint = "r8_..." }
        container.addView(etToken)

        container.addView(android.widget.TextView(ctx).apply { text = "模型" })
        val models = arrayOf("ultimate_rvc（完整功能）", "datong-new/rvc（简洁）", "pseudoram/rvc-v2（v2专用）")
        val modelVersions = arrayOf(
            "5598e8029cbd7e9268db84ce8c2a334eab6ebccbee67b78cf63c38e964379e15",
            "5da9f66869beacc8f2484215e25c88053acfe24044d64d4b26bbc40f7b5428dc",
            "96cea431b65d48f73b2c9ee54368dcf68d1ae3c933b7b9a85f23e84f22043d93"
        )
        var selectedModel = 0
        container.addView(com.google.android.material.chip.ChipGroup(ctx).apply {
            models.forEachIndexed { i, label ->
                addView(com.google.android.material.chip.Chip(ctx).apply {
                    text = label; isCheckable = true
                    if (i == 0) isChecked = true
                    setOnClickListener { selectedModel = i }
                })
            }
        })

        container.addView(android.widget.TextView(ctx).apply { text = "模型 ZIP 下载地址（可选）" })
        val etModelUrl = TextInputEditText(ctx).apply { hint = "https://...model.zip" }
        container.addView(etModelUrl)

        container.addView(android.widget.TextView(ctx).apply { text = "音高偏移" })
        val sliderPitch = Slider(ctx).apply { valueFrom = -24f; valueTo = 24f; value = 0f }
        container.addView(sliderPitch)

        tvStatus = MaterialTextView(ctx).apply { text = "" }
        container.addView(tvStatus)

        val btnSubmit = MaterialButton(ctx).apply {
            text = "开始转换"
            setOnClickListener {
                val token = etToken.text?.toString()?.trim() ?: ""
                if (token.isEmpty()) { Toast.makeText(ctx, "请输入 API Token", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                if (audioFile == null || !audioFile.exists()) { Toast.makeText(ctx, "请先录音生成音频文件", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                GlobalScope.launch(Dispatchers.IO) {
                    runCvc(token, modelVersions[selectedModel], etModelUrl.text?.toString()?.trim() ?: "", audioFile, sliderPitch.value.toInt())
                }
            }
        }
        container.addView(btnSubmit)
        scroll.addView(container)

        dialog = MaterialAlertDialogBuilder(ctx).setTitle("在线变声 (云端RVC)").setView(scroll).setNegativeButton("关闭", null).show()
    }

    private suspend fun runCvc(token: String, modelVer: String, modelUrl: String, audioFile: File, pitch: Int) {
        withContext(Dispatchers.Main) { tvStatus?.text = "上传音频并提交任务..." }
        try {
            // Read audio as base64
            val audioBytes = audioFile.readBytes()
            val b64 = java.util.Base64.getEncoder().encodeToString(audioBytes)
            val dataUri = "data:audio/wav;base64,$b64"

            val input = JSONObject().apply {
                put("song_input", dataUri)
                if (modelUrl.isNotEmpty()) put("rvc_model", modelUrl)
                put("pitch", pitch)
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
            val status = json.optString("status", "")
            val getUrl = json.optJSONObject("urls")?.optString("get", "") ?: ""

            if (getUrl.isEmpty()) {
                withContext(Dispatchers.Main) { tvStatus?.text = "提交失败: ${json.optString("detail", "未知错误")}" }
                return
            }

            // Poll for result
            withContext(Dispatchers.Main) { tvStatus?.text = "处理中（约30-120秒）..." }
            var resultUrl = ""
            for (i in 0..60) {
                kotlinx.coroutines.delay(3000)
                val pollReq = Request.Builder().url(getUrl).addHeader("Authorization", "Bearer $token").build()
                val pollResp = client.newCall(pollReq).execute()
                val pollJson = JSONObject(pollResp.body?.string() ?: "{}")
                val s = pollJson.optString("status", "")
                when (s) {
                    "succeeded" -> { resultUrl = pollJson.optString("output", ""); break }
                    "failed" -> { withContext(Dispatchers.Main) { tvStatus?.text = "转换失败" }; return }
                    else -> { withContext(Dispatchers.Main) { tvStatus?.text = "处理中 ${i+1}/60..." } }
                }
            }

            if (resultUrl.isNotEmpty()) {
                // Download result
                withContext(Dispatchers.Main) { tvStatus?.text = "下载结果中..." }
                val dlReq = Request.Builder().url(resultUrl).build()
                val dlResp = client.newCall(dlReq).execute()
                val outBytes = dlResp.body?.bytes() ?: return
                val outFile = File(ctx.cacheDir, "cloud_rvc_${System.currentTimeMillis()}.wav")
                outFile.writeBytes(outBytes)
                withContext(Dispatchers.Main) {
                    tvStatus?.text = "已保存: ${outFile.absolutePath}"
                    Toast.makeText(ctx, "云端变声完成", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { tvStatus?.text = "错误: ${e.message}" }
        }
    }
}
